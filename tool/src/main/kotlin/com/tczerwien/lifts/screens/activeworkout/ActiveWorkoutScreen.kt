package com.tczerwien.lifts.screens.activeworkout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tczerwien.lifts.data.Tracking
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.screens.ExerciseDetailScreen
import com.tczerwien.lifts.screens.ExerciseJumpScreen
import com.tczerwien.lifts.screens.ExercisePickerScreen
import com.tczerwien.lifts.screens.JumpEntry
import com.tczerwien.lifts.screens.PlateCalculatorScreen
import com.tczerwien.lifts.screens.RestTimerScreen
import com.tczerwien.lifts.screens.WorkoutCompleteResult
import com.tczerwien.lifts.screens.WorkoutCompleteScreen
import com.tczerwien.lifts.screens.WorkoutMenuResult
import com.tczerwien.lifts.screens.WorkoutMenuScreen
import com.tczerwien.lifts.session.ActiveSession
import com.tczerwien.lifts.session.SET_TYPE_DROP
import com.tczerwien.lifts.session.SET_TYPE_FAILURE
import com.tczerwien.lifts.session.SET_TYPE_NORMAL
import com.tczerwien.lifts.session.SET_TYPE_WARMUP
import com.tczerwien.lifts.session.pendingSetCount
import com.tczerwien.lifts.session.restDurationSec
import com.tczerwien.lifts.session.restRemainingSec
import com.tczerwien.lifts.session.rirOf
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.toolmanager.liftsShare
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.SetRow
import com.tczerwien.lifts.ui.SetRowHeader
import com.tczerwien.lifts.ui.SetRowState
import com.tczerwien.lifts.ui.rememberSecondClock
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter

/**
 * Active workout (design/screens/active-workout.html): one exercise at a time with its
 * set grid (PREVIOUS autofill, steppers on the active row, checkbox logging + PR stars),
 * a collapsed next-exercise row, and the rest strip. Top bar: back / name and clock / Finish,
 * which takes two taps and discards a workout with nothing logged. Bottom bar: rest timer / add
 * exercise / "…" (the exercise's note, rest, plates, details, name, removing a set or it; the
 * workout's exercise list, name, note, discard). The elapsed clock and rest remaining are
 * derived from wall-clock timestamps; each ticks in its own small composable, once a second,
 * and only while the app is in the foreground ([rememberSecondClock]).
 *
 * Constructor contract (FROZEN; [fromWorkoutId] added in B10 with a default): [workoutId] resumes; else
 * [templateId] creates the session from a template inside this screen; else [fromWorkoutId] starts a
 * past workout again (Workout Detail); all null starts an empty workout.
 */
