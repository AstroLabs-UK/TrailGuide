package com.trailguide.app.routing

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

enum class TravelMode(val osrmProfile: String, val label: String) {
    DRIVING("driving", "Drive"),
    WALKING("foot", "Walk"),
    CYCLING("bike", "Bike")
}

/** Rough driving-side hint for voice/UI (not full legal rules). */
enum class DrivingRegion(val label: String, val leftHandTraffic: Boolean) {
    USA("USA", false),
    UK("UK", true),
    FRANCE("France", false),
    AUTO("Auto", false)
}

interface RoutingService {
    suspend fun getRoute(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        mode: TravelMode = TravelMode.DRIVING,
        alternatives: Boolean = false,
        avoidHighways: Boolean = false,
        region: DrivingRegion = DrivingRegion.AUTO
    ): List<RouteResult>
}

data class RouteResult(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val geometry: String,
    val steps: List<RouteStep>
)

data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val location: Pair<Double, Double>,
    val laneHint: String? = null,
    val isRoundabout: Boolean = false,
    val roundaboutExit: Int? = null,
    val modifier: String = "",
    val type: String = ""
)

class OsrmRoutingService : RoutingService {

    /** Public OSRM-compatible endpoints (tried in order). */
    private val servers = listOf(
        "https://router.project-osrm.org",
        "https://routing.openstreetmap.de/routed-car", // car only; profile path differs
    )

    override suspend fun getRoute(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        mode: TravelMode,
        alternatives: Boolean,
        avoidHighways: Boolean,
        region: DrivingRegion
    ): List<RouteResult> = withContext(Dispatchers.IO) {
        if (!coordsOk(startLat, startLng) || !coordsOk(endLat, endLng)) {
            Log.e(TAG, "Invalid coordinates: $startLat,$startLng -> $endLat,$endLng")
            return@withContext emptyList()
        }
        // Same point
        if (kotlin.math.abs(startLat - endLat) < 1e-6 && kotlin.math.abs(startLng - endLng) < 1e-6) {
            return@withContext emptyList()
        }

        val attempts = buildList {
            add(RouteAttempt(mode.osrmProfile, alternatives, avoidHighways && mode == TravelMode.DRIVING))
            // Retry without alternatives / without exclude
            add(RouteAttempt(mode.osrmProfile, false, false))
            // Fallback profile if walking/bike fail
            if (mode != TravelMode.DRIVING) {
                add(RouteAttempt("driving", false, false))
            }
        }.distinct()

        for (attempt in attempts) {
            val result = tryOsrmMain(startLat, startLng, endLat, endLng, attempt, region)
            if (result.isNotEmpty()) return@withContext result
        }

        // openrouteservice-style public OSM.de car router (driving only)
        if (mode == TravelMode.DRIVING) {
            val fos = tryOsmDe(startLat, startLng, endLat, endLng, region)
            if (fos.isNotEmpty()) return@withContext fos
        }

        emptyList()
    }

    private data class RouteAttempt(
        val profile: String,
        val alternatives: Boolean,
        val avoidHighways: Boolean
    )

    private fun tryOsrmMain(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        attempt: RouteAttempt,
        region: DrivingRegion
    ): List<RouteResult> {
        val exclude = if (attempt.avoidHighways) "&exclude=motorway" else ""
        val alt = if (attempt.alternatives) "&alternatives=true" else ""
        val base = "https://router.project-osrm.org/route/v1/${attempt.profile}/"
        val coords = "$startLng,$startLat;$endLng,$endLat"
        // geometries=geojson is easier to parse; steps required for turn-by-turn
        val url = base + coords +
            "?overview=full&geometries=geojson&steps=true$alt$exclude"

        return httpGet(url)?.let { body ->
            val code = try {
                JSONObject(body).optString("code")
            } catch (_: Exception) { "?" }
            Log.d(TAG, "OSRM response code=$code url=${url.take(120)}")
            parseOsrmResponse(body, region)
        } ?: emptyList()
    }

    private fun tryOsmDe(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        region: DrivingRegion
    ): List<RouteResult> {
        // https://routing.openstreetmap.de/routed-car/route/v1/driving/...
        val url = "https://routing.openstreetmap.de/routed-car/route/v1/driving/" +
            "$startLng,$startLat;$endLng,$endLat" +
            "?overview=full&geometries=geojson&steps=true"
        return httpGet(url)?.let { parseOsrmResponse(it, region) } ?: emptyList()
    }

