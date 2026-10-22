package com.trailguide.app.data

import android.content.Context
import android.util.Log
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Downloads map tiles around the user / route for offline viewing (MapLibre Offline).
 * Does not include full offline routing graphs — see OfflineJourneyStore for journey guidance offline.
 */
class OfflineMapManager(private val context: Context) {

    interface Listener {
        fun onProgress(percent: Int)
        fun onComplete()
        fun onError(message: String)
    }

    fun downloadArea(
        centerLat: Double,
        centerLng: Double,
        radiusKm: Double = 15.0,
        styleUrl: String,
        listener: Listener
    ) {
        val d = radiusKm / 111.0 // rough degrees
        val bounds = LatLngBounds.Builder()
            .include(LatLng(centerLat - d, centerLng - d))
            .include(LatLng(centerLat + d, centerLng + d))
            .build()

        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            bounds,
            8.0,  // min zoom
            16.0, // max zoom — keep modest to limit size
            context.resources.displayMetrics.density
        )

        val metadata = """{"name":"trail_guide_area","lat":$centerLat,"lng":$centerLng}""".toByteArray()

        OfflineManager.getInstance(context).createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            val pct = if (status.requiredResourceCount > 0) {
                                (100.0 * status.completedResourceCount / status.requiredResourceCount).toInt()
                            } else 0
                            listener.onProgress(pct.coerceIn(0, 100))
                            if (status.isComplete) {
                                listener.onComplete()
                            }
                        }
                        override fun onError(error: OfflineRegionError) {
                            Log.e(TAG, "Offline error: ${error.reason} ${error.message}")
                            listener.onError(error.message ?: "Download failed")
                        }
                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            listener.onError("Tile download limit exceeded")
                        }
                    })
                }
                override fun onError(error: String) {
                    listener.onError(error)
                }
            }
        )
    }

    companion object {
        private const val TAG = "OfflineMap"
    }
}
