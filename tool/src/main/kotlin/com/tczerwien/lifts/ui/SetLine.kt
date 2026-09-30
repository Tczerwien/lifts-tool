package com.tczerwien.lifts.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.tczerwien.lifts.analytics.SetRun
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Sets as Detail text, "w95×10; 140×8; 165×5☆; 165×4" (compactNotation's runs, "225×5 ×3"), in the
 * normal font: monospace gave every "×" and ";" a whole cell ("w61×5;  102×5") and fit fewer sets. A PR
 * star is the outline star icon, inline, always in the content colour so it stands out on [lighten]
 * text. It wraps; past [maxLines] it ends in an ellipsis.
 */
@Composable
fun SetLine(
    runs: List<SetRun>,
    modifier: Modifier = Modifier,
    lighten: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign = TextAlign.Start,
) {
    val colors = LightThemeTokens.colors
    val style = detailStyle().copy(
        color = if (lighten) colors.contentSecondary else colors.content,
        textAlign = textAlign,
    )
    val starSizeDp = with(LocalDensity.current) { style.fontSize.toDp() }
    val starSizeUnits = starSizeDp.value / 1f.gridUnitsAsDp().value
    val inlineContent = mapOf(
        PR_STAR_INLINE_ID to InlineTextContent(
            Placeholder(
                width = style.fontSize,
                height = style.fontSize,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) {
            Box(
                modifier = Modifier.size(starSizeDp),
                contentAlignment = Alignment.Center,
            ) {
                LightIcon(
                    icon = LightIcons.STAR_OUTLINE,
                    size = starSizeUnits,
                    contentDescription = "PR",
                )
            }
        },
    )
    BasicText(
        text = buildAnnotatedString {
            runs.forEachIndexed { index, run ->
                if (index > 0) append("; ")
                append(run.text)
                if (run.pr) appendInlineContent(PR_STAR_INLINE_ID, "*")
                append(run.repeat)
            }
        },
        style = style,
        inlineContent = inlineContent,
        maxLines = maxLines,
        overflow = if (maxLines < Int.MAX_VALUE) TextOverflow.Ellipsis else TextOverflow.Clip,
        modifier = modifier,
    )
}

private const val PR_STAR_INLINE_ID = "pr"

/** The Detail variant's style, scaled like LightText scales its variants. */
@Composable
private fun detailStyle(): TextStyle {
    val base = LightThemeTokens.typography.detail
    return base.copy(
        fontSize = base.fontSize.value.designVerticalPxToSp(),
        lineHeight = (base.fontSize.value * 1.25f).designVerticalPxToSp(),
    )
}
