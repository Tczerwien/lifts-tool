package com.tczerwien.lifts.backup

import com.tczerwien.lifts.backup.BackupPolicy.Kind
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

        val deleted = BackupPolicy.toDelete(listing).toSet()

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
        assertEquals(listOf("pre-restore-2026-09-28-1432.db"), BackupPolicy.toDelete(listing))
    }

    @Test
    fun `the file just written is never pruned, even when a wrong clock makes it look oldest`() {
        val kept = (1..3).map { d -> stored("pre-restore-2026-10-%02d-0800.db".format(d), sep1 + (30 + d) * day) }
        val fresh = stored("pre-restore-2026-09-01-0800.db", sep1)
        val deleted = BackupPolicy.toDelete(kept + fresh, justWritten = fresh.name)
        assertEquals(listOf(kept.first().name), deleted, "three copies stay, the new one among them")
    }

    @Test
    fun `nothing is pruned below the limits`() {
        val few = (1..10).map { stored("lifts-2026-09-%02d-0800.db".format(it), sep1 + it * day) } +
            (1..3).map { stored("pre-restore-2026-09-%02d-0800.db".format(it), sep1 + it * day) } + stored(BackupPolicy.LATEST, sep1)
        assertEquals(emptyList(), BackupPolicy.toDelete(few))
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
}
