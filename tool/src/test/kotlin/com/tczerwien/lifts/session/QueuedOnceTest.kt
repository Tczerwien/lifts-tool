package com.tczerwien.lifts.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueuedOnceTest {

    @Test
    fun `requests while one waits are served by it, one after it starts queues again`() {
        val backups = QueuedOnce()
        assertTrue(backups.tryQueue(), "Finish: queue a backup")
        assertFalse(backups.tryQueue(), "RESUME and Finish again while it waits: the waiting one holds both")
        assertFalse(backups.tryQueue())
        backups.started()
        assertTrue(backups.tryQueue(), "a Finish after it began reading: it may not hold that one")
    }
}
