package com.tczerwien.lifts.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToneTest {

    private val rate = 48_000
    private val tone = rate * BEEP_TONE_MS / 1_000
    private val gap = rate * BEEP_GAP_MS / 1_000

    @Test
    fun `two 150 ms tones with 100 ms of silence between them`() {
        val pcm = beepPcm(rate)
        assertEquals(BEEP_COUNT * tone + (BEEP_COUNT - 1) * gap, pcm.size)
        assertTrue(pcm.copyOfRange(tone, tone + gap).all { it == 0.toShort() })
        assertTrue(pcm.copyOfRange(0, tone).any { it != 0.toShort() })
        assertTrue(pcm.copyOfRange(tone + gap, pcm.size).any { it != 0.toShort() })
    }

    @Test
    fun `each tone starts and ends at silence, so it doesn't click`() {
        val pcm = beepPcm(rate)
        for (start in listOf(0, tone + gap)) {
            assertEquals(0, pcm[start].toInt())
            assertEquals(0, pcm[start + tone - 1].toInt())
            // Within the first millisecond the envelope keeps it well under the peak.
            assertTrue(pcm.copyOfRange(start, start + rate / 1_000).all { abs(it.toInt()) < Short.MAX_VALUE * 0.2 })
        }
    }

    @Test
    fun `the peak sits just under the chosen level, never clipping`() {
        val peak = beepPcm(rate).maxOf { abs(it.toInt()) }
        val target = BEEP_AMPLITUDE * Short.MAX_VALUE
        assertTrue(peak <= target, "peak $peak above $target")
        assertTrue(peak >= target * 0.95, "peak $peak well under $target")
    }

    @Test
    fun `works at the phone's usual rates and refuses one too low for the pitch`() {
        assertEquals(2 * 44_100 * BEEP_TONE_MS / 1_000 + 44_100 * BEEP_GAP_MS / 1_000, beepPcm(44_100).size)
        assertFailsWith<IllegalArgumentException> { beepPcm(3_000) }
    }
}
