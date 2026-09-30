package com.tczerwien.lifts.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A tap right after a screen appears or switches mode is the bounce of the tap before ([tapTooSoonAfterSwitch]). */
class SwitchTapGuardTest {

    @Test
    fun `a tap in the first moments after a switch is dropped, a later one counts`() {
        val switched = 50_000L
        assertTrue(tapTooSoonAfterSwitch(switched, switched), "the same instant")
        assertTrue(tapTooSoonAfterSwitch(switched, switched + 150), "a double tap's second half")
        assertTrue(tapTooSoonAfterSwitch(switched, switched + SWITCH_TAP_GUARD_MS - 1))
        assertFalse(tapTooSoonAfterSwitch(switched, switched + SWITCH_TAP_GUARD_MS), "he has seen the new screen")
        assertFalse(tapTooSoonAfterSwitch(switched, switched - 10), "a touch already down before the switch")
    }
}
