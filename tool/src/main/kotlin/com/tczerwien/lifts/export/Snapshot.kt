package com.tczerwien.lifts.export

import com.tczerwien.lifts.analytics.bodyweightTrend
import com.tczerwien.lifts.analytics.chronologicalPrSetIds
import com.tczerwien.lifts.analytics.computePrs
import com.tczerwien.lifts.analytics.weeklySetsPerMuscle
import com.tczerwien.lifts.data.LiftsRows
import com.tczerwien.lifts.data.WorkoutEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Export snapshot model — plain data classes decoupled from Room, built once from entity
 * lists and rendered by [MarkdownExport], [JsonExport] and [CsvExport]. Dates and times are the
 * phone's local ones ([ExportSnapshot.zone]), as the screens show them: an evening workout keeps
 * its day. Tests pass a fixed zone.
 */

/** Bumped when the export shape changes: minor for additive changes, major for breaking ones. */
const val EXPORT_SCHEMA_VERSION = "1.2"

/** Number of ISO weeks in the precomputed weekly-sets-per-muscle summary. */
const val WEEKLY_SUMMARY_WEEKS = 8

data class ExportSnapshot(
    val schemaVersion: String,
    /** With its offset, e.g. 2026-07-14T07:00:00-05:00. */
    val exportedAtIso: String,
    /** The zone every date and time is in, e.g. America/Chicago. */
    val zone: ZoneId,
    val goals: String?,
    val injuries: List<String>,
    /** User-defined implements, ordered by position. */
    val bars: List<SnapshotBar>,
    /** Sorted oldest first. */
    val bodyweightLog: List<BodyweightPoint>,
    /** Trend derived from [bodyweightLog]; null when there are no bodyweight entries. */
    val bodyweightTrend: SnapshotBodyweightTrend?,
    /** Only exercises referenced by [workouts], sorted by name. */
    val exercises: List<SnapshotExercise>,
    /** Newest first. */
    val workouts: List<SnapshotWorkout>,
    /** Sorted by exercise name. */
    val prs: List<SnapshotPr>,
    /** Muscle -> per-week set counts, newest week last ([WEEKLY_SUMMARY_WEEKS] entries). */
    val weeklySetsPerMuscle: Map<String, List<Double>>,
)

data class BodyweightPoint(val dateIso: String, val weightKg: Double)

data class SnapshotBar(val name: String, val weightKg: Double)

data class SnapshotBodyweightTrend(
    val currentKg: Double,
    /** yyyy-MM-dd of the most recent entry. */
    val currentDateIso: String,
    /** [WEEKLY_SUMMARY_WEEKS] ISO-week averages, oldest first; null for weeks with no entry. */
    val weeklyAvgKg: List<Double?>,
    /** Least-squares slope over the last 28 days; null with fewer than 2 entries there. */
    val rateKgPerWeek: Double?,
)

data class SnapshotExercise(
    val id: String,
    val name: String,
    val equipment: String?,
    val primaryMuscles: List<String>,
    val secondaryMuscles: List<String>,
)

data class SnapshotWorkout(
    val id: Long,
    /** Local yyyy-MM-dd of [startEpochMillis]. */
    val dateIso: String,
    /** Local ISO-8601 time with its offset, e.g. 2026-07-13T19:30:00-05:00. */
    val startTimeIso: String,
    val startEpochMillis: Long,
    val name: String,
    /** Rounded minutes; null while the workout is still in progress. */
    val durationMin: Int?,
    val bodyweightKg: Double?,
    val notes: String?,
    /** Ordered by position; only slots with at least one completed set. */
    val exercises: List<SnapshotWorkoutExercise>,
)

data class SnapshotWorkoutExercise(
    val exerciseId: String,
    val name: String,
    val primaryMuscles: List<String>,
    val secondaryMuscles: List<String>,
    val notes: String?,
    /** Completed sets only, ordered by position, [SnapshotSet.index] 1-based. */
    val sets: List<SnapshotSet>,
)

data class SnapshotSet(
    val index: Int,
    val type: String,
    val weightKg: Double?,
    val reps: Int?,
    val durationSec: Int?,
    val rpe: Double?,
    val restSec: Int?,
    val isPr: Boolean,
)

