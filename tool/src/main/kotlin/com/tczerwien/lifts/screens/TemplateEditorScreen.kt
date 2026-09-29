package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.TemplateExerciseEntity
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.toolmanager.LiftsShare
import com.tczerwien.lifts.toolmanager.liftsShare
import com.tczerwien.lifts.ui.ArmedHint
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.Stepper
import com.tczerwien.lifts.ui.displayName
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEFAULT_TARGET_SETS = 3
private const val MIN_TARGET_SETS = 1
private const val MAX_TARGET_SETS = 10
private const val REST_STEP_SEC = 15
private const val MIN_REST_SEC = 0
private const val MAX_REST_SEC = 600

/** Left slot caps at this fraction of the row so the reorder arrows never get squeezed out. */
private const val ROW_LEFT_WIDTH_FRACTION = 0.85f

private const val REORDER_TARGET_WIDTH_UNITS = 2f
private const val REORDER_TARGET_HEIGHT_UNITS = 1.5f
private const val REORDER_ICON_UNITS = 0.9f
private const val REORDER_DISABLED_ALPHA = 0.4f

private const val LIST_ROW_HEIGHT_UNITS = 3f

class TemplateEditorViewModel(
    private val repository: LiftsRepository,
    private val settingsStore: SettingsStore,
    private val share: LiftsShare,
    private val templateId: Long,
) : LightViewModel<Unit>() {

    sealed interface Mode {
        /** The exercise list. */
        data object Overview : Mode

        /** Full-screen SDK text editor renaming the template. */
        data object Rename : Mode

        /** Full-screen stepper takeover editing one template exercise. */
        data class EditExercise(val templateExerciseId: Long) : Mode
    }

    data class ExerciseRow(
        val id: Long,
        val name: String,
        val targetSets: Int,
        /** Effective work rest — explicit value or the settings default. */
        val restSec: Int,
        /** Effective warmup rest — explicit value or the settings default. */
        val warmupRestSec: Int,
    )

    data class UiState(
        val name: String = "",
        val rows: List<ExerciseRow> = emptyList(),
        val mode: Mode = Mode.Overview,
        /** Overview trash armed — next trash tap deletes the template. */
        val deleteArmed: Boolean = false,
        /** Edit-mode trash armed — next trash tap removes the exercise. */
        val removeArmed: Boolean = false,
        /** Bumped per editor entry so the embedded keyboard binds to the fresh field state. */
        val editorSession: Int = 0,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()


    /** Queued behind any write still pending; only reads, so the exports are left alone. */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        LiftsIo.write { refresh() }
    }

    fun openRename() {
        _uiState.update {
            it.copy(
                mode = Mode.Rename,
                deleteArmed = false,
                removeArmed = false,
                editorSession = it.editorSession + 1,
            )
        }
    }

    fun cancelRename() {
        _uiState.update { it.copy(mode = Mode.Overview) }
    }

    /** Renames from the editor text (trimmed); a blank name cancels the rename. */
    fun rename(raw: CharSequence) {
        val name = raw.toString().trim()
        _uiState.update { it.copy(mode = Mode.Overview) }
        if (name.isEmpty()) return
        mutate {
            repository.templateById(templateId)?.let { template ->
                repository.updateTemplate(template.copy(name = name))
            }
        }
    }

    fun openEdit(templateExerciseId: Long) {
        _uiState.update {
            it.copy(
                mode = Mode.EditExercise(templateExerciseId),
                deleteArmed = false,
                removeArmed = false,
            )
        }
    }

    /** Appends the picked exercise with [DEFAULT_TARGET_SETS] sets and settings-default rests. */
    fun addExercise(exerciseId: String) = mutate {
        val position =
            (repository.exercisesForTemplate(templateId).maxOfOrNull { it.position } ?: -1) + 1
        repository.addTemplateExercise(
            TemplateExerciseEntity(
                templateId = templateId,
                exerciseId = exerciseId,
                position = position,
                targetSets = DEFAULT_TARGET_SETS,
            ),
        )
    }

    /** Swaps the exercise with its neighbor ([delta] = ±1) and renumbers positions 0..n-1. */
    fun moveExercise(templateExerciseId: Long, delta: Int) = mutate {
        val exercises = repository.exercisesForTemplate(templateId).toMutableList()
        val index = exercises.indexOfFirst { it.id == templateExerciseId }
        val target = index + delta
        if (index < 0 || target !in exercises.indices) return@mutate
        exercises[index] = exercises[target].also { exercises[target] = exercises[index] }
        repository.inTransaction {
            exercises.forEachIndexed { position, exercise ->
                if (exercise.position != position) {
                    repository.updateTemplateExercise(exercise.copy(position = position))
                }
            }
        }
    }

    fun stepSets(templateExerciseId: Long, delta: Int) = mutate {
        val exercise = templateExercise(templateExerciseId) ?: return@mutate
        val targetSets = (exercise.targetSets + delta).coerceIn(MIN_TARGET_SETS, MAX_TARGET_SETS)
        repository.updateTemplateExercise(exercise.copy(targetSets = targetSets))
    }

    /** ±15s; stepping from the settings default materializes an explicit per-exercise value. */
    fun stepWorkRest(templateExerciseId: Long, delta: Int) = mutate {
        val exercise = templateExercise(templateExerciseId) ?: return@mutate
        val current = exercise.restSecWork ?: settingsStore.snapshot().defaultRestSec
        val restSec = (current + delta * REST_STEP_SEC).coerceIn(MIN_REST_SEC, MAX_REST_SEC)
        repository.updateTemplateExercise(exercise.copy(restSecWork = restSec))
    }

    fun stepWarmupRest(templateExerciseId: Long, delta: Int) = mutate {
        val exercise = templateExercise(templateExerciseId) ?: return@mutate
        val current = exercise.restSecWarmup ?: settingsStore.snapshot().warmupRestSec
        val restSec = (current + delta * REST_STEP_SEC).coerceIn(MIN_REST_SEC, MAX_REST_SEC)
        repository.updateTemplateExercise(exercise.copy(restSecWarmup = restSec))
    }

    /** First tap arms ("tap again to remove"); second removes and returns to the list. */
    fun tapRemoveExercise() {
        val mode = _uiState.value.mode as? Mode.EditExercise ?: return
        if (!_uiState.value.removeArmed) {
            _uiState.update { it.copy(removeArmed = true) }
            return
        }
        _uiState.update { it.copy(removeArmed = false, mode = Mode.Overview) }
        mutate {
            repository.inTransaction {
                repository.deleteTemplateExercise(mode.templateExerciseId)
                repository.exercisesForTemplate(templateId).forEachIndexed { position, exercise ->
                    if (exercise.position != position) {
                        repository.updateTemplateExercise(exercise.copy(position = position))
                    }
                }
            }
        }
    }

    /** First tap arms ("tap again to delete"); second deletes and invokes [onDeleted] on Main. */
    fun tapDeleteTemplate(onDeleted: () -> Unit) {
        if (!_uiState.value.deleteArmed) {
            _uiState.update { it.copy(deleteArmed = true) }
            return
        }
        _uiState.update { it.copy(deleteArmed = false) }
        LiftsIo.write({ repository.deleteTemplate(templateId) }) {
            share.logChanged()
            // The delete survives Back, but the callback must not: if the editor is already gone,
            // goBack() would pop whatever screen is now on top.
            withContext(Dispatchers.Main) { if (!cleared) onDeleted() }
        }
    }

    @Volatile
    private var cleared = false

    override fun onCleared() {
        cleared = true
        super.onCleared()
    }

    /** Back collapses armed trash states and overlay modes before letting the screen pop. */
    override fun onBackPressed(): Boolean {
        val state = _uiState.value
        return when {
            state.removeArmed -> {
                _uiState.update { it.copy(removeArmed = false) }
                true
            }

            state.mode != Mode.Overview -> {
                _uiState.update { it.copy(mode = Mode.Overview, removeArmed = false) }
                true
            }

            state.deleteArmed -> {
                _uiState.update { it.copy(deleteArmed = false) }
                true
            }

            else -> false
        }
    }

    private fun templateExercise(id: Long): TemplateExerciseEntity? =
        repository.exercisesForTemplate(templateId).firstOrNull { it.id == id }

    /**
     * Runs [block] and then a refresh together on the app-wide write queue (serial, survives Back),
     * so read-modify-writes never interleave and an older refresh can never land after a newer one.
     * workouts.json holds the templates, so the exports catch up (a burst of edits folds into one or
     * two rewrites).
     */
    private fun mutate(block: () -> Unit) {
        LiftsIo.write {
            block()
            share.logChanged()
            refresh()
        }
    }

    private fun refresh() {
        val template = repository.templateById(templateId)
        val settings = settingsStore.snapshot()
        val rows = repository.exercisesForTemplate(templateId).map { exercise ->
            ExerciseRow(
                id = exercise.id,
                name = repository.exerciseById(exercise.exerciseId)?.let(::displayName) ?: exercise.exerciseId,
                targetSets = exercise.targetSets,
                restSec = exercise.restSecWork ?: settings.defaultRestSec,
                warmupRestSec = exercise.restSecWarmup ?: settings.warmupRestSec,
            )
        }
        _uiState.update { it.copy(name = template?.name.orEmpty(), rows = rows) }
    }
}

