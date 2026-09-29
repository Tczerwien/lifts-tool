package com.tczerwien.lifts.backup

import android.database.sqlite.SQLiteException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.tczerwien.lifts.data.LiftsDatabase
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.LiftsRows
import com.tczerwien.lifts.session.ActiveSession
import com.tczerwien.lifts.settings.SettingsRows
import com.tczerwien.lifts.settings.SettingsStore
import java.io.File

/**
 * Puts a backup's log and settings in place of what is on the phone, in two steps. [prepare] reads
 * a private copy of the file and says what it holds; nothing on the phone changes. [apply], after
 * the lifter has confirmed, saves a pre-restore backup first, then replaces every row in one
 * transaction and restores the settings.
 */
class Restorer(
    /** Private (not under files/shared): where the copy is read. */
    private val stagingDir: File,
    /** Opens a file with Room by absolute path, so a backup from an older schema is migrated first. */
    private val openStaged: (File) -> LiftsDatabase,
    private val live: LiftsRepository,
    private val settings: SettingsStore,
    private val dataStore: DataStore<Preferences>,
    private val backups: BackupManager,
) {
    /** A backup read and checked, held in memory until it is applied or dropped. */
    class Prepared internal constructor(
        val source: File,
        val rows: LiftsRows,
        /** Empty when the file carries no settings (a database pulled over adb): the phone's stay. */
        val settingsRows: Map<String, String>,
    ) {
        val summary: RestorePlan.Summary = RestorePlan.summarize(rows)
    }

    /** [prepare] turned the file down; the message says why, in words a lifter can act on. */
    class Refused(message: String) : Exception(message)

    /** [apply] stopped before replacing anything: the log and the settings are as they were. */
    class NothingChanged(message: String) : Exception(message)

    data class Outcome(
        /** The pre-restore backup's file name. */
        val preRestore: String,
        val settingsRestored: Boolean,
        /** Set when the log was restored but the settings could not be. */
        val settingsProblem: String? = null,
        /** Set when the restored log could not be saved as the newest backup. */
        val backupProblem: String? = null,
    )

    /**
     * Copies [source] aside, checks it and reads every row; throws [Refused]. Touches only the copy,
     * so it needn't wait for the write lane; one prepare runs at a time.
     */
    @Synchronized
    fun prepare(source: File): Prepared {
        val staged = File(stagingDir, STAGED_NAME)
        try {
            deleteStaged()
            if (!source.isFile) throw Refused("the file is gone")
            if (!stagingDir.isDirectory && !stagingDir.mkdirs()) throw Refused("no room to read it")
            source.copyTo(staged, overwrite = true)
            if (!BackupFile.looksLikeSqlite(staged)) throw Refused("it isn't a Lifts backup (not a database file)")
            val settingsRows = try {
                BackupFile.integrityProblem(staged)?.let { throw Refused("the file is damaged: $it") }
                val version = BackupFile.userVersion(staged)
                if (version <= 0 || !BackupFile.hasTable(staged, ROOM_TABLE)) throw Refused("it isn't a Lifts backup")
                if (version > LiftsDatabase.VERSION) throw Refused("a newer Lifts made it; update Lifts first")
                BackupFile.readSettings(staged)
            } catch (e: SQLiteException) {
                // SQLite can't even read it: typically a copy that was cut short.
                throw Refused("the file is damaged or incomplete")
            }
            val rows = try {
                val db = openStaged(staged)
                try {
                    LiftsRepository.readAll(db)
                } finally {
                    db.close()
                }
            } catch (e: Exception) {
                throw Refused("Lifts can't read it (${e.message ?: e})")
            }
            val problems = RestorePlan.problems(rows)
            if (problems.isNotEmpty()) throw Refused("it is damaged: ${problems.joinToString("; ")}")
            return Prepared(source, rows, settingsRows)
        } finally {
            deleteStaged()
        }
    }

    /**
     * Restores [prepared]. Call it on the write lane only (inside a LiftsIo block), so no save lands
     * halfway. The pre-restore backup comes first: if it can't be saved, nothing is replaced. Then
     * every row in one transaction; only once they are in do the in-workout keys go (restored ids can
     * repeat), so a restore that fails leaves a running rest alone; then the settings. Last, the restored log is saved as the newest dated backup, so lifts-latest.db
     * holds it rather than what was replaced (on a new install, an empty log). Throws
     * [NothingChanged] when it stopped before the rows were replaced.
     */
    suspend fun apply(prepared: Prepared): Outcome {
        // Restoring from one of the phone's own backups: pruning must not take that file before the
        // rows are in (it may be the only copy of what it holds).
        val source = setOfNotNull(prepared.source.name.takeIf { backups.holds(prepared.source) })
        val safety = try {
            backups.backUp(BackupPolicy.Kind.PRE_RESTORE, keep = source)
        } catch (e: Exception) {
            throw NothingChanged("could not save a copy of this phone's log first (${e.message ?: e})")
        }
        if (!safety.isFile || safety.length() == 0L) throw NothingChanged("the copy of this phone's log went missing")
        try {
            live.replaceAll(prepared.rows)
        } catch (e: Exception) {
            throw NothingChanged("the backup's rows would not go in (${e.message ?: e})")
        }
        // The rest, the exercise on screen: they name ids of the log just replaced. The same store as
        // the settings, so a failure here reads as a settings problem.
        val keysProblem = try {
            ActiveSession.forgetWorkoutState(dataStore)
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
        val settingsProblem = if (prepared.settingsRows.isEmpty()) {
            keysProblem
        } else {
            try {
                settings.replaceAll(SettingsRows.decode(prepared.settingsRows, current = settings.snapshot()))
                keysProblem
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
        }
        val backupProblem = try {
            // Still keeping the source: if the settings didn't come back, it is the copy to retry from.
            backups.backUp(BackupPolicy.Kind.DATED, keep = source)
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
        return Outcome(
            preRestore = safety.name,
            settingsRestored = prepared.settingsRows.isNotEmpty() && settingsProblem == null,
            settingsProblem = settingsProblem,
            backupProblem = backupProblem,
        )
    }

    private fun deleteStaged() {
        listOf("", "-journal", "-wal", "-shm").forEach { File(stagingDir, STAGED_NAME + it).delete() }
    }

    private companion object {
        const val STAGED_NAME = "incoming.db"
        const val ROOM_TABLE = "room_master_table"
    }
}
