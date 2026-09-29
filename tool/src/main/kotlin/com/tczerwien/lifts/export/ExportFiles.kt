package com.tczerwien.lifts.export

import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.session.LiftsIo
import com.tczerwien.lifts.settings.SettingsStore
import java.time.ZoneId

/**
 * The export files for everything in [repository], by file name. Every table is read in one read
 * transaction ([LiftsRepository.readAll]) on the write lane, so the files agree with each other and
 * include every save made before the request, even the few made off the lane; the files are then
 * built off the lane, so a long history never holds up a tick. Storage is canonical kg and the
 * generators own all formatting.
 */
suspend fun renderExports(repository: LiftsRepository, settings: SettingsStore): Map<String, String> {
    val (rows, unit) = LiftsIo.inLane { repository.readAll() to settings.snapshot().units }
    val snapshot = buildSnapshot(
        rows = rows,
        exportedAtMillis = System.currentTimeMillis(),
        zone = ZoneId.systemDefault(),
        unit = unit,
        goals = null,
        injuries = emptyList(),
    )
    return mapOf(
        ExportWriter.TRAINING_LOG to MarkdownExport.render(snapshot),
        ExportWriter.WORKOUTS_JSON to JsonExport.render(snapshot),
        ExportWriter.STRONG_CSV to CsvExport.render(snapshot),
        ExportWriter.BODYWEIGHT_CSV to CsvExport.renderBodyweight(snapshot),
    )
}
