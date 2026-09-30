package com.tczerwien.lifts.export

import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An exercise done one side at a time (B10, #154): its reps count one side in every file, so a split
 * squat's "10" is never read as 5 a leg, and its sets stay one row each (not doubled). And a template's
 * target copied onto the workout (#054) reaches the files beside it.
 */
class PerSideExportTest {

    private fun exercise(id: String, name: String, perSide: Boolean) = ExerciseEntity(
        id = id, name = name, equipment = "dumbbell", primaryMuscles = listOf("quadriceps"), secondaryMuscles = emptyList(),
        category = "strength", level = null, instructions = emptyList(), perSide = perSide,
    )

    private val splitSquat = exercise("split-squat-with-dumbbells", "Split Squat with Dumbbells", perSide = true)
    private val gobletSquat = exercise("goblet-squat", "Goblet Squat", perSide = false)

    private val workout = WorkoutEntity(id = 1, name = "Legs", startedAt = at("2026-07-13T10:00:00Z"), endedAt = at("2026-07-13T11:00:00Z"))

    private val slots = listOf(
        WorkoutExerciseEntity(id = 1, workoutId = 1, exerciseId = splitSquat.id, position = 0, notes = "left knee fine"),
        WorkoutExerciseEntity(id = 2, workoutId = 1, exerciseId = gobletSquat.id, position = 1),
    )

    private fun set(id: Long, slot: Long, position: Int, lb: Double, reps: Int, at: String) = SetEntity(
        id = id, workoutExerciseId = slot, position = position, type = "normal",
        weightKg = Format.lbToKg(lb), reps = reps, completedAt = at(at),
    )

    private val snapshot = ExportFixtures.snapshot(
        exercises = listOf(splitSquat, gobletSquat),
        workouts = listOf(workout),
        slots = slots,
        sets = listOf(
            set(1, 1, 0, 25.0, 10, "2026-07-13T10:05:00Z"),
            set(2, 1, 1, 25.0, 10, "2026-07-13T10:08:00Z"),
            set(3, 2, 0, 50.0, 12, "2026-07-13T10:15:00Z"),
        ),
        unit = WeightUnit.LB,
    )

    @Test
    fun trainingLogWritesTheCountPerSide() {
        val markdown = MarkdownExport.render(snapshot)
        assertTrue(markdown.contains("  25x10/side — **PR**; 25x10/side r180\n"), markdown)
        assertTrue(markdown.contains("  50x12 r420 — **PR**\n"), "a two-sided lift reads as before")
        assertTrue(markdown.contains("`x10/side` is 10 reps on each side"), "the legend explains it")
        assertTrue(markdown.contains("- Bulgarian Split Squat: e1RM 33.5 lb (2026-07-13) — best set 25x10/side"), markdown)

    }

    @Test
    fun workoutsJsonMarksTheExercise() {
        val exercises = Json.parseToJsonElement(JsonExport.render(snapshot)).jsonObject.getValue("exercises").jsonArray
            .associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject }
        assertEquals("true", exercises.getValue(splitSquat.id)["per_side"]?.jsonPrimitive?.content)
        assertEquals(null, exercises.getValue(gobletSquat.id)["per_side"], "only written when true")
    }

    @Test
    fun strongCsvSaysSoInNotesAndKeepsOneRowPerSet() {
        val rows = CsvExport.render(snapshot).trimEnd().split("\n").drop(1)
        assertEquals(3, rows.size, "one row per set, not one per side")
        assertContains(rows[0], ",10,0,0,Per side. left knee fine,")
        assertContains(rows[2], ",12,0,0,,")
    }

    @Test
    fun aTemplatesTargetRidesAlongWithTheWorkout() {
        val targeted = ExportFixtures.snapshot(
            exercises = listOf(splitSquat, gobletSquat),
            workouts = listOf(workout),
            slots = listOf(slots[0].copy(targetRepMin = 10, targetRepMax = 12, targetRir = 2), slots[1]),
            sets = listOf(set(1, 1, 0, 25.0, 10, "2026-07-13T10:05:00Z"), set(3, 2, 0, 50.0, 12, "2026-07-13T10:15:00Z")),
            unit = WeightUnit.LB,
        )
        val markdown = MarkdownExport.render(targeted)
        assertTrue(markdown.contains("- **Bulgarian Split Squat** [quadriceps] (target 10–12 reps/side, RIR 2)\n"), markdown)
        assertTrue(markdown.contains("- **Goblet Squat** [quadriceps]\n"), "no target, no tag")
        val exercises = Json.parseToJsonElement(JsonExport.render(targeted)).jsonObject.getValue("workouts").jsonArray
            .single().jsonObject.getValue("exercises").jsonArray.map { it.jsonObject }
        assertEquals(listOf("10", "12", "2"), listOf("rep_min", "rep_max", "target_rir").map { exercises[0].getValue(it).jsonPrimitive.content })
        assertEquals(null, exercises[1]["rep_min"], "absent without one")
    }

    @Test
    fun aHoldLeavesOutARangeSetBeforeItsTypeChanged() {
        val plank = exercise("plank", "Plank", perSide = false).copy(trackingType = "duration")
        val held = ExportFixtures.snapshot(
            exercises = listOf(plank),
            workouts = listOf(workout),
            slots = listOf(WorkoutExerciseEntity(id = 1, workoutId = 1, exerciseId = "plank", position = 0, targetRepMin = 30, targetRepMax = 60, targetRir = 1)),
            sets = listOf(set(1, 1, 0, 0.0, 0, "2026-07-13T10:05:00Z").copy(weightKg = null, reps = null, durationSec = 45)),
            unit = WeightUnit.LB,
        )
        assertTrue(MarkdownExport.render(held).contains("- **Plank** [quadriceps]\n"), "no target tag on a hold")
        val exercise = Json.parseToJsonElement(JsonExport.render(held)).jsonObject.getValue("workouts").jsonArray
            .single().jsonObject.getValue("exercises").jsonArray.single().jsonObject
        assertEquals(null, exercise["rep_min"])
        assertEquals(null, exercise["target_rir"])
    }

    private fun at(iso: String): Long = ExportFixtures.millis(iso)
}
