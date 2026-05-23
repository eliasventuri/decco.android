package com.decco.android.server

import android.util.Log
import android.content.Context
import android.content.Intent
import com.decco.android.engine.TorrentManager
import fi.iki.elonen.NanoHTTPD
import java.io.File

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
    private val torrentManager: TorrentManager,
    private val context: android.content.Context
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

            // ---- Start download ----
            uri == "/download/start" -> {
                val p = session.parms
                val hash = p["hash"] ?: return cors(jsonResponse(Response.Status.BAD_REQUEST, """{"error":"hash is required"}"""))
                val title = p["title"] ?: "Video"
                val imdbId = p["imdbId"] ?: ""
                val season = p["season"]?.toIntOrNull() ?: 0
                val episode = p["episode"]?.toIntOrNull() ?: 0
                val fileIdx = p["fileIdx"]?.toIntOrNull()

                val meta = torrentManager.startDownload(hash, title, imdbId, season, episode, fileIdx)
                cors(jsonResponse(Response.Status.OK, """{"status":"started","hash":"$hash","title":"${meta["title"] ?: title}"}"""))
            }

            // ---- Downloads list ----
            uri == "/download/list" -> {
                val list = torrentManager.getDownloadsList()
                val arr = org.json.JSONArray()
                for (map in list) {
                    val obj = org.json.JSONObject()
                    for ((k, v) in map) {
                        if (v is List<*>) {
                            val subArr = org.json.JSONArray()
                            for (subItem in v) {
                                if (subItem is Map<*, *>) {
                                    val subObj = org.json.JSONObject()
                                    for ((sk, sv) in subItem) {
                                        subObj.put(sk.toString(), sv)
                                    }
                                    subArr.put(subObj)
                                }
                            }
                            obj.put(k, subArr)
                        } else {
                            obj.put(k, v ?: org.json.JSONObject.NULL)
                        }
                    }
                    arr.put(obj)
                }
                val responseJson = org.json.JSONObject().put("downloads", arr).toString()
                cors(jsonResponse(Response.Status.OK, responseJson))
            }

            // ---- Pause download ----
            uri.matches(Regex("/download/pause/([a-fA-F0-9]+)")) -> {
                val hash = uri.substringAfter("/download/pause/")
                torrentManager.pauseDownload(hash)
                cors(jsonResponse(Response.Status.OK, """{"status":"paused","hash":"$hash"}"""))
            }

            // ---- Resume download ----
            uri.matches(Regex("/download/resume/([a-fA-F0-9]+)")) -> {
                val hash = uri.substringAfter("/download/resume/")
                torrentManager.resumeDownload(hash)
                cors(jsonResponse(Response.Status.OK, """{"status":"resumed","hash":"$hash"}"""))
            }

            // ---- Delete download ----
            uri.matches(Regex("/download/delete/([a-fA-F0-9]+)")) -> {
                val hash = uri.substringAfter("/download/delete/")
                torrentManager.deleteDownload(hash)
                cors(jsonResponse(Response.Status.OK, """{"status":"deleted","hash":"$hash"}"""))
            }

            // ---- Open download (Play locally) ----
            uri.matches(Regex("/download/open/([a-fA-F0-9]+)")) -> {
                val hash = uri.substringAfter("/download/open/")
                val list = torrentManager.getDownloadsList()
                val match = list.find { (it["hash"] as? String)?.lowercase() == hash.lowercase() }
                
                if (match != null) {
                    val title = match["title"] as? String ?: "Video"
                    val imdbId = match["imdbId"] as? String ?: ""
                    val season = match["season"] as? Int ?: 0
                    val episode = match["episode"] as? Int ?: 0
                    val fileIdx = match["fileIdx"] as? Int
                    
                    val subList = match["subtitles"] as? List<Map<String, String>>
                    val subArray = org.json.JSONArray()
                    subList?.forEach { sub ->
                        val subObj = org.json.JSONObject()
                        subObj.put("language", sub["lang"])
                        subObj.put("label", sub["label"])
                        subObj.put("url", "http://127.0.0.1:8888/download/subtitle/$hash/${sub["lang"]}")
                        subArray.put(subObj)
                    }

                    val streamUrl = "http://127.0.0.1:8888/proxy/$hash"
                    val playerIntent = com.decco.android.player.PlayerActivity.createIntent(
                        context = context,
                        streamUrl = streamUrl,
                        hash = hash,
                        title = title,
                        subtitleTitle = if (season > 0) "S${season}E${episode}" else "",
                        startPosition = 0,
                        imdbId = imdbId,
                        season = season,
                        episode = episode,
                        subtitlesJson = if (subArray.length() > 0) subArray.toString() else null
                    )
                    playerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(playerIntent)
                    cors(jsonResponse(Response.Status.OK, """{"status":"opened","hash":"$hash"}"""))
                } else {
                    cors(jsonResponse(Response.Status.NOT_FOUND, """{"error":"Download not found"}"""))
                }
            }

            // ---- Download subtitle ----
            uri.matches(Regex("/download/subtitle/([a-fA-F0-9]+)/(\\w+)")) -> {
                val parts = uri.split("/")
                val hash = parts[3]
                val lang = parts[4]
                
                val list = torrentManager.getDownloadsList()
                val match = list.find { (it["hash"] as? String)?.lowercase() == hash.lowercase() }
                val subs = match?.get("subtitles") as? List<Map<String, String>>
                val subMatch = subs?.find { it["lang"] == lang }
                val filePath = subMatch?.get("path")
                
                if (filePath != null) {
                    val file = java.io.File(filePath)
                    if (file.exists()) {
                        val input = java.io.FileInputStream(file)
                        cors(newFixedLengthResponse(Response.Status.OK, "text/vtt", input, file.length()))
                    } else {
                        cors(jsonResponse(Response.Status.NOT_FOUND, """{"error":"Subtitle file not found"}"""))
                    }
                } else {
                    cors(jsonResponse(Response.Status.NOT_FOUND, """{"error":"Subtitle not found"}"""))
                }
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

            // ---- Cache clear ----
            uri == "/cache/clear" || uri == "/cache" -> {
                val cleared = torrentManager.clearCache()
                val status = if (cleared) Response.Status.OK else Response.Status.INTERNAL_ERROR
                cors(jsonResponse(status, """{"status":"${if (cleared) "ok" else "error"}","cleared":$cleared}"""))
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
        // Check if it is a completed download — always serve the LARGEST video file
        val list = torrentManager.getDownloadsList()
        val downloadMatch = list.find { (it["hash"] as? String)?.lowercase() == hash.lowercase() }
        if (downloadMatch != null && downloadMatch["status"] == "completed") {
            val downloadDirStr = downloadMatch["downloadDir"] as? String
            if (!downloadDirStr.isNullOrEmpty()) {
                val largestFile = findLargestVideoFile(File(downloadDirStr))
                if (largestFile != null && largestFile.exists()) {
                    Log.i(TAG, "Serving completed download (largest file) from disk: ${largestFile.absolutePath}")
                    return serveLocalFile(session, largestFile)
                }
            }
        }

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

    private fun serveLocalFile(session: IHTTPSession, file: File): Response {
        val fileSize = file.length()
        val extension = file.name.substringAfterLast('.', "mp4")
        val mimeType = when (extension.lowercase()) {
            "mkv" -> "video/x-matroska"
            else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "video/mp4"
        }

        val rangeHeader = session.headers["range"]

        if (rangeHeader == null) {
            val input = java.io.FileInputStream(file)
            val resp = newFixedLengthResponse(Response.Status.OK, mimeType, input, fileSize)
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
        val contentLength = end - start + 1

        return try {
            val raf = java.io.RandomAccessFile(file, "r")
            raf.seek(start)
            
            val input = object : java.io.InputStream() {
                private var pos = start
                
                override fun read(): Int {
                    if (pos > end) return -1
                    val b = raf.read()
                    if (b != -1) pos++
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (pos > end) return -1
                    val toRead = java.lang.Math.min(len.toLong(), end - pos + 1).toInt()
                    val read = raf.read(b, off, toRead)
                    if (read != -1) pos += read
                    return read
                }

                override fun close() {
                    raf.close()
                }

                override fun available(): Int {
                    return java.lang.Math.min(super.available().toLong(), end - pos + 1).toInt()
                }
            }

            val resp = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mimeType,
                input,
                contentLength
            )
            resp.addHeader("Content-Range", "bytes $start-$end/$fileSize")
            resp.addHeader("Accept-Ranges", "bytes")
            cors(resp)
        } catch (e: Exception) {
            Log.e(TAG, "Local File Stream Error: ${e.message}")
            cors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Stream error"))
        }
    }

    // ========================
    //  HELPERS
    // ========================

    private fun cors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
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

    /**
     * Recursively find the largest video file in a directory.
     * This avoids serving sample/preview clips when the torrent
     * contains both main content and small sample files.
     */
    private fun findLargestVideoFile(dir: File): File? {
        if (!dir.exists() || !dir.isDirectory) return null
        val videoExtensions = setOf(
            "mkv", "mp4", "avi", "webm", "ts", "mov",
            "wmv", "flv", "m4v", "3gp", "mpg", "mpeg", "ogv"
        )
        var largestFile: File? = null
        var largestSize = -1L

        fun scan(currentDir: File) {
            val children = currentDir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    scan(child)
                } else {
                    val ext = child.extension.lowercase()
                    if (ext in videoExtensions && child.length() > largestSize) {
                        largestSize = child.length()
                        largestFile = child
                    }
                }
            }
        }

        scan(dir)
        return largestFile
    }

    companion object {
        private const val TAG = "EngineServer"
    }
}
