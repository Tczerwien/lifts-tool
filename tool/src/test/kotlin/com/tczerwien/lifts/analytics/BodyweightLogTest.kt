package com.tczerwien.lifts.analytics

import com.tczerwien.lifts.data.BodyweightEntity
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class BodyweightLogTest {

    private val today = LocalDate.parse("2026-09-29").toEpochDay()

    private fun lb(day: Long, pounds: Double, id: Long = day) = BodyweightEntity(id = id, epochDay = day, weightKg = Format.lbToKg(pounds))

    @Test
    fun `the day steps back a year at most and never past today`() {
        assertEquals(today - 1, stepLogDay(today, -1, today))
        assertEquals(today, stepLogDay(today, +1, today), "no weigh-ins from the future")
        assertEquals(today - BACKDATE_MAX_DAYS, stepLogDay(today - BACKDATE_MAX_DAYS, -1, today))
        assertEquals(today - 3, stepLogDay(today - 8, +5, today))
    }

    @Test
    fun `the day reads as he would say it`() {
        assertEquals("Today", logDayLabel(today, today))
        assertEquals("Yesterday", logDayLabel(today - 1, today))
        assertEquals("Sun Sep 27", logDayLabel(today - 2, today))
    }

    @Test
    fun `a day from last year says so, in place of the weekday`() {
        val newYear = LocalDate.of(2027, 1, 2).toEpochDay()
        assertEquals("Dec 20, 2026", logDayLabel(LocalDate.of(2026, 12, 20).toEpochDay(), newYear))
        assertEquals("Fri Jan 1", logDayLabel(LocalDate.of(2027, 1, 1).toEpochDay(), newYear + 1))
    }

    @Test
    fun `a day with an entry shows it, so he sees what he is changing`() {
        val entries = listOf(lb(today - 3, 181.0), lb(today - 1, 182.4))
        assertEquals(181.0, bodyweightStepperValue(entries, today - 3, WeightUnit.LB, held = 183.0, fallback = 150.0))
    }

    @Test
    fun `a day without one keeps what the stepper holds, else the latest, else the start`() {
        val entries = listOf(lb(today - 3, 181.0), lb(today - 1, 182.4))
        assertEquals(183.0, bodyweightStepperValue(entries, today - 2, WeightUnit.LB, held = 183.0, fallback = 150.0))
        assertEquals(182.4, bodyweightStepperValue(entries, today, WeightUnit.LB, held = null, fallback = 150.0))
        assertEquals(150.0, bodyweightStepperValue(emptyList(), today, WeightUnit.LB, held = null, fallback = 150.0))
        // In kg the same entry reads on the kg grid.
        assertEquals(82.7, bodyweightStepperValue(entries, today - 1, WeightUnit.KG, held = null, fallback = 70.0))
    }

    @Test
    fun `a value held across a unit switch is converted, not reread`() {
        assertEquals(82.7, bodyweightInUnit(182.4, WeightUnit.LB, WeightUnit.KG))
        assertEquals(182.3, bodyweightInUnit(82.7, WeightUnit.KG, WeightUnit.LB))
        assertEquals(182.4, bodyweightInUnit(182.4, WeightUnit.LB, WeightUnit.LB))
    }
}
