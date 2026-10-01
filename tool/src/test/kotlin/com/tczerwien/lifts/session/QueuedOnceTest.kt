package com.tczerwien.lifts.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueuedOnceTest {

    @Test
    fun `requests while one waits are served by it, one after it starts queues again`() {
        val backups = QueuedOnce()
        assertTrue(backups.tryQueue(), "Back up now: queue a backup")
        assertFalse(backups.tryQueue(), "tapped again while it waits: the waiting one holds both")
        assertFalse(backups.tryQueue())
        backups.started()
        assertTrue(backups.tryQueue(), "a tap after it began reading: it may not hold what changed since")
    }
}
