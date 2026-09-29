package com.tczerwien.lifts.seed

import com.tczerwien.lifts.data.BarEntity
import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.ui.Format

/** What a first run puts into an empty database. [com.tczerwien.lifts.data.LiftsRepository.seedIfEmpty] writes it. */
object Seeder {
    /** The bundled exercise catalog. */
    fun exercises(): List<ExerciseEntity> = SeedParser.parse()

    /** The one bar a new install starts with. */
    fun defaultBar(): BarEntity = BarEntity(name = DEFAULT_BAR_NAME, weightKg = DEFAULT_BAR_KG, position = 0)

    /** Its name; while it keeps it (and its weight), a unit switch changes it to that unit's standard bar. */
    const val DEFAULT_BAR_NAME = "Olympic Bar"

    /** A 45 lb Olympic bar, converted exactly (not rounded) so plate math lands on real loads. */
    val DEFAULT_BAR_KG = Format.lbToKg(45.0)
}
