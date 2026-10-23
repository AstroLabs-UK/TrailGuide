package com.trailguide.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

data class BannerMessage(
    val id: String,
    val text: String,
    val loading: Boolean = false,
    val actionLabel: String? = null,
    val sticky: Boolean = false,
    val isError: Boolean = true
)

/**
 * Centered status / error card. Auto-dismisses after [autoClearMs] unless [message.sticky].
 * Optional action button (e.g. "Go to settings").
 */
@Composable
fun ErrorBanner(
    message: BannerMessage?,
    onDismiss: () -> Unit,
    onAction: (() -> Unit)? = null,
    autoClearMs: Long = 8_000L,
    modifier: Modifier = Modifier
) {
    if (message == null) return

    LaunchedEffect(message.id, message.sticky) {
        if (!message.sticky && autoClearMs > 0) {
            delay(autoClearMs)
            onDismiss()
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (message.isError)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (message.loading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }
            if (message.actionLabel != null || !message.sticky) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!message.sticky) {
                        TextButton(onClick = onDismiss) {
                            Text("Dismiss")
                        }
                    }
                    if (message.actionLabel != null && onAction != null) {
                        OutlinedButton(onClick = onAction) {
                            Text(message.actionLabel)
                        }
                    }
                }
            }
        }
    }
}
