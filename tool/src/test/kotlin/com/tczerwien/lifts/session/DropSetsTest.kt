package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.Tracking
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Drop sets: which set a drop belongs to, and how the rows are numbered. */
class DropSetsTest {

    private val w = SET_TYPE_WARMUP
    private val n = SET_TYPE_NORMAL
    private val f = SET_TYPE_FAILURE
    private val d = SET_TYPE_DROP

    /** One slot's rows in order, ids 1, 2, …; a capital letter ("N", "D") is a ticked set of that type. */
    private fun rows(vararg types: String): List<SetEntity> = types.mapIndexed { index, type ->
        val ticked = mapOf("W" to w, "N" to n, "F" to f, "D" to d)[type]
        SetEntity(
            id = index + 1L, workoutExerciseId = 1, position = index, type = ticked ?: type,
            completedAt = if (ticked != null) 1_000L + index else null,
        )
    }

    // --- DR-1: which set a drop belongs to ---

    @Test
    fun `a drop belongs to the work set above it, a chain of drops to the same one`() {
        val slot = rows(w, n, f, d, d, n)
        assertEquals(listOf(RowKind.WARMUP, RowKind.WORK, RowKind.WORK, RowKind.DROP, RowKind.DROP, RowKind.WORK), rowKinds(slot))
        assertEquals(2, dropParent(slot, 3), "under the set to failure")
        assertEquals(2, dropParent(slot, 4), "the second drop of the chain too")
        assertNull(dropParent(slot, 5), "a work set belongs to none")
        assertNull(dropParent(slot, 0))
    }

    @Test
    fun `a drop with no work set above it is a set of its own`() {
        // The marker can make one on the first row or under a warm-up: nothing to belong to.
        assertEquals(listOf(RowKind.WORK, RowKind.DROP, RowKind.WORK), rowKinds(rows(d, d, n)))
        assertEquals(listOf(RowKind.WORK, RowKind.WARMUP, RowKind.WORK), rowKinds(rows(n, w, d)), "a warm-up between")
        assertNull(dropParent(rows(w, d), 1))
        assertEquals(0, dropParent(rows(d, d), 1), "the next drop belongs to it, as to any set")
    }

    // --- DR-1: the markers ---

    @Test
    fun `a drop is numbered with its set, and the set after it keeps its number`() {
        assertEquals(listOf("W", "1", "2", "3", "3", "4"), setMarkers(rows(w, n, n, n, d, n)))
        assertEquals(listOf("1", "1", "1", "2"), setMarkers(rows(n, d, d, n)), "a chain")
        assertEquals(listOf("1", "2", "2"), setMarkers(rows(d, n, d)), "a drop on the first row is set 1")
        assertEquals(listOf("W", "1", "2", "2"), setMarkers(rows("W", "N", "N", "D")), "ticked or not")
    }

    // --- DR-6: no rest before a drop ---

    @Test
    fun `a drop follows a set when the next row of its slot is a drop still to do`() {
        val slot = rows("N", d, n, "N", "D", n)
        assertEquals(listOf(true, false, false, false, false, false), slot.map { dropFollows(slot, it.id) })
        // Rows of other slots, out of order, don't count: by position within its own slot.
        val other = SetEntity(id = 99, workoutExerciseId = 2, position = 1, type = d)
        assertEquals(false, dropFollows(listOf(other) + slot.reversed(), 4))
        assertEquals(true, dropFollows(listOf(other) + slot.reversed(), 1))
        assertEquals(false, dropFollows(slot, 404), "not there")
    }

    // --- DR-4: done/total ---

    @Test
    fun `done of total counts work sets only`() {
        assertEquals(3 to 3, workSetProgress(rows("W", "N", "N", "N", "D")), "a drop after the last set leaves it 3/3")
        assertEquals(1 to 3, workSetProgress(rows(w, "N", d, n, n)), "warm-ups and an unticked drop out")
        assertEquals(1 to 2, workSetProgress(rows("D", n)), "a drop with no set above it is one")
        assertEquals(0 to 0, workSetProgress(emptyList()))
    }

