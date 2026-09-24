package com.tczerwien.lifts.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ConvertersTest {

    private val converters = Converters()

    @Test
    fun listsAreStoredAsCompactJson() {
        // ExerciseDao.listByPrimaryMuscle matches '%"chest"%': the encoding must stay compact.
        assertEquals("[\"chest\",\"triceps\"]", converters.fromStringList(listOf("chest", "triceps")))
    }

    @Test
    fun roundTripAndEmptyString() {
        val muscles = listOf("lower back", "hamstrings")
        assertEquals(muscles, converters.toStringList(converters.fromStringList(muscles)))
        assertEquals(emptyList(), converters.toStringList(""))
    }
}
