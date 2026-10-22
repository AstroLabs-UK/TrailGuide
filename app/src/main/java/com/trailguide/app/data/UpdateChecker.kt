package com.trailguide.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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

/**
 * Checks GitHub Releases for a newer version.
 *
 * Setup:
 * 1. Push the project to GitHub
 * 2. Create a Release with tag like v0.8.0 and attach app-release.apk
 * 3. In the app Settings, set owner/repo (e.g. yourname/TrailGuide)
 * 4. Tap "Check for updates"
 *
 * The app compares release tag to versionName (stripping leading "v").
 */
class UpdateChecker(private val context: Context) {

    private val prefs = MapPreferences(context)

    suspend fun check(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        val repo = prefs.githubRepo.trim()
        if (repo.isBlank() || !repo.contains("/")) {
            return@withContext Result.failure(
                IllegalStateException("Set GitHub repo in Settings as owner/name (e.g. alice/TrailGuide)")
            )
        }
        try {
            val url = "https://api.github.com/repos/$repo/releases/latest"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "TrailGuide-UpdateChecker")
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            if (conn.responseCode == 404) {
                return@withContext Result.failure(
                    IllegalStateException("No releases found for $repo")
                )
            }
            if (conn.responseCode != 200) {
                return@withContext Result.failure(
                    IllegalStateException("GitHub error HTTP ${conn.responseCode}")
                )
            }
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            val name = json.optString("name", tag)
            val notes = json.optString("body", "")
            val htmlUrl = json.optString("html_url", "https://github.com/$repo/releases")
            val assets = json.optJSONArray("assets") ?: JSONArray()
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val n = a.optString("name", "").lowercase()
                if (n.endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url")
                    break
                }
            }
            val current = currentVersionName()
            val newer = isNewer(tag, current)
            Result.success(
                UpdateInfo(
                    tagName = tag,
                    name = name,
                    body = notes,
                    htmlUrl = htmlUrl,
                    apkUrl = apkUrl,
                    isNewer = newer
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            Result.failure(e)
        }
    }

    fun openReleasePage(info: UpdateInfo) {
        val uri = Uri.parse(info.apkUrl ?: info.htmlUrl)
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun currentVersionName(): String {
        return try {
            val p = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            p.versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    /** Compare semver-ish tags: v0.8.0 vs 0.7.1 */
    private fun isNewer(remoteTag: String, localVersion: String): Boolean {
        val r = parseVersion(remoteTag)
        val l = parseVersion(localVersion)
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    private fun parseVersion(v: String): List<Int> {
        return v.trim().removePrefix("v").removePrefix("V")
            .split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull() }
    }

    companion object {
        private const val TAG = "UpdateChecker"
    }
}
