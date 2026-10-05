package com.tczerwien.lifts.analytics

/**
 * Weekly sets against his targets, in the order Sets per Muscle and training-log.md list them (WS-4).
 * Pure, covered by MuscleTargetsTest.
 */

/** One row of Sets per Muscle: a muscle's hard sets in the week shown, against its [target] when it has one. */
data class MuscleRow(val muscle: String, val sets: Int, val target: Int?)

/**
 * The muscles to list, in order: first every muscle with a target (one with no set yet too: "0 / 12"), then
 * the ones with sets but no target; each part A to Z by [muscleLabel]. A muscle with neither isn't listed.
 * [withSets] are the muscles that have sets (in the week, or in any week of a table).
 */
fun muscleOrder(withSets: Collection<String>, targets: Map<String, Int>): List<String> =
    targets.keys.sortedBy(::muscleLabel) + (withSets.toSet() - targets.keys).sortedBy(::muscleLabel)

/** One week's rows ([muscleOrder]) from its hard sets per muscle ([sets]) and the [targets]. */
fun muscleRows(sets: Map<String, Int>, targets: Map<String, Int>): List<MuscleRow> =
    muscleOrder(sets.filterValues { it > 0 }.keys, targets).map { MuscleRow(it, sets[it] ?: 0, targets[it]) }

/** What "+ Target" offers: every one of the 21 muscles without a target yet, A to Z. */
fun targetChoices(targets: Map<String, Int>): List<String> = MUSCLES.filter { it !in targets }

/** Where a new target's stepper starts: a round number in the usual 10–20 hard sets a week; he steps from there. */
const val NEW_SET_TARGET = 10
