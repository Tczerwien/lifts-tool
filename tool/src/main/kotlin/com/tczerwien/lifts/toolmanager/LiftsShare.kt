package com.tczerwien.lifts.toolmanager

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.tczerwien.lifts.backup.BackupManager
import com.tczerwien.lifts.backup.Restorer
import com.tczerwien.lifts.data.LiftsDatabase
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.export.ExportWriter
import com.tczerwien.lifts.export.renderExports
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.settings.SettingsStore
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.toolmanager.LightFileProvider
import java.io.File
import kotlinx.coroutines.launch

/**
 * The folders Tool Manager serves (files/shared/<folder>) and the one writer for each. Like the
 * repository, there is one per process, whichever screen the process started on, so two screens can
 * never write the same folder at once.
 */
class LiftsShare private constructor(
    root: File,
    stagingDir: File,
    val repository: LiftsRepository,
    settings: SettingsStore,
    dataStore: DataStore<Preferences>,
    openDatabase: (File) -> LiftsDatabase,
) {

    val exportsDir = File(root, LiftsToolManager.EXPORTS)

    val exports = ExportWriter(exportsDir, LiftsIo.scope) { renderExports(repository) }

    val backups = BackupManager(File(root, LiftsToolManager.BACKUPS), repository, settings)

    /** Uploads waiting to be restored land here; applied and set-aside ones move to subfolders. */
    private val restoreDir = File(root, LiftsToolManager.RESTORE)

    val restorer = Restorer(stagingDir, openDatabase, repository, settings, dataStore, backups)

    /** Finish saved a workout: a backup, and the exports catch up. */
    fun workoutFinished() {
        backups.request()
        exports.request()
    }

    /** Workout Detail deleted a workout: the exports drop it. */
    fun workoutDeleted() {
        exports.request()
    }

    /** The file waiting in Restore (uploaded through Tool Manager) that Home offers ([RestoreInbox]), or null. Blocking. */
    fun waitingRestore(now: Long = System.currentTimeMillis()): File? {
        val uploads = restoreDir.listFiles().orEmpty().filter { it.isFile }
        val name = RestoreInbox.pick(uploads.map { RestoreInbox.Upload(it.name, it.lastModified()) }, now)
        return name?.let { File(restoreDir, it) }
    }

    /**
     * A restore from [source] landed: an upload moves to restore/applied, and the exports catch up.
     * Never throws, so a failed move can't make a restore that landed read as failed; at worst Home
     * offers the file again and checking it says it is gone.
     */
    fun restored(source: File) {
        try {
            if (source.parentFile == restoreDir) moveInto(File(restoreDir, APPLIED), source)
        } catch (e: Exception) {
            Log.w(TAG, "Could not move ${source.name} to restore/$APPLIED", e)
        }
        exports.request()
    }

    /** SET ASIDE on a refused upload: it moves to restore/rejected, so Home stops offering it. */
    fun setAside(source: File) {
        if (source.parentFile == restoreDir) moveInto(File(restoreDir, REJECTED), source)
    }

    private fun moveInto(folder: File, file: File) {
        folder.mkdirs()
        var target = File(folder, file.name)
        if (target.exists()) target = File(folder, "${System.currentTimeMillis()}-${file.name}")
        file.renameTo(target)
    }

    /**
     * Home has loaded (on IO): the daily backup if one is due, and the exports if any is missing, as
     * after a new install.
     */
    fun homeShown() {
        backups.requestIfDue()
        if (ExportWriter.FILE_NAMES.any { !File(exportsDir, it).isFile }) exports.request()
    }

    companion object {
        @Volatile
        private var instance: LiftsShare? = null

        fun getInstance(create: () -> LiftsShare): LiftsShare {
            return instance ?: synchronized(this) {
                instance ?: create().also { instance = it }
            }
        }

        private const val TAG = "LiftsShare"
        private const val APPLIED = "applied"
        private const val REJECTED = "rejected"

        internal fun create(
            filesDir: File,
            repository: LiftsRepository,
            dataStore: DataStore<Preferences>,
            openDatabase: (File) -> LiftsDatabase,
        ): LiftsShare {
            // The Wi-Fi share server (removed in 0.7.0) wrote its copies to the private files/exports.
            LiftsIo.scope.launch { File(filesDir, "exports").deleteRecursively() }
            return LiftsShare(
                root = File(filesDir, LightFileProvider.SHARED_DIR),
                stagingDir = File(filesDir, "restore-staging"),
                repository = repository,
                settings = SettingsStore(dataStore),
                dataStore = dataStore,
                openDatabase = openDatabase,
            )
        }
    }
}

fun SealedLightContext.liftsShare(): LiftsShare = LiftsShare.getInstance {
    LiftsShare.create(filesDir, liftsRepository(), dataStore) { file ->
        buildDatabase(LiftsDatabase::class.java, file.absolutePath)
    }
}
