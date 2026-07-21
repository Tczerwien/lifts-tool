package com.tczerwien.lifts.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** Blocking DAO — call from Dispatchers.IO. */
@Dao
interface BarDao {
    @Insert
    fun insert(bar: BarEntity): Long

    @Update
    fun update(bar: BarEntity): Int

    @Query("DELETE FROM bars WHERE id = :id")
    fun delete(id: Long): Int

    @Query("SELECT * FROM bars ORDER BY position")
    fun listAll(): List<BarEntity>

    @Query("SELECT COUNT(*) FROM bars")
    fun count(): Long
}
