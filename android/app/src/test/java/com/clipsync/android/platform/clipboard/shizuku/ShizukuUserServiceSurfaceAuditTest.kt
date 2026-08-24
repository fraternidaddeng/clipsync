package com.clipsync.android.platform.clipboard.shizuku

import com.clipsync.android.platform.clipboard.BackendHealthState
import com.clipsync.android.platform.clipboard.CapabilityState
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the Stage 6 Shizuku UserService surface: clipboard read/write,
 * listener register/unregister, health, and destroy. Fails if a method
 * or binder opcode is added. Also locks revoke-leaves-READY in one probe.
 */
class ShizukuUserServiceSurfaceAuditTest {
    @Test
    fun `ClipboardUserService declared public methods stay on the allow-list`() {
        val actual = ClipboardUserService::class.java.declaredMethods
            .filter { method ->
                Modifier.isPublic(method.modifiers) &&
                    !method.isSynthetic &&
                    !method.isBridge
            }
            .map { it.name }
            .toSet()

        assertEquals(
            "UserService public surface grew or shrank; keep it clipboard-only.",
            ALLOWED_USER_SERVICE_METHODS,
            actual,
        )
    }

    @Test
    fun `ShizukuClipboardSession methods stay on the allow-list`() {
        val actual = ShizukuClipboardSession::class.java.declaredMethods
            .filter { method -> !method.isSynthetic && !method.isBridge }
            .map { it.name }
            .toSet()

        assertEquals(
            "Session API grew or shrank; keep it read/write/listener/health.",
            ALLOWED_SESSION_METHODS,
            actual,
        )
    }

    @Test
    fun `destroy opcode matches the Shizuku UserService teardown contract`() {
        assertEquals(16777115, ShizukuClipboardBinderContract.TRANSACTION_DESTROY)
    }

    @Test
    fun `binder contract transaction names stay on the allow-list`() {
        val actual = ShizukuClipboardBinderContract::class.java.declaredFields
            .filter { field ->
                Modifier.isStatic(field.modifiers) &&
                    field.name.startsWith(TRANSACTION_PREFIX)
            }
            .map { it.name }
            .toSet()

        assertEquals(
            "Binder opcode set grew or shrank; do not add shell/network/secret ops.",
            ALLOWED_TRANSACTION_FIELDS,
            actual,
        )
    }

    @Test
    fun `IClipboard adapter method names stay clipboard-only`() {
        assertEquals(IClipboardReflectionAdapter.GET_PRIMARY_CLIP, "getPrimaryClip")
        assertEquals(IClipboardReflectionAdapter.SET_PRIMARY_CLIP, "setPrimaryClip")
        assertEquals(IClipboardReflectionAdapter.ADD_LISTENER, "addPrimaryClipChangedListener")
        assertEquals(IClipboardReflectionAdapter.REMOVE_LISTENER, "removePrimaryClipChangedListener")
        assertEquals(
            setOf(
                IClipboardReflectionAdapter.GET_PRIMARY_CLIP,
                IClipboardReflectionAdapter.SET_PRIMARY_CLIP,
                IClipboardReflectionAdapter.ADD_LISTENER,
                IClipboardReflectionAdapter.REMOVE_LISTENER,
            ),
            ADAPTER_METHOD_NAMES,
        )
    }

    @Test
    fun `revoking Shizuku authorization leaves READY within one probe and health check`() {
        val runtime = FakeShizukuRuntime()
        runtime.authorized = true
        val backend = ShizukuClipboardBackend(runtime)
        backend.start { }

        val ready = backend.probe()
        assertEquals(CapabilityState.READY, ready.readState)

        runtime.authorized = false
        val after = backend.probe()
        assertTrue(after.readState != CapabilityState.READY)
        assertEquals(ShizukuErrorCodes.NOT_AUTHORIZED, after.errorCode)

        val health = backend.health()
        assertTrue(health.state != BackendHealthState.HEALTHY)
        assertEquals(ShizukuErrorCodes.NOT_AUTHORIZED, health.errorCode)
    }

    private companion object {
        val ALLOWED_USER_SERVICE_METHODS = setOf(
            "destroy",
            "binderDied",
        )

        val ALLOWED_SESSION_METHODS = setOf(
            "readText",
            "writeText",
            "addChangedListener",
            "removeChangedListener",
            "pingHealth",
        )

        const val TRANSACTION_PREFIX = "TRANSACTION_"

        val ALLOWED_TRANSACTION_FIELDS = setOf(
            "TRANSACTION_READ",
            "TRANSACTION_WRITE",
            "TRANSACTION_ADD_LISTENER",
            "TRANSACTION_REMOVE_LISTENER",
            "TRANSACTION_PING",
            "TRANSACTION_DESTROY",
            "TRANSACTION_ON_CHANGED",
            "TRANSACTION_ON_CLIPBOARD_DIED",
        )

        val ADAPTER_METHOD_NAMES = setOf(
            "getPrimaryClip",
            "setPrimaryClip",
            "addPrimaryClipChangedListener",
            "removePrimaryClipChangedListener",
        )
    }
}
