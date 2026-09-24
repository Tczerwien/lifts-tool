package com.tczerwien.lifts.analytics

import com.tczerwien.lifts.data.SetEntity

/**
 * The one PR/records engine. Every screen and export that stars a set or shows a record goes
 * through here, so they all agree on what counts.
 *
 * Rules:
 * - Only completed working sets with at least one rep count ([recordEligible]). Warm-ups, failed
 *   0-rep attempts and sets with no reps never set or hold a record.
 * - Weighted sets (weight > 0) compete on weight, then on Epley e1RM. Weightless sets (reps only,
 *   e.g. pull-ups) compete on reps, separately from weighted sets of the same exercise.
 * - A set is compared only with sets completed before it, never with later workouts.
 * - Weights within [WEIGHT_TOLERANCE_KG] count as equal, so lb↔kg rounding never fakes a PR.
 */

/**
 * Weights closer than this are the same weight. 0.12 kg ≈ 0.26 lb: well under the smallest
 * plate step (1.25 lb / 0.5 kg), but wider than the drift a lb↔kg round trip leaves behind.
 */
const val WEIGHT_TOLERANCE_KG = 0.12

private const val SET_TYPE_WARMUP = "warmup"

/** Whether a logged set can set or hold a record: completed, not a warm-up, at least one rep. */
fun recordEligible(set: SetEntity): Boolean =
    set.completedAt != null && set.type != SET_TYPE_WARMUP && (set.reps ?: 0) >= 1

private fun SetEntity.isWeighted(): Boolean = (weightKg ?: 0.0) > 0.0

/**
 * Running record state for one exercise. Feed sets in completion order with [record]; ask
 * [wouldBePr] before recording a set to learn whether it beats everything seen so far.
 */
class RecordTracker {
    var maxWeightKg: Double? = null
        private set
    var bestE1RmKg: Double? = null
        private set
    var maxRepsWeightless: Int? = null
        private set

    /**
     * Whether [set] beats every set recorded so far. [set] need not be completed yet (the UI
     * asks before logging), but it must be a working set with reps. The first weighted set of
     * an exercise is a PR; so is the first weightless one.
     */
    fun wouldBePr(set: SetEntity): Boolean {
        if (set.type == SET_TYPE_WARMUP) return false
        val reps = set.reps ?: return false
        if (reps < 1) return false
        if (!set.isWeighted()) {
            val best = maxRepsWeightless ?: return true
            return reps > best
        }
        val weight = set.weightKg ?: return false
        val max = maxWeightKg ?: return true
        if (weight > max + WEIGHT_TOLERANCE_KG) return true
        val e1Rm = epleyE1RmKg(weight, reps) ?: return false
        val best = bestE1RmKg ?: return false
        return e1Rm > best + WEIGHT_TOLERANCE_KG
    }

    /** Adds a logged set to the running state; ineligible sets are ignored. */
    fun record(set: SetEntity) {
        if (!recordEligible(set)) return
        val reps = set.reps ?: return
        if (!set.isWeighted()) {
            maxRepsWeightless = maxOf(maxRepsWeightless ?: 0, reps)
            return
        }
        val weight = set.weightKg ?: return
        maxWeightKg = maxOf(maxWeightKg ?: weight, weight)
        epleyE1RmKg(weight, reps)?.let { e1Rm -> bestE1RmKg = maxOf(bestE1RmKg ?: e1Rm, e1Rm) }
    }
}

/**
 * Whether [newSet] would be a personal record against [history] (earlier sets of the same
 * exercise; history is filtered to eligible sets here, so callers may pass raw lists, but it
 * must not contain [newSet] itself).
 */
fun isPr(newSet: SetEntity, history: List<SetEntity>): Boolean {
    val tracker = RecordTracker()
    history.forEach(tracker::record)
    return tracker.wouldBePr(newSet)
}

private val COMPLETION_ORDER = compareBy<SetEntity>({ it.completedAt }, { it.id })

/**
 * Ids of the sets of ONE exercise that were PRs when they were completed: each completed set is
 * compared with the sets completed before it. One pass with running maxima.
 */
fun chronologicalPrSetIds(sets: List<SetEntity>): Set<Long> {
    val tracker = RecordTracker()
    val result = HashSet<Long>()
    for (set in sets.filter { it.completedAt != null }.sortedWith(COMPLETION_ORDER)) {
        if (recordEligible(set) && tracker.wouldBePr(set)) result.add(set.id)
        tracker.record(set)
    }
    return result
}

