package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.screens.activeworkout.ActiveWorkoutScreen
import com.tczerwien.lifts.session.ActiveSession
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.session.shouldAutoResume
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.toolmanager.LiftsEntryPoint
import com.tczerwien.lifts.toolmanager.LiftsShare
import com.tczerwien.lifts.toolmanager.liftsShare
import com.tczerwien.lifts.ui.ArmedAction
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.SectionLabel
import com.tczerwien.lifts.ui.rememberBarChartIconPainter
import com.tczerwien.lifts.ui.rememberScaleIconPainter
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: LiftsRepository,
    private val session: ActiveSession,
    private val share: LiftsShare,
) : LightViewModel<Unit>() {

    data class TemplateRow(val id: Long, val name: String, val exerciseCount: Int)

    data class UnfinishedRow(val workoutId: Long, val name: String, val startedAt: Long)

    data class RecentRow(val workoutId: Long, val name: String, val dateMillis: Long, val setCount: Int)

    /** A file uploaded to Tool Manager › Lifts › Restore, waiting to be checked. */
    data class RestoreWaiting(val name: String, val path: String)

    /** A Start row tapped while a workout is open; [templateId] null is Empty Workout. */
    data class StartChoice(val templateId: Long?)

    data class UiState(
        val templates: List<TemplateRow> = emptyList(),
        val unfinished: UnfinishedRow? = null,
        val recent: List<RecentRow> = emptyList(),
        /** Expanded under its Start row: "Resume <open workout>" or "Discard and start". */
        val startChoice: StartChoice? = null,
        val restoreWaiting: RestoreWaiting? = null,
        /** One-shot: Content opens this workout straight away (a cold start mid-workout). */
        val autoResumeWorkoutId: Long? = null,
        /**
         * Loaded since Home last showed. Until then [unfinished] may be stale (Back from a workout
         * started while none was open), so a Start tap could open a second workout; it is ignored.
         */
        val ready: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** "Discard and start" takes two taps. */
    val discardAndStartArm = ArmedAction(viewModelScope)

    /** Auto-resume is considered on the first load only: a cold start, not a Back from the workout. */
    @Volatile
    private var firstLoad = true

    @Volatile
    private var showing = false

    /** The newest load asked for. Loads read in that order on the lane, so it has the freshest data. */
    @Volatile
    private var latestLoad = 0L

    /** Guards [appliedLoad], the newest load on screen. */
    private val loadLock = Any()
    private var appliedLoad = 0L

    init {
        // An upload through Tool Manager while Home is on screen shows up without leaving Home.
        viewModelScope.launch(Dispatchers.IO) {
            LiftsEntryPoint.inbox.drop(1).collect {
                if (showing) {
                    val waiting = share.waitingRestore()?.let { RestoreWaiting(it.name, it.absolutePath) }
                    _uiState.update { it.copy(restoreWaiting = waiting) }
                }
            }
        }
    }

    /**
     * Loads on every show, on the write lane: that orders it after anything Active Workout queued on
     * its way out (such as discarding an untouched workout), and the cleanup below writes too.
     */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        showing = true
        val ticket = ++latestLoad
        _uiState.update { it.copy(ready = false) }
        LiftsIo.write({ load() }) { loaded ->
            share.homeShown()
            // Loads land in any order: an older one never replaces a newer one, and Home is ready
            // for Start taps only once the newest has landed.
            synchronized(loadLock) {
                if (ticket < appliedLoad) return@write
                appliedLoad = ticket
                _uiState.update { current ->
                    loaded.copy(
                        ready = ticket == latestLoad,
                        startChoice = current.startChoice.takeIf { loaded.unfinished != null },
                        // A cold start the lifter already left Home from (History, say) stays put.
                        autoResumeWorkoutId = loaded.autoResumeWorkoutId.takeIf { showing },
                    )
                }
            }
        }
    }

    /** Leaving Home drops a pending auto-resume and any open Resume / Discard choice. */
    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        showing = false
        discardAndStartArm.disarm()
        _uiState.update { it.copy(autoResumeWorkoutId = null, startChoice = null) }
    }

    /**
     * A Start row: starts at once (true), unless a workout is open, when it offers Resume or Discard
     * and start. Ignored until Home has loaded, so a quick tap can't miss the open workout.
     */
    fun onStartTapped(templateId: Long?): Boolean {
        val state = _uiState.value
        if (!state.ready) return false
        if (state.unfinished == null) return true
        discardAndStartArm.disarm()
        val choice = StartChoice(templateId).takeIf { it != state.startChoice }
        _uiState.update { it.copy(startChoice = choice) }
        return false
    }

    fun clearStartChoice() {
        discardAndStartArm.disarm()
        _uiState.update { it.copy(startChoice = null) }
    }

    /**
     * Second tap of "Discard and start": queues the delete of the open workout and returns true, and
     * the caller opens the new one. Its creation is queued behind the delete on the same lane.
     * Ignored until Home has loaded, so rows left over from before can't delete a finished workout.
     */
    fun tapDiscardAndStart(): Boolean {
        val state = _uiState.value
        if (!state.ready) return false
        val open = state.unfinished ?: return true
        if (!discardAndStartArm.tap()) return false
        _uiState.update { it.copy(startChoice = null, unfinished = null) }
        LiftsIo.write { session.discardWorkout(open.workoutId) }
        return true
    }

    fun consumeAutoResume() {
        _uiState.update { it.copy(autoResumeWorkoutId = null) }
    }

    private fun load(): UiState {
        repository.seedIfEmpty()
        val now = System.currentTimeMillis()
        val open = session.tidyOpenWorkouts(now)
        val autoResume = firstLoad && open != null && shouldAutoResume(session.lastActivity(open), now)
        firstLoad = false
        val templates = repository.listTemplates().map { template ->
            TemplateRow(
                id = template.id,
                name = template.name,
                exerciseCount = repository.exercisesForTemplate(template.id).size,
            )
        }
        val recent = repository.finishedWorkoutsWithSetCounts(limit = 2).map { (workout, completedSets) ->
            RecentRow(
                workoutId = workout.id,
                name = workout.name,
                dateMillis = workout.endedAt ?: workout.startedAt,
                setCount = completedSets,
            )
        }
        return UiState(
            restoreWaiting = share.waitingRestore()?.let { RestoreWaiting(it.name, it.absolutePath) },
            templates = templates,
            unfinished = open?.let { UnfinishedRow(workoutId = it.id, name = it.name, startedAt = it.startedAt) },
            recent = recent,
            autoResumeWorkoutId = open?.id?.takeIf { autoResume },
        )
    }
}

