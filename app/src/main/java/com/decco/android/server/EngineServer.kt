package com.decco.android.server

import android.util.Log
import com.decco.android.engine.TorrentManager
import fi.iki.elonen.NanoHTTPD

/**
 * HTTP server replicating the full Decco Engine API on port 8888.
 *
 * All endpoints:
 * - GET /status/check               → Health check
 * - GET /start/:hash                → Start torrent engine
 * - GET /pause/:hash                → Pause torrent engine
 * - GET /stop/:hash                 → Stop and remove torrent
 * - GET /status/:hash               → Engine status
 * - GET /proxy/:hash                → Raw video proxy (Range)
 * - GET /network/metered            → Set metered mode (value=true|false)
 */
class EngineServer(
    port: Int,
    private val torrentManager: TorrentManager
) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "${session.method} $uri")

        return try {
            route(session, uri)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $uri", e)
            cors(jsonResponse(Response.Status.INTERNAL_ERROR, """{"error":"${e.message}"}"""))
        }
    }

    private fun route(session: IHTTPSession, uri: String): Response {
        if (session.method == NanoHTTPD.Method.OPTIONS) {
            return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
        }

        return when {
            // ---- Health check ----
            uri == "/status/check" -> {
                cors(jsonResponse(Response.Status.OK,
                    """{"status":"ok","platform":"android","version":"1.0.0"}"""))
            }

            // ---- Start torrent ----
            uri.matches(Regex("/start/([a-fA-F0-9]+)")) -> {
                val hash = uri.removePrefix("/start/")
                val p = session.parms
                val fileIdx = p["fileIdx"]?.toIntOrNull()
                val season = p["season"]?.toIntOrNull()
                val episode = p["episode"]?.toIntOrNull()

                Log.i(TAG, "Start: hash=$hash fileIdx=$fileIdx S${season}E${episode}")
                torrentManager.startTorrent(hash, fileIdx, season, episode)

                cors(jsonResponse(Response.Status.OK,
                    """{"status":"started","hash":"$hash","fileIdx":$fileIdx,"season":$season,"episode":$episode}"""))
            }

            // ---- Torrent status ----
            uri.matches(Regex("/status/([a-fA-F0-9]+)")) -> {
                val hash = uri.removePrefix("/status/")
                cors(jsonResponse(Response.Status.OK, mapToJson(torrentManager.getStatus(hash))))
            }

            // ---- Pause torrent ----
            uri.matches(Regex("/pause/([a-fA-F0-9]+)")) -> {
                val hash = uri.removePrefix("/pause/")
                torrentManager.pauseTorrent(hash)
                cors(jsonResponse(Response.Status.OK, """{"status":"paused","hash":"$hash"}"""))
            }

            // ---- Stop torrent ----
            uri.matches(Regex("/stop/([a-fA-F0-9]+)")) -> {
                val hash = uri.removePrefix("/stop/")
                torrentManager.removeTorrent(hash)
                cors(jsonResponse(Response.Status.OK, """{"status":"removed","hash":"$hash"}"""))
            }

            // ---- Network mode ----
            uri == "/network/metered" -> {
                val isMetered = session.parms["value"]?.toBoolean() ?: false
                torrentManager.setMeteredMode(isMetered)
                cors(jsonResponse(Response.Status.OK, """{"status":"ok","metered":$isMetered}"""))
            }

            // ---- Video proxy (Range) ----
            uri.matches(Regex("/proxy/([a-fA-F0-9]+)")) -> {
                val hash = uri.substringAfter("/proxy/").substringBefore("/")
                Log.d(TAG, "Proxy Request: $uri | Range: ${session.headers["range"]}")
                handleProxy(session, hash)
            }

            // ---- 404 ----
            else -> {
                cors(jsonResponse(Response.Status.NOT_FOUND, """{"error":"Not found","uri":"$uri"}"""))
            }
        }
    }

    // ========================
    //  PROXY (Range support)
    // ========================

    private fun handleProxy(session: IHTTPSession, hash: String): Response {
        val state = waitForMetadata(hash, timeoutMs = 60_000)
        if (state == null) {
            return cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Torrent not started"))
        }
        if (state.status == "error") {
            return cors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Torrent error"))
        }
        if (!state.metadataReady) {
            return cors(newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Engine warming up"))
        }

        val fileSize = state.selectedFileSize
        val fileName = state.selectedFileName ?: "video.mp4"
        val extension = fileName.substringAfterLast('.', "mp4")
        val mimeType = when (extension.lowercase()) {
            "mkv" -> "video/x-matroska"
            else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "video/mp4"
        }

        val rangeHeader = session.headers["range"]

        if (rangeHeader == null) {
            val streamInfo = torrentManager.createFileStream(hash) ?: return cors(
                newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "File not available yet")
            )
            val resp = newFixedLengthResponse(Response.Status.OK, mimeType, streamInfo.createInputStream(), streamInfo.fileSize)
            // NanoHTTPD handles Content-Length automatically when size is provided to newFixedLengthResponse
            resp.addHeader("Accept-Ranges", "bytes")
            return cors(resp)
        }

        val parsedRange = parseRangeHeader(rangeHeader, fileSize)
        if (parsedRange == null) {
            val resp = newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid Range header")
            resp.addHeader("Accept-Ranges", "bytes")
            return cors(resp)
        }
        val start = parsedRange.first
        val end = parsedRange.second

        val streamInfo = torrentManager.createFileStream(hash, start, end) ?: return cors(
            newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "File segment not available yet")
        )

        return try {
            val resp = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mimeType,
                streamInfo.createInputStream(),
                streamInfo.contentLength
            )
            resp.addHeader("Content-Range", "bytes $start-$end/$fileSize")
            resp.addHeader("Accept-Ranges", "bytes")
            cors(resp)
        } catch (e: Exception) {
            Log.e(TAG, "Proxy Stream Error: ${e.message}")
            cors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Stream error"))
        }
    }

    // ========================
    //  HELPERS
    // ========================

    private fun cors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Range, Content-Type")
        return response
    }

    private fun jsonResponse(status: Response.Status, json: String): Response {
        return newFixedLengthResponse(status, "application/json", json)
    }

    private fun waitForMetadata(hash: String, timeoutMs: Long): TorrentManager.TorrentState? {
        val start = System.currentTimeMillis()
        var lastState: TorrentManager.TorrentState? = null

        while (System.currentTimeMillis() - start < timeoutMs) {
            val state = torrentManager.getState(hash)
            lastState = state
            if (state == null) {
                try {
                    Thread.sleep(150)
                } catch (_: InterruptedException) {
                    return lastState
                }
                continue
            }
            if (state.metadataReady || state.status == "error") {
                return state
            }
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return lastState
            }
        }

        return lastState
    }

    private fun parseRangeHeader(rangeHeader: String, fileSize: Long): Pair<Long, Long>? {
        if (!rangeHeader.startsWith("bytes=")) return null
        val value = rangeHeader.removePrefix("bytes=").trim()
        if (value.contains(",")) return null // Multi-range not supported.

        val parts = value.split("-", limit = 2)
        if (parts.size != 2) return null

        val startText = parts[0].trim()
        val endText = parts[1].trim()

        if (startText.isEmpty()) {
            val suffixLength = endText.toLongOrNull() ?: return null
            if (suffixLength <= 0) return null
            val start = maxOf(fileSize - suffixLength, 0L)
            val end = fileSize - 1
            return if (start <= end) start to end else null
        }

        val start = startText.toLongOrNull() ?: return null
        if (start < 0 || start >= fileSize) return null

        val end = if (endText.isEmpty()) {
            fileSize - 1
        } else {
            val parsed = endText.toLongOrNull() ?: return null
            minOf(parsed, fileSize - 1)
        }
        if (end < start) return null

        return start to end
    }

    override fun useGzipWhenAccepted(r: Response): Boolean {
        // NEVER gzip video streams, it breaks seeking and header sniffing
        val mime = r.mimeType?.lowercase() ?: ""
        if (mime.contains("video") || mime.contains("matroska")) return false
        return super.useGzipWhenAccepted(r)
    }

    private fun mapToJson(map: Map<String, Any?>): String {
        val entries = map.entries.joinToString(",") { (k, v) ->
            val jv = when (v) {
                is String -> "\"$v\""
                is Boolean -> v.toString()
                is Number -> v.toString()
                null -> "null"
                else -> "\"$v\""
            }
            "\"$k\":$jv"
        }
        return "{$entries}"
    }

    companion object {
        private const val TAG = "EngineServer"
    }
}
