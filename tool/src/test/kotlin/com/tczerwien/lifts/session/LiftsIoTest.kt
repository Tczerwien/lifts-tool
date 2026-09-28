package com.tczerwien.lifts.session

import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiftsIoTest {

    @Test
    fun writesNeverRunOnTheCallingThread() {
        // Regression: the block once ran inline on the caller's (main) thread and Room crashed.
        val caller = Thread.currentThread()
        val ran = Collections.synchronizedList(mutableListOf<Thread>())
        val job = LiftsIo.write({ ran += Thread.currentThread() }) { ran += Thread.currentThread() }
        runBlocking { job.join() }
        assertEquals(2, ran.size)
        assertTrue(ran.none { it == caller }, "write and its follow-up must run off the calling thread")
    }

    @Test
    fun writesRunInTheOrderRequested() {
        val order = Collections.synchronizedList(mutableListOf<Int>())
        val jobs = (1..200).map { i -> LiftsIo.write { order += i } }
        runBlocking { jobs.joinAll() }
        assertEquals((1..200).toList(), order)
    }

    @Test
    fun `inLane runs after the writes queued before it and returns its result`() {
        val order = Collections.synchronizedList(mutableListOf<Int>())
        val jobs = (1..50).map { i -> LiftsIo.write { order += i } }
        val seen = runBlocking { LiftsIo.inLane { order.toList() } }
        runBlocking { jobs.joinAll() }
        assertEquals((1..50).toList(), seen)
    }
}
