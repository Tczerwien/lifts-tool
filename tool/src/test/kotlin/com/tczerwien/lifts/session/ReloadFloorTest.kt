package com.tczerwien.lifts.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReloadFloorTest {

    @Test
    fun `a reload that read before the save landed keeps what the screen shows`() {
        val floor = ReloadFloor()
        val before = floor.reloadStarted()
        assertFalse(floor.keepsShown(before), "no save: the reload shows what it read")
        floor.saveQueued()
        val during = floor.reloadStarted()
        assertTrue(floor.keepsShown(before), "a save pending: the note just typed stands")
        assertTrue(floor.keepsShown(during))
        floor.saveLanded()
        assertTrue(floor.keepsShown(before), "started before it landed: may hold the old note")
        assertTrue(floor.keepsShown(during))
        val after = floor.reloadStarted()
        assertFalse(floor.keepsShown(after), "started after it landed: reads the saved note")
    }

    @Test
    fun `a save that lands without being queued first sets the floor too`() {
        // Workout Detail's notes show once the save lands, so only the landing counts.
        val floor = ReloadFloor()
        val old = floor.reloadStarted()
        floor.saveLanded()
        assertTrue(floor.keepsShown(old))
        assertFalse(floor.keepsShown(floor.reloadStarted()))
    }

    @Test
    fun `with two saves the note stands until both have landed`() {
        val floor = ReloadFloor()
        floor.saveQueued()
        floor.saveQueued()
        floor.saveLanded()
        assertTrue(floor.keepsShown(floor.reloadStarted()), "one still pending")
        floor.saveLanded()
        assertFalse(floor.keepsShown(floor.reloadStarted()))
    }
}
