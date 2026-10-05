package com.tczerwien.lifts.export

import com.tczerwien.lifts.analytics.NotationStyle
import com.tczerwien.lifts.analytics.setNotation
import com.tczerwien.lifts.session.repRangeText
import com.tczerwien.lifts.session.rirTarget
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
        append(" an `F` after it a set to failure, a `D` a drop set. `@8` is RPE, logged as reps in reserve (RPE = 10 − RIR,")
        append(" so `@8` left 2 reps in the tank). `r150` is the seconds since the")
        append(" previous set was ticked (the rest plus the set itself; none after a break over 15 minutes).")
        append(" On bodyweight exercises the weight is what was added to the body, and a negative weight is")
        append(" help (`-30x8` is an assisted pull-up with 30 ").append(unit).append(" of assistance).")
        append(" `x10/side` is 10 reps on each side, one side at a time (a split squat); a dumbbell weight is one dumbbell's.")
        append(" `(target 6–12 reps, RIR 1–2)` after an exercise is what its template asked for that day.")
        append(" `deload` in a workout's heading marks a deload: fewer work sets at an easier effort (more reps in")
        append(" reserve) than the targets shown, which are its template's.")
        append(" `(superset 1)` marks the exercises done in turn that day as superset 1: one set of each, then the")
        append(" rest after the round.")
        append(" `(instead of Leg Curl)` after an exercise means it was done in place of the one its template planned,")
        append(" an alternative (a workout started again from another keeps that one's plan).")
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
                } else if (pr.maxWeightKg < 0.0) {
                    append("least assistance ").append(Format.weight(-pr.maxWeightKg, unit))
                    append(' ').append(unit.suffix).append(" (").append(pr.maxWeightDateIso).append(')')
                } else if (pr.maxWeightKg == 0.0) {
                    append("unassisted (").append(pr.maxWeightDateIso).append(')')
                } else {
                    append("max ").append(Format.weight(pr.maxWeightKg, unit))
                    append(' ').append(unit.suffix).append(" (").append(pr.maxWeightDateIso).append(')')
                }
                setNotation(pr.bestSetWeightKg, pr.bestSetReps, unit, NotationStyle.EXPORT, perSide = pr.perSide)?.let {
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
            append("Counting: hard sets, as Lifts' Sets per Muscle screen counts them. Each ticked working set adds 1 to")
            append(" each muscle its exercise counts for, its primary muscles; a warm-up adds 0, and so does a drop set (`D`),")
            append(" part of the set before it. Secondary muscles add nothing, and a deload's sets count. The muscles are")
            append(" free-exercise-db's 17 groups: shoulders = front, side and rear delts; most rows count as middle back.\n\n")
            for ((muscle, values) in snapshot.weeklySetsPerMuscle) {
                append("- ").append(muscle).append(": ")
                append(values.joinToString(", "))
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
                append(' ').append(unit.suffix).append("/week over ").append(bodyweight.rateSpanDays ?: 0)
                append(" days of weigh-ins\n")
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
            if (workout.deload) parts.add(0, "deload")
            workout.bodyweight?.let { bodyweight ->
                val weight = "BW ${Format.bodyweight(bodyweight.weightKg, snapshot.unit)} ${snapshot.unit.suffix}"
                parts.add(if (bodyweight.sameDay) weight else "$weight from ${bodyweight.dateIso}")
            }
            if (parts.isNotEmpty()) append(" (").append(parts.joinToString(", ")).append(')')
            append('\n')
            workout.notes?.let { append('\n').append(quote(it, indent = "")) }
            for (exercise in workout.exercises) {
                append("\n- **").append(exercise.name).append("**")
                exercise.planned?.let { append(" (instead of ").append(it.name).append(')') }
                exercise.supersetId?.let { append(" (superset ").append(it).append(')') }
                muscleTag(exercise)?.let { append(' ').append(it) }
                targetTag(exercise)?.let { append(' ').append(it) }
                append('\n')
                if (exercise.sets.isNotEmpty()) {
                    append("  ").append(exercise.sets.joinToString("; ") { notation(it, snapshot.unit, exercise.perSide) }).append('\n')
                }
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

    /** "(target 6–12 reps, RIR 1–2)", "(target 10–12 reps/side)" from its template, or null without one. */
    private fun targetTag(exercise: SnapshotWorkoutExercise): String? {
        val parts = listOfNotNull(
            repRangeText(exercise.targetRepMin, exercise.targetRepMax)?.let { "$it reps" + if (exercise.perSide) "/side" else "" },
            rirTarget(exercise.targetRir, exercise.targetRirMax)?.let { "RIR ${it.text}" },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "(target ", postfix = ")")
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
     * One set ([setNotation], with the rest gap, "/side" on an exercise done one side at a time) and
     * " — **PR**" when it was a record when lifted.
     */
    private fun notation(set: SnapshotSet, unit: WeightUnit, perSide: Boolean): String {
        val core = setNotation(
            weightKg = set.weightKg,
            reps = set.reps,
            unit = unit,
            style = NotationStyle.EXPORT,
            type = set.type,
            durationSec = set.durationSec,
            rpe = set.rpe,
            restSec = set.restSec,
            perSide = perSide,
        ) ?: "-"
        return if (set.isPr) "$core — **PR**" else core
    }
}
