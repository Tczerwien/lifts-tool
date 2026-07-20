package com.tczerwien.lifts.export

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonExportTest {

    private val rendered = JsonExport.render(ExportFixtures.snapshot())
    private val decoded = Json.decodeFromString(JsonExportDocument.serializer(), rendered)

    @Test
    fun usesSnakeCaseFieldNames() {
        assertContains(rendered, "\"schema_version\"")
        assertContains(rendered, "\"exported_at\"")
        assertContains(rendered, "\"weight_kg\"")
        assertContains(rendered, "\"is_pr\"")
        assertContains(rendered, "\"bodyweight_log\"")
        assertContains(rendered, "\"weekly_sets_per_muscle\"")
    }

    @Test
    fun roundTripsTopLevelFields() {
        assertEquals("1.1", decoded.schemaVersion)
        assertEquals(ExportFixtures.EXPORTED_AT, decoded.exportedAt)
        assertEquals("kg", decoded.units.weight)
        assertEquals(ExportFixtures.GOALS, decoded.profile.goals)
        assertEquals(listOf("left knee"), decoded.profile.injuries)
        assertEquals(6, decoded.profile.bodyweightLog.size)
        assertEquals("2026-06-15", decoded.profile.bodyweightLog.first().date)
        assertEquals(82.0, decoded.profile.bodyweightLog.first().value)
    }

    @Test
    fun onlyReferencedExercisesAreExported() {
        assertEquals(listOf("bench-press-barbell"), decoded.exercises.map { it.id })
        val bench = decoded.exercises.single()
        assertEquals(listOf("chest"), bench.primaryMuscles)
        assertEquals(listOf("triceps", "shoulders"), bench.secondaryMuscles)
    }

    @Test
    fun workoutsAreNewestFirstWithFullSetData() {
        assertEquals(2, decoded.workouts.size)
        val newest = decoded.workouts.first()
        assertEquals(2L, newest.id)
        assertEquals("2026-07-13T10:00:00Z", newest.startTime)
        assertEquals(98, newest.durationMin)
        assertEquals("Push Day", newest.routine)
        assertEquals(80.5, newest.bodyweightKg)
        assertEquals("Felt strong", newest.notes)

        val exercise = newest.exercises.single()
        assertEquals("bench-press-barbell", exercise.exerciseId)
        assertEquals(ExportFixtures.INSTANCE_NOTES, exercise.notes)
        assertEquals(3, exercise.sets.size)

        val warmup = exercise.sets[0]
        assertEquals(1, warmup.index)
        assertEquals("warmup", warmup.type)
        assertEquals(false, warmup.isPr)

        val prSet = exercise.sets[1]
        assertEquals(2, prSet.index)
        assertEquals("normal", prSet.type)
        assertEquals(102.5, prSet.weightKg)
        assertEquals(5, prSet.reps)
        assertEquals(8.0, prSet.rpe)
        assertEquals(180, prSet.restSec)
        assertTrue(prSet.isPr, "102.5x5 beats the previous 100 kg max")

        assertEquals(false, exercise.sets[2].isPr)
    }

    @Test
    fun summaryHasPrsAndWeeklyVolume() {
        val pr = decoded.summary.prs.single()
        assertEquals("bench-press-barbell", pr.exerciseId)
        assertEquals("Bench Press (Barbell)", pr.name)
        assertEquals(102.5, pr.maxWeightKg)
        assertEquals("2026-07-13", pr.maxWeightDate)
        assertNotNull(pr.bestE1RmKg)
        assertEquals(119.58333333333333, pr.bestE1RmKg!!, 1e-9)
        assertEquals("2026-07-13", pr.bestE1RmDate)
        assertEquals("102.5x5", pr.bestSet)

        val chest = decoded.summary.weeklySetsPerMuscle.getValue("chest")
        assertEquals(8, chest.size)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 2.0, 2.0), chest)
    }

    @Test
    fun summaryHasBodyweightTrend() {
        assertContains(rendered, "\"current_kg\"")
        assertContains(rendered, "\"current_date\"")
        assertContains(rendered, "\"rate_kg_per_week_4w\"")
        assertContains(rendered, "\"weekly_avg_kg\"")

        val bodyweight = assertNotNull(decoded.summary.bodyweight)
        assertEquals(80.5, bodyweight.currentKg)
        assertEquals("2026-07-13", bodyweight.currentDate)
        // Least-squares over the five entries of 2026-06-17..07-14.
        assertEquals(-0.3034497090606799, assertNotNull(bodyweight.rateKgPerWeek4w), 1e-9)
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
