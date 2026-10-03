package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Moving between exercises: the next-exercise row, auto-advance, and the Rest Timer agreeing with both. */
class ExerciseOrderTest {

    // Squat (1), Bench (2), Row (3), Curl (4), in that order.
    private val slots = listOf(slot(1, 0), slot(2, 1), slot(3, 2), slot(4, 3))

    @Test
    fun `the next-exercise row skips finished exercises and wraps round`() {
        val sets = listOf(row(11, 1), row(21, 2, done = true), row(31, 3), row(41, 4))
        assertEquals(3L, nextExerciseSlotId(slots, sets, currentSlotId = 1), "Bench is done")
        assertEquals(1L, nextExerciseSlotId(slots, sets, currentSlotId = 4), "from the last, round to the first")
    }

    @Test
    fun `with every other exercise finished, the row still offers the one after`() {
        val sets = listOf(row(11, 1), row(21, 2, done = true), row(31, 3, done = true), row(41, 4, done = true))
        assertEquals(2L, nextExerciseSlotId(slots, sets, currentSlotId = 1))
    }

    @Test
    fun `a workout with one exercise has no next`() {
        assertNull(nextExerciseSlotId(listOf(slot(1, 0)), listOf(row(11, 1)), currentSlotId = 1))
    }

    @Test
    fun `it moves on only once every row of the exercise on screen is ticked`() {
        val midway = listOf(row(11, 1, done = true), row(12, 1), row(21, 2))
        assertNull(autoAdvanceSlotId(slots, midway, currentSlotId = 1))
        val done = midway.map { if (it.id == 12L) it.copy(completedAt = 9L) else it }
        assertEquals(2L, autoAdvanceSlotId(slots, done, currentSlotId = 1))
    }

    @Test
    fun `it skips finished exercises, and stays when everything is done`() {
        val sets = listOf(row(11, 1, done = true), row(21, 2, done = true), row(41, 4))
        assertEquals(4L, autoAdvanceSlotId(slots, sets, currentSlotId = 1), "Row has no rows, Bench is done")
        assertNull(autoAdvanceSlotId(slots, sets.map { it.copy(completedAt = 9L) }, currentSlotId = 1))
    }

    @Test
    fun `an exercise with every row removed is not done`() {
        assertNull(autoAdvanceSlotId(slots, listOf(row(21, 2)), currentSlotId = 1))
    }

    @Test
    fun `the Rest Timer's NEXT names the exercise it moves on to, wherever the last tick was`() {
        // Squat finished on screen, but the latest tick was a Curl set: NEXT used to follow the Curl.
        val sets = listOf(
            row(11, 1, done = true, at = 5L),
            row(21, 2),
            row(41, 4, done = true, at = 9L),
            row(42, 4),
        )
        val next = restNextContext(slots, sets, currentSlotId = 1) as RestNext.Next
        assertEquals(2L, next.slot.id)
        assertEquals(autoAdvanceSlotId(slots, sets, currentSlotId = 1), next.slot.id)
    }

    @Test
    fun `the Rest Timer's NEXT names the row the steppers are on, not a warm-up he skipped`() {
        // Squat: W1 ticked, W2 skipped, set 1 just ticked; the steppers are on set 2.
        val sets = listOf(
            row(11, 1, done = true, at = 10L), row(12, 1), row(13, 1, done = true, at = 20L), row(14, 1),
            row(21, 2),
        )
        val next = restNextContext(slots, sets, currentSlotId = 1) as RestNext.Next
        assertEquals(14L, next.rows[next.index].id, "the row after his last tick")
        // He tapped W2 to do it now: NEXT follows the steppers.
        val chosen = restNextContext(slots, sets, currentSlotId = 1, activeSetId = 12) as RestNext.Next
        assertEquals(12L, chosen.rows[chosen.index].id)
        // An active row that is ticked (or gone) says nothing: the rule again.
        val stale = restNextContext(slots, sets, currentSlotId = 1, activeSetId = 13) as RestNext.Next
        assertEquals(14L, stale.rows[stale.index].id)
    }

    @Test
    fun `it moves on as the rest ends, or two seconds after the tick with none running`() {
        val tick = 1_000_000L
        assertEquals(tick + 90_000, autoAdvanceAt(tick, restEndMillis = tick + 90_000))
        assertEquals(tick + AUTO_ADVANCE_NO_REST_MS, autoAdvanceAt(tick, restEndMillis = null), "a 0 s slot, or Skip")
        assertEquals(tick + AUTO_ADVANCE_NO_REST_MS, autoAdvanceAt(tick, restEndMillis = tick - 30_000), "an older rest, over")
    }

