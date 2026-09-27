package com.tczerwien.lifts.screens.activeworkout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.screens.ExercisePickerScreen
import com.tczerwien.lifts.screens.RestTimerScreen
import com.tczerwien.lifts.screens.WorkoutCompleteResult
import com.tczerwien.lifts.screens.WorkoutCompleteScreen
import com.tczerwien.lifts.screens.WorkoutMenuAction
import com.tczerwien.lifts.screens.WorkoutMenuScreen
import com.tczerwien.lifts.session.ActiveSession
import com.tczerwien.lifts.session.SET_TYPE_FAILURE
import com.tczerwien.lifts.session.SET_TYPE_NORMAL
import com.tczerwien.lifts.session.SET_TYPE_WARMUP
import com.tczerwien.lifts.session.pendingSetCount
import com.tczerwien.lifts.session.restDurationSec
import com.tczerwien.lifts.session.restRemainingSec
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.rememberSecondClock
import com.tczerwien.lifts.ui.SetRow
import com.tczerwien.lifts.ui.SetRowHeader
import com.tczerwien.lifts.ui.SetRowState
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter

/**
 * Active workout (design/screens/active-workout.html): one exercise at a time with its
 * set grid (PREVIOUS autofill, steppers on the active row, checkbox logging + PR stars),
 * a collapsed next-exercise row, and the rest strip. Top bar: back / name and clock / Finish,
 * which takes two taps and discards a workout with nothing logged. Bottom bar: rest timer / add
 * exercise / "…" (remove set, remove exercise, discard). The elapsed clock and rest remaining are
 * derived from wall-clock timestamps; each ticks in its own small composable, once a second,
 * and only while the app is in the foreground ([rememberSecondClock]).
 *
 * Constructor contract (FROZEN): [workoutId] resumes; else [templateId] creates the
 * session from a template inside this screen; both null starts an empty workout.
 */
