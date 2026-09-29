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
}
