package com.tczerwien.lifts.backup

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Backup file names, which copies to keep, and when the next automatic backup is due. */
object BackupPolicy {

    /** Always the newest dated backup: the one file to grab when in doubt. */
    const val LATEST = "lifts-latest.db"

    const val KEEP_DATED = 10
    const val KEEP_PRE_RESTORE = 3

    /** Home backs up when the newest dated backup is at least this old. */
    const val DUE_AFTER_MS = 24 * 60 * 60 * 1000L

    enum class Kind(val prefix: String) {
        /** After a Finish, once a day from Home, or Back up now. */
        DATED("lifts-"),

        /** What the phone held just before a restore replaced it. */
        PRE_RESTORE("pre-restore-"),
    }

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.US)

    private val named = Regex("""(lifts-|pre-restore-)\d{4}-\d{2}-\d{2}-\d{4}\.db""")

    /** "lifts-2026-09-28-1432.db": local time, so names sort by when they were taken. */
    fun fileName(kind: Kind, millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        kind.prefix + stamp.format(Instant.ofEpochMilli(millis).atZone(zone)) + ".db"

    /** The kind of backup [name] is; null for [LATEST] and for any file Lifts didn't name. */
    fun kindOf(name: String): Kind? {
        if (!named.matches(name)) return null
        return Kind.entries.first { name.startsWith(it.prefix) }
    }

    /**
     * The files of a backups folder listing ([names]) to delete: every dated copy but the newest
     * [KEEP_DATED] and every pre-restore copy but the newest [KEEP_PRE_RESTORE]. [LATEST] and files
     * Lifts didn't name are never on the list.
     */
    fun toDelete(names: Collection<String>): List<String> {
        val byKind = names.distinct().groupBy { kindOf(it) }
        return Kind.entries.flatMap { kind ->
            val keep = when (kind) {
                Kind.DATED -> KEEP_DATED
                Kind.PRE_RESTORE -> KEEP_PRE_RESTORE
            }
            byKind[kind].orEmpty().sortedDescending().drop(keep)
        }
    }

    /**
     * Whether Home should back up now, given when the newest dated backup was written ([lastMillis],
     * null when there is none). A clock set back by more than a day counts as due too, or the
     * backups would stop until the clock caught up.
     */
    fun isDue(lastMillis: Long?, now: Long): Boolean =
        lastMillis == null || now - lastMillis >= DUE_AFTER_MS || lastMillis - now > DUE_AFTER_MS
}
