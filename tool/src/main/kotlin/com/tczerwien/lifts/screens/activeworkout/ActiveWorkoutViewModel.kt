package com.tczerwien.lifts.screens.activeworkout

import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import com.tczerwien.lifts.session.ActiveSession
import com.tczerwien.lifts.session.ActiveSession.FinishOutcome
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.session.SET_TYPE_FAILURE
import com.tczerwien.lifts.session.SET_TYPE_NORMAL
import com.tczerwien.lifts.session.RowValues
import com.tczerwien.lifts.session.SET_TYPE_WARMUP
import com.tczerwien.lifts.session.alignPrevious
import com.tczerwien.lifts.session.nextPendingSet
import com.tczerwien.lifts.session.prSetIds
import com.tczerwien.lifts.session.previousForSlots
import com.tczerwien.lifts.session.seedRows
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.ui.ArmedAction
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How long "SET WEIGHT OR REPS FIRST" shows after a tick on a row with nothing to log. */
private const val EMPTY_TICK_HINT_MILLIS = 2_500L

class ActiveWorkoutViewModel(
    private val repository: LiftsRepository,
    private val session: ActiveSession,
    private val settingsStore: SettingsStore,
    private val resumeWorkoutId: Long?,
    private val templateId: Long?,
) : LightViewModel<Unit>() {

    /** One exercise of the session: its rows, and what last session did in the matching slot. */
    data class ExerciseBlock(
        val slot: WorkoutExerciseEntity,
        val name: String,
        val baseName: String,
        val detailLine: String,
        val cue: String?,
        /** Today's rows in position order. */
        val sets: List<SetEntity>,
        /** Last session's sets for this slot ([previousForSlots]). */
        val lastTime: List<SetEntity>,
        /** Display unit; starting weights compare as they show ([seedRows]). */
        val unit: WeightUnit,
    ) {
        /** PREVIOUS for each row, paired by kind ([alignPrevious]), parallel to [sets]. */
        val previous: List<SetEntity?> = alignPrevious(sets, lastTime)

        /** What each row logs if ticked as it stands ([seedRows]), parallel to [sets]. */
        val seeds: List<RowValues> = seedRows(sets, previous, unit)
    }

    data class UiState(
        val loaded: Boolean = false,
        val workoutId: Long = 0,
        val workoutName: String = "",
        val startedAt: Long = 0,
        val exercises: List<ExerciseBlock> = emptyList(),
        val currentIndex: Int = 0,
        val activeSetId: Long? = null,
        val prSetIds: Set<Long> = emptySet(),
        val restEndMillis: Long? = null,
        val restTotalSec: Int = 0,
        val units: WeightUnit = SettingsStore.DEFAULT_UNITS,
        val defaultRestSec: Int = SettingsStore.DEFAULT_REST_SEC_VALUE,
        val warmupRestSec: Int = SettingsStore.DEFAULT_WARMUP_REST_SEC_VALUE,
        /**
         * Finish or Discard confirmed and in flight (or Workout Complete is showing): every other tap
         * is ignored and the set list is hidden, so the finished workout never flashes on screen.
         */
        val finishing: Boolean = false,
        /** Finish flow: non-null asks Content to navigate to Workout Complete once. */
        val pendingCompleteWorkoutId: Long? = null,
        /** Workout Complete is on top of this screen. */
        val completeOpen: Boolean = false,
        /** Nothing left to show (discarded, finished, or gone): Content leaves the screen. */
        val leave: Boolean = false,
        /** A tick on a row with nothing to log: "SET WEIGHT OR REPS FIRST" shows for a moment. */
        val emptyTickHint: Boolean = false,
    )

    /**
     * What the "…" menu can offer for the current exercise and row. The ids travel with the menu, so
     * its result acts on exactly what it showed even if the screen moved on meanwhile.
     */
    data class MenuOptions(
        /** The active row while it is unticked, named as its marker shows it ("Set 2", "Warm-up"). */
        val removeSetLabel: String? = null,
        val removeSetId: Long? = null,
        /** The current exercise's name while none of its sets is ticked. */
        val removeExerciseLabel: String? = null,
        val removeSlotId: Long? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    @Volatile
    private var resolvedWorkoutId: Long? = null

    /**
     * Per exercise, from other workouts, fetched once for the life of this screen: no other workout
     * can change while this one is open. [lastTimeByExercise] is previousPerformance (for PREVIOUS
     * and starting values); [priorHistoryByExercise] is every completed set (for PR stars). With
     * these cached, a reload is four queries, not a scan of the whole history.
     */
    @Volatile
    private var lastTimeByExercise: Map<String, List<List<SetEntity>>> = emptyMap()

    @Volatile
    private var priorHistoryByExercise: Map<String, List<SetEntity>> = emptyMap()

    private var emptyTickHintJob: Job? = null

    /** Top-bar Finish takes two taps; see [tapFinish]. */
    val finishArm = ArmedAction(viewModelScope)

    @Volatile
    private var started = false

    /** Everything loads here, not in init: the SDK calls this straight after creating the view model. */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        if (!started) {
            started = true
            start()
            return
        }
        if (_uiState.value.completeOpen) {
            // Back from Workout Complete. Its result (DONE or RESUME) is delivered right after this
            // returns; with none (the system Back) the workout stays finished and this screen leaves.
            viewModelScope.launch(Dispatchers.Main) {
                if (_uiState.value.completeOpen) _uiState.update { it.copy(completeOpen = false, leave = true) }
            }
            return
        }
        // Back after a while (the phone may have slept on this screen overnight): a workout idle past
        // four hours closes at its last logged set, as Home would close it, and refresh then leaves.
        // Otherwise a late tick in the morning would make it look active and Finish would stamp now.
        LiftsIo.write({ resolvedWorkoutId?.let { session.closeIfStale(it) } }) { refresh() }
    }

    /**
     * Creates (or resumes) the workout on the write lane, so it is ordered with every other write:
     * a discard queued just before on Home lands first, and the untouched check in [onCleared] after.
     */
    private fun start() {
        LiftsIo.write({
            resolvedWorkoutId = when {
                resumeWorkoutId != null -> resumeWorkoutId
                templateId != null -> session.startFromTemplate(templateId)
                else -> session.startEmpty()
            }
        }) { refresh() }
    }

    /** Backing out of a workout nothing was done in leaves nothing behind (a template opened by mistake). */
    override fun onCleared() {
        if (!_uiState.value.finishing) {
            LiftsIo.write { resolvedWorkoutId?.let { session.discardIfUntouched(it) } }
        }
        super.onCleared()
    }

    /** Back cancels an armed Finish before it leaves the screen. */
    override fun onBackPressed(): Boolean {
        val state = _uiState.value
        if (state.finishing || state.leave || !finishArm.armed.value) return false
        finishArm.disarm()
        return true
    }

    /** True once Finish or Discard is under way: taps that would change the workout are ignored. */
    private val locked: Boolean
        get() = _uiState.value.finishing

    // --- Row interactions ---

    /** A tap on a row targets it with the steppers; tapping the active row again does nothing. */
    fun onRowTapped(setId: Long) {
        if (locked) return
        if (_uiState.value.activeSetId == setId) return
        _uiState.update { it.copy(activeSetId = setId) }
    }

    /** A tap on the set marker cycles the set type (normal → warm-up → failure) and targets the row. */
    fun onMarkerTapped(setId: Long) {
        if (locked) return
        val edit = applySetChange(setId) { it.copy(type = nextType(it.type)) } ?: return
        _uiState.update { it.copy(activeSetId = setId) }
        persistSet(edit)
    }

    /**
     * Tap on PREVIOUS: copy last session's exact weight/reps into the row and target it. A logged
     * set is left alone, so a stray tap can't overwrite what was lifted.
     */
    fun autofill(setId: Long) {
        if (locked) return
        val located = locate(setId) ?: return
        if (located.block.sets[located.index].completedAt != null) return
        val previous = located.block.previous.getOrNull(located.index) ?: return
        if (previous.weightKg == null && previous.reps == null) return
        val edit = applySetChange(setId) {
            it.copy(weightKg = previous.weightKg ?: it.weightKg, reps = previous.reps ?: it.reps)
        } ?: return
        _uiState.update { it.copy(activeSetId = setId) }
        persistSet(edit)
    }

    /**
     * Checkbox toggle. Completing logs exactly what the row shows: its starting value ([seedRows])
     * wherever it has none of its own. A row with nothing to log shows a hint instead. Completing
     * advances the active row and starts rest per the completion result. Uncompleting keeps the
     * logged values.
     */
    fun toggleComplete(setId: Long) {
        if (locked) return
        val located = locate(setId) ?: return
        val set = located.block.sets[located.index]
        if (set.completedAt != null) {
            val edit = applySetChange(setId) { it.copy(completedAt = null) } ?: return
            LiftsIo.write({ session.uncompleteSet(set) }) { settle(edit) }
            return
        }
        val seed = located.block.seeds[located.index]
        if (seed.weightKg == null && seed.reps == null) {
            showEmptyTickHint()
            return
        }
        val now = System.currentTimeMillis()
        val edit = applySetChange(setId) { it.copy(weightKg = seed.weightKg, reps = seed.reps, completedAt = now) } ?: return
        _uiState.update { state ->
            val block = state.exercises.firstOrNull { it.slot.id == set.workoutExerciseId }
            state.copy(activeSetId = block?.sets?.firstOrNull { it.completedAt == null }?.id)
        }
        val workoutId = _uiState.value.workoutId
        LiftsIo.write({ session.completeSet(workoutId, set, seed.weightKg, seed.reps, set.type, now) }) { completion ->
            synchronized(refreshLock) {
                edit.savedAt = refreshesStarted
                restSetAt = refreshesStarted
                _uiState.update {
                    it.copy(restEndMillis = completion.restEndMillis, restTotalSec = completion.restDurationSec)
                }
            }
        }
    }

    /** "SET WEIGHT OR REPS FIRST" for a couple of seconds, instead of a tick that does nothing. */
    private fun showEmptyTickHint() {
        emptyTickHintJob?.cancel()
        _uiState.update { it.copy(emptyTickHint = true) }
        emptyTickHintJob = viewModelScope.launch {
            delay(EMPTY_TICK_HINT_MILLIS)
            _uiState.update { it.copy(emptyTickHint = false) }
        }
    }

    // --- Steppers (operate on the active row) ---

    fun stepWeight(direction: Int) {
        if (locked) return
        val state = _uiState.value
        val set = activeSet(state) ?: return
        val base = currentWeightDisplay(state, set)
        val next = Format.roundWeight(
            (base + direction * state.units.weightStep).coerceAtLeast(0.0),
        )
        val edit = applySetChange(set.id) {
            it.copy(weightKg = Format.displayToKg(next, state.units))
        } ?: return
        persistSet(edit)
    }

    fun stepReps(direction: Int) {
        if (locked) return
        val state = _uiState.value
        val set = activeSet(state) ?: return
        val next = (currentReps(set) + direction).coerceAtLeast(0)
        val edit = applySetChange(set.id) { it.copy(reps = next) } ?: return
        persistSet(edit)
    }

    fun weightDecrementEnabled(state: UiState): Boolean {
        val set = activeSet(state) ?: return false
        return currentWeightDisplay(state, set) > 0.0
    }

    fun repsDecrementEnabled(state: UiState): Boolean {
        val set = activeSet(state) ?: return false
        return currentReps(set) > 0
    }

    fun activeSet(state: UiState): SetEntity? {
        val id = state.activeSetId ?: return null
        return state.exercises.getOrNull(state.currentIndex)?.sets?.firstOrNull { it.id == id }
    }

    /** The active row's weight in display units as it shows: its own, else its starting value, else 0. */
    private fun currentWeightDisplay(state: UiState, set: SetEntity): Double {
        val located = locate(set.id)
        val kg = set.weightKg ?: located?.block?.seeds?.getOrNull(located.index)?.weightKg ?: return 0.0
        return Format.roundWeight(Format.kgToDisplay(kg, state.units))
    }

    /** The active row's reps as they show: its own, else its starting value, else 0. */
    private fun currentReps(set: SetEntity): Int {
        val located = locate(set.id)
        return set.reps ?: located?.block?.seeds?.getOrNull(located.index)?.reps ?: 0
    }

    // --- Structure ---

    /** Collapsed next-exercise row: advance (wrapping), targeting its first pending set. */
    fun advanceExercise() {
        if (locked) return
        _uiState.update { state ->
            if (state.exercises.size < 2) {
                state
            } else {
                val next = (state.currentIndex + 1) % state.exercises.size
                state.copy(
                    currentIndex = next,
                    activeSetId = state.exercises[next].sets.firstOrNull { it.completedAt == null }?.id,
                )
            }
        }
    }

    fun addSet() {
        if (locked) return
        val state = _uiState.value
        val block = state.exercises.getOrNull(state.currentIndex) ?: return
        LiftsIo.write({ session.addSet(state.workoutId, block.slot.id) }) { refresh() }
    }

    /** Result of the exercise picker: append the exercise and jump to it. */
    fun addExercise(exerciseId: String) {
        if (locked) return
        val workoutId = resolvedWorkoutId ?: return
        LiftsIo.write({ session.addExercise(workoutId, exerciseId) }) { refresh(focusLastExercise = true) }
    }

    // --- Finish, discard and the "…" menu ---

    /**
     * Top-bar Finish. The first tap arms it and the screen says how many sets are still unticked; a
     * second tap 0.4 to 3 s later confirms, so a double-tap can't finish by accident.
     */
    fun tapFinish() {
        if (locked || !_uiState.value.loaded) return
        if (finishArm.tap()) finish()
    }

    /** Ends the workout and opens Workout Complete, or discards it when nothing was logged. */
    private fun finish() {
        val workoutId = resolvedWorkoutId ?: return
        _uiState.update { it.copy(finishing = true) }
        LiftsIo.write({ session.finishOrDiscard(workoutId) }) { outcome ->
            _uiState.update {
                when (outcome) {
                    FinishOutcome.ENDED -> it.copy(
                        pendingCompleteWorkoutId = workoutId,
                        restEndMillis = null,
                        restTotalSec = 0,
                    )
                    FinishOutcome.DISCARDED, FinishOutcome.MISSING -> it.copy(leave = true)
                }
            }
        }
    }

    fun consumePendingComplete() {
        _uiState.update { it.copy(pendingCompleteWorkoutId = null, completeOpen = true) }
    }

    /** RESUME on Workout Complete: the workout is open again and logging carries on. */
    fun resumeAfterComplete() {
        val workoutId = resolvedWorkoutId ?: return
        _uiState.update { it.copy(completeOpen = false) }
        LiftsIo.write({ session.reopenWorkout(workoutId) }) {
            refresh()
            _uiState.update { it.copy(finishing = false) }
        }
    }

    fun menuOptions(state: UiState): MenuOptions {
        val block = state.exercises.getOrNull(state.currentIndex) ?: return MenuOptions()
        val activeIndex = block.sets.indexOfFirst { it.id == state.activeSetId }
        val removableSet = block.sets.getOrNull(activeIndex)?.takeIf { it.completedAt == null }
        val exerciseRemovable = block.sets.none { it.completedAt != null }
        return MenuOptions(
            removeSetLabel = removableSet?.let {
                if (it.type == SET_TYPE_WARMUP) "Warm-up" else "Set ${buildMarkers(block.sets)[activeIndex]}"
            },
            removeSetId = removableSet?.id,
            removeExerciseLabel = block.baseName.takeIf { exerciseRemovable },
            removeSlotId = block.slot.id.takeIf { exerciseRemovable },
        )
    }

    /** Menu: removes the set it offered, only while it is unticked (the session checks again). */
    fun removeSet(setId: Long) {
        if (locked) return
        val located = locate(setId) ?: return
        if (located.block.sets[located.index].completedAt != null) return
        LiftsIo.write({ session.removeSet(setId) }) { refresh() }
    }

    /** Menu: removes the exercise it offered, only while none of its sets is ticked. */
    fun removeExercise(slotId: Long) {
        if (locked) return
        val workoutId = resolvedWorkoutId ?: return
        val block = _uiState.value.exercises.firstOrNull { it.slot.id == slotId } ?: return
        if (block.sets.any { it.completedAt != null }) return
        LiftsIo.write({ session.removeExercise(workoutId, slotId) }) { refresh() }
    }

    /** Menu: deletes the whole workout (the menu already took two taps) and leaves. */
    fun discard() {
        val workoutId = resolvedWorkoutId ?: return
        if (locked) return
        finishArm.disarm()
        _uiState.update { it.copy(finishing = true) }
        LiftsIo.write({ session.discardWorkout(workoutId) }) { _uiState.update { it.copy(leave = true) } }
    }

    // --- Internals ---

    private data class LocatedSet(val block: ExerciseBlock, val index: Int)

    private fun locate(setId: Long): LocatedSet? {
        for (block in _uiState.value.exercises) {
            val index = block.sets.indexOfFirst { it.id == setId }
            if (index >= 0) return LocatedSet(block, index)
        }
        return null
    }

    /**
     * A change to one set that is on screen but may not be in every reload yet. A reload that read
     * the database before this change was saved puts [set]'s values back over what it read, so it
     * can't undo a tap made while it ran. Guarded by [refreshLock].
     */
    private class PendingEdit(val set: SetEntity) {
        /** [refreshesStarted] when the save landed; reloads numbered above it read the saved values. */
        var savedAt: Long? = null
    }

    /** The newest unsettled change per set id. Guarded by [refreshLock]. */
    private val pendingEdits = HashMap<Long, PendingEdit>()

    /**
     * Applies [transform] to one set on screen, re-replays PR flags, and records the change as
     * pending until its save lands ([settle]). Returns null when the set isn't on screen.
     */
    private fun applySetChange(setId: Long, transform: (SetEntity) -> SetEntity): PendingEdit? =
        synchronized(refreshLock) {
            var changed: SetEntity? = null
            _uiState.update { state ->
                val exercises = state.exercises.map { block ->
                    val index = block.sets.indexOfFirst { it.id == setId }
                    if (index < 0) {
                        block
                    } else {
                        val updated = transform(block.sets[index])
                        changed = updated
                        block.copy(sets = block.sets.mapIndexed { i, s -> if (i == index) updated else s })
                    }
                }
                state.copy(
                    exercises = exercises,
                    prSetIds = prSetIds(
                        slots = exercises.map { it.slot },
                        workoutSets = exercises.flatMap { it.sets },
                        priorHistory = priorHistoryByExercise,
                    ),
                )
            }
            changed?.let { PendingEdit(it).also { edit -> pendingEdits[setId] = edit } }
        }

    /** Marks [edit]'s save as landed: reloads that start from now on read it from the database. */
    private fun settle(edit: PendingEdit) {
        synchronized(refreshLock) { edit.savedAt = refreshesStarted }
    }

    /** Saves [edit] on the app-wide write queue, so pressing Back right after a tap can't drop it. */
    private fun persistSet(edit: PendingEdit) {
        LiftsIo.write({ session.updateSet(edit.set) }) { settle(edit) }
    }

    private fun nextType(type: String): String = when (type) {
        SET_TYPE_WARMUP -> SET_TYPE_FAILURE
        SET_TYPE_FAILURE -> SET_TYPE_NORMAL
        else -> SET_TYPE_WARMUP
    }

    /** Guards [refreshesStarted], [refreshApplied], [restSetAt] and [pendingEdits]. */
    private val refreshLock = Any()
    private var refreshesStarted = 0L

    /** The newest refresh whose result is on screen. */
    private var refreshApplied = 0L

    /** [refreshesStarted] when a tick last put its rest on screen; older reloads keep that rest. */
    private var restSetAt = 0L

    /**
     * Runs [apply] for refresh number [ticket] unless one that started later has already landed. A
     * return from the menu or picker starts two refreshes (onScreenShow's and the action's); the
     * older one must not put back a set the action just removed.
     */
    private fun applyRefresh(ticket: Long, apply: () -> Unit) {
        synchronized(refreshLock) {
            if (ticket < refreshApplied) return
            refreshApplied = ticket
            apply()
        }
    }

    /**
     * [loaded] sets with every pending change a reload numbered [ticket] may have missed put back
     * on top (values only; the reload's positions stand). Changes the reload read from the database
     * are settled and dropped. Call under [refreshLock].
     */
    private fun overlayPendingEdits(ticket: Long, loaded: List<SetEntity>): List<SetEntity> {
        val ids = loaded.mapTo(HashSet()) { it.id }
        pendingEdits.entries.removeAll { (id, edit) -> id !in ids || edit.savedAt?.let { ticket > it } == true }
        if (pendingEdits.isEmpty()) return loaded
        return loaded.map { set ->
            val local = pendingEdits[set.id]?.set ?: return@map set
            set.copy(
                type = local.type,
                weightKg = local.weightKg,
                reps = local.reps,
                durationSec = local.durationSec,
                rpe = local.rpe,
                completedAt = local.completedAt,
            )
        }
    }

    /**
     * Last session and PR history for exercises not seen yet on this screen: two queries per new
     * exercise plus one history query for all of them. Merged under [refreshLock], so two reloads
     * running at once can't drop each other's additions.
     */
    private fun cacheHistory(exerciseIds: List<String>, workoutId: Long) {
        val missing = exerciseIds.filterNot { it in lastTimeByExercise }
        if (missing.isEmpty()) return
        val lastTime = session.previousGroupsByExercise(missing)
        val history = session.priorHistory(missing, workoutId)
        synchronized(refreshLock) {
            lastTimeByExercise = lastTimeByExercise + lastTime
            priorHistoryByExercise = priorHistoryByExercise + history
        }
    }

    /**
     * Full reload from the database, call from Dispatchers.IO: the workout, its slots, its sets and
     * their catalog rows (four queries), plus history for any exercise new to this screen.
     */
    private fun refresh(focusLastExercise: Boolean = false) {
        val workoutId = resolvedWorkoutId ?: return
        val ticket = synchronized(refreshLock) { ++refreshesStarted }
        val workout = repository.workoutById(workoutId)
        if (workout == null || workout.endedAt != null) {
            // Gone or finished. Our own Finish or Discard handles its exit; anything else leaves.
            applyRefresh(ticket) {
                _uiState.update { state -> if (state.finishing) state else state.copy(loaded = true, leave = true) }
            }
            return
        }
        val settings = settingsStore.snapshot()
        val slots = repository.exercisesForWorkout(workoutId)
        val loadedSets = repository.setsForWorkout(workoutId)
        val exerciseIds = slots.map { it.exerciseId }.distinct()
        val catalog = repository.exercisesByIds(exerciseIds)
        cacheHistory(exerciseIds, workoutId)
        val lastTimeBySlot = previousForSlots(slots, lastTimeByExercise)
        val restState = session.restStateSnapshot()
        applyRefresh(ticket) {
            val sets = overlayPendingEdits(ticket, loadedSets)
            val setsBySlot = sets.groupBy { it.workoutExerciseId }
            val blocks = slots.map { slot ->
                val exercise = catalog[slot.exerciseId]
                val name = exercise?.name ?: slot.exerciseId
                ExerciseBlock(
                    slot = slot,
                    name = name,
                    baseName = name.substringBefore(" ("),
                    detailLine = detailLine(exercise),
                    cue = exercise?.pinnedNote?.takeIf { it.isNotBlank() },
                    sets = setsBySlot[slot.id].orEmpty().sortedBy { it.position },
                    lastTime = lastTimeBySlot[slot.id].orEmpty(),
                    unit = settings.units,
                )
            }
            val prIds = prSetIds(slots, sets, priorHistoryByExercise)
            // A tick's rest that landed after this reload read the timer stays on screen.
            val keepRest = restSetAt >= ticket
            _uiState.update { state ->
                val lastIndex = (blocks.size - 1).coerceAtLeast(0)
                val currentIndex = when {
                    focusLastExercise -> lastIndex
                    state.loaded -> state.currentIndex.coerceIn(0, lastIndex)
                    else -> initialExerciseIndex(slots, sets, blocks)
                }
                val currentBlock = blocks.getOrNull(currentIndex)
                val activeSetId = state.activeSetId
                    ?.takeIf { id -> currentBlock?.sets?.any { it.id == id } == true }
                    ?: currentBlock?.sets?.firstOrNull { it.completedAt == null }?.id
                state.copy(
                    loaded = true,
                    workoutId = workoutId,
                    workoutName = workout.name,
                    startedAt = workout.startedAt,
                    exercises = blocks,
                    currentIndex = currentIndex,
                    activeSetId = activeSetId,
                    prSetIds = prIds,
                    restEndMillis = if (keepRest) state.restEndMillis else restState?.endMillis,
                    restTotalSec = if (keepRest) state.restTotalSec else restState?.totalSec ?: 0,
                    units = settings.units,
                    defaultRestSec = settings.defaultRestSec,
                    warmupRestSec = settings.warmupRestSec,
                )
            }
        }
    }

    /** On first load (fresh or resumed) open the exercise the lifter is actually on. */
    private fun initialExerciseIndex(
        slots: List<WorkoutExerciseEntity>,
        sets: List<SetEntity>,
        blocks: List<ExerciseBlock>,
    ): Int {
        val next = nextPendingSet(slots, sets) ?: return 0
        return blocks.indexOfFirst { it.slot.id == next.slot.id }.coerceAtLeast(0)
    }

    /** "Barbell · Chest — Triceps, Front Delts" from the catalog entity. */
    private fun detailLine(exercise: ExerciseEntity?): String {
        exercise ?: return ""
        val parts = mutableListOf<String>()
        exercise.equipment?.takeIf { it.isNotBlank() }?.let { parts += titleCase(it) }
        val muscles = buildString {
            append(exercise.primaryMuscles.joinToString(", ") { titleCase(it) })
            if (exercise.secondaryMuscles.isNotEmpty()) {
                if (isNotEmpty()) append(" — ")
                append(exercise.secondaryMuscles.joinToString(", ") { titleCase(it) })
            }
        }
        if (muscles.isNotBlank()) parts += muscles
        return parts.joinToString(" · ")
    }

    private fun titleCase(value: String): String =
        value.split(' ').joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercaseChar() }
        }
}
