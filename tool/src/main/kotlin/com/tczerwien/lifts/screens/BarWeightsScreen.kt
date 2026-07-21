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
import com.tczerwien.lifts.data.LiftsDatabase
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.Stepper
import com.tczerwien.lifts.ui.WeightUnit
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
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
) : LightViewModel<Unit>() {

    sealed interface Mode {
        /** The named-implement list (design/screens/bar-weights.html). */
        data object BarList : Mode

        /** SDK text editor for the name; [barId] null = adding. [session] forces fresh editor state per open. */
        data class EditName(val barId: Long?, val prefill: String, val session: Int) : Mode

        /** Weight stepper; ACCEPT commits, back cancels, TRASH (existing bars) deletes. */
        data class EditWeight(val barId: Long?, val name: String, val displayWeight: Double) : Mode
    }

    data class UiState(
        val units: WeightUnit = SettingsStore.DEFAULT_UNITS,
        val bars: List<BarEntity> = emptyList(),
        val mode: Mode = Mode.BarList,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var editorSession = 0

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch(Dispatchers.IO) {
            val units = settingsStore.snapshot().units
            val bars = repository.listBars()
            _uiState.update { it.copy(units = units, bars = bars) }
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
            ?.let { Format.roundWeight(Format.kgToDisplay(it.weightKg, state.units)) }
            ?: defaultNewBarDisplay(state.units)
        _uiState.update {
            it.copy(mode = Mode.EditWeight(barId = mode.barId, name = trimmed, displayWeight = displayWeight))
        }
    }

    /** Applies a ±1 stepper delta as ±[WeightUnit.weightStep] display units, floored at 0. */
    fun stepWeight(delta: Int) {
        val state = _uiState.value
        val mode = state.mode as? Mode.EditWeight ?: return
        val next = (mode.displayWeight + delta * state.units.weightStep).coerceAtLeast(0.0)
        _uiState.update { it.copy(mode = mode.copy(displayWeight = next)) }
    }

    fun save() {
        val state = _uiState.value
        val mode = state.mode as? Mode.EditWeight ?: return
        // Close the editor synchronously so a double-tap on ACCEPT cannot insert twice.
        _uiState.update { it.copy(mode = Mode.BarList) }
        viewModelScope.launch(Dispatchers.IO) {
            val weightKg = Format.displayToKg(mode.displayWeight, state.units)
            val existing = state.bars.firstOrNull { it.id == mode.barId }
            if (existing != null) {
                repository.updateBar(existing.copy(name = mode.name, weightKg = weightKg))
            } else {
                val position = (state.bars.maxOfOrNull { it.position } ?: -1) + 1
                repository.addBar(BarEntity(name = mode.name, weightKg = weightKg, position = position))
            }
            val bars = repository.listBars()
            _uiState.update { it.copy(bars = bars) }
        }
    }

    fun delete() {
        val state = _uiState.value
        val barId = (state.mode as? Mode.EditWeight)?.barId ?: return
        _uiState.update { it.copy(mode = Mode.BarList) }
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteBar(barId)
            val bars = repository.listBars()
            _uiState.update { it.copy(bars = bars) }
        }
    }

    fun closeEditor() {
        _uiState.update { it.copy(mode = Mode.BarList) }
    }

    override fun onBackPressed(): Boolean {
        if (_uiState.value.mode == Mode.BarList) return false
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
 * to re-edit. Editing = SDK name editor, then a weight stepper committed with ACCEPT;
 * delete lives in the stepper's bottom bar (no long-press exists on the platform).
 */
class BarWeightsScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, BarWeightsViewModel>(sealedActivity) {

    private val repository = LiftsRepository.getInstance {
        lightContext.buildDatabase(LiftsDatabase::class.java, LiftsRepository.DATABASE_NAME)
    }

    private val settingsStore = SettingsStore(lightContext.dataStore)

    override val viewModelClass: Class<BarWeightsViewModel>
        get() = BarWeightsViewModel::class.java

    override fun createViewModel() = BarWeightsViewModel(repository, settingsStore)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val uiState by viewModel.uiState.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
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
                        submitLabel = "NEXT",
                        editorKey = "lifts-bar-name-${mode.session}",
                        modifier = Modifier.background(LightThemeTokens.colors.background),
                    )
                }

                is BarWeightsViewModel.Mode.EditWeight -> WeightEditor(mode, uiState.units)
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
                            LightText(
                                text = bar.name,
                                variant = LightTextVariant.Copy,
                                maxLines = 1,
                            )
                        },
                        right = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LightText(
                                    text = Format.weight(bar.weightKg, uiState.units),
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
