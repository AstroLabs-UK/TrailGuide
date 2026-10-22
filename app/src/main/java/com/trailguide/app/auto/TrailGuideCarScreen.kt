package com.trailguide.app.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.trailguide.app.data.NavSnapshot

/** Live-ish Android Auto screen reading NavSnapshot from the phone process. */
class TrailGuideCarScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val rows = ItemList.Builder()
        if (NavSnapshot.isNavigating) {
            rows.addRow(
                Row.Builder()
                    .setTitle(NavSnapshot.instruction.ifBlank { "Continue" })
                    .addText(
                        listOf(NavSnapshot.distanceText, NavSnapshot.etaText, NavSnapshot.destinationName)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    )
                    .build()
            )
            if (NavSnapshot.speedKmh > 0) {
                rows.addRow(
                    Row.Builder()
                        .setTitle("Speed")
                        .addText("${NavSnapshot.speedKmh} km/h")
                        .build()
                )
            }
            if (NavSnapshot.muted) {
                rows.addRow(Row.Builder().setTitle("Voice muted").addText("Unmute on the phone").build())
            }
        } else {
            rows.addRow(
                Row.Builder()
                    .setTitle("Trail Guide")
                    .addText("Search or long-press on the phone to start navigation. Voice plays in the car.")
                    .build()
            )
            rows.addRow(
                Row.Builder()
                    .setTitle("Tips")
                    .addText("Drive · Walk · Bike modes, Home, and mute are on the phone.")
                    .build()
            )
        }
        // Invalidate periodically so the template can refresh when user returns
        return ListTemplate.Builder()
            .setTitle(if (NavSnapshot.isNavigating) "Navigating" else "Trail Guide")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(rows.build())
            .build()
    }
}
