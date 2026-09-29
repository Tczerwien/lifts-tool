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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.tczerwien.lifts.analytics.NotationStyle
import com.tczerwien.lifts.analytics.SetRun
import com.tczerwien.lifts.analytics.SetToken
import com.tczerwien.lifts.analytics.chronologicalPrSetIds
import com.tczerwien.lifts.analytics.compactNotation
import com.tczerwien.lifts.analytics.exerciseRecords
import com.tczerwien.lifts.analytics.setNotation
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.seed.SeedParser
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.toolmanager.LiftsShare
import com.tczerwien.lifts.toolmanager.liftsShare
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFieldRow
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.LiftsTabRow
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.SectionLabel
import com.tczerwien.lifts.ui.SetLine
import com.tczerwien.lifts.ui.WeightUnit
import com.tczerwien.lifts.ui.displayName
import com.tczerwien.lifts.ui.knownCatalogName
import com.tczerwien.lifts.ui.renameTarget
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ExerciseDetailViewModel(
    private val exerciseId: String,
    private val repository: LiftsRepository,
    private val settings: SettingsStore,
    private val share: LiftsShare,
) : LightViewModel<Unit>() {

    data class HistoryRow(
        val workoutId: Long,
        val dateLabel: String,
        /** The workout's sets in one line, repeats collapsed ("w95×10; 225×5 ×3"): see [compactNotation]. */
        val runs: List<SetRun>,
        /** The workout still in progress: shown, but it doesn't open Workout Detail, whose trash would delete it. */
        val inProgress: Boolean = false,
    )

    data class Stat(val label: String, val value: String, val dateLabel: String)

    /** [estimated] values are predicted from the best e1RM (no set of that many reps yet). */
    data class RepMax(val reps: Int, val valueLabel: String?, val estimated: Boolean = false)

    data class Records(val stats: List<Stat>, val repMaxes: List<RepMax>)

    data class UiState(
        val name: String = "",
        val tab: Int = TAB_HISTORY,
        val pinnedNote: String? = null,
        val instructions: List<String> = emptyList(),
        val history: List<HistoryRow> = emptyList(),
        val records: Records? = null,
        /** The full-screen editor showing, if any: the pinned note or the name. */
        val editing: Editing? = null,
        val editSession: Int = 0,
        /** The name the name editor opened with: submitting it unchanged does nothing ([renameTarget]). */
        val nameAtOpen: String = "",
        val loaded: Boolean = false,
    )

    enum class Editing { NOTE, NAME }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * Loads here only: the SDK calls this straight after creating the view model, so init needn't.
     * Queued behind any write still pending, so a workout deleted a moment ago can't show.
     */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        LiftsIo.write({}) { refresh() }
    }

    /**
     * Back from the note or name editor closes it. Only the in-app back button reaches this: the SDK
     * routes the system Back key straight to the activity.
     */
    override fun onBackPressed(): Boolean {
        if (_uiState.value.editing == null) return false
        closeEditor()
        return true
    }

    fun selectTab(index: Int) {
        _uiState.value = _uiState.value.copy(tab = index)
    }

    fun openNoteEditor() = openEditor(Editing.NOTE)

    fun openNameEditor() = openEditor(Editing.NAME)

    private fun openEditor(editing: Editing) {
        val state = _uiState.value
        if (!state.loaded) return
        _uiState.value = state.copy(editing = editing, editSession = state.editSession + 1, nameAtOpen = state.name)
    }

    fun closeEditor() {
        _uiState.value = _uiState.value.copy(editing = null)
    }

    /** Blank input clears the pinned note. workouts.json carries the cue, so the exports catch up. */
    fun saveNote(raw: String) {
        _uiState.value = _uiState.value.copy(editing = null)
        LiftsIo.write({
            val exercise = repository.exerciseById(exerciseId) ?: return@write false
            val note = raw.trim().takeIf { it.isNotEmpty() }
            note != exercise.pinnedNote && repository.updateExercise(exercise.copy(pinnedNote = note))
        }) { changed ->
            if (changed) share.logChanged()
            refresh()
        }
    }

    /**
     * Renames the exercise ([renameTarget]): the new name shows everywhere, exports included, over any
     * short name ([displayName]). Submitting the name as the editor showed it changes nothing; a blank
     * name puts a catalog exercise back to its catalog name.
     */
    fun saveName(raw: String) {
        val shown = _uiState.value.nameAtOpen
        _uiState.value = _uiState.value.copy(editing = null)
        LiftsIo.write({
            val exercise = repository.exerciseById(exerciseId) ?: return@write false
            val name = renameTarget(exercise, raw, shown) {
                knownCatalogName(exercise.id) ?: SeedParser.catalogName(exercise.id)
            } ?: return@write false
            repository.updateExercise(exercise.copy(name = name))
        }) { changed ->
            if (changed) share.logChanged()
            refresh()
        }
    }

    private fun refresh() {
        val exercise = repository.exerciseById(exerciseId)
        val unit = settings.snapshot().units

        // Every workout containing this exercise, newest first, with its completed sets in slot
        // then set order: two queries, whatever the history length.
        val setsByWorkout = repository.completedSetsWithWorkout(exerciseId).groupBy { it.workoutId }
        val perWorkout = repository.listWorkouts().mapNotNull { workout ->
            val sets = setsByWorkout[workout.id] ?: return@mapNotNull null
            PerWorkout(
                workoutId = workout.id,
                inProgress = workout.endedAt == null,
                dateMillis = workout.endedAt ?: workout.startedAt,
                sets = sets.sortedWith(compareBy({ it.slotPosition }, { it.set.position })).map { it.set },
            )
        }

        // A set is starred when it beat every set completed before it (shared records engine).
        val allSets = perWorkout.flatMap { it.sets }
        val prSetIds = chronologicalPrSetIds(allSets)

        val history = perWorkout.map { entry ->
            HistoryRow(
                workoutId = entry.workoutId,
                dateLabel = Format.dateShort(entry.dateMillis),
                runs = compactNotation(entry.sets.map { set -> set.toToken(unit, set.id in prSetIds) }),
                inProgress = entry.inProgress,
            )
        }

        val records = recordsFor(allSets, unit)
        // Merged into the state as it is now, so a tap made while this ran (opening an editor) stands.
        _uiState.update { state ->
            state.copy(
                name = exercise?.let(::displayName) ?: "Exercise",
                pinnedNote = exercise?.pinnedNote,
                instructions = exercise?.instructions ?: emptyList(),
                history = history,
                records = records,
                loaded = true,
            )
        }
    }

    private fun SetEntity.toToken(unit: WeightUnit, pr: Boolean) =
        SetToken(setNotation(this, unit, NotationStyle.LIST) ?: "-", pr)

    /** The Records tab, formatted from the shared records engine. */
    private fun recordsFor(sets: List<SetEntity>, unit: WeightUnit): Records? {
        val records = exerciseRecords(sets, REP_MAX_TARGETS) ?: return null
        fun weightLabel(kg: Double) = "${Format.weight(kg, unit)} ${unit.suffix}"
        val stats = buildList {
            records.maxWeightSet?.let { set ->
                val label = setNotation(set.weightKg, set.reps, unit, NotationStyle.SINGLE, withUnit = true).orEmpty()
                add(Stat("Max Weight", label, Format.dateWithYear(set.completedAt ?: 0L)))
            }
            val bestE1Rm = records.bestE1RmKg
            val bestE1RmSet = records.bestE1RmSet
            if (bestE1Rm != null && bestE1RmSet != null) {
                add(Stat("Best e1RM", weightLabel(bestE1Rm), Format.dateWithYear(bestE1RmSet.completedAt ?: 0L)))
            }
            records.bestVolumeSet?.let { set ->
                add(
                    Stat(
                        "Best Volume Set",
                        setNotation(set.weightKg, set.reps, unit, NotationStyle.SINGLE).orEmpty(),
                        Format.dateWithYear(set.completedAt ?: 0L),
                    ),
                )
            }
            records.maxRepsSet?.let { set ->
                add(Stat("Max Reps", "${set.reps ?: 0}", Format.dateWithYear(set.completedAt ?: 0L)))
            }
        }
        val repMaxes = records.repMaxes.map { repMax ->
            RepMax(
                reps = repMax.reps,
                valueLabel = repMax.weightKg?.let { weightLabel(it) + if (repMax.estimated) " est." else "" },
                estimated = repMax.estimated,
            )
        }
        return Records(stats = stats, repMaxes = repMaxes)
    }

    private data class PerWorkout(
        val workoutId: Long,
        val inProgress: Boolean,
        val dateMillis: Long,
        val sets: List<SetEntity>,
    )

    companion object {
        const val TAB_ABOUT = 0
        const val TAB_HISTORY = 1
        const val TAB_RECORDS = 2
        private val REP_MAX_TARGETS = listOf(1, 5, 8)
    }
}

