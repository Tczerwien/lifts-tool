package com.tczerwien.lifts.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A named implement (bar, sled, machine) with a fixed weight, ordered in the equipment list by [position]. */
@Entity(tableName = "bars")
data class BarEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val weightKg: Double,
    val position: Int,
)
