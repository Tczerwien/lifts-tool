package com.tczerwien.lifts.export

import com.tczerwien.lifts.data.BodyweightEntity
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
        assertContains(lines, "schema_version: \"2.3\"")
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
        val counting = lines.indexOfFirst { it.startsWith("Counting: each working set (warm-ups excluded) adds 1") }
        assertTrue(counting > 0, "the counting rule (#126)")
        assertEquals("", lines[counting - 1], "its own paragraph, not run into the week line")
        assertContains(lines, "- chest: 0, 0, 0, 0, 0, 0, 2, 2")
        assertContains(lines, "- triceps: 0, 0, 0, 0, 0, 0, 1, 1")
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
    fun aLegendExplainsTheNotation() {
        val legend = lines.single { it.startsWith("How to read this:") }
        for (term in listOf("`100x5` is 100 kg for 5 reps", "`w`", "`F`", "`D`", "`@8` is RPE", "`r150`", "**PR**", "(Z)")) {
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
