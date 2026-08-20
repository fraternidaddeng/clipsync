package com.clipsync.android.platform.clipboard.shizuku.host

/**
 * Whether a UserService slot that hit [PrivilegedHostConstants.USER_SERVICE_START_TIMEOUT_MS]
 * should be torn down. Recheck both flags under the same lock as attach.
 */
internal object UserServiceStartTimeoutPolicy {
    fun shouldDestroy(starting: Boolean, binderAttached: Boolean): Boolean =
        starting && !binderAttached
}
