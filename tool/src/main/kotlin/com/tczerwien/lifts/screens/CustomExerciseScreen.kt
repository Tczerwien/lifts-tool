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
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.toolmanager.LiftsShare
import com.tczerwien.lifts.toolmanager.liftsShare
import com.tczerwien.lifts.ui.LiftsFieldRow
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.RowDivider
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CustomExerciseViewModel(
    private val repository: LiftsRepository,
    private val share: LiftsShare,
) : LightViewModel<String>() {

    enum class Mode { FORM, NAME_EDITOR, PRIMARY_PICKER, SECONDARY_PICKER, EQUIPMENT_PICKER, CATEGORY_PICKER }

    data class UiState(
        val mode: Mode = Mode.FORM,
        val name: String = "",
        /** Raw dataset value ("chest", "middle back"); required for save. */
        val primaryMuscle: String? = null,
        val secondaryMuscles: List<String> = emptyList(),
        /** Raw dataset value; null = no equipment. */
        val equipment: String? = null,
        val category: String = DEFAULT_CATEGORY,
        val muscleOptions: List<String> = emptyList(),
        val equipmentOptions: List<String> = emptyList(),
        val categoryOptions: List<String> = emptyList(),
        val editorSession: Int = 0,
        /** Set once the insert succeeded; the screen returns it via goBack. */
        val createdId: String? = null,
    ) {
        val valid: Boolean
            get() = name.isNotBlank() && primaryMuscle != null
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var saving = false

    /** Loads here only: the SDK calls this straight after creating the view model, so init needn't. */
    override fun onScreenShow(screen: SimpleLightScreen<String>) {
        viewModelScope.launch(Dispatchers.IO) { loadOptions() }
    }

    /**
     * Back from any editor or picker mode returns to the form. Only the in-app back button reaches this: the SDK routes the
     * system Back key straight to the activity.
     */
    override fun onBackPressed(): Boolean {
        // Saved: the goBack that hands the new id to the picker must not be taken for closing a mode.
        if (_uiState.value.createdId != null) return false
        if (_uiState.value.mode == Mode.FORM) return false
        closeMode()
        return true
    }

    fun openNameEditor() {
        val state = _uiState.value
        _uiState.value = state.copy(mode = Mode.NAME_EDITOR, editorSession = state.editorSession + 1)
    }

    fun openMode(mode: Mode) {
        _uiState.value = _uiState.value.copy(mode = mode)
    }

    fun closeMode() {
        _uiState.value = _uiState.value.copy(mode = Mode.FORM)
    }

    fun submitName(raw: String) {
        _uiState.value = _uiState.value.copy(name = raw.trim(), mode = Mode.FORM)
    }

    fun selectPrimaryMuscle(muscle: String) {
        _uiState.value = _uiState.value.copy(primaryMuscle = muscle, mode = Mode.FORM)
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

    /** Inserts the exercise as "custom-<uuid>"; the primary muscle is dropped from secondaries. */
    fun save() {
        val state = _uiState.value
        val primaryMuscle = state.primaryMuscle
        if (!state.valid || primaryMuscle == null || saving) return
        saving = true
        val id = "custom-${UUID.randomUUID()}"
        // On the write lane, so Back can't drop it half-way.
        LiftsIo.write({
            repository.addExercise(
                ExerciseEntity(
                    id = id,
                    name = state.name.trim(),
                    equipment = state.equipment,
                    primaryMuscles = listOf(primaryMuscle),
                    secondaryMuscles = state.secondaryMuscles.filterNot { it == primaryMuscle },
                    category = state.category,
                    level = null,
                    instructions = emptyList(),
                    isCustom = true,
                ),
            )
        }) {
            // workouts.json lists every custom exercise, used or not.
            share.logChanged()
            // Atomic: a mode tap on Main in the same instant must not write createdId back to null.
            _uiState.update { it.copy(createdId = id) }
        }
    }

    /** Option lists derive from the live catalog so custom values stay dataset-consistent. */
    private fun loadOptions() {
        val all = repository.listExercises()
        val muscles = (all.flatMap { it.primaryMuscles } + all.flatMap { it.secondaryMuscles })
            .distinct()
            .sorted()
        _uiState.value = _uiState.value.copy(
            muscleOptions = muscles,
            equipmentOptions = all.mapNotNull { it.equipment }.distinct().sorted(),
            categoryOptions = all.map { it.category }.distinct().sorted()
                .ifEmpty { listOf(DEFAULT_CATEGORY) },
        )
    }

    companion object {
        const val DEFAULT_CATEGORY = "strength"
    }
}

/**
 * Custom exercise form (design/screens/custom-exercise.html): five field rows — name via
 * the SDK editor, the rest via inline picker-list modes fed from the live catalog values.
 * The top-bar checkmark appears once name + primary muscle are set; save inserts and
 * returns the new exerciseId via goBack.
 */
class CustomExerciseScreen(
    private val sealedActivity: SealedLightActivity,
) : LightScreen<String, CustomExerciseViewModel>(sealedActivity) {

    override val viewModelClass: Class<CustomExerciseViewModel>
        get() = CustomExerciseViewModel::class.java

    override fun createViewModel() = CustomExerciseViewModel(
        lightContext.liftsRepository(),
        lightContext.liftsShare(),
    )

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        val createdId = uiState.createdId
        LaunchedEffect(createdId) {
            if (createdId != null) goBack(createdId)
        }

        LiftsFrame(sealedActivity, lightContext) {
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
                    options = uiState.muscleOptions,
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
            }
        }
    }

    @Composable
    private fun FormContent(uiState: CustomExerciseViewModel.UiState) {
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
                center = LightTopBarCenter.Text("New Exercise"),
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
