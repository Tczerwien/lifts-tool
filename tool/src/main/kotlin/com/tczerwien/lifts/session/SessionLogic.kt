package com.tczerwien.lifts.session

import com.tczerwien.lifts.analytics.WorkoutBodyweight
import com.tczerwien.lifts.analytics.chronologicalPrSetIds
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
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

/** The highest RIR the set rows offer: 5 reps left in the tank is already far from a hard set. */
const val MAX_RIR = 5

/**
 * RIR (reps in reserve) for a stored [rpe]: the rpe column holds RPE = 10 − RIR, the scale the
 * exports use. Null for a set without one.
 */
fun rirOf(rpe: Double?): Int? = rpe?.let { (10.0 - it).roundToInt() }

/** The RPE a set stores for [rir] reps in reserve (RPE = 10 − RIR); null clears it. */
fun rpeOf(rir: Int?): Double? = rir?.let { 10.0 - it }

/** A tap on a row's RIR cell: blank → 0 → 1 … [MAX_RIR] → blank. */
fun nextRir(rir: Int?): Int? = when {
    rir == null -> 0
    rir >= MAX_RIR || rir < 0 -> null
    else -> rir + 1
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
 * warm-ups never count toward volume.
 */
fun totalVolumeKg(sets: List<SetEntity>): Double =
    sets.filter { it.completedAt != null && it.type != SET_TYPE_WARMUP }
        .sumOf { set ->
            val weight = set.weightKg ?: return@sumOf 0.0
            val reps = set.reps ?: return@sumOf 0.0
            weight * reps
        }

/**
 * Ids of sets in [workoutSets] that were PRs at the moment they were completed. Each exercise's
 * completed sets from this workout and from [priorHistory] (sets of the same exercises from other
 * workouts, keyed by exerciseId) are replayed together in completion order, so a set is only
 * compared with sets completed before it. Later or abandoned workouts in [priorHistory] can't
 * steal a star. Rules are those of the shared records engine.
 */
fun prSetIds(
    slots: List<WorkoutExerciseEntity>,
    workoutSets: List<SetEntity>,
    priorHistory: Map<String, List<SetEntity>>,
): Set<Long> {
    val slotById = slots.associateBy { it.id }
    val workoutSetIds = workoutSets.mapTo(HashSet()) { it.id }
    val byExercise = HashMap<String, MutableList<SetEntity>>()
    for (set in workoutSets) {
        val exerciseId = slotById[set.workoutExerciseId]?.exerciseId ?: continue
        byExercise.getOrPut(exerciseId) { mutableListOf() }.add(set)
    }
    for ((exerciseId, sets) in byExercise) {
        sets += priorHistory[exerciseId].orEmpty().filter { it.id !in workoutSetIds }
    }
    return chronologicalPrSetIds(byExercise).filterTo(HashSet()) { it in workoutSetIds }
}

/** One "New Records" line: the current record-holding set of one exercise. [weightKg] is null for a reps-only record. */
data class PrRecord(
    val exerciseId: String,
    val exerciseName: String,
    val weightKg: Double?,
    val reps: Int?,
)

/**
 * One [PrRecord] per exercise that flagged at least one PR set ([prIds] from [prSetIds]):
 * the latest-completed PR set is the current record holder. Ordered by the exercise's
 * first slot position in the workout; [exerciseNames] fall back to the exercise id.
 */
fun prRecords(
    slots: List<WorkoutExerciseEntity>,
    workoutSets: List<SetEntity>,
    prIds: Set<Long>,
    exerciseNames: Map<String, String>,
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
            val weight = set.weightKg?.takeIf { it > 0.0 }
            if (weight == null && set.reps == null) return@mapNotNull null
            PrRecord(
                exerciseId = exerciseId,
                exerciseName = exerciseNames[exerciseId] ?: exerciseId,
                weightKg = weight,
                reps = set.reps,
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
 * notes, no set logged or given a value, and the slots and their set types are exactly what the
 * start made. That is no slots at all for an empty workout, and [planned] ([planTemplate] from the
 * template as it is now) for a template workout; [planned] is null for a workout with no template.
 * Any doubt (the template was edited or deleted since) counts as touched, so the workout is kept.
 * Starting values are only shown, never written at the start, so any stored value means a change.
 */
fun isUntouched(
    workout: WorkoutEntity,
    slots: List<WorkoutExerciseEntity>,
    sets: List<SetEntity>,
    planned: List<PlannedSlot>?,
): Boolean {
    if (!workout.notes.isNullOrBlank() || slots.any { !it.notes.isNullOrBlank() }) return false
    val hasValue = sets.any { set ->
        set.completedAt != null || set.weightKg != null || set.reps != null ||
            set.durationSec != null || set.rpe != null
    }
    if (hasValue) return false
    val expected = planned.orEmpty()
    val actual = slots.sortedBy { it.position }
    if (actual.size != expected.size) return false
    val typesBySlot = sets.groupBy { it.workoutExerciseId }
        .mapValues { (_, slotSets) -> slotSets.sortedBy { it.position }.map { it.type } }
    return actual.zip(expected).all { (slot, plannedSlot) ->
        slot.exerciseId == plannedSlot.exerciseId && typesBySlot[slot.id].orEmpty() == plannedSlot.types
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
 * logged (sets without a tick never count anywhere, so nothing is lost).
 */
fun closeAction(startedAt: Long, sets: List<SetEntity>): OpenWorkoutAction {
    val lastLogged = sets.mapNotNull { it.completedAt }.maxOrNull() ?: return OpenWorkoutAction.Delete
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
): OpenWorkoutAction =
    if (now - lastActivityMillis(startedAt, sets) <= staleAfterMillis) {
        OpenWorkoutAction.Keep
    } else {
        closeAction(startedAt, sets)
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
                staleAction(workout.startedAt, workout.sets, now)
            }
            else -> closeAction(workout.startedAt, workout.sets)
        }
    }
}

/** True when an open workout was used within [windowMillis] of [now], so a cold start reopens it. */
fun shouldAutoResume(
    lastActivity: Long,
    now: Long,
    windowMillis: Long = AUTO_RESUME_WITHIN_MILLIS,
): Boolean = now >= lastActivity && now - lastActivity <= windowMillis
