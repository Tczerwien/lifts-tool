package com.tczerwien.lifts.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        /** Available plates per side, canonical kg, descending. */
        val plateInventoryKg: List<Double>,
        /** Beep when a rest ends; the flash and keep-awake don't depend on it. */
        val restBeep: Boolean,
    )

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
            plateInventoryKg = prefs[PLATE_INVENTORY_KG]?.let(::decodePlates)
                ?: DEFAULT_PLATE_INVENTORY_KG,
            restBeep = prefs[REST_BEEP] ?: DEFAULT_REST_BEEP,
        )
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
            prefs[PLATE_INVENTORY_KG] = encodePlates(settings.plateInventoryKg)
            prefs[REST_BEEP] = settings.restBeep
        }
    }

    private fun decodePlates(encoded: String): List<Double> =
        parsePlates(encoded) ?: DEFAULT_PLATE_INVENTORY_KG

    companion object {
        const val KEY_UNITS = "lifts_units"
        const val KEY_DEFAULT_REST_SEC = "lifts_default_rest_sec"
        const val KEY_WARMUP_REST_SEC = "lifts_warmup_rest_sec"
        const val KEY_PLATE_INVENTORY_KG = "lifts_plate_inventory_kg"
        const val KEY_REST_BEEP = "lifts_rest_beep"

        private val UNITS = stringPreferencesKey(KEY_UNITS)
        private val DEFAULT_REST_SEC = intPreferencesKey(KEY_DEFAULT_REST_SEC)
        private val WARMUP_REST_SEC = intPreferencesKey(KEY_WARMUP_REST_SEC)
        private val PLATE_INVENTORY_KG = stringPreferencesKey(KEY_PLATE_INVENTORY_KG)
        private val REST_BEEP = booleanPreferencesKey(KEY_REST_BEEP)

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
    }
}
