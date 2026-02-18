package com.decco.android.updater

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.decco.android.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import java.io.File

interface GitHubApi {
    @GET("repos/eliasventuri/decco.android/releases/latest")
    suspend fun getLatestRelease(): Release
}

data class Release(val tag_name: String, val assets: List<Asset>)
data class Asset(val browser_download_url: String, val name: String)

object UpdateManager {
    private const val BASE_URL = "https://api.github.com/"

    fun checkForUpdates(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val retrofit = Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                val api = retrofit.create(GitHubApi::class.java)
                val release = api.getLatestRelease()

                val currentVersion = BuildConfig.VERSION_NAME
                val latestVersion = release.tag_name.removePrefix("v")

                if (isNewerVersion(currentVersion, latestVersion)) {
                    val apkAsset = release.assets.find { it.name.endsWith(".apk") }
                    if (apkAsset != null) {
                        withContext(Dispatchers.Main) {
                            showUpdateDialog(context, latestVersion, apkAsset.browser_download_url)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }

        val length = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until length) {
            val c = currentParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun showUpdateDialog(context: Context, version: String, url: String) {
        AlertDialog.Builder(context)
            .setTitle("Update Available")
            .setMessage("Version $version is available. Would you like to update?")
            .setPositiveButton("Update") { _, _ ->
                downloadUpdate(context, url)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadUpdate(context: Context, url: String) {
        Toast.makeText(context, "Downloading update...", Toast.LENGTH_SHORT).show()

        val fileName = "update.apk"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Decco Update")
            .setDescription("Downloading version update...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, null, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        // Register receiver for download completion
        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctxt: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (downloadId == id) {
                    context.unregisterReceiver(this)
                    installUpdate(ctxt, fileName)
                }
            }
        }
        context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
    }

    private fun installUpdate(context: Context, fileName: String) {
        try {
            val file = File(context.getExternalFilesDir(null), fileName)
            if (!file.exists()) {
                Toast.makeText(context, "Update file not found", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