class ActiveWorkoutScreen(
    private val sealedActivity: SealedLightActivity,
    /** Resume this workout when non-null; takes precedence over [templateId]. */
    private val workoutId: Long? = null,
    /** Create a new session from this template; null with null [workoutId] = empty workout. */
    private val templateId: Long? = null,
    /** Create a new session with this finished workout's exercises and set pattern ("Start again"). */
    private val fromWorkoutId: Long? = null,
) : LightScreen<Unit, ActiveWorkoutViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    private val settingsStore = SettingsStore(lightContext.dataStore)

    private val session = ActiveSession(repository, settingsStore, lightContext.dataStore)

    private val share = lightContext.liftsShare()

    override val viewModelClass: Class<ActiveWorkoutViewModel>
        get() = ActiveWorkoutViewModel::class.java

    override fun createViewModel() =
        ActiveWorkoutViewModel(repository, session, settingsStore, share, workoutId, templateId, fromWorkoutId)

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
                        // A result, so a screen that opened this one (Workout Detail's Start Again) can
                        // leave too: DONE means back to where the day started, not to the old workout.
                        WorkoutCompleteResult.DONE -> goBack(Unit)
                        WorkoutCompleteResult.RESUME -> viewModel.resumeAfterComplete()
                    }
                }
            }
        }
        LaunchedEffect(uiState.leave) {
            if (uiState.leave) {
                // Finished: a result, as DONE gives, so a Start Again's Workout Detail leaves too.
                if (uiState.leaveFinished) goBack(Unit) else goBack()
            }
        }

        val current = uiState.exercises.getOrNull(uiState.currentIndex)
        // resumeOnly from the rest strip: a rest that ends on the way shows REST OVER, not a new rest.
        val openTimer = { resumeOnly: Boolean ->
            // Another screen on top: an armed Finish must not still be armed when he comes back.
            viewModel.finishArm.disarm()
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
                    activeSetId = uiState.activeSetId,
                    resumeOnly = resumeOnly,
                )
            })
        }

        LiftsFrame(sealedActivity, lightContext, switchKey = uiState.weightEntry != null) {
            val entry = uiState.weightEntry
            if (entry != null && current != null) {
                WeightEntryEditor(entry, current.tracking, uiState)
                return@LiftsFrame
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                val live = uiState.loaded && !uiState.finishing && !uiState.leave
                TopBar(uiState, live, onTitleClick = { openExerciseList(uiState) })

                when {
                    !live -> Spacer(modifier = Modifier.weight(1f))

                    current == null -> EmptyWorkoutBody(modifier = Modifier.weight(1f))

                    else -> {
                        val activeSet = viewModel.activeSet(uiState)
                        val logging = activeSet != null
                        // The header's equipment line shows only while no row is logged (and the exercise
                        // has one): when it comes or goes, every row moves 48 px under his finger
                        // ([ActiveWorkoutViewModel.headerShifted]).
                        val lineShown = !logging && current.detailLine.isNotEmpty()
                        key(current.slot.id) {
                            var shownBefore by remember { mutableStateOf(lineShown) }
                            LaunchedEffect(lineShown) {
                                if (lineShown != shownBefore) {
                                    shownBefore = lineShown
                                    viewModel.headerShifted()
                                }
                            }
                        }
                        ExerciseHeader(
                            block = current,
                            logging = logging,
                            onOpenDetail = {
                                viewModel.finishArm.disarm()
                                viewModel.holdPosition()
                                navigateTo({ ExerciseDetailScreen(it, exerciseId = current.slot.exerciseId) })
                            },
                        )
                        SetRowHeader(unit = uiState.units, tracking = current.tracking, perSide = current.perSide)

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
                                val target = activeRowScrollTarget(activeIndex, fullyVisible.firstOrNull()?.index, fullyVisible.lastOrNull()?.index)
                                // A tick that scrolls the list slides another row under his finger: a
                                // double tap's second tap would tick it, or land on "+ ADD SET".
                                if (scrollMovesRows(target, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)) {
                                    viewModel.rowsScrolled()
                                }
                                target?.let { listState.animateScrollToItem(it) }
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
                                    val timed = current.tracking.takesDuration
                                    SetRow(
                                        state = SetRowState(
                                            marker = markers[index],
                                            previous = previousSummary(previous, uiState.units, current.tracking),
                                            // Help as its size under "-LBS", as the stepper and Strong show it.
                                            weight = current.tracking.shownWeight(seed.weightKg)?.let { Format.weight(it, uiState.units) },
                                            // A hold's seconds take the reps column, under "SEC".
                                            reps = (if (timed) seed.durationSec else seed.reps)?.toString(),
                                            weightShown = current.tracking.takesWeight,
                                            weightSeeded = set.weightKg == null && seed.weightKg != null,
                                            repsSeeded = if (timed) set.durationSec == null && seed.durationSec != null else set.reps == null && seed.reps != null,
                                            active = set.id == uiState.activeSetId,
                                            completed = set.completedAt != null,
                                            warmup = set.type == SET_TYPE_WARMUP,
                                            failure = set.type == SET_TYPE_FAILURE,
                                            drop = set.type == SET_TYPE_DROP,
                                            pr = set.id in uiState.prSetIds,
                                            rir = rirOf(set.rpe)?.toString(),
                                            rirShown = set.type != SET_TYPE_WARMUP && !timed,
                                        ),
                                        onRowClick = { viewModel.onRowTapped(set.id) },
                                        onMarkerClick = { viewModel.onMarkerTapped(set.id) },
                                        onPreviousClick = previous
                                            ?.takeIf { set.completedAt == null }
                                            ?.let { { viewModel.autofill(set.id) } },
                                        onRirClick = { viewModel.onRirTapped(set.id) },
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
                            tracking = current.tracking,
                            activeSet = activeSet,
                            seed = activeSet?.let { current.seeds[current.sets.indexOf(it)] },
                            perSide = current.perSide,
                        )

                        viewModel.nextExercise(uiState)?.let { next ->
                            NextExerciseRow(
                                name = next.name,
                                doneCount = next.sets.count { it.completedAt != null },
                                setCount = next.sets.size,
                                onClick = viewModel::goToNextExercise,
                            )
                        }

                    }
                }

                if (live) {
                    // A confirm or a hint borrows the rest slot for its few seconds, so nothing above moves.
                    val hint = when {
                        finishArmed -> finishHint(uiState)
                        uiState.emptyTickHint != null -> uiState.emptyTickHint
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
                                    viewModel.finishArm.disarm()
                                    viewModel.holdPosition()
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

    /**
     * Back / name and elapsed clock / Finish. A tap on the name opens the exercise list. The clock
     * ticks here, so only the top bar redraws each second.
     */
    @Composable
    private fun TopBar(uiState: ActiveWorkoutViewModel.UiState, live: Boolean, onTitleClick: () -> Unit) {
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
                    onClick = onTitleClick.takeIf { uiState.exercises.isNotEmpty() },
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

    /** The weight's keyboard: "Weight (lb)", "Added (lb)", "Assist (lb)"; "Not a weight (lb)" after text that isn't one. */
    @Composable
    private fun WeightEntryEditor(
        entry: ActiveWorkoutViewModel.WeightEntry,
        tracking: Tracking,
        uiState: ActiveWorkoutViewModel.UiState,
    ) {
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val textState = remember(entry.session) { TextFieldState(entry.prefill) }
        val what = when {
            entry.refused -> "Not a weight"
            tracking == Tracking.WEIGHTED_BODYWEIGHT -> "Added"
            tracking == Tracking.ASSISTED_BODYWEIGHT -> "Assist"
            else -> "Weight"
        }
        LightTextInputEditor(
            title = "$what (${uiState.units.suffix})",
            state = textState,
            onSubmit = { viewModel.submitWeightEntry(it.toString()) },
            onBack = viewModel::closeWeightEntry,
            keyboardOptionsFlow = keyboardOptionsFlow,
            singleLine = true,
            editorKey = "lifts-weight-${entry.session}",
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        )
    }

    /** The workout's exercises, to jump straight to one ([ExerciseJumpScreen]). */
    private fun openExerciseList(uiState: ActiveWorkoutViewModel.UiState) {
        viewModel.finishArm.disarm()
        viewModel.holdPosition()
        val entries = uiState.exercises.map { block ->
            JumpEntry(
                slotId = block.slot.id,
                name = block.name,
                doneCount = block.sets.count { it.completedAt != null },
                setCount = block.sets.size,
            )
        }
        val currentSlotId = uiState.exercises.getOrNull(uiState.currentIndex)?.slot?.id
        navigateTo({ ExerciseJumpScreen(it, entries, currentSlotId) }) { slotId -> viewModel.jumpTo(slotId) }
    }

    /**
     * The "…" menu ([WorkoutMenuScreen]). Its result acts on the exercise and rows it showed, even if
     * the screen moved on meanwhile; the ones that open another screen do so from here, as if tapped.
     */
    private fun openMenu(uiState: ActiveWorkoutViewModel.UiState) {
        viewModel.finishArm.disarm()
        viewModel.holdPosition()
        val options = viewModel.menuOptions(uiState)
        navigateTo({ WorkoutMenuScreen(it, options.content) }) { result ->
            when (result) {
                WorkoutMenuResult.RemoveSet -> options.removeSetId?.let(viewModel::removeSet)
                WorkoutMenuResult.RemoveExercise -> options.removeSlotId?.let(viewModel::removeExercise)
                WorkoutMenuResult.DiscardWorkout -> viewModel.discard()
                WorkoutMenuResult.Jump -> openExerciseList(viewModel.uiState.value)
                WorkoutMenuResult.ExerciseDetails -> options.exerciseId?.let { exerciseId ->
                    navigateTo({ ExerciseDetailScreen(it, exerciseId = exerciseId) })
                }
                WorkoutMenuResult.RenameExercise -> options.exerciseId?.let { exerciseId ->
                    navigateTo({ ExerciseDetailScreen(it, exerciseId = exerciseId, renameOnly = true) })
                }
                WorkoutMenuResult.Plates -> options.platesKg?.let { kg ->
                    navigateTo({ PlateCalculatorScreen(it, targetWeightKg = kg) })
                }
                is WorkoutMenuResult.RenameWorkout -> viewModel.renameWorkout(result.name)
                is WorkoutMenuResult.WorkoutNote -> viewModel.setWorkoutNotes(result.text)
                is WorkoutMenuResult.ExerciseNote -> options.slotId?.let { viewModel.setSlotNotes(it, result.text) }
                is WorkoutMenuResult.Rest -> options.slotId?.let { viewModel.setSlotRest(it, result.sec) }
            }
        }
    }

    /** "TAP AGAIN TO FINISH · 3 SETS NOT LOGGED"; with nothing logged, Finish discards and says so. */
    private fun finishHint(uiState: ActiveWorkoutViewModel.UiState): String {
        val sets = uiState.exercises.flatMap { it.sets }
        val pending = pendingSetCount(sets)
        return when {
            // Discarding takes his notes with it: say so.
            pending == sets.size && (!uiState.workoutNotes.isNullOrBlank() || uiState.exercises.any { !it.slot.notes.isNullOrBlank() }) ->
                "TAP AGAIN TO DISCARD · NOTES TOO"
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