/**
 * Exercise detail (design/screens/exercise-detail.html): ABOUT / HISTORY / RECORDS tabs.
 * History lists one row per workout (date + compact set notation with PR stars); Records
 * shows max weight, best e1RM, best volume set, and 1/5/8 rep maxes; About shows the
 * pinned note and the catalog instructions. Top-bar pencil edits the pinned note; a tap on the
 * name (top bar, or About's Name row) renames the exercise.
 * Opens on History — the common in-workout question is "what did I do last time".
 */
class ExerciseDetailScreen(
    private val sealedActivity: SealedLightActivity,
    private val exerciseId: String,
) : LightScreen<Unit, ExerciseDetailViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    override val viewModelClass: Class<ExerciseDetailViewModel>
        get() = ExerciseDetailViewModel::class.java

    override fun createViewModel() =
        ExerciseDetailViewModel(exerciseId, repository, SettingsStore(lightContext.dataStore), lightContext.liftsShare())

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LiftsFrame(sealedActivity, lightContext) {
            val editing = uiState.editing
            if (editing != null) {
                val naming = editing == ExerciseDetailViewModel.Editing.NAME
                val textState = remember(uiState.editSession) {
                    TextFieldState(if (naming) uiState.nameAtOpen else uiState.pinnedNote ?: "")
                }
                LightTextInputEditor(
                    title = if (naming) "Name" else "Note",
                    // A name is one line: Return submits it. A note may run to several.
                    singleLine = naming,
                    state = textState,
                    onSubmit = { if (naming) viewModel.saveName(it.toString()) else viewModel.saveNote(it.toString()) },
                    onBack = viewModel::closeEditor,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    editorKey = "exercise-${editing.name.lowercase()}-${uiState.editSession}",
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background),
                )
            } else {
                DetailContent(uiState)
            }
        }
    }

    @Composable
    private fun DetailContent(uiState: ExerciseDetailViewModel.UiState) {
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
                // A tap on the name renames it, as the About tab's Name row does.
                center = LightTopBarCenter.Text(uiState.name, onClick = viewModel::openNameEditor),
                rightButton = LightBarButton.LightIcon(
                    icon = LightIcons.PENCIL,
                    contentDescription = "edit note",
                    onClick = { viewModel.openNoteEditor() },
                ),
            )

            LiftsTabRow(
                tabs = listOf("About", "History", "Records"),
                activeIndex = uiState.tab,
                onSelect = viewModel::selectTab,
            )

            val bodyModifier = Modifier
                .weight(1f)
                .fillMaxWidth()
            when (uiState.tab) {
                ExerciseDetailViewModel.TAB_ABOUT -> AboutTab(uiState, bodyModifier)
                ExerciseDetailViewModel.TAB_HISTORY -> HistoryTab(uiState, bodyModifier)
                else -> RecordsTab(uiState, bodyModifier)
            }
        }
    }

    @Composable
    private fun AboutTab(uiState: ExerciseDetailViewModel.UiState, modifier: Modifier) {
        val note = uiState.pinnedNote
        LightScrollView(modifier = modifier) {
            LiftsFieldRow(label = "Name", value = uiState.name, onClick = viewModel::openNameEditor, chevron = true)
            if (uiState.loaded && note == null && uiState.instructions.isEmpty()) {
                LightText(
                    text = "No instructions",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                )
            }
            if (note != null) {
                SectionLabel("Note")
                LightText(
                    text = note,
                    variant = LightTextVariant.Paragraph,
                    modifier = Modifier.padding(
                        start = 1f.gridUnitsAsDp(),
                        end = 1f.gridUnitsAsDp(),
                        bottom = 0.5f.gridUnitsAsDp(),
                    ),
                )
            }
            if (uiState.instructions.isNotEmpty()) {
                SectionLabel("Instructions")
                uiState.instructions.forEach { paragraph ->
                    LightText(
                        text = paragraph,
                        variant = LightTextVariant.Paragraph,
                        modifier = Modifier.padding(
                            start = 1f.gridUnitsAsDp(),
                            end = 1f.gridUnitsAsDp(),
                            bottom = 0.75f.gridUnitsAsDp(),
                        ),
                    )
                }
            }
        }
    }

    @Composable
    private fun HistoryTab(uiState: ExerciseDetailViewModel.UiState, modifier: Modifier) {
        if (uiState.loaded && uiState.history.isEmpty()) {
            EmptyTabMessage("No sets logged yet", modifier)
            return
        }
        LightLazyScrollView(
            modifier = modifier,
            uniformItemHeightGridUnits = HISTORY_ROW_HEIGHT_UNITS,
        ) {
            itemsIndexed(uiState.history, key = { _, row -> row.workoutId }) { index, row ->
                HistoryRow(
                    row = row,
                    showDivider = index < uiState.history.lastIndex,
                    onClick = if (row.inProgress) {
                        null
                    } else {
                        { navigateTo({ WorkoutDetailScreen(it, workoutId = row.workoutId) }) }
                    },
                )
            }
        }
    }

    @Composable
    private fun RecordsTab(uiState: ExerciseDetailViewModel.UiState, modifier: Modifier) {
        val records = uiState.records
        if (records == null) {
            if (uiState.loaded) EmptyTabMessage("No sets logged yet", modifier)
            return
        }
        LightScrollView(modifier = modifier) {
            records.stats.forEach { stat -> StatRow(stat) }

            SectionLabel("Rep Maxes")
            records.repMaxes.forEachIndexed { index, repMax ->
                if (index > 0) RowDivider()
                LiftsListRow(
                    left = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LightText(
                                text = "${repMax.reps}",
                                variant = LightTextVariant.Detail,
                                monospace = true,
                                maxLines = 1,
                            )
                            LightText(
                                text = if (repMax.reps == 1) " rep" else " reps",
                                variant = LightTextVariant.Detail,
                                maxLines = 1,
                            )
                        }
                    },
                    right = {
                        LightText(
                            text = repMax.valueLabel ?: "—",
                            variant = LightTextVariant.Copy,
                            monospace = true,
                            lighten = repMax.valueLabel == null || repMax.estimated,
                            maxLines = 1,
                        )
                    },
                )
            }
        }
    }

    @Composable
    private fun EmptyTabMessage(text: String, modifier: Modifier) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            LightText(
                text = text,
                variant = LightTextVariant.Copy,
                lighten = true,
                align = TextAlign.Center,
            )
        }
    }
}

