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
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LiftsDatabase : RoomDatabase() {
    abstract val exerciseDao: ExerciseDao
    abstract val workoutDao: WorkoutDao
    abstract val templateDao: TemplateDao
    abstract val bodyweightDao: BodyweightDao
    abstract val barDao: BarDao
}
