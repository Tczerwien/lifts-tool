package com.tczerwien.lifts.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single set. [type] is one of "warmup" | "normal" | "failure" | "drop".
 * Weight is canonical kg; [completedAt] (epoch millis) is null until the set is logged.
 */
@Entity(
    tableName = "sets",
    indices = [Index("workoutExerciseId")],
)
data class SetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutExerciseId: Long,
    val position: Int,
    val type: String,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val rpe: Double? = null,
    val completedAt: Long? = null,
    val restTakenSec: Int? = null,
)
