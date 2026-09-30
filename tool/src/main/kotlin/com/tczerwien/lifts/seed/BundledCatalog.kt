package com.tczerwien.lifts.seed

import com.tczerwien.lifts.data.ExerciseEntity

/** Reads the bundled catalog's JSON: the APK's asset in the app ([com.tczerwien.lifts.data.bundledCatalog]), a file in tests. */
fun interface CatalogSource {
    fun read(): String
}

/**
 * The exercise catalog bundled in the APK: assets/exercises.json, from free-exercise-db (public
 * domain, Unlicense). An asset rather than Kotlin string constants: it compresses in the APK (about
 * 150 KB against 1.6 MB of dex) and is read only when needed. Blocking: call off the main thread.
 */
object BundledCatalog {
    /** The file in src/main/assets (tool/scripts/generate_exercise_seed.py writes it). */
    const val ASSET = "exercises.json"

    /**
     * The catalog's version, bumped whenever exercises.json changes: a phone whose catalog is older
     * takes the new one in on its next Home ([com.tczerwien.lifts.data.LiftsRepository.updateCatalog],
     * [mergeCatalog]). 1 is the catalog every Lifts so far has shipped.
     */
    const val VERSION = 1

    /** The catalog's rows ([SeedParser.parse]), parsed afresh: a first run or a catalog update only. */
    fun exercises(source: CatalogSource): List<ExerciseEntity> = SeedParser.parse(source.read())

    @Volatile
    private var names: Map<String, String>? = null

    /**
     * Every catalog name by app id, read and parsed the first time only, once per process (the catalog
     * in the APK never changes while it runs): a renamed lift answers to its catalog name in the picker
     * and the name checks.
     */
    fun names(source: CatalogSource): Map<String, String> =
        names ?: synchronized(this) { names ?: SeedParser.parseNames(source.read()).also { names = it } }
}
