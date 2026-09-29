package com.tczerwien.lifts.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tczerwien.lifts.session.ActiveSession
import com.tczerwien.lifts.session.InMemoryLiftsStore
import com.tczerwien.lifts.session.RestStore
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Settings survive a backup and restore, and a restore forgets only the in-workout state. */
class RestoreSettingsTest {

    private val dir: File = Files.createTempDirectory("lifts-restore-settings").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "lifts.preferences_pb") }
    private val settingsStore = SettingsStore(dataStore)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private val defaults = SettingsStore.Settings(
        units = SettingsStore.DEFAULT_UNITS,
        defaultRestSec = SettingsStore.DEFAULT_REST_SEC_VALUE,
        warmupRestSec = SettingsStore.DEFAULT_WARMUP_REST_SEC_VALUE,
        customPlatesKg = null,
        restBeep = SettingsStore.DEFAULT_REST_BEEP,
    )

    /** Every field off its default, so a field that doesn't round-trip shows. */
    private val his = SettingsStore.Settings(
        units = WeightUnit.KG,
        defaultRestSec = 150,
        warmupRestSec = 60,
        customPlatesKg = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 1.0),
        restBeep = false,
        weightStepLb = 2.5,
        weightStepKg = 1.25,
    )

    @Test
    fun `every setting round-trips through the backup rows`() {
        assertEquals(his, SettingsRows.decode(SettingsRows.encode(his), current = defaults))
        assertEquals(defaults, SettingsRows.decode(SettingsRows.encode(defaults), current = his))
    }

    @Test
    fun `lb plates round-trip exactly in kg`() {
        val lb = his.copy(units = WeightUnit.LB, customPlatesKg = listOf(45.0, 35.0, 2.5).map(Format::lbToKg))
        assertEquals(lb, SettingsRows.decode(SettingsRows.encode(lb), current = defaults))
    }

    @Test
    fun `a pre-B9 backup's lb default plates are untouched plates`() {
        val rows = SettingsRows.encode(his).toMutableMap()
        rows[SettingsStore.KEY_PLATE_INVENTORY_KG] = SettingsStore.encodePlates(SettingsStore.DEFAULT_PLATE_INVENTORY_KG)
        assertEquals(null, SettingsRows.decode(rows, current = his).customPlatesKg)
    }

    @Test
    fun `a backup without settings rows keeps the phone's settings`() {
        assertEquals(his, SettingsRows.decode(emptyMap(), current = his))
    }

    @Test
    fun `a damaged row keeps that setting and costs no other`() {
        val rows = SettingsRows.encode(his).toMutableMap()
        rows[SettingsStore.KEY_UNITS] = "STONE"
        rows[SettingsStore.KEY_DEFAULT_REST_SEC] = "-30"
        rows[SettingsStore.KEY_PLATE_INVENTORY_KG] = "[20.0, oops"
        rows["lifts_something_newer"] = "42"
        val restored = SettingsRows.decode(rows, current = defaults)
        assertEquals(
            his.copy(
                units = defaults.units,
                defaultRestSec = defaults.defaultRestSec,
                customPlatesKg = defaults.customPlatesKg,
            ),
            restored,
        )
    }

    @Test
    fun `nonsense values are refused field by field`() {
        val rows = mapOf(
            SettingsStore.KEY_WARMUP_REST_SEC to "99999",
            SettingsStore.KEY_PLATE_INVENTORY_KG to "[20.0, -5.0]",
            SettingsStore.KEY_REST_BEEP to "yes",
            SettingsStore.KEY_WEIGHT_STEP_LB to "3.0",
            SettingsStore.KEY_WEIGHT_STEP_KG to "2.5.1",
        )
        assertEquals(his, SettingsRows.decode(rows, current = his))
    }

    @Test
    fun `replaceAll lands every setting in DataStore`() = runBlocking {
        settingsStore.replaceAll(his)
        assertEquals(his, settingsStore.snapshot())
        assertEquals(false, settingsStore.restBeep.first())
    }

    @Test
    fun `forgetting the workout state clears the current slot and the rest, and nothing else`() = runBlocking {
        val session = ActiveSession(InMemoryLiftsStore(), settingsStore, dataStore)
        val rest = RestStore(dataStore)
        val sdkKey = stringPreferencesKey("push_endpoint")
        settingsStore.replaceAll(his)
        dataStore.edit { it[sdkKey] = "https://example.invalid/up" }
        session.rememberCurrentSlot(workoutId = 7, slotId = 21)
        rest.start(totalSec = 120, now = 1_000L)

        ActiveSession.forgetWorkoutState(dataStore)

        assertNull(session.currentSlotSnapshot(7))
        assertNull(rest.snapshot())
        assertEquals(his, settingsStore.snapshot(), "settings, lifts_rest_beep included, stay")
        assertEquals("https://example.invalid/up", dataStore.data.first()[sdkKey], "keys that aren't ours stay")
    }
}
