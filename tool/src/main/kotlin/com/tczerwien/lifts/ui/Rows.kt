package com.tczerwien.lifts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

private const val LIST_ROW_HEIGHT_UNITS = 3f

/**
 * A row with a name over a detail line: the SDK's line heights put the pair at about 3gu, so in a 3gu
 * row the detail sat on the divider. 4gu clears it (and leaves room at a larger text size).
 */
const val TWO_LINE_ROW_HEIGHT_UNITS = 4f
private const val TAB_ROW_HEIGHT_UNITS = 2f
private const val DIVIDER_ALPHA = 0.3f

/** The gap kept between a row's left and right slots. */
private const val ROW_SLOT_GAP_UNITS = 0.5f

/** Which slot of a [LiftsListRow] keeps its full width when both don't fit; the other is cut. */
enum class KeepWhole { LEFT, RIGHT }

/**
 * Uniform 3gu list row with left/right slots and 1gu horizontal padding
 * (tokens.css .listrow). Safe inside LightLazyScrollView(uniformItemHeightGridUnits = 3f).
 * Two-line rows pass [heightUnits] = [TWO_LINE_ROW_HEIGHT_UNITS].
 * By default the right slot is measured first and keeps its full width; the left takes what is left,
 * less a 0.5gu gap, so a long name is cut ([RowName]) instead of pushing "5 exercises" off the row. A
 * row whose right side is the one to give (a long hint beside a short title) passes [keepWhole] LEFT.
 */
@Composable
fun LiftsListRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    heightUnits: Float = LIST_ROW_HEIGHT_UNITS,
    keepWhole: KeepWhole = KeepWhole.RIGHT,
    right: (@Composable () -> Unit)? = null,
    left: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(heightUnits.gridUnitsAsDp())
            .let { m -> if (onClick != null) m.lightClickable(onClick = onClick) else m }
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // The weighted slot is measured last and gets what the other leaves.
        val cut = Modifier.weight(1f, fill = false)
        Box(modifier = if (keepWhole == KeepWhole.RIGHT) cut else Modifier, contentAlignment = Alignment.CenterStart) {
            left()
        }
        if (right != null) {
            Spacer(modifier = Modifier.width(ROW_SLOT_GAP_UNITS.gridUnitsAsDp()))
            Box(modifier = if (keepWhole == KeepWhole.LEFT) cut else Modifier, contentAlignment = Alignment.CenterEnd) {
                right()
            }
        }
    }
}

/**
 * A name in a list row: one line of Copy, cut with an ellipsis. The SDK's LightText clips at a word
 * by default, so a cut name looked whole ("Barbell Bench Press -"); the ellipsis shows it was cut.
 */
@Composable
fun RowName(text: String, modifier: Modifier = Modifier, lighten: Boolean = false) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        lighten = lighten,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 1px contentSecondary divider at 30% alpha, inset 1gu on both sides (tokens.css .rowdivider). */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp())
            .height(1.dp)
            .background(LightThemeTokens.colors.contentSecondary.copy(alpha = DIVIDER_ALPHA)),
    )
}

/**
 * Field row (tokens.css .fieldrow): superfine contentSecondary label above an underlined
 * value row, with an optional right chevron. Tap opens the editor/picker for the field.
 */
@Composable
fun LiftsFieldRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .let { m -> if (onClick != null) m.lightClickable(onClick = onClick) else m }
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.6f.gridUnitsAsDp()),
    ) {
        LightText(
            text = label.uppercase(),
            variant = LightTextVariant.Superfine,
            lighten = true,
            maxLines = 1,
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 0.2f.gridUnitsAsDp()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LightText(
                    text = value,
                    variant = LightTextVariant.Copy,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (chevron) {
                    LightIcon(icon = LightIcons.ARROW_RIGHT, size = 1.6f)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LightThemeTokens.colors.contentSecondary),
            )
        }
    }
}

/** Superfine contentSecondary section label with 1gu/0.5gu padding (tokens.css .section-label). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 1f.gridUnitsAsDp(),
                end = 1f.gridUnitsAsDp(),
                top = 1f.gridUnitsAsDp(),
                bottom = 0.5f.gridUnitsAsDp(),
            ),
    ) {
        LightText(
            text = text.uppercase(),
            variant = LightTextVariant.Superfine,
            lighten = true,
            maxLines = 1,
        )
    }
}

/**
 * 2gu superfine tab row with 2px active underline; inactive tabs are contentSecondary
 * (tokens.css .tabrow). Used for per-screen modes, e.g. ABOUT / HISTORY / RECORDS.
 */
@Composable
fun LiftsTabRow(
    tabs: List<String>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TAB_ROW_HEIGHT_UNITS.gridUnitsAsDp())
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.5f.gridUnitsAsDp()),
    ) {
        tabs.forEachIndexed { index, tab ->
            val active = index == activeIndex
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .lightClickable { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LightText(
                    text = tab.uppercase(),
                    variant = LightTextVariant.Superfine,
                    lighten = !active,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 0.2f.gridUnitsAsDp()),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(
                            if (active) {
                                LightThemeTokens.colors.content
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            },
                        ),
                )
            }
        }
    }
}
