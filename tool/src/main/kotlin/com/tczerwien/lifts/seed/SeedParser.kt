package com.tczerwien.lifts.seed

import com.tczerwien.lifts.data.ExerciseEntity
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
            ExerciseEntity(
                id = uniqueSlug(slugify(record.id), usedSlugs),
                name = record.name,
                equipment = record.equipment,
                primaryMuscles = record.primaryMuscles,
                secondaryMuscles = record.secondaryMuscles,
                category = record.category,
                level = record.level,
                instructions = record.instructions,
                isCustom = false,
            )
        }
    }

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
