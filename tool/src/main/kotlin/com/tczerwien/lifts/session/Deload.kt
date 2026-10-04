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

/** What switching a deload on or off does to one slot: its rows to delete, and how many plain work rows to add at its end. */
data class DeloadChange(val deleteIds: List<Long> = emptyList(), val addRows: Int = 0)

/**
 * What switching a deload [on] or off does to one slot's [rows] (in position order) (DL-2).
 *
 * On: the unticked work sets after the first [keep] are deleted, each with the unticked drops after it.
 * A ticked row is never deleted, warm-ups stay, and a drop never counts as a kept work set: it goes with
 * the work set before it. Values typed into a deleted row go with it.
 *
 * Off: plain work rows are added until the slot has its [targetSets] work sets again (drops not counted);
 * nothing when it already has them.
 */
fun deloadRows(rows: List<SetEntity>, targetSets: Int, keep: Int, on: Boolean): DeloadChange {
    if (!on) return DeloadChange(addRows = (targetSets - rows.count { it.isWorkSet() }).coerceAtLeast(0))
    val delete = ArrayList<Long>()
    var workSets = 0
    var parentDeleted = false
    for (row in rows) {
        when {
            row.isWorkSet() -> {
                workSets++
                parentDeleted = workSets > keep && row.completedAt == null
                if (parentDeleted) delete += row.id
            }
            row.type == SET_TYPE_DROP -> if (parentDeleted && row.completedAt == null) delete += row.id
            else -> parentDeleted = false
        }
    }
    return DeloadChange(deleteIds = delete)
}

/**
 * The RIR a slot shows and its RIR cell starts from (DL-3): in a deload the deload's own ([deloadRir],
 * Settings), else the slot's target. Display only: the slot keeps its own target, so switching the
 * deload off needs nothing put back.
 */
fun shownRir(slotRir: RirTarget?, deload: Boolean, deloadRir: RirTarget): RirTarget? = if (deload) deloadRir else slotRir
