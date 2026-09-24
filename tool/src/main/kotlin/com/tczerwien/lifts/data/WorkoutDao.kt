package com.tczerwien.lifts.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** Blocking DAO — call from Dispatchers.IO. */
@Dao
interface WorkoutDao {
    @Insert
    fun insert(workout: WorkoutEntity): Long

    @Update
    fun update(workout: WorkoutEntity): Int

    @Query("SELECT * FROM workouts WHERE id = :id")
    fun byId(id: Long): WorkoutEntity?

    @Query("SELECT * FROM workouts ORDER BY startedAt DESC")
    fun listAll(): List<WorkoutEntity>

    @Query("SELECT * FROM workouts ORDER BY startedAt DESC LIMIT :limit")
    fun listRecent(limit: Int): List<WorkoutEntity>

    @Insert
    fun insertWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long

    @Insert
    fun insertSet(set: SetEntity): Long

    @Update
    fun updateSet(set: SetEntity): Int

    @Query("SELECT * FROM workout_exercises WHERE workoutId = :workoutId ORDER BY position")
    fun exercisesForWorkout(workoutId: Long): List<WorkoutExerciseEntity>

    /** All sets of a workout, ordered by exercise position then set position. */
    @Query(
        "SELECT sets.* FROM sets " +
            "JOIN workout_exercises ON sets.workoutExerciseId = workout_exercises.id " +
            "WHERE workout_exercises.workoutId = :workoutId " +
            "ORDER BY workout_exercises.position, sets.position"
    )
    fun setsForWorkout(workoutId: Long): List<SetEntity>

    /**
     * Completed sets of the most recent FINISHED workout (endedAt not null) in which [exerciseId]
     * had at least one completed set, ordered by slot position then set position. A workout
     * where the exercise was added but skipped doesn't count. Empty if it was never performed.
     */
    @Query(
        "SELECT sets.* FROM sets " +
            "JOIN workout_exercises ON sets.workoutExerciseId = workout_exercises.id " +
            "WHERE workout_exercises.exerciseId = :exerciseId " +
            "AND sets.completedAt IS NOT NULL " +
            "AND workout_exercises.workoutId = (" +
            "SELECT w.id FROM workouts w " +
            "JOIN workout_exercises we ON we.workoutId = w.id " +
            "WHERE we.exerciseId = :exerciseId AND w.endedAt IS NOT NULL " +
            "AND EXISTS (SELECT 1 FROM sets s WHERE s.workoutExerciseId = we.id AND s.completedAt IS NOT NULL) " +
            "ORDER BY w.startedAt DESC, w.id DESC LIMIT 1" +
            ") " +
            "ORDER BY workout_exercises.position, sets.position"
    )
    fun previousPerformance(exerciseId: String): List<SetEntity>

    /**
     * Every completed set of the given exercises, from any workout (finished or not) except
     * [excludeWorkoutId], in completion order. Pass -1 to exclude nothing.
     */
    @Query(
        "SELECT sets.*, we.exerciseId AS exerciseId, we.workoutId AS workoutId FROM sets " +
            "JOIN workout_exercises we ON sets.workoutExerciseId = we.id " +
            "WHERE we.exerciseId IN (:exerciseIds) AND we.workoutId != :excludeWorkoutId " +
            "AND sets.completedAt IS NOT NULL " +
            "ORDER BY sets.completedAt, sets.id"
    )
    fun completedSetsForExercises(exerciseIds: List<String>, excludeWorkoutId: Long): List<SetWithExercise>
}

/** A set joined with the exercise and workout its slot belongs to. */
data class SetWithExercise(
    @Embedded val set: SetEntity,
    val exerciseId: String,
    val workoutId: Long,
)
