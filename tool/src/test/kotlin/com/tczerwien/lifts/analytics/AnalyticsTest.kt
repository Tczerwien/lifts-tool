package com.tczerwien.lifts.analytics

import com.tczerwien.lifts.data.BodyweightEntity
import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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
        assertEquals(100.0, epleyE1RmKg(100.0, 1)!!, 1e-9, "a single is its own 1RM")
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
        assertEquals(millis("2026-07-06T10:00:00Z"), pr.maxWeightDate, "dated by its workout")
        assertEquals(119.58333333333333, pr.bestE1RmKg!!, 1e-9)
        assertEquals(millis("2026-07-06T10:00:00Z"), pr.bestE1RmDate)
        assertEquals(102.5 to 5, pr.bestSet.weightKg to pr.bestSet.reps)
    }

    @Test
    fun computePrsDatesARecordAfterMidnightByItsWorkout() {
        // An evening workout that runs past midnight: History lists it on the 6th, so its record is the 6th's.
        val workout = WorkoutEntity(id = 1, name = "Push", startedAt = millis("2026-07-06T23:30:00Z"))
        val slot = WorkoutExerciseEntity(id = 1, workoutId = 1, exerciseId = bench.id, position = 0)
        val sets = listOf(
            SetEntity(id = 1, workoutExerciseId = 1, position = 0, type = "normal", weightKg = 100.0, reps = 5, completedAt = millis("2026-07-06T23:50:00Z")),
            SetEntity(id = 2, workoutExerciseId = 1, position = 1, type = "normal", weightKg = 105.0, reps = 5, completedAt = millis("2026-07-07T00:10:00Z")),
        )

        val pr = computePrs(listOf(bench), listOf(workout), listOf(slot), sets).single()

        assertEquals(105.0, pr.maxWeightKg)
        assertEquals(millis("2026-07-06T23:30:00Z"), pr.maxWeightDate)
        assertEquals(millis("2026-07-06T23:30:00Z"), pr.bestE1RmDate)
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
        assertEquals(60.0 to 20, pr.bestSet.weightKg to pr.bestSet.reps)
    }

    // --- weeklySetsPerMuscle ---

    @Test
    fun weeklyBucketsWithFixedToday() {
        // Tuesday 2026-07-14 -> current ISO week starts Monday 2026-07-13.
        val today = millis("2026-07-14T12:00:00Z")
        val workouts = listOf(
            WorkoutEntity(id = 1, name = "A", startedAt = millis("2026-07-06T10:00:00Z"), endedAt = millis("2026-07-06T11:00:00Z")),
            WorkoutEntity(id = 2, name = "B", startedAt = millis("2026-07-13T10:00:00Z"), endedAt = millis("2026-07-13T11:00:00Z")),
            WorkoutEntity(id = 3, name = "Old", startedAt = millis("2026-06-01T10:00:00Z"), endedAt = millis("2026-06-01T11:00:00Z")),
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

        val weekly = weeklySetsPerMuscle(listOf(bench), workouts, slots, sets, weeks = 4, todayEpochMillis = today, zone = ZoneOffset.UTC)

        // Direct counting (B17): the primary muscle only; triceps and shoulders, secondary, get nothing.
        assertEquals(setOf("chest"), weekly.keys)
        assertEquals(listOf(0, 0, 2, 1), weekly.getValue("chest"))
    }

    @Test
    fun weeklyBucketsASetAfterMidnightInItsWorkoutsWeek() {
        // A Sunday workout (2026-07-12) that runs past midnight into Monday, the current week's first day.
        val today = millis("2026-07-14T12:00:00Z")
        val workouts = listOf(WorkoutEntity(id = 1, name = "Late", startedAt = millis("2026-07-12T23:30:00Z"), endedAt = millis("2026-07-13T00:40:00Z")))
        val slots = listOf(WorkoutExerciseEntity(id = 1, workoutId = 1, exerciseId = bench.id, position = 0))
        val sets = listOf(
            SetEntity(id = 1, workoutExerciseId = 1, position = 0, type = "normal", weightKg = 100.0, reps = 5, completedAt = millis("2026-07-12T23:50:00Z")),
            SetEntity(id = 2, workoutExerciseId = 1, position = 1, type = "normal", weightKg = 100.0, reps = 5, completedAt = millis("2026-07-13T00:10:00Z")),
        )

        val weekly = weeklySetsPerMuscle(listOf(bench), workouts, slots, sets, weeks = 2, todayEpochMillis = today, zone = ZoneOffset.UTC)

        assertEquals(listOf(2, 0), weekly.getValue("chest"), "both sets in Sunday's week, where History lists the workout")
    }

    @Test
    fun e1RmTrendCountsASetOnItsWorkoutsDay() {
        // Blocks of 7 days ending 2026-07-14: the last starts 07-08. A workout started 07-07 23:30 with a set after midnight.
        val today = day("2026-07-14")
        val set = SetEntity(id = 1, workoutExerciseId = 1, position = 0, type = "normal", weightKg = 100.0, reps = 5, completedAt = millis("2026-07-08T00:10:00Z"))
        val trend = e1RmTrend(mapOf("bench" to listOf(set)), today, ZoneOffset.UTC, blocks = 2, blockDays = 7, startedAtOf = { millis("2026-07-07T23:30:00Z") })
        assertEquals(listOf(100.0 * (1 + 5 / 30.0), null), trend.single().bestE1RmKg)
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

    @Test
    fun bodyweightTrendRateNeedsThreeEntriesSpanningTwoWeeks() {
        // The review's case: two weigh-ins two days apart would read +3.5 lb/week.
        val twoDays = listOf(
            bw(id = 1, dateIso = "2026-07-11", weightKg = 82.0),
            bw(id = 2, dateIso = "2026-07-13", weightKg = 82.5),
        )
        assertNull(bodyweightTrend(twoDays, todayEpochDay = day("2026-07-14"))!!.rateKgPerWeek)

        // Two, however far apart: a line through two points is still two readings.
        val twoWeeks = listOf(
            bw(id = 1, dateIso = "2026-06-22", weightKg = 82.0),
            bw(id = 2, dateIso = "2026-07-13", weightKg = 81.0),
        )
        assertNull(bodyweightTrend(twoWeeks, todayEpochDay = day("2026-07-14"))!!.rateKgPerWeek)

        // Three, but only 13 days first to last: still water, not a trend.
        val thirteenDays = listOf(
            bw(id = 1, dateIso = "2026-06-30", weightKg = 82.0),
            bw(id = 2, dateIso = "2026-07-06", weightKg = 82.3),
            bw(id = 3, dateIso = "2026-07-13", weightKg = 82.5),
        )
        val short = bodyweightTrend(thirteenDays, todayEpochDay = day("2026-07-14"))!!
        assertNull(short.rateKgPerWeek)
        assertNull(short.rateSpanDays)

        // Two weeks apart, first to last: a rate, and the span it covers.
        val fourteenDays = listOf(
            bw(id = 1, dateIso = "2026-06-29", weightKg = 82.0),
            bw(id = 2, dateIso = "2026-07-06", weightKg = 82.5),
            bw(id = 3, dateIso = "2026-07-13", weightKg = 83.0),
        )
        val trend = bodyweightTrend(fourteenDays, todayEpochDay = day("2026-07-14"))!!
        assertEquals(0.5, trend.rateKgPerWeek!!, 1e-9)
        assertEquals(14, trend.rateSpanDays)
    }

    @Test
    fun bodyweightTrendSpanCountsOnlyTheWindow() {
        // An entry from before the 28 days neither counts toward the three nor stretches the span.
        val entries = listOf(
            bw(id = 1, dateIso = "2026-05-01", weightKg = 84.0),
            bw(id = 2, dateIso = "2026-06-10", weightKg = 82.0),
            bw(id = 3, dateIso = "2026-07-01", weightKg = 81.8),
            bw(id = 4, dateIso = "2026-07-13", weightKg = 81.4),
        )
        val trend = bodyweightTrend(entries, todayEpochDay = day("2026-07-14"))!!
        assertNull(trend.rateKgPerWeek)

        val later = bodyweightTrend(entries, todayEpochDay = day("2026-07-17"))!!
        assertNull(later.rateKgPerWeek)

        val inWindow = entries + bw(id = 5, dateIso = "2026-06-17", weightKg = 82.2)
        assertEquals(26, bodyweightTrend(inWindow, todayEpochDay = day("2026-07-14"))!!.rateSpanDays)
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

        val counts = workoutsPerWeek(workouts, weeks = 4, todayEpochDay = day("2026-07-14"), zone = ZoneOffset.UTC)

        assertEquals(listOf(2, 0, 1, 2), counts)
    }

    @Test
    fun workoutsPerWeekSingleWeekWindow() {
        val workouts = listOf(
            finished(id = 1, startIso = "2026-07-13T10:00:00Z"),
            // Sunday of the previous week: outside a 1-week window.
            finished(id = 2, startIso = "2026-07-12T10:00:00Z"),
        )

        val counts = workoutsPerWeek(workouts, weeks = 1, todayEpochDay = day("2026-07-14"), zone = ZoneOffset.UTC)

        assertEquals(listOf(1), counts)
    }

    @Test
    fun workoutsPerWeekAllZeroWithoutFinishedWorkouts() {
        val workouts = listOf(
            WorkoutEntity(id = 1, name = "In progress", startedAt = millis("2026-07-13T10:00:00Z")),
        )

        val counts = workoutsPerWeek(workouts, weeks = 3, todayEpochDay = day("2026-07-14"), zone = ZoneOffset.UTC)

        assertEquals(listOf(0, 0, 0), counts)
    }

    @Test
    fun workoutsPerWeekUsesTheLocalWeek() {
        // Sunday 2026-07-12 at 20:00 in Chicago is already Monday 01:00 UTC: it belongs to the
        // week before, as History shows it, not to the current week.
        val sundayEvening = listOf(finished(id = 1, startIso = "2026-07-13T01:00:00Z"))
        val chicago = ZoneId.of("America/Chicago")

        assertEquals(listOf(1, 0), workoutsPerWeek(sundayEvening, weeks = 2, todayEpochDay = day("2026-07-14"), zone = chicago))
        assertEquals(listOf(0, 1), workoutsPerWeek(sundayEvening, weeks = 2, todayEpochDay = day("2026-07-14"), zone = ZoneOffset.UTC))
    }

    // --- e1RmTrend ---

    @Test
    fun e1RmTrendTakesTheBestPerBlockForTheMostTrainedLifts() {
        // Three 7-day blocks ending Tuesday 2026-07-14: 06-24..06-30, 07-01..07-07, 07-08..07-14.
        val today = day("2026-07-14")
        assertEquals(listOf(day("2026-06-24"), day("2026-07-01"), day("2026-07-08")), trendBlockStarts(today, 3, 7))
        fun done(id: Long, iso: String, kg: Double?, reps: Int, type: String = "normal") = SetEntity(
            id = id, workoutExerciseId = 1, position = 0, type = type, weightKg = kg, reps = reps, completedAt = millis(iso),
        )
        val bench = listOf(
            done(1, "2026-06-23T10:00:00Z", 200.0, 1), // the day before the window
            done(2, "2026-06-25T10:00:00Z", 100.0, 5), // 116.67
            done(3, "2026-06-26T10:00:00Z", 105.0, 3), // 115.5
            done(4, "2026-06-26T10:05:00Z", 150.0, 5, type = "warmup"), // warm-ups never count
            done(5, "2026-07-14T10:00:00Z", 110.0, 5), // 128.33, the last block
            done(6, "2026-07-14T10:05:00Z", 90.0, 15), // over 12 reps: a working set, no e1RM
        )
        val squat = listOf(done(7, "2026-07-02T10:00:00Z", 140.0, 5), done(8, "2026-07-03T10:00:00Z", 140.0, 5))
        // Pull-ups have no weight: no e1RM, so no trend, however many sets.
        val pullUps = List(9) { done(10L + it, "2026-07-09T10:00:00Z", null, 10) }
        // Nine bodyweight sets and one weighted: only the weighted one ranks it, so it stays below squat.
        val chinUps = List(9) { done(20L + it, "2026-07-09T10:00:00Z", null, 10) } + done(30, "2026-07-10T10:00:00Z", 20.0, 5)

        val trend = e1RmTrend(
            mapOf("bench" to bench, "squat" to squat, "pullups" to pullUps, "chinups" to chinUps),
            today, ZoneOffset.UTC, blocks = 3, blockDays = 7,
        )

        assertEquals(listOf("bench", "squat", "chinups"), trend.map { it.exerciseId }, "most e1RM sets first; no weightless lift")
        val benchBest = trend.first().bestE1RmKg
        assertEquals(100.0 * (1 + 5 / 30.0), benchBest[0]!!, 1e-9)
        assertNull(benchBest[1])
        assertEquals(110.0 * (1 + 5 / 30.0), benchBest[2]!!, 1e-9)
        assertEquals(listOf("bench"), e1RmTrend(mapOf("bench" to bench, "squat" to squat), today, ZoneOffset.UTC, 3, 7, top = 1).map { it.exerciseId })
    }

    // --- restGapsSec ---

    @Test
    fun restGapsRunAcrossExercisesInTickOrder() {
        val t0 = millis("2026-07-13T10:00:00Z")
        val sets = listOf(
            // Ticked out of position order and across two slots (a superset).
            SetEntity(id = 1, workoutExerciseId = 1, position = 0, type = "warmup", reps = 8, completedAt = t0),
            SetEntity(id = 3, workoutExerciseId = 2, position = 0, type = "normal", reps = 8, completedAt = t0 + 150_000),
            SetEntity(id = 2, workoutExerciseId = 1, position = 1, type = "normal", reps = 5, completedAt = t0 + 90_000),
            // A break of more than 15 minutes: the gap says nothing about the set.
            SetEntity(id = 4, workoutExerciseId = 2, position = 1, type = "normal", reps = 8, completedAt = t0 + 150_000 + 901_000),
            SetEntity(id = 5, workoutExerciseId = 2, position = 2, type = "normal", reps = 8, completedAt = t0 + 150_000 + 901_000 + 900_000),
            // Never ticked: no gap, and it doesn't break the chain.
            SetEntity(id = 6, workoutExerciseId = 2, position = 3, type = "normal", reps = 8),
        )

        assertEquals(mapOf(2L to 90, 3L to 60, 5L to 900), restGapsSec(sets))
    }

    // --- workoutBodyweight ---

    @Test
    fun workoutBodyweightPrefersThatDayThenLooksBackThreeDays() {
        val chicago = ZoneId.of("America/Chicago")
        // Sunday 2026-07-12, 23:30 in Chicago (04:30 UTC on the 13th).
        val startedAt = millis("2026-07-13T04:30:00Z")
        val sunday = day("2026-07-12")
        fun entry(id: Long, epochDay: Long, kg: Double) = BodyweightEntity(id = id, epochDay = epochDay, weightKg = kg)

        // Logged the same local day (even after the workout): that one.
        val sameDay = workoutBodyweight(startedAt, listOf(entry(1, sunday - 1, 80.0), entry(2, sunday, 80.5), entry(3, sunday + 1, 79.0)), chicago)
        assertEquals(WorkoutBodyweight(80.5, sunday, sameDay = true), sameDay)
        // None that day: the latest of the three days before, marked as an earlier day.
        val earlier = workoutBodyweight(startedAt, listOf(entry(1, sunday - 3, 81.0), entry(2, sunday - 2, 80.8)), chicago)
        assertEquals(WorkoutBodyweight(80.8, sunday - 2, sameDay = false), earlier)
        // Four days back is too old, and the day after never counts.
        assertNull(workoutBodyweight(startedAt, listOf(entry(1, sunday - 4, 81.0), entry(2, sunday + 1, 80.0)), chicago))
        // In UTC the same instant is Monday the 13th, whose entry is the Monday one.
        assertEquals(sunday + 1, workoutBodyweight(startedAt, listOf(entry(2, sunday, 80.5), entry(3, sunday + 1, 79.0)), ZoneOffset.UTC)?.epochDay)
    }

    // --- weeksSince ---

    @Test
    fun weeksSinceCountsBothEnds() {
        // Monday 2026-06-22 through Tuesday 2026-07-14: 4 weeks.
        assertEquals(4, weeksSince(millis("2026-06-22T06:00:00Z"), day("2026-07-14"), ZoneOffset.UTC))
        assertEquals(1, weeksSince(millis("2026-07-13T06:00:00Z"), day("2026-07-14"), ZoneOffset.UTC))
    }

    @Test
    fun weeksSinceIsNeverEmptyForAFutureWorkout() {
        // Logged while the clock ran weeks ahead (#129): the window stays one week, never 0 or less,
        // so Consistency opens instead of crashing.
        val future = millis("2026-08-20T10:00:00Z")
        assertEquals(1, weeksSince(future, day("2026-07-14"), ZoneOffset.UTC))
        val counts = workoutsPerWeek(
            listOf(finished(id = 1, startIso = "2026-08-20T10:00:00Z")),
            weeks = weeksSince(future, day("2026-07-14"), ZoneOffset.UTC),
            todayEpochDay = day("2026-07-14"),
            zone = ZoneOffset.UTC,
        )
        assertEquals(listOf(0), counts)
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
