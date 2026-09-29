package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity

/**
 * Moving between the exercises of a workout: what the next-exercise row offers, where Active Workout
 * moves on its own once an exercise is done, and when. The Rest Timer's NEXT line names the same
 * exercise ([restNextContext]). Pure, covered by ExerciseOrderTest.
 */

/** With no rest to wait for (a 0 s slot, or Skip), how long an exercise's last tick stays on screen before moving on. */
const val AUTO_ADVANCE_NO_REST_MS = 2_000L

/**
 * For this long after Active Workout moves on by itself, a tap on NEXT or a checkbox is taken as meant
 * for the screen that was there: he looked up at the 0:00 flash and tapped as it changed.
 */
const val AUTO_ADVANCE_TAP_GRACE_MS = 1_000L

/** Whether a tap at [now] comes too soon after the screen moved on by itself at [movedOnAt] ([AUTO_ADVANCE_TAP_GRACE_MS]). */
fun tapTooSoonAfterMove(movedOnAt: Long?, now: Long): Boolean =
    movedOnAt != null && now - movedOnAt in 0 until AUTO_ADVANCE_TAP_GRACE_MS

/**
 * A tap on a ticked set's checkbox this soon after it was ticked is the second half of a double tap,
 * not an untick: undoing a tick takes a deliberate second tap.
 */
const val UNTICK_DOUBLE_TAP_MS = 600L

/** Whether unticking a set ticked at [completedAt] with a tap at [now] is a double tap to ignore ([UNTICK_DOUBLE_TAP_MS]). */
fun untickIsDoubleTap(completedAt: Long, now: Long): Boolean = now - completedAt in 0 until UNTICK_DOUBLE_TAP_MS

/** [slots] in order starting just after [currentSlotId] and wrapping round, without it; all of them when it isn't one. */
private fun slotsAfter(slots: List<WorkoutExerciseEntity>, currentSlotId: Long?): List<WorkoutExerciseEntity> {
    val ordered = slots.sortedBy { it.position }
    val index = ordered.indexOfFirst { it.id == currentSlotId }
    if (index < 0) return ordered
    return ordered.drop(index + 1) + ordered.take(index)
}

private fun hasUntickedRow(slotId: Long, sets: List<SetEntity>): Boolean =
    sets.any { it.workoutExerciseId == slotId && it.completedAt == null }

/** The first exercise after [currentSlotId] (in order, wrapping round) with a row still unticked; null when none has. */
fun firstUntickedSlotAfter(slots: List<WorkoutExerciseEntity>, sets: List<SetEntity>, currentSlotId: Long?): WorkoutExerciseEntity? =
    slotsAfter(slots, currentSlotId).firstOrNull { hasUntickedRow(it.id, sets) }

/**
 * The exercise the next-exercise row offers after [currentSlotId]: the first after it with a row
 * still unticked, so finished ones are skipped; when every other one is finished, simply the one
 * after it, so the row still moves on. Null when there is no other exercise.
 */
fun nextExerciseSlotId(slots: List<WorkoutExerciseEntity>, sets: List<SetEntity>, currentSlotId: Long?): Long? =
    (firstUntickedSlotAfter(slots, sets, currentSlotId) ?: slotsAfter(slots, currentSlotId).firstOrNull())?.id

/**
 * Where Active Workout moves on its own once every row of the exercise on screen ([currentSlotId])
 * is ticked: the next exercise with a row still unticked. Null while the exercise has an unticked
 * row, or no rows at all, and when every exercise is done.
 */
fun autoAdvanceSlotId(slots: List<WorkoutExerciseEntity>, sets: List<SetEntity>, currentSlotId: Long): Long? {
    val rows = sets.filter { it.workoutExerciseId == currentSlotId }
    if (rows.isEmpty() || rows.any { it.completedAt == null }) return null
    return firstUntickedSlotAfter(slots, sets, currentSlotId)?.id
}

/**
 * When Active Workout moves on after an exercise's last tick at [tickedAt]: as the rest ending at
 * [restEndMillis] ends, so he sees the next exercise when he looks up at 0:00. With no rest running
 * (none, or one that ended before the tick) it waits [AUTO_ADVANCE_NO_REST_MS], so the tick shows.
 */
fun autoAdvanceAt(tickedAt: Long, restEndMillis: Long?): Long =
    restEndMillis?.takeIf { it > tickedAt } ?: (tickedAt + AUTO_ADVANCE_NO_REST_MS)
