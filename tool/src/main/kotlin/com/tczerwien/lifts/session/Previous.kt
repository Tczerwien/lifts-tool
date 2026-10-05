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
 * One of last session's slots for an exercise ([previousGroups]): its sets of that exercise in position
 * order, and whether the slot did it in place of the lift its start planned ([swappedIn], an alternative),
 * or was planned as it but did another ([swappedAway]: no sets of it).
 */
data class LastSlot(val sets: List<SetEntity>, val swappedIn: Boolean = false, val swappedAway: Boolean = false)

/**
 * Last session's completed sets of [exerciseId] ([previous]) split into one group per slot of that session
 * that did it or was planned as it ([slots], any order; [plannedExerciseId]), in slot order. A slot where
 * nothing was ticked, or that did another lift in its place (swapped away), is an empty group and keeps
 * its place: the next slot's sets don't slide up into it.
 */
fun previousGroups(previous: List<SetEntity>, slots: List<WorkoutExerciseEntity>, exerciseId: String): List<LastSlot> {
    val bySlot = previous.groupBy { it.workoutExerciseId }
    return slots.sortedBy { it.position }.map { slot ->
        LastSlot(
            sets = if (slot.exerciseId == exerciseId) bySlot[slot.id].orEmpty().sortedBy { it.position } else emptyList(),
            swappedIn = slot.plannedExerciseId != exerciseId,
            swappedAway = slot.exerciseId != exerciseId,
        )
    }
}

/**
 * Last session's sets for each of today's [slots], keyed by slot id ([lastTime], from [previousGroups]).
 * Like is matched with like, by occurrence in position order: the k-th slot planned as an exercise (counting
 * one swapped for another today, which keeps its place) gets last time's k-th slot planned as it, so a
 * back-off Bench slot is compared with last week's back-off sets, not the top sets, whichever of them a swap
 * took; the k-th slot swapped to an exercise gets last time's k-th slot swapped to it. A slot with no such
 * counterpart then takes, in order, last time's sets of that exercise no slot was matched with (the lift done
 * last time as an alternative, or as the main elsewhere: its numbers, never another slot's); else nothing. One
 * whose counterpart did another lift (swapped away, not skipped) takes only sets of the exercise done last time
 * as an alternative (two slots swapped across).
 */
fun previousForSlots(
    slots: List<WorkoutExerciseEntity>,
    lastTime: Map<String, List<LastSlot>>,
): Map<Long, List<SetEntity>> {
    val ordered = slots.sortedBy { it.position }
    val result = HashMap<Long, List<SetEntity>>()
    for (exerciseId in ordered.mapTo(LinkedHashSet()) { it.exerciseId }) {
        val last = lastTime[exerciseId].orEmpty()
        val plannedAt = last.indices.filter { !last[it].swappedIn }
        val swappedAt = last.indices.filter { last[it].swappedIn }
        val claimed = HashSet<Int>()
        // Slots still to fill, in position order, each with whether only swapped-in sets will do.
        val withoutCounterpart = mutableListOf<Pair<Long, Boolean>>()
        var plannedSeen = 0
        var swappedSeen = 0
        for (slot in ordered) {
            val index = when {
                slot.plannedExerciseId == exerciseId -> plannedAt.getOrNull(plannedSeen++)
                slot.exerciseId == exerciseId -> swappedAt.getOrNull(swappedSeen++)
                else -> continue
            }
            // A slot planned as it but doing another lift today only keeps its place.
            if (slot.exerciseId != exerciseId) continue
            when {
                index == null -> withoutCounterpart += slot.id to false
                last[index].swappedAway -> {
                    claimed += index
                    withoutCounterpart += slot.id to true
                }
                else -> {
                    claimed += index
                    result[slot.id] = last[index].sets
                }
            }
        }
        for ((slotId, swappedInOnly) in withoutCounterpart) {
            val pick = last.indices.firstOrNull { it !in claimed && last[it].sets.isNotEmpty() && (!swappedInOnly || last[it].swappedIn) }
            result[slotId] = pick?.let { claimed += it; last[it].sets }.orEmpty()
        }
    }
    return result
}

/**
 * Pairs each of today's [rows] (one slot, in position order) with last session's set of the same
 * kind from [previous] ([rowKinds]): the k-th warm-up with last time's k-th warm-up, the k-th work set
 * with last time's k-th work set, the k-th drop with last time's k-th drop. Failure sets are work; a
 * drop is part of its set (DR-4), so a drop added today never shifts the work sets after it onto
 * last time's. Null where last time had fewer. The result is parallel to [rows].
 */
