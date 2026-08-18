package com.clipsync.android.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePolicyTest {
    @Test
    fun `built-in password manager package is blocked`() {
        val settings = PolicySettings()
        for (pkg in CapturePolicy.BUILTIN_BLOCKED_PACKAGES) {
            assertEquals(
                PolicyDecision.Reject(CaptureRejectReason.BLOCKED_SOURCE),
                CapturePolicy.evaluate(pkg, utf8Bytes = 12, settings),
            )
        }
    }

    @Test
    fun `user extra package is blocked when toggle is on`() {
        val settings = PolicySettings(userBlacklist = setOf("com.example.vault"))
        assertEquals(
            PolicyDecision.Reject(CaptureRejectReason.BLOCKED_SOURCE),
            CapturePolicy.evaluate("com.example.vault", utf8Bytes = 8, settings),
        )
    }

    @Test
    fun `disabled toggle allows built-in and user extra packages`() {
        val settings = PolicySettings(
            blacklistEnabled = false,
            userBlacklist = setOf("com.example.vault"),
        )
        val sample = CapturePolicy.BUILTIN_BLOCKED_PACKAGES.first()
        assertEquals(PolicyDecision.Allow, CapturePolicy.evaluate(sample, 8, settings))
        assertEquals(PolicyDecision.Allow, CapturePolicy.evaluate("com.example.vault", 8, settings))
    }

    @Test
    fun `internal source tags are never blocked even when listed in user extra`() {
        val settings = PolicySettings(
            userBlacklist = CapturePolicy.INTERNAL_SOURCE_TAGS,
        )
        for (tag in CapturePolicy.INTERNAL_SOURCE_TAGS) {
            assertEquals(PolicyDecision.Allow, CapturePolicy.evaluate(tag, 8, settings))
        }
    }

    @Test
    fun `null source is allowed`() {
        assertEquals(
            PolicyDecision.Allow,
            CapturePolicy.evaluate(null, utf8Bytes = 8, PolicySettings()),
        )
    }

    @Test
    fun `unknown package is allowed`() {
        assertEquals(
            PolicyDecision.Allow,
            CapturePolicy.evaluate("com.example.notes", utf8Bytes = 8, PolicySettings()),
        )
    }

    @Test
    fun `blacklist match is exact and case sensitive`() {
        val sample = CapturePolicy.BUILTIN_BLOCKED_PACKAGES.first()
        assertEquals(
            PolicyDecision.Allow,
            CapturePolicy.evaluate(sample.uppercase(), utf8Bytes = 8, PolicySettings()),
        )
    }

    @Test
    fun `paused or private mode rejects before blacklist`() {
        val blocked = CapturePolicy.BUILTIN_BLOCKED_PACKAGES.first()
        assertEquals(
            PolicyDecision.Reject(CaptureRejectReason.POLICY_PAUSED),
            CapturePolicy.evaluate(blocked, 8, PolicySettings(paused = true)),
        )
        assertEquals(
            PolicyDecision.Reject(CaptureRejectReason.POLICY_PAUSED),
            CapturePolicy.evaluate(null, 8, PolicySettings(privateMode = true)),
        )
    }

    @Test
    fun `oversized payload is rejected after source rules`() {
        assertEquals(
            PolicyDecision.Reject(CaptureRejectReason.TOO_LARGE),
            CapturePolicy.evaluate(null, MAX_CLIP_UTF8_BYTES + 1, PolicySettings()),
        )
        assertEquals(
            PolicyDecision.Allow,
            CapturePolicy.evaluate(null, MAX_CLIP_UTF8_BYTES, PolicySettings()),
        )
    }

    @Test
    fun `user extra parsing trims drops blanks and ignores entries with spaces`() {
        val parsed = CapturePolicy.parseUserBlacklist(
            " com.example.vault , ,com.ok.app,not a package,com.fine",
        )
        assertEquals(setOf("com.example.vault", "com.ok.app", "com.fine"), parsed)
        assertTrue(CapturePolicy.parseUserBlacklist(null).isEmpty())
        assertTrue(CapturePolicy.parseUserBlacklist("  , , ").isEmpty())
    }

    @Test
    fun `loadSettings defaults blacklist on and parses flags`() {
        val defaults = CapturePolicy.loadSettings(null, null, null, null)
        assertEquals(false, defaults.paused)
        assertEquals(false, defaults.privateMode)
        assertEquals(true, defaults.blacklistEnabled)
        assertTrue(defaults.userBlacklist.isEmpty())

        val loaded = CapturePolicy.loadSettings(
            pausedRaw = "true",
            privateModeRaw = "True",
            blacklistEnabledRaw = "false",
            extraRaw = "com.example.vault",
        )
        assertEquals(true, loaded.paused)
        assertEquals(true, loaded.privateMode)
        assertEquals(false, loaded.blacklistEnabled)
        assertEquals(setOf("com.example.vault"), loaded.userBlacklist)
    }

    @Test
    fun `read-mode source tags are on the internal allowlist`() {
        val readModeTags = setOf("shizuku", "adb", "overlay", "foreground")
        assertTrue(CapturePolicy.INTERNAL_SOURCE_TAGS.containsAll(readModeTags))
        val settings = PolicySettings(userBlacklist = readModeTags)
        for (tag in readModeTags) {
            assertEquals(PolicyDecision.Allow, CapturePolicy.evaluate(tag, 8, settings))
        }
    }
}