class ActiveWorkoutScreen(
    private val sealedActivity: SealedLightActivity,
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
        val uiState by viewModel.uiState.collectAsState()

        val finishArmed by viewModel.finishArm.armed.collectAsState()

        LaunchedEffect(uiState.pendingCompleteWorkoutId) {
            uiState.pendingCompleteWorkoutId?.let { completedId ->
                viewModel.consumePendingComplete()
                navigateTo({ WorkoutCompleteScreen(it, workoutId = completedId) }) { result ->
                    // Delivered as Complete pops. DONE leaves straight away, so the finished workout is
                    // never drawn; with no result (system Back) the view model leaves instead.
                    when (result) {
                        WorkoutCompleteResult.DONE -> goBack()
                        WorkoutCompleteResult.RESUME -> viewModel.resumeAfterComplete()
                    }
                }
            }
        }
        LaunchedEffect(uiState.leave) {
            if (uiState.leave) goBack()
        }

        val current = uiState.exercises.getOrNull(uiState.currentIndex)
        // resumeOnly from the rest strip: a rest that ends on the way shows REST OVER, not a new rest.
        val openTimer = { resumeOnly: Boolean ->
            // Read the clock at the tap: nothing on this screen ticks except the small clocks.
            val restRemaining = uiState.restEndMillis?.let { restRemainingSec(it, System.currentTimeMillis()) } ?: 0
            val timerDurationSec = when {
                restRemaining > 0 && uiState.restTotalSec > 0 -> uiState.restTotalSec
                // A slot that rests 0 s (a superset's first half) has no rest of its own to offer;
                // the icon is a request for one, so it gets the default.
                current != null -> restDurationSec(
                    setType = SET_TYPE_NORMAL,
                    restSecWork = current.slot.restSecWork,
                    restSecWarmup = current.slot.restSecWarmup,
                    defaultRestSec = uiState.defaultRestSec,
                    warmupRestSec = uiState.warmupRestSec,
                ).takeIf { it > 0 } ?: uiState.defaultRestSec
                else -> uiState.defaultRestSec
            }
            navigateTo({
                RestTimerScreen(
                    it,
                    durationSec = timerDurationSec,
                    workoutId = uiState.workoutId,
                    slotId = current?.slot?.id,
                    resumeOnly = resumeOnly,
                )
            })
        }

        LiftsFrame(sealedActivity, lightContext) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                val live = uiState.loaded && !uiState.finishing && !uiState.leave
                TopBar(uiState, live)

                when {
                    !live -> Spacer(modifier = Modifier.weight(1f))

                    current == null -> EmptyWorkoutBody(modifier = Modifier.weight(1f))

                    else -> {
                        val activeSet = viewModel.activeSet(uiState)
                        ExerciseHeader(current, logging = activeSet != null)
                        SetRowHeader(unit = uiState.units)

                        // Each exercise gets its own scroll position, opening with the active row in view: the
                        // screen is rebuilt from nothing after the Rest Timer, and the next exercise
                        // must not open where the last one was scrolled to.
                        key(current.slot.id) {
                            val activeIndex = current.sets.indexOfFirst { it.id == uiState.activeSetId }
                            val listState = rememberLazyListState(initialFirstVisibleItemIndex = openingListIndex(activeIndex))
                            LaunchedEffect(uiState.activeSetId) {
                                val info = listState.layoutInfo
                                val fullyVisible = info.visibleItemsInfo.filter {
                                    it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset
                                }
                                activeRowScrollTarget(activeIndex, fullyVisible.firstOrNull()?.index, fullyVisible.lastOrNull()?.index)
                                    ?.let { listState.animateScrollToItem(it) }
                            }
                            // Inside: the scrollbar floats over the rows' 2gu end padding instead of taking
                            // 4gu from them once the list overflows, which slid them off their headers.
                            LightLazyScrollView(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                scrollBarPosition = LightScrollBarPosition.Inside,
                                listState = listState,
                                uniformItemHeightGridUnits = SET_LIST_ROW_UNITS,
                            ) {
                                val markers = buildMarkers(current.sets)
                                itemsIndexed(current.sets, key = { _, set -> set.id }) { index, set ->
                                    val previous = current.previous.getOrNull(index)
                                    // What a tick logs: the row's own values, else its starting values (dimmed).
                                    val seed = current.seeds[index]
                                    SetRow(
                                        state = SetRowState(
                                            marker = markers[index],
                                            previous = previousSummary(previous, uiState.units),
                                            weight = seed.weightKg?.let { Format.weight(it, uiState.units) },
                                            reps = seed.reps?.toString(),
                                            weightSeeded = set.weightKg == null && seed.weightKg != null,
                                            repsSeeded = set.reps == null && seed.reps != null,
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
                        }

                        StepperStrip(
                            viewModel = viewModel,
                            uiState = uiState,
                            activeSet = activeSet,
                            seed = activeSet?.let { current.seeds[current.sets.indexOf(it)] },
                        )

                        if (uiState.exercises.size > 1) {
                            val nextIndex = (uiState.currentIndex + 1) % uiState.exercises.size
                            val next = uiState.exercises[nextIndex]
                            NextExerciseRow(
                                name = next.name,
                                setCount = next.sets.size,
                                onClick = { viewModel.advanceExercise() },
                            )
                        }

                    }
                }

                if (live) {
                    // A confirm or a hint borrows the rest slot for its few seconds, so nothing above moves.
                    val hint = when {
                        finishArmed -> finishHint(uiState)
                        uiState.emptyTickHint -> "SET WEIGHT OR REPS FIRST"
                        else -> null
                    }
                    RestSlot(endMillis = uiState.restEndMillis, hint = hint, onOpenTimer = { openTimer(true) })
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.ALARM,
                            contentDescription = "rest timer",
                            onClick = { if (live) openTimer(false) },
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.ADD,
                            contentDescription = "add exercise",
                            onClick = {
                                if (live) {
                                    navigateTo(::ExercisePickerScreen) { exerciseId ->
                                        viewModel.addExercise(exerciseId)
                                    }
                                }
                            },
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.ELLIPSES,
                            contentDescription = "workout menu",
                            onClick = { if (live) openMenu(uiState) },
                        ),
                    ),
                )
            }
        }
    }

    /** Back / name and elapsed clock / Finish. The clock ticks here, so only the top bar redraws each second. */
    @Composable
    private fun TopBar(uiState: ActiveWorkoutViewModel.UiState, live: Boolean) {
        val nowMillis by rememberSecondClock(uiState.startedAt)
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = { goBack() },
            ),
            center = if (live) {
                LightTopBarCenter.TwoLineDetail(
                    line1 = uiState.workoutName,
                    line2 = topBarSubtitle(uiState, nowMillis),
                )
            } else {
                null
            },
            rightButton = if (live) {
                LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    contentDescription = "finish workout",
                    onClick = viewModel::tapFinish,
                )
            } else {
                null
            },
        )
    }

    private fun openMenu(uiState: ActiveWorkoutViewModel.UiState) {
        viewModel.finishArm.disarm()
        val options = viewModel.menuOptions(uiState)
        navigateTo({
            WorkoutMenuScreen(
                it,
                removeSetLabel = options.removeSetLabel,
                removeExerciseLabel = options.removeExerciseLabel,
            )
        }) { action ->
            when (action) {
                WorkoutMenuAction.REMOVE_SET -> options.removeSetId?.let(viewModel::removeSet)
                WorkoutMenuAction.REMOVE_EXERCISE -> options.removeSlotId?.let(viewModel::removeExercise)
                WorkoutMenuAction.DISCARD_WORKOUT -> viewModel.discard()
            }
        }
    }

    /** "TAP AGAIN TO FINISH · 3 SETS NOT LOGGED"; with nothing logged, Finish discards and says so. */
    private fun finishHint(uiState: ActiveWorkoutViewModel.UiState): String {
        val sets = uiState.exercises.flatMap { it.sets }
        val pending = pendingSetCount(sets)
        return when {
            pending == sets.size -> "TAP AGAIN TO DISCARD · NOTHING LOGGED"
            pending == 1 -> "TAP AGAIN TO FINISH · 1 SET NOT LOGGED"
            pending > 1 -> "TAP AGAIN TO FINISH · $pending SETS NOT LOGGED"
            else -> "TAP AGAIN TO FINISH"
        }
    }

    private fun topBarSubtitle(uiState: ActiveWorkoutViewModel.UiState, nowMillis: Long): String {
        val elapsed = Format.elapsed(nowMillis - uiState.startedAt)
        if (uiState.exercises.isEmpty()) return elapsed
        return "$elapsed · exercise ${uiState.currentIndex + 1} of ${uiState.exercises.size}"
    }

    private companion object {
        const val SET_LIST_ROW_UNITS = 3f
    }
}
