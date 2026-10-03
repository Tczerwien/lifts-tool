package com.tczerwien.lifts.library

/**
 * Moving the item at [index] of an ordered list by [delta] places (-1 up, +1 down), as the template
 * lists do: the positions to write, as (item, new position), for each item whose position changes.
 * Positions come out 0..n-1, so a gap or a repeat left by older data is mended on the way. Null when
 * the item can't move: [index] is outside the list (the item is gone), or it is already at that end.
 */
fun <T> reorderPositions(items: List<T>, index: Int, delta: Int, positionOf: (T) -> Int): List<Pair<T, Int>>? =
    moved(items, index, delta)?.let { renumber(it, positionOf) }

/** [items] with the one at [index] moved [delta] places, the rest closing round it; null when it can't move ([reorderPositions]). */
fun <T> moved(items: List<T>, index: Int, delta: Int): List<T>? {
    val target = index + delta
    if (index !in items.indices || target !in items.indices || delta == 0) return null
    return items.toMutableList().apply { add(target, removeAt(index)) }
}

/** Positions 0..n-1 in list order: (item, new position) for each item that has another (after a removal). */
fun <T> renumber(items: List<T>, positionOf: (T) -> Int): List<Pair<T, Int>> =
    items.withIndex()
        .filter { (position, item) -> positionOf(item) != position }
        .map { (position, item) -> item to position }
