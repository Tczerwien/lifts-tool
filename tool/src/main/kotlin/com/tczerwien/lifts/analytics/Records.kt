package com.tczerwien.lifts.analytics

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.Tracking

/**
 * The one PR/records engine. Every screen and export that stars a set or shows a record goes
 * through here, so they all agree on what counts. What counts depends on how the exercise is
 * measured ([Tracking]); callers that don't say get weight × reps.
 *
 * Rules:
 * - Only completed working sets with something to measure count ([recordEligible]): at least one
 *   rep, or for a hold at least one second. Warm-ups and failed 0-rep attempts never set or hold a
 *   record.
 * - Weight × reps: weighted sets (weight > 0) compete on weight, then on Epley e1RM. Weightless sets
 *   (a pull-up logged under a barbell lift) compete on reps, separately.
 * - Reps only: more reps. A hold: more seconds. Neither looks at a weight.
 * - Weighted bodyweight: sets with added weight compete on it, then on reps at that weight (an e1RM
 *   of the belt alone would rank 20×8 below 25×5, which with the body is the wrong way round). Sets
 *   at bodyweight compete on reps, separately.
 * - Assisted bodyweight: less assistance, then more reps at that assistance. No assistance is the
 *   least. Any weight on it is help, whatever its sign: a set logged as a plain +40 before the
 *   exercise was set to Assisted counts as 40 of help ([assistLoad]), as Lifts now stores it (-40).
 * - A set is compared only with sets completed before it, never with later workouts.
 * - Weights within [WEIGHT_TOLERANCE_KG] count as equal, so lb↔kg rounding never fakes a PR.
 */

/**
 * Weights closer than this are the same weight. 0.12 kg ≈ 0.26 lb: well under the smallest
 * plate step (1.25 lb / 0.5 kg), but wider than the drift a lb↔kg round trip leaves behind.
 */
const val WEIGHT_TOLERANCE_KG = 0.12

private const val SET_TYPE_WARMUP = "warmup"

/** Whether a logged set can set or hold a record: completed, not a warm-up, something measured. */
fun recordEligible(set: SetEntity, tracking: Tracking = Tracking.WEIGHT_REPS): Boolean =
    set.completedAt != null && set.type != SET_TYPE_WARMUP && measured(set, tracking) != null

/** The reps a set counts with, or its seconds for a hold; null (or under 1) is nothing measured. */
private fun measured(set: SetEntity, tracking: Tracking): Int? =
    (if (tracking == Tracking.DURATION) set.durationSec else set.reps)?.takeIf { it >= 1 }

private fun SetEntity.isWeighted(): Boolean = (weightKg ?: 0.0) > 0.0

/** An assisted set's load: its help as a negative number, whatever sign it was stored with; none is 0. */
private fun SetEntity.assistLoad(): Double = -kotlin.math.abs(weightKg ?: 0.0)

/** The load a record set holds, as this engine ranks it: an assisted set's help negative, else its weight. */
fun recordLoadKg(set: SetEntity, tracking: Tracking): Double =
    if (tracking == Tracking.ASSISTED_BODYWEIGHT) set.assistLoad() else set.weightKg ?: 0.0

/**
 * A heaviest-load record with reps as the tie-break: a set beats it with more load, or with the same
 * load (within [WEIGHT_TOLERANCE_KG]) and more reps. For the bodyweight kinds, where no e1RM applies.
 */
private class LoadRecord {
    var bestLoadKg: Double? = null
        private set
    private var repsAtBest = 0

    fun beats(loadKg: Double, reps: Int): Boolean {
        val best = bestLoadKg ?: return true
        if (loadKg > best + WEIGHT_TOLERANCE_KG) return true
        return loadKg >= best - WEIGHT_TOLERANCE_KG && reps > repsAtBest
    }

    fun record(loadKg: Double, reps: Int) {
        val best = bestLoadKg
        when {
            best == null || loadKg > best + WEIGHT_TOLERANCE_KG -> {
                bestLoadKg = loadKg
                repsAtBest = reps
            }
            loadKg >= best - WEIGHT_TOLERANCE_KG -> repsAtBest = maxOf(repsAtBest, reps)
        }
    }

