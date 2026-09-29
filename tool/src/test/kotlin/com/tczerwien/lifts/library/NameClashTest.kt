package com.tczerwien.lifts.library

import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.summary
import com.tczerwien.lifts.seed.SeedParser
import com.tczerwien.lifts.ui.knownCatalogName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class NameClashTest {

    private val catalog = SeedParser.parse()
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
    fun `case, spaces, punctuation and a plural s don't make a new name`() {
        assertEquals(nameKey("Pull-Ups"), nameKey("pull ups"))
        assertEquals(nameKey("Pull-Ups"), nameKey("Pullup"))
        assertEquals(nameKey("Hammer Curls"), nameKey("hammer curl"))
        assertNotEquals(nameKey("Leg Press"), nameKey("Leg Curl"))
        assertEquals("", nameKey(" - "))
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
    }
}
