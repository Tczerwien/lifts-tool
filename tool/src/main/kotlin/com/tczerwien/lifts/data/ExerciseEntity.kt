package com.tczerwien.lifts.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An exercise in the catalog. Seeded exercises use slugs of the dataset id, like
 * "barbell-bench-press-medium-grip"; user-created ones use "custom-<uuid>".
 *
 * [trackingType] decides which numbers a set records: one of [TRACKING_WEIGHT_REPS],
 * [TRACKING_REPS_ONLY], [TRACKING_WEIGHTED_BODYWEIGHT], [TRACKING_ASSISTED_BODYWEIGHT],
 * [TRACKING_DURATION]. [perSide] marks one-side-at-a-time exercises (reps are per side).
 * [hidden] custom exercises stay referenced by old workouts but leave the picker. [countsFor] lists
 * the muscles a set counts for; empty means the catalog's [primaryMuscles]. Fields added after
 * version 1 go last ([WorkoutEntity]).
 */
@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val equipment: String?,
    val primaryMuscles: List<String>,
    val secondaryMuscles: List<String>,
    val category: String,
    val level: String?,
    val instructions: List<String>,
    val isCustom: Boolean = false,
    val pinnedNote: String? = null,
    @ColumnInfo(defaultValue = TRACKING_WEIGHT_REPS) val trackingType: String = TRACKING_WEIGHT_REPS,
    @ColumnInfo(defaultValue = "0") val perSide: Boolean = false,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    @ColumnInfo(defaultValue = "[]") val countsFor: List<String> = emptyList(),
) {
    companion object {
        const val TRACKING_WEIGHT_REPS = "weight_reps"
        const val TRACKING_REPS_ONLY = "reps_only"
        const val TRACKING_WEIGHTED_BODYWEIGHT = "weighted_bodyweight"
        const val TRACKING_ASSISTED_BODYWEIGHT = "assisted_bodyweight"
        const val TRACKING_DURATION = "duration"
    }
}
