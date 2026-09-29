package com.tczerwien.lifts.toolmanager

import com.tczerwien.lifts.toolmanager.RestoreInbox.Upload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RestoreInboxTest {

    private val now = 1_790_000_000_000L

    @Test
    fun `an upload is offered once it has sat still for three seconds`() {
        assertNull(RestoreInbox.pick(listOf(Upload("lifts-latest.db", now - 2_999)), now), "may still be arriving")
        assertEquals("lifts-latest.db", RestoreInbox.pick(listOf(Upload("lifts-latest.db", now - 3_000)), now))
    }

    @Test
    fun `the newest settled upload is the one offered`() {
        val uploads = listOf(
            Upload("older.db", now - 60_000),
            Upload("newer.db", now - 10_000),
            Upload("arriving.db", now - 500),
        )
        assertEquals("newer.db", RestoreInbox.pick(uploads, now))
    }

    @Test
    fun `a file dated in the future is offered, not held until the clock catches up`() {
        assertEquals("from-a-fast-clock.db", RestoreInbox.pick(listOf(Upload("from-a-fast-clock.db", now + 3_600_000)), now))
        assertNull(RestoreInbox.pick(listOf(Upload("just-now.db", now + 1_000)), now), "a second ahead is still arriving")
    }

    @Test
    fun `dot files and an empty folder offer nothing`() {
        assertNull(RestoreInbox.pick(listOf(Upload(".incoming", now - 60_000)), now))
        assertNull(RestoreInbox.pick(emptyList(), now))
    }
}
