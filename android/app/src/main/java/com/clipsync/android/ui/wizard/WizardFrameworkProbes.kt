package com.clipsync.android.ui.wizard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.clipsync.android.platform.clipboard.BackgroundClipboardBackend
import com.clipsync.android.platform.clipboard.BackgroundClipboardBackends
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardReadMode

/**
 * Framework permission probes plus live backend-card bindings. Overlay /
 * READ_LOGS / Shizuku cards use each backend's own [BackgroundClipboardBackend.probe];
 * constructing a backend is not READY.
 */
object WizardFrameworkProbes {
    const val AUTH_SHIZUKU_INSTALLED = "shizuku_installed"
    const val AUTH_SHIZUKU_RUNNING = "shizuku_running"
    const val AUTH_SHIZUKU_AUTHORIZED = "shizuku_authorized"

    fun notifications(context: Context): () -> CapabilityState = {
        if (Build.VERSION.SDK_INT < 33) {
            CapabilityState.READY
        } else if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            CapabilityState.READY
        } else {
            CapabilityState.NEEDS_USER_ACTION
        }
    }

    fun ignoreBattery(context: Context): () -> CapabilityState = {
        val power = context.getSystemService(PowerManager::class.java)
        if (power?.isIgnoringBatteryOptimizations(context.packageName) == true) {
            CapabilityState.READY
        } else {
            CapabilityState.NEEDS_USER_ACTION
        }
    }

    fun overlayPermission(context: Context): () -> CapabilityState = {
        if (Settings.canDrawOverlays(context)) {
            CapabilityState.READY
        } else {
            CapabilityState.NEEDS_USER_ACTION
        }
    }

    fun overlay(backend: BackgroundClipboardBackend?): () -> CapabilityState =
        { backendProbeState(backend) }

    fun readLogs(backend: BackgroundClipboardBackend?): () -> CapabilityState =
        { backendProbeState(backend) }

    fun shizukuBinder(backend: BackgroundClipboardBackend?): () -> CapabilityState = {
        val report = backend?.probe()
        val running = report?.authorizations?.find { it.name == AUTH_SHIZUKU_RUNNING }
        if (running?.granted == true) {
            CapabilityState.READY
        } else {
            CapabilityState.NEEDS_USER_ACTION
        }
    }

    fun shizukuAuth(backend: BackgroundClipboardBackend?): () -> CapabilityState = {
        val report = backend?.probe()
        val authorized = report?.authorizations?.find { it.name == AUTH_SHIZUKU_AUTHORIZED }
        if (authorized?.granted == true) {
            CapabilityState.READY
        } else {
            CapabilityState.NEEDS_USER_ACTION
        }
    }

    fun backgroundRead(
        backends: BackgroundClipboardBackends,
        requestedReadMode: () -> ClipboardReadMode,
    ): () -> CapabilityState = {
        backends.selectedReadState(requestedReadMode())
    }

    fun backgroundWrite(probe: () -> CapabilityState): () -> CapabilityState = probe

    fun bind(
        backends: BackgroundClipboardBackends,
        settings: WizardSettings,
        network: () -> CapabilityState,
        service: () -> CapabilityState,
        backgroundWrite: () -> CapabilityState,
        notifications: () -> CapabilityState,
        foregroundService: () -> CapabilityState,
        ignoreBattery: () -> CapabilityState,
    ): WizardProbes = WizardProbes(
        notifications = notifications,
        foregroundService = foregroundService,
        ignoreBattery = ignoreBattery,
        overlay = overlay(backends.overlayPolling),
        readLogs = readLogs(backends.adbLog),
        shizukuBinder = shizukuBinder(backends.shizuku),
        shizukuAuth = shizukuAuth(backends.shizuku),
        network = network,
        service = service,
        backgroundRead = backgroundRead(backends) { settings.load().preferredReadMode },
        backgroundWrite = backgroundWrite(backgroundWrite),
    )

    private fun backendProbeState(backend: BackgroundClipboardBackend?): CapabilityState =
        backend?.probe()?.readState ?: CapabilityState.NEEDS_USER_ACTION
}
