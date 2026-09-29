package com.tczerwien.lifts.data

/**
 * An exercise without its instructions, the bulk of the catalog's text: for lists over the whole
 * catalog (the picker, name checks). A Room projection of [ExerciseEntity]; no table of its own.
 */
data class ExerciseSummary(
    val id: String,
    val name: String,
    val equipment: String?,
    val primaryMuscles: List<String>,
    val category: String,
    val isCustom: Boolean,
    val hidden: Boolean,
)

/** [exercise] as a summary, for tests and for code that already holds the whole row. */
fun ExerciseEntity.summary() = ExerciseSummary(
    id = id,
    name = name,
    equipment = equipment,
    primaryMuscles = primaryMuscles,
    category = category,
    isCustom = isCustom,
    hidden = hidden,
)
