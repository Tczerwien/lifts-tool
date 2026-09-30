package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.data.BarEntity
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.session.TypedWeight
import com.tczerwien.lifts.session.parseTypedWeight
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.toolmanager.LiftsShare
import com.tczerwien.lifts.toolmanager.liftsShare
import com.tczerwien.lifts.ui.ArmedAction
import com.tczerwien.lifts.ui.ArmedHint
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.RowName
import com.tczerwien.lifts.ui.Stepper
import com.tczerwien.lifts.ui.WeightUnit
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
import kotlinx.coroutines.launch

class BarWeightsViewModel(
    private val repository: LiftsRepository,
    private val settingsStore: SettingsStore,
    private val share: LiftsShare,
) : LightViewModel<Unit>() {

    sealed interface Mode {
        /** The named-implement list (design/screens/bar-weights.html). */
        data object BarList : Mode

        /** SDK text editor for the name; [barId] null = adding. [session] forces fresh editor state per open. */
        data class EditName(val barId: Long?, val prefill: String, val session: Int) : Mode

        /**
         * Weight stepper; ACCEPT commits, back cancels, TRASH (existing bars) deletes. [stepped]
         * records whether the stepper moved; an untouched edit keeps the stored kg exactly.
         */
        data class EditWeight(
            val barId: Long?,
            val name: String,
            val displayWeight: Double,
            val stepped: Boolean = false,
        ) : Mode

        /**
         * The keyboard for [editing]'s weight, opened by a tap on it: for a sled or machine that is
         * no whole number of steps (118 lb). [refused] after text that isn't a weight.
         */
        data class TypeWeight(val editing: EditWeight, val prefill: String, val session: Int, val refused: Boolean = false) : Mode
    }

    data class UiState(
        val units: WeightUnit = SettingsStore.DEFAULT_UNITS,
        /** Settings' weight step in [units]. */
        val weightStep: Double = SettingsStore.DEFAULT_UNITS.defaultWeightStep,
        val bars: List<BarEntity> = emptyList(),
        val mode: Mode = Mode.BarList,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var editorSession = 0

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = settingsStore.snapshot()
            val bars = repository.listBars()
            _uiState.update { it.copy(units = settings.units, weightStep = settings.weightStep, bars = bars) }
        }
    }

    fun startAdd() {
        _uiState.update {
            it.copy(mode = Mode.EditName(barId = null, prefill = "", session = ++editorSession))
        }
    }

    fun startEdit(bar: BarEntity) {
        _uiState.update {
            it.copy(mode = Mode.EditName(barId = bar.id, prefill = bar.name, session = ++editorSession))
        }
    }

    /** Advances name entry to the weight stepper; blank names keep the editor open. */
    fun submitName(name: CharSequence) {
        val trimmed = name.toString().trim()
        if (trimmed.isEmpty()) return
        val state = _uiState.value
        val mode = state.mode as? Mode.EditName ?: return
        val existing = state.bars.firstOrNull { it.id == mode.barId }
        val displayWeight = existing
            ?.let { Format.roundWeight(Format.kgToDisplay(it.weightKg, state.units), state.units) }
            ?: defaultNewBarDisplay(state.units)
        _uiState.update {
            it.copy(mode = Mode.EditWeight(barId = mode.barId, name = trimmed, displayWeight = displayWeight))
        }
    }

    /** Moves the weight [delta] of Settings' weight steps along the grid ([Format.stepWeight]). */
    fun stepWeight(delta: Int) {
        deleteArm.disarm()
        val state = _uiState.value
        val mode = state.mode as? Mode.EditWeight ?: return
        val next = Format.stepWeight(mode.displayWeight, delta, state.weightStep)
        _uiState.update { it.copy(mode = mode.copy(displayWeight = next, stepped = true)) }
    }

    /** A tap on the weight: the keyboard, opening on the weight as it shows. */
    fun openWeightEntry() {
        deleteArm.disarm()
        val state = _uiState.value
        val mode = state.mode as? Mode.EditWeight ?: return
        val prefill = Format.weight(Format.displayToKg(mode.displayWeight, state.units), state.units)
        _uiState.update { it.copy(mode = Mode.TypeWeight(mode, prefill, ++editorSession)) }
    }

    /**
     * The keyboard's SUBMIT: back to the stepper with the typed weight ([parseTypedWeight]), or as it
     * was for blank; text that isn't a weight keeps the keyboard open, saying so. ACCEPT still saves.
     */
    fun submitWeightEntry(text: String) {
        val state = _uiState.value
        val mode = state.mode as? Mode.TypeWeight ?: return
        val next = when (val typed = parseTypedWeight(text, state.units)) {
            null -> mode.copy(prefill = text, session = ++editorSession, refused = true)
            TypedWeight.Clear -> mode.editing
            is TypedWeight.Value -> mode.editing.copy(displayWeight = typed.display, stepped = true)
        }
        _uiState.update { it.copy(mode = next) }
    }

    fun save() {
        deleteArm.disarm()
        val state = _uiState.value
        val mode = state.mode as? Mode.EditWeight ?: return
        // Close the editor synchronously so a double-tap on ACCEPT cannot insert twice.
        _uiState.update { it.copy(mode = Mode.BarList) }
        LiftsIo.write {
            val existing = state.bars.firstOrNull { it.id == mode.barId }
            // A rename alone must not re-round the weight (45 lb would drift through 0.5-unit rounding).
            val weightKg = if (existing != null && !mode.stepped) {
                existing.weightKg
            } else {
                Format.displayToKg(mode.displayWeight, state.units)
            }
            if (existing != null) {
                repository.updateBar(existing.copy(name = mode.name, weightKg = weightKg))
            } else {
                val position = (state.bars.maxOfOrNull { it.position } ?: -1) + 1
                repository.addBar(BarEntity(name = mode.name, weightKg = weightKg, position = position))
            }
            // workouts.json lists the bars.
            share.logChanged()
            val bars = repository.listBars()
            _uiState.update { it.copy(bars = bars) }
        }
    }

    /** The weight editor's trash: two taps, the second 0.4 to 3 s after the first ([ArmedAction]). */
    val deleteArm = ArmedAction(viewModelScope)

    /** First tap arms ("tap again to delete"); the second deletes the bar and goes back to the list. */
    fun delete() {
        val state = _uiState.value
        val barId = (state.mode as? Mode.EditWeight)?.barId ?: return
        if (!deleteArm.tap()) return
        _uiState.update { it.copy(mode = Mode.BarList) }
        LiftsIo.write {
            repository.deleteBar(barId)
            share.logChanged()
            val bars = repository.listBars()
            _uiState.update { it.copy(bars = bars) }
        }
    }

    /** Back from an editor: the keyboard goes back to the stepper, anything else to the list. */
    fun closeEditor() {
        deleteArm.disarm()
        _uiState.update { state ->
            val mode = state.mode
            state.copy(mode = if (mode is Mode.TypeWeight) mode.editing else Mode.BarList)
        }
    }

    override fun onBackPressed(): Boolean {
        if (_uiState.value.mode == Mode.BarList) return false
        if (deleteArm.armed.value) {
            deleteArm.disarm()
            return true
        }
        closeEditor()
        return true
    }

    companion object {
        /** Sensible stepper starting point for a new implement: the standard bar. */
        fun defaultNewBarDisplay(units: WeightUnit): Double = when (units) {
            WeightUnit.LB -> 45.0
            WeightUnit.KG -> 20.0
        }
    }
}

