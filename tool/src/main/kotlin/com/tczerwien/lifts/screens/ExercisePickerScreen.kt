package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.LiftsDatabase
import com.tczerwien.lifts.data.LiftsRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ExercisePickerViewModel(
    private val repository: LiftsRepository,
) : LightViewModel<String>() {

    data class ExerciseRow(val id: String, val name: String, val muscleLabel: String)

    data class UiState(
        /** Distinct primary muscles of the catalog (raw dataset values, alphabetical). */
        val muscles: List<String> = emptyList(),
        /** Active muscle chip; null = ALL. */
        val selectedMuscle: String? = null,
        /** Active name search; null = none. Mutually exclusive with [selectedMuscle]. */
        val searchQuery: String? = null,
        val searchOpen: Boolean = false,
        val searchSession: Int = 0,
        val rows: List<ExerciseRow> = emptyList(),
        val loaded: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { refresh() }
    }

    override fun onScreenShow(screen: SimpleLightScreen<String>) {
        viewModelScope.launch(Dispatchers.IO) { refresh() }
    }

    /** Search editor consumes the system back gesture. */
    override fun onBackPressed(): Boolean {
        if (!_uiState.value.searchOpen) return false
        cancelSearch()
        return true
    }

    fun selectMuscle(muscle: String?) {
        _uiState.value = _uiState.value.copy(selectedMuscle = muscle, searchQuery = null)
        viewModelScope.launch(Dispatchers.IO) { refresh() }
    }

    fun openSearch() {
        val state = _uiState.value
        _uiState.value = state.copy(searchOpen = true, searchSession = state.searchSession + 1)
    }

    fun cancelSearch() {
        _uiState.value = _uiState.value.copy(searchOpen = false)
    }

    /** Blank submit clears the search; otherwise the query replaces any muscle filter. */
    fun submitSearch(raw: String) {
        val query = raw.trim().takeIf { it.isNotEmpty() }
        _uiState.value = _uiState.value.copy(
            searchOpen = false,
            searchQuery = query,
            selectedMuscle = null,
        )
        viewModelScope.launch(Dispatchers.IO) { refresh() }
    }

    fun clearSearch() {
        _uiState.value = _uiState.value.copy(searchQuery = null)
        viewModelScope.launch(Dispatchers.IO) { refresh() }
    }

    private fun refresh() {
        val all = repository.listExercises()
        val muscles = all.flatMap { it.primaryMuscles }.distinct().sorted()
        val state = _uiState.value
        val filtered = when {
            state.searchQuery != null -> repository.searchExercisesByName("%${state.searchQuery}%")
            state.selectedMuscle != null -> repository.exercisesByPrimaryMuscle(state.selectedMuscle)
            else -> all
        }
        _uiState.value = _uiState.value.copy(
            muscles = muscles,
            rows = filtered.map { it.toRow() },
            loaded = true,
        )
    }

    private fun ExerciseEntity.toRow() = ExerciseRow(
        id = id,
        name = name,
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
    sealedActivity: SealedLightActivity,
) : LightScreen<String, ExercisePickerViewModel>(sealedActivity) {

    private val repository = LiftsRepository.getInstance {
        lightContext.buildDatabase(LiftsDatabase::class.java, LiftsRepository.DATABASE_NAME)
    }

    override val viewModelClass: Class<ExercisePickerViewModel>
        get() = ExercisePickerViewModel::class.java

    override fun createViewModel() = ExercisePickerViewModel(repository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val uiState by viewModel.uiState.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
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

            if (uiState.loaded && uiState.rows.isEmpty()) {
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
                LightLazyScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    uniformItemHeightGridUnits = EXERCISE_ROW_HEIGHT_UNITS,
                ) {
                    items(uiState.rows, key = { it.id }) { row ->
                        ExerciseRow(row = row, onClick = { goBack(row.id) })
                    }
                }
            }

            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.ADD,
                        contentDescription = "new exercise",
                        onClick = {
                            navigateTo(::CustomExerciseScreen) { createdId -> goBack(createdId) }
                        },
                    ),
                ),
            )
        }
    }

    /**
     * 2gu chip row (tokens.css-style superfine chips, active inverted). With a search
     * active it shows the query as a single inverted chip; tapping the chip clears it.
     */
    @Composable
    private fun FilterRow(uiState: ExercisePickerViewModel.UiState) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FILTER_ROW_HEIGHT_UNITS.gridUnitsAsDp())
                .padding(horizontal = 1f.gridUnitsAsDp())
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val query = uiState.searchQuery
            if (query != null) {
                FilterChip(label = query, active = true, onClick = { viewModel.clearSearch() })
            } else {
                FilterChip(
                    label = "All",
                    active = uiState.selectedMuscle == null,
                    onClick = { viewModel.selectMuscle(null) },
                )
                uiState.muscles.forEach { muscle ->
                    FilterChip(
                        label = muscle,
                        active = muscle == uiState.selectedMuscle,
                        onClick = { viewModel.selectMuscle(muscle) },
                    )
                }
            }
        }
    }

    @Composable
    private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .lightClickable(onClick = onClick)
                .let { m ->
                    if (active) {
                        m
                            .background(LightThemeTokens.colors.content)
                            .padding(horizontal = 0.5f.gridUnitsAsDp())
                    } else {
                        m
                    }
                },
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
private const val ROW_DIVIDER_ALPHA = 0.3f

/**
 * Uniform 3gu catalog row (design .exrow): name left with ellipsis, primary muscle right
 * in contentSecondary, full-width hairline divider at the bottom edge (inside the row so
 * the LightLazyScrollView uniform-height contract holds).
 */
@Composable
private fun ExerciseRow(row: ExercisePickerViewModel.ExerciseRow, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(EXERCISE_ROW_HEIGHT_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = row.name,
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(0.5f.gridUnitsAsDp()))
            LightText(
                text = row.muscleLabel,
                variant = LightTextVariant.Detail,
                lighten = true,
                maxLines = 1,
            )
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

/** "middle back" -> "Middle Back": display casing for raw dataset muscle/equipment/category values. */
internal fun titleCaseLabel(raw: String): String =
    raw.split(' ').joinToString(" ") { word ->
        word.replaceFirstChar { c -> c.uppercase() }
    }
