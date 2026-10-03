package com.tczerwien.lifts.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One exercise slot inside a workout, ordered by [position]. Deleting the workout deletes its
 * slots. [exerciseId] has no foreign key: custom exercises in use are hidden, never deleted.
 * The target fields are copied from the template when the workout starts.
 *
 * Adjacent slots (by [position]) sharing a non-null [supersetGroup] form a superset. [choices] are
 * the template slot's exercise ids, main first (empty: no alternatives). [targetSets] is the planned
 * number of work sets. [targetRirMax] is the top of an RIR range ("1–2": [targetRir] 1, [targetRirMax]
 * 2); null means one number. Fields added after version 1 go last ([WorkoutEntity]).
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
    val supersetGroup: Int? = null,
    @ColumnInfo(defaultValue = Converters.EMPTY_LIST) val choices: List<String> = emptyList(),
    val targetSets: Int? = null,
    val targetRirMax: Int? = null,
)
