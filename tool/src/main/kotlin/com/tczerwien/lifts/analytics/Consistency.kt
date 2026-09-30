package com.tczerwien.lifts.analytics

import com.tczerwien.lifts.data.WorkoutWithSetCount
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Weeks shown in the Consistency chart (design/screens/consistency.html: the last 12 ISO weeks). */
const val CONSISTENCY_CHART_WEEKS = 12

/** A week counts toward the streak when it has at least this many workouts ("Longest streak (3+/wk)"). */
const val STREAK_THRESHOLD_WORKOUTS = 3

/** Complete weeks averaged for the "4-week average" row. */
const val AVERAGE_WEEKS = 4

private val MONTH_FORMATTER = DateTimeFormatter.ofPattern("MMM", Locale.US)

/**
 * What the Consistency screen shows. [counts] is workouts per ISO week, oldest first, the current
 * week last; [monthLabels] names the month under the first bar and every bar whose week starts a new
 * month.
 */
data class ConsistencySummary(
    val counts: List<Int>,
    val monthLabels: List<String?>,
    val thisWeek: Int,
    /** The last [AVERAGE_WEEKS] complete weeks: the week in progress would pull it down until Sunday. */
    val fourWeekAverage: Double,
    /** Weeks in a row with [STREAK_THRESHOLD_WORKOUTS] or more, over his whole history. */
    val longestStreakWeeks: Int,
)

/**
 * The Consistency screen's numbers from every workout with its ticked-set count, as History lists
 * them. Only a workout that is finished and has a ticked set counts: one opened and closed by mistake,
 * or closed only for its note, is in History as "0 sets" but was no training, and the exports leave it
 * out too. Weeks are the phone's ([zone]) calendar weeks, Monday first, as the other screens date
 * workouts: by their start.
 */
fun consistencySummary(workouts: List<WorkoutWithSetCount>, todayEpochDay: Long, zone: ZoneId): ConsistencySummary {
    val trained = workouts.filter { it.workout.endedAt != null && it.completedSets > 0 }.map { it.workout }
    val counts = workoutsPerWeek(trained, weeks = CONSISTENCY_CHART_WEEKS, todayEpochDay = todayEpochDay, zone = zone)
    val today = LocalDate.ofEpochDay(todayEpochDay)
    val currentMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val monthLabels = List(CONSISTENCY_CHART_WEEKS) { index ->
        val monday = currentMonday.minusWeeks((CONSISTENCY_CHART_WEEKS - 1 - index).toLong())
        if (index == 0 || monday.month != monday.minusWeeks(1).month) MONTH_FORMATTER.format(monday) else null
    }
    val streak = if (trained.isEmpty()) {
        0
    } else {
        val spanWeeks = weeksSince(trained.minOf { it.startedAt }, todayEpochDay, zone)
        longestStreak(workoutsPerWeek(trained, spanWeeks, todayEpochDay, zone), STREAK_THRESHOLD_WORKOUTS)
    }
    return ConsistencySummary(
        counts = counts,
        monthLabels = monthLabels,
        thisWeek = counts.last(),
        fourWeekAverage = completeWeeksAverage(counts, AVERAGE_WEEKS),
        longestStreakWeeks = streak,
    )
}

/**
 * The average of the [weeks] weeks before the last one of [weeklyCounts] (oldest first, the week in
 * progress last): training a steady 4 a week reads 4.0 on Monday morning, not 3.0. 0 without any.
 */
fun completeWeeksAverage(weeklyCounts: List<Int>, weeks: Int): Double =
    weeklyCounts.dropLast(1).takeLast(weeks).takeIf { it.isNotEmpty() }?.average() ?: 0.0
