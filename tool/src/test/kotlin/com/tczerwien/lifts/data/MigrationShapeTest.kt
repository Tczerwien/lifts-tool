package com.tczerwien.lifts.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every row of an older log migrates untouched: each exported schema only adds to the one before it.
 * Between two consecutive versions no table or column is dropped or renamed; no column changes type,
 * nullability or default; every added column is appended to its table and is nullable or has a
 * default; and each table's primary key, indices and foreign keys are as they were.
 *
 * `verify/tools/mutate.py` only edits main sources, so the checker's own negative cases are inline
 * JSON below: each breaks one rule, and the checker must name it.
 */
class MigrationShapeTest {

    @Test
    fun eachSchemaOnlyAddsToTheOneBefore() {
        val versions = SchemaFiles.versions()
        assertTrue(versions.size >= 2, "schema files present: $versions")
        versions.zipWithNext().forEach { (from, to) ->
            assertEquals(
                emptyList(),
                migrationProblems(SchemaFiles.database(from), SchemaFiles.database(to)),
                "schema $from -> $to",
            )
        }
    }

    @Test
    fun version2AddsTheNineV2ColumnsAndNothingElse() {
        assertEquals(
            mapOf(
                "exercises" to listOf("countsFor"),
                "workouts" to listOf("deload"),
                "workout_exercises" to listOf("supersetGroup", "choices", "targetSets", "targetRirMax"),
                "template_exercises" to listOf("supersetGroup", "alternatives", "targetRirMax"),
            ),
            addedColumns(SchemaFiles.database(1), SchemaFiles.database(2)),
        )
    }

    @Test
    fun theCheckerAcceptsAppendedNullableAndDefaultedColumns() {
        val next = V1
            .edited(""""notes", "affinity": "TEXT"}""", """"notes", "affinity": "TEXT"}, $NULLABLE_COLUMN, $DEFAULTED_COLUMN""")
        assertEquals(emptyList(), migrationProblems(parse(V1), parse(next)))
        assertEquals(mapOf("workouts" to listOf("deload", "group")), addedColumns(parse(V1), parse(next)))
    }

    @Test
    fun theCheckerRejectsADroppedColumn() {
        val next = V1.edited(""", {"columnName": "notes", "affinity": "TEXT"}""", "")
        assertEquals(listOf("workouts.notes: dropped"), migrationProblems(parse(V1), parse(next)))
    }

