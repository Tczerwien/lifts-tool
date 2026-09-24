package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionLogicTest {

    // --- restDurationSec ---

    @Test
    fun `work sets use the settings default without an override`() {
        assertEquals(180, restDurationSec(SET_TYPE_NORMAL, null, null, 180, 90))
    }

    @Test
    fun `work sets prefer the exercise override`() {
        assertEquals(240, restDurationSec(SET_TYPE_NORMAL, 240, 45, 180, 90))
    }

    @Test
    fun `warmup sets use the warmup default without an override`() {
        // The work override must not leak into warm-up rest.
        assertEquals(90, restDurationSec(SET_TYPE_WARMUP, 240, null, 180, 90))
    }

    @Test
    fun `warmup sets prefer the warmup override`() {
        assertEquals(45, restDurationSec(SET_TYPE_WARMUP, 240, 45, 180, 90))
    }

    @Test
    fun `failure sets rest like work sets`() {
        assertEquals(240, restDurationSec(SET_TYPE_FAILURE, 240, 45, 180, 90))
        assertEquals(180, restDurationSec(SET_TYPE_FAILURE, null, null, 180, 90))
    }

    // --- restRemainingSec ---

    @Test
    fun `remaining is exact on whole seconds`() {
        assertEquals(95, restRemainingSec(endMillis = 100_000, nowMillis = 5_000))
    }

    @Test
    fun `remaining rounds partial seconds up`() {
        assertEquals(1, restRemainingSec(endMillis = 5_001, nowMillis = 5_000))
        assertEquals(2, restRemainingSec(endMillis = 6_001, nowMillis = 5_000))
    }

    @Test
    fun `remaining is zero at and after the end`() {
        assertEquals(0, restRemainingSec(endMillis = 5_000, nowMillis = 5_000))
        assertEquals(0, restRemainingSec(endMillis = 5_000, nowMillis = 99_000))
    }

    // --- completedSetCount / totalVolumeKg ---

    @Test
    fun `completed count includes warmups but not pending sets`() {
        val sets = listOf(
            set(id = 1, type = SET_TYPE_WARMUP, weightKg = 60.0, reps = 8, completedAt = 1L),
            set(id = 2, weightKg = 100.0, reps = 5, completedAt = 2L),
            set(id = 3, weightKg = 100.0, reps = 5),
        )
        assertEquals(2, completedSetCount(sets))
    }

    @Test
    fun `volume sums completed work sets only`() {
        val sets = listOf(
            set(id = 1, type = SET_TYPE_WARMUP, weightKg = 60.0, reps = 8, completedAt = 1L),
            set(id = 2, weightKg = 100.0, reps = 5, completedAt = 2L),
            set(id = 3, type = SET_TYPE_FAILURE, weightKg = 100.0, reps = 4, completedAt = 3L),
            set(id = 4, weightKg = 102.5, reps = 5),
        )
        // 100x5 + 100x4; the warm-up and the pending set contribute nothing.
        assertEquals(900.0, totalVolumeKg(sets), 1e-9)
    }

    @Test
    fun `volume ignores sets missing weight or reps`() {
        val sets = listOf(
            set(id = 1, weightKg = null, reps = 10, completedAt = 1L),
            set(id = 2, weightKg = 100.0, reps = null, completedAt = 2L),
        )
        assertEquals(0.0, totalVolumeKg(sets), 1e-9)
    }

    // --- prSetIds ---

    @Test
    fun `first ever working set is a PR`() {
        val slots = listOf(slot(id = 1))
        val sets = listOf(set(id = 10, weightKg = 100.0, reps = 5, completedAt = 1L))
        assertEquals(setOf(10L), prSetIds(slots, sets, emptyMap()))
    }

    @Test
    fun `heavier set beats prior history`() {
        val slots = listOf(slot(id = 1))
        val prior = mapOf("bench" to listOf(prior(id = 90, weightKg = 100.0, reps = 5)))
        val sets = listOf(set(id = 10, weightKg = 102.5, reps = 5, completedAt = 1L))
        assertEquals(setOf(10L), prSetIds(slots, sets, prior))
    }

    @Test
    fun `higher e1RM at lower weight is a PR`() {
        val slots = listOf(slot(id = 1))
        // History best: 100x5 -> e1RM 116.67. New: 95x10 -> e1RM 126.67.
        val prior = mapOf("bench" to listOf(prior(id = 90, weightKg = 100.0, reps = 5)))
        val sets = listOf(set(id = 10, weightKg = 95.0, reps = 10, completedAt = 1L))
        assertEquals(setOf(10L), prSetIds(slots, sets, prior))
    }

    @Test
    fun `weaker set is not a PR`() {
        val slots = listOf(slot(id = 1))
        val prior = mapOf("bench" to listOf(prior(id = 90, weightKg = 102.5, reps = 5)))
        val sets = listOf(set(id = 10, weightKg = 100.0, reps = 5, completedAt = 1L))
        assertTrue(prSetIds(slots, sets, prior).isEmpty())
    }

    @Test
    fun `warmup sets never flag even when heaviest`() {
        val slots = listOf(slot(id = 1))
        val sets = listOf(
            set(id = 10, type = SET_TYPE_WARMUP, weightKg = 200.0, reps = 1, completedAt = 1L),
        )
        assertTrue(prSetIds(slots, sets, emptyMap()).isEmpty())
    }

    @Test
    fun `PRs chain within a workout in completion order`() {
        val slots = listOf(slot(id = 1))
        val prior = mapOf("bench" to listOf(prior(id = 90, weightKg = 95.0, reps = 5)))
        val sets = listOf(
            set(id = 10, position = 0, weightKg = 100.0, reps = 5, completedAt = 100L),
            set(id = 11, position = 1, weightKg = 102.5, reps = 5, completedAt = 200L),
            set(id = 12, position = 2, weightKg = 100.0, reps = 5, completedAt = 300L),
        )
        // 100 beats 95, then 102.5 beats 100; the final 100 beats nothing.
        assertEquals(setOf(10L, 11L), prSetIds(slots, sets, prior))
    }

    @Test
    fun `a later workout in the history can't steal a star`() {
        val slots = listOf(slot(id = 1))
        // 100x5 now, but the history also holds a heavier set from a LATER workout (time 5).
        val later = set(id = 91, slotId = 99, weightKg = 120.0, reps = 5, completedAt = 5L)
        val prior = mapOf("bench" to listOf(prior(id = 90, weightKg = 90.0, reps = 5), later))
        val sets = listOf(set(id = 10, weightKg = 100.0, reps = 5, completedAt = 1L))
        assertEquals(setOf(10L), prSetIds(slots, sets, prior))
    }

    @Test
    fun `sets beyond twelve reps only count by weight`() {
        val slots = listOf(slot(id = 1))
        // e1RM history 116.67 (100x5); 90x20 has no e1RM and 90 < 100 -> not a PR.
        val prior = mapOf("bench" to listOf(prior(id = 90, weightKg = 100.0, reps = 5)))
        val sets = listOf(set(id = 10, weightKg = 90.0, reps = 20, completedAt = 1L))
        assertTrue(prSetIds(slots, sets, prior).isEmpty())
    }

    // --- prRecords ---

    @Test
    fun `latest PR set per exercise wins`() {
        val slots = listOf(slot(id = 1))
        val sets = listOf(
            set(id = 10, position = 0, weightKg = 100.0, reps = 5, completedAt = 100L),
            set(id = 11, position = 1, weightKg = 102.5, reps = 5, completedAt = 200L),
        )
        val records = prRecords(slots, sets, setOf(10L, 11L), mapOf("bench" to "Bench Press (Barbell)"))
        assertEquals(1, records.size)
        assertEquals("Bench Press (Barbell)", records[0].exerciseName)
        assertEquals(102.5, records[0].weightKg, 1e-9)
        assertEquals(5, records[0].reps)
    }

    @Test
    fun `records follow slot order and fall back to the exercise id`() {
        val slots = listOf(
            slot(id = 1, exerciseId = "dip", position = 1),
            slot(id = 2, exerciseId = "bench", position = 0),
        )
        val sets = listOf(
            set(id = 10, slotId = 1, weightKg = 20.0, reps = 8, completedAt = 300L),
            set(id = 11, slotId = 2, weightKg = 100.0, reps = 5, completedAt = 100L),
        )
        val records = prRecords(slots, sets, setOf(10L, 11L), mapOf("bench" to "Bench Press"))
        assertEquals(listOf("Bench Press", "dip"), records.map { it.exerciseName })
    }

    // --- nextPendingSet ---

    @Test
    fun `no exercises means no next set`() {
        assertNull(nextPendingSet(emptyList(), emptyList()))
    }

    @Test
    fun `with no completions the first pending set is next`() {
        val slots = listOf(slot(id = 1, position = 0), slot(id = 2, exerciseId = "ohp", position = 1))
        val sets = listOf(
            set(id = 10, slotId = 1, position = 0),
            set(id = 11, slotId = 1, position = 1),
            set(id = 12, slotId = 2, position = 0),
        )
        val next = nextPendingSet(slots, sets)!!
        assertEquals(10L, next.set.id)
        assertEquals(1, next.setNumber)
        assertEquals(2, next.setCount)
    }

    @Test
    fun `stays in the exercise of the last completion`() {
        val slots = listOf(slot(id = 1, position = 0), slot(id = 2, exerciseId = "ohp", position = 1))
        val sets = listOf(
            set(id = 10, slotId = 1, position = 0, completedAt = 100L),
            set(id = 11, slotId = 1, position = 1, completedAt = 200L),
            set(id = 12, slotId = 1, position = 2),
            set(id = 13, slotId = 2, position = 0),
        )
        val next = nextPendingSet(slots, sets)!!
        assertEquals(12L, next.set.id)
        assertEquals(3, next.setNumber)
        assertEquals(3, next.setCount)
    }

    @Test
    fun `moves to the following exercise once the current is exhausted`() {
        val slots = listOf(slot(id = 1, position = 0), slot(id = 2, exerciseId = "ohp", position = 1))
        val sets = listOf(
            set(id = 10, slotId = 1, position = 0, completedAt = 100L),
            set(id = 11, slotId = 2, position = 0),
        )
        val next = nextPendingSet(slots, sets)!!
        assertEquals(11L, next.set.id)
        assertEquals("ohp", next.slot.exerciseId)
    }

    @Test
    fun `wraps around to an earlier exercise with pending sets`() {
        val slots = listOf(slot(id = 1, position = 0), slot(id = 2, exerciseId = "ohp", position = 1))
        val sets = listOf(
            set(id = 10, slotId = 1, position = 0),
            set(id = 11, slotId = 2, position = 0, completedAt = 100L),
        )
        val next = nextPendingSet(slots, sets)!!
        assertEquals(10L, next.set.id)
        assertEquals("bench", next.slot.exerciseId)
    }

    @Test
    fun `all sets completed means no next set`() {
        val slots = listOf(slot(id = 1))
        val sets = listOf(set(id = 10, slotId = 1, completedAt = 100L))
        assertNull(nextPendingSet(slots, sets))
    }

    // --- Fixtures ---

    private fun slot(id: Long, exerciseId: String = "bench", position: Int = 0) =
        WorkoutExerciseEntity(id = id, workoutId = 1, exerciseId = exerciseId, position = position)

    private fun set(
        id: Long,
        slotId: Long = 1,
        position: Int = 0,
        type: String = SET_TYPE_NORMAL,
        weightKg: Double? = null,
        reps: Int? = null,
        completedAt: Long? = null,
    ) = SetEntity(
        id = id,
        workoutExerciseId = slotId,
        position = position,
        type = type,
        weightKg = weightKg,
        reps = reps,
        completedAt = completedAt,
    )

    /** A completed historical set from another workout (slot id irrelevant to isPr). */
    /** A set from an earlier workout: completed at time 0, before every set these tests log. */
    private fun prior(id: Long, weightKg: Double, reps: Int) =
        set(id = id, slotId = 99, weightKg = weightKg, reps = reps, completedAt = 0L)
}
