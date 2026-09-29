package com.tczerwien.lifts.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ConvertersTest {

    private val converters = Converters()

    @Test
    fun listsAreStoredAsCompactJson() {
        // The stored format: a backup written by any version must read back the same.
        assertEquals("[\"chest\",\"triceps\"]", converters.fromStringList(listOf("chest", "triceps")))
    }

    @Test
    fun roundTripAndEmptyString() {
        val muscles = listOf("lower back", "hamstrings")
        assertEquals(muscles, converters.toStringList(converters.fromStringList(muscles)))
        assertEquals(emptyList(), converters.toStringList(""))
    }
}
