package com.trailguide.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Validates Mapbox tokens and prepares style JSON for MapLibre.
 * Official Mapbox styles use mapbox:// sources that MapLibre cannot resolve,
 * so we rewrite them to HTTPS tile endpoints with the access token.
 */
object MapboxStyleHelper {
    private const val TAG = "MapboxStyle"
    // New Western (RDR2-like) — public Mapbox style
    const val STYLE_USER = "mapbox"
    const val STYLE_ID = "cjpn02blt2cw32sqrt9r8e5c3"

    data class TokenCheck(val valid: Boolean, val message: String)

    fun sanitizeToken(raw: String): String {
        return raw.trim()
            .removePrefix("\"")
            .removeSuffix("\"")
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
    }

    suspend fun validateToken(token: String): TokenCheck = withContext(Dispatchers.IO) {
        val t = sanitizeToken(token)
        if (t.isBlank()) return@withContext TokenCheck(false, "Token is empty")
        if (!t.startsWith("pk.") && !t.startsWith("sk.")) {
            return@withContext TokenCheck(false, "Token should start with pk. (public) or sk.")
        }
        try {
            // Token metadata endpoint — 200 means token is recognized
            val url = "https://api.mapbox.com/tokens/v2?access_token=${URLEncoder.encode(t, "UTF-8")}"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "TrailGuide/0.7")
            val code = conn.responseCode
            when {
                code == 200 -> TokenCheck(true, "Token OK")
                code == 401 || code == 403 -> TokenCheck(false, "Token rejected by Mapbox (invalid or restricted)")
                else -> TokenCheck(false, "Mapbox returned HTTP $code")
            }
        } catch (e: Exception) {
            Log.e(TAG, "validate failed", e)
            TokenCheck(false, "Could not reach Mapbox: ${e.message}")
        }
    }

    /**
     * Download style JSON and rewrite mapbox:// sources/glyphs/sprites for MapLibre.
     */
    suspend fun loadStyleJsonForMapLibre(token: String): String? = withContext(Dispatchers.IO) {
        val t = sanitizeToken(token)
        if (t.isBlank()) return@withContext null
        try {
            val styleUrl =
                "https://api.mapbox.com/styles/v1/$STYLE_USER/$STYLE_ID?access_token=${URLEncoder.encode(t, "UTF-8")}"
            val conn = URL(styleUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "TrailGuide/0.7")
            if (conn.responseCode != 200) {
                Log.e(TAG, "style fetch HTTP ${conn.responseCode}")
                return@withContext null
            }
            val raw = conn.inputStream.bufferedReader().readText()
            rewriteMapboxUrls(raw, t)
        } catch (e: Exception) {
            Log.e(TAG, "style load failed", e)
            null
        }
    }

    private fun rewriteMapboxUrls(styleJson: String, token: String): String {
        val root = JSONObject(styleJson)
        val enc = URLEncoder.encode(token, "UTF-8")

        // glyphs: mapbox://fonts/mapbox/{fontstack}/{range}.pbf
        val glyphs = root.optString("glyphs")
        if (glyphs.startsWith("mapbox://fonts/")) {
            // mapbox://fonts/{user}/{fontstack}/{range}.pbf
            val path = glyphs.removePrefix("mapbox://fonts/")
            root.put(
                "glyphs",
                "https://api.mapbox.com/fonts/v1/$path?access_token=$enc"
            )
        }

        // sprite
        val sprite = root.optString("sprite")
        if (sprite.startsWith("mapbox://sprites/")) {
            val path = sprite.removePrefix("mapbox://sprites/")
            root.put(
                "sprite",
                "https://api.mapbox.com/styles/v1/$path/sprite?access_token=$enc"
            )
        } else if (sprite.contains("api.mapbox.com") && !sprite.contains("access_token")) {
            root.put("sprite", "$sprite?access_token=$enc")
        }

        // sources
        val sources = root.optJSONObject("sources") ?: return root.toString()
        val keys = sources.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val src = sources.optJSONObject(key) ?: continue
            val url = src.optString("url")
            if (url.startsWith("mapbox://")) {
                // mapbox://mapbox.mapbox-streets-v8  or mapbox://mapbox.satellite
                val id = url.removePrefix("mapbox://")
                // TileJSON endpoint
                src.put(
                    "url",
                    "https://api.mapbox.com/v4/$id.json?secure&access_token=$enc"
                )
            }
            // Also fix explicit tiles arrays if present
            val tiles = src.optJSONArray("tiles")
            if (tiles != null) {
                val fixed = JSONArray()
                for (i in 0 until tiles.length()) {
                    var tile = tiles.getString(i)
                    if (tile.startsWith("mapbox://")) {
                        val id = tile.removePrefix("mapbox://").substringBefore("{")
                        tile = "https://api.mapbox.com/v4/$id/{z}/{x}/{y}.vector.pbf?access_token=$enc"
                    } else if (tile.contains("api.mapbox.com") && !tile.contains("access_token")) {
                        tile = if (tile.contains("?")) "$tile&access_token=$enc" else "$tile?access_token=$enc"
                    }
                    fixed.put(tile)
                }
                src.put("tiles", fixed)
            }
        }
        return root.toString()
    }
}
