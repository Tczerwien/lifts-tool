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
    fun `only names Lifts writes count as backups`() {
        assertEquals(Kind.DATED, BackupPolicy.kindOf("lifts-2026-09-28-1432.db"))
        assertEquals(Kind.PRE_RESTORE, BackupPolicy.kindOf("pre-restore-2026-09-28-1432.db"))
        listOf(
            BackupPolicy.LATEST,
            "lifts-2026-9-28-1432.db",
            "lifts-2026-09-28-1432.db.tmp",
            ".lifts-2026-09-28-1432.db",
            "my-lifts-2026-09-28-1432.db",
            "workouts.json",
        ).forEach { assertNull(BackupPolicy.kindOf(it), it) }
    }

    @Test
    fun `pruning keeps the newest ten dated and three pre-restore copies and nothing else goes`() {
        val dated = (1..13).map { day -> "lifts-2026-09-%02d-0800.db".format(day) }
        val preRestore = (1..5).map { day -> "pre-restore-2026-08-%02d-2100.db".format(day) }
        val others = listOf(BackupPolicy.LATEST, "notes.txt", ".lifts-backup.tmp")
        val listing = (dated + preRestore + others).shuffled(java.util.Random(6))

        val deleted = BackupPolicy.toDelete(listing).toSet()

        assertEquals(dated.take(3).toSet() + preRestore.take(2).toSet(), deleted)
    }

    @Test
    fun `pruning compares the time in the name, minutes included`() {
        val sameDay = listOf("lifts-2026-09-28-0905.db", "lifts-2026-09-28-1430.db", "lifts-2026-09-28-0930.db")
        val older = (1..9).map { day -> "lifts-2026-09-%02d-2359.db".format(day) }
        assertEquals(listOf("lifts-2026-09-01-2359.db", "lifts-2026-09-02-2359.db"), BackupPolicy.toDelete(sameDay + older).sorted())
    }

    @Test
    fun `nothing is pruned below the limits`() {
        val few = (1..10).map { "lifts-2026-09-%02d-0800.db".format(it) } +
            (1..3).map { "pre-restore-2026-09-%02d-0800.db".format(it) } + BackupPolicy.LATEST
        assertEquals(emptyList(), BackupPolicy.toDelete(few))
    }

    @Test
    fun `a backup is due after 24 hours, with none, or when the clock went back by more than a day`() {
        val now = millis("2026-09-28T12:00:00Z")
        val hour = 60 * 60 * 1000L
        assertTrue(BackupPolicy.isDue(null, now))
        assertFalse(BackupPolicy.isDue(now - 24 * hour + 1, now))
        assertTrue(BackupPolicy.isDue(now - 24 * hour, now))
        assertFalse(BackupPolicy.isDue(now + hour, now), "a clock a little behind is not a reason")
        assertTrue(BackupPolicy.isDue(now + 25 * hour, now))
    }
}
