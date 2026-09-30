package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.export.ExportWriter
import com.tczerwien.lifts.toolmanager.liftsShare
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.SectionLabel
import com.tczerwien.lifts.ui.TWO_LINE_ROW_HEIGHT_UNITS
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ExportViewModel(
    private val writer: ExportWriter,
    private val exportsDir: File,
) : LightViewModel<Unit>() {

    data class FileRow(val name: String, val detail: String)

    data class UiState(
        val files: List<FileRow> = emptyList(),
        /** A run is going or queued: "preparing files…", REGENERATE off. */
        val preparing: Boolean = false,
        /** Why the latest run failed; the files listed are the ones from the run before. */
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // The file rows follow the writer: each run that ends (or fails) re-reads them.
        viewModelScope.launch(Dispatchers.IO) {
            writer.status.collect { status ->
                val rows = fileRows()
                _uiState.update {
                    it.copy(
                        files = rows,
                        preparing = status is ExportWriter.Status.Preparing,
                        error = (status as? ExportWriter.Status.Failed)?.message,
                    )
                }
            }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch(Dispatchers.IO) {
            if (ExportWriter.FILE_NAMES.any { !File(exportsDir, it).isFile }) writer.request()
            val rows = fileRows()
            _uiState.update { it.copy(files = rows) }
        }
    }

    /** REGENERATE: rewrites every file from the data as it is now. Taps during a run fold into one. */
    fun regenerate() {
        writer.request()
    }

    private fun fileRows(): List<FileRow> = ExportWriter.FILE_NAMES.map { name ->
        val file = File(exportsDir, name)
        FileRow(
            name = name,
            detail = if (file.isFile) {
                "updated ${Format.dateTime(file.lastModified())} · ${Format.fileSize(file.length())}"
            } else {
                "not written yet"
            },
        )
    }
}

/**
 * Export (design/screens/export.html): one row per export file, then where to get them. The files
 * live in files/shared/exports, which Tool Manager serves as Lifts › Exports; they are rewritten
 * whenever something in them changes (a workout, a weigh-in, a name, notes, a template, the unit),
 * and REGENERATE rewrites them now.
 */
class ExportScreen(
    private val sealedActivity: SealedLightActivity,
) : LightScreen<Unit, ExportViewModel>(sealedActivity) {

    private val share = lightContext.liftsShare()

    override val viewModelClass: Class<ExportViewModel>
        get() = ExportViewModel::class.java

    override fun createViewModel() = ExportViewModel(share.exports, share.exportsDir)

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()

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
                    center = LightTopBarCenter.Text("Export"),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    uiState.files.forEachIndexed { index, file ->
                        if (index > 0) RowDivider()
                        LiftsListRow(
                            heightUnits = TWO_LINE_ROW_HEIGHT_UNITS,
                            left = {
                                Column {
                                    LightText(
                                        text = file.name,
                                        variant = LightTextVariant.Copy,
                                        maxLines = 1,
                                    )
                                    LightText(
                                        text = file.detail,
                                        variant = LightTextVariant.Detail,
                                        lighten = true,
                                        maxLines = 1,
                                    )
                                }
                            },
                        )
                    }

                    SectionLabel("Tool Manager")
                    LightText(
                        text = "Download them on a computer: Tool Manager › Lifts › Exports. " +
                            "They are rewritten whenever your log changes.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                    val status = when {
                        uiState.preparing -> "preparing files…"
                        uiState.error != null -> "could not write the files · ${uiState.error}"
                        else -> null
                    }
                    if (status != null) {
                        LightText(
                            text = status,
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(
                                start = 1f.gridUnitsAsDp(),
                                end = 1f.gridUnitsAsDp(),
                                top = 0.75f.gridUnitsAsDp(),
                            ),
                        )
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(
                            text = "REGENERATE",
                            onClick = if (uiState.preparing) null else ({ viewModel.regenerate() }),
                        ),
                    ),
                )
            }
        }
    }
}
