package com.tczerwien.lifts.export

import com.tczerwien.lifts.data.BodyweightEntity
import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.LiftsRows
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pounds and names as Lifts shows them (#021). He lifts in lb, so the weights are stored as
 * lb / 2.2046226218 (135 lb is 61.2349… kg): training-log.md and the CSVs read 135, workouts.json keeps
 * the exact kg. Exercise names are the ones on screen: a catalog lift's short name ("Bench Press"), a
 * name he gave it (B5's rename) or his custom exercise's name.
 */
class UnitsAndNamesExportTest {

    /** The catalog bench, not renamed: Lifts shows its short name. */
    private val bench = ExerciseEntity(
        id = "barbell-bench-press-medium-grip",
        name = "Barbell Bench Press - Medium Grip",
        equipment = "barbell",
        primaryMuscles = listOf("chest"),
        secondaryMuscles = listOf("triceps", "shoulders"),
        category = "strength",
        level = "beginner",
        instructions = emptyList(),
    )

    /** A catalog lift he renamed in Exercise Detail: the stored name is his. */
    private val incline = ExerciseEntity(
        id = "incline-dumbbell-press",
        name = "Low Incline DB Press",
        equipment = "dumbbell",
        primaryMuscles = listOf("chest"),
        secondaryMuscles = listOf("shoulders"),
        category = "strength",
        level = "beginner",
        instructions = emptyList(),
        pinnedNote = "30 degrees",
    )

    private val workout = WorkoutEntity(
        id = 1,
        name = "Upper A",
        startedAt = ExportFixtures.millis("2026-07-13T10:00:00Z"),
        endedAt = ExportFixtures.millis("2026-07-13T11:00:00Z"),
    )

    private val snapshot = buildSnapshot(
        rows = LiftsRows(
            exercises = listOf(bench, incline),
            workouts = listOf(workout),
            workoutExercises = listOf(
                WorkoutExerciseEntity(id = 1, workoutId = 1, exerciseId = bench.id, position = 0),
                WorkoutExerciseEntity(id = 2, workoutId = 1, exerciseId = incline.id, position = 1),
            ),
            sets = listOf(
                lbSet(id = 1, slot = 1, lb = 135.0, reps = 8, minute = 5),
                lbSet(id = 2, slot = 1, lb = 225.0, reps = 5, minute = 10),
                lbSet(id = 3, slot = 2, lb = 57.5, reps = 10, minute = 20),
            ),
            templates = emptyList(),
            templateExercises = emptyList(),
            bodyweight = listOf(
                BodyweightEntity(id = 1, epochDay = LocalDate.parse("2026-07-13").toEpochDay(), weightKg = Format.lbToKg(182.4)),
            ),
            bars = emptyList(),
        ),
        exportedAtMillis = ExportFixtures.millis("2026-07-14T12:00:00Z"),
        zone = ZoneOffset.UTC,
        unit = WeightUnit.LB,
        goals = null,
        injuries = emptyList(),
    )

    private fun lbSet(id: Long, slot: Long, lb: Double, reps: Int, minute: Int) = SetEntity(
        id = id,
        workoutExerciseId = slot,
        position = id.toInt(),
        type = "normal",
        weightKg = Format.lbToKg(lb),
        reps = reps,
        completedAt = ExportFixtures.millis("2026-07-13T10:%02d:00Z".format(minute)),
    )

    private val markdown = MarkdownExport.render(snapshot)
    private val lines = markdown.split("\n")

    @Test
    fun trainingLogIsInPounds() {
        assertContains(lines, "  weight: lb")
        assertTrue(lines.any { it.startsWith("  135x8") }, markdown)
        assertTrue("61.23" !in markdown, "no kg decimals may leak into the training log")
        // 225 x (1 + 5/30) = 262.5 lb.
        assertTrue(lines.any { it.startsWith("- Bench Press: e1RM 262.5 lb (2026-07-13) — best set 225x5") }, markdown)
        assertContains(lines, "- Current: 182.4 lb (2026-07-13)")
    }

    @Test
    fun csvsAreInPounds() {
        val rows = CsvExport.render(snapshot).trimEnd('\n').split("\n").drop(1)
        assertEquals(listOf("135", "225", "57.5"), rows.map { it.split(",")[5] })
        val bodyweight = CsvExport.renderBodyweight(snapshot)
        assertEquals("Date,Weight (lb)\n2026-07-13,182.4\n", bodyweight)
    }

    @Test
    fun jsonKeepsExactKilograms() {
        val document = Json.decodeFromString(JsonExportDocument.serializer(), JsonExport.render(snapshot))
        assertEquals(Format.lbToKg(135.0), document.workouts.single().exercises.first().sets.first().weightKg)
    }

    @Test
    fun namesAreTheOnesLiftsShows() {
        assertTrue(lines.any { it.startsWith("- **Bench Press** [") }, markdown)
        assertTrue(lines.any { it.startsWith("- **Low Incline DB Press** [") }, markdown)
        assertTrue("Medium Grip" !in markdown, "the catalog name is not what Lifts shows")
        val csvNames = CsvExport.render(snapshot).trimEnd('\n').split("\n").drop(1).map { it.split(",")[3] }
        assertEquals(listOf("Bench Press", "Bench Press", "Low Incline DB Press"), csvNames)
        val document = Json.decodeFromString(JsonExportDocument.serializer(), JsonExport.render(snapshot))
        assertEquals(listOf("Bench Press", "Low Incline DB Press"), document.exercises.map { it.name })
        assertEquals(listOf("Bench Press", "Low Incline DB Press"), document.summary.prs.map { it.name })
    }
}
