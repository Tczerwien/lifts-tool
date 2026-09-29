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
        appendLegend(snapshot)
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

    /** How to read the file: the set notation, notes and dates, so an AI never has to guess. */
    private fun StringBuilder.appendLegend(snapshot: ExportSnapshot) {
        val unit = snapshot.unit.suffix
        val example = if (snapshot.unit == WeightUnit.LB) "225" else "100"
        append("\nHow to read this: `").append(example).append("x5` is ").append(example).append(' ').append(unit)
        append(" for 5 reps; `x12` is 12 reps with no weight; `45s` is seconds. A `w` before a set marks a warm-up,")
        append(" an `F` after it a set to failure, a `D` a drop set. `@8` is RPE. `r150` is the seconds since the")
        append(" previous set was ticked (the rest plus the set itself; none after a break over 15 minutes).")
        append(" `— **PR**` marks a set that was a personal record when it was lifted. Quoted lines (`>`) are his")
        append(" notes. Weights are in ").append(unit).append("; dates and times are local (").append(snapshot.zone.id).append(").\n")
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
        val trend = snapshot.e1RmTrend
        if (trend.lifts.isNotEmpty()) {
            append("\n### e1RM trend (best e1RM per ").append(trend.blockDays).append("-day block, ").append(unit.suffix)
            append(", oldest → newest)\n\n")
            append("Blocks start ").append(trend.blockStarts.first()).append(" … ").append(trend.blockStarts.last())
            append("; the last ends today. — means no set of 1–12 reps in that block.\n\n")
            for (lift in trend.lifts) {
                append("- ").append(lift.name).append(": ")
                append(lift.bestE1RmKg.joinToString(", ") { e1Rm -> e1Rm?.let { Format.weight(it, unit) } ?: "—" })
                append('\n')
            }
        }
        if (snapshot.weeklySetsPerMuscle.isNotEmpty()) {
            append("\n### Weekly sets per muscle (last ").append(snapshot.weekStarts.size).append(" weeks, oldest → newest)\n\n")
            appendWeeks(snapshot)
            append("Counting: each working set (warm-ups excluded) adds 1 to its exercise's primary muscles and 0.5")
            append(" to each secondary muscle. The muscles are free-exercise-db's 17 groups: shoulders = front, side")
            append(" and rear delts; most rows count as middle back, with half credit to lats.\n\n")
            for ((muscle, values) in snapshot.weeklySetsPerMuscle) {
                append("- ").append(muscle).append(": ")
                append(values.joinToString(", ") { ExportFormat.number(it) })
                append('\n')
            }
        }
        val bodyweight = snapshot.bodyweightTrend
        if (bodyweight != null) {
            append("\n### Bodyweight\n\n")
            append("- Current: ").append(Format.bodyweight(bodyweight.currentKg, unit))
            append(' ').append(unit.suffix).append(" (").append(bodyweight.currentDateIso).append(")\n")
            bodyweight.rateKgPerWeek?.let {
                append("- Rate: ").append(ExportFormat.signed2(Format.kgToDisplay(it, unit)))
                append(' ').append(unit.suffix).append("/week over last 4 weeks\n")
            }
            // Names its weeks itself: the weekly sets, which also list them, are left out without working sets.
            append("- Weekly average, weeks starting ").append(snapshot.weekStarts.first()).append(" … ")
            append(snapshot.weekStarts.last()).append(" (oldest → newest): ")
            append(bodyweight.weeklyAvgKg.joinToString(", ") { avg -> avg?.let { Format.bodyweight(it, unit) } ?: "—" })
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
            workout.notes?.let { append('\n').append(quote(it, indent = "")) }
            for (exercise in workout.exercises) {
                append("\n- **").append(exercise.name).append("**")
                muscleTag(exercise)?.let { append(' ').append(it) }
                append('\n')
                append("  ").append(exercise.sets.joinToString("; ") { notation(it, snapshot.unit) }).append('\n')
                exercise.notes?.let { append(quote(it, indent = "  ")) }
            }
        }
    }

    /** "Weeks start Monday 2026-05-25 … 2026-07-13; the last is this week, still in progress." */
    private fun StringBuilder.appendWeeks(snapshot: ExportSnapshot) {
        append("Weeks start Monday ").append(snapshot.weekStarts.first()).append(" … ").append(snapshot.weekStarts.last())
        append("; the last is this week, still in progress.\n\n")
    }

    /**
     * [text] as a blockquote, every line prefixed "> " after [indent], so a note's own lines can't
     * start a heading ("# …") or a list item ("- …") in the middle of the log.
     */
    private fun quote(text: String, indent: String): String =
        text.lines().joinToString("") { line -> indent + (if (line.isBlank()) ">" else "> ${line.trimEnd()}") + "\n" }

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
