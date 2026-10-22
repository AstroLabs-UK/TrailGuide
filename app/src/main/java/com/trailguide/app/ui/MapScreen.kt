package com.trailguide.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale
import com.trailguide.app.data.MapPreferences
import com.trailguide.app.data.MapboxStyleHelper
import com.trailguide.app.data.NetworkMonitor
import com.trailguide.app.routing.PlaceSearch
import com.trailguide.app.routing.TravelMode
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import androidx.activity.ComponentActivity

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MapScreen(
    styleVersion: Int = 0,
    onOpenSettings: () -> Unit = {},
    mapViewModel: MapViewModel = viewModel(),
    navViewModel: NavigationViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { MapPreferences(context) }
    val networkMonitor = remember { NetworkMonitor(context) }
    val isOnline by networkMonitor.online.collectAsState(initial = true)
    DisposableEffect(Unit) {
        networkMonitor.start()
        onDispose { networkMonitor.stop() }
    }
    val styleUrl = remember(styleVersion) { prefs.styleUrl() }
    val keyboard = LocalSoftwareKeyboardController.current
    val locationState by mapViewModel.uiState.collectAsState()
    val navState by navViewModel.uiState.collectAsState()
    // Auto-dismiss error banners so they don't stick forever
    LaunchedEffect(navState.error) {
        if (navState.error != null) {
            kotlinx.coroutines.delay(6_000)
            navViewModel.clearError()
        }
    }
    LaunchedEffect(locationState.error) {
        // location errors are informational; no action beyond display timeout in status text
    }
    var searchQuery by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var selectedPlace by remember { mutableStateOf<com.trailguide.app.routing.PlaceResult?>(null) }

    val locationPermissions = rememberMultiplePermissionsState(
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    )
    val permanentlyDenied = locationPermissions.permissions.any {
        !it.status.isGranted && !it.status.shouldShowRationale
    } && !locationPermissions.allPermissionsGranted

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var mapLibreMap by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var hasCenteredOnce by remember { mutableStateOf(false) }
    var styleLoadFailed by remember { mutableStateOf(false) }
    var styleReady by remember { mutableStateOf(false) }

    // Keep screen on during navigation
    DisposableEffect(navState.isNavigating, prefs.keepScreenOn) {
        val activity = context as? ComponentActivity
        val window = activity?.window
        if (navState.isNavigating && prefs.keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(Unit) {
        if (!locationPermissions.allPermissionsGranted) locationPermissions.launchMultiplePermissionRequest()
    }

    LaunchedEffect(styleUrl, styleVersion) {
        val map = mapLibreMap ?: return@LaunchedEffect
        styleReady = false
        applyMapStyle(
            map, context, prefs,
            onReady = { styleReady = true; styleLoadFailed = false },
            onFailed = { styleReady = true; styleLoadFailed = true }
        )
    }

    LaunchedEffect(locationState.location) {
        val location = locationState.location ?: return@LaunchedEffect
        val map = mapLibreMap
        if (map != null && !hasCenteredOnce) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 14.0))
            hasCenteredOnce = true
        }
        navViewModel.onLocationUpdate(location)
    }

    LaunchedEffect(navState.route, navState.selectedAltIndex, styleReady) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        removeLayerAndSource(style, "route-layer", "route-source")
        removeLayerAndSource(style, "dest-layer", "dest-source")
        val route = navState.route ?: return@LaunchedEffect
        try {
            val fc = FeatureCollection.fromFeature(
                Feature.fromJson("""{"type":"Feature","geometry":${route.geometry},"properties":{}}""")
            )
            style.addSource(GeoJsonSource("route-source", fc))
            style.addLayer(
                LineLayer("route-layer", "route-source").withProperties(
                    PropertyFactory.lineColor("#1A73E8"),
                    PropertyFactory.lineWidth(6f),
                    PropertyFactory.lineOpacity(0.9f)
                )
            )
        } catch (_: Exception) { }
        val dLat = navState.destinationLat
        val dLng = navState.destinationLng
        if (dLat != null && dLng != null && navState.isNavigating) {
            try {
                style.addSource(GeoJsonSource("dest-source", Feature.fromGeometry(Point.fromLngLat(dLng, dLat))))
                style.addLayer(
                    SymbolLayer("dest-layer", "dest-source").withProperties(
                        PropertyFactory.textField("●"),
                        PropertyFactory.textSize(22f),
                        PropertyFactory.textColor("#EA4335"),
                        PropertyFactory.textHaloColor("#FFFFFF"),
                        PropertyFactory.textHaloWidth(1.5f)
                    )
                )
            } catch (_: Exception) { }
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    fun fitRouteOverview() {
        val route = navState.route ?: return
        val map = mapLibreMap ?: return
        try {
            val geom = org.json.JSONObject(route.geometry)
            val coords = geom.getJSONArray("coordinates")
            if (coords.length() == 0) return
            val b = LatLngBounds.Builder()
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                b.include(LatLng(c.getDouble(1), c.getDouble(0)))
            }
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 100))
        } catch (_: Exception) { }
    }

    fun shareDestination() {
        val lat = navState.destinationLat ?: return
        val lng = navState.destinationLng ?: return
        val name = navState.destinationName ?: "Destination"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$name\nhttps://maps.google.com/?q=$lat,$lng")
        }
        context.startActivity(Intent.createChooser(send, "Share destination"))
    }

    Box(Modifier.fillMaxSize()) {
        if (locationPermissions.allPermissionsGranted) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize(), update = { view ->
                if (mapLibreMap == null) {
                    view.getMapAsync { map ->
                        mapLibreMap = map
                        // Load preferred style; on failure fall back to free OpenFreeMap
                        applyMapStyle(
                            map, context, prefs,
                            onReady = { styleReady = true; styleLoadFailed = false },
                            onFailed = { styleReady = true; styleLoadFailed = true }
                        )
                        map.addOnMapLongClickListener { latLng ->
                            mapViewModel.uiState.value.location?.let {
                                navViewModel.calculateRoute(it, latLng.latitude, latLng.longitude, "Dropped pin")
                            }
                            true
                        }
                    }
                }
            })


            if (!isOnline) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        "No internet connection. Map tiles and routing need data.",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (!navState.isNavigating) {
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(12.dp)
                ) {
                    Card(
                        Modifier.fillMaxWidth().shadow(6.dp, RoundedCornerShape(28.dp)),
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, null, Modifier.padding(start = 12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = {
                                    searchQuery = it
                                    searchFocused = true
                                    navViewModel.searchPlacesDebounced(it, locationState.location?.latitude, locationState.location?.longitude)
                                },
                                Modifier.weight(1f),
                                placeholder = { Text("Search here") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    navViewModel.searchPlaces(searchQuery, locationState.location?.latitude, locationState.location?.longitude)
                                    keyboard?.hide()
                                })
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    navViewModel.clearSearch()
                                }) { Icon(Icons.Default.Close, "Clear") }
                            }
                            IconButton(onClick = onOpenSettings) {
                                Icon(Icons.Default.Settings, "Settings")
                            }
                        }
                    }

                    // Travel mode chips
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TravelMode.entries.forEach { mode ->
                            FilterChip(
                                selected = navState.travelMode == mode,
                                onClick = { navViewModel.setTravelMode(mode) },
                                label = { Text(mode.label) }
                            )
                        }
                    }

                    // Category chips
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Restaurant", "Fuel", "Parking", "Hospital", "Coffee").forEach { cat ->
                            AssistChip(
                                onClick = {
                                    searchQuery = cat
                                    searchFocused = true
                                    selectedPlace = null
                                    navViewModel.searchPlaces(
                                        cat,
                                        locationState.location?.latitude,
                                        locationState.location?.longitude
                                    )
                                },
                                label = { Text(cat) }
                            )
                        }
                    }

                    if (searchFocused) {
                        Card(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            when {
                                navState.isSearching -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text("Searching…")
                                }
                                navState.searchResults.isNotEmpty() -> LazyColumn(Modifier.height(200.dp)) {
                                    items(navState.searchResults) { place ->
                                        Column(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    selectedPlace = place
                                                    searchFocused = false
                                                    keyboard?.hide()
                                                }
                                                .padding(16.dp, 12.dp)
                                        ) {
                                            Text(place.name, style = MaterialTheme.typography.bodyLarge)
                                            Text(
                                                buildString {
                                                    place.distanceMeters?.let {
                                                        append(PlaceSearch.formatDistance(it, prefs.useMetric))
                                                        append(" · ")
                                                    }
                                                    append(place.displayName)
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                else -> {
                                    val recent = prefs.getRecentSearches()
                                    val saved = prefs.getSavedPlaces()
                                    Column(Modifier.padding(8.dp)) {
                                        if (saved.isNotEmpty()) {
                                            Text("Saved", style = MaterialTheme.typography.labelMedium,
                                                modifier = Modifier.padding(8.dp))
                                            saved.take(5).forEach { p ->
                                                Text(
                                                    p.name,
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            locationState.location?.let {
                                                                navViewModel.navigateToCoords(it, p.lat, p.lng, p.name)
                                                                searchFocused = false
                                                            }
                                                        }
                                                        .padding(12.dp, 8.dp)
                                                )
                                            }
                                        }
                                        if (recent.isNotEmpty()) {
                                            Text("Recent", style = MaterialTheme.typography.labelMedium,
                                                modifier = Modifier.padding(8.dp))
                                            recent.take(5).forEach { q ->
                                                Text(
                                                    q,
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            searchQuery = q
                                                            navViewModel.searchPlaces(q, locationState.location?.latitude, locationState.location?.longitude)
                                                        }
                                                        .padding(12.dp, 8.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .padding(bottom = if (navState.isNavigating) 160.dp else 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (navState.isNavigating) {
                    FloatingActionButton(
                        onClick = { fitRouteOverview() },
                        containerColor = MaterialTheme.colorScheme.surface
                    ) { Icon(Icons.Default.ZoomOutMap, "Overview") }
                }
                if (prefs.hasHome() && !navState.isNavigating) {
                    FloatingActionButton(
                        onClick = { locationState.location?.let { navViewModel.navigateToHome(it) } },
                        containerColor = MaterialTheme.colorScheme.surface
                    ) { Icon(Icons.Default.Home, "Home") }
                }
                if (locationState.location != null) {
                    FloatingActionButton(
                        onClick = {
                            val loc = mapViewModel.uiState.value.location
                            mapViewModel.refreshLocation()
                            loc?.let {
                                mapLibreMap?.animateCamera(
                                    CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 15.0)
                                )
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surface
                    ) { Icon(Icons.Default.MyLocation, "My location") }
                }
            }

            if (navState.isNavigating && navState.route != null) {
                val route = navState.route!!
                val step = route.steps.getOrNull(navState.currentStepIndex)
                val dist = formatDistance(route.distanceMeters, prefs.useMetric)
                val nextDist = formatDistance(navState.distanceToNextM, prefs.useMetric)
                val mins = (route.durationSeconds / 60).toInt()
                val speed = if (prefs.useMetric) "${(navState.speedMps * 3.6).toInt()} km/h"
                else "${(navState.speedMps * 2.237).toInt()} mph"

                Card(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp)
                        .shadow(8.dp, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        // Alternative routes
                        if (navState.alternatives.size > 1) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                navState.alternatives.forEachIndexed { i, alt ->
                                    FilterChip(
                                        selected = i == navState.selectedAltIndex,
                                        onClick = { navViewModel.selectAlternative(i) },
                                        label = {
                                            Text(
                                                formatDistance(alt.distanceMeters, prefs.useMetric) +
                                                        " · ${(alt.durationSeconds / 60).toInt()} min"
                                            )
                                        }
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    step?.instruction ?: "Continue",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "In $nextDist · $dist total · $mins min · $speed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                navState.destinationName?.let {
                                    Text(it, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Column {
                                IconButton(onClick = { navViewModel.setMuted(!navState.muted) }) {
                                    Icon(
                                        if (navState.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                        "Mute"
                                    )
                                }
                                IconButton(onClick = { navViewModel.repeatLastInstruction() }) {
                                    Text("↻", style = MaterialTheme.typography.titleLarge)
                                }
                                IconButton(onClick = { navViewModel.stopNavigation() }) {
                                    Icon(Icons.Default.Close, "Stop")
                                }
                            }
                        }
                        Row {
                            TextButton(onClick = { shareDestination() }) { Text("Share") }
                            TextButton(onClick = { navViewModel.saveCurrentDestination() }) { Text("Save place") }
                        }
                    }
                }
            }


            // Selected place — info + navigate
            selectedPlace?.let { place ->
                if (!navState.isNavigating) {
                    Card(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(place.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                place.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3
                            )
                            place.distanceMeters?.let { d ->
                                Text(
                                    PlaceSearch.formatDistance(d, prefs.useMetric) + " away",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (place.category.isNotBlank()) {
                                Text(
                                    place.category.replaceFirstChar { c -> c.uppercase() },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        val loc = locationState.location
                                        if (loc != null) {
                                            navViewModel.navigateTo(loc, place)
                                            selectedPlace = null
                                            searchQuery = ""
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Navigate") }
                                OutlinedButton(
                                    onClick = { selectedPlace = null },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Close") }
                            }
                        }
                    }
                }
            }

            val status = when {
                navState.isRerouting -> "Finding a new path…"
                navState.isCalculating -> "Getting directions…"
                styleLoadFailed -> "Mapbox style failed (bad token or network) — free map shown"
                navState.error != null -> navState.error!!
                locationState.error != null -> locationState.error
                locationState.isLoading -> "Getting your location…"
                navState.hasArrived -> "You've arrived"
                !isOnline && navState.isNavigating -> "Offline guidance — using saved journey"
                else -> null
            }
            status?.let { msg ->
                Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (navState.isCalculating || navState.isRerouting || locationState.isLoading) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(msg)
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp).statusBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Location is needed to navigate.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                if (permanentlyDenied) {
                    Button(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        )
                    }) { Text("Open settings") }
                } else {
                    Button(onClick = { locationPermissions.launchMultiplePermissionRequest() }) {
                        Text("Allow location")
                    }
                }
            }
        }
    }
}

private fun formatDistance(meters: Double, metric: Boolean): String =
    if (metric) {
        if (meters >= 1000) "%.1f km".format(meters / 1000) else "${meters.toInt()} m"
    } else {
        val miles = meters / 1609.344
        if (miles >= 0.1) "%.1f mi".format(miles) else "${(meters * 3.28084).toInt()} ft"
    }

private fun removeLayerAndSource(style: Style, layerId: String, sourceId: String) {
    style.getLayer(layerId)?.let { style.removeLayer(it) }
    style.getSource(sourceId)?.let { style.removeSource(it) }
}

@SuppressLint("MissingPermission")


private fun applyMapStyle(
    map: org.maplibre.android.maps.MapLibreMap,
    context: android.content.Context,
    prefs: MapPreferences,
    onReady: () -> Unit,
    onFailed: () -> Unit
) {
    val token = prefs.sanitizedMapboxToken()
    if (token.isBlank()) {
        map.setStyle(Style.Builder().fromUri(MapPreferences.OPENFREEMAP_LIBERTY)) { style ->
            promoteLabelLayers(style)
            enableLocationComponent(context, map, style, prefs.northUp)
            onReady()
        }
        return
    }
    // Load Mapbox New Western on background, rewrite mapbox:// for MapLibre
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
        val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            MapboxStyleHelper.loadStyleJsonForMapLibre(token)
        }
        if (json != null) {
            try {
                map.setStyle(Style.Builder().fromJson(json)) { style ->
                    promoteLabelLayers(style)
                    enableLocationComponent(context, map, style, prefs.northUp)
                    onReady()
                }
            } catch (e: Exception) {
                map.setStyle(Style.Builder().fromUri(MapPreferences.OPENFREEMAP_LIBERTY)) { style ->
                    promoteLabelLayers(style)
                    enableLocationComponent(context, map, style, prefs.northUp)
                    onFailed()
                }
            }
        } else {
            map.setStyle(Style.Builder().fromUri(MapPreferences.OPENFREEMAP_LIBERTY)) { style ->
                promoteLabelLayers(style)
                enableLocationComponent(context, map, style, prefs.northUp)
                onFailed()
            }
        }
    }
}


/** Move place/road label layers above 3D buildings so text stays readable. */
private fun promoteLabelLayers(style: Style) {
    try {
        val labelIds = style.layers.map { it.id }.filter { id ->
            val x = id.lowercase()
            x.contains("label") || x.contains("place") || x.contains("poi") ||
                x.contains("symbol") || x.contains("text") || x.contains("road-name") ||
                x.contains("settlement") || x.contains("housenum")
        }
        labelIds.forEach { id ->
            try {
                val layer = style.getLayer(id) ?: return@forEach
                style.removeLayer(id)
                style.addLayer(layer)
            } catch (_: Exception) { }
        }
    } catch (_: Exception) { }
}

private fun enableLocationComponent(
    context: Context,
    map: org.maplibre.android.maps.MapLibreMap,
    style: Style,
    northUp: Boolean
) {
    val lc = map.locationComponent
    lc.activateLocationComponent(
        LocationComponentActivationOptions.builder(context, style).useDefaultLocationEngine(true).build()
    )
    lc.isLocationComponentEnabled = true
    lc.cameraMode = if (northUp) CameraMode.TRACKING else CameraMode.TRACKING_GPS
    lc.renderMode = if (northUp) RenderMode.NORMAL else RenderMode.COMPASS
}
