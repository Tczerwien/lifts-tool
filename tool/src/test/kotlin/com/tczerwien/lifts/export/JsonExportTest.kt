package com.tczerwien.lifts.export

import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.TemplateEntity
import com.tczerwien.lifts.data.TemplateExerciseEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonExportTest {

    private val rendered = JsonExport.render(ExportFixtures.snapshot())
    private val decoded = Json.decodeFromString(JsonExportDocument.serializer(), rendered)

    /** A custom exercise he took out of the picker, with a cue: in the file although no workout used it. */
    private val customRow = ExerciseEntity(
        id = "custom-1",
        name = "Cable Y-Raise",
        equipment = "cable",
        primaryMuscles = listOf("shoulders"),
        secondaryMuscles = emptyList(),
        category = "strength",
        level = null,
        instructions = emptyList(),
        isCustom = true,
        pinnedNote = "Thumbs up",
        hidden = true,
    )

    private val template = TemplateEntity(id = 1, name = "Push A", position = 0)

    private val templateSlots = listOf(
        TemplateExerciseEntity(
            id = 1, templateId = 1, exerciseId = ExportFixtures.bench.id, position = 0, targetSets = 3,
            restSecWork = 180, restSecWarmup = 90, warmupSets = 2, repMin = 5, repMax = 8, targetRir = 2, targetRirMax = 3,
            alternatives = listOf(ExportFixtures.squat.id, "gone-from-the-log"),
        ),
        TemplateExerciseEntity(id = 2, templateId = 1, exerciseId = customRow.id, position = 1, targetSets = 2),
        TemplateExerciseEntity(id = 3, templateId = 1, exerciseId = ExportFixtures.squat.id, position = 2, targetSets = 2, supersetGroup = 4),
        TemplateExerciseEntity(id = 4, templateId = 1, exerciseId = customRow.id, position = 3, targetSets = 2, supersetGroup = 4),
    )

    /**
     * The newest workout's bench done as a superset with a squat (2.4), aiming for 1–2 reps in reserve
     * (2.5), in a deload (2.5); the squat done instead of the custom exercise its template planned (2.6).
     */
    private val pairedSlots = ExportFixtures.slots.map {
        // The bench was its own slot's main: offered an alternative, but done as planned.
        if (it.id == 2L) it.copy(supersetGroup = 9, targetRir = 1, targetRirMax = 2, choices = listOf(it.exerciseId, ExportFixtures.squat.id)) else it
    } +
        WorkoutExerciseEntity(
            id = 3, workoutId = 2, exerciseId = ExportFixtures.squat.id, position = 1, supersetGroup = 9,
            choices = listOf(customRow.id, ExportFixtures.squat.id),
        )

    /**
     * Every optional field filled somewhere: a timed set, a template, a hidden custom exercise with a cue, a
     * superset, an RIR range, a deload, an alternative and a swap.
     */
    private val full = JsonExport.render(
        ExportFixtures.snapshot(
            workouts = listOf(ExportFixtures.workout1, ExportFixtures.workout2.copy(deload = true)),
            exercises = listOf(ExportFixtures.bench, ExportFixtures.squat, customRow),
            templates = listOf(template),
            templateExercises = templateSlots,
            slots = pairedSlots,
            sets = ExportFixtures.sets + SetEntity(
                id = 7, workoutExerciseId = 2, position = 3, type = "drop", weightKg = 60.0, durationSec = 40,
                completedAt = ExportFixtures.millis("2026-07-13T10:30:00Z"),
            ) + SetEntity(
                id = 8, workoutExerciseId = 3, position = 0, type = "normal", weightKg = 120.0, reps = 5,
                completedAt = ExportFixtures.millis("2026-07-13T10:20:00Z"),
            ),
        ),
    )

    @Test
    fun theKeyPathsAreTheSchema() {
        // The whole 2.0 contract (#123): a renamed field fails here even though the file still decodes.
        val expected = listOf(
            "exercises", "exercises[].equipment", "exercises[].hidden", "exercises[].id", "exercises[].is_custom",
            "exercises[].name", "exercises[].pinned_note", "exercises[].primary_muscles", "exercises[].secondary_muscles",
            "exercises[].type",
            "exported_at",
            "profile", "profile.bodyweight_log", "profile.bodyweight_log[].date", "profile.bodyweight_log[].weight_kg",
            "profile.equipment", "profile.equipment.bars", "profile.equipment.bars[].name",
            "profile.equipment.bars[].weight_kg", "profile.goals", "profile.injuries",
            "schema_version",
            "summary", "summary.bodyweight", "summary.bodyweight.current_date", "summary.bodyweight.current_kg",
            "summary.bodyweight.rate_kg_per_week_4w", "summary.bodyweight.rate_span_days",
            "summary.bodyweight.weekly_avg_kg",
            "summary.e1rm_trend", "summary.e1rm_trend.block_days", "summary.e1rm_trend.block_starts",
            "summary.e1rm_trend.lifts", "summary.e1rm_trend.lifts[].best_e1rm_kg", "summary.e1rm_trend.lifts[].exercise_id",
            "summary.e1rm_trend.lifts[].name",
            "summary.prs", "summary.prs[].best_e1rm_date", "summary.prs[].best_e1rm_kg", "summary.prs[].best_set_reps",
            "summary.prs[].best_set_weight_kg", "summary.prs[].exercise_id", "summary.prs[].max_weight_date",
            "summary.prs[].max_weight_kg", "summary.prs[].name",
            "summary.week_starts", "summary.weekly_sets_per_muscle",
            "templates", "templates[].exercises", "templates[].exercises[].alternatives",
            "templates[].exercises[].alternatives[].exercise_id", "templates[].exercises[].alternatives[].name",
            "templates[].exercises[].exercise_id", "templates[].exercises[].name",
            "templates[].exercises[].rep_max", "templates[].exercises[].rep_min", "templates[].exercises[].rest_seconds",
            "templates[].exercises[].superset_id", "templates[].exercises[].target_rir", "templates[].exercises[].target_rir_max",
            "templates[].exercises[].target_sets",
            "templates[].exercises[].warmup_rest_seconds", "templates[].exercises[].warmup_sets", "templates[].title",
            "timezone",
            "units", "units.display", "units.weight",
            "workouts", "workouts[].bodyweight_date", "workouts[].bodyweight_kg", "workouts[].deload", "workouts[].end_time",
            "workouts[].exercises", "workouts[].exercises[].exercise_id", "workouts[].exercises[].name",
            "workouts[].exercises[].notes", "workouts[].exercises[].planned_exercise_id", "workouts[].exercises[].superset_id",
            "workouts[].exercises[].target_rir", "workouts[].exercises[].target_rir_max",
            "workouts[].exercises[].sets", "workouts[].exercises[].sets[].completed_at",
            "workouts[].exercises[].sets[].duration_seconds", "workouts[].exercises[].sets[].index",
            "workouts[].exercises[].sets[].is_pr", "workouts[].exercises[].sets[].reps",
            "workouts[].exercises[].sets[].seconds_since_previous_set", "workouts[].exercises[].sets[].rpe",
            "workouts[].exercises[].sets[].type", "workouts[].exercises[].sets[].weight_kg",
            "workouts[].id", "workouts[].notes", "workouts[].start_time", "workouts[].title",
        )
        assertEquals(expected.sorted(), keyPaths(Json.parseToJsonElement(full)).sorted())
    }

    /** Every object key as a dotted path, arrays as "[]"; the muscle map's keys are data, not schema. */
    private fun keyPaths(element: JsonElement, prefix: String = ""): Set<String> = when (element) {
        is JsonObject -> element.flatMapTo(HashSet()) { (key, value) ->
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            if (path == "summary.weekly_sets_per_muscle") setOf(path) else setOf(path) + keyPaths(value, path)
        }
        is JsonArray -> element.flatMapTo(HashSet()) { keyPaths(it, "$prefix[]") }
        else -> emptySet()
    }

    @Test
    fun isOneCompactLine() {
        assertTrue('\n' !in rendered, "workouts.json is written without indentation")
    }

    @Test
    fun topLevelFields() {
        assertEquals("3.0", decoded.schemaVersion)
        assertEquals(ExportFixtures.EXPORTED_AT, decoded.exportedAt)
        assertEquals(JsonUnits(weight = "kg", display = "kg"), decoded.units)
        assertEquals(ExportFixtures.GOALS, decoded.profile.goals)
        assertEquals(listOf("left knee"), decoded.profile.injuries)
        assertEquals(6, decoded.profile.bodyweightLog.size)
        assertEquals(JsonBodyweightEntry("2026-06-15", 82.0), decoded.profile.bodyweightLog.first())
    }

    @Test
    fun profileCarriesEquipmentBars() {
        val bars = assertNotNull(decoded.profile.equipment, "fixture has bars -> profile.equipment").bars
        assertEquals(listOf("Olympic Bar", "Leg Press Sled"), bars.map { it.name })
        assertEquals(20.4117, bars[0].weightKg)
        assertEquals(53.5239, bars[1].weightKg)
    }

    @Test
    fun equipmentOmittedWithoutBars() {
        val without = JsonExport.render(ExportFixtures.snapshot(bars = emptyList()))
        assertTrue("\"bars\"" !in without, "no bars -> no profile.equipment")
        assertNull(Json.decodeFromString(JsonExportDocument.serializer(), without).profile.equipment)
    }

    @Test
    fun unusedCatalogExercisesStayOutButHisOwnGoIn() {
        assertEquals(listOf("bench-press-barbell"), decoded.exercises.map { it.id })
        val bench = decoded.exercises.single()
        assertEquals(listOf("chest"), bench.primaryMuscles)
        assertEquals(listOf("triceps", "shoulders"), bench.secondaryMuscles)
        assertEquals(false, bench.isCustom)

        // Squat has nothing of his in it; the custom exercise does, although no workout used it.
        val withCustom = Json.decodeFromString(
            JsonExportDocument.serializer(),
            JsonExport.render(ExportFixtures.snapshot(exercises = listOf(ExportFixtures.bench, ExportFixtures.squat, customRow))),
        )
        assertEquals(listOf("Bench Press (Barbell)", "Cable Y-Raise"), withCustom.exercises.map { it.name })
        val custom = withCustom.exercises.last()
        assertEquals(true, custom.isCustom)
        assertEquals(true, custom.hidden)
        assertEquals("Thumbs up", custom.pinnedNote)
    }

    @Test
    fun templatesAreKeptWithTheirPrescription() {
        val document = Json.decodeFromString(JsonExportDocument.serializer(), full)
        val push = document.templates.single()
        assertEquals("Push A", push.title)
        assertEquals(
            JsonTemplateExercise(
                exerciseId = ExportFixtures.bench.id, name = "Bench Press (Barbell)", targetSets = 3, warmupSets = 2,
                repMin = 5, repMax = 8, targetRir = 2, restSeconds = 180, warmupRestSeconds = 90, targetRirMax = 3,
                alternatives = listOf(JsonExerciseRef(ExportFixtures.squat.id, "Squat (Barbell)")),
            ),
            push.exercises.first(),
        )
        assertEquals(JsonTemplateExercise(exerciseId = "custom-1", name = "Cable Y-Raise", targetSets = 2), push.exercises[1])
        assertEquals(listOf(null, null, 1, 1), push.exercises.map { it.supersetId }, "the template's pair")
    }

    @Test
    fun supersetsAreNumberedWithinEachWorkout() {
        val document = Json.decodeFromString(JsonExportDocument.serializer(), full)
        assertEquals(listOf(1, 1), document.workouts.first().exercises.map { it.supersetId }, "bench and squat in turn")
        assertEquals(listOf(null), document.workouts.last().exercises.map { it.supersetId })
        // The squat went unticked: the bench left on its own is no superset in the file.
        val alone = JsonExport.render(ExportFixtures.snapshot(slots = pairedSlots))
        assertTrue("superset_id" !in alone, alone)
        // Nor when the squat is in the file only for its note.
        val noted = JsonExport.render(
            ExportFixtures.snapshot(slots = pairedSlots.map { if (it.id == 3L) it.copy(notes = "Rack taken") else it }),
        )
        assertTrue("Rack taken" in noted && "superset_id" !in noted, noted)
        // Nor when the squat was only warmed up.
        val warmedUp = JsonExport.render(
            ExportFixtures.snapshot(
                slots = pairedSlots,
                sets = ExportFixtures.sets + SetEntity(
                    id = 8, workoutExerciseId = 3, position = 0, type = "warmup", weightKg = 60.0, reps = 5,
                    completedAt = ExportFixtures.millis("2026-07-13T10:20:00Z"),
                ),
            ),
        )
        assertTrue("superset_id" !in warmedUp, warmedUp)
    }

    @Test
    fun alternativesAreNamedAndASwapSaysWhatWasPlanned() {
        val document = Json.decodeFromString(JsonExportDocument.serializer(), full)
        val bench = document.templates.single().exercises.first()
        assertEquals(listOf(ExportFixtures.squat.id), bench.alternatives?.map { it.exerciseId }, "one not in the log is left out")
        assertNull(document.templates.single().exercises[1].alternatives, "absent without any")
        val newest = document.workouts.first()
        assertEquals(listOf(null, customRow.id), newest.exercises.map { it.plannedExerciseId }, "the squat was done instead; the bench as planned")
        assertTrue("planned_exercise_id" !in rendered && "alternatives" !in rendered, "nothing written in a file without them")
        // An exercise the file names only as planned, or as an alternative, is in its exercises too.
        val swapped = Json.decodeFromString(
            JsonExportDocument.serializer(),
            JsonExport.render(
                ExportFixtures.snapshot(slots = ExportFixtures.slots.map { it.copy(choices = listOf(ExportFixtures.squat.id, it.exerciseId)) }),
            ),
        )
        assertEquals(listOf(ExportFixtures.squat.id), swapped.workouts.map { it.exercises.single().plannedExerciseId }.distinct())
        assertEquals(listOf(ExportFixtures.bench.id, ExportFixtures.squat.id), swapped.exercises.map { it.id })
        // A main not in the log (a restored file) names nothing: no id the exercises lack.
        val orphan = JsonExport.render(
            ExportFixtures.snapshot(slots = ExportFixtures.slots.map { it.copy(choices = listOf("gone-from-the-log", it.exerciseId)) }),
        )
        assertTrue("planned_exercise_id" !in orphan && "gone-from-the-log" !in orphan, orphan)
        val offered = Json.decodeFromString(
            JsonExportDocument.serializer(),
            JsonExport.render(
                ExportFixtures.snapshot(
                    templates = listOf(template),
                    templateExercises = listOf(templateSlots.first().copy(alternatives = listOf(ExportFixtures.squat.id))),
                ),
            ),
        )
        assertEquals(listOf(ExportFixtures.bench.id, ExportFixtures.squat.id), offered.exercises.map { it.id })
    }

    @Test
    fun aDeloadIsMarkedAndAnRirRangeHasItsTop() {
        val document = Json.decodeFromString(JsonExportDocument.serializer(), full)
        val (newest, oldest) = document.workouts
        assertEquals(true, newest.deload)
        assertNull(oldest.deload, "absent on a normal workout")
        assertEquals(1 to 2, newest.exercises.first().let { it.targetRir to it.targetRirMax }, "the template's targets, kept in a deload")
        assertEquals(2 to 3, document.templates.single().exercises.first().let { it.targetRir to it.targetRirMax })
        assertNull(document.templates.single().exercises[1].targetRirMax, "one number: no top")
        assertTrue("\"deload\"" !in rendered, "nothing written in a file without a deload")
        // A top that isn't above the number is one number.
        val flat = JsonExport.render(
            ExportFixtures.snapshot(slots = ExportFixtures.slots.map { if (it.id == 2L) it.copy(targetRir = 2, targetRirMax = 2) else it }),
        )
        assertTrue("target_rir_max" !in flat, flat)
    }

    @Test
    fun aMemberKeptOnlyForItsNoteDoesNotSplitTheOthers() {
        // Three in turn: bench, a noted squat that wasn't done, and the custom raise, both done.
        val slots = pairedSlots.map { if (it.id == 3L) it.copy(notes = "Rack taken") else it } +
            WorkoutExerciseEntity(id = 4, workoutId = 2, exerciseId = customRow.id, position = 2, supersetGroup = 9)
        val sets = ExportFixtures.sets + SetEntity(
            id = 8, workoutExerciseId = 4, position = 0, type = "normal", weightKg = 10.0, reps = 12,
            completedAt = ExportFixtures.millis("2026-07-13T10:20:00Z"),
        )
        val document = Json.decodeFromString(
            JsonExportDocument.serializer(),
            JsonExport.render(ExportFixtures.snapshot(exercises = listOf(ExportFixtures.bench, ExportFixtures.squat, customRow), slots = slots, sets = sets)),
        )
        assertEquals(listOf(1, null, 1), document.workouts.first().exercises.map { it.supersetId })
    }

    @Test
    fun workoutsAreNewestFirstWithFullSetData() {
        assertEquals(2, decoded.workouts.size)
        val newest = decoded.workouts.first()
        assertEquals(2L, newest.id)
        assertEquals("Push Day", newest.title)
        assertEquals("2026-07-13T10:00:00Z", newest.startTime)
        assertEquals("2026-07-13T11:38:00Z", newest.endTime)
        assertEquals(80.5, newest.bodyweightKg)
        assertEquals("2026-07-13", newest.bodyweightDate)
        assertEquals("Felt strong", newest.notes)

        val exercise = newest.exercises.single()
        assertEquals("bench-press-barbell", exercise.exerciseId)
        assertEquals("Bench Press (Barbell)", exercise.name)
        assertEquals(ExportFixtures.INSTANCE_NOTES, exercise.notes)
        assertEquals(3, exercise.sets.size)

        val warmup = exercise.sets[0]
        assertEquals(1, warmup.index)
        assertEquals("warmup", warmup.type)
        assertEquals(false, warmup.isPr)
        assertEquals(null, warmup.secondsSincePreviousSet, "the first set of a workout has no rest gap")
        assertEquals("2026-07-13T10:05:00Z", warmup.completedAt)

        val prSet = exercise.sets[1]
        assertEquals(JsonSet(2, "normal", 102.5, 5, null, 8.0, 600, "2026-07-13T10:15:00Z", isPr = true), prSet)
        assertEquals(false, exercise.sets[2].isPr)
    }

    @Test
    fun aDropSetIsHevysDropset() {
        val document = Json.decodeFromString(JsonExportDocument.serializer(), full)
        val drop = document.workouts.first().exercises.first().sets.last()
        assertEquals("dropset", drop.type)
        assertEquals(40, drop.durationSeconds)
    }

    @Test
    fun summaryHasPrsAndWeeklyVolume() {
        val pr = decoded.summary.prs.single()
        assertEquals("bench-press-barbell", pr.exerciseId)
        assertEquals("Bench Press (Barbell)", pr.name)
        assertEquals(102.5, pr.maxWeightKg)
        assertEquals("2026-07-13", pr.maxWeightDate)
        assertEquals(119.58333333333333, assertNotNull(pr.bestE1RmKg), 1e-9)
        assertEquals("2026-07-13", pr.bestE1RmDate)
        assertEquals(102.5, pr.bestSetWeightKg)
        assertEquals(5, pr.bestSetReps)

        val trend = decoded.summary.e1RmTrend
        assertEquals(28, trend.blockDays)
        assertEquals(listOf("2026-01-28", "2026-02-25", "2026-03-25", "2026-04-22", "2026-05-20", "2026-06-17"), trend.blockStarts)
        assertEquals(listOf(null, null, null, null, null, 102.5 * (1 + 5 / 30.0)), trend.lifts.single().bestE1RmKg)
        assertEquals("2026-05-25", decoded.summary.weekStarts.first())
        assertEquals("2026-07-13", decoded.summary.weekStarts.last())
        assertEquals(8, decoded.summary.weekStarts.size)
        val chest = decoded.summary.weeklySetsPerMuscle.getValue("chest")
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 2, 2), chest)
        // Direct counting (3.0): the bench's secondary muscles get nothing.
        assertEquals(setOf("chest"), decoded.summary.weeklySetsPerMuscle.keys)
    }

    @Test
    fun summaryHasBodyweightTrend() {
        val bodyweight = assertNotNull(decoded.summary.bodyweight)
        assertEquals(80.5, bodyweight.currentKg)
        assertEquals("2026-07-13", bodyweight.currentDate)
        // Least-squares over the five entries of 2026-06-17..07-14.
        assertEquals(-0.3034497090606799, assertNotNull(bodyweight.rateKgPerWeek4w), 1e-9)
        assertEquals(25, bodyweight.rateSpanDays, "2026-06-18 to 07-13")
        assertEquals(8, bodyweight.weeklyAvgKg.size)
        assertNull(bodyweight.weeklyAvgKg[0], "week without entries must decode as null")
        assertNull(bodyweight.weeklyAvgKg[5], "the gap week must stay null")
        assertEquals(81.9, bodyweight.weeklyAvgKg[3]!!, 1e-9)
        assertEquals(80.5, bodyweight.weeklyAvgKg[7]!!, 1e-9)
    }

    @Test
    fun summaryBodyweightOmittedWithoutData() {
        val without = JsonExport.render(ExportFixtures.snapshot(bodyweight = emptyList()))
        assertTrue("\"current_kg\"" !in without, "no bodyweight data -> no summary.bodyweight")
        assertNull(Json.decodeFromString(JsonExportDocument.serializer(), without).summary.bodyweight)
    }
}
