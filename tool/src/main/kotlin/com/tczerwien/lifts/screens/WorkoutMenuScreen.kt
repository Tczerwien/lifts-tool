package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.ui.ArmedAction
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.KeepWhole
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.SectionLabel
import com.tczerwien.lifts.ui.Stepper
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the lifter picked in the Active Workout "…" menu; Active Workout carries it out. */
sealed interface WorkoutMenuResult {
    data object RemoveSet : WorkoutMenuResult

    data object RemoveExercise : WorkoutMenuResult

    data object DiscardWorkout : WorkoutMenuResult

    /** The exercise list, to go to another exercise. */
    data object Jump : WorkoutMenuResult

    /** Exercise Detail for the exercise on screen: history, records, cue, type. */
    data object ExerciseDetails : WorkoutMenuResult

    /** Rename the exercise, through Exercise Detail's name editor and its duplicate check. */
    data object RenameExercise : WorkoutMenuResult

    /** The Plate Calculator, on the weight the active row shows. */
    data object Plates : WorkoutMenuResult

    data class RenameWorkout(val name: String) : WorkoutMenuResult

    data class WorkoutNote(val text: String) : WorkoutMenuResult

    /** A note on the exercise on screen, for this workout only ("left shoulder twinge on set 3"). */
    data class ExerciseNote(val text: String) : WorkoutMenuResult

    /** This exercise's rest for its work sets, from its next tick. */
    data class Rest(val sec: Int) : WorkoutMenuResult
}

/**
 * What the menu shows, as Active Workout had it when the menu opened; the ids its result acts on
 * stay with Active Workout. [exerciseName] null (no exercise yet) leaves the exercise rows out.
 */
data class WorkoutMenuContent(
    val exerciseName: String? = null,
    val exerciseNote: String? = null,
    /** This exercise's work rest as it stands (its own, else Settings' default). */
    val restSec: Int = 0,
    val defaultRestSec: Int = 0,
    /** "225 lb": the weight the Plate Calculator would open on; null when it can't ([platesUnavailable]). */
    val platesFor: String? = null,
    /** Why there is no Plates for the exercise on screen, e.g. "SET A WEIGHT FIRST". */
    val platesUnavailable: String? = null,
    /** "2 OF 5": where the exercise on screen is in the workout. */
    val position: String? = null,
    /** The active unticked row as its marker names it ("Set 2", "Warm-up"), or null. */
    val removeSetLabel: String? = null,
    /** The current exercise's name while none of its sets is ticked, or null. */
    val removeExerciseLabel: String? = null,
    val workoutName: String = "",
    val workoutNote: String? = null,
)

class WorkoutMenuViewModel : LightViewModel<WorkoutMenuResult>() {

    enum class TextField { WORKOUT_NAME, WORKOUT_NOTE, EXERCISE_NOTE }

    sealed interface Mode {
        data object Rows : Mode

        /** The SDK editor for one text; [session] starts it fresh each time. */
        data class EditText(val field: TextField, val session: Int) : Mode

        /** The ±15 s stepper for this exercise's rest. */
        data class EditRest(val sec: Int) : Mode
    }

    private val _mode = MutableStateFlow<Mode>(Mode.Rows)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private var session = 0

    /** Discard takes two taps (see [ArmedAction]). */
    val discardArm = ArmedAction(viewModelScope)

    fun editText(field: TextField) {
        discardArm.disarm()
        _mode.value = Mode.EditText(field, ++session)
    }

    fun editRest(sec: Int) {
        discardArm.disarm()
        _mode.value = Mode.EditRest(sec)
    }

    /** ±15 s, from 0 (no rest: a superset's first half) to 15 min. */
    fun stepRest(delta: Int) {
        _mode.update { mode ->
            if (mode is Mode.EditRest) mode.copy(sec = (mode.sec + delta * REST_STEP_SEC).coerceIn(0, MAX_REST_SEC)) else mode
        }
    }

    /** Back to the rows; used before leaving with a result, so Back handling can't swallow it. */
    fun showRows() {
        _mode.value = Mode.Rows
    }

