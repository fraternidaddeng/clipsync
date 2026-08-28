package com.clipsync.android.platform.clipboard

/**
 * Route 1 (charter §4.1): privileged event reads through the built-in privileged host's
 * shell binder (「特权直读」).
 *
 * The honest probe stays here: install/running/authorization state with stable error codes.
 * The real event channel — the privileged-host UserService that reflects into `IClipboard` — is
 * supplied as [delegate] (see [com.clipsync.android.platform.clipboard.shizuku.ShizukuClipboardBackend]);
 * this adapter forwards start/stop/read/health to it once the route is chosen. Per plan §8.3 an
 * authorized-but-untested channel reports DEGRADED ("授权但待实测"), never READY: only a
 * device-verified read ([readVerified], persisted by [ClipboardCapabilityStore]) promotes it.
 *
 * A host binder that pings and an authorized app do not prove the read channel is up: the
 * privileged read runs through the UserService child process, which can die on its own (the
 * wireless-debugging / adb shell that launched it drops, the ROM kills it) while the host still
 * answers pings. [lastReadFailureCode] carries the last device read test's failure so a
 * proven-dead channel reports its real state (UNAVAILABLE + `PRIV_HOST_USERSERVICE_DEAD`)
 * instead of the rosy "授权但待实测"; the next passing read test clears it.
 */
class ShizukuClipboardBackend(
    private val probes: RouteProbes,
    private val systemVersion: String,
    private val delegate: BackgroundClipboardBackend? = null,
    private val readVerified: () -> Boolean = { false },
    private val lastReadFailureCode: () -> String? = { null },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : BackgroundClipboardBackend {
    override val mode: ClipboardReadMode = ClipboardReadMode.SHIZUKU_EVENT

    override fun probe(): CapabilityReport {
        val p = probes.probe()
        val (state, errorCode) = when {
            !p.shizukuInstalled -> CapabilityState.UNAVAILABLE to ERROR_CHANNEL_MISSING
            !p.shizukuRunning -> CapabilityState.UNAVAILABLE to ERROR_CHANNEL_OFFLINE
            !p.shizukuAuthorized -> CapabilityState.UNAVAILABLE to ERROR_PERMISSION_DENIED
            readVerified() -> CapabilityState.READY to null
            else -> unverifiedOrProvenDead()
        }
        return CapabilityReport(
            readMode = mode,
            readState = state,
            writeState = CapabilityState.UNKNOWN,
            systemVersion = systemVersion,
            authorizations = listOf(
                ClipboardAuthorization("priv_host_installed", p.shizukuInstalled),
                ClipboardAuthorization("priv_host_running", p.shizukuRunning),
                ClipboardAuthorization("priv_host_authorized", p.shizukuAuthorized),
            ),
            errorCode = errorCode,
        )
    }

    /**
     * Prerequisites are met (host pings, app authorized) but the read is not yet device-verified.
     * If the last read test proved the channel dead, keep reporting that failure honestly
     * (UNAVAILABLE with its code) rather than the optimistic "授权但待实测"; otherwise this is a
     * never-tested channel, which is genuinely just awaiting its first verification.
     */
    private fun unverifiedOrProvenDead(): Pair<CapabilityState, String> {
        val failure = lastReadFailureCode()
        return if (failure != null) {
            CapabilityState.UNAVAILABLE to failure
        } else {
            CapabilityState.DEGRADED to ERROR_READ_UNVERIFIED
        }
    }

    override fun start(onChanged: (ClipboardChange) -> Unit) {
        delegate?.start(onChanged)
    }

    override fun stop() {
        delegate?.stop()
    }

    override fun readText(): ClipboardReadResult =
        delegate?.readText() ?: ClipboardReadResult.Failure(ERROR_READ_UNVERIFIED)

    override fun health(): BackendHealth =
        delegate?.health() ?: BackendHealth(
            state = BackendHealthState.STOPPED,
            checkedAtEpochMillis = nowEpochMillis(),
            errorCode = probe().errorCode,
        )

    // Error codes surface in the conduit UI, so they carry the user-facing route
    // name (特权直读 / privileged) rather than the implementation's brand name.
    companion object {
        const val ERROR_CHANNEL_MISSING = "PRIVILEGED_CHANNEL_MISSING"
        const val ERROR_CHANNEL_OFFLINE = "PRIVILEGED_CHANNEL_OFFLINE"
        const val ERROR_PERMISSION_DENIED = "PRIVILEGED_PERMISSION_DENIED"
        const val ERROR_READ_UNVERIFIED = "PRIVILEGED_READ_UNVERIFIED"
    }
}
