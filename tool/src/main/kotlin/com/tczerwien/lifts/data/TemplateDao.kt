package com.tczerwien.lifts.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** A template's number of exercises, for [TemplateDao.exerciseCounts]. */
data class TemplateExerciseCount(val templateId: Long, val exercises: Int)

/** Blocking DAO — call from Dispatchers.IO. */
@Dao
interface TemplateDao {
    @Insert
    fun insert(template: TemplateEntity): Long

    @Update
    fun update(template: TemplateEntity): Int

    @Query("DELETE FROM templates WHERE id = :id")
    fun delete(id: Long): Int

    @Query("SELECT * FROM templates WHERE id = :id")
    fun byId(id: Long): TemplateEntity?

    @Query("SELECT * FROM templates ORDER BY position")
    fun listAll(): List<TemplateEntity>

    @Insert
    fun insertTemplateExercise(templateExercise: TemplateExerciseEntity): Long

    @Update
    fun updateTemplateExercise(templateExercise: TemplateExerciseEntity): Int

    @Query("DELETE FROM template_exercises WHERE id = :id")
    fun deleteTemplateExercise(id: Long): Int

    @Query("DELETE FROM template_exercises WHERE templateId = :templateId")
    fun deleteExercisesForTemplate(templateId: Long): Int

    @Query("SELECT * FROM template_exercises WHERE templateId = :templateId ORDER BY position")
    fun exercisesForTemplate(templateId: Long): List<TemplateExerciseEntity>

    /** How many exercises each template has, in one query (templates with none are left out). */
    @Query("SELECT templateId, COUNT(*) AS exercises FROM template_exercises GROUP BY templateId")
    fun exerciseCounts(): List<TemplateExerciseCount>

    /** The templates that ask for [exerciseId], each once. */
    @Query("SELECT DISTINCT templateId FROM template_exercises WHERE exerciseId = :exerciseId ORDER BY templateId")
    fun templateIdsWith(exerciseId: String): List<Long>
}
