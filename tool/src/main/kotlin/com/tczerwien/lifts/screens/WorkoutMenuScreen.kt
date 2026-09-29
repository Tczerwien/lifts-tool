package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.ui.ArmedAction
import com.tczerwien.lifts.ui.KeepWhole
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.RowDivider
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter

/** What the lifter picked in the Active Workout "…" menu. */
enum class WorkoutMenuAction { REMOVE_SET, REMOVE_EXERCISE, DISCARD_WORKOUT }

class WorkoutMenuViewModel : LightViewModel<WorkoutMenuAction>() {

    /** Discard takes two taps (see [ArmedAction]). */
    val discardArm = ArmedAction(viewModelScope)

    /** Back cancels an armed Discard before it leaves the menu. */
    override fun onBackPressed(): Boolean {
        if (!discardArm.armed.value) return false
        discardArm.disarm()
        return true
    }
}

/**
 * The Active Workout "…" menu. Remove set and Remove exercise are offered only while nothing in
 * them is logged (a null label greys the row out); Discard workout takes two taps. Returns the
 * chosen action, which Active Workout carries out; Back returns nothing.
 */
class WorkoutMenuScreen(
    private val sealedActivity: SealedLightActivity,
    /** The active unticked row as its marker names it ("Set 2", "Warm-up"), or null. */
    private val removeSetLabel: String?,
    /** The current exercise's name while none of its sets is ticked, or null. */
    private val removeExerciseLabel: String?,
) : LightScreen<WorkoutMenuAction, WorkoutMenuViewModel>(sealedActivity) {

    override val viewModelClass: Class<WorkoutMenuViewModel>
        get() = WorkoutMenuViewModel::class.java

    override fun createViewModel() = WorkoutMenuViewModel()

    @Composable
    override fun Content() {
        val discardArmed by viewModel.discardArm.armed.collectAsState()

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
                    center = LightTopBarCenter.Text("WORKOUT"),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    MenuRow(
                        title = "Remove set",
                        detail = removeSetLabel ?: "UNTICKED SETS ONLY",
                        enabled = removeSetLabel != null,
                        onClick = { choose(WorkoutMenuAction.REMOVE_SET) },
                    )
                    RowDivider()
                    MenuRow(
                        title = "Remove exercise",
                        detail = removeExerciseLabel ?: "ONLY WITH NOTHING TICKED",
                        enabled = removeExerciseLabel != null,
                        onClick = { choose(WorkoutMenuAction.REMOVE_EXERCISE) },
                    )
                    RowDivider()
                    MenuRow(
                        title = "Discard workout",
                        detail = if (discardArmed) "TAP AGAIN TO DISCARD" else null,
                        enabled = true,
                        onClick = {
                            if (viewModel.discardArm.tap()) goBack(WorkoutMenuAction.DISCARD_WORKOUT)
                        },
                    )
                }
            }
        }
    }

    /** Leaves with [action]; an armed Discard is dropped first so Back handling can't swallow it. */
    private fun choose(action: WorkoutMenuAction) {
        viewModel.discardArm.disarm()
        goBack(action)
    }
}

@Composable
private fun MenuRow(title: String, detail: String?, enabled: Boolean, onClick: () -> Unit) {
    LiftsListRow(
        onClick = if (enabled) onClick else null,
        // The title says what the row does; the detail beside it is the part cut when both don't fit.
        keepWhole = KeepWhole.LEFT,
        left = {
            LightText(
                text = title,
                variant = LightTextVariant.Copy,
                lighten = !enabled,
                maxLines = 1,
            )
        },
        right = detail?.let {
            {
                LightText(
                    text = it,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}
