package com.clipsync.android.ui.settings

import com.clipsync.android.service.ServiceProcessState
import com.clipsync.android.service.ServiceSnapshot
import com.clipsync.android.sync.SyncControllerState
import com.clipsync.android.sync.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncControllerStatusAdapterTest {
    @Test
    fun `unpaired is never reachable`() {
        val adapter = adapter(
            paired = false,
            status = SyncStatus.READY,
        )
        assertFalse(adapter.current().windowsReachable)
        assertFalse(adapter.current().paired)
    }

    @Test
    fun `connecting is not reachable`() {
        val adapter = adapter(status = SyncStatus.CONNECTING)
        assertTrue(adapter.current().paired)
        assertFalse(adapter.current().windowsReachable)
    }

    @Test
    fun `ready is reachable when the process is healthy`() {
        val adapter = adapter(status = SyncStatus.READY)
        assertTrue(adapter.current().windowsReachable)
    }

    @Test
    fun `ready is blocked when the service needs recovery`() {
        val adapter = adapter(
            status = SyncStatus.READY,
            service = ServiceSnapshot(
                processState = ServiceProcessState.NEEDS_RECOVERY,
            ),
        )
        assertFalse(adapter.current().windowsReachable)
        assertTrue(adapter.current().serviceNeedsRecovery)
    }

    @Test
    fun `null controller is not reachable`() {
        val adapter = SyncControllerStatusAdapter(
            isPaired = { true },
            serviceSnapshot = { ServiceSnapshot.idle() },
            controllerState = { null },
        )
        assertFalse(adapter.current().windowsReachable)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `READY without a service snapshot change becomes reachable`() = runTest(UnconfinedTestDispatcher()) {
        val controllerState = MutableStateFlow(SyncControllerState(SyncStatus.CONNECTING))
        val service = MutableStateFlow(
            ServiceSnapshot(processState = ServiceProcessState.RUNNING),
        )
        val adapter = SyncControllerStatusAdapter(
            isPaired = { true },
            serviceSnapshot = { service.value },
            serviceSnapshots = service,
            controllerState = { controllerState },
        )
        val seen = mutableListOf<SyncConnectionStatus>()
        val job = backgroundScope.launch {
            adapter.snapshots().collect { seen += it }
        }
        assertFalse(seen.last().windowsReachable)
        controllerState.value = SyncControllerState(SyncStatus.READY, authenticated = true)
        assertTrue(seen.last().windowsReachable)
        job.cancel()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `service snapshot change alone re-emits status`() = runTest(UnconfinedTestDispatcher()) {
        val controllerState = MutableStateFlow(
            SyncControllerState(SyncStatus.READY, authenticated = true),
        )
        val service = MutableStateFlow(
            ServiceSnapshot(processState = ServiceProcessState.RUNNING),
        )
        val adapter = SyncControllerStatusAdapter(
            isPaired = { true },
            serviceSnapshot = { service.value },
            serviceSnapshots = service,
            controllerState = { controllerState },
        )
        val seen = mutableListOf<SyncConnectionStatus>()
        val job = backgroundScope.launch {
            adapter.snapshots().collect { seen += it }
        }
        assertTrue(seen.last().windowsReachable)

        service.value = ServiceSnapshot(processState = ServiceProcessState.NEEDS_RECOVERY)

        assertFalse(seen.last().windowsReachable)
        assertTrue(seen.last().serviceNeedsRecovery)
        job.cancel()
    }

    private fun adapter(
        paired: Boolean = true,
        status: SyncStatus,
        service: ServiceSnapshot = ServiceSnapshot(
            processState = ServiceProcessState.RUNNING,
        ),
    ): SyncControllerStatusAdapter {
        val controllerState = MutableStateFlow(SyncControllerState(status))
        return SyncControllerStatusAdapter(
            isPaired = { paired },
            serviceSnapshot = { service },
            serviceSnapshots = MutableStateFlow(service),
            controllerState = { controllerState },
        )
    }
}
