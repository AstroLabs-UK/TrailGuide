package com.trailguide.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.trailguide.app.data.MapPreferences
import com.trailguide.app.data.UpdateChecker
import com.trailguide.app.ui.MapScreen
import com.trailguide.app.ui.OnboardingScreen
import com.trailguide.app.ui.SettingsScreen
import com.trailguide.app.ui.theme.TrailGuideTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Auto-update: offer pending install, then check GitHub in background
        val prefs = MapPreferences(this)
        val updater = UpdateChecker(this)
        if (prefs.autoUpdateEnabled) {
            updater.installPendingIfAny()
            lifecycleScope.launch {
                updater.check().onSuccess { info ->
                    if (info.isNewer && info.apkUrl != null) {
                        val ok = updater.downloadAndPromptInstall(info)
                        if (ok) {
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Update ${info.tagName} ready — confirm install",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }
        }

        setContent {
            TrailGuideTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val context = LocalContext.current
                    val localPrefs = remember { MapPreferences(context) }

                    var showOnboarding by remember {
                        mutableStateOf(!localPrefs.isOnboardingDone())
                    }
                    var showSettings by remember { mutableStateOf(false) }
                    var styleVersion by remember { mutableStateOf(0) }

                    when {
                        showOnboarding -> {
                            OnboardingScreen(
                                onFinished = {
                                    showOnboarding = false
                                    styleVersion++
                                }
                            )
                        }
                        showSettings -> {
                            SettingsScreen(
                                onBack = { showSettings = false },
                                onTokenSaved = {
                                    styleVersion++
                                    showSettings = false
                                }
                            )
                        }
                        else -> {
                            MapScreen(
                                styleVersion = styleVersion,
                                onOpenSettings = { showSettings = true }
                            )
                        }
                    }
                }
            }
        }
    }
}
