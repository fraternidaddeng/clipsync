package com.clipsync.android.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.clipsync.android.ui.theme.ClipSyncTheme

data class HealthScreenState(
    val app: HealthValue,
    val network: HealthValue,
    val backgroundRead: HealthValue,
    val clipboardWrite: HealthValue,
    val pairedDeviceCount: Int,
) {
    companion object {
        fun initial() = HealthScreenState(
            app = HealthValue("Ready", HealthTone.GOOD),
            network = HealthValue("Not configured", HealthTone.NEUTRAL),
            backgroundRead = HealthValue("Foreground only", HealthTone.NEUTRAL),
            clipboardWrite = HealthValue("Not probed", HealthTone.NEUTRAL),
            pairedDeviceCount = 0,
        )
    }
}

data class HealthValue(
    val label: String,
    val tone: HealthTone,
)

enum class HealthTone {
    GOOD,
    NEUTRAL,
    WARNING,
}

@Composable
fun HealthScreen(
    state: HealthScreenState,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { contentPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                Text(
                    text = "ClipSync",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    text = "Device health",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                HealthRow(label = "App", value = state.app)
                HealthRow(label = "Network", value = state.network)
                HealthRow(label = "Background read", value = state.backgroundRead)
                HealthRow(label = "Clipboard write", value = state.clipboardWrite)
                HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))
                Text(
                    text = if (state.pairedDeviceCount == 0) {
                        "No paired devices"
                    } else {
                        "${state.pairedDeviceCount} paired devices"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun HealthRow(
    label: String,
    value: HealthValue,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(value.tone.color(), CircleShape),
            )
            Text(
                text = value.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HealthTone.color(): Color = when (this) {
    HealthTone.GOOD -> MaterialTheme.colorScheme.primary
    HealthTone.NEUTRAL -> MaterialTheme.colorScheme.outline
    HealthTone.WARNING -> MaterialTheme.colorScheme.error
}

@Preview(showBackground = true)
@Composable
private fun HealthScreenPreview() {
    ClipSyncTheme {
        HealthScreen(state = HealthScreenState.initial())
    }
}
