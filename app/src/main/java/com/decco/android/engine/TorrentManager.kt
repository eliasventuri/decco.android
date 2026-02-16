package com.decco.android.engine

import android.os.Environment
import android.util.Log
import org.libtorrent4j.*
import org.libtorrent4j.alerts.*
import org.libtorrent4j.swig.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages torrent sessions using LibTorrent4j.
 * Replicates the desktop engine's torrent-stream functionality.
 */
class TorrentManager(private val downloadDir: File) {

    private var session: SessionManager? = null
    private val activeHandles = ConcurrentHashMap<String, TorrentState>()
    private var isMetered: Boolean = false

    /**
     * State for an active torrent
     */
    data class TorrentState(
        val hash: String,
        val handle: TorrentHandle,
        var status: String = "loading",
        var metadataReady: Boolean = false,
        var selectedFileIndex: Int = -1,
        var selectedFileName: String? = null,
        var selectedFileSize: Long = 0,
        var duration: Double = 0.0,
        var videoCodec: String? = null,
        var requestedFileIdx: Int? = null,
        var requestedSeason: Int? = null,
        var requestedEpisode: Int? = null,
        var lastAccessed: Long = System.currentTimeMillis()
    )

    companion object {
        private const val TAG = "TorrentManager"

        // Same trackers as the desktop engine
        val TRACKERS = listOf(
            "udp://opentor.net:6969",
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "http://open.tracker.cl:1337/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://zer0day.ch:1337/announce",
            "udp://wepzone.net:6969/announce",
            "udp://tracker.srv00.com:6969/announce",
            "udp://tracker.filemail.com:6969/announce",
            "udp://tracker.dler.org:6969/announce",
            "udp://tracker.bittor.pw:1337/announce",
            "udp://tracker-udp.gbitt.info:80/announce",
            "udp://run.publictracker.xyz:6969/announce",
            "udp://opentracker.io:6969/announce",
            "udp://open.dstud.io:6969/announce",
            "udp://explodie.org:6969/announce",
            "https://tracker.iperson.xyz:443/announce",
            "https://torrent.tracker.durukanbal.com:443/announce",
            "https://cny.fan:443/announce",
            "http://tracker2.dler.org:80/announce",
            "http://tracker.wepzone.net:6969/announce"
        )

        // Video file extensions
        val VIDEO_EXTENSIONS = setOf(
            "mkv", "mp4", "avi", "webm", "ts", "mov",
            "wmv", "flv", "m4v", "3gp", "mpg", "mpeg", "ogv"
        )

        // Episode pattern regex (S05E06, 5x06, etc.)
        fun buildEpisodePattern(season: Int, episode: Int): Regex {
            val s = season.toString().padStart(2, '0')
            val e = episode.toString().padStart(2, '0')
            val sNum = season.toString()
            val eNum = episode.toString()
            return Regex(
                "(s0?${sNum}[.\\s_-]?e0?${eNum}\\b)|(\\b0?${sNum}x0?${eNum}\\b)",
                RegexOption.IGNORE_CASE
            )
        }
    }

    /**
     * Initialize the LibTorrent4j session
     */
    fun start() {
        if (session != null) return

        Log.i(TAG, "Starting torrent session...")

        if (!downloadDir.exists()) downloadDir.mkdirs()

        session = SessionManager(true).apply {
            // Configure session settings
            val params = SessionParams(SettingsPack())
            start(params)
        }

        // Set up alert listener for metadata and state changes
        session?.addListener(object : AlertListener {
            override fun types(): IntArray? = null // Listen to all alerts

            override fun alert(alert: Alert<*>?) {
                when (alert) {
                    is MetadataReceivedAlert -> {
                        val hash = alert.handle().infoHash().toHex()
                        Log.i(TAG, "Metadata received for $hash")
                        onMetadataReceived(hash, alert.handle())
                    }
                    is TorrentFinishedAlert -> {
                        val hash = alert.handle().infoHash().toHex()
                        Log.i(TAG, "Torrent finished: $hash")
                    }
                    is TorrentErrorAlert -> {
                        val hash = alert.handle().infoHash().toHex()
                        Log.e(TAG, "Torrent error for $hash: ${alert.message()}")
                        activeHandles[hash]?.status = "error"
                    }
                }
            }
        })

        Log.i(TAG, "Torrent session started")
    }

