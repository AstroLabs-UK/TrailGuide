package com.trailguide.app.ui

import android.app.Application
import android.content.Intent
import android.location.Location
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trailguide.app.data.MapPreferences
import com.trailguide.app.data.OfflineJourneyStore
import com.trailguide.app.data.NetworkMonitor
import com.trailguide.app.data.NavSnapshot
import com.trailguide.app.data.SavedPlace
import com.trailguide.app.location.LocationService
import com.trailguide.app.routing.DrivingRegion
import com.trailguide.app.routing.OsrmRoutingService
import com.trailguide.app.routing.PlaceResult
import com.trailguide.app.routing.PlaceSearch
import com.trailguide.app.routing.RouteResult
import com.trailguide.app.routing.RoutingService
import com.trailguide.app.routing.TravelMode
import com.trailguide.app.voice.ArthurVoice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class NavigationUiState(
    val route: RouteResult? = null,
    val alternatives: List<RouteResult> = emptyList(),
    val selectedAltIndex: Int = 0,
    val isCalculating: Boolean = false,
    val error: String? = null,
    val currentStepIndex: Int = 0,
    val isNavigating: Boolean = false,
    val destinationLat: Double? = null,
    val destinationLng: Double? = null,
    val destinationName: String? = null,
    val hasArrived: Boolean = false,
    val isRerouting: Boolean = false,
    val searchResults: List<PlaceResult> = emptyList(),
    val isSearching: Boolean = false,
    val travelMode: TravelMode = TravelMode.DRIVING,
    val muted: Boolean = false,
    val speedMps: Float = 0f,
    val distanceToNextM: Double = 0.0,
    val lastInstruction: String = "",
    val searchModeShop: Boolean = false
)

class NavigationViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val prefs = MapPreferences(application)
    private val offlineStore = OfflineJourneyStore(application)
    private val networkMonitor = NetworkMonitor(application)
    private val routingService: RoutingService = OsrmRoutingService()
    private val voice = ArthurVoice(application)
    private val _uiState = MutableStateFlow(NavigationUiState())
    val uiState: StateFlow<NavigationUiState> = _uiState.asStateFlow()

    private val spokenSteps = mutableSetOf<Int>()
    private val approachMetres = 40.0
    private val arrivalMetres = 25.0
    private val offRouteMetres = 80.0
    private var lastRerouteTimeMs = 0L
    private val rerouteCooldownMs = 15_000L
    private var searchJob: Job? = null

    init {
        applyVoicePrefs()
        networkMonitor.start()
        restoreOfflineJourneyIfNeeded()
    }

    fun applyVoicePrefs() { voice.setWesternMode(prefs.westernVoice) }

    private fun region(): DrivingRegion = try {
        DrivingRegion.valueOf(prefs.drivingRegion)
    } catch (_: Exception) { DrivingRegion.AUTO }

    fun setTravelMode(mode: TravelMode) {
        _uiState.value = _uiState.value.copy(travelMode = mode)
    }

    fun setMuted(muted: Boolean) {
        _uiState.value = _uiState.value.copy(muted = muted)
        NavSnapshot.muted = muted
        if (muted) voice.stop()
    }

    fun repeatLastInstruction() {
        val t = _uiState.value.lastInstruction
        if (t.isNotBlank()) speakNow(t)
    }

    fun searchPlacesDebounced(query: String, userLat: Double?, userLng: Double?) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList(), isSearching = false)
            return
        }
        searchJob = viewModelScope.launch {
            delay(450)
            searchPlaces(query, userLat, userLng)
        }
    }

    fun searchPlaces(query: String, userLat: Double? = null, userLng: Double? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true, error = null)
            try {
                val results = if (userLat != null && userLng != null && looksLikeShopQuery(query)) {
                    PlaceSearch.searchShopsNearby(query, userLat, userLng)
                } else {
                    val base = PlaceSearch.search(query)
                    if (userLat != null && userLng != null) {
                        base.map {
                            it.copy(distanceMeters = PlaceSearch.haversine(userLat, userLng, it.lat, it.lng))
                        }.sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
                    } else base
                }
                prefs.addRecentSearch(query)
                _uiState.value = _uiState.value.copy(
                    searchResults = results,
                    isSearching = false,
                    searchModeShop = looksLikeShopQuery(query)
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    error = "Search failed — check your connection"
                )
            }
        }
    }

    private fun looksLikeShopQuery(q: String): Boolean {
        val lower = q.lowercase()
        val keywords = listOf(
            "shop", "store", "market", "supermarket", "mall", "cafe", "coffee",
            "restaurant", "fuel", "gas", "petrol", "pharmacy", "chemist", "bank",
            "walmart", "tesco", "sainsbury", "carrefour", "lidl", "aldi", "costco",
            "starbucks", "mcdonald", "target", "kroger", "asda", "morrisons",
            "boots", "waiterose", "waitrose", "spar", "7-eleven", "cvs", "walgreens",
            "auchan", "leclerc", "intermarche", "franprix", "monoprix"
        )
        return keywords.any { lower.contains(it) }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(searchResults = emptyList(), isSearching = false)
    }

    fun navigateTo(start: Location, place: PlaceResult) =
        calculateRoute(start, place.lat, place.lng, place.name)

    fun navigateToCoords(start: Location, lat: Double, lng: Double, name: String) =
        calculateRoute(start, lat, lng, name)

    fun navigateToHome(start: Location) {
        if (prefs.hasHome()) calculateRoute(start, prefs.homeLat, prefs.homeLng, "Home")
    }

    fun selectAlternative(index: Int) {
        val alts = _uiState.value.alternatives
        if (index !in alts.indices) return
        val route = alts[index]
        spokenSteps.clear()
        _uiState.value = _uiState.value.copy(route = route, selectedAltIndex = index, currentStepIndex = 0)
        route.steps.firstOrNull()?.instruction?.let {
            spokenSteps.add(0)
            speak(it)
            _uiState.value = _uiState.value.copy(lastInstruction = it)
        }
        publishSnapshot()
    }

    fun calculateRoute(start: Location, endLat: Double, endLng: Double, name: String? = null) {
        viewModelScope.launch {
            spokenSteps.clear()
            applyVoicePrefs()
            val mode = _uiState.value.travelMode
            _uiState.value = _uiState.value.copy(
                isCalculating = true, error = null, hasArrived = false, isRerouting = false,
                destinationLat = endLat, destinationLng = endLng, destinationName = name,
                searchResults = emptyList()
            )
            val results = routingService.getRoute(
                start.latitude, start.longitude, endLat, endLng,
                mode = mode, alternatives = true,
                avoidHighways = prefs.avoidHighways, region = region()
            )
            if (results.isNotEmpty()) {
                val primary = results.first()
                _uiState.value = NavigationUiState(
                    route = primary, alternatives = results, selectedAltIndex = 0,
                    isCalculating = false, isNavigating = true, currentStepIndex = 0,
                    destinationLat = endLat, destinationLng = endLng, destinationName = name,
                    travelMode = mode, muted = _uiState.value.muted
                )
                startNavService()
                offlineStore.saveJourney(primary, endLat, endLng, name, 0)
                primary.steps.firstOrNull()?.instruction?.let {
                    spokenSteps.add(0)
                    speak(it)
                    _uiState.value = _uiState.value.copy(lastInstruction = it)
                }
                publishSnapshot()
            } else {
                _uiState.value = NavigationUiState(
                    isCalculating = false,
                    error = "Could not calculate a route — check connection or try again",
                    travelMode = mode
                )
                speakNow("Could not find a trail to that place.")
                NavSnapshot.clear()
            }
        }
    }

    fun onLocationUpdate(location: Location) {
        val state = _uiState.value
        val speed = if (location.hasSpeed()) location.speed else 0f
        _uiState.value = state.copy(speedMps = speed)
        NavSnapshot.speedKmh = (speed * 3.6f).toInt()
        if (!state.isNavigating || state.route == null || state.hasArrived) return
        if (state.isCalculating || state.isRerouting) return
        val route = state.route
        val steps = route.steps
        if (steps.isEmpty()) return
        val destLat = state.destinationLat
        val destLng = state.destinationLng
        if (destLat != null && destLng != null) {
            if (haversine(location.latitude, location.longitude, destLat, destLng) <= arrivalMetres) {
                _uiState.value = state.copy(hasArrived = true, isNavigating = false)
                stopNavService()
                speakNow("You've made it. Rest easy.")
                NavSnapshot.clear()
                return
            }
        }
        val remaining = steps.drop(state.currentStepIndex)
        val minDist = remaining.minOfOrNull {
            haversine(location.latitude, location.longitude, it.location.first, it.location.second)
        } ?: Double.MAX_VALUE
        val now = System.currentTimeMillis()
        if (minDist > offRouteMetres && now - lastRerouteTimeMs > rerouteCooldownMs) {
            if (destLat != null && destLng != null) {
                lastRerouteTimeMs = now
                val online = networkMonitor.online.value
                if (online) {
                    speakNow("You've left the trail. Finding a new path.")
                    reroute(location, destLat, destLng, state.destinationName)
                } else {
                    // Offline recovery: snap guidance back to nearest step on saved route
                    val (nearIdx, dist) = offlineStore.nearestStepIndex(
                        location.latitude, location.longitude, route
                    )
                    speakNow("You're offline. Head back toward the route.")
                    val step = route.steps.getOrNull(nearIdx)
                    if (step != null && nearIdx !in spokenSteps) {
                        spokenSteps.add(nearIdx)
                        speak(step.instruction)
                        _uiState.value = _uiState.value.copy(
                            currentStepIndex = nearIdx,
                            lastInstruction = "Head back to the route. ${step.instruction}",
                            distanceToNextM = dist
                        )
                        offlineStore.updateStepIndex(nearIdx)
                    }
                }
                return
            }
        }
        var bestIndex = state.currentStepIndex
        var distToNext = 0.0
        for (i in state.currentStepIndex until steps.size) {
            val step = steps[i]
            val dist = haversine(location.latitude, location.longitude, step.location.first, step.location.second)
            if (i == state.currentStepIndex) distToNext = dist
            if (dist <= approachMetres && i !in spokenSteps) {
                spokenSteps.add(i)
                bestIndex = i
                speak(step.instruction)
                _uiState.value = _uiState.value.copy(lastInstruction = step.instruction)
                break
            }
            if (dist < 15.0 && i == state.currentStepIndex && i < steps.lastIndex) bestIndex = i + 1
        }
        _uiState.value = _uiState.value.copy(currentStepIndex = bestIndex, distanceToNextM = distToNext)
        offlineStore.updateStepIndex(bestIndex)
        publishSnapshot()
    }

    private fun reroute(from: Location, endLat: Double, endLng: Double, name: String?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRerouting = true, isCalculating = true)
            spokenSteps.clear()
            val mode = _uiState.value.travelMode
            val results = routingService.getRoute(
                from.latitude, from.longitude, endLat, endLng,
                mode = mode, alternatives = false,
                avoidHighways = prefs.avoidHighways, region = region()
            )
            if (results.isNotEmpty()) {
                val route = results.first()
                _uiState.value = NavigationUiState(
                    route = route, alternatives = results, isCalculating = false, isRerouting = false,
                    isNavigating = true, currentStepIndex = 0, destinationLat = endLat, destinationLng = endLng,
                    destinationName = name, travelMode = mode, muted = _uiState.value.muted
                )
                route.steps.firstOrNull()?.instruction?.let {
                    spokenSteps.add(0); speak(it)
                    _uiState.value = _uiState.value.copy(lastInstruction = it)
                }
                publishSnapshot()
            } else {
                _uiState.value = _uiState.value.copy(
                    isCalculating = false, isRerouting = false,
                    error = "Could not recalculate — check connection"
                )
            }
        }
    }

    fun stopNavigation() {
        voice.stop(); spokenSteps.clear(); stopNavService()
        offlineStore.clear()
        _uiState.value = NavigationUiState(travelMode = _uiState.value.travelMode, muted = _uiState.value.muted)
        NavSnapshot.clear()
    }

    fun saveCurrentDestination() {
        val s = _uiState.value
        val lat = s.destinationLat ?: return
        val lng = s.destinationLng ?: return
        prefs.savePlace(SavedPlace(s.destinationName ?: "Saved place", lat, lng))
    }

    private fun publishSnapshot() {
        val s = _uiState.value
        val route = s.route
        NavSnapshot.isNavigating = s.isNavigating
        NavSnapshot.instruction = route?.steps?.getOrNull(s.currentStepIndex)?.instruction ?: ""
        NavSnapshot.destinationName = s.destinationName.orEmpty()
        NavSnapshot.muted = s.muted
        if (route != null) {
            val km = route.distanceMeters / 1000.0
            val mins = (route.durationSeconds / 60).toInt()
            NavSnapshot.distanceText = if (prefs.useMetric) "%.1f km".format(km) else "%.1f mi".format(km / 1.609)
            NavSnapshot.etaText = "$mins min"
        }
    }

    private fun speak(text: String) {
        if (!prefs.voiceEnabled || _uiState.value.muted) return
        applyVoicePrefs(); voice.speak(text)
    }
    private fun speakNow(text: String) {
        if (!prefs.voiceEnabled || _uiState.value.muted) return
        applyVoicePrefs(); voice.speakNow(text)
    }
    private fun startNavService() {
        val i = Intent(app, LocationService::class.java).apply { action = LocationService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i) else app.startService(i)
    }
    private fun stopNavService() {
        app.startService(Intent(app, LocationService::class.java).apply { action = LocationService.ACTION_STOP })
    }
    override fun onCleared() {
        networkMonitor.stop()
        stopNavService(); voice.shutdown(); NavSnapshot.clear(); super.onCleared()
    }
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