/** [chronologicalPrSetIds] for several exercises at once, keyed by exercise id. */
fun chronologicalPrSetIds(setsByExercise: Map<String, List<SetEntity>>): Set<Long> =
    setsByExercise.values.flatMapTo(HashSet()) { chronologicalPrSetIds(it) }

/** A rep-max row: the heaviest weight for [reps] reps, actual or [estimated] from the best e1RM. */
data class RepMaxRecord(
    val reps: Int,
    val weightKg: Double?,
    val estimated: Boolean,
    val completedAt: Long?,
)

/**
 * Records for one exercise. Weighted fields are null when no eligible weighted set exists;
 * [maxRepsSet] is the best weightless set, null when there is none.
 */
data class ExerciseRecords(
    val maxWeightSet: SetEntity?,
    val bestE1RmKg: Double?,
    val bestE1RmSet: SetEntity?,
    val bestVolumeSet: SetEntity?,
    val maxRepsSet: SetEntity?,
    val repMaxes: List<RepMaxRecord>,
)

/**
 * Records over one exercise's [sets] (any order; only eligible sets count). Ties keep the first
 * set to reach a value. A rep max for N reps is the heaviest set of at least N reps (145×10
 * proves you can do 145×8); with no such set it is estimated from the best e1RM by inverse
 * Epley. Returns null when no eligible set exists.
 */
fun exerciseRecords(sets: List<SetEntity>, repTargets: List<Int> = listOf(1, 5, 8)): ExerciseRecords? {
    val eligible = sets.filter(::recordEligible).sortedWith(COMPLETION_ORDER)
    if (eligible.isEmpty()) return null
    val weighted = eligible.filter { it.isWeighted() }

    var maxWeightSet: SetEntity? = null
    var bestE1RmSet: SetEntity? = null
    var bestE1Rm: Double? = null
    var bestVolumeSet: SetEntity? = null
    var bestVolume = 0.0
    for (set in weighted) {
        val weight = set.weightKg ?: continue
        val reps = set.reps ?: continue
        if (maxWeightSet == null || weight > (maxWeightSet.weightKg ?: 0.0) + WEIGHT_TOLERANCE_KG) maxWeightSet = set
        epleyE1RmKg(weight, reps)?.let { e1Rm ->
            if (bestE1Rm == null || e1Rm > bestE1Rm!! + WEIGHT_TOLERANCE_KG) {
                bestE1Rm = e1Rm
                bestE1RmSet = set
            }
        }
        val volume = weight * reps
        if (bestVolumeSet == null || volume > bestVolume + WEIGHT_TOLERANCE_KG) {
            bestVolume = volume
            bestVolumeSet = set
        }
    }

    val maxRepsSet = eligible.filterNot { it.isWeighted() }
        .fold(null as SetEntity?) { best, set -> if (best == null || (set.reps ?: 0) > (best.reps ?: 0)) set else best }

    val repMaxes = if (weighted.isEmpty()) {
        emptyList()
    } else {
        repTargets.map { target ->
            val actual = weighted.filter { (it.reps ?: 0) >= target }
                .fold(null as SetEntity?) { best, set ->
                    if (best == null || (set.weightKg ?: 0.0) > (best.weightKg ?: 0.0) + WEIGHT_TOLERANCE_KG) set else best
                }
            when {
                actual != null -> RepMaxRecord(target, actual.weightKg, estimated = false, completedAt = actual.completedAt)
                bestE1Rm != null -> RepMaxRecord(target, predictedWeightKg(bestE1Rm!!, target), estimated = true, completedAt = null)
                else -> RepMaxRecord(target, null, estimated = false, completedAt = null)
            }
        }
    }

    return ExerciseRecords(
        maxWeightSet = maxWeightSet,
        bestE1RmKg = bestE1Rm,
        bestE1RmSet = bestE1RmSet,
        bestVolumeSet = bestVolumeSet,
        maxRepsSet = maxRepsSet,
        repMaxes = repMaxes,
    )
}

/** Inverse Epley: the weight [e1RmKg] predicts for [reps] reps (a single is the e1RM itself). */
fun predictedWeightKg(e1RmKg: Double, reps: Int): Double =
    if (reps <= 1) e1RmKg else e1RmKg / (1 + reps / 30.0)
