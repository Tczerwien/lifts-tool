package com.tczerwien.lifts.domain

import com.tczerwien.lifts.ui.Format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlateMathTest {

    private val lbPlatesKg = listOf(45.0, 25.0, 10.0, 5.0, 2.5).map(Format::lbToKg)
    private val olympicBarKg = Format.lbToKg(45.0)

    private fun assertPerSide(expectedLb: List<Pair<Double, Int>>, loadout: PlateMath.Loadout) {
        assertEquals(expectedLb.size, loadout.perSide.size)
        expectedLb.zip(loadout.perSide).forEach { (expected, actual) ->
            assertEquals(Format.lbToKg(expected.first), actual.plateKg, 1e-9)
            assertEquals(expected.second, actual.count)
        }
    }

    // --- Exact fits ---

    @Test
    fun `exact fit breaks down greedily per side`() {
        // 295 lb on a 45 lb bar = 125 lb per side = 45x2 + 25 + 10.
        val loadout = PlateMath.loadout(Format.lbToKg(295.0), olympicBarKg, lbPlatesKg)

        assertNotNull(loadout)
        assertTrue(loadout.exact)
        assertPerSide(listOf(45.0 to 2, 25.0 to 1, 10.0 to 1), loadout)
        assertEquals(Format.lbToKg(295.0), loadout.achievedKg, 1e-9)
    }

    @Test
    fun `kg native inventory fits exactly`() {
        // 107.5 kg on a 20 kg bar = 43.75 kg per side = 20x2 + 2.5 + 1.25.
        val loadout = PlateMath.loadout(107.5, 20.0, listOf(20.0, 10.0, 5.0, 2.5, 1.25))

        assertNotNull(loadout)
        assertTrue(loadout.exact)
        assertEquals(listOf(20.0 to 2, 2.5 to 1, 1.25 to 1), loadout.perSide.map { it.plateKg to it.count })
        assertEquals(107.5, loadout.achievedKg, 1e-9)
    }

    @Test
    fun `target equal to bar is an exact empty loadout`() {
        val loadout = PlateMath.loadout(olympicBarKg, olympicBarKg, lbPlatesKg)

        assertNotNull(loadout)
        assertTrue(loadout.exact)
        assertTrue(loadout.perSide.isEmpty())
        assertEquals(olympicBarKg, loadout.achievedKg, 1e-9)
    }

    // --- Below bar / unreachable ---

    @Test
    fun `target below bar returns null`() {
        assertNull(PlateMath.loadout(Format.lbToKg(40.0), olympicBarKg, lbPlatesKg))
        assertNull(PlateMath.loadout(0.0, olympicBarKg, lbPlatesKg))
    }

    @Test
    fun `unreachable target returns nearest achievable below it`() {
        // 297 lb needs 126 lb per side; greedy reaches 125 -> nearest is 295 lb.
        val loadout = PlateMath.loadout(Format.lbToKg(297.0), olympicBarKg, lbPlatesKg)

        assertNotNull(loadout)
        assertFalse(loadout.exact)
        assertPerSide(listOf(45.0 to 2, 25.0 to 1, 10.0 to 1), loadout)
        assertEquals(Format.lbToKg(295.0), loadout.achievedKg, 1e-9)
    }

    @Test
    fun `empty inventory above bar weight is inexact bar only`() {
        val loadout = PlateMath.loadout(100.0, 20.0, emptyList())

        assertNotNull(loadout)
        assertFalse(loadout.exact)
        assertTrue(loadout.perSide.isEmpty())
        assertEquals(20.0, loadout.achievedKg, 1e-9)
    }

    // --- Limits and defensive input ---

    @Test
    fun `plates per size are capped at the limit`() {
        // 315 lb on a 45 lb bar wants 135 lb per side = 45x3; cap at 2 -> 45x2 = 225 lb total.
        val loadout = PlateMath.loadout(
            targetKg = Format.lbToKg(315.0),
            barKg = olympicBarKg,
            inventoryKg = listOf(Format.lbToKg(45.0)),
            platesPerSizeLimit = 2,
        )

        assertNotNull(loadout)
        assertFalse(loadout.exact)
        assertPerSide(listOf(45.0 to 2), loadout)
        assertEquals(Format.lbToKg(225.0), loadout.achievedKg, 1e-9)
    }

    @Test
    fun `zero limit loads nothing`() {
        val loadout = PlateMath.loadout(100.0, 20.0, listOf(20.0, 10.0), platesPerSizeLimit = 0)

        assertNotNull(loadout)
        assertTrue(loadout.perSide.isEmpty())
        assertFalse(loadout.exact)
    }

    @Test
    fun `inventory is deduplicated sorted and filtered`() {
        // Unsorted with duplicates and junk sizes; 60 kg on 20 kg bar = 20 per side = 10x2.
        val loadout = PlateMath.loadout(60.0, 20.0, listOf(5.0, 10.0, -25.0, 10.0, 0.0, 5.0))

        assertNotNull(loadout)
        assertTrue(loadout.exact)
        assertEquals(listOf(10.0 to 2), loadout.perSide.map { it.plateKg to it.count })
    }

    @Test
    fun `greedy walk is the contract even when a global search would fit`() {
        // 12 kg on a zero bar = 6 per side: greedy takes 4 and strands 2 (3+3 would fit).
        val loadout = PlateMath.loadout(12.0, 0.0, listOf(4.0, 3.0))

        assertNotNull(loadout)
        assertFalse(loadout.exact)
        assertEquals(listOf(4.0 to 1), loadout.perSide.map { it.plateKg to it.count })
        assertEquals(8.0, loadout.achievedKg, 1e-9)
    }
}