data class SnapshotPr(
    val exerciseId: String,
    val exerciseName: String,
    val maxWeightKg: Double,
    val maxWeightDateIso: String,
    val bestE1RmKg: Double?,
    val bestE1RmDateIso: String?,
    val bestSetNotation: String,
)

/**
 * Builds the export snapshot from every row of the database ([rows]).
 *
 * Rules:
 * - Only completed sets (completedAt != null) are exported; workouts and exercise slots
 *   with no completed sets are dropped entirely.
 * - PR flags are computed chronologically per exercise (by completedAt, ties by id)
 *   across the full history — see [isPr].
 * - The weekly-volume summary covers the [WEEKLY_SUMMARY_WEEKS] ISO weeks ending at
 *   [exportedAtMillis], in [zone].
 *
 * @param exportedAtMillis when the export was made
 * @param zone the phone's zone: every date, time and week is local to it
 * @param goals optional free-text athlete goals for the front matter / profile
 * @param injuries current injuries/limitations, possibly empty
 */
fun buildSnapshot(
    rows: LiftsRows,
    exportedAtMillis: Long,
    zone: ZoneId,
    goals: String?,
    injuries: List<String>,
): ExportSnapshot {
    val exercises = rows.exercises
    val workouts = rows.workouts
    val workoutExercises = rows.workoutExercises
    val sets = rows.sets
    val bodyweight = rows.bodyweight
    val bars = rows.bars
    val format = ExportFormat(zone)
    val exercisesById = exercises.associateBy { it.id }
    val workoutIds = workouts.mapTo(HashSet()) { it.id }
    val validSlots = workoutExercises.filter { it.workoutId in workoutIds }
    val slotsById = validSlots.associateBy { it.id }
    val completedSets = sets.filter { it.completedAt != null && it.workoutExerciseId in slotsById }

    // PR flags: each exercise's history replayed in completion order by the shared records engine.
    val setsByExercise = completedSets.groupBy { slotsById.getValue(it.workoutExerciseId).exerciseId }
    val prSetIds = chronologicalPrSetIds(setsByExercise)

    val setsBySlot = completedSets.groupBy { it.workoutExerciseId }
    val slotsByWorkout = validSlots.groupBy { it.workoutId }

    val snapshotWorkouts = workouts
        .filter { workout -> slotsByWorkout[workout.id].orEmpty().any { it.id in setsBySlot } }
        .sortedWith(compareByDescending<WorkoutEntity> { it.startedAt }.thenByDescending { it.id })
        .map { workout ->
            val slots = slotsByWorkout.getValue(workout.id)
                .filter { it.id in setsBySlot }
                .sortedBy { it.position }
                .map { slot ->
                    val exercise = exercisesById[slot.exerciseId]
                    val orderedSets = setsBySlot.getValue(slot.id).sortedBy { it.position }
                    SnapshotWorkoutExercise(
                        exerciseId = slot.exerciseId,
                        name = exercise?.name ?: slot.exerciseId,
                        primaryMuscles = exercise?.primaryMuscles ?: emptyList(),
                        secondaryMuscles = exercise?.secondaryMuscles ?: emptyList(),
                        notes = slot.notes,
                        sets = orderedSets.mapIndexed { i, set ->
                            SnapshotSet(
                                index = i + 1,
                                type = set.type,
                                weightKg = set.weightKg,
                                reps = set.reps,
                                durationSec = set.durationSec,
                                rpe = set.rpe,
                                restSec = null, // rest taken isn't recorded yet
                                isPr = set.id in prSetIds,
                            )
                        },
                    )
                }
            SnapshotWorkout(
                id = workout.id,
                dateIso = format.date(workout.startedAt),
                startTimeIso = format.dateTime(workout.startedAt),
                startEpochMillis = workout.startedAt,
                name = workout.name,
                durationMin = workout.endedAt?.let { ((it - workout.startedAt) / 60000.0).roundToInt() },
                bodyweightKg = null, // not stored per workout; comes from the bodyweight log later
                notes = workout.notes,
                exercises = slots,
            )
        }

    val referencedIds = snapshotWorkouts.flatMapTo(HashSet()) { workout -> workout.exercises.map { it.exerciseId } }
    val snapshotExercises = exercises
        .filter { it.id in referencedIds }
        .sortedBy { it.name }
        .map { SnapshotExercise(it.id, it.name, it.equipment, it.primaryMuscles, it.secondaryMuscles) }

    val prs = computePrs(exercises, workouts, workoutExercises, sets)
        .map { pr ->
            SnapshotPr(
                exerciseId = pr.exerciseId,
                exerciseName = exercisesById[pr.exerciseId]?.name ?: pr.exerciseId,
                maxWeightKg = pr.maxWeightKg,
                maxWeightDateIso = format.date(pr.maxWeightDate),
                bestE1RmKg = pr.bestE1RmKg,
                bestE1RmDateIso = pr.bestE1RmDate?.let { format.date(it) },
                bestSetNotation = pr.bestSetNotation,
            )
        }
        .sortedBy { it.exerciseName }

    val weekly = weeklySetsPerMuscle(
        exercises = exercises,
        workouts = workouts,
        workoutExercises = workoutExercises,
        sets = sets,
        weeks = WEEKLY_SUMMARY_WEEKS,
        todayEpochMillis = exportedAtMillis,
        zone = zone,
    )

    val bodyweightLog = bodyweight
        .sortedBy { it.epochDay }
        .map { BodyweightPoint(LocalDate.ofEpochDay(it.epochDay).toString(), it.weightKg) }

    // Bodyweight is logged by local day, so "today" is local too.
    val todayEpochDay = Instant.ofEpochMilli(exportedAtMillis).atZone(zone).toLocalDate().toEpochDay()
    val trend = bodyweightTrend(bodyweight, weeks = WEEKLY_SUMMARY_WEEKS, todayEpochDay = todayEpochDay)
        ?.let {
            SnapshotBodyweightTrend(
                currentKg = it.currentKg,
                currentDateIso = LocalDate.ofEpochDay(it.currentEpochDay).toString(),
                weeklyAvgKg = it.weeklyAvgKg,
                rateKgPerWeek = it.rateKgPerWeek,
            )
        }

    return ExportSnapshot(
        schemaVersion = EXPORT_SCHEMA_VERSION,
        exportedAtIso = format.dateTime(exportedAtMillis),
        zone = zone,
        goals = goals,
        injuries = injuries,
        bars = bars.sortedBy { it.position }.map { SnapshotBar(it.name, it.weightKg) },
        bodyweightLog = bodyweightLog,
        bodyweightTrend = trend,
        exercises = snapshotExercises,
        workouts = snapshotWorkouts,
        prs = prs,
        weeklySetsPerMuscle = weekly,
    )
}

/** Date and number formatting for the export renderers; dates and times are local to [zone]. */
internal class ExportFormat(zone: ZoneId) {
    private val date = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(zone)
    private val csvDateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(zone)
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).withZone(zone)

    /** "2026-07-13" */
    fun date(epochMillis: Long): String = date.format(Instant.ofEpochMilli(epochMillis))

    /** "2026-07-13 19:30:00", local wall-clock time with no offset, as Strong writes it. */
    fun csvDateTime(epochMillis: Long): String = csvDateTime.format(Instant.ofEpochMilli(epochMillis))

    /** "2026-07-13T19:30:00-05:00" ("Z" in UTC). */
    fun dateTime(epochMillis: Long): String = dateTime.format(Instant.ofEpochMilli(epochMillis))

    companion object {
        /** "100" for 100.0, "102.5" for 102.5 — no trailing ".0". */
        fun number(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

        /** Rounds to one decimal, then trims like [number] (119.5833 -> "119.6"). */
        fun rounded1(value: Double): String = number(kotlin.math.round(value * 10) / 10.0)

        /** Signed with two decimals ("+0.25", "-0.30"). */
        fun signed2(value: Double): String = String.format(Locale.ROOT, "%+.2f", value)
    }
}
