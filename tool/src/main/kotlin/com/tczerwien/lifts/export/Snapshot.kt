package com.tczerwien.lifts.export

import com.tczerwien.lifts.analytics.bodyweightTrend
import com.tczerwien.lifts.analytics.chronologicalPrSetIds
import com.tczerwien.lifts.analytics.computePrs
import com.tczerwien.lifts.analytics.e1RmTrend
import com.tczerwien.lifts.analytics.restGapsSec
import com.tczerwien.lifts.analytics.trendBlockStarts
import com.tczerwien.lifts.analytics.weeklySetsPerMuscle
import com.tczerwien.lifts.analytics.workoutBodyweight
import com.tczerwien.lifts.data.LiftsRows
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.ui.WeightUnit
import com.tczerwien.lifts.ui.displayName
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
const val EXPORT_SCHEMA_VERSION = "2.0"

/** Number of ISO weeks in the precomputed weekly-sets-per-muscle summary. */
const val WEEKLY_SUMMARY_WEEKS = 8

/** The e1RM trend: [TREND_BLOCKS] blocks of [TREND_BLOCK_DAYS] days, for the [TREND_LIFTS] most-trained lifts. */
const val TREND_BLOCKS = 6
const val TREND_BLOCK_DAYS = 28
const val TREND_LIFTS = 8

data class ExportSnapshot(
    val schemaVersion: String,
    /** With its offset, e.g. 2026-07-14T07:00:00-05:00. */
    val exportedAtIso: String,
    /** The zone every date and time is in, e.g. America/Chicago. */
    val zone: ZoneId,
    /** The unit Lifts shows weights in: training-log.md and the CSVs use it; workouts.json stays in kg. */
    val unit: WeightUnit,
    val goals: String?,
    val injuries: List<String>,
    /** User-defined implements, ordered by position. */
    val bars: List<SnapshotBar>,
    /** Sorted oldest first. */
    val bodyweightLog: List<BodyweightPoint>,
    /** Trend derived from [bodyweightLog]; null when there are no bodyweight entries. */
    val bodyweightTrend: SnapshotBodyweightTrend?,
    /**
     * Every exercise with something of his in it, sorted by name: done in an exported workout, in a
     * template, a custom one (used or not), or one with a pinned cue. Names are the ones Lifts shows
     * ([displayName]).
     */
    val exercises: List<SnapshotExercise>,
    /** In the order the Templates screen lists them. */
    val templates: List<SnapshotTemplate>,
    /** Newest first. */
    val workouts: List<SnapshotWorkout>,
    /** Sorted by exercise name. */
    val prs: List<SnapshotPr>,
    /** Best e1RM per block for the most-trained lifts; no lifts when nothing qualifies. */
    val e1RmTrend: SnapshotE1RmTrend,
    /** The Mondays (yyyy-MM-dd) of the [WEEKLY_SUMMARY_WEEKS] summary weeks, oldest first; the last is this week. */
    val weekStarts: List<String>,
    /** Muscle -> per-week set counts, one per [weekStarts] entry, newest week last. */
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
    val isCustom: Boolean,
    /** A custom exercise he took out of the picker; old workouts still use it. */
    val hidden: Boolean,
    /** His form cue, shown above the sets. */
    val pinnedNote: String?,
)

data class SnapshotTemplate(val name: String, val exercises: List<SnapshotTemplateExercise>)

data class SnapshotTemplateExercise(
    val exerciseId: String,
    val name: String,
    val targetSets: Int,
    /** Null: repeat last session's warm-ups. */
    val warmupSets: Int?,
    val repMin: Int?,
    val repMax: Int?,
    val targetRir: Int?,
    /** Null: the Settings default. */
    val restSecWork: Int?,
    val restSecWarmup: Int?,
)

data class SnapshotWorkout(
    val id: Long,
    /** Local yyyy-MM-dd of [startEpochMillis]. */
    val dateIso: String,
    /** Local ISO-8601 time with its offset, e.g. 2026-07-13T19:30:00-05:00. */
    val startTimeIso: String,
    val endTimeIso: String,
    val startEpochMillis: Long,
    val name: String,
    /** Rounded minutes. */
    val durationMin: Int,
    /** That day's weigh-in, else the latest from the few days before ([workoutBodyweight]). */
    val bodyweight: SnapshotWorkoutBodyweight?,
    val notes: String?,
    /** Ordered by position; only slots with at least one completed set. */
    val exercises: List<SnapshotWorkoutExercise>,
)

