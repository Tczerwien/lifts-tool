package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.tczerwien.lifts.Perf
import com.tczerwien.lifts.analytics.CONSISTENCY_CHART_WEEKS
import com.tczerwien.lifts.analytics.MAX_SET_TARGET
import com.tczerwien.lifts.analytics.MuscleRow
import com.tczerwien.lifts.analytics.NEW_SET_TARGET
import com.tczerwien.lifts.analytics.deloadWeeks
import com.tczerwien.lifts.analytics.firstMonday
import com.tczerwien.lifts.analytics.muscleLabel
import com.tczerwien.lifts.analytics.muscleRows
import com.tczerwien.lifts.analytics.targetChoices
import com.tczerwien.lifts.analytics.weeklySetsPerMuscle
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.toolmanager.LiftsShare
import com.tczerwien.lifts.toolmanager.liftsShare
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.MonoDigits
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.RowName
import com.tczerwien.lifts.ui.Stepper
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Weeks Sets per Muscle can show: this one and the 11 before it, as Consistency's chart. */
const val SETS_PER_MUSCLE_WEEKS = CONSISTENCY_CHART_WEEKS

class SetsPerMuscleViewModel(
    private val repository: LiftsRepository,
    private val settings: SettingsStore,
    private val share: LiftsShare,
) : LightViewModel<Unit>() {

    /** What the screen shows: the week's list, the muscles "+ Target" offers, or one muscle's target being set. */
    sealed interface Mode {
        data object List : Mode

        data object PickMuscle : Mode

        data class Edit(val muscle: String, val sets: Int) : Mode
    }

    data class UiState(
        val loaded: Boolean = false,
        /** The week shown: 0 is this one, up to [SETS_PER_MUSCLE_WEEKS] - 1 back. */
        val weeksBack: Int = 0,
        /** Hard sets per muscle, one per week, oldest first ([weeklySetsPerMuscle]). */
        val weekly: Map<String, List<Int>> = emptyMap(),
        /** Which of those weeks had a deload workout. */
        val deloads: List<Boolean> = emptyList(),
        /** The Monday of the oldest week, and today, when loaded. */
        val firstMonday: LocalDate? = null,
        val today: LocalDate? = null,
        val targets: Map<String, Int> = emptyMap(),
        val mode: Mode = Mode.List,
    ) {
        private val index: Int get() = SETS_PER_MUSCLE_WEEKS - 1 - weeksBack

        /** The week's rows: muscles with a target, then the rest with sets ([muscleRows]). */
        val rows: List<MuscleRow> get() = muscleRows(weekly.mapValues { it.value[index] }, targets)

        /** "THIS WEEK · FROM MON SEP 28" ([weekLine]); empty until loaded. */
        val weekText: String
            get() {
                val first = firstMonday ?: return ""
                return weekLine(weeksBack, first.plusWeeks(index.toLong()), today ?: first, deloads.getOrElse(index) { false })
            }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Loads behind any write still queued (a Counts For or target just saved), as Exercise Detail does. */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        val perf = Perf.start()
        LiftsIo.write({}) {
            refresh()
            Perf.done("muscles.load", perf, "${_uiState.value.weekly.size} muscles")
        }
    }

    /**
     * One scoped query: the [SETS_PER_MUSCLE_WEEKS] weeks' finished workouts by their start
     * ([LiftsRepository.countedSlots]), in the phone's calendar weeks, then the weekly rule. Stepping
     * between the weeks reads nothing more.
     */
    private fun refresh() {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val first = firstMonday(now, SETS_PER_MUSCLE_WEEKS, zone)
        val slots = repository.countedSlots(
            from = first.atStartOfDay(zone).toInstant().toEpochMilli(),
            until = first.plusWeeks(SETS_PER_MUSCLE_WEEKS.toLong()).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
        val targets = settings.snapshot().muscleTargets
        _uiState.update { state ->
            state.copy(
                loaded = true,
                weekly = weeklySetsPerMuscle(slots, SETS_PER_MUSCLE_WEEKS, now, zone),
                deloads = deloadWeeks(slots, SETS_PER_MUSCLE_WEEKS, now, zone),
                firstMonday = first,
                today = LocalDate.now(zone),
                targets = targets,
            )
        }
    }

    /** ‹ (−1) shows the week before, › (+1) the week after, between this week and [SETS_PER_MUSCLE_WEEKS] - 1 back. */
    fun stepWeek(delta: Int) {
        _uiState.update { it.copy(weeksBack = (it.weeksBack - delta).coerceIn(0, SETS_PER_MUSCLE_WEEKS - 1)) }
    }

    /** A row's tap: its target to set, from the one it has (else [NEW_SET_TARGET]). */
    fun editTarget(muscle: String) {
        val state = _uiState.value
        if (!state.loaded || state.mode != Mode.List) return
        _uiState.update { it.copy(mode = Mode.Edit(muscle, state.targets[muscle] ?: NEW_SET_TARGET)) }
    }

    /** "+ Target": the muscles without one. */
    fun pickMuscle() {
        if (!_uiState.value.loaded || _uiState.value.mode != Mode.List) return
        _uiState.update { it.copy(mode = Mode.PickMuscle) }
    }

    fun musclePicked(muscle: String) {
        if (_uiState.value.mode != Mode.PickMuscle) return
        _uiState.update { it.copy(mode = Mode.Edit(muscle, NEW_SET_TARGET)) }
    }

    /** The target stepper: 0 (no target) to [MAX_SET_TARGET]. */
    fun stepTarget(delta: Int) {
        _uiState.update { state ->
            val edit = state.mode as? Mode.Edit ?: return@update state
            state.copy(mode = edit.copy(sets = (edit.sets + delta).coerceIn(0, MAX_SET_TARGET)))
        }
    }

    /**
     * SAVE: the muscle's target is the stepper's (0 takes it away), on the list at once and in DataStore on
     * the write lane; the exports, which show it, are rewritten when it changed.
     */
    fun saveTarget() {
        val edit = _uiState.value.mode as? Mode.Edit ?: return
        _uiState.update { state ->
            val targets = if (edit.sets == 0) state.targets - edit.muscle else state.targets + (edit.muscle to edit.sets)
            state.copy(targets = targets, mode = Mode.List)
        }
        LiftsIo.write({ settings.setMuscleTarget(edit.muscle, edit.sets) }) { changed -> if (changed) share.logChanged() }
    }

    /** Back from the list of muscles or a target's stepper goes to the week's list, saving nothing. */
    override fun onBackPressed(): Boolean {
        if (_uiState.value.mode == Mode.List) return false
        closeMode()
        return true
    }

    fun closeMode() {
        _uiState.update { it.copy(mode = Mode.List) }
    }
}

/** "THIS WEEK", "LAST WEEK", "3 WEEKS AGO". */
internal fun weekName(weeksBack: Int): String = when (weeksBack) {
    0 -> "THIS WEEK"
    1 -> "LAST WEEK"
    else -> "$weeksBack WEEKS AGO"
}

/**
 * Sets per Muscle's top line: "THIS WEEK · FROM MON SEP 28" (the year too when [monday] isn't in [today]'s,
 * as dates are everywhere), "· DELOAD" after a week with a deload workout in it.
 */
internal fun weekLine(weeksBack: Int, monday: LocalDate, today: LocalDate, deload: Boolean): String =
    listOfNotNull(
        weekName(weeksBack),
        "FROM MON " + Format.dayShort(monday.toEpochDay(), today).uppercase(),
        "DELOAD".takeIf { deload },
    ).joinToString(" · ")

/** A row's count: "9 / 12" against a target, "2" without one. */
internal fun setsText(row: MuscleRow): String = row.target?.let { "${row.sets} / $it" } ?: "${row.sets}"

/**
 * Sets per Muscle (Consistency › Sets per Muscle): one week's hard sets per muscle against his targets, as
 * text. ‹ › step back through the last [SETS_PER_MUSCLE_WEEKS] weeks. Muscles with a target come first
 * ("Chest  9 / 12"), then muscles with sets but none (dimmed). A tap on a row sets its target (a stepper,
 * 0 for none, SAVE); "+ Target" picks a muscle that has none. The counting rule closes the list.
 */
class SetsPerMuscleScreen(
    private val sealedActivity: SealedLightActivity,
) : LightScreen<Unit, SetsPerMuscleViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    override val viewModelClass: Class<SetsPerMuscleViewModel>
        get() = SetsPerMuscleViewModel::class.java

    override fun createViewModel() =
        SetsPerMuscleViewModel(repository, SettingsStore(lightContext.dataStore), lightContext.liftsShare())

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()

        // Keyed on which mode, not its value: a stepper tap mustn't re-arm the guard and drop the next tap.
        LiftsFrame(sealedActivity, lightContext, switchKey = uiState.mode::class) {
            when (val mode = uiState.mode) {
                SetsPerMuscleViewModel.Mode.List -> WeekList(uiState)
                SetsPerMuscleViewModel.Mode.PickMuscle -> OptionPicker(
                    title = "Target For",
                    options = targetChoices(uiState.targets),
                    isSelected = { false },
                    onSelect = { muscle -> muscle?.let(viewModel::musclePicked) },
                    onBack = viewModel::closeMode,
                    label = ::muscleLabel,
                )
                is SetsPerMuscleViewModel.Mode.Edit -> TargetEditor(mode)
            }
        }
    }

    @Composable
    private fun WeekList(uiState: SetsPerMuscleViewModel.UiState) {
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
                center = LightTopBarCenter.Text("Sets per Muscle"),
            )
            if (!uiState.loaded) return@Column

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 0.5f.gridUnitsAsDp()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WeekArrow("‹", enabled = uiState.weeksBack < SETS_PER_MUSCLE_WEEKS - 1) { viewModel.stepWeek(-1) }
                LightText(
                    text = uiState.weekText,
                    variant = LightTextVariant.Superfine,
                    align = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                WeekArrow("›", enabled = uiState.weeksBack > 0) { viewModel.stepWeek(+1) }
            }

            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val rows = uiState.rows
                if (rows.isEmpty()) {
                    LightText(
                        text = "No sets this week",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                    )
                }
                rows.forEach { row ->
                    // Without a target it is dimmed: there, not aimed at.
                    LiftsListRow(
                        onClick = { viewModel.editTarget(row.muscle) },
                        left = { RowName(muscleLabel(row.muscle), lighten = row.target == null) },
                        right = { MonoDigits(setsText(row), LightTextVariant.Copy, lighten = row.target == null) },
                    )
                    RowDivider()
                }
                if (targetChoices(uiState.targets).isNotEmpty()) {
                    LiftsListRow(onClick = viewModel::pickMuscle, left = { RowName("+ Target") })
                    RowDivider()
                }
                LightText(
                    text = "Ticked work sets of finished workouts, by the week each started. A set counts 1 for every " +
                        "muscle its exercise counts for (Exercise › About › Counts For; else its catalog muscle). " +
                        "Warm-ups and drops add 0; a deload's sets count.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                )
            }
        }
    }

    /** ‹ or ›: a 3gu target around the glyph, dimmed and still at the end of the weeks. */
    @Composable
    private fun WeekArrow(glyph: String, enabled: Boolean, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(3f.gridUnitsAsDp())
                .let { if (enabled) it.lightClickable(onClick = onClick) else it },
            contentAlignment = Alignment.Center,
        ) {
            LightText(text = glyph, variant = LightTextVariant.Subtitle, lighten = !enabled, maxLines = 1)
        }
    }

    /** One muscle's weekly target: a stepper from 0 (none) to [MAX_SET_TARGET], then SAVE; Back keeps the old one. */
    @Composable
    private fun TargetEditor(edit: SetsPerMuscleViewModel.Mode.Edit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = viewModel::closeMode,
                ),
                center = LightTopBarCenter.Text(muscleLabel(edit.muscle)),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Stepper(
                        onStep = viewModel::stepTarget,
                        label = "Sets a Week",
                        decrementEnabled = edit.sets > 0,
                        incrementEnabled = edit.sets < MAX_SET_TARGET,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4f.gridUnitsAsDp()),
                    ) {
                        LightText(text = "${edit.sets}", variant = LightTextVariant.Subtitle, monospace = true, maxLines = 1)
                    }
                    LightText(
                        // Its height kept either way, so the stepper doesn't move under his finger at 0.
                        text = if (edit.sets == 0) "0: NO TARGET" else "",
                        variant = LightTextVariant.Superfine,
                        lighten = true,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(top = 1f.gridUnitsAsDp())
                            .height(1f.gridUnitsAsDp()),
                    )
                }
            }
            LightBottomBar(items = listOf(LightBarButton.Text(text = "SAVE", onClick = viewModel::saveTarget)))
        }
    }
}
