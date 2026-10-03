package com.tczerwien.lifts.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Whole tables at once, for a restore: read every row of a backup, then replace every row of the
 * live database with them, ids included. Nothing else uses it.
 */
@Dao
interface BulkDao {
    @Query("SELECT * FROM exercises ORDER BY id")
    fun exercises(): List<ExerciseEntity>

    /**
     * Every exercise with its instructions left empty: the exports never show them, and they are most
     * of the catalog's text (873 JSON lists to decode on every export run).
     */
    @Query("SELECT $EXERCISE_COLUMNS_WITHOUT_INSTRUCTIONS FROM exercises ORDER BY id")
    fun exercisesWithoutInstructions(): List<ExerciseEntity>

    @Query("SELECT * FROM workouts ORDER BY id")
    fun workouts(): List<WorkoutEntity>

    @Query("SELECT * FROM workout_exercises ORDER BY id")
    fun workoutExercises(): List<WorkoutExerciseEntity>

    @Query("SELECT * FROM sets ORDER BY id")
    fun sets(): List<SetEntity>

    @Query("SELECT * FROM templates ORDER BY id")
    fun templates(): List<TemplateEntity>

    @Query("SELECT * FROM template_exercises ORDER BY id")
    fun templateExercises(): List<TemplateExerciseEntity>

    @Query("SELECT * FROM bodyweight ORDER BY id")
    fun bodyweight(): List<BodyweightEntity>

    @Query("SELECT * FROM bars ORDER BY id")
    fun bars(): List<BarEntity>

    @Query("DELETE FROM sets")
    fun clearSets()

    @Query("DELETE FROM workout_exercises")
    fun clearWorkoutExercises()

    @Query("DELETE FROM workouts")
    fun clearWorkouts()

    @Query("DELETE FROM template_exercises")
    fun clearTemplateExercises()

    @Query("DELETE FROM templates")
    fun clearTemplates()

    @Query("DELETE FROM bodyweight")
    fun clearBodyweight()

    @Query("DELETE FROM bars")
    fun clearBars()

    @Query("DELETE FROM exercises")
    fun clearExercises()

    @Insert
    fun insertExercises(rows: List<ExerciseEntity>)

    @Insert
    fun insertWorkouts(rows: List<WorkoutEntity>)

    @Insert
    fun insertWorkoutExercises(rows: List<WorkoutExerciseEntity>)

    @Insert
    fun insertSets(rows: List<SetEntity>)

    @Insert
    fun insertTemplates(rows: List<TemplateEntity>)

    @Insert
    fun insertTemplateExercises(rows: List<TemplateExerciseEntity>)

    @Insert
    fun insertBodyweight(rows: List<BodyweightEntity>)

    @Insert
    fun insertBars(rows: List<BarEntity>)
}

/**
 * [BulkDao.exercisesWithoutInstructions]'s columns: every column of `exercises`, in table order, with
 * the instructions read as empty. A column left out here reads as its default, and Room only fails
 * the build when the field is non-null, so `ExerciseColumnsTest` compares this list with the newest
 * exported schema.
 */
const val EXERCISE_COLUMNS_WITHOUT_INSTRUCTIONS =
    "id, name, equipment, primaryMuscles, secondaryMuscles, category, level, '' AS instructions, " +
        "isCustom, pinnedNote, trackingType, perSide, hidden, countsFor"
