package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity

/**
 * Deload: one workout done with fewer work sets at an easier effort, loads left near normal ("…" ›
 * Deload on an open workout; Workout Detail marks a finished one). The defaults follow the deload
 * research: cut the volume by 40–60 %, keep the load, raise the reps in reserve (Bell et al. 2025;
 * Rogerson et al. 2024). The slots' own targets are never changed: a deload trims rows and shows its own
 * RIR, so switching it off restores the plan. Pure, covered by DeloadTest.
 */

/** The share of a slot's planned work sets a deload keeps, in percent: half, in the research's 40–60 % cut. */
const val DEFAULT_DELOAD_KEEP_PCT = 50

/** Settings › Deload steps the share from 30 to 70 % in 10 % steps. */
const val MIN_DELOAD_KEEP_PCT = 30
const val MAX_DELOAD_KEEP_PCT = 70
const val DELOAD_KEEP_STEP_PCT = 10

/** The RIR a deload aims for, whatever the slots' own targets say: 4–5, well short of failure ("raise RIR"). */
val DEFAULT_DELOAD_RIR = RirTarget(4, 5)

/** The target line's word in a deload, where ADD LOAD would be ([targetLine]). */
const val DELOAD_TAG = "DELOAD"

/** Settings' keep share moved [delta] steps, within [MIN_DELOAD_KEEP_PCT]..[MAX_DELOAD_KEEP_PCT]. */
fun stepDeloadKeep(pct: Int, delta: Int): Int =
    (pct + delta * DELOAD_KEEP_STEP_PCT).coerceIn(MIN_DELOAD_KEEP_PCT, MAX_DELOAD_KEEP_PCT)

/**
 * The work sets a deload keeps of a slot's [targetSets] at [pct] percent (DL-1): rounded up, at least 1.
 * At half: 3 → 2, 4 → 2, 2 → 1.
 */
fun deloadKeep(targetSets: Int, pct: Int): Int = maxOf(1, (targetSets * pct + 99) / 100)

/** A work set in a deload's count: normal or to failure. Warm-ups stay as they are; a drop is part of its parent. */
private fun SetEntity.isWorkSet(): Boolean = type == SET_TYPE_NORMAL || type == SET_TYPE_FAILURE

/** The work sets among one slot's [rows] in a deload's count: normal or to failure, ticked or not. */
fun deloadWorkSets(rows: List<SetEntity>): Int = rows.count { it.isWorkSet() }

/** What switching a deload on or off does to one slot: its rows to delete, and how many plain work rows to add at its end. */
data class DeloadChange(val deleteIds: List<Long> = emptyList(), val addRows: Int = 0)

/**
 * What switching a deload [on] or off does to one slot's [rows] (in position order) (DL-2).
 *
 * On: the slot keeps [keep] work sets, the done ones first: a work set ticked, or with a ticked drop after
 * it, is never deleted and counts toward the number (so a logged drop never loses the set above it), then
 * the unticked ones stay in order until it is reached, and the rest are deleted, each with the unticked
 * drops after it. Warm-ups stay, and a drop never counts as a kept work set: it goes
 * with the work set before it. Values typed into a deleted row go with it.
 *
 * Off: plain work rows are added until the slot has its [targetSets] work sets again (drops not counted);
 * nothing when it already has them.
 */
fun deloadRows(rows: List<SetEntity>, targetSets: Int, keep: Int, on: Boolean): DeloadChange {
    if (!on) return DeloadChange(addRows = (targetSets - deloadWorkSets(rows)).coerceAtLeast(0))
    // The work sets done: ticked, or with a ticked drop after them.
    val done = HashSet<Long>()
    var parent: SetEntity? = null
    for (row in rows) {
        when {
            row.isWorkSet() -> parent = row.also { if (it.completedAt != null) done += it.id }
            row.type == SET_TYPE_DROP -> if (row.completedAt != null) parent?.let { done += it.id }
            else -> parent = null
        }
    }
    val delete = ArrayList<Long>()
    // The not-done work sets that may stay: what the done ones leave of the kept number.
    var untickedLeft = keep - done.size
    var parentDeleted = false
    for (row in rows) {
        when {
            row.isWorkSet() -> {
                val isDone = row.id in done
                parentDeleted = !isDone && untickedLeft <= 0
                if (!isDone) untickedLeft--
                if (parentDeleted) delete += row.id
            }
            row.type == SET_TYPE_DROP -> if (parentDeleted && row.completedAt == null) delete += row.id
            else -> parentDeleted = false
        }
    }
    return DeloadChange(deleteIds = delete)
}

/**
 * [this] slot as a deload's Start Again begins it: its work sets cut to the deload's number of its plan
 * ([deloadKeep] at [keepPct]), warm-ups kept; as it is with no plan, or already that short (a copy of a
 * deload switched on that day). So a workout marked a deload after its Finish, its full sets done, is
 * copied as a deload too.
 */
fun PlannedSlot.deloadTrimmed(keepPct: Int): PlannedSlot {
    val planned = targetSets ?: return this
    var workLeft = deloadKeep(planned, keepPct)
    val kept = types.filter { type ->
        when {
            type == SET_TYPE_WARMUP -> true
            workLeft > 0 -> true.also { workLeft-- }
            else -> false
        }
    }
    return if (kept.size == types.size) this else copy(types = kept)
}

/**
 * The RIR a slot shows and its RIR cell starts from (DL-3): in a deload the deload's own ([deloadRir],
 * Settings), else the slot's target. Display only: the slot keeps its own target, so switching the
 * deload off needs nothing put back.
 */
fun shownRir(slotRir: RirTarget?, deload: Boolean, deloadRir: RirTarget): RirTarget? = if (deload) deloadRir else slotRir
