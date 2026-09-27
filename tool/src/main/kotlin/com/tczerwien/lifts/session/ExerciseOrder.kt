package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity

/** Moving between the exercises of a workout. Pure, covered by ExerciseOrderTest. */

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
