package com.clipsync.android.platform.clipboard

import java.util.UUID

enum class SelfTestKind {
    BACKGROUND_READ,
    BACKGROUND_WRITE,
}

/** Outcome of one self-test run. Never carries the generated token or any clipboard text. */
data class SelfTestResult(
    val kind: SelfTestKind,
    val passed: Boolean,
    val errorCode: String? = null,
    val readMode: ClipboardReadMode? = null,
    val writerKind: ClipboardWriterKind? = null,
)

/**
 * Plan 5.7 one-tap "test background read" / "test background write". Both tests
 * operate only on an app-generated random token and clear it immediately after
 * a successful write; the user's own clipboard content is never read, compared
 * against, stored, or uploaded. Writes go through [ClipboardWriteCoordinator],
 * so its suppression marker keeps the token out of capture and sync.
 */
class ClipboardSelfTest(
    private val writeCoordinator: ClipboardWriteCoordinator,
    private val readBackend: () -> BackgroundClipboardBackend?,
    private val clearClipboard: () -> Boolean,
    private val hasher: ContentHasher = Sha256ContentHasher,
    private val tokenGenerator: () -> String = { randomToken() },
) {
    fun runWriteTest(): SelfTestResult {
        val token = tokenGenerator()
        val outcome = writeCoordinator.writeText(token, originEventId = "selftest-write-${nonce()}")
        return when (val result = outcome.result) {
            is ClipboardWriteResult.Success -> SelfTestResult(
                kind = SelfTestKind.BACKGROUND_WRITE,
                passed = true,
                errorCode = if (clearClipboard()) null else ERROR_CLEAR_FAILED,
                writerKind = outcome.writerKind,
            )
            // The write never landed, so the clipboard still holds the user's own
            // content; clearing here would destroy data this test never touched.
            is ClipboardWriteResult.Failure -> SelfTestResult(
                kind = SelfTestKind.BACKGROUND_WRITE,
                passed = false,
                errorCode = result.errorCode,
                writerKind = outcome.writerKind,
            )
        }
    }

    fun runReadTest(): SelfTestResult {
        val backend = readBackend()
            ?: return SelfTestResult(
                kind = SelfTestKind.BACKGROUND_READ,
                passed = false,
                errorCode = ERROR_NO_READ_BACKEND,
            )
        val token = tokenGenerator()
        val expectedHash = hasher.hash(token)
        val seeded = writeCoordinator.writeText(token, originEventId = "selftest-read-${nonce()}")
        if (seeded.result is ClipboardWriteResult.Failure) {
            // Seeding failed: the clipboard still holds user content. Do not read
            // or clear it; reading now would inspect data the user never offered.
            return SelfTestResult(
                kind = SelfTestKind.BACKGROUND_READ,
                passed = false,
                errorCode = ERROR_SEED_WRITE_FAILED,
                readMode = backend.mode,
            )
        }
        // Verification read: gives an asynchronous channel (特权直读's on-demand UserService
        // bind) time to come up before declaring failure. A plain readText would race a cold
        // bind and report the channel dead, so the route could never pass its own test.
        val read = backend.readTextForVerification()
        val cleared = clearClipboard()
        return when (read) {
            is ClipboardReadResult.Success ->
                if (hasher.hash(read.text) == expectedHash) {
                    SelfTestResult(
                        kind = SelfTestKind.BACKGROUND_READ,
                        passed = true,
                        errorCode = if (cleared) null else ERROR_CLEAR_FAILED,
                        readMode = backend.mode,
                    )
                } else {
                    // Whatever was read is discarded here; only the mismatch code leaves.
                    SelfTestResult(
                        kind = SelfTestKind.BACKGROUND_READ,
                        passed = false,
                        errorCode = ERROR_READ_MISMATCH,
                        readMode = backend.mode,
                    )
                }
            ClipboardReadResult.Empty ->
                SelfTestResult(
                    kind = SelfTestKind.BACKGROUND_READ,
                    passed = false,
                    errorCode = ERROR_READ_EMPTY,
                    readMode = backend.mode,
                )
            is ClipboardReadResult.Failure ->
                SelfTestResult(
                    kind = SelfTestKind.BACKGROUND_READ,
                    passed = false,
                    errorCode = read.errorCode,
                    readMode = backend.mode,
                )
        }
    }

    companion object {
        const val ERROR_NO_READ_BACKEND = "SELFTEST_NO_READ_BACKEND"
        const val ERROR_SEED_WRITE_FAILED = "SELFTEST_SEED_WRITE_FAILED"
        const val ERROR_READ_MISMATCH = "SELFTEST_READ_MISMATCH"
        const val ERROR_READ_EMPTY = "SELFTEST_READ_EMPTY"
        const val ERROR_CLEAR_FAILED = "SELFTEST_CLEAR_FAILED"
        const val ERROR_CRASHED = "SELFTEST_CRASHED"

        private fun randomToken(): String = "clipsync-selftest-${nonce()}"

        private fun nonce(): String = UUID.randomUUID().toString().replace("-", "")
    }
}
