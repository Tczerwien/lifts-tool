package com.tczerwien.lifts.export

import com.tczerwien.lifts.data.BarEntity
import com.tczerwien.lifts.data.BodyweightEntity
import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import java.time.Instant
import java.time.LocalDate

/**
 * Shared fixture for all export tests: two bench workouts a week apart with a warmup,
 * RPE, rest, a PR set, workout notes, and instance notes containing a comma + quotes.
 *
 * Expected derived facts:
 * - Workout 1 (2026-07-06, 44m): w60x8; 100x5 @8 (PR — first working set); 100x5.
 * - Workout 2 (2026-07-13, 98m = "1h 38m"): w60x8; 102.5x5 @8 (PR — heavier); 95x5.
 * - Bench PR: max weight 102.5 (2026-07-13), best e1RM 102.5*(1+5/30) = 119.583… (2026-07-13).
 * - Squat is in the catalog but never performed, so exports must omit it.
 * - Weekly buckets (8 weeks ending 2026-07-14): chest 2.0 in weeks 7 and 8 (1-based),
 *   triceps/shoulders 1.0 each (2 sets x 0.5).
 * - Bodyweight: six weigh-ins across four ISO weeks with one gap week (2026-06-29..07-05).
 *   8-week averages, oldest first: —, —, —, 81.9, 81.6, —, 81.1, 80.5. The last-28-days
 *   window (2026-06-17..07-14) holds five entries (06-15 falls outside); least-squares
 *   slope ≈ -0.3034 kg/week.
 * - Two named bars (Olympic Bar 20.4117 kg, Leg Press Sled 53.5239 kg), exported under
 *   profile.equipment in the JSON export only.
 */
object ExportFixtures {

    const val EXPORTED_AT = "2026-07-14T12:00:00Z"
    const val GOALS = "Bench 110 kg for 5"
    const val INSTANCE_NOTES = "Paused last rep, focus on \"leg drive\""

    val bench = ExerciseEntity(
        id = "bench-press-barbell",
        name = "Bench Press (Barbell)",
        equipment = "barbell",
        primaryMuscles = listOf("chest"),
        secondaryMuscles = listOf("triceps", "shoulders"),
        category = "strength",
        level = "beginner",
        instructions = emptyList(),
    )

    val squat = ExerciseEntity(
        id = "squat-barbell",
        name = "Squat (Barbell)",
        equipment = "barbell",
        primaryMuscles = listOf("quadriceps"),
        secondaryMuscles = listOf("glutes"),
        category = "strength",
        level = null,
        instructions = emptyList(),
    )

    val workout1 = WorkoutEntity(
        id = 1,
        name = "Push Day",
        startedAt = millis("2026-07-06T10:00:00Z"),
        endedAt = millis("2026-07-06T10:44:00Z"),
    )

    val workout2 = WorkoutEntity(
        id = 2,
        name = "Push Day",
        startedAt = millis("2026-07-13T10:00:00Z"),
        endedAt = millis("2026-07-13T11:38:00Z"),
        notes = "Felt strong",
    )

    val workoutExercise1 = WorkoutExerciseEntity(id = 1, workoutId = 1, exerciseId = bench.id, position = 0)
    val workoutExercise2 = WorkoutExerciseEntity(
        id = 2,
        workoutId = 2,
        exerciseId = bench.id,
        position = 0,
        notes = INSTANCE_NOTES,
    )

    val sets = listOf(
        SetEntity(
            id = 1, workoutExerciseId = 1, position = 0, type = "warmup",
            weightKg = 60.0, reps = 8, completedAt = millis("2026-07-06T10:05:00Z"),
        ),
        SetEntity(
            id = 2, workoutExerciseId = 1, position = 1, type = "normal",
            weightKg = 100.0, reps = 5, rpe = 8.0,
            completedAt = millis("2026-07-06T10:15:00Z"),
        ),
        SetEntity(
            id = 3, workoutExerciseId = 1, position = 2, type = "normal",
            weightKg = 100.0, reps = 5, completedAt = millis("2026-07-06T10:25:00Z"),
        ),
        SetEntity(
            id = 4, workoutExerciseId = 2, position = 0, type = "warmup",
            weightKg = 60.0, reps = 8, completedAt = millis("2026-07-13T10:05:00Z"),
        ),
        SetEntity(
            id = 5, workoutExerciseId = 2, position = 1, type = "normal",
            weightKg = 102.5, reps = 5, rpe = 8.0,
            completedAt = millis("2026-07-13T10:15:00Z"),
        ),
        SetEntity(
            id = 6, workoutExerciseId = 2, position = 2, type = "normal",
            weightKg = 95.0, reps = 5, completedAt = millis("2026-07-13T10:25:00Z"),
        ),
    )

    val bodyweight = listOf(
        BodyweightEntity(id = 1, epochDay = LocalDate.parse("2026-06-15").toEpochDay(), weightKg = 82.0),
        BodyweightEntity(id = 2, epochDay = LocalDate.parse("2026-06-18").toEpochDay(), weightKg = 81.8),
        BodyweightEntity(id = 3, epochDay = LocalDate.parse("2026-06-22").toEpochDay(), weightKg = 81.6),
        BodyweightEntity(id = 4, epochDay = LocalDate.parse("2026-07-06").toEpochDay(), weightKg = 81.0),
        BodyweightEntity(id = 5, epochDay = LocalDate.parse("2026-07-09").toEpochDay(), weightKg = 81.2),
        BodyweightEntity(id = 6, epochDay = LocalDate.parse("2026-07-13").toEpochDay(), weightKg = 80.5),
    )

    val bars = listOf(
        BarEntity(id = 1, name = "Olympic Bar", weightKg = 20.4117, position = 0),
        BarEntity(id = 2, name = "Leg Press Sled", weightKg = 53.5239, position = 1),
    )

    fun snapshot(
        bodyweight: List<BodyweightEntity> = ExportFixtures.bodyweight,
        bars: List<BarEntity> = ExportFixtures.bars,
    ): ExportSnapshot = buildSnapshot(
        exercises = listOf(bench, squat),
        workouts = listOf(workout1, workout2),
        workoutExercises = listOf(workoutExercise1, workoutExercise2),
        sets = sets,
        bodyweight = bodyweight,
        bars = bars,
        exportedAtIso = EXPORTED_AT,
        goals = GOALS,
        injuries = listOf("left knee"),
    )

    fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()
}
