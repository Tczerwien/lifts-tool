package com.tczerwien.lifts.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A workout session. [endedAt] is null while the workout is in progress. Timestamps are epoch millis. */
@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val templateId: Long? = null,
    val notes: String? = null,
    val bodyweightKg: Double? = null,
)
