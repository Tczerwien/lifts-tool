package com.tczerwien.lifts

import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Timings for measuring speed on a phone, logged under [TAG] only when it was switched on before the
 * process started: `adb shell setprop log.tag.LiftsPerf DEBUG`. Off, each call is one field read.
 * verify/b11/perf.md reads these lines; nothing in the app depends on them.
 */
object Perf {
    const val TAG = "LiftsPerf"

    /** Read once per process; false in unit tests, where android.util.Log isn't there. */
    val on: Boolean = try {
        Log.isLoggable(TAG, Log.DEBUG)
    } catch (e: RuntimeException) {
        false
    }

    /** A start time for [done]. */
    fun start(): Long = if (on) System.nanoTime() else 0L

    /** "[event] 12.3 ms[ detail]": the time since [startNanos] ([start]). */
    fun done(event: String, startNanos: Long, detail: String = "") {
        if (!on) return
        val ms = (System.nanoTime() - startNanos) / 1_000_000.0
        Log.d(TAG, "$event ${"%.1f".format(ms)} ms${if (detail.isEmpty()) "" else " $detail"}")
    }

    private val marked = HashSet<String>()

    /**
     * "[event] N ms after launch", the first time only: a screen's first frame with its data, counted from
     * when the system was asked to start the process (a tap on the icon, `am start`).
     */
    fun firstSinceStart(event: String) {
        if (!on || !synchronized(marked) { marked.add(event) }) return
        Log.d(TAG, "$event ${SystemClock.uptimeMillis() - Process.getStartRequestedUptimeMillis()} ms after launch")
    }
}
