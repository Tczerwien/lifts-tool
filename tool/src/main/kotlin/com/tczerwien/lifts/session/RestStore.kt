package com.tczerwien.lifts.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/** Persisted rest-timer state; remaining is derived via [restRemainingSec]. */
data class RestTimerState(val endMillis: Long, val totalSec: Int)

/**
 * ±30 is refused this close to the end: the save takes a few milliseconds, and a +30 that lands
 * after 0:00 has alerted would revive a rest the timer already shows as over.
 */
const val REST_ADJUST_CUTOFF_MS = 500L

/** Whether ±30 may still change the rest ending at [endMillis] ([REST_ADJUST_CUTOFF_MS]). */
fun restAdjustable(endMillis: Long, now: Long): Boolean = endMillis - now > REST_ADJUST_CUTOFF_MS

/**
 * The end the Rest Timer shows when it opens, given the saved end ([savedEnd], null when none), or
 * null to start a fresh rest. A running rest is resumed. With nothing running, the alarm icon starts
 * a fresh rest; a tap on the rest strip ([resumeOnly]) never does: the rest ended on the way (a tap
 * at 0:01), so the timer shows REST OVER instead.
 */
fun openingRestEnd(savedEnd: Long?, now: Long, resumeOnly: Boolean): Long? = when {
    savedEnd != null && savedEnd > now -> savedEnd
    resumeOnly -> savedEnd ?: now
    else -> null
}

/**
 * The rest timer's DataStore keys: a wall-clock end and the length it started with ("lifts_"-prefixed,
 * in the file shared with SDK push keys). Remaining time is always derived from the end, never counted.
 */
class RestStore(private val dataStore: DataStore<Preferences>) {

    /** The saved end as it changes (tick, ±30, Skip, Finish); null while no timer is saved. */
    val endMillis: Flow<Long?> = dataStore.data.map { it[REST_END_MILLIS] }.distinctUntilChanged()

    /** Blocking read — call from Dispatchers.IO. Null when no timer is saved. */
    fun snapshot(): RestTimerState? = runBlocking {
        val prefs = dataStore.data.first()
        val endMillis = prefs[REST_END_MILLIS] ?: return@runBlocking null
        RestTimerState(endMillis = endMillis, totalSec = prefs[REST_TOTAL_SEC] ?: 0)
    }

    /** Saves a rest of [totalSec] from [now]; returns its end. */
    suspend fun start(totalSec: Int, now: Long = System.currentTimeMillis()): Long {
        val clamped = totalSec.coerceAtLeast(0)
        val end = now + clamped * 1000L
        dataStore.edit { prefs ->
            prefs[REST_END_MILLIS] = end
            prefs[REST_TOTAL_SEC] = clamped
        }
        return end
    }

    /**
     * Shifts the saved end by [deltaSec]; the end never moves before [now] (an over-subtraction just
     * finishes the rest). Returns the new end, null when no rest is running or it is about to end
     * ([restAdjustable]): an ended rest stays ended, so a +30 that lands at 0:00 can't revive it.
     */
    suspend fun adjust(deltaSec: Int, now: Long = System.currentTimeMillis()): Long? {
        var newEnd: Long? = null
        dataStore.edit { prefs ->
            val endMillis = prefs[REST_END_MILLIS]?.takeIf { restAdjustable(it, now) } ?: return@edit
            val adjusted = (endMillis + deltaSec * 1000L).coerceAtLeast(now)
            prefs[REST_END_MILLIS] = adjusted
            newEnd = adjusted
        }
        return newEnd
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(REST_END_MILLIS)
            prefs.remove(REST_TOTAL_SEC)
        }
    }

    private companion object {
        val REST_END_MILLIS = longPreferencesKey("lifts_rest_end_millis")
        val REST_TOTAL_SEC = intPreferencesKey("lifts_rest_total_sec")
    }
}
