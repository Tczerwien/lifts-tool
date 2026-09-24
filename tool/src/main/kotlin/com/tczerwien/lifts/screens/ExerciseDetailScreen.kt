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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.analytics.epleyE1RmKg
import com.tczerwien.lifts.analytics.isPr
import com.tczerwien.lifts.data.LiftsDatabase
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.LiftsTabRow
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.SectionLabel
import com.tczerwien.lifts.ui.WeightUnit
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ExerciseDetailViewModel(
    private val exerciseId: String,
    private val repository: LiftsRepository,
    private val settings: SettingsStore,
) : LightViewModel<Unit>() {

    /** One set inside a history row, e.g. "w95×10" / "140×8"; [pr] appends the star. */
    data class SetSegment(val text: String, val pr: Boolean)

    data class HistoryRow(
        val workoutId: Long,
        val dateLabel: String,
        val segments: List<SetSegment>,
    )

    data class Stat(val label: String, val value: String, val dateLabel: String)

    data class RepMax(val reps: Int, val valueLabel: String?)

    data class Records(val stats: List<Stat>, val repMaxes: List<RepMax>)

    data class UiState(
        val name: String = "",
        val tab: Int = TAB_HISTORY,
        val pinnedNote: String? = null,
        val instructions: List<String> = emptyList(),
        val history: List<HistoryRow> = emptyList(),
        val records: Records? = null,
        val noteOpen: Boolean = false,
        val noteSession: Int = 0,
        val loaded: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { refresh() }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch(Dispatchers.IO) { refresh() }
    }

    /**
     * Back from the note editor closes it. Only the in-app back button reaches this: the SDK routes the
     * system Back key straight to the activity.
     */
    override fun onBackPressed(): Boolean {
        if (!_uiState.value.noteOpen) return false
        closeNoteEditor()
        return true
    }

    fun selectTab(index: Int) {
        _uiState.value = _uiState.value.copy(tab = index)
    }

    fun openNoteEditor() {
        val state = _uiState.value
        _uiState.value = state.copy(noteOpen = true, noteSession = state.noteSession + 1)
    }

    fun closeNoteEditor() {
        _uiState.value = _uiState.value.copy(noteOpen = false)
    }

    /** Blank input clears the pinned note. */
    fun saveNote(raw: String) {
        _uiState.value = _uiState.value.copy(noteOpen = false)
        viewModelScope.launch(Dispatchers.IO) {
            val exercise = repository.exerciseById(exerciseId)
            if (exercise != null) {
                repository.updateExercise(exercise.copy(pinnedNote = raw.trim().takeIf { it.isNotEmpty() }))
            }
            refresh()
        }
    }

    private fun refresh() {
        val exercise = repository.exerciseById(exerciseId)
        val unit = settings.snapshot().units

        // Every workout containing this exercise, newest first, with its completed sets.
        val perWorkout = repository.listWorkouts().mapNotNull { workout ->
            val slotIds = repository.exercisesForWorkout(workout.id)
                .filter { it.exerciseId == exerciseId }
                .mapTo(HashSet()) { it.id }
            if (slotIds.isEmpty()) return@mapNotNull null
            val sets = repository.setsForWorkout(workout.id)
                .filter { it.workoutExerciseId in slotIds && it.completedAt != null }
            if (sets.isEmpty()) {
                null
            } else {
                PerWorkout(
                    workoutId = workout.id,
                    dateMillis = workout.endedAt ?: workout.startedAt,
                    sets = sets,
                )
            }
        }

        // PR flags computed chronologically: a set is starred when it beat all sets before it.
        val chronological = perWorkout.flatMap { it.sets }
            .sortedWith(compareBy({ it.completedAt }, { it.id }))
        val prSetIds = HashSet<Long>()
        val prior = mutableListOf<SetEntity>()
        for (set in chronological) {
            if (isPr(set, prior)) prSetIds.add(set.id)
            prior.add(set)
        }

        val history = perWorkout.map { entry ->
            HistoryRow(
                workoutId = entry.workoutId,
                dateLabel = Format.dateShort(entry.dateMillis),
                segments = entry.sets.map { set -> set.toSegment(unit, set.id in prSetIds) },
            )
        }

        _uiState.value = _uiState.value.copy(
            name = exercise?.name ?: "Exercise",
            pinnedNote = exercise?.pinnedNote,
            instructions = exercise?.instructions ?: emptyList(),
            history = history,
            records = computeRecords(chronological, unit),
            loaded = true,
        )
    }

    private fun SetEntity.toSegment(unit: WeightUnit, pr: Boolean): SetSegment {
        val weightLabel = weightKg?.let { Format.weight(it, unit) } ?: "—"
        val body = reps?.let { "$weightLabel×$it" } ?: weightLabel
        return SetSegment(
            text = if (type == SET_TYPE_WARMUP) "w$body" else body,
            pr = pr,
        )
    }

    /**
     * Records over completed non-warmup weighted sets. [chronological] is ascending by
     * completion time, so strict `>` comparisons keep the first achiever on ties
     * (same convention as analytics.computePrs).
     */
    private fun computeRecords(chronological: List<SetEntity>, unit: WeightUnit): Records? {
        val working = chronological.filter { it.type != SET_TYPE_WARMUP && it.weightKg != null }
        if (working.isEmpty()) return null

        var maxSet: SetEntity? = null
        var bestE1RmSet: SetEntity? = null
        var bestE1Rm = 0.0
        var bestVolumeSet: SetEntity? = null
        var bestVolume = 0.0
        for (set in working) {
            val weight = set.weightKg ?: continue
            val currentMax = maxSet
            if (currentMax == null || weight > (currentMax.weightKg ?: 0.0)) maxSet = set
            val e1Rm = set.reps?.let { epleyE1RmKg(weight, it) }
            if (e1Rm != null && (bestE1RmSet == null || e1Rm > bestE1Rm)) {
                bestE1Rm = e1Rm
                bestE1RmSet = set
            }
            val volume = set.reps?.let { weight * it }
            if (volume != null && (bestVolumeSet == null || volume > bestVolume)) {
                bestVolume = volume
                bestVolumeSet = set
            }
        }

        val stats = buildList {
            maxSet?.let { set ->
                val weightLabel = "${Format.weight(set.weightKg ?: 0.0, unit)} ${unit.suffix}"
                add(
                    Stat(
                        label = "Max Weight",
                        value = set.reps?.let { "$weightLabel × $it" } ?: weightLabel,
                        dateLabel = Format.dateWithYear(set.completedAt ?: 0L),
                    ),
                )
            }
            bestE1RmSet?.let { set ->
                add(
                    Stat(
                        label = "Best e1RM",
                        value = "${Format.weight(bestE1Rm, unit)} ${unit.suffix}",
                        dateLabel = Format.dateWithYear(set.completedAt ?: 0L),
                    ),
                )
            }
            bestVolumeSet?.let { set ->
                add(
                    Stat(
                        label = "Best Volume Set",
                        value = Format.setSummary(set.weightKg ?: 0.0, set.reps ?: 0, unit),
                        dateLabel = Format.dateWithYear(set.completedAt ?: 0L),
                    ),
                )
            }
        }

        val repMaxes = REP_MAX_TARGETS.map { target ->
            val best = working.filter { it.reps == target }
                .maxByOrNull { it.weightKg ?: 0.0 }
            RepMax(
                reps = target,
                valueLabel = best?.let { "${Format.weight(it.weightKg ?: 0.0, unit)} ${unit.suffix}" },
            )
        }

        return Records(stats = stats, repMaxes = repMaxes)
    }

    private data class PerWorkout(
        val workoutId: Long,
        val dateMillis: Long,
        val sets: List<SetEntity>,
    )

    companion object {
        const val TAB_ABOUT = 0
        const val TAB_HISTORY = 1
        const val TAB_RECORDS = 2
        private const val SET_TYPE_WARMUP = "warmup"
        private val REP_MAX_TARGETS = listOf(1, 5, 8)
    }
}

