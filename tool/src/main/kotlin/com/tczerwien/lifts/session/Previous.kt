package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.TemplateExerciseEntity
import com.tczerwien.lifts.data.Tracking
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit

/**
 * PREVIOUS and starting values: which of last session's sets each of today's rows is compared
 * with, which rows a new slot starts with, and what a row logs when it is ticked as it stands.
 * Every screen that shows or logs these goes through here. Pure, covered by PreviousTest.
 */

/** Rows a hand-added exercise starts with when it has no history. */
const val DEFAULT_NEW_EXERCISE_SETS = 3

private fun SetEntity.isWarmup(): Boolean = type == SET_TYPE_WARMUP

/**
 * Last session's completed sets of one exercise ([previous]) split into one group per slot of
 * that session ([slotIds], in slot order), so a slot where nothing was ticked is an empty group
 * and keeps its place: the next slot's sets don't slide up into it.
 */
fun previousGroups(previous: List<SetEntity>, slotIds: List<Long>): List<List<SetEntity>> {
    val bySlot = previous.groupBy { it.workoutExerciseId }
    return slotIds.map { slotId -> bySlot[slotId].orEmpty().sortedBy { it.position } }
}

/**
 * Last session's sets for each of today's [slots], keyed by slot id. Slots are matched by
 * occurrence: the k-th slot of an exercise (in position order) gets the k-th slot group from last
 * time ([groupsByExercise], from [previousGroups]), so a back-off Bench slot is compared with last
 * week's back-off sets, not the top sets. A slot last time had no counterpart for gets an empty list.
 */
fun previousForSlots(
    slots: List<WorkoutExerciseEntity>,
    groupsByExercise: Map<String, List<List<SetEntity>>>,
): Map<Long, List<SetEntity>> {
    val seen = HashMap<String, Int>()
    return slots.sortedBy { it.position }.associate { slot ->
        val occurrence = seen[slot.exerciseId] ?: 0
        seen[slot.exerciseId] = occurrence + 1
        slot.id to groupsByExercise[slot.exerciseId].orEmpty().getOrElse(occurrence) { emptyList() }
    }
}

/**
 * Pairs each of today's [rows] (one slot, in position order) with last session's set of the same
 * kind from [previous]: the k-th warm-up with last time's k-th warm-up, the k-th work set with last
 * time's k-th work set. Failure and drop sets are work. Null where last time had fewer. The result
 * is parallel to [rows].
 */
fun alignPrevious(rows: List<SetEntity>, previous: List<SetEntity>): List<SetEntity?> {
    val warmups = previous.filter { it.isWarmup() }
    val work = previous.filterNot { it.isWarmup() }
    var warmupIndex = 0
    var workIndex = 0
    return rows.map { row -> if (row.isWarmup()) warmups.getOrNull(warmupIndex++) else work.getOrNull(workIndex++) }
}

/** The weight and reps (or a hold's seconds) a row shows and logs. */
data class RowValues(val weightKg: Double?, val reps: Int?, val durationSec: Int? = null) {
    /** Nothing a tick could log: the row needs a number first. */
    val isEmpty: Boolean get() = weightKg == null && reps == null && durationSec == null
}

/**
 * What each of [rows] (one slot, in position order) logs if ticked as it stands, worked out per
 * field, weight, reps and seconds separately, then only the fields the exercise's [tracking]
 * records (a reps-only row never logs a weight left from before its type changed, and an assisted
 * row logs help as negative even when last time's was stored before the type was set):
 * 1. the row's own value;
 * 2. the value of today's previous row of the same kind (warm-up or work), if you changed it from
 *    last time and last time the two rows matched (straight sets): 5 lb on set 1 carries to set 2,
 *    but a heavier top set doesn't drag its back-off sets up, nor warm-up 1 the rest of the ramp;
 * 3. this row's PREVIOUS ([aligned], from [alignPrevious]);
 * 4. today's previous row of the same kind, which is how a new exercise carries set 1 forward;
 * 5. nothing.
 * "Today's previous row" means its value as worked out here, so a change carries down the sets.
 * Weights compare as they show in [unit], so stepping up and back down again is no change.
 */
