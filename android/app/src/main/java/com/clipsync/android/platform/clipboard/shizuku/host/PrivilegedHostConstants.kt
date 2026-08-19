package com.clipsync.android.platform.clipboard.shizuku.host

/**
 * Names for ClipSync's in-APK privileged host. Process / class / file names
 * are ClipSync-owned. The binder extra key is the Shizuku-API client protocol
 * string required by `rikka.shizuku.ShizukuProvider`; it is not a package id.
 */
internal object PrivilegedHostConstants {
    const val PACKAGE_NAME = "com.clipsync.android"
    const val HOST_PROCESS_NAME = "clipsync_priv_server"
    const val HOST_MAIN_CLASS =
        "com.clipsync.android.platform.clipboard.shizuku.host.PrivilegedHostService"
    const val USER_SERVICE_STARTER_CLASS =
        "com.clipsync.android.platform.clipboard.shizuku.host.PrivilegedUserServiceStarter"
    const val PROVIDER_AUTHORITY = "com.clipsync.android.shizuku"
    const val SCRIPT_FILE_NAME = "start.sh"
    const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
    const val METHOD_SEND_BINDER = "sendBinder"
    const val METHOD_SEND_USER_SERVICE = "sendUserService"
    const val SHELL_PACKAGE = "com.android.shell"
    const val SERVER_VERSION = 13
    const val SERVER_PATCH_VERSION = 6
    const val USER_SERVICE_DESTROY = 16777115
    const val USER_SERVICE_START_TIMEOUT_MS = 30_000L
    const val BINDER_RESEND_FAST_MS = 1_000L
    const val BINDER_RESEND_SLOW_MS = 10_000L
    const val BINDER_RESEND_FAST_TICKS = 30
    const val SEND_USER_SERVICE_TIMEOUT_SEC = 5L
    const val EXIT_FATAL_UID = 6
    const val EXIT_FATAL_APK = 7
}
