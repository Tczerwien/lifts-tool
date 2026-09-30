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
import com.tczerwien.lifts.backup.BackupManager
import com.tczerwien.lifts.backup.BackupPolicy
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BackupsViewModel(
    private val backups: BackupManager,
) : LightViewModel<Unit>() {

    data class Row(val name: String, val detail: String, val path: String)

    data class UiState(
        val rows: List<Row> = emptyList(),
        val loaded: Boolean = false,
        /** A backup is running or queued: "backing up…", BACK UP NOW off. */
        val running: Boolean = false,
        /** Why the latest backup failed; the ones listed are still good. */
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Bumped on each show, so the folder is read again through the same collector. */
    private val shown = MutableStateFlow(0L)

    init {
        // Every backup that lands, fails or changes the folder re-reads it, including the ones a
        // restore takes, which leave the status as it was.
        // One collector reads the folder, one read at a time, so an older listing can't land last.
        viewModelScope.launch(Dispatchers.IO) {
            combine(backups.status, backups.changes, shown) { status, _, _ -> status }.collect { status -> reload(status) }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        shown.update { it + 1 }
    }

    /** BACK UP NOW: a dated backup on the write lane, so it survives Back. */
    fun backUpNow() {
        backups.request()
    }

    private fun reload(status: BackupManager.Status) {
        val rows = backups.list().map { entry ->
            val detail = "${Format.dateTime(entry.writtenAt)} · ${Format.fileSize(entry.sizeBytes)}"
            Row(
                path = entry.file.absolutePath,
                name = entry.file.name,
                detail = when (entry.kind) {
                    BackupPolicy.Kind.DATED -> detail
                    BackupPolicy.Kind.PRE_RESTORE -> "before a restore · $detail"
                },
            )
        }
        _uiState.update {
            it.copy(
                rows = rows,
                loaded = true,
                running = status is BackupManager.Status.Running,
                error = (status as? BackupManager.Status.Failed)?.message,
            )
        }
    }
}

/**
 * Backups (Settings › Backups): the newest backups on the phone, where to download them, and BACK UP
 * NOW. A backup is the whole log plus the settings; Lifts takes one after every Finish and once a day
 * from Home, and keeps the newest ten.
 */
class BackupsScreen(
    private val sealedActivity: SealedLightActivity,
) : LightScreen<Unit, BackupsViewModel>(sealedActivity) {

    private val share = lightContext.liftsShare()

    override val viewModelClass: Class<BackupsViewModel>
        get() = BackupsViewModel::class.java

    override fun createViewModel() = BackupsViewModel(share.backups)

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
                    center = LightTopBarCenter.Text("Backups"),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    val status = when {
                        uiState.running -> "backing up…"
                        uiState.error != null -> "backup failed · ${uiState.error}"
                        uiState.loaded && uiState.rows.isEmpty() -> "no backup yet"
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
                                top = 0.5f.gridUnitsAsDp(),
                            ),
                        )
                    }

                    uiState.rows.forEachIndexed { index, row ->
                        if (index > 0) RowDivider()
                        LiftsListRow(
                            onClick = { navigateTo({ RestoreScreen(it, row.path, fromUpload = false) }) },
                            heightUnits = TWO_LINE_ROW_HEIGHT_UNITS,
                            left = {
                                Column {
                                    LightText(
                                        text = row.name,
                                        variant = LightTextVariant.Copy,
                                        maxLines = 1,
                                    )
                                    LightText(
                                        text = row.detail,
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
                        text = "Download them on a computer: Tool Manager › Lifts › Backups. " +
                            "Each one is the whole log and your settings; tap one to restore it.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(
                            text = "BACK UP NOW",
                            onClick = if (uiState.running) null else ({ viewModel.backUpNow() }),
                        ),
                    ),
                )
            }
        }
    }
}
