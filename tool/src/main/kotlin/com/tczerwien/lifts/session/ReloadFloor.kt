package com.tczerwien.lifts.session

/**
 * A screen's reloads run beside the write lane, so one that read the database before a save of a value
 * on screen landed (a note, a cue) can land after it and put the old value back. Each reload takes a
 * ticket ([reloadStarted]); while a save is pending ([saveQueued]) and for every reload started before
 * the latest one landed ([saveLanded]), the screen keeps the value it shows ([keepsShown]). Thread-safe.
 */
class ReloadFloor {
    private var started = 0L
    private var floor = 0L
    private var pending = 0

    /** A reload begins: its ticket. */
    @Synchronized
    fun reloadStarted(): Long = ++started

    /** A save of the shown value is queued (shown at once, before it lands). */
    @Synchronized
    fun saveQueued() {
        pending++
    }

    /** A save landed: reloads started from now on read it. */
    @Synchronized
    fun saveLanded() {
        pending = (pending - 1).coerceAtLeast(0)
        floor = started
    }

    /** Whether reload [ticket] may have read the value from before a save, so the screen keeps what it shows. */
    @Synchronized
    fun keepsShown(ticket: Long): Boolean = pending > 0 || ticket <= floor
}
