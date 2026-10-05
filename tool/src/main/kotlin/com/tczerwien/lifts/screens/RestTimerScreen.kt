package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.analytics.NotationStyle
import com.tczerwien.lifts.analytics.setNotation
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.Tracking
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.data.tracking
import com.tczerwien.lifts.session.ActiveSession
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.session.REST_PULSE_TOTAL_MS
import com.tczerwien.lifts.session.RestNext
import com.tczerwien.lifts.session.alignPrevious
import com.tczerwien.lifts.session.nextSetLabel
import com.tczerwien.lifts.session.openingRestEnd
import com.tczerwien.lifts.session.previousForSlots
import com.tczerwien.lifts.session.restAdjustable
import com.tczerwien.lifts.session.restNextContext
import com.tczerwien.lifts.session.restRemainingSec
import com.tczerwien.lifts.session.seedFor
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.UNKNOWN_EXERCISE
import com.tczerwien.lifts.ui.displayName
import com.tczerwien.lifts.ui.msUntilNextSecond
import com.tczerwien.lifts.ui.rememberSecondClock
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
    /** The exercise on Active Workout's screen; NEXT names a set of it while one is unticked ([restNextContext]). */
    private val slotId: Long?,
    /** The row Active Workout's steppers are on: NEXT names it while it is unticked. */
    private val activeSetId: Long?,
    /** Opened from the rest strip: resume the saved rest, never start a fresh one. */
    private val resumeOnly: Boolean,
) : LightViewModel<Unit>() {

    data class UiState(
        val loaded: Boolean = false,
        /** Wall-clock end; remaining is derived against it every tick. */
        val endMillis: Long = 0,
        /** "NEXT · 165 × 5 RIR 2": what a tick on the upcoming set would log, or an end-of-workout note. */
        val nextLine: String? = null,
        /** "Bench Press", the exercise of [nextContext]; cut short before the set's number is. */
        val nextExercise: String? = null,
        /** "Set 3 of 4", counting work sets only, or what to do when there is no next set ("Add a set"). */
        val nextContext: String? = null,
    )

    /** The NEXT lines ([loadNextContext]). */
    private data class NextLines(val line: String? = null, val exercise: String? = null, val context: String? = null)

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
                // Skipped: the rest is cleared and the screen is leaving; a load now must not start one.
                if (skipped) return@withLock
                // Read behind any save still queued: a tick just before opening the timer writes its
                // own rest end, and a stepper tap changes what NEXT should show.
                LiftsIo.write {}.join()
                val now = System.currentTimeMillis()
                val persisted = session.restStateSnapshot()
                val endMillis = when {
                    !initialized -> openingRestEnd(persisted?.endMillis, now, resumeOnly) ?: run {
                        // On the lane, as Skip's clear is: whichever was tapped first lands first, and a
                        // rest skipped before this load got here is not started.
                        LiftsIo.write { if (!skipped) session.startRest(requestedDurationSec, now) }.join()
                        now + requestedDurationSec.coerceAtLeast(0) * 1000L
                    }
                    persisted != null -> persisted.endMillis
                    else -> _uiState.value.endMillis
                }
                initialized = true
                val next = loadNextContext()
                _uiState.update {
                    it.copy(
                        loaded = true,
                        endMillis = endMillis,
                        nextLine = next.line,
                        nextExercise = next.exercise,
                        nextContext = next.context,
                    )
                }
            }
        }
    }

    /**
     * −30 / +30: shift the persisted end (never before now — over-subtracting finishes). In the last
     * half second and at 0:00 a tap does nothing, here and in the store ([restAdjustable]). Saved on
     * the write lane, so Back right after the tap can't drop it.
     */
    fun adjust(deltaSec: Int) {
        if (!restAdjustable(_uiState.value.endMillis, System.currentTimeMillis())) return
        LiftsIo.write({ session.adjustRest(deltaSec) }) { newEnd ->
            newEnd?.let { end -> _uiState.update { it.copy(endMillis = end) } }
        }
    }

    /** Set by [skip]: no load after it may start a rest. */
    @Volatile
    private var skipped = false

    /**
     * Skip: clear the persisted timer, then leave (callback runs on Main). Marked first, and cleared on
     * the write lane, where the first load starts its rest: a Skip tapped before the timer had loaded
     * can't be undone by that load, and Back right after can't cancel the clear (it isn't this screen's).
     */
    fun skip(onCleared: () -> Unit) {
        skipped = true
        LiftsIo.write({ session.clearRest() }) { withContext(Dispatchers.Main) { onCleared() } }
    }

    /**
     * The NEXT lines: the next unticked set of the exercise on screen ([restNextContext]), with the
     * values a tick on it would log ([seedFor]) and its number among work sets ([nextSetLabel]).
     * A workout with no exercises, or with every set ticked, says so instead of showing nothing.
     */
    private fun loadNextContext(): NextLines {
        val workout = workoutId?.let { repository.workoutById(it) }
            ?: repository.listOpenWorkouts().firstOrNull()
            ?: return NextLines()
        val slots = repository.exercisesForWorkout(workout.id)
        val sets = repository.setsForWorkout(workout.id)
        val next = when (val context = restNextContext(slots, sets, slotId, activeSetId)) {
            RestNext.NoExercises -> return NextLines("NO EXERCISES YET", context = "Add an exercise")
            RestNext.NoSets -> return NextLines("NO SETS YET", context = "Add a set")
            RestNext.AllDone -> return NextLines("ALL SETS DONE", context = "Finish or add an exercise")
            is RestNext.Next -> context
        }
        val exerciseId = next.slot.exerciseId
        val exercise = repository.exerciseById(exerciseId)
        val lastTime = previousForSlots(slots, session.previousGroupsByExercise(listOf(exerciseId)))[next.slot.id].orEmpty()
        val aligned = alignPrevious(next.rows, lastTime)
        val settings = settingsStore.snapshot()
        val units = settings.units
        val seed = seedFor(next.rows, next.index, aligned, units, exercise?.tracking ?: Tracking.WEIGHT_REPS, settings.weightStep)
        // Only the row's own RPE: a tick logs that, never last session's.
        val rpe = next.rows[next.index].rpe
        val summary = setNotation(
            seed.weightKg, seed.reps, units, NotationStyle.SINGLE,
            durationSec = seed.durationSec, rpe = rpe, perSide = exercise?.perSide == true,
        )
        val exerciseName = exercise?.let { displayName(it) } ?: UNKNOWN_EXERCISE
        return NextLines(summary?.let { "NEXT · $it" }, exerciseName, nextSetLabel(next.rows, next.index))
    }
}

