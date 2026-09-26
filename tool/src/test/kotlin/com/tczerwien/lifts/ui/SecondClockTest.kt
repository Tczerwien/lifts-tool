package com.tczerwien.lifts.ui

import com.tczerwien.lifts.session.restRemainingSec
import kotlin.test.Test
import kotlin.test.assertEquals

class SecondClockTest {

    @Test
    fun `ticks land just past each second counted from the anchor`() {
        assertEquals(1_005L, msUntilNextSecond(anchor = 10_000, now = 10_000))
        assertEquals(505L, msUntilNextSecond(anchor = 10_000, now = 12_500))
        assertEquals(6L, msUntilNextSecond(anchor = 10_000, now = 12_999))
        // Before the anchor (a rest's end is in the future) the boundaries still line up with it.
        assertEquals(705L, msUntilNextSecond(anchor = 10_000, now = 9_300))
    }

    @Test
    fun `a rest countdown drawn on those ticks shows each second, none skipped`() {
        val end = 1_000_000L + 437
        var now = end - 5_000 - 600
        val shown = mutableListOf<Int>()
        var zeroAt: Long? = null
        while (zeroAt == null) {
            val remaining = restRemainingSec(end, now)
            shown += remaining
            if (remaining == 0) zeroAt = now else now += msUntilNextSecond(end, now)
        }
        assertEquals(listOf(6, 5, 4, 3, 2, 1, 0), shown)
        // 0:00 shows a few milliseconds after the end, not up to a second late.
        assertEquals(end + 5, zeroAt)
    }

    @Test
    fun `an elapsed clock drawn on those ticks shows each second, none skipped`() {
        val start = 2_000_000L + 811
        var now = start + 59_100
        val shown = mutableListOf<String>()
        repeat(4) {
            shown += Format.elapsed(now - start)
            now += msUntilNextSecond(start, now)
        }
        assertEquals(listOf("0:59", "1:00", "1:01", "1:02"), shown)
    }
}
