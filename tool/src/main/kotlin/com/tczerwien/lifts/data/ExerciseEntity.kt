package com.tczerwien.lifts.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An exercise in the catalog. Seeded exercises use slug ids like "bench-press-barbell";
 * user-created ones use "custom-<uuid>".
 */
@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val equipment: String?,
    val primaryMuscles: List<String>,
    val secondaryMuscles: List<String>,
    val category: String,
    val level: String?,
    val instructions: List<String>,
    val isCustom: Boolean = false,
    val pinnedNote: String? = null,
)