    /** Back closes an editor, or cancels an armed Discard, before it leaves the menu. */
    override fun onBackPressed(): Boolean {
        if (_mode.value != Mode.Rows) {
            showRows()
            return true
        }
        if (!discardArm.armed.value) return false
        discardArm.disarm()
        return true
    }

    companion object {
        const val REST_STEP_SEC = 15
        const val MAX_REST_SEC = 900
    }
}

/**
 * The Active Workout "…" menu. For the exercise on screen: its note for this workout, its rest,
 * plates for the weight the active row shows, its details, renaming it, and removing a set or the
 * exercise while nothing in them is logged (a null label greys the row out). For the workout: the
 * exercise list, renaming it, its note, and discarding it (two taps). Notes, the name and the rest
 * are edited here; the result goes back to Active Workout, which carries it out. Back returns nothing.
 */
class WorkoutMenuScreen(
    private val sealedActivity: SealedLightActivity,
    private val content: WorkoutMenuContent,
) : LightScreen<WorkoutMenuResult, WorkoutMenuViewModel>(sealedActivity) {

    override val viewModelClass: Class<WorkoutMenuViewModel>
        get() = WorkoutMenuViewModel::class.java

    override fun createViewModel() = WorkoutMenuViewModel()

    @Composable
    override fun Content() {
        val mode by viewModel.mode.collectAsState()

        LiftsFrame(sealedActivity, lightContext) {
            when (val current = mode) {
                WorkoutMenuViewModel.Mode.Rows -> Rows()
                is WorkoutMenuViewModel.Mode.EditText -> TextEditor(current)
                is WorkoutMenuViewModel.Mode.EditRest -> RestEditor(current.sec)
            }
        }
    }

    @Composable
    private fun Rows() {
        val discardArmed by viewModel.discardArm.armed.collectAsState()
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
                content.exerciseName?.let { exerciseName ->
                    SectionLabel(exerciseName)
                    MenuRow(
                        title = "Note",
                        detail = content.exerciseNote?.oneLine() ?: "FOR THIS WORKOUT",
                        onClick = { viewModel.editText(WorkoutMenuViewModel.TextField.EXERCISE_NOTE) },
                    )
                    RowDivider()
                    MenuRow(
                        title = "Rest",
                        detail = Format.clock(content.restSec) + if (content.restSec == content.defaultRestSec) " · DEFAULT" else "",
                        onClick = { viewModel.editRest(content.restSec) },
                    )
                    RowDivider()
                    MenuRow(
                        title = content.platesFor?.let { "Plates for $it" } ?: "Plates",
                        detail = content.platesUnavailable,
                        onClick = { choose(WorkoutMenuResult.Plates) }.takeIf { content.platesFor != null },
                    )
                    RowDivider()
                    MenuRow(title = "Details", detail = "HISTORY · RECORDS · TYPE", onClick = { choose(WorkoutMenuResult.ExerciseDetails) })
                    RowDivider()
                    MenuRow(title = "Rename", detail = exerciseName, onClick = { choose(WorkoutMenuResult.RenameExercise) })
                    RowDivider()
                    MenuRow(
                        title = "Remove set",
                        detail = content.removeSetLabel ?: "UNTICKED SETS ONLY",
                        onClick = { choose(WorkoutMenuResult.RemoveSet) }.takeIf { content.removeSetLabel != null },
                    )
                    RowDivider()
                    MenuRow(
                        title = "Remove exercise",
                        detail = if (content.removeExerciseLabel != null) null else "ONLY WITH NOTHING TICKED",
                        onClick = { choose(WorkoutMenuResult.RemoveExercise) }.takeIf { content.removeExerciseLabel != null },
                    )
                }

                SectionLabel("Workout")
                content.position?.let { position ->
                    MenuRow(title = "Go to exercise", detail = position, onClick = { choose(WorkoutMenuResult.Jump) })
                    RowDivider()
                }
                MenuRow(
                    title = "Rename",
                    detail = content.workoutName,
                    onClick = { viewModel.editText(WorkoutMenuViewModel.TextField.WORKOUT_NAME) },
                )
                RowDivider()
                MenuRow(
                    title = "Note",
                    detail = content.workoutNote?.oneLine() ?: "ADD ONE",
                    onClick = { viewModel.editText(WorkoutMenuViewModel.TextField.WORKOUT_NOTE) },
                )
                RowDivider()
                MenuRow(
                    title = "Discard workout",
                    detail = if (discardArmed) "TAP AGAIN TO DISCARD" else null,
                    onClick = {
                        if (viewModel.discardArm.tap()) goBack(WorkoutMenuResult.DiscardWorkout)
                    },
                )
            }
        }
    }

    /** The SDK editor for the workout's name, its note or the exercise's note, prefilled with it. */
    @Composable
    private fun TextEditor(mode: WorkoutMenuViewModel.Mode.EditText) {
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val (title, prefill) = when (mode.field) {
            WorkoutMenuViewModel.TextField.WORKOUT_NAME -> "Workout Name" to content.workoutName
            WorkoutMenuViewModel.TextField.WORKOUT_NOTE -> "Workout Note" to content.workoutNote.orEmpty()
            WorkoutMenuViewModel.TextField.EXERCISE_NOTE -> "Note" to content.exerciseNote.orEmpty()
        }
        val textState = remember(mode.session) { TextFieldState(prefill) }
        val naming = mode.field == WorkoutMenuViewModel.TextField.WORKOUT_NAME
        LightTextInputEditor(
            title = title,
            state = textState,
            onSubmit = { text ->
                choose(
                    when (mode.field) {
                        WorkoutMenuViewModel.TextField.WORKOUT_NAME -> WorkoutMenuResult.RenameWorkout(text.toString())
                        WorkoutMenuViewModel.TextField.WORKOUT_NOTE -> WorkoutMenuResult.WorkoutNote(text.toString())
                        WorkoutMenuViewModel.TextField.EXERCISE_NOTE -> WorkoutMenuResult.ExerciseNote(text.toString())
                    },
                )
            },
            onBack = viewModel::showRows,
            keyboardOptionsFlow = keyboardOptionsFlow,
            // A name is one line: Return submits it. A note may run to several.
            singleLine = naming,
            // A capital to start a blank note; none mid-way through one being edited.
            initialCaps = prefill.isBlank(),
            editorKey = "lifts-workout-menu-${mode.field.name.lowercase()}-${mode.session}",
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        )
    }

    /** ±15 s for this exercise's work sets; ACCEPT keeps it, Back drops it. */
    @Composable
    private fun RestEditor(sec: Int) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = viewModel::showRows,
                ),
                center = LightTopBarCenter.Text("Rest"),
                rightButton = LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    contentDescription = "save rest",
                    onClick = { choose(WorkoutMenuResult.Rest(sec)) },
                ),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Stepper(
                        onStep = viewModel::stepRest,
                        label = content.exerciseName?.let { "Work sets · $it" },
                        decrementEnabled = sec > 0,
                        incrementEnabled = sec < WorkoutMenuViewModel.MAX_REST_SEC,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4f.gridUnitsAsDp()),
                    ) {
                        LightText(
                            text = Format.clock(sec),
                            variant = LightTextVariant.Subtitle,
                            monospace = true,
                            maxLines = 1,
                        )
                    }
                    LightText(
                        text = when (sec) {
                            content.defaultRestSec -> "SETTINGS' DEFAULT"
                            0 -> "NO REST: STRAIGHT ON"
                            else -> "DEFAULT ${Format.clock(content.defaultRestSec)}"
                        },
                        variant = LightTextVariant.Superfine,
                        lighten = true,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }

    /** Leaves with [result]; an editor or an armed Discard is dropped first so Back handling can't swallow it. */
    private fun choose(result: WorkoutMenuResult) {
        viewModel.discardArm.disarm()
        viewModel.showRows()
        goBack(result)
    }
}

/** A note in a row's detail: its lines run on, and the row cuts it. */
private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()

/** A menu row: what it does, and beside it the detail that is cut when both don't fit. Null [onClick] greys it out. */
@Composable
private fun MenuRow(title: String, detail: String?, onClick: (() -> Unit)?) {
    val enabled = onClick != null
    LiftsListRow(
        onClick = onClick,
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
