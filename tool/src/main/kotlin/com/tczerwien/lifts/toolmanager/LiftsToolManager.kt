package com.tczerwien.lifts.toolmanager

import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.ClientToolManifest
import com.thelightphone.toolmanager.DropboxSpec
import com.thelightphone.toolmanager.FileBrowserSpec

/**
 * What Lifts shows in Tool Manager, the browser page LightOS serves for moving files on and off the
 * phone. Each folder is a directory under files/shared; LightOS reads it through the SDK's file
 * provider, so Lifts needs no network permission of its own.
 */
object LiftsToolManager {
    /** files/shared/exports: the four export files, written by ExportWriter. */
    const val EXPORTS = "exports"

    /** files/shared/backups: whole-log backups, written by BackupManager. */
    const val BACKUPS = "backups"

    /** files/shared/restore: where a backup is uploaded to be restored. */
    const val RESTORE = "restore"

    fun manifest(): ClientToolManifest = ClientToolManifest(
        title = "Lifts",
        roots = listOf(
            ClientLeafNode(
                spec = FileBrowserSpec(
                    label = "Exports",
                    path = EXPORTS,
                    headerText = "Rewritten after every workout. training-log.md reads well on its own or in an AI; " +
                        "workouts.json is the full record; strong.csv imports into Strong or Hevy.",
                ),
                readOnly = true,
            ),
            ClientLeafNode(
                spec = FileBrowserSpec(
                    label = "Backups",
                    path = BACKUPS,
                    headerText = "The whole log and your settings, saved after every workout and once a day. " +
                        "lifts-latest.db is always the newest. To bring one back, upload it to Restore.",
                ),
                readOnly = true,
            ),
            ClientLeafNode(
                spec = DropboxSpec(
                    label = "Restore",
                    path = RESTORE,
                    headerText = "Upload a Lifts backup (.db) here, then open Lifts: it shows what the file holds " +
                        "and asks before replacing anything. What is on the phone is saved first.",
                    buttonText = "Upload backup",
                ),
            ),
        ),
    )
}
