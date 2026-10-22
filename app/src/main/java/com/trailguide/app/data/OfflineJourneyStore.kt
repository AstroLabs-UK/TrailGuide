package com.trailguide.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.trailguide.app.routing.RouteResult
import com.trailguide.app.routing.RouteStep
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Persists the active journey so guidance can continue offline.
 * Stores route geometry, steps, and destination.
 */
class OfflineJourneyStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveJourney(
        route: RouteResult,
        destLat: Double,
        destLng: Double,
        destName: String?,
        stepIndex: Int
    ) {
        val stepsArr = JSONArray()
        route.steps.forEach { s ->
            stepsArr.put(
                JSONObject()
                    .put("instruction", s.instruction)
                    .put("distance", s.distanceMeters)
                    .put("duration", s.durationSeconds)
                    .put("lat", s.location.first)
                    .put("lng", s.location.second)
                    .put("laneHint", s.laneHint ?: "")
                    .put("roundabout", s.isRoundabout)
                    .put("exit", s.roundaboutExit ?: -1)
            )
        }
        val root = JSONObject()
            .put("geometry", route.geometry)
            .put("distance", route.distanceMeters)
            .put("duration", route.durationSeconds)
            .put("steps", stepsArr)
            .put("destLat", destLat)
            .put("destLng", destLng)
            .put("destName", destName ?: "")
            .put("stepIndex", stepIndex)
            .put("savedAt", System.currentTimeMillis())
        prefs.edit().putString(KEY_JOURNEY, root.toString()).apply()
    }

    fun updateStepIndex(index: Int) {
        val raw = prefs.getString(KEY_JOURNEY, null) ?: return
        try {
            val root = JSONObject(raw)
            root.put("stepIndex", index)
            prefs.edit().putString(KEY_JOURNEY, root.toString()).apply()
        } catch (_: Exception) { }
    }

    fun loadJourney(): SavedJourney? {
        val raw = prefs.getString(KEY_JOURNEY, null) ?: return null
        return try {
            val root = JSONObject(raw)
            val stepsArr = root.getJSONArray("steps")
            val steps = buildList {
                for (i in 0 until stepsArr.length()) {
                    val s = stepsArr.getJSONObject(i)
                    val exit = s.optInt("exit", -1)
                    add(
                        RouteStep(
                            instruction = s.getString("instruction"),
                            distanceMeters = s.getDouble("distance"),
                            durationSeconds = s.getDouble("duration"),
                            location = s.getDouble("lat") to s.getDouble("lng"),
                            laneHint = s.optString("laneHint").ifBlank { null },
                            isRoundabout = s.optBoolean("roundabout"),
                            roundaboutExit = if (exit >= 0) exit else null
                        )
                    )
                }
            }
            SavedJourney(
                route = RouteResult(
                    root.getDouble("distance"),
                    root.getDouble("duration"),
                    root.getString("geometry"),
                    steps
                ),
                destLat = root.getDouble("destLat"),
                destLng = root.getDouble("destLng"),
                destName = root.optString("destName").ifBlank { null },
                stepIndex = root.optInt("stepIndex", 0)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_JOURNEY).apply()
    }

    fun hasJourney(): Boolean = prefs.contains(KEY_JOURNEY)

    /**
     * Find nearest point on the route step list (approximation of corridor).
     * Used for offline "get back on track" without a full router.
     */
    fun nearestStepIndex(lat: Double, lng: Double, route: RouteResult): Pair<Int, Double> {
        var bestI = 0
        var bestD = Double.MAX_VALUE
        route.steps.forEachIndexed { i, step ->
            val d = haversine(lat, lng, step.location.first, step.location.second)
            if (d < bestD) {
                bestD = d
                bestI = i
            }
        }
        return bestI to bestD
    }

    companion object {
        private const val PREFS = "trail_guide_offline"
        private const val KEY_JOURNEY = "active_journey"

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
}

data class SavedJourney(
    val route: RouteResult,
    val destLat: Double,
    val destLng: Double,
    val destName: String?,
    val stepIndex: Int
)
