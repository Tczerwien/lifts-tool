package com.tczerwien.lifts.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** The exported Room schemas in `src/main/assets/room-schemas`, as the tests read them. */
object SchemaFiles {
    val dir = File("src/main/assets/room-schemas/com.tczerwien.lifts.data.LiftsDatabase")

    /** The version numbers of the files present, ascending. */
    fun versions(): List<Int> = dir.listFiles().orEmpty().map { it.nameWithoutExtension.toInt() }.sorted()

    /** Version [version]'s `database` object. */
    fun database(version: Int): JsonObject = parseDatabase(File(dir, "$version.json").readText())

    fun parseDatabase(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject.getValue("database").jsonObject

    /** Table [table]'s column names in [database], in table order. */
    fun columns(database: JsonObject, table: String): List<String> =
        entity(database, table).getValue("fields").jsonArray.map { it.jsonObject.getValue("columnName").jsonPrimitive.content }

    fun entity(database: JsonObject, table: String): JsonObject =
        database.getValue("entities").jsonArray.map { it.jsonObject }
            .single { it.getValue("tableName").jsonPrimitive.content == table }
}
