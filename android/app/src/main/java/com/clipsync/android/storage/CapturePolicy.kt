package com.clipsync.android.storage

/**
 * Unified local-capture policy. Pure and JVM-testable; [ClipRepository.captureLocalText]
 * is the single enforcement point so every backend is filtered before outbox/network.
 *
 * Order: paused/private → source blacklist → UTF-8 size.
 * Inbound [ClipRepository.ingestRemoteClip] does not consult this engine.
 */
data class PolicySettings(
    val paused: Boolean = false,
    val privateMode: Boolean = false,
    val blacklistEnabled: Boolean = true,
    val userBlacklist: Set<String> = emptySet(),
    val imageSyncEnabled: Boolean = false,
)

sealed class PolicyDecision {
    data object Allow : PolicyDecision()

    data class Reject(val reason: CaptureRejectReason) : PolicyDecision()
}

object CapturePolicy {
    const val SETTING_BLACKLIST_ENABLED = "capture_blacklist_enabled"
    const val SETTING_BLACKLIST_EXTRA = "capture_blacklist_extra"

    private const val KEY_PAUSED = "is_paused"
    private const val KEY_PRIVATE = "is_private_mode"
    private const val KEY_IMAGE_SYNC = SETTING_IMAGE_SYNC_ENABLED

    /** Internal capture tags — never treated as Android package names. */
    val INTERNAL_SOURCE_TAGS: Set<String> = setOf(
        "share",
        "shizuku",
        "tile",
        "qs_tile",
    ) + setOf("adb", "overlay", "foreground")

    /**
     * Conservative built-in denylist of password managers and authenticator apps.
     * Exact, case-sensitive package match only. Users can add banks via extra.
     */
    val BUILTIN_BLOCKED_PACKAGES: Set<String> = setOf(
        "com.x8bit.bitwarden", // Bitwarden password vault
        "com.lastpass.lpandroid", // LastPass password vault
        "com.onepassword.android", // 1Password password vault
        "keepass2android.keepass2android", // KeePass2Android password database
        "keepass2android.keepass2android_offline", // KeePass2Android Offline database
        "com.dashlane", // Dashlane password vault
        "com.kunzisoft.keepass.free", // KeePass DX password database
        "me.proton.pass.android", // Proton Pass password vault
        "com.google.android.apps.authenticator2", // Google Authenticator OTP secrets
        "com.azure.authenticator", // Microsoft Authenticator OTP / passkeys
        "com.authy.authy", // Authy OTP secrets
        "com.samsung.android.samsungpass", // Samsung Pass credential store
    )

    fun parseUserBlacklist(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) {
            return emptySet()
        }
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && ' ' !in it }
            .toSet()
    }

    fun loadSettings(
        pausedRaw: String?,
        privateModeRaw: String?,
        blacklistEnabledRaw: String?,
        extraRaw: String?,
        imageSyncRaw: String? = null,
    ): PolicySettings =
        PolicySettings(
            paused = parseFlag(pausedRaw),
            privateMode = parseFlag(privateModeRaw),
            blacklistEnabled = parseFlag(blacklistEnabledRaw, default = true),
            userBlacklist = parseUserBlacklist(extraRaw),
            imageSyncEnabled = parseFlag(imageSyncRaw, default = false),
        )

    suspend fun load(getSetting: suspend (String) -> String?): PolicySettings =
        loadSettings(
            pausedRaw = getSetting(KEY_PAUSED),
            privateModeRaw = getSetting(KEY_PRIVATE),
            blacklistEnabledRaw = getSetting(SETTING_BLACKLIST_ENABLED),
            extraRaw = getSetting(SETTING_BLACKLIST_EXTRA),
            imageSyncRaw = getSetting(KEY_IMAGE_SYNC),
        )

    fun evaluate(
        sourceApp: String?,
        utf8Bytes: Int,
        settings: PolicySettings,
    ): PolicyDecision {
        if (settings.paused || settings.privateMode) {
            return PolicyDecision.Reject(CaptureRejectReason.POLICY_PAUSED)
        }
        if (isSourceBlocked(sourceApp, settings)) {
            return PolicyDecision.Reject(CaptureRejectReason.BLOCKED_SOURCE)
        }
        if (utf8Bytes > MAX_CLIP_UTF8_BYTES) {
            return PolicyDecision.Reject(CaptureRejectReason.TOO_LARGE)
        }
        return PolicyDecision.Allow
    }

    fun evaluateImage(
        sourceApp: String?,
        encodedBytes: Int,
        settings: PolicySettings,
    ): PolicyDecision {
        if (settings.paused || settings.privateMode) {
            return PolicyDecision.Reject(CaptureRejectReason.POLICY_PAUSED)
        }
        if (isSourceBlocked(sourceApp, settings)) {
            return PolicyDecision.Reject(CaptureRejectReason.BLOCKED_SOURCE)
        }
        if (!settings.imageSyncEnabled) {
            return PolicyDecision.Reject(CaptureRejectReason.UNSUPPORTED_MEDIA)
        }
        if (encodedBytes > com.clipsync.android.media.MediaLimits.MAX_ENCODED_BYTES) {
            return PolicyDecision.Reject(CaptureRejectReason.TOO_LARGE)
        }
        return PolicyDecision.Allow
    }

    private fun isSourceBlocked(sourceApp: String?, settings: PolicySettings): Boolean {
        if (!settings.blacklistEnabled) {
            return false
        }
        val source = sourceApp?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (source in INTERNAL_SOURCE_TAGS) {
            return false
        }
        return source in BUILTIN_BLOCKED_PACKAGES || source in settings.userBlacklist
    }

    private fun parseFlag(value: String?, default: Boolean = false): Boolean {
        if (value == null) {
            return default
        }
        return value.equals("true", ignoreCase = true)
    }
}
