package com.tczerwien.lifts.analytics

import com.tczerwien.lifts.data.BodyweightEntity
import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Pure-Kotlin analytics over the Lifts entities. No Android, no SDK, no reflection.
 *
 * Conventions (shared with the export layer):
 * - A set counts as "completed" when [SetEntity.completedAt] is non-null.
 * - Warm-up sets ([SetEntity.type] == "warmup") never contribute to PRs or volume.
 * - Epoch-millis timestamps are bucketed by calendar date/week in the zone the caller passes: the
 *   phone's own zone in the app, so a Sunday-evening workout counts in the week the screens show;
 *   tests pass a fixed zone.
 */

private const val SET_TYPE_WARMUP = "warmup"

/**
 * Epley estimated one-rep max: `weightKg * (1 + reps / 30.0)`. A single is its own 1RM (the
 * formula would inflate it by 3.3%). Valid for 1..12 reps (estimates degrade quickly beyond
 * that); returns null otherwise.
 */
fun epleyE1RmKg(weightKg: Double, reps: Int): Double? {
    if (reps !in 1..12) return null
    if (reps == 1) return weightKg
    return weightKg * (1 + reps / 30.0)
}

/**
 * Personal records for one exercise, computed from completed non-warmup sets.
 *
 * @property maxWeightKg heaviest weight ever moved (any rep count)
 * @property maxWeightDate epoch millis of the set that first achieved [maxWeightKg]
 * @property bestE1RmKg best Epley estimate, null when no completed set had 1..12 reps
 * @property bestE1RmDate epoch millis of the set that first achieved [bestE1RmKg]
 * @property bestSet the best-e1RM set, falling back to the max-weight set when no e1RM exists;
 *   the export writes it in the display unit ([setNotation])
 */
data class ExercisePr(
    val exerciseId: String,
    val maxWeightKg: Double,
    val maxWeightDate: Long,
    val bestE1RmKg: Double?,
    val bestE1RmDate: Long?,
    val bestSet: SetEntity,
)

/**
 * Computes one [ExercisePr] per exercise that has at least one record-eligible weighted set
 * (see [recordEligible]: completed, not a warm-up, at least one rep). Sets are joined
 * through [workoutExercises] and must belong to a workout present in [workouts]. Result order
 * follows the [exercises] list; any exercise ids referenced by sets but missing from
 * [exercises] are appended sorted by id. Weights within [WEIGHT_TOLERANCE_KG] tie, and ties keep
 * the first set to reach a value.
 */
fun computePrs(
    exercises: List<ExerciseEntity>,
    workouts: List<WorkoutEntity>,
    workoutExercises: List<WorkoutExerciseEntity>,
    sets: List<SetEntity>,
): List<ExercisePr> {
    val workoutIds = workouts.mapTo(HashSet()) { it.id }
    val weById = workoutExercises.filter { it.workoutId in workoutIds }.associateBy { it.id }

    val setsByExercise = HashMap<String, MutableList<SetEntity>>()
    for (set in sets) {
        if (!recordEligible(set) || (set.weightKg ?: 0.0) <= 0.0) continue
        val slot = weById[set.workoutExerciseId] ?: continue
        setsByExercise.getOrPut(slot.exerciseId) { mutableListOf() }.add(set)
    }

    val orderedIds = exercises.map { it.id }.filter { it in setsByExercise } +
        (setsByExercise.keys - exercises.mapTo(HashSet()) { it.id }).sorted()

    val result = mutableListOf<ExercisePr>()
    for (exerciseId in orderedIds) {
        val ordered = setsByExercise.getValue(exerciseId)
            .sortedWith(compareBy({ it.completedAt }, { it.id }))

        var maxWeight: Double? = null
        var maxWeightDate = 0L
        var maxWeightSet: SetEntity? = null
        var bestE1Rm: Double? = null
        var bestE1RmDate: Long? = null
        var bestE1RmSet: SetEntity? = null

        for (set in ordered) {
            val weight = set.weightKg ?: continue
            val currentMax = maxWeight
            if (currentMax == null || weight > currentMax + WEIGHT_TOLERANCE_KG) {
                maxWeight = weight
                maxWeightDate = set.completedAt ?: 0L
                maxWeightSet = set
            }
            val e1Rm = set.reps?.let { epleyE1RmKg(weight, it) }
            val currentBest = bestE1Rm
            if (e1Rm != null && (currentBest == null || e1Rm > currentBest + WEIGHT_TOLERANCE_KG)) {
                bestE1Rm = e1Rm
                bestE1RmDate = set.completedAt
                bestE1RmSet = set
            }
        }

        val finalMaxWeight = maxWeight ?: continue // no weighted sets -> no PR entry
        val notationSet = bestE1RmSet ?: maxWeightSet ?: continue
        result.add(
            ExercisePr(
                exerciseId = exerciseId,
                maxWeightKg = finalMaxWeight,
                maxWeightDate = maxWeightDate,
                bestE1RmKg = bestE1Rm,
                bestE1RmDate = bestE1RmDate,
                bestSet = notationSet,
            ),
        )
    }
    return result
}