/**
 * Bar Weights (design/screens/bar-weights.html): named implements (bars, machines,
 * sleds) for the plate calculator. Add via top-bar plus or the trailing row; tap a row
 * to re-edit. Editing = SDK name editor, then a weight stepper (a tap on its number types one in) committed with ACCEPT;
 * delete lives in the stepper's bottom bar (no long-press exists on the platform).
 */
class BarWeightsScreen(
    private val sealedActivity: SealedLightActivity,
) : LightScreen<Unit, BarWeightsViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    private val settingsStore = SettingsStore(lightContext.dataStore)

    override val viewModelClass: Class<BarWeightsViewModel>
        get() = BarWeightsViewModel::class.java

    override fun createViewModel() = BarWeightsViewModel(repository, settingsStore, lightContext.liftsShare())

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LiftsFrame(sealedActivity, lightContext) {
            when (val mode = uiState.mode) {
                is BarWeightsViewModel.Mode.BarList -> BarList(uiState)

                is BarWeightsViewModel.Mode.EditName -> {
                    val textState = remember(mode.session) { TextFieldState(mode.prefill) }
                    LightTextInputEditor(
                        title = "Bar Name",
                        state = textState,
                        onSubmit = { viewModel.submitName(it) },
                        onBack = { viewModel.closeEditor() },
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        singleLine = true,
                        // A capital to start a new name; none mid-way through one being edited.
                        initialCaps = mode.prefill.isBlank(),
                        submitLabel = "NEXT",
                        editorKey = "lifts-bar-name-${mode.session}",
                        modifier = Modifier.background(LightThemeTokens.colors.background),
                    )
                }

                is BarWeightsViewModel.Mode.EditWeight -> WeightEditor(mode, uiState.units)

                is BarWeightsViewModel.Mode.TypeWeight -> {
                    val textState = remember(mode.session) { TextFieldState(mode.prefill) }
                    LightTextInputEditor(
                        title = if (mode.refused) "Not a weight (${uiState.units.suffix})" else "Weight (${uiState.units.suffix})",
                        state = textState,
                        onSubmit = { viewModel.submitWeightEntry(it.toString()) },
                        onBack = { viewModel.closeEditor() },
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        singleLine = true,
                        editorKey = "lifts-bar-weight-${mode.session}",
                        modifier = Modifier.background(LightThemeTokens.colors.background),
                    )
                }
            }
        }
    }

    @Composable
    private fun BarList(uiState: BarWeightsViewModel.UiState) {
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
                center = LightTopBarCenter.Text("Bar Weights"),
                rightButton = LightBarButton.LightIcon(
                    icon = LightIcons.ADD,
                    onClick = { viewModel.startAdd() },
                ),
            )

            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                uiState.bars.forEach { bar ->
                    LiftsListRow(
                        onClick = { viewModel.startEdit(bar) },
                        left = {
                            RowName(bar.name)
                        },
                        right = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LightText(
                                    text = Format.plate(bar.weightKg, uiState.units),
                                    variant = LightTextVariant.Copy,
                                    monospace = true,
                                    maxLines = 1,
                                )
                                Spacer(modifier = Modifier.width(0.3f.gridUnitsAsDp()))
                                LightText(
                                    text = uiState.units.suffix,
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    maxLines = 1,
                                )
                            }
                        },
                    )
                    RowDivider()
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3f.gridUnitsAsDp())
                        .lightClickable { viewModel.startAdd() },
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = "+ ADD BAR",
                        variant = LightTextVariant.Button,
                        lighten = true,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    @Composable
    private fun WeightEditor(mode: BarWeightsViewModel.Mode.EditWeight, units: WeightUnit) {
        val deleteArmed by viewModel.deleteArm.armed.collectAsState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = { viewModel.closeEditor() },
                ),
                center = LightTopBarCenter.Text(mode.name),
                rightButton = LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    contentDescription = "save bar",
                    onClick = { viewModel.save() },
                ),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Stepper(
                    onStep = viewModel::stepWeight,
                    label = "Weight (${units.suffix})",
                    decrementEnabled = mode.displayWeight > 0.0,
                    // A tap on the number types one in.
                    onValueClick = viewModel::openWeightEntry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4f.gridUnitsAsDp()),
                    value = {
                        LightText(
                            text = Format.weight(Format.displayToKg(mode.displayWeight, units), units),
                            variant = LightTextVariant.Subtitle,
                            monospace = true,
                            maxLines = 1,
                        )
                    },
                )
            }
            if (mode.barId != null) {
                if (deleteArmed) ArmedHint(text = "TAP AGAIN TO DELETE")
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.TRASH,
                            contentDescription = "delete bar",
                            onClick = { viewModel.delete() },
                        ),
                    ),
                )
            }
        }
    }
}
