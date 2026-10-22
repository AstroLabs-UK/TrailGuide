package com.trailguide.app.routing

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        try {
            val exclude = if (avoidHighways && mode == TravelMode.DRIVING) "&exclude=motorway" else ""
            val alt = if (alternatives) "&alternatives=true" else ""
            // annotations=lanes when available on the public server
            val url = "https://router.project-osrm.org/route/v1/${mode.osrmProfile}/" +
                    "$startLng,$startLat;$endLng,$endLat" +
                    "?overview=full&geometries=geojson&steps=true&annotations=true$alt$exclude"

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 12000
            connection.readTimeout = 15000
            if (connection.responseCode != 200) {
                Log.e(TAG, "OSRM HTTP ${connection.responseCode}")
                return@withContext emptyList()
            }
            parseOsrmResponse(connection.inputStream.bufferedReader().readText(), region)
        } catch (e: Exception) {
            Log.e(TAG, "Routing failed", e)
            emptyList()
        }
    }

    private fun parseOsrmResponse(json: String, region: DrivingRegion): List<RouteResult> {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") return emptyList()
        val routes = root.getJSONArray("routes")
        val out = mutableListOf<RouteResult>()
        for (r in 0 until routes.length()) {
            val route = routes.getJSONObject(r)
            val steps = mutableListOf<RouteStep>()
            val legs = route.getJSONArray("legs")
            for (i in 0 until legs.length()) {
                val legSteps = legs.getJSONObject(i).getJSONArray("steps")
                for (j in 0 until legSteps.length()) {
                    val step = legSteps.getJSONObject(j)
                    val manoeuvre = step.getJSONObject("maneuver")
                    val location = manoeuvre.getJSONArray("location")
                    val lng = location.getDouble(0)
                    val lat = location.getDouble(1)
                    val type = manoeuvre.optString("type", "continue")
                    val modifier = manoeuvre.optString("modifier", "")
                    val roadName = step.optString("name").takeIf { it.isNotBlank() }
                    val exit = if (manoeuvre.has("exit")) manoeuvre.getInt("exit") else null
                    val isRoundabout = type.contains("roundabout", true) || type == "rotary"
                    val laneHint = extractLaneHint(step, region)
                    val instruction = buildInstruction(
                        type, modifier, roadName, isRoundabout, exit, laneHint, region
                    )
                    steps.add(
                        RouteStep(
                            instruction = instruction,
                            distanceMeters = step.getDouble("distance"),
                            durationSeconds = step.getDouble("duration"),
                            location = lat to lng,
                            laneHint = laneHint,
                            isRoundabout = isRoundabout,
                            roundaboutExit = exit,
                            modifier = modifier,
                            type = type
                        )
                    )
                }
            }
            out.add(
                RouteResult(
                    route.getDouble("distance"),
                    route.getDouble("duration"),
                    route.getJSONObject("geometry").toString(),
                    steps
                )
            )
        }
        return out
    }

    /**
     * OSRM may include intersections[].lanes with indications / valid flags.
     * When present, build a short "use the left lane" style hint.
     */
    private fun extractLaneHint(step: JSONObject, region: DrivingRegion): String? {
        val intersections = step.optJSONArray("intersections") ?: return null
        if (intersections.length() == 0) return null
        val first = intersections.getJSONObject(0)
        val lanes = first.optJSONArray("lanes") ?: return null
        if (lanes.length() == 0) return null

        val active = mutableListOf<Int>()
        for (i in 0 until lanes.length()) {
            val lane = lanes.getJSONObject(i)
            if (lane.optBoolean("valid", false)) active.add(i)
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
        val leftHand = region.leftHandTraffic ||
                (region == DrivingRegion.AUTO && Locale.getDefault().country in listOf("GB", "UK", "JP", "AU", "IN", "IE"))

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
            type == "turn" -> "Turn $modifier"
            type == "new name" || type == "continue" -> "Continue"
            type == "depart" -> "Head $modifier"
            type == "arrive" -> "You have arrived"
            type == "merge" -> "Merge $modifier"
            type == "on ramp" -> "Take the ramp"
            type == "off ramp" -> "Take the exit"
            type == "fork" -> "Keep $modifier at the fork"
            type == "end of road" -> "Turn $modifier at the end of the road"
            type == "notification" && modifier.isNotBlank() -> modifier.replaceFirstChar { it.uppercase() }
            else -> type.replaceFirstChar { it.uppercase() }
        }

        // UK/France phrasing tweaks
        if (leftHand && base.startsWith("Turn left")) {
            // same words; region mainly affects lanes
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
