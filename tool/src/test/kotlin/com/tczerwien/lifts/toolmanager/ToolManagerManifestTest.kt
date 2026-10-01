package com.tczerwien.lifts.toolmanager

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The manifest exactly as LightOS receives it: the JSON the SDK's file provider hands over. */
class ToolManagerManifestTest {

    private data class Folder(val template: String, val label: String, val path: String, val readOnly: Boolean)

    private val json: JsonObject = Json.parseToJsonElement(LiftsToolManager.manifest().encode()).jsonObject

    private val folders: List<Folder> = json.getValue("roots").jsonArray.map { root ->
        val node = root.jsonObject
        assertEquals("com.thelightphone.toolmanager.ClientLeafNode", node.getValue("type").jsonPrimitive.content)
        val spec = node.getValue("spec").jsonObject
        Folder(
            template = spec.getValue("type").jsonPrimitive.content.substringAfterLast('.'),
            label = spec.getValue("label").jsonPrimitive.content,
            path = spec.getValue("path").jsonPrimitive.content,
            readOnly = node["readOnly"]?.jsonPrimitive?.boolean ?: false,
        )
    }

    @Test
    fun `Lifts lists its folders in order`() {
        assertEquals("Lifts", json.getValue("title").jsonPrimitive.content)
        assertEquals(
            listOf(
                Folder("FileBrowserSpec", "Exports", LiftsToolManager.EXPORTS, readOnly = true),
                Folder("FileBrowserSpec", "Backups", LiftsToolManager.BACKUPS, readOnly = true),
                Folder("UploadSpec", "Restore", LiftsToolManager.RESTORE, readOnly = false),
            ),
            folders,
        )
    }

    @Test
    fun `the Restore box takes uploads under a button`() {
        val restore = json.getValue("roots").jsonArray.last().jsonObject.getValue("spec").jsonObject
        assertEquals("Upload backup", restore.getValue("buttonText").jsonPrimitive.content)
    }

    @Test
    fun `every folder is one plain directory under files-shared`() {
        folders.forEach { folder ->
            assertTrue(folder.path.matches(Regex("[a-z]+")), "${folder.path} must be a single plain segment")
        }
        assertEquals(folders.size, folders.map { it.path }.toSet().size, "paths must not repeat")
    }
}
