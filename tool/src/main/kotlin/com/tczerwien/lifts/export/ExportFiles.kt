package com.tczerwien.lifts.export

import com.tczerwien.lifts.data.LiftsRepository
import com.tczerwien.lifts.session.LiftsIo
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The export files for everything in [repository], by file name. The tables are read together on the
 * write lane, so the files agree with each other and include every save made before the request;
 * storage is canonical kg and the generators own all formatting.
 */
suspend fun renderExports(repository: LiftsRepository): Map<String, String> {
    val snapshot = LiftsIo.inLane {
        val workouts = repository.listWorkouts()
        buildSnapshot(
            exercises = repository.listExercises(),
            workouts = workouts,
            workoutExercises = repository.listAllWorkoutExercises(),
            sets = repository.listAllSets(),
            bodyweight = repository.listBodyweight(),
            bars = repository.listBars(),
            exportedAtIso = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            goals = null,
            injuries = emptyList(),
        )
    }
    return mapOf(
        ExportWriter.TRAINING_LOG to MarkdownExport.render(snapshot),
        ExportWriter.WORKOUTS_JSON to JsonExport.render(snapshot),
        ExportWriter.STRONG_CSV to CsvExport.render(snapshot),
        ExportWriter.BODYWEIGHT_CSV to CsvExport.renderBodyweight(snapshot),
    )
}
