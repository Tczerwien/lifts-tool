package com.tczerwien.lifts.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the exported Room schema. The SDK's buildDatabase accepts no migrations, so a schema
 * change that isn't a proper version bump would crash every install on launch. KSP rewrites the
 * current version's JSON on every build; if an entity changes without a version bump, the hash
 * below stops matching.
 *
 * When this fails because you changed an entity on purpose: restore the old JSON from git, bump
 * `@Database(version = N + 1, autoMigrations = [AutoMigration(from = N, to = N + 1)])`, build, commit
 * the new N+1.json, and pin its hash here.
 *
 * Room's identity hash ignores the order of an entity's fields, and Room leaves an existing schema
 * file alone while the hash matches, so moving a field changes the table a new install creates but
 * neither the hash nor the JSON. [theNewestSchemaHasTheEntitiesFieldOrder] catches that: a field
 * moved after its version was exported would give new installs a column order that migrated logs
 * don't have (fields added by a migration go last, see [WorkoutEntity]).
 */
class SchemaTripwireTest {

    private val schemaDir = File("src/main/assets/room-schemas/com.tczerwien.lifts.data.LiftsDatabase")

    @Test
    fun latestSchemaIsPinned() {
        val versions = schemaDir.listFiles().orEmpty().map { it.nameWithoutExtension.toInt() }.sorted()
        assertEquals(LATEST_VERSION, versions.lastOrNull(), "schema files present: $versions")

        val database = Json.parseToJsonElement(File(schemaDir, "$LATEST_VERSION.json").readText())
            .jsonObject.getValue("database").jsonObject
        assertEquals(LATEST_VERSION, database.getValue("version").jsonPrimitive.int)
        assertEquals(
            LATEST_IDENTITY_HASH,
            database.getValue("identityHash").jsonPrimitive.content,
            "an entity changed without a version bump; see this test's KDoc",
        )
    }

    @Test
    fun everyVersionFromOneIsKept() {
        val versions = schemaDir.listFiles().orEmpty().map { it.nameWithoutExtension.toInt() }.sorted()
        assertTrue(versions.isNotEmpty(), "no exported schema in $schemaDir")
        assertEquals((1..LATEST_VERSION).toList(), versions, "AutoMigrations need every old schema file")
    }

    @Test
    fun theNewestSchemaHasTheEntitiesFieldOrder() {
        val database = SchemaFiles.database(SchemaFiles.versions().last())
        val tables = database.getValue("entities").jsonArray.map { it.jsonObject.getValue("tableName").jsonPrimitive.content }
        assertEquals(ENTITIES.keys.sorted(), tables.sorted(), "every table's entity class is listed below")
        for ((table, entity) in ENTITIES) {
            // Room creates the columns in the order the data class declares its properties.
            val source = File("src/main/kotlin/com/tczerwien/lifts/data/$entity.kt").readText()
            val parameters = source.substringAfter("data class $entity(").substringBefore("\n)")
            val declared = Regex("""\bva[lr] (\w+):""").findAll(parameters).map { it.groupValues[1] }.toList()
            assertEquals(declared, SchemaFiles.columns(database, table), "$table: fields as declared vs the exported schema")
        }
    }

    private companion object {
        const val LATEST_VERSION = 2
        const val LATEST_IDENTITY_HASH = "216428bd3635950c518a88eec5fd01e2"

        /** Each table's entity class, by name: its source is `data/<name>.kt`. */
        val ENTITIES = mapOf(
            "exercises" to "ExerciseEntity",
            "workouts" to "WorkoutEntity",
            "workout_exercises" to "WorkoutExerciseEntity",
            "sets" to "SetEntity",
            "templates" to "TemplateEntity",
            "template_exercises" to "TemplateExerciseEntity",
            "bodyweight" to "BodyweightEntity",
            "bars" to "BarEntity",
        )
    }
}
