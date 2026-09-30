package com.tczerwien.lifts.ui

import com.tczerwien.lifts.data.ExerciseEntity

/**
 * Exercise names as the screens show them. The catalog (free-exercise-db) names are long and
 * equipment-first ("Barbell Bench Press - Medium Grip"), which a phone header can't fit. The
 * everyday lifts get a short gym name instead ([SHORT_NAMES]: "Bench Press"); everything else shows
 * its stored name. Renaming an exercise (Exercise Detail) changes its stored name, which then wins.
 */

/** A catalog lift's [short] name, shown while it still has its [catalogName] (he hasn't renamed it). */
private data class ShortName(val catalogName: String, val short: String)

/**
 * Short names for the common catalog lifts, by exercise id: a typical hypertrophy program's lifts
 * plus the everyday variants. "DB" is dumbbell. The standard variant gets the plain name; only real
 * variants keep a qualifier. Unique, 22 characters at most.
 */
private val SHORT_NAMES: Map<String, ShortName> = mapOf(
    "band-assisted-pull-up" to ShortName("Band Assisted Pull-Up", "Band-Assisted Pull-Up"),
    "barbell-bench-press-medium-grip" to ShortName("Barbell Bench Press - Medium Grip", "Bench Press"),
    "barbell-curl" to ShortName("Barbell Curl", "Barbell Curl"),
    "barbell-deadlift" to ShortName("Barbell Deadlift", "Deadlift"),
    "barbell-glute-bridge" to ShortName("Barbell Glute Bridge", "Barbell Glute Bridge"),
    "barbell-hip-thrust" to ShortName("Barbell Hip Thrust", "Hip Thrust"),
    "barbell-incline-bench-press-medium-grip" to ShortName("Barbell Incline Bench Press - Medium Grip", "Incline Bench Press"),
    "barbell-shrug" to ShortName("Barbell Shrug", "Barbell Shrug"),
    "barbell-squat" to ShortName("Barbell Squat", "Back Squat"),
    "barbell-walking-lunge" to ShortName("Barbell Walking Lunge", "Barbell Walking Lunge"),
    "bent-over-barbell-row" to ShortName("Bent Over Barbell Row", "Barbell Row"),
    "bodyweight-walking-lunge" to ShortName("Bodyweight Walking Lunge", "Walking Lunge"),
    "butt-lift-bridge" to ShortName("Butt Lift (Bridge)", "Glute Bridge"),
    "calf-press-on-the-leg-press-machine" to ShortName("Calf Press On The Leg Press Machine", "Leg Press Calf Raise"),
    "chin-up" to ShortName("Chin-Up", "Chin-Up"),
    "close-grip-barbell-bench-press" to ShortName("Close-Grip Barbell Bench Press", "Close-Grip Bench Press"),
    "dead-bug" to ShortName("Dead Bug", "Dead Bug"),
    "decline-barbell-bench-press" to ShortName("Decline Barbell Bench Press", "Decline Bench Press"),
    "decline-dumbbell-bench-press" to ShortName("Decline Dumbbell Bench Press", "Decline DB Press"),
    "dips-chest-version" to ShortName("Dips - Chest Version", "Chest Dip"),
    "dumbbell-bench-press" to ShortName("Dumbbell Bench Press", "DB Bench Press"),
    "dumbbell-bicep-curl" to ShortName("Dumbbell Bicep Curl", "DB Curl"),
    "dumbbell-incline-row" to ShortName("Dumbbell Incline Row", "Chest-Supported DB Row"),
    "dumbbell-shoulder-press" to ShortName("Dumbbell Shoulder Press", "DB Shoulder Press"),
    "dumbbell-shrug" to ShortName("Dumbbell Shrug", "DB Shrug"),
    "ez-bar-skullcrusher" to ShortName("EZ-Bar Skullcrusher", "Skull Crusher"),
    "face-pull" to ShortName("Face Pull", "Face Pull"),
    "flat-bench-lying-leg-raise" to ShortName("Flat Bench Lying Leg Raise", "Lying Leg Raise"),
    "front-barbell-squat" to ShortName("Front Barbell Squat", "Front Squat"),
    "goblet-squat" to ShortName("Goblet Squat", "Goblet Squat"),
    "good-morning" to ShortName("Good Morning", "Good Morning"),
    "hack-squat" to ShortName("Hack Squat", "Hack Squat"),
    "hammer-curls" to ShortName("Hammer Curls", "Hammer Curl"),
    "hanging-leg-raise" to ShortName("Hanging Leg Raise", "Hanging Leg Raise"),
    "hyperextensions-back-extensions" to ShortName("Hyperextensions (Back Extensions)", "Back Extension"),
    "incline-cable-flye" to ShortName("Incline Cable Flye", "Incline Cable Fly"),
    "incline-dumbbell-curl" to ShortName("Incline Dumbbell Curl", "Incline DB Curl"),
    "incline-dumbbell-press" to ShortName("Incline Dumbbell Press", "Incline DB Press"),
    "leg-extensions" to ShortName("Leg Extensions", "Leg Extension"),
    "leg-press" to ShortName("Leg Press", "Leg Press"),
    "leverage-iso-row" to ShortName("Leverage Iso Row", "Machine Row"),
    "lying-leg-curls" to ShortName("Lying Leg Curls", "Lying Leg Curl"),
    "lying-t-bar-row" to ShortName("Lying T-Bar Row", "Chest-Supported Row"),
    "machine-preacher-curls" to ShortName("Machine Preacher Curls", "Machine Preacher Curl"),
    "machine-shoulder-military-press" to ShortName("Machine Shoulder (Military) Press", "Machine Shoulder Press"),
    "one-arm-dumbbell-row" to ShortName("One-Arm Dumbbell Row", "DB Row"),
    "parallel-bar-dip" to ShortName("Parallel Bar Dip", "Dip"),
    "plank" to ShortName("Plank", "Plank"),
    "preacher-curl" to ShortName("Preacher Curl", "Preacher Curl"),
    "pullups" to ShortName("Pullups", "Pull-Up"),
    "pushups" to ShortName("Pushups", "Push-Up"),
    "reverse-machine-flyes" to ShortName("Reverse Machine Flyes", "Reverse Pec-Deck"),
    "romanian-deadlift" to ShortName("Romanian Deadlift", "Romanian Deadlift"),
    "seated-calf-raise" to ShortName("Seated Calf Raise", "Seated Calf Raise"),
    "seated-leg-curl" to ShortName("Seated Leg Curl", "Seated Leg Curl"),
    "seated-one-arm-cable-pulley-rows" to ShortName("Seated One-arm Cable Pulley Rows", "Single-Arm Cable Row"),
    "side-lateral-raise" to ShortName("Side Lateral Raise", "DB Lateral Raise"),
    "smith-machine-calf-raise" to ShortName("Smith Machine Calf Raise", "Smith Calf Raise"),
    "smith-machine-incline-bench-press" to ShortName("Smith Machine Incline Bench Press", "Smith Incline Press"),
    "smith-single-leg-split-squat" to ShortName("Smith Single-Leg Split Squat", "Smith Split Squat"),
    "split-squat-with-dumbbells" to ShortName("Split Squat with Dumbbells", "Bulgarian Split Squat"),
    "standing-barbell-calf-raise" to ShortName("Standing Barbell Calf Raise", "Barbell Calf Raise"),
    "standing-calf-raises" to ShortName("Standing Calf Raises", "Standing Calf Raise"),
    "standing-dumbbell-calf-raise" to ShortName("Standing Dumbbell Calf Raise", "DB Calf Raise"),
    "standing-dumbbell-press" to ShortName("Standing Dumbbell Press", "Standing DB Press"),
    "standing-low-pulley-deltoid-raise" to ShortName("Standing Low-Pulley Deltoid Raise", "Cable Lateral Raise"),
    "standing-military-press" to ShortName("Standing Military Press", "Overhead Press"),
    "sumo-deadlift" to ShortName("Sumo Deadlift", "Sumo Deadlift"),
    "triceps-overhead-extension-with-rope" to ShortName("Triceps Overhead Extension with Rope", "Overhead Cable Ext."),
    "triceps-pushdown" to ShortName("Triceps Pushdown", "Triceps Pushdown"),
    "triceps-pushdown-rope-attachment" to ShortName("Triceps Pushdown - Rope Attachment", "Rope Pushdown"),
    "v-bar-pulldown" to ShortName("V-Bar Pulldown", "Neutral-Grip Pulldown"),
    "wide-grip-lat-pulldown" to ShortName("Wide-Grip Lat Pulldown", "Lat Pulldown"),
)

