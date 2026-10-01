package com.tczerwien.lifts.export

import com.tczerwien.lifts.Perf
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Writes the export files into [dir] (files/shared/exports, which Tool Manager serves), one run at a
 * time. An edit's request waits until [settleMillis] pass with no other request, [maxWaitMillis] at
 * most, so a burst of edits (± taps in the Template Editor, a template built exercise by exercise, a
 * run of renames) makes one full rewrite, not one per edit, and the run's whole-log read doesn't queue
 * on the write lane ahead of his next tap or the screen he opens next. An urgent request (Finish, a
 * restore, REGENERATE, a missing file) runs at once. A request made while a run is going is folded
 * into one more run after it, so the files end up reflecting the latest data however many requests
 * pile up.
 *
 * Every file of a run is first written to a hidden temp file; only when all of them are written are
 * they renamed over the old ones. Tool Manager never serves half a file, and a run that fails while
 * writing (full storage, a query that throws) leaves the previous files as they were; one that fails
 * while renaming (rare: the files are in one folder) leaves the ones renamed so far new and the rest
 * old, until the next run. Nothing escapes: a failure shows in [status], never as a crash.
 *
 * [render] returns file name to content. It runs on [scope], which must outlive any screen.
 */
class ExportWriter(
    private val dir: File,
    scope: CoroutineScope,
    private val settleMillis: Long = SETTLE_MS,
    private val maxWaitMillis: Long = MAX_WAIT_MS,
    private val render: suspend () -> Map<String, String>,
) {
    sealed interface Status {
        /** Nothing running or waiting. */
        data object Idle : Status

        /** A run is going or waiting its turn. */
        data object Preparing : Status

        /**
         * The latest run failed. Failed while writing, the files from the run before are still there;
         * failed while renaming, some are the new ones and the rest the old.
         */
        data class Failed(val message: String) : Status
    }

    private val requests = Channel<Unit>(Channel.CONFLATED)

    /** An urgent request is waiting: the next run doesn't wait for the burst to settle. */
    private val urgent = AtomicBoolean(false)

    /** Requests made so far; a run that ends with this unchanged leaves nothing waiting. */
    private val requested = AtomicLong(0)

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        scope.launch {
            for (request in requests) {
                settle()
                urgent.set(false)
                runOnce()
            }
        }
    }

    /**
     * Waits for the rest of a burst: until [settleMillis] pass with no request, [maxWaitMillis] at most
     * (taps that never stop still get their files), or at once for an [urgent] one. Requests made
     * meanwhile are served by the run that follows. Timed by uptime, so a clock that jumps can't hold
     * a run back.
     */
    private suspend fun settle() {
        val started = System.nanoTime()
        while (!urgent.get()) {
            val left = maxWaitMillis - (System.nanoTime() - started) / 1_000_000
            if (left <= 0) return
            withTimeoutOrNull(minOf(settleMillis, left)) { requests.receive() } ?: return
        }
    }

    /**
     * Asks for fresh files. Returns at once and never throws. [urgent]: no wait for a burst to settle
     * (Finish, so a kill on Workout Complete can't leave the exports behind; a restore; REGENERATE).
     */
    fun request(urgent: Boolean = false) {
        requested.incrementAndGet()
        _status.value = Status.Preparing
        if (urgent) this.urgent.set(true)
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
        /** The quiet spell an edit's run waits for: short, so the files are rarely behind. */
        const val SETTLE_MS = 1_000L

        /** The longest an edit's run waits, however long the burst goes on. */
        const val MAX_WAIT_MS = 5_000L

        const val TRAINING_LOG = "training-log.md"
        const val WORKOUTS_JSON = "workouts.json"
        const val STRONG_CSV = "strong.csv"
        const val BODYWEIGHT_CSV = "bodyweight.csv"

        /** Every export file, in the order the Export screen lists them. */
        val FILE_NAMES = listOf(TRAINING_LOG, WORKOUTS_JSON, STRONG_CSV, BODYWEIGHT_CSV)
    }
}
