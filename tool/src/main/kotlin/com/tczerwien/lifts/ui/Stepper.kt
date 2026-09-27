package com.tczerwien.lifts.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

private const val TARGET_UNITS = 4f
private const val GLYPH_UNITS = 2.2f
private const val HOLD_INITIAL_DELAY_MS = 400L
private const val HOLD_REPEAT_INTERVAL_MS = 250L // ~4 steps/s
private const val DISABLED_ALPHA = 0.4f

/**
 * +/- stepper (design/components/stepper.html): 4gu bordered square targets around a
 * caller-rendered value slot. A tap steps once as the finger lifts; a drag past touch slop (a
 * scroll that started on a target) steps nothing. Press-and-hold auto-repeats after ~400ms
 * at ~4 steps/s. Disabled targets dim to 40% with contentSecondary glyphs.
 *
 * @param onStep called with +1 or -1 per step; the caller applies the unit-specific
 *   increment ([WeightUnit.weightStep] / [WeightUnit.bodyweightStep] / 1 rep).
 * @param label optional superfine label centered above the row, e.g. "WEIGHT (LB)".
 * @param value the center slot; typically LightText(variant = Subtitle, monospace = true).
 */
@Composable
fun Stepper(
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    decrementEnabled: Boolean = true,
    incrementEnabled: Boolean = true,
    value: @Composable () -> Unit,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (label != null) {
            LightText(
                text = label.uppercase(),
                variant = LightTextVariant.Superfine,
                lighten = true,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperTarget(delta = -1, enabled = decrementEnabled, onStep = onStep, plus = false)
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                value()
            }
            StepperTarget(delta = +1, enabled = incrementEnabled, onStep = onStep, plus = true)
        }
    }
}

@Composable
private fun StepperTarget(
    delta: Int,
    enabled: Boolean,
    onStep: (Int) -> Unit,
    plus: Boolean,
) {
    val colors = LightThemeTokens.colors
    val currentOnStep by rememberUpdatedState(onStep)

    val glyphColor = if (enabled) colors.content else colors.contentSecondary
    Box(
        modifier = Modifier
            .size(TARGET_UNITS.gridUnitsAsDp())
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .border(1.5.dp, colors.contentSecondary)
            // Steps come from the gesture itself, never from a redraw that might miss a quick tap.
            // Keyed on enabled: reaching a limit restarts the handler, which ends a hold there.
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    when (withTimeoutOrNull(HOLD_INITIAL_DELAY_MS) { awaitPressEnd(down, slopCancels = true) }) {
                        PressEnd.LIFTED -> currentOnStep(delta)
                        PressEnd.CANCELLED -> Unit
                        null -> do {
                            currentOnStep(delta)
                        } while (withTimeoutOrNull(HOLD_REPEAT_INTERVAL_MS) { awaitPressEnd(down, slopCancels = false) } == null)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        StepperGlyph(
            color = glyphColor,
            plus = plus,
            modifier = Modifier.size(GLYPH_UNITS.gridUnitsAsDp()),
        )
    }
}

private enum class PressEnd {
    /** The finger lifted: a tap, or the end of a hold. */
    LIFTED,

    /** A drag past touch slop, a scroll that took the pointer, or the finger leaving the target. */
    CANCELLED,
}

/**
 * Waits for the press that started with [down] to end. A drag past touch slop cancels only while
 * [slopCancels]: once a hold is repeating, a finger drifting on the target keeps it going.
 */
private suspend fun AwaitPointerEventScope.awaitPressEnd(down: PointerInputChange, slopCancels: Boolean): PressEnd {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return PressEnd.CANCELLED
        if (change.isConsumed) return PressEnd.CANCELLED
        if (!change.pressed) return PressEnd.LIFTED
        if (change.isOutOfBounds(size, extendedTouchPadding)) return PressEnd.CANCELLED
        if (slopCancels && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
            return PressEnd.CANCELLED
        }
        // A scroll around the stepper takes the pointer after this handler has looked at the event.
        val afterParents = awaitPointerEvent(PointerEventPass.Final)
        if (afterParents.changes.any { it.id == down.id && it.isConsumed }) return PressEnd.CANCELLED
    }
}

/** Thin +/- strokes on a 24-unit design box: horizontal (5,12)-(19,12), vertical (12,5)-(12,19). */
@Composable
private fun StepperGlyph(color: Color, plus: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val u = size.minDimension / 24f
        val stroke = 1.6f * u
        drawLine(
            color = color,
            start = Offset(5f * u, 12f * u),
            end = Offset(19f * u, 12f * u),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        if (plus) {
            drawLine(
                color = color,
                start = Offset(12f * u, 5f * u),
                end = Offset(12f * u, 19f * u),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}
