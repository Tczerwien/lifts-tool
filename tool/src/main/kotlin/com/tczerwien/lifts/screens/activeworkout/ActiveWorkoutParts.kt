package com.tczerwien.lifts.screens.activeworkout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.session.RowValues
import com.tczerwien.lifts.session.SET_TYPE_WARMUP
import com.tczerwien.lifts.session.restOver
import com.tczerwien.lifts.session.restOverSec
import com.tczerwien.lifts.session.restRemainingSec
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.Stepper
import com.tczerwien.lifts.ui.WeightUnit
import com.tczerwien.lifts.ui.rememberSecondClock
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** "1".."n" for work sets in order, "W" for warm-ups (design set-row numbering). */
internal fun buildMarkers(sets: List<SetEntity>): List<String> {
    var workIndex = 0
    return sets.map { set ->
        if (set.type == SET_TYPE_WARMUP) "W" else (++workIndex).toString()
    }
}

/** "135 × 8" from a previous-performance set; weight-only and reps-only degrade gracefully. */
internal fun previousSummary(previous: SetEntity?, units: WeightUnit): String? {
    previous ?: return null
    val weight = previous.weightKg
    val reps = previous.reps
    return when {
        weight != null && reps != null -> Format.setSummary(weight, reps, units)
        reps != null -> "× $reps"
        weight != null -> Format.weight(weight, units)
        else -> null
    }
}

/** The row the set list opens at when it is built: the one above the active row, so the set just done shows too. */
internal fun openingListIndex(activeIndex: Int): Int = (activeIndex - 1).coerceAtLeast(0)

/**
 * Where the set list scrolls when the active row changes to [activeIndex], or null to stay put.
 * Null while the row is fully on screen ([firstFullyVisible] to [lastFullyVisible]), so tapping a
 * row in view never jumps the list; otherwise the row above it goes to the top, as when opening.
 */
internal fun activeRowScrollTarget(activeIndex: Int, firstFullyVisible: Int?, lastFullyVisible: Int?): Int? = when {
    activeIndex < 0 -> null
    firstFullyVisible != null && lastFullyVisible != null && activeIndex in firstFullyVisible..lastFullyVisible -> null
    else -> openingListIndex(activeIndex)
}

@Composable
internal fun ExerciseHeader(block: ActiveWorkoutViewModel.ExerciseBlock) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        // A long name loses its middle, never the words at either end that tell variants apart.
        LightText(
            text = block.name,
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        LightText(
            text = block.detailLine,
            variant = LightTextVariant.Detail,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        block.cue?.let { cue ->
            LightText(
                text = "CUE: $cue".uppercase(),
                variant = LightTextVariant.Superfine,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 3gu "+ ADD SET" row. The design shows 2gu, but the row lives inside the lazy list and
 * LightLazyScrollView's scrollbar math requires every item at the uniform 3gu height.
 */
@Composable
internal fun AddSetRow(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3f.gridUnitsAsDp())
            .lightClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = "+ ADD SET", variant = LightTextVariant.Button, lighten = true)
    }
}

/**
 * Fixed strip between the set list and the rest slot. With no active row (every set ticked) it
 * stays at full height with both steppers greyed out, so nothing below the list moves.
 * The weight stepper gets the wider slot (1.2 to 1) so "102.5" fits between its 4gu targets and
 * the reps slot still holds "100".
 */
@Composable
internal fun StepperStrip(
    viewModel: ActiveWorkoutViewModel,
    uiState: ActiveWorkoutViewModel.UiState,
    activeSet: SetEntity?,
    /** The active row's starting values; shown dimmed where the row has none of its own. */
    seed: RowValues?,
) {
    val active = activeSet != null
    val weightKg = activeSet?.weightKg ?: seed?.weightKg
    val reps = activeSet?.reps ?: seed?.reps
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.Bottom,
    ) {
        Stepper(
            onStep = viewModel::stepWeight,
            bigSteps = true,
            modifier = Modifier.weight(1.2f),
            label = "Weight (${uiState.units.suffix})",
            decrementEnabled = active && viewModel.weightDecrementEnabled(uiState),
            incrementEnabled = active,
        ) {
            LightText(
                text = weightKg?.let { Format.weight(it, uiState.units) } ?: "—",
                variant = LightTextVariant.Copy,
                lighten = activeSet?.weightKg == null,
                monospace = true,
                maxLines = 1,
            )
        }
        Stepper(
            onStep = viewModel::stepReps,
            modifier = Modifier.weight(1f),
            label = "Reps",
            decrementEnabled = active && viewModel.repsDecrementEnabled(uiState),
            incrementEnabled = active,
        ) {
            LightText(
                text = reps?.toString() ?: "—",
                variant = LightTextVariant.Copy,
                lighten = activeSet?.reps == null,
                monospace = true,
                maxLines = 1,
            )
        }
    }
}

