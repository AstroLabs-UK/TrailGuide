package com.trailguide.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.trailguide.app.data.UpdateChecker
import com.trailguide.app.data.UpdateInfo
import kotlinx.coroutines.launch

/**
 * Shown when a newer GitHub release is available.
 * Changelog is collapsed behind "Read more".
 */
@Composable
fun UpdateScreen(
    info: UpdateInfo,
    onClose: () -> Unit,
    onInstalledPrompted: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checker = remember { UpdateChecker(context) }
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val notes = info.body.ifBlank { "Bug fixes and improvements." }
    val preview = notes.lineSequence().take(4).joinToString("\n").let {
        if (notes.lines().size > 4 || notes.length > 280) "$it…" else it
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Update available",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                info.name.ifBlank { info.tagName },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                info.tagName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "What's new",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (expanded) notes else preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            if (notes.lines().size > 4 || notes.length > 280) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Text(if (expanded) "Show less" else "Read more")
                }
            }

            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = {
                    if (busy) return@Button
                    busy = true
                    status = "Downloading…"
                    scope.launch {
                        val ok = checker.downloadAndPromptInstall(info)
                        busy = false
                        status = if (ok) {
                            onInstalledPrompted()
                            "Confirm install when Android asks"
                        } else {
                            "Download failed — try See source code"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) {
                Text(if (busy) "Please wait…" else "Download & install")
            }

            Spacer(Modifier.height(12.dp))

            // Close — more visible, outlined
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Text("Close", fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(16.dp))

            // See source code — text only
            Text(
                text = "See source code",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable {
                        val uri = Uri.parse(
                            info.htmlUrl.ifBlank {
                                "https://github.com/AstroLabs-UK/TrailGuide"
                            }
                        )
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    .padding(8.dp)
            )
        }
    }
}
