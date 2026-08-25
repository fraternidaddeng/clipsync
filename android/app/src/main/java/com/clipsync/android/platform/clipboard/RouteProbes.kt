package com.clipsync.android.platform.clipboard

/**
 * Snapshot of the user-visible prerequisites behind the three background-read routes. Pure
 * data: the Android implementation asks PackageManager/Settings/PowerManager/Shizuku, tests
 * use fixed values. A granted prerequisite is never treated as a verified read path by itself
 * (plan §0.1.2); the backends map these to at most DEGRADED until reads are device-verified.
 */
data class RoutePrerequisites(
    val shizukuInstalled: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuAuthorized: Boolean = false,
    val readLogsGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val batteryUnrestricted: Boolean = false,
)

interface RouteProbes {
    fun probe(): RoutePrerequisites
}
