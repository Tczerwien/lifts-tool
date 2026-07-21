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
import com.tczerwien.lifts.data.LiftsDatabase
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.session.ActiveSession
import com.tczerwien.lifts.session.nextPendingSet
import com.tczerwien.lifts.session.restRemainingSec
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.ui.Format
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightColors
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
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
) : LightViewModel<Unit>() {

    data class UiState(
        val loaded: Boolean = false,
        /** Wall-clock end; remaining is derived against it every tick. */
        val endMillis: Long = 0,
        /** "NEXT · 165 × 5 @ 8" — previous performance of the upcoming set. */
        val nextLine: String? = null,
        /** "Bench Press · Set 3 of 4" */
        val nextContext: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val syncMutex = Mutex()

    @Volatile
    private var initialized = false

    init {
        sync()
    }

    /** Re-derive from the persisted end on every show (process may have been away). */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        sync()
    }

    private fun sync() {
        viewModelScope.launch(Dispatchers.IO) {
            syncMutex.withLock {
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

    /** Called by the pulse effect at zero so a finished rest never leaves stale keys. */
    suspend fun clearRestNow() {
        session.clearRest()
    }

    /** NEXT-set context from the in-progress workout, target = previous performance. */
    private fun loadNextContext(): Pair<String?, String?> {
        val workout = repository.listWorkouts().firstOrNull { it.endedAt == null }
            ?: return null to null
        val slots = repository.exercisesForWorkout(workout.id)
        val sets = repository.setsForWorkout(workout.id)
        val next = nextPendingSet(slots, sets) ?: return null to null
        val units = settingsStore.snapshot().units
        val exerciseName = repository.exerciseById(next.slot.exerciseId)?.name ?: next.slot.exerciseId
        val target = repository.previousPerformance(next.slot.exerciseId)
            .getOrNull(next.setNumber - 1) ?: next.set
        val summary = target.weightKg?.let { weight ->
            target.reps?.let { reps ->
                buildString {
                    append(Format.setSummary(weight, reps, units))
                    target.rpe?.let { append(" @ ${trimRpe(it)}") }
                }
            }
        }
        val nextLine = summary?.let { "NEXT · $it" }
        val nextContext = "$exerciseName · Set ${next.setNumber} of ${next.setCount}"
        return nextLine to nextContext
    }

    private fun trimRpe(rpe: Double): String =
        if (rpe == rpe.toLong().toDouble()) rpe.toLong().toString() else rpe.toString()
}

/**
 * Rest timer (design/screens/rest-timer.html): giant mono countdown recomputed from the
 * persisted wall-clock end each tick, NEXT-set context, bottom bar −30 / Skip / +30.
 * At zero the whole screen invert-pulses three times, clears the persisted timer, and
 * goes back — the platform has no sound/vibration for tools, so alerting is visual only.
 *
 * Constructor contract (FROZEN): [durationSec] starts a fresh timer only when none is
 * already running; a live persisted timer is resumed so the screen can be re-entered.
 */
class RestTimerScreen(
    sealedActivity: SealedLightActivity,
    /** Requested rest length; the screen owns the wall-clock end timestamp (visual-only alerting). */
    private val durationSec: Int,
) : LightScreen<Unit, RestTimerViewModel>(sealedActivity) {

    private val repository = LiftsRepository.getInstance {
        lightContext.buildDatabase(LiftsDatabase::class.java, LiftsRepository.DATABASE_NAME)
    }

    private val settingsStore = SettingsStore(lightContext.dataStore)

    private val session = ActiveSession(repository, settingsStore, lightContext.dataStore)

    override val viewModelClass: Class<RestTimerViewModel>
        get() = RestTimerViewModel::class.java

    override fun createViewModel() =
        RestTimerViewModel(repository, session, settingsStore, durationSec)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
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

        var inverted by remember { mutableStateOf(false) }
        LaunchedEffect(done) {
            if (done) {
                viewModel.clearRestNow()
                repeat(PULSE_COUNT) {
                    inverted = true
                    delay(PULSE_INTERVAL_MS)
                    inverted = false
                    delay(PULSE_INTERVAL_MS)
                }
                goBack()
            }
        }

        val colors = if (inverted) invertedColors(themeColors) else themeColors
        LightTheme(colors = colors) {
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
        const val PULSE_COUNT = 3
        const val PULSE_INTERVAL_MS = 300L
    }
}

/** Swap background and content for the full-screen alert pulse. */
private fun invertedColors(base: LightColors): LightColors = LightColors(
    background = base.content,
    content = base.background,
    contentSecondary = base.background,
)
