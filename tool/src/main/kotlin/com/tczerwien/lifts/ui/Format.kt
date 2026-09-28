package com.tczerwien.lifts.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/** Display unit for weights. Storage is always kilograms. */
enum class WeightUnit {
    LB,
    KG,
    ;

    /** Column header used by the set-row grid ("Lbs" / "Kg"). */
    val columnLabel: String
        get() = when (this) {
            LB -> "Lbs"
            KG -> "Kg"
        }

    /** Inline suffix ("lb" / "kg"). */
    val suffix: String
        get() = when (this) {
            LB -> "lb"
            KG -> "kg"
        }

    /** Weight stepper increment in display units (±5 lb / ±2.5 kg). */
    val weightStep: Double
        get() = when (this) {
            LB -> 5.0
            KG -> 2.5
        }

    /** Bodyweight stepper increment in display units (±0.2 lb / ±0.1 kg). */
    val bodyweightStep: Double
        get() = when (this) {
            LB -> 0.2
            KG -> 0.1
        }
}

/**
 * Pure-Kotlin formatting and unit conversion. Storage is ALWAYS kg; anything the user
 * sees goes through these helpers using the unit from SettingsStore.
 */
object Format {

    const val LB_PER_KG = 2.2046226218

    // --- Unit conversion ---

    fun kgToLb(kg: Double): Double = kg * LB_PER_KG

    fun lbToKg(lb: Double): Double = lb / LB_PER_KG

    /** Converts canonical kg to the display unit, unrounded. */
    fun kgToDisplay(kg: Double, unit: WeightUnit): Double = when (unit) {
        WeightUnit.LB -> kgToLb(kg)
        WeightUnit.KG -> kg
    }

    /** Converts a display-unit value back to canonical kg. */
    fun displayToKg(value: Double, unit: WeightUnit): Double = when (unit) {
        WeightUnit.LB -> lbToKg(value)
        WeightUnit.KG -> value
    }

    // --- Rounding (display units) ---

    /** Weights render to the nearest 0.5 display unit. */
    fun roundWeight(display: Double): Double = (display * 2).roundToLong() / 2.0

    /** Bodyweight renders to the nearest 0.1 display unit. */
    fun roundBodyweight(display: Double): Double = (display * 10).roundToLong() / 10.0

    /**
     * [display] moved [steps] grid steps of [step] (both in display units), floored at 0. A value off
     * the grid lands on it with its first step, so the grid is never lost: 20.5 kg steps to 22.5 or
     * 20, not 23 or 18, and 60 kg shown in lb (132.5) steps to 135 or 130, never back to a 132.5
     * that stores as 60.1 kg. More than one step (a held stepper speeding up) moves that many.
     */
    fun stepWeight(display: Double, steps: Int, step: Double): Double {
        if (steps == 0) return display
        val onGrid = display / step
        val start = if (steps > 0) floor(onGrid + GRID_EPSILON) else ceil(onGrid - GRID_EPSILON)
        return (((start + steps) * step * 1000).roundToLong() / 1000.0).coerceAtLeast(0.0)
    }

    /** In grid steps: 150 / 0.2 is 749.999…, which is on the grid. */
    private const val GRID_EPSILON = 1e-6

    // --- Weight strings ---

    /** Canonical kg -> rounded display string without suffix, e.g. "135" or "62.5". */
    fun weight(kg: Double, unit: WeightUnit): String =
        trimTrailingZero(roundWeight(kgToDisplay(kg, unit)))

    /**
     * Canonical kg -> plate or bar weight with up to 3 decimals and no trailing zeros, e.g. "45",
     * "1.25", "20.412". Not snapped to the 0.5 weight grid: a 1.25 kg plate must never read 1.5.
     */
    fun plate(kg: Double, unit: WeightUnit): String =
        "%.3f".format(Locale.US, kgToDisplay(kg, unit)).trimEnd('0').trimEnd('.')

    /** Canonical kg -> rounded bodyweight display string, e.g. "180.4". */
    fun bodyweight(kg: Double, unit: WeightUnit): String =
        trimTrailingZero(roundBodyweight(kgToDisplay(kg, unit)))

    /** "135 × 8" — previous-performance summary for a set row. */
    fun setSummary(weightKg: Double, reps: Int, unit: WeightUnit): String =
        "${weight(weightKg, unit)} × $reps"

    // --- Clocks and durations ---

    /** Seconds -> "m:ss", e.g. 95 -> "1:35". */
    fun clock(totalSec: Int): String {
        val sec = totalSec.coerceAtLeast(0)
        return "%d:%02d".format(Locale.US, sec / 60, sec % 60)
    }

    /** How long ago a rest ended: "1:20" under an hour, "1h 23m" from an hour up. */
    fun ago(totalSec: Int): String = if (totalSec < 3600) clock(totalSec) else durationHm(totalSec)

    /** Seconds -> "1h 23m" (or "43m" under an hour). */
    fun durationHm(totalSec: Int): String {
        val sec = totalSec.coerceAtLeast(0)
        val hours = sec / 3600
        val minutes = (sec % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    /** Elapsed millis -> "m:ss" under an hour, "h:mm:ss" from an hour up. */
    fun elapsed(elapsedMillis: Long): String {
        val sec = (elapsedMillis / 1000).coerceAtLeast(0)
        val hours = sec / 3600
        val minutes = (sec % 3600) / 60
        val seconds = sec % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
        } else {
            "%d:%02d".format(Locale.US, minutes, seconds)
        }
    }

    /** "412 B" under a KB, whole KB under a MB ("48 KB"), else MB with one decimal ("1.4 MB"). */
    fun fileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "${(bytes / 1024.0).roundToLong()} KB"
        else -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    }

    // --- Dates (epoch millis) ---

    private val dateShortFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val dateWithYearFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US)

    /** "Jul 18" */
    fun dateShort(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dateShortFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** "Jul 18 14:05", for files rewritten several times a day (exports, backups). */
    fun dateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dateTimeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** "Jul 18, 2026" */
    fun dateWithYear(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dateWithYearFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    private fun trimTrailingZero(value: Double): String =
        if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            "%.1f".format(Locale.US, value)
        }
}
