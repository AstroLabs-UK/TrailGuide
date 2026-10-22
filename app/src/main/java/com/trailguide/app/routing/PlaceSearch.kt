package com.trailguide.app.routing

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class PlaceResult(
    val name: String,
    val displayName: String,
    val lat: Double,
    val lng: Double,
    val category: String = "",
    val brand: String = "",
    val distanceMeters: Double? = null
)

object PlaceSearch {
    private const val TAG = "PlaceSearch"
    private const val USER_AGENT = "TrailGuide/0.7 (Android; personal navigation)"

    suspend fun search(query: String, limit: Int = 8): List<PlaceResult> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val n = nominatimSearch(query, limit, null, null)
            if (n.isNotEmpty()) n else photonSearch(query, limit)
        }

    suspend fun searchShopsNearby(
        query: String,
        lat: Double,
        lng: Double,
        radiusMeters: Int = 5000,
        limit: Int = 15
    ): List<PlaceResult> = withContext(Dispatchers.IO) {
        val overpass = overpassCategory(query, lat, lng, radiusMeters, limit)
        if (overpass.isNotEmpty()) return@withContext overpass
        // Fallback: Nominatim with nearby bias
        nominatimSearch(query, limit, lat, lng)
            .map { it.copy(distanceMeters = haversine(lat, lng, it.lat, it.lng)) }
            .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
    }

    private fun categoryTags(query: String): List<String>? {
        val q = query.lowercase().trim()
        return when {
            q in listOf("restaurant", "restaurants", "food", "dining", "eat") ->
                listOf("""node["amenity"="restaurant"]""", """way["amenity"="restaurant"]""",
                    """node["amenity"="fast_food"]""", """way["amenity"="fast_food"]""")
            q in listOf("coffee", "cafe", "café", "coffee shop") ->
                listOf("""node["amenity"="cafe"]""", """way["amenity"="cafe"]""")
            q in listOf("fuel", "gas", "petrol", "gas station") ->
                listOf("""node["amenity"="fuel"]""", """way["amenity"="fuel"]""")
            q in listOf("parking", "car park", "carpark") ->
                listOf("""node["amenity"="parking"]""", """way["amenity"="parking"]""")
            q in listOf("hospital", "hospitals", "a&e", "er") ->
                listOf("""node["amenity"="hospital"]""", """way["amenity"="hospital"]""",
                    """node["amenity"="clinic"]""")
            q in listOf("shop", "shops", "store", "supermarket") ->
                listOf("""node["shop"]""", """way["shop"]""",
                    """node["shop"="supermarket"]""", """way["shop"="supermarket"]""")
            q in listOf("hotel", "hotels", "lodging") ->
                listOf("""node["tourism"="hotel"]""", """way["tourism"="hotel"]""")
            else -> null
        }
    }

    private fun overpassCategory(
        query: String,
        lat: Double,
        lng: Double,
        radius: Int,
        limit: Int
    ): List<PlaceResult> {
        val tags = categoryTags(query)
        val ql = if (tags != null) {
            val body = tags.joinToString("\n") { "$it(around:$radius,$lat,$lng);" }
            """
                [out:json][timeout:20];
                (
                  $body
                );
                out center $limit;
            """.trimIndent()
        } else {
            val safe = query.replace("\"", "").replace("~", "").take(40)
            if (safe.isBlank()) return emptyList()
            """
                [out:json][timeout:20];
                (
                  node["name"~"$safe",i](around:$radius,$lat,$lng);
                  node["brand"~"$safe",i](around:$radius,$lat,$lng);
                  node["shop"]["name"~"$safe",i](around:$radius,$lat,$lng);
                  node["amenity"]["name"~"$safe",i](around:$radius,$lat,$lng);
                  way["name"~"$safe",i](around:$radius,$lat,$lng);
                  way["brand"~"$safe",i](around:$radius,$lat,$lng);
                );
                out center $limit;
            """.trimIndent()
        }
        return runOverpass(ql, lat, lng, limit, query)
    }

    private fun runOverpass(
        ql: String,
        lat: Double,
        lng: Double,
        limit: Int,
        fallbackCategory: String
    ): List<PlaceResult> {
        return try {
            val url = URL("https://overpass-api.de/api/interpreter")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true
            conn.connectTimeout = 18000
            conn.readTimeout = 25000
            conn.outputStream.use {
                it.write("data=${URLEncoder.encode(ql, "UTF-8")}".toByteArray())
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "Overpass HTTP ${conn.responseCode}")
                return emptyList()
            }
            val body = conn.inputStream.bufferedReader().readText()
            val elements = JSONObject(body).optJSONArray("elements") ?: return emptyList()
            buildList {
                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    val tags = el.optJSONObject("tags")
                    val name = tags?.optString("name")?.ifBlank { null }
                        ?: tags?.optString("brand")?.ifBlank { null }
                        ?: tags?.optString("operator")?.ifBlank { null }
                        ?: fallbackCategory.replaceFirstChar { it.uppercase() }
                    val plat = when {
                        el.has("lat") -> el.getDouble("lat")
                        el.has("center") -> el.getJSONObject("center").optDouble("lat", Double.NaN)
                        else -> Double.NaN
                    }
                    val plng = when {
                        el.has("lon") -> el.getDouble("lon")
                        el.has("center") -> el.getJSONObject("center").optDouble("lon", Double.NaN)
                        else -> Double.NaN
                    }
                    if (plat.isNaN() || plng.isNaN()) continue
                    val cat = tags?.optString("amenity")?.ifBlank { null }
                        ?: tags?.optString("shop")?.ifBlank { null }
                        ?: fallbackCategory
                    add(
                        PlaceResult(
                            name = name,
                            displayName = listOf(name, tags?.optString("addr:street").orEmpty())
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            lat = plat,
                            lng = plng,
                            category = cat,
                            brand = tags?.optString("brand").orEmpty(),
                            distanceMeters = haversine(lat, lng, plat, plng)
                        )
                    )
                }
            }.sortedBy { it.distanceMeters ?: Double.MAX_VALUE }.take(limit)
        } catch (e: Exception) {
            Log.e(TAG, "Overpass failed", e)
            emptyList()
        }
    }

    suspend fun searchParkingNear(
        lat: Double,
        lng: Double,
        radiusMeters: Int = 2500,
        limit: Int = 8
    ): List<PlaceResult> = withContext(Dispatchers.IO) {
        val overpass = overpassCategory("parking", lat, lng, radiusMeters, limit)
        if (overpass.isNotEmpty()) return@withContext overpass
        nominatimSearch("parking", limit, lat, lng)
            .map { it.copy(distanceMeters = haversine(lat, lng, it.lat, it.lng)) }
            .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
    }

    private fun nominatimSearch(
        query: String,
        limit: Int,
        biasLat: Double?,
        biasLng: Double?
    ): List<PlaceResult> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            var url =
                "https://nominatim.openstreetmap.org/search?q=$q&format=json&limit=$limit&addressdetails=1"
            if (biasLat != null && biasLng != null) {
                url += "&viewbox=${biasLng - 0.2},${biasLat + 0.2},${biasLng + 0.2},${biasLat - 0.2}&bounded=0"
            }
            val arr = getJsonArray(url) ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        PlaceResult(
                            name = o.optString("name").ifBlank {
                                o.optString("display_name").substringBefore(",")
                            },
                            displayName = o.optString("display_name"),
                            lat = o.getString("lat").toDouble(),
                            lng = o.getString("lon").toDouble(),
                            category = o.optString("type")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Nominatim failed", e)
            emptyList()
        }
    }

    private fun photonSearch(query: String, limit: Int): List<PlaceResult> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "https://photon.komoot.io/api/?q=$q&limit=$limit"
            val root = getJsonObject(url) ?: return emptyList()
            val features = root.optJSONArray("features") ?: return emptyList()
            buildList {
                for (i in 0 until features.length()) {
                    val f = features.getJSONObject(i)
                    val props = f.getJSONObject("properties")
                    val geom = f.getJSONObject("geometry")
                    val coords = geom.getJSONArray("coordinates")
                    val name = props.optString("name").ifBlank { props.optString("street") }
                    if (name.isBlank()) continue
                    add(
                        PlaceResult(
                            name = name,
                            displayName = listOf(
                                name,
                                props.optString("city"),
                                props.optString("country")
                            ).filter { it.isNotBlank() }.joinToString(", "),
                            lat = coords.getDouble(1),
                            lng = coords.getDouble(0),
                            category = props.optString("osm_value")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Photon failed", e)
            emptyList()
        }
    }

    private fun getJsonArray(url: String): JSONArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        if (conn.responseCode != 200) return null
        return JSONArray(conn.inputStream.bufferedReader().readText())
    }

    private fun getJsonObject(url: String): JSONObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        if (conn.responseCode != 200) return null
        return JSONObject(conn.inputStream.bufferedReader().readText())
    }

    fun formatDistance(meters: Double, metric: Boolean): String {
        return if (metric) {
            if (meters >= 1000) "%.1f km".format(meters / 1000.0) else "${meters.toInt()} m"
        } else {
            val miles = meters / 1609.34
            if (miles >= 0.1) "%.1f mi".format(miles) else "${(meters * 3.28084).toInt()} ft"
        }
    }

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
