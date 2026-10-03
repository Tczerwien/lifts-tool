package com.tczerwien.lifts.library

import com.tczerwien.lifts.data.TemplateExerciseEntity
import com.tczerwien.lifts.data.Tracking
import com.tczerwien.lifts.seed.SeedParser
import com.tczerwien.lifts.session.settledTemplateRows

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

/**
 * What removing a custom exercise does, once it has left every template ([withoutExercise]; B10: a
 * template that still asked for an exercise he removed would be a trap).
 */
enum class Removal {
    /** No workout refers to it: it goes. */
    DELETE,

    /**
     * A workout has it (logged, skipped, or the open one): it stays, so that history keeps its name
     * and numbers, but leaves the picker. A new exercise given its name offers it back ("Restore").
     */
    HIDE,
}

/** [Removal] for a custom exercise some workout refers to ([inWorkouts]) or none. */
fun removalFor(inWorkouts: Boolean): Removal = if (inWorkouts) Removal.HIDE else Removal.DELETE

/** Taking an exercise out of templates: the rows to delete, and the rows left as they must now be stored. */
data class TemplateRemoval(val deleteIds: List<Long>, val updates: List<TemplateExerciseEntity>)

/**
 * [exerciseId] out of the templates [rows] come from (every row of each, any order): its rows go, and
 * each template's other exercises close up to positions 0..n-1 in the order they had, a superset it
 * leaves with one exercise becoming plain ([settledTemplateRows]). A template left with nothing stays, empty.
 */
fun withoutExercise(rows: List<TemplateExerciseEntity>, exerciseId: String): TemplateRemoval {
    val (removed, kept) = rows.partition { it.exerciseId == exerciseId }
    val updates = kept.groupBy { it.templateId }.values.flatMap { template ->
        settledTemplateRows(template.sortedWith(compareBy({ it.position }, { it.id })))
    }
    return TemplateRemoval(removed.map { it.id }, updates)
}
