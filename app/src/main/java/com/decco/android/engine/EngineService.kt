package com.decco.android.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.decco.android.MainActivity
import com.decco.android.R
import com.decco.android.server.EngineServer
import java.io.File

/**
 * Foreground Service that runs the Decco Engine.
 * Manages the lifecycle of all engine components:
 * - TorrentManager (LibTorrent4j)
 * - EngineServer (NanoHTTPD on :8888)
 */
class EngineService : Service() {

    private var server: EngineServer? = null
    private var torrentManager: TorrentManager? = null
    private var connectivityManager: ConnectivityManager? = null
    private val cleanupHandler = Handler(Looper.getMainLooper())

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            checkNetworkMetered()
        }

        override fun onLost(network: Network) {
            checkNetworkMetered()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            checkNetworkMetered()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "EngineService created")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Engine starting..."))

        // Initialize components
        val downloadDir = File(filesDir, "downloads")
        
        // Ensure directories exist
        downloadDir.mkdirs()

        torrentManager = TorrentManager(downloadDir).also { it.start() }

        // Start HTTP server with all components
        try {
            server = EngineServer(
                port = PORT,
                torrentManager = torrentManager!!
            )
            server?.start()
            isRunning = true
            Log.i(TAG, "Engine server started on port $PORT")
            updateNotification("Engine running")
            
            // Register network listener
            connectivityManager = getSystemService(ConnectivityManager::class.java)
            connectivityManager?.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                networkCallback
            )
            checkNetworkMetered()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start engine server", e)
            isRunning = false
            updateNotification("Engine failed to start")
        }

        // Schedule periodic cache cleanup (every hour)
        scheduleCacheCleanup()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "EngineService destroyed")
        cleanupHandler.removeCallbacksAndMessages(null)
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        server?.stop()
        torrentManager?.stop()
        isRunning = false
        super.onDestroy()
    }

    private fun scheduleCacheCleanup() {
        val runnable = object : Runnable {
            override fun run() {
                torrentManager?.cleanupOldTorrents()
                cleanupHandler.postDelayed(this, CLEANUP_INTERVAL_MS)
            }
        }
        cleanupHandler.postDelayed(runnable, 60_000)
    }

    private fun checkNetworkMetered() {
        val cm = connectivityManager ?: return
        val isMetered = cm.isActiveNetworkMetered
        Log.i(TAG, "Network state changed. Metered: $isMetered")
        
        // If we are on mobile data, we might want to be more aggressive about stopping
        if (isMetered) {
             updateNotification("Running on Mobile Data")
        } else {
             updateNotification("Engine running")
        }
        
        torrentManager?.setMeteredMode(isMetered)
    }

    fun stopService() {
        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Decco Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the streaming engine running"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Decco")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "EngineService"
        private const val CHANNEL_ID = "decco_engine"
        private const val NOTIFICATION_ID = 1
        const val PORT = 8888
        private const val CLEANUP_INTERVAL_MS = 60 * 60 * 1000L

        @Volatile
        var isRunning = false
            private set
    }
}
