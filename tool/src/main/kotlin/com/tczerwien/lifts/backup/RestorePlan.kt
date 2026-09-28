package com.tczerwien.lifts.backup

import com.tczerwien.lifts.data.LiftsRows
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.ui.Format
import java.time.ZoneId
import java.util.Locale

/** What a restore would bring back, and whether it can: the pure half of Restore. */
object RestorePlan {

    data class Summary(
        /** Finished workouts with at least one logged set: what History lists. */
        val workouts: Int,
        /** Logged (ticked) sets; unticked ones never count. */
        val loggedSets: Int,
        val templates: Int,
        val customExercises: Int,
        val weighIns: Int,
        val firstWorkoutAt: Long?,
        val lastWorkout: WorkoutEntity?,
        /** A workout still in progress in these rows, if any. */
        val openWorkout: WorkoutEntity?,
    )

    fun summarize(rows: LiftsRows): Summary {
        val slotWorkout = rows.workoutExercises.associate { it.id to it.workoutId }
        val logged = rows.sets.filter { it.completedAt != null }
        val workoutsWithSets = logged.mapNotNull { slotWorkout[it.workoutExerciseId] }.toSet()
        val finished = rows.workouts.filter { it.endedAt != null && it.id in workoutsWithSets }
        return Summary(
            workouts = finished.size,
            loggedSets = logged.size,
            templates = rows.templates.size,
            customExercises = rows.exercises.count { it.isCustom },
            weighIns = rows.bodyweight.size,
            firstWorkoutAt = finished.minOfOrNull { it.startedAt },
            lastWorkout = finished.maxByOrNull { it.startedAt },
            openWorkout = rows.workouts.filter { it.endedAt == null }.maxByOrNull { it.startedAt },
        )
    }

    /**
     * Why [rows] can't be restored as they are; empty when they can. Lifts never writes such rows,
     * so any of these means a damaged or hand-edited file, and restoring it would break screens.
     */
    fun problems(rows: LiftsRows): List<String> = buildList {
        if (rows.exercises.isEmpty()) add("it has no exercises at all")
        val exerciseIds = rows.exercises.map { it.id }.toSet()
        val workoutIds = rows.workouts.map { it.id }.toSet()
        val slotIds = rows.workoutExercises.map { it.id }.toSet()
        val templateIds = rows.templates.map { it.id }.toSet()
        fun report(count: Int, what: String) {
            if (count > 0) add("$what: $count")
        }
        report(rows.workoutExercises.count { it.workoutId !in workoutIds }, "exercise slots with no workout")
        report(rows.sets.count { it.workoutExerciseId !in slotIds }, "sets with no exercise slot")
        report(rows.templateExercises.count { it.templateId !in templateIds }, "template rows with no template")
        report(
            rows.workoutExercises.count { it.exerciseId !in exerciseIds } +
                rows.templateExercises.count { it.exerciseId !in exerciseIds },
            "rows naming a missing exercise",
        )
        val badIds = rows.workouts.count { it.id <= 0 } + rows.workoutExercises.count { it.id <= 0 } +
            rows.sets.count { it.id <= 0 } + rows.templates.count { it.id <= 0 } +
            rows.templateExercises.count { it.id <= 0 } + rows.bodyweight.count { it.id <= 0 } +
            rows.bars.count { it.id <= 0 }
        report(badIds, "rows with no id")
    }

    /** [summary] as the Restore screen's lines, e.g. "26 workouts · 350 sets". */
    fun lines(summary: Summary, zone: ZoneId = ZoneId.systemDefault()): List<String> = buildList {
        add("${count(summary.workouts, "workout")} · ${count(summary.loggedSets, "set")}")
        val first = summary.firstWorkoutAt
        val last = summary.lastWorkout
        if (first != null && last != null) {
            add("${Format.dateWithYear(first, zone)} – ${Format.dateWithYear(last.startedAt, zone)}")
            add("last: ${last.name} · ${Format.dateShort(last.startedAt, zone)}")
        }
        add(
            "${count(summary.templates, "template")} · ${count(summary.customExercises, "custom exercise")} · " +
                count(summary.weighIns, "weigh-in"),
        )
        summary.openWorkout?.let { add("in progress: ${it.name} · ${Format.dateShort(it.startedAt, zone)}") }
    }

    private fun count(n: Int, noun: String) =
        "%,d %s".format(Locale.US, n, if (n == 1) noun else "${noun}s")
}