/**
 * Template editor (design/screens/template-editor.html): uniform 3gu exercise rows
 * ("name" + "N sets · rest M:SS"), up/down reorder, "+ Add Exercise" via the picker,
 * rename via the tappable top-bar center, trash with a two-tap confirm. Tapping a row
 * opens the card's documented overlay as a full-screen MODE takeover (top bar + Sets /
 * Rest / Warmup Rest steppers) so list rows stay uniform.
 *
 * Constructor and result type are FROZEN (cross-referenced by other screens).
 */
class TemplateEditorScreen(
    private val sealedActivity: SealedLightActivity,
    private val templateId: Long,
) : LightScreen<Unit, TemplateEditorViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    private val settingsStore = SettingsStore(lightContext.dataStore)

    override val viewModelClass: Class<TemplateEditorViewModel>
        get() = TemplateEditorViewModel::class.java

    override fun createViewModel() =
        TemplateEditorViewModel(repository, settingsStore, lightContext.liftsShare(), templateId)

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LiftsFrame(sealedActivity, lightContext) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (val mode = uiState.mode) {
                    TemplateEditorViewModel.Mode.Overview -> Overview(uiState)

                    TemplateEditorViewModel.Mode.Rename -> {
                        val nameState = rememberTextFieldState(uiState.name)
                        LightTextInputEditor(
                            title = "Template Name",
                            state = nameState,
                            onSubmit = viewModel::rename,
                            onBack = viewModel::cancelRename,
                            keyboardOptionsFlow = keyboardOptionsFlow,
                            singleLine = true,
                            // A capital only for a blank name, not mid-way through the one being edited.
                            initialCaps = uiState.name.isBlank(),
                            editorKey = "template-rename-${uiState.editorSession}",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    is TemplateEditorViewModel.Mode.EditExercise -> {
                        val row = uiState.rows.firstOrNull { it.id == mode.templateExerciseId }
                        if (row != null) {
                            EditExercise(row = row, removeArmed = uiState.removeArmed)
                        } else {
                            // Row vanished mid-edit (removal just landed); show the list.
                            Overview(uiState)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Overview(uiState: TemplateEditorViewModel.UiState) {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = { goBack() },
                ),
                center = LightTopBarCenter.TwoLineDetail(
                    line1 = uiState.name,
                    line2 = "TAP NAME TO RENAME",
                    onClick = viewModel::openRename,
                ),
                rightButton = LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    contentDescription = "done",
                    onClick = { goBack() },
                ),
            )

            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                uiState.rows.forEachIndexed { index, row ->
                    if (index > 0) RowDivider()
                    LiftsListRow(
                        onClick = { viewModel.openEdit(row.id) },
                        left = {
                            Column(modifier = Modifier.fillMaxWidth(ROW_LEFT_WIDTH_FRACTION)) {
                                LightText(
                                    text = row.name,
                                    variant = LightTextVariant.Copy,
                                    maxLines = 1,
                                )
                                LightText(
                                    text = exerciseDetail(row),
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    maxLines = 1,
                                )
                            }
                        },
                        right = {
                            ReorderArrows(
                                upEnabled = index > 0,
                                downEnabled = index < uiState.rows.lastIndex,
                                onUp = { viewModel.moveExercise(row.id, -1) },
                                onDown = { viewModel.moveExercise(row.id, +1) },
                            )
                        },
                    )
                }

                if (uiState.rows.isNotEmpty()) RowDivider()
                AddExerciseRow(
                    onClick = {
                        navigateTo(::ExercisePickerScreen) { exerciseId ->
                            viewModel.addExercise(exerciseId)
                        }
                    },
                )
            }

            if (uiState.deleteArmed) {
                ArmedHint(text = "TAP AGAIN TO DELETE")
            }
            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.TRASH,
                        contentDescription = "delete template",
                        onClick = { viewModel.tapDeleteTemplate { goBack(Unit) } },
                    ),
                ),
            )
        }
    }

    @Composable
    private fun EditExercise(row: TemplateEditorViewModel.ExerciseRow, removeArmed: Boolean) {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = { goBack() },
                ),
                center = LightTopBarCenter.Text(row.name),
                rightButton = LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    contentDescription = "done",
                    onClick = { goBack() },
                ),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
            ) {
                Stepper(
                    onStep = { delta -> viewModel.stepSets(row.id, delta) },
                    label = "Sets",
                    decrementEnabled = row.targetSets > MIN_TARGET_SETS,
                    incrementEnabled = row.targetSets < MAX_TARGET_SETS,
                ) {
                    LightText(
                        text = row.targetSets.toString(),
                        variant = LightTextVariant.Subtitle,
                        monospace = true,
                    )
                }

                Stepper(
                    onStep = { delta -> viewModel.stepWorkRest(row.id, delta) },
                    label = "Rest",
                    decrementEnabled = row.restSec > MIN_REST_SEC,
                    incrementEnabled = row.restSec < MAX_REST_SEC,
                ) {
                    LightText(
                        text = Format.clock(row.restSec),
                        variant = LightTextVariant.Subtitle,
                        monospace = true,
                    )
                }

                Stepper(
                    onStep = { delta -> viewModel.stepWarmupRest(row.id, delta) },
                    label = "Warmup Rest",
                    decrementEnabled = row.warmupRestSec > MIN_REST_SEC,
                    incrementEnabled = row.warmupRestSec < MAX_REST_SEC,
                ) {
                    LightText(
                        text = Format.clock(row.warmupRestSec),
                        variant = LightTextVariant.Subtitle,
                        monospace = true,
                    )
                }
            }

            if (removeArmed) {
                ArmedHint(text = "TAP AGAIN TO REMOVE")
            }
            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.TRASH,
                        contentDescription = "remove exercise",
                        onClick = viewModel::tapRemoveExercise,
                    ),
                ),
            )
        }
    }
}