    /**
     * Stop the torrent session and cleanup
     */
    fun stop() {
        Log.i(TAG, "Stopping torrent session...")
        activeHandles.clear()
        session?.stop()
        session = null
    }

    /**
     * Start a torrent by info hash (magnet link)
     */
    fun startTorrent(
        hash: String,
        fileIdx: Int? = null,
        season: Int? = null,
        episode: Int? = null
    ): TorrentState? {
        val sess = session ?: return null

        // If already active, check if handle is still valid
        activeHandles[hash]?.let { existing ->
            if (existing.handle.isValid) {
                existing.lastAccessed = System.currentTimeMillis()
                if (season != null && episode != null && existing.metadataReady) {
                    val sameEpisode =
                        existing.requestedSeason == season &&
                        existing.requestedEpisode == episode &&
                        existing.selectedFileIndex >= 0
                    if (!sameEpisode) {
                        updateFileSelection(existing, season, episode)
                    }
                }
                return existing
            } else {
                // Remove stale handle and proceed to re-add
                activeHandles.remove(hash)
            }
        }

        Log.i(TAG, "Starting torrent: hash=$hash fileIdx=$fileIdx S${season}E${episode}")

        // Build magnet URI with trackers
        val trackerParams = TRACKERS.joinToString("&tr=") { java.net.URLEncoder.encode(it, "UTF-8") }
        val magnetUri = "magnet:?xt=urn:btih:$hash&tr=$trackerParams"

        val saveDir = File(downloadDir, hash.take(20))
        if (!saveDir.exists()) saveDir.mkdirs()

        // Use standard download (wrapper)
        val flags = torrent_flags_t()
        sess.download(magnetUri, saveDir, flags)

        // Use AddTorrentParams to get safe Sha1Hash from magnet URI
        // libtorrent 2.x uses 'info_hashes' (v1+v2 container) instead of simple 'info_hash'
        val p = AddTorrentParams.parseMagnetUri("magnet:?xt=urn:btih:$hash")
        val infoHashes = p.swig().info_hashes 
        val swigHash = infoHashes.v1 
        val sHash = Sha1Hash(swigHash)
        val handle = sess.find(sHash)

        if (handle == null || !handle.isValid) {
            Log.e(TAG, "Failed to find valid handle after download for $hash")
            return null
        }

        val state = TorrentState(
            hash = hash,
            handle = handle,
            requestedFileIdx = fileIdx,
            requestedSeason = season,
            requestedEpisode = episode
        )

        activeHandles[hash] = state

        // If torrent info is already available (cached), select file immediately
        if (handle.status().hasMetadata()) {
            onMetadataReceived(hash, handle)
        }

        return state
    }

