package com.tczerwien.lifts.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Persisted rest-timer state; remaining is derived via [restRemainingSec]. */
data class RestTimerState(val endMillis: Long, val totalSec: Int)

/**
 * The rest timer's DataStore keys: a wall-clock end and the length it started with ("lifts_"-prefixed,
 * in the file shared with SDK push keys). Remaining time is always derived from the end, never counted.
 */
class RestStore(private val dataStore: DataStore<Preferences>) {

    /** Blocking read — call from Dispatchers.IO. Null when no timer is saved. */
    fun snapshot(): RestTimerState? = runBlocking {
        val prefs = dataStore.data.first()
        val endMillis = prefs[REST_END_MILLIS] ?: return@runBlocking null
        RestTimerState(endMillis = endMillis, totalSec = prefs[REST_TOTAL_SEC] ?: 0)
    }

    suspend fun start(totalSec: Int, now: Long = System.currentTimeMillis()) {
        val clamped = totalSec.coerceAtLeast(0)
        dataStore.edit { prefs ->
            prefs[REST_END_MILLIS] = now + clamped * 1000L
            prefs[REST_TOTAL_SEC] = clamped
        }
    }

    /**
     * Shifts the saved end by [deltaSec]; the end never moves before [now] (an over-subtraction just
     * finishes the rest). Returns the new end, null when no timer is saved.
     */
    suspend fun adjust(deltaSec: Int, now: Long = System.currentTimeMillis()): Long? {
        var newEnd: Long? = null
        dataStore.edit { prefs ->
            val endMillis = prefs[REST_END_MILLIS] ?: return@edit
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
