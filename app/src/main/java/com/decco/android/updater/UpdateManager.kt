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
import android.util.Log
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

    fun checkForUpdates(context: Context, manual: Boolean = false) {
        Log.d("DeccoUpdate", "checkForUpdates called. manual=$manual")
        if (manual) {
            Toast.makeText(context, "Checking for updates...", Toast.LENGTH_SHORT).show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("DeccoUpdate", "Building Retrofit client...")
                val client = okhttp3.OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("User-Agent", "DeccoAndroid/1.0")
                            .header("Accept", "application/vnd.github.v3+json")
                            .build()
                        chain.proceed(request)
                    }
                    .build()

                val retrofit = Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                val api = retrofit.create(GitHubApi::class.java)
                Log.d("DeccoUpdate", "Fetching latest release...")
                val release = api.getLatestRelease()
                Log.d("DeccoUpdate", "Release fetched: ${release.tag_name}")

                val currentVersion = BuildConfig.VERSION_NAME
                val latestVersion = release.tag_name.removePrefix("v")
                Log.d("DeccoUpdate", "Current: $currentVersion, Latest: $latestVersion")

                if (isNewerVersion(currentVersion, latestVersion)) {
                    Log.d("DeccoUpdate", "New version detected.")
                    val apkAsset = release.assets.find { it.name.endsWith(".apk") }
                    if (apkAsset != null) {
                        Log.d("DeccoUpdate", "APK asset found: ${apkAsset.browser_download_url}")
                        withContext(Dispatchers.Main) {
                            showUpdateDialog(context, latestVersion, apkAsset.browser_download_url)
                        }
                    } else {
                        Log.w("DeccoUpdate", "No APK asset found in release.")
                        if (manual) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Update found ($latestVersion) but no APK asset.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } else {
                    Log.d("DeccoUpdate", "App is up to date.")
                    if (manual) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "No update available. running $currentVersion", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DeccoUpdate", "Update check failed", e)
                if (manual) {
                    withContext(Dispatchers.Main) {
                        val msg = if (e is retrofit2.HttpException) {
                            "HTTP ${e.code()}: ${e.message()}"
                        } else {
                            e.message ?: "Unknown error"
                        }
                        Toast.makeText(context, "Error: $msg", Toast.LENGTH_LONG).show()
                    }
                }
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
        val file = File(context.getExternalFilesDir(null), fileName)
        if (file.exists()) {
            file.delete()
        }

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Decco Update")
            .setDescription("Downloading version update...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, null, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        Log.d("DeccoUpdate", "Download enqueued with ID: $downloadId")

        // Start polling for status
        CoroutineScope(Dispatchers.IO).launch {
            var downloading = true
            while (downloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                    val reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON))
                    
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            Log.d("DeccoUpdate", "Download STATUS_SUCCESSFUL")
                            downloading = false
                            withContext(Dispatchers.Main) {
                                installUpdate(context, fileName)
                            }
                        }
                        DownloadManager.STATUS_FAILED -> {
                            Log.e("DeccoUpdate", "Download STATUS_FAILED: Reason=$reason")
                            downloading = false
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Download failed: Error $reason", Toast.LENGTH_LONG).show()
                            }
                        }
                        DownloadManager.STATUS_RUNNING -> {
                            val total = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            if (total > 0) {
                                val downloaded = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                                val progress = (downloaded * 100 / total).toInt()
                                Log.d("DeccoUpdate", "Download progress: $progress%")
                            }
                        }
                        DownloadManager.STATUS_PENDING -> Log.d("DeccoUpdate", "Download PENDING")
                        DownloadManager.STATUS_PAUSED -> Log.d("DeccoUpdate", "Download PAUSED: Reason=$reason")
                    }
                } else {
                    Log.e("DeccoUpdate", "Download cursor empty (cancelled?)")
                    downloading = false
                }
                cursor.close()
                if (downloading) {
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
    }

    private fun installUpdate(context: Context, fileName: String) {
        try {
            val file = File(context.getExternalFilesDir(null), fileName)
            if (!file.exists()) {
                Log.e("DeccoUpdate", "Update file not found: ${file.absolutePath}")
                Toast.makeText(context, "Update file not found", Toast.LENGTH_SHORT).show()
                return
            }

            Log.d("DeccoUpdate", "Installing update from: ${file.absolutePath}")
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
            Log.e("DeccoUpdate", "Install failed", e)
            e.printStackTrace()
            Toast.makeText(context, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