/**
 * Completed non-warmup set counts per muscle over the last [weeks] ISO weeks
 * (Monday-based, in [zone]), newest week last. The current week is the last element.
 * Each completed non-warmup set adds 1.0 to every primary muscle of its exercise
 * and 0.5 to every secondary muscle. Muscles with no contribution in the window
 * are omitted; keys are sorted alphabetically.
 */
fun weeklySetsPerMuscle(
    exercises: List<ExerciseEntity>,
    workouts: List<WorkoutEntity>,
    workoutExercises: List<WorkoutExerciseEntity>,
    sets: List<SetEntity>,
    weeks: Int,
    todayEpochMillis: Long,
    zone: ZoneId,
): Map<String, List<Double>> {
    require(weeks > 0) { "weeks must be positive" }
    val today = Instant.ofEpochMilli(todayEpochMillis).atZone(zone).toLocalDate()
    val currentMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val firstMonday = currentMonday.minusWeeks((weeks - 1).toLong())

    val exercisesById = exercises.associateBy { it.id }
    val workoutIds = workouts.mapTo(HashSet()) { it.id }
    val weById = workoutExercises.filter { it.workoutId in workoutIds }.associateBy { it.id }

    val buckets = HashMap<String, DoubleArray>()
    for (set in sets) {
        val completedAt = set.completedAt ?: continue
        if (set.type == SET_TYPE_WARMUP) continue
        val slot = weById[set.workoutExerciseId] ?: continue
        val exercise = exercisesById[slot.exerciseId] ?: continue

        val date = Instant.ofEpochMilli(completedAt).atZone(zone).toLocalDate()
        val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
        val index = ChronoUnit.WEEKS.between(firstMonday, monday).toInt()
        if (index < 0 || index >= weeks) continue

        for (muscle in exercise.primaryMuscles) {
            buckets.getOrPut(muscle) { DoubleArray(weeks) }[index] += 1.0
        }
        for (muscle in exercise.secondaryMuscles) {
            buckets.getOrPut(muscle) { DoubleArray(weeks) }[index] += 0.5
        }
    }
    return buckets.entries.sortedBy { it.key }
        .associateTo(LinkedHashMap()) { it.key to it.value.toList() }
}

/**
 * Bodyweight trend derived from daily [BodyweightEntity] measurements.
 *
 * @property currentKg weight of the most recent entry
 * @property currentEpochDay epoch day of the most recent entry
 * @property weeklyAvgKg average weight per ISO week (Monday-based, UTC), one element per
 *   week ending at today's week, oldest first; null for weeks with no entry
 * @property rateKgPerWeek least-squares slope in kg/week over entries in the last
 *   28 days, null when fewer than 2 entries fall in that window
 */
data class BodyweightTrend(
    val currentKg: Double,
    val currentEpochDay: Long,
    val weeklyAvgKg: List<Double?>,
    val rateKgPerWeek: Double?,
)

/**
 * Computes the [BodyweightTrend] over [entries], or null when [entries] is empty.
 *
 * [weeklyAvgKg][BodyweightTrend.weeklyAvgKg] covers the last [weeks] ISO weeks ending at
 * [todayEpochDay]'s week; entries before that window are ignored. The rate window is the
 * 28 days ending at [todayEpochDay] (inclusive).
 */
fun bodyweightTrend(
    entries: List<BodyweightEntity>,
    weeks: Int = 8,
    todayEpochDay: Long,
): BodyweightTrend? {
    require(weeks > 0) { "weeks must be positive" }
    if (entries.isEmpty()) return null

    val latest = entries.maxWith(compareBy({ it.epochDay }, { it.id }))

    val today = LocalDate.ofEpochDay(todayEpochDay)
    val currentMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val firstMonday = currentMonday.minusWeeks((weeks - 1).toLong())

    val sums = DoubleArray(weeks)
    val counts = IntArray(weeks)
    for (entry in entries) {
        val date = LocalDate.ofEpochDay(entry.epochDay)
        val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
        val index = ChronoUnit.WEEKS.between(firstMonday, monday).toInt()
        if (index < 0 || index >= weeks) continue
        sums[index] += entry.weightKg
        counts[index]++
    }
    val weeklyAvg = List(weeks) { i -> if (counts[i] > 0) sums[i] / counts[i] else null }

    val window = entries.filter { it.epochDay in (todayEpochDay - 27)..todayEpochDay }
    val rate = if (window.size < 2) {
        null
    } else {
        val xBar = window.sumOf { it.epochDay.toDouble() } / window.size
        val yBar = window.sumOf { it.weightKg } / window.size
        val numerator = window.sumOf { (it.epochDay - xBar) * (it.weightKg - yBar) }
        val denominator = window.sumOf { (it.epochDay - xBar) * (it.epochDay - xBar) }
        7.0 * numerator / denominator
    }

    return BodyweightTrend(
        currentKg = latest.weightKg,
        currentEpochDay = latest.epochDay,
        weeklyAvgKg = weeklyAvg,
        rateKgPerWeek = rate,
    )
}

