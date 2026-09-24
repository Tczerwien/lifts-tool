package com.tczerwien.lifts.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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

    // --- Dates (epoch millis) ---

    private val dateShortFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val dateWithYearFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    /** "Jul 18" */
    fun dateShort(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dateShortFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

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
