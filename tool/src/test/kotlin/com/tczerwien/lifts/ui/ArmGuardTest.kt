package com.tczerwien.lifts.ui

import com.tczerwien.lifts.ui.ArmGuard.Tap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArmGuardTest {

    private var now = 10_000L
    private val guard = ArmGuard(clock = { now }, timeoutMillis = 3_000, minConfirmMillis = 400)

    @Test
    fun `first tap arms and a second tap in the window confirms`() {
        assertEquals(Tap.ARMED, guard.tap())
        assertTrue(guard.isArmed())
        now += 1_000
        assertEquals(Tap.CONFIRMED, guard.tap())
        assertFalse(guard.isArmed())
    }

    @Test
    fun `a double-tap never confirms`() {
        assertEquals(Tap.ARMED, guard.tap())
        now += 120
        assertEquals(Tap.IGNORED, guard.tap())
        now += 200
        assertEquals(Tap.IGNORED, guard.tap(), "still inside 400 ms of the first tap")
        now += 80
        assertEquals(Tap.CONFIRMED, guard.tap(), "a deliberate second tap still works")
    }

    @Test
    fun `right after the screen switches under his finger, a bounce doesn't arm the trash there`() {
        guard.quiet()
        now += 120
        assertEquals(Tap.IGNORED, guard.tap(), "the second half of the double tap that confirmed the last screen")
        assertFalse(guard.isArmed())
        now += 300
        assertEquals(Tap.ARMED, guard.tap(), "a deliberate tap arms as usual")
        now += 1_000
        assertEquals(Tap.CONFIRMED, guard.tap())
    }

    @Test
    fun `a tap after the window arms again instead of confirming`() {
        guard.tap()
        now += 3_001
        assertFalse(guard.isArmed())
        assertEquals(Tap.ARMED, guard.tap())
        now += 500
        assertEquals(Tap.CONFIRMED, guard.tap())
    }

    @Test
    fun `the window edges are inclusive`() {
        guard.tap()
        now += 400
        assertEquals(Tap.CONFIRMED, guard.tap())
        guard.tap()
        now += 3_000
        assertEquals(Tap.CONFIRMED, guard.tap())
    }

    @Test
    fun `after a confirm or a disarm the next tap only arms`() {
        guard.tap()
        now += 500
        guard.tap()
        now += 500
        assertEquals(Tap.ARMED, guard.tap())
        guard.disarm()
        now += 500
        assertEquals(Tap.ARMED, guard.tap())
    }

    @Test
    fun `a clock that jumps backwards re-arms`() {
        guard.tap()
        now -= 1_000
        assertEquals(Tap.ARMED, guard.tap())
    }
}