    /**
     * Called when torrent metadata is received — select the correct file
     */
    private fun onMetadataReceived(hash: String, handle: TorrentHandle) {
        val state = activeHandles[hash] ?: return
        val torrentInfo = handle.torrentFile() ?: return
        val fileStorage = torrentInfo.files()
        val numFiles = fileStorage.numFiles()

        Log.i(TAG, "Metadata ready for $hash — $numFiles files")

        // Collect video files
        data class FileEntry(val index: Int, val name: String, val size: Long)
        val files = (0 until numFiles).map { i ->
            FileEntry(i, fileStorage.fileName(i), fileStorage.fileSize(i))
        }
        val videoFiles = files.filter { f ->
            val ext = f.name.substringAfterLast('.', "").lowercase()
            ext in VIDEO_EXTENSIONS
        }

        // Priority 1: Episode pattern match
        var selectedFile: FileEntry? = null

        if (state.requestedSeason != null && state.requestedEpisode != null) {
            val pattern = buildEpisodePattern(state.requestedSeason!!, state.requestedEpisode!!)
            selectedFile = videoFiles.find { pattern.containsMatchIn(it.name) }
            if (selectedFile != null) {
                Log.i(TAG, "Found file by episode pattern: ${selectedFile.name}")
            }
        }

        // Priority 2: File index
        if (selectedFile == null && state.requestedFileIdx != null) {
            val idx = state.requestedFileIdx!!
            if (idx in 0 until numFiles) {
                selectedFile = files[idx]
                Log.i(TAG, "Using fileIdx $idx: ${selectedFile.name}")
            }
        }

        // Priority 3: Largest video file
        if (selectedFile == null) {
            selectedFile = videoFiles.maxByOrNull { it.size } ?: files.firstOrNull()
            Log.i(TAG, "Fallback to largest video: ${selectedFile?.name}")
        }

        if (selectedFile == null) {
            state.status = "error"
            Log.e(TAG, "No suitable file found in torrent $hash")
            return
        }

        // Deselect all files, then select only the target
        val priorities = Priority.array(Priority.IGNORE, numFiles)
        priorities[selectedFile.index] = Priority.DEFAULT
        handle.prioritizeFiles(priorities)

        state.selectedFileIndex = selectedFile.index
        state.selectedFileName = selectedFile.name
        state.selectedFileSize = selectedFile.size
        state.metadataReady = true
        state.status = "ready"

        // Streaming optimization: force sequential mode and prioritize early pieces
        applyStreamingPriorities(handle, torrentInfo, selectedFile.index, selectedFile.size)

        Log.i(TAG, "Selected ONLY: ${selectedFile.name} (${selectedFile.size / 1024 / 1024} MB)")
    }

    /**
     * Update file selection for episode change (without restarting torrent)
     */
    private fun updateFileSelection(state: TorrentState, season: Int, episode: Int) {
        val handle = state.handle
        val torrentInfo = handle.torrentFile() ?: return
        val fileStorage = torrentInfo.files()
        val numFiles = fileStorage.numFiles()

        if (state.requestedSeason == season && state.requestedEpisode == episode && state.selectedFileIndex >= 0) {
            return
        }

        val pattern = buildEpisodePattern(season, episode)

        for (i in 0 until numFiles) {
            val name = fileStorage.fileName(i)
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in VIDEO_EXTENSIONS && pattern.containsMatchIn(name)) {
                // Deselect all, select new file
                val priorities = Priority.array(Priority.IGNORE, numFiles)
                priorities[i] = Priority.DEFAULT
                handle.prioritizeFiles(priorities)

                state.selectedFileIndex = i
                state.selectedFileName = name
                state.selectedFileSize = fileStorage.fileSize(i)
                state.requestedSeason = season
                state.requestedEpisode = episode

                applyStreamingPriorities(handle, torrentInfo, i, state.selectedFileSize)
                Log.i(TAG, "Corrected file selection to: $name")
                return
            }
        }

