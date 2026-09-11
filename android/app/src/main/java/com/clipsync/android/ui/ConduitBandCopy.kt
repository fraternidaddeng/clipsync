package com.clipsync.android.ui

/**
 * Status-band copy. The blocked subtitle used to always say "not paired", even when
 * the network segment was already paired and some other segment was beckoning
 * (overlay grant, service off). Pairing state chooses the sentence; the tone
 * still follows the single-beckon rule.
 */
internal enum class ConduitBandTone {
    BLOCKED,
    READY,
    PARTIAL,
}

internal enum class ConduitBandSubtitle {
    BLOCKED_UNPAIRED,
    BLOCKED_PAIRED,
    READY,
    PARTIAL,
}

internal data class ConduitBandCopy(
    val tone: ConduitBandTone,
    val subtitle: ConduitBandSubtitle,
) {
    companion object {
        fun of(
            needsAction: Boolean,
            allReady: Boolean,
            paired: Boolean,
        ): ConduitBandCopy =
            when {
                needsAction ->
                    ConduitBandCopy(
                        ConduitBandTone.BLOCKED,
                        if (paired) {
                            ConduitBandSubtitle.BLOCKED_PAIRED
                        } else {
                            ConduitBandSubtitle.BLOCKED_UNPAIRED
                        },
                    )
                allReady -> ConduitBandCopy(ConduitBandTone.READY, ConduitBandSubtitle.READY)
                else -> ConduitBandCopy(ConduitBandTone.PARTIAL, ConduitBandSubtitle.PARTIAL)
            }
    }
}
