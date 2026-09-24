package com.tczerwien.lifts.domain

/**
 * Pure per-side plate math for the plate calculator. All weights are canonical kg;
 * callers convert to display units.
 */
object PlateMath {

    /**
     * Comparison tolerance in kg (1 g). Weights converted from lb, or stored rounded, can be off
     * by tens of micrograms (a 45 lb bar saved as 20.4117 kg is 43 µg heavy), while the smallest
     * real plate (0.25 kg) is 250 times this.
     */
    private const val EPSILON_KG = 1e-3

    /** Default cap per plate size per side — a collar only holds so many plates. */
    const val DEFAULT_PLATES_PER_SIZE_LIMIT = 8

    /** [count] plates of [plateKg] loaded on ONE side of the bar. */
    data class PlateCount(val plateKg: Double, val count: Int)

    /**
     * @property perSide plate breakdown for one side, heaviest first, all counts >= 1.
     * @property achievedKg bar plus both loaded sides — equals the target when [exact].
     * @property exact whether the target weight is hit exactly.
     */
    data class Loadout(
        val perSide: List<PlateCount>,
        val achievedKg: Double,
        val exact: Boolean,
    )

    /**
     * Greedy per-side breakdown of [targetKg] on a [barKg] bar from the [inventoryKg]
     * plate sizes, each size capped at [platesPerSizeLimit] plates per side.
     *
     * Returns null when the target sits below the bar weight (nothing to load). When no
     * exact load exists, returns the nearest achievable weight not exceeding the target
     * with [Loadout.exact] = false — "nearest" per the greedy walk, which is optimal for
     * conventional plate sets, not a global search over arbitrary ones.
     *
     * Non-positive and duplicate inventory entries are ignored.
     */
    fun loadout(
        targetKg: Double,
        barKg: Double,
        inventoryKg: List<Double>,
        platesPerSizeLimit: Int = DEFAULT_PLATES_PER_SIZE_LIMIT,
    ): Loadout? {
        if (targetKg < barKg - EPSILON_KG) return null

        val sizes = inventoryKg.filter { it > EPSILON_KG }.distinct().sortedDescending()
        var remaining = ((targetKg - barKg) / 2).coerceAtLeast(0.0)
        val perSide = mutableListOf<PlateCount>()
        for (size in sizes) {
            val count = ((remaining + EPSILON_KG) / size).toInt()
                .coerceIn(0, platesPerSizeLimit.coerceAtLeast(0))
            if (count > 0) {
                perSide.add(PlateCount(plateKg = size, count = count))
                remaining -= count * size
            }
        }
        val loadedPerSide = perSide.sumOf { it.plateKg * it.count }
        return Loadout(
            perSide = perSide,
            achievedKg = barKg + 2 * loadedPerSide,
            exact = remaining <= EPSILON_KG,
        )
    }

    /**
     * One side's plates as text, heaviest first: "45 · 25 · 2.5 × 2". A single plate shows no
     * count, and each group is glued with non-breaking spaces so a wrap only ever falls between
     * groups, never inside one. [formatPlate] renders a plate weight in the display unit.
     */
    fun perSideLabel(perSide: List<PlateCount>, formatPlate: (Double) -> String): String =
        perSide.joinToString(" · ") { plate ->
            val weight = formatPlate(plate.plateKg)
            if (plate.count == 1) weight else "$weight\u00A0×\u00A0${plate.count}"
        }
}
