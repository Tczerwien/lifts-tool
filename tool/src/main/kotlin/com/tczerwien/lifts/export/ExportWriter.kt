package com.tczerwien.lifts.export

import com.tczerwien.lifts.Perf
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Writes the export files into [dir] (files/shared/exports, which Tool Manager serves), one run at a
 * time. A request after a quiet spell runs at once; one within [settleMillis] of a run's end waits out
 * the rest of that time, so a burst of edits (a template built exercise by exercise, a run of renames)
 * makes two full rewrites, not one per edit; a request made
 * while a run is going is folded into one more run after it, so the files end up reflecting the latest
 * data however many requests pile up.
 *
 * Every file of a run is first written to a hidden temp file; only when all of them are written are
 * they renamed over the old ones. Tool Manager never serves half a file, and a run that fails (full
 * storage, a query that throws) leaves the previous files as they were. Nothing escapes: a failure
 * shows in [status], never as a crash.
 *
 * [render] returns file name to content. It runs on [scope], which must outlive any screen.
 */
class ExportWriter(
    private val dir: File,
    scope: CoroutineScope,
    private val settleMillis: Long = SETTLE_MS,
    private val render: suspend () -> Map<String, String>,
) {
    sealed interface Status {
        /** Nothing running or waiting. */
        data object Idle : Status

        /** A run is going or waiting its turn. */
        data object Preparing : Status

        /** The latest run failed; the files from the run before are still there. */
        data class Failed(val message: String) : Status
    }

    private val requests = Channel<Unit>(Channel.CONFLATED)

    /** Requests made so far; a run that ends with this unchanged leaves nothing waiting. */
    private val requested = AtomicLong(0)

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        scope.launch {
            var lastRunEnded = Long.MIN_VALUE / 2
            for (request in requests) {
                // Straight away after a quiet spell (Finish's run starts at once); a request close behind
                // a run waits for the rest of the burst, and the ones made meanwhile are served by it.
                val wait = settleMillis - (System.currentTimeMillis() - lastRunEnded)
                if (wait > 0) {
                    delay(wait)
                    requests.tryReceive()
                }
                runOnce()
                lastRunEnded = System.currentTimeMillis()
            }
        }
    }

    /** Asks for fresh files. Returns at once and never throws. */
    fun request() {
        requested.incrementAndGet()
        _status.value = Status.Preparing
        requests.trySend(Unit)
    }

    private suspend fun runOnce() {
        val servedUpTo = requested.get()
        _status.value = Status.Preparing
        val perf = Perf.start()
        val outcome = try {
            writeAll(render())
            Perf.done("export.run", perf)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
        _status.value = when {
            outcome != null -> Status.Failed(outcome)
            requested.get() != servedUpTo -> Status.Preparing
            else -> Status.Idle
        }
        // A request made just as Idle was worked out set Preparing before Idle landed over it: it is
        // waiting for the next run, so say so (the Export screen offers REGENERATE only when Idle).
        if (outcome == null && requested.get() != servedUpTo) _status.value = Status.Preparing
    }

    private fun writeAll(files: Map<String, String>) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.name}")
        val temps = files.keys.map(::tempFor)
        try {
            files.forEach { (name, content) -> tempFor(name).writeText(content) }
            for (name in files.keys) {
                if (!tempFor(name).renameTo(File(dir, name))) throw IOException("cannot replace $name")
            }
        } finally {
            temps.forEach { it.delete() }
        }
    }

    /** Dot-prefixed: a listing that hides dot files (Tool Manager's default) never shows one mid-run. */
    private fun tempFor(name: String) = File(dir, ".$name.tmp")

    companion object {
        /** How long a run waits for more requests: short, so the files are rarely behind. */
        const val SETTLE_MS = 1_000L

        const val TRAINING_LOG = "training-log.md"
        const val WORKOUTS_JSON = "workouts.json"
        const val STRONG_CSV = "strong.csv"
        const val BODYWEIGHT_CSV = "bodyweight.csv"

        /** Every export file, in the order the Export screen lists them. */
        val FILE_NAMES = listOf(TRAINING_LOG, WORKOUTS_JSON, STRONG_CSV, BODYWEIGHT_CSV)
    }
}
