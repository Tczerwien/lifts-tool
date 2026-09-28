package com.tczerwien.lifts.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ExerciseEntity::class,
        WorkoutEntity::class,
        WorkoutExerciseEntity::class,
        SetEntity::class,
        TemplateEntity::class,
        TemplateExerciseEntity::class,
        BodyweightEntity::class,
        BarEntity::class,
    ],
    version = LiftsDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LiftsDatabase : RoomDatabase() {
    abstract val exerciseDao: ExerciseDao
    abstract val workoutDao: WorkoutDao
    abstract val templateDao: TemplateDao
    abstract val bodyweightDao: BodyweightDao
    abstract val barDao: BarDao
    abstract val bulkDao: BulkDao

    companion object {
        /** The @Database version above; a backup from a newer version can't be restored. */
        const val VERSION = 1
    }
}
