package com.tczerwien.lifts.ui

import com.tczerwien.lifts.data.ExerciseEntity

/**
 * Exercise names as the screens show them. The catalog (free-exercise-db) names are long and
 * equipment-first ("Barbell Bench Press - Medium Grip"), which a phone header can't fit. The
 * everyday lifts get a short gym name instead ([SHORT_NAMES]: "Bench Press"); everything else shows
 * its catalog name as it is. Only the display changes: the stored name, which exports use, stays.
 */

/**
 * Short names for the common catalog lifts, by exercise id: a typical program's lifts (from a
 * private note) plus the everyday variants. "DB" is dumbbell. The standard
 * variant gets the plain name; only real variants keep a qualifier. Unique, 22 characters at most.
 */
private val SHORT_NAMES: Map<String, String> = mapOf(
    "band-assisted-pull-up" to "Band-Assisted Pull-Up",
    "barbell-bench-press-medium-grip" to "Bench Press",
    "barbell-curl" to "Barbell Curl",
    "barbell-deadlift" to "Deadlift",
    "barbell-glute-bridge" to "Barbell Glute Bridge",
    "barbell-hip-thrust" to "Hip Thrust",
    "barbell-incline-bench-press-medium-grip" to "Incline Bench Press",
    "barbell-shrug" to "Barbell Shrug",
    "barbell-squat" to "Back Squat",
    "barbell-walking-lunge" to "Barbell Walking Lunge",
    "bent-over-barbell-row" to "Barbell Row",
    "bodyweight-walking-lunge" to "Walking Lunge",
    "butt-lift-bridge" to "Glute Bridge",
    "calf-press-on-the-leg-press-machine" to "Leg Press Calf Raise",
    "chin-up" to "Chin-Up",
    "close-grip-barbell-bench-press" to "Close-Grip Bench Press",
    "dead-bug" to "Dead Bug",
    "decline-barbell-bench-press" to "Decline Bench Press",
    "decline-dumbbell-bench-press" to "Decline DB Press",
    "dips-chest-version" to "Chest Dip",
    "dumbbell-bench-press" to "DB Bench Press",
    "dumbbell-bicep-curl" to "DB Curl",
    "dumbbell-incline-row" to "Chest-Supported DB Row",
    "dumbbell-shoulder-press" to "DB Shoulder Press",
    "dumbbell-shrug" to "DB Shrug",
    "ez-bar-skullcrusher" to "Skull Crusher",
    "face-pull" to "Face Pull",
    "flat-bench-lying-leg-raise" to "Lying Leg Raise",
    "front-barbell-squat" to "Front Squat",
    "goblet-squat" to "Goblet Squat",
    "good-morning" to "Good Morning",
    "hack-squat" to "Hack Squat",
    "hammer-curls" to "Hammer Curl",
    "hanging-leg-raise" to "Hanging Leg Raise",
    "hyperextensions-back-extensions" to "Back Extension",
    "incline-cable-flye" to "Incline Cable Fly",
    "incline-dumbbell-curl" to "Incline DB Curl",
    "incline-dumbbell-press" to "Incline DB Press",
    "leg-extensions" to "Leg Extension",
    "leg-press" to "Leg Press",
    "leverage-iso-row" to "Machine Row",
    "lying-leg-curls" to "Lying Leg Curl",
    "lying-t-bar-row" to "Chest-Supported Row",
    "machine-preacher-curls" to "Machine Preacher Curl",
    "machine-shoulder-military-press" to "Machine Shoulder Press",
    "one-arm-dumbbell-row" to "DB Row",
    "parallel-bar-dip" to "Dip",
    "plank" to "Plank",
    "preacher-curl" to "Preacher Curl",
    "pullups" to "Pull-Up",
    "pushups" to "Push-Up",
    "reverse-machine-flyes" to "Reverse Pec-Deck",
    "romanian-deadlift" to "Romanian Deadlift",
    "seated-calf-raise" to "Seated Calf Raise",
    "seated-leg-curl" to "Seated Leg Curl",
    "seated-one-arm-cable-pulley-rows" to "Single-Arm Cable Row",
    "side-lateral-raise" to "DB Lateral Raise",
    "smith-machine-calf-raise" to "Smith Calf Raise",
    "smith-machine-incline-bench-press" to "Smith Incline Press",
    "smith-single-leg-split-squat" to "Smith Split Squat",
    "split-squat-with-dumbbells" to "Bulgarian Split Squat",
    "standing-barbell-calf-raise" to "Barbell Calf Raise",
    "standing-calf-raises" to "Standing Calf Raise",
    "standing-dumbbell-calf-raise" to "DB Calf Raise",
    "standing-dumbbell-press" to "Standing DB Press",
    "standing-low-pulley-deltoid-raise" to "Cable Lateral Raise",
    "standing-military-press" to "Overhead Press",
    "sumo-deadlift" to "Sumo Deadlift",
    "triceps-overhead-extension-with-rope" to "Overhead Cable Ext.",
    "triceps-pushdown" to "Triceps Pushdown",
    "triceps-pushdown-rope-attachment" to "Rope Pushdown",
    "v-bar-pulldown" to "Neutral-Grip Pulldown",
    "wide-grip-lat-pulldown" to "Lat Pulldown",
)

/**
 * The name the screens show for [exercise]: its short name when it is one of the common catalog
 * lifts ([SHORT_NAMES]), else its own name. A custom exercise always shows the name he gave it.
 */
fun displayName(exercise: ExerciseEntity): String =
    SHORT_NAMES[exercise.id]?.takeUnless { exercise.isCustom } ?: exercise.name.trim()

/** The short names, for tests. */
internal val shortNames: Map<String, String> get() = SHORT_NAMES

/** Catalog equipment values that are not a word a name would contain. */
private val NOT_EQUIPMENT = setOf("other", "body only", "none")

/** "kettlebells" → kettlebells, kettlebell; "barbell" → barbells, barbell. */
private fun equipmentWords(equipment: String?): List<String> {
    val value = equipment?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it !in NOT_EQUIPMENT } ?: return emptyList()
    val other = if (value.endsWith("s")) value.dropLast(1) else value + "s"
    return listOf(value, other)
}

/**
 * Whether [name] already says which [equipment] it uses, as a whole word ("Barbell Deadlift", "Smith
 * Incline Press" does not say "machine"), so the line under it needn't say it again.
 */
fun namesEquipment(name: String, equipment: String?): Boolean =
    equipmentWords(equipment).any { word ->
        Regex("(^|[^a-z])${Regex.escape(word)}([^a-z]|$)", RegexOption.IGNORE_CASE).containsMatchIn(name)
    }
