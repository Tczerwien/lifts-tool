package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.TemplateExerciseEntity
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `new records keep a weighted PR over a later reps-only PR`() {
        val slots = listOf(slot(id = 1, exerciseId = "dips"), slot(id = 2, exerciseId = "pullups", position = 1))
        val sets = listOf(
            set(id = 10, slotId = 1, weightKg = 20.0, reps = 8, completedAt = 1L),
            set(id = 11, slotId = 1, weightKg = null, reps = 15, completedAt = 2L),
            set(id = 12, slotId = 2, weightKg = 0.0, reps = 12, completedAt = 3L),
        )
        val ids = prSetIds(slots, sets, emptyMap())
        assertEquals(setOf(10L, 11L, 12L), ids, "weighted and weightless firsts are both PRs")
        val records = prRecords(slots, sets, ids, emptyMap())
        assertEquals(listOf("dips" to 20.0, "pullups" to null), records.map { it.exerciseId to it.weightKg })
        assertEquals(12, records[1].reps, "a reps-only record keeps its reps and shows no 0 weight")
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
        assertEquals(102.5, records[0].weightKg!!, 1e-9)
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

    // --- Workout lifecycle ---

    @Test
    fun `pending count is the sets not ticked, whatever their values`() {
        val sets = listOf(
            set(id = 1, weightKg = 100.0, reps = 5, completedAt = 1L),
            set(id = 2, weightKg = 100.0, reps = 5),
            set(id = 3),
        )
        assertEquals(2, pendingSetCount(sets))
    }

    @Test
    fun `last activity is the latest logged set, else the start`() {
        assertEquals(1_000L, lastActivityMillis(1_000L, listOf(set(id = 1))))
        val sets = listOf(set(id = 1, completedAt = 5_000L), set(id = 2, completedAt = 3_000L), set(id = 3))
        assertEquals(5_000L, lastActivityMillis(1_000L, sets))
    }

    @Test
    fun `an empty workout with no exercises is untouched`() {
        assertTrue(isUntouched(workout(), emptyList(), emptyList(), planned = null))
    }

    @Test
    fun `adding an exercise to an empty workout touches it`() {
        assertFalse(isUntouched(workout(), listOf(slot(id = 1)), listOf(set(id = 1)), planned = null))
    }

    @Test
    fun `a template workout exactly as started is untouched`() {
        // Last session's warm-up is part of the start, so it doesn't make the workout look touched.
        assertEquals(SET_TYPE_WARMUP, benchAndSquatPlan.first().types.first())
        val (slots, sets) = startedFrom(benchAndSquatPlan)
        assertTrue(isUntouched(workout(templateId = 7), slots, sets, benchAndSquatPlan))
    }

    @Test
    fun `any value, tick, type change, extra set or note touches a template workout`() {
        val (slots, sets) = startedFrom(benchAndSquatPlan)
        val first = sets.first()
        val changed = listOf(
            first.copy(weightKg = 60.0),
            first.copy(reps = 8),
            first.copy(completedAt = 5L),
            first.copy(type = SET_TYPE_NORMAL),
        )
        for (edit in changed) {
            val edited = sets.map { if (it.id == first.id) edit else it }
            assertFalse(isUntouched(workout(templateId = 7), slots, edited, benchAndSquatPlan), "$edit")
        }
        val extraSet = sets + set(id = 99, slotId = slots.first().id, position = 3)
        assertFalse(isUntouched(workout(templateId = 7), slots, extraSet, benchAndSquatPlan))
        assertFalse(isUntouched(workout(templateId = 7, notes = "felt heavy"), slots, sets, benchAndSquatPlan))
    }

    @Test
    fun `a removed exercise or a template edited since the start counts as touched`() {
        val (slots, sets) = startedFrom(benchAndSquatPlan)
        val withoutSquat = slots.filter { it.exerciseId != "squat" }
        assertFalse(isUntouched(workout(templateId = 7), withoutSquat, sets, benchAndSquatPlan))
        val templateNow = benchAndSquat.map { if (it.exerciseId == "squat") it.copy(targetSets = 5) else it }
        assertFalse(isUntouched(workout(templateId = 7), slots, sets, planTemplate(templateNow, benchLastTime)))
        assertFalse(isUntouched(workout(templateId = 7), slots, sets, emptyList()), "template deleted")
    }

    @Test
    fun `a workout used within four hours is kept`() {
        val hour = 3_600_000L
        val sets = listOf(set(id = 1, completedAt = 10 * hour))
        assertEquals(OpenWorkoutAction.Keep, staleAction(startedAt = 0, sets = sets, now = 14 * hour))
        // Measured from the last logged set, not the start.
        assertEquals(OpenWorkoutAction.Keep, staleAction(startedAt = 0, sets = sets, now = 13 * hour))
    }

    @Test
    fun `a stale workout is closed at its last logged set`() {
        val hour = 3_600_000L
        val sets = listOf(
            set(id = 1, completedAt = 2 * hour),
            set(id = 2, completedAt = 3 * hour),
            set(id = 3),
        )
        assertEquals(
            OpenWorkoutAction.CloseAt(3 * hour),
            staleAction(startedAt = hour, sets = sets, now = 1_500 * hour),
        )
    }

    @Test
    fun `a stale workout with nothing logged is deleted`() {
        val hour = 3_600_000L
        val sets = listOf(set(id = 1, weightKg = 100.0, reps = 5))
        assertEquals(OpenWorkoutAction.Delete, staleAction(startedAt = 0, sets = sets, now = 5 * hour))
    }

    @Test
    fun `stale starts one millisecond after four idle hours`() {
        val fourHours = 4 * 3_600_000L
        val sets = listOf(set(id = 1, completedAt = 1_000L))
        assertEquals(OpenWorkoutAction.Keep, staleAction(startedAt = 0, sets = sets, now = 1_000L + fourHours))
        assertEquals(
            OpenWorkoutAction.CloseAt(1_000L),
            staleAction(startedAt = 0, sets = sets, now = 1_000L + fourHours + 1),
        )
    }

    @Test
    fun `Finish records now, or the last logged set after a long idle`() {
        val hour = 3_600_000L
        val sets = listOf(set(id = 1, completedAt = 19 * hour), set(id = 2))
        // Finished during the session: the real end.
        assertEquals(20 * hour, finishedAt(startedAt = 18 * hour, sets = sets, now = 20 * hour))
        // The phone slept on Active Workout and Finish came the next morning.
        assertEquals(19 * hour, finishedAt(startedAt = 18 * hour, sets = sets, now = 32 * hour))
    }

    @Test
    fun `a clock set back keeps the workout`() {
        val sets = listOf(set(id = 1, completedAt = 10_000_000L))
        assertEquals(OpenWorkoutAction.Keep, staleAction(startedAt = 0, sets = sets, now = 1_000L))
    }

    @Test
    fun `Home keeps one open workout, deleting untouched ones and closing older ones`() {
        val hour = 3_600_000L
        val now = 3 * hour
        val plan = planOpenWorkouts(
            listOf(
                // Newest first. A mistaken tap: nothing in it.
                OpenWorkout(id = 4, startedAt = now, sets = emptyList(), untouched = true),
                // The real workout, so it stays open even though it isn't the newest row.
                OpenWorkout(id = 3, startedAt = hour, sets = listOf(set(id = 1, completedAt = 2 * hour)), false),
                // An older orphan with sets logged: closed at its last set.
                OpenWorkout(id = 2, startedAt = 0, sets = listOf(set(id = 2, completedAt = hour / 2)), false),
                // An older orphan with nothing logged: deleted.
                OpenWorkout(id = 1, startedAt = 0, sets = listOf(set(id = 3)), untouched = false),
            ),
            now,
        )
        assertEquals(OpenWorkoutAction.Delete, plan[4])
        assertEquals(OpenWorkoutAction.Keep, plan[3])
        assertEquals(OpenWorkoutAction.CloseAt(hour / 2), plan[2])
        assertEquals(OpenWorkoutAction.Delete, plan[1])
    }

    @Test
    fun `a stale newest workout is closed too, leaving none open`() {
        val hour = 3_600_000L
        val plan = planOpenWorkouts(
            listOf(OpenWorkout(id = 1, startedAt = 0, sets = listOf(set(id = 1, completedAt = hour)), false)),
            now = 1_500 * hour,
        )
        assertEquals(mapOf(1L to OpenWorkoutAction.CloseAt(hour)), plan)
    }

    @Test
    fun `a cold start reopens a workout used in the last two hours only`() {
        val minute = 60_000L
        assertTrue(shouldAutoResume(lastActivity = 0, now = 119 * minute))
        assertTrue(shouldAutoResume(lastActivity = 0, now = 120 * minute))
        assertFalse(shouldAutoResume(lastActivity = 0, now = 121 * minute))
        assertFalse(shouldAutoResume(lastActivity = 10 * minute, now = 0), "clock set back")
    }

    // --- Fixtures ---

    private val benchAndSquat = listOf(
        TemplateExerciseEntity(id = 1, templateId = 7, exerciseId = "bench", position = 0, targetSets = 3),
        TemplateExerciseEntity(id = 2, templateId = 7, exerciseId = "squat", position = 1, targetSets = 2),
    )

    /** Last session's Bench: one warm-up, then three work sets. */
    private val benchLastTime = mapOf(
        "bench" to listOf(listOf(
            set(id = 50, slotId = 40, position = 0, type = SET_TYPE_WARMUP, weightKg = 40.0, reps = 10, completedAt = 1L),
        ) + List(3) { set(id = 51L + it, slotId = 40, position = it + 1, weightKg = 60.0, reps = 5, completedAt = 2L + it) }),
    )

    private val benchAndSquatPlan = planTemplate(benchAndSquat, benchLastTime)

    /** The slots and sets ActiveSession.startFromTemplate creates for [plan]: rows with no values. */
    private fun startedFrom(plan: List<PlannedSlot>): Pair<List<WorkoutExerciseEntity>, List<SetEntity>> {
        var setId = 100L
        val slots = plan.mapIndexed { index, planned ->
            slot(id = index + 1L, exerciseId = planned.exerciseId, position = index)
        }
        val sets = slots.zip(plan).flatMap { (slot, planned) ->
            planned.types.mapIndexed { position, type ->
                set(id = setId++, slotId = slot.id, position = position, type = type)
            }
        }
        return slots to sets
    }

    private fun workout(templateId: Long? = null, notes: String? = null) =
        WorkoutEntity(id = 1, name = "Workout", startedAt = 0, templateId = templateId, notes = notes)

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
