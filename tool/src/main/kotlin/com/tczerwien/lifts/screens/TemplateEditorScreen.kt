package com.tczerwien.lifts.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.TemplateExerciseEntity
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.data.tracking
import com.tczerwien.lifts.library.renumber
import com.tczerwien.lifts.library.reorderPositions
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.session.MAX_REST_SEC
import com.tczerwien.lifts.session.MAX_RIR
import com.tczerwien.lifts.session.MAX_TARGET_REPS
import com.tczerwien.lifts.session.MAX_TEMPLATE_WARMUPS
import com.tczerwien.lifts.session.RepRange
import com.tczerwien.lifts.session.restOverride
import com.tczerwien.lifts.session.stepRepMax
import com.tczerwien.lifts.session.stepRepMin
import com.tczerwien.lifts.session.stepTargetRir
import com.tczerwien.lifts.session.stepTemplateWarmups
import com.tczerwien.lifts.session.templateSlotSummary
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.toolmanager.LiftsShare
import com.tczerwien.lifts.toolmanager.liftsShare
import com.tczerwien.lifts.ui.ArmedAction
import com.tczerwien.lifts.ui.ArmedHint
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.MonoDigits
import com.tczerwien.lifts.ui.ReorderArrows
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.RowName
import com.tczerwien.lifts.ui.Stepper
import com.tczerwien.lifts.ui.TWO_LINE_ROW_HEIGHT_UNITS
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

private const val DEFAULT_TARGET_SETS = 3
private const val MIN_TARGET_SETS = 1
private const val MAX_TARGET_SETS = 10
private const val REST_STEP_SEC = 15
private const val MIN_REST_SEC = 0

private const val LIST_ROW_HEIGHT_UNITS = 3f

