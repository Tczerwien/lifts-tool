package com.tczerwien.lifts.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The held stepper's pace ([holdSchedule]). */
class StepperTest {

    /** How long a hold from the press takes to move [steps] single steps, in ms. */
    private fun holdMillisFor(steps: Int, bigSteps: Boolean): Long {
        var moved = 0
        var at = HOLD_INITIAL_DELAY_MS
        var repeat = 0
        while (true) {
            val hold = holdSchedule(repeat++, bigSteps)
            moved += hold.steps
            if (moved >= steps) return at
            at += hold.delayMillis
        }
    }

    @Test
    fun `a hold repeats four a second for about a second and a half, then twelve a second`() {
        (0 until HOLD_SLOW_REPEATS).forEach { assertEquals(250L, holdSchedule(it).delayMillis) }
        assertEquals(80L, holdSchedule(HOLD_SLOW_REPEATS).delayMillis)
        assertEquals(80L, holdSchedule(40).delayMillis)
        // The fast pace starts 400 + 5 x 250 ms after the press.
        assertEquals(1_650L, HOLD_INITIAL_DELAY_MS + HOLD_SLOW_REPEATS * HOLD_SLOW_INTERVAL_MS)
    }

    @Test
    fun `only a stepper that allows it moves five steps at a time, and only late in a hold`() {
        (0 until HOLD_BIG_STEPS_FROM).forEach { assertEquals(1, holdSchedule(it, bigSteps = true).steps) }
        assertEquals(5, holdSchedule(HOLD_BIG_STEPS_FROM, bigSteps = true).steps)
        assertEquals(1, holdSchedule(HOLD_BIG_STEPS_FROM).steps)
        assertEquals(1, holdSchedule(100, bigSteps = false).steps)
    }

    @Test
    fun `a first bodyweight of 185 from the 150 start takes seconds, not most of a minute`() {
        // 35 lb in 0.2 lb steps. At a steady 4 a second it took about 44 s.
        val millis = holdMillisFor(steps = 175, bigSteps = true)
        assertTrue(millis < 5_000, "took $millis ms")
    }

    @Test
    fun `0 to 315 lb takes under three seconds of holding`() {
        val millis = holdMillisFor(steps = 63, bigSteps = true)
        assertTrue(millis < 3_000, "took $millis ms")
    }

    @Test
    fun `reps never jump, but still speed up`() {
        // 20 reps one at a time: past the slow start, 12 a second.
        val millis = holdMillisFor(steps = 20, bigSteps = false)
        assertEquals(400L + 5 * 250 + 14 * 80, millis)
    }
}
