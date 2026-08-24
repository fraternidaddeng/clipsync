package com.clipsync.android.platform.clipboard

import com.clipsync.android.pairing.KeyValueStore

/**
 * Persists the user's clipboard capability choices and the last verified write probe (plan
 * §2.1 `ClipboardCapabilityStore`). Only modes, states and stable error codes are stored —
 * never clipboard content.
 */
class ClipboardCapabilityStore(private val keyValues: KeyValueStore) {
    fun preferredReadMode(): ClipboardReadMode =
        keyValues.read(KEY_PREFERRED_READ_MODE)
            ?.let { stored -> ClipboardReadMode.entries.firstOrNull { it.name == stored } }
            ?: ClipboardReadMode.SHIZUKU_EVENT

    fun setPreferredReadMode(mode: ClipboardReadMode) {
        keyValues.write(mapOf(KEY_PREFERRED_READ_MODE to mode.name))
    }

    fun autoFallbackAllowed(): Boolean =
        keyValues.read(KEY_AUTO_FALLBACK)?.toBooleanStrictOrNull() ?: true

    fun setAutoFallbackAllowed(allowed: Boolean) {
        keyValues.write(mapOf(KEY_AUTO_FALLBACK to allowed.toString()))
    }

    /**
     * The public-write capability as last verified by a real write test; UNKNOWN until the
     * user runs one. Probing must not fake READY from "the API exists" (plan §0.1.1).
     */
    fun publicWriteState(): CapabilityState =
        keyValues.read(KEY_PUBLIC_WRITE_STATE)
            ?.let { stored -> CapabilityState.entries.firstOrNull { it.name == stored } }
            ?: CapabilityState.UNKNOWN

    fun publicWriteErrorCode(): String? = keyValues.read(KEY_PUBLIC_WRITE_ERROR)

    fun lastWriteTestAtMs(): Long? = keyValues.read(KEY_LAST_WRITE_TEST_AT)?.toLongOrNull()

    fun recordWriteTest(state: CapabilityState, errorCode: String?, atMs: Long) {
        keyValues.write(
            mapOf(
                KEY_PUBLIC_WRITE_STATE to state.name,
                KEY_PUBLIC_WRITE_ERROR to errorCode,
                KEY_LAST_WRITE_TEST_AT to atMs.toString(),
            ),
        )
    }

    private companion object {
        const val KEY_PREFERRED_READ_MODE = "capability.preferred_read_mode"
        const val KEY_AUTO_FALLBACK = "capability.auto_fallback"
        const val KEY_PUBLIC_WRITE_STATE = "capability.public_write_state"
        const val KEY_PUBLIC_WRITE_ERROR = "capability.public_write_error"
        const val KEY_LAST_WRITE_TEST_AT = "capability.last_write_test_at_ms"
    }
}
