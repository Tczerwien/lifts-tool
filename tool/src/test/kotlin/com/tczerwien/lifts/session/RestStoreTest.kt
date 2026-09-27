package com.tczerwien.lifts.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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

/** The rest keys against a real, file-backed DataStore, plus the Rest Timer's opening rule. */
class RestStoreTest {

    private val now = 1_000_000L

    private val dir: File = Files.createTempDirectory("lifts-rest").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rest = RestStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "lifts.preferences_pb") })

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    // --- adjust ---

    @Test
    fun `plus 30 extends a running rest, and minus 30 past zero ends it now`() = runBlocking {
        rest.start(totalSec = 90, now = now)
        assertEquals(now + 120_000, rest.adjust(30, now = now))
        assertEquals(now + 5_000, rest.adjust(-120 + 5, now = now))
        assertEquals(now + 1_000, rest.adjust(-30, now = now + 1_000))
        assertEquals(RestTimerState(endMillis = now + 1_000, totalSec = 90), rest.snapshot())
    }

    @Test
    fun `an ended rest stays ended when plus 30 lands just after 0-00`() = runBlocking {
        rest.start(totalSec = 60, now = now)
        val end = now + 60_000
        assertNull(rest.adjust(30, now = end))
        assertNull(rest.adjust(30, now = end + 20))
        assertEquals(end, rest.endMillis.first())
    }

    @Test
    fun `plus 30 in the last half second is refused, just before it is taken`() = runBlocking {
        rest.start(totalSec = 60, now = now)
        val end = now + 60_000
        assertNull(rest.adjust(30, now = end - REST_ADJUST_CUTOFF_MS))
        assertEquals(end, rest.endMillis.first())
        assertEquals(end + 30_000, rest.adjust(30, now = end - REST_ADJUST_CUTOFF_MS - 1))
    }

    @Test
    fun `nothing to adjust without a rest`() = runBlocking {
        assertNull(rest.adjust(30, now = now))
        assertNull(rest.snapshot())
        assertNull(rest.endMillis.first())
    }

    // --- openingRestEnd ---

    @Test
    fun `a running rest is resumed, from the strip or the alarm icon`() {
        assertEquals(now + 42_000, openingRestEnd(now + 42_000, now, resumeOnly = true))
        assertEquals(now + 42_000, openingRestEnd(now + 42_000, now, resumeOnly = false))
    }

    @Test
    fun `a tap on the strip just as the rest ends shows REST OVER, not a new rest`() {
        assertEquals(now - 300, openingRestEnd(now - 300, now, resumeOnly = true))
        assertEquals(now, openingRestEnd(now, now, resumeOnly = true))
        // Cleared on the way (Finish in flight): still nothing new.
        assertEquals(now, openingRestEnd(null, now, resumeOnly = true))
    }

    @Test
    fun `the alarm icon starts a fresh rest when none is running`() {
        assertNull(openingRestEnd(null, now, resumeOnly = false))
        assertNull(openingRestEnd(now - 60_000, now, resumeOnly = false))
    }
}