/**
 * Finished workouts per ISO week (Monday-based) over the last [weeks] weeks ending
 * at [todayEpochDay]'s week — oldest week first, so the current week is the last element.
 * Only finished workouts ([WorkoutEntity.endedAt] != null) count, bucketed by the calendar
 * date of their start time in [zone].
 */
fun workoutsPerWeek(
    workouts: List<WorkoutEntity>,
    weeks: Int,
    todayEpochDay: Long,
    zone: ZoneId,
): List<Int> {
    require(weeks > 0) { "weeks must be positive" }
    val today = LocalDate.ofEpochDay(todayEpochDay)
    val currentMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val firstMonday = currentMonday.minusWeeks((weeks - 1).toLong())

    val counts = IntArray(weeks)
    for (workout in workouts) {
        if (workout.endedAt == null) continue
        val date = Instant.ofEpochMilli(workout.startedAt).atZone(zone).toLocalDate()
        val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
        val index = ChronoUnit.WEEKS.between(firstMonday, monday).toInt()
        if (index < 0 || index >= weeks) continue
        counts[index]++
    }
    return counts.toList()
}

/**
 * The longest gap that still reads as a rest between two sets. Past it he took a break, answered the
 * phone or moved on to something else, and the gap says nothing about the next set.
 */
const val REST_GAP_MAX_SEC = 15 * 60

/**
 * Seconds from the previous ticked set of the same workout to each ticked set, by set id, taken from
 * when they were ticked: the rest before the set plus the time the set itself took, so a little
 * longer than the rest alone. Sets are ordered by completion time across all of the workout's
 * exercises. The first set has none, and neither has a set after a gap over [REST_GAP_MAX_SEC].
 * Pass one workout's sets; unticked ones are ignored.
 */
fun restGapsSec(sets: List<SetEntity>): Map<Long, Int> {
    val ordered = sets.filter { it.completedAt != null }.sortedWith(compareBy({ it.completedAt }, { it.id }))
    val gaps = HashMap<Long, Int>()
    for (i in 1 until ordered.size) {
        val gapSec = ((ordered[i].completedAt!! - ordered[i - 1].completedAt!!) / 1000L).toInt()
        if (gapSec <= REST_GAP_MAX_SEC) gaps[ordered[i].id] = gapSec
    }
    return gaps
}

/** How many days before a workout a weigh-in may be and still stand for it. */
const val WORKOUT_BODYWEIGHT_LOOKBACK_DAYS = 3

/** The bodyweight that goes with a workout: [sameDay] when it was logged the day the workout started. */
data class WorkoutBodyweight(val weightKg: Double, val epochDay: Long, val sameDay: Boolean)

/**
 * The weigh-in for a workout started at [startedAt]: the entry of that local day in [zone] (logged
 * before or after the workout), else the latest one from the [WORKOUT_BODYWEIGHT_LOOKBACK_DAYS] days
 * before it; null when there is none. Worked out when needed rather than stored, so a weigh-in logged
 * after the workout, or corrected later, still shows.
 */
fun workoutBodyweight(startedAt: Long, entries: List<BodyweightEntity>, zone: ZoneId): WorkoutBodyweight? {
    val day = workoutEpochDay(startedAt, zone)
    val entry = entries
        .filter { it.epochDay in (day - WORKOUT_BODYWEIGHT_LOOKBACK_DAYS)..day }
        .maxWithOrNull(compareBy({ it.epochDay }, { it.id }))
        ?: return null
    return WorkoutBodyweight(entry.weightKg, entry.epochDay, sameDay = entry.epochDay == day)
}

/** The local day (epoch day in [zone]) a workout started at [startedAt] belongs to. */
fun workoutEpochDay(startedAt: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate().toEpochDay()

/**
 * ISO weeks from the week of [firstEpochMillis] (in [zone]) through [todayEpochDay]'s week, counting
 * both: the window [workoutsPerWeek] needs to cover a whole history. At least 1, so a workout dated in
 * a future week (a clock set wrong when it was logged) can't make an empty window.
 */
fun weeksSince(firstEpochMillis: Long, todayEpochDay: Long, zone: ZoneId): Int {
    val first = Instant.ofEpochMilli(firstEpochMillis).atZone(zone).toLocalDate()
    val firstMonday = first.minusDays((first.dayOfWeek.value - 1).toLong())
    val today = LocalDate.ofEpochDay(todayEpochDay)
    val currentMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    return (ChronoUnit.WEEKS.between(firstMonday, currentMonday) + 1).toInt().coerceAtLeast(1)
}

/**
 * Length of the longest run of consecutive entries in [weeklyCounts] that reach at least
 * [threshold] — e.g. the longest streak of weeks with 3+ workouts over a
 * [workoutsPerWeek] series. Returns 0 when no entry reaches the threshold.
 */
fun longestStreak(weeklyCounts: List<Int>, threshold: Int): Int {
    var best = 0
    var current = 0
    for (count in weeklyCounts) {
        if (count >= threshold) {
            current++
            if (current > best) best = current
        } else {
            current = 0
        }
    }
    return best
}
