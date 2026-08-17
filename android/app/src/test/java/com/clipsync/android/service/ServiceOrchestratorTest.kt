package com.clipsync.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceOrchestratorTest {
    @Test
    fun `start hands the controller from Activity to Service`() {
        val activity = RecordingLease().also { it.start() }
        val service = RecordingLease()
        val orch = ServiceOrchestrator()

        val handover = orch.requestBackgroundStart()
        assertEquals(ControllerOwner.ACTIVITY, handover.releaseFrom)
        assertEquals(ControllerOwner.SERVICE, handover.acquireBy)
        orch.applyRelease(handover, activity, service)

        assertFalse(activity.started)
        assertEquals(ControllerOwner.NONE, orch.controllerOwner)
        assertEquals(ServiceProcessState.STARTING, orch.processState)
        assertTrue(orch.wantedRunning)

        orch.onForegroundStarted()
        orch.applyAcquire(handover, activity, service)
        orch.onServiceControllerStarted()

        assertTrue(service.started)
        assertFalse(activity.started)
        assertEquals(ControllerOwner.SERVICE, orch.controllerOwner)
        assertEquals(ServiceProcessState.RUNNING, orch.processState)
        assertTrue(orch.isProcessAlive)
        assertFalse(orch.isOnline)
    }

    @Test
    fun `stop returns the controller from Service to Activity`() {
        val activity = RecordingLease()
        val service = RecordingLease()
        val orch = startedService(activity, service)

        val handover = orch.requestBackgroundStop()
        assertEquals(ControllerOwner.SERVICE, handover.releaseFrom)
        assertEquals(ControllerOwner.ACTIVITY, handover.acquireBy)
        orch.applyRelease(handover, activity, service)
        orch.applyAcquire(handover, activity, service)

        assertFalse(service.started)
        assertTrue(activity.started)
        assertEquals(ControllerOwner.ACTIVITY, orch.controllerOwner)
        assertEquals(ServiceProcessState.STOPPED, orch.processState)
        assertFalse(orch.wantedRunning)
        assertFalse(orch.isOnline)
    }

    @Test
    fun `MissingForegroundServiceTypeException becomes a stable error and is not online`() {
        val activity = RecordingLease().also { it.start() }
        val service = RecordingLease()
        val orch = ServiceOrchestrator()
        val handover = orch.requestBackgroundStart()
        orch.applyRelease(handover, activity, service)

        orch.onForegroundStartFailed(MissingForegroundServiceTypeException("type missing"))

        assertEquals(ServiceProcessState.ERROR, orch.processState)
        assertEquals(ForegroundStartErrors.MISSING_TYPE, orch.errorCode)
        assertEquals(ControllerOwner.NONE, orch.controllerOwner)
        assertFalse(service.started)
        assertFalse(orch.isOnline)
        assertFalse(orch.isProcessAlive)
        assertTrue(orch.wantedRunning)
    }

    @Test
    fun `SecurityException from startForeground is a stable error`() {
        val orch = ServiceOrchestrator()
        orch.requestBackgroundStart()
        orch.onForegroundStartFailed(SecurityException("FOREGROUND_SERVICE_CONNECTED_DEVICE"))
        assertEquals(ServiceProcessState.ERROR, orch.processState)
        assertEquals(ForegroundStartErrors.SECURITY, orch.errorCode)
        assertFalse(orch.isOnline)
    }

    @Test
    fun `killed process shows needs recovery and never fakes online`() {
        val activity = RecordingLease()
        val service = RecordingLease()
        val orch = startedService(activity, service)
        orch.markControllerReady()
        assertTrue(orch.isOnline)

        orch.onProcessKilled()
        service.stop()

        assertEquals(ServiceProcessState.NEEDS_RECOVERY, orch.processState)
        assertEquals(ControllerOwner.NONE, orch.controllerOwner)
        assertTrue(orch.wantedRunning)
        assertFalse(orch.isOnline)
        assertFalse(orch.isProcessAlive)
        assertNotEquals("Running", orch.statusLabel())

        orch.onStickyRestart()
        assertEquals(ServiceProcessState.NEEDS_RECOVERY, orch.processState)
        assertFalse(orch.isOnline)
        assertEquals("Needs recovery", orch.statusLabel())
    }

    @Test
    fun `notification actions target pause sync-now and status and never include clipboard text`() {
        val orch = ServiceOrchestrator()
        val clipboard = "user-copied-secret-do-not-leak"
        val spec = orch.buildNotificationSpec()

        assertEquals(
            listOf(
                ServiceNotificationActions.PAUSE_ALL,
                ServiceNotificationActions.SYNC_NOW,
                ServiceNotificationActions.OPEN_STATUS,
            ),
            spec.actions.map { it.id },
        )
        assertEquals(ServiceNotificationActions.ACTION_PAUSE_ALL, spec.actions[0].intentAction)
        assertEquals(ServiceNotificationActions.COMPONENT_SERVICE, spec.actions[0].componentClass)
        assertEquals(ServiceNotificationActions.ACTION_SYNC_NOW, spec.actions[1].intentAction)
        assertEquals(ServiceNotificationActions.COMPONENT_SERVICE, spec.actions[1].componentClass)
        assertEquals(ServiceNotificationActions.ACTION_OPEN_STATUS, spec.actions[2].intentAction)
        assertEquals(ServiceNotificationActions.COMPONENT_ACTIVITY, spec.actions[2].componentClass)
        assertEquals(
            ServiceNotificationActions.TAB_STATUS,
            spec.actions[2].extras[ServiceNotificationActions.EXTRA_OPEN_TAB],
        )
        assertTrue(spec.ongoing)
        assertEquals(ServiceNotificationActions.CHANNEL_ID, spec.channelId)
        spec.allVisibleText().forEach { visible ->
            assertFalse(visible.contains(clipboard, ignoreCase = true))
            assertFalse(visible.contains("clip_text"))
        }
        spec.actions.forEach { action ->
            assertFalse(action.extras.containsKey("clip_text"))
            assertFalse(action.extras.containsKey("content"))
            assertFalse(action.extras.values.any { it.contains(clipboard) })
        }
    }

    @Test
    fun `boot receiver is active only when boot_recovery_enabled is true`() {
        val orch = ServiceOrchestrator()
        orch.wantedRunning = true
        orch.setBootRecoveryEnabled(false)
        assertFalse(orch.bootReceiverShouldBeEnabled())
        assertEquals(BootOutcome.Ignored, orch.onBootCompleted { true })

        orch.setBootRecoveryEnabled(true)
        assertTrue(orch.bootReceiverShouldBeEnabled())
        assertEquals(BootOutcome.Started, orch.onBootCompleted { true })
    }

    @Test
    fun `boot FGS failure requests recovery and does not crash-loop`() {
        val orch = ServiceOrchestrator()
        orch.wantedRunning = true
        orch.setBootRecoveryEnabled(true)

        val failed = orch.onBootCompleted { false }
        assertEquals(BootOutcome.RequestUserRecovery, failed)
        assertFalse(orch.isOnline)

        val exploded = orch.onBootCompleted { error("oem denied boot FGS") }
        assertEquals(BootOutcome.RequestUserRecovery, exploded)
        assertEquals(ServiceProcessState.STOPPED, orch.processState)
    }

    @Test
    fun `network regain nudges a running service controller without a busy loop`() {
        val activity = RecordingLease()
        val service = RecordingLease()
        val orch = startedService(activity, service)
        val startsBefore = service.startCount

        assertTrue(orch.onNetworkRegained())
        service.start()
        assertEquals(startsBefore + 1, service.startCount)
        assertFalse(orch.onNetworkRegained() && orch.processState == ServiceProcessState.STOPPED)
    }

    private fun startedService(
        activity: RecordingLease,
        service: RecordingLease,
    ): ServiceOrchestrator {
        val orch = ServiceOrchestrator()
        activity.start()
        val handover = orch.requestBackgroundStart()
        orch.applyRelease(handover, activity, service)
        orch.onForegroundStarted()
        orch.applyAcquire(handover, activity, service)
        orch.onServiceControllerStarted()
        return orch
    }
}

/** Mirrors the platform exception simple name so JVM tests can exercise the mapper. */
class MissingForegroundServiceTypeException(message: String) : RuntimeException(message)

class RecordingLease : SyncControllerLease {
    override var started: Boolean = false
        private set
    var startCount: Int = 0
        private set
    var stopCount: Int = 0
        private set

    override fun start() {
        started = true
        startCount += 1
    }

    override fun stop() {
        started = false
        stopCount += 1
    }
}
