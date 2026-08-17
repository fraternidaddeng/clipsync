package com.clipsync.android.e2e

import com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardReadResult
import com.clipsync.android.platform.clipboard.FakeBackgroundClipboardBackend
import com.clipsync.android.platform.clipboard.Sha256ContentHasher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repeated mode switches must refresh the baseline hash so unchanged clipboard
 * text does not fire, while a real hash change still lands once.
 */
class ModeSwitchIdempotencyTest {
    @Test
    fun `one hundred mode switches suppress unchanged text and count only real emits`() {
        val hasher = Sha256ContentHasher
        var currentText = "mode-base"
        val shizuku = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            readResult = ClipboardReadResult.Success(currentText),
        )
        val overlay = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.OVERLAY_POLLING,
            readResult = ClipboardReadResult.Success(currentText),
        )
        val foreground = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.FOREGROUND_ONLY,
            readResult = ClipboardReadResult.Success(currentText),
        )
        val backends = mapOf(
            ClipboardReadMode.SHIZUKU_EVENT to shizuku,
            ClipboardReadMode.OVERLAY_POLLING to overlay,
            ClipboardReadMode.FOREGROUND_ONLY to foreground,
        )
        val modes = backends.keys.toList()

        val captured = mutableListOf<String>()
        val coordinator = ClipboardAccessCoordinator(
            backends = backends.values.toList(),
            hasher = hasher,
            requestedReadMode = ClipboardReadMode.SHIZUKU_EVENT,
            autoFallbackAllowed = false,
        )
        coordinator.start { change -> captured += change.contentHash }
        assertEquals(
            "start must bump modeEpoch once",
            1L,
            coordinator.modeEpoch,
        )
        assertEquals(
            "start must not emit the baseline clipboard",
            0,
            captured.size,
        )

        var expectedEpoch = 1L
        var expectedChanges = 0
        for (switchIndex in 1..SWITCHES) {
            val nextMode = modes[switchIndex % modes.size]
            backends.values.forEach { backend ->
                backend.readResult = ClipboardReadResult.Success(currentText)
            }

            val state = coordinator.requestMode(nextMode)
            expectedEpoch += 1L
            assertEquals(
                "modeEpoch must increment on successful switch $switchIndex",
                expectedEpoch,
                coordinator.modeEpoch,
            )
            assertEquals(
                "active mode must match the requested backend at switch $switchIndex",
                nextMode,
                state.activeReadMode,
            )

            val active = backends.getValue(nextMode)
            val baselineHash = hasher.hash(currentText)
            val beforeEcho = captured.size
            active.emit(currentText, baselineHash)
            assertEquals(
                "unchanged content after switch $switchIndex must not fire a listener callback",
                beforeEcho,
                captured.size,
            )

            val changedText = "mode-change-$switchIndex"
            val changedHash = hasher.hash(changedText)
            active.emit(changedText, changedHash)
            expectedChanges += 1
            assertEquals(
                "real hash change after switch $switchIndex must land exactly once",
                expectedChanges,
                captured.size,
            )
            assertEquals(
                "latest captured hash must be the real change at switch $switchIndex",
                changedHash,
                captured.last(),
            )
            currentText = changedText
        }

        assertEquals(
            "modeEpoch must equal start plus successful switches",
            1L + SWITCHES,
            coordinator.modeEpoch,
        )
        assertEquals(
            "captured-change count must match only real emits",
            SWITCHES,
            captured.size,
        )
        assertEquals(
            "real change hashes must be unique",
            SWITCHES,
            captured.toSet().size,
        )
        assertTrue(
            "every successful switch must have advanced the epoch",
            coordinator.modeEpoch == 1L + SWITCHES.toLong(),
        )
    }

    private companion object {
        const val SWITCHES = 100
    }
}