/**
 * Exercise detail (design/screens/exercise-detail.html): ABOUT / HISTORY / RECORDS tabs.
 * History lists one row per workout (date + compact set notation with PR stars); Records
 * shows max weight, best e1RM, best volume set, and 1/5/8 rep maxes; About shows the
 * pinned note and the catalog instructions. Top-bar pencil edits the pinned note.
 * Opens on History — the common in-workout question is "what did I do last time".
 */
class ExerciseDetailScreen(
    sealedActivity: SealedLightActivity,
    private val exerciseId: String,
) : LightScreen<Unit, ExerciseDetailViewModel>(sealedActivity) {

    private val repository = LiftsRepository.getInstance {
        lightContext.buildDatabase(LiftsDatabase::class.java, LiftsRepository.DATABASE_NAME)
    }

    override val viewModelClass: Class<ExerciseDetailViewModel>
        get() = ExerciseDetailViewModel::class.java

    override fun createViewModel() =
        ExerciseDetailViewModel(exerciseId, repository, SettingsStore(lightContext.dataStore))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val uiState by viewModel.uiState.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
            if (uiState.noteOpen) {
                val textState = remember { TextFieldState(uiState.pinnedNote ?: "") }
                LightTextInputEditor(
                    title = "Note",
                    state = textState,
                    onSubmit = { viewModel.saveNote(it.toString()) },
                    onBack = viewModel::closeNoteEditor,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    editorKey = "exercise-note-${uiState.noteSession}",
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
                center = LightTopBarCenter.Text(uiState.name),
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
        if (uiState.loaded && note == null && uiState.instructions.isEmpty()) {
            EmptyTabMessage("No instructions", modifier)
            return
        }
        LightScrollView(modifier = modifier) {
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
                if (note != null) {
                    SectionLabel("Instructions")
                } else {
                    Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
                }
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
                    onClick = {
                        navigateTo({ WorkoutDetailScreen(it, workoutId = row.workoutId) })
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
                            lighten = repMax.valueLabel == null,
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
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HISTORY_ROW_HEIGHT_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick),
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
            HistoryNotation(
                segments = row.segments,
                modifier = Modifier.weight(1f),
            )
        }
        if (showDivider) {
            RowDivider(modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** "w95×10; 140×8; 165×5★; 165×4" as one ellipsized line; stars are inline STAR_OUTLINE icons. */
@Composable
private fun HistoryNotation(
    segments: List<ExerciseDetailViewModel.SetSegment>,
    modifier: Modifier = Modifier,
) {
    val style = detailMonoStyle().copy(
        color = LightThemeTokens.colors.content,
        textAlign = TextAlign.End,
    )
    val starSizeDp = with(LocalDensity.current) { style.fontSize.toDp() }
    val starSizeUnits = starSizeDp.value / 1f.gridUnitsAsDp().value
    val inlineContent = mapOf(
        PR_STAR_INLINE_ID to InlineTextContent(
            Placeholder(
                width = style.fontSize,
                height = style.fontSize,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) {
            Box(
                modifier = Modifier.size(starSizeDp),
                contentAlignment = Alignment.Center,
            ) {
                LightIcon(
                    icon = LightIcons.STAR_OUTLINE,
                    size = starSizeUnits,
                    contentDescription = "PR",
                )
            }
        },
    )
    BasicText(
        text = buildAnnotatedString {
            segments.forEachIndexed { index, segment ->
                if (index > 0) append("; ")
                append(segment.text)
                if (segment.pr) appendInlineContent(PR_STAR_INLINE_ID, "*")
            }
        },
        style = style,
        inlineContent = inlineContent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private const val PR_STAR_INLINE_ID = "pr"

/** Detail-variant mono style scaled like LightText scales its variants. */
@Composable
private fun detailMonoStyle(): TextStyle {
    val base = LightThemeTokens.typography.detail
    return base.copy(
        fontSize = base.fontSize.value.designVerticalPxToSp(),
        lineHeight = (base.fontSize.value * 1.25f).designVerticalPxToSp(),
        fontFamily = FontFamily.Monospace,
    )
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
