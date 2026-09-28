package com.tczerwien.lifts.seed

import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.ExerciseEntity.Companion.TRACKING_ASSISTED_BODYWEIGHT
import com.tczerwien.lifts.data.ExerciseEntity.Companion.TRACKING_DURATION
import com.tczerwien.lifts.data.ExerciseEntity.Companion.TRACKING_REPS_ONLY
import com.tczerwien.lifts.data.ExerciseEntity.Companion.TRACKING_WEIGHTED_BODYWEIGHT
import com.tczerwien.lifts.data.ExerciseEntity.Companion.TRACKING_WEIGHT_REPS
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One record of the bundled free-exercise-db dataset. [force] and [mechanic] are parsed for
 * completeness but have no column on [ExerciseEntity].
 */
@Serializable
internal data class SeedExercise(
    val id: String,
    val name: String,
    val force: String? = null,
    val level: String? = null,
    val mechanic: String? = null,
    val equipment: String? = null,
    val primaryMuscles: List<String> = emptyList(),
    val secondaryMuscles: List<String> = emptyList(),
    val instructions: List<String> = emptyList(),
    val category: String,
)

/** Parses [ExerciseSeedData.json] into catalog [ExerciseEntity] rows. Pure JVM, no reflection. */
internal object SeedParser {
    private val json = Json { ignoreUnknownKeys = true }

    private val SEED_LIST_SERIALIZER = ListSerializer(SeedExercise.serializer())

    fun parse(raw: String = ExerciseSeedData.json): List<ExerciseEntity> {
        val records = json.decodeFromString(SEED_LIST_SERIALIZER, raw)
        val usedSlugs = HashSet<String>(records.size * 2)
        return records.map { record ->
            val slug = uniqueSlug(slugify(record.id), usedSlugs)
            ExerciseEntity(
                id = slug,
                name = record.name,
                equipment = record.equipment,
                primaryMuscles = record.primaryMuscles,
                secondaryMuscles = record.secondaryMuscles,
                category = record.category,
                level = record.level,
                instructions = record.instructions,
                isCustom = false,
                trackingType = defaultTrackingType(slug, record.category, record.equipment),
            )
        }
    }

    /**
     * Which numbers a seeded exercise records by default. The dataset has no such field, so this
     * is inferred: holds, stretches and cardio are timed; bodyweight moves count reps only; a few
     * known ids are weighted or assisted bodyweight; everything else is weight x reps.
     */
    /** The catalog's own name for exercise [id] (an app id, as [parse] makes them); null for none. Parses the catalog: call off the main thread. */
    fun catalogName(id: String): String? = parse().firstOrNull { it.id == id }?.name

    internal fun defaultTrackingType(slug: String, category: String, equipment: String?): String = when {
        slug in DURATION_IDS || category == "stretching" || category == "cardio" -> TRACKING_DURATION
        slug in ASSISTED_IDS -> TRACKING_ASSISTED_BODYWEIGHT
        slug in WEIGHTED_BODYWEIGHT_IDS -> TRACKING_WEIGHTED_BODYWEIGHT
        slug in REPS_ONLY_IDS || equipment == "body only" -> TRACKING_REPS_ONLY
        else -> TRACKING_WEIGHT_REPS
    }

    internal val DURATION_IDS = setOf(
        "plank",
        "side-bridge",
        "one-handed-hang",
        "isometric-chest-squeezes",
        "isometric-neck-exercise-front-and-back",
        "isometric-neck-exercise-sides",
    )
    internal val ASSISTED_IDS = setOf("band-assisted-pull-up")
    internal val WEIGHTED_BODYWEIGHT_IDS = setOf("weighted-pull-ups", "weighted-bench-dip")

    /** Bodyweight moves the dataset files under "other" equipment (bars, rings). */
    internal val REPS_ONLY_IDS = setOf(
        "parallel-bar-dip",
        "dips-chest-version",
        "ring-dips",
        "one-arm-chin-up",
        "scapular-pull-up",
    )

    /**
     * Lowercases a dataset id and collapses every non-alphanumeric run into a single hyphen,
     * e.g. "Barbell_Bench_Press_-_Medium_Grip" -> "barbell-bench-press-medium-grip".
     */
    internal fun slugify(id: String): String {
        val builder = StringBuilder(id.length)
        for (char in id.lowercase()) {
            if (char in 'a'..'z' || char in '0'..'9') {
                builder.append(char)
            } else if (builder.isNotEmpty() && builder.last() != '-') {
                builder.append('-')
            }
        }
        while (builder.isNotEmpty() && builder.last() == '-') {
            builder.deleteCharAt(builder.length - 1)
        }
        return if (builder.isEmpty()) "exercise" else builder.toString()
    }

    /** Returns [base] if unused, otherwise "base-2", "base-3", ... Records the result in [used]. */
    private fun uniqueSlug(base: String, used: MutableSet<String>): String {
        if (used.add(base)) return base
        var suffix = 2
        while (true) {
            val candidate = "$base-$suffix"
            if (used.add(candidate)) return candidate
            suffix++
        }
    }
}
