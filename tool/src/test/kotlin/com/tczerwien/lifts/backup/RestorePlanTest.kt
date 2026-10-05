package com.tczerwien.lifts.backup

import com.tczerwien.lifts.data.BarEntity
import com.tczerwien.lifts.data.BodyweightEntity
import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.LiftsRows
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.TemplateEntity
import com.tczerwien.lifts.data.TemplateExerciseEntity
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import com.tczerwien.lifts.seed.Seeder
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestorePlanTest {

    private val utc = ZoneId.of("UTC")

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun exercise(id: String, custom: Boolean = false) = ExerciseEntity(
        id = id, name = id, equipment = null, primaryMuscles = emptyList(), secondaryMuscles = emptyList(),
        category = "strength", level = null, instructions = emptyList(), isCustom = custom,
    )

    /** Two finished workouts, one finished with nothing ticked, and one still open. */
    private val rows = LiftsRows(
        exercises = listOf(exercise("bench"), exercise("squat"), exercise("custom-1", custom = true)),
        workouts = listOf(
            WorkoutEntity(id = 1, name = "Upper A", startedAt = millis("2026-07-31T18:00:00Z"), endedAt = millis("2026-07-31T19:00:00Z")),
            WorkoutEntity(id = 2, name = "Lower A", startedAt = millis("2026-09-25T18:00:00Z"), endedAt = millis("2026-09-25T19:00:00Z")),
            WorkoutEntity(id = 3, name = "Oops", startedAt = millis("2026-09-26T18:00:00Z"), endedAt = millis("2026-09-26T18:01:00Z")),
            WorkoutEntity(id = 4, name = "Upper A", startedAt = millis("2026-09-28T07:00:00Z")),
        ),
        workoutExercises = listOf(
            WorkoutExerciseEntity(id = 10, workoutId = 1, exerciseId = "bench", position = 0),
            WorkoutExerciseEntity(id = 11, workoutId = 2, exerciseId = "squat", position = 0),
            WorkoutExerciseEntity(id = 12, workoutId = 3, exerciseId = "bench", position = 0),
            WorkoutExerciseEntity(id = 13, workoutId = 4, exerciseId = "custom-1", position = 0),
        ),
        sets = listOf(
            SetEntity(id = 100, workoutExerciseId = 10, position = 0, type = "normal", weightKg = 100.0, reps = 5, completedAt = 1),
            SetEntity(id = 101, workoutExerciseId = 10, position = 1, type = "normal", weightKg = 100.0, reps = 5),
            SetEntity(id = 102, workoutExerciseId = 11, position = 0, type = "normal", weightKg = 140.0, reps = 3, completedAt = 2),
            SetEntity(id = 103, workoutExerciseId = 12, position = 0, type = "normal", weightKg = 60.0, reps = 8),
            SetEntity(id = 104, workoutExerciseId = 13, position = 0, type = "normal", reps = 10, completedAt = 3),
        ),
        templates = listOf(TemplateEntity(id = 1, name = "Upper A", position = 0)),
        templateExercises = listOf(TemplateExerciseEntity(id = 1, templateId = 1, exerciseId = "bench", position = 0, targetSets = 3)),
        bodyweight = listOf(BodyweightEntity(id = 1, epochDay = 20000, weightKg = 82.0), BodyweightEntity(id = 2, epochDay = 20003, weightKg = 81.5)),
        bars = listOf(BarEntity(id = 1, name = "Olympic Bar", weightKg = 20.4117, position = 0)),
    )

    @Test
    fun `the summary counts what History would show`() {
        val summary = RestorePlan.summarize(rows)
        assertEquals(2, summary.workouts, "a finished workout with nothing ticked and an open one don't count")
        assertEquals(2, summary.loggedSets, "unticked sets never count, nor the open workout's ticked one")
        assertEquals(1, summary.customExercises)
        assertEquals("Lower A", summary.lastWorkout?.name)
        assertEquals(4L, summary.openWorkout?.id)
        assertEquals(millis("2026-07-31T18:00:00Z"), summary.firstWorkoutAt)
    }

    @Test
    fun `the summary reads as short lines`() {
        assertEquals(
            listOf(
                "2 workouts · 2 sets",
                "Jul 31, 2026 – Sep 25, 2026",
                "last: Lower A · Sep 25",
                "1 template · 1 custom exercise · 2 weigh-ins",
                "in progress: Upper A · Sep 28",
            ),
            RestorePlan.lines(RestorePlan.summarize(rows), utc, LocalDate.of(2026, 9, 29)),
        )
    }

    @Test
    fun `an empty log still describes itself`() {
        val empty = rows.copy(workouts = emptyList(), workoutExercises = emptyList(), sets = emptyList(), templates = emptyList(), templateExercises = emptyList(), bodyweight = emptyList())
        assertEquals(
            listOf("0 workouts · 0 sets", "0 templates · 1 custom exercise · 0 weigh-ins"),
            RestorePlan.lines(RestorePlan.summarize(empty), utc, LocalDate.of(2026, 9, 29)),
        )
    }

    @Test
    fun `rows Lifts wrote have no problems`() {
        assertEquals(emptyList(), RestorePlan.problems(rows))
    }

    @Test
    fun `broken references are reported, one line each`() {
        val broken = rows.copy(
            workoutExercises = rows.workoutExercises + WorkoutExerciseEntity(id = 14, workoutId = 99, exerciseId = "gone", position = 0),
            sets = rows.sets + SetEntity(id = 105, workoutExerciseId = 98, position = 0, type = "normal"),
            templateExercises = rows.templateExercises + TemplateExerciseEntity(id = 2, templateId = 97, exerciseId = "bench", position = 0, targetSets = 3),
            bars = rows.bars + BarEntity(id = 0, name = "Sled", weightKg = 40.0, position = 1),
        )
        assertEquals(
            listOf(
                "exercise slots with no workout: 1",
                "sets with no exercise slot: 1",
                "template rows with no template: 1",
                "rows with no id: 1",
            ),
            RestorePlan.problems(broken),
        )
    }

    @Test
    fun `a slot or template row naming a missing exercise doesn't refuse the log`() {
        val orphan = rows.copy(
            workoutExercises = rows.workoutExercises + WorkoutExerciseEntity(id = 14, workoutId = 1, exerciseId = "custom-gone", position = 1),
            templateExercises = rows.templateExercises + TemplateExerciseEntity(id = 2, templateId = 1, exerciseId = "custom-gone", position = 1, targetSets = 3),
        )
        assertEquals(emptyList(), RestorePlan.problems(orphan))
    }

    @Test
    fun `alternatives and choices naming a missing exercise don't refuse the log`() {
        // The swap skips such a choice (AlternativesTest), so the rest of the log restores as it is (ALT-7).
        val orphan = rows.copy(
            workoutExercises = rows.workoutExercises.map { if (it.id == 13L) it.copy(choices = listOf("custom-1", "custom-gone")) else it },
            templateExercises = rows.templateExercises.map { it.copy(alternatives = listOf("custom-gone", "squat")) },
        )
        assertEquals(emptyList(), RestorePlan.problems(orphan))
    }

    @Test
    fun `a file without the exercise catalog is refused`() {
        val bare = rows.copy(exercises = emptyList())
        assertEquals("it has no exercises at all", RestorePlan.problems(bare).first())
    }

    @Test
    fun `types are refitted only for a backup from before B9`() {
        assertTrue(RestorePlan.refitsTypes(mapOf("lifts_units" to "lb")), "settings without the B9 weight step")
        assertFalse(RestorePlan.refitsTypes(mapOf("lifts_units" to "lb", SettingsStore.KEY_WEIGHT_STEP_LB to "5.0")))
        assertFalse(RestorePlan.refitsTypes(emptyMap()), "no settings at all: a pulled database, age unknown")
    }

    @Test
    fun `the rows naming the Lifts that wrote a backup change nothing a restore decides from its settings`() {
        val bars = listOf(Seeder.defaultBar().copy(id = 1))
        val made = MadeBy.rows(19, "0.19.0")
        listOf(
            mapOf("lifts_units" to "kg"),
            mapOf("lifts_units" to "kg", SettingsStore.KEY_WEIGHT_STEP_LB to "5.0", SettingsStore.KEY_DELOAD_KEEP_PCT to "50"),
            mapOf(
                "lifts_units" to "kg",
                SettingsStore.KEY_WEIGHT_STEP_LB to "5.0",
                SettingsStore.KEY_DELOAD_KEEP_PCT to "50",
                SettingsStore.KEY_MUSCLE_TARGETS to "{}",
            ),
        ).forEach { stored ->
            assertEquals(RestorePlan.refitsTypes(stored), RestorePlan.refitsTypes(stored + made), "$stored")
            assertEquals(RestorePlan.predatesTargets(stored), RestorePlan.predatesTargets(stored + made), "$stored")
            assertEquals(RestorePlan.settingsLine(stored), RestorePlan.settingsLine(stored + made), "$stored")
            assertEquals(RestorePlan.barsToFit(bars, stored, WeightUnit.KG), RestorePlan.barsToFit(bars, stored + made, WeightUnit.KG), "$stored")
        }
    }

    @Test
    fun `a backup from before B9 restored in kg gets the 20 kg bar, as the upgrade gave the phone`() {
        val seeded = Seeder.defaultBar().copy(id = 1)
        val his = BarEntity(id = 2, name = "EZ Bar", weightKg = Format.lbToKg(25.0), position = 1)
        val preB9 = mapOf("lifts_units" to "kg")
        assertEquals(listOf(seeded.copy(weightKg = 20.0)), RestorePlan.barsToFit(listOf(seeded, his), preB9, WeightUnit.KG))
        assertEquals(emptyList(), RestorePlan.barsToFit(listOf(seeded), preB9, WeightUnit.LB), "in lb the 45 lb bar is right")
        val b9 = mapOf("lifts_units" to "kg", SettingsStore.KEY_WEIGHT_STEP_LB to "5.0")
        assertEquals(emptyList(), RestorePlan.barsToFit(listOf(seeded), b9, WeightUnit.KG), "a later backup's bar is as he left it")
        assertEquals(emptyList(), RestorePlan.barsToFit(listOf(seeded), emptyMap(), WeightUnit.KG), "a pulled database: age unknown")
    }

    @Test
    fun `a file Lifts can't read is refused in one short line`() {
        // What Room throws when an older file's tables, migrated, aren't what this version expects.
        val room = IllegalStateException(
            "Migration didn't properly handle: bars(com.tczerwien.lifts.data.BarEntity).\n Expected:\n" +
                "TableInfo{name='bars', columns={id=Column{name='id', type='INTEGER'}}}\n Found:\nTableInfo{name='bars', columns={}}",
        )
        assertEquals("Lifts can't read it (its tables don't match a Lifts log)", RestorePlan.unreadable(room))
        val hash = IllegalStateException("Room cannot verify the data integrity. Looks like you've changed schema but forgot to update the version number.")
        assertEquals("Lifts can't read it (its tables don't match a Lifts log)", RestorePlan.unreadable(hash))
        // Any other IllegalStateException is no table problem: it keeps its own words.
        val other = IllegalStateException("Cannot access database on the main thread since it may potentially lock the UI.")
        assertEquals("Lifts can't read it (Cannot access database on the main thread since it may potentially lock the UI.)", RestorePlan.unreadable(other))

        val json = IllegalArgumentException("Unexpected JSON token at offset 0: Expected start of the array '['\nJSON input: chest")
        assertEquals("Lifts can't read it (Unexpected JSON token at offset 0: Expected start of the array '[')", RestorePlan.unreadable(json))

        val long = RuntimeException("x".repeat(500))
        assertEquals("Lifts can't read it (${"x".repeat(100)}…)", RestorePlan.unreadable(long))
        assertEquals("Lifts can't read it (${"x".repeat(100)})", RestorePlan.unreadable(RuntimeException("x".repeat(100))))
        assertEquals("Lifts can't read it (first words)", RestorePlan.unreadable(RuntimeException("\n  \nfirst words\nmore")))
        assertEquals("Lifts can't read it (java.lang.RuntimeException)", RestorePlan.unreadable(RuntimeException()))
        assertEquals("Lifts can't read it (java.lang.RuntimeException)", RestorePlan.unreadable(RuntimeException(" \n ")))
        // A character outside the BMP is a surrogate pair (two UTF-16 units): a cut never splits one.
        val across = "x".repeat(99) + "\uD83C\uDFCB" + "x"
        assertEquals("Lifts can't read it (${"x".repeat(99)}…)", RestorePlan.unreadable(RuntimeException(across)))
        val before = "x".repeat(98) + "\uD83C\uDFCB" + "x"
        assertEquals("Lifts can't read it (${"x".repeat(98)}\uD83C\uDFCB…)", RestorePlan.unreadable(RuntimeException(before)))
    }

    @Test
    fun `a backup from before weekly targets keeps the phone's Counts For, a later one brings its own`() {
        val older = mapOf(SettingsStore.KEY_UNITS to "LB", SettingsStore.KEY_WEIGHT_STEP_LB to "5.0")
        val newer = older + (SettingsStore.KEY_MUSCLE_TARGETS to "{}")
        assertTrue(RestorePlan.predatesTargets(older))
        assertTrue(RestorePlan.predatesTargets(emptyMap()), "a file with no settings is treated so too: its own Counts For stay, the phone's fill the rest")
        assertFalse(RestorePlan.predatesTargets(newer))

        val phone = mapOf("bench" to listOf("chest", "front delts"), "gone" to listOf("lats"), "squat" to listOf("quadriceps"))
        val restored = listOf(
            exercise("bench"), exercise("squat").copy(countsFor = listOf("glutes")), exercise("custom-1", custom = true),
            exercise("pulldown").copy(countsFor = listOf("  ")),
        )
        val phoneWithPulldown = phone + ("pulldown" to listOf("lats"))
        assertEquals(
            listOf(listOf("chest", "front delts"), listOf("glutes"), emptyList(), listOf("lats")),
            RestorePlan.keptCountsFor(restored, phoneWithPulldown).map { it.countsFor },
            "the phone's go where the backup has none (a blank one is none); one the backup holds stays its; an exercise it lacks stays out",
        )
        assertEquals(restored.map { it.id }, RestorePlan.keptCountsFor(restored, phoneWithPulldown).map { it.id })
    }

    @Test
    fun `the Restore screen says what of the settings comes back`() {
        assertEquals("no settings in it: this phone's settings stay, and its Counts For where the file has none", RestorePlan.settingsLine(emptyMap()))
        val beforeB14 = mapOf(SettingsStore.KEY_UNITS to "LB")
        val b14to17 = beforeB14 + (SettingsStore.KEY_DELOAD_KEEP_PCT to "50")
        assertEquals(
            "and its settings: units, rests, plates, beep (this phone's deload settings, weekly targets and Counts For stay)",
            RestorePlan.settingsLine(beforeB14),
        )
        assertEquals(
            "and its settings: units, rests, plates, beep, deload (this phone's weekly targets and Counts For stay)",
            RestorePlan.settingsLine(b14to17),
        )
        assertEquals(
            "and its settings: units, rests, plates, beep, deload, weekly targets",
            RestorePlan.settingsLine(b14to17 + (SettingsStore.KEY_MUSCLE_TARGETS to "{}")),
        )
        // Only a hand-made file has targets without deload rows (Lifts writes both since 0.18.0).
        assertEquals(
            "and its settings: units, rests, plates, beep, weekly targets (this phone's deload settings stay)",
            RestorePlan.settingsLine(beforeB14 + (SettingsStore.KEY_MUSCLE_TARGETS to "{}")),
        )
    }
}
