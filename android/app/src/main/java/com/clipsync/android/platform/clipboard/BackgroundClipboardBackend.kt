package com.clipsync.android.platform.clipboard

interface BackgroundClipboardBackend {
    val mode: ClipboardReadMode

    fun probe(): CapabilityReport

    fun start(onChanged: (ClipboardChange) -> Unit)

    fun stop()

    fun readText(): ClipboardReadResult

    /**
     * Like [readText], but for the capability wizard's one-tap read test (plan §8.3): a route
     * whose read channel binds asynchronously (特权直读's UserService is spawned by the host on
     * demand) may wait, bounded, for that bind to complete before answering. A plain [readText]
     * on a cold channel would return the in-progress bind as a failure and can never verify the
     * route — the chicken-and-egg that leaves the card stuck on PRIV_HOST_USERSERVICE_DEAD.
     * Routes with a synchronous read channel keep the default (identical to [readText]).
     */
    fun readTextForVerification(): ClipboardReadResult = readText()

    fun health(): BackendHealth
}
