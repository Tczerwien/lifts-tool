package com.tczerwien.lifts.export

import com.tczerwien.lifts.data.BodyweightEntity
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownExportTest {

    private val markdown = MarkdownExport.render(ExportFixtures.snapshot())
    private val lines = markdown.split("\n")

    @Test
    fun frontMatterIsFirstAndComplete() {
        assertTrue(markdown.startsWith("---\n"), "front matter must open the file")
        assertContains(lines, "schema_version: \"3.0\"")
        assertContains(lines, "exported_at: ${ExportFixtures.EXPORTED_AT}")
        assertContains(lines, "units:")
        assertContains(lines, "  weight: kg")
        assertContains(lines, "athlete:")
        assertContains(lines, "  goals: ${ExportFixtures.GOALS}")
        assertContains(lines, "  injuries:")
        assertContains(lines, "    - left knee")
    }

    @Test
    fun frontMatterClosesBeforeTheTitle() {
        // The opening "---" alone must not satisfy this (#124): a front matter that never closes swallows
        // the whole file for YAML readers.
        val close = lines.drop(1).indexOf("---") + 1
        val title = lines.indexOfFirst { it.startsWith("# Training Log") }
        assertTrue(close in 1 until title, "closing --- at $close, title at $title")
    }

    @Test
    fun titleSpansDateRangeAndCount() {
        assertContains(lines, "# Training Log — 2026-07-06 to 2026-07-13 (2 workouts)")
    }

    @Test
    fun summaryHasPrsAndWeeklyVolume() {
        assertContains(lines, "## Summary")
        // 102.5 * (1 + 5/30) = 119.583… -> 119.5 on the 0.5 kg grid the screens use.
        assertContains(lines, "- Bench Press (Barbell): e1RM 119.5 kg (2026-07-13) — best set 102.5x5")
        // Best e1RM per 28-day block, the last ending the export day (#022).
        assertContains(lines, "### e1RM trend (best e1RM per 28-day block, kg, oldest → newest)")
        assertContains(lines, "Blocks start 2026-01-28 … 2026-06-17; the last ends today. — means no set of 1–12 reps in that block.")
        assertContains(lines, "- Bench Press (Barbell): —, —, —, —, —, 119.5")
        // Labelled: which way the series runs, which weeks, and that the last one is partial (#069).
        assertContains(lines, "### Weekly sets per muscle (last 8 weeks, oldest → newest)")
        assertContains(lines, "Weeks start Monday 2026-05-25 … 2026-07-13; the last is this week, still in progress.")
        val counting = lines.indexOfFirst { it.startsWith("Counting: hard sets, as Lifts' Sets per Muscle screen counts them.") }
        assertTrue(counting > 0, "the counting rule (#126)")
        assertEquals("", lines[counting - 1], "its own paragraph, not run into the week line")
        assertContains(lines, "- chest: 0, 0, 0, 0, 0, 0, 2, 2")
        // Direct counting: the bench's secondary muscles get no line.
        assertTrue(lines.none { it.startsWith("- triceps:") || it.startsWith("- shoulders:") })
        assertTrue(lines.none { it.startsWith("Counts For he chose") }, "none chosen")
    }

    @Test
    fun targetsReadAsSetsOverTargetAndComeFirst() {
        val withTargets = MarkdownExport.render(ExportFixtures.snapshot(muscleTargets = mapOf("chest" to 13, "abdominals" to 6))).split("\n")
        val first = withTargets.indexOfFirst { it.startsWith("- abdominals:") }
        assertEquals("- abdominals: 0/6, 0/6, 0/6, 0/6, 0/6, 0/6, 0/6, 0/6", withTargets[first])
        assertEquals("- chest: 0/13, 0/13, 0/13, 0/13, 0/13, 0/13, 2/13, 2/13", withTargets[first + 1])
        assertTrue(withTargets.any { it.startsWith("Targets (his, as set now, the same each week): `9/12` is 9 sets") })
        assertTrue(lines.none { it.startsWith("Targets (") }, "no targets, no line")
        // A layoff with a target still lists it: no sets is the news.
        val layoff = MarkdownExport.render(ExportFixtures.snapshot(workouts = emptyList(), muscleTargets = mapOf("calves" to 6))).split("\n")
        assertContains(layoff, "- calves: 0/6, 0/6, 0/6, 0/6, 0/6, 0/6, 0/6, 0/6")
    }

    @Test
    fun theLiftsHeChoseMusclesForAreNamedWithThem() {
        val bench = ExportFixtures.bench.copy(countsFor = listOf("chest", "front delts"))
        val chosen = MarkdownExport.render(ExportFixtures.snapshot(exercises = listOf(bench, ExportFixtures.squat))).split("\n")
        assertContains(chosen, "Counts For he chose: Bench Press (Barbell) → chest, front delts.")
        assertContains(chosen, "- front delts: 0, 0, 0, 0, 0, 0, 2, 2")
    }

    @Test
    fun bodyweightSubsectionIsExact() {
        assertContains(lines, "### Bodyweight")
        assertContains(lines, "- Current: 80.5 kg (2026-07-13)")
        // Least-squares over the five entries of 2026-06-17..07-14 (06-18 to 07-13): -0.3034 kg/week.
        assertContains(lines, "- Rate: -0.30 kg/week over 25 days of weigh-ins")
        // Em-dash for the three empty leading weeks and the 2026-06-29 gap week.
        assertContains(lines, "- Weekly average, weeks starting 2026-05-25 … 2026-07-13 (oldest → newest): —, —, —, 81.9, 81.6, —, 81.1, 80.5")
    }

    @Test
    fun bodyweightAveragesNameTheirWeeksWithoutAnyWorkingSets() {
        // A layoff: weigh-ins, no working sets in the 8 weeks, so no weekly-sets section to borrow dates from.
        val layoff = MarkdownExport.render(ExportFixtures.snapshot(workouts = emptyList())).split("\n")
        assertTrue(layoff.none { it.startsWith("### Weekly sets") })
        assertContains(layoff, "- Weekly average, weeks starting 2026-05-25 … 2026-07-13 (oldest → newest): —, —, —, 81.9, 81.6, —, 81.1, 80.5")
    }

    @Test
    fun aMissingExerciseIsUnknownButKeepsItsId() {
        val orphan = ExportFixtures.snapshot(exercises = listOf(ExportFixtures.squat))
        assertTrue(MarkdownExport.render(orphan).contains("- **Unknown exercise (bench-press-barbell)**"))
        assertTrue(CsvExport.render(orphan).split("\n")[1].contains(",Unknown exercise (bench-press-barbell),"))
    }

    @Test
    fun bodyweightRateIsSignedWhenPositive() {
        val gaining = ExportFixtures.snapshot(
            bodyweight = listOf(
                BodyweightEntity(id = 1, epochDay = LocalDate.parse("2026-06-29").toEpochDay(), weightKg = 80.0),
                BodyweightEntity(id = 2, epochDay = LocalDate.parse("2026-07-06").toEpochDay(), weightKg = 80.25),
                BodyweightEntity(id = 3, epochDay = LocalDate.parse("2026-07-13").toEpochDay(), weightKg = 80.5),
            ),
        )

        assertContains(MarkdownExport.render(gaining).split("\n"), "- Rate: +0.25 kg/week over 14 days of weigh-ins")
    }

    @Test
    fun bodyweightRateLineOmittedWithSingleEntry() {
        val single = ExportFixtures.snapshot(
            bodyweight = listOf(
                BodyweightEntity(id = 1, epochDay = LocalDate.parse("2026-07-13").toEpochDay(), weightKg = 80.5),
            ),
        )

        val singleLines = MarkdownExport.render(single).split("\n")
        assertContains(singleLines, "- Current: 80.5 kg (2026-07-13)")
        assertTrue(singleLines.none { it.startsWith("- Rate:") }, "one entry cannot have a slope")
    }

    @Test
    fun bodyweightSubsectionOmittedWithoutData() {
        val markdown = MarkdownExport.render(ExportFixtures.snapshot(bodyweight = emptyList()))
        assertTrue("### Bodyweight" !in markdown, "no bodyweight data -> no subsection")
    }

    @Test
    fun workoutsAreNewestFirstWithHeadingsAndNotes() {
        // Each carries that day's weigh-in.
        val newer = markdown.indexOf("### 2026-07-13 — Push Day (98m, BW 80.5 kg)")
        val older = markdown.indexOf("### 2026-07-06 — Push Day (44m, BW 81 kg)")
        assertTrue(newer >= 0, "newest workout heading missing")
        assertTrue(older >= 0, "oldest workout heading missing")
        assertTrue(newer < older, "workouts must be newest-first")
        assertContains(lines, "> Felt strong")
        assertContains(lines, "  > ${ExportFixtures.INSTANCE_NOTES}")
    }

    @Test
    fun aSupersetIsMarkedOnEachOfItsExercises() {
        val paired = ExportFixtures.slots.map { if (it.id == 2L) it.copy(supersetGroup = 3) else it } +
            WorkoutExerciseEntity(id = 3, workoutId = 2, exerciseId = ExportFixtures.squat.id, position = 1, supersetGroup = 3)
        val sets = ExportFixtures.sets + SetEntity(
            id = 8, workoutExerciseId = 3, position = 0, type = "normal", weightKg = 120.0, reps = 5,
            completedAt = ExportFixtures.millis("2026-07-13T10:20:00Z"),
        )
        val log = MarkdownExport.render(ExportFixtures.snapshot(slots = paired, sets = sets)).lines()
        assertTrue(log.any { it.startsWith("- **Bench Press (Barbell)** (superset 1) [") }, log.joinToString("\n"))
        assertTrue(log.any { it.startsWith("- **") && "(superset 1)" in it && "Squat" in it }, log.joinToString("\n"))
        assertTrue(lines.none { it.startsWith("- **") && "(superset" in it }, "a plain exercise is unmarked")
    }

    @Test
    fun aSwappedExerciseSaysWhatItWasDoneInsteadOf() {
        val swapped = ExportFixtures.slots.map { if (it.id == 2L) it.copy(choices = listOf(ExportFixtures.squat.id, it.exerciseId)) else it }
        val log = MarkdownExport.render(ExportFixtures.snapshot(slots = swapped)).lines()
        assertTrue(log.any { it.startsWith("- **Bench Press (Barbell)** (instead of Squat (Barbell)) [") }, log.joinToString("\n"))
        assertEquals(1, log.count { "(instead of" in it && it.startsWith("- **") }, "only the swapped one")
    }

    @Test
    fun aDeloadSaysSoInItsHeadingAndAnRirRangeInItsTarget() {
        val ranged = ExportFixtures.slots.map { if (it.id == 2L) it.copy(targetRepMin = 8, targetRepMax = 12, targetRir = 1, targetRirMax = 2) else it }
        val log = MarkdownExport.render(
            ExportFixtures.snapshot(workouts = listOf(ExportFixtures.workout1, ExportFixtures.workout2.copy(deload = true)), slots = ranged),
        ).lines()
        assertContains(log, "### 2026-07-13 — Push Day (deload, 98m, BW 80.5 kg)")
        assertContains(log, "### 2026-07-06 — Push Day (44m, BW 81 kg)")
        assertTrue(log.any { it.startsWith("- **Bench Press (Barbell)**") && it.endsWith("(target 8–12 reps, RIR 1–2)") }, log.joinToString("\n"))
    }

    @Test
    fun aLegendExplainsTheNotation() {
        val legend = lines.single { it.startsWith("How to read this:") }
        for (term in listOf(
            "`100x5` is 100 kg for 5 reps", "`w`", "`F`", "`D`", "`@8` is RPE", "`r150`", "**PR**", "(Z)", "`(superset 1)`",
            "`deload`", "RIR 1–2", "`(instead of",
        )) {
            assertTrue(term in legend, "legend lacks $term: $legend")
        }
    }

    @Test
    fun multiLineNotesStayQuoted() {
        // A note whose lines would otherwise start a heading and a list item (#070).
        val notes = "Felt tired\n# of sets cut short\n- left early"
        val quoted = MarkdownExport.render(
            ExportFixtures.snapshot(workouts = listOf(ExportFixtures.workout1, ExportFixtures.workout2.copy(notes = notes))),
        ).split("\n")
        assertContains(quoted, "> Felt tired")
        assertContains(quoted, "> # of sets cut short")
        assertContains(quoted, "> - left early")
        assertTrue(quoted.none { it.startsWith("# of") || it == "- left early" }, "no raw heading or list item")
    }

    @Test
    fun exerciseLineListsMuscles() {
        assertContains(lines, "- **Bench Press (Barbell)** [chest; triceps, shoulders]")
    }

    @Test
    fun setNotationLineIsExact() {
        // Warmup prefixed "w", RPE "@8", the 10 minutes since the set before "r600", PR marker, joined
        // with "; ". The first set of a workout has no rest gap.
        assertContains(lines, "  w60x8; 102.5x5 @8 r600 — **PR**; 95x5 r600")
        // First working set ever is a PR too.
        assertContains(lines, "  w60x8; 100x5 @8 r600 — **PR**; 100x5 r600")
    }

    @Test
    fun unreferencedExercisesDoNotAppear() {
        assertTrue("Squat" !in markdown, "squat was never performed and must not appear")
    }
}
