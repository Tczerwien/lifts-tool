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

    @Query("SELECT * FROM exercises WHERE id = :id")
    fun byId(id: String): ExerciseEntity?

    @Query("SELECT * FROM exercises WHERE id IN (:ids)")
    fun byIds(ids: List<String>): List<ExerciseEntity>

    /** Caller supplies LIKE wildcards, e.g. "%bench%". */
    @Query("SELECT * FROM exercises WHERE name LIKE :pattern ORDER BY name COLLATE NOCASE")
    fun searchByName(pattern: String): List<ExerciseEntity>

    /** Matches the JSON-quoted element inside the primaryMuscles JSON array column. */
    @Query(
        "SELECT * FROM exercises WHERE primaryMuscles LIKE '%\"' || :muscle || '\"%' " +
            "ORDER BY name COLLATE NOCASE"
    )
    fun listByPrimaryMuscle(muscle: String): List<ExerciseEntity>
}
