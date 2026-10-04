package com.tczerwien.lifts.session

import com.tczerwien.lifts.analytics.PriorHistory
import com.tczerwien.lifts.data.SetEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryCacheTest {

    private var version = 0L

    /** Every read, as the ids it was asked for. */
    private val reads = mutableListOf<List<String>>()

    /** What a read answers for an exercise: its sets tagged with the version they were read at. */
    private fun snapshot(ids: List<String>) = HistoryCache.Snapshot(
        lastTime = ids.associateWith { listOf(LastSlot(listOf(set(version)))) },
        prior = ids.associateWith { PriorHistory(listOf(set(version))) },
    )

    private fun set(tag: Long) = SetEntity(id = tag + 1, workoutExerciseId = 1, position = 0, type = "normal", reps = 5, completedAt = tag + 1)

    private fun cache(onRead: (List<String>) -> Unit = {}) = HistoryCache({ version }) { ids ->
        reads += ids
        onRead(ids)
        snapshot(ids)
    }

    private fun HistoryCache.readAt(exerciseId: String): Long? = snapshot.lastTime[exerciseId]?.single()?.sets?.single()?.id?.minus(1)

    @Test
    fun `each exercise is read once for the life of the screen`() {
        val cache = cache()
        cache.ensure(listOf("bench", "row"))
        cache.ensure(listOf("bench", "row"))
        cache.ensure(listOf("bench", "row", "curl"))
        assertEquals(listOf(listOf("bench", "row"), listOf("curl")), reads)
        assertEquals(setOf("bench", "row", "curl"), cache.snapshot.prior.keys)
    }

    @Test
    fun `after a delete or restore every exercise is read again, and the old history stays until then`() {
        lateinit var cache: HistoryCache
        var seenDuringRead: Long? = null
        cache = cache { if (version == 1L) seenDuringRead = cache.readAt("bench") }
        cache.ensure(listOf("bench", "row"))
        version = 1L
        cache.ensure(listOf("bench"))
        assertEquals(0L, seenDuringRead, "while it reads, taps and reloads see the old history, never an empty one")
        assertEquals(listOf("bench"), reads.last(), "all of today's exercises, not only the missing")
        assertEquals(1L, cache.readAt("bench"))
        assertEquals(setOf("bench"), cache.snapshot.lastTime.keys, "swapped in whole")
    }

    @Test
    fun `a change landing while it reads makes it read again`() {
        var bumps = 1
        val cache = cache { if (bumps-- > 0) version++ }
        cache.ensure(listOf("bench"))
        assertEquals(2, reads.size)
        assertEquals(1L, cache.readAt("bench"), "read at the version that stood")
    }

    @Test
    fun `changes that keep landing leave the old history, never an empty one`() {
        var bumping = false
        val cache = cache { if (bumping) version++ }
        cache.ensure(listOf("bench"))
        version = 1L
        bumping = true
        cache.ensure(listOf("bench"))
        assertEquals(1 + HistoryCache.TRIES, reads.size, "it gives up after ${HistoryCache.TRIES} reads")
        assertEquals(0L, cache.readAt("bench"), "the old history stands until the next reload")
        bumping = false
        cache.ensure(listOf("bench"))
        assertEquals(version, cache.readAt("bench"))
    }
}