    fun copy(): LoadRecord = LoadRecord().also {
        it.bestLoadKg = bestLoadKg
        it.repsAtBest = repsAtBest
    }
}

/**
 * Running record state for one exercise measured by [tracking]. Feed sets in completion order with
 * [record]; ask [wouldBePr] before recording a set to learn whether it beats everything seen so far.
 */
class RecordTracker(private val tracking: Tracking = Tracking.WEIGHT_REPS) {
    var maxWeightKg: Double? = null
        private set
    var bestE1RmKg: Double? = null
        private set
    var maxRepsWeightless: Int? = null
        private set

    /** Reps only: most reps; a hold: most seconds. */
    private var bestAmount: Int? = null

    /** The bodyweight kinds' load record ([LoadRecord]). */
    private var load = LoadRecord()

    /** A tracker in the same state, to go on from without changing this one. */
    fun copy(): RecordTracker = RecordTracker(tracking).also {
        it.maxWeightKg = maxWeightKg
        it.bestE1RmKg = bestE1RmKg
        it.maxRepsWeightless = maxRepsWeightless
        it.bestAmount = bestAmount
        it.load = load.copy()
    }

    /**
     * Whether [set] beats every set recorded so far. [set] need not be completed yet (the UI
     * asks before logging), but it must be a working set with something measured. The first set
     * of each kind of record is a PR.
     */
    fun wouldBePr(set: SetEntity): Boolean {
        if (set.type == SET_TYPE_WARMUP) return false
        val amount = measured(set, tracking) ?: return false
        return when (tracking) {
            Tracking.REPS_ONLY, Tracking.DURATION -> amount > (bestAmount ?: return true)
            Tracking.ASSISTED_BODYWEIGHT -> load.beats(set.assistLoad(), amount)
            Tracking.WEIGHTED_BODYWEIGHT ->
                if (set.isWeighted()) load.beats(set.weightKg!!, amount) else amount > (maxRepsWeightless ?: return true)
            Tracking.WEIGHT_REPS -> weightRepsPr(set, amount)
        }
    }

    private fun weightRepsPr(set: SetEntity, reps: Int): Boolean {
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
        if (!recordEligible(set, tracking)) return
        val amount = measured(set, tracking) ?: return
        when (tracking) {
            Tracking.REPS_ONLY, Tracking.DURATION -> bestAmount = maxOf(bestAmount ?: 0, amount)
            Tracking.ASSISTED_BODYWEIGHT -> load.record(set.assistLoad(), amount)
            Tracking.WEIGHTED_BODYWEIGHT ->
                if (set.isWeighted()) load.record(set.weightKg!!, amount) else recordWeightless(amount)
            Tracking.WEIGHT_REPS -> {
                if (!set.isWeighted()) {
                    recordWeightless(amount)
                    return
                }
                val weight = set.weightKg ?: return
                maxWeightKg = maxOf(maxWeightKg ?: weight, weight)
                epleyE1RmKg(weight, amount)?.let { e1Rm -> bestE1RmKg = maxOf(bestE1RmKg ?: e1Rm, e1Rm) }
            }
        }
    }

    private fun recordWeightless(reps: Int) {
        maxRepsWeightless = maxOf(maxRepsWeightless ?: 0, reps)
    }
}

/**
 * Whether [newSet] would be a personal record against [history] (earlier sets of the same
 * exercise, measured by [tracking]; history is filtered to eligible sets here, so callers may pass
 * raw lists, but it must not contain [newSet] itself).
 */
fun isPr(newSet: SetEntity, history: List<SetEntity>, tracking: Tracking = Tracking.WEIGHT_REPS): Boolean {
    val tracker = RecordTracker(tracking)
    history.forEach(tracker::record)
    return tracker.wouldBePr(newSet)
}

private val COMPLETION_ORDER = compareBy<SetEntity>({ it.completedAt }, { it.id })

