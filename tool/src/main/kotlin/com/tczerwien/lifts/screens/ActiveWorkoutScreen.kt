package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.LiftsDatabase
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import com.tczerwien.lifts.session.ActiveSession
import com.tczerwien.lifts.session.SET_TYPE_FAILURE
import com.tczerwien.lifts.session.SET_TYPE_NORMAL
import com.tczerwien.lifts.session.SET_TYPE_WARMUP
import com.tczerwien.lifts.session.nextPendingSet
import com.tczerwien.lifts.session.prSetIds
import com.tczerwien.lifts.session.restDurationSec
import com.tczerwien.lifts.session.restRemainingSec
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.SetRow
import com.tczerwien.lifts.ui.SetRowHeader
import com.tczerwien.lifts.ui.SetRowState
import com.tczerwien.lifts.ui.Stepper
import com.tczerwien.lifts.ui.WeightUnit
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ActiveWorkoutViewModel(
    private val repository: LiftsRepository,
    private val session: ActiveSession,
    private val settingsStore: SettingsStore,
    private val resumeWorkoutId: Long?,
    private val templateId: Long?,
) : LightViewModel<Unit>() {

    /** One exercise of the session with its sets and previous-per-index lookup. */
    data class ExerciseBlock(
        val slot: WorkoutExerciseEntity,
        val name: String,
        val baseName: String,
        val detailLine: String,
        val cue: String?,
        val sets: List<SetEntity>,
        val previous: List<SetEntity>,
    )

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
        /** Finish flow: non-null asks Content to navigate to Workout Complete once. */
        val pendingCompleteWorkoutId: Long? = null,
        /** The workout ended (returned from Complete): Content leaves the screen. */
        val workoutEnded: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    @Volatile
    private var resolvedWorkoutId: Long? = null

    /** Prior sets per exercise from other workouts; cached per refresh for PR replay. */
    @Volatile
    private var priorHistoryByExercise: Map<String, List<SetEntity>> = emptyMap()

    private val writeMutex = Mutex()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            resolvedWorkoutId = when {
                resumeWorkoutId != null -> resumeWorkoutId
                templateId != null -> session.startFromTemplate(templateId)
                else -> session.startEmpty()
            }
            refresh()
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch(Dispatchers.IO) { refresh() }
    }

    // --- Row interactions ---

    /** First tap targets the row with the steppers; tapping the active row again cycles its type. */
    fun onRowTapped(setId: Long) {
        if (_uiState.value.activeSetId == setId) {
            val changed = applySetChange(setId) { it.copy(type = nextType(it.type)) } ?: return
            persistSet(changed)
        } else {
            _uiState.update { it.copy(activeSetId = setId) }
        }
    }

    /** Tap on PREVIOUS: copy last session's exact weight/reps into the row and target it. */
    fun autofill(setId: Long) {
        val located = locate(setId) ?: return
        val previous = located.block.previous.getOrNull(located.index) ?: return
        if (previous.weightKg == null && previous.reps == null) return
        val changed = applySetChange(setId) {
            it.copy(weightKg = previous.weightKg ?: it.weightKg, reps = previous.reps ?: it.reps)
        } ?: return
        _uiState.update { it.copy(activeSetId = setId) }
        persistSet(changed)
    }

    /**
     * Checkbox toggle. Completing autofills missing values from previous performance
     * (a row with nothing to log is ignored), advances the active row, and starts rest
     * per the completion result. Uncompleting keeps the logged values.
     */
    fun toggleComplete(setId: Long) {
        val located = locate(setId) ?: return
        val set = located.block.sets[located.index]
        if (set.completedAt != null) {
            applySetChange(setId) { it.copy(completedAt = null) }
            viewModelScope.launch(Dispatchers.IO) {
                writeMutex.withLock { session.uncompleteSet(set) }
            }
            return
        }
        val previous = located.block.previous.getOrNull(located.index)
        val weightKg = set.weightKg ?: previous?.weightKg
        val reps = set.reps ?: previous?.reps
        if (weightKg == null && reps == null) return
        val now = System.currentTimeMillis()
        applySetChange(setId) { it.copy(weightKg = weightKg, reps = reps, completedAt = now) }
        _uiState.update { state ->
            val block = state.exercises.firstOrNull { it.slot.id == set.workoutExerciseId }
            state.copy(activeSetId = block?.sets?.firstOrNull { it.completedAt == null }?.id)
        }
        val workoutId = _uiState.value.workoutId
        viewModelScope.launch(Dispatchers.IO) {
            val completion = writeMutex.withLock {
                session.completeSet(workoutId, set, weightKg, reps, set.type, now)
            }
            _uiState.update {
                it.copy(
                    restEndMillis = completion.restEndMillis,
                    restTotalSec = completion.restDurationSec,
                )
            }
        }
    }

    // --- Steppers (operate on the active row) ---

    fun stepWeight(direction: Int) {
        val state = _uiState.value
        val set = activeSet(state) ?: return
        val base = currentWeightDisplay(state, set)
        val next = Format.roundWeight(
            (base + direction * state.units.weightStep).coerceAtLeast(0.0),
        )
        val changed = applySetChange(set.id) {
            it.copy(weightKg = Format.displayToKg(next, state.units))
        } ?: return
        persistSet(changed)
    }

    fun stepReps(direction: Int) {
        val state = _uiState.value
        val set = activeSet(state) ?: return
        val next = (currentReps(state, set) + direction).coerceAtLeast(0)
        val changed = applySetChange(set.id) { it.copy(reps = next) } ?: return
        persistSet(changed)
    }

    fun weightDecrementEnabled(state: UiState): Boolean {
        val set = activeSet(state) ?: return false
        return currentWeightDisplay(state, set) > 0.0
    }

    fun repsDecrementEnabled(state: UiState): Boolean {
        val set = activeSet(state) ?: return false
        return currentReps(state, set) > 0
    }

    fun activeSet(state: UiState): SetEntity? {
        val id = state.activeSetId ?: return null
        return state.exercises.getOrNull(state.currentIndex)?.sets?.firstOrNull { it.id == id }
    }

    /** Active row's display weight; empty rows seed from this session's earlier rows, else previous performance. */
    private fun currentWeightDisplay(state: UiState, set: SetEntity): Double {
        set.weightKg?.let { return Format.roundWeight(Format.kgToDisplay(it, state.units)) }
        val located = locate(set.id) ?: return 0.0
        val seedKg = located.block.sets.take(located.index).lastOrNull { it.weightKg != null }?.weightKg
            ?: located.block.previous.getOrNull(located.index)?.weightKg
        return seedKg?.let { Format.roundWeight(Format.kgToDisplay(it, state.units)) } ?: 0.0
    }

    private fun currentReps(state: UiState, set: SetEntity): Int {
        set.reps?.let { return it }
        val located = locate(set.id) ?: return 0
        return located.block.sets.take(located.index).lastOrNull { it.reps != null }?.reps
            ?: located.block.previous.getOrNull(located.index)?.reps
            ?: 0
    }

    // --- Structure ---

    /** Collapsed next-exercise row: advance (wrapping), targeting its first pending set. */
    fun advanceExercise() {
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
        val state = _uiState.value
        val block = state.exercises.getOrNull(state.currentIndex) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            writeMutex.withLock { session.addSet(state.workoutId, block.slot.id) }
            refresh()
        }
    }

    /** Result of the exercise picker: append the exercise and jump to it. */
    fun addExercise(exerciseId: String) {
        val workoutId = resolvedWorkoutId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            writeMutex.withLock { session.addExercise(workoutId, exerciseId) }
            refresh(focusLastExercise = true)
        }
    }

    fun finish() {
        val workoutId = resolvedWorkoutId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            writeMutex.withLock { session.finish(workoutId) }
            _uiState.update {
                it.copy(pendingCompleteWorkoutId = workoutId, restEndMillis = null, restTotalSec = 0)
            }
        }
    }

    fun consumePendingComplete() {
        _uiState.update { it.copy(pendingCompleteWorkoutId = null) }
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

    /** Applies [transform] to one set in local state and re-replays PR flags; returns the new entity. */
    private fun applySetChange(setId: Long, transform: (SetEntity) -> SetEntity): SetEntity? {
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
        return changed
    }

    private fun persistSet(set: SetEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            writeMutex.withLock { session.updateSet(set) }
        }
    }

    private fun nextType(type: String): String = when (type) {
        SET_TYPE_WARMUP -> SET_TYPE_FAILURE
        SET_TYPE_FAILURE -> SET_TYPE_NORMAL
        else -> SET_TYPE_WARMUP
    }

    /** Full reload from the database — call from Dispatchers.IO. */
    private fun refresh(focusLastExercise: Boolean = false) {
        val workoutId = resolvedWorkoutId ?: return
        val workout = repository.workoutById(workoutId) ?: return
        if (workout.endedAt != null) {
            // Finished (back from Workout Complete): leave — unless we are the ones
            // navigating there right now.
            _uiState.update { state ->
                if (state.pendingCompleteWorkoutId != null) {
                    state
                } else {
                    state.copy(loaded = true, workoutEnded = true)
                }
            }
            return
        }
        val settings = settingsStore.snapshot()
        val slots = repository.exercisesForWorkout(workoutId)
        val sets = repository.setsForWorkout(workoutId)
        val setsBySlot = sets.groupBy { it.workoutExerciseId }
        val blocks = slots.map { slot ->
            val exercise = repository.exerciseById(slot.exerciseId)
            val name = exercise?.name ?: slot.exerciseId
            ExerciseBlock(
                slot = slot,
                name = name,
                baseName = name.substringBefore(" ("),
                detailLine = detailLine(exercise),
                cue = exercise?.pinnedNote?.takeIf { it.isNotBlank() },
                sets = setsBySlot[slot.id].orEmpty().sortedBy { it.position },
                previous = session.previousPerformance(slot.exerciseId),
            )
        }
        priorHistoryByExercise = slots.map { it.exerciseId }.distinct()
            .associateWith { session.priorHistoryForExercise(it, workoutId) }
        val prIds = prSetIds(slots, sets, priorHistoryByExercise)
        val restState = session.restStateSnapshot()
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
                restEndMillis = restState?.endMillis,
                restTotalSec = restState?.totalSec ?: 0,
                units = settings.units,
                defaultRestSec = settings.defaultRestSec,
                warmupRestSec = settings.warmupRestSec,
            )
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

