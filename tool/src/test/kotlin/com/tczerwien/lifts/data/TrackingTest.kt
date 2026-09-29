package com.tczerwien.lifts.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackingTest {

    @Test
    fun `a type that hides logged numbers gives way to one that shows them`() {
        // A pull-up (seeded reps only) he logged with a belt.
        assertEquals(Tracking.WEIGHTED_BODYWEIGHT, fittedToHistory(Tracking.REPS_ONLY, loggedWeight = true, loggedReps = true, loggedDuration = false))
        // Something seeded as a hold that he logged as weight x reps.
        assertEquals(Tracking.WEIGHT_REPS, fittedToHistory(Tracking.DURATION, loggedWeight = true, loggedReps = true, loggedDuration = false))
        // A plank he logged as 60 "reps" before holds existed.
        assertEquals(Tracking.REPS_ONLY, fittedToHistory(Tracking.DURATION, loggedWeight = false, loggedReps = true, loggedDuration = false))
    }

    @Test
    fun `a type that shows what was logged stays`() {
        assertNull(fittedToHistory(Tracking.REPS_ONLY, loggedWeight = false, loggedReps = true, loggedDuration = false))
        assertNull(fittedToHistory(Tracking.DURATION, loggedWeight = false, loggedReps = true, loggedDuration = true), "holds logged as holds")
        assertNull(fittedToHistory(Tracking.WEIGHT_REPS, loggedWeight = true, loggedReps = true, loggedDuration = false))
        assertNull(fittedToHistory(Tracking.ASSISTED_BODYWEIGHT, loggedWeight = true, loggedReps = true, loggedDuration = false), "help logged as +40 is still help")
        assertNull(fittedToHistory(Tracking.WEIGHTED_BODYWEIGHT, loggedWeight = true, loggedReps = true, loggedDuration = false))
    }

    @Test
    fun `help is shown as its size and logged negative`() {
        val assisted = Tracking.ASSISTED_BODYWEIGHT
        assertEquals(18.0, assisted.shownWeight(-18.0))
        assertEquals(-18.0, assisted.storedWeight(18.0))
        assertEquals(-18.0, assisted.asLogged(18.0))
        assertEquals(0.0, assisted.storedWeight(0.0).let { 1 / it!! }.let { if (it > 0) 0.0 else -1.0 }, "none is +0, not -0")
        assertEquals(20.0, Tracking.WEIGHTED_BODYWEIGHT.asLogged(20.0))
        assertEquals(Tracking.WEIGHT_REPS, Tracking.of("something new"))
    }
}
