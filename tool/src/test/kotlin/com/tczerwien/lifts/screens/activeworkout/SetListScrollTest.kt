package com.tczerwien.lifts.screens.activeworkout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When the set list moves to show the active row ([activeRowScrollTarget], [openingListIndex], [scrollMovesRows]). */
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
    fun `a scroll that moves the rows drops the taps on its way, one onto the list already there doesn't`() {
        // Set 3 ticked with rows 0-3 in view: set 4 half hidden, row 3 goes to the top.
        assertTrue(scrollMovesRows(target = 3, firstVisibleIndex = 0, firstVisibleOffset = 0))
        // Back up to a row above the view.
        assertTrue(scrollMovesRows(target = 0, firstVisibleIndex = 2, firstVisibleOffset = 0))
        // The right row at the top, but scrolled part way into it.
        assertTrue(scrollMovesRows(target = 2, firstVisibleIndex = 2, firstVisibleOffset = 40))
        // A new exercise's list is built on its opening row: nothing moves.
        assertFalse(scrollMovesRows(target = 2, firstVisibleIndex = 2, firstVisibleOffset = 0))
        assertFalse(scrollMovesRows(target = null, firstVisibleIndex = 0, firstVisibleOffset = 0))
    }

    @Test
    fun `no active row, no scroll`() {
        assertNull(activeRowScrollTarget(activeIndex = -1, firstFullyVisible = 0, lastFullyVisible = 2))
        assertNull(activeRowScrollTarget(activeIndex = -1, firstFullyVisible = null, lastFullyVisible = null))
    }
}