    private fun httpGet(url: String): String? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "TrailGuide/0.7 (Android; personal nav)")
            connection.setRequestProperty("Accept", "application/json")
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code !in 200..299) {
                val err = try {
                    connection.errorStream?.bufferedReader()?.readText()?.take(200)
                } catch (_: Exception) { null }
                Log.e(TAG, "HTTP $code $url err=$err")
                return null
            }
            connection.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: $url", e)
            null
        }
    }

    private fun coordsOk(lat: Double, lng: Double): Boolean =
        lat in -90.0..90.0 && lng in -180.0..180.0 &&
            !lat.isNaN() && !lng.isNaN() &&
            !(lat == 0.0 && lng == 0.0)

    private fun parseOsrmResponse(json: String, region: DrivingRegion): List<RouteResult> {
        return try {
            val root = JSONObject(json)
            if (root.optString("code") != "Ok") {
                Log.e(TAG, "OSRM not Ok: ${root.optString("code")} ${root.optString("message")}")
                return emptyList()
            }
            val routes = root.optJSONArray("routes") ?: return emptyList()
            val out = mutableListOf<RouteResult>()
            for (r in 0 until routes.length()) {
                val route = routes.getJSONObject(r)
                val steps = mutableListOf<RouteStep>()
                val legs = route.optJSONArray("legs") ?: continue
                for (i in 0 until legs.length()) {
                    val legSteps = legs.getJSONObject(i).optJSONArray("steps") ?: continue
                    for (j in 0 until legSteps.length()) {
                        steps.add(parseStep(legSteps.getJSONObject(j), region))
                    }
                }
                if (steps.isEmpty()) continue
                val geometry = extractGeometry(route)
                out.add(
                    RouteResult(
                        distanceMeters = route.optDouble("distance", 0.0),
                        durationSeconds = route.optDouble("duration", 0.0),
                        geometry = geometry,
                        steps = steps
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "Parse failed", e)
            emptyList()
        }
    }

    private fun extractGeometry(route: JSONObject): String {
        val geom = route.opt("geometry") ?: return ""
        return when (geom) {
            is String -> geom // polyline
            is JSONObject -> geom.toString() // GeoJSON geometry object
            else -> geom.toString()
        }
    }

    private fun parseStep(step: JSONObject, region: DrivingRegion): RouteStep {
        val manoeuvre = step.optJSONObject("maneuver") ?: JSONObject()
        val location = manoeuvre.optJSONArray("location")
        val lng = location?.optDouble(0, 0.0) ?: 0.0
        val lat = location?.optDouble(1, 0.0) ?: 0.0
        val type = manoeuvre.optString("type", "continue")
        val modifier = manoeuvre.optString("modifier", "")
        val roadName = step.optString("name").ifBlank { null }
        val isRoundabout = type.contains("roundabout", ignoreCase = true) ||
            type == "rotary"
        val exit = manoeuvre.optInt("exit", -1).takeIf { it > 0 }
        val laneHint = parseLanes(step.optJSONArray("intersections"), region)
        val instruction = buildInstruction(type, modifier, roadName, isRoundabout, exit, laneHint, region)
        return RouteStep(
            instruction = instruction,
            distanceMeters = step.optDouble("distance", 0.0),
            durationSeconds = step.optDouble("duration", 0.0),
            location = lat to lng,
            laneHint = laneHint,
            isRoundabout = isRoundabout,
            roundaboutExit = exit,
            modifier = modifier,
            type = type
        )
    }

    private fun parseLanes(intersections: JSONArray?, region: DrivingRegion): String? {
        if (intersections == null || intersections.length() == 0) return null
        val first = intersections.optJSONObject(0) ?: return null
        val lanes = first.optJSONArray("lanes") ?: return null
        if (lanes.length() == 0) return null
        val active = mutableListOf<Int>()
        for (i in 0 until lanes.length()) {
            val lane = lanes.optJSONObject(i) ?: continue
            if (lane.optBoolean("active", false) || lane.optBoolean("valid", false)) {
                active.add(i)
            }
        }
        if (active.isEmpty()) return null
        val total = lanes.length()
        val leftHand = region.leftHandTraffic ||
            (region == DrivingRegion.AUTO && Locale.getDefault().country in listOf("GB", "UK", "JP", "AU", "IN", "IE"))
        return when {
            active.size == total -> null
            active.size == 1 && active[0] == 0 ->
                if (leftHand) "Use the rightmost lane" else "Use the leftmost lane"
            active.size == 1 && active[0] == total - 1 ->
                if (leftHand) "Use the leftmost lane" else "Use the rightmost lane"
            active.first() <= 1 && active.last() <= total / 2 ->
                if (leftHand) "Keep right" else "Keep left"
            active.first() >= total / 2 ->
                if (leftHand) "Keep left" else "Keep right"
            else -> "Use lanes ${active.map { it + 1 }.joinToString(" or ")} of $total"
        }
    }

    private fun buildInstruction(
        type: String,
        modifier: String,
        roadName: String?,
        isRoundabout: Boolean,
        exit: Int?,
        laneHint: String?,
        region: DrivingRegion
    ): String {
        var base = when {
            isRoundabout && exit != null -> {
                val ordinal = when (exit) {
                    1 -> "1st"
                    2 -> "2nd"
                    3 -> "3rd"
                    else -> "${exit}th"
                }
                "At the roundabout, take the $ordinal exit"
            }
            isRoundabout -> "Enter the roundabout"
            type == "turn" -> "Turn ${modifier.ifBlank { "ahead" }}"
            type == "new name" || type == "continue" -> "Continue"
            type == "depart" -> "Head ${modifier.ifBlank { "out" }}"
            type == "arrive" -> "You have arrived"
            type == "merge" -> "Merge ${modifier.ifBlank { "" }}".trim()
            type == "on ramp" -> "Take the ramp"
            type == "off ramp" -> "Take the exit"
            type == "fork" -> "Keep ${modifier.ifBlank { "ahead" }} at the fork"
            type == "end of road" -> "Turn ${modifier.ifBlank { "ahead" }} at the end of the road"
            type == "notification" && modifier.isNotBlank() -> modifier.replaceFirstChar { it.uppercase() }
            else -> type.replaceFirstChar { it.uppercase() }
        }

        if (roadName != null && type != "arrive") {
            base = "$base onto $roadName"
        }
        if (laneHint != null) {
            base = "$base. $laneHint"
        }
        return base
    }

    companion object {
        private const val TAG = "OsrmRouting"
    }
}
