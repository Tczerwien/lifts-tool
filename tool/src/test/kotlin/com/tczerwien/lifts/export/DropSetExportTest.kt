package com.tczerwien.lifts.export

import com.tczerwien.lifts.data.SetEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A drop added from "…" › Drop Set goes in right under its set and the rows below move down (B16, DR-3), so
 * every file must write it right after its set, the set after it last, as on the screen: by position, not by
 * when the row was made. The files themselves are unchanged ("D", "dropset", plain Set Order).
 */
class DropSetExportTest {

    /** Workout 2's bench: the warm-up, 102.5 × 5, then a drop added under it (80 × 8), then 95 × 5 moved down. */
    private val withDrop = ExportFixtures.sets.map { if (it.id == 6L) it.copy(position = 3) else it } + SetEntity(
        id = 7, workoutExerciseId = 2, position = 2, type = "drop", weightKg = 80.0, reps = 8,
        completedAt = ExportFixtures.millis("2026-07-13T10:16:00Z"),
    )
    private val snapshot = ExportFixtures.snapshot(sets = withDrop)

    @Test
    fun theDropIsWrittenRightAfterItsSetInEveryFile() {
        val bench = MarkdownExport.render(snapshot).split("\n").first { it.startsWith("  ") && it.contains("102.5x5") }
        val order = listOf("102.5x5", "80x8D", "95x5").map { bench.indexOf(it) }
        assertTrue(order.all { it >= 0 } && order == order.sorted(), bench)

        val csv = CsvExport.render(snapshot).split("\n").filter { it.startsWith("2026-07-13") }.map { it.split(",") }
        assertEquals(listOf("60", "102.5", "80", "95"), csv.map { it[5] })
        assertEquals(listOf("1", "2", "3", "4"), csv.map { it[4] })

        val json = JsonExport.render(snapshot)
        val types = Regex("\"type\":\"(\\w+)\"").findAll(json.substringAfter("\"start_time\":\"2026-07-13")).map { it.groupValues[1] }.take(4).toList()
        assertEquals(listOf("warmup", "normal", "dropset", "normal"), types)
    }
}
