package com.tczerwien.lifts.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
        )
    }

    /** Whether exercise types were fitted to what he had logged ([com.tczerwien.lifts.data.fittedToHistory]); once per install. */
    suspend fun trackingFitted(): Boolean = dataStore.data.first()[TRACKING_FITTED] == true

    suspend fun markTrackingFitted() {
        dataStore.edit { prefs -> prefs[TRACKING_FITTED] = true }
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

        private val UNITS = stringPreferencesKey(KEY_UNITS)
        private val DEFAULT_REST_SEC = intPreferencesKey(KEY_DEFAULT_REST_SEC)
        private val WARMUP_REST_SEC = intPreferencesKey(KEY_WARMUP_REST_SEC)
        private val PLATE_INVENTORY_KG = stringPreferencesKey(KEY_PLATE_INVENTORY_KG)
        private val REST_BEEP = booleanPreferencesKey(KEY_REST_BEEP)
        private val WEIGHT_STEP_LB = doublePreferencesKey(KEY_WEIGHT_STEP_LB)
        private val WEIGHT_STEP_KG = doublePreferencesKey(KEY_WEIGHT_STEP_KG)

        /** Not a setting (backups leave it out): the one-time fit of types to history has run. */
        private val TRACKING_FITTED = booleanPreferencesKey("lifts_tracking_fitted")

        private fun weightStepKey(unit: WeightUnit) = when (unit) {
            WeightUnit.LB -> WEIGHT_STEP_LB
            WeightUnit.KG -> WEIGHT_STEP_KG
        }

        /** [step] when [unit] offers it, else that unit's default: nothing else ever reaches a stepper. */
        fun validStep(step: Double?, unit: WeightUnit): Double = step?.takeIf { it in unit.weightSteps } ?: unit.defaultWeightStep

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
