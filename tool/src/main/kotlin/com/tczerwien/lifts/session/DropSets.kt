package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.Tracking
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Drop sets: a lighter set done straight after a work set, as part of it. No column says which set a drop
 * belongs to: its position does. It belongs to the work set above it, with only drops between (a chain:
 * each one lighter again, all of the same set), is numbered with it ("3 D" under set 3), and is a third kind
 * of row wherever rows are matched or counted, so work sets are counted and compared without their drops.
 * A drop with no work set above it (the first row, or one under a warm-up: the marker can make it) belongs
 * to none and is a set of its own, as every drop was before B16. Pure, covered by DropSetsTest.
 */

/** What a row is wherever rows are matched or counted ([rowKinds]). */
enum class RowKind {
    WARMUP,

    /** Normal, to failure, or a drop that belongs to no work set. */
    WORK,

    /** A drop that belongs to the work set above it ([dropParent]). */
    DROP,
}

/** The kind of each of one slot's rows, from their [types] in position order (DR-1). */
fun rowKinds(types: List<String>): List<RowKind> {
    // Whether the rows since the last warm-up began with a work set, so a drop here has one to belong to.
    var underWork = false
    return types.map { type ->
        when {
            type == SET_TYPE_WARMUP -> RowKind.WARMUP.also { underWork = false }
            type == SET_TYPE_DROP && underWork -> RowKind.DROP
            else -> RowKind.WORK.also { underWork = true }
        }
    }
}

/** [rowKinds] of one slot's [rows] in position order. */
@JvmName("rowKindsOfSets")
fun rowKinds(rows: List<SetEntity>): List<RowKind> = rowKinds(rows.map { it.type })

/**
 * The index of the work set row [index] of [rows] (one slot, in position order) belongs to as a drop: the
 * nearest work set above it. Null when it is no drop, or a drop that belongs to none (DR-1).
 */
fun dropParent(rows: List<SetEntity>, index: Int): Int? {
    val kinds = rowKinds(rows)
    if (kinds.getOrNull(index) != RowKind.DROP) return null
    return (index - 1 downTo 0).first { kinds[it] == RowKind.WORK }
}

/**
 * The set markers of one slot's [rows] in position order: "W" for a warm-up, "1".."n" for the work sets
 * in order, and a drop its work set's number, so the drop under set 3 reads "3 D" (the row adds the "D")
 * and the set after it is still 4 (DR-1).
 */
fun setMarkers(rows: List<SetEntity>): List<String> {
    var number = 0
    return rowKinds(rows).map { kind ->
        when (kind) {
            RowKind.WARMUP -> "W"
            RowKind.WORK -> (++number).toString()
            RowKind.DROP -> number.toString()
        }
    }
}

/**
 * Whether the row right after set [setId] in its slot ([sets]: the slot's rows, or any rows they are among) is
 * a drop still to do: then ticking [setId] starts no rest and stays on the exercise, for the drop straight
 * after it (DR-6, Hevy's rule: no timer before a drop).
 */
fun dropFollows(sets: List<SetEntity>, setId: Long): Boolean {
    val set = sets.firstOrNull { it.id == setId } ?: return false
    val rows = sets.filter { it.workoutExerciseId == set.workoutExerciseId }.sortedWith(compareBy({ it.position }, { it.id }))
    val next = rows.getOrNull(rows.indexOf(set) + 1) ?: return false
    return next.type == SET_TYPE_DROP && next.completedAt == null
}

/**
 * An exercise's progress as the NEXT row and the exercise list show it, "done/total": its work sets ticked,
 * of its work sets ([rowKinds]), as the Rest Timer's "Set 2 of 3" counts. Warm-ups and drops are left out, so
 * a drop added after the last set doesn't make a finished exercise read "3/4" (DR-4).
 */
fun workSetProgress(rows: List<SetEntity>): Pair<Int, Int> {
    val work = rows.zip(rowKinds(rows)).filter { it.second == RowKind.WORK }.map { it.first }
    return work.count { it.completedAt != null } to work.size
}

/**
 * A drop's load as a share of the row above it: about 20 % lighter, the figure Renaissance Periodization gives
 * for a drop (Bell et al. 2025 review drops of 10–25 %).
 */
const val DROP_LOAD_FRACTION = 0.8

/**
 * Where a drop starts (DR-2), each number on its own: last time's matching drop ([lastDrop], the drop's
 * PREVIOUS) when there is one; else from [above], the row above it as it stands (the set it belongs to, or the
 * drop before it in a chain): its reps or seconds, and [DROP_LOAD_FRACTION] of its load, snapped down to the
 * weight [step] (in [unit], as the steppers move), never below none. Snapped down, it is always at least one
 * step lighter. On a weighted bodyweight lift that is the load added, down to none (bodyweight alone); on an
 * assisted one the help grows instead (less load): divided by the share, snapped up, at least one step more.
 * Weights are stored kg (help negative, [Tracking.storedWeight]). Only what [tracking] records.
 */
fun dropStart(tracking: Tracking, above: RowValues, lastDrop: SetEntity?, unit: WeightUnit, step: Double): RowValues =
    RowValues(
        weightKg = (lastDrop?.weightKg ?: lighterKg(tracking, above.weightKg, unit, step))
            ?.takeIf { tracking.takesWeight }?.let(tracking::asLogged),
        reps = (lastDrop?.reps ?: above.reps)?.takeIf { tracking.takesReps },
        durationSec = (lastDrop?.durationSec ?: above.durationSec)?.takeIf { tracking.takesDuration },
    )

/** [dropStart]'s load from [aboveKg]; null for a lift that takes none, or a plain lift with none to drop from. */
private fun lighterKg(tracking: Tracking, aboveKg: Double?, unit: WeightUnit, step: Double): Double? {
    if (!tracking.takesWeight) return null
    // Bodyweight with nothing added (or no help) starts from 0; a barbell lift with no weight has nothing to drop from.
    val shownKg = tracking.shownWeight(aboveKg) ?: if (tracking == Tracking.WEIGHT_REPS) return null else 0.0
    val shown = Format.roundWeight(Format.kgToDisplay(shownKg, unit), unit)
    val next = if (tracking == Tracking.ASSISTED_BODYWEIGHT) {
        maxOf(snap(shown / DROP_LOAD_FRACTION, step, up = true), Format.stepWeight(shown, 1, step))
    } else {
        snap(shown * DROP_LOAD_FRACTION, step, up = false)
    }
    return tracking.storedWeight(Format.displayToKg(Format.roundWeight(next, unit), unit))
}

/** [display] snapped to the grid of [step] (both in display units), [up] or down, never below 0. */
private fun snap(display: Double, step: Double, up: Boolean): Double {
    val onGrid = display / step
    // In grid steps: 150 / 0.2 is 749.999…, which is on the grid.
    val units = if (up) ceil(onGrid - 1e-6) else floor(onGrid + 1e-6)
    return ((units * step * 1000).roundToLong() / 1000.0).coerceAtLeast(0.0)
}
