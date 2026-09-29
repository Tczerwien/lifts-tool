package com.tczerwien.lifts.screens.activeworkout

import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.Tracking
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import com.tczerwien.lifts.data.tracking
import com.tczerwien.lifts.screens.WorkoutMenuContent
import com.tczerwien.lifts.session.ActiveSession
import com.tczerwien.lifts.session.ActiveSession.FinishOutcome
import com.tczerwien.lifts.session.DURATION_STEP_SEC
import com.tczerwien.lifts.session.HistoryVersion
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.session.RowValues
import com.tczerwien.lifts.session.SET_TYPE_WARMUP
import com.tczerwien.lifts.session.TypedWeight
import com.tczerwien.lifts.session.alignPrevious
import com.tczerwien.lifts.session.autoAdvanceAt
import com.tczerwien.lifts.session.autoAdvanceSlotId
import com.tczerwien.lifts.session.nextExerciseSlotId
import com.tczerwien.lifts.session.nextRir
import com.tczerwien.lifts.session.nextSetType
import com.tczerwien.lifts.session.openingSlotId
import com.tczerwien.lifts.session.parseTypedWeight
import com.tczerwien.lifts.session.prSetIds
import com.tczerwien.lifts.session.previousForSlots
import com.tczerwien.lifts.session.rirOf
import com.tczerwien.lifts.session.rpeOf
import com.tczerwien.lifts.session.seedRows
import com.tczerwien.lifts.session.tapTooSoonAfterMove
import com.tczerwien.lifts.session.isDoubleTap
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.toolmanager.LiftsShare
import com.tczerwien.lifts.ui.ArmedAction
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import com.tczerwien.lifts.ui.displayName
import com.tczerwien.lifts.ui.namesEquipment
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How long "SET WEIGHT OR REPS FIRST" shows after a tick on a row with nothing to log. */
private const val EMPTY_TICK_HINT_MILLIS = 2_500L

