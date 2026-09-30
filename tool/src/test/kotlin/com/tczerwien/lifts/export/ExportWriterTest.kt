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
    fun `a burst of edits makes two rewrites, the first at once`() = runBlocking {
        var runs = 0
        val writer = ExportWriter(dir, scope, settleMillis = 300) {
            runs++
            mapOf("a.md" to "run $runs")
        }
        repeat(10) {
            writer.request()
            delay(20)
        }
        assertEquals(ExportWriter.Status.Idle, writer.settled())
        assertEquals(2, runs, "ten edits in a fifth of a second: the first at once, one for the other nine")
        assertEquals(mapOf("a.md" to "run 2"), contents())
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
