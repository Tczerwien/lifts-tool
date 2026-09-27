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

    /**
     * A file's code without its comments, so a screen named only in a comment or KDoc doesn't count as
     * opened. Strings and char literals are copied as they are, so comment markers inside one
     * ("http://" or a MIME wildcard) aren't taken for a comment; block comments nest, as in Kotlin. A
     * scanner, not a regex: Java's regex recurses per character and overflows on the seed catalog.
     */
    private fun code(file: File): String {
        val text = file.readText()
        val out = StringBuilder(text.length)
        var i = 0
        fun copyUntil(end: Int) {
            val stop = end.coerceAtMost(text.length)
            out.append(text, i, stop)
            i = stop
        }
        while (i < text.length) {
            when {
                text.startsWith("\"\"\"", i) ->
                    copyUntil(text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 })
                text[i] == '"' || text[i] == '\'' -> {
                    val quote = text[i]
                    var j = i + 1
                    while (j < text.length && text[j] != quote && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
                    copyUntil(j + 1)
                }
                text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                text.startsWith("/*", i) -> {
                    var depth = 0
                    do {
                        when {
                            text.startsWith("/*", i) -> { depth++; i += 2 }
                            text.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                    } while (depth > 0 && i < text.length)
                }
                else -> { out.append(text[i]); i++ }
            }
        }
        return out.toString()
    }

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
