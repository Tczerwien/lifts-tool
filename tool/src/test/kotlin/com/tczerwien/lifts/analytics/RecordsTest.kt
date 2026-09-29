package com.tczerwien.lifts.analytics

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.Tracking
import com.tczerwien.lifts.ui.Format
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordsTest {

    private var nextId = 1L

    private fun logged(weightKg: Double?, reps: Int?, at: Long, type: String = "normal", durationSec: Int? = null) = SetEntity(
        id = nextId++, workoutExerciseId = 1, position = 0, type = type,
        weightKg = weightKg, reps = reps, durationSec = durationSec, completedAt = at,
    )

    private fun pending(weightKg: Double?, reps: Int?, type: String = "normal") =
        logged(weightKg, reps, at = 0L, type = type).copy(completedAt = null)

    // --- eligibility ---

    @Test
    fun failedAndReplessSetsNeverSetRecords() {
        val history = listOf(logged(100.0, 5, at = 1))
        assertFalse(isPr(pending(150.0, 0), history), "a failed 0-rep attempt is not a PR")
        assertFalse(isPr(pending(150.0, null), history), "a set with no reps is not a PR")
        // ...and doesn't hide a real later single either.
        val withFail = history + logged(150.0, 0, at = 2)
        assertTrue(isPr(pending(145.0, 1), withFail))
    }

    @Test
    fun noEarlierE1RmMeansNoE1RmPr() {
        // All history is over 12 reps, so no e1RM exists; a lighter set is not a PR.
        val history = listOf(logged(60.0, 20, at = 1))
        assertFalse(isPr(pending(55.0, 5), history))
        assertTrue(isPr(pending(62.5, 5), history), "heavier still counts")
    }

    @Test
    fun unitSwitchRoundingIsNotAPr() {
        // 135 lb logged, then re-entered after a kg round trip as 61.2 kg-ish values.
        val history = listOf(logged(Format.lbToKg(135.0), 8, at = 1))
        assertFalse(isPr(pending(61.235, 8), history))
        assertFalse(isPr(pending(61.3, 8), history))
        assertTrue(isPr(pending(Format.lbToKg(140.0), 8), history))
    }

    @Test
    fun weightlessSetsCompeteOnReps() {
        val history = listOf(logged(null, 8, at = 1), logged(0.0, 10, at = 2))
        assertTrue(isPr(pending(null, 11), history))
        assertFalse(isPr(pending(null, 10), history))
        assertTrue(isPr(pending(null, 5), emptyList()), "first pull-up set counts")
    }

    @Test
    fun warmupsNeverCount() {
        assertFalse(isPr(pending(200.0, 1, type = "warmup"), emptyList()))
        val history = listOf(logged(200.0, 1, at = 1, type = "warmup"))
        assertTrue(isPr(pending(100.0, 5), history), "a heavy warm-up doesn't raise the bar")
    }

    // --- chronological replay ---

    @Test
    fun replayComparesOnlyWithEarlierSets() {
        val a = logged(100.0, 5, at = 1)
        val b = logged(90.0, 5, at = 2)
        val c = logged(105.0, 5, at = 3)
        assertEquals(setOf(a.id, c.id), chronologicalPrSetIds(listOf(c, b, a)))
    }

    @Test
    fun runningMaxMatchesNaiveReplayOnRandomHistories() {
        val random = Random(20260923)
        repeat(500) { round ->
            val sets = List(random.nextInt(1, 25)) { i ->
                val weight = when (random.nextInt(6)) {
                    0 -> null
                    1 -> 0.0
                    else -> 40.0 + random.nextInt(0, 30) * 2.5 + random.nextDouble(-0.1, 0.1)
                }
                val reps = if (random.nextInt(8) == 0) random.nextInt(-1, 1) else random.nextInt(1, 16)
                val type = if (random.nextInt(6) == 0) "warmup" else "normal"
                logged(weight, reps, at = 1000L + i * 10 + random.nextInt(0, 5), type = type)
            }
            val ordered = sets.sortedWith(compareBy({ it.completedAt }, { it.id }))
            val expected = ordered.filterIndexed { i, set -> referenceIsPr(set, ordered.take(i)) }
                .mapTo(HashSet()) { it.id }
            assertEquals(expected, chronologicalPrSetIds(sets), "round $round")
        }
    }

    /**
     * The PR rules written out directly over the earlier sets, sharing no code with RecordTracker,
     * so the engine is checked against the rules rather than against itself.
     */
    private fun referenceIsPr(set: SetEntity, earlier: List<SetEntity>): Boolean {
        val reps = set.reps ?: return false
        if (set.completedAt == null || set.type == "warmup" || reps < 1) return false
        val eligible = earlier.filter { it.completedAt != null && it.type != "warmup" && (it.reps ?: 0) >= 1 }
        val weight = set.weightKg ?: 0.0
        if (weight <= 0.0) {
            val bestReps = eligible.filter { (it.weightKg ?: 0.0) <= 0.0 }.maxOfOrNull { it.reps!! } ?: return true
            return reps > bestReps
        }
        val weighted = eligible.filter { (it.weightKg ?: 0.0) > 0.0 }
        val maxWeight = weighted.maxOfOrNull { it.weightKg!! } ?: return true
        if (weight > maxWeight + 0.12) return true
        if (reps > 12) return false
        val e1Rm = if (reps == 1) weight else weight * (1 + reps / 30.0)
        val bestE1Rm = weighted.filter { it.reps!! <= 12 }
            .maxOfOrNull { if (it.reps == 1) it.weightKg!! else it.weightKg!! * (1 + it.reps!! / 30.0) } ?: return false
        return e1Rm > bestE1Rm + 0.12
    }

    // --- exerciseRecords ---

    @Test
    fun repMaxesCountSetsOfAtLeastNReps() {
        val records = exerciseRecords(listOf(logged(145.0, 10, at = 1), logged(100.0, 5, at = 2)))
        assertNotNull(records)
        val eight = records.repMaxes.single { it.reps == 8 }
        assertEquals(145.0, eight.weightKg, "145x10 proves 145x8")
        assertFalse(eight.estimated)
    }

    @Test
    fun missingRepMaxesAreEstimatedFromBestE1Rm() {
        val records = exerciseRecords(listOf(logged(100.0, 5, at = 1)))!!
        // 100x5 is also 100 for at least one rep: an actual single, not an estimate.
        val single = records.repMaxes.single { it.reps == 1 }
        assertFalse(single.estimated)
        assertEquals(100.0, single.weightKg)
        val five = records.repMaxes.single { it.reps == 5 }
        assertFalse(five.estimated)
        val eight = records.repMaxes.single { it.reps == 8 }
        assertTrue(eight.estimated)
        assertEquals(epleyE1RmKg(100.0, 5)!! / (1 + 8 / 30.0), eight.weightKg!!, 1e-9)
    }

    @Test
    fun weightlessOnlyHistoryHasMaxRepsButNoWeightRecords() {
        val records = exerciseRecords(listOf(logged(null, 8, at = 1), logged(null, 12, at = 2)))!!
        assertNull(records.maxWeightSet)
        assertEquals(12, records.maxRepsSet?.reps)
        assertTrue(records.repMaxes.isEmpty())
    }

    @Test
    fun noEligibleSetsMeansNoRecords() {
        assertNull(exerciseRecords(listOf(logged(100.0, 0, at = 1), logged(80.0, 5, at = 2, type = "warmup"))))
    }

    // --- by tracking type ---

    @Test
    fun repsOnlyCompetesOnRepsAndIgnoresWeight() {
        val reps = Tracking.REPS_ONLY
        val history = listOf(logged(null, 10, at = 1), logged(20.0, 8, at = 2))
        assertTrue(isPr(pending(null, 11), history, reps))
        assertFalse(isPr(pending(null, 10), history, reps))
        assertFalse(isPr(pending(40.0, 9), history, reps), "a weight on a reps-only set is no record")
        assertEquals(12, exerciseRecords(history + logged(null, 12, at = 3), tracking = reps)?.maxRepsSet?.reps)
    }

    @Test
    fun holdsCompeteOnSeconds() {
        val hold = Tracking.DURATION
        val history = listOf(logged(null, null, at = 1, durationSec = 45), logged(null, null, at = 2, durationSec = 60))
        assertTrue(isPr(pending(null, null).copy(durationSec = 61), history, hold))
        assertFalse(isPr(pending(null, null).copy(durationSec = 60), history, hold))
        assertFalse(isPr(pending(null, 30), history, hold), "reps on a hold measure nothing")
        assertFalse(recordEligible(logged(null, null, at = 3, durationSec = 0), hold), "0 s is nothing held")
        val records = exerciseRecords(history, tracking = hold)!!
        assertEquals(60, records.longestSet?.durationSec)
        assertNull(records.maxWeightSet)
        assertTrue(records.repMaxes.isEmpty())
    }

    @Test
    fun weightedBodyweightRanksAddedWeightThenReps() {
        val added = Tracking.WEIGHTED_BODYWEIGHT
        val history = listOf(logged(9.0, 8, at = 1))
        assertTrue(isPr(pending(11.3, 5), history, added), "more on the belt, even for fewer reps")
        val after = history + logged(11.3, 5, at = 2)
        assertFalse(isPr(pending(9.0, 9), after, added), "more reps with less on the belt is no record")
        assertTrue(isPr(pending(11.3, 6), after, added), "the same load for one more rep is")
        assertFalse(isPr(pending(11.3, 5), after, added))
        // Bodyweight sets keep their own reps record beside the weighted one.
        assertTrue(isPr(pending(null, 12), after, added))
        val withBodyweight = after + logged(null, 12, at = 3)
        assertFalse(isPr(pending(0.0, 12), withBodyweight, added))
        assertTrue(isPr(pending(null, 13), withBodyweight, added))
        val records = exerciseRecords(withBodyweight + logged(11.3, 7, at = 4), tracking = added)!!
        assertEquals(7, records.maxWeightSet?.reps, "the heaviest load, and the most reps at it")
        assertEquals(12, records.maxRepsSet?.reps)
        assertNull(records.bestE1RmKg, "no e1RM of a belt")
    }

    @Test
    fun assistedRanksLessHelpThenReps() {
        val assisted = Tracking.ASSISTED_BODYWEIGHT
        val history = listOf(logged(-18.0, 8, at = 1))
        assertTrue(isPr(pending(-13.5, 6), history, assisted), "less assistance")
        assertFalse(isPr(pending(-22.5, 12), history, assisted), "more assistance is easier, whatever the reps")
        assertTrue(isPr(pending(-18.0, 9), history, assisted), "the same help for one more rep")
        assertFalse(isPr(pending(-18.05, 8), history, assisted), "within the rounding tolerance is the same help")
        assertTrue(isPr(pending(null, 3), history, assisted), "the first unassisted set beats any help")
        val records = exerciseRecords(history + logged(-13.5, 6, at = 2) + logged(-22.5, 12, at = 3), tracking = assisted)!!
        assertEquals(-13.5, records.maxWeightSet?.weightKg)
        assertNull(records.maxRepsSet)
    }

    @Test
    fun trackingPerExerciseInTheMultiExerciseReplay() {
        val bench = listOf(logged(100.0, 5, at = 1), logged(90.0, 13, at = 2))
        val pullups = listOf(logged(-20.0, 8, at = 1), logged(-15.0, 5, at = 2))
        val ids = chronologicalPrSetIds(
            mapOf("bench" to bench, "pullup" to pullups),
            mapOf("pullup" to Tracking.ASSISTED_BODYWEIGHT),
        )
        // Bench as weight x reps: 90x13 has no e1RM (none past 12 reps), so only the first. Pull-ups by help.
        assertEquals(setOf(bench[0].id, pullups[0].id, pullups[1].id), ids)
    }

    @Test
    fun loadRecordsMatchTheirRulesOnRandomHistories() {
        val random = Random(20260929)
        for (tracking in listOf(Tracking.WEIGHTED_BODYWEIGHT, Tracking.ASSISTED_BODYWEIGHT)) {
            repeat(400) { round ->
                val sets = List(random.nextInt(1, 20)) { i ->
                    val weight = when (random.nextInt(5)) {
                        0 -> null
                        1 -> 0.0
                        else -> (random.nextInt(-8, 9) * 2.5) + random.nextDouble(-0.1, 0.1)
                    }
                    val reps = if (random.nextInt(8) == 0) 0 else random.nextInt(1, 16)
                    val type = if (random.nextInt(6) == 0) "warmup" else "normal"
                    logged(weight, reps, at = 1000L + i * 10 + random.nextInt(0, 5), type = type)
                }
                val ordered = sets.sortedWith(compareBy({ it.completedAt }, { it.id }))
                val expected = ordered.filterIndexed { i, set -> referenceLoadPr(set, ordered.take(i), tracking) }
                    .mapTo(HashSet()) { it.id }
                assertEquals(expected, chronologicalPrSetIds(sets, tracking), "$tracking round $round")
            }
        }
    }

    /**
     * The bodyweight kinds' rules written out over the earlier sets: the best load so far (added
     * weight, or signed help; weighted bodyweight splits off its sets without added weight, which go
     * by reps), then the most reps at that load, both within the tolerance.
     */
    private fun referenceLoadPr(set: SetEntity, earlier: List<SetEntity>, tracking: Tracking): Boolean {
        val reps = set.reps ?: return false
        if (set.type == "warmup" || reps < 1) return false
        val eligible = earlier.filter { it.type != "warmup" && (it.reps ?: 0) >= 1 }
        val split = tracking == Tracking.WEIGHTED_BODYWEIGHT
        val weighted = { s: SetEntity -> (s.weightKg ?: 0.0) > 0.0 }
        if (split && !weighted(set)) {
            val best = eligible.filterNot(weighted).maxOfOrNull { it.reps!! } ?: return true
            return reps > best
        }
        val track = if (split) eligible.filter(weighted) else eligible
        if (track.isEmpty()) return true
        // Replay the best load and the reps at it, as a lifter would read the log.
        var best = track.first().weightKg ?: 0.0
        var repsAtBest = track.first().reps!!
        for (s in track.drop(1)) {
            val load = s.weightKg ?: 0.0
            if (load > best + 0.12) {
                best = load
                repsAtBest = s.reps!!
            } else if (load >= best - 0.12) {
                repsAtBest = maxOf(repsAtBest, s.reps!!)
            }
        }
        val load = set.weightKg ?: 0.0
        return load > best + 0.12 || (load >= best - 0.12 && reps > repsAtBest)
    }
}
