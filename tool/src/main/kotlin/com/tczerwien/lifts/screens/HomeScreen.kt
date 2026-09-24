package com.tczerwien.lifts.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.tczerwien.lifts.data.LiftsDatabase
import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.screens.activeworkout.ActiveWorkoutScreen
import com.tczerwien.lifts.seed.Seeder
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.LiftsListRow
import com.tczerwien.lifts.ui.RowDivider
import com.tczerwien.lifts.ui.SectionLabel
import com.tczerwien.lifts.ui.rememberBarChartIconPainter
import com.tczerwien.lifts.ui.rememberScaleIconPainter
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: LiftsRepository,
    private val database: LiftsDatabase,
) : LightViewModel<Unit>() {

    data class TemplateRow(val id: Long, val name: String, val exerciseCount: Int)

    data class UnfinishedRow(val workoutId: Long, val name: String, val startedAt: Long)

    data class RecentRow(val workoutId: Long, val name: String, val dateMillis: Long, val setCount: Int)

    data class UiState(
        val templates: List<TemplateRow> = emptyList(),
        val unfinished: UnfinishedRow? = null,
        val recent: List<RecentRow> = emptyList(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            Seeder.seedIfEmpty(database.exerciseDao, database.barDao)
            refresh()
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch(Dispatchers.IO) { refresh() }
    }

    private fun refresh() {
        val templates = repository.listTemplates().map { template ->
            TemplateRow(
                id = template.id,
                name = template.name,
                exerciseCount = repository.exercisesForTemplate(template.id).size,
            )
        }
        val workouts = repository.listWorkouts()
        val unfinished = workouts.firstOrNull { it.endedAt == null }?.let { workout ->
            UnfinishedRow(workoutId = workout.id, name = workout.name, startedAt = workout.startedAt)
        }
        val recent = workouts.filter { it.endedAt != null }.take(2).map { workout ->
            RecentRow(
                workoutId = workout.id,
                name = workout.name,
                dateMillis = workout.endedAt ?: workout.startedAt,
                setCount = repository.setsForWorkout(workout.id).count { it.completedAt != null },
            )
        }
        _uiState.value = UiState(templates = templates, unfinished = unfinished, recent = recent)
    }
}

/**
 * Home (design/screens/home.html): start a template, an empty workout, or resume; two
 * most recent finished workouts; bottom bar to History / Bodyweight / Consistency /
 * Settings. Top-bar right (pencil) manages templates.
 */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {

    private val database: LiftsDatabase = obtainDatabase {
        lightContext.buildDatabase(LiftsDatabase::class.java, LiftsRepository.DATABASE_NAME)
    }

    private val repository = LiftsRepository.getInstance { database }

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel() = HomeViewModel(repository, database)

    companion object {
        @Volatile
        private var databaseInstance: LiftsDatabase? = null

        /** Process-wide LiftsDatabase singleton so recreation never opens a second Room instance. */
        private fun obtainDatabase(create: () -> LiftsDatabase): LiftsDatabase {
            return databaseInstance ?: synchronized(this) {
                databaseInstance ?: create().also { databaseInstance = it }
            }
        }
    }

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
                    center = LightTopBarCenter.Text("LIFTS"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.PENCIL,
                        onClick = { navigateTo(::TemplatesScreen) },
                    ),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    uiState.unfinished?.let { unfinished ->
                        SectionLabel("In Progress")
                        LiftsListRow(
                            onClick = {
                                navigateTo({ ActiveWorkoutScreen(it, workoutId = unfinished.workoutId) })
                            },
                            left = {
                                LightText(
                                    text = unfinished.name,
                                    variant = LightTextVariant.Copy,
                                    maxLines = 1,
                                )
                            },
                            right = {
                                LightText(
                                    text = "Resume · ${Format.dateShort(unfinished.startedAt)}",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    maxLines = 1,
                                )
                            },
                        )
                    }

                    SectionLabel("Start")

                    uiState.templates.forEach { template ->
                        LiftsListRow(
                            onClick = {
                                navigateTo({ ActiveWorkoutScreen(it, templateId = template.id) })
                            },
                            left = {
                                LightText(
                                    text = template.name,
                                    variant = LightTextVariant.Copy,
                                    maxLines = 1,
                                )
                            },
                            right = {
                                LightText(
                                    text = "${template.exerciseCount} exercises",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    maxLines = 1,
                                )
                            },
                        )
                        RowDivider()
                    }

                    LiftsListRow(
                        onClick = { navigateTo({ ActiveWorkoutScreen(it) }) },
                        left = {
                            LightText(
                                text = "Empty Workout",
                                variant = LightTextVariant.Copy,
                                lighten = true,
                                maxLines = 1,
                            )
                        },
                        right = { LightIcon(icon = LightIcons.ADD, size = 1.6f) },
                    )

                    if (uiState.recent.isNotEmpty()) {
                        SectionLabel("Recent")
                        uiState.recent.forEachIndexed { index, recent ->
                            if (index > 0) RowDivider()
                            LiftsListRow(
                                onClick = {
                                    navigateTo({ WorkoutDetailScreen(it, workoutId = recent.workoutId) })
                                },
                                left = {
                                    LightText(
                                        text = recent.name,
                                        variant = LightTextVariant.Copy,
                                        maxLines = 1,
                                    )
                                },
                                right = {
                                    LightText(
                                        text = "${Format.dateShort(recent.dateMillis)} · ${recent.setCount} sets",
                                        variant = LightTextVariant.Detail,
                                        lighten = true,
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.LIST,
                            contentDescription = "history",
                            onClick = { navigateTo(::HistoryScreen) },
                        ),
                        LightBarButton.Icon(
                            painter = rememberScaleIconPainter(),
                            contentDescription = "bodyweight",
                            onClick = { navigateTo(::BodyweightScreen) },
                        ),
                        LightBarButton.Icon(
                            painter = rememberBarChartIconPainter(),
                            contentDescription = "consistency",
                            onClick = { navigateTo(::ConsistencyScreen) },
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.ELLIPSES,
                            contentDescription = "settings",
                            onClick = { navigateTo(::SettingsScreen) },
                        ),
                    ),
                )
            }
        }
    }
}
