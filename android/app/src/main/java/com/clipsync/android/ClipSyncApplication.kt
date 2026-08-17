package com.clipsync.android

import android.app.Application

/**
 * Named application class. MIUI 14's LoadedApk.makeApplicationInner NPEs when
 * Shizuku starts a UserService process and [ApplicationInfo.className] is null.
 */
class ClipSyncApplication : Application()
