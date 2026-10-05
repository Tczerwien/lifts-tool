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

    // --- Naming and removing a row ---

    @Test
    fun `rows are named as their markers number them`() {
        val slot = rows(w, n, n, d, n)
        assertEquals(listOf("Warm-up", "Set 1", "Set 2", "Set 2 drop", "Set 3"), slot.indices.map { rowLabel(slot, it) })
    }

    @Test
    fun `removing a set takes its drops, never a ticked one`() {
        val slot = rows(n, n, d, d, n)
        assertEquals(listOf(2L, 3L, 4L), setRemoval(slot, 1), "set 2 with its two drops")
        assertEquals(listOf(3L), setRemoval(slot, 2), "a drop alone; the next one then belongs to set 2")
        assertEquals(listOf(5L), setRemoval(slot, 4))
        assertNull(setRemoval(rows("N", d), 0), "a logged set stays")
        assertNull(setRemoval(rows(n, "D"), 0), "a set with a logged drop stays")
        assertEquals(listOf(1L), setRemoval(rows(w, d), 0), "a warm-up alone")
        assertNull(setRemoval(slot, 9))
    }

    // --- DR-3: where "… › Drop Set" puts it ---

    @Test
    fun `a drop goes under the latest tick, a work set or a drop`() {
        val slot = rows(w, n, n, n)
        fun ticked(vararg at: Pair<Int, Long>) = slot.mapIndexed { i, set -> at.firstOrNull { it.first == i }?.let { set.copy(completedAt = it.second) } ?: set }
        assertEquals(DropPlace.After(3), dropPlace(ticked(1 to 10L, 2 to 20L, 3 to 30L)), "under set 3")
        assertEquals(DropPlace.After(1), dropPlace(ticked(1 to 40L, 2 to 20L, 3 to 30L)), "set 1 ticked again last: under it")
        assertEquals(DropPlace.After(3), dropPlace(rows("N", "N", "D", "D")), "a chain grows under its last drop")
        assertNull(dropPlace(rows("W", n, n)), "only a warm-up ticked")
        assertNull(dropPlace(ticked(0 to 50L, 1 to 10L)), "the latest tick a warm-up")
        assertNull(dropPlace(slot), "nothing ticked")
    }

    @Test
    fun `a drop still to do under the set is the one Drop Set goes to, and a new one goes after those done`() {
        // Made ahead with the marker under set 2, then set 2 ticked: no second drop above it (review R1-2, A1).
        assertEquals(DropPlace.Waiting(2), dropPlace(rows("N", "N", d, n)))
        assertEquals(1, DropPlace.Waiting(2).after, "named after set 2")
        // Set 2 ticked again after its drop was logged: the next drop goes below that one, in the order done.
        val retick = rows("N", "N", "D", n).mapIndexed { i, set -> if (i == 1) set.copy(completedAt = 9_000L) else set }
        assertEquals(DropPlace.After(2), dropPlace(retick))
        // A chain with its second drop still to do: that one.
        assertEquals(DropPlace.Waiting(3), dropPlace(rows("N", "N", "D", d, n)))
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
    fun `last time's drop only while it is lighter than today's set`() {
        // Last time's drop was 70 under a heavier set; today the set is 65, or 70: 20 % under it instead (review A2).
        val lastDrop = SetEntity(id = 9, workoutExerciseId = 2, position = 3, type = d, weightKg = Format.lbToKg(70.0), reps = 6, completedAt = 1L)
        assertEquals(50.0 to 6, dropStart(Tracking.WEIGHT_REPS, above(65.0), lastDrop, lb, 5.0).let { shownLb(it.weightKg) to it.reps })
        assertEquals(55.0, shownLb(dropStart(Tracking.WEIGHT_REPS, above(70.0), lastDrop, lb, 5.0).weightKg), "as heavy: no drop either")
        // Assisted: last time's help is used only while it is more help than today's set has.
        val help = lastDrop.copy(weightKg = Format.lbToKg(-50.0))
        assertEquals(-50.0, shownLb(dropStart(Tracking.ASSISTED_BODYWEIGHT, above(-40.0), help, lb, 5.0).weightKg))
        assertEquals(-75.0, shownLb(dropStart(Tracking.ASSISTED_BODYWEIGHT, above(-60.0), help, lb, 5.0).weightKg), "60 ÷ 0.8")
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