/**
 * Active workout (design/screens/active-workout.html): one exercise at a time with its
 * set grid (PREVIOUS autofill, steppers on the active row, checkbox logging + PR stars),
 * a collapsed next-exercise row, and the rest strip. Bottom bar: rest timer / add
 * exercise / finish. The elapsed clock and rest remaining are derived from wall-clock
 * timestamps, ticking only while the screen is shown.
 *
 * Constructor contract (FROZEN): [workoutId] resumes; else [templateId] creates the
 * session from a template inside this screen; both null starts an empty workout.
 */
class ActiveWorkoutScreen(
    sealedActivity: SealedLightActivity,
    /** Resume this workout when non-null; takes precedence over [templateId]. */
    private val workoutId: Long? = null,
    /** Create a new session from this template; null with null [workoutId] = empty workout. */
    private val templateId: Long? = null,
) : LightScreen<Unit, ActiveWorkoutViewModel>(sealedActivity) {

    private val repository = LiftsRepository.getInstance {
        lightContext.buildDatabase(LiftsDatabase::class.java, LiftsRepository.DATABASE_NAME)
    }

    private val settingsStore = SettingsStore(lightContext.dataStore)

    private val session = ActiveSession(repository, settingsStore, lightContext.dataStore)

    override val viewModelClass: Class<ActiveWorkoutViewModel>
        get() = ActiveWorkoutViewModel::class.java

    override fun createViewModel() =
        ActiveWorkoutViewModel(repository, session, settingsStore, workoutId, templateId)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val uiState by viewModel.uiState.collectAsState()

        var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                delay(CLOCK_TICK_MS)
            }
        }

        LaunchedEffect(uiState.pendingCompleteWorkoutId) {
            uiState.pendingCompleteWorkoutId?.let { completedId ->
                viewModel.consumePendingComplete()
                navigateTo({ WorkoutCompleteScreen(it, workoutId = completedId) })
            }
        }
        LaunchedEffect(uiState.workoutEnded) {
            if (uiState.workoutEnded) goBack()
        }

        val current = uiState.exercises.getOrNull(uiState.currentIndex)
        val restRemaining = uiState.restEndMillis?.let { restRemainingSec(it, nowMillis) } ?: 0
        val timerDurationSec = when {
            restRemaining > 0 && uiState.restTotalSec > 0 -> uiState.restTotalSec
            current != null -> restDurationSec(
                setType = SET_TYPE_NORMAL,
                restSecWork = current.slot.restSecWork,
                restSecWarmup = current.slot.restSecWarmup,
                defaultRestSec = uiState.defaultRestSec,
                warmupRestSec = uiState.warmupRestSec,
            )
            else -> uiState.defaultRestSec
        }
        val openTimer = { navigateTo({ RestTimerScreen(it, durationSec = timerDurationSec) }) }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = if (uiState.loaded) {
                        LightTopBarCenter.TwoLineDetail(
                            line1 = uiState.workoutName,
                            line2 = topBarSubtitle(uiState, nowMillis),
                        )
                    } else {
                        null
                    },
                )

                when {
                    !uiState.loaded -> Spacer(modifier = Modifier.weight(1f))

                    current == null -> EmptyWorkoutBody(modifier = Modifier.weight(1f))

                    else -> {
                        ExerciseHeader(current)
                        SetRowHeader(unit = uiState.units)

                        LightLazyScrollView(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            uniformItemHeightGridUnits = SET_LIST_ROW_UNITS,
                        ) {
                            val markers = buildMarkers(current.sets)
                            itemsIndexed(current.sets, key = { _, set -> set.id }) { index, set ->
                                val previous = current.previous.getOrNull(index)
                                SetRow(
                                    state = SetRowState(
                                        marker = markers[index],
                                        previous = previousSummary(previous, uiState.units),
                                        weight = set.weightKg?.let { Format.weight(it, uiState.units) },
                                        reps = set.reps?.toString(),
                                        active = set.id == uiState.activeSetId,
                                        completed = set.completedAt != null,
                                        warmup = set.type == SET_TYPE_WARMUP,
                                        failure = set.type == SET_TYPE_FAILURE,
                                        pr = set.id in uiState.prSetIds,
                                    ),
                                    onRowClick = { viewModel.onRowTapped(set.id) },
                                    onPreviousClick = previous?.let { { viewModel.autofill(set.id) } },
                                    onCheckToggle = { viewModel.toggleComplete(set.id) },
                                )
                            }
                            item(key = "add-set") {
                                AddSetRow(onClick = { viewModel.addSet() })
                            }
                        }

                        val activeSet = viewModel.activeSet(uiState)
                        if (activeSet != null) {
                            StepperStrip(
                                viewModel = viewModel,
                                uiState = uiState,
                                activeSet = activeSet,
                            )
                        }

                        if (uiState.exercises.size > 1) {
                            val nextIndex = (uiState.currentIndex + 1) % uiState.exercises.size
                            val next = uiState.exercises[nextIndex]
                            NextExerciseRow(
                                name = next.name,
                                setCount = next.sets.size,
                                onClick = { viewModel.advanceExercise() },
                            )
                        }

                        if (restRemaining > 0) {
                            RestStrip(remainingSec = restRemaining, onClick = openTimer)
                        }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.ALARM,
                            contentDescription = "rest timer",
                            onClick = openTimer,
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.ADD,
                            contentDescription = "add exercise",
                            onClick = {
                                navigateTo(::ExercisePickerScreen) { exerciseId ->
                                    viewModel.addExercise(exerciseId)
                                }
                            },
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.ACCEPT,
                            contentDescription = "finish workout",
                            onClick = { viewModel.finish() },
                        ),
                    ),
                )
            }
        }
    }

    private fun topBarSubtitle(uiState: ActiveWorkoutViewModel.UiState, nowMillis: Long): String {
        val elapsed = Format.elapsed(nowMillis - uiState.startedAt)
        if (uiState.exercises.isEmpty()) return elapsed
        return "$elapsed · exercise ${uiState.currentIndex + 1} of ${uiState.exercises.size}"
    }

    private companion object {
        const val CLOCK_TICK_MS = 500L
        const val SET_LIST_ROW_UNITS = 3f
    }
}