/**
 * Home (design/screens/home.html): start a template, an empty workout, or resume; two
 * most recent finished workouts; bottom bar to History / Bodyweight / Consistency /
 * Settings. Top-bar right (pencil) manages templates. Only one workout is ever open: while
 * one is, a Start row offers to resume it or (two taps) discard it and start. On a cold
 * start with a workout used in the last two hours, Home opens it straight away.
 */
@InitialScreen
class HomeScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    private val session = ActiveSession(repository, SettingsStore(lightContext.dataStore), lightContext.dataStore)

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel() = HomeViewModel(repository, session, lightContext.liftsShare())

    /** Opens a new workout from [templateId] (null: empty). */
    private fun openNew(templateId: Long?) {
        navigateTo({ ActiveWorkoutScreen(it, templateId = templateId) })
    }

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()
        val discardArmed by viewModel.discardAndStartArm.armed.collectAsState()

        LaunchedEffect(uiState.autoResumeWorkoutId) {
            uiState.autoResumeWorkoutId?.let { workoutId ->
                viewModel.consumeAutoResume()
                navigateTo({ ActiveWorkoutScreen(it, workoutId = workoutId) })
            }
        }

        fun start(templateId: Long?) {
            if (viewModel.onStartTapped(templateId)) openNew(templateId)
        }

        @Composable
        fun choiceUnder(templateId: Long?) {
            val unfinished = uiState.unfinished ?: return
            if (uiState.startChoice != HomeViewModel.StartChoice(templateId)) return
            StartChoiceRows(
                openName = unfinished.name,
                discardArmed = discardArmed,
                onResume = {
                    viewModel.clearStartChoice()
                    navigateTo({ ActiveWorkoutScreen(it, workoutId = unfinished.workoutId) })
                },
                onDiscardAndStart = {
                    if (viewModel.tapDiscardAndStart()) openNew(templateId)
                },
            )
        }

        LiftsFrame(sealedActivity, lightContext) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    center = LightTopBarCenter.Text("LIFTS"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.PENCIL,
                        onClick = { navigateTo(::TemplatesScreen) },
                    ),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    uiState.restoreWaiting?.let { waiting ->
                        SectionLabel("Restore")
                        LiftsListRow(
                            onClick = { navigateTo({ RestoreScreen(it, waiting.path, fromUpload = true) }) },
                            left = {
                                LightText(
                                    text = waiting.name,
                                    variant = LightTextVariant.Copy,
                                    monospace = true,
                                    maxLines = 1,
                                )
                            },
                            right = {
                                LightText(
                                    text = "Check",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    maxLines = 1,
                                )
                            },
                        )
                    }

                    uiState.unfinished?.let { unfinished ->
                        SectionLabel("In Progress")
                        LiftsListRow(
                            onClick = {
                                navigateTo({ ActiveWorkoutScreen(it, workoutId = unfinished.workoutId) })
                            },
                            left = {
                                LightText(
                                    text = unfinished.name,
                                    variant = LightTextVariant.Copy,
                                    maxLines = 1,
                                )
                            },
                            right = {
                                LightText(
                                    text = "Resume · ${Format.dateShort(unfinished.startedAt)}",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    maxLines = 1,
                                )
                            },
                        )
                    }

                    SectionLabel("Start")

                    uiState.templates.forEach { template ->
                        LiftsListRow(
                            onClick = { start(template.id) },
                            left = {
                                LightText(
                                    text = template.name,
                                    variant = LightTextVariant.Copy,
                                    maxLines = 1,
                                )
                            },
                            right = {
                                LightText(
                                    text = "${template.exerciseCount} exercises",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    maxLines = 1,
                                )
                            },
                        )
                        choiceUnder(template.id)
                        RowDivider()
                    }

                    LiftsListRow(
                        onClick = { start(null) },
                        left = {
                            LightText(
                                text = "Empty Workout",
                                variant = LightTextVariant.Copy,
                                lighten = true,
                                maxLines = 1,
                            )
                        },
                        right = { LightIcon(icon = LightIcons.ADD, size = 1.6f) },
                    )
                    choiceUnder(null)

                    if (uiState.recent.isNotEmpty()) {
                        SectionLabel("Recent")
                        uiState.recent.forEachIndexed { index, recent ->
                            if (index > 0) RowDivider()
                            LiftsListRow(
                                onClick = {
                                    navigateTo({ WorkoutDetailScreen(it, workoutId = recent.workoutId) })
                                },
                                left = {
                                    LightText(
                                        text = recent.name,
                                        variant = LightTextVariant.Copy,
                                        maxLines = 1,
                                    )
                                },
                                right = {
                                    LightText(
                                        text = "${Format.dateShort(recent.dateMillis)} · ${recent.setCount} sets",
                                        variant = LightTextVariant.Detail,
                                        lighten = true,
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.LIST,
                            contentDescription = "history",
                            onClick = { navigateTo(::HistoryScreen) },
                        ),
                        LightBarButton.Icon(
                            painter = rememberScaleIconPainter(),
                            contentDescription = "bodyweight",
                            onClick = { navigateTo(::BodyweightScreen) },
                        ),
                        LightBarButton.Icon(
                            painter = rememberBarChartIconPainter(),
                            contentDescription = "consistency",
                            onClick = { navigateTo(::ConsistencyScreen) },
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.ELLIPSES,
                            contentDescription = "settings",
                            onClick = { navigateTo(::SettingsScreen) },
                        ),
                    ),
                )
            }
        }
    }
}

/** Under a tapped Start row while another workout is open: resume that one, or discard it and start. */
@Composable
private fun StartChoiceRows(
    openName: String,
    discardArmed: Boolean,
    onResume: () -> Unit,
    onDiscardAndStart: () -> Unit,
) {
    val indent = Modifier.padding(start = 1f.gridUnitsAsDp())
    LiftsListRow(
        onClick = onResume,
        left = {
            LightText(
                text = "Resume $openName",
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = indent,
            )
        },
        right = { LightIcon(icon = LightIcons.ARROW_RIGHT, size = 1.2f) },
    )
    LiftsListRow(
        onClick = onDiscardAndStart,
        left = {
            LightText(
                text = "Discard it and start",
                variant = LightTextVariant.Copy,
                lighten = true,
                maxLines = 1,
                modifier = indent,
            )
        },
        right = if (discardArmed) {
            { LightText(text = "TAP AGAIN", variant = LightTextVariant.Detail, lighten = true, maxLines = 1) }
        } else {
            null
        },
    )
}
