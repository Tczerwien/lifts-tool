package com.tczerwien.lifts.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One exercise slot inside a template, ordered by [position]. Deleting the template deletes its
 * slots. [warmupSets] null means "repeat last session's warm-ups"; [repMin]/[repMax]/[targetRir]
 * are the prescription shown at the gym.
 *
 * Adjacent slots (by [position]) sharing a non-null [supersetGroup] form a superset. [alternatives]
 * are other exercise ids that can stand in for [exerciseId], in order. [targetRirMax] is the top of
 * an RIR range; null means [targetRir] alone. Fields added after version 1 go last ([WorkoutEntity]).
 */
@Entity(
    tableName = "template_exercises",
    indices = [Index("templateId")],
    foreignKeys = [
        ForeignKey(
            entity = TemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TemplateExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long,
    val exerciseId: String,
    val position: Int,
    val targetSets: Int,
    val restSecWork: Int? = null,
    val restSecWarmup: Int? = null,
    val warmupSets: Int? = null,
    val repMin: Int? = null,
    val repMax: Int? = null,
    val targetRir: Int? = null,
    val supersetGroup: Int? = null,
    @ColumnInfo(defaultValue = "[]") val alternatives: List<String> = emptyList(),
    val targetRirMax: Int? = null,
)
