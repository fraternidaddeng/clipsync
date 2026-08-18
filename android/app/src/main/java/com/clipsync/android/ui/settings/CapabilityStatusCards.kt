@file:Suppress("ktlint:standard:function-naming")

package com.clipsync.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.clipsync.android.R
import com.clipsync.android.ui.HealthScreenState
import com.clipsync.android.ui.HealthStatus
import com.clipsync.android.ui.HealthTone
import com.clipsync.android.ui.HealthValue

@Composable
fun CapabilityStatusCards(
    state: HealthScreenState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CapabilityCard(title = stringResource(R.string.capability_network), value = state.network)
        CapabilityCard(title = stringResource(R.string.capability_service), value = state.service)
        CapabilityCard(title = stringResource(R.string.capability_read), value = state.read)
        CapabilityCard(title = stringResource(R.string.capability_write), value = state.write)
    }
}

@Composable
private fun CapabilityCard(
    title: String,
    value: HealthValue,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(healthStatusRes(value.label)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier =
                    Modifier
                        .size(10.dp)
                        .background(value.tone.color(), CircleShape),
            )
        }
    }
}

private val HEALTH_STATUS_RES: Map<HealthStatus, Int> =
    mapOf(
        HealthStatus.UNPAIRED to R.string.health_unpaired,
        HealthStatus.CONNECTED to R.string.health_connected,
        HealthStatus.WINDOWS_UNREACHABLE to R.string.health_windows_unreachable,
        HealthStatus.NEEDS_RECOVERY to R.string.health_needs_recovery,
        HealthStatus.RUNNING_HIDDEN to R.string.health_running_hidden,
        HealthStatus.RUNNING to R.string.health_running,
        HealthStatus.NOT_RUNNING to R.string.health_not_running,
        HealthStatus.FGS_TYPE_MISSING to R.string.health_fgs_type_missing,
        HealthStatus.FGS_PERMISSION_MISSING to R.string.health_fgs_permission_missing,
        HealthStatus.SERVICE_START_DENIED to R.string.health_service_start_denied,
        HealthStatus.FOREGROUND_READY to R.string.health_foreground_ready,
        HealthStatus.READ_READY_SHIZUKU to R.string.health_read_ready_shizuku,
        HealthStatus.READ_READY_ADB to R.string.health_read_ready_adb,
        HealthStatus.READ_READY_OVERLAY to R.string.health_read_ready_overlay,
        HealthStatus.DEGRADED to R.string.health_degraded,
        HealthStatus.UNAVAILABLE to R.string.health_unavailable,
        HealthStatus.NEEDS_ACTION to R.string.health_needs_action,
        HealthStatus.FOREGROUND_ONLY to R.string.health_foreground_only,
        HealthStatus.PUBLIC_WRITE_READY to R.string.health_public_write_ready,
        HealthStatus.NOT_PROBED to R.string.health_not_probed,
    )

private fun healthStatusRes(status: HealthStatus): Int = HEALTH_STATUS_RES.getValue(status)

@Composable
private fun HealthTone.color(): Color =
    when (this) {
        HealthTone.GOOD -> MaterialTheme.colorScheme.primary
        HealthTone.NEUTRAL -> MaterialTheme.colorScheme.outline
        HealthTone.WARNING -> MaterialTheme.colorScheme.error
    }
