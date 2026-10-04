package com.tczerwien.lifts.session

import com.tczerwien.lifts.analytics.PriorHistory
import com.tczerwien.lifts.analytics.WorkoutBodyweight
import com.tczerwien.lifts.analytics.prSetIdsAfter
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.Tracking
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Pure decision logic for an active workout session: rest-duration selection, wall-clock
 * timer derivation, summary math, and PR replay. No Android, no SDK, no reflection —
 * everything here is covered by SessionLogicTest.
 */

const val SET_TYPE_NORMAL = "normal"
const val SET_TYPE_WARMUP = "warmup"
const val SET_TYPE_FAILURE = "failure"
const val SET_TYPE_DROP = "drop"

/**
 * The type a tap on a row's marker moves the set to. An unticked row cycles normal → warm-up →
 * failure → drop → normal. A tap never moves a ticked set into or out of the warm-ups, which would
 * take it out of (or put it into) his volume and records unnoticed: a ticked work set cycles normal →
 * failure → drop → normal, and a ticked warm-up stays one (untick it to change it).
 */
fun nextSetType(type: String, ticked: Boolean): String = when {
    ticked && type == SET_TYPE_WARMUP -> SET_TYPE_WARMUP
    ticked -> when (type) {
        SET_TYPE_FAILURE -> SET_TYPE_DROP
        SET_TYPE_DROP -> SET_TYPE_NORMAL
        else -> SET_TYPE_FAILURE
    }
    else -> when (type) {
        SET_TYPE_NORMAL -> SET_TYPE_WARMUP
        SET_TYPE_WARMUP -> SET_TYPE_FAILURE
        SET_TYPE_FAILURE -> SET_TYPE_DROP
        else -> SET_TYPE_NORMAL
    }
}

/**
 * The name a workout started empty gets, from the local hour it started in [zone], as Strong names
 * them: "Morning Workout" 5:00–11:59, "Afternoon Workout" 12:00–16:59, "Evening Workout"
 * 17:00–20:59, "Night Workout" 21:00–4:59. History then tells an empty Tuesday evening from the
 * morning after, and the "…" menu renames it.
 */
