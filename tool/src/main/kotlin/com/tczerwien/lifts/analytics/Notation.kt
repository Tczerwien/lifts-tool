package com.tczerwien.lifts.analytics

import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit

/**
 * The one set-notation formatter. Every screen and every export that writes a set's numbers goes
 * through here, so a set reads the same wherever it shows. Weights are in the display unit, rounded
 * as the steppers round ([Format.weight]); a weight that shows as 0 is no weight, so a pull-up nudged to
 * 0 (or a stray 0.1 kg from elsewhere) reads "×10", not "0×10". Assistance, stored as a negative
 * weight, keeps its sign: an assisted pull-up with 30 lb of help reads "-30×8".
 */
enum class NotationStyle {
    /** A set in a list on screen: "w95×10", "140×8F RIR1", "×12", "20×45s". */
    LIST,

    /** One set on its own, spaced, without type markers (the row shows them): "135 × 8", "× 12", "165 × 5 RIR 2". */
    SINGLE,

    /** training-log.md: plain ASCII "x", markers, RPE and the rest gap: "w60x8", "100x5F @9 r180". */
    EXPORT,
}

private const val TYPE_WARMUP = "warmup"
private const val TYPE_FAILURE = "failure"
private const val TYPE_DROP = "drop"

/**
 * [set]'s numbers in [style], or null when it has none (no weight, reps or duration). In [NotationStyle.LIST]
 * and [NotationStyle.EXPORT] a warm-up is prefixed "w", a set to failure ends "F" and a drop set "D";
 * [restSec] (EXPORT only) adds " r180". [withUnit] (SINGLE only) writes the unit after the weight,
 * "165 lb × 5", for a record shown on its own. The effort ([SetEntity.rpe], logged as RIR, stored as
 * RPE = 10 − RIR) reads as he logged it on screen, "RIR2", and as RPE in the export, "@8", the scale
 * programs and AIs read.
 */
fun setNotation(
    set: SetEntity,
    unit: WeightUnit,
    style: NotationStyle,
    restSec: Int? = null,
    withUnit: Boolean = false,
): String? = setNotation(
    weightKg = set.weightKg,
    reps = set.reps,
    unit = unit,
    style = style,
    type = set.type,
    durationSec = set.durationSec,
    rpe = set.rpe,
    restSec = restSec,
    withUnit = withUnit,
)

/** [setNotation] from loose values: a PREVIOUS row, the values a tick would log, a record. */
fun setNotation(
    weightKg: Double?,
    reps: Int?,
    unit: WeightUnit,
    style: NotationStyle,
    type: String? = null,
    durationSec: Int? = null,
    rpe: Double? = null,
    restSec: Int? = null,
    withUnit: Boolean = false,
): String? {
    val weight = weightKg?.takeIf { Format.roundWeight(Format.kgToDisplay(it, unit), unit) != 0.0 }?.let { kg ->
        Format.weight(kg, unit) + if (withUnit && style == NotationStyle.SINGLE) " ${unit.suffix}" else ""
    }
    val times = when (style) {
        NotationStyle.LIST -> "×"
        NotationStyle.SINGLE -> " × "
        NotationStyle.EXPORT -> "x"
    }
    val amount = when {
        reps != null -> "$reps"
        durationSec != null -> "${durationSec}s"
        else -> null
    }
    val core = when {
        weight != null && amount != null -> weight + times + amount
        weight != null -> weight
        amount != null && reps != null -> times.trimStart() + amount
        amount != null -> amount
        else -> return null
    }
    val marked = style != NotationStyle.SINGLE
    return buildString {
        if (marked && type == TYPE_WARMUP) append('w')
        append(core)
        if (marked && type == TYPE_FAILURE) append('F')
        if (marked && type == TYPE_DROP) append('D')
        rpe?.let {
            when (style) {
                NotationStyle.EXPORT -> append(" @").append(plainNumber(it))
                NotationStyle.LIST -> append(" RIR").append(plainNumber(10.0 - it))
                NotationStyle.SINGLE -> append(" RIR ").append(plainNumber(10.0 - it))
            }
        }
        if (style == NotationStyle.EXPORT) restSec?.let { append(" r").append(it) }
    }
}

/** "8" for 8.0, "7.5" for 7.5. */
fun plainNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/** One set as written in a list ([NotationStyle.LIST]), and whether it was a PR when it was logged. */
data class SetToken(val text: String, val pr: Boolean)

/** [count] identical sets in a row, written once: "225×5 ×3". */
data class SetRun(val text: String, val pr: Boolean, val count: Int) {
    /** " ×3" after the set (and after its star, if any); empty for a single set. */
    val repeat: String get() = if (count > 1) " ×$count" else ""
}

/**
 * [tokens] with runs of identical sets collapsed, for a one-line history row: "w95×10; 225×5 ×3". A PR
 * set never merges with the equal sets after it (they didn't beat it), so its star stays on one set.
 */
fun compactNotation(tokens: List<SetToken>): List<SetRun> {
    val runs = mutableListOf<SetRun>()
    for (token in tokens) {
        val last = runs.lastOrNull()
        if (last != null && last.text == token.text && last.pr == token.pr) {
            runs[runs.lastIndex] = last.copy(count = last.count + 1)
        } else {
            runs.add(SetRun(token.text, token.pr, 1))
        }
    }
    return runs
}
