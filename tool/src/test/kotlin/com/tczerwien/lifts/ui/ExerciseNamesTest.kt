package com.tczerwien.lifts.ui

import com.tczerwien.lifts.seed.SeedParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExerciseNamesTest {

    @Test
    fun `a leading equipment word moves to the end in brackets`() {
        assertEquals("Bench Press - Medium Grip (Barbell)", displayName("Barbell Bench Press - Medium Grip", "barbell"))
        assertEquals("Crossover (Cable)", displayName("Cable Crossover", "cable"))
        assertEquals("Chest Pass (Medicine Ball)", displayName("Medicine Ball Chest Pass", "medicine ball"))
    }

    @Test
    fun `singular and plural equipment both match, and the name keeps its own spelling`() {
        assertEquals("Windmill (Kettlebell)", displayName("Kettlebell Windmill", "kettlebells"))
        assertEquals("Pull Apart (Band)", displayName("Band Pull Apart", "bands"))
        assertEquals("curl (barbell)", displayName("barbell curl", "Barbell"))
    }

    @Test
    fun `a bracket inside the name is never cut`() {
        // The header used to cut at the first " (" and show "Machine Shoulder" (#128).
        assertEquals("Shoulder (Military) Press (Machine)", displayName("Machine Shoulder (Military) Press", "machine"))
        assertEquals("Chest Push (single response)", displayName("Chest Push (single response)", "medicine ball"))
        assertEquals("Kettlebell Turkish Get-Up (Lunge style)", displayName("Kettlebell Turkish Get-Up (Lunge style)", "kettlebells"))
    }

    @Test
    fun `names that don't lead with their equipment show as they are`() {
        assertEquals("Close-Grip Barbell Bench Press", displayName("Close-Grip Barbell Bench Press", "barbell"))
        assertEquals("Leverage Shoulder Press", displayName("Leverage Shoulder Press", "machine"))
        assertEquals("Bench Press (Barbell)", displayName("Bench Press (Barbell)", "barbell"))
        assertEquals("Pullups", displayName("Pullups", "body only"))
        assertEquals("Sled Push", displayName("Sled Push", "other"))
        assertEquals("Plank", displayName("Plank", null))
    }

    @Test
    fun `a name that is only its equipment is left alone`() {
        assertEquals("Barbell", displayName("Barbell", "barbell"))
        assertEquals("Barbell -", displayName("Barbell -", "barbell"))
        // "Barbelled" is not "Barbell" followed by a word.
        assertEquals("Barbelled Row", displayName("Barbelled Row", "barbell"))
    }

    @Test
    fun `the whole catalog shows distinct names, none cut at a bracket`() {
        val catalog = SeedParser.parse()
        val shown = catalog.map { displayName(it.name, it.equipment) }
        assertEquals(catalog.size, shown.toSet().size)
        catalog.zip(shown).filter { (exercise, _) -> '(' in exercise.name }.forEach { (exercise, display) ->
            val inside = exercise.name.substringAfter('(').substringBefore(')')
            assertTrue("($inside)" in display, "${exercise.name} shows as $display")
        }
    }

    @Test
    fun `names equipment only as a whole word`() {
        assertTrue(namesEquipment("Close-Grip Barbell Bench Press", "barbell"))
        assertTrue(namesEquipment("Curl (Barbell)", "barbell"))
        assertTrue(namesEquipment("Windmill (Kettlebell)", "kettlebells"))
        assertFalse(namesEquipment("Leverage Shoulder Press", "machine"))
        assertFalse(namesEquipment("Cablestack Row", "cable"))
        assertFalse(namesEquipment("Plank", null))
        assertFalse(namesEquipment("Other Things", "other"))
    }
}
