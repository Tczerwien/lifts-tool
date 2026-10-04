package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class DeloadTest {

    private val w = SET_TYPE_WARMUP
    private val n = SET_TYPE_NORMAL
    private val f = SET_TYPE_FAILURE
    private val d = SET_TYPE_DROP

    /** One slot's rows in order, ids 1, 2, …; a capital letter ("N", "D") is a ticked set of that type. */
    private fun rows(vararg types: String): List<SetEntity> = types.mapIndexed { index, type ->
        val ticked = mapOf("W" to w, "N" to n, "F" to f, "D" to d)[type]
        SetEntity(
            id = index + 1L, workoutExerciseId = 1, position = index, type = ticked ?: type,
            completedAt = if (ticked != null) 1_000L + index else null,
        )
    }

    // --- How many a deload keeps (DL-1) ---

    @Test
    fun `half the planned work sets, rounded up, at least one`() {
        assertEquals(listOf(1, 1, 2, 2, 3, 5), listOf(1, 2, 3, 4, 5, 10).map { deloadKeep(it, DEFAULT_DELOAD_KEEP_PCT) })
        assertEquals(listOf(1, 1, 2, 3), listOf(1, 3, 4, 10).map { deloadKeep(it, 30) })
        assertEquals(listOf(1, 3, 3, 7), listOf(1, 3, 4, 10).map { deloadKeep(it, 70) })
        assertEquals(1, deloadKeep(0, DEFAULT_DELOAD_KEEP_PCT), "never below one")
    }

    @Test
    fun `the share steps by ten within 30 and 70 percent`() {
        assertEquals(60, stepDeloadKeep(50, +1))
        assertEquals(70, stepDeloadKeep(70, +1))
        assertEquals(30, stepDeloadKeep(30, -1))
        assertEquals(30, stepDeloadKeep(40, -3))
    }

    // --- What a switch does to a slot's rows (DL-2) ---

    @Test
    fun `switching on deletes the work sets past the kept ones, warm-ups stay`() {
        assertEquals(DeloadChange(deleteIds = listOf(5, 6)), deloadRows(rows(w, w, n, n, n, n), targetSets = 4, keep = 2, on = true))
        assertEquals(DeloadChange(deleteIds = listOf(3)), deloadRows(rows(n, f, n), targetSets = 3, keep = 2, on = true), "a set to failure is a work set")
        assertEquals(DeloadChange(), deloadRows(rows(w, n), targetSets = 1, keep = 1, on = true))
    }

    @Test
    fun `a ticked set is never deleted, mid-workout`() {
        // Three done of four, keep two: only the fourth goes.
        assertEquals(DeloadChange(deleteIds = listOf(4)), deloadRows(rows("N", "N", "N", n), targetSets = 4, keep = 2, on = true))
        // The first skipped, the second done: the done one and the first left to do make two, so 3 and 4 go.
        assertEquals(DeloadChange(deleteIds = listOf(3, 4)), deloadRows(rows(n, "N", n, n), targetSets = 4, keep = 2, on = true))
        // Two done after a skipped first: that is the deload's two, so the skipped one goes too (review R1-2).
        assertEquals(DeloadChange(deleteIds = listOf(1, 4)), deloadRows(rows(n, "N", "N", n), targetSets = 4, keep = 2, on = true))
        assertEquals(DeloadChange(), deloadRows(rows("N", "N", "N"), targetSets = 3, keep = 2, on = true), "all done: nothing to take")
    }

    @Test
    fun `a drop goes with its work set and never counts as a kept one`() {
        // Keep one: the first set and its drop stay; the second and third go with their drops.
        assertEquals(DeloadChange(deleteIds = listOf(3, 4, 5, 6)), deloadRows(rows(n, d, n, d, n, d), targetSets = 3, keep = 1, on = true))
        // Two drops after the first set don't take the second set's place.
        assertEquals(DeloadChange(deleteIds = listOf(4)), deloadRows(rows(n, d, d, n), targetSets = 2, keep = 1, on = true))
        // A set whose drop is ticked is done: it stays with its drop, and the other is the one that goes (review R2-1).
        assertEquals(DeloadChange(deleteIds = listOf(1)), deloadRows(rows(n, n, "D"), targetSets = 2, keep = 1, on = true))
        assertEquals(DeloadChange(), deloadRows(rows(n, "D", "N", "N"), targetSets = 3, keep = 2, on = true), "three done: none goes")
    }

    @Test
    fun `switching off adds plain rows back up to the planned work sets`() {
        assertEquals(DeloadChange(addRows = 2), deloadRows(rows(w, n, n), targetSets = 4, keep = 2, on = false))
        assertEquals(DeloadChange(addRows = 1), deloadRows(rows(n, d), targetSets = 2, keep = 1, on = false), "a drop is no work set")
        assertEquals(DeloadChange(), deloadRows(rows(n, n, n), targetSets = 3, keep = 2, on = false))
        assertEquals(DeloadChange(), deloadRows(rows(n, n, n, n), targetSets = 3, keep = 2, on = false), "one he added stays")
    }

    @Test
    fun `on then off gives the slot its planned work sets again`() {
        val start = rows(w, n, n, n, n)
        val on = deloadRows(start, targetSets = 4, keep = deloadKeep(4, DEFAULT_DELOAD_KEEP_PCT), on = true)
        val trimmed = start.filterNot { it.id in on.deleteIds }
        assertEquals(listOf(w, n, n), trimmed.map { it.type })
        val off = deloadRows(trimmed, targetSets = 4, keep = 2, on = false)
        assertEquals(2, off.addRows)
    }

    @Test
    fun `a deload's Start Again cuts each exercise to the deload's number of its plan`() {
        assertEquals(listOf(w, n, n), PlannedSlot("squat", listOf(w, n, n, n), targetSets = 3).deloadTrimmed(50).types)
        val trimmed = PlannedSlot("squat", listOf(n, n), targetSets = 4)
        assertEquals(trimmed, trimmed.deloadTrimmed(50), "a copy of one switched on that day: already so")
        val noPlan = PlannedSlot("curl", listOf(n, n, n))
        assertEquals(noPlan, noPlan.deloadTrimmed(50))
        assertEquals(listOf(n), PlannedSlot("row", listOf(n, n, n), targetSets = 3).deloadTrimmed(30).types)
    }

    // --- The RIR it shows (DL-3) ---

    @Test
    fun `a deload shows its own RIR, the slot's target otherwise`() {
        assertEquals(DEFAULT_DELOAD_RIR, shownRir(RirTarget(1, 2), deload = true, deloadRir = DEFAULT_DELOAD_RIR))
        assertEquals(DEFAULT_DELOAD_RIR, shownRir(null, deload = true, deloadRir = DEFAULT_DELOAD_RIR), "a slot without a target too")
        assertEquals(RirTarget(1, 2), shownRir(RirTarget(1, 2), deload = false, deloadRir = DEFAULT_DELOAD_RIR))
        assertEquals("4–5", DEFAULT_DELOAD_RIR.text)
        // Its first tap on the RIR cell is 5, then 4.
        assertEquals(5, nextRir(null, DEFAULT_DELOAD_RIR.top))
        assertEquals(4, nextRir(5, DEFAULT_DELOAD_RIR.top))
    }
}
