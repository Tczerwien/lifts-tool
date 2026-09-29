package com.tczerwien.lifts.session

import com.tczerwien.lifts.ui.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NumberEntryTest {

    private val lb = WeightUnit.LB
    private val kg = WeightUnit.KG

    private fun value(display: Double) = TypedWeight.Value(display)

    @Test
    fun `a typed weight reads as the number on the dumbbell`() {
        assertEquals(value(17.5), parseTypedWeight("17.5", lb))
        assertEquals(value(118.0), parseTypedWeight(" 118 ", lb), "the sled")
        assertEquals(value(45.0), parseTypedWeight("45lb", lb))
        assertEquals(value(45.0), parseTypedWeight("45 LBS", lb))
        assertEquals(value(20.0), parseTypedWeight("20 kg", kg))
        assertEquals(value(0.5), parseTypedWeight(".5", lb))
        assertEquals(value(22.5), parseTypedWeight("22,5", kg), "a decimal comma")
    }

    @Test
    fun `it rounds to the unit's grid`() {
        assertEquals(value(17.5), parseTypedWeight("17.4", lb))
        assertEquals(value(21.25), parseTypedWeight("21.3", kg))
        assertEquals(value(21.25), parseTypedWeight("21.25", kg))
    }

    @Test
    fun `a sign is left to the exercise`() {
        assertEquals(value(30.0), parseTypedWeight("-30", lb))
        assertEquals(value(45.0), parseTypedWeight("+45", lb))
        assertEquals(value(30.0), parseTypedWeight("−30", lb), "the keyboard's minus")
    }

    @Test
    fun `blank clears and anything else is refused`() {
        assertEquals(TypedWeight.Clear, parseTypedWeight("  ", lb))
        assertEquals(TypedWeight.Clear, parseTypedWeight("", lb))
        assertNull(parseTypedWeight("heavy", lb))
        assertNull(parseTypedWeight("1.2.3", lb))
        assertNull(parseTypedWeight("1e3", lb))
        assertNull(parseTypedWeight("45 20", lb))
        assertNull(parseTypedWeight("45 2", lb), "two numbers are not one")
        assertNull(parseTypedWeight("lb", lb), "a unit alone is no weight")
        assertNull(parseTypedWeight("2500", lb), "past any bar or stack")
        assertEquals(value(2000.0), parseTypedWeight("2000", lb))
    }
}
