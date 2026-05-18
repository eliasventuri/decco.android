package com.decco.android

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.decco.android.engine.EngineService

class DeccoApplication : Application(), Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var startedActivities = 0

    private val stopDownloadsRunnable = Runnable {
        if (startedActivities == 0 && EngineService.isRunning) {
            Log.i(TAG, "No visible Decco activities. Stopping torrent downloads.")
            val intent = Intent(this, EngineService::class.java).apply {
                action = EngineService.ACTION_APP_BACKGROUND
            }
            startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities += 1
        handler.removeCallbacks(stopDownloadsRunnable)
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = maxOf(0, startedActivities - 1)
        if (startedActivities == 0) {
            handler.postDelayed(stopDownloadsRunnable, BACKGROUND_STOP_DELAY_MS)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        private const val TAG = "DeccoApplication"
        private const val BACKGROUND_STOP_DELAY_MS = 1500L
    }
}