    @Test
    fun `a tap in the second after the screen moves on is taken as meant for the old one`() {
        val moved = 5_000_000L
        assertEquals(true, tapTooSoonAfterMove(moved, moved))
        assertEquals(true, tapTooSoonAfterMove(moved, moved + 999))
        assertEquals(false, tapTooSoonAfterMove(moved, moved + 1_000))
        assertEquals(false, tapTooSoonAfterMove(null, moved), "never moved on by itself")
        assertEquals(false, tapTooSoonAfterMove(moved, moved - 1), "clock set back")
    }

    @Test
    fun `after a tick the steppers go to the next unticked row, not back to one he skipped`() {
        // W1 ticked, W2 skipped, sets 1-3: set 1 just ticked.
        val rows = listOf(row(11, 1, done = true), row(12, 1), row(13, 1, done = true), row(14, 1), row(15, 1))
        assertEquals(14L, activeRowAfterTick(rows, tickedSetId = 13), "set 2, not the skipped W2")
        // Ticked out of order: the one after it.
        assertEquals(15L, activeRowAfterTick(rows.map { if (it.id == 14L) it.copy(completedAt = 9L) else it }, tickedSetId = 14))
    }

    @Test
    fun `after the last row's tick the steppers wrap to the first unticked, or none`() {
        val rows = listOf(row(11, 1), row(12, 1, done = true), row(13, 1, done = true))
        assertEquals(11L, activeRowAfterTick(rows, tickedSetId = 13), "nothing after it: back to the skipped one")
        assertNull(activeRowAfterTick(rows.map { it.copy(completedAt = 9L) }, tickedSetId = 13), "all done: the move to the next exercise")
    }

    @Test
    fun `an exercise opens on the row after his last tick, not on a warm-up he skipped`() {
        // W1 ticked, W2 skipped, sets 1 and 2 ticked: Resume or a jump back lands on set 3.
        val rows = listOf(
            row(11, 1, done = true, at = 10L), row(12, 1), row(13, 1, done = true, at = 20L),
            row(14, 1, done = true, at = 30L), row(15, 1),
        )
        assertEquals(15L, nextRowOf(rows))
        // Set 3 ticked before set 1: after the last tick in time (set 1), so set 2, not round to W2.
        val outOfOrder = listOf(
            row(11, 1, done = true, at = 10L), row(12, 1), row(13, 1, done = true, at = 30L),
            row(14, 1), row(15, 1, done = true, at = 20L),
        )
        assertEquals(14L, nextRowOf(outOfOrder))
        // Nothing ticked yet: the first row; everything ticked: none.
        assertEquals(11L, nextRowOf(rows.map { it.copy(completedAt = null) }))
        assertNull(nextRowOf(rows.map { it.copy(completedAt = 9L) }))
    }

    private fun slot(id: Long, position: Int, group: Int? = null) =
        WorkoutExerciseEntity(id = id, workoutId = 1, exerciseId = "ex$id", position = position, supersetGroup = group)

    // Squat (1), the pair Pushdown (2) and Curl (3), then Calf Raise (4).
    private val withPair = listOf(slot(1, 0), slot(2, 1, group = 1), slot(3, 2, group = 1), slot(4, 3))

    @Test
    fun `in a superset the next-exercise row names whose turn is next, then the exercise after the pair`() {
        val fresh = listOf(row(11, 1), row(21, 2), row(22, 2), row(31, 3), row(32, 3), row(41, 4))
        assertEquals(2L, nextExerciseSlotId(withPair, fresh, currentSlotId = 1), "before the pair, as before")
        assertEquals(3L, nextExerciseSlotId(withPair, fresh, currentSlotId = 2), "on Pushdown: Curl")
        assertEquals(2L, nextExerciseSlotId(withPair, fresh, currentSlotId = 3), "on Curl: back to Pushdown")
        // Curl finished, Pushdown has a set left: from Pushdown the row goes past the pair.
        val curlDone = fresh.map { if (it.workoutExerciseId == 3L) it.copy(completedAt = 9L) else it }
        assertEquals(4L, nextExerciseSlotId(withPair, curlDone, currentSlotId = 2))
        // Everything but Pushdown finished: the row still moves on, to the slot after it.
        val restDone = fresh.map { if (it.workoutExerciseId != 2L) it.copy(completedAt = 9L) else it }
        assertEquals(3L, nextExerciseSlotId(withPair, restDone, currentSlotId = 2))
    }

