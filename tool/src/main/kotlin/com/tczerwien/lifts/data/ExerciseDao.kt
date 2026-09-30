package com.tczerwien.lifts.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/** Blocking DAO — call from Dispatchers.IO. */
@Dao
interface ExerciseDao {
    @Query("SELECT COUNT(*) FROM exercises")
    fun count(): Long

    /** Bulk seed. IGNORE keeps existing rows (and user edits like pinnedNote) on re-seed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(exercises: List<ExerciseEntity>)

    @Insert
    fun insert(exercise: ExerciseEntity)

    @Update
    fun update(exercise: ExerciseEntity): Int

    @Query("SELECT * FROM exercises ORDER BY name COLLATE NOCASE")
    fun listAll(): List<ExerciseEntity>

    /** Every exercise without its instructions ([ExerciseSummary]), by name. */
    @Query(
        "SELECT id, name, equipment, primaryMuscles, category, isCustom, hidden FROM exercises " +
            "ORDER BY name COLLATE NOCASE"
    )
    fun listSummaries(): List<ExerciseSummary>

    @Query("SELECT * FROM exercises WHERE id = :id")
    fun byId(id: String): ExerciseEntity?

    @Query("SELECT * FROM exercises WHERE id IN (:ids)")
    fun byIds(ids: List<String>): List<ExerciseEntity>

    /** A workout slot refers to [id], ticked or not, finished or not. */
    @Query("SELECT EXISTS(SELECT 1 FROM workout_exercises WHERE exerciseId = :id)")
    fun inWorkouts(id: String): Boolean

    /** [id] has history: a ticked set in a finished workout (unticked sets and open workouts count nowhere). */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM sets s JOIN workout_exercises we ON s.workoutExerciseId = we.id " +
            "JOIN workouts w ON w.id = we.workoutId " +
            "WHERE we.exerciseId = :id AND s.completedAt IS NOT NULL AND w.endedAt IS NOT NULL)"
    )
    fun hasHistory(id: String): Boolean

    /** Deletes a custom exercise; a catalog one is never deleted. */
    @Query("DELETE FROM exercises WHERE id = :id AND isCustom = 1")
    fun deleteCustom(id: String): Int

    @Query("UPDATE exercises SET hidden = :hidden WHERE id = :id AND hidden != :hidden")
    fun setHidden(id: String, hidden: Boolean): Int

    @Query("UPDATE exercises SET perSide = :perSide WHERE id = :id AND perSide != :perSide")
    fun setPerSide(id: String, perSide: Boolean): Int
}
