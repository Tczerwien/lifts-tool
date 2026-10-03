package com.tczerwien.lifts.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    init {
        // The exercises the tests pick, as the catalog holds them (weight × reps): a pick of one the
        // log doesn't have is refused. A test that needs a type or a custom exercise puts its own over these.
        for (id in listOf("bench", "squat", "fly", "curl", "row", "pullup", "plank")) {
            store.exercises[id] = com.tczerwien.lifts.data.ExerciseEntity(
                id = id, name = id, equipment = null, primaryMuscles = emptyList(), secondaryMuscles = emptyList(),
                category = "strength", level = null, instructions = emptyList(),
            )
        }
    }

    // --- Starting a workout ---

    @Test
    fun `an empty workout is named by the time of day in the phone's zone`() {
        val utc = ActiveSession(store, SettingsStore(dataStore), dataStore, alarm, ZoneOffset.UTC)
        assertEquals("Morning Workout", store.workoutById(utc.startEmpty(now = 7 * 3_600_000L))!!.name)
        assertEquals("Evening Workout", store.workoutById(utc.startEmpty(now = 18 * 3_600_000L))!!.name)
        // A template keeps its own name.
        val template = store.addTemplate("Upper A", "bench" to 3)
        assertEquals("Upper A", store.workoutById(utc.startFromTemplate(template, now = 18 * 3_600_000L))!!.name)
    }

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
    fun `a template's warm-ups, rep range and RIR reach the workout's slot`() {
        store.addFinishedWorkout(startedAt = 1_000_000, "incline" to listOf(Triple(w, 20.0, 10), Triple(n, 30.0, 12)))
        val template = store.addTemplate("Upper A", "incline" to 3)
        val row = store.exercisesForTemplate(template).single()
        store.templateExercises[row.id] = row.copy(warmupSets = 2, repMin = 6, repMax = 12, targetRir = 1, restSecWork = 150)
        val workoutId = session.startFromTemplate(template, now = 2_000_000)
        val slot = store.exercisesForWorkout(workoutId).single()
        assertEquals(listOf(6, 12, 1, 150), listOf(slot.targetRepMin, slot.targetRepMax, slot.targetRir, slot.restSecWork))
        assertEquals(listOf(w, w, n, n, n), store.setsForWorkout(workoutId).map { it.type }, "two warm-ups, not last time's one")
        // Exactly as started: backing out leaves nothing.
        assertTrue(session.discardIfUntouched(workoutId))
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
            val later = session.adjustRest(30, clock = { 1_000 })!!
            assertEquals(RestAlert.MISSED, alarm.check(later, later + 60_000))
            session.startRest(totalSec = 60, now = 200_000)
            val clamped = session.adjustRest(-90, clock = { 210_000 })!!
            assertEquals(RestAlert.NONE, alarm.check(clamped, clamped + 60_000))
        }
    }

    @Test
    fun `the exercise on screen is remembered across a restart, for its own workout only`() {
        kotlinx.coroutines.runBlocking { session.rememberCurrentSlot(workoutId = 7, slotId = 42) }
        // Wait for the old DataStore to close: cancel() alone returns first, and opening the same file
        // while it is still active throws ("multiple DataStores active"), now and then.
        kotlinx.coroutines.runBlocking { scopes.forEach { it.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() } }
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
    fun `a tick logs a hold's seconds and keeps the row's RIR`() {
        val workoutId = session.startEmpty(now = 1_000)
        session.addExercise(workoutId, "plank")!!
        val row = store.setsForWorkout(workoutId).first()
        store.updateSetValues(row.copy(rpe = 8.0))
        val logged = session.completeSet(workoutId, store.setById(row.id)!!, null, null, n, now = 60_000, durationSec = 45)!!
        assertEquals(45, logged.set.durationSec)
        val stored = store.setById(row.id)!!
        assertEquals(45, stored.durationSec)
        assertEquals(8.0, stored.rpe)
        assertNull(stored.reps)
        assertEquals(60_000L, stored.completedAt)
    }

    // --- The "…" menu's edits ---

    @Test
    fun `the menu renames the workout and notes it and its exercises`() {
        val workoutId = session.startEmpty(now = 1_000)
        val slotId = session.addExercise(workoutId, "bench")!!
        assertTrue(session.renameWorkout(workoutId, "  Upper   A "))
        assertEquals("Upper A", store.workoutById(workoutId)!!.name)
        assertFalse(session.renameWorkout(workoutId, "   "), "blank keeps the name")
        assertFalse(session.renameWorkout(workoutId, "Upper A"), "the same name is no change")
        assertEquals("Upper A", store.workoutById(workoutId)!!.name)

        assertTrue(session.setWorkoutNotes(workoutId, " Slept badly \n"))
        assertEquals("Slept badly", store.workoutById(workoutId)!!.notes)
        assertTrue(session.setWorkoutNotes(workoutId, "  "))
        assertNull(store.workoutById(workoutId)!!.notes, "blank clears it")

        assertTrue(session.setSlotNotes(workoutId, slotId, "left shoulder twinge on set 3 "))
        assertEquals("left shoulder twinge on set 3", store.slots.getValue(slotId).notes)
        assertFalse(session.setSlotNotes(workoutId, slotId + 99, "no such slot"))
    }

    @Test
    fun `a workout he renamed or gave a rest is kept when he backs out before a tick`() {
        val utc = ActiveSession(store, SettingsStore(dataStore), dataStore, alarm, ZoneOffset.UTC)
        val renamed = utc.startEmpty(now = 18 * 3_600_000L)
        assertTrue(utc.renameWorkout(renamed, "Legs - deload"))
        assertFalse(utc.discardIfUntouched(renamed))
        assertNotNull(store.workoutById(renamed))

        val template = store.addTemplate("Lower A", "squat" to 3)
        val rested = utc.startFromTemplate(template, now = 19 * 3_600_000L)
        val slot = store.exercisesForWorkout(rested).single()
        assertTrue(utc.setSlotRest(rested, slot.id, 240))
        assertFalse(utc.discardIfUntouched(rested))

        // As started, it goes.
        val untouched = utc.startFromTemplate(template, now = 20 * 3_600_000L)
        assertTrue(utc.discardIfUntouched(untouched))
        val empty = utc.startEmpty(now = 21 * 3_600_000L)
        assertTrue(utc.discardIfUntouched(empty))
    }

    // --- Start again and Save as template (Workout Detail) ---

    /**
     * A finished Tuesday-evening workout: Incline with one warm-up and three work sets (one to failure,
     * one left unticked), 6–12 at RIR 1 and a 150 s rest; Curl done; Fly skipped; notes on it all.
     */
    private fun finishedEvening(): Long {
        val workoutId = store.addFinishedWorkout(
            startedAt = 18 * 3_600_000L,
            "incline" to listOf(Triple(w, 20.0, 10), Triple(n, 30.0, 12), Triple(SET_TYPE_FAILURE, 30.0, 11)),
            "fly" to listOf(Triple(n, 10.0, 15)),
            "curl" to listOf(Triple(n, 12.0, 10)),
        )
        store.updateWorkout(store.workoutById(workoutId)!!.copy(name = "Evening Workout", notes = "tired"))
        val (incline, fly, _) = store.exercisesForWorkout(workoutId)
        store.slots[incline.id] = incline.copy(
            restSecWork = 150, restSecWarmup = 60, targetRepMin = 6, targetRepMax = 12, targetRir = 1, notes = "elbow ok",
        )
        store.addSet(SetEntity(workoutExerciseId = incline.id, position = 3, type = n, weightKg = 30.0))
        store.skipSlot(workoutId, fly.position)
        return workoutId
    }

    @Test
    fun `start again copies each done exercise's ticked pattern, rests and targets, not notes or skipped ones`() {
        val utc = ActiveSession(store, SettingsStore(dataStore), dataStore, alarm, ZoneOffset.UTC)
        val source = finishedEvening()
        val again = assertNotNull(utc.startFromWorkout(source, now = 3 * 24 * 3_600_000L + 7 * 3_600_000L))
        val workout = store.workoutById(again)!!
        assertEquals("Morning Workout", workout.name, "a time-of-day name follows the new start")
        assertNull(workout.notes)
        assertNull(workout.endedAt)
        val slots = store.exercisesForWorkout(again)
        assertEquals(listOf("incline", "curl"), slots.map { it.exerciseId }, "the skipped Fly is left out")
        val incline = slots.first()
        assertEquals(listOf(150, 60, 6, 12, 1), listOf(incline.restSecWork, incline.restSecWarmup, incline.targetRepMin, incline.targetRepMax, incline.targetRir))
        assertNull(incline.notes, "his note stays with the old workout")
        val rows = store.setsForWorkout(again)
        assertEquals(listOf(w, n, n), rows.filter { it.workoutExerciseId == incline.id }.map { it.type }, "warm-up kept, failure as a work set, the unticked set left out")
        assertEquals(listOf(n), rows.filter { it.workoutExerciseId == slots[1].id }.map { it.type })
        assertTrue(rows.all { it.weightKg == null && it.reps == null && it.completedAt == null })
        // Nothing done yet: backing out leaves nothing.
        assertTrue(utc.discardIfUntouched(again))
        assertNull(store.workoutById(again))
    }

    @Test
    fun `a workout started again keeps a name he gave the old one, and counts a tick as a change`() {
        val source = finishedEvening()
        store.updateWorkout(store.workoutById(source)!!.copy(name = "Upper A"))
        val again = assertNotNull(session.startFromWorkout(source, now = 5 * 24 * 3_600_000L))
        assertEquals("Upper A", store.workoutById(again)!!.name)
        val first = store.setsForWorkout(again).first()
        assertNotNull(session.completeSet(again, first, 20.0, 10, w, now = 5 * 24 * 3_600_000L + 1))
        assertFalse(session.discardIfUntouched(again))
    }

    @Test
    fun `an exercise left after its warm-ups is in neither a start again nor a template`() {
        val source = store.addFinishedWorkout(
            startedAt = 1_000_000,
            "bench" to listOf(Triple(w, 40.0, 10), Triple(n, 80.0, 5)),
            "row" to listOf(Triple(w, 30.0, 10)),
        )
        val again = assertNotNull(session.startFromWorkout(source, now = 2_000_000))
        assertEquals(listOf("bench"), store.exercisesForWorkout(again).map { it.exerciseId })
        session.discardWorkout(again)
        val template = assertNotNull(session.saveAsTemplate(source, "Push"))
        assertEquals(listOf("bench" to 1), store.exercisesForTemplate(template).map { it.exerciseId to it.targetSets })
    }

    @Test
    fun `Workout Complete names an exercise whose row is missing as unknown, not by its id`() {
        // A restored log whose slot names a custom exercise deleted before the backup.
        val workoutId = store.addFinishedWorkout(startedAt = 1_000_000, "custom-gone" to listOf(Triple(n, 50.0, 5)))
        val summary = assertNotNull(session.summarize(workoutId, ZoneOffset.UTC))
        assertEquals(listOf(com.tczerwien.lifts.ui.UNKNOWN_EXERCISE), summary.prs.map { it.exerciseName })
    }

    @Test
    fun `save as template keeps the counts within what the editor's steppers reach`() {
        val source = store.addFinishedWorkout(
            startedAt = 1_000_000,
            "row" to List(7) { Triple(w, 40.0, 10) } + List(12) { Triple(n, 60.0, 8) },
        )
        val template = assertNotNull(session.saveAsTemplate(source, "Volume"))
        val row = store.exercisesForTemplate(template).single()
        assertEquals(MAX_TEMPLATE_SETS to MAX_TEMPLATE_WARMUPS, row.targetSets to row.warmupSets)
    }

    @Test
    fun `an exercise he removed comes back in neither a start again nor a template`() {
        val source = store.addFinishedWorkout(
            startedAt = 1_000_000,
            "bench" to listOf(Triple(n, 80.0, 5)),
            "sled" to listOf(Triple(n, 90.0, 10)),
        )
        // His own sled push, removed since: hidden, kept for its history.
        store.exercises["sled"] = com.tczerwien.lifts.data.ExerciseEntity(
            id = "sled", name = "Sled Push", equipment = null, primaryMuscles = emptyList(), secondaryMuscles = emptyList(),
            category = "strength", level = null, instructions = emptyList(), isCustom = true, hidden = true,
        )
        val again = assertNotNull(session.startFromWorkout(source, now = 2_000_000))
        assertEquals(listOf("bench"), store.exercisesForWorkout(again).map { it.exerciseId })
        session.discardWorkout(again)
        val template = assertNotNull(session.saveAsTemplate(source, "Push"))
        assertEquals(listOf("bench"), store.exercisesForTemplate(template).map { it.exerciseId })
    }

    @Test
    fun `a pick of an exercise deleted or removed since the picker read its list adds nothing`() {
        val workoutId = session.startEmpty(now = 1_000)
        assertNull(session.addExercise(workoutId, "custom-gone"), "deleted: a slot would name a missing exercise")
        store.exercises["sled"] = store.exercises.getValue("bench").copy(id = "sled", name = "Sled Push", isCustom = true, hidden = true)
        assertNull(session.addExercise(workoutId, "sled"), "removed: hidden, kept only for its history")
        assertTrue(store.exercisesForWorkout(workoutId).isEmpty())
        assertNotNull(session.addExercise(workoutId, "bench"))
    }

    @Test
    fun `RESUME after Finish brings back the rest Finish cleared, while it hasn't ended`() {
        val workoutId = session.startEmpty(now = 1_000)
        val slot = store.addWorkoutExercise(WorkoutExerciseEntity(workoutId = workoutId, exerciseId = "row", position = 0))
        val set = store.addSet(SetEntity(workoutExerciseId = slot, position = 0, type = n))
        session.completeSet(workoutId, store.setById(set)!!, 60.0, 8, n, now = 10_000)
        val running = assertNotNull(session.restStateSnapshot())
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(workoutId, now = 20_000))
        assertNull(session.restStateSnapshot(), "Finish clears it")
        assertTrue(session.reopenWorkout(workoutId, running, now = 30_000))
        assertEquals(running, session.restStateSnapshot(), "back as it was, end and length")
        // A rest that ended while Workout Complete showed stays gone.
        session.finishOrDiscard(workoutId, now = 40_000)
        assertTrue(session.reopenWorkout(workoutId, running, now = running.endMillis))
        assertNull(session.restStateSnapshot())
    }

    /** What DataStore holds as the workout a Start Again copied ("workoutId:sourceId"), or null. */
    private fun startedFrom(): String? = runBlocking {
        dataStore.data.first()[stringPreferencesKey("lifts_started_from")]
    }

    @Test
    fun `a start again's source is saved before its rows commit`() {
        val source = finishedEvening()
        val atCommit = mutableListOf<String?>()
        store.onCommit = { atCommit += startedFrom() }
        val again = assertNotNull(session.startFromWorkout(source, now = 5 * 24 * 3_600_000L))
        store.onCommit = null
        // A kill right after the commit still leaves the source: never a saved copy Home can't tell is untouched.
        assertEquals(listOf<String?>("$again:$source"), atCommit)
    }

    @Test
    fun `an empty workout never takes a source left by a start again that didn't commit`() {
        val source = finishedEvening()
        // A Start Again saved its source under the id the next workout gets, and was killed before its rows committed.
        runBlocking<Unit> {
            dataStore.edit { prefs -> prefs[stringPreferencesKey("lifts_started_from")] = "${store.nextWorkoutId()}:$source" }
        }
        val empty = session.startEmpty(now = 5 * 24 * 3_600_000L)
        assertNull(startedFrom())
        assertTrue(session.discardIfUntouched(empty), "an empty workout backed out of leaves nothing")
    }

    @Test
    fun `an untouched copy is still known after a restart, so Home's tidy deletes it`() {
        val source = finishedEvening()
        val again = assertNotNull(session.startFromWorkout(source, now = 5 * 24 * 3_600_000L))
        // The app is killed on Active Workout: a new process, Home's cleanup.
        val restarted = newSession()
        val tidied = restarted.tidyOpenWorkouts(now = 5 * 24 * 3_600_000L + 60_000)
        assertNull(tidied.open)
        assertNull(store.workoutById(again), "compared with its source, not with an empty start")
    }

    @Test
    fun `start again and save as template refuse an unfinished source, and start again another open workout`() {
        val source = finishedEvening()
        val open = session.startEmpty(now = 5 * 24 * 3_600_000L)
        store.addWorkoutExercise(WorkoutExerciseEntity(workoutId = open, exerciseId = "curl", position = 0))
        assertNull(session.startFromWorkout(source, now = 5 * 24 * 3_600_000L + 1), "one workout open at a time")
        // An unfinished source is always the open one, so the one-open rule refuses it first.
        assertNull(session.startFromWorkout(open, now = 5 * 24 * 3_600_000L + 1), "not from the open workout")
        assertNull(session.saveAsTemplate(open, "Half done"))
        assertEquals(1, store.listOpenWorkouts().size)
        assertNull(session.startFromWorkout(9_999, now = 5 * 24 * 3_600_000L + 1), "gone")
    }

    @Test
    fun `save as template writes the done exercises with their sets, warm-ups, targets and rests`() {
        val source = finishedEvening()
        val incline = store.exercisesForWorkout(source).first()
        // A warm-up rest equal to Settings' default follows the default in the template.
        store.slots[incline.id] = incline.copy(restSecWarmup = SettingsStore.DEFAULT_WARMUP_REST_SEC_VALUE)
        store.addTemplate("Lower A", "squat" to 3)
        val templateId = assertNotNull(session.saveAsTemplate(source, "  Push   day "))
        val template = store.templateById(templateId)!!
        assertEquals("Push day", template.name)
        assertEquals(1, template.position, "last in the list")
        val rows = store.exercisesForTemplate(templateId)
        assertEquals(listOf("incline", "curl"), rows.map { it.exerciseId })
        val first = rows.first()
        assertEquals(listOf(2, 1, 6, 12, 1, 150), listOf(first.targetSets, first.warmupSets, first.repMin, first.repMax, first.targetRir, first.restSecWork))
        assertNull(first.restSecWarmup)
        assertEquals(1, rows[1].targetSets)
        assertNull(rows[1].warmupSets, "no warm-up ticked: last session's, not \"never\"")
        assertEquals("Evening Workout", store.templateById(session.saveAsTemplate(source, " ")!!)!!.name, "blank takes the workout's")
        assertNull(session.saveAsTemplate(9_999, "Gone"))
    }

    @Test
    fun `a stale workout with a note but no tick is closed, not deleted`() {
        val workoutId = session.startEmpty(now = 1_000)
        val slotId = session.addExercise(workoutId, "squat")!!
        assertTrue(session.setSlotNotes(workoutId, slotId, "knee sore, skipped squats"))
        assertTrue(session.closeIfStale(workoutId, now = 1_000 + 5 * 3_600_000L))
        assertEquals(1_000L, store.workoutById(workoutId)?.endedAt, "closed at its start")
        assertEquals("knee sore, skipped squats", store.slots.getValue(slotId).notes)
        // Without the note it goes, as before.
        val bare = session.startEmpty(now = 2_000)
        session.addExercise(bare, "squat")!!
        assertTrue(session.closeIfStale(bare, now = 2_000 + 5 * 3_600_000L))
        assertNull(store.workoutById(bare))
    }

    @Test
    fun `a rest for this exercise equal to the default follows the default`() {
        val workoutId = session.startEmpty(now = 1_000)
        val slotId = session.addExercise(workoutId, "fly")!!
        assertTrue(session.setSlotRest(workoutId, slotId, 60))
        assertEquals(60, store.slots.getValue(slotId).restSecWork)
        val row = store.setsForWorkout(workoutId).first()
        assertEquals(60, session.completeSet(workoutId, row, 20.0, 12, n, now = 5_000)!!.restDurationSec)
        assertTrue(session.setSlotRest(workoutId, slotId, SettingsStore.DEFAULT_REST_SEC_VALUE))
        assertNull(store.slots.getValue(slotId).restSecWork, "the default is stored as none")
        assertTrue(session.setSlotRest(workoutId, slotId, 0))
        assertEquals(0, store.slots.getValue(slotId).restSecWork, "0 s is a superset's first half, not the default")
    }

    @Test
    fun `the menu's edits can't change a workout that has closed`() {
        val workoutId = session.startEmpty(now = 1_000)
        val slotId = session.addExercise(workoutId, "bench")!!
        session.completeSet(workoutId, store.setsForWorkout(workoutId).first(), 100.0, 5, n, now = 60_000)
        assertTrue(session.closeIfStale(workoutId, now = 60_000 + 5 * 3_600_000L))
        val before = store.workoutById(workoutId) to store.slots.getValue(slotId)
        assertFalse(session.renameWorkout(workoutId, "Upper A"))
        assertFalse(session.setWorkoutNotes(workoutId, "late"))
        assertFalse(session.setSlotNotes(workoutId, slotId, "late"))
        assertFalse(session.setSlotRest(workoutId, slotId, 60))
        assertEquals(before, store.workoutById(workoutId) to store.slots.getValue(slotId))
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

    // --- Supersets ---

    @Test
    fun `a template's superset reaches the workout, and Start Again and Save as Template keep it`() {
        val template = store.addTemplate("Arms", "row" to 2, "fly" to 2, "curl" to 2)
        val (_, fly, curl) = store.exercisesForTemplate(template)
        store.templateExercises[fly.id] = fly.copy(supersetGroup = 5)
        store.templateExercises[curl.id] = curl.copy(supersetGroup = 5)
        val untouched = session.startFromTemplate(template, now = 500_000)
        assertEquals(listOf(null, 5, 5), store.exercisesForWorkout(untouched).map { it.supersetGroup })
        assertTrue(session.discardIfUntouched(untouched), "grouped as the template is: untouched")

        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        var clock = 1_060_000L
        for (slot in store.exercisesForWorkout(workoutId)) {
            session.completeSet(workoutId, rowsOf(slot).first(), 20.0, 10, n, now = clock)
            clock += 60_000
        }
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(workoutId, now = clock))
        val again = assertNotNull(session.startFromWorkout(workoutId, now = clock + 3_600_000))
        assertTrue(sameGrouping(listOf(null, 1, 1), store.exercisesForWorkout(again).map { it.supersetGroup }))
        assertTrue(session.discardIfUntouched(again), "a copy grouped as the source: untouched")
        val saved = assertNotNull(session.saveAsTemplate(workoutId, "Arms 2"))
        assertTrue(sameGrouping(listOf(null, 1, 1), store.exercisesForTemplate(saved).map { it.supersetGroup }))
    }

    @Test
    fun `Start Again drops a superset whose partner wasn't done that day`() {
        val template = store.addTemplate("Arms", "fly" to 2, "curl" to 2, "row" to 2)
        store.exercisesForTemplate(template).take(2).forEach { store.templateExercises[it.id] = it.copy(supersetGroup = 1) }
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        val (fly, _, row) = store.exercisesForWorkout(workoutId)
        session.completeSet(workoutId, rowsOf(fly).first(), 20.0, 10, n, now = 1_060_000)
        session.completeSet(workoutId, rowsOf(row).first(), 40.0, 10, n, now = 1_120_000)
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(workoutId, now = 1_200_000))
        val again = assertNotNull(session.startFromWorkout(workoutId, now = 5_000_000))
        assertEquals(listOf("fly" to null, "row" to null), store.exercisesForWorkout(again).map { it.exerciseId to it.supersetGroup })
    }

    // --- Version 2's columns ---

    @Test
    fun `version 2's columns ride along unchanged through a whole workout`() {
        // Set as the v2 features will set them; nothing reads them yet, and no write may drop them.
        // (An exercise's countsFor isn't here: a workout never writes exercises.)
        val template = store.addTemplate("Upper A", "fly" to 2, "bench" to 2, "curl" to 2)
        store.exercisesForTemplate(template).forEach {
            store.templateExercises[it.id] = it.copy(supersetGroup = 1, alternatives = listOf("row"), targetRirMax = 2)
        }
        val templateRows = store.exercisesForTemplate(template)
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        store.workouts[workoutId] = store.workoutById(workoutId)!!.copy(deload = true)
        store.exercisesForWorkout(workoutId).forEach {
            store.slots[it.id] = it.copy(supersetGroup = 7, choices = listOf(it.exerciseId, "row"), targetSets = 2, targetRirMax = 2)
        }
        val (fly, bench, curl) = store.exercisesForWorkout(workoutId)

        session.completeSet(workoutId, rowsOf(bench).first(), 60.0, 8, n, now = 1_060_000)
        session.addSet(workoutId, bench.id)
        session.removeSet(workoutId, rowsOf(bench).last().id)
        session.setSlotNotes(workoutId, bench.id, "Pause at the chest")
        session.setSlotRest(workoutId, bench.id, 120)
        session.renameWorkout(workoutId, "Push")
        session.setWorkoutNotes(workoutId, "Felt strong")
        assertTrue(session.removeExercise(workoutId, fly.id), "the rest renumber")
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(workoutId, now = 1_200_000))
        assertTrue(session.reopenWorkout(workoutId, now = 1_300_000))
        assertTrue(session.closeIfStale(workoutId, now = 1_060_000 + 10 * 3_600_000L), "closed at its last set")

        val workout = store.workoutById(workoutId)!!
        assertEquals(listOf("Push", "Felt strong", 1_060_000L, true), listOf(workout.name, workout.notes, workout.endedAt, workout.deload))
        val slots = store.exercisesForWorkout(workoutId)
        assertEquals(listOf(bench.id to 0, curl.id to 1), slots.map { it.id to it.position })
        for (slot in slots) {
            assertEquals(
                listOf(7, listOf(slot.exerciseId, "row"), 2, 2),
                listOf(slot.supersetGroup, slot.choices, slot.targetSets, slot.targetRirMax),
                "slot ${slot.exerciseId}",
            )
        }
        assertEquals(templateRows, store.exercisesForTemplate(template), "a workout never writes its template")
    }

    private fun rowsOf(slot: WorkoutExerciseEntity): List<SetEntity> =
        store.setsForWorkout(slot.workoutId).filter { it.workoutExerciseId == slot.id }
}
