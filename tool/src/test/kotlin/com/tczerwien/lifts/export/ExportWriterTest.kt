package com.tczerwien.lifts.export

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The export files are swapped in whole or not at all, and runs never overlap. */
class ExportWriterTest {

    private val dir: File = Files.createTempDirectory("lifts-exports").toFile().resolve("exports")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        dir.parentFile.setWritable(true)
        dir.setWritable(true)
        dir.parentFile.deleteRecursively()
    }

    private suspend fun ExportWriter.settled(): ExportWriter.Status =
        withTimeout(5_000) { status.first { it !is ExportWriter.Status.Preparing } }

    private fun contents(): Map<String, String> =
        dir.listFiles().orEmpty().associate { it.name to it.readText() }

    @Test
    fun `a burst of edits makes one rewrite, once the edits pause`() = runBlocking {
        val runStarts = mutableListOf<Long>()
        var runs = 0
        val writer = ExportWriter(dir, scope, settleMillis = 1_000) {
            runStarts += System.nanoTime()
            runs++
            mapOf("a.md" to "run $runs")
        }
        var lastRequest = 0L
        repeat(10) {
            writer.request()
            lastRequest = System.nanoTime()
            delay(50)
        }
        assertEquals(ExportWriter.Status.Idle, writer.settled())
        assertEquals(1, runs, "ten edits half a second long: one rewrite, after them")
        assertTrue((runStarts.single() - lastRequest) / 1_000_000 >= 900, "it waited for the pause, not the first edit")
        assertEquals(mapOf("a.md" to "run 1"), contents())
    }

    @Test
    fun `edits that never pause still get their files within the longest wait`() = runBlocking {
        val runStarts = mutableListOf<Long>()
        val writer = ExportWriter(dir, scope, settleMillis = 300, maxWaitMillis = 700) {
            runStarts += System.nanoTime()
            mapOf("a.md" to "run")
        }
        val first = System.nanoTime()
        repeat(16) {
            writer.request()
            delay(100)
        }
        writer.settled()
        assertTrue(runStarts.size >= 2, "a run during the burst and one after it: ${runStarts.size}")
        assertTrue((runStarts.first() - first) / 1_000_000 < 1_200, "the first within the longest wait")
    }

    @Test
    fun `Finish, a restore or REGENERATE runs at once, and ends an edit's wait`() = runBlocking {
        val runStarts = mutableListOf<Long>()
        val writer = ExportWriter(dir, scope, settleMillis = 3_000) {
            runStarts += System.nanoTime()
            mapOf("a.md" to "run")
        }
        val asked = System.nanoTime()
        writer.request(urgent = true)
        assertEquals(ExportWriter.Status.Idle, writer.settled())
        assertTrue((runStarts.single() - asked) / 1_000_000 < 1_000, "no wait for a burst")
        // An edit waits; Finish right after it doesn't.
        writer.request()
        delay(100)
        val finish = System.nanoTime()
        writer.request(urgent = true)
        assertEquals(ExportWriter.Status.Idle, writer.settled())
        assertEquals(2, runStarts.size, "one run serves both")
        assertTrue((runStarts.last() - finish) / 1_000_000 < 1_000)
    }

    @Test
    fun `a run writes every file and leaves no temp file behind`() = runBlocking {
        val writer = ExportWriter(dir, scope) { mapOf("a.md" to "one", "b.csv" to "two") }
        writer.request()
        assertEquals(ExportWriter.Status.Idle, writer.settled())
        assertEquals(mapOf("a.md" to "one", "b.csv" to "two"), contents())
    }

    @Test
    fun `a run that fails leaves the previous files untouched`() = runBlocking {
        var fail = false
        var version = 0
        val writer = ExportWriter(dir, scope) {
            version++
            if (fail) error("query failed")
            mapOf("a.md" to "v$version", "b.csv" to "v$version")
        }
        writer.request()
        writer.settled()
        fail = true
        writer.request()
        val failed = writer.settled()
        assertIs<ExportWriter.Status.Failed>(failed)
        assertEquals("query failed", failed.message)
        assertEquals(mapOf("a.md" to "v1", "b.csv" to "v1"), contents())
    }

    @Test
    fun `a write that fails midway keeps every old file and removes its temp files`() = runBlocking {
        val writer = ExportWriter(dir, scope) { mapOf("a.md" to "new", "b.csv" to "new") }
        dir.mkdirs()
        File(dir, "a.md").writeText("old")
        File(dir, "b.csv").writeText("old")
        // A directory where b.csv's temp file should go makes the second write throw.
        File(dir, ".b.csv.tmp").mkdir()
        writer.request()
        assertIs<ExportWriter.Status.Failed>(writer.settled())
        File(dir, ".b.csv.tmp").delete()
        assertEquals(mapOf("a.md" to "old", "b.csv" to "old"), contents())
        // The next run still works.
        writer.request()
        assertEquals(ExportWriter.Status.Idle, writer.settled())
        assertEquals(mapOf("a.md" to "new", "b.csv" to "new"), contents())
    }

    @Test
    fun `requests during a run fold into one more run, and runs never overlap`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val runs = AtomicInteger()
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val writer = ExportWriter(dir, scope) {
            val now = running.incrementAndGet()
            maxRunning.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            val run = runs.incrementAndGet()
            if (run == 1) {
                started.complete(Unit)
                gate.await()
            }
            running.decrementAndGet()
            mapOf("a.md" to "run $run")
        }
        writer.request()
        started.await()
        repeat(5) { writer.request() }
        assertEquals(ExportWriter.Status.Preparing, writer.status.value)
        gate.complete(Unit)
        assertEquals(ExportWriter.Status.Idle, writer.settled())
        assertEquals(2, runs.get(), "five requests during the first run make exactly one more")
        assertEquals(1, maxRunning.get(), "two runs must never write at once")
        assertEquals("run 2", File(dir, "a.md").readText())
    }
}