    // --- DR-2: where a drop starts ---

    private val lb = WeightUnit.LB

    /** [kg] as the lb column shows it. */
    private fun shownLb(kg: Double?): Double? = kg?.let { Format.roundWeight(Format.kgToDisplay(it, lb), lb) }

    private fun above(lbs: Double?, reps: Int? = 10) = RowValues(lbs?.let(Format::lbToKg), reps)

    @Test
    fun `20 percent lighter, snapped down to the step, with the set's reps`() {
        val start = dropStart(Tracking.WEIGHT_REPS, above(100.0), lastDrop = null, lb, step = 5.0)
        assertEquals(80.0, shownLb(start.weightKg))
        assertEquals(10, start.reps)
        assertEquals(10.0, shownLb(dropStart(Tracking.WEIGHT_REPS, above(15.0), null, lb, 5.0).weightKg), "12 snaps down to 10")
        assertEquals(7.5, shownLb(dropStart(Tracking.WEIGHT_REPS, above(10.0), null, lb, 2.5).weightKg), "on a 2.5 lb step")
        assertEquals(80.0, shownLb(dropStart(Tracking.WEIGHT_REPS, above(102.5), null, lb, 5.0).weightKg), "off the grid above")
        assertEquals(0.0, shownLb(dropStart(Tracking.WEIGHT_REPS, above(5.0), null, lb, 5.0).weightKg), "one step: down to none")
        // In kg: 60 → 48 → 47.5 on a 2.5 kg step.
        assertEquals(47.5, dropStart(Tracking.WEIGHT_REPS, RowValues(60.0, 8), null, WeightUnit.KG, 2.5).weightKg)
    }

    @Test
    fun `last time's drop wins, number by number`() {
        val lastDrop = SetEntity(id = 9, workoutExerciseId = 2, position = 3, type = d, weightKg = Format.lbToKg(70.0), reps = 6, completedAt = 1L)
        val start = dropStart(Tracking.WEIGHT_REPS, above(100.0), lastDrop, lb, 5.0)
        assertEquals(70.0 to 6, shownLb(start.weightKg) to start.reps)
        // Only its weight logged last time: the reps come from the set above.
        assertEquals(10, dropStart(Tracking.WEIGHT_REPS, above(100.0), lastDrop.copy(reps = null), lb, 5.0).reps)
        assertEquals(80.0, shownLb(dropStart(Tracking.WEIGHT_REPS, above(100.0), lastDrop.copy(weightKg = null), lb, 5.0).weightKg))
    }

    @Test
    fun `bodyweight lifts drop to no load added, assisted ones get more help`() {
        assertEquals(20.0, shownLb(dropStart(Tracking.WEIGHTED_BODYWEIGHT, above(25.0), null, lb, 5.0).weightKg))
        assertEquals(0.0, shownLb(dropStart(Tracking.WEIGHTED_BODYWEIGHT, above(null), null, lb, 5.0).weightKg), "bodyweight alone stays")
        // Help is stored negative: 40 lb of help → 50.
        assertEquals(-50.0, shownLb(dropStart(Tracking.ASSISTED_BODYWEIGHT, above(-40.0), null, lb, 5.0).weightKg))
        assertEquals(-5.0, shownLb(dropStart(Tracking.ASSISTED_BODYWEIGHT, above(null), null, lb, 5.0).weightKg), "none: one step")
        // A help logged before the type was set (a plain +40) still means 40 of help.
        assertEquals(-50.0, shownLb(dropStart(Tracking.ASSISTED_BODYWEIGHT, above(40.0), null, lb, 5.0).weightKg))
    }

    @Test
    fun `nothing to drop from, or nothing the lift records`() {
        assertNull(dropStart(Tracking.WEIGHT_REPS, above(null), null, lb, 5.0).weightKg)
        val reps = dropStart(Tracking.REPS_ONLY, above(100.0), null, lb, 5.0)
        assertEquals(RowValues(null, 10), reps)
    }
}
