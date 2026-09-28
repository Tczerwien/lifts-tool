package com.tczerwien.lifts.settings

import com.tczerwien.lifts.ui.WeightUnit

/**
 * Settings as plain text rows, keyed by their DataStore names: the lifts_settings table a backup
 * carries, since settings live in DataStore rather than in the database file itself.
 */
object SettingsRows {

    /** Rest lengths outside this are not something Lifts wrote; such a row is ignored. */
    private val SANE_REST_SEC = 1..3600

    fun encode(settings: SettingsStore.Settings): Map<String, String> = mapOf(
        SettingsStore.KEY_UNITS to settings.units.name,
        SettingsStore.KEY_DEFAULT_REST_SEC to settings.defaultRestSec.toString(),
        SettingsStore.KEY_WARMUP_REST_SEC to settings.warmupRestSec.toString(),
        SettingsStore.KEY_PLATE_INVENTORY_KG to SettingsStore.encodePlates(settings.plateInventoryKg),
        SettingsStore.KEY_REST_BEEP to settings.restBeep.toString(),
    )

    /**
     * [rows] laid over [current]. Each setting is taken on its own: one that is missing, unknown or
     * unreadable keeps its current value, so a damaged row never costs the others.
     */
    fun decode(rows: Map<String, String>, current: SettingsStore.Settings): SettingsStore.Settings =
        SettingsStore.Settings(
            units = rows[SettingsStore.KEY_UNITS]
                ?.let { value -> WeightUnit.entries.firstOrNull { it.name == value } }
                ?: current.units,
            defaultRestSec = rows[SettingsStore.KEY_DEFAULT_REST_SEC]?.restSec() ?: current.defaultRestSec,
            warmupRestSec = rows[SettingsStore.KEY_WARMUP_REST_SEC]?.restSec() ?: current.warmupRestSec,
            plateInventoryKg = rows[SettingsStore.KEY_PLATE_INVENTORY_KG]
                ?.let(SettingsStore::parsePlates)
                ?.takeIf { plates -> plates.all { it.isFinite() && it > 0 } }
                ?: current.plateInventoryKg,
            restBeep = rows[SettingsStore.KEY_REST_BEEP]?.toBooleanStrictOrNull() ?: current.restBeep,
        )

    private fun String.restSec(): Int? = toIntOrNull()?.takeIf { it in SANE_REST_SEC }
}
