package com.clipsync.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceOrchestratorTest {
    @Test
    fun `background start marks starting and wants running`() {
        val orch = ServiceOrchestrator()

        orch.requestBackgroundStart()

        assertEquals(ServiceProcessState.STARTING, orch.processState)
        assertTrue(orch.wantedRunning)
        assertFalse(orch.isProcessAlive)
        assertFalse(orch.isOnline)

        orch.onForegroundStarted()
        orch.onServiceControllerStarted()

        assertEquals(ServiceProcessState.RUNNING, orch.processState)
        assertTrue(orch.isProcessAlive)
        assertFalse(orch.isOnline)
    }

    @Test
    fun `background stop marks stopped and clears readiness`() {
        val orch = startedService()
        orch.markControllerReady()
        assertTrue(orch.isOnline)

        orch.requestBackgroundStop()

        assertEquals(ServiceProcessState.STOPPED, orch.processState)
        assertFalse(orch.wantedRunning)
        assertFalse(orch.isOnline)
    }

    @Test
    fun `MissingForegroundServiceTypeException becomes a stable error and is not online`() {
        val orch = ServiceOrchestrator()
        orch.requestBackgroundStart()

        orch.onForegroundStartFailed(MissingForegroundServiceTypeException("type missing"))

        assertEquals(ServiceProcessState.ERROR, orch.processState)
        assertEquals(ForegroundStartErrors.MISSING_TYPE, orch.errorCode)
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
        val orch = startedService()
        orch.markControllerReady()
        assertTrue(orch.isOnline)

        orch.onProcessKilled()

        assertEquals(ServiceProcessState.NEEDS_RECOVERY, orch.processState)
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
    fun `controller readiness only sticks while the process is running`() {
        val orch = ServiceOrchestrator()
        orch.markControllerReady()
        assertFalse(orch.isOnline)

        orch.requestBackgroundStart()
        orch.onForegroundStarted()
        orch.onServiceControllerStarted()
        orch.markControllerReady()
        assertTrue(orch.isOnline)

        orch.clearControllerReady()
        assertFalse(orch.isOnline)
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
    fun `boot health check exhaustion flips to needs recovery and stays honest`() {
        val orch = ServiceOrchestrator()
        orch.wantedRunning = true
        orch.setBootRecoveryEnabled(true)
        assertEquals(ServiceProcessState.STOPPED, orch.processState)

        orch.onBootHealthCheckFailed()

        assertEquals(ServiceProcessState.NEEDS_RECOVERY, orch.processState)
        assertTrue(orch.wantedRunning)
        assertFalse(orch.isOnline)
        assertFalse(orch.isProcessAlive)
        assertEquals(ServiceSnapshot.LABEL_NEEDS_RECOVERY, orch.statusLabel())
    }

    @Test
    fun `boot health check failure does not demote a running service`() {
        val orch = startedService()
        orch.markControllerReady()
        assertTrue(orch.isOnline)

        orch.onBootHealthCheckFailed()

        assertEquals(ServiceProcessState.RUNNING, orch.processState)
        assertTrue(orch.isOnline)
    }

    @Test
    fun `network regain nudges only a running service`() {
        val orch = startedService()
        assertTrue(orch.onNetworkRegained())

        orch.requestBackgroundStop()
        assertFalse(orch.onNetworkRegained())
    }

    private fun startedService(): ServiceOrchestrator {
        val orch = ServiceOrchestrator()
        orch.requestBackgroundStart()
        orch.onForegroundStarted()
        orch.onServiceControllerStarted()
        return orch
    }
}

/** Mirrors the platform exception simple name so JVM tests can exercise the mapper. */
class MissingForegroundServiceTypeException(message: String) : RuntimeException(message)
