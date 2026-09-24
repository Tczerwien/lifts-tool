package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.analytics.bodyweightTrend
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.SectionLabel
import com.tczerwien.lifts.ui.Stepper
import com.tczerwien.lifts.ui.WeightUnit
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Stepper row width (design/screens/bodyweight.html .stepper-row). */
private const val STEPPER_WIDTH_UNITS = 16f

/** Trend copy window; the analytics rate itself is a fixed 28-day least-squares slope. */
private const val TREND_WEEKS = 4

/** Entries shown in the Recent list. */
private const val RECENT_ENTRY_COUNT = 7

/** Stepper start value (display units) when no bodyweight was ever logged. */
private const val DEFAULT_START_LB = 150.0
private const val DEFAULT_START_KG = 70.0

class BodyweightViewModel(
    private val repository: LiftsRepository,
    private val settings: SettingsStore,
) : LightViewModel<Unit>() {

    data class RecentRow(val dateText: String, val valueText: String)

    data class UiState(
        val loaded: Boolean = false,
        val unit: WeightUnit = SettingsStore.DEFAULT_UNITS,
        /** Stepper value in display units, rounded to 0.1. */
        val valueDisplay: Double = 0.0,
        /** "+0.5 lb/week over last 4 weeks", null without enough data. */
        val rateText: String? = null,
        val recent: List<RecentRow> = emptyList(),
    ) {
        val valueText: String get() = trimDisplay(valueDisplay)
        val decrementEnabled: Boolean get() = valueDisplay - unit.bodyweightStep > 1e-9
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch(Dispatchers.IO) { refresh() }
    }

    /** [delta] is ±1 from the Stepper; the bodyweight increment is ±0.2 lb / ±0.1 kg. */
    fun step(delta: Int) {
        _uiState.update { state ->
            val stepped = state.valueDisplay + delta * state.unit.bodyweightStep
            state.copy(
                valueDisplay = Format.roundBodyweight(
                    stepped.coerceAtLeast(state.unit.bodyweightStep),
                ),
            )
        }
    }

    /** Upserts today's entry (one per epoch day, local calendar) with the stepper value. */
    fun logToday() {
        val state = _uiState.value
        if (!state.loaded) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.upsertBodyweight(
                epochDay = LocalDate.now().toEpochDay(),
                weightKg = Format.displayToKg(state.valueDisplay, state.unit),
            )
            refresh()
        }
    }

    private fun refresh() {
        val unit = settings.snapshot().units
        val entries = repository.listBodyweight() // epochDay ASC
        // Entries are logged by LOCAL calendar day, so "today" is local here too.
        val trend = bodyweightTrend(
            entries = entries,
            weeks = TREND_WEEKS,
            todayEpochDay = LocalDate.now().toEpochDay(),
        )

        val startDisplay = trend?.currentKg
            ?.let { Format.roundBodyweight(Format.kgToDisplay(it, unit)) }
            ?: when (unit) {
                WeightUnit.LB -> DEFAULT_START_LB
                WeightUnit.KG -> DEFAULT_START_KG
            }

        val recent = entries.takeLast(RECENT_ENTRY_COUNT).reversed().map { entry ->
            RecentRow(
                dateText = Format.dateShort(
                    LocalDate.ofEpochDay(entry.epochDay)
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli(),
                ),
                valueText = Format.bodyweight(entry.weightKg, unit),
            )
        }

        _uiState.value = UiState(
            loaded = true,
            unit = unit,
            valueDisplay = startDisplay,
            rateText = trend?.rateKgPerWeek?.let { rate ->
                "${signedRate(rate, unit)} ${unit.suffix}/week over last $TREND_WEEKS weeks"
            },
            recent = recent,
        )
    }
}

/**
 * Bodyweight (design/screens/bodyweight.html): a Today stepper starting from the latest
 * entry (±0.2 lb / ±0.1 kg), the 4-week trend line, a Recent list, and a LOG bottom-bar
 * action that upserts today's entry.
 */
class BodyweightScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, BodyweightViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    override val viewModelClass: Class<BodyweightViewModel>
        get() = BodyweightViewModel::class.java

    override fun createViewModel() =
        BodyweightViewModel(repository, SettingsStore(lightContext.dataStore))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val uiState by viewModel.uiState.collectAsState()

        LightTheme(colors = themeColors) {
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
                    center = LightTopBarCenter.Text("Bodyweight"),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (uiState.loaded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 0.25f.gridUnitsAsDp()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(modifier = Modifier.width(STEPPER_WIDTH_UNITS.gridUnitsAsDp())) {
                                Stepper(
                                    onStep = viewModel::step,
                                    label = "Today",
                                    decrementEnabled = uiState.decrementEnabled,
                                    value = {
                                        LightText(
                                            text = uiState.valueText,
                                            variant = LightTextVariant.Subtitle,
                                            monospace = true,
                                            maxLines = 1,
                                        )
                                    },
                                )
                            }
                            LightText(
                                text = uiState.unit.suffix,
                                variant = LightTextVariant.Detail,
                                lighten = true,
                            )
                            uiState.rateText?.let { rateText ->
                                LightText(
                                    text = rateText,
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                )
                            }
                        }

                        if (uiState.recent.isNotEmpty()) {
                            SectionLabel("Recent")
                            uiState.recent.forEachIndexed { index, row ->
                                if (index > 0) RowDivider()
                                LiftsListRow(
                                    left = {
                                        LightText(
                                            text = row.dateText,
                                            variant = LightTextVariant.Detail,
                                            lighten = true,
                                            monospace = true,
                                            maxLines = 1,
                                        )
                                    },
                                    right = {
                                        LightText(
                                            text = row.valueText,
                                            variant = LightTextVariant.Copy,
                                            monospace = true,
                                            maxLines = 1,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(
                            text = "LOG",
                            onClick = { viewModel.logToday() },
                        ),
                    ),
                )
            }
        }
    }
}

/** Display-rounded value without a trailing ".0" ("182.4", "183"). */
private fun trimDisplay(value: Double): String =
    if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        "%.1f".format(Locale.US, value)
    }

/** Signed weekly rate in display units with Format's 0.1 rounding: "+0.5", "-0.3", "+0". */
private fun signedRate(kgPerWeek: Double, unit: WeightUnit): String {
    val rounded = Format.roundBodyweight(Format.kgToDisplay(kgPerWeek, unit))
    val text = trimDisplay(abs(rounded))
    return if (rounded < 0) "-$text" else "+$text"
}
