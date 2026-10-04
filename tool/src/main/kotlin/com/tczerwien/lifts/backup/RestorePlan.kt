package com.tczerwien.lifts.backup

import com.tczerwien.lifts.data.BarEntity
import com.tczerwien.lifts.data.LiftsRows
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.settings.barAfterUnitSwitch
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** What a restore would bring back, and whether it can: the pure half of Restore. */
object RestorePlan {

    /** How Room's schema checks begin their messages (Room 2.7). */
    private val ROOM_SCHEMA_FAILURES = listOf("Migration didn't properly handle", "Room cannot verify the data integrity")

    /** At most this much of a failure's first line reaches the Restore screen. */
    private const val MESSAGE_CHARS = 100

    data class Summary(
        /** Finished workouts with at least one logged set: what History lists. */
        val workouts: Int,
        /** Logged (ticked) sets of finished workouts: unticked sets and a workout in progress count nowhere. */
        val loggedSets: Int,
        val templates: Int,
        val customExercises: Int,
        val weighIns: Int,
        val firstWorkoutAt: Long?,
        val lastWorkout: WorkoutEntity?,
        /** A workout still in progress in these rows, if any. */
        val openWorkout: WorkoutEntity?,
    )

    /**
     * Whether a restore fits exercise types to history, as the B9 upgrade did: only for a backup made
     * before B9, whose types are the old guesses. Every backup Lifts writes carries its settings
     * ([settingsRows]), and B9's have the weight step; a file with no settings at all (a database
     * pulled over adb) says nothing about its age, and its types may be his own choices, so it keeps them.
     */
    fun refitsTypes(settingsRows: Map<String, String>): Boolean =
        settingsRows.isNotEmpty() && SettingsStore.KEY_WEIGHT_STEP_LB !in settingsRows

    /**
     * The bars a restore changes, as the B9 upgrade changed the phone's: for a backup made before B9
     * ([refitsTypes]) restored in kg ([unit], as the restore leaves it), the seeded bar still at 45 lb
     * was kept only because nothing switched it then, so it becomes 20 kg ([barAfterUnitSwitch]). A
     * later backup's bars are as he left them.
     */
    fun barsToFit(bars: List<BarEntity>, settingsRows: Map<String, String>, unit: WeightUnit): List<BarEntity> =
        if (!refitsTypes(settingsRows) || unit != WeightUnit.KG) {
            emptyList()
        } else {
            bars.mapNotNull { barAfterUnitSwitch(it, WeightUnit.LB, WeightUnit.KG) }
        }

    fun summarize(rows: LiftsRows): Summary {
        val slotWorkout = rows.workoutExercises.associate { it.id to it.workoutId }
        val finishedIds = rows.workouts.filter { it.endedAt != null }.mapTo(HashSet()) { it.id }
        val logged = rows.sets.filter { it.completedAt != null && slotWorkout[it.workoutExerciseId] in finishedIds }
        val workoutsWithSets = logged.mapNotNull { slotWorkout[it.workoutExerciseId] }.toSet()
        val finished = rows.workouts.filter { it.id in workoutsWithSets }
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
        val workoutIds = rows.workouts.map { it.id }.toSet()
        val slotIds = rows.workoutExercises.map { it.id }.toSet()
        val templateIds = rows.templates.map { it.id }.toSet()
        fun report(count: Int, what: String) {
            if (count > 0) add("$what: $count")
        }
        report(rows.workoutExercises.count { it.workoutId !in workoutIds }, "exercise slots with no workout")
        report(rows.sets.count { it.workoutExerciseId !in slotIds }, "sets with no exercise slot")
        report(rows.templateExercises.count { it.templateId !in templateIds }, "template rows with no template")
        // A slot or template row naming an exercise that isn't there breaks no key (exerciseId has
        // none), and every screen shows it as an unknown exercise: refusing the whole log for it would
        // lose everything else in it. The same for one among a row's alternatives or choices: the swap skips it.
        val badIds = rows.workouts.count { it.id <= 0 } + rows.workoutExercises.count { it.id <= 0 } +
            rows.sets.count { it.id <= 0 } + rows.templates.count { it.id <= 0 } +
            rows.templateExercises.count { it.id <= 0 } + rows.bodyweight.count { it.id <= 0 } +
            rows.bars.count { it.id <= 0 }
        report(badIds, "rows with no id")
    }

    /**
     * Why Lifts couldn't open or read a file that passed the file checks, as one short line for the
     * Restore screen ([Restorer] logs the failure in full). Room's two schema checks (the tables after
     * migrating an older file, and the identity hash of a same-version one) word their failures for a
     * developer, the first one dumping both tables; the screen says it in a few words instead.
     * Anything else shows its first line, cut to [MESSAGE_CHARS] (never inside a surrogate pair).
     */
    fun unreadable(failure: Throwable): String {
        val message = failure.message.orEmpty()
        if (failure is IllegalStateException && ROOM_SCHEMA_FAILURES.any { message.startsWith(it) }) {
            return "Lifts can't read it (its tables don't match a Lifts log)"
        }
        val line = message.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: failure.toString().substringBefore(':') // its class name (the plugin bans reflection)
        if (line.length <= MESSAGE_CHARS) return "Lifts can't read it ($line)"
        val cut = if (line[MESSAGE_CHARS - 1].isHighSurrogate()) MESSAGE_CHARS - 1 else MESSAGE_CHARS
        return "Lifts can't read it (${line.take(cut)}…)"
    }

    /** [summary] as the Restore screen's lines, e.g. "26 workouts · 350 sets". */
    fun lines(summary: Summary, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): List<String> = buildList {
        add("${count(summary.workouts, "workout")} · ${count(summary.loggedSets, "set")}")
        val first = summary.firstWorkoutAt
        val last = summary.lastWorkout
        if (first != null && last != null) {
            add("${Format.dateWithYear(first, zone)} – ${Format.dateWithYear(last.startedAt, zone)}")
            add("last: ${last.name} · ${Format.dateShort(last.startedAt, zone, today)}")
        }
        add(
            "${count(summary.templates, "template")} · ${count(summary.customExercises, "custom exercise")} · " +
                count(summary.weighIns, "weigh-in"),
        )
        summary.openWorkout?.let { add("in progress: ${it.name} · ${Format.dateShort(it.startedAt, zone, today)}") }
    }

    private fun count(n: Int, noun: String) =
        "%,d %s".format(Locale.US, n, if (n == 1) noun else "${noun}s")
}
