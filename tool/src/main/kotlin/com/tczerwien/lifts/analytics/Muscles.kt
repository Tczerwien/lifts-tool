package com.tczerwien.lifts.analytics

/**
 * The muscles weekly sets are counted for (WS-2): the catalog's 17 groups (free-exercise-db's, the names
 * every exercise's primary and secondary muscles use) plus four finer ones the catalog folds into
 * "shoulders" and "chest", so a lateral raise can count for side delts and an incline press for upper
 * chest. Stored as these lower-case keys (an exercise's Counts For, the weekly targets, the exports);
 * shown as [muscleLabel]s. Plain anatomy, nothing of any one program.
 */

/** The catalog's 17 muscle groups, as its exercises name them. */
val CATALOG_MUSCLES: List<String> = listOf(
    "abdominals", "abductors", "adductors", "biceps", "calves", "chest", "forearms", "glutes", "hamstrings",
    "lats", "lower back", "middle back", "neck", "quadriceps", "shoulders", "traps", "triceps",
)

/** The four finer ones: the three heads of the delts, and the upper chest. */
val EXTRA_MUSCLES: List<String> = listOf("front delts", "side delts", "rear delts", "upper chest")

/** Every muscle a set can count for, by [muscleLabel], as the lists show them. */
val MUSCLES: List<String> = (CATALOG_MUSCLES + EXTRA_MUSCLES).sortedBy(::muscleLabel)

/**
 * A muscle as the screens show it: each word capitalised ("side delts" → "Side Delts"). A key Lifts
 * doesn't know (a newer catalog's, or a backup's) shows the same way rather than not at all.
 */
fun muscleLabel(muscle: String): String =
    muscle.trim().split(Regex("\\s+")).joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
