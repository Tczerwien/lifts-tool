package com.tczerwien.lifts.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "lifts.preferences_pb") }
    private val rest = RestStore(store)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    // --- adjust ---

    @Test
    fun `plus 30 extends a running rest, and minus 30 past zero ends it now`() = runBlocking {
        rest.start(totalSec = 90, now = now)
        assertEquals(now + 120_000, rest.adjust(30, clock = { now }))
        assertEquals(now + 5_000, rest.adjust(-120 + 5, clock = { now }))
        assertEquals(now + 1_000, rest.adjust(-30, clock = { now + 1_000 }))
        assertEquals(RestTimerState(endMillis = now + 1_000, totalSec = 90), rest.snapshot())
    }

    @Test
    fun `an ended rest stays ended when plus 30 lands just after 0-00`() = runBlocking {
        rest.start(totalSec = 60, now = now)
        val end = now + 60_000
        assertNull(rest.adjust(30, clock = { end }))
        assertNull(rest.adjust(30, clock = { end + 20 }))
        assertEquals(end, rest.endMillis.first())
    }

    @Test
    fun `plus 30 in the last half second is refused, just before it is taken`() = runBlocking {
        rest.start(totalSec = 60, now = now)
        val end = now + 60_000
        assertNull(rest.adjust(30, clock = { end - REST_ADJUST_CUTOFF_MS }))
        assertEquals(end, rest.endMillis.first())
        assertEquals(end + 30_000, rest.adjust(30, clock = { end - REST_ADJUST_CUTOFF_MS - 1 }))
    }

    @Test
    fun `plus 30 queued behind another save is judged when it lands, not when tapped`() = runBlocking {
        rest.start(totalSec = 60, now = now)
        val end = now + 60_000
        val clock = AtomicLong(end - 5_000) // tapped with 5 s to go
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val other = launch(Dispatchers.IO) {
            store.edit {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val adjusted = async(Dispatchers.IO) { rest.adjust(30, clock = { clock.get() }) }
        delay(200)
        // The rest ends while the +30 waits its turn, and the timer alerts.
        clock.set(end + 100)
        release.complete(Unit)
        assertNull(adjusted.await(), "an ended rest stays ended")
        assertEquals(end, rest.endMillis.first())
        other.join()
    }

    @Test
    fun `nothing to adjust without a rest`() = runBlocking {
        assertNull(rest.adjust(30, clock = { now }))
        assertNull(rest.snapshot())
        assertNull(rest.endMillis.first())
    }

    // --- state (what Active Workout's rest slot shows) ---

    @Test
    fun `the rest slot sees each save, move and clear of the rest`() = runBlocking {
        assertNull(rest.state.first())
        rest.start(totalSec = 90, now = now)
        assertEquals(RestTimerState(endMillis = now + 90_000, totalSec = 90), rest.state.first())
        rest.adjust(30, clock = { now })
        assertEquals(RestTimerState(endMillis = now + 120_000, totalSec = 90), rest.state.first())
        rest.clear()
        assertNull(rest.state.first())
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
