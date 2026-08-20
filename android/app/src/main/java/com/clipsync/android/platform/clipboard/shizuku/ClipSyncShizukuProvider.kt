package com.clipsync.android.platform.clipboard.shizuku

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.clipsync.android.platform.clipboard.shizuku.host.PrivilegedHostConstants
import com.clipsync.android.platform.clipboard.shizuku.host.UserServiceAttachGate
import moe.shizuku.api.BinderContainer
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.ShizukuProvider

/**
 * Receives the privileged-host binder (`sendBinder`) and the UserService
 * binder (`sendUserService`). The second method is implemented in-process
 * so the built-in host does not need a separate manager package.
 */
class ClipSyncShizukuProvider : ShizukuProvider() {
    override fun onCreate(): Boolean {
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (extras == null) {
            return null
        }
        extras.classLoader = BinderContainer::class.java.classLoader
        if (method == PrivilegedHostConstants.METHOD_SEND_BINDER) {
            deliverReplacementHostBinder(extras)
            return super.call(method, arg, extras)
        }
        if (method != PrivilegedHostConstants.METHOD_SEND_USER_SERVICE) {
            return super.call(method, arg, extras)
        }
        val token = extras.getString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN) ?: return null
        @Suppress("DEPRECATION")
        val binder = extras.getParcelable<BinderContainer>(
            PrivilegedHostConstants.EXTRA_BINDER,
        )?.binder ?: return null
        val host = UserServiceAttachGate().attach(binder, token) ?: return null
        val reply = Bundle()
        reply.putParcelable(PrivilegedHostConstants.EXTRA_BINDER, BinderContainer(host))
        return reply
    }

    /**
     * Official [ShizukuProvider] ignores `sendBinder` while a binder is
     * cached. Drop only a *different* unauthorized living binder so
     * super.call can attach. Same-binder resends and granted sessions
     * must stay up: [Shizuku.onBinderReceived](null) fires dead listeners
     * and the host resends the same object every second.
     */
    private fun deliverReplacementHostBinder(extras: Bundle) {
        @Suppress("DEPRECATION")
        val incoming = extras.getParcelable<BinderContainer>(
            PrivilegedHostConstants.EXTRA_BINDER,
        )?.binder
        val current = Shizuku.getBinder()
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
        val tearDown = HostBinderReplacePolicy.shouldTearDownForIncoming(
            incomingAlive = incoming != null && incoming.pingBinder(),
            currentAlive = current != null && current.pingBinder(),
            incomingIsCurrent = incoming != null && current != null && incoming == current,
            granted = granted,
            forceReattach = extras.getBoolean(PrivilegedHostConstants.EXTRA_FORCE_REATTACH),
        )
        if (!tearDown) {
            return
        }
        val pkg = context?.packageName ?: PrivilegedHostConstants.PACKAGE_NAME
        Log.i(TAG, "replace unauthorized host binder")
        Shizuku.onBinderReceived(null, pkg)
    }

    companion object {
        private const val TAG = "ClipSyncShizuku"
    }
}
