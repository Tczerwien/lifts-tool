package com.tczerwien.lifts.toolmanager

/** Which upload in Tool Manager › Lifts › Restore Home offers: the pure half of the waiting check. */
object RestoreInbox {

    /** How long an upload must sit unchanged before Home offers it. */
    const val SETTLE_MS = 3_000L

    /** A regular file in the Restore folder: its name and when it last changed. */
    data class Upload(val name: String, val modifiedAt: Long)

    /**
     * The newest of [uploads] to offer, or null. Tool Manager writes an upload in place, so a file
     * changed in the last [SETTLE_MS] may still be arriving and waits. A file changed well in the
     * future isn't arriving (the clock was wrong, then fixed) and is offered at once. Dot files are
     * never offered: they are copies still being made (lifts-adb.sh push-restore uses one).
     */
    fun pick(uploads: Collection<Upload>, now: Long): String? = uploads
        .filter { !it.name.startsWith(".") }
        .filter { val age = now - it.modifiedAt; age >= SETTLE_MS || age < -SETTLE_MS }
        .maxByOrNull { it.modifiedAt }
        ?.name
}
