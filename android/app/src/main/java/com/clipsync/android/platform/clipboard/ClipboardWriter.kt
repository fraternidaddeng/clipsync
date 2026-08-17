package com.clipsync.android.platform.clipboard

interface ClipboardWriter {
    fun probe(): CapabilityState

    fun writeText(text: String, originEventId: String): ClipboardWriteResult
}

typealias PublicClipboardWriter = ClipboardWriter
