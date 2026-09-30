package com.tczerwien.lifts.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Blocking DAO — call from Dispatchers.IO. */
@Dao
interface BodyweightDao {
    /** One entry per day: replaces any existing row for the same epochDay (unique index). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertByEpochDay(entry: BodyweightEntity): Long

    @Query("SELECT * FROM bodyweight ORDER BY epochDay")
    fun listAll(): List<BodyweightEntity>

    @Query("DELETE FROM bodyweight WHERE epochDay = :epochDay")
    fun deleteByEpochDay(epochDay: Long): Int

    @Query("SELECT * FROM bodyweight WHERE epochDay BETWEEN :fromDay AND :toDay ORDER BY epochDay")
    fun between(fromDay: Long, toDay: Long): List<BodyweightEntity>
}
