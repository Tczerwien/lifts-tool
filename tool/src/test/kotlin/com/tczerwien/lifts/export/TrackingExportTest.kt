package com.tczerwien.lifts.export

import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.Tracking
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
 * The exports by how each exercise is measured (B9): an assisted pull-up's help is a negative weight
 * everywhere, a weighted dip's load has no e1RM, a plank is seconds, and workouts.json names each
 * exercise's type in Hevy's words.
 */
class TrackingExportTest {

    private fun exercise(id: String, name: String, tracking: Tracking) = ExerciseEntity(
        id = id, name = name, equipment = null, primaryMuscles = listOf("lats"), secondaryMuscles = emptyList(),
        category = "strength", level = null, instructions = emptyList(), trackingType = tracking.stored,
    )

    private val pullup = exercise("assisted-pull-up", "Assisted Pull-Up", Tracking.ASSISTED_BODYWEIGHT)
    private val dip = exercise("weighted-dip", "Weighted Dip", Tracking.WEIGHTED_BODYWEIGHT)
    private val plank = exercise("plank", "Plank", Tracking.DURATION)

    private val week1 = WorkoutEntity(id = 1, name = "Pull", startedAt = at("2026-07-06T10:00:00Z"), endedAt = at("2026-07-06T11:00:00Z"))
    private val week2 = WorkoutEntity(id = 2, name = "Pull", startedAt = at("2026-07-13T10:00:00Z"), endedAt = at("2026-07-13T11:00:00Z"))

    private val slots = listOf(
        WorkoutExerciseEntity(id = 1, workoutId = 1, exerciseId = pullup.id, position = 0),
        WorkoutExerciseEntity(id = 2, workoutId = 1, exerciseId = dip.id, position = 1),
        WorkoutExerciseEntity(id = 3, workoutId = 2, exerciseId = pullup.id, position = 0),
        WorkoutExerciseEntity(id = 4, workoutId = 2, exerciseId = dip.id, position = 1),
        WorkoutExerciseEntity(id = 5, workoutId = 2, exerciseId = plank.id, position = 2),
    )

    private fun set(id: Long, slot: Long, lb: Double?, reps: Int?, at: String, durationSec: Int? = null) = SetEntity(
        id = id, workoutExerciseId = slot, position = 0, type = "normal",
        weightKg = lb?.let(Format::lbToKg), reps = reps, durationSec = durationSec, completedAt = at(at),
    )

    private val sets = listOf(
        set(1, 1, -40.0, 8, "2026-07-06T10:05:00Z"),
        set(2, 2, 20.0, 8, "2026-07-06T10:15:00Z"),
        // Less help: a record, though -30 is "less" than 0. More on the belt for fewer reps: a record too.
        set(3, 3, -30.0, 8, "2026-07-13T10:05:00Z"),
        set(4, 4, 25.0, 5, "2026-07-13T10:15:00Z"),
        set(5, 5, null, null, "2026-07-13T10:25:00Z", durationSec = 60),
    )

    private val snapshot = ExportFixtures.snapshot(
        sets = sets,
        workouts = listOf(week1, week2),
        slots = slots,
        unit = WeightUnit.LB,
        exercises = listOf(pullup, dip, plank),
        bodyweight = emptyList(),
        bars = emptyList(),
    )

    @Test
    fun `stars follow each exercise's type`() {
        val stars = snapshot.workouts.first().exercises.associate { it.name to it.sets.single().isPr }
        assertEquals(mapOf("Assisted Pull-Up" to true, "Weighted Dip" to true, "Plank" to true), stars)
    }

    @Test
    fun `training-log reads help as a negative weight and a hold in seconds`() {
        val log = MarkdownExport.render(snapshot)
        assertContains(log, "  -30x8 — **PR**")
        assertContains(log, "  25x5 r600 — **PR**")
        assertContains(log, "  60s r600 — **PR**")
        assertContains(log, "- Assisted Pull-Up: least assistance 30 lb (2026-07-13)")
        assertContains(log, "- Weighted Dip: max 25 lb (2026-07-13) — best set 25x5")
        assertContains(log, "a negative weight is help")
        // An e1RM of a belt or of help is no trend of anything.
        assertTrue(snapshot.e1RmTrend.lifts.isEmpty())
    }

    @Test
    fun `strong_csv keeps the sign of the help`() {
        val rows = CsvExport.render(snapshot).lines()
        assertTrue(rows.any { it.contains(",Assisted Pull-Up,1,-30,8,") }, rows.joinToString("\n"))
        assertTrue(rows.any { it.contains(",Plank,1,0,0,0,60,") }, rows.joinToString("\n"))
    }

    @Test
    fun `workouts_json names each exercise's type in Hevy's words`() {
        val json = Json.parseToJsonElement(JsonExport.render(snapshot)).jsonObject
        val types = json.getValue("exercises").jsonArray.associate {
            it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("type").jsonPrimitive.content
        }
        assertEquals(
            mapOf("Assisted Pull-Up" to "bodyweight_assisted_reps", "Plank" to "duration", "Weighted Dip" to "bodyweight_reps"),
            types,
        )
        val pullupSet = json.getValue("workouts").jsonArray.first().jsonObject.getValue("exercises").jsonArray.first()
            .jsonObject.getValue("sets").jsonArray.single().jsonObject
        assertEquals(Format.lbToKg(-30.0), pullupSet.getValue("weight_kg").jsonPrimitive.content.toDouble(), 1e-9)
    }

    @Test
    fun `a note on an exercise with nothing ticked is still exported`() {
        val skipped = WorkoutExerciseEntity(id = 6, workoutId = 2, exerciseId = dip.id, position = 3, notes = "skipped: left shoulder")
        val unticked = SetEntity(id = 9, workoutExerciseId = 6, position = 0, type = "normal", weightKg = 20.0, reps = 8)
        // A workout closed with only a note (stale, nothing ticked) is no training: it isn't exported.
        val noteOnly = WorkoutEntity(id = 3, name = "Legs", startedAt = at("2026-07-14T09:00:00Z"), endedAt = at("2026-07-14T09:00:00Z"), notes = "knee sore")
        val noteOnlySlot = WorkoutExerciseEntity(id = 7, workoutId = 3, exerciseId = plank.id, position = 0, notes = "skipped")
        val noted = ExportFixtures.snapshot(
            sets = sets + unticked, workouts = listOf(week1, week2, noteOnly), slots = slots + skipped + noteOnlySlot, unit = WeightUnit.LB,
            exercises = listOf(pullup, dip, plank), bodyweight = emptyList(), bars = emptyList(),
        )
        val log = MarkdownExport.render(noted)
        assertContains(log, "  > skipped: left shoulder")
        val exercise = noted.workouts.first().exercises.last()
        assertEquals("skipped: left shoulder", exercise.notes)
        assertTrue(exercise.sets.isEmpty(), "no unticked set sneaks in")
        assertEquals(listOf(2L, 1L), noted.workouts.map { it.id })
        assertContains(log, "(2 workouts)")
    }

    private fun at(iso: String) = ExportFixtures.millis(iso)
}
