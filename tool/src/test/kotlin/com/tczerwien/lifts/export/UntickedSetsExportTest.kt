package com.tczerwien.lifts.export

import com.tczerwien.lifts.data.SetEntity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A finished workout keeps its unticked sets (so RESUME on Workout Complete can carry on), and
 * none of them may reach an export: no rows, no volume, no PR, no muscle-week credit.
 */
class UntickedSetsExportTest {

    /** Workout 2 as finished with two rows never ticked, one of them heavier than anything logged. */
    private val withUnticked = ExportFixtures.sets + listOf(
        SetEntity(id = 7, workoutExerciseId = 2, position = 3, type = "normal", weightKg = 140.0, reps = 5),
        SetEntity(id = 8, workoutExerciseId = 2, position = 4, type = "normal"),
    )

    @Test
    fun untickedSetsChangeNothingInTheSnapshot() {
        assertEquals(ExportFixtures.snapshot(), ExportFixtures.snapshot(sets = withUnticked))
    }

    @Test
    fun untickedSetsChangeNoRenderedExport() {
        val base = ExportFixtures.snapshot()
        val unticked = ExportFixtures.snapshot(sets = withUnticked)
        assertEquals(CsvExport.render(base), CsvExport.render(unticked))
        assertEquals(JsonExport.render(base), JsonExport.render(unticked))
        assertEquals(MarkdownExport.render(base), MarkdownExport.render(unticked))
    }
}
