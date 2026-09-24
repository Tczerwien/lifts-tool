package com.tczerwien.lifts.settings

import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlateListTest {

    @Test
    fun commaIsAlwaysASeparator() {
        // Decided on purpose: "2,5" is two plates, not two and a half.
        assertEquals(listOf(5.0, 2.0), PlateList.parse("2,5", WeightUnit.KG))
    }

    @Test
    fun formatThenParseKeepsExactPlates() {
        val kg = listOf(25.0, 20.0, 1.25, 0.5)
        val text = PlateList.format(kg, WeightUnit.KG)
        assertEquals("25, 20, 1.25, 0.5", text)
        assertEquals(kg, PlateList.parse(text, WeightUnit.KG))
    }

    @Test
    fun defaultLbPlatesRoundTripWithoutDrift() {
        val text = PlateList.format(SettingsStore.DEFAULT_PLATE_INVENTORY_KG, WeightUnit.LB)
        assertEquals("45, 25, 10, 5, 2.5", text)
        val parsed = PlateList.parse(text, WeightUnit.LB)!!
        parsed.zip(SettingsStore.DEFAULT_PLATE_INVENTORY_KG).forEach { (a, b) -> assertEquals(b, a, 1e-9) }
    }

    @Test
    fun junkIsDropped() {
        assertEquals(listOf(Format.lbToKg(45.0)), PlateList.parse("45 abc -5 0 Infinity NaN 45", WeightUnit.LB))
        assertNull(PlateList.parse("none", WeightUnit.LB))
    }
}
