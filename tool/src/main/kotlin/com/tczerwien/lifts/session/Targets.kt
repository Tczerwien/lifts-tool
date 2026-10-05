package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.Tracking
import com.tczerwien.lifts.ui.Format

/**
 * A template's prescription for one exercise, and what the gym sees of it: the rep range and RIR
 * target the Template Editor steps ("6–12 reps · RIR 1–2"), the warm-ups it starts with, and the hint
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

/** The work sets a template exercise can ask for. */
const val MIN_TEMPLATE_SETS = 1
const val MAX_TEMPLATE_SETS = 10

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

/**
 * An RIR target: one number ([min] alone) or a range, "1–2" ([min] 1, [max] 2), as a slot stores it
 * (targetRir, targetRirMax). Ranges replaced B10's one number (B14).
 */
data class RirTarget(val min: Int, val max: Int? = null) {
    /** Where the RIR cell's first tap starts ([nextRir]): the top of the range, so "1–2" gives 2, then 1. */
    val top: Int get() = max ?: min

    /** "1", or "1–2" for a range. */
    val text: String get() = if (max != null) "$min–$max" else "$min"
}

/**
 * The target a slot or template stores as [targetRir] and [targetRirMax]: null without a number, and one
 * number when the top isn't above it (a top alone, or one at or below the number, says nothing more).
 */
fun rirTarget(targetRir: Int?, targetRirMax: Int?): RirTarget? =
    targetRir?.let { min -> RirTarget(min, targetRirMax?.takeIf { it > min }) }

/** The RIR stepper's steps after none: 0, 0–1, 1, 1–2, … 4–5, 5. */
val RIR_TARGET_STEPS: List<RirTarget> =
    (0..MAX_RIR).flatMap { n -> listOfNotNull(RirTarget(n), RirTarget(n, n + 1).takeIf { n < MAX_RIR }) }

/**
 * The RIR target moved [delta] steps along none, then [RIR_TARGET_STEPS]: up from none it is 0, down from
 * 0 it clears ([allowNone] false stops it at 0, for a target that must have one); 5 is the most. A range the
 * steps don't hold (1–3, from a restore) steps as its low end's range (1–2) would.
 */
fun stepRirTarget(target: RirTarget?, delta: Int, allowNone: Boolean = true): RirTarget? {
    val steps = RIR_TARGET_STEPS
    val index = when {
        target == null -> -1
        else -> steps.indexOf(if (target.max != null) RirTarget(target.min, target.min + 1) else target)
            .takeIf { it >= 0 } ?: steps.indexOfLast { it.min <= target.min }.coerceAtLeast(0)
    }
    val next = index + delta
    return when {
        next < 0 && allowNone -> null
        else -> steps[next.coerceIn(0, steps.lastIndex)]
    }
}

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
 * The Active Workout header's target line: "6–12 REPS · RIR 1–2" ("REPS/SIDE" on an exercise done one
 * side at a time, [perSide]), with [hint] after it, and "SUPERSET" last on an exercise done in turn
 * with its neighbour ([superset]); null when the slot has none of these. In a [deload] the hint's place
 * says [DELOAD_TAG], on every exercise: "8–12 REPS · RIR 4–5 · DELOAD".
 */
fun targetLine(
    repMin: Int?,
    repMax: Int?,
    rir: RirTarget?,
    hint: String? = null,
    perSide: Boolean = false,
    superset: Boolean = false,
    deload: Boolean = false,
): String? {
    val parts = listOfNotNull(
        repRangeText(repMin, repMax)?.let { if (perSide) "$it REPS/SIDE" else "$it REPS" },
        rir?.let { "RIR ${it.text}" },
        if (deload) DELOAD_TAG else hint,
        SUPERSET_TAG.takeIf { superset },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** The target line's word for an exercise done in turn with its neighbour: shown only on such exercises. */
const val SUPERSET_TAG = "SUPERSET"

/** The hint once every work set reached the top of the range: more weight, or less help on an assisted lift. */
const val HINT_ADD_LOAD = "ADD LOAD"
const val HINT_LESS_HELP = "LESS HELP"

/**
 * Double progression's cue: [HINT_ADD_LOAD] when every work set of this slot last session ([lastTime],
 * its completed sets) reached [repMax], the top of the range. Warm-ups and drops don't count ([rowKinds]: a
 * drop with no set above it is a set of its own); a set to failure does. Nothing when the slot has no top, last time had no work set, the exercise has
 * no load to add (reps only, a hold), or today's work rows ([todayWeightsKg], what each would log)
 * already start heavier than last time's heaviest: he has added the load. On an assisted exercise,
 * less help is the load ([HINT_LESS_HELP]); weights are stored signed, so "heavier" means that too.
 * With no help left to take away (a set last time done without any), there is no cue.
 * RIR plays no part: it isn't logged reliably enough to hold a cue back. None in a [deload] (DL-4): it
 * is no day to add load, and [lastTime] is the last normal session, so next week's cue is as it was.
 */
fun progressionHint(
    tracking: Tracking,
    repMax: Int?,
    lastTime: List<SetEntity>,
    todayWeightsKg: List<Double?> = emptyList(),
    deload: Boolean = false,
): String? {
    if (deload) return null
    repMax ?: return null
    if (!tracking.takesWeight || !tracking.takesReps) return null
    val done = lastTime.filter { it.completedAt != null }
    val work = done.zip(rowKinds(done)).filter { it.second == RowKind.WORK }.map { it.first }
    if (work.isEmpty() || work.any { (it.reps ?: 0) < repMax }) return null
    val lastHeaviest = work.maxOf { it.weightKg ?: 0.0 }
    if (tracking == Tracking.ASSISTED_BODYWEIGHT && lastHeaviest >= 0.0) return null
    val todayHeaviest = todayWeightsKg.filterNotNull().maxOrNull()
    if (todayHeaviest != null && todayHeaviest > lastHeaviest + WEIGHT_EPSILON_KG) return null
    return if (tracking == Tracking.ASSISTED_BODYWEIGHT) HINT_LESS_HELP else HINT_ADD_LOAD
}

/** Below any step a weight can move by (0.25 kg is the smallest). */
private const val WEIGHT_EPSILON_KG = 0.01

/**
 * A template exercise in one line for the Template Editor's list: "2W + 3 × 8–12 · RIR 1–2 · or Leg Curl · rest 3:00".
 * Warm-ups show when the template sets a number, "0W +" included (nothing: last session's); without a
 * range it is "3 sets"; its [alternatives]' names as "or Leg Curl", "or Leg Curl +1" with two; [restSec] is
 * the rest in effect (the slot's or Settings'). The rest is last: a long line loses it first.
 */
fun templateSlotSummary(
    targetSets: Int,
    warmupSets: Int?,
    repMin: Int?,
    repMax: Int?,
    rir: RirTarget?,
    restSec: Int,
    alternatives: List<String> = emptyList(),
): String {
    val range = repRangeText(repMin, repMax)
    val sets = if (range != null) "$targetSets × $range" else "$targetSets ${if (targetSets == 1) "set" else "sets"}"
    val warmups = warmupSets?.let { "${it}W + " }.orEmpty()
    val or = alternatives.firstOrNull()?.let { first -> "or $first" + if (alternatives.size > 1) " +${alternatives.size - 1}" else "" }
    return listOfNotNull(warmups + sets, rir?.let { "RIR ${it.text}" }, or, "rest ${Format.clock(restSec)}").joinToString(" · ")
}
