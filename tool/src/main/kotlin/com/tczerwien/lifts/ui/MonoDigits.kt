package com.tczerwien.lifts.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant

/**
 * [text] with its digit runs in monospace and everything between them (",", ".", ":", "×", spaces,
 * words) in the normal font, on one baseline: "3,330" and "62.5 × 5" read as the mockups draw them,
 * without the whole cell monospace gives a separator ("3 , 330", "62 . 5"). For the numbers in a
 * summary (Workout Complete, Consistency). Columns and values that change while he watches (set rows,
 * steppers, clocks) stay all monospace; dates, names and set lines take the normal font.
 */
@Composable
fun MonoDigits(text: String, variant: LightTextVariant, modifier: Modifier = Modifier, lighten: Boolean = false) {
    Row(modifier = modifier) {
        digitRuns(text).forEach { run ->
            LightText(
                text = run,
                variant = variant,
                lighten = lighten,
                monospace = run.first().isDigit(),
                maxLines = 1,
                // The two fonts sit differently in their boxes: line them up on the text itself.
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

/** [text] cut into runs of digits and runs of anything else, in order: "3,330 lb" is "3", ",", "330", " lb". */
fun digitRuns(text: String): List<String> {
    val runs = mutableListOf<String>()
    var start = 0
    for (i in 1..text.length) {
        if (i == text.length || text[i].isDigit() != text[start].isDigit()) {
            runs += text.substring(start, i)
            start = i
        }
    }
    return runs
}
