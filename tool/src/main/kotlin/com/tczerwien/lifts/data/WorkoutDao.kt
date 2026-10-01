package com.tczerwien.lifts.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** Blocking DAO — call from Dispatchers.IO. */
@Dao
interface WorkoutDao {
    @Insert
    fun insert(workout: WorkoutEntity): Long

    @Update
    fun update(workout: WorkoutEntity): Int

    @Query("SELECT * FROM workouts WHERE id = :id")
    fun byId(id: Long): WorkoutEntity?

    @Query("SELECT * FROM workouts ORDER BY startedAt DESC")
    fun listAll(): List<WorkoutEntity>

    @Query("SELECT * FROM workouts ORDER BY startedAt DESC LIMIT :limit")
    fun listRecent(limit: Int): List<WorkoutEntity>

    /** Finished workouts, newest first, each with its number of ticked sets. [limit] -1 means all. */
    @Query(
        "SELECT w.*, (SELECT COUNT(*) FROM sets s JOIN workout_exercises we ON s.workoutExerciseId = we.id " +
            "WHERE we.workoutId = w.id AND s.completedAt IS NOT NULL) AS completedSets " +
            "FROM workouts w WHERE w.endedAt IS NOT NULL ORDER BY w.startedAt DESC LIMIT :limit"
    )
    fun finishedWithSetCounts(limit: Int): List<WorkoutWithSetCount>

    /**
     * The exercises he has logged, most recently used first: each by the latest finished workout in
     * which it has a ticked set, then by its place in that workout (SQLite takes a bare column from
     * the row MAX picked). Unfinished workouts and unticked sets count nowhere.
     */
    @Query(
        "SELECT we.exerciseId AS exerciseId, MAX(w.startedAt) AS lastStartedAt, we.position AS position " +
            "FROM workout_exercises we JOIN workouts w ON w.id = we.workoutId " +
            "WHERE w.endedAt IS NOT NULL " +
            "AND EXISTS (SELECT 1 FROM sets s WHERE s.workoutExerciseId = we.id AND s.completedAt IS NOT NULL) " +
            "GROUP BY we.exerciseId ORDER BY lastStartedAt DESC, position LIMIT :limit"
    )
    fun recentExercises(limit: Int): List<RecentExercise>

    @Query("SELECT * FROM workout_exercises")
    fun listAllWorkoutExercises(): List<WorkoutExerciseEntity>

    /** Makes [exerciseId]'s logged weights help (negative), keeping their size: it became Assisted. */
    @Query(
        "UPDATE sets SET weightKg = -ABS(weightKg) WHERE weightKg > 0 AND workoutExerciseId IN " +
            "(SELECT id FROM workout_exercises WHERE exerciseId = :exerciseId)"
    )
    fun makeWeightsHelp(exerciseId: String): Int

    /** Makes [exerciseId]'s logged weights load (positive), keeping their size: it stopped being Assisted. */
    @Query(
        "UPDATE sets SET weightKg = ABS(weightKg) WHERE weightKg < 0 AND workoutExerciseId IN " +
            "(SELECT id FROM workout_exercises WHERE exerciseId = :exerciseId)"
    )
    fun makeWeightsLoad(exerciseId: String): Int

    /** Per exercise with a ticked set: whether any has a weight above 0, reps, seconds ([fittedToHistory]). */
    @Query(
        "SELECT we.exerciseId AS exerciseId, MAX(COALESCE(s.weightKg, 0) > 0) AS weighted, " +
            "MAX(s.reps IS NOT NULL) AS hasReps, MAX(s.durationSec IS NOT NULL) AS hasDuration " +
            "FROM sets s JOIN workout_exercises we ON s.workoutExerciseId = we.id " +
            "WHERE s.completedAt IS NOT NULL GROUP BY we.exerciseId"
    )
    fun loggedFields(): List<LoggedFields>

    @Query("SELECT * FROM sets")
    fun listAllSets(): List<SetEntity>

    /** Workouts still in progress (endedAt null), newest first. */
    @Query("SELECT * FROM workouts WHERE endedAt IS NULL ORDER BY startedAt DESC, id DESC")
    fun listOpen(): List<WorkoutEntity>

    @Query("DELETE FROM workouts WHERE id = :id")
    fun delete(id: Long): Int

    @Insert
    fun insertWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long

    @Insert
    fun insertSet(set: SetEntity): Long

    @Update
    fun updateSet(set: SetEntity): Int

    /**
     * Writes a set's values only. Its slot and position stay as the database has them, so a save
     * that lands after a set was removed and the rest renumbered can't put back an old position.
     */
    @Query(
        "UPDATE sets SET type = :type, weightKg = :weightKg, reps = :reps, durationSec = :durationSec, " +
            "rpe = :rpe, completedAt = :completedAt WHERE id = :id"
    )
    fun updateSetValues(
        id: Long,
        type: String,
        weightKg: Double?,
        reps: Int?,
        durationSec: Int?,
        rpe: Double?,
        completedAt: Long?,
    ): Int

