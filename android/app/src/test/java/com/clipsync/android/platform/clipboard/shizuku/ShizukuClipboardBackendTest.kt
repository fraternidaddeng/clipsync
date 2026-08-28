package com.clipsync.android.platform.clipboard.shizuku

import com.clipsync.android.platform.clipboard.BackendHealthState
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardChange
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardReadResult
import com.clipsync.android.platform.clipboard.ContentHasher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuClipboardBackendTest {
    @Test
    fun `mode is shizuku event`() {
        val backend = ShizukuClipboardBackend(FakeShizukuRuntime())
        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, backend.mode)
    }

    @Test
    fun `probe adopting a rebound session re-registers the change listener`() {
        // Device-observed failure: the UserService process is killed by the OS, the
        // periodic health probe wins the race against the rebind callback and adopts
        // the fresh session bare — READY health, but no listener, so no events ever.
        val runtime = FakeShizukuRuntime()
        val backend = ShizukuClipboardBackend(runtime)
        val changes = mutableListOf<ClipboardChange>()
        backend.start { changes += it }

        runtime.fireDeath(BinderDeathKind.USER_SERVICE)
        val reborn = FakeShizukuClipboardSession()
        runtime.session = reborn
        // Health-loop path: probe() finds the new session before the scheduled
        // rebind or onBound ever runs.
        runtime.capturedOnBound = null
        runtime.pendingRebind = null
        val report = backend.probe()
        assertEquals(CapabilityState.READY, report.readState)
        assertTrue("probe must re-register the listener", reborn.addListenerCount >= 1)

        reborn.clip = SessionRead.Text("after-rebirth")
        reborn.emitChanged()
        assertEquals(1, changes.size)
        assertEquals("after-rebirth", changes.single().text)
    }

    @Test
    fun `probe maps all seven error codes`() {
        val cases = listOf(
            ProbeCase(
                mutate = { presenceState = ShizukuPresence.NOT_INSTALLED },
                state = CapabilityState.NEEDS_USER_ACTION,
                code = ShizukuErrorCodes.NOT_INSTALLED,
            ),
            ProbeCase(
                mutate = { presenceState = ShizukuPresence.NOT_RUNNING },
                state = CapabilityState.NEEDS_USER_ACTION,
                code = ShizukuErrorCodes.NOT_RUNNING,
            ),
            ProbeCase(
                mutate = { authorized = false },
                state = CapabilityState.NEEDS_USER_ACTION,
                code = ShizukuErrorCodes.NOT_AUTHORIZED,
            ),
            ProbeCase(
                mutate = { bindError = ShizukuErrorCodes.BINDER_DEAD },
                state = CapabilityState.UNAVAILABLE,
                code = ShizukuErrorCodes.BINDER_DEAD,
                start = true,
            ),
            ProbeCase(
                mutate = { session = null },
                state = CapabilityState.UNAVAILABLE,
                code = ShizukuErrorCodes.USERSERVICE_DEAD,
                start = true,
            ),
            ProbeCase(
                mutate = { session!!.healthError = ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD },
                state = CapabilityState.UNAVAILABLE,
                code = ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD,
            ),
            ProbeCase(
                mutate = { preV11 = true },
                state = CapabilityState.UNAVAILABLE,
                code = ShizukuErrorCodes.API_MISMATCH,
            ),
        )
        for (case in cases) {
            val runtime = FakeShizukuRuntime()
            case.mutate(runtime)
            val backend = ShizukuClipboardBackend(runtime)
            if (case.start) {
                backend.start { }
            }
            val report = backend.probe()
            assertEquals(case.code, report.errorCode)
            assertEquals(case.state, report.readState)
            assertEquals(ClipboardReadMode.SHIZUKU_EVENT, report.readMode)
        }
    }

    @Test
    fun `probe is ready only after authorized bind and health ping`() {
        val runtime = FakeShizukuRuntime()
        runtime.session!!.healthError = null
        val backend = ShizukuClipboardBackend(runtime)

        val unstarted = backend.probe()
        assertEquals(CapabilityState.READY, unstarted.readState)
        assertNull(unstarted.errorCode)
        assertEquals(0, runtime.bindCount)
        assertEquals(0, runtime.unbindCount)

        backend.start { }
        assertEquals(1, runtime.bindCount)
        assertEquals(0, runtime.unbindCount)
        assertTrue(runtime.session!!.addListenerCount >= 1)

        val started = backend.probe()
        assertEquals(CapabilityState.READY, started.readState)
        assertNull(started.errorCode)
        assertEquals(2, runtime.bindCount)
        assertEquals(0, runtime.unbindCount)
        assertTrue(runtime.session!!.addListenerCount >= 2)
    }

    @Test
    fun `started probe is not ready when addChangedListener fails`() {
        val runtime = FakeShizukuRuntime()
        runtime.session!!.addListenerOk = false
        val backend = ShizukuClipboardBackend(
            runtime,
            rebindDelaysMillis = longArrayOf(1_000L, 2_000L),
        )
        backend.start { }
        assertEquals(1_000L, runtime.lastRebindDelayMillis)

        val report = backend.probe()
        assertEquals(CapabilityState.UNAVAILABLE, report.readState)
        assertEquals(ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD, report.errorCode)
        assertEquals(BackendHealthState.FAILED, backend.health().state)
        assertEquals(ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD, backend.health().errorCode)
        assertEquals(2_000L, runtime.lastRebindDelayMillis)

        runtime.session!!.addListenerOk = true
        runtime.fireRebind()
        assertEquals(CapabilityState.READY, backend.probe().readState)
        assertTrue(runtime.session!!.addListenerCount >= 3)
    }

    @Test
    fun `in-flight bind is degraded not user-service dead`() {
        val runtime = FakeShizukuRuntime()
        runtime.binding = true
        val backend = ShizukuClipboardBackend(runtime)
        backend.start { }

        val report = backend.probe()
        assertEquals(CapabilityState.DEGRADED, report.readState)
        assertNull(report.errorCode)
        val health = backend.health()
        assertEquals(BackendHealthState.DEGRADED, health.state)
        assertNull(health.errorCode)
        assertEquals(0, runtime.rebindScheduleCount)

        runtime.binding = false
        runtime.capturedOnBound?.invoke(runtime.session!!)
        assertEquals(CapabilityState.READY, backend.probe().readState)
        assertEquals(BackendHealthState.HEALTHY, backend.health().state)
    }

    @Test
    fun `authorization flow stays needs user action until grant and healthy ping`() {
        val runtime = FakeShizukuRuntime()
        runtime.authorized = false
        val backend = ShizukuClipboardBackend(runtime, nowEpochMillis = { 10L })
        var granted: Boolean? = null

        val before = backend.probe()
        assertEquals(CapabilityState.NEEDS_USER_ACTION, before.readState)
        assertEquals(ShizukuErrorCodes.NOT_AUTHORIZED, before.errorCode)
        assertTrue(before.authorizations.any { it.name == "priv_host_authorized" && !it.granted })

        backend.requestAuthorization { granted = it }
        assertEquals(1, runtime.authRequests)
        runtime.grantAuthorization()
        assertEquals(true, granted)

        runtime.session!!.healthError = ShizukuErrorCodes.USERSERVICE_DEAD
        val grantedButUnhealthy = backend.probe()
        assertEquals(CapabilityState.UNAVAILABLE, grantedButUnhealthy.readState)
        assertEquals(ShizukuErrorCodes.USERSERVICE_DEAD, grantedButUnhealthy.errorCode)

        runtime.session!!.healthError = null
        val ready = backend.probe()
        assertEquals(CapabilityState.READY, ready.readState)
        assertNull(ready.errorCode)
        assertTrue(ready.authorizations.any { it.name == "priv_host_authorized" && it.granted })
    }

    @Test
    fun `change signal reads and hashes without treating listener as content`() {
        val runtime = FakeShizukuRuntime()
        val session = runtime.session!!
        session.clip = SessionRead.Text("hello")
        val backend = ShizukuClipboardBackend(
            runtime = runtime,
            hasher = ContentHasher { "hash:$it" },
            nowEpochMillis = { 99L },
        )
        val changes = mutableListOf<ClipboardChange>()

        backend.start { changes += it }
        assertEquals(emptyList<ClipboardChange>(), changes)

        session.emitChanged()
        assertEquals(emptyList<ClipboardChange>(), changes)

        session.clip = SessionRead.Text("world")
        session.emitChanged()

        assertEquals(1, changes.size)
        assertEquals("world", changes[0].text)
        assertEquals("hash:world", changes[0].contentHash)
        assertEquals(99L, changes[0].observedAtEpochMillis)
    }

    @Test
    fun `death rebind refreshes baseline and does not emit a false copy`() {
        val runtime = FakeShizukuRuntime()
        val session = runtime.session!!
        session.clip = SessionRead.Text("still-there")
        val backend = ShizukuClipboardBackend(
            runtime = runtime,
            hasher = ContentHasher { "hash:$it" },
            rebindDelaysMillis = longArrayOf(1_000L, 2_000L),
        )
        val changes = mutableListOf<String>()

        backend.start { changes += it.text }
        assertEquals(emptyList<String>(), changes)
        assertEquals(1_000L, ShizukuClipboardBackend.DEFAULT_REBIND_DELAYS_MILLIS[0])

        runtime.fireDeath(BinderDeathKind.USER_SERVICE)
        assertEquals(ShizukuErrorCodes.USERSERVICE_DEAD, backend.health().errorCode)
        assertEquals(1_000L, runtime.lastRebindDelayMillis)
        assertEquals(1, session.removeListenerCount)

        session.clip = SessionRead.Text("still-there")
        runtime.fireRebind()
        assertEquals(emptyList<String>(), changes)
        assertTrue(session.addListenerCount >= 2)

        session.emitChanged()
        assertEquals(emptyList<String>(), changes)

        session.clip = SessionRead.Text("new-copy")
        session.emitChanged()
        assertEquals(listOf("new-copy"), changes)
    }

    @Test
    fun `rebind delays grow exponentially`() {
        val runtime = FakeShizukuRuntime()
        runtime.bindError = ShizukuErrorCodes.USERSERVICE_DEAD
        val backend = ShizukuClipboardBackend(
            runtime = runtime,
            rebindDelaysMillis = longArrayOf(1_000L, 2_000L, 4_000L),
        )
        backend.start { }

        assertEquals(1_000L, runtime.lastRebindDelayMillis)
        runtime.bindError = ShizukuErrorCodes.USERSERVICE_DEAD
        runtime.fireRebind()
        assertEquals(2_000L, runtime.lastRebindDelayMillis)
        runtime.fireRebind()
        assertEquals(4_000L, runtime.lastRebindDelayMillis)
    }

    @Test
    fun `clipboard binder death uses clipboard error and refreshes baseline`() {
        val runtime = FakeShizukuRuntime()
        val session = runtime.session!!
        session.clip = SessionRead.Text("baseline")
        val backend = ShizukuClipboardBackend(runtime, hasher = ContentHasher { it })
        val changes = mutableListOf<String>()
        backend.start { changes += it.text }

        runtime.fireDeath(BinderDeathKind.CLIPBOARD)
        assertEquals(ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD, backend.health().errorCode)
        runtime.fireRebind()
        session.emitChanged()
        assertEquals(emptyList<String>(), changes)
    }

    @Test
    fun `shizuku binder death maps to binder dead`() {
        val runtime = FakeShizukuRuntime()
        val backend = ShizukuClipboardBackend(runtime, nowEpochMillis = { 3L })
        backend.start { }
        runtime.fireDeath(BinderDeathKind.SHIZUKU)
        val health = backend.health()
        assertEquals(BackendHealthState.FAILED, health.state)
        assertEquals(ShizukuErrorCodes.BINDER_DEAD, health.errorCode)
    }

    @Test
    fun `readText maps session outcomes`() {
        val runtime = FakeShizukuRuntime()
        val session = runtime.session!!
        val backend = ShizukuClipboardBackend(runtime)

        session.clip = SessionRead.Text("visible")
        assertEquals(ClipboardReadResult.Success("visible"), backend.readText())

        session.clip = SessionRead.Empty
        assertEquals(ClipboardReadResult.Empty, backend.readText())

        session.clip = SessionRead.Failed(ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD)
        assertEquals(
            ClipboardReadResult.Failure(ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD),
            backend.readText(),
        )
    }

    // ===== verification read: wait out an asynchronous (re)bind before crying dead =====

    @Test
    fun `verification read waits for an in-flight bind then reads the reborn session`() {
        // Cold channel: the UserService bind is still in flight (the host is spawning the child).
        // A plain readText would race that and report USERSERVICE_DEAD; the verification read must
        // hold on until the connection lands, which is what finally lets the route verify.
        val runtime = FakeShizukuRuntime()
        runtime.binding = true
        runtime.session!!.clip = SessionRead.Text("verify-token")
        var slept = 0
        val backend =
            ShizukuClipboardBackend(
                runtime,
                verifyBind = VerifyBindBudget(polls = 10, stepMillis = 0L),
                sleeper = {
                    slept += 1
                    if (slept >= 2) {
                        runtime.binding = false
                    }
                },
            )

        val result = backend.readTextForVerification()

        assertEquals(ClipboardReadResult.Success("verify-token"), result)
        assertTrue("must have waited for the bind to land", slept >= 2)
    }

    @Test
    fun `verification read reports userservice dead only after waiting out the full budget`() {
        val runtime = FakeShizukuRuntime()
        runtime.binding = true // never completes
        var slept = 0
        val backend =
            ShizukuClipboardBackend(
                runtime,
                verifyBind = VerifyBindBudget(polls = 4, stepMillis = 0L),
                sleeper = { slept += 1 },
            )

        val result = backend.readTextForVerification()

        assertEquals(ClipboardReadResult.Failure(ShizukuErrorCodes.USERSERVICE_DEAD), result)
        assertEquals("waits the whole budget, never an instant lie", 4, slept)
    }

    @Test
    fun `verification read fails fast on a missing prerequisite without waiting`() {
        val runtime = FakeShizukuRuntime()
        runtime.authorized = false
        var slept = 0
        val backend = ShizukuClipboardBackend(runtime, sleeper = { slept += 1 })

        val result = backend.readTextForVerification()

        assertEquals(ClipboardReadResult.Failure(ShizukuErrorCodes.NOT_AUTHORIZED), result)
        assertEquals(0, slept)
    }

    @Test
    fun `verification read surfaces a hard bind failure without waiting`() {
        val runtime = FakeShizukuRuntime()
        runtime.bindError = ShizukuErrorCodes.BINDER_DEAD
        var slept = 0
        val backend = ShizukuClipboardBackend(runtime, sleeper = { slept += 1 })

        val result = backend.readTextForVerification()

        assertEquals(ClipboardReadResult.Failure(ShizukuErrorCodes.BINDER_DEAD), result)
        assertEquals(0, slept)
    }

    @Test
    fun `verification read of a live session reads directly`() {
        val runtime = FakeShizukuRuntime()
        runtime.session!!.clip = SessionRead.Text("already-live")
        var slept = 0
        val backend = ShizukuClipboardBackend(runtime, sleeper = { slept += 1 })
        backend.start { }

        val result = backend.readTextForVerification()

        assertEquals(ClipboardReadResult.Success("already-live"), result)
        assertEquals(0, slept)
    }

    @Test
    fun `stop unregisters and suppresses later signals`() {
        val runtime = FakeShizukuRuntime()
        val session = runtime.session!!
        session.clip = SessionRead.Text("after-stop")
        val backend = ShizukuClipboardBackend(runtime)
        val changes = mutableListOf<String>()
        backend.start { changes += it.text }
        backend.stop()
        session.emitChanged()
        assertEquals(emptyList<String>(), changes)
        assertEquals(1, runtime.unbindCount)
        assertEquals(BackendHealthState.STOPPED, backend.health().state)
    }

    @Test
    fun `fallback writer is a clipboard writer sharing the runtime`() {
        val runtime = FakeShizukuRuntime()
        val backend = ShizukuClipboardBackend(runtime)
        val writer = backend.fallbackWriter()
        assertEquals(CapabilityState.READY, writer.probe())
        assertEquals(
            com.clipsync.android.platform.clipboard.ClipboardWriteResult.Success,
            writer.writeText("fallback", "origin-1"),
        )
        assertEquals(listOf("fallback"), runtime.session!!.writes)
    }

    private data class ProbeCase(
        val mutate: FakeShizukuRuntime.() -> Unit,
        val state: CapabilityState,
        val code: String,
        val start: Boolean = false,
    )
}
