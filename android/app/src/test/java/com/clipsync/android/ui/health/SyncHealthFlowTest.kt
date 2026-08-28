package com.clipsync.android.ui.health

import com.clipsync.android.sync.ClipboardSyncService
import com.clipsync.android.sync.SyncConnectionState
import com.clipsync.android.sync.SyncTransportKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [syncHealthFlow] feeds the conduit's 本机服务/网络 segments from the service's
 * live flows plus the 后台同步服务 master switch. The switch must be a combined
 * source of its own: flipping it while the service is already stopped changes
 * none of the service flows, and a per-emission store re-read would then never
 * run again — the segment would keep a stale 启动失败 instead of 已停用.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncHealthFlowTest {
    private val serviceRunning = MutableStateFlow(false)
    private val connectionStates = MutableStateFlow<SyncConnectionState>(SyncConnectionState.NotPaired)
    private val startErrorCodes = MutableStateFlow<String?>(null)
    private val peerThrottled = MutableStateFlow(false)
    private val serviceEnabled = MutableStateFlow(true)

    private fun flowUnderTest() =
        syncHealthFlow(
            serviceRunning = serviceRunning,
            connectionStates = connectionStates,
            startErrorCodes = startErrorCodes,
            peerThrottled = peerThrottled,
            serviceEnabled = serviceEnabled,
        )

    @Test
    fun `flipping the master switch re-emits even while every service flow is quiet`() =
        runTest(UnconfinedTestDispatcher()) {
            // An FGS-denied start left the service down with a recorded failure code.
            startErrorCodes.value = ClipboardSyncService.START_ERROR_FGS_DENIED
            val emissions = mutableListOf<SyncHealth>()
            val collection = launch { flowUnderTest().collect(emissions::add) }
            assertTrue(emissions.last().serviceEnabled)

            // 停止服务 while already stopped: ClipboardSyncService.stop() is a no-op, so no
            // service flow changes — the switch itself must produce the fresh emission.
            serviceEnabled.value = false

            val latest = emissions.last()
            assertFalse(latest.serviceEnabled)
            assertFalse(latest.serviceRunning)
            // The stale failure code travels along; the segment mapping already lets the
            // chosen 已停用 fact outrank it (localServiceSegment's branch order).
            assertEquals(ClipboardSyncService.START_ERROR_FGS_DENIED, latest.serviceErrorCode)
            collection.cancel()
        }

    @Test
    fun `a connected bluetooth session reports the degraded fallback path`() =
        runTest(UnconfinedTestDispatcher()) {
            serviceRunning.value = true
            connectionStates.value =
                SyncConnectionState.Connected("DESKTOP-WIN", transport = SyncTransportKind.BLUETOOTH)
            val emissions = mutableListOf<SyncHealth>()
            val collection = launch { flowUnderTest().collect(emissions::add) }

            val latest = emissions.last()
            assertTrue(latest.connected)
            assertTrue(latest.bluetoothFallback)
            collection.cancel()
        }

    @Test
    fun `an ip session reports connected without the fallback flag`() =
        runTest(UnconfinedTestDispatcher()) {
            serviceRunning.value = true
            connectionStates.value = SyncConnectionState.Connected("DESKTOP-WIN")
            val emissions = mutableListOf<SyncHealth>()
            val collection = launch { flowUnderTest().collect(emissions::add) }

            val latest = emissions.last()
            assertTrue(latest.connected)
            assertFalse(latest.bluetoothFallback)
            collection.cancel()
        }
}
