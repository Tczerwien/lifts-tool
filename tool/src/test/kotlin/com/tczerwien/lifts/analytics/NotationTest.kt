package com.tczerwien.lifts.analytics

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotationTest {

    private val lb = WeightUnit.LB
    private val kg = WeightUnit.KG

    private fun set(type: String = "normal", lb: Double? = null, reps: Int? = null, durationSec: Int? = null, rpe: Double? = null) =
        SetEntity(
            workoutExerciseId = 1,
            position = 0,
            type = type,
            weightKg = lb?.let(Format::lbToKg),
            reps = reps,
            durationSec = durationSec,
            rpe = rpe,
        )

    @Test
    fun poundSetsReadAsEntered() {
        // Stored as 61.2349… kg; every style writes it back as 135.
        val bench = set(lb = 135.0, reps = 8)
        assertEquals("135×8", setNotation(bench, lb, NotationStyle.LIST))
        assertEquals("135 × 8", setNotation(bench, lb, NotationStyle.SINGLE))
        assertEquals("135x8", setNotation(bench, lb, NotationStyle.EXPORT))
    }

    @Test
    fun kilogramsUseTheKilogramGrid() {
        val bench = SetEntity(workoutExerciseId = 1, position = 0, type = "normal", weightKg = 102.5, reps = 5)
        assertEquals("102.5×5", setNotation(bench, kg, NotationStyle.LIST))
        assertEquals("102.5x5", setNotation(bench, kg, NotationStyle.EXPORT))
    }

    @Test
    fun typeMarkersShowInListsAndExportsOnly() {
        assertEquals("w95×10", setNotation(set("warmup", 95.0, 10), lb, NotationStyle.LIST))
        assertEquals("140×8F", setNotation(set("failure", 140.0, 8), lb, NotationStyle.LIST))
        assertEquals("120×10D", setNotation(set("drop", 120.0, 10), lb, NotationStyle.LIST))
        assertEquals("w60x8", setNotation(set("warmup", 60.0, 8), lb, NotationStyle.EXPORT))
        assertEquals("140x8F", setNotation(set("failure", 140.0, 8), lb, NotationStyle.EXPORT))
        // A row on screen already shows its marker.
        assertEquals("95 × 10", setNotation(set("warmup", 95.0, 10), lb, NotationStyle.SINGLE))
        assertEquals("140 × 8", setNotation(set("failure", 140.0, 8), lb, NotationStyle.SINGLE))
    }

    @Test
    fun weightlessSetsHaveNoDash() {
        val pullUp = set(reps = 10)
        assertEquals("×10", setNotation(pullUp, lb, NotationStyle.LIST))
        assertEquals("× 10", setNotation(pullUp, lb, NotationStyle.SINGLE))
        assertEquals("x10", setNotation(pullUp, lb, NotationStyle.EXPORT))
        assertEquals("w×10", setNotation(set("warmup", reps = 10), lb, NotationStyle.LIST))
    }

    @Test
    fun aZeroWeightIsNoWeight() {
        // The weight stepper floors at 0 and stores 0.0, not null (#150).
        val nudged = SetEntity(workoutExerciseId = 1, position = 0, type = "normal", weightKg = 0.0, reps = 10)
        assertEquals("×10", setNotation(nudged, lb, NotationStyle.LIST))
        assertEquals("x10", setNotation(nudged, lb, NotationStyle.EXPORT))
        // Judged on what would show: 0.1 kg is 0 on the 0.5 lb grid (a restored or imported value).
        val sliver = nudged.copy(weightKg = 0.1)
        assertEquals("×10", setNotation(sliver, lb, NotationStyle.LIST))
        assertEquals("0.5×10", setNotation(sliver.copy(weightKg = 0.25), lb, NotationStyle.LIST))
    }

    @Test
    fun assistanceKeepsItsSign() {
        val assisted = set(lb = -30.0, reps = 8)
        assertEquals("-30×8", setNotation(assisted, lb, NotationStyle.LIST))
        assertEquals("-30 × 8", setNotation(assisted, lb, NotationStyle.SINGLE))
        assertEquals("-30x8", setNotation(assisted, lb, NotationStyle.EXPORT))
        assertEquals("-30 lb × 8", setNotation(Format.lbToKg(-30.0), 8, lb, NotationStyle.SINGLE, withUnit = true))
        // A sliver of help that shows as 0 is no weight either way.
        assertEquals("×8", setNotation(-0.1, 8, lb, NotationStyle.LIST))
    }

    @Test
    fun durationsAndWeightOnly() {
        assertEquals("45s", setNotation(set(durationSec = 45), lb, NotationStyle.LIST))
        assertEquals("25×45s", setNotation(set(lb = 25.0, durationSec = 45), lb, NotationStyle.LIST))
        assertEquals("25 × 45s", setNotation(set(lb = 25.0, durationSec = 45), lb, NotationStyle.SINGLE))
        assertEquals("225", setNotation(set(lb = 225.0), lb, NotationStyle.LIST))
    }

    @Test
    fun nothingToWriteIsNull() {
        assertNull(setNotation(set(), lb, NotationStyle.LIST))
        assertNull(setNotation(weightKg = null, reps = null, unit = lb, style = NotationStyle.SINGLE))
    }

    @Test
    fun rpeAndRest() {
        val top = set(lb = 165.0, reps = 5, rpe = 8.0)
        assertEquals("165×5 @8", setNotation(top, lb, NotationStyle.LIST))
        assertEquals("165 × 5 @ 8", setNotation(top, lb, NotationStyle.SINGLE))
        assertEquals("165x5 @8 r180", setNotation(top, lb, NotationStyle.EXPORT, restSec = 180))
        assertEquals("165x5F @9.5", setNotation(set("failure", 165.0, 5, rpe = 9.5), lb, NotationStyle.EXPORT))
        // Rest belongs to the export only.
        assertEquals("165×5 @8", setNotation(top, lb, NotationStyle.LIST, restSec = 180))
    }

    @Test
    fun aRecordCanCarryItsUnit() {
        assertEquals("165 lb × 5", setNotation(Format.lbToKg(165.0), 5, lb, NotationStyle.SINGLE, withUnit = true))
        assertEquals("100 kg", setNotation(100.0, null, kg, NotationStyle.SINGLE, withUnit = true))
        // Lists never carry it.
        assertEquals("165×5", setNotation(Format.lbToKg(165.0), 5, lb, NotationStyle.LIST, withUnit = true))
    }

    @Test
    fun repeatsCollapseButAPrStaysOnItsSet() {
        val tokens = listOf(
            SetToken("w135×5", false),
            SetToken("225×5", true),
            SetToken("225×5", false),
            SetToken("225×5", false),
            SetToken("205×8", false),
        )
        val runs = compactNotation(tokens)
        assertEquals(
            listOf(SetRun("w135×5", false, 1), SetRun("225×5", true, 1), SetRun("225×5", false, 2), SetRun("205×8", false, 1)),
            runs,
        )
        assertEquals(listOf("", "", " ×2", ""), runs.map { it.repeat })
        assertEquals(listOf(SetRun("225×5", false, 3)), compactNotation(List(3) { SetToken("225×5", false) }))
        assertEquals(emptyList(), compactNotation(emptyList()))
    }
}
