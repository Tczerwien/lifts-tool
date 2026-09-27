package com.tczerwien.lifts.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * State of one row in the set grid (design/components/set-row.html).
 *
 * Grid columns: 2gu marker | 8gu previous | fill | 5gu weight | 4gu reps | 3gu checkbox,
 * inside 1gu of padding at the start and 2gu at the end, where the list's scrollbar floats
 * (LightScrollBarPosition.Inside): the rows keep their width whether or not the list scrolls, so
 * they stay under [SetRowHeader]. Row height is exactly 3gu (LightLazyScrollView uniform height
 * contract).
 */
data class SetRowState(
    /** "1".."n" for work sets, "W" for warm-up sets. */
    val marker: String,
    /** Previous performance, e.g. "135 × 8" ([Format.setSummary]); null renders blank. */
    val previous: String?,
    /** Display weight string; null renders an em dash (nothing to start from). */
    val weight: String?,
    /** Display reps string; null renders an em dash (nothing to start from). */
    val reps: String?,
    /** [weight] is a starting value, not entered: it renders dimmed even on the active row. */
    val weightSeeded: Boolean = false,
    /** [reps] is a starting value, not entered: it renders dimmed even on the active row. */
    val repsSeeded: Boolean = false,
    /** Steppers currently target this row: its marker is inverted and its values render full-contrast. */
    val active: Boolean = false,
    /** Set is logged: checkbox fills. */
    val completed: Boolean = false,
    /** Warm-up set: dimmed values, contentSecondary checkbox fill when done. */
    val warmup: Boolean = false,
    /** Taken to failure: superfine "F" after the marker. */
    val failure: Boolean = false,
    /** Personal record: star after reps. */
    val pr: Boolean = false,
)

private const val ROW_HEIGHT_UNITS = 3f
private const val HEADER_HEIGHT_UNITS = 1.5f
private const val COL_MARKER_UNITS = 2f
private const val COL_PREVIOUS_UNITS = 8f
private const val COL_WEIGHT_UNITS = 5f
private const val COL_REPS_UNITS = 4f
private const val COL_CHECK_UNITS = 3f
private const val CHECKBOX_UNITS = 2f
private const val CHECKMARK_UNITS = 0.9f
private const val PR_STAR_UNITS = 1.1f
private const val PAD_START_UNITS = 1f
private const val MARKER_PAD_UNITS = 0.3f

/** The set list's scrollbar floats over this much of the right edge; nothing tappable goes there. */
private const val PAD_END_UNITS = 2f

/** Superfine column header, 1.5gu tall. [unit] picks the "Lbs"/"Kg" label. */
@Composable
fun SetRowHeader(unit: WeightUnit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT_UNITS.gridUnitsAsDp())
            .padding(start = PAD_START_UNITS.gridUnitsAsDp(), end = PAD_END_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(COL_MARKER_UNITS.gridUnitsAsDp()), contentAlignment = Alignment.Center) {
            HeaderLabel("SET")
        }
        Box(Modifier.width(COL_PREVIOUS_UNITS.gridUnitsAsDp()), contentAlignment = Alignment.CenterStart) {
            HeaderLabel("PREVIOUS")
        }
        Box(Modifier.weight(1f))
        Box(Modifier.width(COL_WEIGHT_UNITS.gridUnitsAsDp()), contentAlignment = Alignment.CenterEnd) {
            HeaderLabel(unit.columnLabel.uppercase())
        }
        Box(Modifier.width(COL_REPS_UNITS.gridUnitsAsDp()), contentAlignment = Alignment.CenterEnd) {
            HeaderLabel("REPS")
        }
        Box(Modifier.width(COL_CHECK_UNITS.gridUnitsAsDp()))
    }
}

@Composable
private fun HeaderLabel(text: String) {
    LightText(text = text, variant = LightTextVariant.Superfine, lighten = true, maxLines = 1)
}

/**
 * One set row. All six design states (upcoming / active / completed / completed+PR /
 * warm-up / failure) derive from [state] flags.
 *
 * @param onRowClick selects the row as the stepper target.
 * @param onMarkerClick taps the set marker (number / "W") to change the set type. Kept apart from
 *   [onRowClick] so a near-miss on the row can never silently turn a work set into a warm-up.
 * @param onPreviousClick taps previous performance to autofill.
 * @param onCheckToggle toggles set completion.
 */
