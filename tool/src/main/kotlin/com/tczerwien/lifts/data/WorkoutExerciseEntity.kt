package com.tczerwien.lifts.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One exercise slot inside a workout, ordered by [position]. */
@Entity(
    tableName = "workout_exercises",
    indices = [Index("workoutId"), Index("exerciseId")],
)
data class WorkoutExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val exerciseId: String,
    val position: Int,
    val notes: String? = null,
    val restSecWork: Int? = null,
    val restSecWarmup: Int? = null,
)
