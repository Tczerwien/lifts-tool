package com.tczerwien.lifts.screens

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every screen is opened from somewhere. Exercise Detail sat finished for months with nothing
 * navigating to it (#005); this reads the sources, so the next unwired screen fails here.
 */
class ScreenReachabilityTest {

    private val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()

    /** A file's code without its comments, so a screen named only in a comment or KDoc doesn't count as opened. */
    private fun code(file: File): String =
        file.readText().replace(Regex("(?s)/\\*.*?\\*/"), "").replace(Regex("//[^\n]*"), "")

    @Test
    fun `every screen but the initial one is opened by another file`() {
        assertTrue(sources.isNotEmpty(), "no sources under ${File("src/main/kotlin").absolutePath}")
        val screens = sources.flatMap { file ->
            Regex("""class (\w+Screen)\(""").findAll(code(file)).map { it.groupValues[1] to file }.toList()
        }
        assertTrue(screens.size > 10, "found only ${screens.map { it.first }}")
        val unreached = screens.filter { (name, home) ->
            val initial = Regex("""@InitialScreen\s+class $name\(""").containsMatchIn(code(home))
            !initial && sources.none { it != home && Regex("""(\b$name\(|::$name\b)""").containsMatchIn(code(it)) }
        }
        assertTrue(unreached.isEmpty(), "nothing opens ${unreached.map { it.first }}")
    }
}
