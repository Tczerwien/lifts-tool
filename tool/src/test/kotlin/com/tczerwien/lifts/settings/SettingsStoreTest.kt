package com.tczerwien.lifts.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tczerwien.lifts.ui.WeightUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsStoreTest {

    /** In-memory stand-in for the SDK's shared DataStore file. */
    private class FakeDataStore(initial: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test
    fun emptyStoreGivesDefaults() {
        val settings = SettingsStore(FakeDataStore(emptyPreferences())).snapshot()
        assertEquals(SettingsStore.DEFAULT_UNITS, settings.units)
        assertEquals(SettingsStore.DEFAULT_PLATE_INVENTORY_KG, settings.plateInventoryKg)
        assertTrue(settings.restBeep, "the rest-over beep starts on")
    }

    @Test
    fun corruptValuesFallBackToDefaults() {
        val store = SettingsStore(
            FakeDataStore(
                preferencesOf(
                    stringPreferencesKey("lifts_units") to "STONE",
                    stringPreferencesKey("lifts_plate_inventory_kg") to "{not json",
                ),
            ),
        )
        val settings = store.snapshot()
        assertEquals(SettingsStore.DEFAULT_UNITS, settings.units)
        assertEquals(SettingsStore.DEFAULT_PLATE_INVENTORY_KG, settings.plateInventoryKg)
    }

    @Test
    fun writesReadBack() {
        val store = SettingsStore(FakeDataStore(preferencesOf(intPreferencesKey("unrelated_sdk_key") to 7)))
        runBlocking {
            store.setUnits(WeightUnit.KG)
            store.setPlateInventoryKg(listOf(25.0, 1.25))
        }
        val settings = store.snapshot()
        assertEquals(WeightUnit.KG, settings.units)
        assertEquals(listOf(25.0, 1.25), settings.plateInventoryKg)
    }

    @Test
    fun restBeepSwitchesOffAndBackOn() {
        val store = SettingsStore(FakeDataStore(emptyPreferences()))
        runBlocking {
            assertTrue(store.restBeep.first())
            store.setRestBeep(false)
            assertFalse(store.snapshot().restBeep)
            assertFalse(store.restBeep.first())
            store.setRestBeep(true)
            assertTrue(store.restBeep.first())
        }
    }
}
