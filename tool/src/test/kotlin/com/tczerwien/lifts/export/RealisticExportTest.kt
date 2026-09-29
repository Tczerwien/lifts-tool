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
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Data as the app really writes it (#053, #122), rendered end to end. Only fields Lifts fills today:
 * weights entered in lb, reps, warm-up / normal / failure, tick times, workout notes, a pinned cue and a
 * renamed lift; no RPE, durations or exercise notes. Times are Chicago evenings. It holds every case the
 * drop rules exist for: a template row left unticked, an exercise skipped (its slot has only unticked
 * rows), a workout finished with nothing ticked, and a workout still open with a ticked set heavier
 * than any record. The workouts section and the CSV are pinned whole.
 */
class RealisticExportTest {

    private val chicago = ZoneId.of("America/Chicago")

    private fun catalog(id: String, name: String, primary: String, secondary: List<String>, pinnedNote: String? = null) =
        ExerciseEntity(
            id = id, name = name, equipment = null, primaryMuscles = listOf(primary), secondaryMuscles = secondary,
            category = "strength", level = null, instructions = emptyList(), pinnedNote = pinnedNote,
        )

    private val bench = catalog("barbell-bench-press-medium-grip", "Barbell Bench Press - Medium Grip", "chest", listOf("triceps", "shoulders"))
    private val incline = catalog("incline-dumbbell-press", "Low Incline DB Press", "chest", listOf("shoulders"), pinnedNote = "Elbows at 45")
    private val pullUp = catalog("pullups", "Pullups", "lats", listOf("biceps"))
    private val squat = catalog("barbell-squat", "Barbell Squat", "quadriceps", listOf("glutes"))

    private fun at(date: String, time: String): Long = ZonedDateTime.of(
        LocalDate.parse(date), LocalTime.parse(time), chicago,
    ).toInstant().toEpochMilli()

    private val monday = at("2026-07-06", "19:30")
    private fun minute(m: Int, s: Int = 0) = monday + m * 60_000L + s * 1000L

    private fun set(id: Long, slot: Long, position: Int, type: String, lb: Double?, reps: Int?, done: Long?) = SetEntity(
        id = id, workoutExerciseId = slot, position = position, type = type,
        weightKg = lb?.let(Format::lbToKg), reps = reps, completedAt = done,
    )

    private val rows = LiftsRows(
        exercises = listOf(bench, incline, pullUp, squat),
        workouts = listOf(
            WorkoutEntity(id = 1, name = "Upper A", startedAt = monday, endedAt = minute(55), templateId = 1, notes = "Good day, shoulder ok"),
            // Opened and finished by mistake.
            WorkoutEntity(id = 2, name = "Workout", startedAt = at("2026-07-09", "07:00"), endedAt = at("2026-07-09", "07:08")),
            // Still going.
            WorkoutEntity(id = 3, name = "Upper A", startedAt = at("2026-07-13", "19:30")),
        ),
        workoutExercises = listOf(
            WorkoutExerciseEntity(id = 1, workoutId = 1, exerciseId = bench.id, position = 0),
            WorkoutExerciseEntity(id = 2, workoutId = 1, exerciseId = incline.id, position = 1),
            WorkoutExerciseEntity(id = 3, workoutId = 1, exerciseId = pullUp.id, position = 2),
            WorkoutExerciseEntity(id = 4, workoutId = 1, exerciseId = squat.id, position = 3),
            WorkoutExerciseEntity(id = 5, workoutId = 2, exerciseId = bench.id, position = 0),
            WorkoutExerciseEntity(id = 6, workoutId = 3, exerciseId = bench.id, position = 0),
        ),
        sets = listOf(
            set(1, 1, 0, "warmup", 135.0, 5, minute(5)),
            set(2, 1, 1, "normal", 225.0, 5, minute(8)),
            set(3, 1, 2, "normal", 225.0, 5, minute(11)),
            set(4, 1, 3, "failure", 225.0, 4, minute(14)),
            set(5, 2, 0, "normal", 60.0, 10, minute(16, 30)),
            set(6, 2, 1, "normal", 60.0, 10, minute(18, 30)),
            // A template row he never ticked, still holding its starting values.
            set(7, 2, 2, "normal", 60.0, 10, null),
            set(8, 3, 0, "normal", null, 10, minute(20, 30)),
            set(9, 3, 1, "normal", null, 8, minute(22, 30)),
            // Squat skipped: every row unticked.
            set(10, 4, 0, "normal", 275.0, 5, null),
            set(11, 4, 1, "normal", 275.0, 5, null),
            set(12, 5, 0, "normal", null, null, null),
            set(13, 6, 0, "normal", 245.0, 5, at("2026-07-13", "19:40")),
        ),
        templates = emptyList(),
        templateExercises = emptyList(),
        bodyweight = listOf(BodyweightEntity(id = 1, epochDay = LocalDate.parse("2026-07-05").toEpochDay(), weightKg = Format.lbToKg(182.4))),
        bars = emptyList(),
    )

    private val snapshot = buildSnapshot(
        rows = rows,
        exportedAtMillis = at("2026-07-14", "07:00"),
        zone = chicago,
        unit = WeightUnit.LB,
        goals = null,
        injuries = emptyList(),
    )

    private val markdown = MarkdownExport.render(snapshot)

    @Test
    fun theWorkoutsSectionReadsAsLogged() {
        val expected = """
            |## Workouts
            |
            |### 2026-07-06 — Upper A (55m, BW 182.4 lb from 2026-07-05)
            |
            |> Good day, shoulder ok
            |
            |- **Bench Press** [chest; triceps, shoulders]
            |  w135x5; 225x5 r180 — **PR**; 225x5 r180; 225x4F r180
            |
            |- **Low Incline DB Press** [chest; shoulders]
            |  60x10 r150 — **PR**; 60x10 r120
            |
            |- **Pull-Up** [lats; biceps]
            |  x10 r120 — **PR**; x8 r120
            |
        """.trimMargin()
        assertEquals(expected, markdown.substring(markdown.indexOf("## Workouts")))
    }

    @Test
    fun theCsvHasOneRowPerTickedSetOfTheFinishedWorkout() {
        val expected = """
            |Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes,RPE
            |2026-07-06 19:30:00,Upper A,55m,Bench Press,1,135,5,0,0,,"Good day, shoulder ok",
            |2026-07-06 19:30:00,Upper A,55m,Bench Press,2,225,5,0,0,,"Good day, shoulder ok",
            |2026-07-06 19:30:00,Upper A,55m,Bench Press,3,225,5,0,0,,"Good day, shoulder ok",
            |2026-07-06 19:30:00,Upper A,55m,Bench Press,4,225,4,0,0,,"Good day, shoulder ok",
            |2026-07-06 19:30:00,Upper A,55m,Low Incline DB Press,1,60,10,0,0,,"Good day, shoulder ok",
            |2026-07-06 19:30:00,Upper A,55m,Low Incline DB Press,2,60,10,0,0,,"Good day, shoulder ok",
            |2026-07-06 19:30:00,Upper A,55m,Pull-Up,1,0,10,0,0,,"Good day, shoulder ok",
            |2026-07-06 19:30:00,Upper A,55m,Pull-Up,2,0,8,0,0,,"Good day, shoulder ok",
            |
        """.trimMargin()
        assertEquals(expected, CsvExport.render(snapshot))
    }

    @Test
    fun nothingUnfinishedSkippedOrUntickedLeaks() {
        val files = listOf(markdown, JsonExport.render(snapshot), CsvExport.render(snapshot))
        for (file in files) {
            assertTrue("Back Squat" !in file && "barbell-squat" !in file, "the skipped exercise leaks")
            assertTrue("245" !in file, "the open workout's set leaks")
            assertTrue("2026-07-09" !in file && "2026-07-13T" !in file, "an empty or open workout leaks")
        }
        // The summary counts the same sets: one bench PR at 225x5, no 245.
        assertTrue(markdown.contains("- Bench Press: e1RM 262.5 lb (2026-07-06) — best set 225x5"), markdown)
    }

    @Test
    fun jsonKeepsHisCueAndTheExactWeights() {
        val document = Json.decodeFromString(JsonExportDocument.serializer(), JsonExport.render(snapshot))
        assertEquals(listOf("Bench Press", "Low Incline DB Press", "Pull-Up"), document.exercises.map { it.name })
        assertEquals("Elbows at 45", document.exercises[1].pinnedNote)
        val workout = document.workouts.single()
        assertEquals("2026-07-06T19:30:00-05:00", workout.startTime)
        assertEquals("2026-07-06T20:25:00-05:00", workout.endTime)
        assertEquals(Format.lbToKg(225.0), workout.exercises.first().sets[1].weightKg)
        assertEquals(listOf("warmup", "normal", "normal", "failure"), workout.exercises.first().sets.map { it.type })
    }
}