@Composable
fun SetRow(
    state: SetRowState,
    modifier: Modifier = Modifier,
    onRowClick: (() -> Unit)? = null,
    onMarkerClick: (() -> Unit)? = null,
    onPreviousClick: (() -> Unit)? = null,
    onCheckToggle: (() -> Unit)? = null,
) {
    val colors = LightThemeTokens.colors
    // Upcoming (not yet filled) and warm-up values render contentSecondary.
    val valuesLighten = state.warmup || (!state.active && !state.completed)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT_UNITS.gridUnitsAsDp())
            .let { m -> if (onRowClick != null) m.lightClickable(onClick = onRowClick) else m }
            .padding(start = PAD_START_UNITS.gridUnitsAsDp(), end = PAD_END_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Marker (set number / "W", + superfine "F" for failure) — tap to change the set type. The
        // active row's marker is inverted, so the set being logged stands out at arm's length.
        Box(
            modifier = Modifier
                .width(COL_MARKER_UNITS.gridUnitsAsDp())
                .let { m -> if (onMarkerClick != null) m.lightClickable(onClick = onMarkerClick) else m },
            contentAlignment = Alignment.Center,
        ) {
            val markerColor = if (state.active) colors.background else null
            Row(
                modifier = Modifier
                    .let { m -> if (state.active) m.background(colors.content) else m }
                    .padding(horizontal = MARKER_PAD_UNITS.gridUnitsAsDp()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText(
                    text = state.marker,
                    variant = LightTextVariant.Detail,
                    lighten = !state.failure,
                    maxLines = 1,
                    color = markerColor,
                )
                if (state.failure) {
                    LightText(
                        text = " F",
                        variant = LightTextVariant.Superfine,
                        maxLines = 1,
                        color = markerColor,
                    )
                }
            }
        }

        // Previous performance — tap to autofill.
        Box(
            modifier = Modifier
                .width(COL_PREVIOUS_UNITS.gridUnitsAsDp())
                .let { m -> if (onPreviousClick != null) m.lightClickable(onClick = onPreviousClick) else m },
            contentAlignment = Alignment.CenterStart,
        ) {
            if (state.previous != null) {
                LightText(
                    text = state.previous,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    monospace = true,
                    maxLines = 1,
                )
            }
        }

        Box(Modifier.weight(1f))

        // Weight.
        Box(Modifier.width(COL_WEIGHT_UNITS.gridUnitsAsDp()), contentAlignment = Alignment.CenterEnd) {
            LightText(
                text = state.weight ?: "—",
                variant = LightTextVariant.Copy,
                lighten = valuesLighten || state.weight == null || state.weightSeeded,
                monospace = true,
                maxLines = 1,
            )
        }

        // Reps (+ PR star).
        Row(
            modifier = Modifier.width(COL_REPS_UNITS.gridUnitsAsDp()),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = state.reps ?: "—",
                variant = LightTextVariant.Copy,
                lighten = valuesLighten || state.reps == null || state.repsSeeded,
                monospace = true,
                maxLines = 1,
            )
            if (state.pr) {
                PrStar(
                    color = colors.content,
                    modifier = Modifier
                        .padding(start = 0.4f.gridUnitsAsDp())
                        .size(PR_STAR_UNITS.gridUnitsAsDp()),
                )
            }
        }

        // Checkbox.
        Box(Modifier.width(COL_CHECK_UNITS.gridUnitsAsDp()), contentAlignment = Alignment.CenterEnd) {
            SetCheckbox(
                completed = state.completed,
                warmup = state.warmup,
                onToggle = onCheckToggle,
            )
        }
    }
}

@Composable
private fun SetCheckbox(
    completed: Boolean,
    warmup: Boolean,
    onToggle: (() -> Unit)?,
) {
    val colors = LightThemeTokens.colors
    val fillColor = when {
        completed && warmup -> colors.contentSecondary
        completed -> colors.content
        else -> Color.Transparent
    }
    val borderColor = when {
        completed && warmup -> colors.contentSecondary
        completed -> colors.content
        else -> colors.contentSecondary
    }
    Box(
        modifier = Modifier
            .size(CHECKBOX_UNITS.gridUnitsAsDp())
            .border(1.5.dp, borderColor)
            .background(fillColor)
            .let { m -> if (onToggle != null) m.lightClickable(onClick = onToggle) else m },
        contentAlignment = Alignment.Center,
    ) {
        if (completed) {
            Checkmark(
                color = colors.background,
                modifier = Modifier.size(CHECKMARK_UNITS.gridUnitsAsDp()),
            )
        }
    }
}

/** Thin checkmark on a 24-unit design box: (4,12.5) -> (9.5,18) -> (20,6.5). */
@Composable
private fun Checkmark(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val u = size.minDimension / 24f
        val path = Path().apply {
            moveTo(4f * u, 12.5f * u)
            lineTo(9.5f * u, 18f * u)
            lineTo(20f * u, 6.5f * u)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** Thin outlined five-point star on a 24-unit design box (design pr-star path). */
@Composable
private fun PrStar(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val u = size.minDimension / 24f
        val points = listOf(
            Offset(12f, 3f),
            Offset(14.6f, 8.6f),
            Offset(20.7f, 9.3f),
            Offset(16.2f, 13.5f),
            Offset(17.4f, 19.5f),
            Offset(12f, 16.5f),
            Offset(6.6f, 19.5f),
            Offset(7.8f, 13.5f),
            Offset(3.3f, 9.3f),
            Offset(9.4f, 8.6f),
        )
        val path = Path().apply {
            moveTo(points.first().x * u, points.first().y * u)
            points.drop(1).forEach { lineTo(it.x * u, it.y * u) }
            close()
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
