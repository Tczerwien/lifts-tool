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
 * assisted exercise). Spaces around it are ignored; a trailing "lb"/"kg" in the other unit converts
 * ("45 lb" on a kg screen is 20.5 kg), in the unit shown it changes nothing. A sign is ignored ("+45" on a dip belt,
 * "-30" of help: the exercise's tracking decides which it is); a decimal comma reads as a point.
 * The value rounds to [unit]'s grid, so "17.5" is 17.5 lb and "21.3" is 21.25 kg. Blank is [TypedWeight.Clear];
 * anything else that isn't a weight from 0 to 2000 is null, and the editor says so.
 */
fun parseTypedWeight(text: String, unit: WeightUnit): TypedWeight? {
    val lower = text.trim().lowercase()
    // A unit typed after it: the other unit's is converted ("45 lb" on a kg screen), his own is dropped.
    val typedUnit = when {
        lower.endsWith("kg") -> WeightUnit.KG
        lower.endsWith("lb") || lower.endsWith("lbs") -> WeightUnit.LB
        else -> unit
    }
    val bare = lower
        .removeSuffix("lbs").removeSuffix("lb").removeSuffix("kg")
        .trim()
        .replace(',', '.')
        .trimStart('+', '-', '−')
        .trim()
    if (bare.isEmpty()) return if (text.isBlank()) TypedWeight.Clear else null
    if (!NUMBER.matches(bare)) return null
    val typed = bare.toDoubleOrNull()?.takeIf { it.isFinite() && it <= MAX_TYPED_WEIGHT } ?: return null
    val value = if (typedUnit == unit) typed else Format.kgToDisplay(Format.displayToKg(typed, typedUnit), unit)
    return TypedWeight.Value(Format.roundWeight(value, unit))
}
