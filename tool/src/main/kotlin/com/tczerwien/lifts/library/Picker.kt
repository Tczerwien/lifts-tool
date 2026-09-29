package com.tczerwien.lifts.library

/** A picker chip: what he has logged lately, the whole catalog, or one body part. */
sealed interface PickerChip {
    data object Recent : PickerChip

    data object All : PickerChip

    data class Part(val part: BodyPart) : PickerChip
}

/**
 * The picker's rows: the search results when he has searched ([query] replaces the chip until cleared),
 * else the chip's. Recent keeps [recentIds]' order, most recent first, and leaves out ids the catalog
 * doesn't show (hidden ones); the other chips keep the catalog's order.
 */
fun pickerEntries(
    catalog: List<CatalogEntry>,
    recentIds: List<String>,
    chip: PickerChip,
    query: String?,
): List<CatalogEntry> = when {
    query != null -> searchExercises(catalog, query, recentIds)
    else -> when (chip) {
        PickerChip.Recent -> catalog.associateBy { it.id }.let { byId -> recentIds.mapNotNull(byId::get) }
        PickerChip.All -> catalog
        is PickerChip.Part -> inBodyPart(catalog, chip.part)
    }
}

/**
 * The chip once the catalog has loaded: Recent when he has logged something and hasn't picked a chip
 * or searched yet ([chosen]), so his usual lifts are one tap away; All instead of a Recent with
 * nothing in it; else the chip he is on.
 */
fun chipAfterLoad(current: PickerChip, chosen: Boolean, hasRecent: Boolean): PickerChip = when {
    hasRecent && !chosen -> PickerChip.Recent
    current == PickerChip.Recent && !hasRecent -> PickerChip.All
    else -> current
}
