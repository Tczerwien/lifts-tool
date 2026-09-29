package com.tczerwien.lifts.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReorderTest {

    private data class Row(val id: String, val position: Int)

    private val rows = listOf(Row("a", 0), Row("b", 1), Row("c", 2))

    private fun move(items: List<Row>, index: Int, delta: Int) =
        reorderPositions(items, index, delta) { it.position }?.map { (row, position) -> row.id to position }

    @Test
    fun `a move swaps an item with its neighbour and writes only those two`() {
        assertEquals(listOf("b" to 0, "a" to 1), move(rows, index = 1, delta = -1))
        assertEquals(listOf("c" to 1, "b" to 2), move(rows, index = 1, delta = 1))
    }

    @Test
    fun `an item at the end, or gone, doesn't move`() {
        assertNull(move(rows, index = 0, delta = -1))
        assertNull(move(rows, index = 2, delta = 1))
        assertNull(move(rows, index = -1, delta = 1))
        assertNull(move(rows, index = 1, delta = 0))
    }

    @Test
    fun `gaps and repeats from older data are mended on the way`() {
        val gappy = listOf(Row("a", 0), Row("b", 4), Row("c", 4))
        assertEquals(listOf("c" to 1, "b" to 2), move(gappy, index = 1, delta = 1))
    }

    @Test
    fun `renumber closes the gap a removal leaves`() {
        val afterRemoval = listOf(Row("a", 0), Row("c", 2), Row("d", 3))
        assertEquals(listOf("c" to 1, "d" to 2), renumber(afterRemoval) { it.position }.map { (row, p) -> row.id to p })
        assertEquals(emptyList(), renumber(rows) { it.position })
    }
}