private const val HISTORY_ROW_HEIGHT_UNITS = 3f

/**
 * Uniform 3gu history row (design history tab): contentSecondary mono date left, compact
 * right-aligned set notation with inline PR stars; inset divider inside the row bottom.
 */
@Composable
private fun HistoryRow(
    row: ExerciseDetailViewModel.HistoryRow,
    showDivider: Boolean,
    onClick: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HISTORY_ROW_HEIGHT_UNITS.gridUnitsAsDp())
            .let { m -> if (onClick != null) m.lightClickable(onClick = onClick) else m },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = row.dateLabel,
                variant = LightTextVariant.Detail,
                lighten = true,
                monospace = true,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(1f.gridUnitsAsDp()))
            SetLine(
                runs = row.runs,
                singleLine = true,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        if (showDivider) {
            RowDivider(modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Stat row (design .statrow): superfine label over mono value with the date at the right. */
@Composable
private fun StatRow(stat: ExerciseDetailViewModel.Stat) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
    ) {
        LightText(
            text = stat.label.uppercase(),
            variant = LightTextVariant.Superfine,
            lighten = true,
            maxLines = 1,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LightText(
                text = stat.value,
                variant = LightTextVariant.Copy,
                monospace = true,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
            LightText(
                text = stat.dateLabel,
                variant = LightTextVariant.Detail,
                lighten = true,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}
