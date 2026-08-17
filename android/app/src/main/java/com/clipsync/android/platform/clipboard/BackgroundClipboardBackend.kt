package com.clipsync.android.platform.clipboard

interface BackgroundClipboardBackend {
    val mode: ClipboardReadMode

    fun probe(): CapabilityReport

    fun start(onChanged: (ClipboardChange) -> Unit)

    fun stop()

    fun readText(): ClipboardReadResult

    fun health(): BackendHealth
}
