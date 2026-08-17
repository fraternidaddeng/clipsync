package com.clipsync.android.platform.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundClipboardBackendTest {
    @Test
    fun `mode is foreground only`() {
        val backend = ForegroundClipboardBackend(FakeClipboardOs(), isVisible = { true })

        assertEquals(ClipboardReadMode.FOREGROUND_ONLY, backend.mode)
    }

    @Test
    fun `probe is ready only while process is visible`() {
        var visible = true
        val backend = ForegroundClipboardBackend(
            os = FakeClipboardOs(systemVersion = "35"),
            isVisible = { visible },
        )

        val visibleReport = backend.probe()
        assertEquals(ClipboardReadMode.FOREGROUND_ONLY, visibleReport.readMode)
        assertEquals(CapabilityState.READY, visibleReport.readState)
        assertEquals(CapabilityState.UNKNOWN, visibleReport.writeState)
        assertEquals("35", visibleReport.systemVersion)
        assertNull(visibleReport.errorCode)

        visible = false
        val backgroundReport = backend.probe()
        assertEquals(CapabilityState.UNAVAILABLE, backgroundReport.readState)
        assertEquals(ForegroundClipboardBackend.ERROR_NOT_VISIBLE, backgroundReport.errorCode)
    }

    @Test
    fun `probe never claims background read ready`() {
        val backend = ForegroundClipboardBackend(
            os = FakeClipboardOs(),
            isVisible = { false },
        )

        val report = backend.probe()
        assertTrue(report.readState != CapabilityState.READY)
        assertEquals(ForegroundClipboardBackend.ERROR_NOT_VISIBLE, report.errorCode)
    }

    @Test
    fun `probe is unavailable when clipboard service is missing`() {
        val backend = ForegroundClipboardBackend(
            os = FakeClipboardOs(servicePresent = false),
            isVisible = { true },
        )

        val report = backend.probe()
        assertEquals(CapabilityState.UNAVAILABLE, report.readState)
        assertEquals(ForegroundClipboardBackend.ERROR_UNAVAILABLE, report.errorCode)
    }

    @Test
    fun `start registers listener and stop unregisters`() {
        val os = FakeClipboardOs()
        val backend = ForegroundClipboardBackend(os, isVisible = { true })

        backend.start { }
        assertEquals(1, os.addCount)
        assertEquals(0, os.removeCount)

        backend.stop()
        assertEquals(1, os.addCount)
        assertEquals(1, os.removeCount)
        assertNull(os.listener)
    }

    @Test
    fun `second start replaces callback without double registering`() {
        val os = FakeClipboardOs()
        val backend = ForegroundClipboardBackend(os, isVisible = { true })
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()

        backend.start { first += it.text }
        backend.start { second += it.text }
        os.clip = OsClip.Text("once")
        os.emitChanged()

        assertEquals(1, os.addCount)
        assertEquals(emptyList<String>(), first)
        assertEquals(listOf("once"), second)
    }

    @Test
    fun `stop prevents later clip changes from emitting`() {
        val os = FakeClipboardOs(clip = OsClip.Text("after-stop"))
        val backend = ForegroundClipboardBackend(os, isVisible = { true })
        val emitted = mutableListOf<String>()

        backend.start { emitted += it.text }
        backend.stop()
        os.emitChanged()

        assertEquals(emptyList<String>(), emitted)
    }

    @Test
    fun `text change emits hashed clipboard event`() {
        val os = FakeClipboardOs(clip = OsClip.Text("hello"))
        val backend = ForegroundClipboardBackend(
            os = os,
            isVisible = { true },
            hasher = ContentHasher { "hash:$it" },
            nowEpochMillis = { 99L },
        )
        val changes = mutableListOf<ClipboardChange>()

        backend.start { changes += it }
        os.emitChanged()

        assertEquals(1, changes.size)
        assertEquals("hello", changes[0].text)
        assertEquals("hash:hello", changes[0].contentHash)
        assertEquals(99L, changes[0].observedAtEpochMillis)
    }

    @Test
    fun `default hasher is sha256 of utf8 text`() {
        val os = FakeClipboardOs(clip = OsClip.Text("hello"))
        val backend = ForegroundClipboardBackend(os, isVisible = { true }, nowEpochMillis = { 1L })
        val changes = mutableListOf<ClipboardChange>()

        backend.start { changes += it }
        os.emitChanged()

        assertEquals(Sha256ContentHasher.hash("hello"), changes[0].contentHash)
    }

    @Test
    fun `empty clip produces no change event`() {
        val os = FakeClipboardOs(clip = OsClip.Empty)
        val backend = ForegroundClipboardBackend(os, isVisible = { true })
        val changes = mutableListOf<ClipboardChange>()

        backend.start { changes += it }
        os.emitChanged()

        assertEquals(emptyList<ClipboardChange>(), changes)
    }

    @Test
    fun `non text clip produces no change event`() {
        val os = FakeClipboardOs(clip = OsClip.NonText)
        val backend = ForegroundClipboardBackend(os, isVisible = { true })
        val changes = mutableListOf<ClipboardChange>()

        backend.start { changes += it }
        os.emitChanged()

        assertEquals(emptyList<ClipboardChange>(), changes)
    }

    @Test
    fun `blank text clip produces no change event`() {
        val os = FakeClipboardOs(clip = OsClip.Text(""))
        val backend = ForegroundClipboardBackend(os, isVisible = { true })
        val changes = mutableListOf<ClipboardChange>()

        backend.start { changes += it }
        os.emitChanged()

        assertEquals(emptyList<ClipboardChange>(), changes)
    }

    @Test
    fun `change while not visible produces no event`() {
        val os = FakeClipboardOs(clip = OsClip.Text("hidden"))
        val backend = ForegroundClipboardBackend(os, isVisible = { false })
        val changes = mutableListOf<ClipboardChange>()

        backend.start { changes += it }
        os.emitChanged()

        assertEquals(emptyList<ClipboardChange>(), changes)
    }

    @Test
    fun `readText returns success for visible text`() {
        val backend = ForegroundClipboardBackend(
            os = FakeClipboardOs(clip = OsClip.Text("visible")),
            isVisible = { true },
        )

        assertEquals(ClipboardReadResult.Success("visible"), backend.readText())
    }

    @Test
    fun `readText returns empty for empty or non text clips`() {
        val os = FakeClipboardOs(clip = OsClip.Empty)
        val backend = ForegroundClipboardBackend(os, isVisible = { true })

        assertEquals(ClipboardReadResult.Empty, backend.readText())

        os.clip = OsClip.NonText
        assertEquals(ClipboardReadResult.Empty, backend.readText())
    }

    @Test
    fun `readText fails when process is not visible`() {
        val backend = ForegroundClipboardBackend(
            os = FakeClipboardOs(clip = OsClip.Text("secret")),
            isVisible = { false },
        )

        assertEquals(
            ClipboardReadResult.Failure(ForegroundClipboardBackend.ERROR_NOT_VISIBLE),
            backend.readText(),
        )
    }

    @Test
    fun `health is stopped until start then healthy only while visible`() {
        var visible = true
        val backend = ForegroundClipboardBackend(
            os = FakeClipboardOs(),
            isVisible = { visible },
            nowEpochMillis = { 50L },
        )

        assertEquals(
            BackendHealth(BackendHealthState.STOPPED, 50L, null),
            backend.health(),
        )

        backend.start { }
        assertEquals(
            BackendHealth(BackendHealthState.HEALTHY, 50L, null),
            backend.health(),
        )

        visible = false
        assertEquals(
            BackendHealth(
                BackendHealthState.DEGRADED,
                50L,
                ForegroundClipboardBackend.ERROR_NOT_VISIBLE,
            ),
            backend.health(),
        )
    }

    @Test
    fun `health is failed when clipboard service is missing after start`() {
        val backend = ForegroundClipboardBackend(
            os = FakeClipboardOs(servicePresent = false),
            isVisible = { true },
            nowEpochMillis = { 7L },
        )
        backend.start { }

        assertEquals(
            BackendHealth(
                BackendHealthState.FAILED,
                7L,
                ForegroundClipboardBackend.ERROR_UNAVAILABLE,
            ),
            backend.health(),
        )
    }

    private class FakeClipboardOs(
        var clip: OsClip = OsClip.Empty,
        var servicePresent: Boolean = true,
        override val systemVersion: String = "35",
    ) : ClipboardOs {
        var listener: (() -> Unit)? = null
        var addCount = 0
        var removeCount = 0

        override val isServicePresent: Boolean
            get() = servicePresent

        override fun addPrimaryClipChangedListener(listener: () -> Unit) {
            addCount += 1
            this.listener = listener
        }

        override fun removePrimaryClipChangedListener() {
            removeCount += 1
            this.listener = null
        }

        override fun readPrimaryText(): OsClip = clip

        fun emitChanged() {
            listener?.invoke()
        }
    }
}
