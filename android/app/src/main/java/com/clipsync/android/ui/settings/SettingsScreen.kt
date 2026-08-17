package com.clipsync.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.clipsync.android.R
import com.clipsync.android.ui.HealthScreenState

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        SettingSwitch(
            title = stringResource(R.string.settings_pause),
            subtitle = stringResource(R.string.settings_pause_hint),
            checked = state.paused,
            onCheckedChange = viewModel::setPaused,
        )
        SettingSwitch(
            title = stringResource(R.string.settings_private),
            subtitle = stringResource(R.string.settings_private_hint),
            checked = state.privateMode,
            onCheckedChange = viewModel::setPrivateMode,
        )
        SettingSwitch(
            title = stringResource(R.string.settings_auto_apply),
            subtitle = stringResource(R.string.settings_auto_apply_hint),
            checked = state.autoApplyRemote,
            onCheckedChange = viewModel::setAutoApplyRemote,
        )
        SettingSwitch(
            title = stringResource(R.string.settings_background_sync),
            subtitle = stringResource(R.string.settings_background_sync_hint),
            checked = state.backgroundSync,
            onCheckedChange = viewModel::setBackgroundSync,
        )
        SettingSwitch(
            title = stringResource(R.string.settings_boot_recovery),
            subtitle = stringResource(R.string.settings_boot_recovery_hint),
            checked = state.bootRecoveryEnabled,
            onCheckedChange = viewModel::setBootRecoveryEnabled,
        )
        val visibilityNote = state.notificationVisibilityNote
        if (visibilityNote != null) {
            Text(
                text = visibilityNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.settings_capabilities),
            style = MaterialTheme.typography.titleMedium,
        )
        CapabilityStatusCards(
            state = HealthScreenState(
                network = state.network,
                service = state.service,
                read = state.read,
                write = state.write,
                pairedDeviceCount = state.pairedDeviceCount,
            ),
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
