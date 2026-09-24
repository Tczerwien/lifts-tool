package com.tczerwien.lifts.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
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

    private companion object {
        const val LATEST_VERSION = 1
        const val LATEST_IDENTITY_HASH = "a1ebce5952497c8fa88929c365015c34"
    }
}
