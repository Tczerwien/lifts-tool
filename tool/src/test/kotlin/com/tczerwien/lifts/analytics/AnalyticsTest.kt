package com.tczerwien.lifts.analytics

import com.tczerwien.lifts.data.BodyweightEntity
import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyticsTest {

    private val bench = ExerciseEntity(
        id = "bench-press-barbell",
        name = "Bench Press (Barbell)",
        equipment = "barbell",
        primaryMuscles = listOf("chest"),
        secondaryMuscles = listOf("triceps", "shoulders"),
        category = "strength",
        level = null,
        instructions = emptyList(),
    )

    private fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

    // --- epleyE1RmKg ---

    @Test
    fun epleyKnownValues() {
        assertEquals(116.66666666666667, epleyE1RmKg(100.0, 5)!!, 1e-9)
        assertEquals(140.0, epleyE1RmKg(100.0, 12)!!, 1e-9)
        assertEquals(103.33333333333333, epleyE1RmKg(100.0, 1)!!, 1e-9)
        assertEquals(119.58333333333333, epleyE1RmKg(102.5, 5)!!, 1e-9)
    }

    @Test
    fun epleyRejectsOutOfRangeReps() {
        assertNull(epleyE1RmKg(100.0, 0))
        assertNull(epleyE1RmKg(100.0, 13))
        assertNull(epleyE1RmKg(100.0, -1))
    }

    // --- computePrs ---

    @Test
    fun computePrsPicksRightSet() {
        val workout = WorkoutEntity(id = 1, name = "Push", startedAt = millis("2026-07-06T10:00:00Z"))
        val slot = WorkoutExerciseEntity(id = 1, workoutId = 1, exerciseId = bench.id, position = 0)
        val sets = listOf(
            // Heavier warmup must be ignored.
            SetEntity(id = 1, workoutExerciseId = 1, position = 0, type = "warmup", weightKg = 200.0, reps = 1, completedAt = millis("2026-07-06T10:01:00Z")),
            SetEntity(id = 2, workoutExerciseId = 1, position = 1, type = "normal", weightKg = 100.0, reps = 5, completedAt = millis("2026-07-06T10:10:00Z")),
            SetEntity(id = 3, workoutExerciseId = 1, position = 2, type = "normal", weightKg = 102.5, reps = 5, completedAt = millis("2026-07-06T10:20:00Z")),
            SetEntity(id = 4, workoutExerciseId = 1, position = 3, type = "normal", weightKg = 95.0, reps = 5, completedAt = millis("2026-07-06T10:30:00Z")),
            // Not completed — must be ignored.
            SetEntity(id = 5, workoutExerciseId = 1, position = 4, type = "normal", weightKg = 300.0, reps = 1, completedAt = null),
        )

        val prs = computePrs(listOf(bench), listOf(workout), listOf(slot), sets)

        assertEquals(1, prs.size)
        val pr = prs.single()
        assertEquals(bench.id, pr.exerciseId)
        assertEquals(102.5, pr.maxWeightKg)
        assertEquals(millis("2026-07-06T10:20:00Z"), pr.maxWeightDate)
        assertEquals(119.58333333333333, pr.bestE1RmKg!!, 1e-9)
        assertEquals(millis("2026-07-06T10:20:00Z"), pr.bestE1RmDate)
        assertEquals("102.5x5", pr.bestSetNotation)
    }

    @Test
    fun computePrsWithoutE1RmFallsBackToMaxWeight() {
        val workout = WorkoutEntity(id = 1, name = "Push", startedAt = millis("2026-07-06T10:00:00Z"))
        val slot = WorkoutExerciseEntity(id = 1, workoutId = 1, exerciseId = bench.id, position = 0)
        // 20 reps -> outside Epley's valid range, so no e1RM exists.
        val sets = listOf(
            SetEntity(id = 1, workoutExerciseId = 1, position = 0, type = "normal", weightKg = 60.0, reps = 20, completedAt = millis("2026-07-06T10:10:00Z")),
        )

        val pr = computePrs(listOf(bench), listOf(workout), listOf(slot), sets).single()

        assertEquals(60.0, pr.maxWeightKg)
        assertNull(pr.bestE1RmKg)
        assertNull(pr.bestE1RmDate)
        assertEquals("60x20", pr.bestSetNotation)
    }

    // --- weeklySetsPerMuscle ---

    @Test
    fun weeklyBucketsWithFixedToday() {
        // Tuesday 2026-07-14 -> current ISO week starts Monday 2026-07-13.
        val today = millis("2026-07-14T12:00:00Z")
        val workouts = listOf(
            WorkoutEntity(id = 1, name = "A", startedAt = millis("2026-07-06T10:00:00Z")),
            WorkoutEntity(id = 2, name = "B", startedAt = millis("2026-07-13T10:00:00Z")),
            WorkoutEntity(id = 3, name = "Old", startedAt = millis("2026-06-01T10:00:00Z")),
        )
        val slots = listOf(
            WorkoutExerciseEntity(id = 1, workoutId = 1, exerciseId = bench.id, position = 0),
            WorkoutExerciseEntity(id = 2, workoutId = 2, exerciseId = bench.id, position = 0),
            WorkoutExerciseEntity(id = 3, workoutId = 3, exerciseId = bench.id, position = 0),
        )
        val sets = listOf(
            // Warmup: never counted.
            SetEntity(id = 1, workoutExerciseId = 1, position = 0, type = "warmup", weightKg = 60.0, reps = 8, completedAt = millis("2026-07-06T10:05:00Z")),
            SetEntity(id = 2, workoutExerciseId = 1, position = 1, type = "normal", weightKg = 100.0, reps = 5, completedAt = millis("2026-07-06T10:15:00Z")),
            SetEntity(id = 3, workoutExerciseId = 1, position = 2, type = "normal", weightKg = 100.0, reps = 5, completedAt = millis("2026-07-06T10:25:00Z")),
            SetEntity(id = 4, workoutExerciseId = 2, position = 0, type = "normal", weightKg = 102.5, reps = 5, completedAt = millis("2026-07-13T10:15:00Z")),
            // Outside the 4-week window: dropped.
            SetEntity(id = 5, workoutExerciseId = 3, position = 0, type = "normal", weightKg = 90.0, reps = 5, completedAt = millis("2026-06-01T10:15:00Z")),
        )

        val weekly = weeklySetsPerMuscle(listOf(bench), workouts, slots, sets, weeks = 4, todayEpochMillis = today)

        assertEquals(setOf("chest", "triceps", "shoulders"), weekly.keys)
        assertEquals(listOf(0.0, 0.0, 2.0, 1.0), weekly.getValue("chest"))
        assertEquals(listOf(0.0, 0.0, 1.0, 0.5), weekly.getValue("triceps"))
        assertEquals(listOf(0.0, 0.0, 1.0, 0.5), weekly.getValue("shoulders"))
    }

    // --- bodyweightTrend ---

    @Test
    fun bodyweightTrendIsNullWithoutEntries() {
        assertNull(bodyweightTrend(emptyList(), todayEpochDay = day("2026-07-14")))
    }

    @Test
    fun bodyweightTrendWeeklyAveragesIncludeGapWeek() {
        // Tuesday 2026-07-14 -> current ISO week starts Monday 2026-07-13; 4 weeks
        // reach back to Monday 2026-06-22.
        val entries = listOf(
            bw(id = 1, dateIso = "2026-06-22", weightKg = 82.0),
            bw(id = 2, dateIso = "2026-06-24", weightKg = 81.6), // same week -> averaged
            // Week of 2026-06-29: no entry -> null bucket.
            bw(id = 3, dateIso = "2026-07-06", weightKg = 81.0),
            bw(id = 4, dateIso = "2026-07-13", weightKg = 80.5),
            // Before the window: dropped.
            bw(id = 5, dateIso = "2026-05-01", weightKg = 90.0),
        )

        val trend = bodyweightTrend(entries, weeks = 4, todayEpochDay = day("2026-07-14"))!!

        assertEquals(80.5, trend.currentKg)
        assertEquals(day("2026-07-13"), trend.currentEpochDay)
        assertEquals(4, trend.weeklyAvgKg.size)
        assertEquals(81.8, trend.weeklyAvgKg[0]!!, 1e-9)
        assertNull(trend.weeklyAvgKg[1])
        assertEquals(81.0, trend.weeklyAvgKg[2]!!, 1e-9)
        assertEquals(80.5, trend.weeklyAvgKg[3]!!, 1e-9)
    }

    @Test
    fun bodyweightTrendRateIsLeastSquaresSlope() {
        // Perfectly linear gain: +0.5 kg per week over four weekly weigh-ins, all
        // inside the 28-day window ending 2026-07-14.
        val entries = listOf(
            bw(id = 1, dateIso = "2026-06-22", weightKg = 80.0),
            bw(id = 2, dateIso = "2026-06-29", weightKg = 80.5),
            bw(id = 3, dateIso = "2026-07-06", weightKg = 81.0),
            bw(id = 4, dateIso = "2026-07-13", weightKg = 81.5),
        )

        val trend = bodyweightTrend(entries, todayEpochDay = day("2026-07-14"))!!

        assertEquals(0.5, trend.rateKgPerWeek!!, 1e-9)
    }

    @Test
    fun bodyweightTrendRateIsNullWithFewerThanTwoEntriesInWindow() {
        // Only one entry falls inside the last 28 days; older history alone gives no slope.
        val entries = listOf(
            bw(id = 1, dateIso = "2026-04-01", weightKg = 84.0),
            bw(id = 2, dateIso = "2026-04-08", weightKg = 83.5),
            bw(id = 3, dateIso = "2026-07-13", weightKg = 80.5),
        )

        val trend = bodyweightTrend(entries, todayEpochDay = day("2026-07-14"))!!

        assertNull(trend.rateKgPerWeek)
        assertEquals(80.5, trend.currentKg)
    }

    private fun day(iso: String): Long = LocalDate.parse(iso).toEpochDay()

    private fun bw(id: Long, dateIso: String, weightKg: Double) =
        BodyweightEntity(id = id, epochDay = day(dateIso), weightKg = weightKg)

    // --- isPr ---

    @Test
    fun isPrDetectsHeavierAndBetterE1Rm() {
        val history = listOf(
            SetEntity(id = 1, workoutExerciseId = 1, position = 0, type = "normal", weightKg = 100.0, reps = 5, completedAt = 1000L),
            SetEntity(id = 2, workoutExerciseId = 1, position = 1, type = "warmup", weightKg = 150.0, reps = 1, completedAt = 2000L),
        )

        // Heavier at any reps.
        assertTrue(isPr(newSet(weightKg = 102.5, reps = 1), history))
        // Same weight, same reps: neither heavier nor better e1RM.
        assertFalse(isPr(newSet(weightKg = 100.0, reps = 5), history))
        // Lighter but better e1RM: 95x12 -> 133 beats 100x5 -> 116.7.
        assertTrue(isPr(newSet(weightKg = 95.0, reps = 12), history))
        // Lighter and worse e1RM.
        assertFalse(isPr(newSet(weightKg = 90.0, reps = 5), history))
        // Warmups are never PRs (the heavier 150 warmup in history is also ignored).
        assertFalse(isPr(newSet(weightKg = 160.0, reps = 1, type = "warmup"), history))
        // First-ever weighted working set is a PR.
        assertTrue(isPr(newSet(weightKg = 40.0, reps = 5), emptyList()))
    }

    // --- workoutsPerWeek ---

    @Test
    fun workoutsPerWeekBucketsIsoWeeksOldestFirst() {
        // Tuesday 2026-07-14 -> current ISO week starts Monday 2026-07-13; 4 weeks
        // reach back to Monday 2026-06-22.
        val workouts = listOf(
            // Week 0: Monday start and Sunday start both belong to 2026-06-22's week.
            finished(id = 1, startIso = "2026-06-22T06:00:00Z"),
            finished(id = 2, startIso = "2026-06-28T23:00:00Z"),
            // Week 1 (2026-06-29): none -> gap week stays 0.
            // Week 2 (2026-07-06): one finished; an in-progress workout never counts.
            finished(id = 3, startIso = "2026-07-08T10:00:00Z"),
            WorkoutEntity(id = 4, name = "In progress", startedAt = millis("2026-07-09T10:00:00Z")),
            // Week 3 (current, 2026-07-13): two.
            finished(id = 5, startIso = "2026-07-13T05:00:00Z"),
            finished(id = 6, startIso = "2026-07-14T09:00:00Z"),
            // Sunday before the window: dropped.
            finished(id = 7, startIso = "2026-06-21T10:00:00Z"),
        )

        val counts = workoutsPerWeek(workouts, weeks = 4, todayEpochDay = day("2026-07-14"))

        assertEquals(listOf(2, 0, 1, 2), counts)
    }

    @Test
    fun workoutsPerWeekSingleWeekWindow() {
        val workouts = listOf(
            finished(id = 1, startIso = "2026-07-13T10:00:00Z"),
            // Sunday of the previous week: outside a 1-week window.
            finished(id = 2, startIso = "2026-07-12T10:00:00Z"),
        )

        val counts = workoutsPerWeek(workouts, weeks = 1, todayEpochDay = day("2026-07-14"))

        assertEquals(listOf(1), counts)
    }

    @Test
    fun workoutsPerWeekAllZeroWithoutFinishedWorkouts() {
        val workouts = listOf(
            WorkoutEntity(id = 1, name = "In progress", startedAt = millis("2026-07-13T10:00:00Z")),
        )

        val counts = workoutsPerWeek(workouts, weeks = 3, todayEpochDay = day("2026-07-14"))

        assertEquals(listOf(0, 0, 0), counts)
    }

    private fun finished(id: Long, startIso: String) = WorkoutEntity(
        id = id,
        name = "W$id",
        startedAt = millis(startIso),
        endedAt = millis(startIso) + 3_600_000L,
    )

    // --- longestStreak ---

    @Test
    fun longestStreakFindsLongestRun() {
        assertEquals(3, longestStreak(listOf(3, 4, 2, 3, 3, 3, 0, 5), threshold = 3))
    }

    @Test
    fun longestStreakCountsRunAtEnd() {
        assertEquals(2, longestStreak(listOf(0, 1, 3, 3), threshold = 3))
    }

    @Test
    fun longestStreakZeroWhenNeverReached() {
        assertEquals(0, longestStreak(listOf(1, 2, 0), threshold = 3))
        assertEquals(0, longestStreak(emptyList(), threshold = 3))
    }

    private fun newSet(weightKg: Double, reps: Int, type: String = "normal") = SetEntity(
        id = 99,
        workoutExerciseId = 1,
        position = 9,
        type = type,
        weightKg = weightKg,
        reps = reps,
        completedAt = null,
    )
}
