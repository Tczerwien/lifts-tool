package com.tczerwien.lifts.backup

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Backup file names, which copies to keep, and when the next automatic backup is due. */
object BackupPolicy {

    /** Always the newest dated backup: the one file to grab when in doubt. */
    const val LATEST = "lifts-latest.db"

    const val KEEP_DATED = 10
    const val KEEP_PRE_RESTORE = 3

    /**
     * Besides the newest [KEEP_DATED], the newest dated backup of each of the last [KEEP_WEEKLY] weeks
     * and of the last [KEEP_MONTHLY] months stays, so a mistake noticed weeks later (a workout deleted,
     * the wrong file restored) can still be undone: every Finish writes a backup, and ten alone cover
     * only about two weeks. Each file is about 1 MB.
     */
    const val KEEP_WEEKLY = 12
    const val KEEP_MONTHLY = 12

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
     * and every pre-restore copy but the newest [KEEP_PRE_RESTORE], newest by when they were written;
     * the dated copies that are the newest of one of the last [KEEP_WEEKLY] weeks or [KEEP_MONTHLY]
     * months (local calendar in [zone]) stay too. A file written in the future is never on the list
     * and doesn't count: either it came from a clock that was ahead (it waits until the clock passes
     * it, and meanwhile never stands for a week or a month), or the clock is behind now (a phone that
     * lost its time when the battery ran out), and then it is one of his real restore points, which a
     * wrong clock must not prune. [justWritten] is never on the list and counts toward its kind's limit. Names in [keep] (the
     * file a restore is reading) are never on the list either, but don't count: the limit may be
     * passed for a while, and a later backup trims the extra copy. [LATEST] and files Lifts didn't
     * name are never on the list.
     */
    fun toDelete(
        stored: Collection<Stored>,
        now: Long,
        justWritten: String? = null,
        keep: Collection<String> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<String> {
        val spared = keep + listOfNotNull(justWritten)
        val distinct = stored.distinctBy { it.name }
        val byKind = distinct.filter { it.name !in spared }.groupBy { kindOf(it.name) }
        return Kind.entries.flatMap { kind ->
            val limit = when (kind) {
                Kind.DATED -> KEEP_DATED
                Kind.PRE_RESTORE -> KEEP_PRE_RESTORE
            }
            val kept = if (justWritten != null && kindOf(justWritten) == kind) 1 else 0
            val ordered = byKind[kind].orEmpty()
                .filter { it.writtenAt <= now + CLOCK_SLACK_MS }
                .sortedWith(compareByDescending<Stored> { it.writtenAt }.thenByDescending { it.name })
            val recent = ordered.take((limit - kept).coerceAtLeast(0)).mapTo(HashSet()) { it.name }
            val longer = if (kind == Kind.DATED) newestPerWeekAndMonth(distinct.filter { kindOf(it.name) == kind }, now, zone) else emptySet()
            ordered.filter { it.name !in recent && it.name !in longer }.map { it.name }
        }
    }

    /**
     * The newest of [files] in each of the last [KEEP_WEEKLY] ISO weeks and each of the last
     * [KEEP_MONTHLY] months up to [now], in [zone]. Files from the future are left out.
     */
    private fun newestPerWeekAndMonth(files: List<Stored>, now: Long, zone: ZoneId): Set<String> {
        val newestFirst = files
            .filter { it.writtenAt <= now + CLOCK_SLACK_MS }
            .sortedWith(compareByDescending<Stored> { it.writtenAt }.thenByDescending { it.name })
        fun dayOf(file: Stored): LocalDate = Instant.ofEpochMilli(file.writtenAt).atZone(zone).toLocalDate()
        fun mondayOf(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val firstMonday = mondayOf(today).minusWeeks((KEEP_WEEKLY - 1).toLong())
        val firstMonth = YearMonth.from(today).minusMonths((KEEP_MONTHLY - 1).toLong())
        // groupBy keeps the order, so each group's first file is its newest.
        val weekly = newestFirst.groupBy { mondayOf(dayOf(it)) }.filterKeys { it >= firstMonday }.values.map { it.first().name }
        val monthly = newestFirst.groupBy { YearMonth.from(dayOf(it)) }.filterKeys { it >= firstMonth }.values.map { it.first().name }
        return (weekly + monthly).toSet()
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

    /**
     * Whether the newest dated backup ([lastMillis]) is older than the last finished workout's end
     * ([lastWorkoutEnd]): a Finish since, not yet copied. Home's check then backs up too. An end in
     * the future (the clock was ahead at Finish, or set back mid-workout: Finish never ends before the
     * last tick) doesn't count: every copy made until the clock passes it would look older, and each
     * Home show would back up again. [isDue] still backs up daily until then.
     */
    fun behindLog(lastMillis: Long?, lastWorkoutEnd: Long?, now: Long): Boolean =
        lastWorkoutEnd != null && lastWorkoutEnd <= countedUntil(now) &&
            (lastMillis == null || lastMillis < lastWorkoutEnd)

    /**
     * The latest workout end [behindLog] counts at [now]. Home asks for the last end up to it, not the
     * last end of all: one end in the future must not hide the Finishes after it (a phone whose clock
     * came back months behind after the battery ran out: every real workout is "ahead", and today's
     * Finish still needs its backup).
     */
    fun countedUntil(now: Long): Long = now + CLOCK_SLACK_MS
}
