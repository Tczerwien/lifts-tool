package com.tczerwien.lifts.export

import com.tczerwien.lifts.data.Tracking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Renders `workouts.json` — the complete, schema-versioned export: every finished workout with every
 * ticked set, plus what he typed in himself that history can't rebuild (templates, custom exercises,
 * pinned cues, names he gave lifts). Field names follow Hevy's API where there is one (title,
 * start_time/end_time, duration_seconds, "dropset", weight_kg); set index is 1-based, like strong.csv's
 * Set Order. All weights are exact kilograms. Times are local ISO-8601 with their offset, in
 * `timezone`. Optional fields that are null are left out; fields that are part of the schema contract
 * have no defaults and are always written. One line, no indentation: it is for programs and AIs;
 * training-log.md is the one to read.
 *
 * 2.0 (B7) renamed routine → title, duration_sec → duration_seconds, rest_sec → rest_seconds,
 * type "drop" → "dropset", bodyweight_log[].value → weight_kg and best_set → best_set_weight_kg +
 * best_set_reps; replaced duration_min with end_time; added timezone, units.display, templates,
 * week_starts, e1rm_trend, completed_at, bodyweight_date, is_custom, hidden, pinned_note, every custom
 * exercise, and a name on each workout and template exercise; and is written on one line (1.x was
 * indented).
 *
 * 2.1 (B8) renamed sets[].rest_seconds → seconds_since_previous_set: it is the gap since the previous
 * tick, not a rest setting as in Hevy (templates keep rest_seconds, which is one).
 *
 * 2.2 (B9) added exercises[].type, Hevy's exercise types: weight_reps, reps_only, bodyweight_reps
 * (weighted bodyweight: weight_kg is what was added), bodyweight_assisted_reps (weight_kg is the help,
 * negative) and duration (duration_seconds). An RPE is logged as reps in reserve: rpe = 10 − RIR.
 *
 * 2.3 (B10) added summary.bodyweight.rate_span_days (the rate now needs 3 weigh-ins 14+ days apart
 * in the last 28 days, and says how many days it covers), exercises[].per_side (reps and seconds
 * count one side) and each workout exercise's target from its template: rep_min, rep_max, target_rir.
 *
 * 2.4 (B13) added superset_id (Hevy's name) on workout and template exercises: the exercises sharing a
 * number were done (or are planned) as a superset, one set of each in turn with the rest after the
 * round. Numbered 1, 2, … within each workout or template; absent outside one.
 *
 * 2.5 (B14) added target_rir_max on workout and template exercises (an RIR range, "1–2": target_rir 1,
 * target_rir_max 2; absent for one number) and workouts[].deload (true for a deload: fewer work sets at
 * an easier effort, the deload's RIR from Settings applying instead of the targets; absent otherwise).
 * A deload's exercises keep their template's target_rir and target_rir_max.
 *
 * 2.6 (B15) added alternatives on template exercises ([{exercise_id, name}], in order: exercises that can
 * stand in for it at the gym, swapped in for one workout; absent without any) and planned_exercise_id on
 * workout exercises (the exercise its template planned, its main, when an alternative was done instead; a
 * workout started again from one keeps that template's plan; absent otherwise). Every id named is in exercises[].
 *
 * 3.0 (B17) counts summary.weekly_sets_per_muscle directly, as Lifts' Sets per Muscle screen does: each ticked
 * work set adds 1 to each muscle its exercise counts for (its primary muscles), secondary muscles nothing, and a
 * drop set nothing (it is part of the set above it); whole numbers now. Before, a set added 1 to each primary
 * muscle and 0.5 to each secondary one, a drop as much as any set.
 */
object JsonExport {

    private val json = Json

    fun render(snapshot: ExportSnapshot): String {
        val document = JsonExportDocument(
            schemaVersion = snapshot.schemaVersion,
            exportedAt = snapshot.exportedAtIso,
            timezone = snapshot.zone.id,
            units = JsonUnits(weight = "kg", display = snapshot.unit.suffix),
            profile = JsonProfile(
                goals = snapshot.goals,
                injuries = snapshot.injuries,
                equipment = snapshot.bars
                    .takeIf { it.isNotEmpty() }
                    ?.let { bars -> JsonEquipment(bars = bars.map { JsonBar(it.name, it.weightKg) }) },
                bodyweightLog = snapshot.bodyweightLog.map { JsonBodyweightEntry(it.dateIso, it.weightKg) },
            ),
            exercises = snapshot.exercises.map {
                JsonExercise(
                    id = it.id,
                    name = it.name,
                    equipment = it.equipment,
                    primaryMuscles = it.primaryMuscles,
                    secondaryMuscles = it.secondaryMuscles,
                    isCustom = it.isCustom,
                    type = hevyType(it.tracking),
                    hidden = it.hidden.takeIf { hidden -> hidden },
                    pinnedNote = it.pinnedNote,
                    perSide = it.perSide.takeIf { perSide -> perSide },
                )
            },
            templates = snapshot.templates.map { template ->
                JsonTemplate(
                    title = template.name,
                    exercises = template.exercises.map {
                        JsonTemplateExercise(
                            exerciseId = it.exerciseId,
                            name = it.name,
                            targetSets = it.targetSets,
                            warmupSets = it.warmupSets,
                            repMin = it.repMin,
                            repMax = it.repMax,
                            targetRir = it.targetRir,
                            restSeconds = it.restSecWork,
                            warmupRestSeconds = it.restSecWarmup,
                            supersetId = it.supersetId,
                            targetRirMax = it.targetRirMax,
                            alternatives = it.alternatives.takeIf { alternatives -> alternatives.isNotEmpty() }
                                ?.map { alternative -> JsonExerciseRef(alternative.exerciseId, alternative.name) },
                        )
                    },
                )
            },
            workouts = snapshot.workouts.map { workout ->
                JsonWorkout(
                    id = workout.id,
                    title = workout.name,
                    startTime = workout.startTimeIso,
                    endTime = workout.endTimeIso,
                    bodyweightKg = workout.bodyweight?.weightKg,
                    bodyweightDate = workout.bodyweight?.dateIso,
                    notes = workout.notes,
                    deload = workout.deload.takeIf { it },
                    exercises = workout.exercises.map { exercise ->
                        JsonWorkoutExercise(
                            exerciseId = exercise.exerciseId,
                            name = exercise.name,
                            notes = exercise.notes,
                            repMin = exercise.targetRepMin,
                            repMax = exercise.targetRepMax,
                            targetRir = exercise.targetRir,
                            targetRirMax = exercise.targetRirMax,
                            supersetId = exercise.supersetId,
                            plannedExerciseId = exercise.planned?.exerciseId,
                            sets = exercise.sets.map { set ->
                                JsonSet(
                                    index = set.index,
                                    type = jsonSetType(set.type),
                                    weightKg = set.weightKg,
                                    reps = set.reps,
                                    durationSeconds = set.durationSec,
                                    rpe = set.rpe,
                                    secondsSincePreviousSet = set.restSec,
                                    completedAt = set.completedAtIso,
                                    isPr = set.isPr,
                                )
                            },
                        )
                    },
                )
            },
            summary = JsonSummary(
                prs = snapshot.prs.map {
                    JsonPr(
                        exerciseId = it.exerciseId,
                        name = it.exerciseName,
                        maxWeightKg = it.maxWeightKg,
                        maxWeightDate = it.maxWeightDateIso,
                        bestE1RmKg = it.bestE1RmKg,
                        bestE1RmDate = it.bestE1RmDateIso,
                        bestSetWeightKg = it.bestSetWeightKg,
                        bestSetReps = it.bestSetReps,
                    )
                },
                e1RmTrend = JsonE1RmTrend(
                    blockDays = snapshot.e1RmTrend.blockDays,
                    blockStarts = snapshot.e1RmTrend.blockStarts,
                    lifts = snapshot.e1RmTrend.lifts.map { JsonLiftTrend(it.exerciseId, it.name, it.bestE1RmKg) },
                ),
                weekStarts = snapshot.weekStarts,
                weeklySetsPerMuscle = snapshot.weeklySetsPerMuscle,
                bodyweight = snapshot.bodyweightTrend?.let {
                    JsonBodyweightSummary(
                        currentKg = it.currentKg,
                        currentDate = it.currentDateIso,
                        rateKgPerWeek4w = it.rateKgPerWeek,
                        rateSpanDays = it.rateSpanDays,
                        weeklyAvgKg = it.weeklyAvgKg,
                    )
                },
            ),
        )
        return json.encodeToString(JsonExportDocument.serializer(), document)
    }

    /** Hevy's set types: normal, warmup, failure, dropset (Lifts stores a drop set as "drop"). */
    private fun jsonSetType(type: String): String = if (type == "drop") "dropset" else type

    /** Hevy's name for how an exercise is measured. */
    private fun hevyType(tracking: Tracking): String = when (tracking) {
        Tracking.WEIGHT_REPS -> "weight_reps"
        Tracking.REPS_ONLY -> "reps_only"
        Tracking.WEIGHTED_BODYWEIGHT -> "bodyweight_reps"
        Tracking.ASSISTED_BODYWEIGHT -> "bodyweight_assisted_reps"
        Tracking.DURATION -> "duration"
    }
}

@Serializable
data class JsonExportDocument(
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("exported_at") val exportedAt: String,
    /** The zone of every date and time in the file, e.g. America/Chicago. */
    val timezone: String,
    val units: JsonUnits,
    val profile: JsonProfile,
    val exercises: List<JsonExercise>,
    val templates: List<JsonTemplate>,
    val workouts: List<JsonWorkout>,
    val summary: JsonSummary,
)

/** [weight]: every weight in this file ("kg"). [display]: the unit Lifts shows and he thinks in. */
@Serializable
data class JsonUnits(val weight: String, val display: String)

@Serializable
data class JsonProfile(
    val goals: String? = null,
    val injuries: List<String>,
    /** Omitted when the user has no named bars. */
    val equipment: JsonEquipment? = null,
    @SerialName("bodyweight_log") val bodyweightLog: List<JsonBodyweightEntry>,
)

@Serializable
data class JsonEquipment(val bars: List<JsonBar>)

@Serializable
data class JsonBar(val name: String, @SerialName("weight_kg") val weightKg: Double)

@Serializable
data class JsonBodyweightEntry(val date: String, @SerialName("weight_kg") val weightKg: Double)

@Serializable
data class JsonExercise(
    val id: String,
    /** As Lifts shows it: a name he gave it, else the catalog lift's short name, else its catalog name. */
    val name: String,
    val equipment: String? = null,
    @SerialName("primary_muscles") val primaryMuscles: List<String>,
    @SerialName("secondary_muscles") val secondaryMuscles: List<String>,
    @SerialName("is_custom") val isCustom: Boolean,
    /**
     * How its sets are measured, in Hevy's words: weight_reps, reps_only, bodyweight_reps (weight_kg is
     * added load), bodyweight_assisted_reps (weight_kg is the help, negative) or duration.
     */
    val type: String,
    /** Only written (true) for a custom exercise taken out of the picker. */
    val hidden: Boolean? = null,
    /** His form cue. */
    @SerialName("pinned_note") val pinnedNote: String? = null,
    /** Only written (true) for one done a side at a time: its sets' reps and seconds count one side. */
    @SerialName("per_side") val perSide: Boolean? = null,
)

@Serializable
data class JsonTemplate(
    val title: String,
    val exercises: List<JsonTemplateExercise>,
)

@Serializable
data class JsonTemplateExercise(
    @SerialName("exercise_id") val exerciseId: String,
    val name: String,
    @SerialName("target_sets") val targetSets: Int,
    /** Absent: repeat last session's warm-ups. */
    @SerialName("warmup_sets") val warmupSets: Int? = null,
    @SerialName("rep_min") val repMin: Int? = null,
    @SerialName("rep_max") val repMax: Int? = null,
    @SerialName("target_rir") val targetRir: Int? = null,
    /** Absent: the Settings default. */
    @SerialName("rest_seconds") val restSeconds: Int? = null,
    @SerialName("warmup_rest_seconds") val warmupRestSeconds: Int? = null,
    /** The template's exercises sharing it are a superset; absent outside one. */
    @SerialName("superset_id") val supersetId: Int? = null,
    /** The top of an RIR range ("1–2"); absent for one number. */
    @SerialName("target_rir_max") val targetRirMax: Int? = null,
    /** Exercises that can stand in for it at the gym, swapped in for one workout; absent without any. */
    val alternatives: List<JsonExerciseRef>? = null,
)

/** An exercise by its id (as exercises[] lists it) and name. */
@Serializable
data class JsonExerciseRef(
    @SerialName("exercise_id") val exerciseId: String,
    val name: String,
)

@Serializable
data class JsonWorkout(
    val id: Long,
    val title: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("bodyweight_kg") val bodyweightKg: Double? = null,
    /** The day of that weigh-in: the workout's, or up to 3 days before. */
    @SerialName("bodyweight_date") val bodyweightDate: String? = null,
    val notes: String? = null,
    /** Only written (true) for a deload: fewer work sets at an easier effort than its exercises' targets. */
    val deload: Boolean? = null,
    val exercises: List<JsonWorkoutExercise>,
)

@Serializable
data class JsonWorkoutExercise(
    @SerialName("exercise_id") val exerciseId: String,
    val name: String,
    val notes: String? = null,
    /** The template's target when the workout started (as templates[].exercises[] name them); absent without one. */
    @SerialName("rep_min") val repMin: Int? = null,
    @SerialName("rep_max") val repMax: Int? = null,
    @SerialName("target_rir") val targetRir: Int? = null,
    @SerialName("target_rir_max") val targetRirMax: Int? = null,
    /** The workout's exercises sharing it were done as a superset; absent outside one. */
    @SerialName("superset_id") val supersetId: Int? = null,
    /** The exercise its template planned (its main; a Start Again copy keeps the plan), when this one was done instead; absent otherwise. */
    @SerialName("planned_exercise_id") val plannedExerciseId: String? = null,
    val sets: List<JsonSet>,
)

@Serializable
data class JsonSet(
    /** 1-based, like strong.csv's Set Order. */
    val index: Int,
    /** normal, warmup, failure or dropset. */
    val type: String,
    @SerialName("weight_kg") val weightKg: Double? = null,
    val reps: Int? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    val rpe: Double? = null,
    /**
     * Seconds since the workout's previous ticked set (rest plus the set itself), not a rest setting
     * (Hevy's rest_seconds); absent for the first set or after 15 min.
     */
    @SerialName("seconds_since_previous_set") val secondsSincePreviousSet: Int? = null,
    @SerialName("completed_at") val completedAt: String,
    @SerialName("is_pr") val isPr: Boolean,
)

@Serializable
data class JsonSummary(
    val prs: List<JsonPr>,
    /** Best e1RM per block for the most-trained lifts. */
    @SerialName("e1rm_trend") val e1RmTrend: JsonE1RmTrend,
    /** The Mondays of the summary weeks, oldest first; the last is the current week, still in progress. */
    @SerialName("week_starts") val weekStarts: List<String>,
    /**
     * One count per [weekStarts] week, oldest first: hard sets, 1 per ticked work set for each muscle its
     * exercise counts for (its primary muscles); warm-ups, drop sets and secondary muscles add nothing.
     */
    @SerialName("weekly_sets_per_muscle") val weeklySetsPerMuscle: Map<String, List<Int>>,
    val bodyweight: JsonBodyweightSummary? = null,
)

@Serializable
data class JsonE1RmTrend(
    @SerialName("block_days") val blockDays: Int,
    /** The first day of each block, oldest first; the last block ends on the export day. */
    @SerialName("block_starts") val blockStarts: List<String>,
    /** The most-trained lifts first. */
    val lifts: List<JsonLiftTrend>,
)

@Serializable
data class JsonLiftTrend(
    @SerialName("exercise_id") val exerciseId: String,
    val name: String,
    /** One per block, oldest first; null for a block without a set of 1–12 reps. */
    @SerialName("best_e1rm_kg") val bestE1RmKg: List<Double?>,
)

@Serializable
data class JsonBodyweightSummary(
    @SerialName("current_kg") val currentKg: Double,
    @SerialName("current_date") val currentDate: String,
    /** Over the last 28 days; absent with fewer than 3 weigh-ins there, or under 14 days apart. */
    @SerialName("rate_kg_per_week_4w") val rateKgPerWeek4w: Double? = null,
    /** Days from the first to the last weigh-in the rate covers. */
    @SerialName("rate_span_days") val rateSpanDays: Int? = null,
    /** One per week_starts week, oldest first; null for weeks with no entry. */
    @SerialName("weekly_avg_kg") val weeklyAvgKg: List<Double?>,
)

@Serializable
data class JsonPr(
    @SerialName("exercise_id") val exerciseId: String,
    val name: String,
    @SerialName("max_weight_kg") val maxWeightKg: Double,
    @SerialName("max_weight_date") val maxWeightDate: String,
    @SerialName("best_e1rm_kg") val bestE1RmKg: Double? = null,
    @SerialName("best_e1rm_date") val bestE1RmDate: String? = null,
    /** The best-e1RM set (else the heaviest). */
    @SerialName("best_set_weight_kg") val bestSetWeightKg: Double? = null,
    @SerialName("best_set_reps") val bestSetReps: Int? = null,
)