fun alignPrevious(rows: List<SetEntity>, previous: List<SetEntity>): List<SetEntity?> {
    val lastTime = previous.zip(rowKinds(previous)).groupBy({ it.second }, { it.first })
    val used = HashMap<RowKind, Int>()
    return rowKinds(rows).map { kind ->
        val index = used.getOrDefault(kind, 0)
        used[kind] = index + 1
        lastTime[kind]?.getOrNull(index)
    }
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
 * One slot as a start creates it: the exercise, its rows' set types in order, its rests, the
 * targets shown at the gym (rep range, RIR or an RIR range) and its superset group, all copied from the
 * template (or the workout started again), so editing that later never changes this workout.
 */
data class PlannedSlot(
    val exerciseId: String,
    val types: List<String>,
    val restSecWork: Int? = null,
    val restSecWarmup: Int? = null,
    val targetRepMin: Int? = null,
    val targetRepMax: Int? = null,
    val targetRir: Int? = null,
    /** Slots next to each other sharing one are a superset ([supersetRun]). */
    val supersetGroup: Int? = null,
    /** The top of an RIR range ([rirTarget]); null: [targetRir] alone. */
    val targetRirMax: Int? = null,
    /**
     * The work sets planned (DL-0): the template's, or for Start Again the source slot's (else the work
     * sets it copies). What switching a deload off puts back; null for a slot with no plan.
     */
    val targetSets: Int? = null,
    /** The exercises the swap cycles through, main first ([slotChoices]); empty: no alternatives. */
    val choices: List<String> = emptyList(),
)

/**
 * The slots a workout started from [templateExercises] begins with: one per template exercise in
 * position order, each with [prefillTypes] from its slot group last session as [previousForSlots] matches
 * it ([lastTime], from [previousGroups]), its alternatives as the slot's choices ([slotChoices]),
 * and the template's supersets ([normalizeGroups]: a group the template holds apart, or alone, starts
 * plain). ActiveSession.startFromTemplate creates exactly these, and [isUntouched] compares against
 * them, so the two can't drift apart.
 */
fun planTemplate(
    templateExercises: List<TemplateExerciseEntity>,
    lastTime: Map<String, List<LastSlot>>,
): List<PlannedSlot> {
    val ordered = templateExercises.sortedBy { it.position }
    // A start is as planned: each template exercise a slot doing its main.
    val lastTimeByRow = previousForSlots(
        ordered.mapIndexed { index, row -> WorkoutExerciseEntity(id = row.id, workoutId = 0, exerciseId = row.exerciseId, position = index) },
        lastTime,
    )
    return ordered.map { planned ->
        val lastTime = lastTimeByRow[planned.id].orEmpty()
        PlannedSlot(
            planned.exerciseId,
            prefillTypes(lastTime, targetWorkSets = planned.targetSets, warmupSets = planned.warmupSets),
            restSecWork = planned.restSecWork,
            restSecWarmup = planned.restSecWarmup,
            targetRepMin = planned.repMin,
            targetRepMax = planned.repMax,
            targetRir = planned.targetRir,
            supersetGroup = planned.supersetGroup,
            targetRirMax = planned.targetRirMax,
            targetSets = planned.targetSets,
            choices = slotChoices(planned.exerciseId, planned.alternatives),
        )
    }.withNormalizedGroups()
}

/**
 * The slots "Start again" begins with, from a finished workout's [slots] and [sets]: one per slot
 * with a ticked work set, in position order, each with its ticked sets' pattern (warm-ups kept, every
 * other type a plain work set), the exercise done (a swapped one too) and the slot's choices, the slot's
 * rests and targets, its planned work sets (the slot's, else
 * the work sets copied: a deload's copy keeps the full plan), and its superset among the slots kept
 * (a superset left with one slot is none). Unticked sets count nowhere, so an exercise skipped that
 * day, or left after its warm-ups, is left out; notes stay with the old workout. "Save as template"
 * reads the same plan.
 */
fun planFromWorkout(slots: List<WorkoutExerciseEntity>, sets: List<SetEntity>): List<PlannedSlot> {
    val ticked = sets.filter { it.completedAt != null }.groupBy { it.workoutExerciseId }
    return slots.sortedBy { it.position }.mapNotNull { slot ->
        val done = ticked[slot.id]?.sortedBy { it.position }?.takeIf { sets -> sets.any { !it.isWarmup() } }
            ?: return@mapNotNull null
        val types = done.map { if (it.isWarmup()) SET_TYPE_WARMUP else SET_TYPE_NORMAL }
        PlannedSlot(
            exerciseId = slot.exerciseId,
            types = types,
            restSecWork = slot.restSecWork,
            restSecWarmup = slot.restSecWarmup,
            targetRepMin = slot.targetRepMin,
            targetRepMax = slot.targetRepMax,
            targetRir = slot.targetRir,
            supersetGroup = slot.supersetGroup,
            targetRirMax = slot.targetRirMax,
            targetSets = slot.targetSets ?: types.count { it != SET_TYPE_WARMUP },
            choices = slot.choices,
        )
    }.withNormalizedGroups()
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
 * the first. In a superset whose round closed with the latest tick, on that exercise (or on the last
 * tick's, with none remembered), the member the turn went back to: where the screen was going as the
 * round's rest ended when it was left (Back to Home during the rest, or the app killed), as the NEXT
 * row and the Rest Timer already say. A turn handed on to a later member is followed only when the
 * exercise left on is finished: otherwise it looks the same as his going back to the member he just
 * did, on purpose (and the NEXT row names the partner either way). The trade-off runs the other way
 * after the round closed: going back to the last member on purpose and leaving reopens on the first
 * (Back to Home during the round's rest is the common case). Null only for a workout with no exercises.
 */
fun openingSlotId(
    slots: List<WorkoutExerciseEntity>,
    sets: List<SetEntity>,
    rememberedSlotId: Long?,
): Long? {
    val leftOn = rememberedSlotId?.takeIf { id -> slots.any { it.id == id } }
        ?: sets.filter { it.completedAt != null }.maxWithOrNull(compareBy({ it.completedAt }, { it.id }))?.workoutExerciseId
    if (leftOn != null) {
        val position = slots.firstOrNull { it.id == leftOn }?.position
        val finished = sets.none { it.workoutExerciseId == leftOn && it.completedAt == null }
        turnPassedFrom(slots, sets, leftOn)?.takeIf { position != null && (it.position < position || finished) }?.let { return it.id }
    }
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
 * The set the lifter is resting toward, the one Active Workout's steppers are on: [activeSetId]
 * while it is an unticked row of [currentSlotId] (the exercise on screen), else the row he is on
 * there ([nextRowOf]: after his last tick, not a warm-up he skipped); once that exercise is done,
 * the row he is on in the next exercise with one unticked, where Active Workout moves on to
 * ([autoAdvanceSlotId]). In a superset whose latest tick, on the exercise on screen, hands the turn
 * to another member ([afterTick]: A's set 2 after B's set 1, not after a warm-up), the row he is on
 * in that member, where Active Workout moves to. With no exercise on screen, in [nextPendingSet]'s exercise.
 */
fun restNextContext(
    slots: List<WorkoutExerciseEntity>,
    sets: List<SetEntity>,
    currentSlotId: Long?,
    activeSetId: Long? = null,
): RestNext {
    if (slots.isEmpty()) return RestNext.NoExercises
    val slotIds = slots.mapTo(HashSet()) { it.id }
    if (sets.none { it.workoutExerciseId in slotIds }) return RestNext.NoSets
    fun rowsOf(slotId: Long) = sets.filter { it.workoutExerciseId == slotId }.sortedBy { it.position }
    fun indexOf(rows: List<SetEntity>, id: Long?) = rows.indexOfFirst { it.id == id && it.completedAt == null }
    val current = slots.firstOrNull { it.id == currentSlotId }
    if (current != null) {
        turnPassedFrom(slots, sets, current.id)?.let { member ->
            val memberRows = rowsOf(member.id)
            return RestNext.Next(member, memberRows, indexOf(memberRows, nextRowOf(memberRows)))
        }
        val rows = rowsOf(current.id)
        val index = indexOf(rows, activeSetId).takeIf { it >= 0 } ?: indexOf(rows, nextRowOf(rows))
        if (index >= 0) return RestNext.Next(current, rows, index)
        val next = firstUntickedSlotAfter(slots, sets, current.id) ?: return RestNext.AllDone
        val nextRows = rowsOf(next.id)
        return RestNext.Next(next, nextRows, indexOf(nextRows, nextRowOf(nextRows)))
    }
    val next = nextPendingSet(slots, sets) ?: return RestNext.AllDone
    val rows = rowsOf(next.slot.id)
    return RestNext.Next(next.slot, rows, indexOf(rows, nextRowOf(rows)))
}

/**
 * The superset member the turn has passed to from [slotId]: where the group's latest tick, made on
 * [slotId], moves the screen ([afterTick], the rule the move itself follows: a warm-up tick stays),
 * when that is another member. Null when it stays or leaves the group, [slotId] is in no superset, or
 * its set is still to come (the latest tick was on another member: he has just moved to [slotId]).
 */
private fun turnPassedFrom(slots: List<WorkoutExerciseEntity>, sets: List<SetEntity>, slotId: Long): WorkoutExerciseEntity? {
    val members = supersetOf(slots, slotId)
    if (members.isEmpty()) return null
    val memberIds = members.mapTo(HashSet()) { it.id }
    val latest = sets.filter { it.workoutExerciseId in memberIds && it.completedAt != null }
        .maxWithOrNull(compareBy({ it.completedAt }, { it.id }))
    if (latest?.workoutExerciseId != slotId) return null
    val moveTo = afterTick(slots, sets, slotId, latest.type).moveTo
    return members.firstOrNull { it.id == moveTo && it.id != slotId }
}
