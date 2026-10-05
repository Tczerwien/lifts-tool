package com.tczerwien.lifts.seed

import com.tczerwien.lifts.data.ExerciseEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogMergeTest {

    private val bundled = TestCatalog.exercises
    private val bench = bundled.first { it.id == "barbell-bench-press-medium-grip" }

    private fun custom(name: String) = ExerciseEntity(
        id = "custom-1", name = name, equipment = "cable", primaryMuscles = listOf("shoulders"),
        secondaryMuscles = emptyList(), category = "strength", level = null, instructions = emptyList(), isCustom = true,
    )

    @Test
    fun `the same catalog again changes nothing`() {
        assertTrue(mergeCatalog(bundled, bundled).isEmpty)
    }

    @Test
    fun `a new catalog exercise goes in, measured as the catalog measures it`() {
        val phone = bundled.filterNot { it.id == "plank" }
        val changes = mergeCatalog(phone, bundled)
        assertEquals(listOf("plank"), changes.inserts.map { it.id })
        assertEquals("duration", changes.inserts.single().trackingType)
        assertTrue(changes.updates.isEmpty())
    }

    @Test
    fun `new facts reach a lift he has, and what is his stays`() {
        val his = bench.copy(
            name = "Flat Bench",
            pinnedNote = "Shoulders down",
            trackingType = "weighted_bodyweight",
            perSide = true,
            hidden = true,
            countsFor = listOf("chest", "front delts"),
            primaryMuscles = listOf("chest"),
            secondaryMuscles = emptyList(),
            instructions = listOf("old words"),
        )
        val changes = mergeCatalog(bundled.map { if (it.id == bench.id) his else it }, bundled)
        val updated = changes.updates.single()
        assertEquals(bench.secondaryMuscles, updated.secondaryMuscles)
        assertEquals(bench.instructions, updated.instructions)
        assertEquals(his.copy(secondaryMuscles = bench.secondaryMuscles, instructions = bench.instructions), updated)
    }

    @Test
    fun `what a lift counts for stays his when the catalog moves its muscles`() {
        val his = bench.copy(countsFor = listOf("chest", "front delts"))
        val phone = bundled.map { if (it.id == bench.id) his else it }
        val moved = bundled.map { if (it.id == bench.id) it.copy(primaryMuscles = listOf("triceps")) else it }
        val updated = mergeCatalog(phone, moved).updates.single()
        assertEquals(listOf("triceps"), updated.primaryMuscles, "the catalog's fact")
        assertEquals(listOf("chest", "front delts"), updated.countsFor, "his choice")
        // His Counts For is no difference from the catalog: the same catalog again writes nothing.
        assertTrue(mergeCatalog(phone, bundled).isEmpty)
    }

    @Test
    fun `a new catalog exercise whose name he already uses stays out`() {
        // Day one he made "Tib Bar Raise"; a later catalog brings one of its own.
        val newOne = bench.copy(id = "tib-bar-raise", name = "Tib Bar Raise")
        val changes = mergeCatalog(bundled + custom("Tib Bar Raise"), bundled + newOne)
        assertTrue(changes.inserts.isEmpty(), "two exercises with one name would split his history")
        assertEquals(listOf("Tib Bar Raise"), changes.skipped)
        // A short name counts as a name: the cable seated lateral raise shows as "Cable Lateral Raise".
        val shortNamed = bench.copy(id = "cable-lateral-raise", name = "Cable Lateral Raise")
        assertEquals(listOf("Cable Lateral Raise"), mergeCatalog(bundled, bundled + shortNamed).skipped)
        // Nor may two new ones take one name.
        val twin = newOne.copy(id = "tib-bar-raise-2")
        assertEquals(listOf("tib-bar-raise"), mergeCatalog(bundled, bundled + newOne + twin).inserts.map { it.id })
    }

    @Test
    fun `his exercises and rows the catalog dropped are left alone`() {
        val gone = bench.copy(id = "old-catalog-lift", name = "Old Catalog Lift")
        val changes = mergeCatalog(bundled + custom("Cable Y-Raise") + gone, bundled)
        assertTrue(changes.isEmpty)
    }
}
