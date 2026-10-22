package com.trailguide.app.auto

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.core.graphics.drawable.IconCompat
import com.trailguide.app.R
import com.trailguide.app.data.NavSnapshot
import java.util.TimeZone

/**
 * Android Auto navigation UI — turn card, distance, ETA while navigating.
 * Start the route on the phone; this mirrors [NavSnapshot].
 */
class TrailGuideCarScreen(carContext: CarContext) : Screen(carContext) {

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 3_000L)
        }
    }

    override fun onGetTemplate(): Template {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 3_000L)

        val instruction = when {
            !NavSnapshot.isNavigating ->
                "Open Trail Guide on your phone to search and start a route"
            NavSnapshot.instruction.isNotBlank() -> NavSnapshot.instruction
            else -> "Continue on the trail"
        }

        val meters = when {
            NavSnapshot.distanceMeters > 0 -> NavSnapshot.distanceMeters
            else -> 0.0
        }
        val dist = if (meters >= 1000) {
            Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS)
        } else {
            Distance.create(meters.coerceAtLeast(0.0), Distance.UNIT_METERS)
        }

        val etaSec = when {
            NavSnapshot.etaSeconds > 0 -> NavSnapshot.etaSeconds
            else -> 0L
        }
        val arrival = System.currentTimeMillis() + etaSec * 1000L

        val maneuverType = maneuverFrom(instruction)
        val step = Step.Builder(instruction)
            .setManeuver(
                Maneuver.Builder(maneuverType)
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(
                                carContext,
                                R.drawable.ic_launcher_foreground
                            )
                        ).build()
                    )
                    .build()
            )
            .setRoad(
                when {
                    NavSnapshot.destinationName.isNotBlank() -> NavSnapshot.destinationName
                    NavSnapshot.isNavigating -> "Destination"
                    else -> "Trail Guide"
                }
            )
            .build()

        val estimate = TravelEstimate.Builder(
            dist,
            DateTimeWithZone.create(arrival.coerceAtLeast(System.currentTimeMillis() + 1_000L), TimeZone.getDefault())
        )
            .setRemainingTimeSeconds(etaSec.coerceAtLeast(0))
            .build()

        return NavigationTemplate.Builder()
            .setNavigationInfo(
                RoutingInfo.Builder()
                    .setCurrentStep(step, dist)
                    .build()
            )
            .setDestinationTravelEstimate(estimate)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(
                                when {
                                    !NavSnapshot.isNavigating -> "Idle"
                                    NavSnapshot.muted -> "Muted"
                                    else -> "Guiding"
                                }
                            )
                            .setOnClickListener { }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun maneuverFrom(instruction: String): Int {
        val i = instruction.lowercase()
        return when {
            "arrive" in i || "made it" in i -> Maneuver.TYPE_DESTINATION
            "roundabout" in i -> Maneuver.TYPE_ROUNDABOUT_ENTER_CW
            "u-turn" in i || "uturn" in i -> Maneuver.TYPE_U_TURN_LEFT
            "turn left" in i || "keep left" in i || "leftmost" in i -> Maneuver.TYPE_TURN_NORMAL_LEFT
            "turn right" in i || "keep right" in i || "rightmost" in i -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            "merge" in i -> Maneuver.TYPE_MERGE_RIGHT
            "exit" in i || "ramp" in i -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            "head" in i || "depart" in i || "open trail" in i -> Maneuver.TYPE_DEPART
            else -> Maneuver.TYPE_STRAIGHT
        }
    }
}
