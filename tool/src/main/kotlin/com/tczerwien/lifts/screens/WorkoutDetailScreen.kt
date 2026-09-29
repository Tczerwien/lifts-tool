package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.analytics.NotationStyle
import com.tczerwien.lifts.analytics.SetRun
import com.tczerwien.lifts.analytics.WORKOUT_BODYWEIGHT_LOOKBACK_DAYS
import com.tczerwien.lifts.analytics.chronologicalPrSetIds
import com.tczerwien.lifts.analytics.setNotation
import com.tczerwien.lifts.analytics.workoutBodyweight
import com.tczerwien.lifts.analytics.workoutEpochDay
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.Tracking
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.data.tracking
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.toolmanager.LiftsShare
import com.tczerwien.lifts.toolmanager.liftsShare
import com.tczerwien.lifts.ui.ArmedAction
import com.tczerwien.lifts.ui.ArmedHint
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.SetLine
import com.tczerwien.lifts.ui.UNKNOWN_EXERCISE
import com.tczerwien.lifts.ui.displayName
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class WorkoutDetailViewModel(
    private val repository: LiftsRepository,
    private val settings: SettingsStore,
    private val share: LiftsShare,
    private val workoutId: Long,
) : LightViewModel<Unit>() {

    /**
     * One exercise's logged sets, one run per set ("w95×10; 140×8F RIR1"), PRs starred, and his note
     * on it for this workout, if any.
     */
    data class ExerciseBlock(val exerciseId: String, val name: String, val runs: List<SetRun>, val notes: String? = null)

    data class UiState(
        val loaded: Boolean = false,
        val name: String = "",
        /** "Jul 18, 2026 · 62 min" (duration omitted while unfinished). */
        val subtitle: String = "",
        val notes: String? = null,
        val blocks: List<ExerciseBlock> = emptyList(),
        /** "182.4 lb", or "182.4 lb (Sep 11)" from an earlier day ([workoutBodyweight]); null without one. */
        val bodyweightText: String? = null,
        val editingNotes: Boolean = false,
        /** The workout was deleted while this screen was underneath another one: it leaves. */
        val gone: Boolean = false,
        /** Bumped per edit session so the editor state resets. */
        val notesEditSession: Int = 0,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Queued behind any write still pending, so a rename made in Exercise Detail shows on the way back. */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        LiftsIo.write({}) { refresh() }
    }

    /** The trash takes two taps; see [tapDelete]. */
    val deleteArm = ArmedAction(viewModelScope)

    @Volatile
    private var cleared = false

    /** The delete is confirmed and running: nothing may hold the screen once it lands. */
    @Volatile
    private var deleting = false

    override fun onCleared() {
        cleared = true
        super.onCleared()
    }

    /** A delete is under way: rows and the pencil do nothing until it lands and the screen leaves. */
    val busyDeleting: Boolean get() = deleting

    override fun onBackPressed(): Boolean {
        // Leaving because the workout is gone: an armed trash or an open editor must not swallow it.
        if (deleting || _uiState.value.gone) return false
        if (_uiState.value.editingNotes) {
            cancelEditNotes()
            return true
        }
        if (deleteArm.armed.value) {
            deleteArm.disarm()
            return true
        }
        return false
    }

    /**
     * Trash: the first tap arms, the second deletes the workout with all its sets, on the write lane
     * so it survives Back, then runs [onDeleted] on Main (unless the screen is already gone).
     */
    fun tapDelete(onDeleted: () -> Unit) {
        if (deleting || !deleteArm.tap()) return
        deleting = true
        LiftsIo.write({ repository.deleteWorkout(workoutId) }) {
            share.workoutDeleted()
            withContext(Dispatchers.Main) { if (!cleared) onDeleted() }
        }
    }

    fun startEditNotes() {
        if (deleting) return
        deleteArm.disarm()
        _uiState.update {
            it.copy(editingNotes = true, notesEditSession = it.notesEditSession + 1)
        }
    }

    fun cancelEditNotes() {
        _uiState.update { it.copy(editingNotes = false) }
    }

    /**
     * On the write lane, so Back can't drop it; the notes are in every export file, so they catch up.
     * The editor closes on the saved text, so opening it again before the reload lands starts from
     * that, not the note before.
     */
    fun saveNotes(text: String) {
        val notes = text.trim().ifEmpty { null }
        LiftsIo.write({
            val workout = repository.workoutById(workoutId) ?: return@write false
            notes != workout.notes && repository.updateWorkout(workout.copy(notes = notes))
        }) { changed ->
            if (changed) share.logChanged()
            _uiState.update { it.copy(editingNotes = false, notes = if (changed) notes else it.notes) }
            refresh()
        }
    }

    private fun refresh() {
        val workout = repository.workoutById(workoutId)
        if (workout == null) {
            // Deleted from another Workout Detail stacked above this one (via Exercise Detail's
            // history). Our own delete leaves through its callback instead.
            if (!deleting) _uiState.update { it.copy(gone = true, editingNotes = false) }
            return
        }
        val unit = settings.snapshot().units
        val slots = repository.exercisesForWorkout(workoutId)
        val setsBySlot = repository.setsForWorkout(workoutId)
            .filter { it.completedAt != null }
            .groupBy { it.workoutExerciseId }
        val exercises = repository.exercisesByIds(slots.map { it.exerciseId })

        val prSetIds = computePrSetIds(exercises.keys + slots.map { it.exerciseId }, exercises.mapValues { it.value.tracking })

        val blocks = slots.sortedBy { it.position }.mapNotNull { slot ->
            val completed = setsBySlot[slot.id]?.sortedBy { it.position }
                ?: return@mapNotNull null
            ExerciseBlock(
                exerciseId = slot.exerciseId,
                name = exercises[slot.exerciseId]?.let { displayName(it) } ?: UNKNOWN_EXERCISE,
                runs = completed.map { set ->
                    SetRun(setNotation(set, unit, NotationStyle.LIST) ?: "-", set.id in prSetIds, 1)
                },
                notes = slot.notes?.takeIf { it.isNotBlank() },
            )
        }

        val durationMin = workout.endedAt?.let { ((it - workout.startedAt) / 60000.0).roundToInt().coerceAtLeast(0) }
        val zone = ZoneId.systemDefault()
        val day = workoutEpochDay(workout.startedAt, zone)
        val bodyweight = workoutBodyweight(
            workout.startedAt,
            repository.bodyweightBetween(day - WORKOUT_BODYWEIGHT_LOOKBACK_DAYS, day),
            zone,
        )
        _uiState.update { state ->
            state.copy(
                loaded = true,
                name = workout.name,
                subtitle = Format.dateWithYear(workout.startedAt) +
                    (durationMin?.let { minutes -> " · $minutes min" } ?: ""),
                notes = workout.notes,
                blocks = blocks,
                bodyweightText = bodyweight?.let {
                    "${Format.bodyweight(it.weightKg, unit)} ${unit.suffix}" +
                        if (it.sameDay) "" else " (${Format.dayShort(it.epochDay)})"
                },
            )
        }
    }

    /**
     * Ids of sets (across all workouts) that were PRs when logged, for the exercises in
     * [exerciseIds]: the shared records engine over one history query, each exercise by its [tracking].
     */
    private fun computePrSetIds(exerciseIds: Set<String>, tracking: Map<String, Tracking>): Set<Long> =
        chronologicalPrSetIds(repository.completedSetsByExercise(exerciseIds), tracking)
}

