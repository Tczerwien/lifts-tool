package com.tczerwien.lifts.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [EXERCISE_COLUMNS_WITHOUT_INSTRUCTIONS] must name every column of `exercises`: one left out would
 * read as its default in the exports, and from the custom-exercise screen's list.
 */
class ExerciseColumnsTest {

    @Test
    fun theListNamesEveryExerciseColumnOfTheNewestSchema() {
        val schema = SchemaFiles.columns(SchemaFiles.database(SchemaFiles.versions().last()), "exercises")
        val listed = EXERCISE_COLUMNS_WITHOUT_INSTRUCTIONS.split(",").map { it.trim() }

        assertEquals(schema.map { if (it == "instructions") "'' AS instructions" else it }, listed)
    }
}