data class SnapshotWorkoutBodyweight(
    val weightKg: Double,
    /** yyyy-MM-dd of the weigh-in. */
    val dateIso: String,
    /** Logged the day the workout started. */
    val sameDay: Boolean,
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
    /** Seconds since the workout's previous ticked set ([restGapsSec]); null for the first, or after a break. */
    val restSec: Int?,
    /** When it was ticked, local ISO-8601 with its offset. */
    val completedAtIso: String,
    val isPr: Boolean,
)

data class SnapshotE1RmTrend(
    val blockDays: Int,
    /** The first day (yyyy-MM-dd) of each block, oldest first; the last block ends on the export day. */
    val blockStarts: List<String>,
    /** Most working sets first. */
    val lifts: List<SnapshotLiftTrend>,
)

data class SnapshotLiftTrend(
    val exerciseId: String,
    val name: String,
    /** One per block, oldest first; null for a block without a qualifying set. */
    val bestE1RmKg: List<Double?>,
)

data class SnapshotPr(
    val exerciseId: String,
    val exerciseName: String,
    val maxWeightKg: Double,
    val maxWeightDateIso: String,
    val bestE1RmKg: Double?,
    val bestE1RmDateIso: String?,
    /** The best-e1RM set (else the heaviest): its weight and reps. */
    val bestSetWeightKg: Double?,
    val bestSetReps: Int?,
)

/**
 * Builds the export snapshot from every row of the database ([rows]).
 *
 * Rules:
 * - Only finished workouts ([WorkoutEntity.endedAt] set) are exported, and only their completed
 *   sets (completedAt != null); workouts and exercise slots with no completed sets are dropped
 *   entirely. The summary counts the same sets.
 * - PR flags are computed chronologically per exercise (by completedAt, ties by id)
 *   across the full history — see [isPr].
 * - The weekly-volume summary covers the [WEEKLY_SUMMARY_WEEKS] ISO weeks ending at
 *   [exportedAtMillis], in [zone].
 *
 * @param exportedAtMillis when the export was made
 * @param zone the phone's zone: every date, time and week is local to it
 * @param unit the display unit (Settings), for the files people read
 * @param goals optional free-text athlete goals for the front matter / profile
 * @param injuries current injuries/limitations, possibly empty
 */