        Log.w(TAG, "No file matching S${season}E${episode} found")
    }

    /**
     * Get the state for a specific torrent
     */
    fun getState(hash: String): TorrentState? = activeHandles[hash]

    /**
     * Get live status info for a torrent (peers, speed, progress)
     */
    fun getStatus(hash: String): Map<String, Any?> {
        val state = activeHandles[hash] ?: return mapOf("status" to "not_started")
        val handle = state.handle
        val status = handle.status()

        state.lastAccessed = System.currentTimeMillis()

        return mapOf(
            "status" to state.status,
            "metadataReady" to state.metadataReady,
            "fileName" to state.selectedFileName,
            "fileSize" to state.selectedFileSize,
            "fileIdx" to state.selectedFileIndex,
            "totalFiles" to (handle.torrentFile()?.files()?.numFiles() ?: 0),
            "duration" to state.duration,
            "peers" to status.numPeers(),
            "seeds" to status.numSeeds(),
            "speed" to String.format("%.2f", status.downloadPayloadRate() / 1024.0),
            "progress" to String.format("%.1f", status.progress() * 100)
        )
    }

    /**
     * Create an InputStream for the selected video file at a byte offset.
     * Used by the proxy endpoint for Range-based streaming.
     */
    fun createFileStream(hash: String, start: Long = 0, end: Long = -1): FileStreamInfo? {
        val state = activeHandles[hash] ?: return null
        if (!state.metadataReady || state.selectedFileIndex < 0) return null

        state.lastAccessed = System.currentTimeMillis()

        val handle = state.handle
        val torrentInfo = handle.torrentFile() ?: return null
        val fileStorage = torrentInfo.files()
        val fileSize = fileStorage.fileSize(state.selectedFileIndex)

        // Calculate the actual file path on disk
        val savePath = handle.savePath()
        val filePath = fileStorage.filePath(state.selectedFileIndex)
        val fullPath = File(savePath, filePath)

        if (!fullPath.exists()) {
            Log.w(TAG, "File not yet downloaded: $fullPath")
            return null
        }

        val actualEnd = if (end < 0 || end >= fileSize) fileSize - 1 else end

        return FileStreamInfo(
            handle = handle,
            torrentInfo = torrentInfo,
            fileIndex = state.selectedFileIndex,
            file = fullPath,
            fileSize = fileSize,
            start = start,
            end = actualEnd
        )
    }

    data class FileStreamInfo(
        val handle: TorrentHandle,
        val torrentInfo: TorrentInfo,
        val fileIndex: Int,
        val file: File,
        val fileSize: Long,
        val start: Long,
        val end: Long
    ) {
        val contentLength: Long get() = end - start + 1

        fun createInputStream(): InputStream {
            val raf = java.io.RandomAccessFile(file, "r")
            raf.seek(start)
            
            return object : InputStream() {
                private var pos = start
                private val pieceLength = torrentInfo.pieceLength().toLong()
                private val fileOffsetInTorrent = torrentInfo.files().fileOffset(fileIndex)

                private fun ensurePieceAvailable(bytePos: Long) {
                    val absolutePos = fileOffsetInTorrent + bytePos
                    val pieceIndex = (absolutePos / pieceLength).toInt()
                    
                    if (handle.havePiece(pieceIndex)) return

                    Log.d(TAG, "Waiting for piece $pieceIndex (byte $bytePos)...")
                    val startTime = System.currentTimeMillis()
                    val timeout = 60000L // 60 seconds max wait
                    var lastReannounceAt = startTime

                    // Prioritize current piece and the next few pieces for smooth playback.
                    for (i in 0..12) {
                        handle.setPieceDeadline(pieceIndex + i, 1000 + (i * 250))
                    }
                    
                    while (!handle.havePiece(pieceIndex)) {
                        val now = System.currentTimeMillis()
                        if (now - lastReannounceAt > 5000L) {
                            try {
                                handle.forceReannounce()
                            } catch (_: Exception) {}
                            try {
                                val st = handle.status()
                                Log.d(
                                    TAG,
                                    "Still waiting piece=$pieceIndex peers=${st.numPeers()} seeds=${st.numSeeds()} speed=${st.downloadPayloadRate() / 1024}KB/s progress=${"%.2f".format(st.progress() * 100)}%"
                                )
                            } catch (_: Exception) {}
                            lastReannounceAt = now
                        }

                        if (System.currentTimeMillis() - startTime > timeout) {
                            Log.w(TAG, "Timeout waiting for piece $pieceIndex")
                            throw IOException("Timeout waiting for piece $pieceIndex")
                        }
                        try {
                            Thread.sleep(500)
                        } catch (e: InterruptedException) {
                            throw IOException("Interrupted while waiting for piece $pieceIndex")
                        }
                    }
                }

                override fun read(): Int {
                    if (pos > end) return -1
                    ensurePieceAvailable(pos)
                    return try {
                        val b = raf.read()
                        if (b != -1) pos++
                        b
                    } catch (e: Exception) {
                        Log.e(TAG, "Stream read error at $pos: ${e.message}")
                        -1
                    }
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (pos > end) return -1
                    val toRead = java.lang.Math.min(len.toLong(), end - pos + 1).toInt()
                    
                    ensurePieceAvailable(pos)
                    
                    return try {
                        val read = raf.read(b, off, toRead)
                        if (read != -1) pos += read
                        read
                    } catch (e: Exception) {
                        Log.e(TAG, "Stream chunk read error at $pos: ${e.message}")
                        -1
                    }
                }

                override fun close() {
                    try {
                        raf.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing RAF: ${e.message}")
                    }
                }

                override fun available(): Int {
                    return java.lang.Math.min(super.available().toLong(), end - pos + 1).toInt()
                }
            }
        }
    }

    private fun applyStreamingPriorities(
        handle: TorrentHandle,
        torrentInfo: TorrentInfo,
        fileIndex: Int,
        fileSize: Long
    ) {
        try {
            val fileOffset = torrentInfo.files().fileOffset(fileIndex)
            val pieceLength = torrentInfo.pieceLength().toLong().coerceAtLeast(1L)
            val firstPiece = (fileOffset / pieceLength).toInt()
            val lastPiece = ((fileOffset + fileSize - 1L) / pieceLength).toInt()

            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            handle.setSequentialRange(firstPiece, lastPiece)

            // Ask aggressively for the first chunk of pieces to start playback quickly.
            val bootstrapPieces = minOf(40, (lastPiece - firstPiece + 1).coerceAtLeast(1))
            for (i in 0 until bootstrapPieces) {
                handle.setPieceDeadline(firstPiece + i, 300 + (i * 120))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply streaming priorities: ${e.message}")
        }
    }

    /**
     * Pause a specific torrent
     */
    fun pauseTorrent(hash: String) {
        val state = activeHandles[hash] ?: return
        state.handle.pause()
        state.status = "paused"
        Log.i(TAG, "Paused torrent $hash")
    }

    /**
     * Resume a specific torrent
     */
    fun resumeTorrent(hash: String) {
        val state = activeHandles[hash] ?: return
        // Don't resume if globally metered
        if (isMetered) {
            Log.i(TAG, "Cannot resume $hash: global metered mode active")
            return
        }
        state.handle.resume()
        state.status = if (state.metadataReady) "ready" else "loading"
        Log.i(TAG, "Resumed torrent $hash")
    }

    /**
     * Set global metered mode. If true, all torrents are paused.
     */
    fun setMeteredMode(metered: Boolean) {
        if (this.isMetered == metered) return
        this.isMetered = metered
        Log.i(TAG, "Metered mode: $metered")
        
        if (metered) {
            activeHandles.values.forEach { it.handle.pause() }
        } else {
            activeHandles.values.forEach { it.handle.resume() }
        }
    }

    /**
     * Remove a torrent and clean up its files
     */
    fun removeTorrent(hash: String) {
        val state = activeHandles.remove(hash) ?: return
        session?.remove(state.handle)
        Log.i(TAG, "Removed torrent $hash")
    }

    /**
     * Cleanup torrents older than maxAge milliseconds
     */
    fun cleanupOldTorrents(maxAgeMs: Long = 72 * 60 * 60 * 1000) {
        val now = System.currentTimeMillis()
        val toRemove = activeHandles.filter { (_, state) ->
            now - state.lastAccessed > maxAgeMs
        }

        toRemove.forEach { (hash, _) ->
            removeTorrent(hash)
            Log.i(TAG, "Cleaned old torrent: $hash")
        }

        if (toRemove.isNotEmpty()) {
            Log.i(TAG, "Cleanup complete. Removed ${toRemove.size} old torrents.")
        }
    }
}
