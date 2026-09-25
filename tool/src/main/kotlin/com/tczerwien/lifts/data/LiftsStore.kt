package com.tczerwien.lifts.data

/**
 * The storage ActiveSession works against: just the calls it makes. [LiftsRepository] is the real
 * one (Room); tests use an in-memory one, since the plugin's allowlist rules out Robolectric and
 * mocking libraries. Blocking, like the repository: call from Dispatchers.IO.
 */
interface LiftsStore {
    /** Runs [block] as one transaction: its writes land together or not at all. */
    fun <T> inTransaction(block: () -> T): T

    fun exerciseById(id: String): ExerciseEntity?

    fun addWorkout(workout: WorkoutEntity): Long

    fun updateWorkout(workout: WorkoutEntity): Boolean

    fun workoutById(id: Long): WorkoutEntity?

    /** Workouts still in progress, newest first. */
    fun listOpenWorkouts(): List<WorkoutEntity>

    /** Deletes the workout with all of its slots and sets. */
    fun deleteWorkout(id: Long): Boolean

    /** Clears endedAt. */
    fun reopenWorkout(id: Long): Boolean

    fun addWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long

    fun addSet(set: SetEntity): Long

    /** Writes [set]'s values (type, weight, reps, duration, RPE, tick); its slot and position are left alone. */
    fun updateSetValues(set: SetEntity): Boolean

    fun setById(id: Long): SetEntity?

    /** Deletes one set and renumbers the rest of its slot 0..n-1. */
    fun deleteSet(id: Long): Boolean

    /** Deletes one slot with its sets and renumbers the workout's remaining slots 0..n-1. */
    fun deleteSlot(id: Long): Boolean

    /** A workout's slots in position order. */
    fun exercisesForWorkout(workoutId: Long): List<WorkoutExerciseEntity>

    /** A workout's sets, by slot position then set position. */
    fun setsForWorkout(workoutId: Long): List<SetEntity>

    /**
     * Completed sets of the most recent finished workout in which [exerciseId] had a completed set,
     * by slot position then set position.
     */
    fun previousPerformance(exerciseId: String): List<SetEntity>

    /** Every slot of [exerciseId] in the workout [previousPerformance] reads, ticked or not, in position order. */
    fun previousSlotIds(exerciseId: String): List<Long>

    /** Completed sets of [exerciseIds] from every workout except [excludeWorkoutId], in completion order. */
    fun completedSetsByExercise(exerciseIds: Collection<String>, excludeWorkoutId: Long = -1): Map<String, List<SetEntity>>

    fun templateById(id: Long): TemplateEntity?

    /** A template's exercises in position order. */
    fun exercisesForTemplate(templateId: Long): List<TemplateExerciseEntity>
}
