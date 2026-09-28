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

    private val named = Regex("""(lifts-|pre-restore-)\d{4}-\d{2}-\d{2}-\d{4}(-\d+)?\.db""")

    /** A backup file as the folder holds it: its name and when it was written (its mtime). */
    data class Stored(val name: String, val writtenAt: Long)

    /** A written backup more than this far in the future came from a wrong clock. */
    private const val CLOCK_SLACK_MS = 60 * 1000L

    /** "lifts-2026-09-28-1432.db": local time, to the minute. */
    fun fileName(kind: Kind, millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        kind.prefix + stamp.format(Instant.ofEpochMilli(millis).atZone(zone)) + ".db"

    /**
     * [fileName], unless the folder already holds it ([existing]); then "…-1432-2.db", "-3" and so on.
     * A backup never replaces another: two restores in one minute keep both pre-restore copies.
     */
    fun uniqueName(kind: Kind, millis: Long, existing: Collection<String>, zone: ZoneId = ZoneId.systemDefault()): String {
        val base = fileName(kind, millis, zone)
        if (base !in existing) return base
        val stem = base.removeSuffix(".db")
        return generateSequence(2) { it + 1 }.map { "$stem-$it.db" }.first { it !in existing }
    }

    /** The kind of backup [name] is; null for [LATEST] and for any file Lifts didn't name. */
    fun kindOf(name: String): Kind? {
        if (!named.matches(name)) return null
        return Kind.entries.first { name.startsWith(it.prefix) }
    }

    /**
     * The files of a backups folder ([stored]) to delete: every dated copy but the newest [KEEP_DATED]
     * and every pre-restore copy but the newest [KEEP_PRE_RESTORE], newest by when they were written.
     * [justWritten] is never on the list, even when a wrong clock makes it look oldest, and neither
     * are [LATEST] and files Lifts didn't name.
     */
    fun toDelete(stored: Collection<Stored>, justWritten: String? = null): List<String> {
        val byKind = stored.distinctBy { it.name }.filter { it.name != justWritten }.groupBy { kindOf(it.name) }
        return Kind.entries.flatMap { kind ->
            val keep = when (kind) {
                Kind.DATED -> KEEP_DATED
                Kind.PRE_RESTORE -> KEEP_PRE_RESTORE
            } - (if (justWritten != null && kindOf(justWritten) == kind) 1 else 0)
            byKind[kind].orEmpty()
                .sortedWith(compareByDescending<Stored> { it.writtenAt }.thenByDescending { it.name })
                .drop(keep)
                .map { it.name }
        }
    }

    /**
     * When the newest dated backup in [stored] was written, for [isDue]; null when there is none. A file
     * written in the future (the clock was wrong, then fixed) doesn't count, or Home would believe a
     * backup was fresh for as long as the clock had been ahead.
     */
    fun lastDated(stored: Collection<Stored>, now: Long): Long? = stored
        .filter { kindOf(it.name) == Kind.DATED && it.writtenAt <= now + CLOCK_SLACK_MS }
        .maxOfOrNull { it.writtenAt }

    /** Whether Home should back up now, given [lastDated]. */
    fun isDue(lastMillis: Long?, now: Long): Boolean = lastMillis == null || now - lastMillis >= DUE_AFTER_MS
}
