package com.tczerwien.lifts.library

import com.tczerwien.lifts.data.summary
import com.tczerwien.lifts.seed.SeedParser
import kotlin.test.Test
import kotlin.test.assertEquals

class PickerTest {

    private val catalog = catalogEntries(SeedParser.parse().map { it.summary() })

    private fun ids(chip: PickerChip, recent: List<String> = emptyList(), query: String? = null) =
        pickerEntries(catalog, recent, chip, query).map { it.id }

    @Test
    fun `he opens on Recent once he has logged something, until he picks a chip himself`() {
        assertEquals(PickerChip.Recent, chipAfterLoad(PickerChip.All, chosen = false, hasRecent = true))
        assertEquals(PickerChip.All, chipAfterLoad(PickerChip.All, chosen = false, hasRecent = false))
        // Picked Chest (or searched) before the catalog landed: that stands.
        val chest = PickerChip.Part(BodyPart.CHEST)
        assertEquals(chest, chipAfterLoad(chest, chosen = true, hasRecent = true))
        // Recent with nothing in it is All.
        assertEquals(PickerChip.All, chipAfterLoad(PickerChip.Recent, chosen = true, hasRecent = false))
    }

    @Test
    fun `Recent keeps its order and leaves out what the catalog doesn't show`() {
        val recent = listOf("dumbbell-shrug", "custom-hidden", "barbell-squat")
        assertEquals(listOf("dumbbell-shrug", "barbell-squat"), ids(PickerChip.Recent, recent))
    }

    @Test
    fun `All is the catalog by shown name, and a body part filters it`() {
        val all = ids(PickerChip.All)
        assertEquals(catalog.size, all.size)
        val shown = catalog.map { it.shown.lowercase() }
        assertEquals(shown.sorted(), shown)
        assertEquals(inBodyPart(catalog, BodyPart.LEGS).map { it.id }, ids(PickerChip.Part(BodyPart.LEGS)))
    }

    @Test
    fun `a search replaces the chip, and what he did recently leads it`() {
        val chest = PickerChip.Part(BodyPart.CHEST)
        assertEquals("dumbbell-shrug", ids(chest, query = "db shrug").first())
        assertEquals("preacher-curl", ids(PickerChip.Recent, listOf("preacher-curl"), query = "curl").first())
    }
}
