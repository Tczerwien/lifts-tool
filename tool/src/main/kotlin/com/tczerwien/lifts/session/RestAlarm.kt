package com.tczerwien.lifts.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How late the alert may still go off after a rest ends; a screen shown later than this stays quiet. */
const val REST_ALERT_GRACE_MS = 5_000L

/** How long after a rest ends the screen stays awake and Active Workout reads REST OVER. */
const val REST_OVER_WINDOW_MS = 30_000L

/** How often a screen looks at a running rest; it also wakes exactly at the end. */
const val REST_CHECK_MS = 250L

/** The invert pulse: [REST_PULSE_COUNT] flashes, each [REST_PULSE_PHASE_MS] inverted then as long normal. */
const val REST_PULSE_COUNT = 3
const val REST_PULSE_PHASE_MS = 300L
const val REST_PULSE_TOTAL_MS = REST_PULSE_COUNT * 2 * REST_PULSE_PHASE_MS

/**
 * True when the rest ending at [endMillis] should alert now: this end's countdown was last seen
 * running ([lastSeenRemainingSec] above zero), it has now reached zero, no more than [graceMillis]
 * ago, and this end hasn't alerted already ([firedForEnd]). A screen reopened long after the end, or
 * a rest never seen running (one that was over as it started), stays quiet.
 */
fun restAlertDue(
    endMillis: Long,
    lastSeenRemainingSec: Int?,
    nowMillis: Long,
    firedForEnd: Long?,
    graceMillis: Long = REST_ALERT_GRACE_MS,
): Boolean =
    endMillis != firedForEnd &&
        (lastSeenRemainingSec ?: 0) > 0 &&
        nowMillis >= endMillis &&
        nowMillis - endMillis <= graceMillis

/**
 * Keep the screen on while a rest runs and for [afterMillis] after it ends, so the alert is seen:
 * once the flag clears, the screen's timeout counts from the last touch, which may be minutes back.
 */
fun keepAwake(endMillis: Long?, nowMillis: Long, afterMillis: Long = REST_OVER_WINDOW_MS): Boolean =
    endMillis != null && nowMillis < endMillis + afterMillis

/** The rest ended within the last [REST_OVER_WINDOW_MS]: Active Workout's rest slot reads REST OVER. */
fun restOver(endMillis: Long?, nowMillis: Long): Boolean =
    endMillis != null && nowMillis >= endMillis && nowMillis < endMillis + REST_OVER_WINDOW_MS

/**
 * How long a screen waits before looking at the rest again: every [REST_CHECK_MS] while it runs,
 * landing exactly on the end; then once more when keep-awake lapses. Null when nothing is left to do.
 */
fun nextRestCheckMillis(endMillis: Long, nowMillis: Long): Long? = when {
    nowMillis < endMillis -> minOf(REST_CHECK_MS, endMillis - nowMillis)
    nowMillis < endMillis + REST_OVER_WINDOW_MS -> endMillis + REST_OVER_WINDOW_MS - nowMillis
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
 * ticks. Only the screen on top is composed, and [check] fires once per rest end, so a rest beeps
 * once whichever screens it runs under. [pulseStartedAt] tells the screen on top to run the invert
 * pulse, or finish it after a navigation mid-pulse.
 */
class RestAlarm {

    /** The end last looked at and what was left of it: a new end (a tick, ±30) starts unseen. */
    private var lastSeenEnd: Long? = null
    private var lastSeenRemainingSec: Int? = null
    private var firedForEnd: Long? = null

    private val _pulseStartedAt = MutableStateFlow<Long?>(null)
    val pulseStartedAt: StateFlow<Long?> = _pulseStartedAt.asStateFlow()

    /** Looks at the rest ending at [endMillis]; true exactly once, when it is seen ending ([restAlertDue]). */
    @Synchronized
    fun check(endMillis: Long, nowMillis: Long): Boolean {
        val seenRemaining = lastSeenRemainingSec.takeIf { lastSeenEnd == endMillis }
        val due = restAlertDue(endMillis, seenRemaining, nowMillis, firedForEnd)
        lastSeenEnd = endMillis
        lastSeenRemainingSec = restRemainingSec(endMillis, nowMillis)
        if (due) {
            firedForEnd = endMillis
            _pulseStartedAt.value = nowMillis
        }
        return due
    }

    companion object {
        /** The process's alarm, shared by every screen. */
        val process = RestAlarm()
    }
}
