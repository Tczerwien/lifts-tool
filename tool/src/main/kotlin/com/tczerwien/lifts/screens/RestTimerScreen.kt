package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.session.ActiveSession
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.session.REST_PULSE_TOTAL_MS
import com.tczerwien.lifts.session.RestNext
import com.tczerwien.lifts.session.alignPrevious
import com.tczerwien.lifts.session.nextSetLabel
import com.tczerwien.lifts.session.previousForSlots
import com.tczerwien.lifts.session.restNextContext
import com.tczerwien.lifts.session.restRemainingSec
import com.tczerwien.lifts.session.seedFor
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFrame
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RestTimerViewModel(
    private val repository: LiftsRepository,
    private val session: ActiveSession,
    private val settingsStore: SettingsStore,
    private val requestedDurationSec: Int,
    /** The workout resting; null falls back to the open workout. */
    private val workoutId: Long?,
    /** The exercise on Active Workout's screen; its next unticked set is the one NEXT names. */
    private val slotId: Long?,
) : LightViewModel<Unit>() {

    data class UiState(
        val loaded: Boolean = false,
        /** Wall-clock end; remaining is derived against it every tick. */
        val endMillis: Long = 0,
        /** "NEXT · 165 × 5 @ 8": what a tick on the upcoming set would log, or an end-of-workout note. */
        val nextLine: String? = null,
        /** "Bench Press · Set 3 of 4", counting work sets only. */
        val nextContext: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val syncMutex = Mutex()

    @Volatile
    private var initialized = false

    /**
     * Re-derive from the persisted end on every show (process may have been away). This is also the
     * first load: the SDK calls it straight after creating the view model.
     */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        sync()
    }

    private fun sync() {
        viewModelScope.launch(Dispatchers.IO) {
            syncMutex.withLock {
                // Read behind any save still queued: a tick just before opening the timer writes its
                // own rest end, and a stepper tap changes what NEXT should show.
                LiftsIo.write {}.join()
                val now = System.currentTimeMillis()
                val persisted = session.restStateSnapshot()
                val endMillis = when {
                    !initialized && (persisted == null || persisted.endMillis <= now) -> {
                        // Nothing running (or a stale abandoned timer): start fresh.
                        session.startRest(requestedDurationSec, now)
                        now + requestedDurationSec.coerceAtLeast(0) * 1000L
                    }
                    persisted != null -> persisted.endMillis
                    else -> _uiState.value.endMillis
                }
                initialized = true
                val (nextLine, nextContext) = loadNextContext()
                _uiState.update {
                    it.copy(
                        loaded = true,
                        endMillis = endMillis,
                        nextLine = nextLine,
                        nextContext = nextContext,
                    )
                }
            }
        }
    }

    /** −30 / +30: shift the persisted end (never before now — over-subtracting finishes). */
    fun adjust(deltaSec: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val newEnd = session.adjustRest(deltaSec) ?: return@launch
            _uiState.update { it.copy(endMillis = newEnd) }
        }
    }

    /** Skip: clear the persisted timer, then leave (callback runs on Main). */
    fun skip(onCleared: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            session.clearRest()
            withContext(Dispatchers.Main) { onCleared() }
        }
    }

    /**
     * The NEXT lines: the next unticked set of the exercise on screen ([restNextContext]), with the
     * values a tick on it would log ([seedFor]) and its number among work sets ([nextSetLabel]).
     * A workout with no exercises, or with every set ticked, says so instead of showing nothing.
     */
    private fun loadNextContext(): Pair<String?, String?> {
        val workout = workoutId?.let { repository.workoutById(it) }
            ?: repository.listOpenWorkouts().firstOrNull()
            ?: return null to null
        val slots = repository.exercisesForWorkout(workout.id)
        val sets = repository.setsForWorkout(workout.id)
        val next = when (val context = restNextContext(slots, sets, slotId)) {
            RestNext.NoExercises -> return "NO EXERCISES YET" to "Add an exercise"
            RestNext.NoSets -> return "NO SETS YET" to "Add a set"
            RestNext.AllDone -> return "ALL SETS DONE" to "Finish or add an exercise"
            is RestNext.Next -> context
        }
        val exerciseId = next.slot.exerciseId
        val lastTime = previousForSlots(slots, session.previousGroupsByExercise(listOf(exerciseId)))[next.slot.id].orEmpty()
        val aligned = alignPrevious(next.rows, lastTime)
        val units = settingsStore.snapshot().units
        val seed = seedFor(next.rows, next.index, aligned, units)
        // Only the row's own RPE: a tick logs that, never last session's.
        val rpe = next.rows[next.index].rpe
        val summary = when {
            seed.weightKg != null && seed.reps != null -> Format.setSummary(seed.weightKg, seed.reps, units)
            seed.reps != null -> "× ${seed.reps}"
            seed.weightKg != null -> Format.weight(seed.weightKg, units)
            else -> null
        }?.let { values -> rpe?.let { "$values @ ${trimRpe(it)}" } ?: values }
        val exerciseName = repository.exerciseById(exerciseId)?.name ?: exerciseId
        return summary?.let { "NEXT · $it" } to "$exerciseName · ${nextSetLabel(next.rows, next.index)}"
    }

    private fun trimRpe(rpe: Double): String =
        if (rpe == rpe.toLong().toDouble()) rpe.toLong().toString() else rpe.toString()
}

