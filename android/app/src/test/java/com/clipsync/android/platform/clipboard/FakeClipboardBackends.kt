package com.clipsync.android.platform.clipboard

class FakeBackgroundClipboardBackend(
    override val mode: ClipboardReadMode,
    var report: CapabilityReport = capabilityReport(mode, CapabilityState.READY),
    var readResult: ClipboardReadResult = ClipboardReadResult.Empty,
    var backendHealth: BackendHealth = BackendHealth(
        state = BackendHealthState.HEALTHY,
        checkedAtEpochMillis = 1L,
    ),
    private val callLog: MutableList<String> = mutableListOf(),
) : BackgroundClipboardBackend {
    private var callback: ((ClipboardChange) -> Unit)? = null

    override fun probe(): CapabilityReport {
        callLog += "$mode.probe"
        return report
    }

    override fun start(onChanged: (ClipboardChange) -> Unit) {
        callLog += "$mode.start"
        callback = onChanged
    }

    override fun stop() {
        callLog += "$mode.stop"
        callback = null
    }

    override fun readText(): ClipboardReadResult {
        callLog += "$mode.read"
        return readResult
    }

    override fun health(): BackendHealth {
        callLog += "$mode.health"
        return backendHealth
    }

    fun emit(text: String, hash: String, observedAtEpochMillis: Long = 1L) {
        callback?.invoke(
            ClipboardChange(
                text = text,
                contentHash = hash,
                observedAtEpochMillis = observedAtEpochMillis,
            ),
        )
    }

    companion object {
        fun capabilityReport(
            mode: ClipboardReadMode,
            state: CapabilityState,
            errorCode: String? = null,
        ) = CapabilityReport(
            readMode = mode,
            readState = state,
            writeState = CapabilityState.UNKNOWN,
            systemVersion = "test",
            errorCode = errorCode,
        )
    }
}

class FakeClipboardWriter(
    var state: CapabilityState = CapabilityState.READY,
    private val results: ArrayDeque<ClipboardWriteResult> = ArrayDeque(),
) : ClipboardWriter {
    val writes = mutableListOf<WriteCall>()

    override fun probe(): CapabilityState = state

    override fun writeText(text: String, originEventId: String): ClipboardWriteResult {
        writes += WriteCall(text, originEventId)
        return if (results.isEmpty()) ClipboardWriteResult.Success else results.removeFirst()
    }

    fun enqueue(result: ClipboardWriteResult) {
        results.addLast(result)
    }

    data class WriteCall(val text: String, val originEventId: String)
}
