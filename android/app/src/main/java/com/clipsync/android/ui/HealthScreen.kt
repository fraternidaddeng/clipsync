package com.clipsync.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.clipsync.android.R
import com.clipsync.android.ui.settings.CapabilityStatusCards
import com.clipsync.android.ui.theme.ClipSyncTheme

data class HealthScreenState(
    val network: HealthValue,
    val service: HealthValue,
    val read: HealthValue,
    val write: HealthValue,
    val pairedDeviceCount: Int,
) {
    companion object {
        fun initial() = HealthScreenState(
            network = HealthValue(HealthStatus.UNPAIRED, HealthTone.NEUTRAL),
            service = HealthValue(HealthStatus.NOT_RUNNING, HealthTone.NEUTRAL),
            read = HealthValue(HealthStatus.FOREGROUND_ONLY, HealthTone.NEUTRAL),
            write = HealthValue(HealthStatus.NOT_PROBED, HealthTone.NEUTRAL),
            pairedDeviceCount = 0,
        )
    }
}

data class HealthValue(
    val label: HealthStatus,
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
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    text = stringResource(R.string.health_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                CapabilityStatusCards(state = state)
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = if (state.pairedDeviceCount == 0) {
                        stringResource(R.string.health_no_peers)
                    } else {
                        stringResource(R.string.health_peer_count, state.pairedDeviceCount)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HealthScreenPreview() {
    ClipSyncTheme {
        HealthScreen(state = HealthScreenState.initial())
    }
}

enum class HealthStatus {
    UNPAIRED,
    CONNECTED,
    WINDOWS_UNREACHABLE,
    NEEDS_RECOVERY,
    RUNNING_HIDDEN,
    RUNNING,
    NOT_RUNNING,
    FGS_TYPE_MISSING,
    FGS_PERMISSION_MISSING,
    SERVICE_START_DENIED,
    FOREGROUND_READY,
    READ_READY_SHIZUKU,
    READ_READY_ADB,
    READ_READY_OVERLAY,
    DEGRADED,
    UNAVAILABLE,
    NEEDS_ACTION,
    FOREGROUND_ONLY,
    PUBLIC_WRITE_READY,
    NOT_PROBED,
}
