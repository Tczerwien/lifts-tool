package com.tczerwien.lifts.screens.activeworkout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tczerwien.lifts.analytics.NotationStyle
import com.tczerwien.lifts.analytics.setNotation
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.Tracking
import com.tczerwien.lifts.session.RowValues
import com.tczerwien.lifts.session.SET_TYPE_WARMUP
import com.tczerwien.lifts.session.restOver
import com.tczerwien.lifts.session.restOverSec
import com.tczerwien.lifts.session.restRemainingSec
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.MonoDigits
import com.tczerwien.lifts.ui.Stepper
import com.tczerwien.lifts.ui.WeightUnit
import com.tczerwien.lifts.ui.rememberSecondClock
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** "1".."n" for work sets in order, "W" for warm-ups (design set-row numbering). */
internal fun buildMarkers(sets: List<SetEntity>): List<String> {
    var workIndex = 0
    return sets.map { set ->
        if (set.type == SET_TYPE_WARMUP) "W" else (++workIndex).toString()
    }
}

/**
 * "135 × 8" from a previous-performance set ([setNotation]); "× 8" for a pull-up, "-30 × 8" with
 * help, "45s" for a hold; null for nothing. Only the numbers the exercise records now ([tracking]).
 */
internal fun previousSummary(previous: SetEntity?, units: WeightUnit, tracking: Tracking): String? =
    previous?.let {
        setNotation(
            weightKg = it.weightKg?.takeIf { tracking.takesWeight },
            reps = it.reps?.takeIf { tracking.takesReps },
            unit = units,
            style = NotationStyle.SINGLE,
            durationSec = it.durationSec?.takeIf { tracking.takesDuration },
        )
    }

/** The row the set list opens at when it is built: the one above the active row, so the set just done shows too. */
internal fun openingListIndex(activeIndex: Int): Int = (activeIndex - 1).coerceAtLeast(0)

/**
 * Where the set list scrolls when the active row changes to [activeIndex], or null to stay put.
 * Null while the row is fully on screen ([firstFullyVisible] to [lastFullyVisible]), so tapping a
 * row in view never jumps the list; otherwise the row above it goes to the top, as when opening.
 */
internal fun activeRowScrollTarget(activeIndex: Int, firstFullyVisible: Int?, lastFullyVisible: Int?): Int? = when {
    activeIndex < 0 -> null
    firstFullyVisible != null && lastFullyVisible != null && activeIndex in firstFullyVisible..lastFullyVisible -> null
    else -> openingListIndex(activeIndex)
}

/**
 * Whether scrolling to [target] moves the rows from where they are ([firstVisibleIndex] at the top,
 * [firstVisibleOffset] px scrolled into it): right after a tick, a double tap's second tap then lands
 * on another row ([ActiveWorkoutViewModel.rowsScrolled]). A list built on the target (a new exercise)
 * doesn't move.
 */
internal fun scrollMovesRows(target: Int?, firstVisibleIndex: Int, firstVisibleOffset: Int): Boolean =
    target != null && (target != firstVisibleIndex || firstVisibleOffset != 0)

/** The swap icon's target: this wide, as tall as the name, at the end of the name's row. */
private const val SWAP_TARGET_UNITS = 3f
private const val SWAP_ICON_UNITS = 1.2f

/** The space kept between the name (Exercise Detail's target) and the swap icon's own target. */
private const val SWAP_GAP_UNITS = 1f

/**
 * The exercise's name, the equipment and muscles under it, the template's target ("8–12 REPS · RIR 1 ·
 * ADD LOAD") and its pinned cue. While a row is being logged ([logging]) the equipment and muscles line
 * gives its height to the set list; the target and the cue stay. The whole header is one tap target
 * for Exercise Detail ([onOpenDetail]), which the "›" signals, except the swap icon at the end of the
 * name's row ([onSwap], only while the exercise has another choice to swap to and nothing ticked): its own
 * target, kept apart from the name, and no taller than the name, so the rows below never move as it
 * comes and goes.
 */
