package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.Tracking
import com.tczerwien.lifts.data.bundledCatalog
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.data.tracking
import com.tczerwien.lifts.library.CatalogEntry
import com.tczerwien.lifts.library.Removal
import com.tczerwien.lifts.library.catalogEntries
import com.tczerwien.lifts.library.formTracking
import com.tczerwien.lifts.library.nameCheckEntries
import com.tczerwien.lifts.library.nameClash
import com.tczerwien.lifts.seed.CatalogSource
import com.tczerwien.lifts.session.HistoryVersion
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.toolmanager.LiftsShare
import com.tczerwien.lifts.toolmanager.liftsShare
import com.tczerwien.lifts.ui.ArmedAction
import com.tczerwien.lifts.ui.ArmedHint
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFieldRow
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.SectionLabel
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
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
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CustomExerciseViewModel(
    private val repository: LiftsRepository,
    private val share: LiftsShare,
    /** The APK's catalog: a renamed lift's catalog name is taken too, for the name check. */
    private val catalog: CatalogSource,
    initialName: String = "",
    /** Editing this custom exercise; null makes a new one. */
    private val editId: String? = null,
) : LightViewModel<String>() {

    enum class Mode { FORM, NAME_EDITOR, PRIMARY_PICKER, SECONDARY_PICKER, EQUIPMENT_PICKER, CATEGORY_PICKER, TYPE_PICKER }

    data class UiState(
        val mode: Mode = Mode.FORM,
        /** Editing an exercise he made, rather than making one. */
        val editing: Boolean = false,
        /** The form holds what it should: always for a new one; an edited one once it has loaded. */
        val loaded: Boolean = true,
        val name: String = "",
        /** Raw dataset value ("chest", "middle back"); required for save. */
        val primaryMuscle: String? = null,
        val secondaryMuscles: List<String> = emptyList(),
        /** Raw dataset value; null = no equipment. */
        val equipment: String? = null,
        val category: String = DEFAULT_CATEGORY,
        /** The Type he picked; until then it follows the form ([formTracking]). */
        val chosenTracking: Tracking? = null,
        val muscleOptions: List<String> = emptyList(),
        val equipmentOptions: List<String> = emptyList(),
        val categoryOptions: List<String> = emptyList(),
        val editorSession: Int = 0,
        /** Another exercise already answers to [name] ([nameClash]); a new one can use it instead. */
        val nameTaken: Taken? = null,
        /** Templates that ask for the edited exercise: removing it takes it out of them. */
        val inTemplates: Int = 0,
        /** A workout has the edited exercise, so removing it hides it ([Removal.HIDE]); otherwise it is deleted. */
        val inWorkouts: Boolean = false,
        /** ...and a finished workout has a ticked set of it: history the hide keeps. */
        val hasHistory: Boolean = false,
        /**
         * Leaving with this result, via goBack: the new exercise once the insert succeeded, the existing
         * one he chose over a taken name, the edited one once saved or hidden, or "" once deleted.
         */
        val createdId: String? = null,
    ) {
        /** How the exercise is measured: his pick, else the default for its category and equipment. */
        val tracking: Tracking get() = formTracking(chosenTracking, category, equipment)

        val valid: Boolean
            get() = loaded && name.isNotBlank() && primaryMuscle != null && nameTaken == null
    }

    /**
     * The exercise that has the name: its id, the name Lifts shows for it, and whether he took it out of
     * the picker ([hidden]: offering it brings it back).
     */
    data class Taken(val id: String, val shown: String, val hidden: Boolean = false)

    private val _uiState = MutableStateFlow(
        UiState(name = initialName.trim(), editing = editId != null, loaded = editId == null),
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** The trash, while editing, takes two taps. */
    val removeArm = ArmedAction(viewModelScope)

    /** A save, restore or removal is on its way; set on Main, cleared on the lane when the name turned out taken. */
    @Volatile
    private var saving = false

    /** Every exercise's names, for the duplicate check; hidden ones too, since the files still list them. */
    @Volatile
    private var names: List<CatalogEntry> = emptyList()

    /** The ids of exercises he took out of the picker. */
    @Volatile
    private var hiddenIds: Set<String> = emptySet()

    /**
     * Loads here only: the SDK calls this straight after creating the view model, so init needn't.
     * Behind any write still queued: an exercise edited from Exercise Detail just after its Type was
     * changed must fill in the new Type, or saving the form would put the old one back ([save]).
     */
    override fun onScreenShow(screen: SimpleLightScreen<String>) {
        LiftsIo.write({}) { loadOptions() }
    }

    /**
     * Back from any editor or picker mode returns to the form, and an armed trash disarms. Only the
     * in-app back button reaches this: the SDK routes the system Back key straight to the activity.
     */
    override fun onBackPressed(): Boolean {
        // Saved: the goBack that hands the result back must not be taken for closing a mode.
        if (_uiState.value.createdId != null) return false
        if (_uiState.value.mode == Mode.FORM) {
            if (!removeArm.armed.value) return false
            removeArm.disarm()
            return true
        }
        closeMode()
        return true
    }

    fun openNameEditor() {
        removeArm.disarm()
        val state = _uiState.value
        _uiState.value = state.copy(mode = Mode.NAME_EDITOR, editorSession = state.editorSession + 1)
    }

    fun openMode(mode: Mode) {
        removeArm.disarm()
        _uiState.value = _uiState.value.copy(mode = mode)
    }

    fun closeMode() {
        _uiState.value = _uiState.value.copy(mode = Mode.FORM)
    }

    /** The name editor's SUBMIT, back to the form, whose trash sits where SUBMIT was ([ArmedAction.quiet]). */
    fun submitName(raw: String) {
        removeArm.quiet()
        _uiState.update { it.copy(name = raw.replace(Regex("\\s+"), " ").trim(), mode = Mode.FORM).withClash() }
    }

    /**
     * He takes the exercise that already has the name: it becomes the pick, and nothing is created. One
     * he had taken out of the picker comes back into it first.
     */
    fun useExisting() {
        val state = _uiState.value
        val taken = state.nameTaken ?: return
        if (state.editing || saving) return
        if (!taken.hidden) {
            _uiState.update { it.copy(createdId = taken.id) }
            return
        }
        saving = true
        LiftsIo.write({ repository.setExerciseHidden(taken.id, hidden = false) }) { changed ->
            // workouts.json marks hidden exercises.
            if (changed) share.logChanged()
            _uiState.update { it.copy(createdId = taken.id) }
        }
    }

    private fun UiState.withClash(): UiState =
        copy(nameTaken = nameClash(name, names, selfId = editId)?.let { Taken(it.id, it.shown, hidden = it.id in hiddenIds) })

    /** The primary muscle leaves the secondaries, so the form shows what save will store. */
    fun selectPrimaryMuscle(muscle: String) {
        _uiState.update {
            it.copy(primaryMuscle = muscle, secondaryMuscles = it.secondaryMuscles - muscle, mode = Mode.FORM)
        }
    }

    /** Multi-select: toggles membership, keeps the picker open (order follows the option list). */
    fun toggleSecondaryMuscle(muscle: String) {
        val state = _uiState.value
        val selection = if (muscle in state.secondaryMuscles) {
            state.secondaryMuscles - muscle
        } else {
            state.muscleOptions.filter { it in state.secondaryMuscles || it == muscle }
        }
        _uiState.value = state.copy(secondaryMuscles = selection)
    }

    fun selectEquipment(equipment: String?) {
        _uiState.value = _uiState.value.copy(equipment = equipment, mode = Mode.FORM)
    }

    fun selectCategory(category: String) {
        _uiState.value = _uiState.value.copy(category = category, mode = Mode.FORM)
    }

    /** The Type row's list, by its label: his pick stands from now on. */
    fun selectTracking(label: String) {
        val tracking = Tracking.entries.firstOrNull { it.label == label } ?: return
        _uiState.update { it.copy(chosenTracking = tracking, mode = Mode.FORM) }
    }

    /**
     * A new exercise is inserted as "custom-<uuid>", measured as the form's Type says; an edited one is
     * saved in place ([LiftsRepository.updateCustomExercise]: history keeps it, by id). The primary muscle
     * is dropped from the secondaries. The name is checked again on the lane, against the catalog as it
     * is then: a name taken meanwhile saves nothing and shows the clash instead.
     */
    fun save() {
        val state = _uiState.value
        val primaryMuscle = state.primaryMuscle
        if (!state.valid || primaryMuscle == null || saving) return
        saving = true
        val id = editId ?: "custom-${UUID.randomUUID()}"
        val exercise = ExerciseEntity(
            id = id,
            name = state.name.trim(),
            equipment = state.equipment,
            primaryMuscles = listOf(primaryMuscle),
            secondaryMuscles = state.secondaryMuscles.filterNot { it == primaryMuscle },
            category = state.category,
            level = null,
            instructions = emptyList(),
            isCustom = true,
            trackingType = state.tracking.stored,
        )
        // On the write lane, so Back can't drop it half-way.
        LiftsIo.write({
            val current = catalogEntries(repository.exerciseSummaries(), catalog)
            names = current
            val clash = nameClash(state.name, current, selfId = editId)
            when {
                clash != null -> SaveResult.Taken
                editId == null -> {
                    repository.addExercise(exercise)
                    SaveResult.Saved(changed = true, typeChanged = false)
                }
                else -> {
                    val before = repository.exerciseById(editId)?.tracking
                    val changed = repository.updateCustomExercise(exercise, state.tracking)
                    SaveResult.Saved(changed, typeChanged = before != null && before != state.tracking)
                }
            }
        }) { result ->
            when (result) {
                SaveResult.Taken -> {
                    saving = false
                    _uiState.update { it.withClash() }
                }
                is SaveResult.Saved -> {
                    // workouts.json lists every custom exercise, used or not.
                    if (result.changed) share.logChanged()
                    // Its logged weights may have changed sign: an open workout's cached history goes.
                    if (result.typeChanged) HistoryVersion.bump()
                    // Atomic: a mode tap on Main in the same instant must not write createdId back to null.
                    _uiState.update { it.copy(createdId = id) }
                }
            }
        }
    }

    private sealed interface SaveResult {
        data object Taken : SaveResult

        data class Saved(val changed: Boolean, val typeChanged: Boolean) : SaveResult
    }

    /**
     * The trash, while editing: the first tap arms, the second removes the exercise
     * ([LiftsRepository.removeCustomExercise]): out of every template, then deleted when no workout
     * has it, else hidden from the picker with its history kept. The screen then leaves.
     */
    fun tapRemove() {
        val id = editId ?: return
        if (saving || !_uiState.value.loaded || !removeArm.tap()) return
        saving = true
        LiftsIo.write({ repository.removeCustomExercise(id) }) { removal ->
            if (removal != null) share.logChanged()
            _uiState.update { it.copy(createdId = if (removal == Removal.HIDE) id else "") }
        }
    }

    /**
     * Option lists derive from the live catalog so custom values stay dataset-consistent; the names
     * load for the duplicate check (a name filled in by the picker is checked as soon as they land).
     * An exercise being edited fills the form from what is stored.
     */
    private fun loadOptions() {
        val summaries = repository.exerciseSummaries()
        names = nameCheckEntries(summaries, catalog)
        hiddenIds = summaries.filter { it.hidden }.mapTo(HashSet()) { it.id }
        val edited = editId?.let { repository.exerciseById(it) }
        val inTemplates = editId?.let { repository.templatesWithExercise(it) } ?: 0
        val hasHistory = editId?.let { repository.exerciseHasHistory(it) } ?: false
        val inWorkouts = editId?.let { repository.exerciseInWorkouts(it) } ?: false
        _uiState.update { state ->
            val filled = if (edited != null && !state.loaded) {
                state.copy(
                    loaded = true,
                    name = edited.name,
                    primaryMuscle = edited.primaryMuscles.firstOrNull(),
                    secondaryMuscles = edited.secondaryMuscles,
                    equipment = edited.equipment,
                    category = edited.category,
                    chosenTracking = edited.tracking,
                )
            } else {
                state
            }
            filled.copy(inTemplates = inTemplates, hasHistory = hasHistory, inWorkouts = inWorkouts).withClash()
        }
        // Muscles, equipment and categories only: without the 873 instruction lists to decode.
        val all = repository.listExercisesWithoutInstructions()
        val muscles = (all.flatMap { it.primaryMuscles } + all.flatMap { it.secondaryMuscles })
            .distinct()
            .sorted()
        _uiState.update {
            it.copy(
                muscleOptions = muscles,
                equipmentOptions = all.mapNotNull { exercise -> exercise.equipment }.distinct().sorted(),
                categoryOptions = all.map { exercise -> exercise.category }.distinct().sorted()
                    .ifEmpty { listOf(DEFAULT_CATEGORY) },
            )
        }
    }

    companion object {
        const val DEFAULT_CATEGORY = "strength"
    }
}

/**
 * Custom exercise form (design/screens/custom-exercise.html): six field rows — name via
 * the SDK editor, the rest via inline picker-list modes fed from the live catalog values, Type last
 * (it follows category and equipment until he picks one). The top-bar checkmark appears once name +
 * primary muscle are set; save inserts and returns the new exerciseId via goBack. [initialName]
 * fills the name (the picker's Create row passes what he searched for). With [editId] it edits one
 * he made ("Edit Exercise"), and its trash deletes it, or hides it once a workout or template uses
 * it; the result is its id, or "" once deleted.
 */
class CustomExerciseScreen(
    private val sealedActivity: SealedLightActivity,
    private val initialName: String = "",
    private val editId: String? = null,
) : LightScreen<String, CustomExerciseViewModel>(sealedActivity) {

    override val viewModelClass: Class<CustomExerciseViewModel>
        get() = CustomExerciseViewModel::class.java

    override fun createViewModel() = CustomExerciseViewModel(
        lightContext.liftsRepository(),
        lightContext.liftsShare(),
        lightContext.bundledCatalog(),
        initialName,
        editId,
    )

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        val createdId = uiState.createdId
        LaunchedEffect(createdId) {
            if (createdId != null) goBack(createdId)
        }

        LiftsFrame(sealedActivity, lightContext, switchKey = uiState.mode) {
            when (uiState.mode) {
                CustomExerciseViewModel.Mode.FORM -> FormContent(uiState)

                CustomExerciseViewModel.Mode.NAME_EDITOR -> {
                    val textState = remember { TextFieldState(uiState.name) }
                    LightTextInputEditor(
                        title = "Name",
                        state = textState,
                        onSubmit = { viewModel.submitName(it.toString()) },
                        onBack = viewModel::closeMode,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        singleLine = true,
                        // A capital to start a new name; none mid-way through one filled in or edited.
                        initialCaps = uiState.name.isBlank(),
                        editorKey = "custom-name-${uiState.editorSession}",
                        modifier = Modifier
                            .fillMaxSize()
                            .background(LightThemeTokens.colors.background),
                    )
                }

                CustomExerciseViewModel.Mode.PRIMARY_PICKER -> OptionPicker(
                    title = "Primary Muscle",
                    options = uiState.muscleOptions,
                    isSelected = { it == uiState.primaryMuscle },
                    onSelect = { it?.let(viewModel::selectPrimaryMuscle) },
                    onBack = viewModel::closeMode,
                )

                CustomExerciseViewModel.Mode.SECONDARY_PICKER -> OptionPicker(
                    title = "Secondary Muscles",
                    // The primary is not a secondary too; save would drop it anyway.
                    options = uiState.muscleOptions - setOfNotNull(uiState.primaryMuscle),
                    isSelected = { it in uiState.secondaryMuscles },
                    onSelect = { it?.let(viewModel::toggleSecondaryMuscle) },
                    onBack = viewModel::closeMode,
                    multiSelect = true,
                )

                CustomExerciseViewModel.Mode.EQUIPMENT_PICKER -> OptionPicker(
                    title = "Equipment",
                    options = uiState.equipmentOptions,
                    isSelected = { it == uiState.equipment },
                    onSelect = viewModel::selectEquipment,
                    onBack = viewModel::closeMode,
                    includeNone = true,
                )

                CustomExerciseViewModel.Mode.CATEGORY_PICKER -> OptionPicker(
                    title = "Category",
                    options = uiState.categoryOptions,
                    isSelected = { it == uiState.category },
                    onSelect = { it?.let(viewModel::selectCategory) },
                    onBack = viewModel::closeMode,
                )

                CustomExerciseViewModel.Mode.TYPE_PICKER -> OptionPicker(
                    title = "Type",
                    options = Tracking.entries.map { it.label },
                    isSelected = { it == uiState.tracking.label },
                    onSelect = { it?.let(viewModel::selectTracking) },
                    onBack = viewModel::closeMode,
                )
            }
        }
    }

    @Composable
    private fun FormContent(uiState: CustomExerciseViewModel.UiState) {
        val removeArmed by viewModel.removeArm.armed.collectAsState()
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
                center = LightTopBarCenter.Text(if (uiState.editing) "Edit Exercise" else "New Exercise"),
                rightButton = if (uiState.valid) {
                    LightBarButton.LightIcon(
                        icon = LightIcons.ACCEPT,
                        contentDescription = "save",
                        onClick = { viewModel.save() },
                    )
                } else {
                    null
                },
            )

            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LiftsFieldRow(
                    label = "Name",
                    value = uiState.name.ifBlank { "—" },
                    onClick = { viewModel.openNameEditor() },
                    chevron = true,
                )
                uiState.nameTaken?.let { taken ->
                    // Two exercises with one name would split his history: offer the one he has (and
                    // one he took out of the picker comes back). Editing, the name just can't be used.
                    SectionLabel(if (uiState.editing) "Name in use: ${taken.shown}" else "Name in use")
                    if (!uiState.editing) {
                        LiftsListRow(
                            onClick = viewModel::useExisting,
                            left = {
                                LightText(
                                    text = if (taken.hidden) "Restore ${taken.shown}" else "Use ${taken.shown}",
                                    variant = LightTextVariant.Copy,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            right = { LightIcon(icon = LightIcons.ARROW_RIGHT, size = 1.6f) },
                        )
                    }
                }
                LiftsFieldRow(
                    label = "Primary Muscle",
                    value = uiState.primaryMuscle?.let(::titleCaseLabel) ?: "—",
                    onClick = { viewModel.openMode(CustomExerciseViewModel.Mode.PRIMARY_PICKER) },
                    chevron = true,
                )
                LiftsFieldRow(
                    label = "Secondary Muscles",
                    value = if (uiState.secondaryMuscles.isEmpty()) {
                        "None"
                    } else {
                        uiState.secondaryMuscles.joinToString(", ") { titleCaseLabel(it) }
                    },
                    onClick = { viewModel.openMode(CustomExerciseViewModel.Mode.SECONDARY_PICKER) },
                    chevron = true,
                )
                LiftsFieldRow(
                    label = "Equipment",
                    value = uiState.equipment?.let(::titleCaseLabel) ?: "None",
                    onClick = { viewModel.openMode(CustomExerciseViewModel.Mode.EQUIPMENT_PICKER) },
                    chevron = true,
                )
                LiftsFieldRow(
                    label = "Category",
                    value = titleCaseLabel(uiState.category),
                    onClick = { viewModel.openMode(CustomExerciseViewModel.Mode.CATEGORY_PICKER) },
                    chevron = true,
                )
                // How its sets are measured: which numbers the rows take, and what a record is.
                LiftsFieldRow(
                    label = "Type",
                    value = uiState.tracking.label,
                    onClick = { viewModel.openMode(CustomExerciseViewModel.Mode.TYPE_PICKER) },
                    chevron = true,
                )
            }

            if (uiState.editing) {
                if (removeArmed) {
                    // In a workout, it leaves the picker and stays in the workouts; otherwise it is gone.
                    ArmedHint(
                        text = when {
                            !uiState.inWorkouts -> "TAP AGAIN TO DELETE"
                            uiState.hasHistory -> "TAP AGAIN TO HIDE · HISTORY KEPT"
                            else -> "TAP AGAIN TO HIDE · IT'S IN A WORKOUT"
                        },
                    )
                    // Either way it leaves his templates: say so before he confirms.
                    if (uiState.inTemplates > 0) {
                        ArmedHint(text = "IT LEAVES ${Format.count(uiState.inTemplates, "TEMPLATE")}")
                    }
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.TRASH,
                            contentDescription = "remove exercise",
                            onClick = viewModel::tapRemove,
                        ),
                    ),
                )
            }
        }
    }
}

