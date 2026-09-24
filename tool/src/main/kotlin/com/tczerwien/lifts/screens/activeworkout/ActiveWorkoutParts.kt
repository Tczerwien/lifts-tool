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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.session.SET_TYPE_WARMUP
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.Stepper
import com.tczerwien.lifts.ui.WeightUnit
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

@Composable
internal fun ExerciseHeader(block: ActiveWorkoutViewModel.ExerciseBlock) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = block.baseName,
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
 * Fixed strip between the set list and the rest strip; visible while a row is active.
 * The weight stepper gets the wider slot so "102.5" fits between its 4gu targets.
 */
@Composable
internal fun StepperStrip(
    viewModel: ActiveWorkoutViewModel,
    uiState: ActiveWorkoutViewModel.UiState,
    activeSet: SetEntity,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.Bottom,
    ) {
        Stepper(
            onStep = viewModel::stepWeight,
            modifier = Modifier.weight(1.3f),
            label = "Weight (${uiState.units.suffix})",
            decrementEnabled = viewModel.weightDecrementEnabled(uiState),
        ) {
            LightText(
                text = activeSet.weightKg?.let { Format.weight(it, uiState.units) } ?: "—",
                variant = LightTextVariant.Copy,
                monospace = true,
                maxLines = 1,
            )
        }
        Stepper(
            onStep = viewModel::stepReps,
            modifier = Modifier.weight(1f),
            label = "Reps",
            decrementEnabled = viewModel.repsDecrementEnabled(uiState),
        ) {
            LightText(
                text = activeSet.reps?.toString() ?: "—",
                variant = LightTextVariant.Copy,
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

/** 2gu rest strip above the bottom bar: remaining countdown, tap opens the timer screen. */
@Composable
internal fun RestStrip(remainingSec: Int, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LightThemeTokens.colors.contentSecondary),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(2f.gridUnitsAsDp())
                .lightClickable(onClick = onClick)
                .padding(horizontal = 1f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
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
