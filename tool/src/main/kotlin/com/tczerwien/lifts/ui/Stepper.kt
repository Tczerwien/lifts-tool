package com.tczerwien.lifts.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
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
import com.thelightphone.sdk.ui.lightClickable

private const val TARGET_UNITS = 4f
private const val GLYPH_UNITS = 2.2f
private const val DISABLED_ALPHA = 0.4f

/** A press held this long starts repeating; a shorter one is a tap. */
const val HOLD_INITIAL_DELAY_MS = 400L

/** A hold repeats every [HOLD_SLOW_INTERVAL_MS] (4 a second) for its first [HOLD_SLOW_REPEATS] repeats (to about 1.5 s)… */
const val HOLD_SLOW_INTERVAL_MS = 250L
const val HOLD_SLOW_REPEATS = 5

/** …then every [HOLD_FAST_INTERVAL_MS] (12 a second). */
const val HOLD_FAST_INTERVAL_MS = 80L

/** From repeat [HOLD_BIG_STEPS_FROM] (about 1.9 s in), a stepper that allows it moves [HOLD_BIG_STEPS] steps at a time. */
const val HOLD_BIG_STEPS_FROM = 8
const val HOLD_BIG_STEPS = 5

/** One repeat of a held stepper: how many steps it moves, and how long until the next one. */
data class HoldStep(val steps: Int, val delayMillis: Long)

/**
 * Repeat number [repeat] of a hold (0 is the first, [HOLD_INITIAL_DELAY_MS] after the press). It
 * speeds up after about 1.5 s; with [bigSteps] (weights, never reps or rest times) it then also
 * moves [HOLD_BIG_STEPS] steps at a time, so 0 to 315 lb, or a first bodyweight of 185 from the
 * 150 start, takes seconds rather than most of a minute.
 */
fun holdSchedule(repeat: Int, bigSteps: Boolean = false): HoldStep = HoldStep(
    steps = if (bigSteps && repeat >= HOLD_BIG_STEPS_FROM) HOLD_BIG_STEPS else 1,
    delayMillis = if (repeat < HOLD_SLOW_REPEATS) HOLD_SLOW_INTERVAL_MS else HOLD_FAST_INTERVAL_MS,
)

/**
 * +/- stepper (design/components/stepper.html): 4gu bordered square targets around a
 * caller-rendered value slot. A tap steps once as the finger lifts; a drag past touch slop (a
 * scroll that started on a target) steps nothing. Press-and-hold auto-repeats after ~400ms
 * and speeds up as it goes ([holdSchedule]). Disabled targets dim to 40% with contentSecondary glyphs.
 *
 * @param onStep called with the number of steps to move, +1 or -1 (or ±[HOLD_BIG_STEPS] late in a
 *   hold with [bigSteps]); the caller applies the increment (Settings' weight step /
 *   [WeightUnit.bodyweightStep] / 1 rep).
 * @param bigSteps a long hold moves several steps at a time: for weights, never counts or times.
 * @param label optional superfine label centered above the row, e.g. "WEIGHT (LB)".
 * @param value the center slot; typically LightText(variant = Subtitle, monospace = true). Give
 *   the stepper room for it: five monospace characters ("182.4") need about 8gu between the targets
 *   at Subtitle, 5gu at Copy.
 * @param onValueClick a tap on the value between the targets, e.g. to type a number in; null for none.
 * @param labelGapUnits the gap under [label]: 1gu, or less where many steppers share a screen.
 */
@Composable
fun Stepper(
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    decrementEnabled: Boolean = true,
    incrementEnabled: Boolean = true,
    bigSteps: Boolean = false,
    onValueClick: (() -> Unit)? = null,
    labelGapUnits: Float = 1f,
    value: @Composable () -> Unit,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (label != null) {
            LightText(
                text = label.uppercase(),
                variant = LightTextVariant.Superfine,
                lighten = true,
                maxLines = 1,
                modifier = Modifier.padding(bottom = labelGapUnits.gridUnitsAsDp()),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperTarget(delta = -1, enabled = decrementEnabled, bigSteps = bigSteps, onStep = onStep, plus = false)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .let { m -> if (onValueClick != null) m.height(TARGET_UNITS.gridUnitsAsDp()).lightClickable(onClick = onValueClick) else m },
                contentAlignment = Alignment.Center,
            ) {
                // Measured at its own width, so a value too wide for the slot overlaps the targets
                // rather than wrapping and silently hiding its last digit ("182.4" as "182.").
                Box(modifier = Modifier.wrapContentWidth(unbounded = true)) { value() }
            }
            StepperTarget(delta = +1, enabled = incrementEnabled, bigSteps = bigSteps, onStep = onStep, plus = true)
        }
    }
}

@Composable
private fun StepperTarget(
    delta: Int,
    enabled: Boolean,
    bigSteps: Boolean,
    onStep: (Int) -> Unit,
    plus: Boolean,
) {
    val colors = LightThemeTokens.colors
    val currentOnStep by rememberUpdatedState(onStep)
    val currentBigSteps by rememberUpdatedState(bigSteps)

    val glyphColor = if (enabled) colors.content else colors.contentSecondary
    Box(
        modifier = Modifier
            .size(TARGET_UNITS.gridUnitsAsDp())
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .border(1.5.dp, colors.contentSecondary)
            // The SDK's buzz on finger-down, as on every other control (when LightOS haptics are on).
            // Only the handler below steps; it sits inside, so it sees each touch first.
            .lightClickable(enabled = enabled, onClick = {})
            // Steps come from the gesture itself, never from a redraw that might miss a quick tap.
            // Keyed on enabled: reaching a limit restarts the handler, which ends a hold there.
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    when (withTimeoutOrNull(HOLD_INITIAL_DELAY_MS) { awaitPressEnd(down, slopCancels = true) }) {
                        PressEnd.LIFTED -> currentOnStep(delta)
                        PressEnd.CANCELLED -> Unit
                        null -> {
                            var repeat = 0
                            do {
                                val hold = holdSchedule(repeat++, currentBigSteps)
                                currentOnStep(delta * hold.steps)
                            } while (withTimeoutOrNull(hold.delayMillis) { awaitPressEnd(down, slopCancels = false) } == null)
                        }
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
