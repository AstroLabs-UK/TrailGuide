package com.trailguide.app.data

/**
 * Process-wide navigation snapshot so Android Auto can show live turn info.
 */
object NavSnapshot {
    @Volatile var isNavigating: Boolean = false
    @Volatile var instruction: String = ""
    @Volatile var distanceText: String = ""
    @Volatile var etaText: String = ""
    @Volatile var destinationName: String = ""
    @Volatile var speedKmh: Int = 0
    @JvmField var distanceMeters: Double = 0.0
    @JvmField var etaSeconds: Long = 0
    @Volatile var muted: Boolean = false

    fun clear() {
        distanceMeters = 0.0
        etaSeconds = 0
        isNavigating = false
        instruction = ""
        distanceText = ""
        etaText = ""
        destinationName = ""
        speedKmh = 0
    }
}
