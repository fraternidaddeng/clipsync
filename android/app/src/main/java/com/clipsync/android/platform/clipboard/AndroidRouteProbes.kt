package com.clipsync.android.platform.clipboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import rikka.shizuku.Shizuku

/**
 * Real prerequisite probes for the three wizard routes. Every value is re-checked on each
 * probe — a grant observed once is never assumed permanent (plan §5.4): installs, reboots and
 * ROM policy changes can all revoke these silently.
 */
class AndroidRouteProbes(context: Context) : RouteProbes {
    private val appContext = context.applicationContext

    override fun probe(): RoutePrerequisites {
        val shizukuInstalled = isPackageInstalled(SHIZUKU_PACKAGE)
        val shizukuRunning = shizukuInstalled && runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val shizukuAuthorized = shizukuRunning && runCatching {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return RoutePrerequisites(
            shizukuInstalled = shizukuInstalled,
            shizukuRunning = shizukuRunning,
            shizukuAuthorized = shizukuAuthorized,
            readLogsGranted = appContext.checkSelfPermission(Manifest.permission.READ_LOGS) ==
                PackageManager.PERMISSION_GRANTED,
            overlayGranted = Settings.canDrawOverlays(appContext),
            batteryUnrestricted = (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(appContext.packageName),
        )
    }

    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