    @Test
    fun theCheckerRejectsARenamedColumn() {
        val next = V1.edited(""""columnName": "notes"""", """"columnName": "note"""")
        assertEquals(listOf("workouts.notes: dropped"), migrationProblems(parse(V1), parse(next)))
    }

    @Test
    fun theCheckerRejectsAnAddedNotNullColumnWithoutADefault() {
        val next = V1.edited(
            """"notes", "affinity": "TEXT"}""",
            """"notes", "affinity": "TEXT"}, {"columnName": "deload", "affinity": "INTEGER", "notNull": true}""",
        )
        assertEquals(listOf("workouts.deload: added NOT NULL without a default"), migrationProblems(parse(V1), parse(next)))
    }

    @Test
    fun theCheckerRejectsAChangedType() {
        val next = V1.edited(""""name", "affinity": "TEXT"""", """"name", "affinity": "INTEGER"""")
        assertEquals(listOf("workouts.name: type TEXT -> INTEGER"), migrationProblems(parse(V1), parse(next)))
    }

    @Test
    fun theCheckerRejectsChangedNullabilityOrDefault() {
        val nullable = V1.edited(""""name", "affinity": "TEXT", "notNull": true""", """"name", "affinity": "TEXT"""")
        assertEquals(listOf("workouts.name: NOT NULL -> nullable"), migrationProblems(parse(V1), parse(nullable)))

        val defaulted = V1.edited(""""notes", "affinity": "TEXT"}""", """"notes", "affinity": "TEXT", "defaultValue": "''"}""")
        assertEquals(listOf("workouts.notes: default none -> ''"), migrationProblems(parse(V1), parse(defaulted)))
    }

    @Test
    fun theCheckerRejectsAColumnAddedInTheMiddle() {
        val next = V1.edited(""""notNull": true}, {"columnName": "name"""", """"notNull": true}, $NULLABLE_COLUMN, {"columnName": "name"""")
        assertEquals(listOf("workouts: a column added before the old ones"), migrationProblems(parse(V1), parse(next)))
    }

    @Test
    fun theCheckerRejectsADroppedIndex() {
        val next = V1.edited(INDEX, "")
        assertEquals(listOf("workouts: indices changed"), migrationProblems(parse(V1), parse(next)))
    }

    @Test
    fun theCheckerRejectsAChangedForeignKeyOrPrimaryKey() {
        val cascade = V1.edited(""""onDelete": "CASCADE"""", """"onDelete": "NO ACTION"""")
        assertEquals(listOf("sets: foreignKeys changed"), migrationProblems(parse(V1), parse(cascade)))

        val key = V1.edited(""""autoGenerate": true, "columnNames": ["id"]}, "indices"""", """"autoGenerate": false, "columnNames": ["id"]}, "indices"""")
        assertEquals(listOf("workouts: primaryKey changed"), migrationProblems(parse(V1), parse(key)))
    }

    @Test
    fun theCheckerRejectsADroppedTable() {
        val next = V1.edited(", $SETS_TABLE", "")
        assertEquals(listOf("sets: dropped"), migrationProblems(parse(V1), parse(next)))
    }

    private fun parse(text: String) = SchemaFiles.parseDatabase(text)

    /** [this] with [old] (which must be there, once) replaced by [new]. */
    private fun String.edited(old: String, new: String): String {
        assertEquals(1, split(old).size - 1, "the case must find exactly one \"$old\"")
        return replace(old, new)
    }

    private companion object {
        const val INDEX =
            """{"name": "index_workouts_name", "unique": false, "columnNames": ["name"], "orders": [], "createSql": "CREATE INDEX"}"""
        const val SETS_TABLE =
            """{"tableName": "sets", "fields": [{"columnName": "id", "affinity": "INTEGER", "notNull": true}, """ +
                """{"columnName": "workoutId", "affinity": "INTEGER", "notNull": true}], """ +
                """"primaryKey": {"autoGenerate": true, "columnNames": ["id"]}, "foreignKeys": [{"table": "workouts", """ +
                """"onDelete": "CASCADE", "onUpdate": "NO ACTION", "columns": ["workoutId"], "referencedColumns": ["id"]}]}"""

        /** A small version-1 schema in Room's export format: the base every case edits. */
        const val V1 =
            """{"database": {"version": 1, "entities": [""" +
                """{"tableName": "workouts", "fields": [{"columnName": "id", "affinity": "INTEGER", "notNull": true}, """ +
                """{"columnName": "name", "affinity": "TEXT", "notNull": true}, {"columnName": "notes", "affinity": "TEXT"}], """ +
                """"primaryKey": {"autoGenerate": true, "columnNames": ["id"]}, "indices": [$INDEX]}, """ +
                SETS_TABLE + """]}}"""

        const val NULLABLE_COLUMN = """{"columnName": "deload", "affinity": "INTEGER"}"""
        const val DEFAULTED_COLUMN = """{"columnName": "group", "affinity": "TEXT", "notNull": true, "defaultValue": "'[]'"}"""
    }
}

/**
 * What stops [old]'s rows from moving into [new] untouched by column additions alone; empty when
 * nothing does. A table [new] adds is fine (it starts empty).
 */
internal fun migrationProblems(old: JsonObject, new: JsonObject): List<String> {
    val problems = mutableListOf<String>()
    val after = tables(new)
    for ((table, before) in tables(old)) {
        val now = after[table] ?: run { problems += "$table: dropped"; continue }
        val oldColumns = columns(before)
        val newColumns = columns(now).associateBy { it.name }
        for (column in oldColumns) {
            val next = newColumns[column.name] ?: run { problems += "$table.${column.name}: dropped"; continue }
            if (next.type != column.type) problems += "$table.${column.name}: type ${column.type} -> ${next.type}"
            if (next.notNull != column.notNull) {
                problems += "$table.${column.name}: " + if (column.notNull) "NOT NULL -> nullable" else "nullable -> NOT NULL"
            }
            if (next.default != column.default) {
                problems += "$table.${column.name}: default ${column.default ?: "none"} -> ${next.default ?: "none"}"
            }
        }
        val oldNames = oldColumns.map { it.name }
        if (oldNames.all { it in newColumns } && columns(now).take(oldNames.size).map { it.name } != oldNames) {
            problems += "$table: a column added before the old ones"
        }
        columns(now).filter { it.name !in oldNames && it.notNull && it.default == null }
            .forEach { problems += "$table.${it.name}: added NOT NULL without a default" }
        for (key in listOf("primaryKey", "indices", "foreignKeys")) {
            if (withoutSql(before[key]) != withoutSql(now[key])) problems += "$table: $key changed"
        }
    }
    return problems
}

/** The columns [new] adds to each of [old]'s tables, in table order (tables with none left out). */
internal fun addedColumns(old: JsonObject, new: JsonObject): Map<String, List<String>> {
    val after = tables(new)
    return tables(old).mapNotNull { (table, before) ->
        val oldNames = columns(before).map { it.name }.toSet()
        val added = after[table]?.let { columns(it) }.orEmpty().map { it.name }.filter { it !in oldNames }
        added.takeIf { it.isNotEmpty() }?.let { table to it }
    }.toMap()
}

private data class Column(val name: String, val type: String, val notNull: Boolean, val default: String?)

private fun tables(database: JsonObject): Map<String, JsonObject> =
    database.getValue("entities").jsonArray.map { it.jsonObject }
        .associateBy { it.getValue("tableName").jsonPrimitive.content }

private fun columns(table: JsonObject): List<Column> =
    table.getValue("fields").jsonArray.map { it.jsonObject }.map { field ->
        Column(
            name = field.getValue("columnName").jsonPrimitive.content,
            type = field.getValue("affinity").jsonPrimitive.content,
            notNull = field["notNull"]?.jsonPrimitive?.boolean ?: false,
            default = field["defaultValue"]?.jsonPrimitive?.content,
        )
    }

/** [element] without its `createSql` strings: they name the table by placeholder and add nothing. */
private fun withoutSql(element: JsonElement?): JsonElement? = when (element) {
    is JsonObject -> JsonObject(element.filterKeys { it != "createSql" }.mapValues { withoutSql(it.value)!! })
    is JsonArray -> JsonArray(element.map { withoutSql(it)!! })
    else -> element
}