fun buildSnapshot(
    rows: LiftsRows,
    exportedAtMillis: Long,
    zone: ZoneId,
    unit: WeightUnit,
    goals: String?,
    injuries: List<String>,
): ExportSnapshot {
    val exercises = rows.exercises
    // Only finished workouts, as History shows them: an open one (or one left open and never closed)
    // is still being logged, so none of its sets count anywhere, summary included.
    val workouts = rows.workouts.filter { it.endedAt != null }
    val workoutExercises = rows.workoutExercises
    val sets = rows.sets
    val bodyweight = rows.bodyweight
    val bars = rows.bars
    val format = ExportFormat(zone)
    val exercisesById = exercises.associateBy { it.id }
    val names = exercises.associate { it.id to displayName(it) }
    val workoutIds = workouts.mapTo(HashSet()) { it.id }
    val validSlots = workoutExercises.filter { it.workoutId in workoutIds }
    val slotsById = validSlots.associateBy { it.id }
    val completedSets = sets.filter { it.completedAt != null && it.workoutExerciseId in slotsById }

    // PR flags: each exercise's history replayed in completion order by the shared records engine.
    val setsByExercise = completedSets.groupBy { slotsById.getValue(it.workoutExerciseId).exerciseId }
    val prSetIds = chronologicalPrSetIds(setsByExercise)

    val setsBySlot = completedSets.groupBy { it.workoutExerciseId }
    val slotsByWorkout = validSlots.groupBy { it.workoutId }
    // Rest gaps run across all of a workout's exercises, in the order the sets were ticked.
    val restGaps = completedSets
        .groupBy { slotsById.getValue(it.workoutExerciseId).workoutId }
        .values
        .fold(HashMap<Long, Int>()) { gaps, workoutSets -> gaps.apply { putAll(restGapsSec(workoutSets)) } }

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
                        name = names[slot.exerciseId] ?: slot.exerciseId,
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
                                restSec = restGaps[set.id],
                                completedAtIso = format.dateTime(set.completedAt ?: workout.startedAt),
                                isPr = set.id in prSetIds,
                            )
                        },
                    )
                }
            SnapshotWorkout(
                id = workout.id,
                dateIso = format.date(workout.startedAt),
                startTimeIso = format.dateTime(workout.startedAt),
                endTimeIso = format.dateTime(workout.endedAt ?: workout.startedAt),
                startEpochMillis = workout.startedAt,
                name = workout.name,
                durationMin = (((workout.endedAt ?: workout.startedAt) - workout.startedAt) / 60000.0).roundToInt(),
                bodyweight = workoutBodyweight(workout.startedAt, bodyweight, zone)?.let {
                    SnapshotWorkoutBodyweight(it.weightKg, LocalDate.ofEpochDay(it.epochDay).toString(), it.sameDay)
                },
                notes = workout.notes,
                exercises = slots,
            )
        }

    val templateSlots = rows.templateExercises.groupBy { it.templateId }
    val snapshotTemplates = rows.templates.sortedWith(compareBy({ it.position }, { it.id })).map { template ->
        SnapshotTemplate(
            name = template.name,
            exercises = templateSlots[template.id].orEmpty().sortedWith(compareBy({ it.position }, { it.id })).map {
                SnapshotTemplateExercise(
                    exerciseId = it.exerciseId,
                    name = names[it.exerciseId] ?: it.exerciseId,
                    targetSets = it.targetSets,
                    warmupSets = it.warmupSets,
                    repMin = it.repMin,
                    repMax = it.repMax,
                    targetRir = it.targetRir,
                    restSecWork = it.restSecWork,
                    restSecWarmup = it.restSecWarmup,
                )
            },
        )
    }

    val usedIds = snapshotWorkouts.flatMapTo(HashSet()) { workout -> workout.exercises.map { it.exerciseId } } +
        rows.templateExercises.map { it.exerciseId }
    val snapshotExercises = exercises
        .filter { it.id in usedIds || it.isCustom || it.pinnedNote != null }
        .map {
            SnapshotExercise(
                id = it.id,
                name = names.getValue(it.id),
                equipment = it.equipment,
                primaryMuscles = it.primaryMuscles,
                secondaryMuscles = it.secondaryMuscles,
                isCustom = it.isCustom,
                hidden = it.hidden,
                pinnedNote = it.pinnedNote,
            )
        }
        .sortedWith(compareBy({ it.name }, { it.id }))

    val prs = computePrs(exercises, workouts, workoutExercises, sets)
        .map { pr ->
            SnapshotPr(
                exerciseId = pr.exerciseId,
                exerciseName = names[pr.exerciseId] ?: pr.exerciseId,
                maxWeightKg = pr.maxWeightKg,
                maxWeightDateIso = format.date(pr.maxWeightDate),
                bestE1RmKg = pr.bestE1RmKg,
                bestE1RmDateIso = pr.bestE1RmDate?.let { format.date(it) },
                bestSetWeightKg = pr.bestSet.weightKg,
                bestSetReps = pr.bestSet.reps,
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
    val today = Instant.ofEpochMilli(exportedAtMillis).atZone(zone).toLocalDate()
    val todayEpochDay = today.toEpochDay()
    val currentMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val weekStarts = List(WEEKLY_SUMMARY_WEEKS) { i ->
        currentMonday.minusWeeks((WEEKLY_SUMMARY_WEEKS - 1 - i).toLong()).toString()
    }
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
        unit = unit,
        goals = goals,
        injuries = injuries,
        bars = bars.sortedBy { it.position }.map { SnapshotBar(it.name, it.weightKg) },
        bodyweightLog = bodyweightLog,
        bodyweightTrend = trend,
        exercises = snapshotExercises,
        templates = snapshotTemplates,
        workouts = snapshotWorkouts,
        prs = prs,
        e1RmTrend = SnapshotE1RmTrend(
            blockDays = TREND_BLOCK_DAYS,
            blockStarts = trendBlockStarts(todayEpochDay, TREND_BLOCKS, TREND_BLOCK_DAYS).map { LocalDate.ofEpochDay(it).toString() },
            lifts = e1RmTrend(setsByExercise, todayEpochDay, zone, TREND_BLOCKS, TREND_BLOCK_DAYS, TREND_LIFTS).map {
                SnapshotLiftTrend(it.exerciseId, names[it.exerciseId] ?: it.exerciseId, it.bestE1RmKg)
            },
        ),
        weekStarts = weekStarts,
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