/** What an exercise is called when its row is missing (a restored log naming a deleted one): on every screen and in the files. */
const val UNKNOWN_EXERCISE = "Unknown exercise"

/**
 * The name the screens show for [exercise]: its short name when it is one of the common catalog lifts
 * and still has its catalog name ([SHORT_NAMES]), else its own name. A name he gave it (a custom
 * exercise, or a catalog one he renamed) always wins.
 */
fun displayName(exercise: ExerciseEntity): String = displayName(exercise.id, exercise.name, exercise.isCustom)

/** [displayName] from the three things it reads, for lists that load exercises without their instructions. */
fun displayName(id: String, name: String, isCustom: Boolean): String =
    SHORT_NAMES[id]
        ?.takeIf { !isCustom && name == it.catalogName }
        ?.short
        ?: name.trim()

/** The catalog name of a lift with a short name, without reading the catalog; null for the others. */
fun knownCatalogName(id: String): String? = SHORT_NAMES[id]?.catalogName

/** The short name of catalog lift [id], whether or not it shows now (he may have renamed it); null for none. */
fun knownShortName(id: String): String? = SHORT_NAMES[id]?.short

/**
 * What a rename submitted as [typed] should store for [exercise], or null to leave it alone.
 * Whitespace runs (a Return included) collapse to single spaces. Submitting [shown], the name the
 * editor opened with, changes nothing. Blank puts a catalog exercise back to its catalog name
 * ([catalogName], asked only then) and so back to its short name; a custom exercise keeps its name.
 */
fun renameTarget(exercise: ExerciseEntity, typed: String, shown: String, catalogName: () -> String?): String? {
    val name = typed.replace(Regex("\\s+"), " ").trim()
    if (name == shown.trim()) return null
    val target = when {
        name.isNotEmpty() -> name
        exercise.isCustom -> return null
        else -> catalogName() ?: return null
    }
    return target.takeIf { it != exercise.name }
}

/** Short name by id, for tests. */
internal val shortNames: Map<String, String> get() = SHORT_NAMES.mapValues { it.value.short }

/** Catalog name by id for the lifts with a short name, for tests. */
internal val shortNameCatalogNames: Map<String, String> get() = SHORT_NAMES.mapValues { it.value.catalogName }

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
