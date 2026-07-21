package com.tczerwien.lifts.data

/**
 * SDK-independent repository over [LiftsDatabase]. The database instance is injected via the
 * [getInstance] lambda — this layer never constructs a Context or touches the SDK.
 * All methods are blocking; call from Dispatchers.IO.
 */
class LiftsRepository private constructor(
    private val db: LiftsDatabase,
) {
    // --- Exercises ---

    fun exerciseCount(): Long = db.exerciseDao.count()

    fun seedExercises(exercises: List<ExerciseEntity>) = db.exerciseDao.insertAll(exercises)

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

    fun addWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long =
        db.workoutDao.insertWorkoutExercise(workoutExercise)

    fun addSet(set: SetEntity): Long = db.workoutDao.insertSet(set)

    fun updateSet(set: SetEntity): Boolean = db.workoutDao.updateSet(set) > 0

    fun exercisesForWorkout(workoutId: Long): List<WorkoutExerciseEntity> =
        db.workoutDao.exercisesForWorkout(workoutId)

    fun setsForWorkout(workoutId: Long): List<SetEntity> = db.workoutDao.setsForWorkout(workoutId)

    /** Completed sets of the most recent finished workout containing [exerciseId]. */
    fun previousPerformance(exerciseId: String): List<SetEntity> =
        db.workoutDao.previousPerformance(exerciseId)

    // --- Templates ---

    fun addTemplate(template: TemplateEntity): Long = db.templateDao.insert(template)

    fun updateTemplate(template: TemplateEntity): Boolean = db.templateDao.update(template) > 0

    /** Deletes the template and all of its template_exercises rows atomically. */
    fun deleteTemplate(id: Long): Boolean = db.runInTransaction<Boolean> {
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
