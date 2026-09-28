package com.tczerwien.lifts.ui

import com.tczerwien.lifts.seed.SeedParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExerciseNamesTest {

    private val catalog = SeedParser.parse().associateBy { it.id }

    private fun shown(id: String) = displayName(catalog.getValue(id))

    @Test
    fun `the everyday lifts show their short gym name`() {
        assertEquals("Bench Press", shown("barbell-bench-press-medium-grip"))
        assertEquals("Incline DB Press", shown("incline-dumbbell-press"))
        assertEquals("Romanian Deadlift", shown("romanian-deadlift"))
        assertEquals("Cable Lateral Raise", shown("standing-low-pulley-deltoid-raise"))
        assertEquals("Machine Shoulder Press", shown("machine-shoulder-military-press"))
    }

    @Test
    fun `any other catalog exercise shows its own name, never cut at a bracket`() {
        assertEquals("Kettlebell Turkish Get-Up (Lunge style)", shown("kettlebell-turkish-get-up-lunge-style"))
        assertEquals("Barbell Guillotine Bench Press", shown("barbell-guillotine-bench-press"))
    }

    @Test
    fun `a catalog lift he renamed shows his name, and his name only`() {
        val renamed = catalog.getValue("barbell-bench-press-medium-grip").copy(name = "Flat Bench")
        assertEquals("Flat Bench", displayName(renamed))
        // Renamed back to the catalog name: the short name again.
        assertEquals("Bench Press", displayName(renamed.copy(name = "Barbell Bench Press - Medium Grip")))
    }

    @Test
    fun `each short name is tied to its lift's catalog name exactly`() {
        val wrong = shortNameCatalogNames.filter { (id, name) -> catalog[id]?.name != name }
        assertTrue(wrong.isEmpty(), "catalog name differs: $wrong")
    }

    @Test
    fun `a custom exercise shows the name he gave it`() {
        val custom = catalog.getValue("barbell-bench-press-medium-grip").copy(name = "My Bench", isCustom = true)
        assertEquals("My Bench", displayName(custom))
    }

    @Test
    fun `every short name belongs to a real catalog lift, is unique, and fits the header`() {
        val missing = shortNames.keys.filterNot { it in catalog }
        assertTrue(missing.isEmpty(), "no such catalog ids: $missing")
        assertEquals(shortNames.size, shortNames.values.toSet().size, "two lifts share a short name")
        val long = shortNames.values.filter { it.length > 22 }
        assertTrue(long.isEmpty(), "too long for the header: $long")
    }

    @Test
    fun `no two catalog exercises end up showing the same name`() {
        val shownNames = catalog.values.map(::displayName)
        val clashes = shownNames.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(clashes.isEmpty(), "shown twice: $clashes")
    }

    // --- Renaming ---

    private val bench get() = catalog.getValue("barbell-bench-press-medium-grip")
    private val benchCatalogName = "Barbell Bench Press - Medium Grip"

    @Test
    fun `a rename stores the typed name, on one line with single spaces`() {
        assertEquals("Flat Bench", renameTarget(bench, "Flat Bench", shown = "Bench Press") { benchCatalogName })
        assertEquals("Flat Bench", renameTarget(bench, "  Flat \n Bench\n", shown = "Bench Press") { benchCatalogName })
    }

    @Test
    fun `submitting the name the editor showed changes nothing`() {
        assertNull(renameTarget(bench, "Bench Press", shown = "Bench Press") { benchCatalogName })
        assertNull(renameTarget(bench, " Bench Press ", shown = "Bench Press") { benchCatalogName })
    }

    @Test
    fun `blank puts a renamed catalog lift back, and leaves a custom one alone`() {
        val renamed = bench.copy(name = "Flat Bench")
        assertEquals(benchCatalogName, renameTarget(renamed, "  ", shown = "Flat Bench") { benchCatalogName })
        // Never renamed: blank has nothing to undo.
        assertNull(renameTarget(bench, "", shown = "Bench Press") { benchCatalogName })
        val custom = bench.copy(id = "custom-1", name = "My Press", isCustom = true)
        assertNull(renameTarget(custom, "", shown = "My Press") { error("a custom lift has no catalog name") })
    }

    @Test
    fun `the catalog is read only for a blank reset`() {
        var asked = 0
        renameTarget(bench, "Flat Bench", shown = "Bench Press") { asked++; benchCatalogName }
        renameTarget(bench, "Bench Press", shown = "Bench Press") { asked++; benchCatalogName }
        assertEquals(0, asked)
        assertEquals(benchCatalogName, knownCatalogName("barbell-bench-press-medium-grip"))
        assertNull(knownCatalogName("barbell-guillotine-bench-press"))
    }

    @Test
    fun `names equipment only as a whole word`() {
        assertTrue(namesEquipment("Barbell Deadlift", "barbell"))
        assertTrue(namesEquipment("Kettlebell Windmill", "kettlebells"))
        assertFalse(namesEquipment("Bench Press", "barbell"))
        assertFalse(namesEquipment("Cablestack Row", "cable"))
        assertFalse(namesEquipment("Plank", null))
        assertFalse(namesEquipment("Other Things", "other"))
    }
}
