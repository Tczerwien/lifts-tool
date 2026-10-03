package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.TemplateExerciseEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Supersets: which slots form one, whose turn a tick passes to, and how editing keeps them whole. */
class SupersetsTest {

    // --- SS-1: which slots form a superset ---

    @Test
    fun `a superset is the run of adjacent slots sharing a group, in position order`() {
        // Listed out of order on purpose: position decides, as the screen orders them.
        val slots = listOf(slot(3, 2, group = 4), slot(1, 0), slot(2, 1, group = 4), slot(4, 3))
        assertEquals(listOf(2L, 3L), supersetOf(slots, 3).map { it.id })
        assertEquals(listOf(2L, 3L), supersetOf(slots, 2).map { it.id })
        assertEquals(emptyList(), supersetOf(slots, 1), "no group")
        assertEquals(emptyList(), supersetOf(slots, 99), "not a slot of this workout")
    }

    @Test
    fun `a group of one, or one held apart by another slot, is no superset`() {
        assertEquals(emptyList(), supersetOf(listOf(slot(1, 0, group = 1), slot(2, 1)), 1), "its partner was deleted")
        val apart = listOf(slot(1, 0, group = 1), slot(2, 1), slot(3, 2, group = 1))
        assertEquals(emptyList(), supersetOf(apart, 1))
        assertEquals(emptyList(), supersetOf(apart, 3))
        assertNull(supersetRun(listOf(1, 2), 0), "neighbours in different groups")
    }

    @Test
    fun `a slot shows the bar while in a superset, and links with the next only inside one`() {
        val groups = listOf(null, 5, 5, 5, 7, null)
        assertEquals(listOf(false, true, true, true, false, false), groups.indices.map { inSuperset(groups, it) })
        assertEquals(listOf(false, true, true, false, false, false), groups.indices.map { linkedWithNext(groups, it) })
    }

    // --- SS-2: what a tick leads to ---

    // Squat (1), the pair Fly (2) and Row (3), then Calf Raise (4).
    private val pair = listOf(slot(1, 0), slot(2, 1, group = 1), slot(3, 2, group = 1), slot(4, 3))

    @Test
    fun `equal sets go A1 B1 A2 B2 A3 B3, resting only after each round`() {
        val sets = rows(1, 3, done = 3) + rows(2, 3) + rows(3, 3) + rows(4, 3)
        val order = listOf(21L, 31L, 22L, 32L, 23L, 33L)
        val outcomes = mutableListOf<AfterTick>()
        var state = sets
        for (id in order) {
            state = tick(state, id)
            outcomes += afterTick(pair, state, slotOf(id), SET_TYPE_NORMAL)
        }
        assertEquals(
            listOf(
                AfterTick(startsRest = false, moveTo = 3), AfterTick(startsRest = true, moveTo = 2),
                AfterTick(startsRest = false, moveTo = 3), AfterTick(startsRest = true, moveTo = 2),
                AfterTick(startsRest = false, moveTo = 3), AfterTick(startsRest = true, moveTo = 4),
            ),
            outcomes,
            "round 3 closes on Row, and the screen moves on to the exercise after the pair",
        )
    }

    @Test
    fun `with unequal sets the member with more finishes on its own, resting each time`() {
        // Fly has 4, Row 3: after round 3 the turn wraps to Fly's 4th, then the pair is done.
        val moreA = rows(1, 3, done = 3) + rows(2, 4, done = 3) + rows(3, 3, done = 3) + rows(4, 3)
        assertEquals(AfterTick(startsRest = true, moveTo = 2), afterTick(pair, moreA, 3, SET_TYPE_NORMAL))
        assertEquals(AfterTick(startsRest = true, moveTo = 4), afterTick(pair, tick(moreA, 24), 2, SET_TYPE_NORMAL))
        // Row has 4: after Fly's last, Row's 3rd comes without a rest; then Row stays and rests.
        val moreB = rows(1, 3, done = 3) + rows(2, 3, done = 3) + rows(3, 4, done = 2) + rows(4, 3)
        assertEquals(AfterTick(startsRest = false, moveTo = 3), afterTick(pair, moreB, 2, SET_TYPE_NORMAL))
        assertEquals(AfterTick(startsRest = true, moveTo = null), afterTick(pair, tick(moreB, 33), 3, SET_TYPE_NORMAL))
        assertEquals(AfterTick(startsRest = true, moveTo = 4), afterTick(pair, tick(tick(moreB, 33), 34), 3, SET_TYPE_NORMAL))
    }