fun seedRows(
    rows: List<SetEntity>,
    aligned: List<SetEntity?>,
    unit: WeightUnit,
    tracking: Tracking = Tracking.WEIGHT_REPS,
): List<RowValues> {
    val sameWeight = { a: Double?, b: Double? ->
        a != null && b != null &&
            Format.roundWeight(Format.kgToDisplay(a, unit), unit) == Format.roundWeight(Format.kgToDisplay(b, unit), unit)
    }
    val result = ArrayList<RowValues>(rows.size)
    var lastWarmup: Int? = null
    var lastWork: Int? = null
    rows.forEachIndexed { index, row ->
        val earlierIndex = if (row.isWarmup()) lastWarmup else lastWork
        val earlier = earlierIndex?.let { result[it] }
        val earlierPrevious = earlierIndex?.let { aligned.getOrNull(it) }
        val previous = aligned.getOrNull(index)
        val weight = row.weightKg
            ?: earlier?.weightKg?.takeIf {
                carries(it, earlierPrevious?.weightKg, previous?.weightKg, sameWeight)
            }
            ?: previous?.weightKg
            ?: earlier?.weightKg
        val reps = row.reps
            ?: earlier?.reps?.takeIf { carries(it, earlierPrevious?.reps, previous?.reps) { a, b -> a == b } }
            ?: previous?.reps
            ?: earlier?.reps
        val durationSec = row.durationSec
            ?: earlier?.durationSec?.takeIf { carries(it, earlierPrevious?.durationSec, previous?.durationSec) { a, b -> a == b } }
            ?: previous?.durationSec
            ?: earlier?.durationSec
        result += RowValues(weight, reps, durationSec)
        if (row.isWarmup()) lastWarmup = index else lastWork = index
    }
    return result.map { values ->
        RowValues(
            weightKg = values.weightKg?.takeIf { tracking.takesWeight }?.let(tracking::asLogged),
            reps = values.reps?.takeIf { tracking.takesReps },
            durationSec = values.durationSec?.takeIf { tracking.takesDuration },
        )
    }
}

/**
 * Rule 2 of [seedRows]: the earlier row's value carries when it was changed from last time and
 * last time this row had the same value as the earlier one, or neither had one (a bodyweight set 1
 * followed by a weighted set 2 is not a match).
 */
private fun <T : Any> carries(earlier: T, earlierPrevious: T?, previous: T?, same: (T?, T?) -> Boolean): Boolean =
    !same(earlier, earlierPrevious) &&
        (if (earlierPrevious == null) previous == null else same(earlierPrevious, previous))

/** [seedRows] for one row. */
fun seedFor(
    rows: List<SetEntity>,
    index: Int,
    aligned: List<SetEntity?>,
    unit: WeightUnit,
    tracking: Tracking = Tracking.WEIGHT_REPS,
): RowValues = seedRows(rows, aligned, unit, tracking)[index]

/**
 * Set types a new slot starts with, from [lastTime] (the matching slot group from last session).
 * From a template ([targetWorkSets] given): [warmupSets] warm-ups, or last time's when the template
 * leaves them to last session (null), then that many work sets. Added by hand: last time's pattern,
 * warm-ups kept, every other type as a plain work set. With no history either way: [targetWorkSets],
 * or [DEFAULT_NEW_EXERCISE_SETS], work sets.
 */
fun prefillTypes(lastTime: List<SetEntity>, targetWorkSets: Int? = null, warmupSets: Int? = null): List<String> {
    if (targetWorkSets != null) {
        val warmups = warmupSets ?: lastTime.count { it.isWarmup() }
        return List(warmups.coerceAtLeast(0)) { SET_TYPE_WARMUP } +
            List(targetWorkSets.coerceAtLeast(0)) { SET_TYPE_NORMAL }
    }
    if (lastTime.isEmpty()) return List(DEFAULT_NEW_EXERCISE_SETS) { SET_TYPE_NORMAL }
    return lastTime.map { if (it.isWarmup()) SET_TYPE_WARMUP else SET_TYPE_NORMAL }
}

/**
 * One slot as a start creates it: the exercise, its rows' set types in order, its rests, and the
 * targets shown at the gym (rep range, RIR), all copied from the template (or the workout started
 * again), so editing that later never changes this workout.
 */
data class PlannedSlot(
    val exerciseId: String,
    val types: List<String>,
    val restSecWork: Int? = null,
    val restSecWarmup: Int? = null,
    val targetRepMin: Int? = null,
    val targetRepMax: Int? = null,
    val targetRir: Int? = null,
)

/**
 * The slots a workout started from [templateExercises] begins with: one per template exercise in
 * position order, each with [prefillTypes] from its occurrence's slot group last session
 * ([groupsByExercise], from [previousGroups]). ActiveSession.startFromTemplate creates exactly
 * these, and [isUntouched] compares against them, so the two can't drift apart.
 */
fun planTemplate(
    templateExercises: List<TemplateExerciseEntity>,
    groupsByExercise: Map<String, List<List<SetEntity>>>,
): List<PlannedSlot> {
    val seen = HashMap<String, Int>()
    return templateExercises.sortedBy { it.position }.map { planned ->
        val occurrence = seen[planned.exerciseId] ?: 0
        seen[planned.exerciseId] = occurrence + 1
        val lastTime = groupsByExercise[planned.exerciseId].orEmpty().getOrElse(occurrence) { emptyList() }
        PlannedSlot(
            planned.exerciseId,
            prefillTypes(lastTime, targetWorkSets = planned.targetSets, warmupSets = planned.warmupSets),
            restSecWork = planned.restSecWork,
            restSecWarmup = planned.restSecWarmup,
            targetRepMin = planned.repMin,
            targetRepMax = planned.repMax,
            targetRir = planned.targetRir,
        )
    }
}

