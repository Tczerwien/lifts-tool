package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.TemplateEntity
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.RowDivider
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
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
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Duration heuristic: ~3.5 min per working set, rounded to the nearest 5 min. */
private const val MINUTES_PER_WORKING_SET = 3.5
private const val ESTIMATE_ROUND_MINUTES = 5

/** Left slot caps at this fraction of the row so the reorder arrows never get squeezed out. */
private const val ROW_LEFT_WIDTH_FRACTION = 0.85f

private const val REORDER_TARGET_WIDTH_UNITS = 2f
private const val REORDER_TARGET_HEIGHT_UNITS = 1.5f
private const val REORDER_ICON_UNITS = 0.9f
private const val REORDER_DISABLED_ALPHA = 0.4f

class TemplatesViewModel(
    private val repository: LiftsRepository,
) : LightViewModel<Unit>() {

    sealed interface Mode {
        /** The template list. */
        data object Overview : Mode

        /** Full-screen SDK text editor collecting a new template's name. */
        data object NameEntry : Mode
    }

    data class TemplateRow(
        val id: Long,
        val name: String,
        val exerciseCount: Int,
        val estimatedMinutes: Int,
    )

    data class UiState(
        val templates: List<TemplateRow> = emptyList(),
        val mode: Mode = Mode.Overview,
        /** Bumped per editor entry so the embedded keyboard binds to the fresh field state. */
        val editorSession: Int = 0,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val mutationMutex = Mutex()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        mutate {}
    }

    fun openNameEntry() {
        _uiState.update {
            it.copy(mode = Mode.NameEntry, editorSession = it.editorSession + 1)
        }
    }

    fun cancelNameEntry() {
        _uiState.update { it.copy(mode = Mode.Overview) }
    }

    /**
     * Creates a template from the editor text (trimmed; blank cancels) and invokes
     * [onCreated] on Main with the new row id so the screen can push its editor.
     */
    fun createTemplate(raw: CharSequence, onCreated: (Long) -> Unit) {
        val name = raw.toString().trim()
        _uiState.update { it.copy(mode = Mode.Overview) }
        if (name.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val id = mutationMutex.withLock {
                val position = (repository.listTemplates().maxOfOrNull { it.position } ?: -1) + 1
                val newId = repository.addTemplate(TemplateEntity(name = name, position = position))
                refresh()
                newId
            }
            withContext(Dispatchers.Main) { onCreated(id) }
        }
    }

    /** Swaps the template with its neighbor ([delta] = ±1) and renumbers positions 0..n-1. */
    fun moveTemplate(id: Long, delta: Int) = mutate {
        val templates = repository.listTemplates().toMutableList()
        val index = templates.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in templates.indices) return@mutate
        templates[index] = templates[target].also { templates[target] = templates[index] }
        templates.forEachIndexed { position, template ->
            if (template.position != position) {
                repository.updateTemplate(template.copy(position = position))
            }
        }
    }

    override fun onBackPressed(): Boolean {
        if (_uiState.value.mode == Mode.NameEntry) {
            _uiState.update { it.copy(mode = Mode.Overview) }
            return true
        }
        return false
    }

    /** Runs [block] then a refresh serially on IO so read-modify-writes never interleave. */
    private fun mutate(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            mutationMutex.withLock {
                block()
                refresh()
            }
        }
    }

    private fun refresh() {
        val templates = repository.listTemplates().map { template ->
            val exercises = repository.exercisesForTemplate(template.id)
            TemplateRow(
                id = template.id,
                name = template.name,
                exerciseCount = exercises.size,
                estimatedMinutes = estimateMinutes(exercises.sumOf { it.targetSets }),
            )
        }
        _uiState.update { it.copy(templates = templates) }
    }

    /** [MINUTES_PER_WORKING_SET] per target set, rounded to the nearest 5 min (min 5). */
    private fun estimateMinutes(totalSets: Int): Int {
        if (totalSets <= 0) return 0
        val minutes = totalSets * MINUTES_PER_WORKING_SET
        return ((minutes / ESTIMATE_ROUND_MINUTES).roundToInt() * ESTIMATE_ROUND_MINUTES)
            .coerceAtLeast(ESTIMATE_ROUND_MINUTES)
    }
}

