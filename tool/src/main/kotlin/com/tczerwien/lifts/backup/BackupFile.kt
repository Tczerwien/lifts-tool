package com.tczerwien.lifts.backup

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Plain SQLite on a backup file: never the live lifts.db, which only Room opens. Files open without
 * Android's default corruption handler, which would delete a file it can't read; a bad backup is
 * reported, never removed.
 */
internal object BackupFile {

    /** Settings rows ([com.tczerwien.lifts.settings.SettingsRows]) ride along in the copy. */
    const val SETTINGS_TABLE = "lifts_settings"

    private fun open(file: File, writable: Boolean): SQLiteDatabase {
        val params = SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(if (writable) SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY)
            .setErrorHandler { }
            .apply { if (writable) setJournalMode("DELETE") }
            .build()
        return SQLiteDatabase.openDatabase(file, params)
    }

    /** Replaces the settings table in [file] with [rows], in one transaction. */
    fun writeSettings(file: File, rows: Map<String, String>) {
        open(file, writable = true).use { db ->
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS $SETTINGS_TABLE (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
                db.execSQL("DELETE FROM $SETTINGS_TABLE")
                rows.forEach { (key, value) ->
                    db.execSQL("INSERT INTO $SETTINGS_TABLE (key, value) VALUES (?, ?)", arrayOf(key, value))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** The settings rows in [file]; empty when it has none (a database pulled over adb, say). */
    fun readSettings(file: File): Map<String, String> = open(file, writable = false).use { db ->
        val exists = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(SETTINGS_TABLE),
        ).use { it.moveToFirst() }
        if (!exists) return@use emptyMap()
        db.rawQuery("SELECT key, value FROM $SETTINGS_TABLE", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }
    }

    /** SQLite's own check of every page and index; null when [file] is sound, else what it found. */
    fun integrityProblem(file: File): String? = open(file, writable = false).use { db ->
        val lines = db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        lines.takeUnless { it == listOf("ok") }?.joinToString("; ")?.ifEmpty { "no answer" }
    }
}
