package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.tczerwien.lifts.Perf
import com.tczerwien.lifts.analytics.MUSCLES
import com.tczerwien.lifts.analytics.NotationStyle
import com.tczerwien.lifts.analytics.PER_SIDE
import com.tczerwien.lifts.analytics.SetRun
import com.tczerwien.lifts.analytics.SetToken
import com.tczerwien.lifts.analytics.chronologicalPrSetIds
import com.tczerwien.lifts.analytics.compactNotation
import com.tczerwien.lifts.analytics.countsForText
import com.tczerwien.lifts.analytics.exerciseRecords
import com.tczerwien.lifts.analytics.muscleLabel
import com.tczerwien.lifts.analytics.recordLoadKg
import com.tczerwien.lifts.analytics.setNotation
import com.tczerwien.lifts.analytics.toggledCountsFor
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.Tracking
import com.tczerwien.lifts.data.bundledCatalog
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.data.tracking
import com.tczerwien.lifts.library.CatalogEntry
import com.tczerwien.lifts.library.catalogEntries
import com.tczerwien.lifts.library.nameCheckEntries
import com.tczerwien.lifts.library.nameClash
import com.tczerwien.lifts.library.renameClash
import com.tczerwien.lifts.seed.BundledCatalog
import com.tczerwien.lifts.seed.CatalogSource
import com.tczerwien.lifts.session.BounceGuard
import com.tczerwien.lifts.session.HistoryVersion
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.session.ReloadFloor
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.toolmanager.LiftsShare
import com.tczerwien.lifts.toolmanager.liftsShare
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFieldRow
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.LiftsTabRow
import com.tczerwien.lifts.ui.MonoDigits
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.SectionLabel
import com.tczerwien.lifts.ui.SetLine
import com.tczerwien.lifts.ui.TWO_LINE_ROW_HEIGHT_UNITS
import com.tczerwien.lifts.ui.UNKNOWN_EXERCISE
import com.tczerwien.lifts.ui.WeightUnit
import com.tczerwien.lifts.ui.displayName
import com.tczerwien.lifts.ui.knownCatalogName
import com.tczerwien.lifts.ui.renameTarget
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ExerciseDetailViewModel(
    private val exerciseId: String,
    private val repository: LiftsRepository,
    private val settings: SettingsStore,
    private val share: LiftsShare,
    /** The APK's catalog: a renamed lift's catalog name, for the rename checks and a reset. */
    private val catalog: CatalogSource,
    /** Opened only to rename (the "…" menu): the name editor opens at once, and closing it leaves. */
    private val renameOnly: Boolean = false,
) : LightViewModel<Unit>() {

    data class HistoryRow(
        val workoutId: Long,
        val dateLabel: String,
        /** The workout's sets in one line, repeats collapsed ("w95×10; 225×5 ×3"): see [compactNotation]. */
        val runs: List<SetRun>,
        /** The workout still in progress: shown, but it doesn't open Workout Detail, whose trash would delete it. */
        val inProgress: Boolean = false,
    )

    data class Stat(val label: String, val value: String, val dateLabel: String)

    /** [estimated] values are predicted from the best e1RM (no set of that many reps yet). */
    data class RepMax(val reps: Int, val valueLabel: String?, val estimated: Boolean = false)

    data class Records(val stats: List<Stat>, val repMaxes: List<RepMax>)

    data class UiState(
        val name: String = "",
        /** How the exercise is measured (About's Type row); records follow it. */
        val tracking: Tracking = Tracking.WEIGHT_REPS,
        /** The Type list is showing. */
        val pickingType: Boolean = false,
        val tab: Int = TAB_HISTORY,
        val pinnedNote: String? = null,
        val instructions: List<String> = emptyList(),
        val history: List<HistoryRow> = emptyList(),
        val records: Records? = null,
        /** The full-screen editor showing, if any: the pinned note or the name. */
        val editing: Editing? = null,
        val editSession: Int = 0,
        /** The name the name editor opened with: submitting it unchanged does nothing ([renameTarget]). */
        val nameAtOpen: String = "",
        /** The name he submitted is another exercise's (shown here): the editor stays open, saying so. */
        val nameTakenBy: String? = null,
        /** What the name editor opens with in place of [nameAtOpen]: his text, when the lane refused it. */
        val nameDraft: String? = null,
        val loaded: Boolean = false,
        /** Opened only to rename, and the rename is done or dropped: the screen goes back. */
        val leave: Boolean = false,
        /** One he made: About offers the whole form ([CustomExerciseScreen]). */
        val isCustom: Boolean = false,
        /** "Chest · Dumbbell": its primary muscle and equipment, for that row. */
        val musclesAndEquipment: String = "",
        /** Done one side at a time: its reps (or seconds) count one side ("10/side"). */
        val perSide: Boolean = false,
        /** Taken out of the picker ([com.tczerwien.lifts.library.Removal.HIDE]): About offers it back. */
        val hidden: Boolean = false,
        /** What a set of it counts for in weekly sets (About's Counts For); empty: [primaryMuscles]. */
        val countsFor: List<String> = emptyList(),
        val primaryMuscles: List<String> = emptyList(),
        /** The Counts For list is showing. */
        val pickingCounts: Boolean = false,
    )

    enum class Editing { NOTE, NAME }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Every exercise's names, for the rename's duplicate check; loaded when the name editor opens. */
    @Volatile
    private var names: List<CatalogEntry> = emptyList()

    /**
     * Loads here only: the SDK calls this straight after creating the view model, so init needn't.
     * Queued behind any write still pending, so a workout deleted a moment ago can't show.
     */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        val perf = Perf.start()
        LiftsIo.write({}) {
            refresh()
            Perf.done("detail.load", perf, "${_uiState.value.history.size} workouts")
            // Opened to rename: straight into the name editor, once, as the name has loaded.
            if (renameOnly && !renameOpened) {
                renameOpened = true
                openNameEditor()
            }
        }
    }

    /** [renameOnly]: the name editor has been opened once; after that, closing it leaves. */
    @Volatile
    private var renameOpened = false

    /**
     * Back from the note or name editor closes it. Only the in-app back button reaches this: the SDK
     * routes the system Back key straight to the activity.
     */
    override fun onBackPressed(): Boolean {
        if (_uiState.value.pickingType) {
            _uiState.update { it.copy(pickingType = false) }
            return true
        }
        if (_uiState.value.pickingCounts) {
            closeCountsFor()
            return true
        }
        if (_uiState.value.editing == null) return false
        closeEditor()
        return true
    }

    /** About's Type row: the list of ways to measure it. */
    fun openTypePicker() {
        if (!_uiState.value.loaded) return
        _uiState.update { it.copy(pickingType = true) }
    }

    /**
     * Measures the exercise by [tracking] from now on: its rows and steppers, its records and stars
     * (so the exports catch up). Logged weights keep their size and take the new type's sign
     * ([LiftsRepository.setTracking]): 40 of help becomes 40 added and back. That is history
     * changing under an open workout, so its cached history is dropped ([HistoryVersion]).
     */
    fun chooseType(tracking: Tracking) {
        aboutFloor.saveQueued()
        _uiState.update { it.copy(pickingType = false, tracking = tracking) }
        LiftsIo.write({ repository.setTracking(exerciseId, tracking) }) { changed ->
            aboutFloor.saveLanded()
            if (changed) {
                HistoryVersion.bump()
                share.logChanged()
            }
            refresh()
        }
    }

    fun selectTab(index: Int) {
        _uiState.value = _uiState.value.copy(tab = index)
    }

    fun openNoteEditor() = openEditor(Editing.NOTE)

    fun openNameEditor() {
        if (!_uiState.value.loaded) return
        openEditor(Editing.NAME)
        LiftsIo.write({}) { names = nameCheckEntries(repository.exerciseSummaries(), catalog) }
    }

    private fun openEditor(editing: Editing) {
        val state = _uiState.value
        if (!state.loaded) return
        _uiState.value = state.copy(
            editing = editing,
            editSession = state.editSession + 1,
            nameAtOpen = state.name,
            nameTakenBy = null,
            nameDraft = null,
        )
    }

    fun closeEditor() {
        _uiState.value = _uiState.value.copy(editing = null, nameTakenBy = null, nameDraft = null, leave = renameOnly)
    }

    /**
     * Blank input clears the pinned note. workouts.json carries the cue, so the exports catch up. The
     * note shows at once, so the editor opened again before the save lands starts from it, not the old one.
     */
    fun saveNote(raw: String) {
        noteFloor.saveQueued()
        _uiState.update { it.copy(editing = null, pinnedNote = raw.trim().takeIf { note -> note.isNotEmpty() }) }
        LiftsIo.write({
            val exercise = repository.exerciseById(exerciseId) ?: return@write false
            val note = raw.trim().takeIf { it.isNotEmpty() }
            note != exercise.pinnedNote && repository.updateExercise(exercise.copy(pinnedNote = note))
        }) { changed ->
            noteFloor.saveLanded()
            if (changed) share.logChanged()
            refresh()
        }
    }

    /**
     * Reloads run beside the write lane, so one that read the database before a cue save landed can
     * land after it. While a save is pending, and for every reload started before it landed, the note
     * on screen (the one just saved) stands ([ReloadFloor]).
     */
    private val noteFloor = ReloadFloor()

    /** [noteFloor] for About's Type, Per Side, Counts For and Show: a reload that read before their save can't put them back. */
    private val aboutFloor = ReloadFloor()

    /**
     * Renames the exercise ([renameTarget]): the new name shows everywhere, exports included, over any
     * short name ([displayName]). Submitting the name as the editor showed it changes nothing; a blank
     * name puts a catalog exercise back to its catalog name. A name another exercise answers to
     * ([nameClash]) would split his history and the Strong CSV's names: the editor stays open and says
     * whose it is. The lane checks again against the catalog as it is then, and against the name a reset
     * would show ([renameClash]); a clash found there renames nothing and opens the editor again with his
     * text and the same title (the names may not have loaded when he submitted, or a reset was refused).
     */
    fun saveName(raw: String) {
        val shown = _uiState.value.nameAtOpen
        val typed = raw.replace(Regex("\\s+"), " ").trim()
        // Blank resets and an unchanged name rename nothing here, so they can't be refused.
        val clash = if (typed.isEmpty() || typed == shown.trim()) null else nameClash(typed, names, selfId = exerciseId)
        if (clash != null) {
            _uiState.update { it.copy(nameTakenBy = clash.shown) }
            return
        }
        _uiState.update { it.copy(editing = null, nameTakenBy = null, nameDraft = null) }
        // Opened only to rename: back to the workout once the lane says it's done (not when refused).
        LiftsIo.write({
            val exercise = repository.exerciseById(exerciseId) ?: return@write RenameResult.Unchanged
            val name = renameTarget(exercise, raw, shown) {
                knownCatalogName(exercise.id) ?: BundledCatalog.names(catalog)[exercise.id]
            } ?: return@write RenameResult.Unchanged
            // What it would show counts too: a reset to the catalog name shows the short name again.
            val taken = renameClash(exerciseId, exercise.isCustom, name, catalogEntries(repository.exerciseSummaries(), catalog))
            when {
                taken != null -> RenameResult.Taken(taken.shown)
                repository.updateExercise(exercise.copy(name = name)) -> RenameResult.Saved
                else -> RenameResult.Unchanged
            }
        }) { result ->
            if (result == RenameResult.Saved) share.logChanged()
            if (renameOnly && result !is RenameResult.Taken) _uiState.update { it.copy(leave = true) }
            if (result is RenameResult.Taken) {
                // Back in the editor with his text, unless he has opened the note editor meanwhile.
                _uiState.update { state ->
                    if (state.editing != null) {
                        state
                    } else {
                        state.copy(
                            editing = Editing.NAME,
                            editSession = state.editSession + 1,
                            nameTakenBy = result.by,
                            nameDraft = raw,
                        )
                    }
                }
            }
            refresh()
        }
    }

    /** Per Side flips on each tap: a bounce would flip it straight back. */
    private val perSideTaps = BounceGuard()

    /**
     * About's Per Side row: from now on (and in its history, which reads the same) the reps or seconds
     * count one side, "10/side" on every screen and in every export; tapping again takes it back.
     */
    fun togglePerSide() {
        val state = _uiState.value
        if (!state.loaded || !perSideTaps.take()) return
        val perSide = !state.perSide
        aboutFloor.saveQueued()
        _uiState.update { it.copy(perSide = perSide) }
        LiftsIo.write({ repository.setPerSide(exerciseId, perSide) }) { changed ->
            aboutFloor.saveLanded()
            if (changed) share.logChanged()
            refresh()
        }
    }

    /** About's "Show in picker": a hidden exercise of his goes back into the picker (workouts.json marks it). */
    fun showInPicker() {
        if (!_uiState.value.hidden) return
        aboutFloor.saveQueued()
        _uiState.update { it.copy(hidden = false) }
        LiftsIo.write({ repository.setExerciseHidden(exerciseId, hidden = false) }) { changed ->
            aboutFloor.saveLanded()
            if (changed) share.logChanged()
            refresh()
        }
    }

    /** About's Counts For row: the list of muscles a set of it can count for. */
    fun openCountsFor() {
        if (!_uiState.value.loaded) return
        _uiState.update { it.copy(pickingCounts = true) }
    }

    fun closeCountsFor() {
        _uiState.update { it.copy(pickingCounts = false) }
    }

    /** A tap on a muscle is let through once per bounce, per row: two quick taps on two rows are two choices. */
    private val countsForTaps = HashMap<String, BounceGuard>()

    /**
     * A tap in the Counts For list ([toggledCountsFor]; null is the catalog row): from now on its sets, past
     * ones too, count for these muscles in Sets per Muscle and the exports. Shown at once and saved on the
     * lane, each save the whole list as shown, so quick taps land in order.
     */
    fun toggleCountsFor(muscle: String?) {
        val state = _uiState.value
        if (!state.loaded || !countsForTaps.getOrPut(muscle.orEmpty()) { BounceGuard() }.take()) return
        val next = toggledCountsFor(state.countsFor, muscle)
        if (next == state.countsFor) return
        aboutFloor.saveQueued()
        _uiState.update { it.copy(countsFor = next) }
        LiftsIo.write({ repository.setCountsFor(exerciseId, next) }) { changed ->
            aboutFloor.saveLanded()
            if (changed) share.logChanged()
            refresh()
        }
    }

    /** How a rename on the lane went. */
    private sealed interface RenameResult {
        data object Saved : RenameResult

        data object Unchanged : RenameResult

        /** Refused: the exercise shown as [by] answers to that name. */
        data class Taken(val by: String) : RenameResult
    }

    private fun refresh() {
        val reload = noteFloor.reloadStarted()
        val aboutReload = aboutFloor.reloadStarted()
        val exercise = repository.exerciseById(exerciseId)
        val unit = settings.snapshot().units
        val tracking = exercise?.tracking ?: Tracking.WEIGHT_REPS

        // Every workout containing this exercise, newest first, with its completed sets in slot
        // then set order: two queries, whatever the history length.
        val setsByWorkout = repository.completedSetsWithWorkout(exerciseId).groupBy { it.workoutId }
        val perWorkout = repository.listWorkouts().mapNotNull { workout ->
            val sets = setsByWorkout[workout.id] ?: return@mapNotNull null
            PerWorkout(
                workoutId = workout.id,
                inProgress = workout.endedAt == null,
                // Dated by its start, as History and the export files date it: an evening workout
                // that runs past midnight keeps its day.
                dateMillis = workout.startedAt,
                sets = sets.sortedWith(compareBy({ it.slotPosition }, { it.set.position })).map { it.set },
            )
        }

        // A set is starred when it beat every set completed before it (shared records engine).
        val allSets = perWorkout.flatMap { it.sets }
        val prSetIds = chronologicalPrSetIds(allSets, tracking)

        val perSide = exercise?.perSide == true
        val history = perWorkout.map { entry ->
            HistoryRow(
                workoutId = entry.workoutId,
                // Today's open workout: shown, but not opened (its trash would delete the live workout).
                dateLabel = Format.dateShort(entry.dateMillis) + if (entry.inProgress) " · NOW" else "",
                runs = compactNotation(entry.sets.map { set -> set.toToken(unit, set.id in prSetIds, perSide) }),
                inProgress = entry.inProgress,
            )
        }

        // Records count finished workouts only: a mistaken tick in today's workout isn't a record until
        // the workout is done. Today's row and its live stars stay, as Active Workout shows them.
        val finished = perWorkout.filterNot { it.inProgress }
        // A record is dated by its workout's day, as the History tab dates it: a PR ticked after
        // midnight in an evening workout is on that evening's row.
        val startedAtBySet = finished.flatMap { entry -> entry.sets.map { it.id to entry.dateMillis } }.toMap()
        val records = recordsFor(finished.flatMap { it.sets }, unit, tracking, perSide) { set ->
            startedAtBySet[set.id] ?: set.completedAt ?: 0L
        }
        // Merged into the state as it is now, so a tap made while this ran (opening an editor) stands.
        _uiState.update { state ->
            // A Type, Per Side, Counts For or Show tapped while this read stands until its save is read back.
            val keepAbout = aboutFloor.keepsShown(aboutReload)
            state.copy(
                name = exercise?.let(::displayName) ?: UNKNOWN_EXERCISE,
                tracking = if (keepAbout) state.tracking else tracking,
                pinnedNote = if (noteFloor.keepsShown(reload)) state.pinnedNote else exercise?.pinnedNote,
                instructions = exercise?.instructions ?: emptyList(),
                history = history,
                records = records,
                loaded = true,
                isCustom = exercise?.isCustom == true,
                perSide = if (keepAbout) state.perSide else exercise?.perSide == true,
                musclesAndEquipment = listOfNotNull(exercise?.primaryMuscles?.firstOrNull(), exercise?.equipment)
                    .joinToString(" · ") { titleCaseLabel(it) }
                    .ifEmpty { "—" },
                hidden = if (keepAbout) state.hidden else exercise?.hidden == true,
                countsFor = if (keepAbout) state.countsFor else exercise?.countsFor.orEmpty(),
                primaryMuscles = exercise?.primaryMuscles.orEmpty(),
            )
        }
    }

    private fun SetEntity.toToken(unit: WeightUnit, pr: Boolean, perSide: Boolean) =
        SetToken(setNotation(this, unit, NotationStyle.LIST, perSide = perSide) ?: "-", pr)

    /**
     * The Records tab, formatted from the shared records engine, as the exercise is measured ([perSide]:
     * "× 10/side"); each record dated by [dayOf] its set.
     */
    private fun recordsFor(
        sets: List<SetEntity>,
        unit: WeightUnit,
        tracking: Tracking,
        perSide: Boolean,
        dayOf: (SetEntity) -> Long,
    ): Records? {
        val records = exerciseRecords(sets, REP_MAX_TARGETS, tracking) ?: return null
        fun weightLabel(kg: Double) = "${Format.weight(kg, unit)} ${unit.suffix}"
        fun dated(set: SetEntity) = Format.dateWithYear(dayOf(set))
        val stats = buildList {
            records.maxWeightSet?.let { set ->
                val label = when (tracking) {
                    // The help as the machine's stack shows it; none at all is a plain pull-up.
                    Tracking.ASSISTED_BODYWEIGHT -> (-recordLoadKg(set, tracking)).takeIf { it > 0.0 }
                        ?.let { setNotation(it, set.reps, unit, NotationStyle.SINGLE, withUnit = true, perSide = perSide) }
                        ?: "None × ${set.reps ?: 0}${if (perSide) PER_SIDE else ""}"
                    else -> setNotation(set.weightKg, set.reps, unit, NotationStyle.SINGLE, withUnit = true, perSide = perSide).orEmpty()
                }
                val title = when (tracking) {
                    Tracking.WEIGHTED_BODYWEIGHT -> "Max Added"
                    Tracking.ASSISTED_BODYWEIGHT -> "Least Assistance"
                    else -> "Max Weight"
                }
                add(Stat(title, label, dated(set)))
            }
            records.longestSet?.let { set ->
                add(Stat("Longest", "${set.durationSec ?: 0}s${if (perSide) PER_SIDE else ""}", dated(set)))
            }
            val bestE1Rm = records.bestE1RmKg
            val bestE1RmSet = records.bestE1RmSet
            if (bestE1Rm != null && bestE1RmSet != null) {
                add(Stat("Best e1RM", weightLabel(bestE1Rm), dated(bestE1RmSet)))
            }
            records.bestVolumeSet?.let { set ->
                add(
                    Stat(
                        "Best Volume Set",
                        setNotation(set.weightKg, set.reps, unit, NotationStyle.SINGLE, perSide = perSide).orEmpty(),
                        dated(set),
                    ),
                )
            }
            records.maxRepsSet?.let { set ->
                add(Stat("Max Reps", "${set.reps ?: 0}${if (perSide) PER_SIDE else ""}", dated(set)))
            }
        }
        val repMaxes = records.repMaxes.map { repMax ->
            RepMax(
                reps = repMax.reps,
                valueLabel = repMax.weightKg?.let { weightLabel(it) + if (repMax.estimated) " est." else "" },
                estimated = repMax.estimated,
            )
        }
        return Records(stats = stats, repMaxes = repMaxes)
    }

    private data class PerWorkout(
        val workoutId: Long,
        val inProgress: Boolean,
        val dateMillis: Long,
        val sets: List<SetEntity>,
    )

    companion object {
        const val TAB_ABOUT = 0
        const val TAB_HISTORY = 1
        const val TAB_RECORDS = 2
        private val REP_MAX_TARGETS = listOf(1, 5, 8)
    }
}

