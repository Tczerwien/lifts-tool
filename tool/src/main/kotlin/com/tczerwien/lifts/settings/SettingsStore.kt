package com.tczerwien.lifts.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import kotlinx.coroutines.flow.first
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
    )

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

    suspend fun setPlateInventoryKg(platesKg: List<Double>) {
        dataStore.edit { prefs ->
            prefs[PLATE_INVENTORY_KG] = json.encodeToString(PLATES_SERIALIZER, platesKg)
        }
    }

    private fun decodePlates(encoded: String): List<Double> =
        runCatching { json.decodeFromString(PLATES_SERIALIZER, encoded) }
            .getOrDefault(DEFAULT_PLATE_INVENTORY_KG)

    companion object {
        private val UNITS = stringPreferencesKey("lifts_units")
        private val DEFAULT_REST_SEC = intPreferencesKey("lifts_default_rest_sec")
        private val WARMUP_REST_SEC = intPreferencesKey("lifts_warmup_rest_sec")
        private val PLATE_INVENTORY_KG = stringPreferencesKey("lifts_plate_inventory_kg")

        private val json = Json
        private val PLATES_SERIALIZER = ListSerializer(Double.serializer())

        val DEFAULT_UNITS = WeightUnit.LB
        const val DEFAULT_REST_SEC_VALUE = 180
        const val DEFAULT_WARMUP_REST_SEC_VALUE = 90

        /** lb plate set [45, 25, 10, 5, 2.5] stored canonically in kg. */
        val DEFAULT_PLATE_INVENTORY_KG: List<Double> =
            listOf(45.0, 25.0, 10.0, 5.0, 2.5).map(Format::lbToKg)
    }
}
