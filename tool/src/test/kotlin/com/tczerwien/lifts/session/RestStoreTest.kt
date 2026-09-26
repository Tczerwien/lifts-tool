package com.tczerwien.lifts.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RestStoreTest {

    private val now = 1_000_000L

    // --- openingRestEnd ---

    @Test
    fun `a running rest is resumed, from the strip or the alarm icon`() {
        assertEquals(now + 42_000, openingRestEnd(now + 42_000, now, resumeOnly = true))
        assertEquals(now + 42_000, openingRestEnd(now + 42_000, now, resumeOnly = false))
    }

    @Test
    fun `a tap on the strip just as the rest ends shows REST OVER, not a new rest`() {
        assertEquals(now - 300, openingRestEnd(now - 300, now, resumeOnly = true))
        assertEquals(now, openingRestEnd(now, now, resumeOnly = true))
        // Cleared on the way (Finish in flight): still nothing new.
        assertEquals(now, openingRestEnd(null, now, resumeOnly = true))
    }

    @Test
    fun `the alarm icon starts a fresh rest when none is running`() {
        assertNull(openingRestEnd(null, now, resumeOnly = false))
        assertNull(openingRestEnd(now - 60_000, now, resumeOnly = false))
    }
}