/** "3 sets · rest 3:00" — rest shown is the effective work rest. */
private fun exerciseDetail(row: TemplateEditorViewModel.ExerciseRow): String {
    val noun = if (row.targetSets == 1) "set" else "sets"
    return "${row.targetSets} $noun · rest ${Format.clock(row.restSec)}"
}

/** Centered "+ ADD EXERCISE" 3gu row (design .listrow.add). */
@Composable
private fun AddExerciseRow(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LIST_ROW_HEIGHT_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = "+ Add Exercise".uppercase(),
            variant = LightTextVariant.Button,
            lighten = true,
            maxLines = 1,
        )
    }
}

/** Stacked up/down arrows (design .reorder cluster); disabled arrows dim to 40%. */
@Composable
private fun ReorderArrows(
    upEnabled: Boolean,
    downEnabled: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ReorderArrow(icon = LightIcons.UP, enabled = upEnabled, onClick = onUp)
        ReorderArrow(icon = LightIcons.DOWN, enabled = downEnabled, onClick = onDown)
    }
}

@Composable
private fun ReorderArrow(icon: LightIconConfiguration, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(
                width = REORDER_TARGET_WIDTH_UNITS.gridUnitsAsDp(),
                height = REORDER_TARGET_HEIGHT_UNITS.gridUnitsAsDp(),
            )
            .alpha(if (enabled) 1f else REORDER_DISABLED_ALPHA)
            .lightClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LightIcon(icon = icon, size = REORDER_ICON_UNITS)
    }
}
