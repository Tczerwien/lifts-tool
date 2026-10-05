package com.tczerwien.lifts.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tczerwien.lifts.session.ActiveSession
import com.tczerwien.lifts.session.InMemoryLiftsStore
import com.tczerwien.lifts.session.RestStore
import com.tczerwien.lifts.session.RirTarget
import com.tczerwien.lifts.ui.Format
import com.tczerwien.lifts.ui.WeightUnit
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

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
        deloadKeepPct = 60,
        deloadRir = RirTarget(3, 4),
        muscleTargets = mapOf("chest" to 13, "front delts" to 3),
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
    fun `a backup from before deloads keeps the phone's deload settings`() {
        val older = SettingsRows.encode(defaults) - SettingsStore.KEY_DELOAD_KEEP_PCT - SettingsStore.KEY_DELOAD_RIR
        val restored = SettingsRows.decode(older, current = his)
        assertEquals(listOf(60, RirTarget(3, 4)), listOf(restored.deloadKeepPct, restored.deloadRir))
        assertEquals(defaults.copy(deloadKeepPct = 60, deloadRir = RirTarget(3, 4)), restored, "everything else from the backup")
    }

    @Test
    fun `a backup from before weekly targets keeps the phone's targets`() {
        // Every v1 backup is one, and every 0.13.0 to 0.17.0 one: they have no targets row.
        val older = SettingsRows.encode(defaults) - SettingsStore.KEY_MUSCLE_TARGETS
        val restored = SettingsRows.decode(older, current = his)
        assertEquals(his.muscleTargets, restored.muscleTargets)
        assertEquals(defaults.copy(muscleTargets = his.muscleTargets), restored, "everything else from the backup")
    }

    @Test
    fun `a backup made with no targets clears the phone's`() {
        assertEquals("{}", SettingsRows.encode(defaults)[SettingsStore.KEY_MUSCLE_TARGETS])
        assertEquals(emptyMap(), SettingsRows.decode(SettingsRows.encode(defaults), current = his).muscleTargets)
    }

    @Test
    fun `targets are stored as one object, read entry by entry`() {
        assertEquals("{\"chest\":13,\"front delts\":3}", SettingsStore.encodeTargets(mapOf("front delts" to 3, "chest" to 13)))
        assertEquals(
            mapOf("chest" to 12, "calves" to 7),
            SettingsStore.parseTargets("{\"chest\":12,\"lats\":0,\"traps\":41,\"neck\":2.5,\"abdominals\":\"3\",\"calves\":7,\"\":4}"),
            "0, past 40, a fraction, a string and a blank name are no targets",
        )
        assertEquals(emptyMap(), SettingsStore.parseTargets("{}"))
        for (bad in listOf("", "[1]", "{oops", "12", "null")) assertNull(SettingsStore.parseTargets(bad), bad)
    }

    @Test
    fun `the deload RIR is stored as one value`() {
        assertEquals("4-5", SettingsStore.encodeRir(RirTarget(4, 5)))
        assertEquals("2", SettingsStore.encodeRir(RirTarget(2)))
        assertEquals(RirTarget(4, 5), SettingsStore.parseRir("4-5"))
        assertEquals(RirTarget(0), SettingsStore.parseRir("0"))
        for (bad in listOf("", "x", "6", "-1", "3-3", "4-3", "4-6", "1-2-3", "4–5")) assertNull(SettingsStore.parseRir(bad), bad)
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
            SettingsStore.KEY_DELOAD_KEEP_PCT to "55",
            SettingsStore.KEY_DELOAD_RIR to "4-9",
            SettingsStore.KEY_MUSCLE_TARGETS to "{\"chest\": oops",
        )
        assertEquals(his, SettingsRows.decode(rows, current = his))
        for (pct in listOf("20", "80", "half")) {
            assertEquals(60, SettingsRows.decode(mapOf(SettingsStore.KEY_DELOAD_KEEP_PCT to pct), current = his).deloadKeepPct, pct)
        }
    }

    @Test
    fun `replaceAll lands every setting in DataStore`() = runBlocking {
        settingsStore.replaceAll(his)
        assertEquals(his, settingsStore.snapshot())
        assertEquals(false, settingsStore.restBeep.first())
    }

    @Test
    fun `restoring a backup made with no targets takes the phone's out of DataStore`() = runBlocking {
        settingsStore.replaceAll(his)
        settingsStore.replaceAll(SettingsRows.decode(SettingsRows.encode(defaults), current = settingsStore.snapshot()))
        assertEquals(emptyMap(), settingsStore.snapshot().muscleTargets)
        assertNull(dataStore.data.first()[stringPreferencesKey(SettingsStore.KEY_MUSCLE_TARGETS)], "the key itself is gone")
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
