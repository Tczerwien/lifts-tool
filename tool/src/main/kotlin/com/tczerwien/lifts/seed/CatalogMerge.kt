package com.tczerwien.lifts.seed

import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.summary
import com.tczerwien.lifts.library.catalogEntry
import com.tczerwien.lifts.library.nameClash

/** What a catalog update writes: exercises to add, catalog rows to rewrite, and names it left out. */
data class CatalogChanges(
    val inserts: List<ExerciseEntity>,
    val updates: List<ExerciseEntity>,
    /** New catalog exercises left out because an exercise of his already answers to the name. */
    val skipped: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = inserts.isEmpty() && updates.isEmpty()
}

/**
 * The bundled catalog ([bundled], every record) merged into what the phone holds ([current], every
 * exercise row), for an install whose catalog is older than [BundledCatalog.VERSION]:
 * - A catalog exercise the phone lacks goes in, measured as the catalog measures it, unless an
 *   exercise already answers to its name ([nameClash]): the one he made on day one ("Cable Lateral
 *   Raise") stays the one, since two exercises with one name would split his history and the Strong CSV.
 * - A catalog exercise the phone has takes the catalog's facts (equipment, muscles, category, level,
 *   instructions). What is his stays: its name (a rename; a catalog rename doesn't reach it), its cue,
 *   its type, per side and hidden. Rows already matching aren't written.
 * - His own exercises, and catalog rows the new catalog no longer has, stay as they are: workouts refer
 *   to them.
 */
fun mergeCatalog(current: List<ExerciseEntity>, bundled: List<ExerciseEntity>): CatalogChanges {
    val currentById = current.associateBy { it.id }
    val bundledNames = bundled.associate { it.id to it.name }
    val entries = current.map { catalogEntry(it.summary(), catalogName = bundledNames[it.id]) }.toMutableList()
    val inserts = mutableListOf<ExerciseEntity>()
    val updates = mutableListOf<ExerciseEntity>()
    val skipped = mutableListOf<String>()
    for (record in bundled) {
        val existing = currentById[record.id]
        when {
            existing == null -> if (nameClash(record.name, entries) != null) {
                skipped += record.name
            } else {
                inserts += record
                entries += catalogEntry(record.summary())
            }
            existing.isCustom -> Unit
            else -> {
                val updated = existing.copy(
                    equipment = record.equipment,
                    primaryMuscles = record.primaryMuscles,
                    secondaryMuscles = record.secondaryMuscles,
                    category = record.category,
                    level = record.level,
                    instructions = record.instructions,
                )
                if (updated != existing) updates += updated
            }
        }
    }
    return CatalogChanges(inserts, updates, skipped)
}
