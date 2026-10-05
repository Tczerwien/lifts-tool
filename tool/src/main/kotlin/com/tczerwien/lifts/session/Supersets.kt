package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.TemplateExerciseEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity

/**
 * Supersets: exercises done in turn, one set of each, with the rest after the round. Adjacent slots
 * (by position) sharing a non-null group form one; a run of fewer than two is no superset, so a
 * partner deleted or moved away leaves a plain slot. Groups are stored per slot (workout and
 * template alike) and handled here as one list per workout or template, in position order. Pure,
 * covered by SupersetsTest.
 */

/**
 * The superset run around [index] of [groups] (in position order): the adjacent indices sharing its
 * non-null group. Null when it is in none: no group, or a group no neighbour shares.
 */
fun supersetRun(groups: List<Int?>, index: Int): IntRange? {
    val group = groups.getOrNull(index) ?: return null
    var first = index
    while (first > 0 && groups[first - 1] == group) first--
    var last = index
    while (last < groups.lastIndex && groups[last + 1] == group) last++
    return (first..last).takeIf { last > first }
}

/** Whether index [index] of [groups] is in a superset ([supersetRun]): the bar beside its row. */
fun inSuperset(groups: List<Int?>, index: Int): Boolean = supersetRun(groups, index) != null

/** Whether index [index] of [groups] is joined to the one after it: "Superset With Next" is on. */
fun linkedWithNext(groups: List<Int?>, index: Int): Boolean {
    val group = groups.getOrNull(index) ?: return false
    return groups.getOrNull(index + 1) == group
}

/**
 * The superset [slotId] is in: its run of adjacent slots by position ([supersetRun]), in order.
 * Empty when it is in none (SS-1).
 */
fun supersetOf(slots: List<WorkoutExerciseEntity>, slotId: Long): List<WorkoutExerciseEntity> {
    val ordered = slots.sortedBy { it.position }
    val index = ordered.indexOfFirst { it.id == slotId }
    if (index < 0) return emptyList()
    val run = supersetRun(ordered.map { it.supersetGroup }, index) ?: return emptyList()
    return ordered.slice(run)
}

/**
 * [groups] (in position order) with every superset whole and told apart: a group survives only as
 * one adjacent run of two or more; a run of one goes back to none; a run sharing its number with an
 * earlier one gets a new number above every number in use, so two pairs never merge when a move
 * brings them together. Runs already alone keep their numbers, so a removal rewrites only what it
 * changed (SS-4). Every reorder and removal runs it.
 */
fun normalizeGroups(groups: List<Int?>): List<Int?> {
    val result = MutableList<Int?>(groups.size) { null }
    val used = HashSet<Int>()
    var next = (groups.filterNotNull().maxOrNull() ?: 0) + 1
    var index = 0
    while (index < groups.size) {
        val run = supersetRun(groups, index)
        if (run == null) {
            index++
            continue
        }
        val group = groups[index]!!
        val number = if (used.add(group)) group else next++.also { used += it }
        for (i in run) result[i] = number
        index = run.last + 1
    }
    return result
}

/**
 * "Superset With Next" at [index] of [groups] turned [on] or off, normalised ([normalizeGroups]).
 * On joins it to the slot after it: into that one's superset, or a new one; two supersets that meet
 * merge. Off splits the run there, so a pair becomes two plain slots and a longer run loses the
 * slot at that end. Nothing changes when there is no slot after it (SS-4).
 */
fun linkWithNext(groups: List<Int?>, index: Int, on: Boolean): List<Int?> {
    if (index !in groups.indices || index + 1 !in groups.indices) return normalizeGroups(groups)
    val result = normalizeGroups(groups).toMutableList()
    val fresh = (result.filterNotNull().maxOrNull() ?: 0) + 1
    if (on) {
        val group = result[index] ?: result[index + 1] ?: fresh
        val other = result[index + 1]
        // The run after it joins whole: its number becomes this one's.
        val joined = supersetRun(result, index + 1) ?: (index + 1..index + 1)
        if (other != group) for (i in joined) result[i] = group
        result[index] = group
    } else if (linkedWithNext(result, index)) {
        val run = supersetRun(result, index)!!
        for (i in index + 1..run.last) result[i] = fresh
    }
    return normalizeGroups(result)
}

/**
 * Whether [a] and [b] (each in position order) group their slots the same way: the same runs at the
 * same places, whatever numbers they carry. A workout grouped as its template was is untouched.
 */
fun sameGrouping(a: List<Int?>, b: List<Int?>): Boolean = a.size == b.size && runShape(a) == runShape(b)

/** Each index's run as its first index, or null outside a superset: the grouping without its numbers. */
private fun runShape(groups: List<Int?>): List<Int?> = groups.indices.map { supersetRun(groups, it)?.first }

/**
 * Each index's superset as numbered in the exports: 1 for the first run, 2 for the next, and so on;
 * null outside a superset.
 */
fun supersetNumbers(groups: List<Int?>): List<Int?> {
    val starts = runShape(groups)
    val numbers = HashMap<Int, Int>()
    return starts.map { start -> start?.let { numbers.getOrPut(it) { numbers.size + 1 } } }
}

