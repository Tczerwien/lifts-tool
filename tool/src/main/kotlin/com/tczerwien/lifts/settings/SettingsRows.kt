package com.tczerwien.lifts.settings

import com.tczerwien.lifts.ui.WeightUnit

/**
 * Settings as plain text rows, keyed by their DataStore names: the lifts_settings table a backup
 * carries, since settings live in DataStore rather than in the database file itself.
 */
object SettingsRows {

    /** The plates row of a lifter who never typed his in: the unit's standard set, whichever unit. */
    private const val DEFAULT_PLATES = "default"

    /** Rest lengths outside this are not something Lifts wrote; such a row is ignored. */
    private val SANE_REST_SEC = 1..3600

    fun encode(settings: SettingsStore.Settings): Map<String, String> = mapOf(
        SettingsStore.KEY_UNITS to settings.units.name,
        SettingsStore.KEY_DEFAULT_REST_SEC to settings.defaultRestSec.toString(),
        SettingsStore.KEY_WARMUP_REST_SEC to settings.warmupRestSec.toString(),
        SettingsStore.KEY_PLATE_INVENTORY_KG to (settings.customPlatesKg?.let(SettingsStore::encodePlates) ?: DEFAULT_PLATES),
        SettingsStore.KEY_REST_BEEP to settings.restBeep.toString(),
        SettingsStore.KEY_WEIGHT_STEP_LB to settings.weightStepLb.toString(),
        SettingsStore.KEY_WEIGHT_STEP_KG to settings.weightStepKg.toString(),
        SettingsStore.KEY_DELOAD_KEEP_PCT to settings.deloadKeepPct.toString(),
        SettingsStore.KEY_DELOAD_RIR to SettingsStore.encodeRir(settings.deloadRir),
        // Written even with none ("{}"), so restoring this backup clears targets set since.
        SettingsStore.KEY_MUSCLE_TARGETS to SettingsStore.encodeTargets(settings.muscleTargets),
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
            customPlatesKg = when (val row = rows[SettingsStore.KEY_PLATE_INVENTORY_KG]) {
                null -> current.customPlatesKg
                DEFAULT_PLATES -> null
                else -> {
                    val plates = SettingsStore.parsePlates(row)?.takeIf { list -> list.all { it.isFinite() && it > 0 } }
                    // Unreadable keeps his; the old lb default (every pre-B9 backup has it) is untouched.
                    if (plates == null) current.customPlatesKg else SettingsStore.custom(plates)
                }
            },
            restBeep = rows[SettingsStore.KEY_REST_BEEP]?.toBooleanStrictOrNull() ?: current.restBeep,
            weightStepLb = rows[SettingsStore.KEY_WEIGHT_STEP_LB]?.step(WeightUnit.LB) ?: current.weightStepLb,
            weightStepKg = rows[SettingsStore.KEY_WEIGHT_STEP_KG]?.step(WeightUnit.KG) ?: current.weightStepKg,
            // A backup from before B14 has neither: the phone's deload settings stay.
            deloadKeepPct = SettingsStore.validDeloadKeep(rows[SettingsStore.KEY_DELOAD_KEEP_PCT]?.toIntOrNull()) ?: current.deloadKeepPct,
            deloadRir = rows[SettingsStore.KEY_DELOAD_RIR]?.let(SettingsStore::parseRir) ?: current.deloadRir,
            // A backup from before B17 (any v1 backup among them) has none: the phone's targets stay.
            muscleTargets = rows[SettingsStore.KEY_MUSCLE_TARGETS]?.let(SettingsStore::parseTargets) ?: current.muscleTargets,
        )

    private fun String.restSec(): Int? = toIntOrNull()?.takeIf { it in SANE_REST_SEC }

    /** A weight step [unit] offers, else null (the current one stays). */
    private fun String.step(unit: WeightUnit): Double? = toDoubleOrNull()?.takeIf { it in unit.weightSteps }
}