/** "1".."n" for work sets in order, "W" for warm-ups (design set-row numbering). */
private fun buildMarkers(sets: List<SetEntity>): List<String> {
    var workIndex = 0
    return sets.map { set ->
        if (set.type == SET_TYPE_WARMUP) "W" else (++workIndex).toString()
    }
}

/** "135 × 8" from a previous-performance set; weight-only and reps-only degrade gracefully. */
private fun previousSummary(previous: SetEntity?, units: WeightUnit): String? {
    previous ?: return null
    val weight = previous.weightKg
    val reps = previous.reps
    return when {
        weight != null && reps != null -> Format.setSummary(weight, reps, units)
        reps != null -> "× $reps"
        weight != null -> Format.weight(weight, units)
        else -> null
    }
}

@Composable
private fun ExerciseHeader(block: ActiveWorkoutViewModel.ExerciseBlock) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = block.baseName,
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = block.detailLine,
            variant = LightTextVariant.Detail,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        block.cue?.let { cue ->
            LightText(
                text = "CUE: $cue".uppercase(),
                variant = LightTextVariant.Superfine,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 3gu "+ ADD SET" row. The design shows 2gu, but the row lives inside the lazy list and
 * LightLazyScrollView's scrollbar math requires every item at the uniform 3gu height.
 */
@Composable
private fun AddSetRow(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3f.gridUnitsAsDp())
            .lightClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = "+ ADD SET", variant = LightTextVariant.Button, lighten = true)
    }
}