    @Test
    fun `a warm-up tick rests and stays on its exercise`() {
        val sets = listOf(row(20, 2, type = SET_TYPE_WARMUP, done = true)) + rows(2, 3) + rows(3, 3)
        assertEquals(AfterTick(startsRest = true, moveTo = null), afterTick(pair, sets, 2, SET_TYPE_WARMUP))
    }

    @Test
    fun `failure and drop ticks pass the turn like any work set`() {
        val sets = rows(2, 3, done = 1) + rows(3, 3)
        for (type in listOf(SET_TYPE_FAILURE, SET_TYPE_DROP)) {
            assertEquals(AfterTick(startsRest = false, moveTo = 3), afterTick(pair, sets, 2, type), type)
        }
    }

    @Test
    fun `a member with no work left is skipped, and the turn wraps to the first that has some`() {
        // Three in a row: B finished early, so A hands to C, and C closes the round back to A.
        val tri = listOf(slot(1, 0, group = 2), slot(2, 1, group = 2), slot(3, 2, group = 2))
        val sets = rows(1, 3, done = 1) + rows(2, 1, done = 1) + rows(3, 3)
        assertEquals(AfterTick(startsRest = false, moveTo = 3), afterTick(tri, sets, 1, SET_TYPE_NORMAL))
        assertEquals(AfterTick(startsRest = true, moveTo = 1), afterTick(tri, tick(sets, 31), 3, SET_TYPE_NORMAL))
    }

    @Test
    fun `a group of one behaves as a plain slot`() {
        val lone = listOf(slot(1, 0, group = 3), slot(2, 1))
        val midway = rows(1, 2, done = 1) + rows(2, 2)
        assertEquals(AfterTick(startsRest = true, moveTo = null), afterTick(lone, midway, 1, SET_TYPE_NORMAL))
        assertEquals(AfterTick(startsRest = true, moveTo = 2), afterTick(lone, tick(midway, 12), 1, SET_TYPE_NORMAL))
    }

    @Test
    fun `after the pair it moves on to the next unfinished exercise, or nowhere once all is done`() {
        // Row is finished and the pair is last but one: from the pair to Calf Raise, then round to nothing.
        val sets = rows(1, 3, done = 3) + rows(2, 2, done = 2) + rows(3, 2, done = 2) + rows(4, 3)
        assertEquals(AfterTick(startsRest = true, moveTo = 4), afterTick(pair, sets, 3, SET_TYPE_NORMAL))
        val allDone = sets.map { it.copy(completedAt = it.completedAt ?: 9L) }
        assertEquals(AfterTick(startsRest = true, moveTo = null), afterTick(pair, allDone, 3, SET_TYPE_NORMAL))
    }

    @Test
    fun `a warm-up skipped on the other member doesn't hold the move after the last round`() {
        // As on two plain exercises: only the exercise on screen decides; the skipped warm-up isn't on it.
        val sets = listOf(row(20, 2, type = SET_TYPE_WARMUP)) + rows(2, 2, done = 2) + rows(3, 2, done = 2) + rows(4, 3)
        assertEquals(AfterTick(startsRest = true, moveTo = 4), afterTick(pair, sets, 3, SET_TYPE_NORMAL))
    }

    @Test
    fun `a warm-up left on the exercise just ticked holds the move, as on a plain exercise`() {
        val sets = rows(2, 2, done = 2) + listOf(row(30, 3, type = SET_TYPE_WARMUP)) + rows(3, 2, done = 2) + rows(4, 3)
        assertEquals(AfterTick(startsRest = true, moveTo = null), afterTick(pair, sets, 3, SET_TYPE_NORMAL))
        // Ticking that warm-up last finishes the pair: it rests, then moves on.
        assertEquals(AfterTick(startsRest = true, moveTo = 4), afterTick(pair, tick(sets, 30), 3, SET_TYPE_WARMUP))
    }

