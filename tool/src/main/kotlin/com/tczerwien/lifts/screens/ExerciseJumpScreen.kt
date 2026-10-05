package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.MonoDigits
import com.tczerwien.lifts.ui.SupersetBar
import com.tczerwien.lifts.ui.supersetBar
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * One exercise of the workout as the list shows it: its name, how many of its work sets are ticked
 * (warm-ups and drops left out: [com.tczerwien.lifts.session.workSetProgress]), whether every row of it is
 * ticked ([finished]: a drop still to do keeps it open), and where it sits in a superset ([bar]).
 */
data class JumpEntry(
    val slotId: Long,
    val name: String,
    val doneCount: Int,
    val setCount: Int,
    val bar: SupersetBar = SupersetBar.NONE,
    val finished: Boolean,
)

class ExerciseJumpViewModel : LightViewModel<Long>()

/**
 * The workout's exercises in order, opened by tapping Active Workout's title, so any exercise is one
 * tap away rather than a trip round the next-exercise row. Numbered like set markers, the one on
 * screen inverted, the finished ones dimmed, a superset's rows barred down their left edge. Returns
 * the chosen exercise's slot id; Back returns nothing.
 */
class ExerciseJumpScreen(
    private val sealedActivity: SealedLightActivity,
    private val entries: List<JumpEntry>,
    /** The exercise Active Workout shows. */
    private val currentSlotId: Long?,
) : LightScreen<Long, ExerciseJumpViewModel>(sealedActivity) {

    override val viewModelClass: Class<ExerciseJumpViewModel>
        get() = ExerciseJumpViewModel::class.java

    override fun createViewModel() = ExerciseJumpViewModel()

    @Composable
    override fun Content() {
        LiftsFrame(sealedActivity, lightContext) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text("EXERCISES"),
                )
                LightLazyScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    scrollBarPosition = LightScrollBarPosition.Inside,
                    uniformItemHeightGridUnits = ROW_UNITS,
                ) {
                    itemsIndexed(entries, key = { _, entry -> entry.slotId }) { index, entry ->
                        JumpRow(
                            number = index + 1,
                            entry = entry,
                            current = entry.slotId == currentSlotId,
                            onClick = { goBack(entry.slotId) },
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val ROW_UNITS = 3f
    }
}

/** 3gu row: number (inverted for the exercise on screen), name, "2/3" work sets ticked. Clear of the scrollbar's 2gu. */
@Composable
private fun JumpRow(number: Int, entry: JumpEntry, current: Boolean, onClick: () -> Unit) {
    val colors = LightThemeTokens.colors
    val finished = entry.finished
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(3f.gridUnitsAsDp())
            .supersetBar(entry.bar, colors.content)
            .lightClickable(onClick = onClick)
            .padding(start = 1f.gridUnitsAsDp(), end = 2f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(2f.gridUnitsAsDp()), contentAlignment = Alignment.Center) {
            LightText(
                text = "$number",
                variant = LightTextVariant.Detail,
                lighten = !current,
                color = if (current) colors.background else null,
                maxLines = 1,
                modifier = Modifier
                    .let { m -> if (current) m.background(colors.content) else m }
                    .padding(horizontal = 0.3f.gridUnitsAsDp()),
            )
        }
        LightText(
            text = entry.name,
            variant = LightTextVariant.Copy,
            lighten = finished && !current,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 0.5f.gridUnitsAsDp()),
        )
        MonoDigits(
            text = "${entry.doneCount}/${entry.setCount}",
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
        )
    }
}
