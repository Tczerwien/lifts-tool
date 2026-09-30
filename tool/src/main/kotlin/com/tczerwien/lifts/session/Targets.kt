package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.Tracking
import com.tczerwien.lifts.ui.Format

/**
 * A template's prescription for one exercise, and what the gym sees of it: the rep range and RIR
 * target the Template Editor steps ("6–12 reps · RIR 1"), the warm-ups it starts with, and the hint
 * that the top of the range was reached on every work set ("ADD LOAD", double progression). Pure,
 * covered by TargetsTest.
 */

/** The fewest and most reps a range can name. */
const val MIN_TARGET_REPS = 1
const val MAX_TARGET_REPS = 50

/** Where a range starts when he steps it up from none: 8–12, the commonest hypertrophy range. */
const val DEFAULT_REP_MIN = 8
const val DEFAULT_RANGE_WIDTH = 4

/** The most warm-ups a template can ask for. */
const val MAX_TEMPLATE_WARMUPS = 5

/** A rep range as the editor holds it; either end may be open (null). */
data class RepRange(val min: Int?, val max: Int?)

/**
 * The range's low end moved [delta] reps. Up from none it starts at [DEFAULT_REP_MIN] (or the top, if
 * that is lower); down past 1 it clears. Passing the top takes the top along, so the range never
 * turns inside out.
 */
fun stepRepMin(range: RepRange, delta: Int): RepRange {
    val min = range.min
    val next = when {
        min == null && delta > 0 -> range.max?.let { minOf(it, DEFAULT_REP_MIN) } ?: DEFAULT_REP_MIN
        min == null -> return range
        else -> min + delta
    }
    if (next < MIN_TARGET_REPS) return range.copy(min = null)
    val clamped = next.coerceAtMost(MAX_TARGET_REPS)
    return RepRange(clamped, range.max?.let { maxOf(it, clamped) })
}

/**
 * The range's top moved [delta] reps. Up from none it starts [DEFAULT_RANGE_WIDTH] above the low end
 * (8–12); down past 1 it clears. Dropping below the low end takes the low end along.
 */
fun stepRepMax(range: RepRange, delta: Int): RepRange {
    val max = range.max
    val next = when {
        max == null && delta > 0 -> ((range.min ?: DEFAULT_REP_MIN) + DEFAULT_RANGE_WIDTH).coerceAtMost(MAX_TARGET_REPS)
        max == null -> return range
        else -> max + delta
    }
    if (next < MIN_TARGET_REPS) return range.copy(max = null)
    val clamped = next.coerceAtMost(MAX_TARGET_REPS)
    return RepRange(range.min?.let { minOf(it, clamped) }, clamped)
}

/** "6–12", "8+" with no top, "≤12" with no bottom, "10" when both ends meet; null with neither. */
fun repRangeText(min: Int?, max: Int?): String? = when {
    min != null && max != null && min == max -> "$min"
    min != null && max != null -> "$min–$max"
    min != null -> "$min+"
    max != null -> "≤$max"
    else -> null
}

/** The RIR target moved [delta]: none, then 0 to [MAX_RIR]; below 0 clears it. */
fun stepTargetRir(rir: Int?, delta: Int): Int? = stepOptional(rir, delta, MAX_RIR)

/**
 * The warm-ups a template asks for, moved [delta]: none (null: repeat last session's, the default),
 * then 0 to [MAX_TEMPLATE_WARMUPS]. Below 0 goes back to last session's.
 */
fun stepTemplateWarmups(warmups: Int?, delta: Int): Int? = stepOptional(warmups, delta, MAX_TEMPLATE_WARMUPS)

/** null → 0 → … → [max]; stepping down from 0 is null again. */
private fun stepOptional(value: Int?, delta: Int, max: Int): Int? = when {
    value == null -> if (delta > 0) 0 else null
    value + delta < 0 -> null
    else -> (value + delta).coerceAtMost(max)
}

/**
 * The rest a template or workout slot stores for [restSec]: none when it equals [defaultSec], so the
 * slot follows Settings' default if that changes later. Nudging + then − is no change.
 */
fun restOverride(restSec: Int, defaultSec: Int): Int? = restSec.coerceAtLeast(0).takeIf { it != defaultSec }

/**
 * The Active Workout header's target line: "6–12 REPS · RIR 1", with [hint] after it; null when the
 * slot has no target and there is no hint.
 */
fun targetLine(repMin: Int?, repMax: Int?, targetRir: Int?, hint: String? = null): String? {
    val parts = listOfNotNull(
        repRangeText(repMin, repMax)?.let { "$it REPS" },
        targetRir?.let { "RIR $it" },
        hint,
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** The hint once every work set reached the top of the range: more weight, or less help on an assisted lift. */
const val HINT_ADD_LOAD = "ADD LOAD"
const val HINT_LESS_HELP = "LESS HELP"

/**
 * Double progression's cue: [HINT_ADD_LOAD] when every work set of this slot last session ([lastTime],
 * its completed sets) reached [repMax], the top of the range. Warm-ups and drop sets don't count; a
 * set to failure does. Nothing when the slot has no top, last time had no work set, the exercise has
 * no load to add (reps only, a hold), or today's work rows ([todayWeightsKg], what each would log)
 * already start heavier than last time's heaviest: he has added the load. On an assisted exercise,
 * less help is the load ([HINT_LESS_HELP]); weights are stored signed, so "heavier" means that too.
 * RIR plays no part: it isn't logged reliably enough to hold a cue back.
 */
fun progressionHint(
    tracking: Tracking,
    repMax: Int?,
    lastTime: List<SetEntity>,
    todayWeightsKg: List<Double?> = emptyList(),
): String? {
    repMax ?: return null
    if (!tracking.takesWeight || !tracking.takesReps) return null
    val work = lastTime.filter { it.completedAt != null && it.type != SET_TYPE_WARMUP && it.type != SET_TYPE_DROP }
    if (work.isEmpty() || work.any { (it.reps ?: 0) < repMax }) return null
    val lastHeaviest = work.maxOf { it.weightKg ?: 0.0 }
    val todayHeaviest = todayWeightsKg.filterNotNull().maxOrNull()
    if (todayHeaviest != null && todayHeaviest > lastHeaviest + WEIGHT_EPSILON_KG) return null
    return if (tracking == Tracking.ASSISTED_BODYWEIGHT) HINT_LESS_HELP else HINT_ADD_LOAD
}

/** Below any step a weight can move by (0.25 kg is the smallest). */
private const val WEIGHT_EPSILON_KG = 0.01

/**
 * A template exercise in one line for the Template Editor's list: "2W + 3 × 8–12 · RIR 1 · rest 3:00".
 * Warm-ups show only when the template asks for some (not for none, nor last session's); without a
 * range it is "3 sets"; [restSec] is the rest in effect (the slot's or Settings').
 */
fun templateSlotSummary(targetSets: Int, warmupSets: Int?, repMin: Int?, repMax: Int?, targetRir: Int?, restSec: Int): String {
    val range = repRangeText(repMin, repMax)
    val sets = if (range != null) "$targetSets × $range" else "$targetSets ${if (targetSets == 1) "set" else "sets"}"
    val warmups = warmupSets?.takeIf { it > 0 }?.let { "${it}W + " }.orEmpty()
    return listOfNotNull(warmups + sets, targetRir?.let { "RIR $it" }, "rest ${Format.clock(restSec)}").joinToString(" · ")
}
