package com.tczerwien.lifts.analytics

import com.tczerwien.lifts.seed.TestCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MusclesTest {

    @Test
    fun `21 muscles, the catalog's 17 and the four finer ones, each once`() {
        assertEquals(21, MUSCLES.size)
        assertEquals(MUSCLES.toSet().size, MUSCLES.size)
        assertEquals((CATALOG_MUSCLES + EXTRA_MUSCLES).toSet(), MUSCLES.toSet())
        assertEquals(listOf("front delts", "side delts", "rear delts", "upper chest"), EXTRA_MUSCLES)
    }

    @Test
    fun `the catalog's muscles are exactly the ones its exercises name`() {
        val named = TestCatalog.exercises.flatMap { it.primaryMuscles + it.secondaryMuscles }.toSet()
        assertEquals(named, CATALOG_MUSCLES.toSet())
    }

    @Test
    fun `listed by label, A to Z`() {
        assertEquals(MUSCLES.map(::muscleLabel).sorted(), MUSCLES.map(::muscleLabel))
        assertEquals("Abdominals", muscleLabel(MUSCLES.first()))
        assertEquals("Upper Chest", muscleLabel(MUSCLES.last()))
        assertTrue(MUSCLES.indexOf("front delts") < MUSCLES.indexOf("glutes"))
    }

    @Test
    fun `a label capitalises each word`() {
        assertEquals("Side Delts", muscleLabel("side delts"))
        assertEquals("Lower Back", muscleLabel("lower back"))
        assertEquals("Quadriceps", muscleLabel("quadriceps"))
        // One Lifts doesn't know still shows, the same way.
        assertEquals("Serratus Anterior", muscleLabel(" serratus  anterior"))
    }

    @Test
    fun `a muscle from a file is stored as Lifts writes it`() {
        assertEquals("side delts", muscleKey(" Side  Delts "))
        assertEquals("", muscleKey("   "))
        assertEquals(listOf("chest", "lats"), muscleKeys(listOf("Chest", "chest ", " ", "LATS")))
    }

    @Test
    fun `a tap adds or takes a muscle, in label order, and the catalog row empties the list`() {
        // From the catalog's muscles (an empty list), the first tap is the whole list.
        assertEquals(listOf("triceps"), toggledCountsFor(emptyList(), "triceps"))
        assertEquals(listOf("chest", "front delts", "triceps"), toggledCountsFor(listOf("chest", "triceps"), "front delts"))
        assertEquals(listOf("chest"), toggledCountsFor(listOf("chest", "triceps"), "triceps"))
        // The last one out leaves the list empty: the catalog's muscles count again.
        assertEquals(emptyList(), toggledCountsFor(listOf("chest"), "chest"))
        assertEquals(emptyList(), toggledCountsFor(listOf("chest", "triceps"), null))
        // A muscle this Lifts doesn't know (a newer backup's) can still be taken out, and stays while others change.
        assertEquals(listOf("chest", "serratus"), toggledCountsFor(listOf("serratus"), "chest"))
        assertEquals(emptyList(), toggledCountsFor(listOf("serratus"), "serratus"))
    }

    @Test
    fun `the Counts For row names his muscles, or the catalog's`() {
        assertEquals("Chest, Front Delts", countsForText(listOf("chest", "front delts"), listOf("triceps")))
        assertEquals("Catalog · Middle Back", countsForText(emptyList(), listOf("middle back")))
        assertEquals("Catalog · —", countsForText(emptyList(), emptyList()))
        // His own exercise's primary muscle is his choice, not a catalog's.
        assertEquals("Primary · Shoulders", countsForText(emptyList(), listOf("shoulders"), custom = true))
        assertEquals("Rear Delts", countsForText(listOf("rear delts"), listOf("shoulders"), custom = true))
    }
}
