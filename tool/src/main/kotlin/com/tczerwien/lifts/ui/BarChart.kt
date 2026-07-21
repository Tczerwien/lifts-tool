package com.tczerwien.lifts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

private const val CHART_HEIGHT_UNITS = 8f
private const val LABEL_ROW_HEIGHT_UNITS = 1f
private const val BAR_GAP_UNITS = 0.35f
private const val ZERO_STUB_ALPHA = 0.5f

/**
 * Monochrome bar chart (tokens.css .barchart): 8gu tall, bottom-aligned flex bars with a
 * 0.35gu gap. Zero values render a 2px contentSecondary stub. An optional 1gu x-axis
 * label row renders beneath; use Modifier.weight(1f) per label to align with the bars.
 */
@Composable
fun LiftsBarChart(
    values: List<Int>,
    maxValue: Int,
    modifier: Modifier = Modifier,
    xAxisLabels: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = LightThemeTokens.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT_UNITS.gridUnitsAsDp())
                .padding(horizontal = 1f.gridUnitsAsDp()),
            horizontalArrangement = Arrangement.spacedBy(BAR_GAP_UNITS.gridUnitsAsDp()),
            verticalAlignment = Alignment.Bottom,
        ) {
            values.forEach { value ->
                if (value <= 0 || maxValue <= 0) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp)
                            .background(colors.contentSecondary.copy(alpha = ZERO_STUB_ALPHA)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(fraction = (value.toFloat() / maxValue).coerceIn(0f, 1f))
                            .background(colors.content),
                    )
                }
            }
        }
        if (xAxisLabels != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LABEL_ROW_HEIGHT_UNITS.gridUnitsAsDp())
                    .padding(horizontal = 1f.gridUnitsAsDp()),
                horizontalArrangement = Arrangement.spacedBy(BAR_GAP_UNITS.gridUnitsAsDp()),
                verticalAlignment = Alignment.CenterVertically,
                content = xAxisLabels,
            )
        }
    }
}
