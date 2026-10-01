package com.tczerwien.lifts.backup

import android.util.Log
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.session.QueuedOnce
import com.tczerwien.lifts.settings.SettingsRows
import com.tczerwien.lifts.settings.SettingsStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backups of the whole log and the settings, in [dir] (files/shared/backups, which Tool Manager
 * serves read-only). Each is one SQLite file: a VACUUM INTO copy of lifts.db plus a lifts_settings
 * table. It is written under a hidden temp name, checked with integrity_check, flushed to disk, and
 * only then renamed into place, so a listed backup is always a whole one. Backups run on the write
 * lane, so each holds every save made before it and no save lands halfway through one.
 */
class BackupManager(
    private val dir: File,
    private val repository: LiftsRepository,
    private val settings: SettingsStore,
) {
    data class Entry(val file: File, val kind: BackupPolicy.Kind, val sizeBytes: Long, val writtenAt: Long)

    sealed interface Status {
        data object Idle : Status

        data object Running : Status

        /** The latest backup failed; the older ones are still there. */
        data class Failed(val message: String) : Status
    }

    /** Back up now requests queued or running. */
    private val requested = AtomicInteger()

    /** Why the latest backup failed; null once one succeeds. Kept until then, and Home retries. */
    @Volatile
    private var lastFailure: String? = null

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _changes = MutableStateFlow(0L)

    /**
     * Bumped each time the folder changes (a backup written, old ones pruned), including the backups
     * a restore takes, which leave [status] as it was.
     */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** A dated backup already queued and not started: it will hold whatever a request made now would. */
    private val queued = QueuedOnce()

    /**
     * A dated backup, queued on the write lane: Back up now (a Finish's comes from [requestIfDue]). While
     * one waits its turn a second isn't queued (taps while a busy lane holds the first): the one waiting
     * reads the database when its turn comes, so it holds whatever the second would.
     */
    fun request() {
        if (!queued.tryQueue()) return
        requested.incrementAndGet()
        publish()
        LiftsIo.write({
            queued.started()
            runCatching { backUp(BackupPolicy.Kind.DATED) }
        }) {
            requested.decrementAndGet()
            publish()
        }
    }

    /** One Home check waiting at a time: Home shows often, and each check would read the folder again. */
    private val dueCheck = QueuedOnce()

    /**
     * Home: a dated backup if the newest is a day old ([BackupPolicy.isDue]), older than the last Finish
     * ([BackupPolicy.behindLog]: Finish leaves its backup to this check), or the latest attempt
     * failed, decided when its turn comes. It waits [HOME_CHECK_DELAY_MS] before it queues: queued as
     * Home shows, the first Start of the day waited behind the whole backup (VACUUM INTO, a check, two
     * copies flushed to disk), which grows with the log. A check that finds nothing due leaves the
     * status alone. Leaving Workout Complete makes the same check ([delayMs] after it, past the next
     * screen's load); Lifts going to the background there makes it at once ([delayMs] 0).
     */
    fun requestIfDue(delayMs: Long = HOME_CHECK_DELAY_MS) {
        if (!dueCheck.tryQueue()) return
        LiftsIo.scope.launch {
            delay(delayMs)
            LiftsIo.write({
                dueCheck.started()
                val now = System.currentTimeMillis()
                val last = lastBackupMillis(now)
                val lastEnd = repository.lastWorkoutEndedAt(until = BackupPolicy.countedUntil(now))
                if (lastFailure != null || BackupPolicy.isDue(last, now) || BackupPolicy.behindLog(last, lastEnd, now)) {
                    runCatching { backUp(BackupPolicy.Kind.DATED) }
                }
            }) { publish() }
        }
    }

    @Synchronized
    private fun publish() {
        val failure = lastFailure
        _status.value = when {
            requested.get() > 0 -> Status.Running
            failure != null -> Status.Failed(failure)
            else -> Status.Idle
        }
    }

    /**
     * Writes a backup of [kind] now and returns it; throws when it can't, leaving no partial file.
     * Call it on the write lane only (inside a [LiftsIo] block): it reads the database and settings
     * as one picture. A failure is kept for [status] until a backup succeeds.
     */
    fun backUp(kind: BackupPolicy.Kind, now: Long = System.currentTimeMillis(), keep: Set<String> = emptySet()): File = try {
        write(kind, now, keep).also { lastFailure = null }
    } catch (e: Exception) {
        Log.w(TAG, "Backup failed", e)
        lastFailure = e.message ?: e.toString()
        throw e
    } finally {
        publish()
    }

    /** [keep]: backup names pruning must leave alone this time (a restore's own source). */
    private fun write(kind: BackupPolicy.Kind, now: Long, keep: Set<String>): File {
        val started = System.nanoTime()
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.name}")
        val temp = File(dir, TEMP_NAME)
        var changed = false
        try {
            deleteWithSidecars(temp)
            repository.copyTo(temp.absolutePath)
            BackupFile.writeSettings(temp, SettingsRows.encode(settings.snapshot()))
            BackupFile.integrityProblem(temp)?.let { throw IOException("the copy failed its check: $it") }
            sync(temp)
            val target = File(dir, BackupPolicy.uniqueName(kind, now, dir.list().orEmpty().toList()))
            if (!temp.renameTo(target)) throw IOException("cannot name ${target.name}")
            changed = true
            if (kind == BackupPolicy.Kind.DATED) replaceLatest(target)
            prune(now, justWritten = target.name, keep = keep)
            Log.i(TAG, "Backup ${target.name}: ${target.length()} B in ${(System.nanoTime() - started) / 1_000_000} ms")
            return target
        } finally {
            deleteWithSidecars(temp)
            // A backup that landed shows in the list even if lifts-latest.db or pruning then failed.
            if (changed) _changes.update { it + 1 }
        }
    }

    /** Backups on the phone, newest first. */
    fun list(): List<Entry> = dir.listFiles().orEmpty().mapNotNull { file ->
        val kind = BackupPolicy.kindOf(file.name) ?: return@mapNotNull null
        Entry(file, kind, file.length(), file.lastModified())
    }.sortedByDescending { it.writtenAt }

    /** When the newest dated backup was written ([BackupPolicy.lastDated]); null when there is none. */
    fun lastBackupMillis(now: Long = System.currentTimeMillis()): Long? = BackupPolicy.lastDated(stored(), now)

    private fun stored(): List<BackupPolicy.Stored> =
        dir.listFiles().orEmpty().map { BackupPolicy.Stored(it.name, it.lastModified()) }

    private fun replaceLatest(source: File) {
        val temp = File(dir, LATEST_TEMP_NAME)
        try {
            source.copyTo(temp, overwrite = true)
            sync(temp)
            if (!temp.renameTo(File(dir, BackupPolicy.LATEST))) throw IOException("cannot name ${BackupPolicy.LATEST}")
        } finally {
            temp.delete()
        }
    }

    private fun prune(now: Long, justWritten: String, keep: Set<String>) {
        BackupPolicy.toDelete(stored(), now, justWritten, keep).forEach { File(dir, it).delete() }
    }

    /** Whether [file] is one of the backups in this folder (a restore reading its own backup). */
    fun holds(file: File): Boolean = file.parentFile?.canonicalPath == dir.canonicalPath

    /** Flushes [file] to storage, so a power cut right after the rename can't leave an empty backup. */
    private fun sync(file: File) {
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    private fun deleteWithSidecars(file: File) {
        listOf("", "-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    private companion object {
        const val TAG = "LiftsBackup"

        /** How long after Home shows its backup check queues: past the first taps on Home. */
        const val HOME_CHECK_DELAY_MS = 5_000L

        /** Dot-prefixed, so a listing that hides dot files (Tool Manager's default) never offers one. */
        const val TEMP_NAME = ".backup.tmp"
        const val LATEST_TEMP_NAME = ".latest.tmp"
    }
}
