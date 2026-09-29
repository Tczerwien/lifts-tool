package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.analytics.NotationStyle
import com.tczerwien.lifts.analytics.setNotation
import com.tczerwien.lifts.data.liftsRepository
import com.tczerwien.lifts.session.ActiveSession
import com.tczerwien.lifts.session.WorkoutSummary
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsFrame
import com.tczerwien.lifts.ui.WeightUnit
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How the lifter leaves Workout Complete. */
enum class WorkoutCompleteResult {
    /** Back to Home. */
    DONE,

    /** The Finish was a mistake: reopen the workout and carry on logging. */
    RESUME,
}

class WorkoutCompleteViewModel(
    private val session: ActiveSession,
    private val settingsStore: SettingsStore,
    private val workoutId: Long,
) : LightViewModel<WorkoutCompleteResult>() {

    data class UiState(
        val loaded: Boolean = false,
        val summary: WorkoutSummary? = null,
        val units: WeightUnit = SettingsStore.DEFAULT_UNITS,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    @Volatile
    private var loadStarted = false

    /** The summary of a finished workout never changes, so it is worked out once, on first show. */
    override fun onScreenShow(screen: SimpleLightScreen<WorkoutCompleteResult>) {
        if (loadStarted) return
        loadStarted = true
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = UiState(
                loaded = true,
                summary = session.summarize(workoutId),
                units = settingsStore.snapshot().units,
            )
        }
    }
}

/**
 * Post-finish summary (design/screens/workout-complete.html): duration, completed sets +
 * volume in display units, the New Records list (star per line), and the bodyweight line
 * when the workout logged one. No back chevron. DONE returns to Active Workout, which pops
 * straight on to Home; RESUME has Active Workout reopen the workout for more sets.
 */
class WorkoutCompleteScreen(
    private val sealedActivity: SealedLightActivity,
    private val workoutId: Long,
) : LightScreen<WorkoutCompleteResult, WorkoutCompleteViewModel>(sealedActivity) {

    private val repository = lightContext.liftsRepository()

    private val settingsStore = SettingsStore(lightContext.dataStore)

    private val session = ActiveSession(repository, settingsStore, lightContext.dataStore)

    override val viewModelClass: Class<WorkoutCompleteViewModel>
        get() = WorkoutCompleteViewModel::class.java

    override fun createViewModel() =
        WorkoutCompleteViewModel(session, settingsStore, workoutId)

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
                    center = uiState.summary?.let { LightTopBarCenter.Text(it.name) },
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    uiState.summary?.let { summary ->
                        SummaryBody(summary = summary, units = uiState.units)
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(
                            text = "RESUME",
                            contentDescription = "resume workout",
                            onClick = { goBack(WorkoutCompleteResult.RESUME) },
                        ),
                        LightBarButton.Text(
                            text = "DONE",
                            onClick = { goBack(WorkoutCompleteResult.DONE) },
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun SummaryBody(summary: WorkoutSummary, units: WeightUnit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LightText(text = "Workout Complete", variant = LightTextVariant.Heading)

        Gap(1.5f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono("${summary.durationSec / 60}", LightTextVariant.Copy)
            LightText(text = " min", variant = LightTextVariant.Copy)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono("${summary.completedSetCount}", LightTextVariant.Copy)
            LightText(text = " sets · ", variant = LightTextVariant.Copy)
            Mono(volumeDisplay(summary.totalVolumeKg, units), LightTextVariant.Copy)
            LightText(text = " ${units.suffix} volume", variant = LightTextVariant.Copy)
        }

        if (summary.prs.isNotEmpty()) {
            Gap(1.5f)
            LightText(text = "NEW RECORDS", variant = LightTextVariant.Superfine, lighten = true)
            Gap(0.5f)
            summary.prs.forEach { pr ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LightText(
                        text = "${pr.exerciseName} ",
                        variant = LightTextVariant.Copy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Mono(prSetDisplay(pr.weightKg, pr.reps, units), LightTextVariant.Copy)
                    LightIcon(
                        icon = LightIcons.STAR_OUTLINE,
                        size = 1.1f,
                        contentDescription = "personal record",
                        modifier = Modifier.padding(start = 0.4f.gridUnitsAsDp()),
                    )
                }
            }
        }

        summary.bodyweightKg?.let { bodyweightKg ->
            Gap(1.5f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                LightText(
                    text = "Bodyweight logged: ",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
                Mono(Format.bodyweight(bodyweightKg, units), LightTextVariant.Detail, lighten = true)
                LightText(
                    text = " ${units.suffix}",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
            }
        }
    }
}

@Composable
private fun Gap(units: Float) {
    Spacer(modifier = Modifier.height(units.gridUnitsAsDp()))
}

@Composable
private fun Mono(text: String, variant: LightTextVariant, lighten: Boolean = false) {
    LightText(
        text = text,
        variant = variant,
        lighten = lighten,
        monospace = true,
        maxLines = 1,
    )
}

/** "165 × 5", or "× 15" for a pull-up record ([setNotation]). */
private fun prSetDisplay(weightKg: Double?, reps: Int?, units: WeightUnit): String =
    setNotation(weightKg, reps, units, NotationStyle.SINGLE).orEmpty()

/** Volume in display units with a thousands separator, e.g. "12,440". */
private fun volumeDisplay(volumeKg: Double, units: WeightUnit): String =
    "%,d".format(Locale.US, Format.kgToDisplay(volumeKg, units).roundToLong())
