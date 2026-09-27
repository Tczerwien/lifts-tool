package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Moving between exercises: the next-exercise row. */
class ExerciseOrderTest {

    // Squat (1), Bench (2), Row (3), Curl (4), in that order.
    private val slots = listOf(slot(1, 0), slot(2, 1), slot(3, 2), slot(4, 3))

    @Test
    fun `the next-exercise row skips finished exercises and wraps round`() {
        val sets = listOf(row(11, 1), row(21, 2, done = true), row(31, 3), row(41, 4))
        assertEquals(3L, nextExerciseSlotId(slots, sets, currentSlotId = 1), "Bench is done")
        assertEquals(1L, nextExerciseSlotId(slots, sets, currentSlotId = 4), "from the last, round to the first")
    }

    @Test
    fun `with every other exercise finished, the row still offers the one after`() {
        val sets = listOf(row(11, 1), row(21, 2, done = true), row(31, 3, done = true), row(41, 4, done = true))
        assertEquals(2L, nextExerciseSlotId(slots, sets, currentSlotId = 1))
    }

    @Test
    fun `a workout with one exercise has no next`() {
        assertNull(nextExerciseSlotId(listOf(slot(1, 0)), listOf(row(11, 1)), currentSlotId = 1))
    }

    private fun slot(id: Long, position: Int) =
        WorkoutExerciseEntity(id = id, workoutId = 1, exerciseId = "ex$id", position = position)

    private fun row(id: Long, slot: Long, done: Boolean = false, at: Long = 5L) = SetEntity(
        id = id,
        workoutExerciseId = slot,
        position = id.toInt() % 10,
        type = SET_TYPE_NORMAL,
        completedAt = if (done) at else null,
    )
}
