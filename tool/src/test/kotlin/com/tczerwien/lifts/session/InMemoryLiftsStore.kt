package com.tczerwien.lifts.session

import com.tczerwien.lifts.data.ExerciseEntity
import com.tczerwien.lifts.data.LiftsStore
import com.tczerwien.lifts.data.SetEntity
import com.tczerwien.lifts.data.TemplateEntity
import com.tczerwien.lifts.data.TemplateExerciseEntity
import com.tczerwien.lifts.data.WorkoutEntity
import com.tczerwien.lifts.data.WorkoutExerciseEntity

/**
 * [LiftsStore] in memory, following the DAO's rules: auto-increment ids, the same orderings, and
 * previousPerformance's "latest finished workout where the exercise has a completed set". The
 * queries themselves are checked on the emulator against the real database.
 */
class InMemoryLiftsStore : LiftsStore {
    val exercises = LinkedHashMap<String, ExerciseEntity>()
    val workouts = LinkedHashMap<Long, WorkoutEntity>()
    val slots = LinkedHashMap<Long, WorkoutExerciseEntity>()
    val sets = LinkedHashMap<Long, SetEntity>()
    val templates = LinkedHashMap<Long, TemplateEntity>()
    val templateExercises = LinkedHashMap<Long, TemplateExerciseEntity>()

    private var nextId = 1L

    private fun newId(id: Long): Long = if (id != 0L) id.also { nextId = maxOf(nextId, id + 1) } else nextId++

    override fun <T> inTransaction(block: () -> T): T = block()

    override fun exerciseById(id: String): ExerciseEntity? = exercises[id]

    override fun addWorkout(workout: WorkoutEntity): Long =
        newId(workout.id).also { workouts[it] = workout.copy(id = it) }

    override fun updateWorkout(workout: WorkoutEntity): Boolean =
        workouts.containsKey(workout.id).also { if (it) workouts[workout.id] = workout }

    override fun workoutById(id: Long): WorkoutEntity? = workouts[id]

    override fun listOpenWorkouts(): List<WorkoutEntity> =
        workouts.values.filter { it.endedAt == null }.sortedWith(compareByDescending<WorkoutEntity> { it.startedAt }.thenByDescending { it.id })

    override fun deleteWorkout(id: Long): Boolean {
        val slotIds = slots.values.filter { it.workoutId == id }.map { it.id }.toSet()
        sets.values.removeAll { it.workoutExerciseId in slotIds }
        slots.keys.removeAll(slotIds)
        return workouts.remove(id) != null
    }

    override fun reopenWorkout(id: Long): Boolean {
        val workout = workouts[id] ?: return false
        workouts[id] = workout.copy(endedAt = null)
        return true
    }

    override fun addWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long =
        newId(workoutExercise.id).also { slots[it] = workoutExercise.copy(id = it) }

    override fun addSet(set: SetEntity): Long = newId(set.id).also { sets[it] = set.copy(id = it) }

    override fun updateSet(set: SetEntity): Boolean = sets.containsKey(set.id).also { if (it) sets[set.id] = set }

    override fun setById(id: Long): SetEntity? = sets[id]

    override fun deleteSet(id: Long): Boolean {
        val set = sets.remove(id) ?: return false
        sets.values.filter { it.workoutExerciseId == set.workoutExerciseId }
            .sortedWith(compareBy({ it.position }, { it.id }))
            .forEachIndexed { position, remaining -> sets[remaining.id] = remaining.copy(position = position) }
        return true
    }

    override fun deleteSlot(id: Long): Boolean {
        val slot = slots.remove(id) ?: return false
        sets.values.removeAll { it.workoutExerciseId == id }
        exercisesForWorkout(slot.workoutId).forEachIndexed { position, remaining ->
            slots[remaining.id] = remaining.copy(position = position)
        }
        return true
    }

    override fun exercisesForWorkout(workoutId: Long): List<WorkoutExerciseEntity> =
        slots.values.filter { it.workoutId == workoutId }.sortedBy { it.position }

    override fun setsForWorkout(workoutId: Long): List<SetEntity> =
        exercisesForWorkout(workoutId).flatMap { slot -> setsOf(slot.id) }

    override fun previousPerformance(exerciseId: String): List<SetEntity> {
        val latest = workouts.values
            .filter { workout ->
                workout.endedAt != null && exercisesForWorkout(workout.id).any { slot ->
                    slot.exerciseId == exerciseId && setsOf(slot.id).any { it.completedAt != null }
                }
            }
            .maxWithOrNull(compareBy<WorkoutEntity> { it.startedAt }.thenBy { it.id })
            ?: return emptyList()
        return exercisesForWorkout(latest.id)
            .filter { it.exerciseId == exerciseId }
            .flatMap { slot -> setsOf(slot.id).filter { it.completedAt != null } }
    }

    override fun completedSetsByExercise(
        exerciseIds: Collection<String>,
        excludeWorkoutId: Long,
    ): Map<String, List<SetEntity>> =
        sets.values
            .filter { set ->
                val slot = slots[set.workoutExerciseId]
                set.completedAt != null && slot != null && slot.exerciseId in exerciseIds && slot.workoutId != excludeWorkoutId
            }
            .sortedWith(compareBy({ it.completedAt }, { it.id }))
            .groupBy { slots.getValue(it.workoutExerciseId).exerciseId }

    override fun templateById(id: Long): TemplateEntity? = templates[id]

    override fun exercisesForTemplate(templateId: Long): List<TemplateExerciseEntity> =
        templateExercises.values.filter { it.templateId == templateId }.sortedBy { it.position }

    private fun setsOf(slotId: Long): List<SetEntity> =
        sets.values.filter { it.workoutExerciseId == slotId }.sortedBy { it.position }

    // --- Test helpers ---

    fun addTemplate(name: String, vararg exercises: Pair<String, Int>): Long {
        val id = newId(0)
        templates[id] = TemplateEntity(id = id, name = name, position = templates.size)
        exercises.forEachIndexed { position, (exerciseId, targetSets) ->
            val rowId = newId(0)
            templateExercises[rowId] = TemplateExerciseEntity(
                id = rowId, templateId = id, exerciseId = exerciseId, position = position, targetSets = targetSets,
            )
        }
        return id
    }

    /**
     * A finished workout from [startedAt]: one slot per entry of [blocks], each a list of
     * (type, weightKg, reps) sets, all ticked one minute apart.
     */
    fun addFinishedWorkout(startedAt: Long, vararg blocks: Pair<String, List<Triple<String, Double?, Int?>>>): Long {
        val workoutId = addWorkout(WorkoutEntity(name = "Past", startedAt = startedAt))
        var clock = startedAt
        blocks.forEachIndexed { position, (exerciseId, rows) ->
            val slotId = addWorkoutExercise(WorkoutExerciseEntity(workoutId = workoutId, exerciseId = exerciseId, position = position))
            rows.forEachIndexed { index, (type, weightKg, reps) ->
                clock += 60_000
                addSet(SetEntity(workoutExerciseId = slotId, position = index, type = type, weightKg = weightKg, reps = reps, completedAt = clock))
            }
        }
        updateWorkout(workouts.getValue(workoutId).copy(endedAt = clock + 60_000))
        return workoutId
    }
}
