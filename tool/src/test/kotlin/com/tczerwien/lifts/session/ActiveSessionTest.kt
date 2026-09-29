package com.tczerwien.lifts.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.tczerwien.lifts.analytics.WorkoutBodyweight
import com.tczerwien.lifts.data.BodyweightEntity
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import com.tczerwien.lifts.session.ActiveSession.FinishOutcome
import com.tczerwien.lifts.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The logging engine against an in-memory store and a real, file-backed DataStore (the same
 * Preferences file format the SDK uses), so the saved rest timer is tested through its real
 * read/write path.
 */
class ActiveSessionTest {

    private val w = SET_TYPE_WARMUP
    private val n = SET_TYPE_NORMAL

    private val dir: File = Files.createTempDirectory("lifts-session").toFile()
    private val scopes = mutableListOf<CoroutineScope>()
    private val store = InMemoryLiftsStore()
    private val alarm = RestAlarm()
    private var dataStore = openDataStore()
    private var session = newSession()

    @AfterTest
    fun cleanUp() {
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
    }

    private fun openDataStore(): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return PreferenceDataStoreFactory.create(scope = scope) { File(dir, "lifts.preferences_pb") }
    }

    private fun newSession() = ActiveSession(store, SettingsStore(dataStore), dataStore, alarm)

    // --- Starting a workout ---

    @Test
    fun `a template starts each slot with last session's warm-ups, then its target sets, all empty`() {
        store.addFinishedWorkout(
            startedAt = 1_000_000,
            "rdl" to listOf(Triple(w, 43.0, 8), Triple(w, 61.0, 5), Triple(n, 84.0, 8), Triple(n, 84.0, 8), Triple(n, 84.0, 8)),
        )
        val template = store.addTemplate("Lower A", "rdl" to 3)
        val workoutId = session.startFromTemplate(template, now = 2_000_000)
        val rows = store.setsForWorkout(workoutId)
        assertEquals(listOf(w, w, n, n, n), rows.map { it.type })
        assertTrue(rows.all { it.weightKg == null && it.reps == null && it.completedAt == null })
    }

    @Test
    fun `a template with Bench twice gives each slot its own group`() {
        store.addFinishedWorkout(
            startedAt = 1_000_000,
            "bench" to listOf(Triple(w, 40.0, 10), Triple(n, 85.0, 5)),
            "bench" to listOf(Triple(n, 70.0, 8), Triple(n, 70.0, 8), Triple(n, 70.0, 8)),
        )
        val template = store.addTemplate("Upper A", "bench" to 1, "bench" to 3)
        val workoutId = session.startFromTemplate(template, now = 2_000_000)
        val (top, backOff) = store.exercisesForWorkout(workoutId)
        assertEquals(listOf(w, n), rowsOf(top).map { it.type })
        assertEquals(listOf(n, n, n), rowsOf(backOff).map { it.type })
    }

    @Test
    fun `a second Bench added by hand mirrors last week's second slot`() {
        store.addFinishedWorkout(
            startedAt = 1_000_000,
            "bench" to List(3) { Triple(n, 85.0, 5) },
            "bench" to List(2) { Triple(n, 70.0, 10) },
        )
        val workoutId = session.startEmpty(now = 2_000_000)
        val first = session.addExercise(workoutId, "bench")!!
        val second = session.addExercise(workoutId, "bench")!!
        val third = session.addExercise(workoutId, "bench")!!
        assertEquals(3, store.setsForWorkout(workoutId).count { it.workoutExerciseId == first })
        assertEquals(2, store.setsForWorkout(workoutId).count { it.workoutExerciseId == second })
        assertEquals(DEFAULT_NEW_EXERCISE_SETS, store.setsForWorkout(workoutId).count { it.workoutExerciseId == third })
    }

    @Test
    fun `a Bench slot skipped last week doesn't shift the next one onto it`() {
        val lastWeek = store.addFinishedWorkout(
            startedAt = 1_000_000,
            "bench" to List(3) { Triple(n, 85.0, 5) },
            "bench" to List(2) { Triple(n, 70.0, 10) },
        )
        store.skipSlot(lastWeek, position = 0)
        val workoutId = session.startEmpty(now = 2_000_000)
        val top = session.addExercise(workoutId, "bench")!!
        val backOff = session.addExercise(workoutId, "bench")!!
        // The skipped top slot has nothing to mirror; the back-off slot still mirrors its own two sets.
        assertEquals(DEFAULT_NEW_EXERCISE_SETS, store.setsForWorkout(workoutId).count { it.workoutExerciseId == top })
        assertEquals(2, store.setsForWorkout(workoutId).count { it.workoutExerciseId == backOff })
    }

    @Test
    fun `backing out of a template start with warm-ups discards it, but not once a set is ticked`() {
        store.addFinishedWorkout(1_000_000, "rdl" to listOf(Triple(w, 43.0, 8), Triple(n, 84.0, 8)))
        val template = store.addTemplate("Lower A", "rdl" to 2)

        val untouched = session.startFromTemplate(template, now = 2_000_000)
        assertTrue(session.discardIfUntouched(untouched))
        assertNull(store.workoutById(untouched))

        val used = session.startFromTemplate(template, now = 3_000_000)
        val warmup = store.setsForWorkout(used).first()
        session.completeSet(used, warmup, weightKg = 43.0, reps = 8, type = w, now = 3_060_000)
        assertFalse(session.discardIfUntouched(used))
        assertNotNull(store.workoutById(used))
    }

    // --- Logging ---

    @Test
    fun `a warm-up rests for the slot's warm-up override, and the rest survives a restart`() {
        val workoutId = session.startEmpty(now = 1_000)
        val slotId = store.addWorkoutExercise(
            WorkoutExerciseEntity(workoutId = workoutId, exerciseId = "bench", position = 0, restSecWarmup = 45),
        )
        val setId = store.addSet(SetEntity(workoutExerciseId = slotId, position = 0, type = w))
        val completion = session.completeSet(workoutId, store.setById(setId)!!, 40.0, 10, w, now = 10_000)!!
        assertEquals(45, completion.restDurationSec)
        assertEquals(55_000L, completion.restEndMillis)

        // The process dies: a new DataStore and a new session read the same saved end.
        scopes.forEach { it.cancel() }
        dataStore = openDataStore()
        session = newSession()
        assertEquals(RestTimerState(endMillis = 55_000, totalSec = 45), session.restStateSnapshot())
    }

    @Test
    fun `a slot that rests 0 s ends the running rest instead of starting one`() {
        val workoutId = session.startEmpty(now = 1_000)
        val row = store.addWorkoutExercise(WorkoutExerciseEntity(workoutId = workoutId, exerciseId = "row", position = 0))
        val bench = store.addWorkoutExercise(
            WorkoutExerciseEntity(workoutId = workoutId, exerciseId = "bench", position = 1, restSecWork = 0),
        )
        val rowSet = store.addSet(SetEntity(workoutExerciseId = row, position = 0, type = n))
        val benchSet = store.addSet(SetEntity(workoutExerciseId = bench, position = 0, type = n))
        session.completeSet(workoutId, store.setById(rowSet)!!, 60.0, 8, n, now = 10_000)
        assertNotNull(session.restStateSnapshot())

        val completion = session.completeSet(workoutId, store.setById(benchSet)!!, 80.0, 5, n, now = 60_000)!!
        assertNull(completion.restEndMillis)
        assertNull(session.restStateSnapshot())
    }

    @Test
    fun `a rest counts as seen as it is saved, so power pressed right after the tick still flashes later`() {
        val workoutId = session.startEmpty(now = 1_000)
        val slotId = store.addWorkoutExercise(WorkoutExerciseEntity(workoutId = workoutId, exerciseId = "bench", position = 0))
        val setId = store.addSet(SetEntity(workoutExerciseId = slotId, position = 0, type = n))
        val end = session.completeSet(workoutId, store.setById(setId)!!, 60.0, 5, n, now = 10_000)!!.restEndMillis!!
        // No frame ever looked at it: the screen went dark straight after the tick.
        assertEquals(RestAlert.MISSED, alarm.check(end, end + 60_000))
    }

    @Test
    fun `a moved rest is armed at its new end, and a minus 30 past zero stays quiet`() {
        kotlinx.coroutines.runBlocking {
            session.startRest(totalSec = 60, now = 0)
            val later = session.adjustRest(30, now = 1_000)!!
            assertEquals(RestAlert.MISSED, alarm.check(later, later + 60_000))
            session.startRest(totalSec = 60, now = 200_000)
            val clamped = session.adjustRest(-90, now = 210_000)!!
            assertEquals(RestAlert.NONE, alarm.check(clamped, clamped + 60_000))
        }
    }

    @Test
    fun `the exercise on screen is remembered across a restart, for its own workout only`() {
        kotlinx.coroutines.runBlocking { session.rememberCurrentSlot(workoutId = 7, slotId = 42) }
        scopes.forEach { it.cancel() }
        dataStore = openDataStore()
        session = newSession()
        assertEquals(42L, session.currentSlotSnapshot(workoutId = 7))
        assertNull(session.currentSlotSnapshot(workoutId = 8))
    }

    @Test
    fun `starting a new workout clears a rest left running`() {
        val first = session.startEmpty(now = 1_000)
        val slotId = store.addWorkoutExercise(WorkoutExerciseEntity(workoutId = first, exerciseId = "bench", position = 0))
        val setId = store.addSet(SetEntity(workoutExerciseId = slotId, position = 0, type = n))
        session.completeSet(first, store.setById(setId)!!, 60.0, 5, n, now = 2_000)
        assertNotNull(session.restStateSnapshot())
        session.startEmpty(now = 3_000)
        assertNull(session.restStateSnapshot())
    }

    @Test
    fun `a save that lands after a removal keeps the renumbered position`() {
        val workoutId = session.startEmpty(now = 1_000)
        val slotId = session.addExercise(workoutId, "bench")!!
        val (first, _, third) = store.setsForWorkout(workoutId)
        session.removeSet(workoutId, first.id)
        // The screen still holds the third row at its old position 2 when a stepper tap saves it.
        session.updateSet(workoutId, third.copy(weightKg = 50.0))
        val rows = store.setsForWorkout(workoutId).filter { it.workoutExerciseId == slotId }
        assertEquals(listOf(0, 1), rows.map { it.position })
        assertEquals(50.0, rows.last().weightKg)
    }

    // --- Finishing ---

    @Test
    fun `finishing twice keeps the first end and clears the rest`() {
        val workoutId = session.startEmpty(now = 1_000)
        val slotId = store.addWorkoutExercise(WorkoutExerciseEntity(workoutId = workoutId, exerciseId = "bench", position = 0))
        val setId = store.addSet(SetEntity(workoutExerciseId = slotId, position = 0, type = n))
        session.completeSet(workoutId, store.setById(setId)!!, 60.0, 5, n, now = 60_000)

        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(workoutId, now = 120_000))
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(workoutId, now = 999_000))
        assertEquals(120_000L, store.workoutById(workoutId)?.endedAt)
        assertNull(session.restStateSnapshot())
    }

    @Test
    fun `finishing with nothing ticked discards the workout`() {
        val workoutId = session.startEmpty(now = 1_000)
        session.addExercise(workoutId, "bench")!!
        assertEquals(FinishOutcome.DISCARDED, session.finishOrDiscard(workoutId, now = 60_000))
        assertNull(store.workoutById(workoutId))
        assertTrue(store.sets.isEmpty() && store.slots.isEmpty())
    }

    @Test
    fun `the summary counts ticked sets only, keeps warm-ups out of volume, and stars a real PR once`() {
        store.addFinishedWorkout(1_000_000, "bench" to listOf(Triple(n, 100.0, 5)))
        store.exercises["bench"] = com.tczerwien.lifts.data.ExerciseEntity(
            id = "bench", name = "Bench Press", equipment = null, primaryMuscles = emptyList(),
            secondaryMuscles = emptyList(), category = "strength", level = null, instructions = emptyList(),
        )
        val workoutId = session.startEmpty(now = 2_000_000)
        val slotId = session.addExercise(workoutId, "bench")!!
        val first = store.setsForWorkout(workoutId).single()
        val secondId = session.addSet(workoutId, slotId)!!
        val warmupId = store.addSet(SetEntity(workoutExerciseId = slotId, position = 2, type = w))
        // Values typed in but never ticked.
        store.addSet(SetEntity(workoutExerciseId = slotId, position = 3, type = n, weightKg = 140.0, reps = 5))
        session.completeSet(workoutId, store.setById(warmupId)!!, 60.0, 8, w, now = 2_060_000)
        session.completeSet(workoutId, first, 102.5, 5, n, now = 2_120_000)
        session.completeSet(workoutId, store.setById(secondId)!!, 102.5, 5, n, now = 2_180_000)
        session.finishOrDiscard(workoutId, now = 2_240_000)

        val summary = session.summarize(workoutId)!!
        assertEquals(3, summary.completedSetCount, "the warm-up counts as a set; the untouched 140 row doesn't")
        assertEquals(1_025.0, summary.totalVolumeKg, 1e-9)
        assertEquals(listOf(PrRecord("bench", "Bench Press", 102.5, 5)), summary.prs)
        assertEquals(240, summary.durationSec)
        assertNull(summary.bodyweight, "no weigh-in near the workout")
    }

    @Test
    fun `the summary's records follow how each exercise is measured`() {
        store.addFinishedWorkout(1_000_000, "pullup" to listOf(Triple(n, -20.0, 8)))
        store.exercises["pullup"] = com.tczerwien.lifts.data.ExerciseEntity(
            id = "pullup", name = "Assisted Pull-Up", equipment = "machine", primaryMuscles = emptyList(),
            secondaryMuscles = emptyList(), category = "strength", level = null, instructions = emptyList(),
            trackingType = com.tczerwien.lifts.data.Tracking.ASSISTED_BODYWEIGHT.stored,
        )
        store.exercises["plank"] = store.exercises.getValue("pullup").copy(
            id = "plank", name = "Plank", trackingType = com.tczerwien.lifts.data.Tracking.DURATION.stored,
        )
        val workoutId = session.startEmpty(now = 2_000_000)
        session.addExercise(workoutId, "pullup")!!
        val row = store.setsForWorkout(workoutId).first()
        // Less help than last time: a record, though the stored weight is "less" than 0.
        session.completeSet(workoutId, row, -15.0, 6, n, now = 2_060_000)
        val plankSlot = session.addExercise(workoutId, "plank")!!
        val hold = store.setsForWorkout(workoutId).first { it.workoutExerciseId == plankSlot }
        store.updateSetValues(hold.copy(durationSec = 60, completedAt = 2_100_000))
        session.finishOrDiscard(workoutId, now = 2_120_000)

        val summary = session.summarize(workoutId)!!
        assertEquals(
            listOf(PrRecord("pullup", "Assisted Pull-Up", -15.0, 6), PrRecord("plank", "Plank", null, null, 60)),
            summary.prs,
        )
        assertEquals(0.0, summary.totalVolumeKg, 1e-9, "assistance is no volume")
    }

    @Test
    fun `the summary carries that day's weigh-in, looked up rather than stored`() {
        val workoutId = session.startEmpty(now = 1_000)
        val slotId = session.addExercise(workoutId, "bench")!!
        session.completeSet(workoutId, store.setsForWorkout(workoutId).first { it.workoutExerciseId == slotId }, 100.0, 5, n, now = 60_000)
        session.finishOrDiscard(workoutId, now = 120_000)
        // Logged after the workout, on its day (epoch day 0 in UTC); the day before is older.
        store.bodyweight += BodyweightEntity(id = 1, epochDay = -1, weightKg = 81.0)
        store.bodyweight += BodyweightEntity(id = 2, epochDay = 0, weightKg = 80.5)

        assertEquals(WorkoutBodyweight(80.5, 0, sameDay = true), session.summarize(workoutId, ZoneOffset.UTC)!!.bodyweight)
    }

    @Test
    fun `nothing is added to a workout closed or deleted while the picker was open`() {
        // Closed as stale (it had a tick) while he picked an exercise: no rows join a finished workout.
        val closed = session.startEmpty(now = 1_000)
        val slotId = session.addExercise(closed, "bench")!!
        session.completeSet(closed, store.setsForWorkout(closed).first { it.workoutExerciseId == slotId }, 100.0, 5, n, now = 60_000)
        assertTrue(session.closeIfStale(closed, now = 60_000 + 5 * 3_600_000L))
        val before = store.setsForWorkout(closed).size to store.exercisesForWorkout(closed).size
        assertNull(session.addExercise(closed, "squat"))
        assertNull(session.addSet(closed, slotId))
        assertEquals(before, store.setsForWorkout(closed).size to store.exercisesForWorkout(closed).size)

        // Deleted as stale (nothing ticked): no orphan rows (on the phone, a foreign-key crash).
        val deleted = session.startEmpty(now = 20_000_000)
        assertTrue(session.closeIfStale(deleted, now = 20_000_000 + 5 * 3_600_000L))
        assertNull(store.workoutById(deleted))
        assertNull(session.addExercise(deleted, "bench"))
        assertTrue(store.slots.values.none { it.workoutId == deleted })

        // A slot removed by a queued "…" action takes no new set.
        val open = session.startEmpty(now = 40_000_000)
        val gone = session.addExercise(open, "bench")!!
        session.removeExercise(open, gone)
        assertNull(session.addSet(open, gone))
    }

    @Test
    fun `a tick, untick or value queued behind a stale close changes nothing in the finished workout`() {
        val workoutId = session.startEmpty(now = 1_000)
        val slotId = session.addExercise(workoutId, "bench")!!
        val (first, second) = store.setsForWorkout(workoutId).filter { it.workoutExerciseId == slotId }
        // A second exercise with nothing ticked: while open, the menu could remove it.
        val untouched = session.addExercise(workoutId, "squat")!!
        session.completeSet(workoutId, first, 100.0, 5, n, now = 60_000)
        assertTrue(session.closeIfStale(workoutId, now = 60_000 + 5 * 3_600_000L))
        val logged = store.setsForWorkout(workoutId)
        val restBefore = session.restStateSnapshot()

        // The screen still showed the old rows when these taps were made.
        assertNull(session.completeSet(workoutId, second, 100.0, 5, n, now = 20_000_000))
        assertFalse(session.uncompleteSet(workoutId, store.setById(first.id)!!))
        assertFalse(session.updateSet(workoutId, second.copy(weightKg = 120.0)))
        assertEquals(logged, store.setsForWorkout(workoutId))
        assertEquals(restBefore, session.restStateSnapshot())

        // Nor do the menu's removals: RESUME brings the finished workout back with its unticked rows.
        assertFalse(session.removeSet(workoutId, second.id))
        assertFalse(session.removeExercise(workoutId, untouched))
        assertEquals(logged, store.setsForWorkout(workoutId))
    }

    // --- Home's cleanup ---

    @Test
    fun `Home deletes an untouched workout and closes a stale one at its last set`() {
        val stale = session.startEmpty(now = 0)
        val slotId = store.addWorkoutExercise(WorkoutExerciseEntity(workoutId = stale, exerciseId = "bench", position = 0))
        val setId = store.addSet(SetEntity(workoutExerciseId = slotId, position = 0, type = n))
        session.completeSet(stale, store.setById(setId)!!, 60.0, 5, n, now = 600_000)
        val untouched = session.startEmpty(now = 20_000_000)

        assertEquals(ActiveSession.Tidied(open = null, closed = 1), session.tidyOpenWorkouts(now = 20_060_000))
        assertNull(store.workoutById(untouched))
        assertEquals(600_000L, store.workoutById(stale)?.endedAt)
    }

    private fun rowsOf(slot: WorkoutExerciseEntity): List<SetEntity> =
        store.setsForWorkout(slot.workoutId).filter { it.workoutExerciseId == slot.id }
}
