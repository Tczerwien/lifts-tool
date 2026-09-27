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
