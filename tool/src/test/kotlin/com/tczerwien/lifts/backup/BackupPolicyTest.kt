package com.tczerwien.lifts.backup

import com.tczerwien.lifts.backup.BackupPolicy.Kind
import com.tczerwien.lifts.data.FinishedWorkouts
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupPolicyTest {

    private val utc = ZoneId.of("UTC")

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `names are local time, zero-padded, by kind`() {
        assertEquals("lifts-2026-01-05-0704.db", BackupPolicy.fileName(Kind.DATED, millis("2026-01-05T07:04:59Z"), utc))
        assertEquals(
            "pre-restore-2026-09-28-1432.db",
            BackupPolicy.fileName(Kind.PRE_RESTORE, millis("2026-09-28T14:32:00Z"), utc),
        )
        assertEquals(
            "lifts-2026-09-29-0030.db",
            BackupPolicy.fileName(Kind.DATED, millis("2026-09-28T22:30:00Z"), ZoneId.of("UTC+2")),
        )
    }

    @Test
    fun `a name already taken gets a number, so no backup replaces another`() {
        val at = millis("2026-09-28T14:32:10Z")
        assertEquals("pre-restore-2026-09-28-1432.db", BackupPolicy.uniqueName(Kind.PRE_RESTORE, at, listOf("lifts-2026-09-28-1432.db"), utc))
        assertEquals(
            "pre-restore-2026-09-28-1432-2.db",
            BackupPolicy.uniqueName(Kind.PRE_RESTORE, at, listOf("pre-restore-2026-09-28-1432.db"), utc),
        )
        assertEquals(
            "lifts-2026-09-28-1432-3.db",
            BackupPolicy.uniqueName(Kind.DATED, at, listOf("lifts-2026-09-28-1432.db", "lifts-2026-09-28-1432-2.db"), utc),
        )
    }

    @Test
    fun `only names Lifts writes count as backups`() {
        assertEquals(Kind.DATED, BackupPolicy.kindOf("lifts-2026-09-28-1432.db"))
        assertEquals(Kind.DATED, BackupPolicy.kindOf("lifts-2026-09-28-1432-2.db"))
        assertEquals(Kind.PRE_RESTORE, BackupPolicy.kindOf("pre-restore-2026-09-28-1432.db"))
        listOf(
            BackupPolicy.LATEST,
            "lifts-2026-9-28-1432.db",
            "lifts-2026-09-28-1432.db.tmp",
            "lifts-2026-09-28-1432-.db",
            ".lifts-2026-09-28-1432.db",
            "my-lifts-2026-09-28-1432.db",
            "workouts.json",
        ).forEach { assertNull(BackupPolicy.kindOf(it), it) }
    }

    private val day = 24 * 60 * 60 * 1000L
    private val sep1 = millis("2026-09-01T08:00:00Z")

    private fun stored(name: String, writtenAt: Long) = BackupPolicy.Stored(name, writtenAt)

    @Test
    fun `pruning keeps the newest ten dated and three pre-restore copies and nothing else goes`() {
        val dated = (1..13).map { d -> stored("lifts-2026-09-%02d-0800.db".format(d), sep1 + (d - 1) * day) }
        val preRestore = (1..5).map { d -> stored("pre-restore-2026-08-%02d-2100.db".format(d), sep1 - (40 - d) * day) }
        val others = listOf(stored(BackupPolicy.LATEST, sep1), stored("notes.txt", 0), stored(".backup.tmp", 0))
        val listing = (dated + preRestore + others).shuffled(java.util.Random(6))

        val deleted = BackupPolicy.toDelete(listing, now = sep1 + 20 * day, zone = utc).toSet()

        assertEquals((dated.take(3) + preRestore.take(2)).map { it.name }.toSet(), deleted)
    }

    @Test
    fun `pruning goes by when a file was written, not by its name`() {
        // "…1432-2.db" sorts last by name, but it was written after "…1432.db", which is the oldest.
        val listing = listOf(
            stored("pre-restore-2026-09-28-1432.db", sep1),
            stored("pre-restore-2026-09-28-1432-2.db", sep1 + 30_000),
            stored("pre-restore-2026-09-28-1433.db", sep1 + 60_000),
            stored("pre-restore-2026-09-28-1434.db", sep1 + 90_000),
        )
        assertEquals(listOf("pre-restore-2026-09-28-1432.db"), BackupPolicy.toDelete(listing, now = sep1 + day))
    }

    @Test
    fun `files from a clock that was ahead stay until it passes them, and later backups survive`() {
        // The clock ran a month ahead for three pre-restore copies, then was fixed.
        val future = (1..3).map { d -> stored("pre-restore-2026-10-%02d-0800.db".format(d), sep1 + (30 + d) * day) }
        val fresh = stored("pre-restore-2026-09-01-0800.db", sep1)
        assertEquals(emptyList(), BackupPolicy.toDelete(future + fresh, now = sep1, justWritten = fresh.name))
        // Later copies are pruned among themselves, never pushed out by the future-dated ones.
        val later = (2..4).map { d -> stored("pre-restore-2026-09-%02d-0800.db".format(d), sep1 + (d - 1) * day) }
        assertEquals(
            listOf(fresh.name),
            BackupPolicy.toDelete(future + fresh + later, now = later.last().writtenAt, justWritten = later.last().name),
        )
        // Once the clock passes them they are ordinary copies again: the newest three, so the rest go.
        val pastThem = sep1 + 40 * day
        assertEquals(later.map { it.name }.toSet(), BackupPolicy.toDelete(future + later, now = pastThem).toSet())
    }

    @Test
    fun `a clock reset to months ago prunes none of the real backups`() {
        // A year of daily backups to 2026-09-29; the battery ran out and the phone came back thinking it
        // is 2026-06-01. Home finds no backup it believes and writes one; nothing written later goes.
        val first = millis("2025-09-30T20:00:00Z")
        val daily = (0 until 365).map { d -> (first + d * day).let { stored(BackupPolicy.fileName(Kind.DATED, it, utc), it) } }
        val now = millis("2026-06-01T09:00:00Z")
        val wrong = stored(BackupPolicy.fileName(Kind.DATED, now, utc), now)
        val deleted = BackupPolicy.toDelete(daily + wrong, now = now, justWritten = wrong.name, zone = utc).toSet()
        assertTrue(deleted.isNotEmpty(), "copies before the wrong date are pruned as usual")
        assertTrue(daily.filter { it.writtenAt > now }.none { it.name in deleted }, "every copy from after the wrong date stays")
    }

    @Test
    fun `restoring a pre-restore copy deletes no other copy, and a later backup trims the source`() {
        // Three pre-restore copies; the lifter restores the oldest. Each may be the only copy of what it holds.
        val copies = (1..3).map { d -> stored("pre-restore-2026-09-%02d-0800.db".format(d), sep1 + d * day) }
        val safety = stored("pre-restore-2026-09-05-0800.db", sep1 + 5 * day)
        val during = BackupPolicy.toDelete(copies + safety, now = sep1 + 5 * day, justWritten = safety.name, keep = listOf(copies[0].name))
        assertEquals(emptyList(), during, "four copies for now: the source and all the others stay")
        // The next backup that doesn't keep it (the next Finish, say) trims the source.
        val dated = stored("lifts-2026-09-05-0801.db", sep1 + 5 * day + 60_000)
        assertEquals(listOf(copies[0].name), BackupPolicy.toDelete(copies + safety + dated, now = dated.writtenAt, justWritten = dated.name))
    }

    @Test
    fun `a backup a day for 400 days keeps ten recent, one a week for 12 weeks and one a month for a year`() {
        // Daily at 20:00 UTC from 2025-08-26 to 2026-09-29 (a Tuesday); just written: today's.
        val first = millis("2025-08-26T20:00:00Z")
        val daily = (0 until 400).map { d ->
            val at = first + d * day
            stored(BackupPolicy.fileName(Kind.DATED, at, utc), at)
        }
        val now = daily.last().writtenAt
        val deleted = BackupPolicy.toDelete(daily, now = now, justWritten = daily.last().name, zone = utc).toSet()
        val kept = daily.map { it.name }.filterNot { it in deleted }

        // The newest ten: 2026-09-20 .. 09-29.
        assertTrue(kept.containsAll(daily.takeLast(10).map { it.name }))
        // Each of the last 12 weeks (Mondays 2026-07-13 .. 09-28) keeps its Sunday, or today in this week.
        for (sunday in listOf("2026-07-19", "2026-08-02", "2026-08-30", "2026-09-13")) {
            assertTrue("lifts-$sunday-2000.db" in kept, "week ending $sunday")
        }
        assertFalse("lifts-2026-07-12-2000.db" in kept, "13 weeks back and not its month's newest")
        assertTrue("lifts-2026-07-31-2000.db" in kept && "lifts-2026-08-31-2000.db" in kept, "months' newest")
        // Each of the last 12 months (2025-10 .. 2026-09) keeps its last day; older months nothing.
        for (lastDay in listOf("2025-10-31", "2025-12-31", "2026-02-28", "2026-06-30")) {
            assertTrue("lifts-$lastDay-2000.db" in kept, "month ending $lastDay")
        }
        assertTrue(kept.none { it.startsWith("lifts-2025-08") || it.startsWith("lifts-2025-09") }, "older than 12 months")
        // 10 recent (09-20..29), 9 more weeks (Sundays 07-19..09-13; the week of 09-14 ends in the recent
        // ten) and 11 more months (2025-10 .. 2026-08; September's newest is today's).
        assertEquals(10 + 9 + 11, kept.size)
    }

    @Test
    fun `a file from a wrong clock never stands for a week or a month`() {
        val now = millis("2026-09-29T20:00:00Z")
        val recent = (0 until 10).map { d -> stored("lifts-2026-09-%02d-2000.db".format(29 - d), now - d * day) }
        val future = stored("lifts-2026-12-01-2000.db", now + 60 * day)
        // August's newest real copy stays as its month's, whatever the future file's date.
        val lastMonth = stored("lifts-2026-08-15-2000.db", now - 45 * day)
        val older = stored("lifts-2026-08-10-2000.db", now - 50 * day)
        val deleted = BackupPolicy.toDelete(recent + future + lastMonth + older, now = now, zone = utc)
        assertEquals(listOf(older.name), deleted)
    }

    @Test
    fun `nothing is pruned below the limits`() {
        val few = (1..10).map { stored("lifts-2026-09-%02d-0800.db".format(it), sep1 + it * day) } +
            (1..3).map { stored("pre-restore-2026-09-%02d-0800.db".format(it), sep1 + it * day) } + stored(BackupPolicy.LATEST, sep1)
        assertEquals(emptyList(), BackupPolicy.toDelete(few, now = sep1 + 20 * day))
    }

    @Test
    fun `the newest dated backup ignores pre-restore copies and files from the future`() {
        val now = millis("2026-09-28T12:00:00Z")
        val listing = listOf(
            stored("lifts-2026-09-27-0800.db", now - day),
            stored("lifts-2026-11-02-0800.db", now + 35 * day),
            stored("pre-restore-2026-09-28-1100.db", now - 3_600_000),
            stored(BackupPolicy.LATEST, now + 35 * day),
        )
        assertEquals(now - day, BackupPolicy.lastDated(listing, now))
        assertNull(BackupPolicy.lastDated(listing.drop(1).take(2), now), "only future and pre-restore files: none")
    }

    @Test
    fun `a backup is due after 24 hours, or with none`() {
        val now = millis("2026-09-28T12:00:00Z")
        assertTrue(BackupPolicy.isDue(null, now))
        assertFalse(BackupPolicy.isDue(now - day + 1, now))
        assertTrue(BackupPolicy.isDue(now - day, now))
    }

    @Test
    fun `a Finish the newest backup doesn't hold makes Home back up, whatever the clock says`() {
        val copied = FinishedWorkouts(count = 41, lastId = 57, endSum = 73_000_000_000L)
        assertFalse(BackupPolicy.behindLog(inLatest = copied, inLog = copied), "copied since")
        assertTrue(BackupPolicy.behindLog(copied, copied.copy(count = 42, lastId = 58, endSum = 74_800_000_000L)), "a Finish since")
        // RESUME and Finish again: same workout, a later end.
        assertTrue(BackupPolicy.behindLog(copied, copied.copy(endSum = copied.endSum + 600_000)), "finished again")
        assertTrue(BackupPolicy.behindLog(copied, copied.copy(count = 40, endSum = 71_000_000_000L)), "a workout deleted")
        // RESUME, two more sets, Finish again with the clock set back: the end stays at the last tick.
        val ticked = copied.copy(ticked = 610, tickSum = 1_090_000_000_000_000L)
        assertTrue(BackupPolicy.behindLog(ticked, ticked.copy(ticked = 612, tickSum = ticked.tickSum + 3_580_000_000_000L)), "more sets, same end")
        // RESUME, a logged weight corrected, Finish again with the end at the last tick.
        val valued = ticked.copy(valueSum = 4_400_000_000_000L)
        assertTrue(BackupPolicy.behindLog(valued, valued.copy(valueSum = valued.valueSum + 2_268L * 9_041)), "a value corrected, same ticks")
        // A deload marked in Workout Detail after its Finish: nothing else changes.
        assertTrue(BackupPolicy.behindLog(valued, valued.copy(deloadSum = 57)), "a deload marked")
        assertTrue(BackupPolicy.behindLog(inLatest = null, inLog = copied), "no backup (or one that can't be read)")
        val none = FinishedWorkouts(count = 0, lastId = 0, endSum = 0)
        assertFalse(BackupPolicy.behindLog(inLatest = null, inLog = none), "nothing finished, no backup: isDue's business")
        assertFalse(BackupPolicy.behindLog(inLatest = none, inLog = none))
    }
}
