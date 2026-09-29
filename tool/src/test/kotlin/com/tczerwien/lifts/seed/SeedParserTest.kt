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
    fun wellKnownIdsStayPinned() {
        // Workouts store these ids. If a catalog update renamed one, logged history would lose its
        // exercise, so a change here must come with a data fix.
        val ids = entities.map { it.id }.toSet()
        val pinned = listOf(
            "barbell-bench-press-medium-grip", "barbell-squat", "romanian-deadlift", "pullups",
            "incline-dumbbell-press", "cable-seated-lateral-raise", "leg-press", "chin-up", "plank",
        )
        assertEquals(emptyList(), pinned.filterNot { it in ids })
    }

    // --- defaultTrackingType ---

    private fun trackingOf(id: String): String {
        val entity = entities.find { it.id == id }
        assertNotNull(entity, "expected seeded exercise '$id'")
        return entity.trackingType
    }

    @Test
    fun trackingTypeIdListsNameRealExercises() {
        val ids = entities.map { it.id }.toSet()
        val listed = SeedParser.DURATION_IDS + SeedParser.ASSISTED_IDS +
            SeedParser.WEIGHTED_BODYWEIGHT_IDS + SeedParser.REPS_ONLY_IDS
        assertEquals(emptySet(), listed - ids, "tracking-type lists name ids missing from the catalog")
    }

    @Test
    fun trackingTypeDefaultsFollowTheRules() {
        assertEquals("weight_reps", trackingOf("barbell-bench-press-medium-grip"))
        assertEquals("reps_only", trackingOf("chin-up"), "body-only equipment counts reps")
        assertEquals("reps_only", trackingOf("parallel-bar-dip"), "listed bodyweight move under 'other'")
        assertEquals("duration", trackingOf("plank"), "listed hold")
        assertEquals("duration", trackingOf("running-treadmill"), "cardio is timed")
        assertEquals("assisted_bodyweight", trackingOf("band-assisted-pull-up"))
        assertEquals("weighted_bodyweight", trackingOf("weighted-pull-ups"))
    }

    @Test
    fun everyStretchIsTimed() {
        val stretches = entities.filter { it.category == "stretching" }
        assertTrue(stretches.isNotEmpty())
        assertTrue(stretches.all { it.trackingType == "duration" }, "stretches must default to duration")
    }

    @Test
    fun jsonPayloadIsUnderTwoMegabytes() {
        val bytes = ExerciseSeedData.json.toByteArray(Charsets.UTF_8).size
        assertTrue(bytes < 2 * 1024 * 1024, "seed payload is $bytes bytes; expected < 2 MB")
    }

    @Test
    fun `catalog name lookup finds a lift by its app id`() {
        assertEquals("Barbell Bench Press - Medium Grip", SeedParser.catalogName("barbell-bench-press-medium-grip"))
        assertEquals(null, SeedParser.catalogName("no-such-lift"))
        // One map for the whole catalog, the same one each time: parsed once per process.
        assertEquals(SeedParser.parse().size, SeedParser.catalogNames.size)
        assertTrue(SeedParser.catalogNames === SeedParser.catalogNames)
    }
}
