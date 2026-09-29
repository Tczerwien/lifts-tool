package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.data.BarEntity
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.domain.PlateMath
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.SectionLabel
import com.tczerwien.lifts.ui.Stepper
import com.tczerwien.lifts.ui.WeightUnit
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PlateCalculatorViewModel(
    private val repository: LiftsRepository,
    private val settingsStore: SettingsStore,
    private val initialTargetKg: Double?,
    private val initialBarKg: Double?,
) : LightViewModel<Unit>() {

    data class UiState(
        val units: WeightUnit = SettingsStore.DEFAULT_UNITS,
        /** Settings' weight step in [units]: the target moves by it. */
        val weightStep: Double = SettingsStore.DEFAULT_UNITS.defaultWeightStep,
        val bars: List<BarEntity> = emptyList(),
        val selectedBarIndex: Int = 0,
        /** Stepper value in display units, kept on the unit's rounding grid ([Format.roundWeight]). */
        val targetDisplay: Double = 0.0,
        val plateInventoryKg: List<Double> = emptyList(),
        val loadout: PlateMath.Loadout? = null,
        val loaded: Boolean = false,
    ) {
        val selectedBar: BarEntity? get() = bars.getOrNull(selectedBarIndex)
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch(Dispatchers.IO) { refresh() }
    }

    private fun refresh() {
        val settings = settingsStore.snapshot()
        val bars = repository.listBars()
        _uiState.update { state ->
            val firstLoad = !state.loaded
            val selectedBarIndex = if (firstLoad && initialBarKg != null) {
                bars.indexOfFirst { abs(it.weightKg - initialBarKg) < BAR_MATCH_EPSILON_KG }
                    .takeIf { it >= 0 } ?: 0
            } else {
                state.selectedBarIndex.coerceIn(0, (bars.size - 1).coerceAtLeast(0))
            }
            val barKg = bars.getOrNull(selectedBarIndex)?.weightKg ?: initialBarKg ?: 0.0
            val targetDisplay = when {
                firstLoad -> initialTargetDisplay(settings, barKg)
                state.units != settings.units -> Format.roundWeight(
                    Format.kgToDisplay(Format.displayToKg(state.targetDisplay, state.units), settings.units),
                    settings.units,
                )
                else -> state.targetDisplay
            }
            derive(
                state.copy(
                    units = settings.units,
                    weightStep = settings.weightStep,
                    bars = bars,
                    selectedBarIndex = selectedBarIndex,
                    targetDisplay = targetDisplay,
                    plateInventoryKg = settings.plateInventoryKg,
                    loaded = true,
                ),
            )
        }
    }

    /** Prefilled target, else bar + one largest plate per side — a sensible first load. */
    private fun initialTargetDisplay(settings: SettingsStore.Settings, barKg: Double): Double {
        val unit = settings.units
        initialTargetKg?.let { return Format.roundWeight(Format.kgToDisplay(it, unit), unit) }
        val barDisplay = Format.roundWeight(Format.kgToDisplay(barKg, unit), unit)
        val largestPlateKg = settings.plateInventoryKg.maxOrNull() ?: return barDisplay
        return barDisplay + 2 * Format.roundWeight(Format.kgToDisplay(largestPlateKg, unit), unit)
    }

    /** Moves the target [delta] of Settings' weight steps along the grid ([Format.stepWeight]). */
    fun stepTarget(delta: Int) {
        _uiState.update { state ->
            val next = Format.stepWeight(state.targetDisplay, delta, state.weightStep)
            derive(state.copy(targetDisplay = next))
        }
    }

    /** Cycles the named-bar selector by [direction] (±1), wrapping around. */
    fun cycleBar(direction: Int) {
        _uiState.update { state ->
            if (state.bars.isEmpty()) {
                state
            } else {
                val next = (state.selectedBarIndex + direction + state.bars.size) % state.bars.size
                derive(state.copy(selectedBarIndex = next))
            }
        }
    }

    private fun derive(state: UiState): UiState {
        val barKg = state.selectedBar?.weightKg ?: initialBarKg ?: 0.0
        val loadout = PlateMath.loadout(
            targetKg = Format.displayToKg(state.targetDisplay, state.units),
            barKg = barKg,
            inventoryKg = state.plateInventoryKg,
        )
        return state.copy(loadout = loadout)
    }

    companion object {
        /** A stored bar matching the prefilled bar weight this closely (1 g) is "the" bar. */
        const val BAR_MATCH_EPSILON_KG = 1e-3
    }
}

/**
 * Plate Calculator (design/screens/plate-calculator.html): target stepper in display
 * units, chevron-cycled named bar, proportional per-side plate diagram on a bar line,
 * and the per-side breakdown from [PlateMath]. Bars and plates come from Settings.
 */
