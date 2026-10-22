package com.trailguide.app.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val apkUrl: String?,
    val isNewer: Boolean
)

class UpdateChecker(private val context: Context) {

    private val prefs = MapPreferences(context)

    suspend fun check(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        val repo = prefs.githubRepo.trim().ifBlank { "AstroLabs-UK/TrailGuide" }
        if (!repo.contains("/")) {
            return@withContext Result.failure(IllegalStateException("Set GitHub repo as owner/name"))
        }
        try {
            val url = "https://api.github.com/repos/$repo/releases/latest"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "TrailGuide-UpdateChecker")
            conn.connectTimeout = 12000
            conn.readTimeout = 15000
            if (conn.responseCode == 404) {
                return@withContext Result.failure(IllegalStateException("No releases yet for $repo"))
            }
            if (conn.responseCode != 200) {
                return@withContext Result.failure(IllegalStateException("GitHub HTTP ${conn.responseCode}"))
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name", "")
            val name = json.optString("name", tag)
            val notes = json.optString("body", "")
            val htmlUrl = json.optString("html_url", "https://github.com/$repo/releases")
            val assets = json.optJSONArray("assets") ?: JSONArray()
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name", "").lowercase().endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url")
                    break
                }
            }
            val newer = isNewerThanInstalled(tag)
            Result.success(UpdateInfo(tag, name, notes, htmlUrl, apkUrl, newer))
        } catch (e: Exception) {
            Log.e(TAG, "check failed", e)
            Result.failure(e)
        }
    }

    /**
     * Download APK to app storage and open the system package installer.
     * User must confirm install. Returns true if the installer was launched.
     */
    suspend fun downloadAndPromptInstall(info: UpdateInfo): Boolean = withContext(Dispatchers.IO) {
        val apkUrl = info.apkUrl ?: return@withContext false
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "trailguide-update.apk")
            val conn = URL(apkUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "TrailGuide-UpdateChecker")
            conn.setRequestProperty("Accept", "application/octet-stream")
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) {
                Log.e(TAG, "download HTTP ${conn.responseCode}")
                return@withContext false
            }
            conn.inputStream.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            prefs.pendingUpdatePath = out.absolutePath
            prefs.pendingUpdateTag = info.tagName
            withContext(Dispatchers.Main) {
                promptInstall(out)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
            false
        }
    }

    fun promptInstall(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "install prompt failed", e)
            // Fallback: open release page
            openReleasePage(
                UpdateInfo(
                    prefs.pendingUpdateTag,
                    prefs.pendingUpdateTag,
                    "",
                    "https://github.com/${prefs.githubRepo}/releases",
                    null,
                    true
                )
            )
        }
    }

    /** If a previous download is waiting, offer install again on launch. */
    fun installPendingIfAny(): Boolean {
        val path = prefs.pendingUpdatePath
        if (path.isBlank()) return false
        val f = File(path)
        if (!f.exists()) {
            prefs.pendingUpdatePath = ""
            return false
        }
        promptInstall(f)
        return true
    }

    fun openReleasePage(info: UpdateInfo) {
        val uri = Uri.parse(info.apkUrl ?: info.htmlUrl)
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun isNewerThanInstalled(remoteTag: String): Boolean {
        val localName = currentVersionName()
        val localCode = currentVersionCode()
        val remote = parseVersion(remoteTag)
        val local = parseVersion(localName)
        // Prefer comparing trailing build number from tags like v0.7.3-build.12
        val remoteBuild = Regex("""build[.\-]?(\d+)""", RegexOption.IGNORE_CASE)
            .find(remoteTag)?.groupValues?.get(1)?.toIntOrNull()
        if (remoteBuild != null && remoteBuild > localCode) return true
        for (i in 0 until maxOf(remote.size, local.size)) {
            val rv = remote.getOrElse(i) { 0 }
            val lv = local.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    private fun currentVersionName(): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    } catch (_: Exception) { "0.0.0" }

    private fun currentVersionCode(): Int = try {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else {
            @Suppress("DEPRECATION")
            p.versionCode
        }
    } catch (_: Exception) { 0 }

    private fun parseVersion(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull() }

    companion object {
        private const val TAG = "UpdateChecker"
    }
}
