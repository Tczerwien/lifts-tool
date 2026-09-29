package com.tczerwien.lifts.export

import com.tczerwien.lifts.analytics.plainNumber
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit

/**
 * Renders `strong.csv` — a Strong-iOS-compatible CSV for migrating history into
 * other trackers (Hevy, Reps, Arvo can all import this format).
 *
 * The Weight column is in the unit Lifts shows (Settings), rounded as the screens round it: 135,
 * not 61.2349…. Strong writes its own unit the same way and the format has no unit column, so
 * importers assume their unit setting; set the other app to the same unit before importing.
 *
 * Format notes (mirroring Strong iOS exports):
 * - One row per completed set, chronological (oldest workout first).
 * - Date is the workout start time in the phone's local time with no offset ("yyyy-MM-dd HH:mm:ss"),
 *   as Strong writes it, repeated on every row.
 * - Duration is a human string ("1h 38m", "44m"). Workouts still in progress are left out.
 * - Set types are NOT exported (Strong limitation); warm-ups appear as plain rows.
 * - Distance is always 0 (no distance cardio in v1); Seconds is durationSec or 0.
 * - Weight is signed: the help on an assisted exercise is negative ("-30"); no weight is 0.
 * - Notes = exercise-instance notes, Workout Notes = workout notes, both repeated per row.
 * - RFC-4180 quoting: fields containing comma, quote, or newline are quoted and embedded
 *   quotes doubled. Rows are separated by "\n".
 */
object CsvExport {

    const val HEADER =
        "Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes,RPE"


    fun render(snapshot: ExportSnapshot): String = buildString {
        append(HEADER).append('\n')
        val format = ExportFormat(snapshot.zone)
        val unit = snapshot.unit
        // Snapshot is newest-first; Strong exports chronologically.
        for (workout in snapshot.workouts.asReversed()) {
            val date = format.csvDateTime(workout.startEpochMillis)
            val duration = formatDuration(workout.durationMin)
            for (exercise in workout.exercises) {
                for (set in exercise.sets) {
                    val fields = listOf(
                        date,
                        workout.name,
                        duration,
                        exercise.name,
                        set.index.toString(),
                        // Signed: an assisted set's help is negative, as Strong writes it.
                        set.weightKg?.let { Format.weight(it, unit) } ?: "0",
                        (set.reps ?: 0).toString(),
                        "0",
                        (set.durationSec ?: 0).toString(),
                        exercise.notes ?: "",
                        workout.notes ?: "",
                        set.rpe?.let { plainNumber(it) } ?: "",
                    )
                    append(fields.joinToString(",") { quote(it) }).append('\n')
                }
            }
        }
    }

    /**
     * Renders `bodyweight.csv` — one row per bodyweight entry, chronological (oldest
     * first). Date is the local yyyy-MM-dd it was logged on; Weight is in the display unit, which the
     * header names ("Weight (lb)"), to 0.1 ("182.4", "82"). Same RFC-4180 rules as [render].
     */
    fun renderBodyweight(snapshot: ExportSnapshot): String = buildString {
        append(bodyweightHeader(snapshot.unit)).append('\n')
        for (point in snapshot.bodyweightLog) {
            val fields = listOf(point.dateIso, Format.bodyweight(point.weightKg, snapshot.unit))
            append(fields.joinToString(",") { quote(it) }).append('\n')
        }
    }

    /** "Date,Weight (lb)". */
    fun bodyweightHeader(unit: WeightUnit): String = "Date,Weight (${unit.suffix})"

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
