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
import com.tczerwien.lifts.analytics.BACKDATE_MAX_DAYS
import com.tczerwien.lifts.analytics.bodyweightInUnit
import com.tczerwien.lifts.analytics.bodyweightStepperValue
import com.tczerwien.lifts.analytics.bodyweightTrend
import com.tczerwien.lifts.analytics.logDayLabel
import com.tczerwien.lifts.analytics.stepLogDay
import com.tczerwien.lifts.data.BodyweightEntity
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.toolmanager.LiftsShare
import com.tczerwien.lifts.toolmanager.liftsShare
import com.tczerwien.lifts.ui.ArmedAction
import com.tczerwien.lifts.ui.ArmedHint
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.MonoDigits
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
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Stepper row width: the design's 16gu left "182.4" 1px short of room between the targets, which hid the last digit. */
private const val STEPPER_WIDTH_UNITS = 19f

/** The Day stepper is wider: "Wed Sep 30" takes more room than a weight. */
private const val DAY_STEPPER_WIDTH_UNITS = 23f

/** Weeks of weekly averages asked for; the rate itself is a 28-day least-squares slope ([bodyweightTrend]). */
private const val TREND_WEEKS = 4

/** Entries shown in the Recent list. */
private const val RECENT_ENTRY_COUNT = 7

/** Stepper start value (display units) when no bodyweight was ever logged. */
private const val DEFAULT_START_LB = 150.0
private const val DEFAULT_START_KG = 70.0

class BodyweightViewModel(
    private val repository: LiftsRepository,
    private val settings: SettingsStore,
    private val share: LiftsShare,
) : LightViewModel<Unit>() {

    /** One weigh-in in the Recent list; a tap picks its day ([selectDay]). */
    data class RecentRow(val epochDay: Long, val dateText: String, val valueText: String)

    data class UiState(
        val loaded: Boolean = false,
        val unit: WeightUnit = SettingsStore.DEFAULT_UNITS,
        /** The day being logged or corrected (local epoch day): today, unless he stepped back or tapped an entry. */
        val day: Long = 0,
        val today: Long = 0,
        /** Stepper value in display units, rounded to 0.1. */
        val valueDisplay: Double = 0.0,
        /** [day] already has a weigh-in: SAVE replaces it, and the trash deletes it. */
        val dayHasEntry: Boolean = false,
        /** "+0.5 lb/week over 23 days", null without enough data ([bodyweightTrend]). */
        val rateText: String? = null,
        val recent: List<RecentRow> = emptyList(),
    ) {
        val valueText: String get() = trimDisplay(valueDisplay)
        val decrementEnabled: Boolean get() = valueDisplay - unit.bodyweightStep > 1e-9

        /** "Today", "Yesterday", "Sun Sep 27" ([logDayLabel]). */
        val dayText: String get() = logDayLabel(day, today)
        val earlierEnabled: Boolean get() = day > today - BACKDATE_MAX_DAYS
        val laterEnabled: Boolean get() = day < today
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** The trash, for a day with a weigh-in: two taps ([ArmedAction]). */
    val deleteArm = ArmedAction(viewModelScope)

    /** Every weigh-in, oldest first, as the last reload read them. */
    @Volatile
    private var entries: List<BodyweightEntity> = emptyList()

    /**
     * Reloads behind any write still queued. The phone waking (onResume shows the screen again) keeps the
     * day and the value he stepped to: only the first load, a save and a delete set them ([refresh]).
     */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        deleteArm.disarm()
        LiftsIo.write({}) { refresh() }
    }

    override fun onBackPressed(): Boolean {
        if (!deleteArm.armed.value) return false
        deleteArm.disarm()
        return true
    }

    /** Moves [delta] steps of ±0.2 lb / ±0.1 kg along the grid ([Format.stepWeight]), never below one step. */
    fun step(delta: Int) {
        deleteArm.disarm()
        _uiState.update { state ->
            val stepped = Format.stepWeight(state.valueDisplay, delta, state.unit.bodyweightStep)
            state.copy(
                valueDisplay = Format.roundBodyweight(
                    stepped.coerceAtLeast(state.unit.bodyweightStep),
                ),
            )
        }
    }

    /**
     * The day moved [delta] days ([stepLogDay]: back a year at most, never past today). A day with a
     * weigh-in shows it; one without keeps the weight the stepper holds ([bodyweightStepperValue]).
     */
    fun stepDay(delta: Int) {
        deleteArm.disarm()
        _uiState.update { state -> if (state.loaded) state.onDay(stepLogDay(state.day, delta, state.today)) else state }
    }

    /** A Recent row: its day, and its weight, to correct or delete. */
    fun selectDay(epochDay: Long) {
        deleteArm.disarm()
        _uiState.update { state -> if (state.loaded) state.onDay(epochDay.coerceAtMost(state.today)) else state }
    }

    private fun UiState.onDay(day: Long): UiState = copy(
        day = day,
        valueDisplay = bodyweightStepperValue(entries, day, unit, held = valueDisplay, fallback = defaultStart(unit)),
        dayHasEntry = entries.any { it.epochDay == day },
    )

    /**
     * LOG (or SAVE over the day's weigh-in): writes the stepper value for the day shown, one entry per
     * day, on the write lane so Back can't drop it; the exports catch up. Then back to today.
     */
    fun save() {
        val state = _uiState.value
        if (!state.loaded) return
        deleteArm.disarm()
        LiftsIo.write({
            repository.upsertBodyweight(epochDay = state.day, weightKg = Format.displayToKg(state.valueDisplay, state.unit))
        }) {
            share.logChanged()
            refresh(Reset(held = state.valueDisplay))
        }
    }

    /** The trash, on a day with a weigh-in: the first tap arms, the second deletes it; then back to today. */
    fun delete() {
        val state = _uiState.value
        if (!state.loaded || !state.dayHasEntry || !deleteArm.tap()) return
        LiftsIo.write({ repository.deleteBodyweight(state.day) }) { deleted ->
            if (deleted) share.logChanged()
            refresh(Reset(held = null))
        }
    }

    /** After a save or delete: back to today, showing its weigh-in, else [held], else the latest. */
    private data class Reset(val held: Double?)

    /**
     * Reads the weigh-ins again. The first load, and [reset] after a save or delete, put the stepper on
     * today; any other reload (the phone waking, back from elsewhere) keeps his day and the value he
     * stepped to, converted if the unit changed meanwhile, so steps not yet logged survive the screen
     * timing out. Weigh-ins are by local calendar day, so "today" is local too.
     */
    private fun refresh(reset: Reset? = null) {
        val unit = settings.snapshot().units
        val list = repository.listBodyweight() // epochDay ASC
        entries = list
        val today = LocalDate.now().toEpochDay()
        val trend = bodyweightTrend(entries = list, weeks = TREND_WEEKS, todayEpochDay = today)
        val recent = list.takeLast(RECENT_ENTRY_COUNT).reversed().map { entry ->
            RecentRow(
                epochDay = entry.epochDay,
                dateText = logDayLabel(entry.epochDay, today),
                valueText = Format.bodyweight(entry.weightKg, unit),
            )
        }
        _uiState.update { state ->
            val keep = state.loaded && reset == null
            // "Today" stays today across midnight; a day he picked stays his.
            val day = if (keep && state.day != state.today) state.day.coerceAtMost(today) else today
            val value = if (keep) {
                bodyweightInUnit(state.valueDisplay, state.unit, unit)
            } else {
                bodyweightStepperValue(list, day, unit, held = reset?.held, fallback = defaultStart(unit))
            }
            state.copy(
                loaded = true,
                unit = unit,
                day = day,
                today = today,
                valueDisplay = value,
                dayHasEntry = list.any { it.epochDay == day },
                rateText = trend?.rateKgPerWeek?.let { rate ->
                    "${signedRate(rate, unit)} ${unit.suffix}/week over ${trend.rateSpanDays ?: 0} days"
                },
                recent = recent,
            )
        }
    }

    private fun defaultStart(unit: WeightUnit): Double = when (unit) {
        WeightUnit.LB -> DEFAULT_START_LB
        WeightUnit.KG -> DEFAULT_START_KG
    }
}

