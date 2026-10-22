package com.trailguide.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trailguide.app.data.MapPreferences

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { MapPreferences(context) }
    var token by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Trail Guide",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Optional: add a free Mapbox key for the RDR2-style map. You can skip and use the free map, or add a key later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Mapbox public token (optional)") },
            placeholder = { Text("pk.eyJ...") },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Get a free token at account.mapbox.com → Access tokens",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (token.isNotBlank()) {
                    prefs.mapboxToken = token
                }
                prefs.markOnboardingDone()
                onFinished()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = token.isNotBlank()
        ) {
            Text("Save & continue")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                prefs.markOnboardingDone()
                onFinished()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Skip — use free map")
        }

        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = {
            prefs.markOnboardingDone()
            onFinished()
        }) {
            Text("I'll do this later in Settings")
        }
    }
}
