package com.tczerwien.lifts.analytics

import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutWithSetCount
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class ConsistencyTest {

    private val utc = ZoneOffset.UTC

    /** Tuesday: the current ISO week started Monday 2026-09-28. */
    private val today = LocalDate.of(2026, 9, 29)

    private var nextId = 1L

    /** A workout started at 18:00 UTC on [date], [sets] ticked sets, finished unless [open]. */
    private fun workout(date: LocalDate, sets: Int = 12, open: Boolean = false): WorkoutWithSetCount {
        val start = date.atTime(18, 0).toInstant(utc).toEpochMilli()
        val workout = WorkoutEntity(id = nextId++, name = "Upper", startedAt = start, endedAt = if (open) null else start + 3_600_000)
        return WorkoutWithSetCount(workout, sets)
    }

    /** Four a week, Monday, Tuesday, Thursday, Friday, for [weeks] weeks ending with last week. */
    private fun steady(weeks: Int): List<WorkoutWithSetCount> {
        val lastMonday = today.minusDays(1).minusWeeks(1)
        return (0 until weeks).flatMap { back ->
            val monday = lastMonday.minusWeeks(back.toLong())
            listOf(0L, 1L, 3L, 4L).map { workout(monday.plusDays(it)) }
        }
    }

    @Test
    fun `a workout without a ticked set counts nowhere`() {
        val workouts = steady(3) + workout(today, sets = 0) + workout(today.minusDays(1), sets = 0)
        val summary = consistencySummary(workouts, today.toEpochDay(), utc)
        assertEquals(0, summary.thisWeek, "opened and closed by mistake, or closed only for its note")
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 0, 4, 4, 4, 0), summary.counts)
    }

    @Test
    fun `an unfinished workout counts nowhere`() {
        val summary = consistencySummary(steady(1) + workout(today, open = true), today.toEpochDay(), utc)
        assertEquals(0, summary.thisWeek)
    }

    @Test
    fun `the average leaves out the week in progress`() {
        // A steady 4 a week, Tuesday of a week with one done so far: 4.0, not (4+4+4+1)/4.
        val summary = consistencySummary(steady(6) + workout(today.minusDays(1)), today.toEpochDay(), utc)
        assertEquals(1, summary.thisWeek)
        assertEquals(4.0, summary.fourWeekAverage)
        // The mockup's bars: the last four complete weeks, 4 3 5 4, average 4.0.
        assertEquals(4.0, completeWeeksAverage(listOf(3, 4, 3, 2, 0, 3, 4, 4, 3, 5, 4, 3), weeks = 4))
        assertEquals(0.0, completeWeeksAverage(listOf(2), weeks = 4), "only the week in progress")
        assertEquals(2.5, completeWeeksAverage(listOf(2, 3, 9), weeks = 4), "fewer complete weeks than asked")
    }

    @Test
    fun `the streak counts weeks of three or more over the whole history, not just the chart`() {
        val old = (0 until 20).flatMap { week ->
            val monday = today.minusDays(1).minusWeeks(40L + week)
            listOf(0L, 2L, 4L).map { workout(monday.plusDays(it)) }
        }
        val summary = consistencySummary(old + steady(2), today.toEpochDay(), utc)
        assertEquals(20, summary.longestStreakWeeks)
        // Twice a week for months is no streak: the screen says the rule is 3+.
        val twice = (0 until 10).flatMap { week ->
            val monday = today.minusDays(1).minusWeeks(1L + week)
            listOf(0L, 3L).map { workout(monday.plusDays(it)) }
        }
        assertEquals(0, consistencySummary(twice, today.toEpochDay(), utc).longestStreakWeeks)
        // A streak of 0-set workouts is no streak either.
        val empty = (0 until 5).flatMap { week ->
            val monday = today.minusDays(1).minusWeeks(1L + week)
            listOf(0L, 1L, 3L).map { workout(monday.plusDays(it), sets = 0) }
        }
        assertEquals(0, consistencySummary(empty, today.toEpochDay(), utc).longestStreakWeeks)
    }

    @Test
    fun `month labels go under the first bar and each week that starts a new month`() {
        val summary = consistencySummary(emptyList(), today.toEpochDay(), utc)
        // Mondays from 2026-07-13 to 2026-09-28.
        assertEquals(
            listOf("Jul", null, null, "Aug", null, null, null, null, "Sep", null, null, null),
            summary.monthLabels,
        )
        assertEquals(CONSISTENCY_CHART_WEEKS, summary.counts.size)
    }

    @Test
    fun `weeks are the phone's`() {
        // Sunday 20:00 in Chicago is Monday 01:00 UTC: last week's, as History dates it.
        val chicago = ZoneId.of("America/Chicago")
        val sundayEvening = today.minusDays(2).atTime(20, 0).atZone(chicago).toInstant().toEpochMilli()
        val workout = WorkoutWithSetCount(WorkoutEntity(id = 1, name = "Lower", startedAt = sundayEvening, endedAt = sundayEvening + 1), 9)
        val summary = consistencySummary(listOf(workout), today.toEpochDay(), chicago)
        assertEquals(0, summary.thisWeek)
        assertEquals(1, summary.counts[CONSISTENCY_CHART_WEEKS - 2])
    }
}
