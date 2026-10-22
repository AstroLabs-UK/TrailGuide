package com.trailguide.app.ui

import android.annotation.SuppressLint
import android.app.Application
import android.location.Location
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class LocationUiState(
    val location: Location? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(application)

    private val _uiState = MutableStateFlow(LocationUiState())
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    private var updatesStarted = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            _uiState.value = LocationUiState(
                location = loc,
                isLoading = false,
                error = null
            )
        }
    }

    init {
        refreshLocation()
    }

    @SuppressLint("MissingPermission")
    fun refreshLocation() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val last = fusedClient.lastLocation.await()
                if (last != null) {
                    _uiState.value = LocationUiState(location = last, isLoading = false)
                } else {
                    val token = CancellationTokenSource()
                    val current = fusedClient.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        token.token
                    ).await()
                    if (current != null) {
                        _uiState.value = LocationUiState(location = current, isLoading = false)
                    } else {
                        _uiState.value = LocationUiState(
                            isLoading = false,
                            error = "Waiting for GPS signal…"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = LocationUiState(
                    isLoading = false,
                    error = "Could not get location"
                )
            }
            startContinuousUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    fun startContinuousUpdates() {
        if (updatesStarted) return
        updatesStarted = true
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(5f)
            .build()
        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    fun stopContinuousUpdates() {
        if (!updatesStarted) return
        fusedClient.removeLocationUpdates(locationCallback)
        updatesStarted = false
    }

    override fun onCleared() {
        stopContinuousUpdates()
        super.onCleared()
    }
}
