package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.TemplateExerciseEntity
import com.tczerwien.lifts.data.Tracking
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class PreviousTest {

    private val w = SET_TYPE_WARMUP
    private val n = SET_TYPE_NORMAL
    private val f = SET_TYPE_FAILURE
    private val d = SET_TYPE_DROP

    // --- Slot matching ---

    @Test
    fun `last session's sets split per slot in slot order`() {
        val previous = listOf(
            set(1, slot = 10, position = 0, weightKg = 85.0, reps = 5),
            set(2, slot = 11, position = 0, weightKg = 70.0, reps = 8),
            set(3, slot = 11, position = 1, weightKg = 70.0, reps = 8),
        )
        assertEquals(
            listOf(listOf(1L), listOf(2L, 3L)),
            previousGroups(previous, listOf(slot(11, "bench", 1), slot(10, "bench", 0)), "bench").map { g -> g.sets.map { it.id } },
        )
    }

    @Test
    fun `a slot skipped last time keeps its place`() {
        // Last week: Bench top slot 10 skipped (nothing ticked), back-off slot 11 done.
        val previous = listOf(
            set(2, slot = 11, position = 0, weightKg = 70.0, reps = 8),
            set(3, slot = 11, position = 1, weightKg = 70.0, reps = 8),
        )
        val groups = mapOf("bench" to previousGroups(previous, listOf(slot(10, "bench", 0), slot(11, "bench", 1)), "bench"))
        val bySlot = previousForSlots(listOf(slot(20, "bench", 0), slot(21, "bench", 1)), groups)
        assertEquals(emptyList(), bySlot.getValue(20), "the top slot has no PREVIOUS")
        assertEquals(listOf(2L, 3L), bySlot.getValue(21).map { it.id }, "the back-off slot still gets the back-off sets")
    }

    // --- Slot matching with alternatives (B15 review R1-1, A1) ---

    @Test
    fun `a slot swapped away keeps its place, so the other slot of that lift still reads its own sets`() {
        // Last week: bench top set (slot 10), bench back-offs (slot 11). Today the top set's slot does the press.
        val lastWeek = groups(mapOf("bench" to listOf(set(1, slot = 10, weightKg = 100.0), set(2, slot = 11, weightKg = 85.0))))
        val today = listOf(slot(20, "press", 0).copy(choices = listOf("bench", "press")), slot(21, "bench", 1))
        val bySlot = previousForSlots(today, lastWeek)
        assertEquals(listOf(2L), bySlot.getValue(21).map { it.id }, "the back-offs, not the top set")
        assertEquals(emptyList(), bySlot.getValue(20), "the press has no last session")

        // The next week, as planned: the top set wasn't done last time, as if skipped; the back-offs read theirs.
        val swappedWeek = listOf(slot(10, "press", 0).copy(choices = listOf("bench", "press")), slot(11, "bench", 1))
        val after = mapOf(
            "bench" to previousGroups(listOf(set(4, slot = 11, weightKg = 85.0)), swappedWeek, "bench"),
            "press" to previousGroups(listOf(set(3, slot = 10, weightKg = 30.0)), swappedWeek, "press"),
        )
        val nextWeek = previousForSlots(listOf(slot(30, "bench", 0), slot(31, "bench", 1)), after)
        assertEquals(emptyList(), nextWeek.getValue(30), "not the back-offs moved up")
        assertEquals(listOf(4L), nextWeek.getValue(31).map { it.id })
    }

    @Test
    fun `a slot swapped to a lift the workout has as its own never takes that slot's sets`() {
        // Today: slot 0 planned as the squat, swapped to the press; slot 1 the press as planned.
        val lastWeek = groups(mapOf("press" to listOf(set(1, slot = 11, weightKg = 60.0))))
        val today = listOf(slot(20, "press", 0).copy(choices = listOf("squat", "press")), slot(21, "press", 1))
        val bySlot = previousForSlots(today, lastWeek)
        assertEquals(listOf(1L), bySlot.getValue(21).map { it.id }, "the press as planned keeps its own")
        assertEquals(emptyList(), bySlot.getValue(20), "the swapped one takes nothing from it")

        // The next week, as planned: the press slot reads its own sets, not the swapped slot's.
        val swappedWeek = listOf(slot(10, "press", 0).copy(choices = listOf("squat", "press")), slot(11, "press", 1))
        val after = mapOf(
            "press" to previousGroups(listOf(set(2, slot = 10, weightKg = 40.0), set(3, slot = 11, weightKg = 62.5)), swappedWeek, "press"),
        )
        val nextWeek = previousForSlots(listOf(slot(30, "squat", 0), slot(31, "press", 1)), after)
        assertEquals(listOf(3L), nextWeek.getValue(31).map { it.id })
    }

    @Test
    fun `two slots swapped across last time, bench reads its sets from the other slot`() {
        // Last week slot 10, planned as bench, did the fly; slot 11, planned as the row, did bench.
        val lastWeek = listOf(slot(10, "fly", 0).copy(choices = listOf("bench", "fly")), slot(11, "bench", 1).copy(choices = listOf("row", "bench")))
        val groups = mapOf(
            "bench" to previousGroups(listOf(set(1, slot = 11, weightKg = 80.0)), lastWeek, "bench"),
            "fly" to previousGroups(listOf(set(2, slot = 10, weightKg = 14.0)), lastWeek, "fly"),
        )
        val today = listOf(slot(20, "bench", 0).copy(choices = listOf("bench", "fly")), slot(21, "row", 1).copy(choices = listOf("row", "bench")))
        assertEquals(listOf(1L), previousForSlots(today, groups).getValue(20).map { it.id }, "bench, done last week in the row's slot")
        // Only sets done as an alternative: never another slot's own (the back-offs, with no second slot today).
        val awayAndBackOffs = mapOf(
            "bench" to previousGroups(
                listOf(set(3, slot = 11, weightKg = 80.0)),
                listOf(slot(10, "fly", 0).copy(choices = listOf("bench", "fly")), slot(11, "bench", 1)),
                "bench",
            ),
        )
        assertEquals(emptyList(), previousForSlots(listOf(slot(20, "bench", 0)), awayAndBackOffs).getValue(20))
        // A slot only skipped last time stays empty, even with bench done in another slot as an alternative.
        val skippedWeek = listOf(slot(10, "bench", 0), slot(11, "bench", 1).copy(choices = listOf("row", "bench")))
        val skipped = mapOf("bench" to previousGroups(listOf(set(4, slot = 11, weightKg = 80.0)), skippedWeek, "bench"))
        assertEquals(emptyList(), previousForSlots(listOf(slot(20, "bench", 0)), skipped).getValue(20))
    }

    @Test
    fun `an alternative reads the lift's last session wherever it was done, and back`() {
        // Last time the press was the main of another workout: swapped in today, it reads that.
        val asMain = groups(mapOf("press" to listOf(set(1, slot = 11, weightKg = 60.0))))
        val swapped = slot(20, "press", 0).copy(choices = listOf("squat", "press"))
        assertEquals(listOf(1L), previousForSlots(listOf(swapped), asMain).getValue(20).map { it.id })
        // Last time it was swapped in elsewhere: done as the main today, it reads that.
        val asAlternative = mapOf(
            "press" to previousGroups(listOf(set(2, slot = 10, weightKg = 62.5)), listOf(slot(10, "press", 0).copy(choices = listOf("squat", "press"))), "press"),
        )
        assertEquals(listOf(2L), previousForSlots(listOf(slot(21, "press", 0)), asAlternative).getValue(21).map { it.id })
        // And swapped in again: like with like.
        assertEquals(listOf(2L), previousForSlots(listOf(swapped), asAlternative).getValue(20).map { it.id })
        // Its sets, not a slot of it skipped last time: the top set skipped, the back-offs done.
        val skippedFirst = mapOf(
            "press" to previousGroups(listOf(set(3, slot = 11, weightKg = 50.0)), listOf(slot(10, "press", 0), slot(11, "press", 1)), "press"),
        )
        assertEquals(listOf(3L), previousForSlots(listOf(swapped), skippedFirst).getValue(20).map { it.id })
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
    fun `failure sets count as work on both sides, and a drop lines up with last time's drop`() {
        // B3 counted a drop as work too, so last time's drop was today's set 3's PREVIOUS. A drop is part of
        // the set above it (B16, DR-4): set 3 has none, and today's drop gets last time's.
        val previous = listOf(prev(1, w), prev(2, n), prev(3, f), prev(4, d))
        assertEquals(listOf(1L, 2L, 3L, null), alignPrevious(rows(w, f, n, n), previous).map { it?.id })
        assertEquals(listOf(1L, 2L, 3L, 4L), alignPrevious(rows(w, n, f, d), previous).map { it?.id })
    }

    @Test
    fun `a drop added today doesn't shift the sets after it`() {
        // Last time: 3 sets, no drop. Today a drop under set 2: set 3 still reads last time's set 3.
        val previous = listOf(prev(1, n), prev(2, n), prev(3, n))
        assertEquals(listOf(1L, 2L, null, 3L), alignPrevious(rows(n, n, d, n), previous).map { it?.id })
        // Last time a drop under set 1, today none: set 2 reads set 2, not the drop.
        val withDrop = listOf(prev(1, n), prev(2, d), prev(3, n))
        assertEquals(listOf(1L, 3L), alignPrevious(rows(n, n), withDrop).map { it?.id })
        // The k-th drop with last time's k-th drop, wherever it sits.
        val twoDrops = listOf(prev(1, n), prev(2, d), prev(3, n), prev(4, d), prev(5, d))
        assertEquals(listOf(1L, 3L, 2L, 4L), alignPrevious(rows(n, n, d, d), twoDrops).map { it?.id })
    }

    @Test
    fun `a drop with no set above it lines up as a set of its own`() {
        // Last time's first row was a drop (made with the marker): it was that day's set 1.
        val previous = listOf(prev(1, d), prev(2, n))
        assertEquals(listOf(1L, 2L), alignPrevious(rows(n, n), previous).map { it?.id })
        assertEquals(listOf(null, 1L), alignPrevious(rows(w, d), previous).map { it?.id }, "under a warm-up today: work too")
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
        val stepped = Format.displayToKg(Format.roundWeight(Format.kgToDisplay(60.0, WeightUnit.LB), WeightUnit.LB), WeightUnit.LB)
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

    @Test
    fun `a hold's seconds carry like reps`() {
        val previous = listOf(hold(1, 45), hold(2, 45), hold(3, 30))
        val today = listOf(set(11, position = 0).copy(durationSec = 60, completedAt = 1L), set(12, position = 1), set(13, position = 2))
        val seeds = seedRows(today, alignPrevious(today, previous), WeightUnit.LB, Tracking.DURATION)
        assertEquals(RowValues(null, null, 60), seeds[1], "60 s on set 1 carries through the straight sets")
        assertEquals(RowValues(null, null, 30), seeds[2], "not onto last time's shorter third hold")
        assertEquals(RowValues(null, null, 45), seedFor(listOf(set(12, position = 0)), 0, listOf(previous[0]), WeightUnit.LB, Tracking.DURATION))
    }

    @Test
    fun `a row offers only what its exercise records`() {
        // Logged as weight x reps before its type changed: a reps-only row neither shows nor logs the weight.
        val previous = listOf(prev(1, n, weightKg = 10.0, reps = 12))
        val today = listOf(set(11, position = 0))
        val aligned = alignPrevious(today, previous)
        assertEquals(RowValues(null, 12), seedFor(today, 0, aligned, WeightUnit.KG, Tracking.REPS_ONLY))
        assertEquals(RowValues(10.0, 12), seedFor(today, 0, aligned, WeightUnit.KG, Tracking.WEIGHTED_BODYWEIGHT))
        assertEquals(RowValues(null, null), seedFor(today, 0, aligned, WeightUnit.KG, Tracking.DURATION))
        assertEquals(true, seedFor(today, 0, aligned, WeightUnit.KG, Tracking.DURATION).isEmpty)
        // Assistance (negative) carries like any weight.
        val assisted = listOf(prev(1, n, weightKg = -20.0, reps = 8), prev(2, n, weightKg = -20.0, reps = 8))
        val rows = listOf(set(11, position = 0, weightKg = -15.0, reps = 8, completedAt = 1L), set(12, position = 1))
        assertEquals(RowValues(-15.0, 8), seedFor(rows, 1, alignPrevious(rows, assisted), WeightUnit.KG, Tracking.ASSISTED_BODYWEIGHT))
        // Help logged as a plain weight before the exercise was set to Assisted starts as help, not added load.
        val before = listOf(prev(1, n, weightKg = 18.0, reps = 8))
        val row = listOf(set(11, position = 0))
        assertEquals(RowValues(-18.0, 8), seedFor(row, 0, alignPrevious(row, before), WeightUnit.KG, Tracking.ASSISTED_BODYWEIGHT))
    }

    // --- Starting values with drops (DR-2, DR-4) ---

    @Test
    fun `set 4 starts from set 3, not from the drop under it`() {
        // A new lift, no PREVIOUS: set 3 ticked at 100 × 8, a drop under it, then set 4.
        val today = listOf(
            set(11, position = 0, weightKg = 100.0, reps = 8, completedAt = 1L),
            set(12, position = 1, type = d),
            set(13, position = 2),
        )
        val seeds = seedRows(today, alignPrevious(today, emptyList()), WeightUnit.KG, weightStep = 2.5)
        assertEquals(RowValues(80.0, 8), seeds[1], "the drop: 20 % lighter, with the set's reps")
        assertEquals(RowValues(100.0, 8), seeds[2], "set 4 carries set 3, not the drop's 80")
    }

    @Test
    fun `a heavier set still carries past a drop to the next set`() {
        // Last time straight sets of 100 × 8; today set 1 raised to 105, with a drop under it.
        val previous = List(2) { prev(it + 1L, n, weightKg = 100.0, reps = 8) }
        val today = listOf(
            set(11, position = 0, weightKg = 105.0, reps = 8, completedAt = 1L),
            set(12, position = 1, type = d),
            set(13, position = 2),
        )
        assertEquals(RowValues(105.0, 8), seedRows(today, alignPrevious(today, previous), WeightUnit.KG)[2])
    }

    @Test
    fun `a drop starts at last time's drop, and each drop of a chain goes lighter again`() {
        val previous = listOf(prev(1, n, weightKg = 100.0, reps = 8), prev(2, d, weightKg = 70.0, reps = 6))
        val today = listOf(
            set(11, position = 0, weightKg = 105.0, reps = 8, completedAt = 1L),
            set(12, position = 1, type = d),
            set(13, position = 2, type = d),
        )
        val seeds = seedRows(today, alignPrevious(today, previous), WeightUnit.KG, weightStep = 2.5)
        assertEquals(RowValues(70.0, 6), seeds[1], "last time's drop, though set 1 is heavier")
        assertEquals(RowValues(55.0, 6), seeds[2], "56 snaps down to 55, with the drop above's reps")
        // Its own numbers win, and the next drop goes from them.
        val stepped = today.toMutableList().also { it[1] = it[1].copy(weightKg = 80.0) }
        val steppedSeeds = seedRows(stepped, alignPrevious(stepped, previous), WeightUnit.KG, weightStep = 2.5)
        assertEquals(RowValues(80.0, 6), steppedSeeds[1])
        assertEquals(RowValues(62.5, 6), steppedSeeds[2])
    }

    @Test
    fun `a drop under a swapped slot starts from the lift done, lined up by kind`() {
        // Today the slot planned as the cable raise does the dumbbell raise, which last time had a drop under set 1.
        // Today the drop comes under set 2: set 2 reads last time's set 2 (the third row), the drop its drop (R1-13).
        val lastWeek = mapOf(
            "db" to previousGroups(
                listOf(
                    set(1, slot = 10, weightKg = 10.0, reps = 12, completedAt = 1L),
                    set(2, slot = 10, position = 1, type = d, weightKg = 6.0, reps = 10, completedAt = 2L),
                    set(3, slot = 10, position = 2, weightKg = 10.0, reps = 11, completedAt = 3L),
                ),
                listOf(slot(10, "db", 0)),
                "db",
            ),
        )
        val today = listOf(slot(20, "db", 0).copy(choices = listOf("cable", "db")))
        val rows = listOf(
            set(21, slot = 20, weightKg = 10.0, reps = 12, completedAt = 4L),
            set(22, slot = 20, position = 1, weightKg = 10.0, reps = 11, completedAt = 5L),
            set(23, slot = 20, position = 2, type = d),
        )
        val aligned = alignPrevious(rows, previousForSlots(today, lastWeek).getValue(20))
        assertEquals(listOf(1L, 3L, 2L), aligned.map { it?.id }, "set 2 with set 2, the drop with the dumbbell raise's drop")
        assertEquals(RowValues(6.0, 10), seedRows(rows, aligned, WeightUnit.KG)[2])
    }

    // --- New slots ---

    @Test
    fun `a template slot starts with last time's warm-ups, then its target work sets`() {
        val lastTime = listOf(prev(1, w), prev(2, w), prev(3, n), prev(4, n), prev(5, f))
        assertEquals(listOf(w, w, n, n, n, n), prefillTypes(lastTime, targetWorkSets = 4))
        assertEquals(listOf(n, n, n), prefillTypes(emptyList(), targetWorkSets = 3))
    }

    @Test
    fun `a template's own warm-up count wins over last time's, and 0 means none`() {
        val lastTime = listOf(prev(1, w), prev(2, w), prev(3, n))
        assertEquals(listOf(w, n, n, n), prefillTypes(lastTime, targetWorkSets = 3, warmupSets = 1))
        assertEquals(listOf(n, n, n), prefillTypes(lastTime, targetWorkSets = 3, warmupSets = 0))
        assertEquals(listOf(w, w, w, n), prefillTypes(emptyList(), targetWorkSets = 1, warmupSets = 3))
        assertEquals(listOf(w, w, n), prefillTypes(lastTime, targetWorkSets = 1, warmupSets = null), "none: last session's")
    }

    @Test
    fun `a template slot's rep range, RIR and rests reach the plan`() {
        val template = listOf(
            TemplateExerciseEntity(
                id = 1, templateId = 7, exerciseId = "incline", position = 0, targetSets = 4, warmupSets = 2,
                repMin = 6, repMax = 12, targetRir = 1, restSecWork = 150, restSecWarmup = 60, targetRirMax = 2,
            ),
        )
        assertEquals(
            listOf(
                PlannedSlot(
                    "incline", listOf(w, w, n, n, n, n), 150, 60, targetRepMin = 6, targetRepMax = 12, targetRir = 1, targetRirMax = 2,
                    targetSets = 4,
                ),
            ),
            planTemplate(template, emptyMap()),
        )
    }

    @Test
    fun `a template exercise's alternatives reach the plan as its choices, main first`() {
        val template = listOf(
            TemplateExerciseEntity(id = 1, templateId = 7, exerciseId = "squat", position = 0, targetSets = 3, alternatives = listOf("press", "lunge")),
            TemplateExerciseEntity(id = 2, templateId = 7, exerciseId = "curl", position = 1, targetSets = 3),
        )
        assertEquals(listOf(listOf("squat", "press", "lunge"), emptyList()), planTemplate(template, emptyMap()).map { it.choices })
    }

    @Test
    fun `a template's supersets reach the plan, and a slot it holds apart starts plain`() {
        val groups = listOf(null, 3, 3, null, 3)
        val template = groups.mapIndexed { index, group ->
            TemplateExerciseEntity(
                id = index + 1L, templateId = 7, exerciseId = "ex$index", position = index, targetSets = 2, supersetGroup = group,
            )
        }
        assertEquals(listOf(null, 3, 3, null, null), planTemplate(template, emptyMap()).map { it.supersetGroup })
    }

    @Test
    fun `Start Again keeps the supersets of the slots it keeps, and a pair left with one slot is none`() {
        // A and B were done as a pair; of C and D only C was done (D only warmed up); E alone.
        val slots = listOf(1, 1, 2, 2, null).mapIndexed { index, group ->
            slot(index + 1L, "ex$index", index).copy(supersetGroup = group)
        }
        val sets = listOf(
            set(11, slot = 1, completedAt = 1L), set(21, slot = 2, completedAt = 2L), set(31, slot = 3, completedAt = 3L),
            set(41, slot = 4, type = w, completedAt = 4L), set(42, slot = 4, position = 1), set(51, slot = 5, completedAt = 5L),
        )
        val plan = planFromWorkout(slots, sets)
        assertEquals(listOf("ex0", "ex1", "ex2", "ex4"), plan.map { it.exerciseId })
        assertEquals(listOf(1, 1, null, null), plan.map { it.supersetGroup })
    }

    @Test
    fun `Start Again plans the exercise done, a swapped one too, with the slot's choices`() {
        val slots = listOf(slot(1, "press", 0).copy(choices = listOf("squat", "press")), slot(2, "curl", 1))
        val sets = listOf(set(11, slot = 1, completedAt = 1L), set(21, slot = 2, completedAt = 2L))
        val plan = planFromWorkout(slots, sets)
        assertEquals(listOf("press", "curl"), plan.map { it.exerciseId })
        assertEquals(listOf(listOf("squat", "press"), emptyList()), plan.map { it.choices })
    }

    @Test
    fun `Start Again plans the source slot's work sets, else the work sets it copies`() {
        // A deload's slot: 4 planned, 2 done (and a warm-up); a slot with no plan (added by hand): 3 done.
        val slots = listOf(slot(1, "squat", 0).copy(targetSets = 4), slot(2, "curl", 1))
        val sets = listOf(
            set(11, slot = 1, type = w, completedAt = 1L), set(12, slot = 1, position = 1, completedAt = 2L),
            set(13, slot = 1, position = 2, completedAt = 3L),
            set(21, slot = 2, completedAt = 4L), set(22, slot = 2, position = 1, type = f, completedAt = 5L),
            set(23, slot = 2, position = 2, completedAt = 6L), set(24, slot = 2, position = 3),
        )
        val plan = planFromWorkout(slots, sets)
        assertEquals(listOf(listOf(w, n, n), listOf(n, n, n)), plan.map { it.types })
        assertEquals(listOf(4, 3), plan.map { it.targetSets }, "the full plan kept; the ticked work sets otherwise")
    }

    @Test
    fun `a hand-added exercise mirrors last time's pattern, or starts with three sets`() {
        assertEquals(listOf(w, n, n), prefillTypes(listOf(prev(1, w), prev(2, n), prev(3, f))))
        assertEquals(listOf(n, n, n), prefillTypes(emptyList()))
    }

    @Test
    fun `a drop is never a planned row`() {
        // Last time 3 sets and a drop under the last: added by hand, 3 sets, not 4 (DR-5).
        assertEquals(listOf(w, n, n, n), prefillTypes(listOf(prev(1, w), prev(2, n), prev(3, n), prev(4, n), prev(5, d))))
        assertEquals(listOf(w, n), prefillTypes(listOf(prev(1, w), prev(2, d))), "one with no set above it was a set")
        // Start Again: 4 sets, one with a drop, plan 4 sets.
        val slots = listOf(WorkoutExerciseEntity(id = 1, workoutId = 1, exerciseId = "fly", position = 0))
        val sets = listOf(
            set(11, position = 0, completedAt = 1L), set(12, position = 1, completedAt = 2L), set(13, position = 2, completedAt = 3L),
            set(14, position = 3, type = d, completedAt = 4L), set(15, position = 4, completedAt = 5L),
        )
        val plan = planFromWorkout(slots, sets).single()
        assertEquals(listOf(n, n, n, n) to 4, plan.types to plan.targetSets)
        // As logged, as PREVIOUS reads it: a drop whose set was left unticked is a set of its own (review R1-1, A4).
        val skipped = listOf(set(21, position = 0, completedAt = 1L), set(22, position = 1), set(23, position = 2, type = d, completedAt = 2L))
        assertEquals(listOf(n), planFromWorkout(slots, skipped).single().types, "set 1 and its drop: one set")
        val dropOnly = listOf(set(31, position = 0), set(32, position = 1, type = d, completedAt = 1L))
        assertEquals(listOf(n), planFromWorkout(slots, dropOnly).single().types, "only the drop done: kept, as one set")
        assertEquals(listOf(n), prefillTypes(dropOnly.filter { it.completedAt != null }), "as a hand-added exercise plans it")
        assertEquals(dropOnly[1], alignPrevious(rows(n), dropOnly.filter { it.completedAt != null }).single(), "and as PREVIOUS reads it")
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
                PlannedSlot("bench", listOf(w, n), targetSets = 1),
                PlannedSlot("bench", listOf(n, n, n), targetSets = 3),
                PlannedSlot("rdl", listOf(w, w, n, n, n), targetSets = 3),
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
    fun `a drop doesn't count as a set in the labels, and is named by its set`() {
        // The Rest Timer after a drop's tick says set 4 of 4, not 5 of 5 (DR-4).
        val today = rows(n, n, n, d, n)
        assertEquals("Set 4 of 4", nextSetLabel(today, 4))
        assertEquals("Set 3 drop", nextSetLabel(today, 3))
        assertEquals("Set 1 of 2", nextSetLabel(rows(w, d, n), 1), "a drop under a warm-up is a set of its own")
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
    fun `in a superset, a set with a drop still to do keeps NEXT on its drop, not the partner`() {
        // Fly and row as a pair; fly's set 1 ticked with a drop under it to come (DR-6).
        val slots = listOf(slot(1, "fly", 0).copy(supersetGroup = 1), slot(2, "row", 1).copy(supersetGroup = 1))
        val sets = listOf(
            set(11, slot = 1, position = 0, completedAt = 5L),
            set(12, slot = 1, position = 1, type = d),
            set(13, slot = 1, position = 2),
            set(21, slot = 2, position = 0),
        )
        val next = restNextContext(slots, sets, currentSlotId = 1) as RestNext.Next
        assertEquals(12L, next.rows[next.index].id)
        assertEquals(1L, openingSlotId(slots, sets, rememberedSlotId = null), "reopened on the fly, its drop to do")
        // The drop ticked: the turn is the row's.
        val dropped = sets.map { if (it.id == 12L) it.copy(completedAt = 6L) else it }
        assertEquals(21L, (restNextContext(slots, dropped, currentSlotId = 1) as RestNext.Next).let { it.rows[it.index].id })
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

    @Test
    fun `a reopened superset lands where its turn went when the round closed, or the exercise left on is finished`() {
        // Squat, then Fly and Row as a superset.
        val slots = listOf(
            slot(1, "squat", 0),
            slot(2, "fly", 1).copy(supersetGroup = 1),
            slot(3, "row", 2).copy(supersetGroup = 1),
        )
        val afterB1 = listOf(
            set(11, slot = 1, completedAt = 1L),
            set(21, slot = 2, completedAt = 5L), set(22, slot = 2, position = 1), set(23, slot = 2, position = 2),
            set(31, slot = 3, completedAt = 6L), set(32, slot = 3, position = 1), set(33, slot = 3, position = 2),
        )
        assertEquals(2L, openingSlotId(slots, afterB1, rememberedSlotId = 3), "left during the round's rest: back on Fly")
        assertEquals(2L, openingSlotId(slots, afterB1, rememberedSlotId = null), "nothing saved: the turn from the last tick")
        val afterA2 = afterB1.map { if (it.id == 22L) it.copy(completedAt = 7L) else it }
        // Fly's tick handed the turn on to Row: Fly as remembered, whether he left in the 2 s or went back to
        // Fly on purpose after the move (the two look the same; the NEXT row names Row either way).
        assertEquals(2L, openingSlotId(slots, afterA2, rememberedSlotId = 2), "back on Fly, or left in the 2 s: Fly stands")
        assertEquals(3L, openingSlotId(slots, afterA2, rememberedSlotId = 3), "the move had happened: Row, as remembered")
        assertEquals(2L, openingSlotId(slots, afterA2, rememberedSlotId = null), "nothing saved: the last tick's Fly, which has sets left")
        // Three in turn, the middle one finished but for a skipped warm-up: Fly's last set hands the turn past it.
        val three = slots + slot(4, "curl", 3).copy(supersetGroup = 1)
        val middleDone = listOf(
            set(21, slot = 2, completedAt = 5L), set(22, slot = 2, position = 1, completedAt = 9L),
            set(30, slot = 3, type = SET_TYPE_WARMUP), set(31, slot = 3, position = 1, completedAt = 6L),
            set(32, slot = 3, position = 2, completedAt = 8L),
            set(41, slot = 4, completedAt = 7L), set(42, slot = 4, position = 1),
        )
        assertEquals(4L, openingSlotId(three, middleDone, rememberedSlotId = 2), "Fly finished: on to Curl, not the warmed-down Row")
        assertEquals(4L, openingSlotId(three, middleDone, rememberedSlotId = null))
        // A warm-up tick hands nothing on: the exercise that was on screen.
        val warmedUp = listOf(
            set(20, slot = 2, type = SET_TYPE_WARMUP, completedAt = 5L), set(21, slot = 2, position = 1), set(31, slot = 3),
        )
        assertEquals(2L, openingSlotId(slots, warmedUp, rememberedSlotId = 2))
    }

    // --- Fixtures ---

    /** Last session's sets per exercise, grouped per slot in the order the slots first appear. */
    /** Last session's groups from its sets alone: one slot per slot id, in order, each doing its exercise as planned. */
    private fun groups(byExercise: Map<String, List<SetEntity>>): Map<String, List<LastSlot>> =
        byExercise.mapValues { (exerciseId, sets) ->
            previousGroups(sets, sets.map { it.workoutExerciseId }.distinct().mapIndexed { index, id -> slot(id, exerciseId, index) }, exerciseId)
        }

    private fun rows(vararg types: String): List<SetEntity> =
        types.mapIndexed { index, type -> set(100L + index, position = index, type = type) }

    private fun prev(id: Long, type: String, weightKg: Double? = 50.0, reps: Int? = 5) =
        set(id, slot = 9, position = id.toInt(), type = type, weightKg = weightKg, reps = reps, completedAt = id)

    private fun hold(id: Long, seconds: Int) =
        set(id, slot = 9, position = id.toInt(), completedAt = id).copy(durationSec = seconds)

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
