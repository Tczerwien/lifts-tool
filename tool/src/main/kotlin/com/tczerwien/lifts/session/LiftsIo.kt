package com.tczerwien.lifts.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Saves that outlive the screen. The SDK cancels a screen's viewModelScope the moment the user
 * presses Back, which silently dropped any write still waiting its turn. Writes queued here run
 * on a process-wide scope, one at a time, in the order they were requested.
 */
object LiftsIo {

    /**
     * Never cancelled; for work that must finish even after the screen that started it is gone.
     * Deliberately NOT Dispatchers.IO: [write] starts on the caller's (main) thread, and
     * withContext(Dispatchers.IO) skips the thread hop when the coroutine's own dispatcher is
     * already IO, which would run Room on the main thread and crash.
     */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One lane for every database and settings write in the app. The mutex is fair (FIFO). */
    private val writeLane = Mutex()

    /**
     * Queues [block] (on IO, under the write lane), then runs [after] with its result, still on IO
     * but outside the lane (e.g. a refresh). The lock request is made immediately on the calling
     * thread, so writes requested in order run in order.
     */
    fun <T> write(block: suspend () -> T, after: suspend (T) -> Unit = {}): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = writeLane.withLock { withContext(Dispatchers.IO) { block() } }
            withContext(Dispatchers.IO) { after(result) }
        }

    /** [write] without a follow-up. */
    fun write(block: suspend () -> Unit): Job = write(block, after = {})
}
