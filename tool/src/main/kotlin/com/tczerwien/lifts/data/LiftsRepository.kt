package com.tczerwien.lifts.data

import com.tczerwien.lifts.seed.Seeder

/**
 * SDK-independent repository over [LiftsDatabase]. The database instance is injected via the
 * [getInstance] lambda — this layer never constructs a Context or touches the SDK.
 * All methods are blocking; call from Dispatchers.IO.
 */
class LiftsRepository private constructor(
    private val db: LiftsDatabase,
) {
    /**
     * Runs [block] as one database transaction, so a group of writes lands together or not at all:
     * a process killed halfway leaves nothing half-written.
     */
    fun <T> inTransaction(block: () -> T): T = db.runInTransaction<T>(block)

    // --- First run ---

    /** Seeds the bundled exercise catalog and the default bar into an empty database. */
    fun seedIfEmpty() = inTransaction {
        if (db.exerciseDao.count() == 0L) db.exerciseDao.insertAll(Seeder.exercises())
        if (db.barDao.count() == 0L) db.barDao.insert(Seeder.defaultBar())
    }

    // --- Exercises ---

    fun addExercise(exercise: ExerciseEntity) = db.exerciseDao.insert(exercise)

    fun updateExercise(exercise: ExerciseEntity): Boolean = db.exerciseDao.update(exercise) > 0

    fun listExercises(): List<ExerciseEntity> = db.exerciseDao.listAll()

    fun exerciseById(id: String): ExerciseEntity? = db.exerciseDao.byId(id)

    /** [pattern] is a raw LIKE pattern, e.g. "%bench%". */
    fun searchExercisesByName(pattern: String): List<ExerciseEntity> =
        db.exerciseDao.searchByName(pattern)

    fun exercisesByPrimaryMuscle(muscle: String): List<ExerciseEntity> =
        db.exerciseDao.listByPrimaryMuscle(muscle)

    // --- Workouts ---

    fun addWorkout(workout: WorkoutEntity): Long = db.workoutDao.insert(workout)

    fun updateWorkout(workout: WorkoutEntity): Boolean = db.workoutDao.update(workout) > 0

    fun workoutById(id: Long): WorkoutEntity? = db.workoutDao.byId(id)

    fun listWorkouts(): List<WorkoutEntity> = db.workoutDao.listAll()

    fun listRecentWorkouts(limit: Int): List<WorkoutEntity> = db.workoutDao.listRecent(limit)

    /** Workouts still in progress, newest first. Normally at most one; see HomeViewModel's cleanup. */
    fun listOpenWorkouts(): List<WorkoutEntity> = db.workoutDao.listOpen()

    /**
     * Deletes the workout with all of its exercise slots and sets, in one transaction. The rows
     * are removed child-first, so nothing is left behind whether or not SQLite's foreign-key
     * cascade is switched on.
     */
    fun deleteWorkout(id: Long): Boolean = inTransaction {
        db.workoutDao.deleteSetsForWorkout(id)
        db.workoutDao.deleteExercisesForWorkout(id)
        db.workoutDao.delete(id) > 0
    }

    /** Clears endedAt, so a workout finished by mistake is in progress again. */
    fun reopenWorkout(id: Long): Boolean = inTransaction {
        val workout = db.workoutDao.byId(id) ?: return@inTransaction false
        workout.endedAt == null || db.workoutDao.update(workout.copy(endedAt = null)) > 0
    }

    fun addWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long =
        db.workoutDao.insertWorkoutExercise(workoutExercise)

    fun addSet(set: SetEntity): Long = db.workoutDao.insertSet(set)

    fun updateSet(set: SetEntity): Boolean = db.workoutDao.updateSet(set) > 0

    /** Deletes one set and renumbers the rest of its exercise slot 0..n-1, in one transaction. */
    fun deleteSet(id: Long): Boolean = inTransaction {
        val set = db.workoutDao.setById(id) ?: return@inTransaction false
        db.workoutDao.deleteSet(id)
        db.workoutDao.setsForWorkoutExercise(set.workoutExerciseId).forEachIndexed { position, remaining ->
            if (remaining.position != position) db.workoutDao.updateSet(remaining.copy(position = position))
        }
        true
    }

    /**
     * Deletes one exercise slot with its sets and renumbers the workout's remaining slots 0..n-1,
     * in one transaction.
     */
    fun deleteSlot(id: Long): Boolean = inTransaction {
        val slot = db.workoutDao.workoutExerciseById(id) ?: return@inTransaction false
        db.workoutDao.deleteSetsForWorkoutExercise(id)
        db.workoutDao.deleteWorkoutExercise(id)
        db.workoutDao.exercisesForWorkout(slot.workoutId).forEachIndexed { position, remaining ->
            if (remaining.position != position) {
                db.workoutDao.updateWorkoutExercise(remaining.copy(position = position))
            }
        }
        true
    }

    fun exercisesForWorkout(workoutId: Long): List<WorkoutExerciseEntity> =
        db.workoutDao.exercisesForWorkout(workoutId)

    fun setsForWorkout(workoutId: Long): List<SetEntity> = db.workoutDao.setsForWorkout(workoutId)

    /** Completed sets of the most recent finished workout where [exerciseId] had a completed set. */
    fun previousPerformance(exerciseId: String): List<SetEntity> =
        db.workoutDao.previousPerformance(exerciseId)

    /**
     * Completed sets of [exerciseIds] from every workout except [excludeWorkoutId], in
     * completion order, grouped by exercise id. One query, whatever the history length.
     */
    fun completedSetsByExercise(exerciseIds: Collection<String>, excludeWorkoutId: Long = -1): Map<String, List<SetEntity>> {
        if (exerciseIds.isEmpty()) return emptyMap()
        return db.workoutDao.completedSetsForExercises(exerciseIds.distinct(), excludeWorkoutId)
            .groupBy(keySelector = { it.exerciseId }, valueTransform = { it.set })
    }

    // --- Templates ---

    fun addTemplate(template: TemplateEntity): Long = db.templateDao.insert(template)

    fun updateTemplate(template: TemplateEntity): Boolean = db.templateDao.update(template) > 0

    /** Deletes the template and all of its template_exercises rows atomically. */
    fun deleteTemplate(id: Long): Boolean = inTransaction {
        db.templateDao.deleteExercisesForTemplate(id)
        db.templateDao.delete(id) > 0
    }

    fun templateById(id: Long): TemplateEntity? = db.templateDao.byId(id)

    fun listTemplates(): List<TemplateEntity> = db.templateDao.listAll()

    fun addTemplateExercise(templateExercise: TemplateExerciseEntity): Long =
        db.templateDao.insertTemplateExercise(templateExercise)

    fun updateTemplateExercise(templateExercise: TemplateExerciseEntity): Boolean =
        db.templateDao.updateTemplateExercise(templateExercise) > 0

    fun deleteTemplateExercise(id: Long): Boolean = db.templateDao.deleteTemplateExercise(id) > 0

    fun exercisesForTemplate(templateId: Long): List<TemplateExerciseEntity> =
        db.templateDao.exercisesForTemplate(templateId)

    // --- Bodyweight ---

    /** One entry per day; replaces any existing entry for [epochDay]. */
    fun upsertBodyweight(epochDay: Long, weightKg: Double) {
        db.bodyweightDao.upsertByEpochDay(BodyweightEntity(epochDay = epochDay, weightKg = weightKg))
    }

    fun listBodyweight(): List<BodyweightEntity> = db.bodyweightDao.listAll()

    // --- Bars ---

    fun addBar(bar: BarEntity): Long = db.barDao.insert(bar)

    fun updateBar(bar: BarEntity): Boolean = db.barDao.update(bar) > 0

    fun deleteBar(id: Long): Boolean = db.barDao.delete(id) > 0

    fun listBars(): List<BarEntity> = db.barDao.listAll()

    companion object {
        const val DATABASE_NAME = "lifts.db"

        @Volatile
        private var instance: LiftsRepository? = null

        fun getInstance(create: () -> LiftsDatabase): LiftsRepository {
            return instance ?: synchronized(this) {
                instance ?: LiftsRepository(create()).also { instance = it }
            }
        }
    }
}
