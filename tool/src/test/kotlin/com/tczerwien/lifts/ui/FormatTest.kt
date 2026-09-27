package com.tczerwien.lifts.ui

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {

    private val utc = ZoneId.of("UTC")

    private fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

    // --- Conversion ---

    @Test
    fun `kg to lb round trips`() {
        assertEquals(100.0, Format.lbToKg(Format.kgToLb(100.0)), 1e-9)
        assertEquals(220.46226218, Format.kgToLb(100.0), 1e-6)
    }

    @Test
    fun `display conversion respects unit`() {
        assertEquals(100.0, Format.kgToDisplay(100.0, WeightUnit.KG), 1e-9)
        assertEquals(220.46226218, Format.kgToDisplay(100.0, WeightUnit.LB), 1e-6)
        assertEquals(100.0, Format.displayToKg(100.0, WeightUnit.KG), 1e-9)
        assertEquals(45.359237, Format.displayToKg(100.0, WeightUnit.LB), 1e-6)
    }

    // --- Rounding ---

    @Test
    fun `weights round to nearest half display unit`() {
        assertEquals(135.0, Format.roundWeight(135.2), 1e-9)
        assertEquals(135.5, Format.roundWeight(135.3), 1e-9)
        assertEquals(135.5, Format.roundWeight(135.65), 1e-9)
        assertEquals(136.0, Format.roundWeight(135.8), 1e-9)
    }

    @Test
    fun `bodyweight rounds to nearest tenth`() {
        assertEquals(180.4, Format.roundBodyweight(180.44), 1e-9)
        assertEquals(180.5, Format.roundBodyweight(180.46), 1e-9)
    }

    // --- Weight strings ---

    @Test
    fun `whole weights drop the decimal`() {
        // 61.235 kg -> 135.0 lb exactly at the .5 grid.
        assertEquals("135", Format.weight(Format.lbToKg(135.0), WeightUnit.LB))
        assertEquals("60", Format.weight(60.0, WeightUnit.KG))
    }

    @Test
    fun `half weights keep one decimal`() {
        assertEquals("62.5", Format.weight(62.5, WeightUnit.KG))
        assertEquals("2.5", Format.weight(Format.lbToKg(2.5), WeightUnit.LB))
    }

    @Test
    fun `bodyweight keeps one decimal step`() {
        assertEquals("180.4", Format.bodyweight(Format.lbToKg(180.42), WeightUnit.LB))
        assertEquals("82", Format.bodyweight(82.0, WeightUnit.KG))
        assertEquals("81.9", Format.bodyweight(81.93, WeightUnit.KG))
    }

    @Test
    fun `set summary combines weight and reps`() {
        assertEquals("135 × 8", Format.setSummary(Format.lbToKg(135.0), 8, WeightUnit.LB))
    }

    // --- Clocks and durations ---

    @Test
    fun `ago reads minutes and seconds under an hour, hours and minutes after`() {
        assertEquals("0:00", Format.ago(0))
        assertEquals("1:20", Format.ago(80))
        assertEquals("59:59", Format.ago(3599))
        assertEquals("1h 0m", Format.ago(3600))
        assertEquals("3h 58m", Format.ago(14_310))
    }

    @Test
    fun `clock formats minutes and padded seconds`() {
        assertEquals("0:00", Format.clock(0))
        assertEquals("1:35", Format.clock(95))
        assertEquals("3:00", Format.clock(180))
        assertEquals("0:00", Format.clock(-5))
    }

    @Test
    fun `durationHm formats hours and minutes`() {
        assertEquals("43m", Format.durationHm(43 * 60))
        assertEquals("1h 23m", Format.durationHm(3600 + 23 * 60))
        assertEquals("0m", Format.durationHm(30))
        assertEquals("2h 0m", Format.durationHm(2 * 3600))
    }

    @Test
    fun `elapsed switches to hours format at one hour`() {
        assertEquals("0:07", Format.elapsed(7_000))
        assertEquals("12:05", Format.elapsed((12 * 60 + 5) * 1000L))
        assertEquals("1:02:07", Format.elapsed((3600 + 2 * 60 + 7) * 1000L))
    }

    // --- Dates ---

    @Test
    fun `short date renders month and day`() {
        assertEquals("Jul 18", Format.dateShort(millis("2026-07-18T12:00:00Z"), utc))
        assertEquals("Jan 5", Format.dateShort(millis("2026-01-05T00:00:00Z"), utc))
    }

    @Test
    fun `date with year appends the year`() {
        assertEquals("Jul 18, 2026", Format.dateWithYear(millis("2026-07-18T12:00:00Z"), utc))
    }

    @Test
    fun `dates respect the zone`() {
        // 23:30 UTC on Jul 17 is already Jul 18 in UTC+2.
        val instant = millis("2026-07-17T23:30:00Z")
        assertEquals("Jul 17", Format.dateShort(instant, utc))
        assertEquals("Jul 18", Format.dateShort(instant, ZoneId.of("UTC+2")))
    }
}
