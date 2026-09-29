package com.tczerwien.lifts.library

import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.summary
import com.tczerwien.lifts.seed.SeedParser
import com.tczerwien.lifts.ui.knownCatalogName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExerciseSearchTest {

    private val catalog = SeedParser.parse()
    private val entries = catalog.map { catalogEntry(it.summary()) }

    private fun search(query: String, recent: List<String> = emptyList(), within: List<CatalogEntry> = entries) =
        searchExercises(within, query, recent).map { it.id }

    private fun custom(name: String, muscle: String = "abdominals") = ExerciseEntity(
        id = "custom-${name.lowercase().replace(' ', '-')}",
        name = name,
        equipment = null,
        primaryMuscles = listOf(muscle),
        secondaryMuscles = emptyList(),
        category = "strength",
        level = null,
        instructions = emptyList(),
        isCustom = true,
    )

    @Test
    fun `hyphenated and run-together names are found word by word`() {
        assertEquals(listOf("chin-up", "one-arm-chin-up"), search("chin up"))
        assertEquals("chin-up", search("chinup").first())
        assertEquals(7, search("sit up").size)
        assertEquals("sit-up", search("sit up").first())
        val pullUps = search("pull up")
        assertEquals("pullups", pullUps.first())
        assertTrue(pullUps.containsAll(listOf("band-assisted-pull-up", "v-bar-pullup", "weighted-pull-ups")), "$pullUps")
        assertEquals("pullups", search("pullups").first())
        // Run together, from the start of a word: "pullup" is in Band-Assisted Pull-Up, "rdl" is not in Hurdle.
        assertTrue("band-assisted-pull-up" in search("pullup"))
        assertTrue("hurdle-hops" !in search("rdl"))
        assertEquals(21, search("push up").size)
        assertEquals("pushups", search("push up").first())
        assertTrue(search("t bar row").containsAll(listOf("t-bar-row-with-handle", "lying-t-bar-row")))
    }

    @Test
    fun `words match in any order, half typed`() {
        val results = search("bench dumb")
        assertTrue("dumbbell-bench-press" in results.take(3), "$results")
        assertEquals(search("dumbbell bench").toSet(), search("bench dumbbell").toSet())
        // Plurals: "curls" is curl, "presses" is press.
        assertEquals("hammer-curls", search("curls hammer").first())
        assertEquals("barbell-bench-press-medium-grip", search("presses bench").first())
    }

    @Test
    fun `typed percent and underscore are plain characters, not wildcards`() {
        assertEquals(emptyList(), search("%"))
        assertEquals(emptyList(), search("_"))
        assertEquals(search("bench"), search("bench%"))
    }

    @Test
    fun `gym shorthand finds the catalog's words`() {
        assertEquals("dumbbell-shrug", search("db shrug").first())
        assertEquals(listOf("romanian-deadlift", "romanian-deadlift-from-deficit"), search("rdl"))
        assertEquals("butterfly", search("pec deck").first())
        assertEquals("reverse-machine-flyes", search("reverse pec-deck").first())
        assertEquals("standing-military-press", search("ohp").first())
        assertEquals("seated-one-arm-cable-pulley-rows", search("single-arm cable row").first())
        assertEquals("v-bar-pulldown", search("neutral-grip lat pulldown").first())
        assertEquals("triceps-pushdown", search("cable pushdown").first())
        assertEquals("split-squat-with-dumbbells", search("bss").first())
    }

    @Test
    fun `a lift answers to its short name, its stored name and, once renamed, its catalog name`() {
        assertEquals("dumbbell-shrug", search("dumbbell shrug").first())
        val bench = catalog.first { it.id == "barbell-bench-press-medium-grip" }.copy(name = "Flat Bench")
        val renamed = entries.filter { it.id != bench.id } + catalogEntry(bench.summary(), knownCatalogName(bench.id))
        assertEquals(bench.id, search("flat bench", within = renamed).first())
        assertTrue(bench.id in search("barbell bench press medium", within = renamed))
        // "Bench Press" is no longer the name it shows, but it still answers to it, behind the lifts that show it.
        val benchPress = search("bench press", within = renamed)
        assertTrue(bench.id in benchPress && benchPress.first() != bench.id, "$benchPress")
    }

    @Test
    fun `his custom exercises are searched like the rest`() {
        val withCustom = entries + catalogEntry(custom("Hanging Knee Raise").summary())
        assertEquals("custom-hanging-knee-raise", search("hanging knee", within = withCustom).first())
    }

    @Test
    fun `what he has done before comes first among equals`() {
        assertEquals("barbell-curl", search("curl").first())
        assertEquals("preacher-curl", search("curl", recent = listOf("preacher-curl", "hammer-curls")).first())
        assertEquals("hammer-curls", search("curl", recent = listOf("preacher-curl", "hammer-curls"))[1])
    }

    @Test
    fun `lifts rank above stretches and jumps with the same words`() {
        val splitSquat = search("split squat")
        assertTrue(splitSquat.indexOf("split-squat-with-dumbbells") < splitSquat.indexOf("split-squats"), "$splitSquat")
    }

    @Test
    fun `the plain lift comes before its variants`() {
        assertEquals("barbell-bench-press-medium-grip", search("press").first())
        assertEquals("wide-grip-lat-pulldown", search("lat pulldown").first())
        assertEquals("leg-press", search("leg press").first())
    }

    @Test
    fun `a long query nothing answers offers the names that miss one word`() {
        assertEquals("dumbbell-shoulder-press", search("seated db shoulder press").first())
        assertEquals("hanging-leg-raise", search("hanging knee raise").first())
        // Two words must both match: no near misses.
        assertEquals(emptyList(), search("hanging kneez"))
    }

    /**
     * Everyday lifts (from a private note), in plain gym words: 28 exercises, each
     * alternative ("hack squat or leg press") searched on its own. Every one is found first, or second
     * where the catalog has two right answers.
     */
    @Test
    fun `everyday lifts are found in plain gym words`() {
        val first = mapOf(
            "romanian deadlift" to "romanian-deadlift",
            "barbell hip thrust" to "barbell-hip-thrust",
            "seated leg curl" to "seated-leg-curl",
            "standing calf raise" to "standing-calf-raises",
            "dumbbell shrug" to "dumbbell-shrug",
            "cable lateral raise" to "standing-low-pulley-deltoid-raise",
            "dead bug" to "dead-bug",
            "incline db press" to "incline-dumbbell-press",
            "lat pulldown" to "wide-grip-lat-pulldown",
            "incline cable fly" to "incline-cable-flye",
            "incline db curl" to "incline-dumbbell-curl",
            "face pull" to "face-pull",
            "hack squat" to "hack-squat",
            "leg press" to "leg-press",
            "walking lunge" to "bodyweight-walking-lunge",
            "bulgarian split squat" to "split-squat-with-dumbbells",
            "leg extension" to "leg-extensions",
            "back extension" to "hyperextensions-back-extensions",
            "seated calf raise" to "seated-calf-raise",
            "incline barbell press" to "barbell-incline-bench-press-medium-grip",
            "smith incline press" to "smith-machine-incline-bench-press",
            "seated db shoulder press" to "dumbbell-shoulder-press",
            "machine shoulder press" to "machine-shoulder-military-press",
            "neutral-grip lat pulldown" to "v-bar-pulldown",
            "assisted pull-up" to "band-assisted-pull-up",
            "single-arm cable row" to "seated-one-arm-cable-pulley-rows",
            "db lateral raise" to "side-lateral-raise",
            "reverse pec-deck" to "reverse-machine-flyes",
            "cable pushdown" to "triceps-pushdown",
            "preacher curl" to "preacher-curl",
            "hammer curl" to "hammer-curls",
            "db shrug" to "dumbbell-shrug",
        )
        first.forEach { (query, id) -> assertEquals(id, search(query).firstOrNull(), query) }
        val topTwo = mapOf(
            "chest-supported row" to listOf("lying-t-bar-row", "dumbbell-incline-row"),
            "overhead cable triceps ext" to listOf("triceps-overhead-extension-with-rope"),
        )
        topTwo.forEach { (query, ids) -> assertTrue(search(query).take(2).containsAll(ids), "$query: ${search(query)}") }
    }

    @Test
    fun `a catalog lift is renamed only when its name no longer runs together to its id`() {
        assertTrue(catalog.none { isRenamed(it.summary()) }, catalog.filter { isRenamed(it.summary()) }.map { it.id }.toString())
        val shrug = catalog.first { it.id == "dumbbell-shrug" }
        assertTrue(isRenamed(shrug.copy(name = "Shrugs").summary()))
        assertFalse(isRenamed(custom("Anything").summary()))
    }

    @Test
    fun `the catalog name counts only for a renamed lift`() {
        val shrug = catalog.first { it.id == "dumbbell-shrug" }
        assertEquals(listOf("DB Shrug", "Dumbbell Shrug"), catalogEntry(shrug.summary(), "Something Else").allNames)
        assertEquals(
            listOf("Shrugs", "Dumbbell Shrug"),
            catalogEntry(shrug.copy(name = "Shrugs").summary(), "Dumbbell Shrug").allNames,
        )
    }
}
