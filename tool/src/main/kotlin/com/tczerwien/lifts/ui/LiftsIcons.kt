package com.tczerwien.lifts.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Canvas-drawn icons ONLY for concepts LightIcons lacks: bar chart (consistency) and
 * bodyweight scale. Thin strokes on a 24-unit design box, content color. Exposed both as
 * [Painter]s (for LightBarButton.Icon bottom-bar items) and as plain composables.
 */

private const val STROKE_DESIGN_UNITS = 1.6f

/** Three vertical lines of increasing height (design home.html consistency glyph). */
private class BarChartIconPainter(private val color: Color) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() {
        val u = size.minDimension / 24f
        val stroke = STROKE_DESIGN_UNITS * u
        drawLine(color, Offset(6f * u, 19f * u), Offset(6f * u, 12f * u), stroke, StrokeCap.Round)
        drawLine(color, Offset(12f * u, 19f * u), Offset(12f * u, 6f * u), stroke, StrokeCap.Round)
        drawLine(color, Offset(18f * u, 19f * u), Offset(18f * u, 9f * u), stroke, StrokeCap.Round)
    }
}

/** Square bathroom scale with a dial arc and needle tick (design home.html bodyweight glyph). */
private class ScaleIconPainter(private val color: Color) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = STROKE_DESIGN_UNITS * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Body: 15x15 square at (4.5, 4.5).
        drawPath(
            path = Path().apply {
                moveTo(4.5f * u, 4.5f * u)
                lineTo(19.5f * u, 4.5f * u)
                lineTo(19.5f * u, 19.5f * u)
                lineTo(4.5f * u, 19.5f * u)
                close()
            },
            color = color,
            style = stroke,
        )
        // Dial arc: (9.5,9) bulging up to (14.5,9), approximated with a quadratic curve.
        drawPath(
            path = Path().apply {
                moveTo(9.5f * u, 9f * u)
                quadraticBezierTo(12f * u, 6.4f * u, 14.5f * u, 9f * u)
            },
            color = color,
            style = stroke,
        )
        // Needle tick.
        drawLine(
            color = color,
            start = Offset(12f * u, 9f * u),
            end = Offset(12f * u, 7.5f * u),
            strokeWidth = STROKE_DESIGN_UNITS * u,
            cap = StrokeCap.Round,
        )
    }
}

/** Painter for LightBarButton.Icon bottom-bar items. Defaults to the theme content color. */
@Composable
fun rememberBarChartIconPainter(color: Color = LightThemeTokens.colors.content): Painter =
    remember(color) { BarChartIconPainter(color) }

/** Painter for LightBarButton.Icon bottom-bar items. Defaults to the theme content color. */
@Composable
fun rememberScaleIconPainter(color: Color = LightThemeTokens.colors.content): Painter =
    remember(color) { ScaleIconPainter(color) }

/** Inline bar-chart icon, sized in grid units like LightIcon. */
@Composable
fun LiftsBarChartIcon(
    modifier: Modifier = Modifier,
    sizeUnits: Float = 2f,
    color: Color = LightThemeTokens.colors.content,
) {
    Image(
        painter = rememberBarChartIconPainter(color),
        contentDescription = "consistency",
        modifier = modifier.size(sizeUnits.gridUnitsAsDp()),
    )
}

/** Inline bodyweight-scale icon, sized in grid units like LightIcon. */
@Composable
fun LiftsScaleIcon(
    modifier: Modifier = Modifier,
    sizeUnits: Float = 2f,
    color: Color = LightThemeTokens.colors.content,
) {
    Image(
        painter = rememberScaleIconPainter(color),
        contentDescription = "bodyweight",
        modifier = modifier.size(sizeUnits.gridUnitsAsDp()),
    )
}
