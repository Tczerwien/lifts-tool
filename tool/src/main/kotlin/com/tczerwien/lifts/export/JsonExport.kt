package com.tczerwien.lifts.export

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
                    hidden = it.hidden.takeIf { hidden -> hidden },
                    pinnedNote = it.pinnedNote,
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
                    exercises = workout.exercises.map { exercise ->
                        JsonWorkoutExercise(
                            exerciseId = exercise.exerciseId,
                            name = exercise.name,
                            notes = exercise.notes,
                            sets = exercise.sets.map { set ->
                                JsonSet(
                                    index = set.index,
                                    type = jsonSetType(set.type),
                                    weightKg = set.weightKg,
                                    reps = set.reps,
                                    durationSeconds = set.durationSec,
                                    rpe = set.rpe,
                                    restSeconds = set.restSec,
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
                        weeklyAvgKg = it.weeklyAvgKg,
                    )
                },
            ),
        )
        return json.encodeToString(JsonExportDocument.serializer(), document)
    }

    /** Hevy's set types: normal, warmup, failure, dropset (Lifts stores a drop set as "drop"). */
    private fun jsonSetType(type: String): String = if (type == "drop") "dropset" else type
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
    /** Only written (true) for a custom exercise taken out of the picker. */
    val hidden: Boolean? = null,
    /** His form cue. */
    @SerialName("pinned_note") val pinnedNote: String? = null,
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
    val exercises: List<JsonWorkoutExercise>,
)

@Serializable
data class JsonWorkoutExercise(
    @SerialName("exercise_id") val exerciseId: String,
    val name: String,
    val notes: String? = null,
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
    /** Seconds since the workout's previous ticked set (rest plus the set itself); absent for the first set or after 15 min. */
    @SerialName("rest_seconds") val restSeconds: Int? = null,
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
    /** One count per [weekStarts] week, oldest first: 1 per working set for primary muscles, 0.5 for secondary. */
    @SerialName("weekly_sets_per_muscle") val weeklySetsPerMuscle: Map<String, List<Double>>,
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
    @SerialName("rate_kg_per_week_4w") val rateKgPerWeek4w: Double? = null,
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