/**
 * Workout Detail (design/screens/workout-detail.html): read-only log of a completed
 * session — notes, per-exercise compact set-notation lines with inline PR stars and his note on
 * each exercise, and the bodyweight line. The top-bar pencil edits the workout NOTES only (SDK editor as a
 * screen mode, weather idiom); full past-workout editing is deliberately out of scope.
 * The bottom-bar trash deletes the whole workout after a second tap.
 */
class WorkoutDetailScreen(
    private val sealedActivity: SealedLightActivity,
    private val workoutId: Long,
) : LightScreen<Unit, WorkoutDetailViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    override val viewModelClass: Class<WorkoutDetailViewModel>
        get() = WorkoutDetailViewModel::class.java

    override fun createViewModel() =
        WorkoutDetailViewModel(repository, SettingsStore(lightContext.dataStore), lightContext.liftsShare(), workoutId)

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()
        val deleteArmed by viewModel.deleteArm.armed.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LaunchedEffect(uiState.gone) {
            if (uiState.gone) goBack()
        }

        LiftsFrame(sealedActivity, lightContext) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                if (uiState.editingNotes) {
                    key(uiState.notesEditSession) {
                        val notesState = rememberTextFieldState(uiState.notes.orEmpty())
                        LightTextInputEditor(
                            title = "Notes",
                            state = notesState,
                            onSubmit = { viewModel.saveNotes(it.toString()) },
                            onBack = { viewModel.cancelEditNotes() },
                            keyboardOptionsFlow = keyboardOptionsFlow,
                            // A capital to start a new note; none mid-way through one being edited.
                            initialCaps = uiState.notes.isNullOrBlank(),
                            editorKey = uiState.notesEditSession,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LightTopBar(
                            leftButton = LightBarButton.LightIcon(
                                icon = LightIcons.BACK,
                                onClick = { goBack() },
                            ),
                            center = LightTopBarCenter.TwoLineDetail(
                                line1 = uiState.name,
                                line2 = uiState.subtitle,
                            ),
                            rightButton = LightBarButton.LightIcon(
                                icon = LightIcons.PENCIL,
                                onClick = { viewModel.startEditNotes() },
                            ),
                        )

                        LightScrollView(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            uiState.notes?.let { notes ->
                                LightText(
                                    text = notes,
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                                )
                            }

                            // Each exercise opens its Exercise Detail (history, records, cue).
                            uiState.blocks.forEach { block ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .lightClickable {
                                            // Not while our own delete lands: its goBack would pop that screen instead.
                                            if (!viewModel.busyDeleting) {
                                                navigateTo({ ExerciseDetailScreen(it, exerciseId = block.exerciseId) })
                                            }
                                        }
                                        .padding(
                                            start = 1f.gridUnitsAsDp(),
                                            end = 1f.gridUnitsAsDp(),
                                            top = 0.5f.gridUnitsAsDp(),
                                        ),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        LightText(
                                            text = block.name,
                                            variant = LightTextVariant.Copy,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        LightText(text = " ›", variant = LightTextVariant.Copy, lighten = true, maxLines = 1)
                                    }
                                    SetLine(runs = block.runs, lighten = true)
                                    // His note on this exercise, in his words.
                                    block.notes?.let { note ->
                                        LightText(
                                            text = "“$note”",
                                            variant = LightTextVariant.Detail,
                                            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                                        )
                                    }
                                }
                            }

                            if (uiState.loaded && uiState.blocks.isEmpty()) {
                                LightText(
                                    text = "No sets logged",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(
                                        start = 1f.gridUnitsAsDp(),
                                        end = 1f.gridUnitsAsDp(),
                                        top = 0.5f.gridUnitsAsDp(),
                                    ),
                                )
                            }

                            uiState.bodyweightText?.let { bodyweight ->
                                LightText(
                                    text = "Bodyweight: $bodyweight",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(
                                        start = 1f.gridUnitsAsDp(),
                                        end = 1f.gridUnitsAsDp(),
                                        top = 1f.gridUnitsAsDp(),
                                    ),
                                )
                            }
                        }

                        if (deleteArmed) {
                            ArmedHint(text = "TAP AGAIN TO DELETE THIS WORKOUT")
                        }
                        LightBottomBar(
                            items = listOf(
                                LightBarButton.LightIcon(
                                    icon = LightIcons.TRASH,
                                    contentDescription = "delete workout",
                                    onClick = { viewModel.tapDelete { goBack() } },
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }
}
