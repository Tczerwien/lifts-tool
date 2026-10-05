package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.Perf
import com.tczerwien.lifts.analytics.AVERAGE_WEEKS
import com.tczerwien.lifts.analytics.CONSISTENCY_CHART_WEEKS
import com.tczerwien.lifts.analytics.STREAK_THRESHOLD_WORKOUTS
import com.tczerwien.lifts.analytics.consistencySummary
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsBarChart
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.MonoDigits
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.SectionLabel
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Bar-height denominator: weeks with this many (or more) workouts render full height. */
private const val MAX_BAR_WORKOUTS = 5

class ConsistencyViewModel(
    private val repository: LiftsRepository,
) : LightViewModel<Unit>() {

    data class UiState(
        val loaded: Boolean = false,
        /** Workouts per ISO week, oldest first, [CONSISTENCY_CHART_WEEKS] entries ([consistencySummary]). */
        val counts: List<Int> = emptyList(),
        /** Per-bar month abbreviation under weeks that start a new month, else null. */
        val monthLabels: List<String?> = emptyList(),
        val thisWeekText: String = "0",
        val fourWeekAvgText: String = "0.0",
        val longestStreakText: String = "0 weeks",
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        val perf = Perf.start()
        viewModelScope.launch(Dispatchers.IO) {
            refresh()
            Perf.done("consistency.load", perf)
        }
    }

    /** One query: every finished workout with its ticked-set count; one without a ticked set counts nowhere. */
    private fun refresh() {
        // Weeks are the phone's calendar weeks, as History shows them.
        val zone = ZoneId.systemDefault()
        val summary = consistencySummary(
            repository.finishedWorkoutsWithSetCounts(),
            todayEpochDay = LocalDate.now(zone).toEpochDay(),
            zone = zone,
        )
        _uiState.value = UiState(
            loaded = true,
            counts = summary.counts,
            monthLabels = summary.monthLabels,
            thisWeekText = summary.thisWeek.toString(),
            fourWeekAvgText = "%.1f".format(Locale.US, summary.fourWeekAverage),
            longestStreakText = Format.count(summary.longestStreakWeeks, "week"),
        )
    }
}

/**
 * Consistency (design/screens/consistency.html): workouts per ISO week over the last
 * [CONSISTENCY_CHART_WEEKS] weeks as a bar chart (current week last; bars max out at
 * [MAX_BAR_WORKOUTS]+), month labels on new-month weeks, and a summary: this week, the average of
 * the last [AVERAGE_WEEKS] complete weeks, and the longest streak of weeks with
 * [STREAK_THRESHOLD_WORKOUTS]+ workouts across the full history. Only workouts with a ticked set count.
 * Its last row opens Sets per Muscle ([SetsPerMuscleScreen]).
 */
class ConsistencyScreen(
    private val sealedActivity: SealedLightActivity,
) : LightScreen<Unit, ConsistencyViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    override val viewModelClass: Class<ConsistencyViewModel>
        get() = ConsistencyViewModel::class.java

    override fun createViewModel() = ConsistencyViewModel(repository)

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
                    center = LightTopBarCenter.Text("Consistency"),
                )

                if (uiState.loaded) {
                    SectionLabel("Workouts / Week")

                    LiftsBarChart(
                        values = uiState.counts,
                        maxValue = MAX_BAR_WORKOUTS,
                        modifier = Modifier.fillMaxWidth(),
                        xAxisLabels = {
                            uiState.monthLabels.forEach { label ->
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (label != null) {
                                        // Superfine, not the mockup's micro: 8 px labels are about 1 mm tall on the phone.
                                        LightText(
                                            text = label,
                                            variant = LightTextVariant.Superfine,
                                            lighten = true,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        },
                    )

                    SectionLabel("Summary")

                    LiftsListRow(
                        left = {
                            LightText(text = "This week", variant = LightTextVariant.Detail)
                        },
                        right = {
                            MonoDigits(uiState.thisWeekText, LightTextVariant.Copy)
                        },
                    )
                    RowDivider()
                    LiftsListRow(
                        left = {
                            LightText(text = "$AVERAGE_WEEKS-week average", variant = LightTextVariant.Detail)
                        },
                        right = {
                            // Digits in monospace, the point not: in monospace it gets a whole cell, "3 . 5".
                            MonoDigits(uiState.fourWeekAvgText, LightTextVariant.Copy)
                        },
                    )
                    RowDivider()
                    LiftsListRow(
                        left = {
                            // The rule shows: twice a week for months is no streak, and that should read as a rule, not a bug.
                            LightText(text = "Longest streak ($STREAK_THRESHOLD_WORKOUTS+/wk)", variant = LightTextVariant.Detail)
                        },
                        right = {
                            MonoDigits(uiState.longestStreakText, LightTextVariant.Copy)
                        },
                    )
                    RowDivider()
                    // Weekly hard sets per muscle against his targets: a screen of its own.
                    LiftsListRow(
                        onClick = { navigateTo(::SetsPerMuscleScreen) },
                        left = {
                            LightText(text = "Sets per Muscle", variant = LightTextVariant.Detail)
                        },
                        right = {
                            LightIcon(icon = LightIcons.ARROW_RIGHT, size = 1.6f)
                        },
                    )
                }
            }
        }
    }
}
