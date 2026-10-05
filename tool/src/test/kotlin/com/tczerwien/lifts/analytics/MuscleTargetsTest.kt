package com.tczerwien.lifts.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WS-4: Sets per Muscle's rows, and what "+ Target" offers. */
class MuscleTargetsTest {

    @Test
    fun `targets first, then muscles with sets but none, each A to Z`() {
        val sets = mapOf("forearms" to 2, "chest" to 9, "abductors" to 4, "triceps" to 0)
        val targets = mapOf("upper chest" to 3, "chest" to 13, "front delts" to 5)
        assertEquals(
            listOf(
                MuscleRow("chest", 9, 13),
                MuscleRow("front delts", 0, 5),
                MuscleRow("upper chest", 0, 3),
                MuscleRow("abductors", 4, null),
                MuscleRow("forearms", 2, null),
            ),
            muscleRows(sets, targets),
        )
    }

    @Test
    fun `a target with no set yet is listed at 0, a muscle with neither is not`() {
        assertEquals(listOf(MuscleRow("calves", 0, 6)), muscleRows(emptyMap(), mapOf("calves" to 6)))
        assertEquals(emptyList(), muscleRows(mapOf("neck" to 0), emptyMap()))
    }

    @Test
    fun `order goes by label, so a two-word muscle sorts as it reads`() {
        // "Lower Back" before "Middle Back" before "Neck"; a key Lifts doesn't know sorts by its label too.
        assertEquals(listOf("lower back", "middle back", "neck", "zygomaticus"), muscleOrder(listOf("neck", "zygomaticus", "middle back", "lower back"), emptyMap()))
    }

    @Test
    fun `+ Target offers the muscles without one, A to Z`() {
        assertEquals(MUSCLES, targetChoices(emptyMap()))
        val choices = targetChoices(mapOf("chest" to 13, "side delts" to 4))
        assertEquals(19, choices.size)
        assertTrue("chest" !in choices && "side delts" !in choices)
        assertEquals(choices.sortedBy(::muscleLabel), choices)
    }
}
