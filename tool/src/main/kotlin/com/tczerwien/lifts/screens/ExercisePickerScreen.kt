package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.library.BodyPart
import com.tczerwien.lifts.library.CatalogEntry
import com.tczerwien.lifts.library.catalogEntries
import com.tczerwien.lifts.library.inBodyPart
import com.tczerwien.lifts.library.nameFromQuery
import com.tczerwien.lifts.library.searchExercises
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.ui.KeptListState
import com.tczerwien.lifts.ui.LiftsFrame
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollBarPosition
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

class ExercisePickerViewModel(
    private val repository: LiftsRepository,
) : LightViewModel<String>() {

    data class ExerciseRow(val id: String, val name: String, val muscleLabel: String)

    /** A chip: what he has logged lately, the whole catalog, or one body part. */
    sealed interface Chip {
        data object Recent : Chip

        data object All : Chip

        data class Part(val part: BodyPart) : Chip
    }

    data class UiState(
        /** The active chip, shown while no search is. */
        val chip: Chip = Chip.All,
        /** He picked a chip or searched: the first load no longer moves him to Recent. */
        val chipChosen: Boolean = false,
        /** He has logged something, so the Recent chip shows. */
        val hasRecent: Boolean = false,
        /** Active name search; null = none. It replaces the chip until cleared. */
        val searchQuery: String? = null,
        val searchOpen: Boolean = false,
        val searchSession: Int = 0,
        val rows: List<ExerciseRow> = emptyList(),
        /** The rows differ in muscle: under Chest every row would say Chest, so the label is left off. */
        val showMuscles: Boolean = true,
        /** Bumped with every new list (a chip, a search, the load): the list starts over at the top. */
        val listGeneration: Int = 0,
        val loaded: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * The catalog as the search sees it, by shown name, loaded once: it can't change while the picker
     * is open (a new exercise closes it). Chips and search filter it in memory.
     */
    @Volatile
    private var catalog: List<CatalogEntry> = emptyList()

    /** What he has logged, most recently used first ([LiftsRepository.recentExerciseIds]). */
    @Volatile
    private var recentIds: List<String> = emptyList()

    private var loadStarted = false

    /**
     * Loads the catalog the first time only; the SDK calls this straight after creating the view model,
     * and again on every return. Behind any write still queued, so a rename saved a moment ago shows.
     */
    override fun onScreenShow(screen: SimpleLightScreen<String>) {
        if (loadStarted) return
        loadStarted = true
        LiftsIo.write({}) { load() }
    }

    /**
     * Back from the search editor closes it. Only the in-app back button reaches this: the SDK routes the
     * system Back key straight to the activity.
     */
    override fun onBackPressed(): Boolean {
        if (!_uiState.value.searchOpen) return false
        cancelSearch()
        return true
    }

    fun selectChip(chip: Chip) {
        _uiState.update { it.copy(chip = chip, chipChosen = true, searchQuery = null).withRows() }
    }

    fun openSearch() {
        _uiState.update { it.copy(searchOpen = true, searchSession = it.searchSession + 1) }
    }

    fun cancelSearch() {
        _uiState.update { it.copy(searchOpen = false) }
    }

    /** Blank submit clears the search; otherwise the query replaces the chip until cleared. */
    fun submitSearch(raw: String) {
        val query = raw.trim().takeIf { it.isNotEmpty() }
        _uiState.update { it.copy(searchOpen = false, searchQuery = query, chipChosen = true).withRows() }
    }

    fun clearSearch() {
        _uiState.update { it.copy(searchQuery = null).withRows() }
    }

    /** From the light projection, without the instructions ([catalogEntries]). */
    private fun load() {
        catalog = catalogEntries(repository.exerciseSummaries().filterNot { it.hidden })
        val shown = catalog.mapTo(HashSet()) { it.id }
        recentIds = repository.recentExerciseIds(RECENT_LIMIT).filter { it in shown }
        val hasRecent = recentIds.isNotEmpty()
        _uiState.update { state ->
            // He opens on Recent once he has logged something, unless he has already picked a chip.
            val chip = when {
                hasRecent && !state.chipChosen -> Chip.Recent
                state.chip == Chip.Recent && !hasRecent -> Chip.All
                else -> state.chip
            }
            state.copy(chip = chip, hasRecent = hasRecent, loaded = true).withRows()
        }
    }

    /** The rows for this state: a search, else the chip's. Recent keeps its order, most recent first. */
    private fun UiState.withRows(): UiState {
        val entries = when {
            searchQuery != null -> searchExercises(catalog, searchQuery, recentIds)
            else -> when (val chip = chip) {
                Chip.Recent -> catalog.associateBy { it.id }.let { byId -> recentIds.mapNotNull(byId::get) }
                Chip.All -> catalog
                is Chip.Part -> inBodyPart(catalog, chip.part)
            }
        }
        val rows = entries.map { it.toRow() }
        return copy(
            rows = rows,
            showMuscles = rows.mapTo(HashSet()) { it.muscleLabel }.size > 1,
            listGeneration = listGeneration + 1,
        )
    }

    private fun CatalogEntry.toRow() = ExerciseRow(
        id = id,
        name = shown,
        muscleLabel = primaryMuscles.firstOrNull()?.let(::titleCaseLabel) ?: "—",
    )
}

/**
 * Exercise picker (design/screens/exercise-picker.html): body-part filter chips over the
 * full 873-exercise catalog in uniform 3gu rows. Top-bar search opens the SDK editor and
 * filters by name; the bottom-bar plus creates a custom exercise, which is returned as the
 * selection. Tapping a row returns its exerciseId via goBack; plain back cancels.
 */
class ExercisePickerScreen(
    private val sealedActivity: SealedLightActivity,
) : LightScreen<String, ExercisePickerViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    /** The list's place survives the search editor and Custom Exercise; a new list starts at the top. */
    private val listState = KeptListState()

    override val viewModelClass: Class<ExercisePickerViewModel>
        get() = ExercisePickerViewModel::class.java

    override fun createViewModel() = ExercisePickerViewModel(repository)

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LiftsFrame(sealedActivity, lightContext) {
            if (uiState.searchOpen) {
                val textState = remember { TextFieldState(uiState.searchQuery ?: "") }
                LightTextInputEditor(
                    title = "Search",
                    state = textState,
                    onSubmit = { viewModel.submitSearch(it.toString()) },
                    onBack = viewModel::cancelSearch,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    submitIcon = LightIcons.SEARCH,
                    singleLine = true,
                    editorKey = "picker-search-${uiState.searchSession}",
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background),
                )
            } else {
                PickerContent(uiState)
            }
        }
    }

    @Composable
    private fun PickerContent(uiState: ExercisePickerViewModel.UiState) {
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
                center = LightTopBarCenter.Text("Exercises"),
                rightButton = LightBarButton.LightIcon(
                    icon = LightIcons.SEARCH,
                    onClick = { viewModel.openSearch() },
                ),
            )

            FilterRow(uiState)

            val query = uiState.searchQuery
            if (uiState.loaded && uiState.rows.isEmpty() && query == null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = "No exercises found",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        align = TextAlign.Center,
                    )
                }
            } else {
                // Inside: the scrollbar floats over the rows' 2gu end padding instead of taking 4gu of
                // every name once the list scrolls. Keyed, because the scroll view remembers its first
                // list state for the scrollbar: a new list gets a new view.
                key(uiState.listGeneration) {
                    LightLazyScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        scrollBarPosition = LightScrollBarPosition.Inside,
                        listState = listState.forKey(uiState.listGeneration),
                        uniformItemHeightGridUnits = EXERCISE_ROW_HEIGHT_UNITS,
                    ) {
                        if (query != null && uiState.loaded && uiState.rows.isEmpty()) {
                            item(key = NO_MATCH_KEY) { NoMatchRow() }
                        }
                        items(uiState.rows, key = { it.id }) { row ->
                            ExerciseRow(row = row, showMuscle = uiState.showMuscles, onClick = { goBack(row.id) })
                        }
                        // After the results, not instead of them: none may be the one he means.
                        if (query != null && uiState.loaded) {
                            val name = nameFromQuery(query)
                            item(key = CREATE_KEY) { CreateRow(name = name, onClick = { createExercise(name) }) }
                        }
                    }
                }
            }

            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.ADD,
                        contentDescription = "new exercise",
                        onClick = { createExercise(name = "") },
                    ),
                ),
            )
        }
    }

    /** Opens New Exercise with [name] filled in; the exercise he saves is the pick. */
    private fun createExercise(name: String) {
        navigateTo({ CustomExerciseScreen(it, initialName = name) }) { createdId -> goBack(createdId) }
    }

    /**
     * 2gu chip row (tokens.css-style superfine chips, active inverted): Recent (once he has logged
     * something), All, then the body parts in gym order. With a search active it shows the query as a
     * single inverted chip with an ×; tapping it clears the search. Every chip carries the same padding,
     * so the row doesn't shift when another is picked; the row still scrolls if a phone's text is wider.
     */
    @Composable
    private fun FilterRow(uiState: ExercisePickerViewModel.UiState) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FILTER_ROW_HEIGHT_UNITS.gridUnitsAsDp())
                .padding(horizontal = (1f - CHIP_PADDING_UNITS).gridUnitsAsDp())
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val query = uiState.searchQuery
            if (query != null) {
                // The × says what a tap does: it clears the search.
                FilterChip(label = "$query ×", active = true, onClick = { viewModel.clearSearch() })
            } else {
                val chips = buildList {
                    if (uiState.hasRecent) add(ExercisePickerViewModel.Chip.Recent to "Recent")
                    add(ExercisePickerViewModel.Chip.All to "All")
                    BodyPart.entries.forEach { add(ExercisePickerViewModel.Chip.Part(it) to it.label) }
                }
                chips.forEach { (chip, label) ->
                    FilterChip(label = label, active = chip == uiState.chip, onClick = { viewModel.selectChip(chip) })
                }
            }
        }
    }

    @Composable
    private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .lightClickable(onClick = onClick)
                .let { m -> if (active) m.background(LightThemeTokens.colors.content) else m }
                .padding(horizontal = CHIP_PADDING_UNITS.gridUnitsAsDp()),
        ) {
            LightText(
                text = label.uppercase(),
                variant = LightTextVariant.Superfine,
                maxLines = 1,
                color = if (active) {
                    LightThemeTokens.colors.background
                } else {
                    LightThemeTokens.colors.contentSecondary
                },
            )
        }
    }
}

