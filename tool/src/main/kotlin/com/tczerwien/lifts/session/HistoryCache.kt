package com.tczerwien.lifts.session

import com.tczerwien.lifts.analytics.PriorHistory
import com.tczerwien.lifts.data.SetEntity

/**
 * Other workouts' history for the exercises of the open workout, as Active Workout needs it: read
 * once per exercise for the life of the screen, since other workouts don't change while one is open,
 * except by a delete or a deload mark (Workout Detail, reached through Exercise Detail) or a restore, which
 * move [HistoryVersion]. Then it reads again for every exercise and swaps the new history in whole: until
 * then the old stays, so nothing works from an empty cache (PREVIOUS blank, every set a record). A
 * change that lands while it reads makes it read again, [TRIES] times at most; after that the old
 * history stands until the next [ensure]. [read] runs on the caller's thread (IO); [snapshot] may be
 * read from any thread.
 */
class HistoryCache(
    private val currentVersion: () -> Long = { HistoryVersion.current },
    private val read: (exerciseIds: List<String>) -> Snapshot,
) {
    /**
     * [lastTime]: last session's sets of each exercise, one group per slot ([previousGroups]), for
     * PREVIOUS and starting values. [prior]: every completed set, replayed once ([PriorHistory]), for
     * the PR stars.
     */
    data class Snapshot(
        val lastTime: Map<String, List<LastSlot>> = emptyMap(),
        val prior: Map<String, PriorHistory> = emptyMap(),
    ) {
        operator fun plus(other: Snapshot) = Snapshot(lastTime + other.lastTime, prior + other.prior)
    }

    private val lock = Any()

    @Volatile
    private var cached = Snapshot()

    /** The [HistoryVersion] [cached] was read at. Guarded by [lock]. */
    private var cachedVersion = currentVersion()

    /** What is cached now: whole, never half of one read and half of another. */
    val snapshot: Snapshot get() = cached

    /**
     * Makes sure [exerciseIds] are cached at the current version: reads the ones missing, or all of
     * them after a delete or restore. Two reloads may run it at once; neither drops the other's reads.
     */
    fun ensure(exerciseIds: List<String>) {
        repeat(TRIES) {
            val version = currentVersion()
            val stale = synchronized(lock) { version != cachedVersion }
            val missing = if (stale) exerciseIds.distinct() else exerciseIds.distinct().filterNot { it in cached.lastTime }
            if (missing.isEmpty()) return
            val fresh = read(missing)
            synchronized(lock) {
                if (currentVersion() != version) return@repeat
                if (stale) {
                    // Another reload may have swapped in this version's history meanwhile: add to it.
                    cached = if (cachedVersion == version) cached + fresh else fresh
                    cachedVersion = version
                } else {
                    if (cachedVersion != version) return@repeat
                    cached += fresh
                }
                return
            }
        }
    }

    companion object {
        const val TRIES = 3
    }
}