/** Collapsed next-exercise row (2gu + 0.5gu top margin); tap advances (wraps around). */
@Composable
internal fun NextExerciseRow(name: String, setCount: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 0.5f.gridUnitsAsDp())
            .height(2f.gridUnitsAsDp())
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LightText(
            text = name,
            variant = LightTextVariant.Copy,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        LightText(
            text = "$setCount Sets",
            variant = LightTextVariant.Detail,
            lighten = true,
            maxLines = 1,
            modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
        )
    }
}

/**
 * The 2gu rest slot above the bottom bar, always the same height so nothing above it moves:
 * - a [hint] ("TAP AGAIN TO FINISH · 3 SETS NOT LOGGED", "SET WEIGHT OR REPS FIRST") takes the
 *   slot for the few seconds it shows;
 * - while the rest ending at [endMillis] runs, the countdown ("Rest 1:23 · TAP FOR TIMER"); a tap
 *   opens the timer screen;
 * - once it has ended, how long ago ("REST OVER · 1:20 AGO", [restOver]), until the next tick, Skip
 *   or Finish; so a rest that ended with the screen off still says so when he looks;
 * - with no rest saved, blank.
 * Only the countdown takes taps. The clock ticks here, so only the slot redraws.
 */
@Composable
internal fun RestSlot(endMillis: Long?, hint: String?, onOpenTimer: () -> Unit) {
    val nowMillis = endMillis?.let { end -> rememberSecondClock(anchor = end).value }
    val remainingSec = if (endMillis != null && nowMillis != null) restRemainingSec(endMillis, nowMillis) else 0
    // Seconds since the end once the rest is over; null while it runs or with none saved.
    val overSec = if (endMillis != null && nowMillis != null && restOver(endMillis, nowMillis)) {
        restOverSec(endMillis, nowMillis)
    } else {
        null
    }
    val over = overSec != null && hint == null
    val counting = remainingSec > 0 && hint == null
    val showing = hint != null || counting || over
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(if (showing) LightThemeTokens.colors.contentSecondary else Color.Transparent),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(2f.gridUnitsAsDp())
                .let { if (counting) it.lightClickable(onClick = onOpenTimer) else it }
                .padding(horizontal = 1f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (counting) Arrangement.SpaceBetween else Arrangement.Center,
        ) {
            when {
                hint != null -> LightText(text = hint, variant = LightTextVariant.Superfine, maxLines = 1)
                counting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LightText(text = "Rest ", variant = LightTextVariant.Detail)
                        LightText(
                            text = Format.clock(remainingSec),
                            variant = LightTextVariant.Detail,
                            monospace = true,
                        )
                    }
                    LightText(
                        text = "TAP FOR TIMER",
                        variant = LightTextVariant.Superfine,
                        lighten = true,
                    )
                }
                // Only the number is monospace, like the countdown's, so the words hold still.
                overSec != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    LightText(text = "REST OVER · ", variant = LightTextVariant.Detail, maxLines = 1)
                    LightText(text = Format.ago(overSec), variant = LightTextVariant.Detail, monospace = true, maxLines = 1)
                    LightText(text = " AGO", variant = LightTextVariant.Detail, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun EmptyWorkoutBody(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LightText(
                text = "No exercises yet",
                variant = LightTextVariant.Copy,
                lighten = true,
            )
            LightText(
                text = "ADD ONE BELOW",
                variant = LightTextVariant.Superfine,
                lighten = true,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}
