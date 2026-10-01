package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.data.TemplateEntity
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.library.reorderPositions
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.session.isDoubleTap
import com.tczerwien.lifts.toolmanager.LiftsShare
import com.tczerwien.lifts.toolmanager.liftsShare
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.ReorderArrows
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.RowName
import com.tczerwien.lifts.ui.TWO_LINE_ROW_HEIGHT_UNITS
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Duration heuristic: ~3.5 min per working set, rounded to the nearest 5 min. */
private const val MINUTES_PER_WORKING_SET = 3.5
private const val ESTIMATE_ROUND_MINUTES = 5

class TemplatesViewModel(
    private val repository: LiftsRepository,
    private val share: LiftsShare,
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
        /** The list has been read once: until then an empty list is not "No templates yet". */
        val loaded: Boolean = false,
        val templates: List<TemplateRow> = emptyList(),
        val mode: Mode = Mode.Overview,
        /** Bumped per editor entry so the embedded keyboard binds to the fresh field state. */
        val editorSession: Int = 0,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    @Volatile
    private var cleared = false

    /** In front (between onScreenShow and onScreenHide): a late create opens its editor only then. */
    @Volatile
    private var shown = false

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        shown = false
    }

    override fun onCleared() {
        cleared = true
        super.onCleared()
    }

    /**
     * Refreshes on the write lane, behind any write still pending, so a template just saved or
     * deleted shows as it is and an older refresh can never land after a newer one.
     */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        shown = true
        LiftsIo.write { refresh() }
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
     * Creates a template from the editor text (trimmed; blank cancels) on the write lane, so Back
     * can't drop it, and invokes [onCreated] on Main with the new row id so the screen can push its
     * editor, unless he has moved on meanwhile (another screen in front, or the name entry open again):
     * the create can wait its turn on the lane. workouts.json lists the templates, so the exports catch up.
     */
    fun createTemplate(raw: CharSequence, onCreated: (Long) -> Unit) {
        val name = raw.toString().trim()
        _uiState.update { it.copy(mode = Mode.Overview) }
        if (name.isEmpty()) return
        LiftsIo.write({
            val position = (repository.listTemplates().maxOfOrNull { it.position } ?: -1) + 1
            repository.addTemplate(TemplateEntity(name = name, position = position)).also { refresh() }
        }) { id ->
            share.logChanged()
            withContext(Dispatchers.Main) {
                if (!cleared && shown && _uiState.value.mode == Mode.Overview) onCreated(id)
            }
        }
    }

    /** When an arrow last moved a template: the move puts another row's arrows under his finger. */
    private var lastMoveAt: Long? = null

    /**
     * Swaps the template with its neighbor ([delta] = ±1), positions 0..n-1 ([reorderPositions]), on the
     * write lane (serial, survives Back); the exports catch up when anything moved. A tap within
     * [isDoubleTap] of the last move is its double tap or bounce, aimed at the row that was there: it
     * would move the other template (undoing the move), so it is dropped.
     */
    fun moveTemplate(id: Long, delta: Int) {
        val now = System.currentTimeMillis()
        if (lastMoveAt?.let { isDoubleTap(it, now) } == true) return
        lastMoveAt = now
        LiftsIo.write({
            val templates = repository.listTemplates()
            val moves = reorderPositions(templates, templates.indexOfFirst { it.id == id }, delta) { it.position }
            if (moves == null) {
                refresh()
                return@write false
            }
            repository.inTransaction {
                moves.forEach { (template, position) -> repository.updateTemplate(template.copy(position = position)) }
            }
            refresh()
            true
        }) { moved ->
            if (moved) share.logChanged()
        }
    }

    override fun onBackPressed(): Boolean {
        if (_uiState.value.mode == Mode.NameEntry) {
            _uiState.update { it.copy(mode = Mode.Overview) }
            return true
        }
        return false
    }

    /** Two queries whatever the number of templates: the list, and every template's counts at once. */
    private fun refresh() {
        val counts = repository.templateCounts()
        val templates = repository.listTemplates().map { template ->
            val count = counts[template.id]
            TemplateRow(
                id = template.id,
                name = template.name,
                exerciseCount = count?.exercises ?: 0,
                estimatedMinutes = estimateMinutes(count?.sets ?: 0),
            )
        }
        _uiState.update { it.copy(loaded = true, templates = templates) }
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
 * Template list (design/screens/templates.html): uniform 4gu rows of name +
 * "N exercises · ~M min", up/down arrows side by side swap order ([ReorderArrows]), tap opens the
 * editor. One plus, in the bottom bar where the thumb is (the mockup's top-bar twin went), opens the
 * SDK text editor (screen mode, weather idiom) for a new template's name, then pushes straight into
 * its editor.
 *
 * Constructor and result type are FROZEN (cross-referenced by other screens).
 */
class TemplatesScreen(
    private val sealedActivity: SealedLightActivity,
) : LightScreen<Unit, TemplatesViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    override val viewModelClass: Class<TemplatesViewModel>
        get() = TemplatesViewModel::class.java

    override fun createViewModel() = TemplatesViewModel(repository, lightContext.liftsShare())

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LiftsFrame(sealedActivity, lightContext, switchKey = uiState.mode::class) {
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
                            // A new template's name starts with a capital.
                            initialCaps = true,
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
            )

            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (uiState.loaded && uiState.templates.isEmpty()) {
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
                        heightUnits = TWO_LINE_ROW_HEIGHT_UNITS,
                        left = {
                            Column {
                                RowName(row.name)
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
