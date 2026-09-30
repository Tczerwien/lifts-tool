package com.tczerwien.lifts.library

import com.tczerwien.lifts.data.Tracking
import com.tczerwien.lifts.seed.SeedParser

/**
 * Rules for the exercises he makes himself: how a new one is measured until he says otherwise, and
 * what removing one does. Pure, covered by CustomExerciseTest.
 */

/**
 * How a custom exercise is measured when he hasn't picked a Type: as the catalog would measure a lift
 * of that [category] and [equipment] ([SeedParser.defaultTrackingType]): stretches and cardio are
 * timed, "body only" moves are reps only, anything else weight × reps.
 */
fun customTrackingDefault(category: String, equipment: String?): Tracking =
    Tracking.of(SeedParser.defaultTrackingType(slug = "", category = category, equipment = equipment))

/** The New / Edit Exercise form's Type: his pick ([chosen]) once made, until then the default for what the form says. */
fun formTracking(chosen: Tracking?, category: String, equipment: String?): Tracking =
    chosen ?: customTrackingDefault(category, equipment)

/** What removing a custom exercise does. */
enum class Removal {
    /** Nothing refers to it: it goes. */
    DELETE,

    /**
     * A workout or template uses it: it stays, so that history keeps its name and numbers, but leaves
     * the picker. A new exercise given its name offers it back ("Restore").
     */
    HIDE,
}

/** [Removal] for a custom exercise a workout or template refers to ([inUse]) or not. */
fun removalFor(inUse: Boolean): Removal = if (inUse) Removal.HIDE else Removal.DELETE
