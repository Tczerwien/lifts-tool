package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.TemplateExerciseEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class PreviousTest {

    private val w = SET_TYPE_WARMUP
    private val n = SET_TYPE_NORMAL
    private val f = SET_TYPE_FAILURE

    // --- Slot matching ---

    @Test
    fun `last session's sets split per slot in slot order`() {
        val previous = listOf(
            set(1, slot = 10, position = 0, weightKg = 85.0, reps = 5),
            set(2, slot = 11, position = 0, weightKg = 70.0, reps = 8),
            set(3, slot = 11, position = 1, weightKg = 70.0, reps = 8),
        )
        assertEquals(listOf(listOf(1L), listOf(2L, 3L)), previousGroups(previous, listOf(10, 11)).map { g -> g.map { it.id } })
    }

    @Test
    fun `a slot skipped last time keeps its place`() {
        // Last week: Bench top slot 10 skipped (nothing ticked), back-off slot 11 done.
        val previous = listOf(
            set(2, slot = 11, position = 0, weightKg = 70.0, reps = 8),
            set(3, slot = 11, position = 1, weightKg = 70.0, reps = 8),
        )
        val groups = mapOf("bench" to previousGroups(previous, slotIds = listOf(10, 11)))
        val bySlot = previousForSlots(listOf(slot(20, "bench", 0), slot(21, "bench", 1)), groups)
        assertEquals(emptyList(), bySlot.getValue(20), "the top slot has no PREVIOUS")
        assertEquals(listOf(2L, 3L), bySlot.getValue(21).map { it.id }, "the back-off slot still gets the back-off sets")
    }

    @Test
    fun `the second Bench slot is compared with last week's second Bench slot`() {
        val lastWeek = mapOf(
            "bench" to listOf(
                set(1, slot = 10, position = 0, weightKg = 85.0, reps = 5),
                set(2, slot = 11, position = 0, weightKg = 70.0, reps = 8),
                set(3, slot = 11, position = 1, weightKg = 70.0, reps = 8),
            ),
            "squat" to listOf(set(4, slot = 12, position = 0, weightKg = 100.0, reps = 5)),
        )
        val today = listOf(
            slot(20, "bench", 0),
            slot(21, "squat", 1),
            slot(22, "bench", 2),
            slot(23, "bench", 3),
        )
        val bySlot = previousForSlots(today, groups(lastWeek))
        assertEquals(listOf(1L), bySlot.getValue(20).map { it.id })
        assertEquals(listOf(4L), bySlot.getValue(21).map { it.id })
        assertEquals(listOf(2L, 3L), bySlot.getValue(22).map { it.id })
        assertEquals(emptyList(), bySlot.getValue(23), "no third Bench slot last week: no PREVIOUS")
    }

    // --- alignPrevious (the five cases from finding 072) ---

    @Test
    fun `work rows skip last time's warm-ups`() {
        val previous = listOf(prev(1, w), prev(2, w), prev(3, n), prev(4, n), prev(5, n))
        assertEquals(listOf(3L, 4L, 5L), alignPrevious(rows(n, n, n), previous).map { it?.id })
    }

    @Test
    fun `a warm-up with no warm-up last time has no PREVIOUS`() {
        val previous = listOf(prev(1, n), prev(2, n))
        assertEquals(listOf(null, 1L, 2L), alignPrevious(rows(w, n, n), previous).map { it?.id })
    }

    @Test
    fun `rows beyond last time's count have no PREVIOUS`() {
        val previous = listOf(prev(1, n), prev(2, n))
        assertEquals(listOf(1L, 2L, null, null), alignPrevious(rows(n, n, n, n), previous).map { it?.id })
    }

    @Test
    fun `failure and drop sets count as work on both sides`() {
        val previous = listOf(prev(1, w), prev(2, n), prev(3, f), prev(4, "drop"))
        assertEquals(listOf(1L, 2L, 3L, 4L), alignPrevious(rows(w, f, n, n), previous).map { it?.id })
    }

    @Test
    fun `warm-ups line up with warm-ups wherever they sit`() {
        val previous = listOf(prev(1, w), prev(2, w), prev(3, n), prev(4, n))
        // A row switched to warm-up mid-session still pairs by kind, not by position.
        assertEquals(listOf(1L, 3L, 2L, 4L), alignPrevious(rows(w, n, w, n), previous).map { it?.id })
    }

    // --- Starting values ---

    @Test
    fun `set 1 raised to 140, set 2 ticked untouched logs 140`() {
        val lb135 = Format.lbToKg(135.0)
        val lb140 = Format.lbToKg(140.0)
        val previous = List(3) { prev(it + 1L, n, weightKg = lb135, reps = 8) }
        val today = listOf(
            set(11, position = 0, weightKg = lb140, reps = 8, completedAt = 1L),
            set(12, position = 1),
            set(13, position = 2),
        )
        val seeds = seedRows(today, alignPrevious(today, previous), WeightUnit.LB)
        assertEquals(RowValues(lb140, 8), seeds[1])
        assertEquals(RowValues(lb140, 8), seeds[2], "the change carries down every set")
    }

    @Test
    fun `a set 1 done as last time leaves set 2 on its own PREVIOUS (a pyramid)`() {
        val previous = listOf(
            prev(1, n, weightKg = 60.0, reps = 10),
            prev(2, n, weightKg = 70.0, reps = 8),
            prev(3, n, weightKg = 80.0, reps = 6),
        )
        val today = listOf(set(11, position = 0, weightKg = 60.0, reps = 10, completedAt = 1L), set(12, position = 1), set(13, position = 2))
        val seeds = seedRows(today, alignPrevious(today, previous), WeightUnit.LB)
        assertEquals(RowValues(70.0, 8), seeds[1])
        assertEquals(RowValues(80.0, 6), seeds[2])
    }

    @Test
    fun `weight and reps are seeded separately`() {
        val previous = listOf(prev(1, n, weightKg = 60.0, reps = 8), prev(2, n, weightKg = 60.0, reps = 7))
        val today = listOf(set(11, position = 0, weightKg = 62.5, reps = 8, completedAt = 1L), set(12, position = 1))
        // Weight moved, so it carries; reps matched last time, so set 2 takes its own PREVIOUS (7).
        assertEquals(RowValues(62.5, 7), seedFor(today, 1, alignPrevious(today, previous), WeightUnit.KG))
    }

    @Test
    fun `with no history, set 1 carries forward`() {
        val today = listOf(set(11, position = 0, weightKg = 30.0, reps = 5, completedAt = 1L), set(12, position = 1))
        assertEquals(RowValues(30.0, 5), seedFor(today, 1, alignPrevious(today, emptyList()), WeightUnit.KG))
    }

    @Test
    fun `a warm-up never seeds a work set`() {
        val today = listOf(set(11, position = 0, type = w, weightKg = 40.0, reps = 10), set(12, position = 1))
        assertEquals(RowValues(null, null), seedFor(today, 1, alignPrevious(today, emptyList()), WeightUnit.KG))
    }

    @Test
    fun `a row's own value wins, field by field`() {
        val previous = listOf(prev(1, n, weightKg = 60.0, reps = 8))
        val today = listOf(set(11, position = 0, reps = 5))
        assertEquals(RowValues(60.0, 5), seedFor(today, 0, alignPrevious(today, previous), WeightUnit.KG))
    }

    @Test
    fun `a set 1 stepped up and back down again does not count as a change`() {
        // Last time was logged in kg (60 kg = 132.3 lb). In lb the steppers snap to 132.5, so +5 then
        // -5 stores 60.10 kg: the same on screen, and it must not carry as if set 1 were raised.
        val previous = listOf(prev(1, n, weightKg = 60.0, reps = 8), prev(2, n, weightKg = 60.0, reps = 8))
        val stepped = Format.displayToKg(Format.roundWeight(Format.kgToDisplay(60.0, WeightUnit.LB)), WeightUnit.LB)
        val today = listOf(set(11, position = 0, weightKg = stepped, reps = 8, completedAt = 1L), set(12, position = 1))
        assertEquals(60.0, seedFor(today, 1, alignPrevious(today, previous), WeightUnit.LB).weightKg)
    }

    @Test
    fun `a heavier top set does not drag its back-off sets up`() {
        val previous = listOf(
            prev(1, n, weightKg = 100.0, reps = 5),
            prev(2, n, weightKg = 85.0, reps = 8),
            prev(3, n, weightKg = 85.0, reps = 8),
        )
        val today = listOf(set(11, position = 0, weightKg = 102.5, reps = 5, completedAt = 1L), set(12, position = 1), set(13, position = 2))
        val seeds = seedRows(today, alignPrevious(today, previous), WeightUnit.KG)
        assertEquals(RowValues(85.0, 8), seeds[1])
        assertEquals(RowValues(85.0, 8), seeds[2])
    }

    @Test
    fun `adding weight to a bodyweight set 1 leaves a weighted set 2 on its own PREVIOUS`() {
        val previous = listOf(prev(1, n, weightKg = null, reps = 10), prev(2, n, weightKg = 10.0, reps = 6))
        val today = listOf(set(11, position = 0, weightKg = 2.5, reps = 10, completedAt = 1L), set(12, position = 1))
        assertEquals(RowValues(10.0, 6), seedFor(today, 1, alignPrevious(today, previous), WeightUnit.KG))
    }

    @Test
    fun `a heavier first warm-up leaves the rest of the ramp alone`() {
        val previous = listOf(prev(1, w, weightKg = 43.0, reps = 8), prev(2, w, weightKg = 61.0, reps = 5))
        val today = listOf(set(11, position = 0, type = w, weightKg = 45.0, reps = 8, completedAt = 1L), set(12, position = 1, type = w))
        assertEquals(RowValues(61.0, 5), seedFor(today, 1, alignPrevious(today, previous), WeightUnit.KG))
    }

    // --- New slots ---

    @Test
    fun `a template slot starts with last time's warm-ups, then its target work sets`() {
        val lastTime = listOf(prev(1, w), prev(2, w), prev(3, n), prev(4, n), prev(5, f))
        assertEquals(listOf(w, w, n, n, n, n), prefillTypes(lastTime, targetWorkSets = 4))
        assertEquals(listOf(n, n, n), prefillTypes(emptyList(), targetWorkSets = 3))
    }

    @Test
    fun `a hand-added exercise mirrors last time's pattern, or starts with three sets`() {
        assertEquals(listOf(w, n, n), prefillTypes(listOf(prev(1, w), prev(2, n), prev(3, f))))
        assertEquals(listOf(n, n, n), prefillTypes(emptyList()))
    }

    @Test
    fun `a template with Bench twice plans each slot from its own group`() {
        val template = listOf(
            TemplateExerciseEntity(id = 1, templateId = 7, exerciseId = "bench", position = 0, targetSets = 1),
            TemplateExerciseEntity(id = 2, templateId = 7, exerciseId = "bench", position = 1, targetSets = 3),
            TemplateExerciseEntity(id = 3, templateId = 7, exerciseId = "rdl", position = 2, targetSets = 3),
        )
        val lastTime = mapOf(
            "bench" to listOf(
                set(1, slot = 10, position = 0, type = w),
                set(2, slot = 10, position = 1),
                set(3, slot = 11, position = 0),
            ),
            "rdl" to listOf(set(4, slot = 12, position = 0, type = w), set(5, slot = 12, position = 1, type = w), set(6, slot = 12, position = 2)),
        )
        assertEquals(
            listOf(
                PlannedSlot("bench", listOf(w, n)),
                PlannedSlot("bench", listOf(n, n, n)),
                PlannedSlot("rdl", listOf(w, w, n, n, n)),
            ),
            planTemplate(template, groups(lastTime)),
        )
    }

    // --- Rest Timer's NEXT line ---

    @Test
    fun `set labels count work sets only`() {
        val today = rows(w, w, n, n, n)
        assertEquals("Warm-up 1 of 2", nextSetLabel(today, 0))
        assertEquals("Warm-up 2 of 2", nextSetLabel(today, 1))
        assertEquals("Set 1 of 3", nextSetLabel(today, 2))
        assertEquals("Set 3 of 3", nextSetLabel(today, 4))
    }

    @Test
    fun `NEXT is the exercise on screen while it has sets left`() {
        val slots = listOf(slot(1, "squat", 0), slot(2, "press", 1))
        val sets = listOf(
            set(11, slot = 1, position = 0, completedAt = 5L),
            set(12, slot = 1, position = 1),
            set(21, slot = 2, position = 0),
        )
        // The lifter moved on to Leg Press after squat set 1.
        val onScreen = restNextContext(slots, sets, currentSlotId = 2) as RestNext.Next
        assertEquals(2L, onScreen.slot.id)
        assertEquals(0, onScreen.index)
        // Without a slot, the last tick's exercise.
        val guessed = restNextContext(slots, sets, currentSlotId = null) as RestNext.Next
        assertEquals(12L, guessed.rows[guessed.index].id)
    }

    @Test
    fun `NEXT moves on when the exercise on screen is done, and says so at the end`() {
        val slots = listOf(slot(1, "squat", 0), slot(2, "press", 1))
        val squatDone = listOf(set(11, slot = 1, position = 0, completedAt = 5L), set(21, slot = 2, position = 0))
        val next = restNextContext(slots, squatDone, currentSlotId = 1) as RestNext.Next
        assertEquals(21L, next.rows[next.index].id)
        val allDone = squatDone.map { it.copy(completedAt = 9L) }
        assertEquals(RestNext.AllDone, restNextContext(slots, allDone, currentSlotId = 1))
        assertEquals(RestNext.NoExercises, restNextContext(emptyList(), emptyList(), currentSlotId = null))
        assertEquals(RestNext.NoSets, restNextContext(slots, emptyList(), currentSlotId = 1), "every row removed")
    }

    // --- Reopening ---

    @Test
    fun `a reopened workout lands on the exercise that was on screen while it has sets left`() {
        val slots = listOf(slot(1, "squat", 0), slot(2, "press", 1))
        val sets = listOf(
            set(11, slot = 1, position = 0, completedAt = 5L),
            set(12, slot = 1, position = 1),
            set(21, slot = 2, position = 0),
        )
        assertEquals(2L, openingSlotId(slots, sets, rememberedSlotId = 2), "moved on to Leg Press after squat set 1")
        assertEquals(1L, openingSlotId(slots, sets, rememberedSlotId = null), "nothing saved: the last tick's exercise")
        assertEquals(1L, openingSlotId(slots, sets, rememberedSlotId = 99), "saved slot removed since")
        val pressDone = sets.map { if (it.id == 21L) it.copy(completedAt = 9L) else it }
        assertEquals(1L, openingSlotId(slots, pressDone, rememberedSlotId = 2), "saved exercise finished")
        val allDone = sets.map { it.copy(completedAt = 9L) }
        assertEquals(1L, openingSlotId(slots, allDone, rememberedSlotId = 2), "everything ticked: the first")
        assertEquals(null, openingSlotId(emptyList(), emptyList(), rememberedSlotId = 2))
    }

    // --- Fixtures ---

    /** Last session's sets per exercise, grouped per slot in the order the slots first appear. */
    private fun groups(byExercise: Map<String, List<SetEntity>>): Map<String, List<List<SetEntity>>> =
        byExercise.mapValues { (_, sets) -> previousGroups(sets, sets.map { it.workoutExerciseId }.distinct()) }

    private fun rows(vararg types: String): List<SetEntity> =
        types.mapIndexed { index, type -> set(100L + index, position = index, type = type) }

    private fun prev(id: Long, type: String, weightKg: Double? = 50.0, reps: Int? = 5) =
        set(id, slot = 9, position = id.toInt(), type = type, weightKg = weightKg, reps = reps, completedAt = id)

    private fun set(
        id: Long,
        slot: Long = 1,
        position: Int = 0,
        type: String = SET_TYPE_NORMAL,
        weightKg: Double? = null,
        reps: Int? = null,
        completedAt: Long? = null,
    ) = SetEntity(
        id = id,
        workoutExerciseId = slot,
        position = position,
        type = type,
        weightKg = weightKg,
        reps = reps,
        completedAt = completedAt,
    )

    private fun slot(id: Long, exerciseId: String, position: Int) =
        WorkoutExerciseEntity(id = id, workoutId = 1, exerciseId = exerciseId, position = position)
}
