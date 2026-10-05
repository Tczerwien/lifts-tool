package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
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
}
