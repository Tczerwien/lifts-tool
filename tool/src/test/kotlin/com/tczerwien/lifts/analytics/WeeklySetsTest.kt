package com.tczerwien.lifts.analytics

import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** WS-1: the weekly rule the Sets per Muscle screen and the exports share. Generic lifts throughout. */
class WeeklySetsTest {

    private val zone = ZoneId.of("America/Chicago")

    /** Wednesday 2026-07-15 noon, local: this week starts Monday 07-13, last week 07-06. */
    private val today = local("2026-07-15T12:00")

    private fun local(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun exercise(id: String, primary: String, secondary: List<String> = emptyList(), countsFor: List<String> = emptyList()) =
        ExerciseEntity(
            id = id, name = id, equipment = null, primaryMuscles = listOf(primary), secondaryMuscles = secondary,
            category = "strength", level = null, instructions = emptyList(), countsFor = countsFor,
        )

    private val dips = exercise("dips", primary = "triceps", secondary = listOf("chest", "shoulders"))
    private val frontRaise = exercise("front-raise", primary = "shoulders", countsFor = listOf("front delts"))
    private val pushUp = exercise("push-up", primary = "chest", secondary = listOf("triceps"), countsFor = listOf("chest", "triceps", "chest"))

    /** A small log: each slot is (exercise, its sets' (type, ticked) in position order); one workout per call. */
    private class Log {
        val workouts = mutableListOf<WorkoutEntity>()
        val slots = mutableListOf<WorkoutExerciseEntity>()
        val sets = mutableListOf<SetEntity>()

        fun workout(startedAt: Long, finished: Boolean = true, deload: Boolean = false, vararg slots: Pair<String, List<Pair<String, Boolean>>>) {
            val workoutId = workouts.size + 1L
            workouts += WorkoutEntity(
                id = workoutId, name = "W$workoutId", startedAt = startedAt,
                endedAt = if (finished) startedAt + 3_600_000 else null, deload = deload,
            )
            slots.forEachIndexed { position, (exerciseId, rows) ->
                val slotId = this.slots.size + 1L
                this.slots += WorkoutExerciseEntity(id = slotId, workoutId = workoutId, exerciseId = exerciseId, position = position)
                rows.forEachIndexed { index, (type, ticked) ->
                    sets += SetEntity(
                        id = sets.size + 1L, workoutExerciseId = slotId, position = index, type = type, reps = 10,
                        completedAt = if (ticked) startedAt + 60_000L * (index + 1) else null,
                    )
                }
            }
        }
    }

    private fun work(n: Int) = List(n) { "normal" to true }

    private fun weekly(log: Log, exercises: List<ExerciseEntity> = listOf(dips, frontRaise, pushUp), weeks: Int = 2) =
        weeklySetsPerMuscle(exercises, log.workouts, log.slots, log.sets, weeks, today, zone)

    @Test
    fun `Counts For wins, empty means the primary, and secondary muscles add nothing`() {
        val log = Log().apply {
            workout(local("2026-07-13T18:00"), slots = arrayOf("dips" to work(3), "front-raise" to work(2), "push-up" to work(4)))
        }
        assertEquals(
            mapOf(
                // Dips: no Counts For, so its primary; chest and shoulders are only secondary.
                // Push-ups: chest twice in its list counts once.
                "chest" to listOf(0, 4),
                "front delts" to listOf(0, 2),
                "triceps" to listOf(0, 3 + 4),
            ),
            weekly(log),
        )
        assertEquals(listOf("chest", "front delts", "triceps"), weekly(log).keys.toList(), "sorted A to Z")
        assertEquals(listOf("front delts"), countsFor(frontRaise), "the shoulders it was in the catalog are not counted")
        assertEquals(listOf("chest", "triceps"), countsFor(pushUp))
        assertEquals(listOf("triceps"), countsFor(dips))
        // A hand-made file's spelling is the same muscle; a blank one is none, so the primary counts.
        assertEquals(listOf("side delts"), countsFor(listOf("Side Delts ", "side delts", " "), listOf("shoulders")))
        assertEquals(listOf("shoulders"), countsFor(listOf(" "), listOf("shoulders")))
    }

    @Test
    fun `a drop adds nothing, as part of the set above it, and a warm-up nothing`() {
        assertEquals(2, hardSets(listOf("warmup", "normal", "drop", "normal", "drop", "drop")))
        assertEquals(3, hardSets(listOf("normal", "failure", "normal")), "a set to failure is a hard set")
        // A drop with no work set above it is a set of its own (DR-1): the first row, or one under a warm-up.
        assertEquals(2, hardSets(listOf("drop", "normal")))
        assertEquals(1, hardSets(listOf("warmup", "drop")))
        assertEquals(0, hardSets(listOf("warmup", "warmup")))

        val log = Log().apply {
            workout(local("2026-07-14T18:00"), slots = arrayOf("dips" to listOf("warmup" to true, "normal" to true, "drop" to true, "drop" to true, "normal" to true)))
        }
        assertEquals(mapOf("triceps" to listOf(0, 2)), weekly(log))
    }

    @Test
    fun `a drop's kind comes from the ticked rows, as PREVIOUS and Start Again read them`() {
        val log = Log().apply {
            workout(
                local("2026-07-14T18:00"),
                slots = arrayOf(
                    // The set above the drop was never ticked: the drop logged alone is a set of its own.
                    "dips" to listOf("normal" to false, "drop" to true),
                    // Read from the ticked rows, this drop goes with the logged set: one hard set, as Start Again copies it.
                    "dips" to listOf("normal" to true, "normal" to false, "drop" to true),
                ),
            )
        }
        assertEquals(mapOf("triceps" to listOf(0, 1 + 1)), weekly(log))
        assertEquals(
            listOf(listOf("drop"), listOf("normal", "drop")),
            countedSlots(listOf(dips), log.workouts, log.slots, log.sets).map { it.tickedTypes },
        )
    }

    @Test
    fun `rows are read in position order, whatever their ids`() {
        // Drop Set inserts its row under the set, so a drop can have a higher id than the rows below it; here the
        // first row's id is the higher one. In position order the drop comes first: a set of its own, then the set.
        val log = Log().apply { workout(local("2026-07-14T18:00"), slots = arrayOf("dips" to work(1))) }
        val slotId = log.slots.single().id
        val sets = listOf(
            SetEntity(id = 2, workoutExerciseId = slotId, position = 0, type = "drop", reps = 8, completedAt = today - 1),
            SetEntity(id = 1, workoutExerciseId = slotId, position = 1, type = "normal", reps = 8, completedAt = today - 1),
        )
        assertEquals(mapOf("triceps" to listOf(0, 2)), weeklySetsPerMuscle(listOf(dips), log.workouts, log.slots, sets, 2, today, zone))
    }

    @Test
    fun `unticked sets and unfinished workouts count nowhere`() {
        val log = Log().apply {
            workout(local("2026-07-13T18:00"), slots = arrayOf("dips" to listOf("normal" to true, "normal" to false, "normal" to false)))
            workout(local("2026-07-14T18:00"), finished = false, slots = arrayOf("dips" to work(3), "push-up" to work(3)))
            workout(local("2026-07-15T07:00"), slots = arrayOf("push-up" to listOf("normal" to false)))
        }
        assertEquals(mapOf("triceps" to listOf(0, 1)), weekly(log))
        assertEquals(1, countedSlots(listOf(dips, pushUp), log.workouts, log.slots, log.sets).size, "only a finished workout's slot with a tick")
    }

    @Test
    fun `a deload's sets count, and its week says so`() {
        val log = Log().apply {
            workout(local("2026-07-07T18:00"), deload = true, slots = arrayOf("dips" to work(2)))
            workout(local("2026-07-09T18:00"), slots = arrayOf("dips" to work(3)))
            workout(local("2026-07-14T18:00"), slots = arrayOf("dips" to work(3)))
        }
        assertEquals(mapOf("triceps" to listOf(2 + 3, 3)), weekly(log))
        val slots = countedSlots(listOf(dips), log.workouts, log.slots, log.sets)
        assertEquals(listOf(true, false), deloadWeeks(slots, 2, today, zone))
        // Four weeks ending Tuesday 07-28 start Monday 07-06: the deload's week is the first.
        assertEquals(listOf(true, false, false, false), deloadWeeks(slots, 4, local("2026-07-28T09:00"), zone))
    }

    @Test
    fun `weeks are the phone's Monday weeks, by the workout's start`() {
        val log = Log().apply {
            // Monday 00:00 local starts this week; Sunday 23:59 local is still last week (UTC would say Monday).
            workout(local("2026-07-13T00:00"), slots = arrayOf("dips" to work(1)))
            workout(local("2026-07-12T23:59"), slots = arrayOf("dips" to work(2)))
            // The Sunday before last is outside the two weeks; next Monday (a clock set wrong) is ahead of them.
            workout(local("2026-07-05T23:59"), slots = arrayOf("dips" to work(4)))
            workout(local("2026-07-20T00:00"), slots = arrayOf("dips" to work(8)))
            // A Sunday-night workout ticked after midnight counts on Sunday, where History lists it.
            workout(local("2026-07-12T23:50"), slots = arrayOf("push-up" to work(1)))
        }
        assertEquals(mapOf("chest" to listOf(1, 0), "triceps" to listOf(2 + 1, 1)), weekly(log))
        assertEquals(listOf(4, 2 + 1, 1), weekly(log, weeks = 3).getValue("triceps"))
    }

    @Test
    fun `a set counts for the lift done, so a swap counts the alternative`() {
        val log = Log().apply { workout(local("2026-07-14T18:00"), slots = arrayOf("front-raise" to work(3))) }
        // The slot was planned as dips (its main) and swapped to the front raise.
        val swapped = log.slots.map { it.copy(choices = listOf("dips", "front-raise")) }
        assertEquals(mapOf("front delts" to listOf(0, 3)), weeklySetsPerMuscle(listOf(dips, frontRaise), log.workouts, swapped, log.sets, 2, today, zone))
    }

    @Test
    fun `an exercise no longer in the log counts for nothing`() {
        val log = Log().apply { workout(local("2026-07-14T18:00"), slots = arrayOf("gone" to work(3), "dips" to work(1))) }
        assertEquals(mapOf("triceps" to listOf(0, 1)), weekly(log))
    }
}
