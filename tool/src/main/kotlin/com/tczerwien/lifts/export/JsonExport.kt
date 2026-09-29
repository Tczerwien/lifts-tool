package com.tczerwien.lifts.export

import com.tczerwien.lifts.analytics.NotationStyle
import com.tczerwien.lifts.analytics.setNotation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Renders `workouts.json` — the lossless, schema-versioned export with Hevy-aligned
 * snake_case field names. All weights are kilograms. Optional fields that are null are
 * omitted from the output; fields that are part of the schema contract have no defaults
 * and are always emitted.
 */
object JsonExport {

    private val json = Json { prettyPrint = true }

    fun render(snapshot: ExportSnapshot): String {
        val document = JsonExportDocument(
            schemaVersion = snapshot.schemaVersion,
            exportedAt = snapshot.exportedAtIso,
            timezone = snapshot.zone.id,
            units = JsonUnits(weight = "kg"),
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
                )
            },
            workouts = snapshot.workouts.map { workout ->
                JsonWorkout(
                    id = workout.id,
                    startTime = workout.startTimeIso,
                    durationMin = workout.durationMin,
                    routine = workout.name,
                    bodyweightKg = workout.bodyweight?.weightKg,
                    bodyweightDate = workout.bodyweight?.dateIso,
                    notes = workout.notes,
                    exercises = workout.exercises.map { exercise ->
                        JsonWorkoutExercise(
                            exerciseId = exercise.exerciseId,
                            notes = exercise.notes,
                            sets = exercise.sets.map { set ->
                                JsonSet(
                                    index = set.index,
                                    type = set.type,
                                    weightKg = set.weightKg,
                                    reps = set.reps,
                                    durationSec = set.durationSec,
                                    rpe = set.rpe,
                                    restSec = set.restSec,
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
                        bestSet = setNotation(it.bestSetWeightKg, it.bestSetReps, snapshot.unit, NotationStyle.EXPORT).orEmpty(),
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
    val workouts: List<JsonWorkout>,
    val summary: JsonSummary,
)

@Serializable
data class JsonUnits(val weight: String)

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
data class JsonBodyweightEntry(val date: String, val value: Double)

@Serializable
data class JsonExercise(
    val id: String,
    val name: String,
    val equipment: String? = null,
    @SerialName("primary_muscles") val primaryMuscles: List<String>,
    @SerialName("secondary_muscles") val secondaryMuscles: List<String>,
)

@Serializable
data class JsonWorkout(
    val id: Long,
    @SerialName("start_time") val startTime: String,
    @SerialName("duration_min") val durationMin: Int? = null,
    val routine: String,
    @SerialName("bodyweight_kg") val bodyweightKg: Double? = null,
    /** The day of that weigh-in: the workout's, or up to 3 days before. */
    @SerialName("bodyweight_date") val bodyweightDate: String? = null,
    val notes: String? = null,
    val exercises: List<JsonWorkoutExercise>,
)

@Serializable
data class JsonWorkoutExercise(
    @SerialName("exercise_id") val exerciseId: String,
    val notes: String? = null,
    val sets: List<JsonSet>,
)

@Serializable
data class JsonSet(
    val index: Int,
    val type: String,
    @SerialName("weight_kg") val weightKg: Double? = null,
    val reps: Int? = null,
    @SerialName("duration_sec") val durationSec: Int? = null,
    val rpe: Double? = null,
    @SerialName("rest_sec") val restSec: Int? = null,
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
    @SerialName("best_set") val bestSet: String,
)
