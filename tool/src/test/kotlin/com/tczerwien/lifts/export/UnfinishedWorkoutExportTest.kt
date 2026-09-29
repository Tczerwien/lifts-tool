package com.tczerwien.lifts.export

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A workout still open (being logged, or left open and never closed) is in no file (#067): no heading,
 * no rows, no PR, no weekly sets, even when its ticked sets would beat every record.
 */
class UnfinishedWorkoutExportTest {

    /** Workout 3, started after workout 2 and never finished, with one ticked set heavier than anything. */
    private val open = WorkoutEntity(id = 3, name = "Still going", startedAt = ExportFixtures.millis("2026-07-14T10:00:00Z"))

    private val withOpen = ExportFixtures.snapshot(
        workouts = listOf(ExportFixtures.workout1, ExportFixtures.workout2, open),
        slots = ExportFixtures.slots + WorkoutExerciseEntity(
            id = 3, workoutId = 3, exerciseId = ExportFixtures.bench.id, position = 0,
        ),
        sets = ExportFixtures.sets + SetEntity(
            id = 7, workoutExerciseId = 3, position = 0, type = "normal",
            weightKg = 140.0, reps = 5, completedAt = ExportFixtures.millis("2026-07-14T10:10:00Z"),
        ),
    )

    @Test
    fun anOpenWorkoutChangesNothingInTheSnapshot() {
        assertEquals(ExportFixtures.snapshot(), withOpen)
    }

    @Test
    fun anOpenWorkoutChangesNoFile() {
        val base = ExportFixtures.snapshot()
        assertEquals(MarkdownExport.render(base), MarkdownExport.render(withOpen))
        assertEquals(JsonExport.render(base), JsonExport.render(withOpen))
        assertEquals(CsvExport.render(base), CsvExport.render(withOpen))
    }
}
