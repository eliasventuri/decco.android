package com.decco.android.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.decco.android.R
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayList
import java.util.Locale

/**
 * Full-screen video player using Media3 ExoPlayer.
 * Provides feature parity with the web player including subtitle formatting and track selection.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PlayerActivity"

        private const val EXTRA_STREAM_URL = "stream_url"
        private const val EXTRA_HASH = "hash"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE_TITLE = "subtitle_title"
        private const val EXTRA_START_POSITION = "start_position"
        private const val EXTRA_IMDB_ID = "imdb_id"
        private const val EXTRA_SEASON = "season"
        private const val EXTRA_EPISODE = "episode"
        private const val EXTRA_SUBTITLES_JSON = "subtitles_json"

        fun createIntent(
            context: Context,
            streamUrl: String,
            hash: String,
            title: String = "",
            subtitleTitle: String = "",
            startPosition: Long = 0,
            imdbId: String = "",
            season: Int = 0,
            episode: Int = 0,
            subtitlesJson: String? = null
        ): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_STREAM_URL, streamUrl)
                putExtra(EXTRA_HASH, hash)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE_TITLE, subtitleTitle)
                putExtra(EXTRA_START_POSITION, startPosition)
                putExtra(EXTRA_IMDB_ID, imdbId)
                putExtra(EXTRA_SEASON, season)
                putExtra(EXTRA_EPISODE, episode)
                putExtra(EXTRA_SUBTITLES_JSON, subtitlesJson)
            }
        }
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var loadingView: View? = null

    private var titleView: TextView? = null
    private var subtitleTitleView: TextView? = null
    
    private var errorOverlay: View? = null
    private var errorMessageView: TextView? = null

    private var streamUrl: String = ""
    private var hash: String = ""
    private var mediaTitle: String = ""
    private var subtitleTitle: String = ""
    private var startPosition: Long = 0
    private var imdbId: String = ""
    private var season: Int = 0
    private var episode: Int = 0

    private val externalSubtitles = ArrayList<MediaItem.SubtitleConfiguration>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "PlayerActivity (ExoPlayer) onCreate started")

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handleIntent(intent)

        if (streamUrl.isEmpty()) {
            Toast.makeText(this, "Error: No stream URL", Toast.LENGTH_LONG).show()
            finishAndRemoveTask()
            return
        }

        setContentView(R.layout.activity_player)
        goFullscreen()

        playerView = findViewById(R.id.player_view)
        loadingView = findViewById(R.id.loading_overlay)
        titleView = findViewById(R.id.title_text)
        subtitleTitleView = findViewById(R.id.subtitle_title_text)
        
        errorOverlay = findViewById(R.id.error_overlay)
        errorMessageView = findViewById(R.id.error_message)

        updateUi()

        playerView.findViewById<View>(R.id.btn_back)?.setOnClickListener { finishAndRemoveTask() }
        playerView.findViewById<View>(R.id.btn_subtitle)?.setOnClickListener { showSubtitleSelectionDialog() }
        playerView.findViewById<View>(R.id.btn_audio)?.setOnClickListener { showAudioTrackSelectionDialog() }
        
        findViewById<View>(R.id.btn_retry)?.setOnClickListener { retryPlayback() }
        findViewById<View>(R.id.btn_error_back)?.setOnClickListener { finishAndRemoveTask() }

        initializePlayer()
        
        // Only fetch if we don't already have external subtitles from Intent
        if (externalSubtitles.isEmpty() && hash.isNotEmpty() && imdbId.isNotEmpty()) {
            fetchExternalSubtitles()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "onNewIntent called")
        setIntent(intent)
        handleIntent(intent)
        updateUi()
        player?.let { preparePlayer(it) }
        
        if (hash.isNotEmpty() && imdbId.isNotEmpty()) {
            fetchExternalSubtitles()
        }
    }

    private fun handleIntent(intent: Intent) {
        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: ""
        hash = intent.getStringExtra(EXTRA_HASH) ?: ""
        mediaTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        subtitleTitle = intent.getStringExtra(EXTRA_SUBTITLE_TITLE) ?: ""
        startPosition = intent.getLongExtra(EXTRA_START_POSITION, 0)
        imdbId = intent.getStringExtra(EXTRA_IMDB_ID) ?: ""
        season = intent.getIntExtra(EXTRA_SEASON, 0)
        episode = intent.getIntExtra(EXTRA_EPISODE, 0)
        
        val subsJson = intent.getStringExtra(EXTRA_SUBTITLES_JSON)
        if (!subsJson.isNullOrEmpty()) {
            parseSubtitlesJson(subsJson)
        }
        
        Log.d(TAG, "handleIntent: url=$streamUrl, hash=$hash, title=$mediaTitle, subsCount=${externalSubtitles.size}")
    }

    private fun parseSubtitlesJson(jsonStr: String) {
        try {
            val jsonArray = JSONArray(jsonStr)
            val configs = ArrayList<MediaItem.SubtitleConfiguration>()
            for (i in 0 until jsonArray.length()) {
                val sub = jsonArray.getJSONObject(i)
                val subUrl = sub.optString("url", "")
                val langCode = sub.optString("language", "und")
                val providedLabel = sub.optString("label")
                
                // Use provided label if present, otherwise generate full language name
                val label = if (!providedLabel.isNullOrEmpty()) providedLabel else getLanguageName(langCode)
                
                if (subUrl.isNotEmpty()) {
                    val formatHint = sub.optString("format", "").lowercase()
                    val mimeType = when {
                        formatHint == "vtt" || subUrl.contains(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
                        formatHint == "srt" || subUrl.contains(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
                        else -> MimeTypes.TEXT_VTT // Default
                    }
                    configs.add(
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                            .setMimeType(mimeType)
                            .setLanguage(langCode) // Keep code for system
                            .setLabel(label)       // Readable name for UI
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                    )
                }
            }
            if (configs.isNotEmpty()) {
                externalSubtitles.clear()
                externalSubtitles.addAll(configs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing subtitles JSON: ${e.message}")
        }
    }
    
    private fun startLogoAnimation() {
        val logo = findViewById<ImageView>(R.id.loading_logo) ?: return
        logo.clearAnimation()
        val anim = AlphaAnimation(0.4f, 1.0f).apply {
            duration = 1000
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        logo.startAnimation(anim)
    }


    private fun updateUi() {
        try {
            val titleText = playerView.findViewById<TextView>(R.id.title_text)
            val subtitleText = playerView.findViewById<TextView>(R.id.subtitle_title_text)

            titleText?.text = mediaTitle
            subtitleText?.text = subtitleTitle
            subtitleText?.visibility = if (subtitleTitle.isNotEmpty()) View.VISIBLE else View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Error updating UI: ${e.message}")
        }
    }

    private fun initializePlayer() {
        // Use DefaultRenderersFactory and prefer extensions (like FFmpeg, VP9) for universal codec support
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        // Tune Buffering for heavy/slow streams
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,  // Min buffer
                60_000,  // Max buffer
                2_500,   // Buffer for playback
                5_000    // Buffer for playback after rebuffer
            )
            .setBackBuffer(10_000, true)
            .build()

        // Tune HTTP Network Timeouts
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build().also { exoPlayer ->
            playerView.player = exoPlayer
            
            // Subtitle Formatting (Premium Web Style)
            playerView.subtitleView?.apply {
                setApplyEmbeddedStyles(false)
                setApplyEmbeddedFontSizes(false)
                setStyle(CaptionStyleCompat(
                    Color.WHITE, // Foreground (White or Yellow is common)
                    Color.TRANSPARENT, // Background
                    Color.TRANSPARENT, // Window
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, // Outline for high legibility
                    Color.BLACK, // Outline color
                    null // Typeface
                ))
                setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 24f) // Large readable text
            }

            // Consistently hide controls
            playerView.controllerShowTimeoutMs = 3000
            // Disable default hide on touch so we can handle it manually for "Tap to Play/Pause"
            playerView.controllerHideOnTouch = false

            // Sync System Bars with Controller Visibility
            playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
                if (visibility == View.VISIBLE) {
                    windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
                } else {
                    windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
                }
            })

            // Manual Play/Pause Button Logic (Fallback if XML binding fails)
            val btnPlay = playerView.findViewById<ImageButton>(androidx.media3.ui.R.id.exo_play)
            val btnPause = playerView.findViewById<ImageButton>(androidx.media3.ui.R.id.exo_pause)

            btnPlay?.setOnClickListener { exoPlayer.play() }
            btnPause?.setOnClickListener { exoPlayer.pause() }

            fun updatePlayPauseButtons(isPlaying: Boolean) {
                if (isPlaying) {
                    btnPlay?.visibility = View.GONE
                    btnPause?.visibility = View.VISIBLE
                } else {
                    btnPlay?.visibility = View.VISIBLE
                    btnPause?.visibility = View.GONE
                }
            }

            // Custom Touch Logic: Tap anywhere to toggle Play/Pause
            playerView.setOnClickListener {
                if (exoPlayer.isPlaying) {
                    exoPlayer.pause()
                    playerView.showController()
                } else {
                    exoPlayer.play()
                    playerView.showController() 
                }
            }
            
            // Initial state
            updatePlayPauseButtons(exoPlayer.isPlaying)

            exoPlayer.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseButtons(isPlaying)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> {
                            loadingView?.visibility = View.VISIBLE
                            startLogoAnimation()
                        }
                        Player.STATE_READY -> {
                            loadingView?.visibility = View.GONE
                            findViewById<ImageView>(R.id.loading_logo)?.clearAnimation()
                        }
                        Player.STATE_ENDED -> finishAndRemoveTask()
                        else -> {}
                    }
                    updatePlayPauseButtons(exoPlayer.isPlaying)
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val errorMessage = when (error.errorCode) {
                        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED -> 
                            "Codec Error: Decoding failed. Your hardware might not support this profile."
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                            "Connection Error: Check local engine"
                        else -> "Playback Error: ${error.errorCodeName}"
                    }
                    
                    Log.e(TAG, "Player Error (Code ${error.errorCode}): ${error.message}", error)
                    
                    // Show error overlay instead of closing
                    errorMessageView?.text = errorMessage
                    errorOverlay?.visibility = View.VISIBLE
                    loadingView?.visibility = View.GONE
                    
                    // Pass error back to web app (legacy support)
                    val resultIntent = Intent().apply {
                        putExtra("error_code", error.errorCode)
                        putExtra("error_message", errorMessage)
                        putExtra("hash", hash)
                    }
                    setResult(RESULT_CANCELED, resultIntent)
                }
            })

            preparePlayer(exoPlayer)
        }
    }

    private fun retryPlayback() {
        val exoPlayer = player ?: return
        val currentPos = exoPlayer.currentPosition
        
        errorOverlay?.visibility = View.GONE
        loadingView?.visibility = View.VISIBLE
        startLogoAnimation()
        
        // Hard reset: re-bind surface and re-set media item to force codec/surface re-init
        playerView.player = null
        playerView.player = exoPlayer
        
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(hash)
            .setSubtitleConfigurations(externalSubtitles)

        if (streamUrl.contains(".m3u8")) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        exoPlayer.setMediaItem(mediaItemBuilder.build())
        exoPlayer.seekTo(currentPos)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    private fun preparePlayer(exoPlayer: ExoPlayer) {
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(hash)
            .setSubtitleConfigurations(externalSubtitles)

        if (streamUrl.contains(".m3u8")) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        exoPlayer.setMediaItem(mediaItemBuilder.build())
        if (startPosition > 0) {
            exoPlayer.seekTo(startPosition)
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    private fun updatePlayerMediaItems() {
        val exoPlayer = player ?: return
        val currentPosition = exoPlayer.currentPosition
        val wasPlaying = exoPlayer.playWhenReady

        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(hash)
            .setSubtitleConfigurations(externalSubtitles)
            .build()

        // false = don't reset position
        exoPlayer.setMediaItem(mediaItem, false)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = wasPlaying
    }

    private fun fetchExternalSubtitles() {
        scope.launch {
            try {
                val params = buildString {
                    append("imdbId=$imdbId")
                    if (season > 0) append("&season=$season")
                    if (episode > 0) append("&episode=$episode")
                }
                val result = withContext(Dispatchers.IO) {
                    val url = URL("https://decco.tv/api/subtitles/external?$params")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 10000
                    val response = connection.inputStream.bufferedReader().readText()
                    connection.disconnect()
                    response
                }

                val json = JSONObject(result)
                val subtitlesArray = json.optJSONArray("subtitles") ?: JSONArray()
                
                val configs = ArrayList<MediaItem.SubtitleConfiguration>()
                for (i in 0 until subtitlesArray.length()) {
                    val sub = subtitlesArray.getJSONObject(i)
                    val subUrl = sub.optString("url", "")
                    val langCode = sub.optString("language", "und")
                    val label = getLanguageName(langCode)
                    
                    if (subUrl.isNotEmpty()) {
                        val formatHint = sub.optString("format", "").lowercase()
                        val mimeType = when {
                            formatHint == "vtt" || subUrl.contains(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
                            formatHint == "srt" || subUrl.contains(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
                            else -> MimeTypes.TEXT_VTT // Default to VTT
                        }

                        configs.add(
                            MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                                .setMimeType(mimeType)
                                .setLanguage(langCode)
                                .setLabel(label)
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .build()
                        )
                    }
                }

                if (configs.isNotEmpty()) {
                    externalSubtitles.clear()
                    externalSubtitles.addAll(configs)
                    withContext(Dispatchers.Main) {
                        updatePlayerMediaItems()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error fetching sub: ${e.message}")
            }
        }
    }

    private fun getLanguageName(code: String): String {
        val normalized = code.lowercase()
        return when (normalized) {
            "en", "eng", "english" -> "English"
            "es", "spa", "spanish", "español" -> "Spanish"
            "fr", "fre", "fra", "french", "français" -> "French"
            "de", "ger", "deu", "german", "deutsch" -> "German"
            "it", "ita", "italian", "italiano" -> "Italian"
            "pt", "por", "portuguese", "português" -> "Portuguese"
            "ru", "rus", "russian", "русский" -> "Russian"
            "ja", "jpn", "japanese", "日本語" -> "Japanese"
            "ko", "kor", "korean", "한국어" -> "Korean"
            "zh", "chi", "zho", "chinese", "中文" -> "Chinese"
            "ar", "ara", "arabic", "العربية" -> "Arabic"
            "hi", "hin", "hindi", "हिन्दी" -> "Hindi"
            "tr", "tur", "turkish", "türkçe" -> "Turkish"
            "pl", "pol", "polish", "polski" -> "Polish"
            "nl", "dut", "nld", "dutch", "nederlands" -> "Dutch"
            "sv", "swe", "swedish", "svenska" -> "Swedish"
            "fi", "fin", "finnish", "suomi" -> "Finnish"
            "da", "dan", "danish", "dansk" -> "Danish"
            "no", "nor", "norwegian", "norsk" -> "Norwegian"
            "cs", "cze", "ces", "czech", "čeština" -> "Czech"
            "el", "gre", "ell", "greek", "ελληνικά" -> "Greek"
            "hu", "hun", "hungarian", "magyar" -> "Hungarian"
            "ro", "rum", "ron", "romanian", "română" -> "Romanian"
            "th", "tha", "thai", "ไทย" -> "Thai"
            "vi", "vie", "vietnamese", "tiếng việt" -> "Vietnamese"
            "id", "ind", "indonesian", "bahasa" -> "Indonesian"
            "uk", "ukr", "ukrainian", "українська" -> "Ukrainian"
            else -> {
                if (normalized == "und") return "Unknown"
                try {
                    val loc = Locale(normalized)
                    val display = loc.getDisplayLanguage(Locale.ENGLISH)
                    if (display.isNotEmpty() && display != normalized) display 
                    else normalized.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                } catch (e: Exception) {
                    normalized
                }
            }
        }
    }

    private fun showSubtitleSelectionDialog() {
        val tracks = player?.currentTracks ?: return
        val subtitleGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        
        if (subtitleGroups.isEmpty()) {
            Toast.makeText(this, "No valid subtitles found", Toast.LENGTH_SHORT).show()
            return
        }

        val options = ArrayList<String>()
        val mappings = ArrayList<Pair<Int, Int>>() // GroupIndex, TrackIndex

        options.add("Off")
        
        subtitleGroups.forEachIndexed { gIdx, group ->
            for (tIdx in 0 until group.length) {
                val format = group.getTrackFormat(tIdx)
                val langCode = format.language ?: "und"
                val displayLang = getLanguageName(langCode)
                val label = format.label ?: displayLang
                options.add(label)
                mappings.add(Pair(gIdx, tIdx))
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    player?.trackSelectionParameters = player?.trackSelectionParameters!!
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                } else {
                    val mapping = mappings[which - 1]
                    val group = subtitleGroups[mapping.first]
                    player?.trackSelectionParameters = player?.trackSelectionParameters!!
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, mapping.second))
                        .build()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAudioTrackSelectionDialog() {
        val tracks = player?.currentTracks ?: return
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        
        if (audioGroups.isEmpty()) {
            Toast.makeText(this, "No audio tracks found", Toast.LENGTH_SHORT).show()
            return
        }

        val options = ArrayList<String>()
        val mappings = ArrayList<Pair<Int, Int>>()

        audioGroups.forEachIndexed { gIdx, group ->
            for (tIdx in 0 until group.length) {
                val format = group.getTrackFormat(tIdx)
                val langCode = format.language ?: "und"
                val displayLang = getLanguageName(langCode)
                val label = format.label ?: displayLang
                options.add(label)
                mappings.add(Pair(gIdx, tIdx))
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Audio Tracks")
            .setItems(options.toTypedArray()) { _, which ->
                val mapping = mappings[which]
                val group = audioGroups[mapping.first]
                player?.trackSelectionParameters = player?.trackSelectionParameters!!
                    .buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, mapping.second))
                    .build()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() {
        super.onStart()
        if (player == null) initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Notify engine to stop torrent if on metered network to save data.
        // On unmetered (WiFi), we let it seed in the background as requested.
        if (hash.isNotEmpty()) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (cm.isActiveNetworkMetered) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val url = URL("http://127.0.0.1:8888/stop/$hash")
                        (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 2000
                            connect()
                            responseCode // trigger request
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send stop command for $hash", e)
                    }
                }
            }
        }
        
        scope.cancel()
    }
}