/**
 * Fixed strip between the set list and the rest strip; visible while a row is active.
 * The weight stepper gets the wider slot so "102.5" fits between its 4gu targets.
 */
@Composable
private fun StepperStrip(
    viewModel: ActiveWorkoutViewModel,
    uiState: ActiveWorkoutViewModel.UiState,
    activeSet: SetEntity,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.Bottom,
    ) {
        Stepper(
            onStep = viewModel::stepWeight,
            modifier = Modifier.weight(1.3f),
            label = "Weight (${uiState.units.suffix})",
            decrementEnabled = viewModel.weightDecrementEnabled(uiState),
        ) {
            LightText(
                text = activeSet.weightKg?.let { Format.weight(it, uiState.units) } ?: "—",
                variant = LightTextVariant.Copy,
                monospace = true,
                maxLines = 1,
            )
        }
        Stepper(
            onStep = viewModel::stepReps,
            modifier = Modifier.weight(1f),
            label = "Reps",
            decrementEnabled = viewModel.repsDecrementEnabled(uiState),
        ) {
            LightText(
                text = activeSet.reps?.toString() ?: "—",
                variant = LightTextVariant.Copy,
                monospace = true,
                maxLines = 1,
            )
        }
    }
}

/** Collapsed next-exercise row (2gu + 0.5gu top margin); tap advances (wraps around). */
@Composable
private fun NextExerciseRow(name: String, setCount: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 0.5f.gridUnitsAsDp())
            .height(2f.gridUnitsAsDp())
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LightText(
            text = name,
            variant = LightTextVariant.Copy,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        LightText(
            text = "$setCount Sets",
            variant = LightTextVariant.Detail,
            lighten = true,
            maxLines = 1,
            modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
        )
    }
}

/** 2gu rest strip above the bottom bar: remaining countdown, tap opens the timer screen. */
@Composable
private fun RestStrip(remainingSec: Int, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LightThemeTokens.colors.contentSecondary),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(2f.gridUnitsAsDp())
                .lightClickable(onClick = onClick)
                .padding(horizontal = 1f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LightText(text = "Rest ", variant = LightTextVariant.Detail)
                LightText(
                    text = Format.clock(remainingSec),
                    variant = LightTextVariant.Detail,
                    monospace = true,
                )
            }
            LightText(
                text = "TAP FOR TIMER",
                variant = LightTextVariant.Superfine,
                lighten = true,
            )
        }
    }
}

@Composable
private fun EmptyWorkoutBody(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LightText(
                text = "No exercises yet",
                variant = LightTextVariant.Copy,
                lighten = true,
            )
            LightText(
                text = "ADD ONE BELOW",
                variant = LightTextVariant.Superfine,
                lighten = true,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}
