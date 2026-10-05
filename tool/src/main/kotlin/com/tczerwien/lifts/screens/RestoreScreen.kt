package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.backup.RestorePlan
import com.tczerwien.lifts.backup.Restorer
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.toolmanager.LiftsShare
import com.tczerwien.lifts.toolmanager.liftsShare
import com.tczerwien.lifts.ui.ArmedAction
import com.tczerwien.lifts.ui.ArmedHint
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.SectionLabel
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

class RestoreViewModel(
    private val share: LiftsShare,
    private val source: File,
) : LightViewModel<Unit>() {

    sealed interface Phase {
        /** Copying the file aside and reading it. */
        data object Checking : Phase

        /** Read and sound: what it holds next to what the phone holds, and RESTORE. */
        data class Ready(val backup: List<String>, val settings: String, val phone: List<String>) : Phase

        /** Turned down, with why. */
        data class Refused(val reason: String) : Phase

        data object Restoring : Phase

        data class Done(val lines: List<String>) : Phase

        /** Stopped before replacing anything. */
        data class Failed(val reason: String) : Phase
    }

    data class UiState(
        val fileName: String,
        val fileDetail: String = "",
        val phase: Phase = Phase.Checking,
        /** One-shot: the screen goes back (after SET ASIDE). */
        val leave: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState(fileName = source.name))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** RESTORE takes two taps. */
    val restoreArm = ArmedAction(viewModelScope)

    /** The checked backup, in memory until it is applied or the screen goes. */
    @Volatile
    private var prepared: Restorer.Prepared? = null

    @Volatile
    private var checked = false

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        if (checked) return
        checked = true
        viewModelScope.launch(Dispatchers.IO) {
            val detail = if (source.isFile) {
                "${Format.dateTime(source.lastModified())} · ${Format.fileSize(source.length())}"
            } else {
                ""
            }
            _uiState.update { it.copy(fileDetail = detail) }
            val phase = try {
                val ready = share.restorer.prepare(source)
                prepared = ready
                Phase.Ready(
                    backup = RestorePlan.lines(ready.summary),
                    settings = RestorePlan.settingsLine(ready.settingsRows),
                    phone = RestorePlan.lines(RestorePlan.summarize(share.repository.readAll())),
                )
            } catch (e: Restorer.Refused) {
                Phase.Refused(e.message.orEmpty())
            } catch (e: Exception) {
                Phase.Refused(e.message ?: e.toString())
            }
            _uiState.update { it.copy(phase = phase) }
        }
    }

    override fun onBackPressed(): Boolean {
        if (restoreArm.armed.value) {
            restoreArm.disarm()
            return true
        }
        return false
    }

    /**
     * RESTORE: the first tap arms, the second restores on the write lane, so it finishes even if the
     * lifter leaves the screen.
     */
    fun tapRestore() {
        val ready = prepared ?: return
        if (_uiState.value.phase !is Phase.Ready || !restoreArm.tap()) return
        _uiState.update { it.copy(phase = Phase.Restoring) }
        // The upload moves out of Restore in the same lane step, so a Home reload queued behind it
        // can't still offer it.
        LiftsIo.write({ runCatching { share.restorer.apply(ready).also { share.restored(source) } } }) { result ->
            val phase = result.fold(
                onSuccess = { outcome ->
                    prepared = null
                    Phase.Done(
                        buildList {
                            addAll(RestorePlan.lines(ready.summary))
                            add(
                                when {
                                    outcome.settingsProblem != null -> "the settings could not be restored: ${outcome.settingsProblem}"
                                    outcome.settingsRestored -> "settings restored"
                                    else -> "this phone's settings stay"
                                },
                            )
                            outcome.keysProblem?.let { add("the running rest and the exercise on screen could not be cleared (finish or discard any open workout): $it") }
                            add(
                                outcome.backupProblem?.let { "not backed up yet ($it): tap BACK UP NOW in Backups" }
                                    ?: "saved as the newest backup",
                            )
                            add("What was here before is in Backups as ${outcome.preRestore}.")
                        },
                    )
                },
                onFailure = { failure ->
                    val why = failure.message ?: failure.toString()
                    Phase.Failed(if (failure is Restorer.NothingChanged) "$why. Your log and settings are as they were." else why)
                },
            )
            _uiState.update { it.copy(phase = phase) }
        }
    }

    /** SET ASIDE on a refused upload: moves it out of the way, so Home stops offering it. */
    fun setAside() {
        LiftsIo.write({ share.setAside(source) }) { _uiState.update { it.copy(leave = true) } }
    }

    fun consumeLeave() {
        _uiState.update { it.copy(leave = false) }
    }
}

