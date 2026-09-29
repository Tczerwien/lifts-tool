package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.RowName
import com.tczerwien.lifts.ui.TWO_LINE_ROW_HEIGHT_UNITS
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HistoryViewModel(
    private val repository: LiftsRepository,
) : LightViewModel<Unit>() {

    data class Row(
        val workoutId: Long,
        val name: String,
        /** Workout start time — the date shown under the name. */
        val dateMillis: Long,
        /** Completed sets only (completedAt != null). */
        val setCount: Int,
        /** Rounded minutes, same derivation as the export layer. */
        val durationMin: Int,
    )

    data class UiState(
        val loaded: Boolean = false,
        val rows: List<Row> = emptyList(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Reads behind any queued write, so a workout just deleted on Workout Detail never reappears. */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        LiftsIo.write({}) { refresh() }
    }

    /** One query: finished workouts newest first, each with its ticked-set count. */
    private fun refresh() {
        val rows = repository.finishedWorkoutsWithSetCounts().mapNotNull { (workout, completedSets) ->
            val endedAt = workout.endedAt ?: return@mapNotNull null
            Row(
                workoutId = workout.id,
                name = workout.name,
                dateMillis = workout.startedAt,
                setCount = completedSets,
                durationMin = ((endedAt - workout.startedAt) / 60000.0).roundToInt().coerceAtLeast(0),
            )
        }
        _uiState.value = UiState(loaded = true, rows = rows)
    }
}

/**
 * History (design/screens/history.html): finished workouts newest first as uniform 4gu
 * rows — name over date on the left, "N sets · M min" on the right — each opening its
 * [WorkoutDetailScreen].
 */
class HistoryScreen(
    private val sealedActivity: SealedLightActivity,
) : LightScreen<Unit, HistoryViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    /**
     * Held by the screen, which stays on the back stack under Workout Detail: back from a workout in
     * March, the list is still at March. A remembered state would be dropped with the composition.
     */
    private val listState = LazyListState()

    override val viewModelClass: Class<HistoryViewModel>
        get() = HistoryViewModel::class.java

    override fun createViewModel() = HistoryViewModel(repository)

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
                    center = LightTopBarCenter.Text("History"),
                )

                if (uiState.loaded && uiState.rows.isEmpty()) {
                    LightText(
                        text = "No finished workouts yet.",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(1f.gridUnitsAsDp()),
                    )
                } else {
                    LightLazyScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        listState = listState,
                        uniformItemHeightGridUnits = TWO_LINE_ROW_HEIGHT_UNITS,
                    ) {
                        items(uiState.rows.size) { index ->
                            val row = uiState.rows[index]
                            // The divider lives inside the 4gu item so every lazy item
                            // keeps the uniform height the scrollbar math requires.
                            Box {
                                LiftsListRow(
                                    onClick = {
                                        navigateTo({ WorkoutDetailScreen(it, workoutId = row.workoutId) })
                                    },
                                    heightUnits = TWO_LINE_ROW_HEIGHT_UNITS,
                                    left = {
                                        Column {
                                            RowName(row.name)
                                            LightText(
                                                text = Format.dateShort(row.dateMillis),
                                                variant = LightTextVariant.Detail,
                                                lighten = true,
                                                monospace = true,
                                                maxLines = 1,
                                            )
                                        }
                                    },
                                    right = {
                                        LightText(
                                            text = "${Format.count(row.setCount, "set")} · ${row.durationMin} min",
                                            variant = LightTextVariant.Detail,
                                            lighten = true,
                                            maxLines = 1,
                                        )
                                    },
                                )
                                if (index < uiState.rows.lastIndex) {
                                    RowDivider(modifier = Modifier.align(Alignment.BottomCenter))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
