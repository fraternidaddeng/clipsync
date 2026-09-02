package com.clipsync.android.update

/**
 * Product version used by the in-app updater. Matches the release-tag scheme
 * documented in `scripts/package-android.ps1`: `X.Y.Z` or `X.Y.Z-rc.N`, with
 * an optional leading `v` and optional `+metadata` suffix.
 *
 * Rank is `major×1_000_000 + minor×10_000 + patch×100 + (N for rc.N, 99 for
 * final)` so every rc sorts below its final release and above the previous
 * patch — the same numbers a tagged APK gets as `versionCode`.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preReleaseOffset: Int,
) {
    val isFinal: Boolean get() = preReleaseOffset == FINAL_OFFSET

    val rank: Int
        get() = major * MAJOR_WEIGHT + minor * MINOR_WEIGHT + patch * PATCH_WEIGHT + preReleaseOffset

    fun toDisplayString(): String =
        if (isFinal) {
            "$major.$minor.$patch"
        } else {
            "$major.$minor.$patch-rc.$preReleaseOffset"
        }

    companion object {
        const val FINAL_OFFSET = 99
        private const val MAJOR_WEIGHT = 1_000_000
        private const val MINOR_WEIGHT = 10_000
        private const val PATCH_WEIGHT = 100
        private const val MAJOR_MAX = 2099
        private const val COMPONENT_MAX = 99
        private const val RC_MIN = 1
        private const val RC_MAX = 98
        private const val GROUP_MAJOR = 1
        private const val GROUP_MINOR = 2
        private const val GROUP_PATCH = 3
        private const val GROUP_RC = 4

        private val PATTERN =
            Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?(?:\+.*)?$""", RegexOption.IGNORE_CASE)

        @Suppress("ReturnCount")
        fun parseOrNull(raw: String?): AppVersion? {
            val match = PATTERN.matchEntire(raw?.trim().orEmpty()) ?: return null
            val major = match.groupValues[GROUP_MAJOR].toIntOrNull() ?: return null
            val minor = match.groupValues[GROUP_MINOR].toIntOrNull() ?: return null
            val patch = match.groupValues[GROUP_PATCH].toIntOrNull() ?: return null
            if (major > MAJOR_MAX || minor > COMPONENT_MAX || patch > COMPONENT_MAX) {
                return null
            }
            val rcGroup = match.groupValues[GROUP_RC]
            val offset =
                if (rcGroup.isEmpty()) {
                    FINAL_OFFSET
                } else {
                    val rc = rcGroup.toIntOrNull() ?: return null
                    if (rc !in RC_MIN..RC_MAX) {
                        return null
                    }
                    rc
                }
            return AppVersion(major, minor, patch, offset)
        }

        /**
         * Unparseable values lose to parseable ones so a broken local stamp still
         * offers a real GitHub release.
         */
        fun compare(
            left: String?,
            right: String?,
        ): Int {
            val leftVersion = parseOrNull(left)
            val rightVersion = parseOrNull(right)
            return when {
                leftVersion == null && rightVersion == null ->
                    (left ?: "").compareTo(right ?: "", ignoreCase = true)
                leftVersion == null -> -1
                rightVersion == null -> 1
                else -> leftVersion.rank.compareTo(rightVersion.rank)
            }
        }
    }
}
