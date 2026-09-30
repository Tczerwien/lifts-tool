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
import com.tczerwien.lifts.analytics.longestStreak
import com.tczerwien.lifts.analytics.weeksSince
import com.tczerwien.lifts.analytics.workoutsPerWeek
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.ui.LiftsBarChart
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.SectionLabel
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Weeks shown in the chart (design/screens/consistency.html: last 12 ISO weeks). */
private const val CHART_WEEKS = 12

/** Bar-height denominator: weeks with this many (or more) workouts render full height. */
private const val MAX_BAR_WORKOUTS = 5

/** A week counts toward the streak when it has at least this many finished workouts. */
private const val STREAK_THRESHOLD_WORKOUTS = 3

/** Weeks averaged for the summary's "4-week average" row. */
private const val AVERAGE_WEEKS = 4

private val MONTH_FORMATTER = DateTimeFormatter.ofPattern("MMM", Locale.US)

class ConsistencyViewModel(
    private val repository: LiftsRepository,
) : LightViewModel<Unit>() {

    data class UiState(
        val loaded: Boolean = false,
        /** Finished workouts per ISO week, oldest first, [CHART_WEEKS] entries. */
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

    private fun refresh() {
        val workouts = repository.listWorkouts()
        // Weeks are the phone's calendar weeks, as History shows them.
        val zone = ZoneId.systemDefault()
        val todayEpochDay = LocalDate.now(zone).toEpochDay()
        val counts = workoutsPerWeek(workouts, weeks = CHART_WEEKS, todayEpochDay = todayEpochDay, zone = zone)

        val currentMonday = mondayOf(LocalDate.ofEpochDay(todayEpochDay))
        val monthLabels = List(CHART_WEEKS) { index ->
            val monday = currentMonday.minusWeeks((CHART_WEEKS - 1 - index).toLong())
            // Label the first bar and every week whose Monday lands in a new month.
            if (index == 0 || monday.month != monday.minusWeeks(1).month) {
                MONTH_FORMATTER.format(monday)
            } else {
                null
            }
        }

        // Longest streak spans the FULL history, not just the charted window.
        val finished = workouts.filter { it.endedAt != null }
        val longestStreakWeeks = if (finished.isEmpty()) {
            0
        } else {
            val spanWeeks = weeksSince(finished.minOf { it.startedAt }, todayEpochDay, zone)
            longestStreak(
                workoutsPerWeek(workouts, weeks = spanWeeks, todayEpochDay = todayEpochDay, zone = zone),
                threshold = STREAK_THRESHOLD_WORKOUTS,
            )
        }

        _uiState.value = UiState(
            loaded = true,
            counts = counts,
            monthLabels = monthLabels,
            thisWeekText = counts.last().toString(),
            fourWeekAvgText = "%.1f".format(Locale.US, counts.takeLast(AVERAGE_WEEKS).average()),
            longestStreakText = if (longestStreakWeeks == 1) {
                "1 week"
            } else {
                "$longestStreakWeeks weeks"
            },
        )
    }

    private fun mondayOf(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - 1).toLong())
}

/**
 * Consistency (design/screens/consistency.html): finished workouts per ISO week over the
 * last [CHART_WEEKS] weeks as a bar chart (current week last; bars max out at
 * [MAX_BAR_WORKOUTS]+), month labels on new-month weeks, and a summary — this week,
 * 4-week average, and the longest streak of weeks with [STREAK_THRESHOLD_WORKOUTS]+
 * workouts across the full history.
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
                                        LightText(
                                            text = label,
                                            variant = LightTextVariant.Micro,
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
                            LightText(
                                text = uiState.thisWeekText,
                                variant = LightTextVariant.Copy,
                                monospace = true,
                            )
                        },
                    )
                    RowDivider()
                    LiftsListRow(
                        left = {
                            LightText(text = "4-week average", variant = LightTextVariant.Detail)
                        },
                        right = {
                            LightText(
                                text = uiState.fourWeekAvgText,
                                variant = LightTextVariant.Copy,
                                monospace = true,
                            )
                        },
                    )
                    RowDivider()
                    LiftsListRow(
                        left = {
                            LightText(text = "Longest streak", variant = LightTextVariant.Detail)
                        },
                        right = {
                            LightText(
                                text = uiState.longestStreakText,
                                variant = LightTextVariant.Copy,
                            )
                        },
                    )
                }
            }
        }
    }
}
