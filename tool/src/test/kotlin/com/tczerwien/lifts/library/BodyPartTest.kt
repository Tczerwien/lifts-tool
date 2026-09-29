package com.tczerwien.lifts.library

import com.tczerwien.lifts.data.summary
import com.tczerwien.lifts.seed.SeedParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyPartTest {

    private val catalog = SeedParser.parse()
    private val entries = catalog.map { catalogEntry(it.summary()) }

    private fun ids(part: BodyPart) = inBodyPart(entries, part).map { it.id }

    @Test
    fun `every catalog muscle but neck has a chip`() {
        val muscles = catalog.flatMap { it.primaryMuscles + it.secondaryMuscles }.toSet()
        assertEquals(setOf("neck"), muscles.filter { bodyPartOf(it) == null }.toSet())
        assertEquals(17, muscles.size)
    }

    @Test
    fun `the chips cover the catalog but its neck exercises, each exercise once`() {
        val chipped = BodyPart.entries.flatMap { ids(it) }
        assertEquals(chipped.size, chipped.toSet().size)
        assertEquals(catalog.size - 8, chipped.size)
    }

    @Test
    fun `his lifts sit under the chip he'd look in`() {
        assertTrue("dumbbell-shrug" in ids(BodyPart.BACK))
        assertTrue("wide-grip-lat-pulldown" in ids(BodyPart.BACK))
        assertTrue("hyperextensions-back-extensions" in ids(BodyPart.BACK))
        assertTrue("seated-calf-raise" in ids(BodyPart.LEGS))
        assertTrue("barbell-hip-thrust" in ids(BodyPart.LEGS))
        assertTrue("incline-dumbbell-curl" in ids(BodyPart.ARMS))
        assertTrue("face-pull" in ids(BodyPart.SHOULDERS))
        assertTrue("dead-bug" in ids(BodyPart.CORE))
        assertTrue("incline-dumbbell-press" in ids(BodyPart.CHEST))
    }

    @Test
    fun `muscles match whatever their case`() {
        assertEquals(BodyPart.BACK, bodyPartOf(" Middle Back "))
        assertNull(bodyPartOf("neck"))
        assertNull(bodyPartOf(""))
    }
}
