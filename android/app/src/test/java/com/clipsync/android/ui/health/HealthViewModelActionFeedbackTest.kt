package com.clipsync.android.ui.health

import com.clipsync.android.R
import com.clipsync.android.i18n.UiText
import com.clipsync.android.i18n.testString
import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.pairing.FakeSecretProtector
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.clipboard.BackendHealth
import com.clipsync.android.platform.clipboard.BackendHealthState
import com.clipsync.android.platform.clipboard.BackgroundClipboardBackend
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator
import com.clipsync.android.platform.clipboard.ClipboardCapabilityStore
import com.clipsync.android.platform.clipboard.ClipboardChange
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardReadResult
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.platform.clipboard.ClipboardWriter
import com.clipsync.android.platform.clipboard.FakeBackgroundClipboardBackend
import com.clipsync.android.platform.clipboard.RoutePrerequisites
import com.clipsync.android.platform.clipboard.RouteProbes
import com.clipsync.android.platform.clipboard.shizuku.ShizukuErrorCodes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The three conduit actions answer their tap immediately (charter: feedback
 * within 100ms): 重新探测 / write test / read test each state busy while their
 * single-flight pass runs, and a failed read test explains the closed-set
 * 特权直读 machine code with its one-line human hint.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModelActionFeedbackTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = PairingStore(FakeKeyValueStore(), FakeSecretProtector())

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    /** One shared "system clipboard": the writer fills it, the backend reads it back. */
    private class FakeClipboardEnvironment {
        var text: String? = null

        val writer =
            object : ClipboardWriter {
                override fun probe(): CapabilityState = CapabilityState.READY

                override fun writeText(
                    text: String,
                    originEventId: String,
                ): ClipboardWriteResult {
                    this@FakeClipboardEnvironment.text = text
                    return ClipboardWriteResult.Success
                }
            }

        val readBackend =
            object : BackgroundClipboardBackend {
                override val mode = ClipboardReadMode.FOREGROUND_ONLY

                override fun probe() =
                    FakeBackgroundClipboardBackend.capabilityReport(
                        mode,
                        CapabilityState.READY,
                    )

                override fun start(onChanged: (ClipboardChange) -> Unit) = Unit

                override fun stop() = Unit

                override fun readText(): ClipboardReadResult {
                    val current = text ?: return ClipboardReadResult.Empty
                    return ClipboardReadResult.Success(current)
                }

                override fun health() = BackendHealth(BackendHealthState.HEALTHY, 1L)
            }
    }

    /** 特权直读 whose reads fail with a stable closed-set code. */
    private class FailingPrivilegedBackend : BackgroundClipboardBackend {
        override val mode = ClipboardReadMode.SHIZUKU_EVENT

        override fun probe() =
            FakeBackgroundClipboardBackend.capabilityReport(
                mode,
                CapabilityState.UNAVAILABLE,
                errorCode = ShizukuErrorCodes.NOT_RUNNING,
            )

        override fun start(onChanged: (ClipboardChange) -> Unit) = Unit

        override fun stop() = Unit

        override fun readText(): ClipboardReadResult = ClipboardReadResult.Failure(ShizukuErrorCodes.NOT_RUNNING)

        override fun health() = BackendHealth(BackendHealthState.FAILED, 1L)
    }

    private fun model(
        probeDispatcher: CoroutineDispatcher = dispatcher,
        environment: FakeClipboardEnvironment = FakeClipboardEnvironment(),
        extraBackend: BackgroundClipboardBackend? = null,
    ) = HealthViewModel(
        pairingStore = store,
        clipboard =
            ClipboardAccessCoordinator(
                listOfNotNull(extraBackend, environment.readBackend),
            ),
        syncHealthSource = null,
        probeDispatcher = probeDispatcher,
        capability =
            CapabilityWiring(
                routeProbes =
                    object : RouteProbes {
                        override fun probe() = RoutePrerequisites()
                    },
                capabilityStore = ClipboardCapabilityStore(FakeKeyValueStore()),
                writeCoordinator = ClipboardWriteCoordinator(publicWriter = environment.writer),
                foregroundBackend = environment.readBackend,
                clearClipboard = { environment.text = null },
                nowMs = { 1_755_000_000_000 },
            ),
    )

    // ---- busy facts: each in-flight pass states itself ---------------------------------------

    @Test
    fun `重新探测 states busy from the tap until the pass lands`() {
        // A StandardTestDispatcher as the probe dispatcher holds every probe pass
        // open, so the in-flight busy fact is observable before advanceUntilIdle.
        val model = model(probeDispatcher = StandardTestDispatcher(dispatcher.scheduler))
        assertTrue(model.state.value.probing)
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.state.value.probing)

        model.refresh()
        assertTrue(model.state.value.probing)
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.state.value.probing)
    }

    @Test
    fun `write test states busy while its round trip is in flight`() {
        val model = model(probeDispatcher = StandardTestDispatcher(dispatcher.scheduler))
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.state.value.writeTestRunning)

        model.runWriteTest()
        assertTrue(model.state.value.writeTestRunning)
        // A re-tap during the flight is absorbed (single flight), not stacked.
        model.runWriteTest()

        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.state.value.writeTestRunning)
        assertEquals(
            true,
            model.state.value.testResult
                ?.success,
        )
    }

    @Test
    fun `read test names its route while in flight and clears the mark after`() {
        val model = model(probeDispatcher = StandardTestDispatcher(dispatcher.scheduler))
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(model.state.value.readTestMode)

        model.runReadTest(ClipboardReadMode.FOREGROUND_ONLY)
        assertEquals(ClipboardReadMode.FOREGROUND_ONLY, model.state.value.readTestMode)
        // One route's round trip at a time: a second request is absorbed.
        model.runReadTest(ClipboardReadMode.OVERLAY_POLLING)
        assertEquals(ClipboardReadMode.FOREGROUND_ONLY, model.state.value.readTestMode)

        dispatcher.scheduler.advanceUntilIdle()
        assertNull(model.state.value.readTestMode)
        assertEquals(
            true,
            model.state.value.testResult
                ?.success,
        )
    }

    // ---- 特权直读 closed-set hints on read-test failures -------------------------------------

    @Test
    fun `failed read test explains the machine code with its closed-set hint`() {
        val model = model(extraBackend = FailingPrivilegedBackend())

        model.runReadTest(ClipboardReadMode.SHIZUKU_EVENT)

        val result = model.state.value.testResult
        assertEquals(false, result?.success)
        // Machine code and human hint travel together: the code stays for reports,
        // the hint says what to do about it.
        assertTrue(result!!.label.testString().contains(ShizukuErrorCodes.NOT_RUNNING))
        assertEquals(UiText.Res(R.string.priv_hint_not_running), result.hint)
    }
}