/**
 * Rest timer (design/screens/rest-timer.html): giant mono countdown recomputed from the
 * persisted wall-clock end each tick, NEXT-set context, bottom bar −30 / Skip / +30.
 * At zero it reads REST OVER while the shared alert runs ([LiftsFrame]: beep and invert
 * pulse), then goes back. The ended rest stays saved, so Active Workout reads REST OVER too.
 *
 * Constructor contract (FROZEN): [durationSec] starts a fresh timer only when none is
 * already running; a live persisted timer is resumed so the screen can be re-entered.
 */
class RestTimerScreen(
    private val sealedActivity: SealedLightActivity,
    /** Requested rest length; the screen owns the wall-clock end timestamp (visual-only alerting). */
    private val durationSec: Int,
    /** The workout resting; null falls back to the open workout. */
    private val workoutId: Long? = null,
    /** The exercise on Active Workout's screen, which NEXT follows; null falls back to the last tick's. */
    private val slotId: Long? = null,
) : LightScreen<Unit, RestTimerViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    private val settingsStore = SettingsStore(lightContext.dataStore)

    private val session = ActiveSession(repository, settingsStore, lightContext.dataStore)

    override val viewModelClass: Class<RestTimerViewModel>
        get() = RestTimerViewModel::class.java

    override fun createViewModel() =
        RestTimerViewModel(repository, session, settingsStore, durationSec, workoutId, slotId)

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()

        var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                delay(TICK_MS)
            }
        }

        val remainingSec = if (uiState.loaded) restRemainingSec(uiState.endMillis, nowMillis) else -1
        val done = uiState.loaded && remainingSec == 0

        LaunchedEffect(done) {
            if (done) {
                // As long as the pulse, which LiftsFrame runs on this screen.
                delay(REST_PULSE_TOTAL_MS)
                goBack()
            }
        }

        LiftsFrame(sealedActivity, lightContext) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                if (done) {
                    // Bare bars during the pulse, per the design's done frame.
                    LightTopBar()
                } else {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                        ),
                        center = LightTopBarCenter.Text("Rest"),
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (uiState.loaded) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
                        ) {
                            LightText(
                                text = Format.clock(remainingSec.coerceAtLeast(0)),
                                variant = LightTextVariant.Title,
                                monospace = true,
                                maxLines = 1,
                            )
                            if (done) {
                                LightText(text = "REST OVER", variant = LightTextVariant.Button)
                            } else {
                                uiState.nextLine?.let { line ->
                                    LightText(
                                        text = line,
                                        variant = LightTextVariant.Detail,
                                        lighten = true,
                                        monospace = true,
                                        maxLines = 1,
                                    )
                                }
                                uiState.nextContext?.let { context ->
                                    LightText(
                                        text = context.uppercase(),
                                        variant = LightTextVariant.Superfine,
                                        lighten = true,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                if (done) {
                    LightBottomBar(items = emptyList())
                } else {
                    LightBottomBar(
                        items = listOf(
                            LightBarButton.Text(
                                text = "−30",
                                onClick = { viewModel.adjust(-ADJUST_STEP_SEC) },
                            ),
                            LightBarButton.Text(
                                text = "SKIP",
                                onClick = { viewModel.skip { goBack() } },
                            ),
                            LightBarButton.Text(
                                text = "+30",
                                onClick = { viewModel.adjust(ADJUST_STEP_SEC) },
                            ),
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        const val TICK_MS = 200L
        const val ADJUST_STEP_SEC = 30
    }
}
