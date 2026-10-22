package com.trailguide.app.routing

import android.location.Location
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

/**
 * Free place search using Nominatim + Overpass (OpenStreetMap shop/POI data).
 * OSM includes many shops, brands, and amenities worldwide (coverage varies by area).
 */
object PlaceSearch {
    private const val TAG = "PlaceSearch"
    private const val USER_AGENT = "TrailGuide/0.7 (personal navigation)"

    suspend fun search(query: String, limit: Int = 8): List<PlaceResult> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            nominatimSearch(query, limit).ifEmpty { photonSearch(query, limit) }
        }

    /** Search shops/chains near a location and sort by distance. */
    suspend fun searchShopsNearby(
        query: String,
        lat: Double,
        lng: Double,
        radiusMeters: Int = 15000,
        limit: Int = 15
    ): List<PlaceResult> = withContext(Dispatchers.IO) {
        val overpass = overpassShops(query, lat, lng, radiusMeters, limit)
        if (overpass.isNotEmpty()) return@withContext overpass
        // Fallback: nominatim bounded search
        nominatimSearch(query, limit, lat, lng)
            .map {
                it.copy(
                    distanceMeters = haversine(lat, lng, it.lat, it.lng)
                )
            }
            .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
    }

    private fun nominatimSearch(
        query: String,
        limit: Int,
        biasLat: Double? = null,
        biasLng: Double? = null
    ): List<PlaceResult> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            var url = "https://nominatim.openstreetmap.org/search?q=$q&format=json&limit=$limit&addressdetails=1"
            if (biasLat != null && biasLng != null) {
                url += "&viewbox=${biasLng - 0.15},${biasLat + 0.15},${biasLng + 0.15},${biasLat - 0.15}&bounded=0"
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

    /** Photon (Komoot) – free geocoder, good fallback. */
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

    private fun overpassShops(
        query: String,
        lat: Double,
        lng: Double,
        radius: Int,
        limit: Int
    ): List<PlaceResult> {
        return try {
            val safe = query.replace("\"", "").take(40)
            // brand or name match on shop/amenity nodes
            val ql = """
                [out:json][timeout:15];
                (
                  node["shop"]["name"~"$safe",i](around:$radius,$lat,$lng);
                  node["shop"]["brand"~"$safe",i](around:$radius,$lat,$lng);
                  node["amenity"]["name"~"$safe",i](around:$radius,$lat,$lng);
                  node["amenity"]["brand"~"$safe",i](around:$radius,$lat,$lng);
                  way["shop"]["name"~"$safe",i](around:$radius,$lat,$lng);
                  way["shop"]["brand"~"$safe",i](around:$radius,$lat,$lng);
                );
                out center $limit;
            """.trimIndent()
            val url = URL("https://overpass-api.de/api/interpreter")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.outputStream.use { it.write("data=${URLEncoder.encode(ql, "UTF-8")}".toByteArray()) }
            if (conn.responseCode != 200) return emptyList()
            val body = conn.inputStream.bufferedReader().readText()
            val elements = JSONObject(body).optJSONArray("elements") ?: return emptyList()
            buildList {
                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    val tags = el.optJSONObject("tags") ?: continue
                    val name = tags.optString("name").ifBlank { tags.optString("brand") }
                    if (name.isBlank()) continue
                    val plat = when {
                        el.has("lat") -> el.getDouble("lat")
                        el.has("center") -> el.getJSONObject("center").getDouble("lat")
                        else -> continue
                    }
                    val plng = when {
                        el.has("lon") -> el.getDouble("lon")
                        el.has("center") -> el.getJSONObject("center").getDouble("lon")
                        else -> continue
                    }
                    val brand = tags.optString("brand")
                    val cat = tags.optString("shop").ifBlank { tags.optString("amenity") }
                    add(
                        PlaceResult(
                            name = name,
                            displayName = listOf(name, brand, cat).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                            lat = plat,
                            lng = plng,
                            category = cat,
                            brand = brand,
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

    private fun getJsonArray(url: String): JSONArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 10000
        conn.readTimeout = 12000
        if (conn.responseCode != 200) return null
        return JSONArray(conn.inputStream.bufferedReader().readText())
    }

    private fun getJsonObject(url: String): JSONObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 10000
        conn.readTimeout = 12000
        if (conn.responseCode != 200) return null
        return JSONObject(conn.inputStream.bufferedReader().readText())
    }


    /** Find car parking near a point (OSM amenity=parking via Overpass + Nominatim). */
    suspend fun searchParkingNear(
        lat: Double,
        lng: Double,
        radiusMeters: Int = 2500,
        limit: Int = 8
    ): List<PlaceResult> = withContext(Dispatchers.IO) {
        val overpass = overpassParking(lat, lng, radiusMeters, limit)
        if (overpass.isNotEmpty()) return@withContext overpass
        nominatimSearch("parking", limit, lat, lng)
            .map { it.copy(distanceMeters = haversine(lat, lng, it.lat, it.lng)) }
            .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
    }

    private fun overpassParking(
        lat: Double,
        lng: Double,
        radius: Int,
        limit: Int
    ): List<PlaceResult> {
        return try {
            val ql = """
                [out:json][timeout:15];
                (
                  node["amenity"="parking"](around:$radius,$lat,$lng);
                  way["amenity"="parking"](around:$radius,$lat,$lng);
                  node["amenity"="parking_entrance"](around:$radius,$lat,$lng);
                  way["amenity"="parking_entrance"](around:$radius,$lat,$lng);
                );
                out center $limit;
            """.trimIndent()
            val url = URL("https://overpass-api.de/api/interpreter")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.outputStream.use {
                it.write("data=${URLEncoder.encode(ql, "UTF-8")}".toByteArray())
            }
            if (conn.responseCode != 200) return emptyList()
            val body = conn.inputStream.bufferedReader().readText()
            val elements = JSONObject(body).optJSONArray("elements") ?: return emptyList()
            buildList {
                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    val tags = el.optJSONObject("tags")
                    val name = tags?.optString("name")?.ifBlank { null }
                        ?: tags?.optString("operator")?.ifBlank { null }
                        ?: "Parking"
                    val plat = when {
                        el.has("lat") -> el.getDouble("lat")
                        el.has("center") -> el.getJSONObject("center").getDouble("lat")
                        else -> continue
                    }
                    val plng = when {
                        el.has("lon") -> el.getDouble("lon")
                        el.has("center") -> el.getJSONObject("center").getDouble("lon")
                        else -> continue
                    }
                    add(
                        PlaceResult(
                            name = name,
                            displayName = "Parking · $name",
                            lat = plat,
                            lng = plng,
                            category = "parking",
                            distanceMeters = haversine(lat, lng, plat, plng)
                        )
                    )
                }
            }.sortedBy { it.distanceMeters ?: Double.MAX_VALUE }.take(limit)
        } catch (e: Exception) {
            Log.e(TAG, "Parking Overpass failed", e)
            emptyList()
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

    fun formatDistance(meters: Double, metric: Boolean): String =
        if (metric) {
            if (meters >= 1000) "%.1f km".format(meters / 1000) else "${meters.toInt()} m"
        } else {
            val mi = meters / 1609.344
            if (mi >= 0.1) "%.1f mi".format(mi) else "${(meters * 3.28084).toInt()} ft"
        }
}