    @Test
    fun `in a superset the Rest Timer's NEXT names the set whose turn it is once this member's is done`() {
        // A1 then B1 ticked, B on screen with B2 under the steppers: the rest leads back to A2.
        val afterB1 = listOf(
            row(21, 2, done = true, at = 10L), row(22, 2), row(23, 2),
            row(31, 3, done = true, at = 20L), row(32, 3), row(33, 3), row(41, 4),
        )
        val back = restNextContext(withPair, afterB1, currentSlotId = 3, activeSetId = 32) as RestNext.Next
        assertEquals(2L to 22L, back.slot.id to back.rows[back.index].id)
        // A2 ticked, still on A: NEXT is B2, where the screen goes in 2 s.
        val afterA2 = afterB1.map { if (it.id == 22L) it.copy(completedAt = 30L) else it }
        val over = restNextContext(withPair, afterA2, currentSlotId = 2, activeSetId = 23) as RestNext.Next
        assertEquals(3L to 32L, over.slot.id to over.rows[over.index].id)
        // Moved to B and B2 not done yet: B2, as on any exercise.
        val onB = restNextContext(withPair, afterA2, currentSlotId = 3, activeSetId = 32) as RestNext.Next
        assertEquals(3L to 32L, onB.slot.id to onB.rows[onB.index].id)
        // The last round closed on B: the exercise after the pair.
        val pairDone = afterA2.map { if (it.workoutExerciseId in 2L..3L) it.copy(completedAt = it.completedAt ?: 40L) else it }
        val after = restNextContext(withPair, pairDone, currentSlotId = 3, activeSetId = null) as RestNext.Next
        assertEquals(4L, after.slot.id)
    }

    @Test
    fun `in a superset a warm-up tick keeps the Rest Timer's NEXT on the exercise on screen, as the screen stays`() {
        // A's warm-up ticked: the warm-up rest runs and A's set 1 is next, not B's.
        val warmedUp = listOf(
            row(20, 2, done = true, at = 10L).copy(type = SET_TYPE_WARMUP), row(21, 2), row(22, 2),
            row(30, 3).copy(type = SET_TYPE_WARMUP), row(31, 3), row(32, 3), row(41, 4),
        )
        val onA = restNextContext(withPair, warmedUp, currentSlotId = 2, activeSetId = 21) as RestNext.Next
        assertEquals(2L to 21L, onA.slot.id to onA.rows[onA.index].id)
        // A1 done, moved to B, B's warm-up ticked: B's set 1, not A's set 2.
        val bWarmedUp = warmedUp.map {
            when (it.id) {
                21L -> it.copy(completedAt = 20L)
                30L -> it.copy(completedAt = 30L)
                else -> it
            }
        }
        val onB = restNextContext(withPair, bWarmedUp, currentSlotId = 3, activeSetId = 31) as RestNext.Next
        assertEquals(3L to 31L, onB.slot.id to onB.rows[onB.index].id)
    }

    private fun row(id: Long, slot: Long, done: Boolean = false, at: Long = 5L) = SetEntity(
        id = id,
        workoutExerciseId = slot,
        position = id.toInt() % 10,
        type = SET_TYPE_NORMAL,
        completedAt = if (done) at else null,
    )

    @Test
    fun `a checkbox tap right after the last one is a double tap`() {
        assertTrue(isDoubleTap(firstTapAt = 10_000, now = 10_000))
        assertTrue(isDoubleTap(firstTapAt = 10_000, now = 10_599))
        assertFalse(isDoubleTap(firstTapAt = 10_000, now = 10_600))
        // A first tap stamped later than this one (clock moved back) is not a double tap.
        assertFalse(isDoubleTap(firstTapAt = 10_000, now = 9_000))
    }

    @Test
    fun `a two-state row takes one tap of a double tap, and a deliberate one after it`() {
        val units = BounceGuard()
        assertTrue(units.take(now = 10_000), "the tap")
        assertFalse(units.take(now = 10_150), "its bounce would flip it straight back")
        assertFalse(units.take(now = 10_599), "still within the first tap's window")
        assertTrue(units.take(now = 10_600), "a deliberate second tap")
        assertFalse(units.take(now = 10_700), "measured from the last tap taken")
    }
}
