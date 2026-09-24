package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.analytics.chronologicalPrSetIds
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Suffix appended to PR set tokens (design's inline star, U+2605). */
private const val PR_STAR = "★"

class WorkoutDetailViewModel(
    private val repository: LiftsRepository,
    private val settings: SettingsStore,
    private val workoutId: Long,
) : LightViewModel<Unit>() {

    data class ExerciseBlock(val name: String, val notation: String)

    data class UiState(
        val loaded: Boolean = false,
        val name: String = "",
        /** "Jul 18, 2026 · 62 min" (duration omitted while unfinished). */
        val subtitle: String = "",
        val notes: String? = null,
        val blocks: List<ExerciseBlock> = emptyList(),
        /** "182.4 lb", null when the workout has no bodyweight. */
        val bodyweightText: String? = null,
        val editingNotes: Boolean = false,
        /** Bumped per edit session so the editor state resets. */
        val notesEditSession: Int = 0,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch(Dispatchers.IO) { refresh() }
    }

    override fun onBackPressed(): Boolean {
        if (_uiState.value.editingNotes) {
            cancelEditNotes()
            return true
        }
        return false
    }

    fun startEditNotes() {
        _uiState.update {
            it.copy(editingNotes = true, notesEditSession = it.notesEditSession + 1)
        }
    }

    fun cancelEditNotes() {
        _uiState.update { it.copy(editingNotes = false) }
    }

    fun saveNotes(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.workoutById(workoutId)?.let { workout ->
                repository.updateWorkout(workout.copy(notes = text.trim().ifEmpty { null }))
            }
            _uiState.update { it.copy(editingNotes = false) }
            refresh()
        }
    }

    private fun refresh() {
        val workout = repository.workoutById(workoutId) ?: return
        val unit = settings.snapshot().units
        val slots = repository.exercisesForWorkout(workoutId)
        val setsBySlot = repository.setsForWorkout(workoutId)
            .filter { it.completedAt != null }
            .groupBy { it.workoutExerciseId }

        val prSetIds = computePrSetIds(slots.mapTo(HashSet()) { it.exerciseId })

        val blocks = slots.sortedBy { it.position }.mapNotNull { slot ->
            val completed = setsBySlot[slot.id]?.sortedBy { it.position }
                ?: return@mapNotNull null
            ExerciseBlock(
                name = repository.exerciseById(slot.exerciseId)?.name ?: slot.exerciseId,
                notation = completed.joinToString("; ") { set ->
                    setToken(set, unit) + if (set.id in prSetIds) PR_STAR else ""
                },
            )
        }

        val durationMin = workout.endedAt?.let { ((it - workout.startedAt) / 60000.0).roundToInt() }
        _uiState.update { state ->
            state.copy(
                loaded = true,
                name = workout.name,
                subtitle = Format.dateWithYear(workout.startedAt) +
                    (durationMin?.let { minutes -> " · $minutes min" } ?: ""),
                notes = workout.notes,
                blocks = blocks,
                bodyweightText = null, // not stored per workout; comes from the bodyweight log later
            )
        }
    }

    /**
     * Ids of sets (across all workouts) that were PRs when logged, for the exercises in
     * [exerciseIds]: the shared records engine over one history query.
     */
    private fun computePrSetIds(exerciseIds: Set<String>): Set<Long> =
        chronologicalPrSetIds(repository.completedSetsByExercise(exerciseIds))
}

/**
 * Workout Detail (design/screens/workout-detail.html): read-only log of a completed
 * session — notes, per-exercise compact set-notation lines with inline PR stars, and the
 * bodyweight line. The top-bar pencil edits the workout NOTES only (SDK editor as a
 * screen mode, weather idiom); full past-workout editing is deliberately out of scope.
 */
class WorkoutDetailScreen(
    sealedActivity: SealedLightActivity,
    private val workoutId: Long,
) : LightScreen<Unit, WorkoutDetailViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    override val viewModelClass: Class<WorkoutDetailViewModel>
        get() = WorkoutDetailViewModel::class.java

    override fun createViewModel() =
        WorkoutDetailViewModel(repository, SettingsStore(lightContext.dataStore), workoutId)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val uiState by viewModel.uiState.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
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

                            uiState.blocks.forEach { block ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = 1f.gridUnitsAsDp(),
                                            end = 1f.gridUnitsAsDp(),
                                            top = 0.5f.gridUnitsAsDp(),
                                        ),
                                ) {
                                    LightText(
                                        text = block.name,
                                        variant = LightTextVariant.Copy,
                                        maxLines = 1,
                                    )
                                    LightText(
                                        text = block.notation,
                                        variant = LightTextVariant.Detail,
                                        lighten = true,
                                        monospace = true,
                                    )
                                }
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
                    }
                }
            }
        }
    }
}

/**
 * Compact display notation for one completed set — consistent with the export layer's
 * MarkdownExport notation but in display units with "×": warm-ups prefixed "w"
 * ("w95×10"), working sets "140×8", " @8" when RPE was recorded, duration sets "45s".
 * Rest is omitted on screen; the caller appends [PR_STAR] for PR sets.
 */
private fun setToken(set: SetEntity, unit: WeightUnit): String = buildString {
    if (set.type == "warmup") append('w')
    val weight = set.weightKg?.let { Format.weight(it, unit) }
    when {
        weight != null && set.reps != null -> append(weight).append('×').append(set.reps)
        weight != null && set.durationSec != null ->
            append(weight).append('×').append(set.durationSec).append('s')
        weight != null -> append(weight)
        set.reps != null -> append('×').append(set.reps)
        set.durationSec != null -> append(set.durationSec).append('s')
        else -> append('-')
    }
    set.rpe?.let { append(" @").append(trimNumber(it)) }
}

/** "8" for 8.0, "7.5" for 7.5 (matches the export layer's number formatting). */
private fun trimNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
