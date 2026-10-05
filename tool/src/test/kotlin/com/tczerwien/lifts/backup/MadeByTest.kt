package com.tczerwien.lifts.backup

import com.tczerwien.lifts.settings.SettingsRows
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.ui.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MadeByTest {

    private val settings = SettingsStore.Settings(
        units = WeightUnit.LB,
        defaultRestSec = 180,
        warmupRestSec = 90,
        customPlatesKg = null,
        restBeep = true,
        muscleTargets = mapOf("chest" to 12),
    )

    @Test
    fun `a backup names the Lifts that wrote it in two rows`() {
        assertEquals(
            mapOf("lifts_made_by_version_code" to "19", "lifts_made_by_version_name" to "0.19.0"),
            MadeBy.rows(19, "0.19.0"),
        )
    }

    @Test
    fun `the rows are no setting a restore takes`() {
        val rows = MadeBy.rows(20, "0.20.0")
        assertTrue(rows.keys.none { it in SettingsRows.encode(settings) }, "no key shared with a setting")
        val current = settings.copy(units = WeightUnit.KG, muscleTargets = emptyMap())
        assertEquals(
            SettingsRows.decode(SettingsRows.encode(settings), current),
            SettingsRows.decode(SettingsRows.encode(settings) + rows, current),
        )
    }
}