/**
 * Template list (design/screens/templates.html): uniform 3gu rows of name +
 * "N exercises · ~M min", up/down arrows swap order, tap opens the editor. The top-bar
 * and bottom-bar plus both open the SDK text editor (screen mode, weather idiom) for a
 * new template's name, then push straight into its editor.
 *
 * Constructor and result type are FROZEN (cross-referenced by other screens).
 */
class TemplatesScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, TemplatesViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    override val viewModelClass: Class<TemplatesViewModel>
        get() = TemplatesViewModel::class.java

    override fun createViewModel() = TemplatesViewModel(repository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val uiState by viewModel.uiState.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (uiState.mode) {
                    TemplatesViewModel.Mode.Overview -> Overview(uiState)

                    TemplatesViewModel.Mode.NameEntry -> {
                        val nameState = rememberTextFieldState("")
                        LightTextInputEditor(
                            title = "Template Name",
                            state = nameState,
                            onSubmit = { raw ->
                                viewModel.createTemplate(raw) { templateId ->
                                    navigateTo({ TemplateEditorScreen(it, templateId = templateId) })
                                }
                            },
                            onBack = viewModel::cancelNameEntry,
                            keyboardOptionsFlow = keyboardOptionsFlow,
                            singleLine = true,
                            editorKey = "template-name-${uiState.editorSession}",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Overview(uiState: TemplatesViewModel.UiState) {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = { goBack() },
                ),
                center = LightTopBarCenter.Text("Templates"),
                rightButton = LightBarButton.LightIcon(
                    icon = LightIcons.ADD,
                    contentDescription = "new template",
                    onClick = viewModel::openNameEntry,
                ),
            )

            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (uiState.templates.isEmpty()) {
                    LightText(
                        text = "No templates yet",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(1f.gridUnitsAsDp()),
                    )
                }

                uiState.templates.forEachIndexed { index, row ->
                    if (index > 0) RowDivider()
                    LiftsListRow(
                        onClick = {
                            navigateTo({ TemplateEditorScreen(it, templateId = row.id) })
                        },
                        left = {
                            Column(modifier = Modifier.fillMaxWidth(ROW_LEFT_WIDTH_FRACTION)) {
                                LightText(
                                    text = row.name,
                                    variant = LightTextVariant.Copy,
                                    maxLines = 1,
                                )
                                LightText(
                                    text = templateDetail(row),
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    maxLines = 1,
                                )
                            }
                        },
                        right = {
                            ReorderArrows(
                                upEnabled = index > 0,
                                downEnabled = index < uiState.templates.lastIndex,
                                onUp = { viewModel.moveTemplate(row.id, -1) },
                                onDown = { viewModel.moveTemplate(row.id, +1) },
                            )
                        },
                    )
                }
            }

            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.ADD,
                        contentDescription = "new template",
                        onClick = viewModel::openNameEntry,
                    ),
                ),
            )
        }
    }
}

/** "5 exercises · ~55 min"; the time is omitted while the template has no sets. */
private fun templateDetail(row: TemplatesViewModel.TemplateRow): String {
    val noun = if (row.exerciseCount == 1) "exercise" else "exercises"
    return if (row.estimatedMinutes > 0) {
        "${row.exerciseCount} $noun · ~${row.estimatedMinutes} min"
    } else {
        "${row.exerciseCount} $noun"
    }
}

/** Stacked up/down arrows (design .reorder cluster); disabled arrows dim to 40%. */
@Composable
private fun ReorderArrows(
    upEnabled: Boolean,
    downEnabled: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ReorderArrow(icon = LightIcons.UP, enabled = upEnabled, onClick = onUp)
        ReorderArrow(icon = LightIcons.DOWN, enabled = downEnabled, onClick = onDown)
    }
}

@Composable
private fun ReorderArrow(icon: LightIconConfiguration, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(
                width = REORDER_TARGET_WIDTH_UNITS.gridUnitsAsDp(),
                height = REORDER_TARGET_HEIGHT_UNITS.gridUnitsAsDp(),
            )
            .alpha(if (enabled) 1f else REORDER_DISABLED_ALPHA)
            .lightClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LightIcon(icon = icon, size = REORDER_ICON_UNITS)
    }
}
