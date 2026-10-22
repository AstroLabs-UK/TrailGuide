package com.trailguide.app.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.trailguide.app.data.MapPreferences
import com.trailguide.app.data.OfflineMapManager
import com.trailguide.app.data.UpdateChecker
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

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
    var githubRepo by remember { mutableStateOf(prefs.githubRepo) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val updateChecker = remember { UpdateChecker(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
                .statusBarsPadding()
        ) {
            Text("Map style", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                label = { Text("Mapbox token (optional)") },
                singleLine = true
            )
            Button(onClick = {
                prefs.mapboxToken = token
                msg = if (prefs.hasToken()) "RDR2 style on" else "Free map"
                onTokenSaved()
            }, Modifier.fillMaxWidth()) { Text("Save token") }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
            Text("Navigation", style = MaterialTheme.typography.titleMedium)
            SettingSwitch("Spoken directions", voice) { voice = it; prefs.voiceEnabled = it }
            SettingSwitch("Western phrasing", western) { western = it; prefs.westernVoice = it }
            SettingSwitch("Metric units", metric) { metric = it; prefs.useMetric = it }
            SettingSwitch("Avoid highways", avoidHwy) { avoidHwy = it; prefs.avoidHighways = it }
            SettingSwitch("Keep screen on while navigating", screenOn) { screenOn = it; prefs.keepScreenOn = it }
            SettingSwitch("North-up map (not heading-up)", northUp) { northUp = it; prefs.northUp = it }

            Spacer(Modifier.height(8.dp))
            Text("Driving region (lanes / roundabouts)", style = MaterialTheme.typography.titleSmall)
            Text("USA & France: right-hand traffic. UK: left-hand. Auto uses device country.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            listOf("AUTO" to "Auto", "USA" to "USA", "UK" to "UK", "FRANCE" to "France").forEach { (id, label) ->
                SettingSwitch(label, region == id) {
                    if (it) { region = id; prefs.drivingRegion = id }
                }
            }


            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
            
            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
            Text("Offline maps", style = MaterialTheme.typography.titleMedium)
            Text(
                "Download tiles around your current location (~20 km). Active journeys are auto-saved for offline guidance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            var offlineProgress by remember { mutableStateOf<String?>(null) }
            Button(
                onClick = {
                    offlineProgress = "Locating…"
                    @SuppressLint("MissingPermission")
                    val client = LocationServices.getFusedLocationProviderClient(context)
                    client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                        .addOnSuccessListener { loc ->
                            if (loc == null) {
                                offlineProgress = "No location"
                                return@addOnSuccessListener
                            }
                            offlineProgress = "Downloading 0%"
                            OfflineMapManager(context).downloadArea(
                                loc.latitude, loc.longitude, 20.0, prefs.styleUrl(),
                                object : OfflineMapManager.Listener {
                                    override fun onProgress(percent: Int) {
                                        offlineProgress = "Downloading $percent%"
                                    }
                                    override fun onComplete() {
                                        offlineProgress = "Offline map ready"
                                    }
                                    override fun onError(message: String) {
                                        offlineProgress = message
                                    }
                                }
                            )
                        }
                        .addOnFailureListener { offlineProgress = "Location failed" }
                },
                Modifier.fillMaxWidth()
            ) { Text("Download map for this area") }
            offlineProgress?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

Text("Home", style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                @SuppressLint("MissingPermission")
                val client = LocationServices.getFusedLocationProviderClient(context)
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                    .addOnSuccessListener { loc ->
                        if (loc != null) {
                            prefs.homeLat = loc.latitude
                            prefs.homeLng = loc.longitude
                            msg = "Home saved"
                        } else msg = "No location"
                    }
            }, Modifier.fillMaxWidth()) { Text("Set home to current location") }
            if (prefs.hasHome()) {
                OutlinedButton(onClick = { prefs.clearHome(); msg = "Home cleared" }, Modifier.fillMaxWidth()) {
                    Text("Clear home")
                }
            }
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
            Text("Updates (GitHub)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Publish a GitHub Release with tag vX.Y.Z and attach an .apk. Then check here for updates.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = githubRepo,
                onValueChange = { githubRepo = it },
                Modifier.fillMaxWidth(),
                label = { Text("GitHub repo (owner/name)") },
                placeholder = { Text("yourname/TrailGuide") },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    prefs.githubRepo = githubRepo
                    updateMsg = "Checking…"
                    scope.launch {
                        val result = updateChecker.check()
                        result.fold(
                            onSuccess = { info ->
                                if (info.isNewer) {
                                    updateMsg = "Update available: ${info.tagName}. Opening download…"
                                    updateChecker.openReleasePage(info)
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
            ) { Text("Check for updates") }
            updateMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