    @Test
    fun `a plain slot rests and moves on only once every row is ticked`() {
        val sets = rows(1, 2, done = 1) + rows(2, 2)
        assertEquals(AfterTick(startsRest = true, moveTo = null), afterTick(pair, sets, 1, SET_TYPE_NORMAL))
        assertEquals(AfterTick(startsRest = true, moveTo = 2), afterTick(pair, tick(sets, 12), 1, SET_TYPE_NORMAL))
    }

    @Test
    fun `whose turn it is goes round the group from the member after, itself last`() {
        val members = pair.slice(1..2)
        val sets = rows(2, 2, done = 1) + rows(3, 2, done = 1)
        assertEquals(3L, supersetTurn(members, sets, 2)?.id)
        assertEquals(2L, supersetTurn(members, sets, 3)?.id)
        assertEquals(2L, supersetTurn(members, rows(2, 2, done = 1) + rows(3, 1, done = 1), 2)?.id, "only itself left")
        assertNull(supersetTurn(members, rows(2, 1, done = 1) + rows(3, 1, done = 1), 2))
        assertNull(supersetTurn(members, sets, 1), "not a member")
    }

    // --- SS-4: editing keeps supersets whole ---

    @Test
    fun `normalising keeps each superset one run, and tells runs with the same number apart`() {
        assertEquals(listOf(null, null, null), normalizeGroups(listOf(1, null, 1)), "held apart: both alone")
        assertEquals(listOf(4, 4, null, 5, 5), normalizeGroups(listOf(4, 4, null, 4, 4)), "the second run gets a new number")
        assertEquals(listOf(7, 7, 2, 2), normalizeGroups(listOf(7, 7, 2, 2)), "runs already apart keep their numbers")
        assertEquals(listOf(null, 3, 3, null), normalizeGroups(listOf(2, 3, 3, 2)), "a group of one goes")
        assertEquals(emptyList(), normalizeGroups(emptyList()))
    }

    @Test
    fun `Superset With Next joins, merges and splits`() {
        assertEquals(listOf(1, 1, null), linkWithNext(listOf(null, null, null), 0, on = true), "two plain slots")
        assertEquals(listOf(null, 2, 2, 2), linkWithNext(listOf(null, 2, 2, null), 2, on = true), "into the pair")
        assertEquals(listOf(3, 3, 3), linkWithNext(listOf(null, 3, 3), 0, on = true), "from the left")
        assertEquals(listOf(1, 1, 1, 1), linkWithNext(listOf(1, 1, 2, 2), 1, on = true), "two pairs merge")
        assertEquals(listOf(null, null), linkWithNext(listOf(5, 5), 0, on = false), "a pair splits into two plain slots")
        assertEquals(listOf(5, 5, null), linkWithNext(listOf(5, 5, 5), 1, on = false), "the end of three goes alone")
        assertEquals(listOf(null, 6, 6), linkWithNext(listOf(5, 5, 5), 0, on = false), "the rest keep their own superset")
    }

    @Test
    fun `Superset With Next changes nothing on the last slot or where it is already so`() {
        assertEquals(listOf(1, 1), linkWithNext(listOf(1, 1), 1, on = true), "the last slot has no next")
        assertEquals(listOf(null, null), linkWithNext(listOf(null, null), 1, on = true))
        assertEquals(listOf(1, 1), linkWithNext(listOf(1, 1), 0, on = true), "already on")
        assertEquals(listOf(null, 2, 2), linkWithNext(listOf(null, 2, 2), 0, on = false), "already off")
    }

