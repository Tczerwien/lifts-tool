package com.tczerwien.lifts.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How late the full alert (beep and flash) may still go off after a rest ends; later is a missed end. */
const val REST_ALERT_GRACE_MS = 5_000L

/**
 * How long after the end a missed rest still flashes when he looks again. Past this he has moved on
 * (or left the gym without Finish), and the slot's "REST OVER · 12:34 AGO" says enough.
 */
const val REST_MISSED_MAX_MS = 5 * 60_000L

/** How long after a rest ends the screen stays awake, so the alert is seen. */
const val REST_KEEP_AWAKE_MS = 30_000L

/** How often a screen looks at a running rest; it also wakes exactly at the end. */
const val REST_CHECK_MS = 250L

/** The invert pulse: [REST_PULSE_COUNT] flashes, each [REST_PULSE_PHASE_MS] inverted then as long normal. */
const val REST_PULSE_COUNT = 3
const val REST_PULSE_PHASE_MS = 300L
const val REST_PULSE_TOTAL_MS = REST_PULSE_COUNT * 2 * REST_PULSE_PHASE_MS

/** What a rest's end calls for when a screen looks at it ([restAlertFor]). */
enum class RestAlert {
    /** Nothing: still running, already alerted, or never seen running (over as it started). */
    NONE,

    /** Seen reaching zero: beep and flash. */
    ON_TIME,

    /**
     * It ended out of sight (screen off, app in the background) and is seen now, past the grace
     * window and within [REST_MISSED_MAX_MS]: flash only, the moment he looks again. The countdown
     * kept going; only the screen was dark.
     */
    MISSED,
}

/**
 * What the rest ending at [endMillis] calls for now. It alerts once ([firedForEnd]), and only if this
 * end's countdown was seen running ([lastSeenRemainingSec] above zero): [RestAlert.ON_TIME] up to
 * [graceMillis] after the end, [RestAlert.MISSED] after that up to [missedMaxMillis], then nothing.
 */
fun restAlertFor(
    endMillis: Long,
    lastSeenRemainingSec: Int?,
    nowMillis: Long,
    firedForEnd: Long?,
    graceMillis: Long = REST_ALERT_GRACE_MS,
    missedMaxMillis: Long = REST_MISSED_MAX_MS,
): RestAlert = when {
    endMillis == firedForEnd || (lastSeenRemainingSec ?: 0) <= 0 || nowMillis < endMillis -> RestAlert.NONE
    nowMillis - endMillis <= graceMillis -> RestAlert.ON_TIME
    nowMillis - endMillis <= missedMaxMillis -> RestAlert.MISSED
    else -> RestAlert.NONE
}

/**
 * Keep the screen on while a rest runs and for [afterMillis] after it ends, so the alert is seen:
 * once the flag clears, the screen's timeout counts from the last touch, which may be minutes back.
 */
fun keepAwake(endMillis: Long?, nowMillis: Long, afterMillis: Long = REST_KEEP_AWAKE_MS): Boolean =
    endMillis != null && nowMillis < endMillis + afterMillis

/**
 * The saved rest has ended: Active Workout's rest slot reads "REST OVER · 1:20 AGO" ([restOverSec])
 * until the next tick, Skip, Finish or new workout replaces or clears it.
 */
fun restOver(endMillis: Long?, nowMillis: Long): Boolean =
    endMillis != null && nowMillis >= endMillis

/** Whole seconds since the rest ending at [endMillis] ended; 0 before the end. */
fun restOverSec(endMillis: Long, nowMillis: Long): Int =
    ((nowMillis - endMillis) / 1000L).coerceAtLeast(0L).toInt()

/**
 * How long a screen waits before looking at the rest again: every [REST_CHECK_MS] while it runs,
 * landing exactly on the end; then once more when keep-awake lapses. Null when nothing is left to do.
 */
fun nextRestCheckMillis(endMillis: Long, nowMillis: Long): Long? = when {
    nowMillis < endMillis -> minOf(REST_CHECK_MS, endMillis - nowMillis)
    nowMillis < endMillis + REST_KEEP_AWAKE_MS -> endMillis + REST_KEEP_AWAKE_MS - nowMillis
    else -> null
}

/** Whether the pulse that started at [startedAt] shows inverted colours now; null once it is over. */
fun pulseInverted(startedAt: Long, nowMillis: Long): Boolean? {
    val elapsed = nowMillis - startedAt
    if (elapsed < 0 || elapsed >= REST_PULSE_TOTAL_MS) return null
    return (elapsed / REST_PULSE_PHASE_MS) % 2 == 0L
}

/** Milliseconds until the pulse that started at [startedAt] flips colour again. */
fun msToNextPulsePhase(startedAt: Long, nowMillis: Long): Long =
    REST_PULSE_PHASE_MS - Math.floorMod(nowMillis - startedAt, REST_PULSE_PHASE_MS)

/**
 * The one owner of the rest-over alert in the process. Every screen's frame calls [check] as it
 * ticks. Only the screen on top is composed, and [check] alerts once per rest end, so a rest beeps
 * once whichever screens it runs under. [pulseStartedAt] tells the screen on top to run the invert
 * pulse (on time or missed), or finish it after a navigation mid-pulse.
 */
class RestAlarm {

    /** The end last looked at and what was left of it: a new end (a tick, ±30) starts unseen. */
    private var lastSeenEnd: Long? = null
    private var lastSeenRemainingSec: Int? = null
    private var firedForEnd: Long? = null

    private val _pulseStartedAt = MutableStateFlow<Long?>(null)
    val pulseStartedAt: StateFlow<Long?> = _pulseStartedAt.asStateFlow()

    /**
     * Marks the rest ending at [endMillis] as seen running, as it is saved or moved. A tick followed
     * at once by the power button still counts as seen, though no frame had looked at it yet. A rest
     * over as it is saved (a −30 past zero) is armed with nothing left, so it stays quiet.
     */
    @Synchronized
    fun arm(endMillis: Long, nowMillis: Long) {
        lastSeenEnd = endMillis
        lastSeenRemainingSec = restRemainingSec(endMillis, nowMillis)
    }

    /**
     * Looks at the rest ending at [endMillis] ([restAlertFor]): anything but [RestAlert.NONE] comes
     * back exactly once per end, and starts the pulse.
     */
    @Synchronized
    fun check(endMillis: Long, nowMillis: Long): RestAlert {
        val seenRemaining = lastSeenRemainingSec.takeIf { lastSeenEnd == endMillis }
        val alert = restAlertFor(endMillis, seenRemaining, nowMillis, firedForEnd)
        lastSeenEnd = endMillis
        lastSeenRemainingSec = restRemainingSec(endMillis, nowMillis)
        if (alert != RestAlert.NONE) {
            firedForEnd = endMillis
            _pulseStartedAt.value = nowMillis
        }
        return alert
    }

    companion object {
        /** The process's alarm, shared by every screen. */
        val process = RestAlarm()
    }
}
