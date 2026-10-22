package com.trailguide.app.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class TrailGuideCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return TrailGuideCarScreen(carContext)
    }
}