    @Update
    fun updateWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Int

    /** One slot's note: what he wrote about this exercise in this workout. */
    @Query("UPDATE workout_exercises SET notes = :notes WHERE id = :id")
    fun updateSlotNotes(id: Long, notes: String?): Int

    /** One slot's work rest; null follows Settings' default. */
    @Query("UPDATE workout_exercises SET restSecWork = :restSecWork WHERE id = :id")
    fun updateSlotRest(id: Long, restSecWork: Int?): Int

    @Query("SELECT * FROM sets WHERE id = :id")
    fun setById(id: Long): SetEntity?

    @Query("SELECT * FROM workout_exercises WHERE id = :id")
    fun workoutExerciseById(id: Long): WorkoutExerciseEntity?

    @Query("SELECT * FROM sets WHERE workoutExerciseId = :workoutExerciseId ORDER BY position, id")
    fun setsForWorkoutExercise(workoutExerciseId: Long): List<SetEntity>

    @Query("DELETE FROM sets WHERE id = :id")
    fun deleteSet(id: Long): Int

    @Query("DELETE FROM sets WHERE workoutExerciseId = :workoutExerciseId")
    fun deleteSetsForWorkoutExercise(workoutExerciseId: Long): Int

    @Query("DELETE FROM workout_exercises WHERE id = :id")
    fun deleteWorkoutExercise(id: Long): Int

    @Query(
        "DELETE FROM sets WHERE workoutExerciseId IN " +
            "(SELECT id FROM workout_exercises WHERE workoutId = :workoutId)"
    )
    fun deleteSetsForWorkout(workoutId: Long): Int

    @Query("DELETE FROM workout_exercises WHERE workoutId = :workoutId")
    fun deleteExercisesForWorkout(workoutId: Long): Int

    @Query("SELECT * FROM workout_exercises WHERE workoutId = :workoutId ORDER BY position")
    fun exercisesForWorkout(workoutId: Long): List<WorkoutExerciseEntity>

    /** All sets of a workout, ordered by exercise position then set position. */
    @Query(
        "SELECT sets.* FROM sets " +
            "JOIN workout_exercises ON sets.workoutExerciseId = workout_exercises.id " +
            "WHERE workout_exercises.workoutId = :workoutId " +
            "ORDER BY workout_exercises.position, sets.position"
    )
    fun setsForWorkout(workoutId: Long): List<SetEntity>

    /**
     * Completed sets of the most recent FINISHED workout (endedAt not null) in which [exerciseId]
     * had at least one completed set, ordered by slot position then set position. A workout
     * where the exercise was added but skipped doesn't count. Empty if it was never performed.
     */
    @Query(
        "SELECT sets.* FROM sets " +
            "JOIN workout_exercises ON sets.workoutExerciseId = workout_exercises.id " +
            "WHERE workout_exercises.exerciseId = :exerciseId " +
            "AND sets.completedAt IS NOT NULL " +
            "AND workout_exercises.workoutId = (" +
            "SELECT w.id FROM workouts w " +
            "JOIN workout_exercises we ON we.workoutId = w.id " +
            "WHERE we.exerciseId = :exerciseId AND w.endedAt IS NOT NULL " +
            "AND EXISTS (SELECT 1 FROM sets s WHERE s.workoutExerciseId = we.id AND s.completedAt IS NOT NULL) " +
            "ORDER BY w.startedAt DESC, w.id DESC LIMIT 1" +
            ") " +
            "ORDER BY workout_exercises.position, sets.position"
    )
    fun previousPerformance(exerciseId: String): List<SetEntity>

    /**
     * Every slot of [exerciseId] in the workout [previousPerformance] reads, in position order,
     * including slots where nothing was ticked, so a skipped slot keeps its place.
     */
    @Query(
        "SELECT workout_exercises.id FROM workout_exercises " +
            "WHERE workout_exercises.exerciseId = :exerciseId " +
            "AND workout_exercises.workoutId = (" +
            "SELECT w.id FROM workouts w " +
            "JOIN workout_exercises we ON we.workoutId = w.id " +
            "WHERE we.exerciseId = :exerciseId AND w.endedAt IS NOT NULL " +
            "AND EXISTS (SELECT 1 FROM sets s WHERE s.workoutExerciseId = we.id AND s.completedAt IS NOT NULL) " +
            "ORDER BY w.startedAt DESC, w.id DESC LIMIT 1" +
            ") " +
            "ORDER BY workout_exercises.position"
    )
    fun previousSlotIds(exerciseId: String): List<Long>

