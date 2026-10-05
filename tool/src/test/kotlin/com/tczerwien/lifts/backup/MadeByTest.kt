package com.tczerwien.lifts.backup

import com.tczerwien.lifts.settings.SettingsRows
import com.tczerwien.lifts.settings.SettingsStore
import com.tczerwien.lifts.ui.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun `a backup this Lifts or an older one made restores`() {
        assertNull(MadeBy.refusal(MadeBy.rows(19, "0.19.0"), 19, "0.19.0"), "made by this Lifts")
        assertNull(MadeBy.refusal(MadeBy.rows(18, "0.18.0"), 19, "0.19.0"), "made by an older one")
        assertNull(MadeBy.refusal(mapOf(MadeBy.KEY_VERSION_CODE to "-5"), 19, "0.19.0"), "a number below any Lifts")
    }

    @Test
    fun `a backup a newer Lifts made is refused, naming both`() {
        assertEquals(
            "a newer Lifts made it (0.20.0; this is 0.19.0); update Lifts first",
            MadeBy.refusal(SettingsRows.encode(settings) + MadeBy.rows(20, "0.20.0"), 19, "0.19.0"),
        )
        assertEquals(
            "a newer Lifts made it (version 20; this is 0.19.0); update Lifts first",
            MadeBy.refusal(mapOf(MadeBy.KEY_VERSION_CODE to " 20 ", MadeBy.KEY_VERSION_NAME to " "), 19, "0.19.0"),
            "no name to show: its number",
        )
        assertEquals(
            "a newer Lifts made it (version 99999999999; this is 0.19.0); update Lifts first",
            MadeBy.refusal(mapOf(MadeBy.KEY_VERSION_CODE to "99999999999"), 19, "0.19.0"),
            "a number past the Int range is still a number",
        )
    }

    @Test
    fun `the refusal names the newer Lifts in a few words`() {
        fun named(name: String) =
            MadeBy.refusal(mapOf(MadeBy.KEY_VERSION_CODE to "20", MadeBy.KEY_VERSION_NAME to name), 19, "0.19.0")
        assertEquals("a newer Lifts made it (0.20.0; this is 0.19.0); update Lifts first", named(" 0.20.0 "), "trimmed")
        assertEquals(
            "a newer Lifts made it (version 20; this is 0.19.0); update Lifts first",
            named("0.19.0"),
            "a build that kept this Lifts' name: its number",
        )
        assertEquals(
            "a newer Lifts made it (version 20; this is 0.19.0); update Lifts first",
            named("x".repeat(21)),
            "a hand-made name too long for the screen: its number",
        )
        assertEquals("a newer Lifts made it (${"x".repeat(20)}; this is 0.19.0); update Lifts first", named("x".repeat(20)))
    }

    @Test
    fun `a backup with no version row restores`() {
        assertNull(MadeBy.refusal(emptyMap(), 19, "0.19.0"), "no settings at all: a database pulled over adb")
        assertNull(MadeBy.refusal(SettingsRows.encode(settings), 19, "0.19.0"), "every backup from before 0.19.0")
        assertNull(MadeBy.refusal(mapOf(MadeBy.KEY_VERSION_NAME to "9.0.0"), 19, "0.19.0"), "a name alone says nothing")
    }

    @Test
    fun `a version that isn't a whole number counts as none`() {
        listOf("", "abc", "20.5", "0x20").forEach { code ->
            assertNull(MadeBy.refusal(mapOf(MadeBy.KEY_VERSION_CODE to code), 19, "0.19.0"), "\"$code\"")
        }
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
