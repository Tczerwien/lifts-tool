package com.tczerwien.lifts.data

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ConvertersTest {

    private val converters = Converters()

    @Test
    fun listsAreStoredAsCompactJson() {
        // The stored format: a backup written by any version must read back the same.
        assertEquals("[\"chest\",\"triceps\"]", converters.fromStringList(listOf("chest", "triceps")))
    }

    @Test
    fun roundTripAndEmptyString() {
        val muscles = listOf("lower back", "hamstrings")
        assertEquals(muscles, converters.toStringList(converters.fromStringList(muscles)))
        assertEquals(emptyList(), converters.toStringList(""))
    }

    @Test
    fun anEmptyListIsStoredAsTheListColumnsDefault() {
        // A row the app writes and a row the migration defaulted must read and compare the same.
        assertEquals("[]", converters.fromStringList(emptyList()))
        assertEquals(emptyList(), converters.toStringList("[]"))
        assertEquals(emptyList(), converters.toStringList(Converters.EMPTY_LIST))
        val database = SchemaFiles.database(SchemaFiles.versions().last())
        val listDefaults = database.getValue("entities").jsonArray.map { it.jsonObject }.flatMap { entity ->
            val table = entity.getValue("tableName").jsonPrimitive.content
            entity.getValue("fields").jsonArray.map { it.jsonObject }
                .filter { it["defaultValue"]?.jsonPrimitive?.content?.startsWith("'[") == true }
                .map { "$table.${it.getValue("columnName").jsonPrimitive.content}" to it.getValue("defaultValue").jsonPrimitive.content }
        }
        assertEquals(
            listOf("exercises.countsFor", "workout_exercises.choices", "template_exercises.alternatives"),
            listDefaults.map { it.first },
        )
        listDefaults.forEach { (column, default) -> assertEquals("'${Converters.EMPTY_LIST}'", default, column) }
    }
}
