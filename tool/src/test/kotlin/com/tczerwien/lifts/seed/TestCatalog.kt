package com.tczerwien.lifts.seed

import com.tczerwien.lifts.data.ExerciseEntity
import java.io.File

/** The bundled catalog as the JVM tests read it: the asset file itself (Gradle runs them in tool/). */
object TestCatalog {
    val json: String by lazy { File("src/main/assets/${BundledCatalog.ASSET}").readText() }

    val source = CatalogSource { json }

    val exercises: List<ExerciseEntity> by lazy { SeedParser.parse(json) }
}
