package com.tczerwien.lifts.session

import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit

/** What was typed into a weight's number entry. */
sealed interface TypedWeight {
    /** A weight in display units, on the unit's rounding grid ([Format.roundWeight]). */
    data class Value(val display: Double) : TypedWeight

    /** Nothing typed: the row goes back to its starting value. */
    data object Clear : TypedWeight
}

/** Heavier than anything on a bar or a stack, in either unit: a slip of the finger, not a weight. */
private const val MAX_TYPED_WEIGHT = 2000.0

private val NUMBER = Regex("""(\d+(\.\d*)?|\.\d+)""")

/**
 * A weight typed on the LP3 keyboard, for the weight a row shows (the assistance itself on an
 * assisted exercise). Spaces around it and a trailing "lb"/"kg" are ignored, as is a sign ("+45" on a dip belt,
 * "-30" of help: the exercise's tracking decides which it is); a decimal comma reads as a point.
 * The value rounds to [unit]'s grid, so "17.5" is 17.5 lb and "21.3" is 21.25 kg. Blank is [TypedWeight.Clear];
 * anything else that isn't a weight from 0 to 2000 is null, and the editor says so.
 */
fun parseTypedWeight(text: String, unit: WeightUnit): TypedWeight? {
    val bare = text.trim().lowercase()
        .removeSuffix("lbs").removeSuffix("lb").removeSuffix("kg")
        .trim()
        .replace(',', '.')
        .trimStart('+', '-', '−')
        .trim()
    if (bare.isEmpty()) return if (text.isBlank()) TypedWeight.Clear else null
    if (!NUMBER.matches(bare)) return null
    val value = bare.toDoubleOrNull()?.takeIf { it.isFinite() && it <= MAX_TYPED_WEIGHT } ?: return null
    return TypedWeight.Value(Format.roundWeight(value, unit))
}