/**
 * Restore: opened with a backup (one of the phone's own, or a file uploaded to Tool Manager ›
 * Lifts › Restore). It first shows what the file holds beside what the phone holds; RESTORE (two
 * taps) saves a pre-restore backup, then replaces the whole log and the settings with the file's.
 * [path] is the file's absolute path; [fromUpload] offers SET ASIDE when the file is refused.
 */
class RestoreScreen(
    private val sealedActivity: SealedLightActivity,
    private val path: String,
    private val fromUpload: Boolean,
) : LightScreen<Unit, RestoreViewModel>(sealedActivity) {

    private val share = lightContext.liftsShare()

    override val viewModelClass: Class<RestoreViewModel>
        get() = RestoreViewModel::class.java

    override fun createViewModel() = RestoreViewModel(share, File(path))

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()
        val armed by viewModel.restoreArm.armed.collectAsState()

        LaunchedEffect(uiState.leave) {
            if (uiState.leave) {
                viewModel.consumeLeave()
                goBack()
            }
        }

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
                    center = LightTopBarCenter.Text("Restore"),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                        LightText(
                            text = uiState.fileName,
                            variant = LightTextVariant.Copy,
                            maxLines = 1,
                            // Cut in the middle: the date and extension at the end tell uploads apart.
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                        if (uiState.fileDetail.isNotEmpty()) {
                            LightText(text = uiState.fileDetail, variant = LightTextVariant.Detail, lighten = true)
                        }
                    }
                    when (val phase = uiState.phase) {
                        RestoreViewModel.Phase.Checking -> Note("checking the file…")
                        is RestoreViewModel.Phase.Ready -> {
                            SectionLabel("The backup holds")
                            Lines(phase.backup + phase.settings)
                            SectionLabel("This phone holds")
                            Lines(phase.phone)
                            Note(
                                "Restore replaces this phone's log with the backup's. " +
                                    "What is here now is saved first, as a pre-restore backup.",
                            )
                        }
                        is RestoreViewModel.Phase.Refused -> {
                            SectionLabel("Can't restore this file")
                            Lines(listOf(phase.reason))
                        }
                        RestoreViewModel.Phase.Restoring -> Note("restoring…")
                        is RestoreViewModel.Phase.Done -> {
                            SectionLabel("Restored")
                            Lines(phase.lines)
                        }
                        is RestoreViewModel.Phase.Failed -> {
                            SectionLabel("Restore failed")
                            Lines(listOf(phase.reason))
                        }
                    }
                }

                if (armed) ArmedHint("TAP AGAIN TO REPLACE EVERYTHING")
                val button = when (uiState.phase) {
                    is RestoreViewModel.Phase.Ready -> LightBarButton.Text(text = "RESTORE", onClick = { viewModel.tapRestore() })
                    is RestoreViewModel.Phase.Refused ->
                        if (fromUpload) LightBarButton.Text(text = "SET ASIDE", onClick = { viewModel.setAside() }) else null
                    is RestoreViewModel.Phase.Done -> LightBarButton.Text(text = "DONE", onClick = { goBack() })
                    else -> null
                }
                LightBottomBar(items = listOfNotNull(button))
            }
        }
    }

    @Composable
    private fun Lines(lines: List<String>) {
        Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
            lines.forEach { LightText(text = it, variant = LightTextVariant.Copy) }
        }
    }

    @Composable
    private fun Note(text: String) {
        LightText(
            text = text,
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(start = 1f.gridUnitsAsDp(), end = 1f.gridUnitsAsDp(), top = 1f.gridUnitsAsDp()),
        )
    }
}