/** The gap under each overlay stepper's label: a quarter of the usual, so four rows fit. */
private const val STEPPER_LABEL_GAP_UNITS = 0.25f

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
        /** Warm-ups it asks for; null repeats last session's. */
        val warmupSets: Int? = null,
        val repMin: Int? = null,
        val repMax: Int? = null,
        val targetRir: Int? = null,
        /** Effective work rest — explicit value or the settings default. */
        val restSec: Int,
        /** Effective warmup rest — explicit value or the settings default. */
        val warmupRestSec: Int,
        /** The work rest follows Settings' default (none stored). */
        val restIsDefault: Boolean = true,
        val warmupRestIsDefault: Boolean = true,
        /** A hold (Duration): seconds, so no rep range or RIR. */
        val hold: Boolean = false,
    )

    data class UiState(
        val name: String = "",
        val rows: List<ExerciseRow> = emptyList(),
        val mode: Mode = Mode.Overview,
        /**
         * The delete is confirmed and waiting its turn on the lane: rows, rename and add are ignored, and
         * Back lets the editor go, so the delete's own exit can't be swallowed or pop another screen.
         */
        val deleting: Boolean = false,
        /** Bumped per editor entry so the embedded keyboard binds to the fresh field state. */
        val editorSession: Int = 0,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * The overview trash: two taps, the second 0.4 to 3 s after the first ([ArmedAction]), so a
     * double-tap can't delete and an arm left over from before can't be confirmed later.
     */
    val deleteArm = ArmedAction(viewModelScope)

    /** An exercise's trash in its overlay, the same way. */
    val removeArm = ArmedAction(viewModelScope)

    /**
     * Queued behind any write still pending; only reads, so the exports are left alone. Back from the
     * picker or the screen waking: an armed trash is forgotten, so it can't be confirmed by a tap meant
     * for something else.
     */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        disarm()
        LiftsIo.write { refresh() }
    }

    private fun disarm() {
        deleteArm.disarm()
        removeArm.disarm()
    }

    fun openRename() {
        if (_uiState.value.deleting) return
        disarm()
        _uiState.update { it.copy(mode = Mode.Rename, editorSession = it.editorSession + 1) }
    }

    fun cancelRename() {
        _uiState.update { it.copy(mode = Mode.Overview) }
    }

    /**
     * Renames from the editor text (trimmed); a blank name cancels the rename. The list's trash sits
     * where SUBMIT was: a bounce of SUBMIT must not arm it ([ArmedAction.quiet]).
     */
    fun rename(raw: CharSequence) {
        val name = raw.toString().trim()
        deleteArm.quiet()
        _uiState.update { it.copy(mode = Mode.Overview) }
        if (name.isEmpty()) return
        mutate {
            repository.templateById(templateId)?.let { template ->
                repository.updateTemplate(template.copy(name = name))
            }
        }
    }

    fun openEdit(templateExerciseId: Long) {
        if (_uiState.value.deleting) return
        disarm()
        _uiState.update { it.copy(mode = Mode.EditExercise(templateExerciseId)) }
    }

    /** "+ Add Exercise" row: the picker opens, unless the template is on its way out. */
    fun canAddExercise(): Boolean {
        if (_uiState.value.deleting) return false
        disarm()
        return true
    }

    /**
     * Appends the picked exercise with [DEFAULT_TARGET_SETS] sets and settings-default rests; nothing
     * when the template is gone (deleted while the picker was open), which would break the foreign key.
     */
    fun addExercise(exerciseId: String) = mutate {
        repository.inTransaction {
            if (repository.templateById(templateId) == null) return@inTransaction
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
    }

    /** Swaps the exercise with its neighbor ([delta] = ±1); positions come out 0..n-1 ([reorderPositions]). */
    fun moveExercise(templateExerciseId: Long, delta: Int) {
        disarm()
        mutate {
            val exercises = repository.exercisesForTemplate(templateId)
            val index = exercises.indexOfFirst { it.id == templateExerciseId }
            val moves = reorderPositions(exercises, index, delta) { it.position } ?: return@mutate
            repository.inTransaction {
                moves.forEach { (exercise, position) ->
                    repository.updateTemplateExercise(exercise.copy(position = position))
                }
            }
        }
    }

    fun stepSets(templateExerciseId: Long, delta: Int) = edit(templateExerciseId) { exercise ->
        exercise.copy(targetSets = (exercise.targetSets + delta).coerceIn(MIN_TARGET_SETS, MAX_TARGET_SETS))
    }

    /** Warm-ups: last session's (none stored), then 0 to 5 ([stepTemplateWarmups]). */
    fun stepWarmups(templateExerciseId: Long, delta: Int) = edit(templateExerciseId) { exercise ->
        exercise.copy(warmupSets = stepTemplateWarmups(exercise.warmupSets, delta))
    }

    /** The rep range's low end ([stepRepMin]: 8 from none, never above the top). */
    fun stepRepMin(templateExerciseId: Long, delta: Int) = edit(templateExerciseId) { exercise ->
        val range = stepRepMin(RepRange(exercise.repMin, exercise.repMax), delta)
        exercise.copy(repMin = range.min, repMax = range.max)
    }

    /** The rep range's top ([stepRepMax]: four above the low end from none, never below it). */
    fun stepRepMax(templateExerciseId: Long, delta: Int) = edit(templateExerciseId) { exercise ->
        val range = stepRepMax(RepRange(exercise.repMin, exercise.repMax), delta)
        exercise.copy(repMin = range.min, repMax = range.max)
    }

    fun stepRir(templateExerciseId: Long, delta: Int) = edit(templateExerciseId) { exercise ->
        exercise.copy(targetRir = stepTargetRir(exercise.targetRir, delta))
    }

    /**
     * ±15 s from the rest in effect. A rest that lands on Settings' default is stored as none
     * ([restOverride]), so the exercise follows the default again if that changes: + then − is no change.
     */
    fun stepWorkRest(templateExerciseId: Long, delta: Int) = edit(templateExerciseId) { exercise ->
        val default = settingsStore.snapshot().defaultRestSec
        val restSec = ((exercise.restSecWork ?: default) + delta * REST_STEP_SEC).coerceIn(MIN_REST_SEC, MAX_REST_SEC)
        exercise.copy(restSecWork = restOverride(restSec, default))
    }

    fun stepWarmupRest(templateExerciseId: Long, delta: Int) = edit(templateExerciseId) { exercise ->
        val default = settingsStore.snapshot().warmupRestSec
        val restSec = ((exercise.restSecWarmup ?: default) + delta * REST_STEP_SEC).coerceIn(MIN_REST_SEC, MAX_REST_SEC)
        exercise.copy(restSecWarmup = restOverride(restSec, default))
    }

    /** Reads the exercise as it is on the lane, and writes [change]'s result when it differs. */
    private fun edit(templateExerciseId: Long, change: (TemplateExerciseEntity) -> TemplateExerciseEntity) {
        removeArm.disarm()
        mutate {
            val exercise = templateExercise(templateExerciseId) ?: return@mutate
            val changed = change(exercise)
            if (changed != exercise) repository.updateTemplateExercise(changed)
        }
    }

    /**
     * First tap arms ("tap again to remove"); a second, 0.4 to 3 s later, removes and returns to the
     * list, whose trash (the whole template's) sits in the same spot: a bounce of the confirming tap
     * must not arm it ([ArmedAction.quiet]).
     */
    fun tapRemoveExercise() {
        val mode = _uiState.value.mode as? Mode.EditExercise ?: return
        if (!removeArm.tap()) return
        deleteArm.quiet()
        _uiState.update { it.copy(mode = Mode.Overview) }
        mutate {
            repository.inTransaction {
                repository.deleteTemplateExercise(mode.templateExerciseId)
                renumber(repository.exercisesForTemplate(templateId)) { it.position }.forEach { (exercise, position) ->
                    repository.updateTemplateExercise(exercise.copy(position = position))
                }
            }
        }
    }

    /** First tap arms ("tap again to delete"); a second, 0.4 to 3 s later, deletes and invokes [onDeleted] on Main. */
    fun tapDeleteTemplate(onDeleted: () -> Unit) {
        // A second delete would queue a second exit, which would pop the screen below.
        if (_uiState.value.deleting || !deleteArm.tap()) return
        _uiState.update { it.copy(deleting = true) }
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
            // The template is going: its exit (or his Back) leaves, whatever else is open.
            state.deleting -> false

            removeArm.armed.value -> {
                removeArm.disarm()
                true
            }

            state.mode != Mode.Overview -> {
                _uiState.update { it.copy(mode = Mode.Overview) }
                true
            }

            deleteArm.armed.value -> {
                deleteArm.disarm()
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
            val catalog = repository.exerciseById(exercise.exerciseId)
            ExerciseRow(
                id = exercise.id,
                name = catalog?.let(::displayName) ?: exercise.exerciseId,
                hold = catalog?.tracking?.takesDuration == true,
                targetSets = exercise.targetSets,
                warmupSets = exercise.warmupSets,
                repMin = exercise.repMin,
                repMax = exercise.repMax,
                targetRir = exercise.targetRir,
                restSec = exercise.restSecWork ?: settings.defaultRestSec,
                warmupRestSec = exercise.restSecWarmup ?: settings.warmupRestSec,
                restIsDefault = exercise.restSecWork == null,
                warmupRestIsDefault = exercise.restSecWarmup == null,
            )
        }
        _uiState.update { it.copy(name = template?.name.orEmpty(), rows = rows) }
    }
}

