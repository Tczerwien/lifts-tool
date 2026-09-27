package com.tczerwien.lifts.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tczerwien.lifts.data.LiftsStore
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import com.tczerwien.lifts.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Store-backed manager for the active workout session: creates sessions from
 * templates, logs sets (PR detection + rest-duration selection), and owns the persisted
 * rest-timer state.
 *
 * All non-suspend methods are blocking — call from Dispatchers.IO. The rest timer is a
 * wall-clock end timestamp kept by [RestStore]; remaining time is always derived via
 * [restRemainingSec], never counted.
 */
class ActiveSession(
    private val repository: LiftsStore,
    private val settings: SettingsStore,
    private val dataStore: DataStore<Preferences>,
) {

    // --- Session lifecycle ---

    /**
     * Creates a workout from [templateId]: one workout_exercises row per template slot (copying rest
     * overrides), each starting with last session's warm-ups for that slot, then targetSets work
     * sets ([planTemplate]), so PREVIOUS lines up warm-up to warm-up. Rows start empty; their
     * starting values are only shown ([seedRows]). A vanished template degrades to an empty
     * workout. All rows are written in one transaction. A rest left running from an earlier
     * workout is cleared, so the new one never inherits it.
     */
    fun startFromTemplate(templateId: Long, now: Long = System.currentTimeMillis()): Long {
        val workoutId = repository.inTransaction { createFromTemplate(templateId, now) }
        runBlocking { clearRest() }
        return workoutId
    }

    private fun createFromTemplate(templateId: Long, now: Long): Long {
        val template = repository.templateById(templateId)
        val workoutId = repository.addWorkout(
            WorkoutEntity(
                name = template?.name ?: DEFAULT_WORKOUT_NAME,
                startedAt = now,
                templateId = template?.id,
            ),
        )
        template ?: return workoutId
        val templateExercises = repository.exercisesForTemplate(templateId).sortedBy { it.position }
        val plan = planTemplate(templateExercises, previousGroupsByExercise(templateExercises.map { it.exerciseId }))
        templateExercises.zip(plan).forEachIndexed { index, (templateExercise, planned) ->
            val slotId = repository.addWorkoutExercise(
                WorkoutExerciseEntity(
                    workoutId = workoutId,
                    exerciseId = templateExercise.exerciseId,
                    position = index,
                    restSecWork = templateExercise.restSecWork,
                    restSecWarmup = templateExercise.restSecWarmup,
                ),
            )
            planned.types.forEachIndexed { setIndex, type ->
                repository.addSet(SetEntity(workoutExerciseId = slotId, position = setIndex, type = type))
            }
        }
        return workoutId
    }

    /**
     * Last session's sets of each of [exerciseIds], one group per slot that session had, empty
     * slots included ([previousGroups]). Two queries per exercise.
     */
    fun previousGroupsByExercise(exerciseIds: Collection<String>): Map<String, List<List<SetEntity>>> =
        exerciseIds.distinct().associateWith { previousGroupsOf(it) }

    private fun previousGroupsOf(exerciseId: String): List<List<SetEntity>> =
        previousGroups(repository.previousPerformance(exerciseId), repository.previousSlotIds(exerciseId))

    /** Creates an empty workout; like [startFromTemplate] it clears any leftover rest. */
    fun startEmpty(now: Long = System.currentTimeMillis()): Long {
        val workoutId = repository.addWorkout(WorkoutEntity(name = DEFAULT_WORKOUT_NAME, startedAt = now))
        runBlocking { clearRest() }
        return workoutId
    }

    /**
     * Appends an exercise slot. Its rows mirror the matching slot from last session: the k-th Bench
     * slot added copies last time's k-th Bench slot (same count, warm-ups kept as warm-ups), so a
     * second Bench gets the back-off sets, not both blocks. With no such slot last time,
     * [DEFAULT_NEW_EXERCISE_SETS] work sets. The slot and its sets are written in one transaction.
     */
    fun addExercise(workoutId: Long, exerciseId: String): Long = repository.inTransaction {
        val slots = repository.exercisesForWorkout(workoutId)
        val position = slots.maxOfOrNull { it.position + 1 } ?: 0
        val occurrence = slots.count { it.exerciseId == exerciseId }
        val slotId = repository.addWorkoutExercise(
            WorkoutExerciseEntity(workoutId = workoutId, exerciseId = exerciseId, position = position),
        )
        val lastTime = previousGroupsOf(exerciseId).getOrElse(occurrence) { emptyList() }
        prefillTypes(lastTime).forEachIndexed { index, type ->
            repository.addSet(SetEntity(workoutExerciseId = slotId, position = index, type = type))
        }
        slotId
    }

    /** Appends one pending "normal" set to [workoutExerciseId]; returns the new set id. */
    fun addSet(workoutId: Long, workoutExerciseId: Long): Long {
        val position = repository.setsForWorkout(workoutId)
            .filter { it.workoutExerciseId == workoutExerciseId }
            .maxOfOrNull { it.position + 1 } ?: 0
        return repository.addSet(
            SetEntity(workoutExerciseId = workoutExerciseId, position = position, type = SET_TYPE_NORMAL),
        )
    }

    /** Saves [set]'s values; see [LiftsStore.updateSetValues]. */
    fun updateSet(set: SetEntity): Boolean = repository.updateSetValues(set)

    /** Removes a set that hasn't been logged; a logged set is left alone. */
    fun removeSet(setId: Long): Boolean = repository.inTransaction {
        val set = repository.setById(setId) ?: return@inTransaction false
        set.completedAt == null && repository.deleteSet(setId)
    }

    /** Removes an exercise slot with its sets, only while none of them is logged. */
    fun removeExercise(workoutId: Long, workoutExerciseId: Long): Boolean = repository.inTransaction {
        val logged = repository.setsForWorkout(workoutId)
            .any { it.workoutExerciseId == workoutExerciseId && it.completedAt != null }
        !logged && repository.deleteSlot(workoutExerciseId)
    }

    fun uncompleteSet(set: SetEntity): Boolean = repository.updateSetValues(set.copy(completedAt = null))

    /**
     * Result of logging a set. [restEndMillis] is the persisted wall-clock rest end, null when no
     * rest started: the workout has already ended or was deleted, or the slot rests 0 s.
     */
    data class SetCompletion(
        val set: SetEntity,
        val restDurationSec: Int,
        val restEndMillis: Long?,
    )

    /**
     * Marks [set] completed with the given values, selects the rest duration (exercise
     * override else settings default; warm-up pair for warm-up sets), and persists the rest
     * end timestamp — the DataStore keys are written on set completion, as the timer contract
     * requires. PR stars are worked out by the screen from [prSetIds].
     */
    fun completeSet(
        workoutId: Long,
        set: SetEntity,
        weightKg: Double?,
        reps: Int?,
        type: String,
        now: Long = System.currentTimeMillis(),
    ): SetCompletion {
        val slots = repository.exercisesForWorkout(workoutId)
        val slot = slots.firstOrNull { it.id == set.workoutExerciseId }
        val completed = set.copy(weightKg = weightKg, reps = reps, type = type, completedAt = now)
        repository.updateSetValues(completed)

        val snapshot = settings.snapshot()
        val restSec = restDurationSec(
            setType = type,
            restSecWork = slot?.restSecWork,
            restSecWarmup = slot?.restSecWarmup,
            defaultRestSec = snapshot.defaultRestSec,
            warmupRestSec = snapshot.warmupRestSec,
        )
        val workoutOpen = repository.workoutById(workoutId)?.let { it.endedAt == null } == true
        // A slot that rests 0 s (a superset's first half) means no rest: the tick still ends any other.
        val rests = workoutOpen && restSec > 0
        if (workoutOpen) runBlocking { if (rests) startRest(restSec, now) else clearRest() }
        return SetCompletion(
            set = completed,
            restDurationSec = restSec,
            restEndMillis = if (rests) now + restSec * 1000L else null,
        )
    }

    // --- Lookups ---

    /**
     * Completed sets of each of [exerciseIds] from workouts other than [excludeWorkoutId], in
     * completion order, keyed by exercise id (exercises never performed map to an empty list).
     * One query, whatever the history length.
     */
    fun priorHistory(exerciseIds: Collection<String>, excludeWorkoutId: Long): Map<String, List<SetEntity>> {
        val found = repository.completedSetsByExercise(exerciseIds, excludeWorkoutId)
        return exerciseIds.associateWith { found[it].orEmpty() }
    }

    // --- Finishing and discarding ---

    enum class FinishOutcome { ENDED, DISCARDED, MISSING }

    /**
     * Finish button: ends the workout (endedAt, idempotent) and clears the rest. A workout with no
     * logged set is discarded instead, so a mistaken start never lands in History. Sets left
     * unticked stay as they are, so a workout reopened from Workout Complete still has them. A
     * workout left idle for hours ends at its last logged set ([finishedAt]). The summary is left to
     * Workout Complete, which is the only screen that shows it.
     */
    fun finishOrDiscard(workoutId: Long, now: Long = System.currentTimeMillis()): FinishOutcome {
        val workout = repository.workoutById(workoutId) ?: return FinishOutcome.MISSING
        val sets = repository.setsForWorkout(workoutId)
        if (completedSetCount(sets) == 0) {
            discardWorkout(workoutId)
            return FinishOutcome.DISCARDED
        }
        if (workout.endedAt == null) {
            repository.updateWorkout(workout.copy(endedAt = finishedAt(workout.startedAt, sets, now)))
        }
        runBlocking { clearRest() }
        return FinishOutcome.ENDED
    }

    /** Deletes the workout with its slots and sets, and clears the rest. */
    fun discardWorkout(workoutId: Long): Boolean {
        val deleted = repository.deleteWorkout(workoutId)
        runBlocking { clearRest() }
        return deleted
    }

    /**
     * Deletes the workout if it is still open and nothing has changed since it was started
     * ([isUntouched]), so opening a template by mistake and backing out leaves no trace.
     */
    fun discardIfUntouched(workoutId: Long): Boolean {
        val workout = repository.workoutById(workoutId) ?: return false
        if (workout.endedAt != null || !untouched(workout, repository.setsForWorkout(workoutId))) return false
        return discardWorkout(workoutId)
    }

    /**
     * Home's cleanup ([planOpenWorkouts]), run whenever Home shows: deletes untouched open workouts,
     * closes stale or superseded ones at their last logged set, and returns the one left open, if
     * any. It never touches the rest timer, which belongs to the newest workout.
     */
    fun tidyOpenWorkouts(now: Long = System.currentTimeMillis()): WorkoutEntity? {
        val open = repository.listOpenWorkouts()
        val setsById = open.associate { it.id to repository.setsForWorkout(it.id) }
        val plan = planOpenWorkouts(
            open.map { workout ->
                val sets = setsById.getValue(workout.id)
                OpenWorkout(workout.id, workout.startedAt, sets, untouched(workout, sets))
            },
            now,
        )
        var kept: WorkoutEntity? = null
        for (workout in open) {
            when (val action = plan.getValue(workout.id)) {
                OpenWorkoutAction.Keep -> kept = workout
                is OpenWorkoutAction.CloseAt -> repository.updateWorkout(workout.copy(endedAt = action.endedAt))
                OpenWorkoutAction.Delete -> repository.deleteWorkout(workout.id)
            }
        }
        return kept
    }

    /**
     * Home's stale rule ([staleAction]) for one open workout, for a lifter who comes back to Active
     * Workout itself hours later: closed at its last logged set, or deleted with nothing logged.
     * Returns true when it closed or deleted the workout.
     */
    fun closeIfStale(workoutId: Long, now: Long = System.currentTimeMillis()): Boolean {
        val workout = repository.workoutById(workoutId)?.takeIf { it.endedAt == null } ?: return false
        return when (val action = staleAction(workout.startedAt, repository.setsForWorkout(workoutId), now)) {
            OpenWorkoutAction.Keep -> false
            is OpenWorkoutAction.CloseAt -> repository.updateWorkout(workout.copy(endedAt = action.endedAt))
            OpenWorkoutAction.Delete -> repository.deleteWorkout(workoutId)
        }
    }

    /** When [workout] was last used: its latest logged set, else its start. */
    fun lastActivity(workout: WorkoutEntity): Long =
        lastActivityMillis(workout.startedAt, repository.setsForWorkout(workout.id))

    /**
     * [isUntouched] for a stored workout, against what its template would start with now
     * ([planTemplate]). Other workouts can't finish while this one is open, so last session is the
     * same as at the start; if it somehow isn't, the workout counts as touched and is kept.
     */
    private fun untouched(workout: WorkoutEntity, sets: List<SetEntity>): Boolean = isUntouched(
        workout = workout,
        slots = repository.exercisesForWorkout(workout.id),
        sets = sets,
        planned = workout.templateId?.let { templateId ->
            val templateExercises = repository.exercisesForTemplate(templateId)
            planTemplate(templateExercises, previousGroupsByExercise(templateExercises.map { it.exerciseId }))
        },
    )

    /** Clears endedAt after a Finish the lifter takes back from Workout Complete. */
    fun reopenWorkout(workoutId: Long): Boolean = repository.reopenWorkout(workoutId)

    /**
     * Summary of [workoutId]: duration, completed-set count (any type), volume
     * (non-warmup), PR records replayed against prior history, and the bodyweight
     * passthrough. Works for already-finished workouts (Workout Complete recomputes
     * from its constructor id).
     */
    fun summarize(workoutId: Long): WorkoutSummary? {
        val workout = repository.workoutById(workoutId) ?: return null
        val slots = repository.exercisesForWorkout(workoutId)
        val sets = repository.setsForWorkout(workoutId)
        val exerciseIds = slots.map { it.exerciseId }.distinct()
        val priorHistory = priorHistory(exerciseIds, workoutId)
        val prIds = prSetIds(slots, sets, priorHistory)
        val names = exerciseIds.associateWith { repository.exerciseById(it)?.name ?: it }
        val end = workout.endedAt ?: workout.startedAt
        return WorkoutSummary(
            workoutId = workoutId,
            name = workout.name,
            durationSec = ((end - workout.startedAt) / 1000L).toInt().coerceAtLeast(0),
            completedSetCount = completedSetCount(sets),
            totalVolumeKg = totalVolumeKg(sets),
            prs = prRecords(slots, sets, prIds, names),
            bodyweightKg = null, // not stored per workout; comes from the bodyweight log later
        )
    }

    // --- Rest timer (persisted wall-clock end, [RestStore]) ---

    private val rest = RestStore(dataStore)

    /**
     * Blocking read — call from Dispatchers.IO. Null when no rest is saved (none yet, or cleared by
     * Skip, Finish, Discard, a 0 s slot or a new workout); an ended rest stays saved until then.
     */
    fun restStateSnapshot(): RestTimerState? = rest.snapshot()

    suspend fun startRest(totalSec: Int, now: Long = System.currentTimeMillis()) = rest.start(totalSec, now)

    /** See [RestStore.adjust]. */
    suspend fun adjustRest(deltaSec: Int, now: Long = System.currentTimeMillis()): Long? = rest.adjust(deltaSec, now)

    /**
     * Saves which exercise Active Workout shows for [workoutId], so reopening after the app was
     * killed lands on it ([openingSlotId]) rather than on the last tick's.
     */
    suspend fun rememberCurrentSlot(workoutId: Long, slotId: Long) {
        dataStore.edit { prefs -> prefs[CURRENT_SLOT] = "$workoutId:$slotId" }
    }

    /** The slot [rememberCurrentSlot] saved for [workoutId], or null. Blocking read. */
    fun currentSlotSnapshot(workoutId: Long): Long? = runBlocking {
        val saved = dataStore.data.first()[CURRENT_SLOT]?.split(':') ?: return@runBlocking null
        if (saved.size != 2 || saved[0].toLongOrNull() != workoutId) return@runBlocking null
        saved[1].toLongOrNull()
    }

    suspend fun clearRest() = rest.clear()

    companion object {
        const val DEFAULT_WORKOUT_NAME = "Workout"

        /** "workoutId:slotId" of the exercise last on screen; tied to its workout, so a stale one is ignored. */
        private val CURRENT_SLOT = stringPreferencesKey("lifts_current_slot")
    }
}
