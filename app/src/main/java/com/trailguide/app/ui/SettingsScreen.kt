package com.trailguide.app.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.trailguide.app.data.MapPreferences
import com.trailguide.app.data.MapboxStyleHelper
import com.trailguide.app.data.OfflineMapManager
import com.trailguide.app.data.UpdateChecker
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onTokenSaved: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { MapPreferences(context) }
    var token by remember { mutableStateOf(prefs.mapboxToken) }
    var voice by remember { mutableStateOf(prefs.voiceEnabled) }
    var western by remember { mutableStateOf(prefs.westernVoice) }
    var metric by remember { mutableStateOf(prefs.useMetric) }
    var avoidHwy by remember { mutableStateOf(prefs.avoidHighways) }
    var screenOn by remember { mutableStateOf(prefs.keepScreenOn) }
    var northUp by remember { mutableStateOf(prefs.northUp) }
    var region by remember { mutableStateOf(prefs.drivingRegion) }
    var githubRepo by remember { mutableStateOf(prefs.githubRepo.ifBlank { "AstroLabs-UK/TrailGuide" }) }
    var autoUpdate by remember { mutableStateOf(prefs.autoUpdateEnabled) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var offlineMsg by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val updateChecker = remember { UpdateChecker(context) }

    // Auto-clear feedback messages after 8s
    LaunchedEffect(msg) {
        if (msg != null) {
            kotlinx.coroutines.delay(8_000)
            msg = null
        }
    }
    LaunchedEffect(offlineMsg) {
        if (offlineMsg != null && offlineMsg?.startsWith("Downloading") != true) {
            kotlinx.coroutines.delay(8_000)
            offlineMsg = null
        }
    }
    LaunchedEffect(updateMsg) {
        if (updateMsg != null && updateMsg?.startsWith("Downloading") != true && updateMsg != "Checking…") {
            kotlinx.coroutines.delay(8_000)
            updateMsg = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Map style", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                label = { Text("Mapbox token (optional)") },
                singleLine = true
            )
            Button(
                onClick = {
                    val cleaned = MapboxStyleHelper.sanitizeToken(token)
                    if (cleaned.isBlank()) {
                        prefs.mapboxToken = ""
                        msg = "Token cleared — free map"
                        onTokenSaved()
                        return@Button
                    }
                    msg = "Checking token with Mapbox…"
                    scope.launch {
                        val check = MapboxStyleHelper.validateToken(cleaned)
                        if (check.valid) {
                            prefs.mapboxToken = cleaned
                            msg = "Token verified — loading RDR2-style map"
                            onTokenSaved()
                        } else {
                            msg = "Token failed: ${check.message}"
                        }
                    }
                },
                Modifier.fillMaxWidth()
            ) { Text("Save & verify token") }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Navigation", style = MaterialTheme.typography.titleMedium)
            SettingSwitch("Spoken directions", voice) { voice = it; prefs.voiceEnabled = it }
            SettingSwitch("Western phrasing", western) { western = it; prefs.westernVoice = it }
            SettingSwitch("Metric units", metric) { metric = it; prefs.useMetric = it }
            SettingSwitch("Avoid highways", avoidHwy) { avoidHwy = it; prefs.avoidHighways = it }
            SettingSwitch("Keep screen on while navigating", screenOn) { screenOn = it; prefs.keepScreenOn = it }
            SettingSwitch("North-up map", northUp) { northUp = it; prefs.northUp = it }

            Spacer(Modifier.height(8.dp))
            Text("Driving region", style = MaterialTheme.typography.titleSmall)
            Text(
                "Affects lane hints (UK = left-hand traffic).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            listOf("AUTO" to "Auto", "USA" to "USA", "UK" to "UK", "FRANCE" to "France").forEach { (id, label) ->
                SettingSwitch(label, region == id) { checked ->
                    if (checked) {
                        region = id
                        prefs.drivingRegion = id
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Offline maps", style = MaterialTheme.typography.titleMedium)
            Text(
                "Downloads map tiles near you (~20 km). Journeys are saved automatically for offline guidance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    offlineMsg = "Locating…"
                    try {
                        MapLibre.getInstance(context)
                    } catch (_: Exception) { }
                    @SuppressLint("MissingPermission")
                    try {
                        val client = LocationServices.getFusedLocationProviderClient(context)
                        client.getCurrentLocation(
                            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                            CancellationTokenSource().token
                        ).addOnSuccessListener { loc ->
                            if (loc == null) {
                                offlineMsg = "No GPS fix — try outside or enable location"
                                return@addOnSuccessListener
                            }
                            offlineMsg = "Downloading 0%"
                            try {
                                OfflineMapManager(context).downloadArea(
                                    loc.latitude,
                                    loc.longitude,
                                    20.0,
                                    prefs.styleUrl(),
                                    object : OfflineMapManager.Listener {
                                        override fun onProgress(percent: Int) {
                                            offlineMsg = "Downloading $percent%"
                                        }
                                        override fun onComplete() {
                                            offlineMsg = "Offline map ready"
                                        }
                                        override fun onError(message: String) {
                                            offlineMsg = "Offline download failed: $message"
                                        }
                                    }
                                )
                            } catch (e: Exception) {
                                offlineMsg = "Offline maps unavailable: ${e.message ?: "error"}"
                            }
                        }.addOnFailureListener {
                            offlineMsg = "Could not get location"
                        }
                    } catch (e: Exception) {
                        offlineMsg = "Location error: ${e.message ?: "unavailable"}"
                    }
                },
                Modifier.fillMaxWidth()
            ) { Text("Download map for this area") }
            offlineMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Home", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = {
                    @SuppressLint("MissingPermission")
                    try {
                        val client = LocationServices.getFusedLocationProviderClient(context)
                        client.getCurrentLocation(
                            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                            CancellationTokenSource().token
                        ).addOnSuccessListener { loc ->
                            if (loc != null) {
                                prefs.homeLat = loc.latitude
                                prefs.homeLng = loc.longitude
                                msg = "Home saved"
                            } else msg = "No location"
                        }.addOnFailureListener { msg = "Location failed" }
                    } catch (e: Exception) {
                        msg = "Location unavailable"
                    }
                },
                Modifier.fillMaxWidth()
            ) { Text("Set home to current location") }
            if (prefs.hasHome()) {
                OutlinedButton(
                    onClick = { prefs.clearHome(); msg = "Home cleared" },
                    Modifier.fillMaxWidth()
                ) { Text("Clear home") }
            }
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Updates", style = MaterialTheme.typography.titleMedium)
            SettingSwitch("Check for updates on launch", autoUpdate) {
                autoUpdate = it
                prefs.autoUpdateEnabled = it
            }
            OutlinedTextField(
                value = githubRepo,
                onValueChange = { githubRepo = it },
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                label = { Text("GitHub repo (owner/name)") },
                singleLine = true
            )
            Button(
                onClick = {
                    prefs.githubRepo = githubRepo.trim()
                    updateMsg = "Checking…"
                    scope.launch {
                        updateChecker.check().fold(
                            onSuccess = { info ->
                                if (info.isNewer) {
                                    updateMsg = "Downloading ${info.tagName}…"
                                    val ok = updateChecker.downloadAndPromptInstall(info)
                                    updateMsg = if (ok) "Install prompt opened — confirm to update"
                                    else "Download failed — open release page"
                                    if (!ok) updateChecker.openReleasePage(info)
                                } else {
                                    updateMsg = "You're up to date (${info.tagName})"
                                }
                            },
                            onFailure = { e ->
                                updateMsg = e.message ?: "Update check failed"
                            }
                        )
                    }
                },
                Modifier.fillMaxWidth()
            ) { Text("Check for updates now") }
            updateMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