/**
 * Rest timer (design/screens/rest-timer.html): giant mono countdown recomputed from the
 * persisted wall-clock end each tick, NEXT-set context, bottom bar −30 / Skip / +30.
 * At zero it reads REST OVER while the shared alert runs ([LiftsFrame]: beep and invert
 * pulse), then goes back. The ended rest stays saved, so Active Workout reads REST OVER too.
 *
 * Constructor contract (FROZEN): [durationSec] starts a fresh timer only when none is
 * already running; a live persisted timer is resumed so the screen can be re-entered.
 * With [resumeOnly] (a tap on the rest strip) it never starts one: a rest that ended on the
 * way here shows REST OVER and goes back.
 */
class RestTimerScreen(
    private val sealedActivity: SealedLightActivity,
    /** Rest length for a fresh timer; the end is saved as a wall-clock timestamp (RestStore). */
    private val durationSec: Int,
    /** The workout resting; null falls back to the open workout. */
    private val workoutId: Long? = null,
    /** The exercise on Active Workout's screen, which NEXT follows; null falls back to the last tick's. */
    private val slotId: Long? = null,
    /** The row Active Workout's steppers are on, which NEXT names while it is unticked. */
    private val activeSetId: Long? = null,
    /** Resume the saved rest only; true from the rest strip, false from the alarm icon. */
    private val resumeOnly: Boolean = false,
) : LightScreen<Unit, RestTimerViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    private val settingsStore = SettingsStore(lightContext.dataStore)

    private val session = ActiveSession(repository, settingsStore, lightContext.dataStore)

    override val viewModelClass: Class<RestTimerViewModel>
        get() = RestTimerViewModel::class.java

    override fun createViewModel() =
        RestTimerViewModel(repository, session, settingsStore, durationSec, workoutId, slotId, activeSetId, resumeOnly)

    /**
     * False once this screen has been popped. Nothing opens on top of the timer, so hiding means
     * leaving; a goBack after that would pop Active Workout too.
     */
    @Volatile
    private var onTop = true

    override fun willShow() {
        onTop = true
    }

    override fun willHide() {
        onTop = false
    }

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()

        // Latched as the rest ends: once 0:00 shows, a late +30 result can't bring the countdown back
        // mid-pulse. Checked on the countdown's own second boundaries ([msUntilNextSecond]), not by a
        // clock read here, so only the countdown redraws each second ([Countdown]). Never one long
        // wait: delays count uptime, which stops while the phone sleeps, so a rest that ended with the
        // screen off is caught within a second of waking. Frames time it too: in the background it waits.
        var latched by remember { mutableStateOf(false) }
        val loaded = uiState.loaded
        val endMillis = uiState.endMillis
        LaunchedEffect(loaded, endMillis) {
            if (!loaded) return@LaunchedEffect
            while (!latched) {
                val now = withFrameMillis { System.currentTimeMillis() }
                if (restRemainingSec(endMillis, now) == 0) latched = true else delay(msUntilNextSecond(endMillis, now))
            }
        }
        // A rest already over as the timer loads (opened from the strip at 0:01) shows REST OVER on its
        // first frame, not the running bars for a frame first: one clock read as it loads, not a clock.
        val overAtLoad = remember(loaded, endMillis) { loaded && restRemainingSec(endMillis, System.currentTimeMillis()) == 0 }
        val done = latched || overAtLoad

        LaunchedEffect(done) {
            if (done) {
                // As long as the pulse, which LiftsFrame runs on this screen. Back may have left meanwhile.
                delay(REST_PULSE_TOTAL_MS)
                if (onTop) goBack()
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
                            Countdown(endMillis = uiState.endMillis, done = done)
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
                                    NextContextLine(exercise = uiState.nextExercise, context = context)
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
                                // Back may have left meanwhile: a goBack then would pop Active Workout.
                                onClick = { viewModel.skip { if (onTop) goBack() } },
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
        const val ADJUST_STEP_SEC = 30
    }
}

/**
 * The rest's minutes and seconds, 0:00 once [done]. It alone reads the second clock
 * ([rememberSecondClock]), so the rest of the Rest Timer doesn't redraw every second.
 */
@Composable
private fun Countdown(endMillis: Long, done: Boolean) {
    val nowMillis by rememberSecondClock(endMillis)
    LightText(
        text = Format.clock(if (done) 0 else restRemainingSec(endMillis, nowMillis)),
        variant = LightTextVariant.Title,
        monospace = true,
        maxLines = 1,
    )
}

/**
 * "BENCH PRESS · SET 3 OF 4" on one line: a name too long for it is cut short with an ellipsis, so
 * the set's number always shows whole.
 */
@Composable
private fun NextContextLine(exercise: String?, context: String) {
    Row(
        modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (exercise != null) {
            LightText(
                text = exercise.uppercase(),
                variant = LightTextVariant.Superfine,
                modifier = Modifier.weight(1f, fill = false),
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LightText(
            text = (if (exercise != null) " · $context" else context).uppercase(),
            variant = LightTextVariant.Superfine,
            lighten = true,
            maxLines = 1,
        )
    }
}
