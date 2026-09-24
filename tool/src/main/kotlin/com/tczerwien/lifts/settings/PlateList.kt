package com.tczerwien.lifts.settings

import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit

/** The plate inventory as the text the user edits: display units, comma separated. Pure. */
object PlateList {

    private val SPLIT = Regex("[,/;\\s]+")

    /** "45, 25, 10, 5, 2.5": exact plate values (no 0.5 rounding), heaviest first as stored. */
    fun format(inventoryKg: List<Double>, unit: WeightUnit): String =
        inventoryKg.joinToString(", ") { Format.plate(it, unit) }

    /**
     * Parses a user-entered plate list in display units to canonical kg, heaviest first. Splits
     * on commas, slashes, semicolons and whitespace, so a comma is always a separator ("2,5" is
     * two plates, 5 and 2; decimals use a point). Drops non-numbers, non-finite and non-positive
     * values, and duplicates. Null when nothing valid remains.
     */
    fun parse(text: String, unit: WeightUnit): List<Double>? {
        val display = text.split(SPLIT)
            .mapNotNull { it.trim().toDoubleOrNull() }
            .filter { it.isFinite() && it > 0 }
            .distinct()
            .sortedDescending()
        if (display.isEmpty()) return null
        return display.map { Format.displayToKg(it, unit) }
    }
}