/**
 * Ids of the sets of ONE exercise (measured by [tracking]) that were PRs when they were completed:
 * each completed set is compared with the sets completed before it. One pass with running maxima.
 */
fun chronologicalPrSetIds(sets: List<SetEntity>, tracking: Tracking = Tracking.WEIGHT_REPS): Set<Long> {
    val tracker = RecordTracker(tracking)
    val result = HashSet<Long>()
    for (set in sets.filter { it.completedAt != null }.sortedWith(COMPLETION_ORDER)) {
        if (recordEligible(set, tracking) && tracker.wouldBePr(set)) result.add(set.id)
        tracker.record(set)
    }
    return result
}

/**
 * [chronologicalPrSetIds] for several exercises at once, keyed by exercise id, each by its own
 * [tracking] (an exercise missing from it is weight × reps).
 */
fun chronologicalPrSetIds(
    setsByExercise: Map<String, List<SetEntity>>,
    tracking: Map<String, Tracking> = emptyMap(),
): Set<Long> = setsByExercise.flatMapTo(HashSet()) { (exerciseId, sets) ->
    chronologicalPrSetIds(sets, tracking[exerciseId] ?: Tracking.WEIGHT_REPS)
}

/**
 * One exercise's completed sets from other workouts, replayed through the records engine once, so
 * starring today's sets costs a pass over today's sets, not over years of history: Active Workout
 * works the stars out again on every tap, on the main thread. The replay is done the first time a
 * [tracking] is asked for and kept.
 */
class PriorHistory(sets: List<SetEntity>) {
    /** The completed sets, in completion order. */
    val ordered: List<SetEntity> = sets.filter { it.completedAt != null }.sortedWith(COMPLETION_ORDER)

    private val replayed = HashMap<Tracking, RecordTracker>()

    /** The record state after every set in [ordered], as a copy to go on from. */
    fun after(tracking: Tracking): RecordTracker = replay(tracking).copy()

    /** Replays [ordered] for [tracking] now (off the main thread), so the first [after] doesn't have to. */
    fun warm(tracking: Tracking) {
        replay(tracking)
    }

    private fun replay(tracking: Tracking): RecordTracker = synchronized(replayed) {
        replayed.getOrPut(tracking) { RecordTracker(tracking).also { tracker -> ordered.forEach(tracker::record) } }
    }

    /** Whether [set] comes after every set in [ordered] in completion order (it can go on from [after]). */
    fun isBefore(set: SetEntity): Boolean = ordered.lastOrNull()?.let { COMPLETION_ORDER.compare(it, set) < 0 } ?: true

    companion object {
        val NONE = PriorHistory(emptyList())
    }
}

/**
 * Ids of [sets] (ONE exercise's sets of one workout, any order, unticked ones ignored) that were PRs
 * when they were completed, measured by [tracking] against [prior] and each other: the same answer as
 * [chronologicalPrSetIds] over [prior]'s sets and [sets] together. When every ticked set of [sets]
 * comes after all of [prior] (always, but for a workout resumed after a later one or a clock set back)
 * it goes on from [prior]'s replayed state; otherwise it replays everything.
 */
fun prSetIdsAfter(sets: List<SetEntity>, prior: PriorHistory, tracking: Tracking = Tracking.WEIGHT_REPS): Set<Long> {
    val ticked = sets.filter { it.completedAt != null }.sortedWith(COMPLETION_ORDER)
    if (ticked.isEmpty()) return emptySet()
    if (!prior.isBefore(ticked.first())) {
        val ids = ticked.mapTo(HashSet()) { it.id }
        return chronologicalPrSetIds(ticked + prior.ordered.filter { it.id !in ids }, tracking).filterTo(HashSet()) { it in ids }
    }
    val tracker = prior.after(tracking)
    val result = HashSet<Long>()
    for (set in ticked) {
        if (recordEligible(set, tracking) && tracker.wouldBePr(set)) result.add(set.id)
        tracker.record(set)
    }
    return result
}

