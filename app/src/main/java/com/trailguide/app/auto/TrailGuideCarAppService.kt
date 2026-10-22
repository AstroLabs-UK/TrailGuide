package com.trailguide.app.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point for Android Auto.
 * Declared in the manifest; the system starts this when the phone is connected to a car.
 */
class TrailGuideCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Allow all hosts for personal/debug use.
        // For Play Store release you should restrict to known hosts.
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return TrailGuideCarSession()
    }
}
