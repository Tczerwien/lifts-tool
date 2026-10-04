package com.tczerwien.lifts.library

import com.tczerwien.lifts.data.TemplateExerciseEntity
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
    fun `an exercise a workout has is hidden, one only templates ask for is deleted`() {
        assertEquals(Removal.HIDE, removalFor(inWorkouts = true))
        assertEquals(Removal.DELETE, removalFor(inWorkouts = false))
    }

    private fun row(id: Long, templateId: Long, exerciseId: String, position: Int) =
        TemplateExerciseEntity(id = id, templateId = templateId, exerciseId = exerciseId, position = position, targetSets = 3)

    @Test
    fun `a removed exercise leaves every template and the rest close up`() {
        val rows = listOf(
            row(1, templateId = 1, "bench", 0),
            row(2, templateId = 1, "y-raise", 1),
            row(3, templateId = 1, "curl", 2),
            row(4, templateId = 1, "y-raise", 3),
            row(5, templateId = 1, "pushdown", 4),
            row(6, templateId = 2, "y-raise", 0),
            row(7, templateId = 2, "squat", 1),
            row(8, templateId = 3, "y-raise", 0),
        )
        val out = withoutExercise(rows.shuffled(kotlin.random.Random(3)), "y-raise")
        assertEquals(listOf(2L, 4L, 6L, 8L), out.deleteIds.sorted(), "twice in one template: both rows go")
        val moved = out.updates.associate { it.id to it.position }
        assertEquals(mapOf(3L to 1, 5L to 2, 7L to 0), moved, "positions 0..n-1 in each template, in the order they had")
        assertEquals(null, moved[1L], "a row already in place isn't written")
    }

    @Test
    fun `a removed exercise's superset partner is left plain, and another superset whole`() {
        val rows = listOf(
            row(1, templateId = 1, "y-raise", 0).copy(supersetGroup = 1),
            row(2, templateId = 1, "curl", 1).copy(supersetGroup = 1),
            row(3, templateId = 1, "dip", 2).copy(supersetGroup = 2),
            row(4, templateId = 1, "fly", 3).copy(supersetGroup = 2),
        )
        val out = withoutExercise(rows, "y-raise")
        assertEquals(
            listOf(Triple(2L, 0, null), Triple(3L, 1, 2), Triple(4L, 2, 2)),
            out.updates.map { Triple(it.id, it.position, it.supersetGroup) },
        )
    }

    @Test
    fun `a removed exercise leaves every alternatives list, and a slot it was the main of keeps its place`() {
        val rows = listOf(
            row(1, templateId = 1, "squat", 0).copy(alternatives = listOf("y-raise", "press")),
            row(2, templateId = 1, "y-raise", 1).copy(alternatives = listOf("curl", "fly"), supersetGroup = 4),
            row(3, templateId = 1, "dip", 2).copy(supersetGroup = 4),
            row(4, templateId = 2, "y-raise", 0).copy(alternatives = listOf("y-raise")),
            row(5, templateId = 2, "row", 1),
        )
        val out = withoutExercise(rows, "y-raise")
        assertEquals(listOf(4L), out.deleteIds, "the main with no other alternative goes")
        assertEquals(
            listOf(
                row(1, templateId = 1, "squat", 0).copy(alternatives = listOf("press")),
                // Its first alternative is the main now, the rest its alternatives, in the superset as before.
                row(2, templateId = 1, "curl", 1).copy(alternatives = listOf("fly"), supersetGroup = 4),
                row(5, templateId = 2, "row", 0),
            ),
            out.updates,
        )
    }

    @Test
    fun `an exercise no template asks for changes no template`() {
        val rows = listOf(row(1, templateId = 1, "bench", 0).copy(alternatives = listOf("press")), row(2, templateId = 1, "curl", 1))
        assertEquals(TemplateRemoval(emptyList(), emptyList()), withoutExercise(rows, "y-raise"))
    }
}
