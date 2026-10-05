package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.SetEntity

/**
 * Drop sets: a lighter set done straight after a work set, as part of it. No column says which set a drop
 * belongs to: its position does. It belongs to the work set above it, with only drops between (a chain:
 * each one lighter again, all of the same set), is numbered with it ("3 D" under set 3), and is a third kind
 * of row wherever rows are matched or counted, so work sets are counted and compared without their drops.
 * A drop with no work set above it (the first row, or one under a warm-up: the marker can make it) belongs
 * to none and is a set of its own, as every drop was before B16. Pure, covered by DropSetsTest.
 */

/** What a row is wherever rows are matched or counted ([rowKinds]). */
enum class RowKind {
    WARMUP,

    /** Normal, to failure, or a drop that belongs to no work set. */
    WORK,

    /** A drop that belongs to the work set above it ([dropParent]). */
    DROP,
}

/** The kind of each of one slot's rows, from their [types] in position order (DR-1). */
fun rowKinds(types: List<String>): List<RowKind> {
    // Whether the rows since the last warm-up began with a work set, so a drop here has one to belong to.
    var underWork = false
    return types.map { type ->
        when {
            type == SET_TYPE_WARMUP -> RowKind.WARMUP.also { underWork = false }
            type == SET_TYPE_DROP && underWork -> RowKind.DROP
            else -> RowKind.WORK.also { underWork = true }
        }
    }
}

/** [rowKinds] of one slot's [rows] in position order. */
@JvmName("rowKindsOfSets")
fun rowKinds(rows: List<SetEntity>): List<RowKind> = rowKinds(rows.map { it.type })

/**
 * The index of the work set row [index] of [rows] (one slot, in position order) belongs to as a drop: the
 * nearest work set above it. Null when it is no drop, or a drop that belongs to none (DR-1).
 */
fun dropParent(rows: List<SetEntity>, index: Int): Int? {
    val kinds = rowKinds(rows)
    if (kinds.getOrNull(index) != RowKind.DROP) return null
    return (index - 1 downTo 0).first { kinds[it] == RowKind.WORK }
}

/**
 * The set markers of one slot's [rows] in position order: "W" for a warm-up, "1".."n" for the work sets
 * in order, and a drop its work set's number, so the drop under set 3 reads "3 D" (the row adds the "D")
 * and the set after it is still 4 (DR-1).
 */
fun setMarkers(rows: List<SetEntity>): List<String> {
    var number = 0
    return rowKinds(rows).map { kind ->
        when (kind) {
            RowKind.WARMUP -> "W"
            RowKind.WORK -> (++number).toString()
            RowKind.DROP -> number.toString()
        }
    }
}
