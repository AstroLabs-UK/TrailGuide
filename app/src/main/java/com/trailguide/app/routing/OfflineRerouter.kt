package com.trailguide.app.routing

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline recovery along a saved route geometry.
 * True city-wide offline routing needs a GraphHopper graph pack (large download);
 * this keeps you on / returns you to the planned path without a network.
 */
object OfflineRerouter {

    data class Recovery(
        val instruction: String,
        val nearestStepIndex: Int,
        val distanceToRouteM: Double,
        val bearingDeg: Double
    )

    fun recover(
        userLat: Double,
        userLng: Double,
        route: RouteResult
    ): Recovery {
        var bestI = 0
        var bestD = Double.MAX_VALUE
        route.steps.forEachIndexed { i, step ->
            val d = haversine(userLat, userLng, step.location.first, step.location.second)
            if (d < bestD) {
                bestD = d
                bestI = i
            }
        }
        val target = route.steps.getOrNull(bestI) ?: return Recovery(
            "Continue toward your destination", 0, bestD, 0.0
        )
        val bearing = bearing(userLat, userLng, target.location.first, target.location.second)
        val dir = compass(bearing)
        val instruction = when {
            bestD < 30 -> target.instruction
            bestD < 150 -> "Return to the route, head $dir. Then: ${target.instruction}"
            else -> "You've left the trail. Head $dir for about ${bestD.toInt()} metres to rejoin."
        }
        return Recovery(instruction, bestI, bestD, bearing)
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δλ = Math.toRadians(lon2 - lon1)
        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun compass(deg: Double): String = when (deg) {
        in 0.0..22.5, in 337.5..360.0 -> "north"
        in 22.5..67.5 -> "north-east"
        in 67.5..112.5 -> "east"
        in 112.5..157.5 -> "south-east"
        in 157.5..202.5 -> "south"
        in 202.5..247.5 -> "south-west"
        in 247.5..292.5 -> "west"
        else -> "north-west"
    }
}