/**
 * The slots "Start again" begins with, from a finished workout's [slots] and [sets]: one per slot
 * with a ticked work set, in position order, each with its ticked sets' pattern (warm-ups kept, every
 * other type a plain work set) and the slot's rests and targets. Unticked sets count nowhere, so an
 * exercise skipped that day, or left after its warm-ups, is left out; notes stay with the old
 * workout. "Save as template" reads the same plan.
 */
fun planFromWorkout(slots: List<WorkoutExerciseEntity>, sets: List<SetEntity>): List<PlannedSlot> {
    val ticked = sets.filter { it.completedAt != null }.groupBy { it.workoutExerciseId }
    return slots.sortedBy { it.position }.mapNotNull { slot ->
        val done = ticked[slot.id]?.sortedBy { it.position }?.takeIf { sets -> sets.any { !it.isWarmup() } }
            ?: return@mapNotNull null
        PlannedSlot(
            exerciseId = slot.exerciseId,
            types = done.map { if (it.isWarmup()) SET_TYPE_WARMUP else SET_TYPE_NORMAL },
            restSecWork = slot.restSecWork,
            restSecWarmup = slot.restSecWarmup,
            targetRepMin = slot.targetRepMin,
            targetRepMax = slot.targetRepMax,
            targetRir = slot.targetRir,
        )
    }
}

/** "Set 2 of 3", counting work sets only, or "Warm-up 1 of 2" for a warm-up row of [rows]. */
fun nextSetLabel(rows: List<SetEntity>, index: Int): String {
    val warmup = rows[index].isWarmup()
    val number = rows.take(index + 1).count { it.isWarmup() == warmup }
    val count = rows.count { it.isWarmup() == warmup }
    return if (warmup) "Warm-up $number of $count" else "Set $number of $count"
}

/**
 * The exercise Active Workout opens on: the one that was on screen ([rememberedSlotId]) while it
 * still exists and has an unticked row, else the one [nextPendingSet] picks (the last tick's), else
 * the first. Null only for a workout with no exercises.
 */
fun openingSlotId(
    slots: List<WorkoutExerciseEntity>,
    sets: List<SetEntity>,
    rememberedSlotId: Long?,
): Long? {
    if (rememberedSlotId != null && slots.any { it.id == rememberedSlotId } &&
        sets.any { it.workoutExerciseId == rememberedSlotId && it.completedAt == null }
    ) {
        return rememberedSlotId
    }
    return nextPendingSet(slots, sets)?.slot?.id ?: slots.minByOrNull { it.position }?.id
}

/** What the Rest Timer's NEXT line is about. */
sealed interface RestNext {
    /** [rows] is the slot's rows in position order; [index] is the next unticked one. */
    data class Next(val slot: WorkoutExerciseEntity, val rows: List<SetEntity>, val index: Int) : RestNext

    /** The workout has no exercises yet. */
    data object NoExercises : RestNext

    /** It has exercises, but every one of them has had all its rows removed. */
    data object NoSets : RestNext

    /** Every row is ticked. */
    data object AllDone : RestNext
}

/**
 * The set the lifter is resting toward: the first unticked row of [currentSlotId] (the exercise on
 * screen) when it has one, otherwise the first of the next exercise with one, where Active Workout
 * moves on to ([autoAdvanceSlotId]). With no exercise on screen, [nextPendingSet]'s pick.
 */
fun restNextContext(
    slots: List<WorkoutExerciseEntity>,
    sets: List<SetEntity>,
    currentSlotId: Long?,
): RestNext {
    if (slots.isEmpty()) return RestNext.NoExercises
    val slotIds = slots.mapTo(HashSet()) { it.id }
    if (sets.none { it.workoutExerciseId in slotIds }) return RestNext.NoSets
    val current = slots.firstOrNull { it.id == currentSlotId }
    if (current != null) {
        val rows = sets.filter { it.workoutExerciseId == current.id }.sortedBy { it.position }
        val index = rows.indexOfFirst { it.completedAt == null }
        if (index >= 0) return RestNext.Next(current, rows, index)
        val next = firstUntickedSlotAfter(slots, sets, current.id) ?: return RestNext.AllDone
        val nextRows = sets.filter { it.workoutExerciseId == next.id }.sortedBy { it.position }
        return RestNext.Next(next, nextRows, nextRows.indexOfFirst { it.completedAt == null })
    }
    val next = nextPendingSet(slots, sets) ?: return RestNext.AllDone
    val rows = sets.filter { it.workoutExerciseId == next.slot.id }.sortedBy { it.position }
    return RestNext.Next(next.slot, rows, rows.indexOfFirst { it.id == next.set.id })
}