private const val EXERCISE_ROW_HEIGHT_UNITS = 3f
private const val FILTER_ROW_HEIGHT_UNITS = 2f

/**
 * Each chip's padding, active or not, so the gap between chip names is 0.6gu: the eight chips then take
 * about 980 of the row's 1000px (at 1gu, CORE was cut at the edge).
 */
private const val CHIP_PADDING_UNITS = 0.3f

/** How many exercises Recent lists: a program's week and then some. */
private const val RECENT_LIMIT = 30
private const val ROW_DIVIDER_ALPHA = 0.3f

/**
 * Uniform 3gu catalog row (design .exrow): the name left, cut in the middle when too long so the words
 * that tell variants apart stay ("Decline Dumb…Flyes"); the primary muscle right in contentSecondary
 * when [showMuscle]; a full-width hairline divider at the bottom edge (inside the row so the
 * LightLazyScrollView uniform-height contract holds). The 2gu end padding holds the scrollbar.
 */
@Composable
private fun ExerciseRow(row: ExercisePickerViewModel.ExerciseRow, showMuscle: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(EXERCISE_ROW_HEIGHT_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 1f.gridUnitsAsDp(), end = 2f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = row.name,
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
            if (showMuscle) {
                Spacer(modifier = Modifier.width(0.5f.gridUnitsAsDp()))
                LightText(
                    text = row.muscleLabel,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 1,
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(LightThemeTokens.colors.contentSecondary.copy(alpha = ROW_DIVIDER_ALPHA)),
        )
    }
}

/** Lazy-list keys of the two rows that aren't exercises; exercise ids never look like these. */
private const val NO_MATCH_KEY = " no-match"
private const val CREATE_KEY = " create"

/** A search nothing answers: says so, as a row, so the Create row below still sits in the list. */
@Composable
private fun NoMatchRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(EXERCISE_ROW_HEIGHT_UNITS.gridUnitsAsDp())
            .padding(start = 1f.gridUnitsAsDp(), end = 2f.gridUnitsAsDp()),
        contentAlignment = Alignment.CenterStart,
    ) {
        LightText(text = "No exercises found", variant = LightTextVariant.Copy, lighten = true, maxLines = 1)
    }
}

/**
 * Last row of a search: 'Create "Hanging Knee Raise"' and a plus, opening New Exercise with that name
 * filled in (#058), so a lift the catalog lacks isn't typed twice.
 */
@Composable
private fun CreateRow(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(EXERCISE_ROW_HEIGHT_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick)
            .padding(start = 1f.gridUnitsAsDp(), end = 2f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = "Create \u201C$name\u201D",
            variant = LightTextVariant.Copy,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(0.5f.gridUnitsAsDp()))
        LightIcon(icon = LightIcons.ADD, size = 1.6f)
    }
}

/** "middle back" -> "Middle Back": display casing for raw dataset muscle/equipment/category values. */
internal fun titleCaseLabel(raw: String): String =
    raw.split(' ').joinToString(" ") { word ->
        word.replaceFirstChar { c -> c.uppercase() }
    }
