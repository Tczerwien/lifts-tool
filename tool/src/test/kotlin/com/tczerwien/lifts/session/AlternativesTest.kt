package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Alternatives: the choices a slot keeps from its template, and where the swap icon takes it. */
class AlternativesTest {

    // --- ALT-1: a slot's choices ---

    @Test
    fun `a slot's choices are its main, then each alternative once, in order`() {
        assertEquals(listOf("squat", "press", "lunge"), slotChoices("squat", listOf("press", "lunge")))
        assertEquals(listOf("squat", "press", "lunge"), slotChoices("squat", listOf("press", "squat", "lunge", "press")), "no repeats, the main once")
    }

    @Test
    fun `a template exercise without another exercise to go to keeps no choices`() {
        assertEquals(emptyList(), slotChoices("squat", emptyList()))
        assertEquals(emptyList(), slotChoices("squat", listOf("squat")), "only itself")
    }

    @Test
    fun `the next choice cycles through them and back to the main`() {
        val choices = listOf("squat", "press", "lunge")
        assertEquals(listOf("press", "lunge", "squat"), choices.map { nextChoice(choices, it) })
        assertEquals("squat", nextChoice(choices, "row"), "one not among them goes to the main")
        assertNull(nextChoice(listOf("squat"), "squat"), "nowhere else to go")
        assertNull(nextChoice(emptyList(), "squat"))
    }

    // --- ALT-2: where the swap icon goes, and when it shows ---

    @Test
    fun `the swap skips an exercise gone from the log or hidden, and shows nothing with no other left`() {
        val choices = listOf("squat", "gone", "press", "hidden")
        val usable = { id: String -> id != "gone" && id != "hidden" }
        assertEquals("press", swapTarget(choices, "squat", rows(), usable))
        assertEquals("squat", swapTarget(choices, "press", rows(), usable), "round to the main")
        assertNull(swapTarget(listOf("squat", "gone"), "squat", rows(), usable), "the only alternative is missing")
        assertNull(swapTarget(emptyList(), "squat", rows(), usable), "no alternatives")
    }

    @Test
    fun `the slot on screen is a choice even when it couldn't be picked now`() {
        // A main he hid since: the slot started on it, and the swap still leaves it for the next.
        assertEquals("press", swapTarget(listOf("hidden", "press"), "hidden", rows(), { it != "hidden" }))
    }

    @Test
    fun `once a set is ticked the slot keeps its exercise`() {
        assertNull(swapTarget(listOf("squat", "press"), "squat", rows(ticked = 1), { true }))
        assertEquals("press", swapTarget(listOf("squat", "press"), "squat", rows(ticked = 0), { true }), "values typed in but nothing ticked")
    }

    private fun rows(ticked: Int = 0): List<SetEntity> = List(3) { index ->
        SetEntity(
            id = index + 1L,
            workoutExerciseId = 1,
            position = index,
            type = SET_TYPE_NORMAL,
            weightKg = 100.0,
            reps = 8,
            completedAt = if (index < ticked) 1_000L + index else null,
        )
    }
}
