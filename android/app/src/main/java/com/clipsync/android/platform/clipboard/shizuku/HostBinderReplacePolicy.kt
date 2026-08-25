package com.clipsync.android.platform.clipboard.shizuku

/**
 * Whether [ClipSyncShizukuProvider] should tear down a living host binder
 * so a later `sendBinder` can attach.
 *
 * Same-binder resends must not drop: [rikka.shizuku.Shizuku.onBinderReceived]
 * with null fires dead listeners and the host resends the same object every
 * second until attach succeeds.
 */
internal object HostBinderReplacePolicy {
    fun shouldTearDownForIncoming(
        incomingAlive: Boolean,
        currentAlive: Boolean,
        incomingIsCurrent: Boolean,
        granted: Boolean,
        forceReattach: Boolean = false,
    ): Boolean {
        if (!incomingAlive || !currentAlive) {
            return false
        }
        if (granted) {
            return false
        }
        if (forceReattach) {
            return true
        }
        return !incomingIsCurrent
    }
}
