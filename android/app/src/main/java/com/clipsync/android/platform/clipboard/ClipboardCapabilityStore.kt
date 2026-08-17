package com.clipsync.android.platform.clipboard

import com.clipsync.android.pairing.KeyValueStore

data class ReadCapabilitySnapshot(
    val requestedReadMode: ClipboardReadMode,
    val activeReadMode: ClipboardReadMode?,
    val autoFallbackAllowed: Boolean,
    val lastErrorCode: String?,
    val lastHealthAtEpochMillis: Long?,
    val modeEpoch: Long = 0L,
    val lastReadState: CapabilityState = CapabilityState.UNKNOWN,
)

data class WriteCapabilitySnapshot(
    val writeMode: ClipboardWriteMode,
    val publicLastSuccessAtEpochMillis: Long? = null,
    val publicLastErrorCode: String? = null,
    val fallbackLastSuccessAtEpochMillis: Long? = null,
    val fallbackLastErrorCode: String? = null,
)

interface ClipboardCapabilityStore {
    fun loadRead(): ReadCapabilitySnapshot?

    fun saveRead(snapshot: ReadCapabilitySnapshot)

    fun loadWrite(): WriteCapabilitySnapshot?

    fun saveWrite(snapshot: WriteCapabilitySnapshot)
}

/** Persists read and write capability snapshots on separate keys. Never stores clipboard text. */
class KeyValueClipboardCapabilityStore(
    private val keyValues: KeyValueStore,
) : ClipboardCapabilityStore {
    override fun loadRead(): ReadCapabilitySnapshot? {
        val requested = keyValues.read(KEY_REQUESTED_READ_MODE)?.toEnumOrNull<ClipboardReadMode>()
            ?: return null
        return ReadCapabilitySnapshot(
            requestedReadMode = requested,
            activeReadMode = keyValues.read(KEY_ACTIVE_READ_MODE)?.toEnumOrNull<ClipboardReadMode>(),
            autoFallbackAllowed = keyValues.read(KEY_AUTO_FALLBACK)?.toBooleanStrictOrNull() ?: true,
            lastErrorCode = keyValues.read(KEY_LAST_ERROR),
            lastHealthAtEpochMillis = keyValues.read(KEY_LAST_HEALTH_AT)?.toLongOrNull(),
            modeEpoch = keyValues.read(KEY_MODE_EPOCH)?.toLongOrNull() ?: 0L,
            lastReadState = keyValues.read(KEY_LAST_READ_STATE)?.toEnumOrNull<CapabilityState>()
                ?: CapabilityState.UNKNOWN,
        )
    }

    override fun saveRead(snapshot: ReadCapabilitySnapshot) {
        keyValues.write(
            mapOf(
                KEY_REQUESTED_READ_MODE to snapshot.requestedReadMode.name,
                KEY_ACTIVE_READ_MODE to snapshot.activeReadMode?.name,
                KEY_AUTO_FALLBACK to snapshot.autoFallbackAllowed.toString(),
                KEY_LAST_ERROR to snapshot.lastErrorCode,
                KEY_LAST_HEALTH_AT to snapshot.lastHealthAtEpochMillis?.toString(),
                KEY_MODE_EPOCH to snapshot.modeEpoch.toString(),
                KEY_LAST_READ_STATE to snapshot.lastReadState.name,
            ),
        )
    }

    override fun loadWrite(): WriteCapabilitySnapshot? {
        val writeMode = keyValues.read(KEY_WRITE_MODE)?.toEnumOrNull<ClipboardWriteMode>() ?: return null
        return WriteCapabilitySnapshot(
            writeMode = writeMode,
            publicLastSuccessAtEpochMillis = keyValues.read(KEY_PUBLIC_LAST_SUCCESS)?.toLongOrNull(),
            publicLastErrorCode = keyValues.read(KEY_PUBLIC_LAST_ERROR),
            fallbackLastSuccessAtEpochMillis = keyValues.read(KEY_FALLBACK_LAST_SUCCESS)?.toLongOrNull(),
            fallbackLastErrorCode = keyValues.read(KEY_FALLBACK_LAST_ERROR),
        )
    }

    override fun saveWrite(snapshot: WriteCapabilitySnapshot) {
        keyValues.write(
            mapOf(
                KEY_WRITE_MODE to snapshot.writeMode.name,
                KEY_PUBLIC_LAST_SUCCESS to snapshot.publicLastSuccessAtEpochMillis?.toString(),
                KEY_PUBLIC_LAST_ERROR to snapshot.publicLastErrorCode,
                KEY_FALLBACK_LAST_SUCCESS to snapshot.fallbackLastSuccessAtEpochMillis?.toString(),
                KEY_FALLBACK_LAST_ERROR to snapshot.fallbackLastErrorCode,
            ),
        )
    }

    private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
        runCatching { java.lang.Enum.valueOf(T::class.java, this) }.getOrNull()

    companion object {
        const val PREFIX_READ = "capability.read."
        const val PREFIX_WRITE = "capability.write."

        const val KEY_REQUESTED_READ_MODE = PREFIX_READ + "requested_read_mode"
        const val KEY_ACTIVE_READ_MODE = PREFIX_READ + "active_read_mode"
        const val KEY_AUTO_FALLBACK = PREFIX_READ + "auto_fallback_allowed"
        const val KEY_LAST_ERROR = PREFIX_READ + "last_error_code"
        const val KEY_LAST_HEALTH_AT = PREFIX_READ + "last_health_at"
        const val KEY_MODE_EPOCH = PREFIX_READ + "mode_epoch"
        const val KEY_LAST_READ_STATE = PREFIX_READ + "last_read_state"

        const val KEY_WRITE_MODE = PREFIX_WRITE + "write_mode"
        const val KEY_PUBLIC_LAST_SUCCESS = PREFIX_WRITE + "public.last_success_at"
        const val KEY_PUBLIC_LAST_ERROR = PREFIX_WRITE + "public.last_error_code"
        const val KEY_FALLBACK_LAST_SUCCESS = PREFIX_WRITE + "fallback.last_success_at"
        const val KEY_FALLBACK_LAST_ERROR = PREFIX_WRITE + "fallback.last_error_code"
    }
}
