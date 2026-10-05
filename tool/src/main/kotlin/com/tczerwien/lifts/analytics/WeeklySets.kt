package com.tczerwien.lifts.analytics

import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import com.tczerwien.lifts.session.RowKind
import com.tczerwien.lifts.session.rowKinds
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Weekly sets per muscle (WS-1): the one rule the Sets per Muscle screen and the exports share. Hard sets,
 * counted directly:
 * - A set counts when it is ticked in a finished workout: unticked sets and open workouts count nowhere.
 * - In the ISO week (Monday first, in the phone's zone) its workout started in, as History dates it: a
 *   Sunday workout that runs past midnight is all Sunday's week.
 * - A work set adds 1, a set to failure too, and so does a deload's. A warm-up adds 0, and so does a drop:
 *   it is part of the set above it, one hard set with it. Which rows are drops is read from the exercise's
 *   ticked rows ([rowKinds]), as PREVIOUS and Start Again read last session, so a drop whose set wasn't
 *   ticked is a set of its own.
 * - The 1 goes to each muscle its exercise counts for ([countsFor]): the Counts For he chose, else the
 *   catalog's primary muscles. Secondary muscles add nothing. A slot swapped to an alternative counts for
 *   the lift done.
 * Pure, covered by WeeklySetsTest.
 */

/** One exercise of a finished workout with a ticked set, as the weekly rule reads it. */
data class CountedSlot(
    /** Its workout's start: the week its sets count in. */
    val startedAt: Long,
    /** What its exercise counts for ([countsFor]); none for an exercise no longer in the log. */
    val muscles: List<String>,
    /** Its ticked sets' types, in position order. */
    val tickedTypes: List<String>,
    /** Its workout was a deload: its sets count all the same, and its week says so ([deloadWeeks]). */
    val deload: Boolean = false,
)

/** The muscles a set of [exercise] counts 1 for: its Counts For, else the catalog's primary muscles. */
fun countsFor(exercise: ExerciseEntity): List<String> = countsFor(exercise.countsFor, exercise.primaryMuscles)

/** [countsFor] from the two columns: [countsFor] when he chose any, else [primaryMuscles]; each once, as [muscleKeys]. */
fun countsFor(countsFor: List<String>, primaryMuscles: List<String>): List<String> =
    muscleKeys(countsFor).ifEmpty { muscleKeys(primaryMuscles) }

/** The hard sets among one exercise's ticked rows ([tickedTypes], in position order): its work sets, not warm-ups or drops. */
fun hardSets(tickedTypes: List<String>): Int = rowKinds(tickedTypes).count { it == RowKind.WORK }

/**
 * Every exercise of a finished workout with a ticked set, from the log's rows: what the rule reads of a
 * whole log (the exports). An exercise missing from [exercises] counts for nothing.
 */
fun countedSlots(
    exercises: List<ExerciseEntity>,
    workouts: List<WorkoutEntity>,
    workoutExercises: List<WorkoutExerciseEntity>,
    sets: List<SetEntity>,
): List<CountedSlot> {
    val finished = workouts.filter { it.endedAt != null }.associateBy { it.id }
    val muscles = exercises.associate { it.id to countsFor(it) }
    val ticked = sets.filter { it.completedAt != null }.groupBy { it.workoutExerciseId }
    return workoutExercises.mapNotNull { slot ->
        val workout = finished[slot.workoutId] ?: return@mapNotNull null
        val rows = ticked[slot.id]?.sortedWith(compareBy({ it.position }, { it.id })) ?: return@mapNotNull null
        CountedSlot(workout.startedAt, muscles[slot.exerciseId].orEmpty(), rows.map { it.type }, workout.deload)
    }
}

/**
 * Hard sets per muscle over the last [weeks] ISO weeks ending at [todayEpochMillis]'s week (in [zone]),
 * oldest first, so this week is the last element. A muscle with no set in the window is left out; keys
 * are sorted alphabetically.
 */
fun weeklySetsPerMuscle(slots: List<CountedSlot>, weeks: Int, todayEpochMillis: Long, zone: ZoneId): Map<String, List<Int>> {
    require(weeks > 0) { "weeks must be positive" }
    val firstMonday = firstMonday(todayEpochMillis, weeks, zone)
    val buckets = HashMap<String, IntArray>()
    for (slot in slots) {
        val index = weekIndex(slot.startedAt, firstMonday, zone).takeIf { it in 0 until weeks } ?: continue
        val hard = hardSets(slot.tickedTypes)
        if (hard == 0) continue
        for (muscle in slot.muscles) buckets.getOrPut(muscle) { IntArray(weeks) }[index] += hard
    }
    return buckets.entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { it.key to it.value.toList() }
}

/** [weeklySetsPerMuscle] over a whole log's rows ([countedSlots]). */
fun weeklySetsPerMuscle(
    exercises: List<ExerciseEntity>,
    workouts: List<WorkoutEntity>,
    workoutExercises: List<WorkoutExerciseEntity>,
    sets: List<SetEntity>,
    weeks: Int,
    todayEpochMillis: Long,
    zone: ZoneId,
): Map<String, List<Int>> = weeklySetsPerMuscle(countedSlots(exercises, workouts, workoutExercises, sets), weeks, todayEpochMillis, zone)

/** Whether each of the same [weeks] weeks as [weeklySetsPerMuscle] (oldest first) had a deload workout among [slots]. */
fun deloadWeeks(slots: List<CountedSlot>, weeks: Int, todayEpochMillis: Long, zone: ZoneId): List<Boolean> {
    require(weeks > 0) { "weeks must be positive" }
    val firstMonday = firstMonday(todayEpochMillis, weeks, zone)
    val deload = BooleanArray(weeks)
    for (slot in slots) {
        if (!slot.deload) continue
        weekIndex(slot.startedAt, firstMonday, zone).takeIf { it in 0 until weeks }?.let { deload[it] = true }
    }
    return deload.toList()
}

/** The Monday that starts the first of the [weeks] weeks ending at [todayEpochMillis]'s week, in [zone]. */
fun firstMonday(todayEpochMillis: Long, weeks: Int, zone: ZoneId): LocalDate =
    mondayOf(Instant.ofEpochMilli(todayEpochMillis).atZone(zone).toLocalDate()).minusWeeks((weeks - 1).toLong())

/** The Monday of [date]'s ISO week. */
fun mondayOf(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())

/** Weeks from [firstMonday] to the week [startedAt] falls in (in [zone]); negative before it. */
private fun weekIndex(startedAt: Long, firstMonday: LocalDate, zone: ZoneId): Int =
    ChronoUnit.WEEKS.between(firstMonday, mondayOf(Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate())).toInt()

/** The most sets a week a target can ask of one muscle: Sets per Muscle's stepper runs 0 (none) to this. */
const val MAX_SET_TARGET = 40
