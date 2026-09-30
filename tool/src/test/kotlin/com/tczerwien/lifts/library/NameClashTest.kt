package com.tczerwien.lifts.library

import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.summary
import com.tczerwien.lifts.seed.TestCatalog
import com.tczerwien.lifts.ui.knownCatalogName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NameClashTest {

    private val catalog = TestCatalog.exercises
    private val entries = catalog.map { catalogEntry(it.summary()) }

    private val landmine = ExerciseEntity(
        id = "custom-1",
        name = "Landmine Press",
        equipment = null,
        primaryMuscles = listOf("shoulders"),
        secondaryMuscles = emptyList(),
        category = "strength",
        level = null,
        instructions = emptyList(),
        isCustom = true,
    )

    @Test
    fun `case, spaces, punctuation and plural endings don't make a new name`() {
        assertTrue(sameName("Pull-Ups", "pull ups"))
        assertTrue(sameName("Pull-Ups", "Pullup"))
        assertTrue(sameName("Hammer Curls", "hammer curl"))
        assertTrue(sameName("Crunch", "Crunches"))
        assertTrue(sameName("Bench Presses", "Bench Press"))
        assertTrue(sameName("Dumbbell Fly", "Dumbbell Flyes"))
        assertTrue(sameName("Incline Cable Flye", "incline cable flies"))
        assertFalse(sameName("Leg Press", "Leg Curl"))
        assertFalse(sameName("Press", "Pres"))
        assertEquals(emptySet(), nameKeys(" - "))
    }

    @Test
    fun `a plural of a catalog name is taken`() {
        assertEquals("crunches", nameClash("Crunch", entries)?.id)
        assertEquals("dumbbell-flyes", nameClash("Dumbbell Fly", entries)?.id)
    }

    @Test
    fun `a name the catalog already shows or stores is taken`() {
        assertEquals("barbell-bench-press-medium-grip", nameClash("bench press", entries)?.id)
        assertEquals("dumbbell-shrug", nameClash("Dumbbell Shrugs", entries)?.id)
        assertEquals("dumbbell-shrug", nameClash("db shrug", entries)?.id)
        assertEquals("pullups", nameClash("Pull Ups", entries)?.id)
        assertNull(nameClash("Hanging Knee Raise", entries))
        assertNull(nameClash("", entries))
    }

    @Test
    fun `his own custom exercises count too`() {
        val withCustom = entries + catalogEntry(landmine.summary())
        assertEquals("custom-1", nameClash("landmine press", withCustom)?.id)
        // Renaming it to its own name again is no clash.
        assertNull(nameClash("landmine press", withCustom, selfId = "custom-1"))
    }

    @Test
    fun `a lift renamed back to its own names is free, another lift's is not`() {
        val shrug = catalog.first { it.id == "dumbbell-shrug" }.copy(name = "Shrugs")
        val renamed = entries.filter { it.id != shrug.id } + catalogEntry(shrug.summary(), knownCatalogName(shrug.id))
        assertNull(nameClash("Dumbbell Shrug", renamed, selfId = shrug.id))
        assertNull(nameClash("DB Shrug", renamed, selfId = shrug.id))
        // Another exercise can't take the name he gave it, nor its old catalog name.
        assertEquals(shrug.id, nameClash("shrugs", renamed, selfId = "barbell-shrug")?.id)
        assertEquals(shrug.id, nameClash("Dumbbell Shrug", renamed, selfId = "barbell-shrug")?.id)
        // Nor its short name, which it shows again once reset.
        assertEquals(shrug.id, nameClash("DB Shrug", renamed, selfId = "custom-1")?.id)
    }

    @Test
    fun `a reset is checked against the name it would show, not only the one it stores`() {
        // Older data: a custom "DB Shrug" made while the catalog lift was renamed "Shrugs".
        val shrug = catalog.first { it.id == "dumbbell-shrug" }.copy(name = "Shrugs")
        val customShrug = landmine.copy(id = "custom-2", name = "DB Shrug")
        val entries = entries.filter { it.id != shrug.id } +
            catalogEntry(shrug.summary(), knownCatalogName(shrug.id)) + catalogEntry(customShrug.summary())
        // Stored "Dumbbell Shrug" is free by name, but it would show "DB Shrug", which the custom has.
        assertNull(nameClash("Dumbbell Shrug", entries, selfId = shrug.id))
        assertEquals("custom-2", renameClash(shrug.id, isCustom = false, target = "Dumbbell Shrug", entries)?.id)
        // A custom exercise shows what it stores, so only that is checked.
        assertNull(renameClash("custom-2", isCustom = true, target = "Shrug Hold", entries))
    }
}