/**
 * Where one arrow tap ([delta] ±1) moves row [index] of [groups] (a template's groups in position
 * order): the next place, or past the whole superset there when the row isn't in it, so a plain
 * exercise moved past a pair doesn't land between the two and split them. A member moving within or
 * out of its own superset moves one place (out of it, it leaves: [normalizeGroups]).
 */
fun moveTarget(groups: List<Int?>, index: Int, delta: Int): Int {
    val next = index + delta
    val run = supersetRun(groups, next) ?: return next
    return when {
        index in run -> next
        delta > 0 -> run.last
        else -> run.first
    }
}

/**
 * A template's rows in their new order ([ordered]: after a move or a removal) as they must be stored:
 * positions 0..n-1, and supersets whole ([normalizeGroups]: a member moved away from its partner leaves
 * both plain). Only the rows that change, so a move writes two rows, or a few more when it splits a pair.
 */
fun settledTemplateRows(ordered: List<TemplateExerciseEntity>): List<TemplateExerciseEntity> {
    val groups = normalizeGroups(ordered.map { it.supersetGroup })
    return ordered.mapIndexedNotNull { index, row ->
        row.copy(position = index, supersetGroup = groups[index]).takeIf { it != row }
    }
}

/** [PlannedSlot]s with their groups normalised among themselves ([normalizeGroups]): after leaving slots out. */
fun List<PlannedSlot>.withNormalizedGroups(): List<PlannedSlot> {
    val groups = normalizeGroups(map { it.supersetGroup })
    return mapIndexed { index, slot -> if (slot.supersetGroup == groups[index]) slot else slot.copy(supersetGroup = groups[index]) }
}

private fun hasUntickedWork(slotId: Long, sets: List<SetEntity>): Boolean =
    sets.any { it.workoutExerciseId == slotId && it.completedAt == null && it.type != SET_TYPE_WARMUP }

/**
 * Whose turn it is after a set of [slotId] in superset [members] (in order): the next member after
 * it, wrapping round, that still has an unticked work row, itself last. Null when none has one.
 * Active Workout's move, its NEXT row and the Rest Timer's NEXT all follow it.
 */
fun supersetTurn(members: List<WorkoutExerciseEntity>, sets: List<SetEntity>, slotId: Long): WorkoutExerciseEntity? {
    val index = members.indexOfFirst { it.id == slotId }
    if (index < 0) return null
    return (members.drop(index + 1) + members.take(index + 1)).firstOrNull { hasUntickedWork(it.id, sets) }
}

/** What a tick leads to: whether the ticked slot's rest starts, and where Active Workout moves on its own (null: it stays). */
data class AfterTick(val startsRest: Boolean, val moveTo: Long?)

/**
 * What a tick of a [tickedType] set on slot [tickedSlotId] leads to, [sets] having the tick applied
 * (SS-2). A work tick of set [tickedSetId] whose next row is a drop still to do ([dropFollows]) starts no
 * rest and stays, plain or in a superset: the drop comes straight after, and the turn passes with its tick
 * (DR-6). A plain slot: its rest, and once its every row is ticked, the next exercise with a row
 * unticked ([autoAdvanceSlotId]), as before supersets. A work tick (normal, failure or drop) in a
 * superset passes the turn ([supersetTurn]):
 * - to a member later in the group: no rest, and the move comes 2 s after the tick;
 * - round to an earlier member (the round closed), or staying (only this member has sets left): the
 *   ticked slot's own work rest, and the move, if any, as it ends;
 * - to no one: the rest, and the move to the next exercise after the group with a row unticked, once the
 *   ticked exercise has no row unticked either, as a plain exercise moves on (a warm-up skipped on the
 *   other member doesn't hold it; one left on this exercise does, as it would on a plain one).
 * A warm-up tick rests and stays, as on a plain slot, unless it finishes the last of the group's work.
 */
fun afterTick(
    slots: List<WorkoutExerciseEntity>,
    sets: List<SetEntity>,
    tickedSlotId: Long,
    tickedType: String,
    tickedSetId: Long? = null,
): AfterTick {
    if (tickedType != SET_TYPE_WARMUP && tickedSetId != null && dropFollows(sets, tickedSetId)) {
        return AfterTick(startsRest = false, moveTo = null)
    }
    val members = supersetOf(slots, tickedSlotId)
    if (members.isEmpty()) return AfterTick(startsRest = true, moveTo = autoAdvanceSlotId(slots, sets, tickedSlotId))
    val turn = supersetTurn(members, sets, tickedSlotId)
    if (turn == null || tickedType == SET_TYPE_WARMUP) {
        val done = turn == null && sets.none { it.workoutExerciseId == tickedSlotId && it.completedAt == null }
        return AfterTick(startsRest = true, moveTo = if (done) firstUntickedSlotAfter(slots, sets, members.last().id)?.id else null)
    }
    return when {
        members.indexOf(turn) > members.indexOfFirst { it.id == tickedSlotId } -> AfterTick(startsRest = false, moveTo = turn.id)
        turn.id == tickedSlotId -> AfterTick(startsRest = true, moveTo = null)
        else -> AfterTick(startsRest = true, moveTo = turn.id)
    }
}