/**
 * Template editor (design/screens/template-editor.html): uniform 4gu exercise rows
 * ("name" + "2W + 3 × 8–12 · RIR 1 · rest 3:00"), up/down reorder, "+ Add Exercise" via the picker,
 * rename via the tappable top-bar center, trash with a two-tap confirm. Tapping a row
 * opens the card's documented overlay as a full-screen MODE takeover (top bar + steppers in pairs:
 * Sets and Warm-ups, the rep range, RIR and Rest, Warm-up Rest) so list rows stay uniform.
 *
 * Constructor and result type are FROZEN (cross-referenced by other screens).
 */
class TemplateEditorScreen(
    private val sealedActivity: SealedLightActivity,
    private val templateId: Long,
) : LightScreen<Unit, TemplateEditorViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    private val settingsStore = SettingsStore(lightContext.dataStore)

    /**
     * The list's place, held by the screen: the rename editor, an exercise's overlay and the picker all
     * replace the list, and a remembered state would come back at the top, with #9 off screen again.
     */
    private val scrollState = ScrollState(0)

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
                            EditExercise(row = row)
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
        val deleteArmed by viewModel.deleteArm.armed.collectAsState()
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = { goBack() },
                ),
                // The SDK draws both lines alike; in lower case the hint no longer outshouts the name.
                center = LightTopBarCenter.TwoLineDetail(
                    line1 = uiState.name,
                    line2 = "tap to rename",
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
                scrollState = scrollState,
            ) {
                uiState.rows.forEachIndexed { index, row ->
                    if (index > 0) RowDivider()
                    LiftsListRow(
                        onClick = { viewModel.openEdit(row.id) },
                        heightUnits = TWO_LINE_ROW_HEIGHT_UNITS,
                        left = {
                            Column {
                                RowName(row.name)
                                LightText(
                                    text = if (row.hold) {
                                        templateSlotSummary(row.targetSets, row.warmupSets, null, null, null, row.restSec)
                                    } else {
                                        templateSlotSummary(row.targetSets, row.warmupSets, row.repMin, row.repMax, row.targetRir, row.restSec)
                                    },
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
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
                        // The state as it is now, not as this frame drew it: the delete may be a moment old.
                        if (viewModel.canAddExercise()) {
                            navigateTo(::ExercisePickerScreen) { exerciseId -> viewModel.addExercise(exerciseId) }
                        }
                    },
                )
            }

            if (deleteArmed) {
                ArmedHint(text = "TAP AGAIN TO DELETE TEMPLATE")
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
    private fun EditExercise(row: TemplateEditorViewModel.ExerciseRow) {
        val removeArmed by viewModel.removeArm.armed.collectAsState()
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

            // Seven steppers in pairs, so the whole prescription fits one screen with nothing to scroll
            // (labels sit close to their targets: four rows at the usual 1gu gap overflowed the 1240px screen).
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.spacedBy(0.5f.gridUnitsAsDp()),
            ) {
                StepperPair(
                    left = {
                        TemplateStepper(
                            label = "Sets",
                            value = row.targetSets.toString(),
                            decrementEnabled = row.targetSets > MIN_TARGET_SETS,
                            incrementEnabled = row.targetSets < MAX_TARGET_SETS,
                            onStep = { viewModel.stepSets(row.id, it) },
                        )
                    },
                    right = {
                        // None stored: as many warm-ups as last session had.
                        TemplateStepper(
                            label = "Warm-ups",
                            value = row.warmupSets?.toString() ?: "Last",
                            decrementEnabled = row.warmupSets != null,
                            incrementEnabled = (row.warmupSets ?: -1) < MAX_TEMPLATE_WARMUPS,
                            onStep = { viewModel.stepWarmups(row.id, it) },
                        )
                    },
                )
                if (!row.hold) {
                    StepperPair(
                        left = {
                            TemplateStepper(
                                label = "Reps From",
                                value = row.repMin?.toString() ?: "—",
                                decrementEnabled = row.repMin != null,
                                incrementEnabled = (row.repMin ?: 0) < MAX_TARGET_REPS,
                                onStep = { viewModel.stepRepMin(row.id, it) },
                            )
                        },
                        right = {
                            TemplateStepper(
                                label = "Reps To",
                                value = row.repMax?.toString() ?: "—",
                                decrementEnabled = row.repMax != null,
                                incrementEnabled = (row.repMax ?: 0) < MAX_TARGET_REPS,
                                onStep = { viewModel.stepRepMax(row.id, it) },
                            )
                        },
                    )
                    StepperPair(
                        left = {
                            TemplateStepper(
                                label = "RIR",
                                value = row.targetRir?.toString() ?: "—",
                                decrementEnabled = row.targetRir != null,
                                incrementEnabled = (row.targetRir ?: -1) < MAX_RIR,
                                onStep = { viewModel.stepRir(row.id, it) },
                            )
                        },
                        right = {
                            TemplateStepper(
                                label = if (row.restIsDefault) "Rest · Default" else "Rest",
                                value = Format.clock(row.restSec),
                                decrementEnabled = row.restSec > MIN_REST_SEC,
                                incrementEnabled = row.restSec < MAX_REST_SEC,
                                onStep = { viewModel.stepWorkRest(row.id, it) },
                            )
                        },
                    )
                    StepperPair(
                        left = {
                            TemplateStepper(
                                label = if (row.warmupRestIsDefault) "Warm-up Rest · Default" else "Warm-up Rest",
                                value = Format.clock(row.warmupRestSec),
                                decrementEnabled = row.warmupRestSec > MIN_REST_SEC,
                                incrementEnabled = row.warmupRestSec < MAX_REST_SEC,
                                onStep = { viewModel.stepWarmupRest(row.id, it) },
                            )
                        },
                        right = null,
                    )
                } else {
                    // A hold is seconds: no rep range and no RIR, so its rests pair up.
                    StepperPair(
                        left = { WorkRestStepper(row) },
                        right = { WarmupRestStepper(row) },
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

    @Composable
    private fun WorkRestStepper(row: TemplateEditorViewModel.ExerciseRow) {
        TemplateStepper(
            label = if (row.restIsDefault) "Rest · Default" else "Rest",
            value = Format.clock(row.restSec),
            decrementEnabled = row.restSec > MIN_REST_SEC,
            incrementEnabled = row.restSec < MAX_REST_SEC,
            onStep = { viewModel.stepWorkRest(row.id, it) },
        )
    }

    @Composable
    private fun WarmupRestStepper(row: TemplateEditorViewModel.ExerciseRow) {
        TemplateStepper(
            label = if (row.warmupRestIsDefault) "Warm-up Rest · Default" else "Warm-up Rest",
            value = Format.clock(row.warmupRestSec),
            decrementEnabled = row.warmupRestSec > MIN_REST_SEC,
            incrementEnabled = row.warmupRestSec < MAX_REST_SEC,
            onStep = { viewModel.stepWarmupRest(row.id, it) },
        )
    }
}

/** Two steppers side by side, each half the width; [right] null leaves its half empty. */
@Composable
private fun StepperPair(left: @Composable () -> Unit, right: (@Composable () -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
    ) {
        Box(modifier = Modifier.weight(1f)) { left() }
        Box(modifier = Modifier.weight(1f)) { right?.invoke() }
    }
}

/**
 * One of the overlay's steppers: its label above, the value in Copy between the 4gu targets, its digits
 * monospace and the colon not ([MonoDigits]): a rest of 10:00 or more in full monospace ran over them.
 */
@Composable
private fun TemplateStepper(
    label: String,
    value: String,
    decrementEnabled: Boolean,
    incrementEnabled: Boolean,
    onStep: (Int) -> Unit,
) {
    Stepper(
        onStep = onStep,
        label = label,
        decrementEnabled = decrementEnabled,
        incrementEnabled = incrementEnabled,
        labelGapUnits = STEPPER_LABEL_GAP_UNITS,
        modifier = Modifier.fillMaxWidth(),
    ) {
        MonoDigits(text = value, variant = LightTextVariant.Copy)
    }
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
