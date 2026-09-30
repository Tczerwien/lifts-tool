package com.tczerwien.lifts.ui

import java.time.Instant
import java.time.LocalDate
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
    fun `lb weights round to the nearest half pound`() {
        assertEquals(135.0, Format.roundWeight(135.2, WeightUnit.LB), 1e-9)
        assertEquals(135.5, Format.roundWeight(135.3, WeightUnit.LB), 1e-9)
        assertEquals(135.5, Format.roundWeight(135.65, WeightUnit.LB), 1e-9)
        assertEquals(136.0, Format.roundWeight(135.8, WeightUnit.LB), 1e-9)
    }

    @Test
    fun `kg weights round to a quarter, so a 1_25 kg step stays exact`() {
        assertEquals(21.25, Format.roundWeight(21.3, WeightUnit.KG), 1e-9)
        assertEquals(21.25, Format.roundWeight(Format.stepWeight(20.0, +1, 1.25), WeightUnit.KG), 1e-9)
        assertEquals("21.25", Format.weight(21.25, WeightUnit.KG))
        assertEquals("102.75", Format.weight(102.75, WeightUnit.KG))
        assertEquals("21.5", Format.weight(21.5, WeightUnit.KG))
        assertEquals("21", Format.weight(21.0, WeightUnit.KG))
        // In lb a 1.25 kg step's 21.25 kg is 46.85 lb, which shows on the half-pound grid.
        assertEquals("47", Format.weight(21.25, WeightUnit.LB))
        assertEquals("-30", Format.weight(Format.lbToKg(-30.0), WeightUnit.LB))
    }

    @Test
    fun `every offered step lands on its unit's grid`() {
        for (unit in WeightUnit.entries) {
            assertEquals(unit.weightSteps.first(), unit.defaultWeightStep)
            for (step in unit.weightSteps) {
                var display = 0.0
                repeat(40) {
                    display = Format.stepWeight(display, +1, step)
                    assertEquals(display, Format.roundWeight(display, unit), 1e-9, "$unit $step")
                }
            }
        }
        assertEquals(listOf(5.0, 2.5), WeightUnit.LB.weightSteps)
        assertEquals(listOf(2.5, 1.25, 1.0), WeightUnit.KG.weightSteps)
    }

    @Test
    fun `counts take the word in the number they need`() {
        assertEquals("1 set", Format.count(1, "set"))
        assertEquals("0 sets", Format.count(0, "set"))
        assertEquals("12 sets", Format.count(12, "set"))
        assertEquals("2 reps", Format.count(2, "rep"))
    }

    @Test
    fun `Weight Step cycles through what the unit offers`() {
        assertEquals(2.5, WeightUnit.LB.nextWeightStep(5.0))
        assertEquals(5.0, WeightUnit.LB.nextWeightStep(2.5))
        assertEquals(listOf(1.25, 1.0, 2.5), listOf(2.5, 1.25, 1.0).map(WeightUnit.KG::nextWeightStep))
        assertEquals(2.5, WeightUnit.KG.nextWeightStep(7.0), "anything else starts the cycle over")
    }

    @Test
    fun `bodyweight rounds to nearest tenth`() {
        assertEquals(180.4, Format.roundBodyweight(180.44), 1e-9)
        assertEquals(180.5, Format.roundBodyweight(180.46), 1e-9)
    }

    // --- Stepping ---

    @Test
    fun `a weight on the grid steps one step each way`() {
        assertEquals(140.0, Format.stepWeight(135.0, +1, 5.0), 1e-9)
        assertEquals(130.0, Format.stepWeight(135.0, -1, 5.0), 1e-9)
        assertEquals(22.5, Format.stepWeight(20.0, +1, 2.5), 1e-9)
        assertEquals(17.5, Format.stepWeight(20.0, -1, 2.5), 1e-9)
    }

    @Test
    fun `an off-grid weight lands on the grid with its first step`() {
        // The seeded Olympic bar in kg shows 20.5.
        assertEquals(22.5, Format.stepWeight(20.5, +1, 2.5), 1e-9)
        assertEquals(20.0, Format.stepWeight(20.5, -1, 2.5), 1e-9)
        assertEquals(135.0, Format.stepWeight(132.5, +1, 5.0), 1e-9)
        assertEquals(130.0, Format.stepWeight(132.5, -1, 5.0), 1e-9)
    }

    @Test
    fun `60 kg shown in lb never steps back to a weight that stores a sliver off 60 kg`() {
        val shown = Format.roundWeight(Format.kgToDisplay(60.0, WeightUnit.LB), WeightUnit.LB)
        assertEquals(132.5, shown, 1e-9)
        val up = Format.stepWeight(shown, +1, WeightUnit.LB.defaultWeightStep)
        val upThenDown = Format.stepWeight(up, -1, WeightUnit.LB.defaultWeightStep)
        assertEquals(130.0, upThenDown, 1e-9)
        // 130 lb is a whole plate step lighter, not 60.10 kg passing for a new best.
        assertEquals(58.967, Format.displayToKg(upThenDown, WeightUnit.LB), 1e-3)
    }

    @Test
    fun `several steps at once move that many grid steps`() {
        assertEquals(65.0, Format.stepWeight(40.0, +5, 5.0), 1e-9)
        assertEquals(20.0, Format.stepWeight(42.0, -5, 5.0), 1e-9)
        assertEquals(151.0, Format.stepWeight(150.0, +5, 0.2), 1e-9)
    }

    @Test
    fun `stepping never goes below zero`() {
        assertEquals(0.0, Format.stepWeight(3.0, -1, 5.0), 1e-9)
        assertEquals(0.0, Format.stepWeight(0.0, -1, 5.0), 1e-9)
        assertEquals(0.0, Format.stepWeight(10.0, -5, 5.0), 1e-9)
    }

    @Test
    fun `bodyweight steps stay on the tenth grid despite floating point`() {
        // 150 / 0.2 is 749.999… in floating point; it is still on the grid.
        assertEquals(150.2, Format.stepWeight(150.0, +1, 0.2), 1e-9)
        assertEquals(149.8, Format.stepWeight(150.0, -1, 0.2), 1e-9)
        assertEquals(80.1, Format.stepWeight(80.0, +1, 0.1), 1e-9)
        assertEquals(180.6, Format.stepWeight(180.5, +1, 0.2), 1e-9)
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

    @Test
    fun `file sizes read in B, whole KB, then MB`() {
        assertEquals("0 B", Format.fileSize(0))
        assertEquals("1023 B", Format.fileSize(1023))
        assertEquals("1 KB", Format.fileSize(1024))
        assertEquals("48 KB", Format.fileSize(48 * 1024 + 300))
        assertEquals("1000 KB", Format.fileSize(1000L * 1024))
        assertEquals("1.0 MB", Format.fileSize(1024L * 1024))
        assertEquals("1.4 MB", Format.fileSize((1.4 * 1024 * 1024).toLong()))
    }

    // --- Dates ---

    /** Dates this year leave the year off; tests pin "today" so they don't change on New Year's Day. */
    private val today = LocalDate.of(2026, 9, 29)

    @Test
    fun `short date renders month and day`() {
        assertEquals("Jul 18", Format.dateShort(millis("2026-07-18T12:00:00Z"), utc, today))
        assertEquals("Jan 5", Format.dateShort(millis("2026-01-05T00:00:00Z"), utc, today))
    }

    @Test
    fun `a date from another year says which`() {
        assertEquals("Sep 22, 2025", Format.dateShort(millis("2025-09-22T12:00:00Z"), utc, today))
        assertEquals("Dec 31, 2025", Format.dateShort(millis("2025-12-31T23:30:00Z"), utc, today))
        assertEquals("Jan 1, 2027", Format.dateShort(millis("2027-01-01T08:00:00Z"), utc, today), "a clock set wrong")
        // The zone decides the year too: 23:30 UTC on Dec 31 is already January 1 in UTC+2.
        assertEquals("Jan 1", Format.dateShort(millis("2025-12-31T23:30:00Z"), ZoneId.of("UTC+2"), LocalDate.of(2026, 1, 1)))
        assertEquals("Sep 22, 2025", Format.dayShort(LocalDate.of(2025, 9, 22).toEpochDay(), today))
        assertEquals("Sep 22", Format.dayShort(LocalDate.of(2026, 9, 22).toEpochDay(), today))
    }

    @Test
    fun `dateTime adds a 24-hour clock in the given zone`() {
        assertEquals("Jul 18 14:05", Format.dateTime(millis("2026-07-18T14:05:59Z"), utc, today))
        assertEquals("Jan 5 00:00", Format.dateTime(millis("2026-01-05T00:00:00Z"), utc, today))
        assertEquals("Jul 19 01:30", Format.dateTime(millis("2026-07-18T23:30:00Z"), ZoneId.of("UTC+2"), today))
        assertEquals("Sep 22, 2025 14:05", Format.dateTime(millis("2025-09-22T14:05:00Z"), utc, today), "last year's monthly backup")
    }

    @Test
    fun `durations round to the nearest minute, never below 0`() {
        assertEquals(6, Format.roundedMinutes(5 * 60_000L + 40_000L), "5:40 is 6 min, as History says")
        assertEquals(5, Format.roundedMinutes(5 * 60_000L + 29_000L))
        assertEquals(0, Format.roundedMinutes(-60_000L), "a clock that went back")
        assertEquals("62 min", Format.durationMin(62 * 60_000L + 10_000L))
    }

    @Test
    fun `date with year appends the year`() {
        assertEquals("Jul 18, 2026", Format.dateWithYear(millis("2026-07-18T12:00:00Z"), utc))
    }

    @Test
    fun `dates respect the zone`() {
        // 23:30 UTC on Jul 17 is already Jul 18 in UTC+2.
        val instant = millis("2026-07-17T23:30:00Z")
        assertEquals("Jul 17", Format.dateShort(instant, utc, today))
        assertEquals("Jul 18", Format.dateShort(instant, ZoneId.of("UTC+2"), today))
    }
}
