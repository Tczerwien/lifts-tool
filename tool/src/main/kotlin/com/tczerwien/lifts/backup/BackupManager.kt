package com.tczerwien.lifts.backup

import android.util.Log
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.settings.SettingsRows
import com.tczerwien.lifts.settings.SettingsStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /** Finish and Back up now requests queued or running. */
    private val requested = AtomicInteger()

    /** Why the latest backup failed; null once one succeeds. Kept until then, and Home retries. */
    @Volatile
    private var lastFailure: String? = null

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** A dated backup, queued on the write lane: Finish and Back up now. */
    fun request() {
        requested.incrementAndGet()
        publish()
        LiftsIo.write({ runCatching { backUp(BackupPolicy.Kind.DATED) } }) {
            requested.decrementAndGet()
            publish()
        }
    }

    /**
     * Home: a dated backup if the newest is a day old ([BackupPolicy.isDue]) or the latest attempt
     * failed, decided when its turn comes. A check that finds nothing due leaves the status alone.
     */
    fun requestIfDue() {
        LiftsIo.write({
            if (lastFailure != null || BackupPolicy.isDue(lastBackupMillis(), System.currentTimeMillis())) {
                runCatching { backUp(BackupPolicy.Kind.DATED) }
            }
        }) { publish() }
    }

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
    fun backUp(kind: BackupPolicy.Kind, now: Long = System.currentTimeMillis()): File = try {
        write(kind, now).also { lastFailure = null }
    } catch (e: Exception) {
        Log.w(TAG, "Backup failed", e)
        lastFailure = e.message ?: e.toString()
        throw e
    } finally {
        publish()
    }

    private fun write(kind: BackupPolicy.Kind, now: Long): File {
        val started = System.nanoTime()
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.name}")
        val temp = File(dir, TEMP_NAME)
        try {
            deleteWithSidecars(temp)
            repository.copyTo(temp.absolutePath)
            BackupFile.writeSettings(temp, SettingsRows.encode(settings.snapshot()))
            BackupFile.integrityProblem(temp)?.let { throw IOException("the copy failed its check: $it") }
            sync(temp)
            val target = File(dir, BackupPolicy.uniqueName(kind, now, dir.list().orEmpty().toList()))
            if (!temp.renameTo(target)) throw IOException("cannot name ${target.name}")
            if (kind == BackupPolicy.Kind.DATED) replaceLatest(target)
            prune(justWritten = target.name)
            Log.i(TAG, "Backup ${target.name}: ${target.length()} B in ${(System.nanoTime() - started) / 1_000_000} ms")
            return target
        } finally {
            deleteWithSidecars(temp)
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

    private fun prune(justWritten: String) {
        BackupPolicy.toDelete(stored(), justWritten).forEach { File(dir, it).delete() }
    }

    /** Flushes [file] to storage, so a power cut right after the rename can't leave an empty backup. */
    private fun sync(file: File) {
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    private fun deleteWithSidecars(file: File) {
        listOf("", "-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    private companion object {
        const val TAG = "LiftsBackup"

        /** Dot-prefixed, so a listing that hides dot files (Tool Manager's default) never offers one. */
        const val TEMP_NAME = ".backup.tmp"
        const val LATEST_TEMP_NAME = ".latest.tmp"
    }
}