    @Test
    fun `the same grouping is the same runs, whatever their numbers`() {
        assertTrue(sameGrouping(listOf(null, 1, 1), listOf(null, 9, 9)))
        assertTrue(sameGrouping(listOf(3, null, 3), listOf(null, null, null)), "a group held apart is none")
        assertFalse(sameGrouping(listOf(1, 1, null), listOf(null, 1, 1)))
        assertFalse(sameGrouping(listOf(1, 1, 2, 2), listOf(1, 1, 1, 1)), "two pairs are not one superset of four")
        assertFalse(sameGrouping(listOf(1, 1), listOf(1, 1, null)))
    }

    @Test
    fun `the exports number supersets 1, 2 in order`() {
        assertEquals(listOf(null, 1, 1, null, 2, 2), supersetNumbers(listOf(null, 8, 8, null, 3, 3)))
        assertEquals(listOf(null, null), supersetNumbers(listOf(4, null)))
    }

    @Test
    fun `a template's rows settle to positions 0 to n-1 with whole supersets, writing only what changed`() {
        // Squat, then the pair Fly and Row, then Calf Raise; Row moved down past Calf Raise.
        val rows = listOf(templateRow(1, 0), templateRow(2, 1, group = 1), templateRow(3, 2, group = 1), templateRow(4, 3))
        val curlDown = listOf(rows[0], rows[1], rows[3], rows[2])
        assertEquals(
            listOf(Triple(2L, 1, null), Triple(4L, 2, null), Triple(3L, 3, null)),
            settledTemplateRows(curlDown).map { Triple(it.id, it.position, it.supersetGroup) },
            "moving a member away leaves both plain",
        )
        val swapped = listOf(rows[0], rows[2], rows[1], rows[3])
        assertEquals(
            listOf(Triple(3L, 1, 1), Triple(2L, 2, 1)),
            settledTemplateRows(swapped).map { Triple(it.id, it.position, it.supersetGroup) },
            "the pair swapped within itself stays a pair",
        )
        assertEquals(emptyList(), settledTemplateRows(rows), "nothing to write")
    }

    @Test
    fun `an arrow tap moves a plain exercise past a whole superset, and a member one place`() {
        // Squat, the pair (1, 2), Calf Raise.
        val groups = listOf(null, 4, 4, null)
        assertEquals(2, moveTarget(groups, 0, +1), "Squat down: past the pair, not between the two")
        assertEquals(1, moveTarget(groups, 3, -1), "Calf Raise up: before the pair")
        assertEquals(2, moveTarget(groups, 1, +1), "a member swaps within its pair")
        assertEquals(3, moveTarget(groups, 2, +1), "a member moves out (and leaves the pair)")
        assertEquals(0, moveTarget(groups, 1, -1))
        assertEquals(4, moveTarget(groups, 3, +1), "off the end: no move (moved refuses it)")
        assertEquals(3, moveTarget(listOf(4, 4, 7, 7), 1, +1), "a member moving out past another pair")
    }

    // --- Helpers ---

    private fun templateRow(id: Long, position: Int, group: Int? = null) = TemplateExerciseEntity(
        id = id, templateId = 1, exerciseId = "ex$id", position = position, targetSets = 3, supersetGroup = group,
    )

    private fun slot(id: Long, position: Int, group: Int? = null) =
        WorkoutExerciseEntity(id = id, workoutId = 1, exerciseId = "ex$id", position = position, supersetGroup = group)

    private fun row(id: Long, slot: Long, type: String = SET_TYPE_NORMAL, done: Boolean = false) = SetEntity(
        id = id,
        workoutExerciseId = slot,
        position = (id % 10).toInt(),
        type = type,
        completedAt = if (done) id else null,
    )

    /** [count] work rows of [slot], ids slot·10 + 1…, the first [done] of them ticked. */
    private fun rows(slot: Long, count: Int, done: Int = 0): List<SetEntity> =
        (1..count).map { row(slot * 10 + it, slot, done = it <= done) }

    private fun tick(sets: List<SetEntity>, id: Long): List<SetEntity> =
        sets.map { if (it.id == id) it.copy(completedAt = 1_000 + id) else it }

    private fun slotOf(setId: Long): Long = setId / 10
}
