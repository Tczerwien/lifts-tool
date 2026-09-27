package com.tczerwien.lifts.screens.activeworkout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** When the set list moves to show the active row ([activeRowScrollTarget], [openingListIndex]). */
class SetListScrollTest {

    @Test
    fun `the list opens with the row above the active one at the top`() {
        assertEquals(2, openingListIndex(3))
        assertEquals(0, openingListIndex(1))
        assertEquals(0, openingListIndex(0))
        // No active row (every set ticked): the top.
        assertEquals(0, openingListIndex(-1))
    }

    @Test
    fun `a row already fully on screen never moves the list`() {
        assertNull(activeRowScrollTarget(activeIndex = 1, firstFullyVisible = 0, lastFullyVisible = 2))
        assertNull(activeRowScrollTarget(activeIndex = 2, firstFullyVisible = 0, lastFullyVisible = 2))
        assertNull(activeRowScrollTarget(activeIndex = 4, firstFullyVisible = 4, lastFullyVisible = 6))
    }

    @Test
    fun `a row below or above the view, or half hidden, scrolls in with one row of context`() {
        // Set 3 of 4 half hidden under the stepper strip after set 2 is ticked.
        assertEquals(2, activeRowScrollTarget(activeIndex = 3, firstFullyVisible = 0, lastFullyVisible = 2))
        assertEquals(4, activeRowScrollTarget(activeIndex = 5, firstFullyVisible = 0, lastFullyVisible = 2))
        assertEquals(0, activeRowScrollTarget(activeIndex = 1, firstFullyVisible = 3, lastFullyVisible = 5))
        // Not laid out yet.
        assertEquals(1, activeRowScrollTarget(activeIndex = 2, firstFullyVisible = null, lastFullyVisible = null))
    }

    @Test
    fun `no active row, no scroll`() {
        assertNull(activeRowScrollTarget(activeIndex = -1, firstFullyVisible = 0, lastFullyVisible = 2))
        assertNull(activeRowScrollTarget(activeIndex = -1, firstFullyVisible = null, lastFullyVisible = null))
    }
}
