package com.tczerwien.lifts.toolmanager

import com.tczerwien.lifts.backup.BackupManager
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.export.ExportWriter
import com.tczerwien.lifts.export.renderExports
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.settings.SettingsStore
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.toolmanager.LightFileProvider
import java.io.File
import kotlinx.coroutines.launch

/**
 * The folders Tool Manager serves (files/shared/<folder>) and the one writer for each. Like the
 * repository, there is one per process, whichever screen the process started on, so two screens can
 * never write the same folder at once.
 */
class LiftsShare private constructor(root: File, repository: LiftsRepository, settings: SettingsStore) {

    val exportsDir = File(root, LiftsToolManager.EXPORTS)

    val exports = ExportWriter(exportsDir, LiftsIo.scope) { renderExports(repository) }

    val backups = BackupManager(File(root, LiftsToolManager.BACKUPS), repository, settings)

    /** Finish saved a workout: a backup, and the exports catch up. */
    fun workoutFinished() {
        backups.request()
        exports.request()
    }

    /** Workout Detail deleted a workout: the exports drop it. */
    fun workoutDeleted() {
        exports.request()
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

        internal fun create(filesDir: File, repository: LiftsRepository, settings: SettingsStore): LiftsShare {
            // The Wi-Fi share server (removed in 0.7.0) wrote its copies to the private files/exports.
            LiftsIo.scope.launch { File(filesDir, "exports").deleteRecursively() }
            return LiftsShare(File(filesDir, LightFileProvider.SHARED_DIR), repository, settings)
        }
    }
}

fun SealedLightContext.liftsShare(): LiftsShare = LiftsShare.getInstance {
    LiftsShare.create(filesDir, liftsRepository(), SettingsStore(dataStore))
}
