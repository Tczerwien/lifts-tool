package com.tczerwien.lifts.ui

/**
 * Exercise names as the screens show them. The catalog (free-exercise-db) often leads with the
 * equipment ("Barbell Bench Press - Medium Grip"); the design's rule is Strong-style, equipment
 * last in brackets ("Bench Press - Medium Grip (Barbell)"), so the words that tell variants apart
 * come first and survive an ellipsis. Only the display changes: the stored name, which the exports
 * use, is left as it is.
 */

/** Catalog equipment values that are not a word a name would start with. */
private val NOT_EQUIPMENT = setOf("other", "body only", "none")

/** "kettlebells" → kettlebells, kettlebell; "barbell" → barbells, barbell. Longest first. */
private fun equipmentWords(equipment: String?): List<String> {
    val value = equipment?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it !in NOT_EQUIPMENT } ?: return emptyList()
    val other = if (value.endsWith("s")) value.dropLast(1) else value + "s"
    return listOf(value, other).sortedByDescending { it.length }
}

/**
 * [name] with a leading equipment word moved to the end in brackets: "Barbell Bench Press - Medium
 * Grip" with equipment "barbell" shows as "Bench Press - Medium Grip (Barbell)", and "Kettlebell
 * Windmill" (kettlebells) as "Windmill (Kettlebell)". Every other name shows as it is, brackets and
 * all: "Machine Shoulder (Military) Press" is never cut at its bracket. A name that already ends in
 * brackets, or would be left with nothing but the equipment, is left alone too.
 */
fun displayName(name: String, equipment: String?): String {
    val trimmed = name.trim()
    if (trimmed.endsWith(")")) return trimmed
    for (word in equipmentWords(equipment)) {
        if (!trimmed.startsWith("$word ", ignoreCase = true)) continue
        val rest = trimmed.substring(word.length).trim()
        if (rest.none { it.isLetter() }) return trimmed
        return "$rest (${trimmed.substring(0, word.length)})"
    }
    return trimmed
}

/**
 * Whether [name] already says which [equipment] it uses, as a whole word ("Close-Grip Barbell Bench
 * Press", "Curl (Barbell)"), so a line under it needn't say it again.
 */
fun namesEquipment(name: String, equipment: String?): Boolean =
    equipmentWords(equipment).any { word ->
        Regex("(^|[^a-z])${Regex.escape(word)}([^a-z]|$)", RegexOption.IGNORE_CASE).containsMatchIn(name)
    }
