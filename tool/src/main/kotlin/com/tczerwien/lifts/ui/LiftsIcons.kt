package com.tczerwien.lifts.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Canvas-drawn icons ONLY for concepts LightIcons lacks: bar chart (consistency) and bodyweight scale,
 * as [Painter]s for LightBarButton.Icon bottom-bar items. Content color, on a 24-unit design box, with
 * a stroke fixed in grid units ([BAR_ICON_STROKE_UNITS]) rather than a share of the glyph: the design
 * draws every icon with one stroke whatever its size (tokens.css: non-scaling-stroke).
 */

/** The stroke of the glyphs Lifts draws inside rows (checkmark, PR star): about 4 px; 2.4 px read faint. */
const val GLYPH_STROKE_UNITS = 0.1f

/** The stroke of the bottom-bar glyphs, beside Light's own icons: about 5.3 px, as at their 2gu before. */
const val BAR_ICON_STROKE_UNITS = 0.133f

/** [units] grid units in pixels, for a Canvas stroke. */
@Composable
fun strokePx(units: Float): Float = with(LocalDensity.current) { units.gridUnitsAsDp().toPx() }

/** Three vertical lines of increasing height (design home.html consistency glyph). */
private class BarChartIconPainter(private val color: Color, private val stroke: Float) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() {
        val u = size.minDimension / 24f
        drawLine(color, Offset(6f * u, 19f * u), Offset(6f * u, 12f * u), stroke, StrokeCap.Round)
        drawLine(color, Offset(12f * u, 19f * u), Offset(12f * u, 6f * u), stroke, StrokeCap.Round)
        drawLine(color, Offset(18f * u, 19f * u), Offset(18f * u, 9f * u), stroke, StrokeCap.Round)
    }
}

/** Square bathroom scale with a dial arc and needle tick (design home.html bodyweight glyph). */
private class ScaleIconPainter(private val color: Color, private val strokeWidth: Float) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
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
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

/** Painter for LightBarButton.Icon bottom-bar items. Defaults to the theme content color. */
@Composable
fun rememberBarChartIconPainter(color: Color = LightThemeTokens.colors.content): Painter {
    val stroke = strokePx(BAR_ICON_STROKE_UNITS)
    return remember(color, stroke) { BarChartIconPainter(color, stroke) }
}

/** Painter for LightBarButton.Icon bottom-bar items. Defaults to the theme content color. */
@Composable
fun rememberScaleIconPainter(color: Color = LightThemeTokens.colors.content): Painter {
    val stroke = strokePx(BAR_ICON_STROKE_UNITS)
    return remember(color, stroke) { ScaleIconPainter(color, stroke) }
}
