package com.tczerwien.lifts.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
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
        assertEquals(null, settings.customPlatesKg)
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
    fun weightStepIsKeptPerUnitAndOnlyFromTheOffer() {
        val store = SettingsStore(FakeDataStore(emptyPreferences()))
        assertEquals(5.0, store.snapshot().weightStep, "lb starts at 5")
        runBlocking {
            store.setWeightStep(WeightUnit.LB, 2.5)
            store.setWeightStep(WeightUnit.KG, 1.25)
            store.setWeightStep(WeightUnit.KG, 3.0)
        }
        val lb = store.snapshot()
        assertEquals(2.5, lb.weightStep)
        runBlocking { store.setUnits(WeightUnit.KG) }
        assertEquals(1.25, store.snapshot().weightStep, "kg keeps its own step; 3 kg is not on offer")
        runBlocking { store.setUnits(WeightUnit.LB) }
        assertEquals(2.5, store.snapshot().weightStep, "and switching back finds lb's as it was")
    }

    @Test
    fun aStoredStepOffTheOfferReadsAsTheDefault() {
        val store = SettingsStore(
            FakeDataStore(preferencesOf(doublePreferencesKey("lifts_weight_step_kg") to 0.5, stringPreferencesKey("lifts_units") to "KG")),
        )
        assertEquals(2.5, store.snapshot().weightStep)
    }

    @Test
    fun untouchedPlatesFollowTheUnitAndHisStay() {
        val store = SettingsStore(FakeDataStore(emptyPreferences()))
        assertEquals(SettingsStore.DEFAULT_PLATE_INVENTORY_KG, store.snapshot().plateInventoryKg)
        runBlocking { store.setUnits(WeightUnit.KG) }
        assertEquals(listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25), store.snapshot().plateInventoryKg, "kg plates in kg")
        assertEquals(null, store.snapshot().customPlatesKg)
        val his = listOf(20.0, 10.0, 1.0)
        runBlocking {
            store.setPlateInventoryKg(his)
            store.setUnits(WeightUnit.LB)
        }
        assertEquals(his, store.snapshot().plateInventoryKg, "plates he typed in are real plates in any unit")
    }

    @Test
    fun theOldStoredLbDefaultIsUntouched() {
        val store = SettingsStore(FakeDataStore(emptyPreferences()))
        runBlocking {
            store.setPlateInventoryKg(SettingsStore.DEFAULT_PLATE_INVENTORY_KG)
            store.setUnits(WeightUnit.KG)
        }
        assertEquals(SettingsStore.DEFAULT_KG_PLATES_KG, store.snapshot().plateInventoryKg)
    }

    @Test
    fun replaceAllWithUntouchedPlatesForgetsStoredOnes() {
        val store = SettingsStore(FakeDataStore(emptyPreferences()))
        runBlocking {
            store.setPlateInventoryKg(listOf(20.0))
            store.replaceAll(store.snapshot().copy(customPlatesKg = null, units = WeightUnit.KG))
        }
        assertEquals(SettingsStore.DEFAULT_KG_PLATES_KG, store.snapshot().plateInventoryKg)
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
