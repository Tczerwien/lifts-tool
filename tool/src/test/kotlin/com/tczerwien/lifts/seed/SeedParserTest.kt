package com.tczerwien.lifts.seed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pure-JVM tests over the bundled seed payload — no Room, no Android. */
class SeedParserTest {

    private val entities = SeedParser.parse()

    @Test
    fun recordCountMatchesDataset() {
        val datasetCount = Json.parseToJsonElement(ExerciseSeedData.json).jsonArray.size
        assertEquals(datasetCount, entities.size, "parser must not drop records")
        assertEquals(873, entities.size, "bundled free-exercise-db snapshot has 873 exercises")
    }

    @Test
    fun allSlugsAreUnique() {
        val slugs = entities.map { it.id }
        assertEquals(slugs.size, slugs.toSet().size, "exercise ids (slugs) must be unique")
    }

    @Test
    fun slugsAreLowercaseHyphenSafe() {
        val pattern = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
        entities.forEach { entity ->
            assertTrue(pattern.matches(entity.id), "bad slug: '${entity.id}'")
        }
    }

    @Test
    fun benchPressSpotCheck() {
        val bench = entities.find { it.name == "Barbell Bench Press - Medium Grip" }
        assertNotNull(bench, "expected 'Barbell Bench Press - Medium Grip' in the catalog")
        assertEquals("barbell-bench-press-medium-grip", bench.id)
        assertTrue("chest" in bench.primaryMuscles, "bench press primaryMuscles must contain chest")
        assertEquals("strength", bench.category)
        assertTrue(bench.instructions.isNotEmpty(), "instructions must be mapped through")
    }

    @Test
    fun noRecordHasEmptyName() {
        entities.forEach { entity ->
            assertTrue(entity.name.isNotBlank(), "record '${entity.id}' has a blank name")
        }
    }

    @Test
    fun allRecordsAreNotCustom() {
        assertTrue(entities.none { it.isCustom }, "seeded records must have isCustom=false")
    }

    @Test
    fun jsonPayloadIsUnderTwoMegabytes() {
        val bytes = ExerciseSeedData.json.toByteArray(Charsets.UTF_8).size
        assertTrue(bytes < 2 * 1024 * 1024, "seed payload is $bytes bytes; expected < 2 MB")
    }
}
