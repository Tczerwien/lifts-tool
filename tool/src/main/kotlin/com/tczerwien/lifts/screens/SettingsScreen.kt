package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.settings.PlateList
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFieldRow
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.Stepper
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
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: LiftsRepository,
    private val settingsStore: SettingsStore,
) : LightViewModel<Unit>() {

    enum class RestField { DEFAULT, WARMUP }

    sealed interface Mode {
        /** The field-row list (design/screens/settings.html). */
        data object FieldList : Mode

        /** Full-screen ±15 s stepper for one of the rest durations. */
        data class EditRest(val field: RestField) : Mode

        /** SDK text editor for the comma-separated plate list; [session] forces fresh editor state per open. */
        data class EditPlates(val prefill: String, val session: Int) : Mode
    }

    data class UiState(
        val settings: SettingsStore.Settings? = null,
        val barCount: Int = 0,
        val mode: Mode = Mode.FieldList,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var editorSession = 0

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = settingsStore.snapshot()
            val barCount = repository.listBars().size
            _uiState.update { it.copy(settings = settings, barCount = barCount) }
        }
    }

    fun toggleUnits() {
        val settings = _uiState.value.settings ?: return
        val next = when (settings.units) {
            WeightUnit.LB -> WeightUnit.KG
            WeightUnit.KG -> WeightUnit.LB
        }
        _uiState.update { it.copy(settings = settings.copy(units = next)) }
        LiftsIo.write { settingsStore.setUnits(next) }
    }

    /** The rest-over beep on or off; the flash and keep-awake stay either way. */
    fun toggleRestBeep() {
        val settings = _uiState.value.settings ?: return
        val next = !settings.restBeep
        _uiState.update { it.copy(settings = settings.copy(restBeep = next)) }
        LiftsIo.write { settingsStore.setRestBeep(next) }
    }

    fun openRestEditor(field: RestField) {
        _uiState.update { it.copy(mode = Mode.EditRest(field)) }
    }

    fun openPlateEditor() {
        val settings = _uiState.value.settings ?: return
        val prefill = PlateList.format(settings.plateInventoryKg, settings.units)
        _uiState.update { it.copy(mode = Mode.EditPlates(prefill = prefill, session = ++editorSession)) }
    }

    fun closeEditor() {
        _uiState.update { it.copy(mode = Mode.FieldList) }
    }

    /** Applies a ±1 stepper delta as ±15 s to the rest field being edited; persists per step. */
    fun stepRest(delta: Int) {
        val state = _uiState.value
        val settings = state.settings ?: return
        val mode = state.mode as? Mode.EditRest ?: return
        val current = when (mode.field) {
            RestField.DEFAULT -> settings.defaultRestSec
            RestField.WARMUP -> settings.warmupRestSec
        }
        val next = (current + delta * REST_STEP_SEC).coerceIn(MIN_REST_SEC, MAX_REST_SEC)
        if (next == current) return
        val updated = when (mode.field) {
            RestField.DEFAULT -> settings.copy(defaultRestSec = next)
            RestField.WARMUP -> settings.copy(warmupRestSec = next)
        }
        _uiState.update { it.copy(settings = updated) }
        LiftsIo.write {
            when (mode.field) {
                RestField.DEFAULT -> settingsStore.setDefaultRestSec(next)
                RestField.WARMUP -> settingsStore.setWarmupRestSec(next)
            }
        }
    }

    /**
     * Parses the submitted plate list. Submitting the prefill unchanged, or garbage with no valid
     * numbers, leaves the inventory untouched.
     */
    fun submitPlates(text: String) {
        val settings = _uiState.value.settings ?: return
        val prefill = (_uiState.value.mode as? Mode.EditPlates)?.prefill
        val parsed = if (text.trim() == prefill?.trim()) null else PlateList.parse(text, settings.units)
        if (parsed == null) {
            _uiState.update { it.copy(mode = Mode.FieldList) }
            return
        }
        _uiState.update {
            it.copy(settings = settings.copy(plateInventoryKg = parsed), mode = Mode.FieldList)
        }
        LiftsIo.write { settingsStore.setPlateInventoryKg(parsed) }
    }

    override fun onBackPressed(): Boolean {
        if (_uiState.value.mode == Mode.FieldList) return false
        closeEditor()
        return true
    }

    companion object {
        const val REST_STEP_SEC = 15
        const val MIN_REST_SEC = 15

        /** 15:00 ceiling so hold-repeat cannot run away. */
        const val MAX_REST_SEC = 900
    }
}

/**
 * Settings (design/screens/settings.html): field rows for Units (tap toggles LB/KG),
 * the two rest durations (tap opens a ±15 s stepper mode), Rest-over Beep (tap toggles
 * ON/OFF), Plate Inventory (tap opens the SDK text editor with a comma list in display
 * units), Bar Weights and Export (navigate), and a static About row.
 */
