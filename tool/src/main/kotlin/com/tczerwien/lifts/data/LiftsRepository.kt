package com.tczerwien.lifts.data

import com.tczerwien.lifts.library.Removal
import com.tczerwien.lifts.library.removalFor
import com.tczerwien.lifts.library.withoutExercise
import com.tczerwien.lifts.seed.Seeder

/**
 * SDK-independent repository over [LiftsDatabase]. The database instance is injected via the
 * [getInstance] lambda — this layer never constructs a Context or touches the SDK.
 * All methods are blocking; call from Dispatchers.IO.
 */
class LiftsRepository private constructor(
    private val db: LiftsDatabase,
) : LiftsStore {
    /**
     * Runs [block] as one database transaction, so a group of writes lands together or not at all:
     * a process killed halfway leaves nothing half-written.
     */
    override fun <T> inTransaction(block: () -> T): T = db.runInTransaction<T>(block)

    // --- First run ---

    /**
     * First run only (the catalog is empty): seeds the bundled exercise catalog and the default bar.
     * Home calls this on every show, so the bar is tied to the catalog; a lifter who deletes every
     * bar doesn't get the default back.
     */
    fun seedIfEmpty() = inTransaction {
        if (db.exerciseDao.count() != 0L) return@inTransaction
        db.exerciseDao.insertAll(Seeder.exercises())
        if (db.barDao.count() == 0L) db.barDao.insert(Seeder.defaultBar())
    }

    // --- Exercises ---

    fun addExercise(exercise: ExerciseEntity) = db.exerciseDao.insert(exercise)

    fun updateExercise(exercise: ExerciseEntity): Boolean = db.exerciseDao.update(exercise) > 0

    fun listExercises(): List<ExerciseEntity> = db.exerciseDao.listAll()

    override fun exerciseById(id: String): ExerciseEntity? = db.exerciseDao.byId(id)

    /** The catalog rows for [ids], keyed by id, in one query; unknown ids are left out. */
    fun exercisesByIds(ids: Collection<String>): Map<String, ExerciseEntity> =
        if (ids.isEmpty()) emptyMap() else db.exerciseDao.byIds(ids.distinct()).associateBy { it.id }

    /**
     * Gives every exercise whose logged sets carry numbers its type hides a type that shows them
     * ([fittedToHistory]), in one transaction; returns how many changed.
     */
    fun fitTrackingToHistory(): Int = inTransaction {
        db.workoutDao.loggedFields().count { logged ->
            val exercise = db.exerciseDao.byId(logged.exerciseId) ?: return@count false
            // Help logged as a plain +40 before the type was set (the seeded band-assisted pull-up) is help.
            if (exercise.tracking == Tracking.ASSISTED_BODYWEIGHT && logged.weighted) {
                return@count db.workoutDao.makeWeightsHelp(exercise.id) > 0
            }
            val fitted = fittedToHistory(exercise.tracking, logged.weighted, logged.hasReps, logged.hasDuration)
                ?: return@count false
            db.exerciseDao.update(exercise.copy(trackingType = fitted.stored)) > 0
        }
    }

    /**
     * Measures [exerciseId] by [tracking] from now on, and its logged weights with it, in one
     * transaction: the sign says which (help is negative), so they keep the size he entered and read
     * as the new type means them. False when nothing changed.
     */
    fun setTracking(exerciseId: String, tracking: Tracking): Boolean = inTransaction {
        val exercise = db.exerciseDao.byId(exerciseId) ?: return@inTransaction false
        if (exercise.trackingType == tracking.stored) return@inTransaction false
        db.exerciseDao.update(exercise.copy(trackingType = tracking.stored))
        when {
            tracking == Tracking.ASSISTED_BODYWEIGHT -> db.workoutDao.makeWeightsHelp(exerciseId)
            tracking.takesWeight -> db.workoutDao.makeWeightsLoad(exerciseId)
        }
        true
    }

    /**
     * Saves his edit of a custom exercise ([edited]'s name, muscles, equipment and category) and measures
     * it by [tracking] ([setTracking], which keeps logged weights' signs in step), in one transaction.
     * False when nothing changed, or it isn't a custom exercise.
     */
    fun updateCustomExercise(edited: ExerciseEntity, tracking: Tracking): Boolean = inTransaction {
        val current = db.exerciseDao.byId(edited.id)?.takeIf { it.isCustom } ?: return@inTransaction false
        val next = current.copy(
            name = edited.name,
            equipment = edited.equipment,
            primaryMuscles = edited.primaryMuscles,
            secondaryMuscles = edited.secondaryMuscles,
            category = edited.category,
        )
        val fields = next != current && db.exerciseDao.update(next) > 0
        setTracking(edited.id, tracking) || fields
    }

    /**
     * Removes custom exercise [id]: first out of every template, the rest of each closing up
     * ([withoutExercise]), then deleted when no workout refers to it, else hidden from the picker so
     * history keeps it ([removalFor]). One transaction, so a workout can't start using it between the
     * check and the delete. Null when it isn't a custom exercise.
     */
    fun removeCustomExercise(id: String): Removal? = inTransaction {
        db.exerciseDao.byId(id)?.takeIf { it.isCustom } ?: return@inTransaction null
        val rows = db.templateDao.templateIdsWith(id).flatMap { db.templateDao.exercisesForTemplate(it) }
        val out = withoutExercise(rows, id)
        out.deleteIds.forEach { db.templateDao.deleteTemplateExercise(it) }
        out.moves.forEach { (row, position) -> db.templateDao.updateTemplateExercise(row.copy(position = position)) }
        when (removalFor(inWorkouts = db.exerciseDao.inWorkouts(id))) {
            Removal.HIDE -> Removal.HIDE.also { db.exerciseDao.setHidden(id, true) }
            Removal.DELETE -> Removal.DELETE.also { db.exerciseDao.deleteCustom(id) }
        }
    }

    /** How many templates ask for exercise [id]: removing it takes it out of them ([removeCustomExercise]). */
    fun templatesWithExercise(id: String): Int = db.templateDao.templateIdsWith(id).size

    /** A workout has exercise [id], ticked or not; one in use without this is only in templates. */
    fun exerciseInWorkouts(id: String): Boolean = db.exerciseDao.inWorkouts(id)

    /** Exercise [id] has a ticked set in a finished workout. */
    fun exerciseHasHistory(id: String): Boolean = db.exerciseDao.hasHistory(id)

    /** Takes a hidden exercise back into the picker (or out); true when it changed. */
    fun setExerciseHidden(id: String, hidden: Boolean): Boolean = db.exerciseDao.setHidden(id, hidden) > 0

    /** Reps (or seconds) of [id] are per side from now on, or not; true when it changed. */
    fun setPerSide(id: String, perSide: Boolean): Boolean = db.exerciseDao.setPerSide(id, perSide) > 0

    /** Every exercise without its instructions, by name: the picker's catalog. */
    fun exerciseSummaries(): List<ExerciseSummary> = db.exerciseDao.listSummaries()

    /** Up to [limit] exercises he has logged, most recently used first ([WorkoutDao.recentExercises]). */
    fun recentExerciseIds(limit: Int): List<String> = db.workoutDao.recentExercises(limit).map { it.exerciseId }

    // --- Workouts ---

    override fun addWorkout(workout: WorkoutEntity): Long = db.workoutDao.insert(workout)

    override fun updateWorkout(workout: WorkoutEntity): Boolean = db.workoutDao.update(workout) > 0

    override fun workoutById(id: Long): WorkoutEntity? = db.workoutDao.byId(id)

    fun listWorkouts(): List<WorkoutEntity> = db.workoutDao.listAll()

    fun listRecentWorkouts(limit: Int): List<WorkoutEntity> = db.workoutDao.listRecent(limit)

    /** Finished workouts, newest first, with their ticked-set counts, in one query. [limit] -1 means all. */
    fun finishedWorkoutsWithSetCounts(limit: Int = -1): List<WorkoutWithSetCount> =
        db.workoutDao.finishedWithSetCounts(limit)

    /** Every exercise slot of every workout, in one query (for export). */
    fun listAllWorkoutExercises(): List<WorkoutExerciseEntity> = db.workoutDao.listAllWorkoutExercises()

    /** Every set of every workout, in one query (for export). */
    fun listAllSets(): List<SetEntity> = db.workoutDao.listAllSets()

    /**
     * Every completed set of [exerciseId] from any workout, with the workout and slot position it
     * belongs to, in completion order, in one query.
     */
    fun completedSetsWithWorkout(exerciseId: String): List<SetWithExercise> =
        db.workoutDao.completedSetsForExercises(listOf(exerciseId), -1)

    /** Workouts still in progress, newest first. Normally at most one; see HomeViewModel's cleanup. */
    override fun listOpenWorkouts(): List<WorkoutEntity> = db.workoutDao.listOpen()

    /**
     * Deletes the workout with all of its exercise slots and sets, in one transaction. The rows
     * are removed child-first, so nothing is left behind whether or not SQLite's foreign-key
     * cascade is switched on.
     */
    override fun deleteWorkout(id: Long): Boolean = inTransaction {
        db.workoutDao.deleteSetsForWorkout(id)
        db.workoutDao.deleteExercisesForWorkout(id)
        db.workoutDao.delete(id) > 0
    }

    /** Clears endedAt, so a workout finished by mistake is in progress again. */
    override fun reopenWorkout(id: Long): Boolean = inTransaction {
        val workout = db.workoutDao.byId(id) ?: return@inTransaction false
        workout.endedAt == null || db.workoutDao.update(workout.copy(endedAt = null)) > 0
    }

    override fun addWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long =
        db.workoutDao.insertWorkoutExercise(workoutExercise)

    override fun addSet(set: SetEntity): Long = db.workoutDao.insertSet(set)

    override fun updateSetValues(set: SetEntity): Boolean = db.workoutDao.updateSetValues(
        id = set.id,
        type = set.type,
        weightKg = set.weightKg,
        reps = set.reps,
        durationSec = set.durationSec,
        rpe = set.rpe,
        completedAt = set.completedAt,
    ) > 0

    override fun setById(id: Long): SetEntity? = db.workoutDao.setById(id)

    /** Deletes one set and renumbers the rest of its exercise slot 0..n-1, in one transaction. */
    override fun deleteSet(id: Long): Boolean = inTransaction {
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
    override fun deleteSlot(id: Long): Boolean = inTransaction {
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

    override fun updateSlotNotes(id: Long, notes: String?): Boolean = db.workoutDao.updateSlotNotes(id, notes) > 0

    override fun updateSlotRest(id: Long, restSecWork: Int?): Boolean = db.workoutDao.updateSlotRest(id, restSecWork) > 0

    override fun exercisesForWorkout(workoutId: Long): List<WorkoutExerciseEntity> =
        db.workoutDao.exercisesForWorkout(workoutId)

    override fun setsForWorkout(workoutId: Long): List<SetEntity> = db.workoutDao.setsForWorkout(workoutId)

    /** Completed sets of the most recent finished workout where [exerciseId] had a completed set. */
    override fun previousPerformance(exerciseId: String): List<SetEntity> =
        db.workoutDao.previousPerformance(exerciseId)

    override fun previousSlotIds(exerciseId: String): List<Long> = db.workoutDao.previousSlotIds(exerciseId)

    /**
     * Completed sets of [exerciseIds] from every workout except [excludeWorkoutId], in
     * completion order, grouped by exercise id. One query, whatever the history length.
     */
    override fun completedSetsByExercise(exerciseIds: Collection<String>, excludeWorkoutId: Long): Map<String, List<SetEntity>> {
        if (exerciseIds.isEmpty()) return emptyMap()
        return db.workoutDao.completedSetsForExercises(exerciseIds.distinct(), excludeWorkoutId)
            .groupBy(keySelector = { it.exerciseId }, valueTransform = { it.set })
    }

    // --- Templates ---

    override fun addTemplate(template: TemplateEntity): Long = db.templateDao.insert(template)

    fun updateTemplate(template: TemplateEntity): Boolean = db.templateDao.update(template) > 0

    /** Deletes the template and all of its template_exercises rows atomically. */
    fun deleteTemplate(id: Long): Boolean = inTransaction {
        db.templateDao.deleteExercisesForTemplate(id)
        db.templateDao.delete(id) > 0
    }

    override fun templateById(id: Long): TemplateEntity? = db.templateDao.byId(id)

    override fun listTemplates(): List<TemplateEntity> = db.templateDao.listAll()

    override fun addTemplateExercise(templateExercise: TemplateExerciseEntity): Long =
        db.templateDao.insertTemplateExercise(templateExercise)

    fun updateTemplateExercise(templateExercise: TemplateExerciseEntity): Boolean =
        db.templateDao.updateTemplateExercise(templateExercise) > 0

    fun deleteTemplateExercise(id: Long): Boolean = db.templateDao.deleteTemplateExercise(id) > 0

    override fun exercisesForTemplate(templateId: Long): List<TemplateExerciseEntity> =
        db.templateDao.exercisesForTemplate(templateId)

    // --- Bodyweight ---

    /** One entry per day; replaces any existing entry for [epochDay]. */
    fun upsertBodyweight(epochDay: Long, weightKg: Double) {
        db.bodyweightDao.upsertByEpochDay(BodyweightEntity(epochDay = epochDay, weightKg = weightKg))
    }

    fun listBodyweight(): List<BodyweightEntity> = db.bodyweightDao.listAll()

    /** Deletes the weigh-in of [epochDay]; true when there was one. */
    fun deleteBodyweight(epochDay: Long): Boolean = db.bodyweightDao.deleteByEpochDay(epochDay) > 0

    override fun bodyweightBetween(fromEpochDay: Long, toEpochDay: Long): List<BodyweightEntity> =
        db.bodyweightDao.between(fromEpochDay, toEpochDay)

    // --- Bars ---

    fun addBar(bar: BarEntity): Long = db.barDao.insert(bar)

    fun updateBar(bar: BarEntity): Boolean = db.barDao.update(bar) > 0

    fun deleteBar(id: Long): Boolean = db.barDao.delete(id) > 0

    fun listBars(): List<BarEntity> = db.barDao.listAll()

    // --- Backup and restore ---

    /** Every row of every table (the phone's side of a restore's summary). */
    fun readAll(): LiftsRows = readAll(db)

    /**
     * Replaces every row of every table with [rows], ids included, in one transaction: a failure (a
     * row that breaks a foreign key, say) leaves the database exactly as it was. Children are
     * removed first and parents written first, so the foreign keys hold at every step.
     */
    fun replaceAll(rows: LiftsRows) = inTransaction {
        with(db.bulkDao) {
            clearSets()
            clearWorkoutExercises()
            clearWorkouts()
            clearTemplateExercises()
            clearTemplates()
            clearBodyweight()
            clearBars()
            clearExercises()
            insertExercises(rows.exercises)
            insertBars(rows.bars)
            insertBodyweight(rows.bodyweight)
            insertTemplates(rows.templates)
            insertTemplateExercises(rows.templateExercises)
            insertWorkouts(rows.workouts)
            insertWorkoutExercises(rows.workoutExercises)
            insertSets(rows.sets)
        }
    }

    /**
     * Writes a copy of the whole database to [path], which must not exist yet (SQLite's VACUUM INTO).
     * The copy comes from one read snapshot, so it is consistent even while other reads run; it is
     * compact and needs no -wal file beside it.
     */
    fun copyTo(path: String) {
        db.openHelper.writableDatabase.execSQL("VACUUM INTO ?", arrayOf(path))
    }

    companion object {
        const val DATABASE_NAME = "lifts.db"

        @Volatile
        private var instance: LiftsRepository? = null

        /** Every row of every table in [db]: the live database, or a backup opened on its own. */
        fun readAll(db: LiftsDatabase): LiftsRows = db.runInTransaction<LiftsRows> {
            with(db.bulkDao) {
                LiftsRows(
                    exercises = exercises(),
                    workouts = workouts(),
                    workoutExercises = workoutExercises(),
                    sets = sets(),
                    templates = templates(),
                    templateExercises = templateExercises(),
                    bodyweight = bodyweight(),
                    bars = bars(),
                )
            }
        }

        fun getInstance(create: () -> LiftsDatabase): LiftsRepository {
            return instance ?: synchronized(this) {
                instance ?: LiftsRepository(create()).also { instance = it }
            }
        }
    }
}
