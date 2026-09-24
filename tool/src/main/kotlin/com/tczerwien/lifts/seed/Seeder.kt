package com.tczerwien.lifts.seed

import com.tczerwien.lifts.data.BarDao
import com.tczerwien.lifts.data.BarEntity
import com.tczerwien.lifts.data.ExerciseDao
import com.tczerwien.lifts.ui.Format

/** Seeds the bundled exercise catalog and default equipment on first run. Blocking — call from Dispatchers.IO. */
object Seeder {
    fun seedIfEmpty(exerciseDao: ExerciseDao, barDao: BarDao) {
        if (exerciseDao.count() == 0L) {
            exerciseDao.insertAll(SeedParser.parse())
        }
        if (barDao.count() == 0L) {
            barDao.insert(BarEntity(name = "Olympic Bar", weightKg = DEFAULT_BAR_KG, position = 0))
        }
    }

    /** A 45 lb Olympic bar, converted exactly (not rounded) so plate math lands on real loads. */
    val DEFAULT_BAR_KG = Format.lbToKg(45.0)
}
