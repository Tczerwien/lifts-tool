package com.tczerwien.lifts.seed

import com.tczerwien.lifts.data.BarDao
import com.tczerwien.lifts.data.BarEntity
import com.tczerwien.lifts.data.ExerciseDao

/** Seeds the bundled exercise catalog and default equipment on first run. Blocking — call from Dispatchers.IO. */
object Seeder {
    fun seedIfEmpty(exerciseDao: ExerciseDao, barDao: BarDao) {
        if (exerciseDao.count() == 0L) {
            exerciseDao.insertAll(SeedParser.parse())
        }
        if (barDao.count() == 0L) {
            // 45 lb in kg.
            barDao.insert(BarEntity(name = "Olympic Bar", weightKg = 20.4117, position = 0))
        }
    }
}
