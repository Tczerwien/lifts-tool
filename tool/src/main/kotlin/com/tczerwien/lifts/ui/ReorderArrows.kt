package com.tczerwien.lifts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** Each arrow's target is this wide and as tall as the row. */
private const val REORDER_TARGET_WIDTH_UNITS = 2.5f
private const val REORDER_GAP_UNITS = 0.25f
private const val REORDER_ICON_UNITS = 0.9f
private const val REORDER_DISABLED_ALPHA = 0.4f

/**
 * Up and down arrows side by side at a row's end (Templates, Template Editor), each a 2.5gu target the
 * row's full height with a gap between: stacked 1.5gu targets, touching, took "down" for "up". Disabled
 * arrows dim to 40%. The row passes a fixed height, which the targets fill.
 */
@Composable
fun ReorderArrows(
    upEnabled: Boolean,
    downEnabled: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxHeight(),
        horizontalArrangement = Arrangement.spacedBy(REORDER_GAP_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReorderArrow(icon = LightIcons.UP, enabled = upEnabled, description = "move up", onClick = onUp)
        ReorderArrow(icon = LightIcons.DOWN, enabled = downEnabled, description = "move down", onClick = onDown)
    }
}

@Composable
private fun ReorderArrow(icon: LightIconConfiguration, enabled: Boolean, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(REORDER_TARGET_WIDTH_UNITS.gridUnitsAsDp())
            .fillMaxHeight()
            .alpha(if (enabled) 1f else REORDER_DISABLED_ALPHA)
            .lightClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LightIcon(icon = icon, size = REORDER_ICON_UNITS, contentDescription = description)
    }
}
