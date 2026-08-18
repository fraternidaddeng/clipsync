@file:Suppress("ktlint:standard:function-naming")

package com.clipsync.android.ui.wizard

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.clipsync.android.R
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.SelfTestKind
import com.clipsync.android.platform.clipboard.SelfTestResult

@Composable
fun WizardScreen(
    viewModel: WizardViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.wizard_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.wizard_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LiveIndicatorRow(state.indicators)
        Text(
            text = stringResource(R.string.wizard_manual_fallback),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.wizard_steps_title),
            style = MaterialTheme.typography.titleMedium,
        )
        state.steps.forEach { step ->
            CapabilityStepCard(
                step = step,
                onAction = {
                    viewModel.onStepAction(step.id)
                    when (step.actionKind) {
                        WizardActionKind.REQUEST_RUNTIME_PERMISSION -> {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.refresh()
                            }
                        }
                        WizardActionKind.OPEN_SYSTEM_SETTINGS ->
                            runCatching {
                                context.startActivity(systemSettingsIntent(step.id, context.packageName))
                            }
                        WizardActionKind.RECHECK_ADB -> viewModel.refresh()
                        WizardActionKind.OPEN_SHIZUKU ->
                            runCatching { context.startActivity(shizukuIntent(context)) }
                    }
                },
                onSkip = { viewModel.skip(step.id) },
            )
        }
        Text(
            text = stringResource(R.string.wizard_choices_title),
            style = MaterialTheme.typography.titleMedium,
        )
        ChoicesCard(state = state, viewModel = viewModel)
        val selfTest by viewModel.selfTestState.collectAsState()
        SelfTestCard(
            state = selfTest,
            onReadTest = viewModel::runBackgroundReadTest,
            onWriteTest = viewModel::runBackgroundWriteTest,
        )
        Button(
            onClick = viewModel::finish,
            enabled = state.canFinish,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.wizard_finish))
        }
    }
}

@Composable
private fun SelfTestCard(
    state: SelfTestUiState,
    onReadTest: () -> Unit,
    onWriteTest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.wizard_selftest_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.wizard_selftest_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onReadTest, enabled = !state.running) {
                    Text(stringResource(R.string.wizard_selftest_read))
                }
                Button(onClick = onWriteTest, enabled = !state.running) {
                    Text(stringResource(R.string.wizard_selftest_write))
                }
            }
            if (state.running) {
                Text(
                    text = stringResource(R.string.wizard_selftest_running),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.read?.let { SelfTestResultLine(it) }
            state.write?.let { SelfTestResultLine(it) }
        }
    }
}

@Composable
private fun SelfTestResultLine(result: SelfTestResult) {
    val label = stringResource(
        if (result.kind == SelfTestKind.BACKGROUND_READ) {
            R.string.wizard_selftest_read
        } else {
            R.string.wizard_selftest_write
        },
    )
    val detail = result.readMode?.name ?: result.writerKind?.name ?: "-"
    val text = if (result.passed) {
        stringResource(R.string.wizard_selftest_pass, label, detail) +
            (result.errorCode?.let { " · $it" } ?: "")
    } else {
        stringResource(R.string.wizard_selftest_fail, label, result.errorCode ?: "UNKNOWN")
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (result.passed) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
    )
}

@Composable
private fun LiveIndicatorRow(indicators: LiveIndicators) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.wizard_indicators_title),
            style = MaterialTheme.typography.titleMedium,
        )
        IndicatorCard(stringResource(R.string.capability_network), indicators.network)
        IndicatorCard(stringResource(R.string.capability_service), indicators.service)
        IndicatorCard(
            title = stringResource(R.string.wizard_indicator_read),
            state = indicators.backgroundRead,
            checkedAtEpochMillis = indicators.backgroundReadCheckedAtEpochMillis,
        )
        IndicatorCard(
            title = stringResource(R.string.wizard_indicator_write),
            state = indicators.backgroundWrite,
            checkedAtEpochMillis = indicators.backgroundWriteCheckedAtEpochMillis,
        )
    }
}