class PlateCalculatorScreen(
    private val sealedActivity: SealedLightActivity,
    /** Optional prefill from the active workout; canonical kg. */
    private val targetWeightKg: Double? = null,
    private val barWeightKg: Double? = null,
) : LightScreen<Unit, PlateCalculatorViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    private val settingsStore = SettingsStore(lightContext.dataStore)

    override val viewModelClass: Class<PlateCalculatorViewModel>
        get() = PlateCalculatorViewModel::class.java

    override fun createViewModel() =
        PlateCalculatorViewModel(repository, settingsStore, targetWeightKg, barWeightKg)

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()

        LiftsFrame(sealedActivity, lightContext) {
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
                    center = LightTopBarCenter.Text("Plates"),
                )

                LightText(
                    text = "TARGET",
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 0.75f.gridUnitsAsDp()),
                )
                Stepper(
                    onStep = viewModel::stepTarget,
                    bigSteps = true,
                    decrementEnabled = uiState.targetDisplay > 0.0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 4f.gridUnitsAsDp(),
                            end = 4f.gridUnitsAsDp(),
                            top = 0.25f.gridUnitsAsDp(),
                        ),
                    value = {
                        LightText(
                            text = Format.weight(
                                Format.displayToKg(uiState.targetDisplay, uiState.units),
                                uiState.units,
                            ),
                            variant = LightTextVariant.Subtitle,
                            monospace = true,
                            maxLines = 1,
                        )
                    },
                )

                BarSelector(uiState)
                PlateDiagram(uiState.loadout)

                SectionLabel("Per Side")
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    PerSideLine(uiState)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 0.5f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = noteText(uiState),
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.BarSelector(uiState: PlateCalculatorViewModel.UiState) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 0.25f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.75f.gridUnitsAsDp()),
        ) {
            val bar = uiState.selectedBar
            if (bar == null) {
                LightText(
                    text = "No bars — add one in Settings",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 1,
                )
            } else {
                BarChevron(icon = LightIcons.BACK, description = "previous bar") {
                    viewModel.cycleBar(-1)
                }
                Row(modifier = Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
                    // A long bar name is cut, not the weight after it or the chevrons.
                    LightText(
                        text = "${bar.name} · ",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    LightText(
                        text = Format.plate(bar.weightKg, uiState.units),
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        monospace = true,
                        maxLines = 1,
                    )
                    LightText(
                        text = " ${uiState.units.suffix}",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        maxLines = 1,
                    )
                }
                BarChevron(icon = LightIcons.ARROW_RIGHT, description = "next bar") {
                    viewModel.cycleBar(+1)
                }
            }
        }
    }

    @Composable
    private fun BarChevron(
        icon: LightIconConfiguration,
        description: String,
        onClick: () -> Unit,
    ) {
        Box(
            modifier = Modifier
                .size(2f.gridUnitsAsDp())
                .lightClickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            LightIcon(icon = icon, size = 0.9f, contentDescription = description)
        }
    }

    /** Proportional per-side plates standing on a contentSecondary bar line (mock .bar-diagram). */
    @Composable
    private fun ColumnScope.PlateDiagram(loadout: PlateMath.Loadout?) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(DIAGRAM_WIDTH_UNITS.gridUnitsAsDp())
                .padding(top = 1.25f.gridUnitsAsDp()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DIAGRAM_PLATES_HEIGHT_UNITS.gridUnitsAsDp())
                    .padding(start = 1.5f.gridUnitsAsDp()),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(0.15f.gridUnitsAsDp()),
            ) {
                val platesKg = loadout?.perSide.orEmpty()
                    .flatMap { plateCount -> List(plateCount.count) { plateCount.plateKg } }
                platesKg.forEach { plateKg ->
                    val (widthUnits, heightUnits) = plateTier(plateKg)
                    Box(
                        modifier = Modifier
                            .width(widthUnits.gridUnitsAsDp())
                            .height(heightUnits.gridUnitsAsDp())
                            .background(LightThemeTokens.colors.content),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.4f.gridUnitsAsDp())
                    .background(LightThemeTokens.colors.contentSecondary),
            )
        }
    }

    @Composable
    private fun PerSideLine(uiState: PlateCalculatorViewModel.UiState) {
        if (!uiState.loaded) return
        val loadout = uiState.loadout
        when {
            loadout == null -> LightText(
                text = "Below bar weight",
                variant = LightTextVariant.Copy,
                lighten = true,
                maxLines = 1,
            )

            loadout.perSide.isEmpty() -> LightText(
                text = "Empty bar",
                variant = LightTextVariant.Copy,
                lighten = true,
                maxLines = 1,
            )

            // Up to two lines: dropping a plate off the end would have you load the wrong weight.
            else -> LightText(
                text = PlateMath.perSideLabel(loadout.perSide) { Format.plate(it, uiState.units) },
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    private fun noteText(uiState: PlateCalculatorViewModel.UiState): String {
        val loadout = uiState.loadout
        return if (loadout != null && !loadout.exact) {
            "no exact load — nearest ${Format.plate(loadout.achievedKg, uiState.units)} ${uiState.units.suffix}"
        } else {
            "bars & plates from Settings"
        }
    }

    companion object {
        private const val DIAGRAM_WIDTH_UNITS = 14f
        private const val DIAGRAM_PLATES_HEIGHT_UNITS = 4f

        /**
         * Plate rect size (width, height) in grid units by plate weight, generalizing the
         * mock's 45/25/10 lb classes; thresholds sit between the common lb and kg sizes.
         */
        private fun plateTier(plateKg: Double): Pair<Float, Float> = when {
            plateKg >= 15.9 -> 0.8f to 4.0f // 45 lb / 20+ kg
            plateKg >= 9.0 -> 0.7f to 3.0f // 25 lb / 10-15 kg
            plateKg >= 3.4 -> 0.6f to 2.2f // 10 lb / 5 kg
            plateKg >= 1.7 -> 0.5f to 1.6f // 5 lb / 2.5 kg
            else -> 0.4f to 1.2f // 2.5 lb / 1.25 kg
        }
    }
}
