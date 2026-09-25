package com.tczerwien.lifts.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import com.tczerwien.lifts.session.ActiveSession.FinishOutcome
import com.tczerwien.lifts.settings.SettingsStore
import java.io.File
import java.nio.file.Files
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

    private fun newSession() = ActiveSession(store, SettingsStore(dataStore), dataStore)

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
        val first = session.addExercise(workoutId, "bench")
        val second = session.addExercise(workoutId, "bench")
        val third = session.addExercise(workoutId, "bench")
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
        val top = session.addExercise(workoutId, "bench")
        val backOff = session.addExercise(workoutId, "bench")
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
        val completion = session.completeSet(workoutId, store.setById(setId)!!, 40.0, 10, w, now = 10_000)
        assertEquals(45, completion.restDurationSec)
        assertEquals(55_000L, completion.restEndMillis)

        // The process dies: a new DataStore and a new session read the same saved end.
        scopes.forEach { it.cancel() }
        dataStore = openDataStore()
        session = newSession()
        assertEquals(ActiveSession.RestTimerState(endMillis = 55_000, totalSec = 45), session.restStateSnapshot())
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
        val slotId = session.addExercise(workoutId, "bench")
        val (first, _, third) = store.setsForWorkout(workoutId)
        session.removeSet(first.id)
        // The screen still holds the third row at its old position 2 when a stepper tap saves it.
        session.updateSet(third.copy(weightKg = 50.0))
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
        session.addExercise(workoutId, "bench")
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
        val slotId = session.addExercise(workoutId, "bench")
        val first = store.setsForWorkout(workoutId).single()
        val secondId = session.addSet(workoutId, slotId)
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
    }

    // --- Home's cleanup ---

    @Test
    fun `Home deletes an untouched workout and closes a stale one at its last set`() {
        val stale = session.startEmpty(now = 0)
        val slotId = store.addWorkoutExercise(WorkoutExerciseEntity(workoutId = stale, exerciseId = "bench", position = 0))
        val setId = store.addSet(SetEntity(workoutExerciseId = slotId, position = 0, type = n))
        session.completeSet(stale, store.setById(setId)!!, 60.0, 5, n, now = 600_000)
        val untouched = session.startEmpty(now = 20_000_000)

        assertNull(session.tidyOpenWorkouts(now = 20_060_000))
        assertNull(store.workoutById(untouched))
        assertEquals(600_000L, store.workoutById(stale)?.endedAt)
    }

    private fun rowsOf(slot: WorkoutExerciseEntity): List<SetEntity> =
        store.setsForWorkout(slot.workoutId).filter { it.workoutExerciseId == slot.id }
}
