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
        store.templateExercises[row.id] = row.copy(warmupSets = 2, repMin = 6, repMax = 12, targetRir = 1, targetRirMax = 2, restSecWork = 150)
        val workoutId = session.startFromTemplate(template, now = 2_000_000)
        val slot = store.exercisesForWorkout(workoutId).single()
        assertEquals(listOf(6, 12, 1, 2, 150), listOf(slot.targetRepMin, slot.targetRepMax, slot.targetRir, slot.targetRirMax, slot.restSecWork))
        assertEquals(listOf(w, w, n, n, n), store.setsForWorkout(workoutId).map { it.type }, "two warm-ups, not last time's one")
        assertEquals(3, slot.targetSets, "the planned work sets, for a deload to restore")
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
        assertTrue(store.exercisesForWorkout(workoutId).all { it.targetSets == null }, "a hand-added slot has no plan")
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
    fun `a set with a drop still to do after it starts no rest, and the drop's tick starts the work rest`() {
        // A drop made ahead with the marker under set 1 (DR-6): no beep mid-drop.
        val workoutId = session.startEmpty(now = 1_000)
        val slot = store.addWorkoutExercise(WorkoutExerciseEntity(workoutId = workoutId, exerciseId = "fly", position = 0, restSecWork = 90))
        val first = store.addSet(SetEntity(workoutExerciseId = slot, position = 0, type = n))
        val drop = store.addSet(SetEntity(workoutExerciseId = slot, position = 1, type = SET_TYPE_DROP))
        store.addSet(SetEntity(workoutExerciseId = slot, position = 2, type = n))
        runBlocking { session.startRest(120, now = 1_000) }
        val set1 = assertNotNull(session.completeSet(workoutId, store.setById(first)!!, 30.0, 12, n, now = 10_000))
        assertEquals(0 to null, set1.restDurationSec to set1.restEndMillis)
        assertNull(session.restStateSnapshot(), "the running rest ends too")
        val dropped = assertNotNull(session.completeSet(workoutId, store.setById(drop)!!, 22.5, 8, SET_TYPE_DROP, now = 40_000))
        assertEquals(90 to 130_000L, dropped.restDurationSec to dropped.restEndMillis, "the work rest, after the drop")
    }

    // --- Drop sets (DR-3) ---

    @Test
    fun `Drop Set puts an empty drop under the set just ticked, the rows below moving down, and ends the rest`() {
        val template = store.addTemplate("Chest", "fly" to 4)
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        val fly = store.exercisesForWorkout(workoutId).single()
        rowsOf(fly).take(3).forEachIndexed { index, row -> session.completeSet(workoutId, row, 30.0, 12, n, now = 1_100_000L + index * 60_000) }
        assertNotNull(session.restStateSnapshot())
        val set4 = rowsOf(fly)[3]

        val dropId = assertNotNull(session.addDrop(workoutId, fly.id))
        assertEquals(listOf(n, n, n, SET_TYPE_DROP, n), rowsOf(fly).map { it.type })
        assertEquals(listOf(0, 1, 2, 3, 4), rowsOf(fly).map { it.position })
        assertEquals(dropId to set4.id, rowsOf(fly)[3].id to rowsOf(fly)[4].id, "set 4 moved down, the same row")
        val drop = store.setById(dropId)!!
        assertEquals(listOf(null, null, null), listOf(drop.weightKg, drop.reps, drop.completedAt), "empty: it shows where a drop starts")
        assertNull(session.restStateSnapshot(), "no beep mid-drop")
        // Its tick starts the work rest; another drop goes under it, a chain under set 3.
        assertNotNull(session.completeSet(workoutId, drop, 22.5, 8, SET_TYPE_DROP, now = 1_300_000)!!.restEndMillis)
        val second = assertNotNull(session.addDrop(workoutId, fly.id))
        assertEquals(listOf(n, n, n, SET_TYPE_DROP, SET_TYPE_DROP, n), rowsOf(fly).map { it.type })
        assertEquals(4, rowsOf(fly).indexOfFirst { it.id == second })
        assertEquals(listOf("1", "2", "3", "3", "3", "4"), setMarkers(rowsOf(fly)))
    }

    @Test
    fun `Drop Set is refused without a work set or drop ticked last, on a lift without weight, or a closed workout`() {
        val workoutId = session.startEmpty(now = 1_000)
        val fly = assertNotNull(session.addExercise(workoutId, "fly"))
        val flyRows = { store.setsForWorkout(workoutId).filter { it.workoutExerciseId == fly } }
        assertNull(session.addDrop(workoutId, fly), "nothing ticked")
        store.sets[flyRows()[0].id] = flyRows()[0].copy(type = w)
        session.completeSet(workoutId, flyRows()[0], 10.0, 12, w, now = 10_000)
        assertNull(session.addDrop(workoutId, fly), "only a warm-up ticked")
        val plank = assertNotNull(session.addExercise(workoutId, "plank"))
        store.exercises["plank"] = store.exercises.getValue("plank").copy(trackingType = com.tczerwien.lifts.data.Tracking.DURATION.stored)
        val hold = store.setsForWorkout(workoutId).first { it.workoutExerciseId == plank }
        session.completeSet(workoutId, hold, null, null, n, now = 20_000, durationSec = 60)
        assertNull(session.addDrop(workoutId, plank), "a hold takes no weight")
        session.completeSet(workoutId, flyRows()[1], 30.0, 12, n, now = 30_000)
        assertNull(session.addDrop(workoutId, 9_999), "not a slot of it")
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(workoutId, now = 40_000))
        val before = store.setsForWorkout(workoutId)
        assertNull(session.addDrop(workoutId, fly), "closed")
        assertEquals(before, store.setsForWorkout(workoutId))
    }

    @Test
    fun `Drop Set goes to a drop already waiting, and after a logged one when its set is ticked again`() {
        val workoutId = session.startEmpty(now = 1_000)
        val slot = store.addWorkoutExercise(WorkoutExerciseEntity(workoutId = workoutId, exerciseId = "fly", position = 0))
        val ids = listOf(n, SET_TYPE_DROP, n).mapIndexed { position, type -> store.addSet(SetEntity(workoutExerciseId = slot, position = position, type = type)) }
        session.completeSet(workoutId, store.setById(ids[0])!!, 30.0, 12, n, now = 10_000)
        // The drop made ahead with the marker: Drop Set goes to it, no second row (review R1-2, A1).
        assertEquals(ids[1], session.addDrop(workoutId, slot))
        assertEquals(3, store.setsForWorkout(workoutId).size)
        // Logged, then set 1 ticked again: the new drop goes below the logged one.
        session.completeSet(workoutId, store.setById(ids[1])!!, 22.5, 8, SET_TYPE_DROP, now = 20_000)
        session.uncompleteSet(workoutId, store.setById(ids[0])!!)
        session.completeSet(workoutId, store.setById(ids[0])!!, 30.0, 11, n, now = 30_000)
        val second = assertNotNull(session.addDrop(workoutId, slot))
        assertEquals(listOf(ids[0], ids[1], second, ids[2]), store.setsForWorkout(workoutId).map { it.id })
    }

    @Test
    fun `removing a set takes its drop, so it never ends up under the set above`() {
        val workoutId = session.startEmpty(now = 1_000)
        val slot = store.addWorkoutExercise(WorkoutExerciseEntity(workoutId = workoutId, exerciseId = "fly", position = 0))
        val types = listOf(n, n, SET_TYPE_DROP, n)
        val ids = types.mapIndexed { position, type -> store.addSet(SetEntity(workoutExerciseId = slot, position = position, type = type)) }
        assertTrue(session.removeSet(workoutId, ids[1]))
        assertEquals(listOf(ids[0] to 0, ids[3] to 1), store.setsForWorkout(workoutId).map { it.id to it.position })
        // A set whose drop is logged stays, with it.
        val more = listOf(n, SET_TYPE_DROP).mapIndexed { index, type -> store.addSet(SetEntity(workoutExerciseId = slot, position = 2 + index, type = type)) }
        session.completeSet(workoutId, store.setById(more[1])!!, 20.0, 8, SET_TYPE_DROP, now = 10_000)
        assertFalse(session.removeSet(workoutId, more[0]))
        assertEquals(4, store.setsForWorkout(workoutId).size)
    }

    @Test
    fun `a deload keeps a drop with its set`() {
        // Set 1 done with a drop, then the deload switched on: set 1 and its drop stay, with one more set.
        val template = store.addTemplate("Chest", "fly" to 4)
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        val fly = store.exercisesForWorkout(workoutId).single()
        session.completeSet(workoutId, rowsOf(fly)[0], 30.0, 12, n, now = 1_100_000)
        assertNotNull(session.addDrop(workoutId, fly.id))
        assertTrue(session.setDeload(workoutId, on = true))
        assertEquals(listOf(n, SET_TYPE_DROP, n), rowsOf(fly).map { it.type }, "half of 4 work sets; the drop is no set")
        assertTrue(session.setDeload(workoutId, on = false))
        assertEquals(listOf(n, SET_TYPE_DROP, n, n, n), rowsOf(fly).map { it.type })
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
            restSecWork = 150, restSecWarmup = 60, targetRepMin = 6, targetRepMax = 12, targetRir = 1, targetRirMax = 2, notes = "elbow ok",
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
        assertEquals(
            listOf(150, 60, 6, 12, 1, 2),
            listOf(incline.restSecWork, incline.restSecWarmup, incline.targetRepMin, incline.targetRepMax, incline.targetRir, incline.targetRirMax),
        )
        assertNull(incline.notes, "his note stays with the old workout")
        assertEquals(listOf(2, 1), slots.map { it.targetSets }, "no plan on the source: the work sets it copies")
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
    fun `a drop is no extra set in a start again, a template, or a hand-added exercise`() {
        // Four sets of flyes, the last with a drop under it (DR-5).
        val d = SET_TYPE_DROP
        val source = store.addFinishedWorkout(
            startedAt = 1_000_000,
            "fly" to listOf(Triple(n, 30.0, 12), Triple(n, 30.0, 12), Triple(n, 30.0, 12), Triple(n, 30.0, 10), Triple(d, 22.5, 8)),
        )
        val again = assertNotNull(session.startFromWorkout(source, now = 2_000_000))
        assertEquals(listOf(n, n, n, n), store.setsForWorkout(again).map { it.type })
        assertEquals(4, store.exercisesForWorkout(again).single().targetSets)
        session.discardWorkout(again)
        val template = assertNotNull(session.saveAsTemplate(source, "Chest"))
        assertEquals(4, store.exercisesForTemplate(template).single().targetSets, "4, not 5")
        val empty = session.startEmpty(now = 3_000_000)
        val slot = assertNotNull(session.addExercise(empty, "fly"))
        assertEquals(listOf(n, n, n, n), store.setsForWorkout(empty).filter { it.workoutExerciseId == slot }.map { it.type })
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
        assertEquals(listOf<String?>("$again:$source:0"), atCommit)
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
        assertEquals(
            listOf(2, 1, 6, 12, 1, 2, 150),
            listOf(first.targetSets, first.warmupSets, first.repMin, first.repMax, first.targetRir, first.targetRirMax, first.restSecWork),
        )
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
    fun `in a superset the first member's tick starts no rest, and the round's last starts its own`() {
        val template = store.addTemplate("Arms", "fly" to 2, "curl" to 2, "row" to 2)
        val (flyRow, curlRow) = store.exercisesForTemplate(template)
        store.templateExercises[flyRow.id] = flyRow.copy(supersetGroup = 1, restSecWork = 75)
        store.templateExercises[curlRow.id] = curlRow.copy(supersetGroup = 1, restSecWork = 90)
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        val (fly, curl, row) = store.exercisesForWorkout(workoutId)
        // A rest left from before the pair: the first member's tick ends it, as a 0 s slot's does.
        runBlocking { session.startRest(120, now = 1_000_000) }

        val a1 = assertNotNull(session.completeSet(workoutId, rowsOf(fly)[0], 20.0, 12, n, now = 1_010_000))
        assertEquals(0 to null, a1.restDurationSec to a1.restEndMillis)
        assertNull(session.restStateSnapshot(), "no rest and no beep between the two")
        val b1 = assertNotNull(session.completeSet(workoutId, rowsOf(curl)[0], 12.0, 10, n, now = 1_040_000))
        assertEquals(90 to 1_130_000L, b1.restDurationSec to b1.restEndMillis, "the curl's own rest, after the round")
        assertEquals(1_130_000L, session.restStateSnapshot()?.endMillis)
        // A plain slot after the pair rests as always (Settings' default here).
        assertEquals(
            SettingsStore.DEFAULT_REST_SEC_VALUE,
            session.completeSet(workoutId, rowsOf(row)[0], 40.0, 10, n, now = 1_200_000)!!.restDurationSec,
        )
    }

    @Test
    fun `removing a member leaves its partner plain, and another superset keeps its own`() {
        val template = store.addTemplate("Arms", "fly" to 2, "curl" to 2, "row" to 2, "bench" to 2)
        store.exercisesForTemplate(template).forEachIndexed { index, it ->
            store.templateExercises[it.id] = it.copy(supersetGroup = if (index < 2) 1 else 2)
        }
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        val curl = store.exercisesForWorkout(workoutId)[1]
        assertTrue(session.removeExercise(workoutId, curl.id))
        assertEquals(
            listOf("fly" to null, "row" to 2, "bench" to 2),
            store.exercisesForWorkout(workoutId).map { it.exerciseId to it.supersetGroup },
        )
    }

    @Test
    fun `Superset With Next joins and splits this workout's exercises only, until it closes`() {
        val template = store.addTemplate("Legs", "squat" to 2, "curl" to 2, "row" to 2)
        val templateRows = store.exercisesForTemplate(template)
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        val (squat, curl, row) = store.exercisesForWorkout(workoutId)
        // As the exports number them: the grouping, whatever numbers it is stored with.
        fun groups() = supersetNumbers(store.exercisesForWorkout(workoutId).map { it.supersetGroup })

        assertTrue(session.setSupersetWithNext(workoutId, curl.id, on = true))
        assertEquals(listOf(null, 1, 1), groups())
        assertFalse(session.setSupersetWithNext(workoutId, curl.id, on = true), "already joined")
        assertFalse(session.setSupersetWithNext(workoutId, row.id, on = true), "the last has no next")
        assertFalse(session.discardIfUntouched(workoutId), "joined for today: Back keeps it")
        assertTrue(session.setSupersetWithNext(workoutId, squat.id, on = true), "joins the pair: three in turn")
        assertEquals(listOf(1, 1, 1), groups())
        assertTrue(session.setSupersetWithNext(workoutId, squat.id, on = false))
        assertEquals(listOf(null, 1, 1), groups())
        assertEquals(templateRows, store.exercisesForTemplate(template), "the template never changes")

        session.completeSet(workoutId, rowsOf(squat)[0], 100.0, 5, n, now = 1_060_000)
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(workoutId, now = 1_200_000))
        assertFalse(session.setSupersetWithNext(workoutId, curl.id, on = false), "closed: refused")
        assertEquals(listOf(null, 1, 1), groups())
    }

    @Test
    fun `Superset With Next leaves odd stored groups alone where it changes nothing`() {
        // A restored log with a group of one: the switch on the last exercise, or where it already is so, writes nothing.
        val template = store.addTemplate("Legs", "squat" to 2, "curl" to 2)
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        val (squat, curl) = store.exercisesForWorkout(workoutId)
        store.slots[curl.id] = curl.copy(supersetGroup = 8)
        assertFalse(session.setSupersetWithNext(workoutId, curl.id, on = true), "the last has no next")
        assertFalse(session.setSupersetWithNext(workoutId, squat.id, on = false), "already off")
        assertEquals(listOf(null, 8), store.exercisesForWorkout(workoutId).map { it.supersetGroup })
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

    // --- Deload (B14) ---

    @Test
    fun `last session skips a deload, unless only deloads did the exercise`() {
        store.addFinishedWorkout(
            startedAt = 1_000_000,
            "squat" to listOf(Triple(w, 60.0, 5), Triple(n, 100.0, 5), Triple(n, 100.0, 5), Triple(n, 100.0, 5)),
        )
        val deload = store.addFinishedWorkout(
            startedAt = 2_000_000,
            "squat" to listOf(Triple(n, 90.0, 5), Triple(n, 90.0, 5)),
            "curl" to listOf(Triple(n, 12.0, 10)),
        )
        store.updateWorkout(store.workoutById(deload)!!.copy(deload = true))
        val groups = session.previousGroupsByExercise(listOf("squat", "curl"))
        assertEquals(listOf(60.0, 100.0, 100.0, 100.0), groups.getValue("squat").single().sets.map { it.weightKg }, "the normal session, though older")
        assertEquals(listOf(12.0), groups.getValue("curl").single().sets.map { it.weightKg }, "only a deload did it: that is last time")
        // The next start plans from it too: the normal session's warm-up, not the deload's none.
        val template = store.addTemplate("Lower A", "squat" to 3)
        val workoutId = session.startFromTemplate(template, now = 3_000_000)
        assertEquals(listOf(w, n, n, n), store.setsForWorkout(workoutId).map { it.type })
    }

    /** Each slot's rows of [workoutId] as types, in slot order. */
    private fun typesBySlot(workoutId: Long): List<List<String>> =
        store.exercisesForWorkout(workoutId).map { slot -> rowsOf(slot).map { it.type } }

    @Test
    fun `deload on trims each exercise to half its planned work sets, off puts them back, the template untouched`() {
        val template = store.addTemplate("Lower A", "squat" to 4, "curl" to 3, "row" to 1)
        val squatRow = store.exercisesForTemplate(template).first()
        store.templateExercises[squatRow.id] = squatRow.copy(warmupSets = 2)
        val templateRows = store.exercisesForTemplate(template)
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        assertEquals(listOf(listOf(w, w, n, n, n, n), listOf(n, n, n), listOf(n)), typesBySlot(workoutId))

        assertTrue(session.setDeload(workoutId, on = true))
        assertTrue(store.workoutById(workoutId)!!.deload)
        assertEquals(listOf(listOf(w, w, n, n), listOf(n, n), listOf(n)), typesBySlot(workoutId), "4 -> 2, 3 -> 2, 1 -> 1; warm-ups stay")
        assertEquals(listOf(4, 3, 1), store.exercisesForWorkout(workoutId).map { it.targetSets }, "the plan is kept")
        store.exercisesForWorkout(workoutId).forEach { slot ->
            assertEquals(rowsOf(slot).indices.toList(), rowsOf(slot).map { it.position }, "positions close up")
        }
        assertFalse(session.setDeload(workoutId, on = true), "already on: nothing to do")

        assertTrue(session.setDeload(workoutId, on = false))
        assertFalse(store.workoutById(workoutId)!!.deload)
        assertEquals(listOf(listOf(w, w, n, n, n, n), listOf(n, n, n), listOf(n)), typesBySlot(workoutId))
        assertEquals(templateRows, store.exercisesForTemplate(template), "a deload never writes the template")
        // On and off again with nothing else done is the workout as it started.
        assertTrue(session.discardIfUntouched(workoutId))
    }

    @Test
    fun `a deload switched on mid-workout never removes a ticked set, and is kept on Back`() {
        val template = store.addTemplate("Lower A", "squat" to 4)
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        val squat = store.exercisesForWorkout(workoutId).single()
        rowsOf(squat).take(3).forEachIndexed { index, row ->
            session.completeSet(workoutId, row, 100.0, 5, n, now = 1_100_000L + index * 60_000)
        }
        assertTrue(session.setDeload(workoutId, on = true))
        val left = rowsOf(squat)
        assertEquals(3, left.size, "the fourth went; the three done stay")
        assertTrue(left.all { it.completedAt != null })
        assertTrue(session.setDeload(workoutId, on = false))
        assertEquals(4, rowsOf(squat).size)
        assertNull(rowsOf(squat).last().completedAt)
        // Switched on before a tick: his, so Back keeps it for Resume.
        val other = store.addTemplate("Upper A", "bench" to 3)
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(workoutId, now = 1_400_000))
        val next = session.startFromTemplate(other, now = 2_000_000)
        assertTrue(session.setDeload(next, on = true))
        assertFalse(session.discardIfUntouched(next))
    }

    @Test
    fun `an exercise with no plan keeps its own sets as the plan, and a closed workout is refused`() {
        val workoutId = session.startEmpty(now = 1_000_000)
        val curl = session.addExercise(workoutId, "curl")!!
        assertNull(store.exercisesForWorkout(workoutId).single().targetSets)
        assertTrue(session.setDeload(workoutId, on = true))
        assertEquals(3, store.exercisesForWorkout(workoutId).single().targetSets, "its three rows, recorded")
        assertEquals(2, store.setsForWorkout(workoutId).count { it.workoutExerciseId == curl })
        assertTrue(session.setDeload(workoutId, on = false))
        assertEquals(3, store.setsForWorkout(workoutId).count { it.workoutExerciseId == curl })

        // Added while the deload is on: its sets become its plan and it starts at the deload's number.
        assertTrue(session.setDeload(workoutId, on = true))
        val row = session.addExercise(workoutId, "row")!!
        assertEquals(3, store.exercisesForWorkout(workoutId).single { it.id == row }.targetSets)
        assertEquals(2, store.setsForWorkout(workoutId).count { it.workoutExerciseId == row })
        assertTrue(session.setDeload(workoutId, on = false))
        assertEquals(3, store.setsForWorkout(workoutId).count { it.workoutExerciseId == row }, "off: its three")
        // A slot left with warm-ups only records no plan of nought.
        val plank = session.addExercise(workoutId, "plank")!!
        store.setsForWorkout(workoutId).filter { it.workoutExerciseId == plank }.forEach { store.sets[it.id] = it.copy(type = w) }
        assertTrue(session.setDeload(workoutId, on = true))
        assertNull(store.exercisesForWorkout(workoutId).single { it.id == plank }.targetSets)
        assertTrue(session.setDeload(workoutId, on = false))

        session.completeSet(workoutId, store.setsForWorkout(workoutId).first(), 12.0, 10, n, now = 1_100_000)
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(workoutId, now = 1_200_000))
        val before = store.setsForWorkout(workoutId)
        assertFalse(session.setDeload(workoutId, on = true), "finished: the menu's switch can't change it")
        assertFalse(store.workoutById(workoutId)!!.deload)
        assertEquals(before, store.setsForWorkout(workoutId))
    }

    @Test
    fun `Start Again of a deload is a deload with its trimmed rows and the full plan`() {
        val template = store.addTemplate("Lower A", "squat" to 4)
        val source = session.startFromTemplate(template, now = 1_000_000)
        assertTrue(session.setDeload(source, on = true))
        rowsOf(store.exercisesForWorkout(source).single()).forEachIndexed { index, row ->
            session.completeSet(source, row, 90.0, 5, n, now = 1_100_000L + index * 60_000)
        }
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(source, now = 1_300_000))
        assertTrue(session.summarize(source)!!.deload, "Workout Complete says so")

        val again = assertNotNull(session.startFromWorkout(source, now = 5_000_000))
        assertTrue(store.workoutById(again)!!.deload)
        val slot = store.exercisesForWorkout(again).single()
        assertEquals(listOf(n, n), rowsOf(slot).map { it.type }, "the two done that day")
        assertEquals(4, slot.targetSets, "the full plan")
        // The source unmarked from inside the copy (Workout Detail) changes nothing about how the copy started.
        assertTrue(session.markDeload(source, on = false))
        // Backed out at once: nothing done, so nothing is kept.
        assertTrue(session.discardIfUntouched(again))
        assertNull(store.workoutById(again))
        assertTrue(session.markDeload(source, on = true))
        // Switched off, the copy has the full four.
        val third = assertNotNull(session.startFromWorkout(source, now = 6_000_000))
        assertTrue(session.setDeload(third, on = false))
        assertEquals(4, rowsOf(store.exercisesForWorkout(third).single()).size)
        assertFalse(session.discardIfUntouched(third), "switched off: his")
    }

    @Test
    fun `Workout Detail marks a finished workout as a deload without touching its rows`() {
        val older = store.addFinishedWorkout(startedAt = 1_000_000, "squat" to listOf(Triple(n, 100.0, 5), Triple(n, 100.0, 5)))
        val last = store.addFinishedWorkout(startedAt = 2_000_000, "squat" to listOf(Triple(n, 90.0, 5)))
        val rows = store.setsForWorkout(last)
        assertEquals(listOf(90.0), session.previousGroupsByExercise(listOf("squat")).getValue("squat").single().sets.map { it.weightKg })

        assertTrue(session.markDeload(last, on = true))
        assertTrue(store.workoutById(last)!!.deload)
        assertEquals(rows, store.setsForWorkout(last), "only the mark")
        assertEquals(listOf(100.0, 100.0), session.previousGroupsByExercise(listOf("squat")).getValue("squat").single().sets.map { it.weightKg })
        assertFalse(session.markDeload(last, on = true), "already so")
        assertTrue(session.markDeload(last, on = false))
        assertEquals(listOf(90.0), session.previousGroupsByExercise(listOf("squat")).getValue("squat").single().sets.map { it.weightKg })

        val open = session.startEmpty(now = 3_000_000)
        assertFalse(session.markDeload(open, on = true), "an open workout takes the menu's switch")
        assertFalse(store.workoutById(open)!!.deload)
        assertFalse(session.markDeload(9_999, on = true))
        assertFalse(store.workoutById(older)!!.deload)
    }

    @Test
    fun `Start Again of a workout marked a deload after its Finish starts trimmed`() {
        val template = store.addTemplate("Lower A", "squat" to 3)
        val source = session.startFromTemplate(template, now = 1_000_000)
        rowsOf(store.exercisesForWorkout(source).single()).forEachIndexed { index, row ->
            session.completeSet(source, row, 100.0, 5, n, now = 1_100_000L + index * 60_000)
        }
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(source, now = 1_400_000))
        assertTrue(session.markDeload(source, on = true), "forgot the switch")

        val again = assertNotNull(session.startFromWorkout(source, now = 5_000_000))
        assertTrue(store.workoutById(again)!!.deload)
        val slot = store.exercisesForWorkout(again).single()
        assertEquals(listOf(n, n), rowsOf(slot).map { it.type }, "two of the three, as a deload keeps")
        assertEquals(3, slot.targetSets)
        assertTrue(session.discardIfUntouched(again), "as it started: discarded on Back")
        val third = assertNotNull(session.startFromWorkout(source, now = 6_000_000))
        assertTrue(session.setDeload(third, on = false))
        assertEquals(3, rowsOf(store.exercisesForWorkout(third).single()).size, "off: the full three")
    }

    @Test
    fun `a copy saved before the deload flag was stored falls back to the source's mark`() {
        val template = store.addTemplate("Lower A", "squat" to 4)
        val source = session.startFromTemplate(template, now = 1_000_000)
        assertTrue(session.setDeload(source, on = true))
        rowsOf(store.exercisesForWorkout(source).single()).forEach { session.completeSet(source, it, 90.0, 5, n, now = 1_100_000) }
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(source, now = 1_300_000))
        val again = assertNotNull(session.startFromWorkout(source, now = 5_000_000))
        // As 0.14.0 would have saved it: "copy:source", no third part (review R2-7).
        runBlocking<Unit> { dataStore.edit { prefs -> prefs[stringPreferencesKey("lifts_started_from")] = "$again:$source" } }
        assertTrue(session.discardIfUntouched(again), "the source is a deload, so the copy started as one")
        // A source marked after its Finish: the copy starts trimmed, and the plan the check compares with is trimmed too.
        val full = store.addTemplate("Lower B", "row" to 4)
        val marked = session.startFromTemplate(full, now = 6_000_000)
        rowsOf(store.exercisesForWorkout(marked).single()).forEach { session.completeSet(marked, it, 60.0, 10, n, now = 6_100_000) }
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(marked, now = 6_300_000))
        assertTrue(session.markDeload(marked, on = true))
        val copy = assertNotNull(session.startFromWorkout(marked, now = 7_000_000))
        assertEquals(2, rowsOf(store.exercisesForWorkout(copy).single()).size)
        runBlocking<Unit> { dataStore.edit { prefs -> prefs[stringPreferencesKey("lifts_started_from")] = "$copy:$marked" } }
        assertTrue(session.discardIfUntouched(copy), "both fallbacks: the copy's mark and its trimmed plan (closing check C1-2)")
    }

    @Test
    fun `a superset keeps alternating over a deload's rows, trimmed and put back`() {
        val template = store.addTemplate("Arms", "fly" to 3, "curl" to 3)
        store.exercisesForTemplate(template).forEach { store.templateExercises[it.id] = it.copy(supersetGroup = 1, restSecWork = 90) }
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        val (fly, curl) = store.exercisesForWorkout(workoutId)
        assertTrue(session.setDeload(workoutId, on = true))
        assertEquals(listOf(listOf(n, n), listOf(n, n)), typesBySlot(workoutId), "two rounds, not three")
        var clock = 1_100_000L
        fun tick(slot: WorkoutExerciseEntity): AfterTick {
            val row = rowsOf(slot).first { it.completedAt == null }
            clock += 30_000
            assertNotNull(session.completeSet(workoutId, row, 10.0, 10, n, now = clock))
            return afterTick(store.exercisesForWorkout(workoutId), store.setsForWorkout(workoutId), slot.id, n)
        }
        assertEquals(AfterTick(startsRest = false, moveTo = curl.id), tick(fly))
        assertEquals(AfterTick(startsRest = true, moveTo = fly.id), tick(curl))
        assertEquals(AfterTick(startsRest = false, moveTo = curl.id), tick(fly))
        assertEquals(AfterTick(startsRest = true, moveTo = null), tick(curl), "the last round: nothing after the pair")
        // Off again: the third round is back, and the turn goes on from the first member.
        assertTrue(session.setDeload(workoutId, on = false))
        assertEquals(listOf(3, 3), store.exercisesForWorkout(workoutId).map { rowsOf(it).size })
        assertEquals(AfterTick(startsRest = false, moveTo = curl.id), tick(fly))
        assertEquals(AfterTick(startsRest = true, moveTo = null), tick(curl))
    }

    // --- Version 2's columns ---

    // --- Alternatives ---

    @Test
    fun `a template's alternatives reach the workout as each slot's choices, and Back discards it as started`() {
        val template = store.addTemplate("Legs", "squat" to 3, "curl" to 2)
        val (squatRow) = store.exercisesForTemplate(template)
        store.templateExercises[squatRow.id] = squatRow.copy(alternatives = listOf("row", "fly"))
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        assertEquals(
            listOf(listOf("squat", "row", "fly"), emptyList()),
            store.exercisesForWorkout(workoutId).map { it.choices },
        )
        assertTrue(session.discardIfUntouched(workoutId), "as the template started it")
    }

    @Test
    fun `a swap does another choice for this workout only, its rows kept and emptied, the template untouched`() {
        val template = store.addTemplate("Legs", "squat" to 3, "curl" to 2)
        val (squatRow) = store.exercisesForTemplate(template)
        store.templateExercises[squatRow.id] = squatRow.copy(
            alternatives = listOf("row", "fly"), warmupSets = 1, repMin = 8, repMax = 12, targetRir = 2, restSecWork = 150,
        )
        val templateRows = store.exercisesForTemplate(template)
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        val squat = store.exercisesForWorkout(workoutId).first()
        // A weight typed in and an effort rated before the swap were the squat's: they go with it.
        session.updateSet(workoutId, rowsOf(squat)[1].copy(weightKg = 100.0, reps = 5, rpe = 8.0))

        assertTrue(session.swapExercise(workoutId, squat.id, "row"))
        val swapped = store.exercisesForWorkout(workoutId).first()
        assertEquals(squat.copy(exerciseId = "row"), swapped, "only the exercise changes: targets, rests, choices stay")
        assertEquals(listOf(w, n, n, n), rowsOf(swapped).map { it.type }, "its rows stay, the warm-up too (not recounted)")
        assertTrue(rowsOf(swapped).all { it.weightKg == null && it.reps == null && it.durationSec == null && it.rpe == null })
        assertEquals(templateRows, store.exercisesForTemplate(template), "the template is never written")
        assertFalse(session.discardIfUntouched(workoutId), "a swap is his: Back keeps it")

        session.completeSet(workoutId, rowsOf(swapped)[1], 60.0, 10, n, now = 1_060_000)
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(workoutId, now = 1_200_000))
        val next = session.startFromTemplate(template, now = 2_000_000)
        assertEquals(listOf("squat", "curl"), store.exercisesForWorkout(next).map { it.exerciseId }, "next time the main again")
        assertEquals(listOf("squat", "row", "fly"), store.exercisesForWorkout(next).first().choices)
    }

    @Test
    fun `a swap is refused after a tick on the slot, once the workout has closed, or to no other choice in the picker`() {
        val template = store.addTemplate("Legs", "squat" to 2, "curl" to 2)
        val (squatRow) = store.exercisesForTemplate(template)
        store.templateExercises[squatRow.id] = squatRow.copy(alternatives = listOf("row", "fly"))
        store.exercises["fly"] = store.exercises.getValue("fly").copy(hidden = true)
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        val (squat, curl) = store.exercisesForWorkout(workoutId)

        assertFalse(session.swapExercise(workoutId, squat.id, "bench"), "not one of its choices")
        assertFalse(session.swapExercise(workoutId, squat.id, "fly"), "hidden: he removed it")
        assertFalse(session.swapExercise(workoutId, squat.id, "squat"), "already doing it")
        assertFalse(session.swapExercise(workoutId, curl.id, "row"), "the curl has no choices")
        session.completeSet(workoutId, rowsOf(curl).first(), 20.0, 10, n, now = 1_060_000)
        assertTrue(session.swapExercise(workoutId, squat.id, "row"), "a tick on another exercise doesn't matter")
        session.completeSet(workoutId, rowsOf(squat).first(), 60.0, 10, n, now = 1_120_000)
        assertFalse(session.swapExercise(workoutId, squat.id, "squat"), "a set of it is ticked: it stays that lift")
        assertEquals("row", store.exercisesForWorkout(workoutId).first().exerciseId)

        val closed = session.startFromTemplate(template, now = 2_000_000)
        val slot = store.exercisesForWorkout(closed).first()
        store.workouts[closed] = store.workoutById(closed)!!.copy(endedAt = 2_100_000)
        assertFalse(session.swapExercise(closed, slot.id, "row"), "closed")
        assertEquals("squat", store.exercisesForWorkout(closed).first().exerciseId)
    }

    @Test
    fun `swapped back to the main, the workout is as it started again`() {
        val template = store.addTemplate("Legs", "squat" to 2)
        val (squatRow) = store.exercisesForTemplate(template)
        store.templateExercises[squatRow.id] = squatRow.copy(alternatives = listOf("row"))
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        val squat = store.exercisesForWorkout(workoutId).first()
        assertTrue(session.swapExercise(workoutId, squat.id, "row"))
        assertTrue(session.swapExercise(workoutId, squat.id, "squat"))
        assertTrue(session.discardIfUntouched(workoutId), "nothing of his left in it")
    }

    @Test
    fun `a swap to a lift the workout already has reads last session's sets of it that no slot as planned reads`() {
        // Last session: a bench top set, then bench back-offs as a second slot.
        store.addFinishedWorkout(
            startedAt = 500_000,
            "bench" to listOf(Triple(n, 100.0, 3)),
            "bench" to listOf(Triple(n, 80.0, 8), Triple(n, 80.0, 8)),
        )
        val template = store.addTemplate("Push", "bench" to 1, "fly" to 2)
        val (_, flyRow) = store.exercisesForTemplate(template)
        store.templateExercises[flyRow.id] = flyRow.copy(alternatives = listOf("bench"))
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        val fly = store.exercisesForWorkout(workoutId)[1]
        assertTrue(session.swapExercise(workoutId, fly.id, "bench"))

        // The bench as planned reads last time's first bench slot; the swapped one the one left, the back-offs.
        val slots = store.exercisesForWorkout(workoutId)
        val lastTime = previousForSlots(slots, session.previousGroupsByExercise(slots.map { it.exerciseId }))
        assertEquals(listOf(100.0), lastTime.getValue(slots[0].id).map { it.weightKg })
        assertEquals(listOf(80.0, 80.0), lastTime.getValue(fly.id).map { it.weightKg })
    }

    @Test
    fun `a slot that did a lift removed since starts again on its first choice still in the picker`() {
        val template = store.addTemplate("Legs", "squat" to 2, "curl" to 2)
        val (squatRow) = store.exercisesForTemplate(template)
        store.templateExercises[squatRow.id] = squatRow.copy(alternatives = listOf("row", "fly"))
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        val (squat, curl) = store.exercisesForWorkout(workoutId)
        assertTrue(session.swapExercise(workoutId, squat.id, "row"))
        session.completeSet(workoutId, rowsOf(squat).first(), 60.0, 10, n, now = 1_060_000)
        session.completeSet(workoutId, rowsOf(curl).first(), 20.0, 10, n, now = 1_120_000)
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(workoutId, now = 1_200_000))
        store.exercises["row"] = store.exercises.getValue("row").copy(hidden = true)

        val again = assertNotNull(session.startFromWorkout(workoutId, now = 2_000_000))
        val copy = store.exercisesForWorkout(again).first()
        assertEquals("squat" to listOf("squat", "fly"), copy.exerciseId to copy.choices, "the main, not the slot dropped")
        assertEquals(listOf(n), rowsOf(copy).map { it.type }, "the pattern done that day")
        assertTrue(session.discardIfUntouched(again))
        val saved = assertNotNull(session.saveAsTemplate(workoutId, "Legs 2"))
        assertEquals("squat" to listOf("fly"), store.exercisesForTemplate(saved).first().let { it.exerciseId to it.alternatives })
    }

    @Test
    fun `a swapped superset member keeps the pair alternating and a deload's rows, which off still puts back`() {
        val template = store.addTemplate("Arms", "fly" to 3, "curl" to 3)
        val (flyRow, curlRow) = store.exercisesForTemplate(template)
        store.templateExercises[flyRow.id] = flyRow.copy(supersetGroup = 1, alternatives = listOf("row"))
        store.templateExercises[curlRow.id] = curlRow.copy(supersetGroup = 1, restSecWork = 90)
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        assertTrue(session.setDeload(workoutId, on = true))
        val (fly, curl) = store.exercisesForWorkout(workoutId)
        assertEquals(2, rowsOf(fly).size, "half of 3, rounded up")

        assertTrue(session.swapExercise(workoutId, fly.id, "row"))
        val swapped = store.exercisesForWorkout(workoutId).first()
        assertTrue(sameGrouping(listOf(1, 1), store.exercisesForWorkout(workoutId).map { it.supersetGroup }), "still a pair")
        assertEquals(2 to 3, rowsOf(swapped).size to swapped.targetSets, "the deload's rows; the plan kept")
        val a1 = assertNotNull(session.completeSet(workoutId, rowsOf(swapped)[0], 40.0, 10, n, now = 1_010_000))
        assertEquals(0, a1.restDurationSec, "the turn passes to the curl: no rest")
        val b1 = assertNotNull(session.completeSet(workoutId, rowsOf(curl)[0], 12.0, 10, n, now = 1_040_000))
        assertEquals(90, b1.restDurationSec, "the round's rest")
        assertTrue(session.setDeload(workoutId, on = false))
        assertEquals(3, rowsOf(swapped).size, "back up to the plan")
    }

    @Test
    fun `a swapped slot's last session skips a deload of that lift, as any slot's does`() {
        store.addFinishedWorkout(startedAt = 100_000, "row" to listOf(Triple(n, 60.0, 10)))
        val deload = store.addFinishedWorkout(startedAt = 200_000, "row" to listOf(Triple(n, 40.0, 10)))
        store.workouts[deload] = store.workoutById(deload)!!.copy(deload = true)
        val template = store.addTemplate("Back", "pullup" to 2)
        val (row) = store.exercisesForTemplate(template)
        store.templateExercises[row.id] = row.copy(alternatives = listOf("row"))
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        val slot = store.exercisesForWorkout(workoutId).single()
        assertTrue(session.swapExercise(workoutId, slot.id, "row"))

        val slots = store.exercisesForWorkout(workoutId)
        val lastTime = previousForSlots(slots, session.previousGroupsByExercise(slots.map { it.exerciseId }))
        assertEquals(listOf(60.0), lastTime.getValue(slot.id).map { it.weightKg }, "the normal session, not the deload")
    }

    @Test
    fun `Start Again keeps the exercise done and its choices, Save as Template makes it the main`() {
        val template = store.addTemplate("Legs", "squat" to 2, "curl" to 2)
        val (squatRow) = store.exercisesForTemplate(template)
        store.templateExercises[squatRow.id] = squatRow.copy(alternatives = listOf("row", "fly", "pullup"))
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        val (squat, curl) = store.exercisesForWorkout(workoutId)
        assertTrue(session.swapExercise(workoutId, squat.id, "row"))
        session.completeSet(workoutId, rowsOf(squat).first(), 60.0, 10, n, now = 1_060_000)
        session.completeSet(workoutId, rowsOf(curl).first(), 20.0, 10, n, now = 1_120_000)
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(workoutId, now = 1_200_000))
        // He removes the pull-up since: it comes back nowhere, as a choice neither.
        store.exercises["pullup"] = store.exercises.getValue("pullup").copy(hidden = true)

        val again = assertNotNull(session.startFromWorkout(workoutId, now = 2_000_000))
        val copy = store.exercisesForWorkout(again).first()
        assertEquals("row" to listOf("squat", "row", "fly"), copy.exerciseId to copy.choices, "the template's plan, main first")
        assertTrue(session.discardIfUntouched(again), "a copy as Start Again made it")

        val saved = assertNotNull(session.saveAsTemplate(workoutId, "Legs 2"))
        val main = store.exercisesForTemplate(saved).first()
        assertEquals("row" to listOf("squat", "fly"), main.exerciseId to main.alternatives)
        assertEquals(emptyList(), store.exercisesForTemplate(saved)[1].alternatives)
    }

    @Test
    fun `swapping one of two slots of a lift leaves the other's PREVIOUS, this week and next, as it was`() {
        store.addFinishedWorkout(
            startedAt = 500_000,
            "bench" to listOf(Triple(n, 100.0, 3)),
            "bench" to listOf(Triple(n, 80.0, 8), Triple(n, 80.0, 8)),
        )
        val template = store.addTemplate("Push", "bench" to 1, "bench" to 2)
        val (topRow) = store.exercisesForTemplate(template)
        store.templateExercises[topRow.id] = topRow.copy(alternatives = listOf("fly"))
        fun lastTime(workoutId: Long) = store.exercisesForWorkout(workoutId).let { slots ->
            previousForSlots(slots, session.previousGroupsByExercise(slots.map { it.exerciseId }))
        }
        val workoutId = session.startFromTemplate(template, now = 1_000_000)
        val (top, backOffs) = store.exercisesForWorkout(workoutId)
        assertTrue(session.swapExercise(workoutId, top.id, "fly"))
        assertEquals(listOf(80.0, 80.0), lastTime(workoutId).getValue(backOffs.id).map { it.weightKg }, "the back-offs, not the top set")

        session.completeSet(workoutId, rowsOf(top).first(), 20.0, 12, n, now = 1_060_000)
        rowsOf(backOffs).forEachIndexed { index, row -> session.completeSet(workoutId, row, 82.5, 8, n, now = 1_120_000 + index * 60_000L) }
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(workoutId, now = 1_300_000))
        val next = session.startFromTemplate(template, now = 2_000_000)
        val (nextTop, nextBackOffs) = store.exercisesForWorkout(next)
        val previous = lastTime(next)
        assertEquals(emptyList(), previous.getValue(nextTop.id), "the top set wasn't done last time: nothing, not the back-offs")
        assertEquals(listOf(82.5, 82.5), previous.getValue(nextBackOffs.id).map { it.weightKg })
    }

    @Test
    fun `a Start Again copy of a swapped slot reads PREVIOUS as its source did, and so does the next start`() {
        store.addFinishedWorkout(
            startedAt = 500_000,
            "bench" to listOf(Triple(n, 100.0, 3)),
            "bench" to listOf(Triple(n, 80.0, 8), Triple(n, 80.0, 8)),
        )
        val template = store.addTemplate("Push", "bench" to 1, "bench" to 2)
        val (topRow) = store.exercisesForTemplate(template)
        store.templateExercises[topRow.id] = topRow.copy(alternatives = listOf("fly"))
        fun lastTime(workoutId: Long) = store.exercisesForWorkout(workoutId).let { slots ->
            previousForSlots(slots, session.previousGroupsByExercise(slots.map { it.exerciseId }))
        }
        fun doAll(workoutId: Long, backOffKg: Double, at: Long) {
            val (top, backOffs) = store.exercisesForWorkout(workoutId)
            session.completeSet(workoutId, rowsOf(top).first(), 20.0, 12, n, now = at)
            rowsOf(backOffs).forEachIndexed { index, row -> session.completeSet(workoutId, row, backOffKg, 8, n, now = at + (index + 1) * 60_000L) }
            assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(workoutId, now = at + 300_000))
        }
        val week1 = session.startFromTemplate(template, now = 1_000_000)
        assertTrue(session.swapExercise(week1, store.exercisesForWorkout(week1).first().id, "fly"))
        doAll(week1, 82.5, at = 1_060_000)

        val copy = assertNotNull(session.startFromWorkout(week1, now = 2_000_000))
        val (_, copyBackOffs) = store.exercisesForWorkout(copy)
        assertEquals(listOf(82.5, 82.5), lastTime(copy).getValue(copyBackOffs.id).map { it.weightKg }, "the back-offs, not the top set's place")
        doAll(copy, 85.0, at = 2_060_000)
        val next = session.startFromTemplate(template, now = 3_000_000)
        val (nextTop, nextBackOffs) = store.exercisesForWorkout(next)
        assertEquals(emptyList(), lastTime(next).getValue(nextTop.id), "the top set wasn't done in the copy either")
        assertEquals(listOf(85.0, 85.0), lastTime(next).getValue(nextBackOffs.id).map { it.weightKg })
    }

    @Test
    fun `a Start Again copy of a slot swapped to a lift the workout has keeps each slot's own sets`() {
        val template = store.addTemplate("Legs", "squat" to 1, "row" to 1)
        val (squatRow) = store.exercisesForTemplate(template)
        store.templateExercises[squatRow.id] = squatRow.copy(alternatives = listOf("row"))
        val source = session.startFromTemplate(template, now = 1_000_000)
        val (swapped, own) = store.exercisesForWorkout(source)
        assertTrue(session.swapExercise(source, swapped.id, "row"))
        session.completeSet(source, rowsOf(swapped).first(), 40.0, 10, n, now = 1_060_000)
        session.completeSet(source, rowsOf(own).first(), 62.5, 10, n, now = 1_120_000)
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(source, now = 1_200_000))

        val copy = assertNotNull(session.startFromWorkout(source, now = 2_000_000))
        val (copySwapped, copyOwn) = store.exercisesForWorkout(copy)
        val slots = store.exercisesForWorkout(copy)
        val lastTime = previousForSlots(slots, session.previousGroupsByExercise(slots.map { it.exerciseId }))
        assertEquals(listOf(40.0), lastTime.getValue(copySwapped.id).map { it.weightKg }, "the swapped slot's own, not crossed")
        assertEquals(listOf(62.5), lastTime.getValue(copyOwn.id).map { it.weightKg })
        // Done as copied, then the template as planned: the row slot reads the copy's row slot.
        session.completeSet(copy, rowsOf(copySwapped).first(), 42.5, 10, n, now = 2_060_000)
        session.completeSet(copy, rowsOf(copyOwn).first(), 65.0, 10, n, now = 2_120_000)
        assertEquals(FinishOutcome.ENDED, session.finishOrDiscard(copy, now = 2_200_000))
        val next = session.startFromTemplate(template, now = 3_000_000)
        val nextSlots = store.exercisesForWorkout(next)
        val nextLast = previousForSlots(nextSlots, session.previousGroupsByExercise(nextSlots.map { it.exerciseId }))
        assertEquals(emptyList(), nextLast.getValue(nextSlots[0].id), "the squat wasn't done")
        assertEquals(listOf(65.0), nextLast.getValue(nextSlots[1].id).map { it.weightKg })
    }

    @Test
    fun `version 2's columns ride along unchanged through a whole workout`() {
        // Set as the v2 features set them (supersets, RIR ranges, deload, alternatives); no write may drop them.
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