class ActiveWorkoutViewModel(
    private val repository: LiftsRepository,
    private val session: ActiveSession,
    private val settingsStore: SettingsStore,
    private val share: LiftsShare,
    private val resumeWorkoutId: Long?,
    private val templateId: Long?,
) : LightViewModel<Unit>() {

    /** One exercise of the session: its rows, and what last session did in the matching slot. */
    data class ExerciseBlock(
        val slot: WorkoutExerciseEntity,
        /** As shown everywhere on this screen ([displayName]): "Bench Press", not "Barbell Bench Press - Medium Grip". */
        val name: String,
        val detailLine: String,
        val cue: String?,
        /** Today's rows in position order. */
        val sets: List<SetEntity>,
        /** Last session's sets for this slot ([previousForSlots]). */
        val lastTime: List<SetEntity>,
        /** Display unit; starting weights compare as they show ([seedRows]). */
        val unit: WeightUnit,
        /** How the exercise is measured: which numbers the rows and steppers take. */
        val tracking: Tracking = Tracking.WEIGHT_REPS,
    ) {
        /** PREVIOUS for each row, paired by kind ([alignPrevious]), parallel to [sets]. */
        val previous: List<SetEntity?> = alignPrevious(sets, lastTime)

        /** What each row logs if ticked as it stands ([seedRows]), parallel to [sets]. */
        val seeds: List<RowValues> = seedRows(sets, previous, unit, tracking)
    }

    data class UiState(
        val loaded: Boolean = false,
        val workoutId: Long = 0,
        val workoutName: String = "",
        val workoutNotes: String? = null,
        val startedAt: Long = 0,
        val exercises: List<ExerciseBlock> = emptyList(),
        val currentIndex: Int = 0,
        val activeSetId: Long? = null,
        val prSetIds: Set<Long> = emptySet(),
        /** The saved rest ([ActiveSession.restState]), kept current as it changes anywhere. */
        val restEndMillis: Long? = null,
        val restTotalSec: Int = 0,
        val units: WeightUnit = SettingsStore.DEFAULT_UNITS,
        /** Settings' weight step in [units], in display units. */
        val weightStep: Double = SettingsStore.DEFAULT_UNITS.defaultWeightStep,
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
        /** A tick on a row with nothing to log: "SET WEIGHT OR REPS FIRST" (or reps, or time) shows for a moment. */
        val emptyTickHint: String? = null,
        /** The weight's number entry, while it is open. */
        val weightEntry: WeightEntry? = null,
    )

    /**
     * The keyboard for one row's weight ([setId]), opened by a tap on the weight between its steppers:
     * for what the steppers make slow (a 118 lb sled, 17.5 lb dumbbells on a 5 lb step). [refused]
     * after text that isn't a weight; [session] starts a fresh editor each time.
     */
    data class WeightEntry(val setId: Long, val prefill: String, val session: Int, val refused: Boolean = false)

    /**
     * What the "…" menu shows ([content]) and the ids its result acts on. The ids travel with the
     * menu, so its result acts on exactly what it showed even if the screen moved on meanwhile.
     */
    data class MenuOptions(
        val content: WorkoutMenuContent,
        /** The active row while it is unticked. */
        val removeSetId: Long? = null,
        /** The current exercise while none of its sets is ticked. */
        val removeSlotId: Long? = null,
        /** The exercise on screen: its slot and catalog id. */
        val slotId: Long? = null,
        val exerciseId: String? = null,
        /** The weight the active row shows, in kg, for Plates; null when there are none to load. */
        val platesKg: Double? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    @Volatile
    private var resolvedWorkoutId: Long? = null

    /**
     * Per exercise, from other workouts, fetched once for the life of this screen: other workouts
     * don't change while this one is open, except by a delete (Workout Detail, reached through
     * Exercise Detail) or a restore, which bump [HistoryVersion] and so empty these.
     * [lastTimeByExercise] is previousPerformance (for PREVIOUS and starting values);
     * [priorHistoryByExercise] is every completed set (for PR stars). With these cached, a reload is
     * four queries, not a scan of the whole history.
     */
    @Volatile
    private var lastTimeByExercise: Map<String, List<List<SetEntity>>> = emptyMap()

    @Volatile
    private var priorHistoryByExercise: Map<String, List<SetEntity>> = emptyMap()

    /** The [HistoryVersion] the two caches above were filled at. */
    @Volatile
    private var cachedHistoryVersion: Long = HistoryVersion.current

    private var emptyTickHintJob: Job? = null

    /** Top-bar Finish takes two taps; see [tapFinish]. */
    val finishArm = ArmedAction(viewModelScope)

    @Volatile
    private var started = false

    /** Everything loads here, not in init: the SDK calls this straight after creating the view model. */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        // Back from another screen (or the screen waking): whatever moved on meanwhile, he sees it now,
        // so his first taps are meant for it ([tapTooSoonAfterMove] is for a move under his finger).
        movedOnAt = null
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
        LiftsIo.write({ resolvedWorkoutId?.let { session.closeIfStale(it) } == true }) { closed ->
            if (closed) share.logChanged()
            refresh()
        }
    }

    /**
     * Creates (or resumes) the workout on the write lane, so it is ordered with every other write:
     * a discard queued just before on Home lands first, and the untouched check in [onCleared] after.
     */
    private fun start() {
        // The rest slot follows the saved rest itself, not a copy taken at the last reload, so a ±30
        // or Skip on the Rest Timer shows the moment he is back, and a tick's rest the moment it saves.
        viewModelScope.launch {
            session.restState.collect { rest ->
                _uiState.update { it.copy(restEndMillis = rest?.endMillis, restTotalSec = rest?.totalSec ?: 0) }
            }
        }
        // A finished exercise moves on as its rest ends; ±30 moves that moment, and Skip brings it now.
        viewModelScope.launch {
            combine(pendingAdvance, session.restState) { pending, rest -> pending to rest?.endMillis }
                .collectLatest { (pending, restEnd) ->
                    if (pending == null || !pending.restSaved) return@collectLatest
                    // restEnd is already the tick's rest here: DataStore updates its data before the save
                    // returns, so the rest emission reaches this collector before restSaved does. If that
                    // ever changes, a save stuck for 2 s could time the move from an older rest.
                    delay((autoAdvanceAt(pending.tickedAt, restEnd) - System.currentTimeMillis()).coerceAtLeast(0L))
                    autoAdvance(pending)
                }
        }
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

    /** Back closes the weight's keyboard, or cancels an armed Finish, before it leaves the screen. */
    override fun onBackPressed(): Boolean {
        val state = _uiState.value
        if (state.weightEntry != null && !state.finishing && !state.leave) {
            closeWeightEntry()
            return true
        }
        if (state.finishing || state.leave || !finishArm.armed.value) return false
        finishArm.disarm()
        return true
    }

    /** True once Finish or Discard is under way: taps that would change the workout are ignored. */
    private val locked: Boolean
        get() = _uiState.value.finishing

    /**
     * Every tap that changes the workout or what is on screen starts here. False once Finish is under
     * way ([locked]). Otherwise it cancels a pending move to the next exercise ([pendingAdvance]): he is
     * still busy with this one.
     */
    private fun beginAction(): Boolean {
        if (locked) return false
        pendingAdvance.value = null
        return true
    }

    /**
     * [beginAction] for a tap on the rows or the next-exercise row, which the screen moving on by itself
     * can change under his finger: in the second after such a move ([tapTooSoonAfterMove]) the tap was
     * aimed at what was there before, so it is dropped rather than applied to the new exercise.
     */
    private fun beginRowAction(): Boolean =
        !tapTooSoonAfterMove(movedOnAt, System.currentTimeMillis()) && beginAction()

    /**
     * He opened a screen about this exercise or workout (Exercise Detail, the exercise list, the "…"
     * menu, the exercise picker): he is still busy here, so the screen must not move on under him. The
     * Rest Timer doesn't call this: tick, timer, then the next exercise is the flow it is for.
     */
    fun holdPosition() {
        pendingAdvance.value = null
    }

    /**
     * The exercise [slotId] was finished by a tick at [tickedAt]; see [pendingAdvance]. [restSaved] once
     * that tick's rest is saved: until then the rest on record is an older one, and timing the move by
     * it could move on early (behind a slow write) or late.
     */
    private data class PendingAdvance(val slotId: Long, val tickedAt: Long, val restSaved: Boolean = false)

    /**
     * Set when a tick finishes the exercise on screen: the screen moves on to the next exercise with
     * an unticked set as the rest ends ([autoAdvanceAt]), not at the tick, so the tick shows and a
     * mis-tap can be undone. Any other tap on this screen cancels it ([beginAction]), and so does
     * opening any other screen but the Rest Timer ([holdPosition]).
     */
    private val pendingAdvance = MutableStateFlow<PendingAdvance?>(null)

    /** When the screen last moved on by itself; a NEXT or tick right after is ignored ([tapTooSoonAfterMove]). */
    @Volatile
    private var movedOnAt: Long? = null

    /** Moves on for [pending] if it still stands, the exercise is still on screen and still finished. */
    private fun autoAdvance(pending: PendingAdvance) {
        if (!pendingAdvance.compareAndSet(pending, null) || locked) return
        val state = _uiState.value
        val current = state.exercises.getOrNull(state.currentIndex) ?: return
        if (current.slot.id != pending.slotId) return
        val target = autoAdvanceSlotId(state.exercises.map { it.slot }, state.exercises.flatMap { it.sets }, current.slot.id) ?: return
        movedOnAt = System.currentTimeMillis()
        showExercise(target)
    }

    // --- Row interactions ---

    /** A tap on a row targets it with the steppers; tapping the active row again does nothing. */
    fun onRowTapped(setId: Long) {
        if (!beginRowAction()) return
        if (_uiState.value.activeSetId == setId) return
        _uiState.update { it.copy(activeSetId = setId) }
    }

    /**
     * A tap on the set marker targets the row and cycles its type ([nextSetType]: normal → warm-up →
     * failure → drop). A ticked set never becomes a warm-up or stops being one this way.
     */
    fun onMarkerTapped(setId: Long) {
        if (!beginRowAction()) return
        val located = locate(setId) ?: return
        val set = located.block.sets[located.index]
        val next = nextSetType(set.type, ticked = set.completedAt != null)
        _uiState.update { it.copy(activeSetId = setId) }
        if (next == set.type) return
        val edit = applySetChange(setId) { it.copy(type = next) } ?: return
        persistSet(edit)
    }

    /**
     * A tap on a row's RIR cell cycles it (blank, 0–5, blank; [nextRir]), stored as RPE = 10 − RIR.
     * Ticked rows too: the effort is known once the set is done, usually while resting. It rates the
     * set without moving the steppers or cancelling a pending move to the next exercise, but a tap in
     * the second after the screen moved on was aimed at the rows before and is dropped.
     */
    fun onRirTapped(setId: Long) {
        if (locked || tapTooSoonAfterMove(movedOnAt, System.currentTimeMillis())) return
        val edit = applySetChange(setId) { it.copy(rpe = rpeOf(nextRir(rirOf(it.rpe)))) } ?: return
        persistSet(edit)
    }

    /**
     * Tap on PREVIOUS: copy last session's exact numbers into the row (those the exercise records) and
     * target it. A logged set is left alone, so a stray tap can't overwrite what was lifted.
     */
    fun autofill(setId: Long) {
        if (!beginRowAction()) return
        val located = locate(setId) ?: return
        if (located.block.sets[located.index].completedAt != null) return
        val previous = located.block.previous.getOrNull(located.index) ?: return
        val tracking = located.block.tracking
        val weight = previous.weightKg?.takeIf { tracking.takesWeight }
        val reps = previous.reps?.takeIf { tracking.takesReps }
        val seconds = previous.durationSec?.takeIf { tracking.takesDuration }
        if (weight == null && reps == null && seconds == null) return
        val edit = applySetChange(setId) {
            it.copy(weightKg = weight ?: it.weightKg, reps = reps ?: it.reps, durationSec = seconds ?: it.durationSec)
        } ?: return
        _uiState.update { it.copy(activeSetId = setId) }
        persistSet(edit)
    }

    /**
     * Checkbox toggle. Completing logs exactly what the row shows: its starting value ([seedRows])
     * wherever it has none of its own. A row with nothing to log shows a hint instead. Completing
     * advances the active row and starts rest per the completion result. Uncompleting keeps the
     * logged values. A tap right after the one that ticked or unticked the same set ([isDoubleTap]) is
     * a double tap or a bounce: it is ignored before anything else, so it cancels no pending move either.
     */
    fun toggleComplete(setId: Long) {
        val tapAt = System.currentTimeMillis()
        val unticked = lastToggle
        val located = locate(setId) ?: return
        val set = located.block.sets[located.index]
        val firstTapAt = set.completedAt ?: unticked?.takeIf { it.first == setId }?.second
        if (firstTapAt != null && isDoubleTap(firstTapAt, tapAt)) return
        if (!beginRowAction()) return
        if (set.completedAt != null) {
            lastToggle = setId to tapAt
            val edit = applySetChange(setId) { it.copy(completedAt = null) } ?: return
            val workoutId = _uiState.value.workoutId
            LiftsIo.write({ session.uncompleteSet(workoutId, set) }) { settle(edit) }
            return
        }
        val seed = located.block.seeds[located.index]
        if (seed.isEmpty) {
            showEmptyTickHint(located.block.tracking)
            return
        }
        val now = System.currentTimeMillis()
        val edit = applySetChange(setId) {
            it.copy(weightKg = seed.weightKg, reps = seed.reps, durationSec = seed.durationSec, completedAt = now)
        } ?: return
        _uiState.update { state ->
            val block = state.exercises.firstOrNull { it.slot.id == set.workoutExerciseId }
            state.copy(activeSetId = block?.sets?.firstOrNull { it.completedAt == null }?.id)
        }
        // The exercise's last set: move on once the rest that this tick starts is over.
        val pending = PendingAdvance(set.workoutExerciseId, now).takeIf { _uiState.value.activeSetId == null }
        pending?.let { pendingAdvance.value = it }
        val workoutId = _uiState.value.workoutId
        LiftsIo.write({ session.completeSet(workoutId, set, seed.weightKg, seed.reps, set.type, now, seed.durationSec) }) { logged ->
            settle(edit)
            // Its rest is saved now, so the move can be timed by it; unless a tap has cancelled it since.
            // Nothing logged (the workout closed meanwhile): no move either.
            pending?.let { pendingAdvance.compareAndSet(it, if (logged != null) it.copy(restSaved = true) else null) }
        }
    }

    /**
     * "SET WEIGHT OR REPS FIRST" ("SET REPS FIRST", "SET TIME FIRST", as the exercise is measured) for
     * a couple of seconds, instead of a tick that does nothing.
     */
    private fun showEmptyTickHint(tracking: Tracking) {
        emptyTickHintJob?.cancel()
        val hint = when {
            tracking.takesDuration -> "SET TIME FIRST"
            tracking.takesWeight -> "SET WEIGHT OR REPS FIRST"
            else -> "SET REPS FIRST"
        }
        _uiState.update { it.copy(emptyTickHint = hint) }
        emptyTickHintJob = viewModelScope.launch {
            delay(EMPTY_TICK_HINT_MILLIS)
            _uiState.update { it.copy(emptyTickHint = null) }
        }
    }

    // --- Steppers (operate on the active row) ---

    /**
     * Moves the active row's weight [steps] steps of Settings' weight step along its grid
     * ([Format.stepWeight]); a long hold sends several.
     */
    fun stepWeight(steps: Int) {
        if (!beginAction()) return
        val state = _uiState.value
        val set = activeSet(state) ?: return
        val tracking = currentTracking(state)
        val base = currentWeightDisplay(state, set)
        val next = Format.roundWeight(Format.stepWeight(base, steps, state.weightStep), state.units)
        // The steppers move the weight as it shows: on an assisted exercise the help, stored negative.
        val edit = applySetChange(set.id) {
            it.copy(weightKg = tracking.storedWeight(Format.displayToKg(next, state.units)))
        } ?: return
        persistSet(edit)
    }

    private var weightEntrySession = 0

    /**
     * A tap on the weight between the steppers: the keyboard opens on the active row's weight as it
     * shows (blank for none), in the unit shown; on an assisted exercise the help itself.
     */
    fun openWeightEntry() {
        if (!beginAction()) return
        val state = _uiState.value
        val set = activeSet(state) ?: return
        if (!currentTracking(state).takesWeight) return
        val located = locate(set.id) ?: return
        val shows = set.weightKg ?: located.block.seeds[located.index].weightKg
        val prefill = shows?.let { Format.weight(currentTracking(state).shownWeight(it)!!, state.units) }.orEmpty()
        _uiState.update { it.copy(weightEntry = WeightEntry(set.id, prefill, ++weightEntrySession)) }
    }

    /**
     * The keyboard's SUBMIT: the typed weight ([parseTypedWeight]) goes on the row it was opened for,
     * stored as the exercise records it (help as a negative weight); blank clears the row back to its
     * starting value. Text that isn't a weight keeps the keyboard open, saying so.
     */
    fun submitWeightEntry(text: String) {
        val state = _uiState.value
        val entry = state.weightEntry ?: return
        val typed = parseTypedWeight(text, state.units)
        if (typed == null) {
            _uiState.update { it.copy(weightEntry = entry.copy(prefill = text, session = ++weightEntrySession, refused = true)) }
            return
        }
        _uiState.update { it.copy(weightEntry = null) }
        if (locked) return
        val located = locate(entry.setId) ?: return
        val weightKg = when (typed) {
            TypedWeight.Clear -> null
            is TypedWeight.Value -> located.block.tracking.storedWeight(Format.displayToKg(typed.display, state.units))
        }
        val edit = applySetChange(entry.setId) { it.copy(weightKg = weightKg) } ?: return
        persistSet(edit)
    }

    fun closeWeightEntry() {
        _uiState.update { it.copy(weightEntry = null) }
    }

    /** Moves the active row's seconds [steps] steps of [DURATION_STEP_SEC] along their grid; a long hold sends several. */
    fun stepDuration(steps: Int) {
        if (!beginAction()) return
        val state = _uiState.value
        val set = activeSet(state) ?: return
        val next = Format.stepWeight(currentDuration(set).toDouble(), steps, DURATION_STEP_SEC.toDouble()).roundToInt()
        val edit = applySetChange(set.id) { it.copy(durationSec = next) } ?: return
        persistSet(edit)
    }

    fun durationDecrementEnabled(state: UiState): Boolean {
        val set = activeSet(state) ?: return false
        return currentDuration(set) > 0
    }

    fun stepReps(direction: Int) {
        if (!beginAction()) return
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

    /**
     * The active row's weight in display units as it shows: its own, else its starting value, else 0.
     * On an assisted exercise that is the help itself (30, for -30 stored).
     */
    private fun currentWeightDisplay(state: UiState, set: SetEntity): Double {
        val located = locate(set.id)
        val kg = set.weightKg ?: located?.block?.seeds?.getOrNull(located.index)?.weightKg ?: return 0.0
        val shown = currentTracking(state).shownWeight(kg) ?: return 0.0
        return Format.roundWeight(Format.kgToDisplay(shown, state.units), state.units)
    }

    /** The active row's seconds as they show: its own, else its starting value, else 0. */
    private fun currentDuration(set: SetEntity): Int {
        val located = locate(set.id)
        return set.durationSec ?: located?.block?.seeds?.getOrNull(located.index)?.durationSec ?: 0
    }

    /** How the exercise on screen is measured. */
    private fun currentTracking(state: UiState): Tracking =
        state.exercises.getOrNull(state.currentIndex)?.tracking ?: Tracking.WEIGHT_REPS

    /** The active row's reps as they show: its own, else its starting value, else 0. */
    private fun currentReps(set: SetEntity): Int {
        val located = locate(set.id)
        return set.reps ?: located?.block?.seeds?.getOrNull(located.index)?.reps ?: 0
    }

    // --- Structure ---

    /** What the next-exercise row offers ([nextExerciseSlotId]); null with no other exercise. */
    fun nextExercise(state: UiState): ExerciseBlock? {
        val current = state.exercises.getOrNull(state.currentIndex) ?: return null
        val nextSlotId = nextExerciseSlotId(state.exercises.map { it.slot }, state.exercises.flatMap { it.sets }, current.slot.id)
        return state.exercises.firstOrNull { it.slot.id == nextSlotId }
    }

    /** The next-exercise row: moves to the exercise it offers. */
    fun goToNextExercise() {
        if (!beginRowAction()) return
        nextExercise(_uiState.value)?.let { showExercise(it.slot.id) }
    }

    /** Result of the exercise list: that exercise, on its first unticked set. */
    fun jumpTo(slotId: Long) {
        if (!beginAction()) return
        // Even back to the same exercise: a bounce aimed at the list must not tick a row, which is
        // worse than one tap dropped in the next second.
        rowsMovedUnderFinger()
        showExercise(slotId)
    }

    /** The set last unticked, and when: a tick on it right after is a bounce ([isDoubleTap]). */
    @Volatile
    private var lastToggle: Pair<Long, Long>? = null

    /**
     * The rows changed as another screen (exercise list, picker, "…" menu) closed: a bounce or a tap
     * already on its way was aimed at that screen, so the rows ignore taps for a moment, as after the
     * screen moves on by itself ([tapTooSoonAfterMove]).
     */
    private fun rowsMovedUnderFinger() {
        movedOnAt = System.currentTimeMillis()
    }

    /** Puts exercise [slotId] on screen with the steppers on its first unticked row, and saves it as the one on screen. */
    private fun showExercise(slotId: Long) {
        _uiState.update { state ->
            val index = state.exercises.indexOfFirst { it.slot.id == slotId }
            if (index < 0) {
                state
            } else {
                state.copy(
                    currentIndex = index,
                    activeSetId = state.exercises[index].sets.firstOrNull { it.completedAt == null }?.id,
                )
            }
        }
        rememberCurrentSlot()
    }

    /** The slot last saved as on screen, so it is saved again only when it changes. Guarded by [refreshLock]. */
    @Volatile
    private var rememberedSlotId: Long? = null

    /**
     * Saves the exercise on screen, so reopening after a kill lands on it ([openingSlotId]). Reads
     * the state and queues the save under [refreshLock], so a reload and a swipe to the next
     * exercise can't save in the wrong order.
     */
    private fun rememberCurrentSlot() {
        synchronized(refreshLock) {
            val state = _uiState.value
            val slotId = state.exercises.getOrNull(state.currentIndex)?.slot?.id ?: return
            if (slotId == rememberedSlotId) return
            rememberedSlotId = slotId
            val workoutId = state.workoutId
            LiftsIo.write { session.rememberCurrentSlot(workoutId, slotId) }
        }
    }

    fun addSet() {
        if (!beginRowAction()) return
        val state = _uiState.value
        val block = state.exercises.getOrNull(state.currentIndex) ?: return
        LiftsIo.write({ session.addSet(state.workoutId, block.slot.id) }) { refresh() }
    }

    /** Result of the exercise picker: append the exercise and jump to it. */
    fun addExercise(exerciseId: String) {
        if (!beginAction()) return
        val workoutId = resolvedWorkoutId ?: return
        rowsMovedUnderFinger()
        LiftsIo.write({ session.addExercise(workoutId, exerciseId) }) {
            // The rows change when this reload lands, which a busy lane can put past the guard's second.
            rowsMovedUnderFinger()
            refresh(focusLastExercise = true)
        }
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
            if (outcome == FinishOutcome.ENDED) share.workoutFinished()
            _uiState.update {
                when (outcome) {
                    FinishOutcome.ENDED -> it.copy(pendingCompleteWorkoutId = workoutId)
                    FinishOutcome.DISCARDED, FinishOutcome.MISSING -> it.copy(leave = true)
                }
            }
        }
    }

    fun consumePendingComplete() {
        _uiState.update { it.copy(pendingCompleteWorkoutId = null, completeOpen = true) }
    }

    /**
     * RESUME on Workout Complete: the workout is open again and logging carries on. Unfinished
     * workouts are in no export file, so the exports drop it until the next Finish (or a Discard,
     * which then has nothing to take out).
     */
    fun resumeAfterComplete() {
        val workoutId = resolvedWorkoutId ?: return
        _uiState.update { it.copy(completeOpen = false) }
        LiftsIo.write({ session.reopenWorkout(workoutId) }) {
            share.logChanged()
            refresh()
            _uiState.update { it.copy(finishing = false) }
        }
    }

    fun menuOptions(state: UiState): MenuOptions {
        val workout = WorkoutMenuContent(workoutName = state.workoutName, workoutNote = state.workoutNotes)
        val block = state.exercises.getOrNull(state.currentIndex) ?: return MenuOptions(workout)
        val activeIndex = block.sets.indexOfFirst { it.id == state.activeSetId }
        val activeSet = block.sets.getOrNull(activeIndex)
        val removableSet = activeSet?.takeIf { it.completedAt == null }
        val exerciseRemovable = block.sets.none { it.completedAt != null }
        // Plates for the weight the active row shows, its own or its starting value.
        val shownKg = activeSet?.let { it.weightKg ?: block.seeds[activeIndex].weightKg }
        val platesKg = shownKg?.takeIf { block.tracking == Tracking.WEIGHT_REPS && Format.roundWeight(Format.kgToDisplay(it, state.units), state.units) > 0.0 }
        return MenuOptions(
            content = workout.copy(
                exerciseName = block.name,
                exerciseNote = block.slot.notes,
                restSec = block.slot.restSecWork ?: state.defaultRestSec,
                defaultRestSec = state.defaultRestSec,
                platesFor = platesKg?.let { "${Format.weight(it, state.units)} ${state.units.suffix}" },
                platesUnavailable = when {
                    platesKg != null -> null
                    block.tracking != Tracking.WEIGHT_REPS -> "BARBELL LIFTS ONLY"
                    activeSet == null -> "NO SET SELECTED"
                    else -> "SET A WEIGHT FIRST"
                },
                position = "${state.currentIndex + 1} OF ${state.exercises.size}",
                removeSetLabel = removableSet?.let {
                    if (it.type == SET_TYPE_WARMUP) "Warm-up" else "Set ${buildMarkers(block.sets)[activeIndex]}"
                },
                removeExerciseLabel = block.name.takeIf { exerciseRemovable },
            ),
            removeSetId = removableSet?.id,
            removeSlotId = block.slot.id.takeIf { exerciseRemovable },
            slotId = block.slot.id,
            exerciseId = block.slot.exerciseId,
            platesKg = platesKg,
        )
    }

    /**
     * Menu: renames the workout. Shown at once, so the menu opened again reads the new name; the
     * session ignores a blank or unchanged name and a workout that has closed, and the reload after
     * shows what was saved.
     */
    fun renameWorkout(name: String) {
        if (!beginAction()) return
        val workoutId = resolvedWorkoutId ?: return
        val tidy = name.replace(Regex("\\s+"), " ").trim()
        if (tidy.isNotEmpty()) _uiState.update { it.copy(workoutName = tidy) }
        LiftsIo.write({ session.renameWorkout(workoutId, name) }) { refresh() }
    }

    /** Menu: the workout's note (blank clears it); shown at once, like [renameWorkout]. */
    fun setWorkoutNotes(text: String) {
        if (!beginAction()) return
        val workoutId = resolvedWorkoutId ?: return
        _uiState.update { it.copy(workoutNotes = text.trim().ifEmpty { null }) }
        LiftsIo.write({ session.setWorkoutNotes(workoutId, text) }) { refresh() }
    }

    /** Menu: the note on exercise [slotId] for this workout (blank clears it); shown at once. */
    fun setSlotNotes(slotId: Long, text: String) {
        if (!beginAction()) return
        val workoutId = resolvedWorkoutId ?: return
        updateSlot(slotId) { it.copy(notes = text.trim().ifEmpty { null }) }
        LiftsIo.write({ session.setSlotNotes(workoutId, slotId, text) }) { refresh() }
    }

    /** Menu: exercise [slotId]'s work rest from its next tick; Settings' default is stored as none. Shown at once. */
    fun setSlotRest(slotId: Long, sec: Int) {
        if (!beginAction()) return
        val workoutId = resolvedWorkoutId ?: return
        val default = _uiState.value.defaultRestSec
        updateSlot(slotId) { it.copy(restSecWork = sec.takeIf { rest -> rest != default }) }
        LiftsIo.write({ session.setSlotRest(workoutId, slotId, sec) }) { refresh() }
    }

    /** [transform] applied to slot [slotId] on screen, ahead of the save and the reload after it. */
    private fun updateSlot(slotId: Long, transform: (WorkoutExerciseEntity) -> WorkoutExerciseEntity) {
        synchronized(refreshLock) {
            _uiState.update { state ->
                state.copy(exercises = state.exercises.map { if (it.slot.id == slotId) it.copy(slot = transform(it.slot)) else it })
            }
        }
    }

    /** Menu: removes the set it offered, only while it is unticked (the session checks again). */
    fun removeSet(setId: Long) {
        if (!beginAction()) return
        val workoutId = resolvedWorkoutId ?: return
        val located = locate(setId) ?: return
        if (located.block.sets[located.index].completedAt != null) return
        rowsMovedUnderFinger()
        LiftsIo.write({ session.removeSet(workoutId, setId) }) { refresh() }
    }

    /** Menu: removes the exercise it offered, only while none of its sets is ticked. */
    fun removeExercise(slotId: Long) {
        if (!beginAction()) return
        val workoutId = resolvedWorkoutId ?: return
        val block = _uiState.value.exercises.firstOrNull { it.slot.id == slotId } ?: return
        if (block.sets.any { it.completedAt != null }) return
        rowsMovedUnderFinger()
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
                        tracking = trackingOf(exercises),
                    ),
                )
            }
            changed?.let { PendingEdit(it).also { edit -> pendingEdits[setId] = edit } }
        }

    /** Each exercise's tracking, for the records engine. */
    private fun trackingOf(blocks: List<ExerciseBlock>): Map<String, Tracking> =
        blocks.associate { it.slot.exerciseId to it.tracking }

    /** Marks [edit]'s save as landed: reloads that start from now on read it from the database. */
    private fun settle(edit: PendingEdit) {
        synchronized(refreshLock) { edit.savedAt = refreshesStarted }
    }

    /** Saves [edit] on the app-wide write queue, so pressing Back right after a tap can't drop it. */
    private fun persistSet(edit: PendingEdit) {
        val workoutId = _uiState.value.workoutId
        LiftsIo.write({ session.updateSet(workoutId, edit.set) }) { settle(edit) }
    }

    /** Guards [refreshesStarted], [refreshApplied] and [pendingEdits]. */
    private val refreshLock = Any()
    private var refreshesStarted = 0L

    /** The newest refresh whose result is on screen. */
    private var refreshApplied = 0L

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
        val version = HistoryVersion.current
        synchronized(refreshLock) {
            if (version != cachedHistoryVersion) {
                lastTimeByExercise = emptyMap()
                priorHistoryByExercise = emptyMap()
                cachedHistoryVersion = version
            }
        }
        val missing = exerciseIds.filterNot { it in lastTimeByExercise }
        if (missing.isEmpty()) return
        val lastTime = session.previousGroupsByExercise(missing)
        val history = session.priorHistory(missing, workoutId)
        synchronized(refreshLock) {
            // A delete or restore that landed while these were read makes them stale: the next
            // reload fetches them again.
            if (cachedHistoryVersion != version) return
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
        // Only the first load picks the exercise; after that the lifter's choice stands.
        val savedSlotId = if (_uiState.value.loaded) null else session.currentSlotSnapshot(workoutId)
        applyRefresh(ticket) {
            val sets = overlayPendingEdits(ticket, loadedSets)
            val setsBySlot = sets.groupBy { it.workoutExerciseId }
            val blocks = slots.map { slot ->
                val exercise = catalog[slot.exerciseId]
                val name = exercise?.let { displayName(it) } ?: slot.exerciseId
                ExerciseBlock(
                    slot = slot,
                    name = name,
                    detailLine = detailLine(exercise, equipmentInName = namesEquipment(name, exercise?.equipment)),
                    cue = exercise?.pinnedNote?.takeIf { it.isNotBlank() },
                    sets = setsBySlot[slot.id].orEmpty().sortedBy { it.position },
                    lastTime = lastTimeBySlot[slot.id].orEmpty(),
                    unit = settings.units,
                    tracking = exercise?.tracking ?: Tracking.WEIGHT_REPS,
                )
            }
            val prIds = prSetIds(slots, sets, priorHistoryByExercise, trackingOf(blocks))
            _uiState.update { state ->
                val lastIndex = (blocks.size - 1).coerceAtLeast(0)
                val currentIndex = when {
                    focusLastExercise -> lastIndex
                    state.loaded -> state.currentIndex.coerceIn(0, lastIndex)
                    else -> blocks.indexOfFirst { it.slot.id == openingSlotId(slots, sets, savedSlotId) }.coerceAtLeast(0)
                }
                val currentBlock = blocks.getOrNull(currentIndex)
                val activeSetId = state.activeSetId
                    ?.takeIf { id -> currentBlock?.sets?.any { it.id == id } == true }
                    ?: currentBlock?.sets?.firstOrNull { it.completedAt == null }?.id
                state.copy(
                    loaded = true,
                    workoutId = workoutId,
                    workoutName = workout.name,
                    workoutNotes = workout.notes,
                    startedAt = workout.startedAt,
                    exercises = blocks,
                    currentIndex = currentIndex,
                    activeSetId = activeSetId,
                    prSetIds = prIds,
                    units = settings.units,
                    weightStep = settings.weightStep,
                    defaultRestSec = settings.defaultRestSec,
                    warmupRestSec = settings.warmupRestSec,
                )
            }
            rememberCurrentSlot()
        }
    }

    /**
     * "Barbell · Chest — Triceps, Front Delts" from the catalog entity; just the muscles when the name
     * above it already says the equipment ([equipmentInName]).
     */
    private fun detailLine(exercise: ExerciseEntity?, equipmentInName: Boolean): String {
        exercise ?: return ""
        val parts = mutableListOf<String>()
        exercise.equipment?.takeIf { it.isNotBlank() && !equipmentInName }?.let { parts += titleCase(it) }
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
