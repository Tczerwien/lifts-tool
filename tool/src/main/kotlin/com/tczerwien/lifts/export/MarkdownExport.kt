package com.tczerwien.lifts.export

/**
 * Renders `training-log.md` — the markdown-first, AI-readable export: YAML front matter,
 * a precomputed summary (PRs, weekly sets per muscle, bodyweight), then per-workout
 * compact lifting notation ("102.5x5 @8 r180"), newest workout first.
 * All weights are kilograms.
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
        append("  weight: kg\n")
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
        append("\n## Summary\n")
        if (snapshot.prs.isNotEmpty()) {
            append("\n### Current PRs\n\n")
            for (pr in snapshot.prs) {
                append("- ").append(pr.exerciseName).append(": ")
                if (pr.bestE1RmKg != null) {
                    append("e1RM ").append(ExportFormat.rounded1(pr.bestE1RmKg))
                    append(" kg (").append(pr.bestE1RmDateIso).append(')')
                } else {
                    append("max ").append(ExportFormat.number(pr.maxWeightKg))
                    append(" kg (").append(pr.maxWeightDateIso).append(')')
                }
                append(" — best set ").append(pr.bestSetNotation).append('\n')
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
            append("- Current: ").append(ExportFormat.number(trend.currentKg))
            append(" kg (").append(trend.currentDateIso).append(")\n")
            trend.rateKgPerWeek?.let {
                append("- Rate: ").append(ExportFormat.signed2(it)).append(" kg/week over last 4 weeks\n")
            }
            append("- ").append(trend.weeklyAvgKg.size).append("-week avg: ")
            append(trend.weeklyAvgKg.joinToString(", ") { avg -> avg?.let { ExportFormat.rounded1(it) } ?: "—" })
            append('\n')
        }
    }

    private fun StringBuilder.appendWorkouts(snapshot: ExportSnapshot) {
        append("\n## Workouts\n")
        for (workout in snapshot.workouts) {
            append("\n### ").append(workout.dateIso).append(" — ").append(workout.name)
            val parts = mutableListOf<String>()
            workout.durationMin?.let { parts.add("${it}m") }
            workout.bodyweightKg?.let { parts.add("BW ${ExportFormat.number(it)} kg") }
            if (parts.isNotEmpty()) append(" (").append(parts.joinToString(", ")).append(')')
            append('\n')
            workout.notes?.let { append("\nNotes: ").append(it).append('\n') }
            for (exercise in workout.exercises) {
                append("\n- **").append(exercise.name).append("**")
                muscleTag(exercise)?.let { append(' ').append(it) }
                append('\n')
                append("  ").append(exercise.sets.joinToString("; ") { setNotation(it) }).append('\n')
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

    /**
     * Compact set notation: warmups prefixed "w" ("w60x8"), working sets "100x5",
     * " @8" when RPE is present, " r180" when rest was recorded, " — **PR**" on PR sets.
     * Reps-only sets render "x12", duration sets "45s".
     */
    private fun setNotation(set: SnapshotSet): String {
        val core = buildString {
            if (set.type == "warmup") append('w')
            val weight = set.weightKg?.let { ExportFormat.number(it) }
            when {
                weight != null && set.reps != null -> append(weight).append('x').append(set.reps)
                weight != null && set.durationSec != null -> append(weight).append('x').append(set.durationSec).append('s')
                weight != null -> append(weight)
                set.reps != null -> append('x').append(set.reps)
                set.durationSec != null -> append(set.durationSec).append('s')
                else -> append('-')
            }
            set.rpe?.let { append(" @").append(ExportFormat.number(it)) }
            set.restSec?.let { append(" r").append(it) }
        }
        return if (set.isPr) "$core — **PR**" else core
    }
}
