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
 * Repeated read-mode switches on [com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator]
 * must refresh the baseline hash so unchanged clipboard text does not re-fire after every
 * switch, while a real hash change still lands exactly once.
 */
class ModeSwitchIdempotencyTest {
    @Test
    fun `one hundred mode switches suppress unchanged text and count only real emits`() {
        val hasher = Sha256ContentHasher
        var currentText = "mode-base"
        val backends = MODES.associateWith { mode ->
            FakeBackgroundClipboardBackend(
                mode = mode,
                readResult = ClipboardReadResult.Success(currentText),
            )
        }

        val captured = mutableListOf<String>()
        val coordinator = ClipboardAccessCoordinator(
            backends = backends.values.toList(),
            hasher = hasher,
            requestedReadMode = ClipboardReadMode.SHIZUKU_EVENT,
            autoFallbackAllowed = false,
        )
        val startState = coordinator.start { change -> captured += change.contentHash }
        assertEquals(
            "start must activate the requested backend",
            ClipboardReadMode.SHIZUKU_EVENT,
            startState.activeReadMode,
        )
        assertEquals("start must not emit the baseline clipboard", 0, captured.size)

        var expectedChanges = 0
        for (switchIndex in 1..SWITCHES) {
            val nextMode = MODES[switchIndex % MODES.size]
            backends.values.forEach { backend ->
                backend.readResult = ClipboardReadResult.Success(currentText)
            }

            val state = coordinator.requestMode(nextMode)
            assertEquals(
                "active mode must match the requested backend at switch $switchIndex",
                nextMode,
                state.activeReadMode,
            )

            // The backend re-reads the clipboard on switch, so re-emitting the same
            // content must be swallowed as the baseline.
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

        assertEquals("captured-change count must match only real emits", SWITCHES, captured.size)
        assertEquals("real change hashes must be unique", SWITCHES, captured.toSet().size)
        assertTrue(
            "the final switch must leave a live backend",
            coordinator.state.activeReadMode != null,
        )
    }

    private companion object {
        const val SWITCHES = 100
        val MODES = listOf(
            ClipboardReadMode.SHIZUKU_EVENT,
            ClipboardReadMode.OVERLAY_POLLING,
            ClipboardReadMode.FOREGROUND_ONLY,
        )
    }
}
