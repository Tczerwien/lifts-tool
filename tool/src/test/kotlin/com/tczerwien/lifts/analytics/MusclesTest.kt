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
}
