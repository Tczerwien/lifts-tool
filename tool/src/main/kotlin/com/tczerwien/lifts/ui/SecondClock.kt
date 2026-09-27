package com.tczerwien.lifts.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.delay

/** Lands a tick just past the boundary, never just before it, so a clock never skips a second. */
private const val TICK_SLACK_MS = 5L

/**
 * Milliseconds until the next whole second counted from [anchor]. A workout's start is the anchor
 * for its elapsed clock, a rest's end for its countdown: both change on those boundaries.
 */
fun msUntilNextSecond(anchor: Long, now: Long): Long = 1_000L - Math.floorMod(now - anchor, 1_000L) + TICK_SLACK_MS

/**
 * Wall-clock now, updated as each whole second passes since [anchor] ([msUntilNextSecond]), for a
 * small composable that draws a clock: only it redraws, once a second. Frames drive it, so it
 * stops while the app is in the background and catches up when it's back.
 */
@Composable
fun rememberSecondClock(anchor: Long): State<Long> {
    // Keyed, so a new anchor (the next rest) never draws one frame from the old clock's time.
    val now = remember(anchor) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(anchor) {
        while (true) {
            val frameNow = withFrameMillis { System.currentTimeMillis() }
            now.longValue = frameNow
            delay(msUntilNextSecond(anchor, frameNow))
        }
    }
    return now
}
