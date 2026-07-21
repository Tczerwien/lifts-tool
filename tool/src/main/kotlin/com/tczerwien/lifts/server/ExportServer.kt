package com.tczerwien.lifts.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.roundToLong

/**
 * Embedded HTTP server that shares the generated export files over the local network — the
 * only file-egress path available to a tool (filesDir is app-private and a tool has no way
 * to hand files to other apps; see SDK-Capabilities-Reference).
 *
 * Binds 0.0.0.0:[PORT]. `GET /` renders a minimal monochrome HTML index linking the files;
 * `GET /<name>` serves each export file with its content type. Only the fixed [FILE_NAMES]
 * are routed, so nothing outside the known files in [exportsDir] is ever reachable.
 *
 * Single-use: one [start]/[stop] cycle per instance (a ktor EmbeddedServer cannot be
 * restarted). ExportScreen's view model creates a fresh instance per session and shuts it
 * down whenever the screen hides or the app pauses — LightOS tools have no background
 * execution, so the server only runs while the Export screen is foregrounded.
 */
class ExportServer(private val exportsDir: File) {

    private val server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
        routing {
            get("/") {
                call.respondText(indexHtml(), ContentType.Text.Html.withCharset(Charsets.UTF_8))
            }
            for ((name, contentType) in FILE_TYPES) {
                get("/$name") {
                    val file = File(exportsDir, name)
                    if (file.isFile) {
                        call.respondBytes(file.readBytes(), contentType)
                    } else {
                        call.respondText("not found", status = HttpStatusCode.NotFound)
                    }
                }
            }
        }
    }

    /** Binds the port. Throws when it cannot; the caller surfaces the error. */
    fun start() {
        server.start(wait = false)
    }

    /** Graceful, bounded stop — safe to call from lifecycle callbacks. Idempotent. */
    fun stop() {
        server.stop(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS)
    }

    /** Black/white/#bbb only — mirrors the dark-theme tokens of the tool itself. */
    private fun indexHtml(): String {
        val items = buildString {
            for ((name, _) in FILE_TYPES) {
                val file = File(exportsDir, name)
                val size = if (file.isFile) sizeLabel(file.length()) else "missing"
                append("""  <li><a href="/$name">$name</a><span>$size</span></li>""").append('\n')
            }
        }
        return """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Lifts Export</title>
<style>
  body { background: #000; color: #fff; font-family: ui-monospace, Menlo, Consolas, monospace;
         max-width: 34rem; margin: 3rem auto; padding: 0 1rem; }
  h1 { font-size: 0.9rem; font-weight: 500; letter-spacing: 0.15em; }
  ul { list-style: none; padding: 0; margin-top: 1.5rem; }
  li { display: flex; justify-content: space-between; align-items: baseline;
       border-top: 1px solid #444; padding: 0.7rem 0; }
  a { color: #fff; }
  li span { color: #bbb; font-size: 0.85rem; }
</style>
</head>
<body>
<h1>LIFTS EXPORT</h1>
<ul>
$items</ul>
</body>
</html>
"""
    }

    companion object {
        const val PORT = 8642

        /** Subdirectory of filesDir holding the generated export files. */
        const val EXPORTS_DIR_NAME = "exports"

        const val FILE_TRAINING_LOG = "training-log.md"
        const val FILE_WORKOUTS_JSON = "workouts.json"
        const val FILE_STRONG_CSV = "strong.csv"
        const val FILE_BODYWEIGHT_CSV = "bodyweight.csv"

        private const val STOP_GRACE_MILLIS = 250L
        private const val STOP_TIMEOUT_MILLIS = 1000L

        /** Served files in display order with their content types. */
        private val FILE_TYPES = listOf(
            FILE_TRAINING_LOG to ContentType("text", "markdown").withCharset(Charsets.UTF_8),
            FILE_WORKOUTS_JSON to ContentType.Application.Json,
            FILE_STRONG_CSV to ContentType.Text.CSV.withCharset(Charsets.UTF_8),
            FILE_BODYWEIGHT_CSV to ContentType.Text.CSV.withCharset(Charsets.UTF_8),
        )

        /** File names in display order — the Export screen renders one row per name. */
        val FILE_NAMES: List<String> = FILE_TYPES.map { it.first }

        /** "412 B" under a KB, otherwise rounded to whole KB ("48 KB"). */
        fun sizeLabel(bytes: Long): String =
            if (bytes < 1024) "$bytes B" else "${(bytes / 1024.0).roundToLong()} KB"

        /**
         * Best-guess LAN IPv4 of this device via plain java.net interface enumeration
         * (no Android context involved). Prefers wlan interfaces so the address is the
         * Wi-Fi one when several radios are up; null when there is no site-local address,
         * i.e. no Wi-Fi/LAN connection.
         */
        fun siteLocalAddress(): String? = runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
            val candidates = mutableListOf<Pair<String, String>>()
            for (nic in interfaces) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.inetAddresses) {
                    if (address is Inet4Address && address.isSiteLocalAddress) {
                        val ip = address.hostAddress ?: continue
                        candidates.add((nic.name ?: "") to ip)
                    }
                }
            }
            candidates.firstOrNull { it.first.startsWith("wlan") }?.second
                ?: candidates.firstOrNull()?.second
        }.getOrNull()
    }
}
