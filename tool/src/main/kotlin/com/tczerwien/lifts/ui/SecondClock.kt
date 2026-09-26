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
 * stops while the app is in the background and catches up when it's back. It stops for good at
 * [stopAt], once nothing it draws can change.
 */
@Composable
fun rememberSecondClock(anchor: Long, stopAt: Long = Long.MAX_VALUE): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(anchor, stopAt) {
        while (true) {
            val frameNow = withFrameMillis { System.currentTimeMillis() }
            now.longValue = frameNow
            if (frameNow >= stopAt) break
            delay(msUntilNextSecond(anchor, frameNow))
        }
    }
    return now
}
