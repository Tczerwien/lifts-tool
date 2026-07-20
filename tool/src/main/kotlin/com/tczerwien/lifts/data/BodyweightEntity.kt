package com.tczerwien.lifts.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One bodyweight measurement per day. [epochDay] is days since 1970-01-01 (java.time LocalDate.toEpochDay). */
@Entity(
    tableName = "bodyweight",
    indices = [Index(value = ["epochDay"], unique = true)],
)
data class BodyweightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val weightKg: Double,
)