    /**
     * Every completed set of the given exercises, from any workout (finished or not) except
     * [excludeWorkoutId], in completion order. Pass -1 to exclude nothing.
     */
    @Query(
        "SELECT sets.*, we.exerciseId AS exerciseId, we.workoutId AS workoutId, we.position AS slotPosition FROM sets " +
            "JOIN workout_exercises we ON sets.workoutExerciseId = we.id " +
            "WHERE we.exerciseId IN (:exerciseIds) AND we.workoutId != :excludeWorkoutId " +
            "AND sets.completedAt IS NOT NULL " +
            "ORDER BY sets.completedAt, sets.id"
    )
    fun completedSetsForExercises(exerciseIds: List<String>, excludeWorkoutId: Long): List<SetWithExercise>

    /**
     * Every completed set of the given exercises completed at or before [until], from any workout, in
     * completion order: the history a past workout's PR stars depend on (later sets can't change them).
     */
    @Query(
        "SELECT sets.*, we.exerciseId AS exerciseId, we.workoutId AS workoutId, we.position AS slotPosition FROM sets " +
            "JOIN workout_exercises we ON sets.workoutExerciseId = we.id " +
            "WHERE we.exerciseId IN (:exerciseIds) AND sets.completedAt IS NOT NULL AND sets.completedAt <= :until " +
            "ORDER BY sets.completedAt, sets.id"
    )
    fun completedSetsForExercisesUntil(exerciseIds: List<String>, until: Long): List<SetWithExercise>

    /** The finished workouts in brief ([FinishedWorkouts]). */
    @Query(FINISHED_WORKOUTS_SQL)
    fun finishedWorkouts(): FinishedWorkouts
}

/**
 * The finished workouts in brief: how many, the newest one's id, the sum of their ends; how many sets
 * they hold ticked, the sum of those ticks' times, and every ticked set's values (weight in grams,
 * reps, seconds, RIR, type) folded into one whole number, weighted by the set's id so two edits can't
 * cancel out. Whole numbers only: the same sum in any row order, so a backup and the log it copied
 * agree exactly. Different after any Finish since: a new workout, or a RESUME and Finish again (a
 * later end, more sets ticked, a value corrected, even when the end stays put: the clock set back),
 * a stale close, a delete, a restore. Read the same way from a backup file
 * ([com.tczerwien.lifts.backup.BackupFile.finishedWorkouts]).
 */
const val FINISHED_WORKOUTS_SQL =
    "SELECT f.count, f.lastId, f.endSum, s.ticked, s.tickSum, s.valueSum FROM " +
        "(SELECT COUNT(*) AS count, COALESCE(MAX(id), 0) AS lastId, COALESCE(SUM(endedAt), 0) AS endSum " +
        "FROM workouts WHERE endedAt IS NOT NULL) AS f, " +
        "(SELECT COUNT(*) AS ticked, COALESCE(SUM(sets.completedAt), 0) AS tickSum, COALESCE(SUM(sets.id * (" +
        "COALESCE(CAST(ROUND(sets.weightKg * 1000) AS INTEGER), 0) + COALESCE(sets.reps, 0) * 1000003 + " +
        "COALESCE(sets.durationSec, 0) * 7919 + COALESCE(CAST(ROUND(sets.rpe * 10) AS INTEGER), 0) * 104729 + " +
        "(CASE sets.type WHEN 'warmup' THEN 1 WHEN 'failure' THEN 2 WHEN 'drop' THEN 3 ELSE 0 END) * 15485863" +
        ")), 0) AS valueSum " +
        "FROM sets JOIN workout_exercises ON workout_exercises.id = sets.workoutExerciseId " +
        "JOIN workouts ON workouts.id = workout_exercises.workoutId " +
        "WHERE workouts.endedAt IS NOT NULL AND sets.completedAt IS NOT NULL) AS s"

/** [FINISHED_WORKOUTS_SQL]'s one row. */
data class FinishedWorkouts(
    val count: Int,
    val lastId: Long,
    val endSum: Long,
    val ticked: Int = 0,
    val tickSum: Long = 0,
    val valueSum: Long = 0,
)

/** A set joined with the exercise and workout its slot belongs to, and that slot's position. */
data class SetWithExercise(
    @Embedded val set: SetEntity,
    val exerciseId: String,
    val workoutId: Long,
    val slotPosition: Int,
)

/** A workout with its number of ticked sets. */
data class WorkoutWithSetCount(
    @Embedded val workout: WorkoutEntity,
    val completedSets: Int,
)

/** What an exercise's ticked sets carry, for [WorkoutDao.loggedFields]. */
data class LoggedFields(val exerciseId: String, val weighted: Boolean, val hasReps: Boolean, val hasDuration: Boolean)

/** An exercise's last use, for [WorkoutDao.recentExercises]. */
data class RecentExercise(val exerciseId: String, val lastStartedAt: Long, val position: Int)