/**
 * Exercise detail (design/screens/exercise-detail.html): ABOUT / HISTORY / RECORDS tabs.
 * History lists one row per workout (date + compact set notation with PR stars); Records
 * shows max weight, best e1RM, best volume set, and 1/5/8 rep maxes (or what the exercise's type
 * records: max reps, max added, least assistance, longest hold); About shows its name, its Type
 * (how it is measured, changeable), what its sets count for in weekly sets (Counts For, changeable),
 * the pinned note and the catalog instructions. Top-bar pencil
 * edits the pinned note; a tap on the name (top bar, or About's Name row) renames the exercise.
 * Opens on History — the common in-workout question is "what did I do last time".
 */
class ExerciseDetailScreen(
    private val sealedActivity: SealedLightActivity,
    private val exerciseId: String,
    /** Opened by the Active Workout menu's Rename: only the name editor, then back. */
    private val renameOnly: Boolean = false,
) : LightScreen<Unit, ExerciseDetailViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    /**
     * The History tab's place, held by the screen, which stays on the back stack under the Workout
     * Detail a row opens: back from a workout months ago, the list is still there. A remembered state
     * would be dropped with the composition.
     */
    private val historyListState = LazyListState()

    override val viewModelClass: Class<ExerciseDetailViewModel>
        get() = ExerciseDetailViewModel::class.java

    override fun createViewModel() =
        ExerciseDetailViewModel(
            exerciseId,
            repository,
            SettingsStore(lightContext.dataStore),
            lightContext.liftsShare(),
            lightContext.bundledCatalog(),
            renameOnly,
        )

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LaunchedEffect(uiState.leave) {
            if (uiState.leave) goBack()
        }

        LiftsFrame(sealedActivity, lightContext, switchKey = Triple(uiState.pickingType, uiState.pickingCounts, uiState.editing)) {
            val editing = uiState.editing
            if (editing != null) {
                val naming = editing == ExerciseDetailViewModel.Editing.NAME
                val textState = remember(uiState.editSession) {
                    TextFieldState(if (naming) uiState.nameDraft ?: uiState.nameAtOpen else uiState.pinnedNote ?: "")
                }
                LightTextInputEditor(
                    // A name another exercise has keeps the editor open, saying whose it is.
                    title = when {
                        !naming -> "Note"
                        uiState.nameTakenBy != null -> "In use: ${uiState.nameTakenBy}"
                        else -> "Name"
                    },
                    // A name is one line: Return submits it. A note may run to several.
                    singleLine = naming,
                    state = textState,
                    onSubmit = { if (naming) viewModel.saveName(it.toString()) else viewModel.saveNote(it.toString()) },
                    onBack = viewModel::closeEditor,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    // A capital to start a blank note or name; none mid-way through one being edited.
                    initialCaps = (if (naming) uiState.nameAtOpen else uiState.pinnedNote.orEmpty()).isBlank(),
                    editorKey = "exercise-${editing.name.lowercase()}-${uiState.editSession}",
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background),
                )
            } else if (uiState.pickingType) {
                TypePicker(uiState.tracking)
            } else if (uiState.pickingCounts) {
                // Multi-select; the first row is the catalog's muscles, which count while none is chosen.
                OptionPicker(
                    title = "Counts For",
                    options = MUSCLES + (uiState.countsFor - MUSCLES.toSet()),
                    isSelected = { muscle -> if (muscle == null) uiState.countsFor.isEmpty() else muscle in uiState.countsFor },
                    onSelect = viewModel::toggleCountsFor,
                    onBack = viewModel::closeCountsFor,
                    multiSelect = true,
                    includeNone = true,
                    noneLabel = countsForText(emptyList(), uiState.primaryMuscles),
                    label = ::muscleLabel,
                )
            } else if (!renameOnly) {
                DetailContent(uiState)
            } else {
                // Opened only to rename: nothing behind the editor while it opens and closes.
                Box(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background))
            }
        }
    }

    @Composable
    private fun DetailContent(uiState: ExerciseDetailViewModel.UiState) {
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
                // A tap on the name renames it, as the About tab's Name row does.
                center = LightTopBarCenter.Text(uiState.name, onClick = viewModel::openNameEditor),
                rightButton = LightBarButton.LightIcon(
                    icon = LightIcons.PENCIL,
                    contentDescription = "edit note",
                    onClick = { viewModel.openNoteEditor() },
                ),
            )

            LiftsTabRow(
                tabs = listOf("About", "History", "Records"),
                activeIndex = uiState.tab,
                onSelect = viewModel::selectTab,
            )

            val bodyModifier = Modifier
                .weight(1f)
                .fillMaxWidth()
            when (uiState.tab) {
                ExerciseDetailViewModel.TAB_ABOUT -> AboutTab(uiState, bodyModifier)
                ExerciseDetailViewModel.TAB_HISTORY -> HistoryTab(uiState, bodyModifier)
                else -> RecordsTab(uiState, bodyModifier)
            }
        }
    }

    @Composable
    private fun AboutTab(uiState: ExerciseDetailViewModel.UiState, modifier: Modifier) {
        val note = uiState.pinnedNote
        LightScrollView(modifier = modifier) {
            LiftsFieldRow(label = "Name", value = uiState.name, onClick = viewModel::openNameEditor, chevron = true)
            LiftsFieldRow(label = "Type", value = uiState.tracking.label, onClick = viewModel::openTypePicker, chevron = true)
            // A split squat's 10 is 10 a leg: the count is each side's, written "10/side" everywhere.
            LiftsFieldRow(
                label = "Per Side",
                value = if (!uiState.perSide) "No" else if (uiState.tracking.takesDuration) "Yes · seconds each side" else "Yes · reps each side",
                onClick = viewModel::togglePerSide,
            )
            // Sets per Muscle counts each work set 1 for each of these; the catalog's primary muscles until he picks.
            LiftsFieldRow(
                label = "Counts For",
                value = countsForText(uiState.countsFor, uiState.primaryMuscles),
                onClick = viewModel::openCountsFor,
                chevron = true,
            )
            if (uiState.isCustom) {
                // His own: muscles, equipment and category too, and the trash (hidden, as it has history).
                LiftsFieldRow(label = "Muscles & Equipment", value = uiState.musclesAndEquipment, onClick = ::editCustom, chevron = true)
            }
            if (uiState.hidden) {
                LiftsFieldRow(label = "Hidden From Picker", value = "Show", onClick = viewModel::showInPicker, chevron = true)
            }
            if (uiState.loaded && note == null && uiState.instructions.isEmpty()) {
                LightText(
                    text = "No instructions",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                )
            }
            if (note != null) {
                SectionLabel("Note")
                LightText(
                    text = note,
                    variant = LightTextVariant.Paragraph,
                    modifier = Modifier.padding(
                        start = 1f.gridUnitsAsDp(),
                        end = 1f.gridUnitsAsDp(),
                        bottom = 0.5f.gridUnitsAsDp(),
                    ),
                )
            }
            if (uiState.instructions.isNotEmpty()) {
                SectionLabel("Instructions")
                uiState.instructions.forEach { paragraph ->
                    LightText(
                        text = paragraph,
                        variant = LightTextVariant.Paragraph,
                        modifier = Modifier.padding(
                            start = 1f.gridUnitsAsDp(),
                            end = 1f.gridUnitsAsDp(),
                            bottom = 0.75f.gridUnitsAsDp(),
                        ),
                    )
                }
            }
        }
    }

    /** The whole form for one he made; the screen reloads on the way back, and leaves if it was deleted. */
    private fun editCustom() {
        navigateTo({ CustomExerciseScreen(it, editId = exerciseId) }) { result -> if (result.isEmpty()) goBack() }
    }

    /** The ways to measure an exercise, each with what it is for; the current one says so. */
    @Composable
    private fun TypePicker(current: Tracking) {
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
                center = LightTopBarCenter.Text("Type"),
            )
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Tracking.entries.forEachIndexed { index, tracking ->
                    if (index > 0) RowDivider()
                    LiftsListRow(
                        onClick = { viewModel.chooseType(tracking) },
                        heightUnits = TWO_LINE_ROW_HEIGHT_UNITS,
                        left = {
                            Column {
                                LightText(text = tracking.label, variant = LightTextVariant.Copy, maxLines = 1)
                                LightText(
                                    text = typeExample(tracking),
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    maxLines = 1,
                                )
                            }
                        },
                        right = if (tracking == current) {
                            { LightText(text = "CURRENT", variant = LightTextVariant.Superfine, maxLines = 1) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    private fun typeExample(tracking: Tracking): String = when (tracking) {
        Tracking.WEIGHT_REPS -> "Barbell, dumbbell, cable, machine"
        Tracking.REPS_ONLY -> "Push-ups, dead bugs, hanging raises"
        Tracking.WEIGHTED_BODYWEIGHT -> "Added weight: belt dips, pull-ups"
        Tracking.ASSISTED_BODYWEIGHT -> "Machine help: assisted pull-ups"
        Tracking.DURATION -> "Seconds: planks, holds"
    }

    @Composable
    private fun HistoryTab(uiState: ExerciseDetailViewModel.UiState, modifier: Modifier) {
        if (uiState.loaded && uiState.history.isEmpty()) {
            EmptyTabMessage("No sets logged yet", modifier)
            return
        }
        LightLazyScrollView(
            modifier = modifier,
            listState = historyListState,
            uniformItemHeightGridUnits = HISTORY_ROW_HEIGHT_UNITS,
        ) {
            itemsIndexed(uiState.history, key = { _, row -> row.workoutId }) { index, row ->
                HistoryRow(
                    row = row,
                    showDivider = index < uiState.history.lastIndex,
                    onClick = if (row.inProgress) {
                        null
                    } else {
                        { navigateTo({ WorkoutDetailScreen(it, workoutId = row.workoutId) }) }
                    },
                )
            }
        }
    }

    @Composable
    private fun RecordsTab(uiState: ExerciseDetailViewModel.UiState, modifier: Modifier) {
        val records = uiState.records
        if (records == null) {
            // Sets only in today's open workout: History shows them, Records waits for the finish.
            // Finished sets that set no record (only warm-ups, or 0 reps): no records yet.
            val text = when {
                uiState.history.isEmpty() -> "No sets logged yet"
                uiState.history.all { it.inProgress } -> "No finished sets yet"
                else -> "No records yet"
            }
            if (uiState.loaded) EmptyTabMessage(text, modifier)
            return
        }
        LightScrollView(modifier = modifier) {
            records.stats.forEach { stat -> StatRow(stat) }

            if (records.repMaxes.isNotEmpty()) SectionLabel("Rep Maxes")
            records.repMaxes.forEachIndexed { index, repMax ->
                if (index > 0) RowDivider()
                LiftsListRow(
                    left = {
                        MonoDigits(Format.count(repMax.reps, "rep"), LightTextVariant.Detail)
                    },
                    right = {
                        MonoDigits(
                            repMax.valueLabel ?: "—",
                            LightTextVariant.Copy,
                            lighten = repMax.valueLabel == null || repMax.estimated,
                        )
                    },
                )
            }
        }
    }

    @Composable
    private fun EmptyTabMessage(text: String, modifier: Modifier) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            LightText(
                text = text,
                variant = LightTextVariant.Copy,
                lighten = true,
                align = TextAlign.Center,
            )
        }
    }
}

private const val HISTORY_ROW_HEIGHT_UNITS = 3f

/**
 * Uniform 3gu history row (design history tab): contentSecondary mono date left, compact
 * right-aligned set notation with inline PR stars; inset divider inside the row bottom.
 */
@Composable
private fun HistoryRow(
    row: ExerciseDetailViewModel.HistoryRow,
    showDivider: Boolean,
    onClick: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HISTORY_ROW_HEIGHT_UNITS.gridUnitsAsDp())
            .let { m -> if (onClick != null) m.lightClickable(onClick = onClick) else m },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = row.dateLabel,
                variant = LightTextVariant.Detail,
                lighten = true,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(1f.gridUnitsAsDp()))
            // Two lines: with an RIR on each set a day rarely fits on one, and cut at one line it was
            // the work sets and the star that went, not the warm-ups.
            SetLine(
                runs = row.runs,
                maxLines = 2,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        if (showDivider) {
            RowDivider(modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Stat row (design .statrow): superfine label over the value (digits mono) with the date at the right. */
@Composable
private fun StatRow(stat: ExerciseDetailViewModel.Stat) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
    ) {
        LightText(
            text = stat.label.uppercase(),
            variant = LightTextVariant.Superfine,
            lighten = true,
            maxLines = 1,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MonoDigits(stat.value, LightTextVariant.Copy, modifier = Modifier.alignByBaseline())
            LightText(
                text = stat.dateLabel,
                variant = LightTextVariant.Detail,
                lighten = true,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}
