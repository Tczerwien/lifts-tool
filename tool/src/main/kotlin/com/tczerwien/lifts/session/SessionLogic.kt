package com.tczerwien.lifts.session

import com.tczerwien.lifts.analytics.chronologicalPrSetIds
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity

/**
 * Pure decision logic for an active workout session: rest-duration selection, wall-clock
 * timer derivation, summary math, and PR replay. No Android, no SDK, no reflection —
 * everything here is covered by SessionLogicTest.
 */

const val SET_TYPE_NORMAL = "normal"
const val SET_TYPE_WARMUP = "warmup"
const val SET_TYPE_FAILURE = "failure"

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
    val bodyweightKg: Double?,
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
