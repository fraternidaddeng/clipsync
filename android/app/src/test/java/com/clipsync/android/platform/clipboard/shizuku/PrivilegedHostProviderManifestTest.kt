package com.clipsync.android.platform.clipboard.shizuku

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.platform.clipboard.shizuku.host.PrivilegedHostConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The 特权直读 (privileged read) channel only ever worked on paper unless the
 * ContentProvider registered for [PrivilegedHostConstants.PROVIDER_AUTHORITY] is
 * ClipSyncShizukuProvider. The UserService child hands its clipboard binder back
 * through `provider.call("sendUserService")` (PrivilegedUserServiceStarter.sendBinder),
 * and only [ClipSyncShizukuProvider] overrides `call()` to service that method. If the
 * manifest is ever reverted to the stock `rikka.shizuku.ShizukuProvider`, that call is
 * ignored, the child's attach fails, and every read ends in PRIV_HOST_USERSERVICE_DEAD
 * even though the host process pings alive — the exact regression this test guards.
 */
@RunWith(RobolectricTestRunner::class)
class PrivilegedHostProviderManifestTest {
    @Test
    fun `host authority is served by ClipSyncShizukuProvider so sendUserService is handled`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        val provider =
            context.packageManager.resolveContentProvider(
                PrivilegedHostConstants.PROVIDER_AUTHORITY,
                0,
            )

        assertNotNull(
            "no provider registered for ${PrivilegedHostConstants.PROVIDER_AUTHORITY}",
            provider,
        )
        assertEquals(ClipSyncShizukuProvider::class.java.name, provider!!.name)
    }
}
