package com.tczerwien.lifts.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import com.tczerwien.lifts.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Repository-backed manager for the active workout session: creates sessions from
 * templates, logs sets (PR detection + rest-duration selection), and owns the persisted
 * rest-timer state.
 *
 * All non-suspend methods are blocking — call from Dispatchers.IO. The rest timer is a
 * wall-clock end timestamp in DataStore ("lifts_"-prefixed keys, shared file with SDK
 * push keys); remaining time is always derived via [restRemainingSec], never counted.
 */
class ActiveSession(
    private val repository: LiftsRepository,
    private val settings: SettingsStore,
    private val dataStore: DataStore<Preferences>,
) {

    // --- Session lifecycle ---

    /**
     * Creates a workout from [templateId]: one workout_exercises row per template slot
     * (copying rest overrides), each prefilled with targetSets pending "normal" sets.
     * A vanished template degrades to an empty workout. All rows are written in one transaction.
     * A rest left running from an earlier workout is cleared, so the new one never inherits it.
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
        repository.exercisesForTemplate(templateId).forEachIndexed { index, templateExercise ->
            val slotId = repository.addWorkoutExercise(
                WorkoutExerciseEntity(
                    workoutId = workoutId,
                    exerciseId = templateExercise.exerciseId,
                    position = index,
                    restSecWork = templateExercise.restSecWork,
                    restSecWarmup = templateExercise.restSecWarmup,
                ),
            )
            templateSetTypes(templateExercise).forEachIndexed { setIndex, type ->
                repository.addSet(SetEntity(workoutExerciseId = slotId, position = setIndex, type = type))
            }
        }
        return workoutId
    }

    /** Creates an empty workout; like [startFromTemplate] it clears any leftover rest. */
    fun startEmpty(now: Long = System.currentTimeMillis()): Long {
        val workoutId = repository.addWorkout(WorkoutEntity(name = DEFAULT_WORKOUT_NAME, startedAt = now))
        runBlocking { clearRest() }
        return workoutId
    }

    /**
     * Appends an exercise slot. Pending sets are prefilled to mirror the previous
     * session's completed sets (same count, warm-ups kept as warm-ups) so the
     * previous-per-index columns line up; with no history, [DEFAULT_NEW_EXERCISE_SETS]
     * normal sets. The slot and its sets are written in one transaction.
     */
    fun addExercise(workoutId: Long, exerciseId: String): Long = repository.inTransaction {
        val position = repository.exercisesForWorkout(workoutId)
            .maxOfOrNull { it.position + 1 } ?: 0
        val slotId = repository.addWorkoutExercise(
            WorkoutExerciseEntity(workoutId = workoutId, exerciseId = exerciseId, position = position),
        )
        val previous = repository.previousPerformance(exerciseId)
        val prefillTypes = if (previous.isNotEmpty()) {
            previous.map { if (it.type == SET_TYPE_WARMUP) SET_TYPE_WARMUP else SET_TYPE_NORMAL }
        } else {
            List(DEFAULT_NEW_EXERCISE_SETS) { SET_TYPE_NORMAL }
        }
        prefillTypes.forEachIndexed { index, type ->
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

    fun updateSet(set: SetEntity): Boolean = repository.updateSet(set)

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

    fun uncompleteSet(set: SetEntity): Boolean = repository.updateSet(set.copy(completedAt = null))

    /**
     * Result of logging a set. [restEndMillis] is the persisted wall-clock rest end, null when no
     * rest started because the workout has already ended.
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
        repository.updateSet(completed)

        val snapshot = settings.snapshot()
        val restSec = restDurationSec(
            setType = type,
            restSecWork = slot?.restSecWork,
            restSecWarmup = slot?.restSecWarmup,
            defaultRestSec = snapshot.defaultRestSec,
            warmupRestSec = snapshot.warmupRestSec,
        )
        val workoutOpen = repository.workoutById(workoutId)?.endedAt == null
        if (workoutOpen) runBlocking { startRest(restSec, now) }
        return SetCompletion(
            set = completed,
            restDurationSec = restSec,
            restEndMillis = if (workoutOpen) now + restSec * 1000L else null,
        )
    }

    // --- Lookups ---

    /** Completed sets of the most recent finished workout containing [exerciseId], by position. */
    fun previousPerformance(exerciseId: String): List<SetEntity> =
        repository.previousPerformance(exerciseId)

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

    /** When [workout] was last used: its latest logged set, else its start. */
    fun lastActivity(workout: WorkoutEntity): Long =
        lastActivityMillis(workout.startedAt, repository.setsForWorkout(workout.id))

    /** [isUntouched] for a stored workout, against the template it came from as it is now. */
    private fun untouched(workout: WorkoutEntity, sets: List<SetEntity>): Boolean = isUntouched(
        workout = workout,
        slots = repository.exercisesForWorkout(workout.id),
        sets = sets,
        templateExercises = workout.templateId?.let { repository.exercisesForTemplate(it) },
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

    // --- Rest timer (persisted wall-clock end) ---

    /** Persisted rest-timer state; remaining is derived via [restRemainingSec]. */
    data class RestTimerState(val endMillis: Long, val totalSec: Int)

    /** Blocking read — call from Dispatchers.IO. Null when no timer was ever started. */
    fun restStateSnapshot(): RestTimerState? = runBlocking {
        val prefs = dataStore.data.first()
        val endMillis = prefs[REST_END_MILLIS] ?: return@runBlocking null
        RestTimerState(endMillis = endMillis, totalSec = prefs[REST_TOTAL_SEC] ?: 0)
    }

    suspend fun startRest(totalSec: Int, now: Long = System.currentTimeMillis()) {
        val clamped = totalSec.coerceAtLeast(0)
        dataStore.edit { prefs ->
            prefs[REST_END_MILLIS] = now + clamped * 1000L
            prefs[REST_TOTAL_SEC] = clamped
        }
    }

    /**
     * Shifts the persisted end by [deltaSec]; the end never moves before [now] (an
     * over-subtraction just finishes the rest). Returns the new end, null when no timer
     * is persisted.
     */
    suspend fun adjustRest(deltaSec: Int, now: Long = System.currentTimeMillis()): Long? {
        var newEnd: Long? = null
        dataStore.edit { prefs ->
            val endMillis = prefs[REST_END_MILLIS] ?: return@edit
            val adjusted = (endMillis + deltaSec * 1000L).coerceAtLeast(now)
            prefs[REST_END_MILLIS] = adjusted
            newEnd = adjusted
        }
        return newEnd
    }

    suspend fun clearRest() {
        dataStore.edit { prefs ->
            prefs.remove(REST_END_MILLIS)
            prefs.remove(REST_TOTAL_SEC)
        }
    }

    companion object {
        const val DEFAULT_WORKOUT_NAME = "Workout"
        const val DEFAULT_NEW_EXERCISE_SETS = 3

        private val REST_END_MILLIS = longPreferencesKey("lifts_rest_end_millis")
        private val REST_TOTAL_SEC = intPreferencesKey("lifts_rest_total_sec")
    }
}
