package com.tczerwien.lifts.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One exercise slot inside a template, ordered by [position]. */
@Entity(
    tableName = "template_exercises",
    indices = [Index("templateId")],
)
data class TemplateExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long,
    val exerciseId: String,
    val position: Int,
    val targetSets: Int,
    val restSecWork: Int? = null,
    val restSecWarmup: Int? = null,
)
