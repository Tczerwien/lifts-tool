package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.Tracking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TargetsTest {

    // --- Rep range steppers ---

    @Test
    fun `stepping the low end up from none starts a range at 8`() {
        assertEquals(RepRange(8, null), stepRepMin(RepRange(null, null), +1))
        assertEquals(RepRange(null, null), stepRepMin(RepRange(null, null), -1), "down from none stays none")
        // Below a top of 6 it starts at the top.
        assertEquals(RepRange(6, 6), stepRepMin(RepRange(null, 6), +1))
    }

    @Test
    fun `stepping the top up from none makes 8–12`() {
        assertEquals(RepRange(null, 12), stepRepMax(RepRange(null, null), +1))
        assertEquals(RepRange(12, 16), stepRepMax(RepRange(12, null), +1), "four above the low end")
        assertEquals(RepRange(12, null), stepRepMax(RepRange(12, null), -1), "down from none stays none")
    }

    @Test
    fun `the range never turns inside out`() {
        assertEquals(RepRange(13, 13), stepRepMin(RepRange(12, 12), +1), "the low end pushes the top up")
        assertEquals(RepRange(11, 11), stepRepMax(RepRange(12, 12), -1), "the top pushes the low end down")
        assertEquals(RepRange(9, 12), stepRepMin(RepRange(8, 12), +1))
        assertEquals(RepRange(8, 11), stepRepMax(RepRange(8, 12), -1))
    }

    @Test
    fun `an end stepped below one clears it and the top stops at 50`() {
        assertEquals(RepRange(null, 12), stepRepMin(RepRange(1, 12), -1))
        assertEquals(RepRange(null, null), stepRepMax(RepRange(null, 1), -1))
        assertEquals(RepRange(8, 50), stepRepMax(RepRange(8, 50), +1))
        assertEquals(RepRange(50, 50), stepRepMin(RepRange(50, 50), +5))
        // A big hold step can't push past 50 either.
        assertEquals(RepRange(46, 50), stepRepMax(RepRange(46, 48), +5))
    }

    @Test
    fun `a range reads as he writes it`() {
        assertEquals("6–12", repRangeText(6, 12))
        assertEquals("10", repRangeText(10, 10))
        assertEquals("8+", repRangeText(8, null))
        assertEquals("≤12", repRangeText(null, 12))
        assertNull(repRangeText(null, null))
    }

    // --- RIR target and warm-ups ---

    @Test
    fun `template warm-ups step from last session's to a count`() {
        assertEquals(0, stepTemplateWarmups(null, +1), "none: an explicit no warm-ups")
        assertEquals(2, stepTemplateWarmups(1, +1))
        assertEquals(5, stepTemplateWarmups(5, +1))
        assertNull(stepTemplateWarmups(0, -1), "below none is last session's again")
        assertNull(stepTemplateWarmups(null, -1))
    }

    // --- Rest ---

    @Test
    fun `a rest equal to the default is stored as none`() {
        assertNull(restOverride(180, defaultSec = 180), "+ then − is no change")
        assertEquals(165, restOverride(165, defaultSec = 180))
        assertEquals(0, restOverride(0, defaultSec = 180), "a 0 s rest (a superset) is kept")
        assertEquals(0, restOverride(-15, defaultSec = 180))
    }

    // --- Header line ---

    @Test
    fun `the header's target line`() {
        assertEquals("6–12 REPS · RIR 1", targetLine(6, 12, RirTarget(1)))
        assertEquals("8–12 REPS · RIR 1–2", targetLine(8, 12, RirTarget(1, 2)))
        assertEquals("8–12 REPS · ADD LOAD", targetLine(8, 12, null, HINT_ADD_LOAD))
        assertEquals("RIR 0", targetLine(null, null, RirTarget(0)))
        assertEquals("10–12 REPS/SIDE · RIR 2", targetLine(10, 12, RirTarget(2), perSide = true))
        assertNull(targetLine(null, null, null))
        assertEquals("10–15 REPS · RIR 1 · SUPERSET", targetLine(10, 15, RirTarget(1), superset = true))
        assertEquals("8–12 REPS · ADD LOAD · SUPERSET", targetLine(8, 12, null, HINT_ADD_LOAD, superset = true))
        assertEquals("SUPERSET", targetLine(null, null, null, superset = true), "a superset without a target still says so")
    }

    @Test
    fun `a deload says so where the hint goes, on every exercise`() {
        assertEquals("8–12 REPS · RIR 4–5 · DELOAD", targetLine(8, 12, RirTarget(4, 5), deload = true))
        assertEquals("8–12 REPS · DELOAD", targetLine(8, 12, null, HINT_ADD_LOAD, deload = true), "never beside ADD LOAD")
        assertEquals("DELOAD · SUPERSET", targetLine(null, null, null, superset = true, deload = true), "a hold in a superset")
    }

    // --- Progression hint ---

    private var nextId = 1L

    private fun done(type: String, weightKg: Double?, reps: Int?) =
        SetEntity(id = nextId++, workoutExerciseId = 1, position = 0, type = type, weightKg = weightKg, reps = reps, completedAt = 1_000L)

    @Test
    fun `every work set at the top of the range says add load`() {
        val last = listOf(
            done(SET_TYPE_WARMUP, 40.0, 5),
            done(SET_TYPE_NORMAL, 60.0, 12),
            done(SET_TYPE_NORMAL, 60.0, 12),
            done(SET_TYPE_FAILURE, 60.0, 13),
        )
        assertEquals(HINT_ADD_LOAD, progressionHint(Tracking.WEIGHT_REPS, 12, last))
    }

    @Test
    fun `one work set short of the top says nothing`() {
        val last = listOf(done(SET_TYPE_NORMAL, 60.0, 12), done(SET_TYPE_NORMAL, 60.0, 11))
        assertNull(progressionHint(Tracking.WEIGHT_REPS, 12, last))
    }

    @Test
    fun `warm-ups and drop sets don't hold the cue back`() {
        val last = listOf(
            done(SET_TYPE_WARMUP, 40.0, 5),
            done(SET_TYPE_NORMAL, 60.0, 12),
            done(SET_TYPE_DROP, 40.0, 8),
        )
        assertEquals(HINT_ADD_LOAD, progressionHint(Tracking.WEIGHT_REPS, 12, last))
        assertNull(progressionHint(Tracking.WEIGHT_REPS, 12, listOf(done(SET_TYPE_WARMUP, 40.0, 12))), "warm-ups alone are no work")
    }

    @Test
    fun `an unticked set from last time is no set`() {
        val unticked = done(SET_TYPE_NORMAL, 60.0, 6).copy(completedAt = null)
        assertEquals(HINT_ADD_LOAD, progressionHint(Tracking.WEIGHT_REPS, 12, listOf(done(SET_TYPE_NORMAL, 60.0, 12), unticked)))
    }

    @Test
    fun `no top, no history, or nothing to load says nothing`() {
        val last = listOf(done(SET_TYPE_NORMAL, 60.0, 15))
        assertNull(progressionHint(Tracking.WEIGHT_REPS, null, last))
        assertNull(progressionHint(Tracking.WEIGHT_REPS, 12, emptyList()))
        assertNull(progressionHint(Tracking.REPS_ONLY, 12, listOf(done(SET_TYPE_NORMAL, null, 15))))
        assertNull(progressionHint(Tracking.DURATION, 12, listOf(done(SET_TYPE_NORMAL, null, 15))))
    }

    @Test
    fun `once today starts heavier the cue is done`() {
        val last = listOf(done(SET_TYPE_NORMAL, 60.0, 12), done(SET_TYPE_NORMAL, 60.0, 12))
        assertEquals(HINT_ADD_LOAD, progressionHint(Tracking.WEIGHT_REPS, 12, last, listOf(60.0, 60.0)), "same weight: still to do")
        assertNull(progressionHint(Tracking.WEIGHT_REPS, 12, last, listOf(62.5, 60.0)))
        assertEquals(HINT_ADD_LOAD, progressionHint(Tracking.WEIGHT_REPS, 12, last, listOf(null, null)))
    }

    @Test
    fun `an assisted lift asks for less help`() {
        // 30 of help, stored negative; 12 on every set.
        val last = listOf(done(SET_TYPE_NORMAL, -13.6, 12), done(SET_TYPE_NORMAL, -13.6, 12))
        assertEquals(HINT_LESS_HELP, progressionHint(Tracking.ASSISTED_BODYWEIGHT, 12, last))
        // Starting today with 25 of help (-11.3) is less help already.
        assertNull(progressionHint(Tracking.ASSISTED_BODYWEIGHT, 12, last, listOf(-11.3)))
        assertEquals(HINT_ADD_LOAD, progressionHint(Tracking.WEIGHTED_BODYWEIGHT, 12, listOf(done(SET_TYPE_NORMAL, 10.0, 12))))
    }

    @Test
    fun `no cue in a deload`() {
        val last = listOf(done(SET_TYPE_NORMAL, 60.0, 12), done(SET_TYPE_NORMAL, 60.0, 12))
        assertEquals(HINT_ADD_LOAD, progressionHint(Tracking.WEIGHT_REPS, 12, last))
        assertNull(progressionHint(Tracking.WEIGHT_REPS, 12, last, deload = true))
    }

    @Test
    fun `an assisted lift already done without help has no help to take away`() {
        assertNull(progressionHint(Tracking.ASSISTED_BODYWEIGHT, 12, listOf(done(SET_TYPE_NORMAL, null, 12), done(SET_TYPE_NORMAL, 0.0, 12))))
        // One set of the two without help: the next step isn't less help either.
        assertNull(progressionHint(Tracking.ASSISTED_BODYWEIGHT, 12, listOf(done(SET_TYPE_NORMAL, -9.1, 12), done(SET_TYPE_NORMAL, null, 12))))
    }

    // --- Template list line ---

    @Test
    fun `a template exercise in one line`() {
        assertEquals("3 × 8–12 · RIR 1 · rest 3:00", templateSlotSummary(3, null, 8, 12, RirTarget(1), 180))
        assertEquals("3 × 8–12 · RIR 0–1 · rest 3:00", templateSlotSummary(3, null, 8, 12, RirTarget(0, 1), 180))
        assertEquals("2W + 4 × 6–12 · rest 2:30", templateSlotSummary(4, 2, 6, 12, null, 150))
        assertEquals("0W + 1 set · rest 1:00", templateSlotSummary(1, 0, null, null, null, 60), "no warm-ups, on purpose")
        assertEquals("1 set · rest 1:00", templateSlotSummary(1, null, null, null, null, 60), "last session's")
        assertEquals("3 sets · RIR 2 · rest 3:00", templateSlotSummary(3, null, null, null, RirTarget(2), 180))
        // Its alternatives: the first by name, how many more after it; the rest still last.
        assertEquals("3 × 8–12 · RIR 2 · or Leg Curl · rest 1:30", templateSlotSummary(3, null, 8, 12, RirTarget(2), 90, listOf("Leg Curl")))
        assertEquals("3 sets · or Leg Curl +2 · rest 1:30", templateSlotSummary(3, null, null, null, null, 90, listOf("Leg Curl", "Nordic", "Glute Ham")))
    }

    // --- RIR ranges (DL-9) ---

    @Test
    fun `an RIR target is one number or a range, as the slot stores it`() {
        assertEquals(RirTarget(1, 2), rirTarget(1, 2))
        assertEquals(RirTarget(1), rirTarget(1, null))
        assertNull(rirTarget(null, 2), "a top alone says nothing")
        assertEquals(RirTarget(2), rirTarget(2, 2), "a top equal to the number is the number")
        assertEquals(RirTarget(2), rirTarget(2, 1), "nor one below it")
        assertEquals("1–2", RirTarget(1, 2).text)
        assertEquals("3", RirTarget(3).text)
        assertEquals(2, RirTarget(1, 2).top)
        assertEquals(3, RirTarget(3).top)
    }

    @Test
    fun `the RIR stepper goes through none, 0, 0–1, 1 … 4–5, 5`() {
        val up = generateSequence(stepRirTarget(null, +1)) { stepRirTarget(it, +1).takeIf { next -> next != it } }.toList()
        assertEquals(listOf("0", "0–1", "1", "1–2", "2", "2–3", "3", "3–4", "4", "4–5", "5"), up.map { it.text })
        assertNull(stepRirTarget(null, -1))
        assertNull(stepRirTarget(RirTarget(0), -1), "below 0 clears it")
        assertEquals(RirTarget(0), stepRirTarget(RirTarget(0), -1, allowNone = false), "unless it must have one")
        assertEquals(RirTarget(5), stepRirTarget(RirTarget(5), +1), "5 is the most")
        assertEquals(RirTarget(1), stepRirTarget(RirTarget(1, 2), -1))
        assertEquals(RirTarget(3, 4), stepRirTarget(RirTarget(3), +1))
        // A range the steps don't hold (from a restore) steps as its low end's range would.
        assertEquals(RirTarget(2), stepRirTarget(RirTarget(1, 3), +1))
        assertEquals(RirTarget(1), stepRirTarget(RirTarget(1, 3), -1))
        assertEquals(RirTarget(5), stepRirTarget(RirTarget(7), +1), "a number above the steps comes back to them")
        assertEquals(RirTarget(4, 5), stepRirTarget(RirTarget(7), -1))
    }

    @Test
    fun `a range's first tap on the RIR cell is its top, then down, then up`() {
        var rir: Int? = null
        val taps = List(7) { nextRir(rir, RirTarget(1, 2).top).also { rir = it } }
        assertEquals(listOf(2, 1, 0, 3, 4, 5, null), taps)
    }
}
