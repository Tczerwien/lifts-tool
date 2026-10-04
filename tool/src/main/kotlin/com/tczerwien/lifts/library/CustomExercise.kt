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
     * A workout has it (logged, skipped, the open one, or as one of a slot's choices): it stays, so that
     * history keeps its name and numbers and no workout names an exercise that isn't there, but leaves the
     * picker (and every swap). A new exercise given its name offers it back ("Restore").
     */
    HIDE,
}

/** [Removal] for a custom exercise some workout refers to ([inWorkouts]) or none. */
fun removalFor(inWorkouts: Boolean): Removal = if (inWorkouts) Removal.HIDE else Removal.DELETE

/** Taking an exercise out of templates: the rows to delete, and the rows left as they must now be stored. */
data class TemplateRemoval(val deleteIds: List<Long>, val updates: List<TemplateExerciseEntity>)

/**
 * [exerciseId] out of the templates [rows] come from (every row of each, any order; ALT-6): it leaves
 * every exercise's alternatives, and an exercise it is goes, unless that one has an alternative left that
 * is [usable] (in the log and in the picker): then the first such takes its place as the main, the others
 * staying its alternatives in order, so a slot with a fallback keeps its place (and its superset). Each
 * template's other exercises close up to positions 0..n-1 in the order they had, a superset it leaves with
 * one exercise becoming plain ([settledTemplateRows]). A template left with nothing stays, empty. Only rows
 * that change are in [TemplateRemoval.updates].
 */
fun withoutExercise(
    rows: List<TemplateExerciseEntity>,
    exerciseId: String,
    usable: (String) -> Boolean = { true },
): TemplateRemoval {
    val deleteIds = mutableListOf<Long>()
    val kept = rows.mapNotNull { row ->
        val others = row.alternatives.filter { it != exerciseId }
        val promoted = if (row.exerciseId == exerciseId) others.firstOrNull(usable) else null
        when {
            row.exerciseId != exerciseId -> row.copy(alternatives = others)
            promoted == null -> null.also { deleteIds += row.id }
            else -> row.copy(exerciseId = promoted, alternatives = others - promoted)
        }
    }
    val before = rows.associateBy { it.id }
    val updates = kept.groupBy { it.templateId }.values.flatMap { template ->
        val ordered = template.sortedWith(compareBy({ it.position }, { it.id }))
        val settled = settledTemplateRows(ordered).associateBy { it.id }
        ordered.map { settled[it.id] ?: it }.filter { it != before[it.id] }
    }
    return TemplateRemoval(deleteIds, updates)
}