@Composable
internal fun ExerciseHeader(
    block: ActiveWorkoutViewModel.ExerciseBlock,
    logging: Boolean,
    onOpenDetail: () -> Unit,
    onSwap: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onOpenDetail)
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                // Short names for the common lifts ([displayName]); anything longer is cut at the end.
                LightText(
                    text = block.name,
                    variant = LightTextVariant.Copy,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                LightText(text = " ›", variant = LightTextVariant.Copy, lighten = true, maxLines = 1)
            }
            if (onSwap != null) {
                Spacer(modifier = Modifier.width(SWAP_GAP_UNITS.gridUnitsAsDp()))
                Box(
                    modifier = Modifier
                        .width(SWAP_TARGET_UNITS.gridUnitsAsDp())
                        .fillMaxHeight()
                        .lightClickable(onClick = onSwap),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    LightIcon(icon = LightIcons.LOOP, size = SWAP_ICON_UNITS, contentDescription = "swap exercise")
                }
            }
        }
        if (!logging && block.detailLine.isNotEmpty()) {
            LightText(
                text = block.detailLine,
                variant = LightTextVariant.Detail,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        block.targetLine?.let { target ->
            // Full contrast, unlike the cue: it is what today's sets aim at.
            LightText(
                text = target,
                variant = LightTextVariant.Superfine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        block.cue?.let { cue ->
            LightText(
                text = "CUE: $cue".uppercase(),
                variant = LightTextVariant.Superfine,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 3gu "+ ADD SET" row. The design shows 2gu, but the row lives inside the lazy list and
 * LightLazyScrollView's scrollbar math requires every item at the uniform 3gu height.
 */
@Composable
internal fun AddSetRow(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3f.gridUnitsAsDp())
            .lightClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = "+ ADD SET", variant = LightTextVariant.Button, lighten = true)
    }
}

/**
 * Fixed strip between the set list and the rest slot, set by how the exercise is measured
 * ([tracking]): weight and reps ("Added" for a dip belt, "Assist" for the help on an assisted
 * machine, shown as the number on its stack), reps alone, or a hold's seconds (±5 s). With no active
 * row (every set ticked) it stays at full height with its steppers greyed out, so nothing below the
 * list moves. The weight stepper gets the wider slot (1.2 to 1) so "102.5" fits between its 4gu
 * targets and the reps slot still holds "100"; a lone stepper takes the middle at that width.
 */
@Composable
internal fun StepperStrip(
    viewModel: ActiveWorkoutViewModel,
    uiState: ActiveWorkoutViewModel.UiState,
    tracking: Tracking,
    activeSet: SetEntity?,
    /** The active row's starting values; shown dimmed where the row has none of its own. */
    seed: RowValues?,
    /** One side at a time: the count stepper says so ("Reps/side"). */
    perSide: Boolean = false,
) {
    val active = activeSet != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (tracking.takesWeight) {
            val weightKg = tracking.shownWeight(activeSet?.weightKg ?: seed?.weightKg)
            val label = when (tracking) {
                Tracking.WEIGHTED_BODYWEIGHT -> "Added"
                Tracking.ASSISTED_BODYWEIGHT -> "Assist"
                else -> "Weight"
            }
            Stepper(
                onStep = viewModel::stepWeight,
                bigSteps = true,
                modifier = Modifier.weight(1.2f),
                label = "$label (${uiState.units.suffix})",
                decrementEnabled = active && viewModel.weightDecrementEnabled(uiState),
                incrementEnabled = active,
                // A quarter unit under the label, not one: most of a set row back while resting (B11).
                labelGapUnits = STRIP_LABEL_GAP_UNITS,
                // A tap on the number types one in: the sled, the odd dumbbell.
                onValueClick = viewModel::openWeightEntry.takeIf { active },
            ) {
                // Digits monospace, the point not: "103.75" kg in full monospace ran over the steppers.
                MonoDigits(
                    text = weightKg?.let { Format.weight(it, uiState.units) } ?: "—",
                    variant = LightTextVariant.Copy,
                    lighten = activeSet?.weightKg == null,
                )
            }
        } else {
            Spacer(modifier = Modifier.weight(LONE_STEPPER_MARGIN))
        }
        if (tracking.takesDuration) {
            val seconds = activeSet?.durationSec ?: seed?.durationSec
            Stepper(
                onStep = viewModel::stepDuration,
                bigSteps = true,
                modifier = Modifier.weight(1.2f),
                label = if (perSide) "Time/side (sec)" else "Time (sec)",
                decrementEnabled = active && viewModel.durationDecrementEnabled(uiState),
                incrementEnabled = active,
                // A quarter unit under the label, not one: most of a set row back while resting (B11).
                labelGapUnits = STRIP_LABEL_GAP_UNITS,
            ) {
                LightText(
                    text = seconds?.toString() ?: "—",
                    variant = LightTextVariant.Copy,
                    lighten = activeSet?.durationSec == null,
                    monospace = true,
                    maxLines = 1,
                )
            }
        } else {
            val reps = activeSet?.reps ?: seed?.reps
            Stepper(
                onStep = viewModel::stepReps,
                modifier = Modifier.weight(if (tracking.takesWeight) 1f else 1.2f),
                label = if (perSide) "Reps/side" else "Reps",
                decrementEnabled = active && viewModel.repsDecrementEnabled(uiState),
                incrementEnabled = active,
                // A quarter unit under the label, not one: most of a set row back while resting (B11).
                labelGapUnits = STRIP_LABEL_GAP_UNITS,
            ) {
                LightText(
                    text = reps?.toString() ?: "—",
                    variant = LightTextVariant.Copy,
                    lighten = activeSet?.reps == null,
                    monospace = true,
                    maxLines = 1,
                )
            }
        }
        if (!tracking.takesWeight) Spacer(modifier = Modifier.weight(LONE_STEPPER_MARGIN))
    }
}

/** Each side of a lone stepper (reps only, a hold): it sits in the middle at the weight stepper's width. */
private const val LONE_STEPPER_MARGIN = 0.5f

/** The gap under the strip's stepper labels (B11: 0.25gu, from 1gu). */
private const val STRIP_LABEL_GAP_UNITS = 0.25f

/**
 * Collapsed next-exercise row (2gu + 0.5gu top margin), "NEXT › Overhead Press · 0/3": the exercise
 * it moves to ([ActiveWorkoutViewModel.nextExercise], finished ones skipped) and its sets ticked of all.
 */
@Composable
internal fun NextExerciseRow(name: String, doneCount: Int, setCount: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 0.5f.gridUnitsAsDp())
            .height(2f.gridUnitsAsDp())
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(text = "NEXT › ", variant = LightTextVariant.Detail, lighten = true, maxLines = 1)
        LightText(
            text = name,
            variant = LightTextVariant.Copy,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        LightText(text = " · $doneCount/$setCount", variant = LightTextVariant.Detail, lighten = true, maxLines = 1)
    }
}

/**
 * The 2gu rest slot above the bottom bar, always the same height so nothing above it moves:
 * - a [hint] ("TAP AGAIN TO FINISH · 3 SETS NOT LOGGED", "SET WEIGHT OR REPS FIRST") takes the
 *   slot for the few seconds it shows;
 * - while the rest ending at [endMillis] runs, the countdown ("Rest 1:23 · TAP FOR TIMER"); a tap
 *   opens the timer screen;
 * - once it has ended, how long ago ("REST OVER · 1:20 AGO", [restOver]), until the next tick, Skip
 *   or Finish; so a rest that ended with the screen off still says so when he looks;
 * - with no rest saved, blank.
 * Only the countdown takes taps. The clock ticks here, so only the slot redraws.
 */
@Composable
internal fun RestSlot(endMillis: Long?, hint: String?, onOpenTimer: () -> Unit) {
    val nowMillis = endMillis?.let { end -> rememberSecondClock(anchor = end).value }
    val remainingSec = if (endMillis != null && nowMillis != null) restRemainingSec(endMillis, nowMillis) else 0
    // Seconds since the end once the rest is over; null while it runs or with none saved.
    val overSec = if (endMillis != null && nowMillis != null && restOver(endMillis, nowMillis)) {
        restOverSec(endMillis, nowMillis)
    } else {
        null
    }
    val over = overSec != null && hint == null
    val counting = remainingSec > 0 && hint == null
    val showing = hint != null || counting || over
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(if (showing) LightThemeTokens.colors.contentSecondary else Color.Transparent),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(2f.gridUnitsAsDp())
                .let { if (counting) it.lightClickable(onClick = onOpenTimer) else it }
                .padding(horizontal = 1f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (counting) Arrangement.SpaceBetween else Arrangement.Center,
        ) {
            when {
                hint != null -> LightText(text = hint, variant = LightTextVariant.Superfine, maxLines = 1)
                counting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LightText(text = "Rest ", variant = LightTextVariant.Detail)
                        LightText(
                            text = Format.clock(remainingSec),
                            variant = LightTextVariant.Detail,
                            monospace = true,
                        )
                    }
                    LightText(
                        text = "TAP FOR TIMER",
                        variant = LightTextVariant.Superfine,
                        lighten = true,
                    )
                }
                // Only the number is monospace, like the countdown's, so the words hold still.
                overSec != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    LightText(text = "REST OVER · ", variant = LightTextVariant.Detail, maxLines = 1)
                    LightText(text = Format.ago(overSec), variant = LightTextVariant.Detail, monospace = true, maxLines = 1)
                    LightText(text = " AGO", variant = LightTextVariant.Detail, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun EmptyWorkoutBody(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LightText(
                text = "No exercises yet",
                variant = LightTextVariant.Copy,
                lighten = true,
            )
            LightText(
                text = "ADD ONE BELOW",
                variant = LightTextVariant.Superfine,
                lighten = true,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}
