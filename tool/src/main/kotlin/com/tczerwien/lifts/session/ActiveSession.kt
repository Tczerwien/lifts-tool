package com.tczerwien.lifts.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tczerwien.lifts.analytics.WORKOUT_BODYWEIGHT_LOOKBACK_DAYS
import com.tczerwien.lifts.analytics.workoutBodyweight
import com.tczerwien.lifts.analytics.workoutEpochDay
import com.tczerwien.lifts.data.LiftsStore
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.TemplateEntity
import com.tczerwien.lifts.data.TemplateExerciseEntity
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import com.tczerwien.lifts.data.tracking
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.ui.UNKNOWN_EXERCISE
import com.tczerwien.lifts.ui.displayName
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
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
    /** Told of every rest saved or moved here ([RestAlarm.arm]); the process's own outside tests. */
    private val alarm: RestAlarm = RestAlarm.process,
    /** The zone a new workout's name reads the time of day in: the phone's, a fixed one in tests. */
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    // --- Session lifecycle ---

    /**
     * Creates a workout from [templateId]: one workout_exercises row per template slot (copying rest
     * overrides, the rep range and RIR target, and its superset), each starting with the template's warm-ups, or last
     * session's for that slot when it leaves them to last session, then targetSets work sets
     * ([planTemplate]), so PREVIOUS lines up warm-up to warm-up. Rows start empty; their
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
                name = template?.name ?: defaultWorkoutName(now, zone),
                startedAt = now,
                templateId = template?.id,
            ),
        )
        template ?: return workoutId
        val templateExercises = repository.exercisesForTemplate(templateId).sortedBy { it.position }
        val plan = planTemplate(templateExercises, previousGroupsByExercise(templateExercises.map { it.exerciseId }))
        addPlannedSlots(workoutId, plan)
        return workoutId
    }

    /** Writes [plan]'s slots into [workoutId], in order, with their empty rows: exactly what [isUntouched] expects. */
    private fun addPlannedSlots(workoutId: Long, plan: List<PlannedSlot>) {
        plan.forEachIndexed { index, planned ->
            val slotId = repository.addWorkoutExercise(
                WorkoutExerciseEntity(
                    workoutId = workoutId,
                    exerciseId = planned.exerciseId,
                    position = index,
                    restSecWork = planned.restSecWork,
                    restSecWarmup = planned.restSecWarmup,
                    targetRepMin = planned.targetRepMin,
                    targetRepMax = planned.targetRepMax,
                    targetRir = planned.targetRir,
                    supersetGroup = planned.supersetGroup,
                ),
            )
            planned.types.forEachIndexed { setIndex, type ->
                repository.addSet(SetEntity(workoutExerciseId = slotId, position = setIndex, type = type))
            }
        }
    }

    /**
     * Last session's sets of each of [exerciseIds], one group per slot that session had, empty
     * slots included ([previousGroups]). Two queries per exercise.
     */
    fun previousGroupsByExercise(exerciseIds: Collection<String>): Map<String, List<List<SetEntity>>> =
        exerciseIds.distinct().associateWith { previousGroupsOf(it) }

    private fun previousGroupsOf(exerciseId: String): List<List<SetEntity>> =
        previousGroups(repository.previousPerformance(exerciseId), repository.previousSlotIds(exerciseId))

    /**
     * "Start again" from Workout Detail: a new workout with [sourceId]'s exercises, each with the set
     * pattern he ticked that day, its rests, its targets and its superset ([planFromWorkout]); the rows start empty,
     * as a template's do, and his notes stay with the old workout. Named as the old one was, unless
     * that name was only its time of day ([startAgainName]). Written in one transaction; any leftover
     * rest is cleared, and the source is remembered by the new workout's id, so the untouched check
     * knows what it started as even after the app was killed: it is saved before the rows commit, so a
     * kill in between leaves at worst a source for a workout never saved, never a saved copy without
     * its source (which the check would keep as touched: an empty copy to Resume). Null, starting nothing, when another
     * workout is open (only one ever is) or the source is gone or not finished (an unfinished workout
     * counts nowhere).
     */
    fun startFromWorkout(sourceId: Long, now: Long = System.currentTimeMillis()): Long? {
        val workoutId = repository.inTransaction {
            val source = repository.workoutById(sourceId)?.takeIf { it.endedAt != null } ?: return@inTransaction null
            if (repository.listOpenWorkouts().isNotEmpty()) return@inTransaction null
            val workoutId = repository.addWorkout(WorkoutEntity(name = startAgainName(source, now, zone), startedAt = now))
            addPlannedSlots(workoutId, planOf(source.id))
            runBlocking { dataStore.edit { prefs -> prefs[STARTED_FROM] = "$workoutId:$sourceId" } }
            workoutId
        } ?: return null
        runBlocking { clearRest() }
        return workoutId
    }

    /** The workout [workoutId] was started again from ([startFromWorkout]), or null. Blocking read. */
    private fun startedFromSnapshot(workoutId: Long): Long? = runBlocking {
        val saved = dataStore.data.first()[STARTED_FROM]?.split(':') ?: return@runBlocking null
        if (saved.size != 2 || saved[0].toLongOrNull() != workoutId) return@runBlocking null
        saved[1].toLongOrNull()
    }

    /**
     * What [planFromWorkout] makes of stored workout [workoutId], without the exercises he removed
     * (hidden, kept only for their history): removing one took it out of his templates, so neither a
     * Start Again nor a Save as Template brings it back. A superset it leaves with one slot is none.
     */
    private fun planOf(workoutId: Long): List<PlannedSlot> =
        planFromWorkout(repository.exercisesForWorkout(workoutId), repository.setsForWorkout(workoutId))
            .filterNot { repository.exerciseById(it.exerciseId)?.hidden == true }
            .withNormalizedGroups()

    /**
     * "Save as template" from Workout Detail: a new template named [name] (tidied; blank takes the
     * workout's name), last in the list, with one exercise per slot he ticked a work set in: that many
     * work sets and warm-ups (none ticked leaves them to last session, as a new template exercise does),
     * the slot's rep range and RIR target, its superset, and its rests, stored as none where they equal
     * Settings' defaults so they follow them. Returns the template's id, or null
     * when the workout is gone or not finished (an unfinished workout counts nowhere). One transaction.
     */
    fun saveAsTemplate(workoutId: Long, name: String): Long? = repository.inTransaction {
        val workout = repository.workoutById(workoutId)?.takeIf { it.endedAt != null } ?: return@inTransaction null
        val snapshot = settings.snapshot()
        val templateId = repository.addTemplate(
            TemplateEntity(
                name = name.replace(Regex("\\s+"), " ").trim().ifEmpty { workout.name },
                position = (repository.listTemplates().maxOfOrNull { it.position } ?: -1) + 1,
            ),
        )
        planOf(workoutId).forEachIndexed { index, planned ->
            repository.addTemplateExercise(
                TemplateExerciseEntity(
                    templateId = templateId,
                    exerciseId = planned.exerciseId,
                    position = index,
                    // Within what the Template Editor's steppers reach, so its first − is one set less.
                    targetSets = planned.types.count { it != SET_TYPE_WARMUP }.coerceIn(MIN_TEMPLATE_SETS, MAX_TEMPLATE_SETS),
                    warmupSets = planned.types.count { it == SET_TYPE_WARMUP }.takeIf { it > 0 }?.coerceAtMost(MAX_TEMPLATE_WARMUPS),
                    restSecWork = planned.restSecWork?.let { restOverride(it, snapshot.defaultRestSec) },
                    restSecWarmup = planned.restSecWarmup?.let { restOverride(it, snapshot.warmupRestSec) },
                    repMin = planned.targetRepMin,
                    repMax = planned.targetRepMax,
                    targetRir = planned.targetRir,
                    supersetGroup = planned.supersetGroup,
                ),
            )
        }
        templateId
    }

    /**
     * Creates an empty workout named by the time of day ([defaultWorkoutName]); like
     * [startFromTemplate] it clears any leftover rest.
     */
    fun startEmpty(now: Long = System.currentTimeMillis()): Long {
        // A Start Again killed before its rows committed can leave its source under the id SQLite will
        // hand out again: an empty workout must not take it for its own.
        runBlocking { dataStore.edit { prefs -> prefs.remove(STARTED_FROM) } }
        val workoutId = repository.addWorkout(WorkoutEntity(name = defaultWorkoutName(now, zone), startedAt = now))
        runBlocking { clearRest() }
        return workoutId
    }

    /**
     * Appends an exercise slot. Its rows mirror the matching slot from last session: the k-th Bench
     * slot added copies last time's k-th Bench slot (same count, warm-ups kept as warm-ups), so a
     * second Bench gets the back-off sets, not both blocks. With no such slot last time,
     * [DEFAULT_NEW_EXERCISE_SETS] work sets. The slot and its sets are written in one transaction.
     * Returns null, writing nothing, when the workout is no longer open: the picker can come back
     * after the workout was closed or deleted as stale underneath it, and a slot for a deleted
     * workout would break its foreign key (a crash) or add rows to a finished one.
     */
    fun addExercise(workoutId: Long, exerciseId: String): Long? = repository.inTransaction {
        if (repository.workoutById(workoutId)?.takeIf { it.endedAt == null } == null) return@inTransaction null
        // Picked from a list read before he deleted or removed it: a slot for it would name an
        // exercise that isn't there (or one he took out), and a backup of that log is refused.
        if (repository.exerciseById(exerciseId)?.takeIf { !it.hidden } == null) return@inTransaction null
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

    /**
     * Appends one pending "normal" set to [workoutExerciseId]; returns the new set id, or null, writing
     * nothing, when the workout is no longer open or the slot is gone (removed by a queued "…" action).
     */
    fun addSet(workoutId: Long, workoutExerciseId: Long): Long? = repository.inTransaction {
        if (repository.workoutById(workoutId)?.takeIf { it.endedAt == null } == null) return@inTransaction null
        if (repository.exercisesForWorkout(workoutId).none { it.id == workoutExerciseId }) return@inTransaction null
        val position = repository.setsForWorkout(workoutId)
            .filter { it.workoutExerciseId == workoutExerciseId }
            .maxOfOrNull { it.position + 1 } ?: 0
        repository.addSet(
            SetEntity(workoutExerciseId = workoutExerciseId, position = position, type = SET_TYPE_NORMAL),
        )
    }

    /**
     * Saves [set]'s values ([LiftsStore.updateSetValues]) while [workoutId] is open; false, writing
     * nothing, once it has ended or gone. A tap made just before a stale close landed must not change a
     * finished workout. (A set whose slot was removed is gone with it, so that writes nothing either.)
     */
    fun updateSet(workoutId: Long, set: SetEntity): Boolean = repository.inTransaction {
        isOpen(workoutId) && repository.updateSetValues(set)
    }

    /** [workoutId] exists and hasn't ended: its rows may still change. Call inside a transaction. */
    private fun isOpen(workoutId: Long): Boolean = repository.workoutById(workoutId)?.let { it.endedAt == null } == true

    /**
     * Removes a set that hasn't been logged; a logged set is left alone, and so is a workout that has
     * ended (a removal queued behind a stale close must not take rows RESUME would bring back).
     */
    fun removeSet(workoutId: Long, setId: Long): Boolean = repository.inTransaction {
        if (!isOpen(workoutId)) return@inTransaction false
        val set = repository.setById(setId) ?: return@inTransaction false
        set.completedAt == null && repository.deleteSet(setId)
    }

    /** Removes an exercise slot with its sets, only while none of them is logged and the workout is open. */
    fun removeExercise(workoutId: Long, workoutExerciseId: Long): Boolean = repository.inTransaction {
        if (!isOpen(workoutId)) return@inTransaction false
        val logged = repository.setsForWorkout(workoutId)
            .any { it.workoutExerciseId == workoutExerciseId && it.completedAt != null }
        !logged && repository.deleteSlot(workoutExerciseId)
    }

    // --- The "…" menu's edits (all refused once the workout has closed) ---

    /**
     * Renames [workoutId] while it is open. Spaces are tidied; a blank name, or the name it already
     * has, changes nothing. True when it was renamed.
     */
    fun renameWorkout(workoutId: Long, name: String): Boolean = repository.inTransaction {
        val workout = repository.workoutById(workoutId)?.takeIf { it.endedAt == null } ?: return@inTransaction false
        val tidy = name.replace(Regex("\\s+"), " ").trim()
        tidy.isNotEmpty() && tidy != workout.name && repository.updateWorkout(workout.copy(name = tidy))
    }

    /** The workout's note while it is open; blank clears it. True when it changed. */
    fun setWorkoutNotes(workoutId: Long, text: String): Boolean = repository.inTransaction {
        val workout = repository.workoutById(workoutId)?.takeIf { it.endedAt == null } ?: return@inTransaction false
        val notes = text.trim().ifEmpty { null }
        notes != workout.notes && repository.updateWorkout(workout.copy(notes = notes))
    }

    /**
     * The note on one exercise of the open workout (slot [slotId]; "left shoulder twinge on set 3"),
     * shown in Workout Detail and written to every export; blank clears it. True when it changed.
     */
    fun setSlotNotes(workoutId: Long, slotId: Long, text: String): Boolean = repository.inTransaction {
        val slot = openSlot(workoutId, slotId) ?: return@inTransaction false
        val notes = text.trim().ifEmpty { null }
        notes != slot.notes && repository.updateSlotNotes(slotId, notes)
    }

    /**
     * This exercise's rest for its work sets in the open workout (warm-ups keep theirs), from its next
     * tick on. A rest equal to Settings' default is stored as none, so the slot follows the default if
     * that changes. True when it changed.
     */
    fun setSlotRest(workoutId: Long, slotId: Long, restSec: Int): Boolean = repository.inTransaction {
        val slot = openSlot(workoutId, slotId) ?: return@inTransaction false
        val stored = restOverride(restSec, settings.snapshot().defaultRestSec)
        stored != slot.restSecWork && repository.updateSlotRest(slotId, stored)
    }

    /** Slot [slotId] of [workoutId] while that workout is open; null otherwise. Call inside a transaction. */
    private fun openSlot(workoutId: Long, slotId: Long): WorkoutExerciseEntity? =
        if (isOpen(workoutId)) repository.exercisesForWorkout(workoutId).firstOrNull { it.id == slotId } else null

    /** Unticks [set], keeping its values, while [workoutId] is open ([updateSet]): a finished workout keeps its log. */
    fun uncompleteSet(workoutId: Long, set: SetEntity): Boolean = updateSet(workoutId, set.copy(completedAt = null))

    /**
     * Result of logging a set. [restEndMillis] is the persisted wall-clock rest end, null when no
     * rest started because the slot rests 0 s. (A workout that has ended logs nothing: [completeSet] is null.)
     */
    data class SetCompletion(
        val set: SetEntity,
        val restDurationSec: Int,
        val restEndMillis: Long?,
    )

    /**
     * Marks [set] completed with the given values (a hold's [durationSec]; its RIR stays as the row
     * has it), selects the rest duration (exercise
     * override else settings default; warm-up pair for warm-up sets), and persists the rest
     * end timestamp — the DataStore keys are written on set completion, as the timer contract
     * requires. PR stars are worked out by the screen from [prSetIds]. Null, writing nothing and
     * starting no rest, when the workout has closed (a tick queued behind a stale close) or the
     * set is gone.
     */
    fun completeSet(
        workoutId: Long,
        set: SetEntity,
        weightKg: Double?,
        reps: Int?,
        type: String,
        now: Long = System.currentTimeMillis(),
        durationSec: Int? = null,
    ): SetCompletion? {
        val slots = repository.exercisesForWorkout(workoutId)
        val slot = slots.firstOrNull { it.id == set.workoutExerciseId }
        val completed = set.copy(weightKg = weightKg, reps = reps, durationSec = durationSec, type = type, completedAt = now)
        if (!updateSet(workoutId, completed)) return null

        val snapshot = settings.snapshot()
        val restSec = restDurationSec(
            setType = type,
            restSecWork = slot?.restSecWork,
            restSecWarmup = slot?.restSecWarmup,
            defaultRestSec = snapshot.defaultRestSec,
            warmupRestSec = snapshot.warmupRestSec,
        )
        // The workout is open: the write above checked, and nothing else writes between (one lane).
        // A slot that rests 0 s (a superset's first half) means no rest: the tick still ends any other.
        val rests = restSec > 0
        runBlocking { if (rests) startRest(restSec, now) else clearRest() }
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

    /** What Home's cleanup left: the workout still open, if any, and how many it closed (they are history now). */
    data class Tidied(val open: WorkoutEntity?, val closed: Int)

    /**
     * Home's cleanup ([planOpenWorkouts]), run whenever Home shows: deletes untouched open workouts,
     * closes stale or superseded ones at their last logged set, and returns the one left open, if
     * any. It never touches the rest timer, which belongs to the newest workout.
     */
    fun tidyOpenWorkouts(now: Long = System.currentTimeMillis()): Tidied {
        val open = repository.listOpenWorkouts()
        val setsById = open.associate { it.id to repository.setsForWorkout(it.id) }
        val plan = planOpenWorkouts(
            open.map { workout ->
                val sets = setsById.getValue(workout.id)
                OpenWorkout(workout.id, workout.startedAt, sets, untouched(workout, sets), hasNotes(workout))
            },
            now,
        )
        var kept: WorkoutEntity? = null
        var closed = 0
        for (workout in open) {
            when (val action = plan.getValue(workout.id)) {
                OpenWorkoutAction.Keep -> kept = workout
                is OpenWorkoutAction.CloseAt -> if (repository.updateWorkout(workout.copy(endedAt = action.endedAt))) closed++
                OpenWorkoutAction.Delete -> repository.deleteWorkout(workout.id)
            }
        }
        return Tidied(kept, closed)
    }

    /**
     * Home's stale rule ([staleAction]) for one open workout, for a lifter who comes back to Active
     * Workout itself hours later: closed at its last logged set, or deleted with nothing logged.
     * Returns true when it closed or deleted the workout (a closed one is history now; the caller
     * lets the exports catch up).
     */
    fun closeIfStale(workoutId: Long, now: Long = System.currentTimeMillis()): Boolean {
        val workout = repository.workoutById(workoutId)?.takeIf { it.endedAt == null } ?: return false
        return when (val action = staleAction(workout.startedAt, repository.setsForWorkout(workoutId), now, hasNotes = hasNotes(workout))) {
            OpenWorkoutAction.Keep -> false
            is OpenWorkoutAction.CloseAt -> repository.updateWorkout(workout.copy(endedAt = action.endedAt))
            OpenWorkoutAction.Delete -> repository.deleteWorkout(workoutId)
        }
    }

    /** A note on [workout] or one of its exercises: closing it must keep it ([closeAction]). */
    private fun hasNotes(workout: WorkoutEntity): Boolean =
        !workout.notes.isNullOrBlank() || repository.exercisesForWorkout(workout.id).any { !it.notes.isNullOrBlank() }

    /** When [workout] was last used: its latest logged set, else its start. */
    fun lastActivity(workout: WorkoutEntity): Long =
        lastActivityMillis(workout.startedAt, repository.setsForWorkout(workout.id))

    /**
     * [isUntouched] for a stored workout, against what its template would start with now
     * ([planTemplate]), or the workout it was started again from ([startFromWorkout] remembers it,
     * [planFromWorkout]). Other workouts can't finish while this one is open, so last session is the
     * same as at the start; if it somehow isn't, the workout counts as touched and is kept.
     */
    private fun untouched(workout: WorkoutEntity, sets: List<SetEntity>): Boolean {
        val source = startedFromSnapshot(workout.id)?.takeIf { workout.templateId == null }?.let { repository.workoutById(it) }
        return isUntouched(
            workout = workout,
            slots = repository.exercisesForWorkout(workout.id),
            sets = sets,
            planned = {
                workout.templateId?.let { templateId ->
                    val templateExercises = repository.exercisesForTemplate(templateId)
                    planTemplate(templateExercises, previousGroupsByExercise(templateExercises.map { it.exerciseId }))
                } ?: source?.let { planOf(it.id) }
            },
            // A name or a rest he set from the "…" menu is his: Back must not throw it away.
            startName = workout.templateId?.let { repository.templateById(it)?.name }
                ?: source?.let { startAgainName(it, workout.startedAt, zone) }
                ?: defaultWorkoutName(workout.startedAt, zone),
        )
    }

    /**
     * Clears endedAt after a Finish the lifter takes back from Workout Complete. [rest], the rest that
     * was running when he confirmed Finish (which cleared it), comes back while it hasn't ended, armed
     * as it was, so the REST OVER alert still comes.
     */
    fun reopenWorkout(workoutId: Long, rest: RestTimerState? = null, now: Long = System.currentTimeMillis()): Boolean {
        val reopened = repository.reopenWorkout(workoutId)
        if (reopened && rest != null && rest.endMillis > now) {
            runBlocking { this@ActiveSession.rest.restore(rest) }
            alarm.arm(rest.endMillis, now)
        }
        return reopened
    }

    /**
     * Summary of [workoutId]: duration, completed-set count (any type), volume
     * (non-warmup), PR records replayed against prior history by each exercise's tracking, and the weigh-in that goes with
     * it ([workoutBodyweight], by the local day in [zone]). Works for already-finished workouts
     * (Workout Complete recomputes from its constructor id).
     */
    fun summarize(workoutId: Long, zone: ZoneId = ZoneId.systemDefault()): WorkoutSummary? {
        val workout = repository.workoutById(workoutId) ?: return null
        val slots = repository.exercisesForWorkout(workoutId)
        val sets = repository.setsForWorkout(workoutId)
        val exerciseIds = slots.map { it.exerciseId }.distinct()
        val priorHistory = priorHistory(exerciseIds, workoutId)
        val exercises = exerciseIds.mapNotNull { repository.exerciseById(it) }.associateBy { it.id }
        val tracking = exercises.mapValues { (_, exercise) -> exercise.tracking }
        val prIds = prSetIds(slots, sets, priorHistory, tracking)
        val names = exerciseIds.associateWith { id -> exercises[id]?.let(::displayName) ?: UNKNOWN_EXERCISE }
        val end = workout.endedAt ?: workout.startedAt
        val day = workoutEpochDay(workout.startedAt, zone)
        val weighIns = repository.bodyweightBetween(day - WORKOUT_BODYWEIGHT_LOOKBACK_DAYS, day)
        return WorkoutSummary(
            workoutId = workoutId,
            name = workout.name,
            durationSec = ((end - workout.startedAt) / 1000L).toInt().coerceAtLeast(0),
            completedSetCount = completedSetCount(sets),
            totalVolumeKg = totalVolumeKg(sets),
            prs = prRecords(slots, sets, prIds, names, tracking, perSide = exercises.filterValues { it.perSide }.keys),
            bodyweight = workoutBodyweight(workout.startedAt, weighIns, zone),
        )
    }

    // --- Rest timer (persisted wall-clock end, [RestStore]) ---

    private val rest = RestStore(dataStore)

    /**
     * Blocking read — call from Dispatchers.IO. Null when no rest is saved (none yet, or cleared by
     * Skip, Finish, Discard, a 0 s slot or a new workout); an ended rest stays saved until then.
     */
    fun restStateSnapshot(): RestTimerState? = rest.snapshot()

    /** The saved rest as it changes ([RestStore.state]): what Active Workout's rest slot shows. */
    val restState: Flow<RestTimerState?>
        get() = rest.state

    /** Saves a rest and arms the alarm for it, so a tick followed at once by power still alerts. */
    suspend fun startRest(totalSec: Int, now: Long = System.currentTimeMillis()) {
        alarm.arm(rest.start(totalSec, now), now)
    }

    /** See [RestStore.adjust]; the moved end is armed like a new rest. */
    suspend fun adjustRest(deltaSec: Int, clock: () -> Long = System::currentTimeMillis): Long? =
        rest.adjust(deltaSec, clock)?.also { alarm.arm(it, clock()) }

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
        /**
         * Forgets the in-workout state kept in DataStore: the exercise last on screen, the workout a
         * Start Again copied, and the saved rest. A restore calls it, because the restored database can
         * reuse workout and slot ids, so a saved "workout:slot" or rest would point into the wrong data. The keys are named one by
         * one: settings share the "lifts_" prefix (lifts_rest_beep among them) and must stay.
         */
        suspend fun forgetWorkoutState(dataStore: DataStore<Preferences>) {
            dataStore.edit { prefs ->
                prefs.remove(CURRENT_SLOT)
                prefs.remove(STARTED_FROM)
            }
            RestStore(dataStore).clear()
        }

        /** "workoutId:slotId" of the exercise last on screen; tied to its workout, so a stale one is ignored. */
        private val CURRENT_SLOT = stringPreferencesKey("lifts_current_slot")

        /** "workoutId:sourceId": the workout Start Again copied into that one; tied to it, so a stale one is ignored. */
        private val STARTED_FROM = stringPreferencesKey("lifts_started_from")
    }
}
