package com.tczerwien.lifts.seed

import com.tczerwien.lifts.data.BarEntity
import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.ui.Format

/** What a first run puts into an empty database. [com.tczerwien.lifts.data.LiftsRepository.seedIfEmpty] writes it. */
object Seeder {
    /** The bundled exercise catalog. */
    fun exercises(): List<ExerciseEntity> = SeedParser.parse()

    /** The one bar a new install starts with. */
    fun defaultBar(): BarEntity = BarEntity(name = "Olympic Bar", weightKg = DEFAULT_BAR_KG, position = 0)

    /** A 45 lb Olympic bar, converted exactly (not rounded) so plate math lands on real loads. */
    val DEFAULT_BAR_KG = Format.lbToKg(45.0)
}
