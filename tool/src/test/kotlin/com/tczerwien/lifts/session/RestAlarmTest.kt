package com.tczerwien.lifts.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestAlarmTest {

    private val end = 1_000_000L

    // --- restAlertDue ---

    @Test
    fun `the alert is due once the countdown is seen reaching zero`() {
        assertFalse(restAlertDue(end, lastSeenRemainingSec = 1, nowMillis = end - 1, firedForEnd = null))
        assertTrue(restAlertDue(end, lastSeenRemainingSec = 1, nowMillis = end, firedForEnd = null))
    }

    @Test
    fun `an end that already alerted stays quiet`() {
        assertFalse(restAlertDue(end, lastSeenRemainingSec = 1, nowMillis = end + 10, firedForEnd = end))
        assertTrue(restAlertDue(end, lastSeenRemainingSec = 1, nowMillis = end + 10, firedForEnd = end - 60_000))
    }

    @Test
    fun `a screen shown after the grace window stays quiet`() {
        assertTrue(restAlertDue(end, lastSeenRemainingSec = 45, nowMillis = end + REST_ALERT_GRACE_MS, firedForEnd = null))
        assertFalse(restAlertDue(end, lastSeenRemainingSec = 45, nowMillis = end + REST_ALERT_GRACE_MS + 1, firedForEnd = null))
    }

    @Test
    fun `a rest never seen running stays quiet`() {
        assertFalse(restAlertDue(end, lastSeenRemainingSec = null, nowMillis = end + 100, firedForEnd = null))
        assertFalse(restAlertDue(end, lastSeenRemainingSec = 0, nowMillis = end + 100, firedForEnd = null))
    }

    // --- keepAwake and REST OVER ---

    @Test
    fun `the screen stays awake while resting and for 30 s after`() {
        assertFalse(keepAwake(null, end))
        assertTrue(keepAwake(end, end - 180_000))
        assertTrue(keepAwake(end, end + REST_OVER_WINDOW_MS - 1))
        assertFalse(keepAwake(end, end + REST_OVER_WINDOW_MS))
    }

    @Test
    fun `REST OVER shows from the end for 30 s`() {
        assertFalse(restOver(null, end))
        assertFalse(restOver(end, end - 1))
        assertTrue(restOver(end, end))
        assertTrue(restOver(end, end + REST_OVER_WINDOW_MS - 1))
        assertFalse(restOver(end, end + REST_OVER_WINDOW_MS))
    }

    @Test
    fun `checks land on the end, then once more when keep-awake lapses`() {
        assertEquals(REST_CHECK_MS, nextRestCheckMillis(end, end - 60_000))
        assertEquals(40L, nextRestCheckMillis(end, end - 40))
        assertEquals(REST_OVER_WINDOW_MS, nextRestCheckMillis(end, end))
        assertEquals(1L, nextRestCheckMillis(end, end + REST_OVER_WINDOW_MS - 1))
        assertNull(nextRestCheckMillis(end, end + REST_OVER_WINDOW_MS))
    }

    // --- The pulse ---

    @Test
    fun `the pulse inverts three times for 300 ms each, then ends`() {
        val start = 5_000L
        val phases = (0 until REST_PULSE_TOTAL_MS).map { pulseInverted(start, start + it) }
        assertTrue(phases.all { it != null })
        // Count the flashes: each switch into inverted starts one.
        val flashes = phases.zipWithNext().count { (before, after) -> before == false && after == true } + 1
        assertEquals(REST_PULSE_COUNT, flashes)
        assertEquals(true, pulseInverted(start, start + 299))
        assertEquals(false, pulseInverted(start, start + 300))
        assertEquals(true, pulseInverted(start, start + 600))
        assertNull(pulseInverted(start, start + REST_PULSE_TOTAL_MS))
        assertNull(pulseInverted(start, start - 1))
    }

    @Test
    fun `the pulse wakes on each phase boundary`() {
        assertEquals(300L, msToNextPulsePhase(5_000, 5_000))
        assertEquals(1L, msToNextPulsePhase(5_000, 5_299))
        assertEquals(300L, msToNextPulsePhase(5_000, 5_300))
    }

    // --- RestAlarm ---

    @Test
    fun `a rest alerts once, whichever screens look at it`() {
        val alarm = RestAlarm()
        // Active Workout sees it running, then the Rest Timer takes over and sees it end.
        assertFalse(alarm.check(end, end - 1_500))
        assertTrue(alarm.check(end, end + 20))
        assertEquals(end + 20, alarm.pulseStartedAt.value)
        // Back on Active Workout, a fresh frame looks again: no second beep, no second pulse.
        assertFalse(alarm.check(end, end + 400))
        assertEquals(end + 20, alarm.pulseStartedAt.value)
    }

    @Test
    fun `the next rest alerts again`() {
        val alarm = RestAlarm()
        alarm.check(end, end - 1_000)
        assertTrue(alarm.check(end, end))
        val next = end + 200_000
        assertFalse(alarm.check(next, next - 90_000))
        assertTrue(alarm.check(next, next + 3))
    }

    @Test
    fun `coming back long after the end is quiet`() {
        val alarm = RestAlarm()
        alarm.check(end, end - 45_000)
        assertFalse(alarm.check(end, end + 600_000))
        assertNull(alarm.pulseStartedAt.value)
    }

    @Test
    fun `a minus 30 that finishes the rest alerts`() {
        val alarm = RestAlarm()
        alarm.check(end, end - 20_000)
        // −30 with 20 s left clamps the end to now.
        val clamped = end - 20_000 + 50
        assertTrue(alarm.check(clamped, clamped + 30))
    }
}
