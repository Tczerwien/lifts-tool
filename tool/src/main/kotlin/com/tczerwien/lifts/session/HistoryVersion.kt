package com.tczerwien.lifts.session

import java.util.concurrent.atomic.AtomicLong

/**
 * Counts changes to finished workouts made while a workout may be open: a delete from Workout Detail
 * (reachable mid-session through Exercise Detail's history) or a restore. Active Workout caches last
 * session and PR history for the life of its screen and drops the cache when this has moved, so
 * PREVIOUS, starting values and PR stars never come from a workout that no longer exists.
 */
object HistoryVersion {
    private val counter = AtomicLong()

    val current: Long get() = counter.get()

    fun bump() {
        counter.incrementAndGet()
    }
}
