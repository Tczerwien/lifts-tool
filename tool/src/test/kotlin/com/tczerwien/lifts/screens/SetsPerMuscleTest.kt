package com.tczerwien.lifts.screens

import com.tczerwien.lifts.analytics.MuscleRow
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Sets per Muscle's words: the week line and a row's count. */
class SetsPerMuscleTest {

    private val today = LocalDate.parse("2026-10-01")

    @Test
    fun `the week line names the week, its Monday, and a deload`() {
        assertEquals("THIS WEEK · FROM MON SEP 28", weekLine(0, LocalDate.parse("2026-09-28"), today, deload = false))
        assertEquals("LAST WEEK · FROM MON SEP 21 · DELOAD", weekLine(1, LocalDate.parse("2026-09-21"), today, deload = true))
        // Eleven back crosses into last year once the year is young: the year shows, as dates do everywhere.
        assertEquals(
            "11 WEEKS AGO · FROM MON OCT 20, 2025",
            weekLine(11, LocalDate.parse("2025-10-20"), LocalDate.parse("2026-01-05"), deload = false),
        )
    }

    @Test
    fun `an empty week says this week or that week`() {
        assertEquals("No sets this week", emptyWeekText(0))
        assertEquals("No sets that week", emptyWeekText(3))
    }

    @Test
    fun `a row reads sets against its target, or sets alone`() {
        assertEquals("9 / 12", setsText(MuscleRow("chest", 9, 12)))
        assertEquals("0 / 6", setsText(MuscleRow("calves", 0, 6)))
        assertEquals("2", setsText(MuscleRow("forearms", 2, null)))
    }
}
