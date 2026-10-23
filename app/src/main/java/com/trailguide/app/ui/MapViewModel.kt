package com.trailguide.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class LocationUiState(
    val location: Location? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** True only after the user has granted location permission. */
    val permissionGranted: Boolean = false
)

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(application)

    private val _uiState = MutableStateFlow(LocationUiState())
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    private var updatesStarted = false
    private var errorClearJob: Job? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            _uiState.value = LocationUiState(
                location = loc,
                isLoading = false,
                error = null,
                permissionGranted = true
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        val ctx = getApplication<Application>()
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Call only after location permission is granted.
     * Does nothing (and sets no error) if permission is missing.
     */
    fun onPermissionGranted() {
        if (!hasLocationPermission()) {
            _uiState.value = LocationUiState(
                isLoading = false,
                error = null,
                permissionGranted = false
            )
            return
        }
        _uiState.value = _uiState.value.copy(permissionGranted = true, error = null, isLoading = true)
        refreshLocation()
        startLocationUpdates()
    }

    fun onPermissionDenied() {
        stopLocationUpdates()
        _uiState.value = LocationUiState(
            isLoading = false,
            error = null,
            permissionGranted = false
        )
    }

    fun clearError() {
        errorClearJob?.cancel()
        _uiState.value = _uiState.value.copy(error = null)
    }

    @SuppressLint("MissingPermission")
    fun refreshLocation() {
        if (!hasLocationPermission()) {
            // Never surface an error before permission is granted
            _uiState.value = _uiState.value.copy(isLoading = false, error = null, permissionGranted = false)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, permissionGranted = true)
            try {
                val last = fusedClient.lastLocation.await()
                if (last != null) {
                    _uiState.value = LocationUiState(
                        location = last,
                        isLoading = false,
                        error = null,
                        permissionGranted = true
                    )
                    return@launch
                }
                val token = CancellationTokenSource()
                val current = fusedClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    token.token
                ).await()
                if (current != null) {
                    _uiState.value = LocationUiState(
                        location = current,
                        isLoading = false,
                        error = null,
                        permissionGranted = true
                    )
                } else {
                    // Still waiting for GPS — soft message, auto-clear
                    setTemporaryError("Waiting for GPS signal…")
                }
            } catch (_: SecurityException) {
                _uiState.value = LocationUiState(
                    isLoading = false,
                    error = null,
                    permissionGranted = false
                )
            } catch (_: Exception) {
                setTemporaryError("Could not get location yet — move near a window or outdoors")
            }
        }
    }

    private fun setTemporaryError(message: String) {
        _uiState.value = _uiState.value.copy(isLoading = false, error = message, permissionGranted = true)
        errorClearJob?.cancel()
        errorClearJob = viewModelScope.launch {
            delay(8_000)
            if (_uiState.value.error == message) {
                _uiState.value = _uiState.value.copy(error = null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (updatesStarted || !hasLocationPermission()) return
        updatesStarted = true
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setMinUpdateDistanceMeters(3f)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            updatesStarted = false
            _uiState.value = _uiState.value.copy(permissionGranted = false, error = null)
        }
    }

    fun stopLocationUpdates() {
        if (!updatesStarted) return
        fusedClient.removeLocationUpdates(locationCallback)
        updatesStarted = false
    }

    override fun onCleared() {
        stopLocationUpdates()
        errorClearJob?.cancel()
        super.onCleared()
    }
}
