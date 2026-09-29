package com.tczerwien.lifts.export

import com.tczerwien.lifts.data.LiftsRows
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A late workout in Chicago (#023): Sunday 2026-07-12 at 23:30 local is already Monday 04:30 UTC. Every
 * file dates it Sunday, as History does, and counts it in Sunday's week.
 */
class TimeZoneExportTest {

    private val chicago = ZoneId.of("America/Chicago")

    private val sundayNight = WorkoutEntity(
        id = 1,
        name = "Late Push",
        startedAt = ExportFixtures.millis("2026-07-13T04:30:00Z"),
        endedAt = ExportFixtures.millis("2026-07-13T05:10:00Z"),
    )

    private val snapshot = buildSnapshot(
        rows = LiftsRows(
            exercises = listOf(ExportFixtures.bench),
            workouts = listOf(sundayNight),
            workoutExercises = listOf(WorkoutExerciseEntity(id = 1, workoutId = 1, exerciseId = ExportFixtures.bench.id, position = 0)),
            sets = listOf(
                SetEntity(
                    id = 1, workoutExerciseId = 1, position = 0, type = "normal",
                    weightKg = 100.0, reps = 5, completedAt = ExportFixtures.millis("2026-07-13T04:40:00Z"),
                ),
            ),
            templates = emptyList(),
            templateExercises = emptyList(),
            bodyweight = emptyList(),
            bars = emptyList(),
        ),
        exportedAtMillis = ExportFixtures.millis("2026-07-14T12:00:00Z"),
        zone = chicago,
        goals = null,
        injuries = emptyList(),
    )

    @Test
    fun markdownDatesItSundayAndSaysWhichZone() {
        val lines = MarkdownExport.render(snapshot).split("\n")
        assertContains(lines, "exported_at: 2026-07-14T07:00:00-05:00")
        assertContains(lines, "timezone: America/Chicago")
        assertTrue(lines.any { it.startsWith("### 2026-07-12 — Late Push") }, "the heading must carry the local day")
    }

    @Test
    fun csvWritesLocalWallClockTime() {
        val row = CsvExport.render(snapshot).split("\n")[1]
        assertTrue(row.startsWith("2026-07-12 23:30:00,Late Push,"), row)
    }

    @Test
    fun jsonCarriesTheOffsetAndTheZone() {
        val json = JsonExport.render(snapshot).replace(" ", "")
        assertContains(json, "\"timezone\":\"America/Chicago\"")
        assertContains(json, "\"start_time\":\"2026-07-12T23:30:00-05:00\"")
    }

    @Test
    fun theWeeklyCountLandsInSundaysWeek() {
        // Eight weeks ending Tuesday 2026-07-14: Sunday 07-12 is in the seventh (week of 07-06), not the last.
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0), snapshot.weeklySetsPerMuscle.getValue("chest"))
    }
}
