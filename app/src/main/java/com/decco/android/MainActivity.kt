package com.decco.android

import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.*
import android.widget.Toast
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.appcompat.app.AppCompatActivity
import com.decco.android.engine.EngineService
import java.net.HttpURLConnection
import java.net.URL

/**
 * Main Activity — Hosts a fullscreen WebView loading decco.tv
 * The WebView communicates with the local engine service via http://127.0.0.1:8888
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create WebView programmatically (no XML needed)
        webView = WebView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(webView)

        // Go fullscreen (immersive) - Call AFTER setContentView to ensure DecorView is ready
        goFullscreen()

        // Configure WebView
        configureWebView()

        // Start the Engine Service
        startEngineService()

        // Load the web app
        val url = handleDeepLink(intent) ?: DECCO_URL
        webView.loadUrl(url)
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW // Allow localhost HTTP
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString DeccoAndroid/${BuildConfig.VERSION_NAME}"
            Log.d("DeccoWebView", "UserAgent set to: $userAgentString")
        }

        // JavaScript bridge — allows the web app to detect we're on Android
        webView.addJavascriptInterface(DeccoJsBridge(), "DeccoAndroid")

        webView.webViewClient = object : WebViewClient() {

            // Keep navigation inside the WebView (don't open external browser)
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val urlString = uri.toString()

                Log.d("DeccoWebView", "shouldOverrideUrlLoading: $urlString")

                // Allow decco.tv (including subdomains), localhost, and decco:// scheme
                val isDecco = uri.host?.contains("decco.tv") == true
                val isLocal = urlString.contains("127.0.0.1") || urlString.contains("localhost")
                
                // CRITICAL: Intercept social login initialization and open in system browser.
                // This prevents state_mismatch because the state cookie will be set in the system browser.
                // We check for the presence of the login social path across ANY host to support test envs.
                if (urlString.contains("/api/auth/login/social")) {
                    Log.d("DeccoWebView", "Redirecting auth init to system browser: $urlString")
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        return true
                    } catch (e: Exception) {
                        Log.e("DeccoWebView", "Failed to redirect auth init", e)
                    }
                }

                if (isDecco || isLocal) {
                    return false // Let WebView handle it
                }

                // Handle decco:// scheme (Player & Auth)
                if (uri.scheme == "decco") {
                    Log.d("DeccoWebView", "Intercepted decco:// scheme: $urlString")
                    
                    // Auth Callback
                    if (uri.host == "auth-callback") {
                        val token = uri.getQueryParameter("token")
                        if (token != null) {
                            Log.d("DeccoAuth", "Received session token via deep link")
                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(webView, true)
                            
                            // Set the better-auth token for both production and local domains to be safe
                            val cookieValue = "better-auth.session_token=$token; domain=decco.tv; path=/; Secure; SameSite=None"
                            cookieManager.setCookie("https://decco.tv", cookieValue)
                            
                            val localhostValue = "better-auth.session_token=$token; domain=localhost; path=/; Secure; SameSite=None"
                            cookieManager.setCookie("http://localhost:3000", localhostValue)
                            
                            cookieManager.flush()
                            
                            // Reload to apply auth state
                            webView.reload()
                            return true
                        }
                    }

                    // Player Launch
                    try {
                        val hash = extractHashFromDeccoUri(uri)
                        val title = uri.getQueryParameter("title") ?: ""
                        val subtitleTitle = uri.getQueryParameter("subtitleTitle") ?: ""
                        val imdbId = uri.getQueryParameter("imdbId") ?: ""
                        val season = uri.getQueryParameter("season")?.toIntOrNull() ?: 0
                        val episode = uri.getQueryParameter("episode")?.toIntOrNull() ?: 0
                        val startPos = uri.getQueryParameter("startPosition")?.toLongOrNull() ?: 0L

                        launchPlayer(hash, title, subtitleTitle, imdbId, season, episode, startPos)
                        return true
                    } catch (e: Exception) {
                        Log.e("DeccoWebView", "Failed to launch player via scheme", e)
                    }
                    return true
                }

                // Handle intent:// scheme
                if (uri.scheme == "intent") {
                    Log.d("DeccoWebView", "Intercepted intent:// scheme: $urlString")
                    try {
                        val intent = Intent.parseUri(urlString, Intent.URI_INTENT_SCHEME)
                        if (intent != null) {
                            val packageManager = view?.context?.packageManager
                            val info = packageManager?.resolveActivity(intent, 0)
                            
                            // If it targets our own package, launch it
                            if (intent.getPackage() == view?.context?.packageName) {
                                startActivity(intent)
                                return true
                            }

                            // Otherwise try to find a handler
                            if (info != null) {
                                startActivity(intent)
                                return true
                            }
                            
                            // Fallback to browser fallback URL
                            val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                            if (!fallbackUrl.isNullOrEmpty()) {
                                view?.loadUrl(fallbackUrl)
                                return true
                            }
                        }
                    } catch (e: Exception) {
                         e.printStackTrace()
                    }
                    return true
                }

                // External URLs → open in browser
                try {
                    Log.d("DeccoWebView", "Opening external URL: $urlString")
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return true
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                Log.d("DeccoWebView", "onPageCommitVisible: $url")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    Log.d("DeccoJS", "Console: ${consoleMessage.message()}")
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
    }

    private fun launchPlayer(
        hash: String, 
        title: String, 
        subtitleTitle: String, 
        imdbId: String, 
        season: Int, 
        episode: Int, 
        startPos: Long,
        subtitlesJson: String? = null
    ) {
        if (hash.isEmpty()) {
            Toast.makeText(this, "Error: Missing hash", Toast.LENGTH_SHORT).show()
            return
        }

        startEngineService()
        Toast.makeText(this, "Preparing stream...", Toast.LENGTH_SHORT).show()

        Thread {
            startTorrentInEngine(hash, season, episode)
            val ready = waitForTorrentReady(hash, timeoutMs = 60_000)

            runOnUiThread {
                if (!ready) {
                    Toast.makeText(
                        this,
                        "Stream not ready yet. Try again in a few seconds.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@runOnUiThread
                }

                val streamUrl = "http://127.0.0.1:8888/proxy/$hash"
                Log.i("DeccoLaunch", "Launching player for: $title ($hash)")

                val playerIntent = com.decco.android.player.PlayerActivity.createIntent(
                    context = this,
                    streamUrl = streamUrl,
                    hash = hash,
                    title = title,
                    subtitleTitle = subtitleTitle,
                    startPosition = startPos,
                    imdbId = imdbId,
                    season = season,
                    episode = episode,
                    subtitlesJson = subtitlesJson
                )
                playerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(playerIntent)
            }
        }.start()
    }

    /**
     * JavaScript bridge exposed as `window.DeccoAndroid`
     */
    inner class DeccoJsBridge {
        @JavascriptInterface
        fun isAndroid(): Boolean = true

        @JavascriptInterface
        fun getVersion(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun isEngineRunning(): Boolean = EngineService.isRunning

        @JavascriptInterface
        fun getCapabilities(): String {
            val capabilities = org.json.JSONObject()
            try {
                val isLikelyEmulator =
                    Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
                    Build.MODEL.contains("sdk", ignoreCase = true) ||
                    Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
                    Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
                    Build.PRODUCT.contains("sdk", ignoreCase = true)

                val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
                val codecInfos = codecList.codecInfos
                
                val videoCodecs = org.json.JSONObject()
                
                // Common codecs to check
                val codecsToCheck = mapOf(
                    "hevc" to MediaFormat.MIMETYPE_VIDEO_HEVC,
                    "h264" to MediaFormat.MIMETYPE_VIDEO_AVC,
                    "vp9" to MediaFormat.MIMETYPE_VIDEO_VP9,
                    "vp8" to MediaFormat.MIMETYPE_VIDEO_VP8,
                    "av1" to "video/av01" // MediaFormat.MIMETYPE_VIDEO_AV1 (API 29+)
                )

                for ((key, mime) in codecsToCheck) {
                    var supported = false
                    for (info in codecInfos) {
                        if (info.isEncoder) continue
                        try {
                            if (info.supportedTypes.contains(mime)) {
                                val lowerName = info.name.lowercase()
                                // Emulators often expose HEVC decoders that are too limited for real streams.
                                if (isLikelyEmulator && key == "hevc" &&
                                    (lowerName.contains("goldfish") || lowerName.contains("android.hevc"))
                                ) {
                                    continue
                                }
                                supported = true
                                break
                            }
                        } catch (e: Exception) {}
                    }
                    videoCodecs.put(key, supported)
                }

                // Conservative capability report on emulators to avoid selecting incompatible streams.
                if (isLikelyEmulator) {
                    videoCodecs.put("hevc", false)
                    videoCodecs.put("av1", false)
                }
                
                capabilities.put("videoCodecs", videoCodecs)
                capabilities.put("sdkInt", Build.VERSION.SDK_INT)
                capabilities.put("device", Build.MODEL)
                capabilities.put("manufacturer", Build.MANUFACTURER)
                capabilities.put("isEmulator", isLikelyEmulator)
                
            } catch (e: Exception) {
                Log.e("DeccoBridge", "Error getting capabilities", e)
            }
            return capabilities.toString()
        }

        @JavascriptInterface
        fun playNative(jsonData: String) {
            Log.d("DeccoBridge", "playNative called with: $jsonData")
            try {
                val json = org.json.JSONObject(jsonData)
                val hash = json.optString("hash")
                val title = json.optString("title")
                val imdbId = json.optString("imdbId")
                val season = json.optInt("season", 0)
                val episode = json.optInt("episode", 0)
                val startPos = json.optLong("startPosition", 0)
                val subtitles = json.optJSONArray("subtitles")?.toString()

                launchPlayer(hash, title, "", imdbId, season, episode, startPos, subtitles)
            } catch (e: Exception) {
                Log.e("DeccoBridge", "Error parsing playNative", e)
            }
        }

        @JavascriptInterface
        fun showToast(message: String) {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Handle deep links (decco:// or https://decco.tv/...)
     */
    private fun handleDeepLink(intent: Intent?): String? {
        val data = intent?.data ?: return null

        return when (data.scheme) {
            "decco" -> {
                val hash = extractHashFromDeccoUri(data)
                if (hash.isNotEmpty()) {
                    val title = data.getQueryParameter("title") ?: ""
                    val subtitleTitle = data.getQueryParameter("subtitleTitle") ?: ""
                    val imdbId = data.getQueryParameter("imdbId") ?: ""
                    val season = data.getQueryParameter("season")?.toIntOrNull() ?: 0
                    val episode = data.getQueryParameter("episode")?.toIntOrNull() ?: 0
                    val startPos = data.getQueryParameter("startPosition")?.toLongOrNull() ?: 0L
                    
                    launchPlayer(hash, title, subtitleTitle, imdbId, season, episode, startPos)

                    // Return a valid web URL so the WebView "syncs" with what we're playing.
                    // This way, when the player closes, the user is back at the movie/series page.
                    if (imdbId.startsWith("tt")) {
                        return if (season > 0 || episode > 0) {
                            "$DECCO_URL/tv/$imdbId-s$season-e$episode"
                        } else {
                            "$DECCO_URL/movie/$imdbId"
                        }
                    }
                } else {
                    Log.w("DeccoDeepLink", "Deep link decco:// invalid (missing hash): $data")
                }
                null
            }
            "https" -> {
                data.toString()
            }
            else -> null
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = handleDeepLink(intent)
        if (url != null) {
            webView.loadUrl(url)
        }
    }

    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun goFullscreen() {
        val decorView = window.decorView
        decorView?.post {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, decorView).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun startEngineService() {
        val serviceIntent = Intent(this, EngineService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun extractHashFromDeccoUri(uri: Uri): String {
        val raw = when {
            !uri.host.isNullOrBlank() -> uri.host!!
            !uri.encodedPath.isNullOrBlank() -> uri.encodedPath!!.trimStart('/')
            !uri.schemeSpecificPart.isNullOrBlank() -> uri.schemeSpecificPart!!
            else -> ""
        }
        return raw.substringBefore('?').substringBefore('/').trim()
    }

    private fun startTorrentInEngine(hash: String, season: Int, episode: Int) {
        try {
            val query = buildString {
                val params = mutableListOf<String>()
                if (season > 0) params.add("season=$season")
                if (episode > 0) params.add("episode=$episode")
                if (params.isNotEmpty()) {
                    append("?")
                    append(params.joinToString("&"))
                }
            }
            val url = URL("http://127.0.0.1:8888/start/$hash$query")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
            }
            val code = connection.responseCode
            Log.i("DeccoEngine", "Start torrent response code: $code for hash=$hash")
            connection.disconnect()
        } catch (e: Exception) {
            Log.w("DeccoEngine", "Could not call /start for hash=$hash", e)
        }
    }

    private fun waitForTorrentReady(hash: String, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                val url = URL("http://127.0.0.1:8888/status/$hash")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    requestMethod = "GET"
                }

                val code = connection.responseCode
                val response = if (code in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    ""
                }
                connection.disconnect()

                if (response.isNotEmpty()) {
                    val json = org.json.JSONObject(response)
                    val status = json.optString("status")
                    val metadataReady = json.optBoolean("metadataReady", false)
                    if (metadataReady || status == "ready") {
                        return true
                    }
                    if (status == "error") {
                        return false
                    }
                }
            } catch (_: Exception) {
                // Engine might still be booting up.
            }

            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val DECCO_URL = "https://decco.tv"
    }
}