fun defaultWorkoutName(startedAt: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    when (Instant.ofEpochMilli(startedAt).atZone(zone).hour) {
        in 5..11 -> "Morning Workout"
        in 12..16 -> "Afternoon Workout"
        in 17..20 -> "Evening Workout"
        else -> "Night Workout"
    }

/**
 * The name "Start again" gives a copy of [source] started at [startedAt]: its own name, unless that
 * is only the time of day it started ([defaultWorkoutName]), when the copy is named for its own start
 * (Tuesday's "Evening Workout" repeated on Saturday morning is a "Morning Workout").
 */
fun startAgainName(source: WorkoutEntity, startedAt: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    if (source.name == defaultWorkoutName(source.startedAt, zone)) defaultWorkoutName(startedAt, zone) else source.name

/** The longest rest any stepper sets (Settings, the "…" menu, the Template Editor): 15:00. */
const val MAX_REST_SEC = 900

/** A hold's stepper moves 5 s a step. */
const val DURATION_STEP_SEC = 5

/** The highest RIR the set rows offer: 5 reps left in the tank is already far from a hard set. */
const val MAX_RIR = 5

/**
 * RIR (reps in reserve) for a stored [rpe]: the rpe column holds RPE = 10 − RIR, the scale the
 * exports use. Null for a set without one.
 */
fun rirOf(rpe: Double?): Int? = rpe?.let { (10.0 - it).roundToInt() }

/** The RPE a set stores for [rir] reps in reserve (RPE = 10 − RIR); null clears it. */
fun rpeOf(rir: Int?): Double? = rir?.let { 10.0 - it }

/** The RIR a row's first tap gives when the exercise has no RIR target: the usual one or two in reserve. */
const val DEFAULT_FIRST_RIR = 2

/**
 * A tap on a row's RIR cell (B11): the first gives the slot's [target] RIR
 * ([DEFAULT_FIRST_RIR] without one), each next one less down to 0, then the numbers above the start
 * up to [MAX_RIR], then blank. With target 2: blank → 2 → 1 → 0 → 3 → 4 → 5 → blank, so the usual 2
 * is one tap and 1 two. A value from elsewhere outside 0..[MAX_RIR] goes to blank.
 */
fun nextRir(rir: Int?, target: Int? = null): Int? {
    val start = (target ?: DEFAULT_FIRST_RIR).coerceIn(0, MAX_RIR)
    return when {
        rir == null -> start
        rir < 0 || rir > MAX_RIR -> null
        rir in 1..start -> rir - 1
        rir == 0 -> (start + 1).takeIf { it <= MAX_RIR }
        rir < MAX_RIR -> rir + 1
        else -> null
    }
}

/**
 * Rest to start after completing a set: the exercise's per-slot override when present,
 * else the settings default. Warm-up sets use the warm-up pair; every other type
 * (normal/failure/drop) uses the work pair.
 */
fun restDurationSec(
    setType: String,
    restSecWork: Int?,
    restSecWarmup: Int?,
    defaultRestSec: Int,
    warmupRestSec: Int,
): Int = if (setType == SET_TYPE_WARMUP) {
    restSecWarmup ?: warmupRestSec
} else {
    restSecWork ?: defaultRestSec
}

/**
 * Whole seconds remaining until [endMillis], rounded UP so the countdown only reads 0
 * when the rest is truly over; never negative. Derived from the wall clock every time —
 * timers on this platform must never assume process liveness.
 */
fun restRemainingSec(endMillis: Long, nowMillis: Long): Int {
    val remainingMillis = endMillis - nowMillis
    if (remainingMillis <= 0) return 0
    return ((remainingMillis + 999) / 1000).toInt()
}

/** Sets that were actually logged ([SetEntity.completedAt] non-null), any type. */
fun completedSetCount(sets: List<SetEntity>): Int = sets.count { it.completedAt != null }

/**
 * Total volume in canonical kg over completed non-warmup sets (weight × reps; a set
 * missing either contributes nothing). Matches the analytics/export convention that
 * warm-ups never count toward volume. Only weight lifted counts: assistance, stored negative,
 * adds nothing (and takes nothing away).
 */
fun totalVolumeKg(sets: List<SetEntity>): Double =
    sets.filter { it.completedAt != null && it.type != SET_TYPE_WARMUP }
        .sumOf { set ->
            val weight = set.weightKg?.takeIf { it > 0.0 } ?: return@sumOf 0.0
            val reps = set.reps ?: return@sumOf 0.0
            weight * reps
        }

/**
 * Ids of sets in [workoutSets] that were PRs at the moment they were completed. Each exercise's
 * completed sets from this workout are measured against [priorHistory] (its sets from other
 * workouts, keyed by exerciseId, replayed once: [PriorHistory]) and each other in completion order,
 * so a set is only compared with sets completed before it. Later or abandoned workouts in
 * [priorHistory] can't steal a star. Rules are those of the shared records engine, by each
 * exercise's [tracking] (weight × reps where it has none). Cheap enough for every tap: it goes over
 * today's sets, not the history ([prSetIdsAfter]).
 */
fun prSetIds(
    slots: List<WorkoutExerciseEntity>,
    workoutSets: List<SetEntity>,
    priorHistory: Map<String, PriorHistory>,
    tracking: Map<String, Tracking> = emptyMap(),
): Set<Long> {
    val slotById = slots.associateBy { it.id }
    val byExercise = HashMap<String, MutableList<SetEntity>>()
    for (set in workoutSets) {
        val exerciseId = slotById[set.workoutExerciseId]?.exerciseId ?: continue
        byExercise.getOrPut(exerciseId) { mutableListOf() }.add(set)
    }
    return byExercise.flatMapTo(HashSet()) { (exerciseId, sets) ->
        prSetIdsAfter(sets, priorHistory[exerciseId] ?: PriorHistory.NONE, tracking[exerciseId] ?: Tracking.WEIGHT_REPS)
    }
}

/** [prSetIds] against raw lists of each exercise's sets from other workouts, replayed here (a one-off: Workout Complete). */
@JvmName("prSetIdsFromLists")
fun prSetIds(
    slots: List<WorkoutExerciseEntity>,
    workoutSets: List<SetEntity>,
    priorHistory: Map<String, List<SetEntity>>,
    tracking: Map<String, Tracking> = emptyMap(),
): Set<Long> {
    val replayed: Map<String, PriorHistory> = priorHistory.mapValues { PriorHistory(it.value) }
    return prSetIds(slots, workoutSets, replayed, tracking)
}

/**
 * One "New Records" line: the current record-holding set of one exercise, with the numbers its
 * tracking records. [weightKg] is null for a reps-only record and negative for assistance;
 * [durationSec] is a hold's.
 */
data class PrRecord(
    val exerciseId: String,
    val exerciseName: String,
    val weightKg: Double?,
    val reps: Int?,
    val durationSec: Int? = null,
    /** Its reps (or seconds) count one side ("× 10/side"). */
    val perSide: Boolean = false,
)

/**
 * One [PrRecord] per exercise that flagged at least one PR set ([prIds] from [prSetIds]):
 * the latest-completed PR set is the current record holder. Ordered by the exercise's
 * first slot position in the workout; one missing from [exerciseNames] is named by its id. Each record
 * shows only what its exercise's [tracking] measures, and says when its count is each side's ([perSide]).
 */
fun prRecords(
    slots: List<WorkoutExerciseEntity>,
    workoutSets: List<SetEntity>,
    prIds: Set<Long>,
    exerciseNames: Map<String, String>,
    tracking: Map<String, Tracking> = emptyMap(),
    perSide: Set<String> = emptySet(),
): List<PrRecord> {
    val slotById = slots.associateBy { it.id }
    val bestByExercise = LinkedHashMap<String, SetEntity>()
    val prSets = workoutSets.filter { it.id in prIds && it.completedAt != null }
        .sortedWith(compareBy({ it.completedAt }, { it.id }))
    for (set in prSets) {
        val exerciseId = slotById[set.workoutExerciseId]?.exerciseId ?: continue
        val current = bestByExercise[exerciseId]
        // Weighted records outrank reps-only ones (a +20 kg dip beats a bodyweight rep PR);
        // within a kind, chronological iteration makes the latest win.
        if (current == null || set.isWeighted() || !current.isWeighted()) bestByExercise[exerciseId] = set
    }
    val positionByExercise = HashMap<String, Int>()
    for (slot in slots.sortedBy { it.position }) {
        positionByExercise.putIfAbsent(slot.exerciseId, slot.position)
    }
    return bestByExercise.entries
        .sortedBy { positionByExercise[it.key] ?: Int.MAX_VALUE }
        .mapNotNull { (exerciseId, set) ->
            val kind = tracking[exerciseId] ?: Tracking.WEIGHT_REPS
            val weight = set.weightKg?.takeIf { kind.takesWeight && it != 0.0 }
            val reps = set.reps?.takeIf { kind.takesReps }
            val durationSec = set.durationSec?.takeIf { kind.takesDuration }
            if (weight == null && reps == null && durationSec == null) return@mapNotNull null
            PrRecord(
                exerciseId = exerciseId,
                exerciseName = exerciseNames[exerciseId] ?: exerciseId,
                weightKg = weight,
                reps = reps,
                durationSec = durationSec,
                perSide = exerciseId in perSide,
            )
        }
}

private fun SetEntity.isWeighted(): Boolean = (weightKg ?: 0.0) > 0.0

/** Post-finish summary for the Workout Complete screen. */
data class WorkoutSummary(
    val workoutId: Long,
    val name: String,
    val durationSec: Int,
    val completedSetCount: Int,
    val totalVolumeKg: Double,
    val prs: List<PrRecord>,
    /** The weigh-in that goes with the workout ([workoutBodyweight]), null without one. */
    val bodyweight: WorkoutBodyweight?,
)

/**
 * Record lines Workout Complete has room for, a "+N MORE" line included: a first session of an
 * eight-exercise template sets a record on every one, and a column cuts what doesn't fit without a word.
 */
const val MAX_RECORD_LINES = 6

/** The records Workout Complete lists ([shown]), and how many it leaves to "+[more] MORE". */
data class RecordLines(val shown: List<PrRecord>, val more: Int)

/**
 * [records] as Workout Complete lists them in [maxLines] lines: all when they fit, else the first
 * [maxLines] − 1 and a "+N MORE" line for the rest (Workout Detail stars every one).
 */
fun recordLines(records: List<PrRecord>, maxLines: Int = MAX_RECORD_LINES): RecordLines =
    if (records.size <= maxLines) {
        RecordLines(records, more = 0)
    } else {
        RecordLines(records.take(maxLines - 1), more = records.size - (maxLines - 1))
    }

/** The set the lifter is resting toward, with its 1-based number within its exercise. */
data class NextPendingSet(
    val slot: WorkoutExerciseEntity,
    val set: SetEntity,
    val setNumber: Int,
    val setCount: Int,
)

/**
 * Next pending set of the workout: the first uncompleted set of the exercise containing
 * the most recent completion, else scanning subsequent exercises in position order
 * (wrapping around to earlier ones). With no completions yet, the first uncompleted set
 * overall. Null when every set is completed or there are no exercises.
 */
fun nextPendingSet(
    slots: List<WorkoutExerciseEntity>,
    sets: List<SetEntity>,
): NextPendingSet? {
    if (slots.isEmpty()) return null
    val ordered = slots.sortedBy { it.position }
    val setsBySlot = sets.groupBy { it.workoutExerciseId }
        .mapValues { (_, slotSets) -> slotSets.sortedBy { it.position } }
    val lastCompleted = sets.filter { it.completedAt != null }
        .maxWithOrNull(compareBy({ it.completedAt }, { it.id }))
    val startIndex = lastCompleted
        ?.let { last -> ordered.indexOfFirst { it.id == last.workoutExerciseId } }
        ?.takeIf { it >= 0 }
        ?: 0
    for (offset in ordered.indices) {
        val slot = ordered[(startIndex + offset) % ordered.size]
        val slotSets = setsBySlot[slot.id].orEmpty()
        val pendingIndex = slotSets.indexOfFirst { it.completedAt == null }
        if (pendingIndex >= 0) {
            return NextPendingSet(
                slot = slot,
                set = slotSets[pendingIndex],
                setNumber = pendingIndex + 1,
                setCount = slotSets.size,
            )
        }
    }
    return null
}

// --- Workout lifecycle ---

/** A workout untouched this long after its last logged set (or its start) is closed by Home. */
const val STALE_AFTER_MILLIS = 4 * 60 * 60 * 1000L

/** On a cold start, Home reopens an open workout straight away if it was used this recently. */
const val AUTO_RESUME_WITHIN_MILLIS = 2 * 60 * 60 * 1000L

/** Sets not ticked yet ("3 SETS NOT LOGGED" in the Finish confirmation). */
fun pendingSetCount(sets: List<SetEntity>): Int = sets.count { it.completedAt == null }

/** When the lifter last did anything: the latest logged set, else the start. */
fun lastActivityMillis(startedAt: Long, sets: List<SetEntity>): Long =
    maxOf(startedAt, sets.maxOfOrNull { it.completedAt ?: startedAt } ?: startedAt)

/**
 * True when nothing has changed since the workout was created, so discarding it loses nothing: no
 * notes, the name it started with, no set logged or given a value, and the slots, their set types,
 * their rests, their targets (an RIR range's top and the planned work sets too) and their supersets
 * ([sameGrouping]) are exactly what the
 * start made. That is no slots at all for an empty workout, and [planned] ([planTemplate] from the
 * template as it is now) for a template workout; [planned] gives null for a workout with no template.
 * Any doubt (the template was edited or deleted since) counts as touched, so the workout is kept.
 * Starting values are only shown, never written at the start, so any stored value means a change.
 * [planned] is read last, only when nothing cheaper has decided: working out a template's plan takes
 * a query per exercise, and Home asks on every show.
 */
fun isUntouched(
    workout: WorkoutEntity,
    slots: List<WorkoutExerciseEntity>,
    sets: List<SetEntity>,
    planned: () -> List<PlannedSlot>?,
    /** The name the start gave it (the template's, or [defaultWorkoutName]); another name is his. */
    startName: String? = null,
): Boolean {
    if (!workout.notes.isNullOrBlank() || slots.any { !it.notes.isNullOrBlank() }) return false
    if (startName != null && workout.name != startName) return false
    val hasValue = sets.any { set ->
        set.completedAt != null || set.weightKg != null || set.reps != null ||
            set.durationSec != null || set.rpe != null
    }
    if (hasValue) return false
    val expected = planned().orEmpty()
    val actual = slots.sortedBy { it.position }
    if (actual.size != expected.size) return false
    // A superset joined or split from the "…" menu is his: Back must not throw it away.
    if (!sameGrouping(actual.map { it.supersetGroup }, expected.map { it.supersetGroup })) return false
    val typesBySlot = sets.groupBy { it.workoutExerciseId }
        .mapValues { (_, slotSets) -> slotSets.sortedBy { it.position }.map { it.type } }
    return actual.zip(expected).all { (slot, plannedSlot) ->
        slot.exerciseId == plannedSlot.exerciseId && typesBySlot[slot.id].orEmpty() == plannedSlot.types &&
            slot.restSecWork == plannedSlot.restSecWork && slot.restSecWarmup == plannedSlot.restSecWarmup &&
            slot.targetRepMin == plannedSlot.targetRepMin && slot.targetRepMax == plannedSlot.targetRepMax &&
            slot.targetRir == plannedSlot.targetRir && slot.targetRirMax == plannedSlot.targetRirMax &&
            slot.targetSets == plannedSlot.targetSets
    }
}

/** What Home does with a workout that is still open. */
sealed interface OpenWorkoutAction {
    /** Leave it open: it can still be resumed. */
    data object Keep : OpenWorkoutAction

    /** Close it at its last logged set, so its duration is the time actually spent lifting. */
    data class CloseAt(val endedAt: Long) : OpenWorkoutAction

    /** Nothing in it is worth keeping. */
    data object Delete : OpenWorkoutAction
}

/**
 * Closes a workout that won't be resumed: at its last logged set, or deleted when nothing was
 * logged (sets without a tick never count anywhere, so nothing is lost). One that [hasNotes] (a note
 * on it or an exercise, written from the "…" menu) closes at its start instead: the note is his.
 */
fun closeAction(startedAt: Long, sets: List<SetEntity>, hasNotes: Boolean = false): OpenWorkoutAction {
    val lastLogged = sets.mapNotNull { it.completedAt }.maxOrNull()
        ?: return if (hasNotes) OpenWorkoutAction.CloseAt(startedAt) else OpenWorkoutAction.Delete
    return OpenWorkoutAction.CloseAt(maxOf(startedAt, lastLogged))
}

/**
 * Keep while the workout was used within [staleAfterMillis]; after that close it ([closeAction]).
 * This is what stops a forgotten workout finishing weeks later with a 1,500-hour clock. A clock
 * set back (now before the last activity) keeps it.
 */
fun staleAction(
    startedAt: Long,
    sets: List<SetEntity>,
    now: Long,
    staleAfterMillis: Long = STALE_AFTER_MILLIS,
    hasNotes: Boolean = false,
): OpenWorkoutAction =
    if (now - lastActivityMillis(startedAt, sets) <= staleAfterMillis) {
        OpenWorkoutAction.Keep
    } else {
        closeAction(startedAt, sets, hasNotes)
    }

/**
 * The endedAt Finish records: now, unless the workout sat idle past [staleAfterMillis] (the phone
 * slept on Active Workout overnight), when it ends at its last logged set, as Home would have
 * closed it ([staleAction]). Never before its start or its last tick: a clock set back mid-workout
 * would otherwise give a negative duration.
 */
fun finishedAt(
    startedAt: Long,
    sets: List<SetEntity>,
    now: Long,
    staleAfterMillis: Long = STALE_AFTER_MILLIS,
): Long = (staleAction(startedAt, sets, now, staleAfterMillis) as? OpenWorkoutAction.CloseAt)?.endedAt
    ?: maxOf(now, lastActivityMillis(startedAt, sets))

/** One open workout as Home's cleanup sees it. */
data class OpenWorkout(
    val id: Long,
    val startedAt: Long,
    val sets: List<SetEntity>,
    val untouched: Boolean,
    /** A note on it or one of its exercises ([closeAction]). */
    val hasNotes: Boolean = false,
)

/**
 * Home's rule for the open workouts ([open], newest first), so that at most one stays open.
 * Untouched ones are deleted. Of the rest, the newest follows [staleAction] and every older one is
 * closed ([closeAction]), since only one workout can be resumed.
 */
fun planOpenWorkouts(open: List<OpenWorkout>, now: Long): Map<Long, OpenWorkoutAction> {
    var newestSeen = false
    return open.associate { workout ->
        workout.id to when {
            workout.untouched -> OpenWorkoutAction.Delete
            !newestSeen -> {
                newestSeen = true
                staleAction(workout.startedAt, workout.sets, now, hasNotes = workout.hasNotes)
            }
            else -> closeAction(workout.startedAt, workout.sets, workout.hasNotes)
        }
    }
}

/** True when an open workout was used within [windowMillis] of [now], so a cold start reopens it. */
fun shouldAutoResume(
    lastActivity: Long,
    now: Long,
    windowMillis: Long = AUTO_RESUME_WITHIN_MILLIS,
): Boolean = now >= lastActivity && now - lastActivity <= windowMillis