@Suppress("FunctionNaming") // Compose requires PascalCase identifiers.
@Composable
private fun IndicatorCard(
    title: String,
    state: CapabilityState,
    checkedAtEpochMillis: Long? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = capabilityLabel(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                checkedAtEpochMillis?.let { checkedAt ->
                    Text(
                        text = stringResource(
                            R.string.capability_last_check,
                            formatLastCheckClock(checkedAt),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(state.tone(), CircleShape),
            )
        }
    }
}

@Composable
private fun CapabilityStepCard(
    step: WizardStepStatus,
    onAction: () -> Unit,
    onSkip: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stepTitle(step.id), style = MaterialTheme.typography.titleSmall)
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(step.state.tone(), CircleShape),
                )
            }
            Text(
                text = capabilityLabel(step.state) + if (step.skipped) {
                    " · " + stringResource(R.string.wizard_skipped)
                } else {
                    ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.wizard_purpose, stepPurpose(step.id)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.wizard_risk, stepRisk(step.id)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.wizard_skip_consequence, stepSkip(step.id)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (step.id == WizardStepId.READ_LOGS) {
                Text(
                    text = stringResource(R.string.wizard_read_logs_adb_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAction) { Text(stepActionLabel(step.id)) }
                if (!step.completed || step.skipped) {
                    OutlinedButton(onClick = onSkip, enabled = !step.skipped) {
                        Text(stringResource(R.string.wizard_skip))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoicesCard(state: WizardUiState, viewModel: WizardViewModel) {
    val choices = state.choices
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.wizard_preferred_mode),
                style = MaterialTheme.typography.bodyLarge,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ClipboardReadMode.entries.forEach { mode ->
                    FilterChip(
                        selected = choices.preferredReadMode == mode,
                        onClick = { viewModel.setPreferredReadMode(mode) },
                        label = { Text(readModeLabel(mode)) },
                    )
                }
            }
            ChoiceSwitch(
                title = stringResource(R.string.wizard_auto_fallback),
                subtitle = stringResource(R.string.wizard_auto_fallback_hint),
                checked = choices.autoFallbackAllowed,
                onCheckedChange = viewModel::setAutoFallbackAllowed,
            )
            ChoiceSwitch(
                title = stringResource(R.string.wizard_overlay_consent),
                subtitle = stringResource(R.string.wizard_overlay_consent_hint),
                checked = choices.overlayConsented,
                onCheckedChange = viewModel::setOverlayConsented,
            )
            Text(
                text = stringResource(R.string.wizard_overlay_background_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.wizard_polling_interval, choices.pollingIntervalMs),
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = choices.pollingIntervalMs.toFloat(),
                onValueChange = { viewModel.setPollingIntervalMs(it.toInt()) },
                valueRange = WizardChoices.MIN_POLLING_INTERVAL_MS.toFloat()..
                    WizardChoices.MAX_POLLING_INTERVAL_MS.toFloat(),
                steps = 5,
            )
            ChoiceSwitch(
                title = stringResource(R.string.wizard_auto_upload),
                subtitle = stringResource(R.string.wizard_auto_upload_hint),
                checked = choices.backgroundAutoUpload,
                onCheckedChange = viewModel::setBackgroundAutoUpload,
            )
            ChoiceSwitch(
                title = stringResource(R.string.wizard_auto_apply),
                subtitle = stringResource(R.string.wizard_auto_apply_hint),
                checked = choices.backgroundAutoApply,
                onCheckedChange = viewModel::setBackgroundAutoApply,
            )
            Text(
                text = stringResource(R.string.wizard_write_default),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChoiceSwitch(
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

@Composable
private fun CapabilityState.tone(): Color = when (this) {
    CapabilityState.READY -> MaterialTheme.colorScheme.primary
    CapabilityState.DEGRADED,
    CapabilityState.NEEDS_USER_ACTION,
    CapabilityState.UNAVAILABLE,
    -> MaterialTheme.colorScheme.error
    CapabilityState.UNKNOWN -> MaterialTheme.colorScheme.outline
}

@Composable
private fun capabilityLabel(state: CapabilityState): String = when (state) {
    CapabilityState.READY -> stringResource(R.string.wizard_state_ready)
    CapabilityState.DEGRADED -> stringResource(R.string.wizard_state_degraded)
    CapabilityState.UNAVAILABLE -> stringResource(R.string.wizard_state_unavailable)
    CapabilityState.NEEDS_USER_ACTION -> stringResource(R.string.wizard_state_needs_action)
    CapabilityState.UNKNOWN -> stringResource(R.string.wizard_state_unknown)
}

@Composable
private fun stepTitle(id: WizardStepId): String = stringResource(
    when (id) {
        WizardStepId.NOTIFICATIONS -> R.string.wizard_step_notifications
        WizardStepId.FOREGROUND_SERVICE -> R.string.wizard_step_fgs
        WizardStepId.IGNORE_BATTERY -> R.string.wizard_step_battery
        WizardStepId.OVERLAY -> R.string.wizard_step_overlay
        WizardStepId.READ_LOGS -> R.string.wizard_step_read_logs
        WizardStepId.SHIZUKU_BINDER -> R.string.wizard_step_shizuku_binder
        WizardStepId.SHIZUKU_AUTH -> R.string.wizard_step_shizuku_auth
    },
)

@Composable
private fun stepPurpose(id: WizardStepId): String = stringResource(
    when (id) {
        WizardStepId.NOTIFICATIONS -> R.string.wizard_purpose_notifications
        WizardStepId.FOREGROUND_SERVICE -> R.string.wizard_purpose_fgs
        WizardStepId.IGNORE_BATTERY -> R.string.wizard_purpose_battery
        WizardStepId.OVERLAY -> R.string.wizard_purpose_overlay
        WizardStepId.READ_LOGS -> R.string.wizard_purpose_read_logs
        WizardStepId.SHIZUKU_BINDER -> R.string.wizard_purpose_shizuku_binder
        WizardStepId.SHIZUKU_AUTH -> R.string.wizard_purpose_shizuku_auth
    },
)

@Composable
private fun stepRisk(id: WizardStepId): String = stringResource(
    when (id) {
        WizardStepId.NOTIFICATIONS -> R.string.wizard_risk_notifications
        WizardStepId.FOREGROUND_SERVICE -> R.string.wizard_risk_fgs
        WizardStepId.IGNORE_BATTERY -> R.string.wizard_risk_battery
        WizardStepId.OVERLAY -> R.string.wizard_risk_overlay
        WizardStepId.READ_LOGS -> R.string.wizard_risk_read_logs
        WizardStepId.SHIZUKU_BINDER -> R.string.wizard_risk_shizuku_binder
        WizardStepId.SHIZUKU_AUTH -> R.string.wizard_risk_shizuku_auth
    },
)

@Composable
private fun stepSkip(id: WizardStepId): String = stringResource(
    when (id) {
        WizardStepId.NOTIFICATIONS -> R.string.wizard_skip_notifications
        WizardStepId.FOREGROUND_SERVICE -> R.string.wizard_skip_fgs
        WizardStepId.IGNORE_BATTERY -> R.string.wizard_skip_battery
        WizardStepId.OVERLAY -> R.string.wizard_skip_overlay
        WizardStepId.READ_LOGS -> R.string.wizard_skip_read_logs
        WizardStepId.SHIZUKU_BINDER -> R.string.wizard_skip_shizuku_binder
        WizardStepId.SHIZUKU_AUTH -> R.string.wizard_skip_shizuku_auth
    },
)

@Composable
private fun stepActionLabel(id: WizardStepId): String = stringResource(
    when (id) {
        WizardStepId.NOTIFICATIONS -> R.string.wizard_action_notifications
        WizardStepId.FOREGROUND_SERVICE -> R.string.wizard_action_fgs
        WizardStepId.IGNORE_BATTERY -> R.string.wizard_action_battery
        WizardStepId.OVERLAY -> R.string.wizard_action_overlay
        WizardStepId.READ_LOGS -> R.string.wizard_action_read_logs
        WizardStepId.SHIZUKU_BINDER -> R.string.wizard_action_shizuku_binder
        WizardStepId.SHIZUKU_AUTH -> R.string.wizard_action_shizuku_auth
    },
)

@Composable
private fun readModeLabel(mode: ClipboardReadMode): String = stringResource(
    when (mode) {
        ClipboardReadMode.SHIZUKU_EVENT -> R.string.wizard_mode_shizuku
        ClipboardReadMode.ADB_LOG_OVERLAY -> R.string.wizard_mode_adb
        ClipboardReadMode.OVERLAY_POLLING -> R.string.wizard_mode_overlay
        ClipboardReadMode.FOREGROUND_ONLY -> R.string.wizard_mode_foreground
    },
)

private fun systemSettingsIntent(id: WizardStepId, packageName: String): Intent = when (id) {
    WizardStepId.OVERLAY -> Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName"),
    )
    WizardStepId.IGNORE_BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:$packageName")
    }
}

private fun shizukuIntent(context: android.content.Context): Intent {
    val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
    if (launch != null) {
        return launch
    }
    return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:$SHIZUKU_PACKAGE")
    }
}

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