/** A rep-max row: the heaviest weight for [reps] reps, actual or [estimated] from the best e1RM. */
data class RepMaxRecord(
    val reps: Int,
    val weightKg: Double?,
    val estimated: Boolean,
    val completedAt: Long?,
)

/**
 * Records for one exercise; each field is null (or empty) where it doesn't apply to the exercise's
 * [Tracking] or no eligible set has it yet.
 * - [maxWeightSet]: weight × reps: the heaviest weighted set. Weighted bodyweight: the most added
 *   weight, then reps. Assisted: the least assistance, then reps (unassisted is the least).
 * - [bestE1RmKg], [bestE1RmSet], [bestVolumeSet], [repMaxes]: weight × reps only.
 * - [maxRepsSet]: the most reps without weight: weightless sets of a weight × reps lift, every set of
 *   a reps-only one, bodyweight sets of a weighted bodyweight one.
 * - [longestSet]: a hold's longest set.
 */
data class ExerciseRecords(
    val maxWeightSet: SetEntity?,
    val bestE1RmKg: Double?,
    val bestE1RmSet: SetEntity?,
    val bestVolumeSet: SetEntity?,
    val maxRepsSet: SetEntity?,
    val repMaxes: List<RepMaxRecord>,
    val longestSet: SetEntity? = null,
)

/**
 * Records over one exercise's [sets] (any order; only eligible sets count), measured by [tracking].
 * Ties keep the first set to reach a value. A rep max for N reps is the heaviest set of at least N
 * reps (145×10 proves you can do 145×8); with no such set it is estimated from the best e1RM by
 * inverse Epley. Returns null when no eligible set exists.
 */
fun exerciseRecords(
    sets: List<SetEntity>,
    repTargets: List<Int> = listOf(1, 5, 8),
    tracking: Tracking = Tracking.WEIGHT_REPS,
): ExerciseRecords? {
    val eligible = sets.filter { recordEligible(it, tracking) }.sortedWith(COMPLETION_ORDER)
    if (eligible.isEmpty()) return null
    fun firstMost(candidates: List<SetEntity>, amount: (SetEntity) -> Int): SetEntity? =
        candidates.fold(null as SetEntity?) { best, set -> if (best == null || amount(set) > amount(best)) set else best }

    return when (tracking) {
        Tracking.WEIGHT_REPS -> weightRepsRecords(eligible, repTargets)
        Tracking.REPS_ONLY -> ExerciseRecords(null, null, null, null, firstMost(eligible) { it.reps ?: 0 }, emptyList())
        Tracking.DURATION ->
            ExerciseRecords(null, null, null, null, null, emptyList(), longestSet = firstMost(eligible) { it.durationSec ?: 0 })
        Tracking.WEIGHTED_BODYWEIGHT -> ExerciseRecords(
            maxWeightSet = bestLoadSet(eligible.filter { it.isWeighted() }),
            bestE1RmKg = null,
            bestE1RmSet = null,
            bestVolumeSet = null,
            maxRepsSet = firstMost(eligible.filterNot { it.isWeighted() }) { it.reps ?: 0 },
            repMaxes = emptyList(),
        )
        Tracking.ASSISTED_BODYWEIGHT -> ExerciseRecords(bestLoadSet(eligible) { it.assistLoad() }, null, null, null, null, emptyList())
    }
}

/**
 * The set that holds the [LoadRecord] over [ordered] (completion order): the one that last beat it,
 * so at the top load the one with the most reps. [loadOf] is a set's load (the weight; help, negative).
 */
private fun bestLoadSet(ordered: List<SetEntity>, loadOf: (SetEntity) -> Double = { it.weightKg ?: 0.0 }): SetEntity? {
    val record = LoadRecord()
    var holder: SetEntity? = null
    for (set in ordered) {
        val load = loadOf(set)
        val reps = set.reps ?: continue
        if (record.beats(load, reps)) holder = set
        record.record(load, reps)
    }
    return holder
}

private fun weightRepsRecords(eligible: List<SetEntity>, repTargets: List<Int>): ExerciseRecords {
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
