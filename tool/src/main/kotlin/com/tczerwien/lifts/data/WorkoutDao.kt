package com.tczerwien.lifts.data

import androidx.room.Dao
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
     * Completed sets of the most recent FINISHED workout (endedAt not null) that contains
     * [exerciseId], ordered by set position. Empty if the exercise was never performed.
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
            "ORDER BY w.startedAt DESC LIMIT 1" +
            ") " +
            "ORDER BY workout_exercises.position, sets.position"
    )
    fun previousPerformance(exerciseId: String): List<SetEntity>
}
