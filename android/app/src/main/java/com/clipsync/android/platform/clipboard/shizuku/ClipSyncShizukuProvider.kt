package com.clipsync.android.platform.clipboard.shizuku

import android.os.Bundle
import com.clipsync.android.platform.clipboard.shizuku.host.PrivilegedHostConstants
import com.clipsync.android.platform.clipboard.shizuku.host.UserServiceAttachGate
import moe.shizuku.api.BinderContainer
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.ShizukuProvider

/**
 * Receives the privileged-host binder (`sendBinder`) and the UserService
 * binder (`sendUserService`). The second method is implemented by the
 * official manager; this provider does it in-process so the built-in host
 * does not need `moe.shizuku.privileged.api`.
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
        if (method != PrivilegedHostConstants.METHOD_SEND_USER_SERVICE) {
            return super.call(method, arg, extras)
        }
        extras.classLoader = BinderContainer::class.java.classLoader
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
}
