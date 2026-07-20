package com.tczerwien.lifts.seed

import com.tczerwien.lifts.data.ExerciseDao

/** Seeds the bundled exercise catalog on first run. Blocking — call from Dispatchers.IO. */
object Seeder {
    fun seedIfEmpty(exerciseDao: ExerciseDao) {
        if (exerciseDao.count() == 0L) {
            exerciseDao.insertAll(SeedParser.parse())
        }
    }
}
