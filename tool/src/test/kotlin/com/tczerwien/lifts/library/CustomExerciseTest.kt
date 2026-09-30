package com.tczerwien.lifts.library

import com.tczerwien.lifts.data.Tracking
import kotlin.test.Test
import kotlin.test.assertEquals

class CustomExerciseTest {

    @Test
    fun `a new exercise is measured as the catalog would measure its kind`() {
        assertEquals(Tracking.WEIGHT_REPS, customTrackingDefault("strength", "dumbbell"))
        assertEquals(Tracking.WEIGHT_REPS, customTrackingDefault("strength", null))
        assertEquals(Tracking.REPS_ONLY, customTrackingDefault("strength", "body only"))
        assertEquals(Tracking.DURATION, customTrackingDefault("stretching", "body only"))
        assertEquals(Tracking.DURATION, customTrackingDefault("cardio", "machine"))
    }

    @Test
    fun `his pick of Type stands whatever the form says after`() {
        assertEquals(Tracking.REPS_ONLY, formTracking(null, "strength", "body only"))
        assertEquals(Tracking.WEIGHTED_BODYWEIGHT, formTracking(Tracking.WEIGHTED_BODYWEIGHT, "strength", "body only"))
        assertEquals(Tracking.WEIGHT_REPS, formTracking(Tracking.WEIGHT_REPS, "stretching", null))
    }

    @Test
    fun `an exercise in use is hidden, one nothing uses is deleted`() {
        assertEquals(Removal.HIDE, removalFor(inUse = true))
        assertEquals(Removal.DELETE, removalFor(inUse = false))
    }
}
