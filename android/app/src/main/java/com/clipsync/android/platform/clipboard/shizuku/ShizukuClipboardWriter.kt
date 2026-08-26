package com.clipsync.android.platform.clipboard.shizuku

import android.content.Context
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.platform.clipboard.ClipboardWriter

/**
 * 特权直读 privileged write fallback. Register only on
 * [com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator]'s fallback
 * slot — never as the default public writer.
 */
class ShizukuClipboardWriter internal constructor(
    private val runtime: ShizukuRuntime,
) : ClipboardWriter {
    constructor(context: Context) : this(AndroidShizukuRuntime(context))

    override fun probe(): CapabilityState {
        val blocked = diagnose()
        if (blocked != null) {
            return ShizukuErrorCodes.probeReadState(blocked)
        }
        return when (val bind = runtime.bindUserService()) {
            is BindResult.Failed -> ShizukuErrorCodes.probeReadState(bind.errorCode)
            BindResult.Binding -> CapabilityState.DEGRADED
            is BindResult.Bound -> {
                val ping = bind.session.pingHealth()
                if (ping != null) {
                    ShizukuErrorCodes.probeReadState(ping)
                } else {
                    CapabilityState.READY
                }
            }
        }
    }

    override fun writeText(text: String, originEventId: String): ClipboardWriteResult {
        val blocked = diagnose()
        if (blocked != null) {
            return ClipboardWriteResult.Failure(blocked)
        }
        val session = when (val bind = runtime.bindUserService()) {
            is BindResult.Bound -> bind.session
            BindResult.Binding ->
                return ClipboardWriteResult.Failure(ShizukuErrorCodes.USERSERVICE_DEAD)
            is BindResult.Failed -> return ClipboardWriteResult.Failure(bind.errorCode)
        }
        return when (val written = session.writeText(text)) {
            SessionWrite.Success -> ClipboardWriteResult.Success
            is SessionWrite.Failed -> ClipboardWriteResult.Failure(written.errorCode)
        }
    }

    private fun diagnose(): String? {
        if (runtime.isPreV11()) {
            return ShizukuErrorCodes.API_MISMATCH
        }
        return when (runtime.presence()) {
            ShizukuPresence.NOT_INSTALLED -> ShizukuErrorCodes.NOT_INSTALLED
            ShizukuPresence.NOT_RUNNING -> ShizukuErrorCodes.NOT_RUNNING
            ShizukuPresence.RUNNING ->
                if (runtime.isAuthorized()) null else ShizukuErrorCodes.NOT_AUTHORIZED
        }
    }
}
