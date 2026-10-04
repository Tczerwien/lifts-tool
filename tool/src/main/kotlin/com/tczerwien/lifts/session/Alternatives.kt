package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity

/**
 * Exercise alternatives: other exercises a template exercise names as stand-ins (the machine is taken),
 * swapped in for one workout with one tap on Active Workout. A workout slot keeps its template exercise's
 * choices, main first ([slotChoices]), so the swap can cycle through them ([swapTarget]) and the next
 * start begins with the main again. Pure, covered by AlternativesTest.
 */

/**
 * The exercise this slot's start planned: its first choice, the main, when it has choices (a swap leaves
 * that one), else the one it does. A slot doing another is one swapped in for it.
 */
val WorkoutExerciseEntity.plannedExerciseId: String get() = choices.firstOrNull() ?: exerciseId

/** At most this many alternatives per template exercise: the Template Editor's "+" stops there. */
const val MAX_ALTERNATIVES = 3

/**
 * The choices a workout slot keeps for template exercise [main] and its [alternatives] (ALT-1): the main
 * first, then each alternative once, in their order (the main among them left out); none (empty) when no
 * other exercise is left, as for a template exercise without alternatives.
 */
fun slotChoices(main: String, alternatives: List<String>): List<String> {
    val others = alternatives.filter { it != main }.distinct()
    return if (others.isEmpty()) emptyList() else listOf(main) + others
}

/**
 * The choice after [current] among [choices], round to the first: [current] not among them goes to the
 * first, the main. Null when there is no other choice to go to.
 */
fun nextChoice(choices: List<String>, current: String): String? {
    if (choices.none { it != current }) return null
    return choices[(choices.indexOf(current) + 1) % choices.size]
}

/**
 * Where the swap icon takes a slot doing [current] (ALT-2): the next of its [choices] ([nextChoice]),
 * skipping those [usable] refuses (gone from the log, or hidden: he removed it), so a restored log naming
 * a missing exercise only offers the others. Null, no icon, once a set of the slot is ticked ([rows], its
 * rows: what was lifted stays that lift's), or with no other usable choice. The workout being open is the
 * caller's to check.
 */
fun swapTarget(choices: List<String>, current: String, rows: List<SetEntity>, usable: (String) -> Boolean): String? {
    if (rows.any { it.completedAt != null }) return null
    return nextChoice(choices.filter { it == current || usable(it) }, current)
}

/**
 * The choices a copy of a slot doing [current] keeps (Start Again, Save as Template; ALT-5): [current] first,
 * the main of the copy (it starts on it, so it is no swap there), then the others of its [choices] that [usable]
 * keeps (an exercise he removed comes back nowhere), each once, in their order; none when no other is left.
 */
fun keptChoices(choices: List<String>, current: String, usable: (String) -> Boolean): List<String> {
    val others = choices.filter { it != current && usable(it) }.distinct()
    return if (others.isEmpty()) emptyList() else listOf(current) + others
}
