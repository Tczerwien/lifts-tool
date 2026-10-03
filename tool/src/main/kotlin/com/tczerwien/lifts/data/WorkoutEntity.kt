package com.tczerwien.lifts.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A workout session. [endedAt] is null while the workout is in progress. Timestamps are epoch millis.
 * [deload] marks a deload workout.
 *
 * Fields added after version 1 go last: a migration appends its columns, so a migrated table and a
 * new one then have the same column order.
 */
@Entity(
    tableName = "workouts",
    indices = [Index("startedAt")],
)
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val templateId: Long? = null,
    val notes: String? = null,
    @ColumnInfo(defaultValue = "0") val deload: Boolean = false,
)
