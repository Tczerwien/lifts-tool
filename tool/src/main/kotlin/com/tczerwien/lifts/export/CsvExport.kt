package com.tczerwien.lifts.export

/**
 * Renders `strong-compat.csv` — a Strong-iOS-compatible CSV for migrating history into
 * other trackers (Hevy, Reps, Arvo can all import this format).
 *
 * IMPORTANT: the Weight column is always KILOGRAMS (the canonical storage unit). The
 * Strong iOS format has no Weight Unit column, so importers assume the app's unit
 * setting — pick kg when importing.
 *
 * Format notes (mirroring Strong iOS exports):
 * - One row per completed set, chronological (oldest workout first).
 * - Date is the workout start time ("yyyy-MM-dd HH:mm:ss", UTC), repeated on every row.
 * - Duration is a human string ("1h 38m", "44m"), empty for in-progress workouts.
 * - Set types are NOT exported (Strong limitation); warm-ups appear as plain rows.
 * - Distance is always 0 (no distance cardio in v1); Seconds is durationSec or 0.
 * - Notes = exercise-instance notes, Workout Notes = workout notes, both repeated per row.
 * - RFC-4180 quoting: fields containing comma, quote, or newline are quoted and embedded
 *   quotes doubled. Rows are separated by "\n".
 */
object CsvExport {

    const val HEADER =
        "Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes,RPE"

    const val BODYWEIGHT_HEADER = "Date,Weight"

    fun render(snapshot: ExportSnapshot): String = buildString {
        append(HEADER).append('\n')
        // Snapshot is newest-first; Strong exports chronologically.
        for (workout in snapshot.workouts.asReversed()) {
            val date = ExportFormat.csvDateTime(workout.startEpochMillis)
            val duration = workout.durationMin?.let { formatDuration(it) } ?: ""
            for (exercise in workout.exercises) {
                for (set in exercise.sets) {
                    val fields = listOf(
                        date,
                        workout.name,
                        duration,
                        exercise.name,
                        set.index.toString(),
                        set.weightKg?.let { ExportFormat.number(it) } ?: "0",
                        (set.reps ?: 0).toString(),
                        "0",
                        (set.durationSec ?: 0).toString(),
                        exercise.notes ?: "",
                        workout.notes ?: "",
                        set.rpe?.let { ExportFormat.number(it) } ?: "",
                    )
                    append(fields.joinToString(",") { quote(it) }).append('\n')
                }
            }
        }
    }

    /**
     * Renders `bodyweight.csv` — one row per bodyweight entry, chronological (oldest
     * first). Date is yyyy-MM-dd (UTC); Weight is always KILOGRAMS, trailing zeros
     * trimmed ("82", "80.5"). Same RFC-4180 rules as [render].
     */
    fun renderBodyweight(snapshot: ExportSnapshot): String = buildString {
        append(BODYWEIGHT_HEADER).append('\n')
        for (point in snapshot.bodyweightLog) {
            val fields = listOf(point.dateIso, ExportFormat.number(point.weightKg))
            append(fields.joinToString(",") { quote(it) }).append('\n')
        }
    }

    /** "1h 38m" for 98 minutes, "44m" below one hour. */
    private fun formatDuration(minutes: Int): String {
        val hours = minutes / 60
        val remainder = minutes % 60
        return if (hours > 0) "${hours}h ${remainder}m" else "${remainder}m"
    }

    /** RFC-4180: quote when the field contains a comma, quote, or line break; double embedded quotes. */
    private fun quote(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
}