/**
 * Bodyweight (design/screens/bodyweight.html): a Day stepper (today, back a year; "Yesterday",
 * "Sun Sep 27") over the weight stepper, which starts from that day's weigh-in or the latest
 * (±0.2 lb / ±0.1 kg), the trend line, and a Recent list whose rows pick their day to correct.
 * LOG writes the day's entry (SAVE when it has one, beside a two-tap trash that deletes it).
 */
class BodyweightScreen(
    private val sealedActivity: SealedLightActivity,
) : LightScreen<Unit, BodyweightViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    override val viewModelClass: Class<BodyweightViewModel>
        get() = BodyweightViewModel::class.java

    override fun createViewModel() =
        BodyweightViewModel(repository, SettingsStore(lightContext.dataStore), lightContext.liftsShare())

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()
        val deleteArmed by viewModel.deleteArm.armed.collectAsState()

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
                            Box(modifier = Modifier.width(DAY_STEPPER_WIDTH_UNITS.gridUnitsAsDp())) {
                                Stepper(
                                    onStep = viewModel::stepDay,
                                    label = "Day",
                                    decrementEnabled = uiState.earlierEnabled,
                                    incrementEnabled = uiState.laterEnabled,
                                    value = {
                                        LightText(text = uiState.dayText, variant = LightTextVariant.Copy, maxLines = 1)
                                    },
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .padding(top = 0.5f.gridUnitsAsDp())
                                    .width(STEPPER_WIDTH_UNITS.gridUnitsAsDp()),
                            ) {
                                Stepper(
                                    onStep = viewModel::step,
                                    bigSteps = true,
                                    label = "Weight",
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
                                // A tap picks the day, to correct or delete it; the day on the steppers reads full.
                                val picked = row.epochDay == uiState.day
                                LiftsListRow(
                                    onClick = { viewModel.selectDay(row.epochDay) },
                                    left = {
                                        LightText(
                                            text = row.dateText,
                                            variant = LightTextVariant.Detail,
                                            lighten = !picked,
                                            maxLines = 1,
                                        )
                                    },
                                    right = {
                                        MonoDigits(row.valueText, LightTextVariant.Copy)
                                    },
                                )
                            }
                        }
                    }
                }

                if (deleteArmed) {
                    ArmedHint(text = "TAP AGAIN TO DELETE ${uiState.dayText.uppercase()}")
                }
                LightBottomBar(
                    items = if (uiState.dayHasEntry) {
                        listOf(
                            LightBarButton.LightIcon(
                                icon = LightIcons.TRASH,
                                contentDescription = "delete weigh-in",
                                onClick = viewModel::delete,
                            ),
                            LightBarButton.Text(text = "SAVE", onClick = { viewModel.save() }),
                        )
                    } else {
                        listOf(LightBarButton.Text(text = "LOG", onClick = { viewModel.save() }))
                    },
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
