package com.tczerwien.lifts.data

/** Every row of every table: what a restore reads from a backup and writes into the live database. */
data class LiftsRows(
    val exercises: List<ExerciseEntity>,
    val workouts: List<WorkoutEntity>,
    val workoutExercises: List<WorkoutExerciseEntity>,
    val sets: List<SetEntity>,
    val templates: List<TemplateEntity>,
    val templateExercises: List<TemplateExerciseEntity>,
    val bodyweight: List<BodyweightEntity>,
    val bars: List<BarEntity>,
)
