package com.tczerwien.lifts.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One exercise slot inside a workout, ordered by [position]. Deleting the workout deletes its
 * slots. [exerciseId] has no foreign key: custom exercises in use are hidden, never deleted.
 * The target fields are copied from the template when the workout starts.
 */
@Entity(
    tableName = "workout_exercises",
    indices = [Index("workoutId"), Index("exerciseId")],
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class WorkoutExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val exerciseId: String,
    val position: Int,
    val notes: String? = null,
    val restSecWork: Int? = null,
    val restSecWarmup: Int? = null,
    val targetRepMin: Int? = null,
    val targetRepMax: Int? = null,
    val targetRir: Int? = null,
)
