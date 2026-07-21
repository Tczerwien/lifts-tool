package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.data.LiftsDatabase
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.export.CsvExport
import com.tczerwien.lifts.export.JsonExport
import com.tczerwien.lifts.export.MarkdownExport
import com.tczerwien.lifts.export.buildSnapshot
import com.tczerwien.lifts.server.ExportServer
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.SectionLabel
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExportViewModel(
    private val repository: LiftsRepository,
    private val exportsDir: File,
) : LightViewModel<Unit>() {

    data class FileRow(val name: String, val detail: String)

    data class UiState(
        val files: List<FileRow> = emptyList(),
        val running: Boolean = false,
        /** LAN IPv4 while the server runs, e.g. "192.168.1.42". */
        val host: String? = null,
        /** contentSecondary status/error line shown while the server is off. */
        val message: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Guarded by synchronized(this); non-null exactly while a share session is live. */
    private var server: ExportServer? = null

    @Volatile
    private var foreground = false

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        foreground = true
        viewModelScope.launch(Dispatchers.IO) {
            if (ExportServer.FILE_NAMES.any { !File(exportsDir, it).isFile }) {
                writeExportFiles()
            }
            refreshFiles()
        }
    }

    // The share server is strictly foreground-only: LightOS tools have no background
    // execution, so leaving the screen or pausing the app (screen off, home) ends the
    // session. Shutdown must be synchronous here: on the back path the SDK destroys the
    // screen right after hiding it, cancelling viewModelScope before a launched coroutine
    // would get to run.
    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        foreground = false
        shutdownServer()
    }

    override fun onAppPause() {
        foreground = false
        shutdownServer()
    }

    override fun onCleared() {
        super.onCleared()
        foreground = false
        shutdownServer()
    }

    /** START — regenerates all export files, then serves them (design-card caption). */
    fun startSharing() {
        viewModelScope.launch(Dispatchers.IO) {
            val host = ExportServer.siteLocalAddress()
            if (host == null) {
                _uiState.update {
                    it.copy(message = "no wi-fi connection · join a network and try again")
                }
                return@launch
            }
            writeExportFiles()
            refreshFiles()
            val candidate = ExportServer(exportsDir)
            val claimed = synchronized(this@ExportViewModel) {
                if (server == null) {
                    server = candidate
                    true
                } else {
                    false
                }
            }
            if (!claimed) return@launch
            runCatching { candidate.start() }
                .onSuccess {
                    _uiState.update { it.copy(running = true, host = host, message = null) }
                    // Hidden/paused while the port was binding — do not keep serving.
                    if (!foreground) shutdownServer()
                }
                .onFailure {
                    synchronized(this@ExportViewModel) { if (server === candidate) server = null }
                    _uiState.update { it.copy(message = "could not start the server · try again") }
                }
        }
    }

    /** STOP bottom-bar button. */
    fun stopSharing() {
        viewModelScope.launch(Dispatchers.IO) { shutdownServer() }
    }

    private fun shutdownServer() {
        val live = synchronized(this) { server?.also { server = null } }
        live?.stop()
        _uiState.update { it.copy(running = false, host = null) }
    }

    /**
     * Builds one snapshot from the full repository and writes all four export files into
     * [exportsDir]. Storage is canonical kg; the generators own all formatting.
     */
    private suspend fun writeExportFiles() = withContext(Dispatchers.IO) {
        val workouts = repository.listWorkouts()
        val snapshot = buildSnapshot(
            exercises = repository.listExercises(),
            workouts = workouts,
            workoutExercises = workouts.flatMap { repository.exercisesForWorkout(it.id) },
            sets = workouts.flatMap { repository.setsForWorkout(it.id) },
            bodyweight = repository.listBodyweight(),
            bars = repository.listBars(),
            exportedAtIso = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            goals = null,
            injuries = emptyList(),
        )
        exportsDir.mkdirs()
        File(exportsDir, ExportServer.FILE_TRAINING_LOG).writeText(MarkdownExport.render(snapshot))
        File(exportsDir, ExportServer.FILE_WORKOUTS_JSON).writeText(JsonExport.render(snapshot))
        File(exportsDir, ExportServer.FILE_STRONG_CSV).writeText(CsvExport.render(snapshot))
        File(exportsDir, ExportServer.FILE_BODYWEIGHT_CSV).writeText(CsvExport.renderBodyweight(snapshot))
    }

    private fun refreshFiles() {
        val rows = ExportServer.FILE_NAMES.map { name ->
            val file = File(exportsDir, name)
            FileRow(
                name = name,
                detail = if (file.isFile) {
                    "updated ${Format.dateShort(file.lastModified())} · ${ExportServer.sizeLabel(file.length())}"
                } else {
                    "not generated yet"
                },
            )
        }
        _uiState.update { it.copy(files = rows) }
    }
}

/**
 * Export (design/screens/export.html): one row per generated export file, then a Wi-Fi
 * Share section. OFF shows a hint (or a no-wi-fi message) and a START bottom-bar button;
 * ON shows the http:// address, a hint and "server running", with STOP in the bottom bar.
 * Files regenerate on every START.
 */
class ExportScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, ExportViewModel>(sealedActivity) {

    private val repository = LiftsRepository.getInstance {
        lightContext.buildDatabase(LiftsDatabase::class.java, LiftsRepository.DATABASE_NAME)
    }

    override val viewModelClass: Class<ExportViewModel>
        get() = ExportViewModel::class.java

    override fun createViewModel() =
        ExportViewModel(repository, File(lightContext.filesDir, ExportServer.EXPORTS_DIR_NAME))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val uiState by viewModel.uiState.collectAsState()

        LightTheme(colors = themeColors) {
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
                            left = {
                                Column {
                                    LightText(
                                        text = file.name,
                                        variant = LightTextVariant.Copy,
                                        monospace = true,
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

                    SectionLabel("Wi-Fi Share")

                    val host = uiState.host
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 0.5f.gridUnitsAsDp()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (uiState.running && host != null) {
                            LightText(
                                text = "http://",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                            )
                            LightText(
                                text = "$host:${ExportServer.PORT}",
                                variant = LightTextVariant.Heading,
                                monospace = true,
                                maxLines = 1,
                            )
                            Spacer(modifier = Modifier.height(0.75f.gridUnitsAsDp()))
                            LightText(
                                text = "open on a device on the same network",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                align = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(0.75f.gridUnitsAsDp()))
                            LightText(
                                text = "SERVER RUNNING",
                                variant = LightTextVariant.Superfine,
                                lighten = true,
                            )
                        } else {
                            LightText(
                                text = uiState.message ?: "start the server to share over wi-fi",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                align = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp()),
                            )
                            Spacer(modifier = Modifier.height(0.75f.gridUnitsAsDp()))
                            LightText(
                                text = "SERVER OFF",
                                variant = LightTextVariant.Superfine,
                                lighten = true,
                            )
                        }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        if (uiState.running) {
                            LightBarButton.Text(text = "STOP", onClick = { viewModel.stopSharing() })
                        } else {
                            LightBarButton.Text(text = "START", onClick = { viewModel.startSharing() })
                        },
                    ),
                )
            }
        }
    }
}