class SettingsScreen(
    private val sealedActivity: SealedLightActivity,
) : LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    private val settingsStore = SettingsStore(lightContext.dataStore)

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel() = SettingsViewModel(repository, settingsStore)

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LiftsFrame(sealedActivity, lightContext) {
            when (val mode = uiState.mode) {
                is SettingsViewModel.Mode.FieldList -> FieldList(uiState)

                is SettingsViewModel.Mode.EditRest -> RestEditor(
                    field = mode.field,
                    settings = uiState.settings,
                )

                is SettingsViewModel.Mode.EditPlates -> {
                    val textState = remember(mode.session) { TextFieldState(mode.prefill) }
                    LightTextInputEditor(
                        title = "Plate Inventory",
                        state = textState,
                        onSubmit = { viewModel.submitPlates(it.toString()) },
                        onBack = { viewModel.closeEditor() },
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        singleLine = true,
                        editorKey = "lifts-plates-${mode.session}",
                        modifier = Modifier.background(LightThemeTokens.colors.background),
                    )
                }
            }
        }
    }

    @Composable
    private fun FieldList(uiState: SettingsViewModel.UiState) {
        val settings = uiState.settings
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
                center = LightTopBarCenter.Text("Settings"),
            )

            if (settings == null) return@Column
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LiftsFieldRow(
                    label = "Units",
                    value = settings.units.name,
                    onClick = { viewModel.toggleUnits() },
                    chevron = true,
                )
                LiftsFieldRow(
                    label = "Default Rest",
                    value = Format.clock(settings.defaultRestSec),
                    onClick = { viewModel.openRestEditor(SettingsViewModel.RestField.DEFAULT) },
                    chevron = true,
                )
                LiftsFieldRow(
                    label = "Warm-up Rest",
                    value = Format.clock(settings.warmupRestSec),
                    onClick = { viewModel.openRestEditor(SettingsViewModel.RestField.WARMUP) },
                    chevron = true,
                )
                LiftsFieldRow(
                    label = "Rest-over Beep",
                    value = if (settings.restBeep) "ON" else "OFF",
                    onClick = { viewModel.toggleRestBeep() },
                    chevron = true,
                )
                LiftsFieldRow(
                    label = "Plate Inventory",
                    value = plateSummary(settings),
                    onClick = { viewModel.openPlateEditor() },
                    chevron = true,
                )
                LiftsFieldRow(
                    label = "Bar Weights",
                    value = "${uiState.barCount} named",
                    onClick = { navigateTo(::BarWeightsScreen) },
                    chevron = true,
                )
                LiftsFieldRow(
                    label = "Plate Calculator",
                    value = "per-side loadout",
                    onClick = { navigateTo(::PlateCalculatorScreen) },
                    chevron = true,
                )
                LiftsFieldRow(
                    label = "Export",
                    value = "3 formats",
                    onClick = { navigateTo(::ExportScreen) },
                    chevron = true,
                )
                LiftsFieldRow(
                    label = "About",
                    value = ABOUT_VALUE,
                )
            }
        }
    }

    @Composable
    private fun RestEditor(field: SettingsViewModel.RestField, settings: SettingsStore.Settings?) {
        val sec = when (field) {
            SettingsViewModel.RestField.DEFAULT -> settings?.defaultRestSec
            SettingsViewModel.RestField.WARMUP -> settings?.warmupRestSec
        } ?: return
        val title = when (field) {
            SettingsViewModel.RestField.DEFAULT -> "Default Rest"
            SettingsViewModel.RestField.WARMUP -> "Warm-up Rest"
        }

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
                center = LightTopBarCenter.Text(title),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Stepper(
                    onStep = viewModel::stepRest,
                    decrementEnabled = sec > SettingsViewModel.MIN_REST_SEC,
                    incrementEnabled = sec < SettingsViewModel.MAX_REST_SEC,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4f.gridUnitsAsDp()),
                    value = {
                        LightText(
                            text = Format.clock(sec),
                            variant = LightTextVariant.Subtitle,
                            monospace = true,
                            maxLines = 1,
                        )
                    },
                )
            }
        }
    }

    private fun plateSummary(settings: SettingsStore.Settings): String {
        if (settings.plateInventoryKg.isEmpty()) return "None"
        val values = settings.plateInventoryKg
            .joinToString(" / ") { Format.plate(it, settings.units) }
        return "$values ${settings.units.suffix}"
    }

    companion object {
        /** Tool label + versionName from tool/lighttool.toml (no generated config to read them from). */
        private const val ABOUT_VALUE = "Lifts 0.1"
    }
}