private const val OPTION_ROW_HEIGHT_UNITS = 3f

/**
 * Full-screen option list mode: uniform 3gu rows, selected rows carry a right checkmark.
 * Single-select closes on tap; [multiSelect] keeps the list open and adds a top-bar
 * checkmark as an explicit done affordance (back applies the selection either way).
 * [includeNone] prepends a null "None" option (used for equipment).
 */
@Composable
private fun OptionPicker(
    title: String,
    options: List<String>,
    isSelected: (String?) -> Boolean,
    onSelect: (String?) -> Unit,
    onBack: () -> Unit,
    multiSelect: Boolean = false,
    includeNone: Boolean = false,
) {
    val rows: List<String?> = if (includeNone) listOf(null) + options else options
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
            ),
            center = LightTopBarCenter.Text(title),
            rightButton = if (multiSelect) {
                LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    contentDescription = "done",
                    onClick = onBack,
                )
            } else {
                null
            },
        )
        LightLazyScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            uniformItemHeightGridUnits = OPTION_ROW_HEIGHT_UNITS,
        ) {
            itemsIndexed(rows) { index, option ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(OPTION_ROW_HEIGHT_UNITS.gridUnitsAsDp()),
                ) {
                    LiftsListRow(
                        onClick = { onSelect(option) },
                        left = {
                            LightText(
                                text = option?.let(::titleCaseLabel) ?: "None",
                                variant = LightTextVariant.Copy,
                                lighten = option == null,
                                maxLines = 1,
                            )
                        },
                        right = if (isSelected(option)) {
                            {
                                LightIcon(
                                    icon = LightIcons.ACCEPT,
                                    size = 1.6f,
                                    contentDescription = "selected",
                                )
                            }
                        } else {
                            null
                        },
                    )
                    if (index < rows.lastIndex) {
                        RowDivider(modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }
}
