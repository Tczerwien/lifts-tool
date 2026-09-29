package com.tczerwien.lifts.settings

import com.tczerwien.lifts.data.BarEntity
import com.tczerwien.lifts.seed.Seeder
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnitSwitchTest {

    private val lb = WeightUnit.LB
    private val kg = WeightUnit.KG

    @Test
    fun `the seeded bar becomes 20 kg and comes back as 45 lb`() {
        val seeded = Seeder.defaultBar()
        val inKg = barAfterUnitSwitch(seeded, lb, kg)!!
        assertEquals(20.0, inKg.weightKg)
        assertEquals(Format.lbToKg(45.0), barAfterUnitSwitch(inKg, kg, lb)!!.weightKg)
        assertEquals(seeded.copy(weightKg = 20.0), inKg, "only the weight changes")
    }

    @Test
    fun `the old 20_4117 seed counts as the 45 lb bar`() {
        assertEquals(20.0, barAfterUnitSwitch(Seeder.defaultBar().copy(weightKg = 20.4117), lb, kg)?.weightKg)
    }

    @Test
    fun `a bar he renamed, weighed or added stays`() {
        assertNull(barAfterUnitSwitch(Seeder.defaultBar().copy(name = "Texas Power Bar"), lb, kg))
        assertNull(barAfterUnitSwitch(Seeder.defaultBar().copy(weightKg = Format.lbToKg(35.0)), lb, kg))
        assertNull(barAfterUnitSwitch(BarEntity(id = 2, name = "EZ Bar", weightKg = Format.lbToKg(25.0), position = 1), lb, kg))
        assertNull(barAfterUnitSwitch(Seeder.defaultBar(), lb, lb), "no switch, no change")
        assertNull(barAfterUnitSwitch(Seeder.defaultBar(), kg, lb), "a 45 lb bar while in kg is his 45 lb bar")
    }
}
