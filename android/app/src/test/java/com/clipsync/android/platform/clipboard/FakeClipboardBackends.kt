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
    var onStart: () -> Unit = {},
    var onStop: () -> Unit = {},
    var onRead: () -> Unit = {},
) : BackgroundClipboardBackend {
    private var callback: ((ClipboardChange) -> Unit)? = null

    override fun probe(): CapabilityReport {
        callLog += "$mode.probe"
        return report
    }

    override fun start(onChanged: (ClipboardChange) -> Unit) {
        callLog += "$mode.start"
        onStart()
        callback = onChanged
    }

    override fun stop() {
        callLog += "$mode.stop"
        callback = null
        onStop()
    }

    override fun readText(): ClipboardReadResult {
        callLog += "$mode.read"
        onRead()
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
        writes += WriteCall(text = text, originEventId = originEventId)
        return if (results.isEmpty()) ClipboardWriteResult.Success else results.removeFirst()
    }

    override fun writeImage(
        encoded: ByteArray,
        mimeType: String,
        originEventId: String,
    ): ClipboardWriteResult {
        writes += WriteCall(
            originEventId = originEventId,
            imageBytes = encoded.copyOf(),
            mimeType = mimeType,
        )
        return if (results.isEmpty()) ClipboardWriteResult.Success else results.removeFirst()
    }

    fun enqueue(result: ClipboardWriteResult) {
        results.addLast(result)
    }

    data class WriteCall(
        val text: String? = null,
        val originEventId: String,
        val imageBytes: ByteArray? = null,
        val mimeType: String? = null,
    )
}

/** In-memory [com.clipsync.android.pairing.KeyValueStore] for capability persistence tests. */
class InMemoryCapabilityKeyValueStore : com.clipsync.android.pairing.KeyValueStore {
    val map = LinkedHashMap<String, String>()

    override fun read(key: String): String? = map[key]

    override fun write(values: Map<String, String?>) {
        for ((key, value) in values) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }
}
