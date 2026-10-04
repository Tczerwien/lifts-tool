package com.tczerwien.lifts.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tczerwien.lifts.session.DEFAULT_DELOAD_KEEP_PCT
import com.tczerwien.lifts.session.DEFAULT_DELOAD_RIR
import com.tczerwien.lifts.session.DELOAD_KEEP_STEP_PCT
import com.tczerwien.lifts.session.MAX_DELOAD_KEEP_PCT
import com.tczerwien.lifts.session.MAX_RIR
import com.tczerwien.lifts.session.MIN_DELOAD_KEEP_PCT
import com.tczerwien.lifts.session.RirTarget
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Tool settings over the shared SDK DataStore (constructor-injected `lightContext.dataStore`).
 * All keys are prefixed "lifts_" — the DataStore file is shared with SDK push keys.
 *
 * [snapshot] is a blocking read — call from Dispatchers.IO. Setters are suspend.
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    data class Settings(
        val units: WeightUnit,
        val defaultRestSec: Int,
        val warmupRestSec: Int,
        /**
         * The plates he typed in (per side, canonical kg, descending), or null while he never has: then
         * the plates are the standard set of the unit shown ([defaultPlatesKg]), so switching to kg
         * brings kg plates rather than lb plates in odd kg values.
         */
        val customPlatesKg: List<Double>?,
        /** Beep when a rest ends; the flash and keep-awake don't depend on it. */
        val restBeep: Boolean,
        /** The weight steppers' increment when the unit is lb (one of [WeightUnit.weightSteps]). */
        val weightStepLb: Double = WeightUnit.LB.defaultWeightStep,
        /** The same when the unit is kg: each unit keeps its own, so switching back finds it as it was. */
        val weightStepKg: Double = WeightUnit.KG.defaultWeightStep,
        /** The share of each exercise's planned work sets a deload keeps, in percent ([deloadKeep]). */
        val deloadKeepPct: Int = DEFAULT_DELOAD_KEEP_PCT,
        /** The RIR a deload aims for, shown instead of the exercises' own targets ([shownRir]). */
        val deloadRir: RirTarget = DEFAULT_DELOAD_RIR,
    ) {
        /** The plates the calculator loads: his, else the standard set of [units]. */
        val plateInventoryKg: List<Double> get() = customPlatesKg ?: defaultPlatesKg(units)

        /** The weight steppers' increment in the current unit, in display units. */
        val weightStep: Double get() = weightStepFor(units)

        fun weightStepFor(unit: WeightUnit): Double = when (unit) {
            WeightUnit.LB -> weightStepLb
            WeightUnit.KG -> weightStepKg
        }
    }

    /** [Settings.restBeep] as it changes, for the rest-over alert on every screen. */
    val restBeep: Flow<Boolean> = dataStore.data.map { it[REST_BEEP] ?: DEFAULT_REST_BEEP }.distinctUntilChanged()

    /** Blocking read of the current settings — call from Dispatchers.IO. */
    fun snapshot(): Settings = runBlocking {
        val prefs = dataStore.data.first()
        Settings(
            units = prefs[UNITS]?.let { value ->
                WeightUnit.entries.firstOrNull { it.name == value }
            } ?: DEFAULT_UNITS,
            defaultRestSec = prefs[DEFAULT_REST_SEC] ?: DEFAULT_REST_SEC_VALUE,
            warmupRestSec = prefs[WARMUP_REST_SEC] ?: DEFAULT_WARMUP_REST_SEC_VALUE,
            customPlatesKg = prefs[PLATE_INVENTORY_KG]?.let(::parsePlates)?.let(::custom),
            restBeep = prefs[REST_BEEP] ?: DEFAULT_REST_BEEP,
            weightStepLb = validStep(prefs[WEIGHT_STEP_LB], WeightUnit.LB),
            weightStepKg = validStep(prefs[WEIGHT_STEP_KG], WeightUnit.KG),
            deloadKeepPct = validDeloadKeep(prefs[DELOAD_KEEP_PCT]) ?: DEFAULT_DELOAD_KEEP_PCT,
            deloadRir = prefs[DELOAD_RIR]?.let(::parseRir) ?: DEFAULT_DELOAD_RIR,
        )
    }

    /** Whether exercise types were fitted to what he had logged ([com.tczerwien.lifts.data.fittedToHistory]); once per install. */
    suspend fun trackingFitted(): Boolean = dataStore.data.first()[TRACKING_FITTED] == true

    suspend fun markTrackingFitted() {
        dataStore.edit { prefs -> prefs[TRACKING_FITTED] = true }
    }

    /** The bundled catalog's version this phone's exercises are at ([com.tczerwien.lifts.seed.BundledCatalog.VERSION]); 0 before any. */
    suspend fun catalogVersion(): Int = dataStore.data.first()[CATALOG_VERSION] ?: 0

    suspend fun markCatalogVersion(version: Int) {
        dataStore.edit { prefs -> prefs[CATALOG_VERSION] = version }
    }

    /** Forgets [catalogVersion]: a restored log may hold an older catalog, so Home brings it up to date. */
    suspend fun forgetCatalogVersion() {
        dataStore.edit { prefs -> prefs.remove(CATALOG_VERSION) }
    }

    /** Saves [step] as the weight step for [unit]; a step that unit doesn't offer is ignored. */
    suspend fun setWeightStep(unit: WeightUnit, step: Double) {
        if (step !in unit.weightSteps) return
        dataStore.edit { prefs -> prefs[weightStepKey(unit)] = step }
    }

    suspend fun setUnits(units: WeightUnit) {
        dataStore.edit { prefs -> prefs[UNITS] = units.name }
    }

    suspend fun setDefaultRestSec(sec: Int) {
        dataStore.edit { prefs -> prefs[DEFAULT_REST_SEC] = sec }
    }

    suspend fun setWarmupRestSec(sec: Int) {
        dataStore.edit { prefs -> prefs[WARMUP_REST_SEC] = sec }
    }

    suspend fun setRestBeep(on: Boolean) {
        dataStore.edit { prefs -> prefs[REST_BEEP] = on }
    }

    /** Settings › Deload's share; one outside what the stepper offers is ignored. */
    suspend fun setDeloadKeepPct(pct: Int) {
        val valid = validDeloadKeep(pct) ?: return
        dataStore.edit { prefs -> prefs[DELOAD_KEEP_PCT] = valid }
    }

    /** Settings › Deload's RIR. */
    suspend fun setDeloadRir(rir: RirTarget) {
        val stored = encodeRir(rir).takeIf { parseRir(it) != null } ?: return
        dataStore.edit { prefs -> prefs[DELOAD_RIR] = stored }
    }

    suspend fun setPlateInventoryKg(platesKg: List<Double>) {
        dataStore.edit { prefs ->
            prefs[PLATE_INVENTORY_KG] = encodePlates(platesKg)
        }
    }

    /** Writes every setting at once (a restore): all of them land, or none. */
    suspend fun replaceAll(settings: Settings) {
        dataStore.edit { prefs ->
            prefs[UNITS] = settings.units.name
            prefs[DEFAULT_REST_SEC] = settings.defaultRestSec
            prefs[WARMUP_REST_SEC] = settings.warmupRestSec
            val plates = settings.customPlatesKg
            if (plates != null) prefs[PLATE_INVENTORY_KG] = encodePlates(plates) else prefs.remove(PLATE_INVENTORY_KG)
            prefs[REST_BEEP] = settings.restBeep
            prefs[WEIGHT_STEP_LB] = validStep(settings.weightStepLb, WeightUnit.LB)
            prefs[WEIGHT_STEP_KG] = validStep(settings.weightStepKg, WeightUnit.KG)
            prefs[DELOAD_KEEP_PCT] = validDeloadKeep(settings.deloadKeepPct) ?: DEFAULT_DELOAD_KEEP_PCT
            prefs[DELOAD_RIR] = encodeRir(settings.deloadRir).takeIf { parseRir(it) != null } ?: encodeRir(DEFAULT_DELOAD_RIR)
        }
    }

    companion object {
        const val KEY_UNITS = "lifts_units"
        const val KEY_DEFAULT_REST_SEC = "lifts_default_rest_sec"
        const val KEY_WARMUP_REST_SEC = "lifts_warmup_rest_sec"
        const val KEY_PLATE_INVENTORY_KG = "lifts_plate_inventory_kg"
        const val KEY_REST_BEEP = "lifts_rest_beep"
        const val KEY_WEIGHT_STEP_LB = "lifts_weight_step_lb"
        const val KEY_WEIGHT_STEP_KG = "lifts_weight_step_kg"
        const val KEY_DELOAD_KEEP_PCT = "lifts_deload_keep_pct"
        const val KEY_DELOAD_RIR = "lifts_deload_rir"

        private val UNITS = stringPreferencesKey(KEY_UNITS)
        private val DEFAULT_REST_SEC = intPreferencesKey(KEY_DEFAULT_REST_SEC)
        private val WARMUP_REST_SEC = intPreferencesKey(KEY_WARMUP_REST_SEC)
        private val PLATE_INVENTORY_KG = stringPreferencesKey(KEY_PLATE_INVENTORY_KG)
        private val REST_BEEP = booleanPreferencesKey(KEY_REST_BEEP)
        private val WEIGHT_STEP_LB = doublePreferencesKey(KEY_WEIGHT_STEP_LB)
        private val WEIGHT_STEP_KG = doublePreferencesKey(KEY_WEIGHT_STEP_KG)
        private val DELOAD_KEEP_PCT = intPreferencesKey(KEY_DELOAD_KEEP_PCT)
        private val DELOAD_RIR = stringPreferencesKey(KEY_DELOAD_RIR)

        /** Not a setting (backups leave it out): the one-time fit of types to history has run. */
        private val TRACKING_FITTED = booleanPreferencesKey("lifts_tracking_fitted")
        private val CATALOG_VERSION = intPreferencesKey("lifts_catalog_version")

        private fun weightStepKey(unit: WeightUnit) = when (unit) {
            WeightUnit.LB -> WEIGHT_STEP_LB
            WeightUnit.KG -> WEIGHT_STEP_KG
        }

        /** [step] when [unit] offers it, else that unit's default: nothing else ever reaches a stepper. */
        fun validStep(step: Double?, unit: WeightUnit): Double = step?.takeIf { it in unit.weightSteps } ?: unit.defaultWeightStep

        /** [pct] when Settings › Deload offers it (30–70 in steps of 10), else null. */
        fun validDeloadKeep(pct: Int?): Int? =
            pct?.takeIf { it in MIN_DELOAD_KEEP_PCT..MAX_DELOAD_KEEP_PCT && (it - MIN_DELOAD_KEEP_PCT) % DELOAD_KEEP_STEP_PCT == 0 }

        /** An RIR target as stored: "4-5" for a range, "4" for one number. */
        fun encodeRir(rir: RirTarget): String = rir.max?.let { "${rir.min}-$it" } ?: "${rir.min}"

        /** [encodeRir] read back; null unless it is a number from 0 to 5, or a range of two rising ones. */
        fun parseRir(stored: String): RirTarget? {
            val parts = stored.split('-')
            val min = parts[0].toIntOrNull()?.takeIf { it in 0..MAX_RIR } ?: return null
            return when (parts.size) {
                1 -> RirTarget(min)
                2 -> parts[1].toIntOrNull()?.takeIf { it in min + 1..MAX_RIR }?.let { RirTarget(min, it) }
                else -> null
            }
        }

        private val json = Json
        private val PLATES_SERIALIZER = ListSerializer(Double.serializer())

        /** The plate list as stored: a JSON array of kg. */
        fun encodePlates(platesKg: List<Double>): String = json.encodeToString(PLATES_SERIALIZER, platesKg)

        /** [encodePlates] read back; null when it isn't a JSON array of numbers. */
        fun parsePlates(encoded: String): List<Double>? =
            runCatching { json.decodeFromString(PLATES_SERIALIZER, encoded) }.getOrNull()

        val DEFAULT_UNITS = WeightUnit.LB
        const val DEFAULT_REST_SEC_VALUE = 180
        const val DEFAULT_WARMUP_REST_SEC_VALUE = 90
        const val DEFAULT_REST_BEEP = true

        /** lb plate set [45, 25, 10, 5, 2.5] stored canonically in kg. */
        val DEFAULT_PLATE_INVENTORY_KG: List<Double> =
            listOf(45.0, 25.0, 10.0, 5.0, 2.5).map(Format::lbToKg)

        /** The standard kg plate set: 25, 20, 15, 10, 5, 2.5 and 1.25 kg. */
        val DEFAULT_KG_PLATES_KG: List<Double> = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25)

        /**
         * [plates] as his own, or null when they are just the lb default: before B9 every restore wrote
         * that list, so it being stored says nothing about his gym.
         */
        fun custom(plates: List<Double>): List<Double>? =
            plates.takeUnless { it.size == DEFAULT_PLATE_INVENTORY_KG.size && it.zip(DEFAULT_PLATE_INVENTORY_KG).all { (a, b) -> kotlin.math.abs(a - b) < 1e-6 } }

        /** The plates a lifter who never typed his in has in [unit]. */
        fun defaultPlatesKg(unit: WeightUnit): List<Double> = when (unit) {
            WeightUnit.LB -> DEFAULT_PLATE_INVENTORY_KG
            WeightUnit.KG -> DEFAULT_KG_PLATES_KG
        }
    }
}
