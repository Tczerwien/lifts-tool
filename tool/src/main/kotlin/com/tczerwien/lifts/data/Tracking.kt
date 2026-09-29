package com.tczerwien.lifts.data

import com.tczerwien.lifts.data.ExerciseEntity.Companion.TRACKING_ASSISTED_BODYWEIGHT
import com.tczerwien.lifts.data.ExerciseEntity.Companion.TRACKING_DURATION
import com.tczerwien.lifts.data.ExerciseEntity.Companion.TRACKING_REPS_ONLY
import com.tczerwien.lifts.data.ExerciseEntity.Companion.TRACKING_WEIGHTED_BODYWEIGHT
import com.tczerwien.lifts.data.ExerciseEntity.Companion.TRACKING_WEIGHT_REPS
import kotlin.math.abs

/**
 * How an exercise's sets are measured ([ExerciseEntity.trackingType]): which numbers a set row and
 * the steppers take, what a tick logs, and what counts as a record. Names as Strong calls them.
 *
 * Weights stay one signed number per set, in kg: on a weighted bodyweight exercise it is the load
 * added (a dip belt), on an assisted one it is the help, stored negative (40 lb of assistance is
 * -18.1 kg), so less help is a higher number and the sign still says what it was if the type
 * changes later. Bodyweight itself is never part of a set.
 */
enum class Tracking(val stored: String, val label: String) {
    /** A barbell, dumbbell, cable or machine lift: weight and reps. */
    WEIGHT_REPS(TRACKING_WEIGHT_REPS, "Weight × Reps"),

    /** A bodyweight move done as it is (push-ups, dead bugs): reps alone. */
    REPS_ONLY(TRACKING_REPS_ONLY, "Reps Only"),

    /** Bodyweight plus added load (dips with a belt): the weight is what was added; none is bodyweight. */
    WEIGHTED_BODYWEIGHT(TRACKING_WEIGHTED_BODYWEIGHT, "Weighted Bodyweight"),

    /** Bodyweight with help (an assisted pull-up machine): the weight is the assistance, stored negative. */
    ASSISTED_BODYWEIGHT(TRACKING_ASSISTED_BODYWEIGHT, "Assisted Bodyweight"),

    /** A hold (a plank): seconds. */
    DURATION(TRACKING_DURATION, "Duration"),
    ;

    /** A set records a weight: weight × reps and both bodyweight kinds. */
    val takesWeight: Boolean get() = this == WEIGHT_REPS || this == WEIGHTED_BODYWEIGHT || this == ASSISTED_BODYWEIGHT

    /** A set records reps: everything but a hold. */
    val takesReps: Boolean get() = this != DURATION

    /** A set records seconds: holds only. */
    val takesDuration: Boolean get() = this == DURATION

    /**
     * The weight as the steppers and number entry show it: the assistance itself on an assisted
     * exercise (40, not -40), the stored weight otherwise.
     */
    fun shownWeight(storedKg: Double?): Double? = storedKg?.let { if (this == ASSISTED_BODYWEIGHT) abs(it) else it }

    /** [shownWeight] back to what is stored: assistance becomes negative. */
    fun storedWeight(shownKg: Double?): Double? = shownKg?.let { if (this == ASSISTED_BODYWEIGHT) -abs(it) else it }

    companion object {
        /** The tracking an exercise stores; anything unknown is weight × reps, as the column defaults. */
        fun of(stored: String?): Tracking = entries.firstOrNull { it.stored == stored } ?: WEIGHT_REPS
    }
}

/** How this exercise's sets are measured. */
val ExerciseEntity.tracking: Tracking get() = Tracking.of(trackingType)
