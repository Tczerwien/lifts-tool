package com.tczerwien.lifts.screens.activeworkout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.screens.ExercisePickerScreen
import com.tczerwien.lifts.screens.RestTimerScreen
import com.tczerwien.lifts.screens.WorkoutCompleteScreen
import com.tczerwien.lifts.session.ActiveSession
import com.tczerwien.lifts.session.SET_TYPE_FAILURE
import com.tczerwien.lifts.session.SET_TYPE_NORMAL
import com.tczerwien.lifts.session.SET_TYPE_WARMUP
import com.tczerwien.lifts.session.restDurationSec
import com.tczerwien.lifts.session.restRemainingSec
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.SetRow
import com.tczerwien.lifts.ui.SetRowHeader
import com.tczerwien.lifts.ui.SetRowState
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import kotlinx.coroutines.delay

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

    private val repository = lightContext.liftsRepository()

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
                                    onMarkerClick = { viewModel.onMarkerTapped(set.id) },
                                    onPreviousClick = previous
                                        ?.takeIf { set.completedAt == null }
                                        ?.let { { viewModel.autofill(set.id) } },
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
