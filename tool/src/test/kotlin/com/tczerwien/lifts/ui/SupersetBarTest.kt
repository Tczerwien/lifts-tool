package com.tczerwien.lifts.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SupersetBarTest {

    @Test
    fun `the bar runs through a superset and stops at its ends, so two side by side read as two`() {
        val groups = listOf(null, 1, 1, 1, 2, 2, 3)
        assertEquals(
            listOf(
                SupersetBar.NONE, SupersetBar.FIRST, SupersetBar.MIDDLE, SupersetBar.LAST,
                SupersetBar.FIRST, SupersetBar.LAST, SupersetBar.NONE,
            ),
            groups.indices.map { SupersetBar.at(groups, it) },
        )
    }
}
