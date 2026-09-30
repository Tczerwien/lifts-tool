package com.tczerwien.lifts.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MonoDigitsTest {

    @Test
    fun `digit runs and the rest alternate, nothing lost`() {
        assertEquals(listOf("3", ",", "330"), digitRuns("3,330"))
        assertEquals(listOf("62", ".", "5", " × ", "5"), digitRuns("62.5 × 5"))
        assertEquals(listOf("× ", "15"), digitRuns("× 15"))
        assertEquals(listOf("-", "25", " × ", "8"), digitRuns("-25 × 8"))
        assertEquals(listOf("25", " × ", "10", "/side"), digitRuns("25 × 10/side"))
        assertEquals(listOf("45", "s"), digitRuns("45s"))
        assertEquals(listOf("4", ".", "0"), digitRuns("4.0"))
        assertEquals(emptyList(), digitRuns(""))
        for (text in listOf("182.4 lb (Sep 11)", "1,204,005", "no digits")) {
            assertEquals(text, digitRuns(text).joinToString(""))
        }
    }
}
