package com.tczerwien.lifts.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.delay

private const val TARGET_UNITS = 4f
private const val GLYPH_UNITS = 2.2f
private const val HOLD_INITIAL_DELAY_MS = 400L
private const val HOLD_REPEAT_INTERVAL_MS = 250L // ~4 steps/s
private const val DISABLED_ALPHA = 0.4f

/**
 * +/- stepper (design/components/stepper.html): 4gu bordered square targets around a
 * caller-rendered value slot. Tap steps once; press-and-hold auto-repeats after ~400ms
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
    var pressed by remember { mutableStateOf(false) }

    LaunchedEffect(pressed, enabled) {
        if (pressed && enabled) {
            currentOnStep(delta)
            delay(HOLD_INITIAL_DELAY_MS)
            while (true) {
                currentOnStep(delta)
                delay(HOLD_REPEAT_INTERVAL_MS)
            }
        }
    }

    val glyphColor = if (enabled) colors.content else colors.contentSecondary
    Box(
        modifier = Modifier
            .size(TARGET_UNITS.gridUnitsAsDp())
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .border(1.5.dp, colors.contentSecondary)
            .pointerInput(enabled) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        try {
                            awaitRelease()
                        } finally {
                            pressed = false
                        }
                    },
                )
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
