package com.tczerwien.lifts.settings

import com.tczerwien.lifts.data.BarEntity
import com.tczerwien.lifts.seed.Seeder
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import kotlin.math.abs

/** Two bar weights this close (1 g) are the same bar: 45 lb was once seeded as 20.4117 kg. */
private const val SAME_BAR_KG = 1e-3

/** The bar a new install starts with, in [unit]: a 45 lb bar, or a 20 kg one. */
fun defaultBarKg(unit: WeightUnit): Double = when (unit) {
    WeightUnit.LB -> Format.lbToKg(45.0)
    WeightUnit.KG -> 20.0
}

/**
 * The bar to save when the unit switches from [from] to [to], or null to leave it: the seeded
 * Olympic Bar still at [from]'s standard weight becomes [to]'s (45 lb and 20 kg are different bars,
 * and 20.4 kg is neither), so it goes back again on the way back. A bar he renamed, weighed or added
 * is his and stays as it is.
 */
fun barAfterUnitSwitch(bar: BarEntity, from: WeightUnit, to: WeightUnit): BarEntity? {
    if (from == to || bar.name != Seeder.DEFAULT_BAR_NAME) return null
    if (abs(bar.weightKg - defaultBarKg(from)) > SAME_BAR_KG) return null
    return bar.copy(weightKg = defaultBarKg(to))
}
