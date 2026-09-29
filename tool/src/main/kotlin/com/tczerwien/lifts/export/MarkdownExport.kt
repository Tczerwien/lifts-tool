package com.tczerwien.lifts.export

import com.tczerwien.lifts.analytics.NotationStyle
import com.tczerwien.lifts.analytics.setNotation
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit

/**
 * Renders `training-log.md` — the markdown-first, AI-readable export: YAML front matter,
 * a precomputed summary (PRs, weekly sets per muscle, bodyweight), then per-workout
 * compact lifting notation ("225x5 @8 r180", [setNotation]), newest workout first.
 * Weights are in the unit Lifts shows ([ExportSnapshot.unit]), rounded as the screens round them, so
 * a 135 lb set reads "135x8"; the front matter names the unit.
 */
object MarkdownExport {

    fun render(snapshot: ExportSnapshot): String = buildString {
        appendFrontMatter(snapshot)
        appendTitle(snapshot)
        appendSummary(snapshot)
        appendWorkouts(snapshot)
    }

    private fun StringBuilder.appendFrontMatter(snapshot: ExportSnapshot) {
        append("---\n")
        append("schema_version: \"").append(snapshot.schemaVersion).append("\"\n")
        append("exported_at: ").append(snapshot.exportedAtIso).append('\n')
        append("timezone: ").append(snapshot.zone.id).append('\n')
        append("units:\n")
        append("  weight: ").append(snapshot.unit.suffix).append('\n')
        if (snapshot.goals != null || snapshot.injuries.isNotEmpty()) {
            append("athlete:\n")
            snapshot.goals?.let { append("  goals: ").append(it).append('\n') }
            if (snapshot.injuries.isNotEmpty()) {
                append("  injuries:\n")
                for (injury in snapshot.injuries) append("    - ").append(injury).append('\n')
            }
        }
        append("---\n")
    }

    private fun StringBuilder.appendTitle(snapshot: ExportSnapshot) {
        val workouts = snapshot.workouts
        append('\n')
        if (workouts.isEmpty()) {
            append("# Training Log (0 workouts)\n")
            return
        }
        append("# Training Log — ")
        append(workouts.last().dateIso).append(" to ").append(workouts.first().dateIso)
        append(" (").append(workouts.size)
        append(if (workouts.size == 1) " workout)" else " workouts)")
        append('\n')
    }

    private fun StringBuilder.appendSummary(snapshot: ExportSnapshot) {
        val unit = snapshot.unit
        append("\n## Summary\n")
        if (snapshot.prs.isNotEmpty()) {
            append("\n### Current PRs\n\n")
            for (pr in snapshot.prs) {
                append("- ").append(pr.exerciseName).append(": ")
                if (pr.bestE1RmKg != null) {
                    append("e1RM ").append(Format.weight(pr.bestE1RmKg, unit))
                    append(' ').append(unit.suffix).append(" (").append(pr.bestE1RmDateIso).append(')')
                } else {
                    append("max ").append(Format.weight(pr.maxWeightKg, unit))
                    append(' ').append(unit.suffix).append(" (").append(pr.maxWeightDateIso).append(')')
                }
                setNotation(pr.bestSetWeightKg, pr.bestSetReps, unit, NotationStyle.EXPORT)?.let {
                    append(" — best set ").append(it)
                }
                append('\n')
            }
        }
        if (snapshot.weeklySetsPerMuscle.isNotEmpty()) {
            val weeks = snapshot.weeklySetsPerMuscle.values.first().size
            append("\n### Weekly sets per muscle (last ").append(weeks).append(" weeks)\n\n")
            for ((muscle, values) in snapshot.weeklySetsPerMuscle) {
                append("- ").append(muscle).append(": ")
                append(values.joinToString(", ") { ExportFormat.number(it) })
                append('\n')
            }
        }
        val trend = snapshot.bodyweightTrend
        if (trend != null) {
            append("\n### Bodyweight\n\n")
            append("- Current: ").append(Format.bodyweight(trend.currentKg, unit))
            append(' ').append(unit.suffix).append(" (").append(trend.currentDateIso).append(")\n")
            trend.rateKgPerWeek?.let {
                append("- Rate: ").append(ExportFormat.signed2(Format.kgToDisplay(it, unit)))
                append(' ').append(unit.suffix).append("/week over last 4 weeks\n")
            }
            append("- ").append(trend.weeklyAvgKg.size).append("-week avg: ")
            append(trend.weeklyAvgKg.joinToString(", ") { avg -> avg?.let { Format.bodyweight(it, unit) } ?: "—" })
            append('\n')
        }
    }

    private fun StringBuilder.appendWorkouts(snapshot: ExportSnapshot) {
        append("\n## Workouts\n")
        for (workout in snapshot.workouts) {
            append("\n### ").append(workout.dateIso).append(" — ").append(workout.name)
            val parts = mutableListOf("${workout.durationMin}m")
            workout.bodyweight?.let { bodyweight ->
                val weight = "BW ${Format.bodyweight(bodyweight.weightKg, snapshot.unit)} ${snapshot.unit.suffix}"
                parts.add(if (bodyweight.sameDay) weight else "$weight from ${bodyweight.dateIso}")
            }
            if (parts.isNotEmpty()) append(" (").append(parts.joinToString(", ")).append(')')
            append('\n')
            workout.notes?.let { append("\nNotes: ").append(it).append('\n') }
            for (exercise in workout.exercises) {
                append("\n- **").append(exercise.name).append("**")
                muscleTag(exercise)?.let { append(' ').append(it) }
                append('\n')
                append("  ").append(exercise.sets.joinToString("; ") { notation(it, snapshot.unit) }).append('\n')
                exercise.notes?.let { append("  Notes: ").append(it).append('\n') }
            }
        }
    }

    /** "[chest; triceps, shoulders]", "[chest]", or null when no muscles are known. */
    private fun muscleTag(exercise: SnapshotWorkoutExercise): String? {
        val groups = listOf(
            exercise.primaryMuscles.joinToString(", "),
            exercise.secondaryMuscles.joinToString(", "),
        ).filter { it.isNotEmpty() }
        if (groups.isEmpty()) return null
        return groups.joinToString("; ", prefix = "[", postfix = "]")
    }

    /** One set ([setNotation], with the rest gap) and " — **PR**" when it was a record when lifted. */
    private fun notation(set: SnapshotSet, unit: WeightUnit): String {
        val core = setNotation(
            weightKg = set.weightKg,
            reps = set.reps,
            unit = unit,
            style = NotationStyle.EXPORT,
            type = set.type,
            durationSec = set.durationSec,
            rpe = set.rpe,
            restSec = set.restSec,
        ) ?: "-"
        return if (set.isPr) "$core — **PR**" else core
    }
}
