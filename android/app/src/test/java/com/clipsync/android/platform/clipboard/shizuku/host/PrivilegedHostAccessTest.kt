package com.clipsync.android.platform.clipboard.shizuku.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedHostAccessTest {
    @Test
    fun `only ClipSync package name is accepted`() {
        assertTrue(PrivilegedHostAccess.packageAllowed("com.clipsync.android"))
        assertFalse(PrivilegedHostAccess.packageAllowed("moe.shizuku.privileged.api"))
        assertFalse(PrivilegedHostAccess.packageAllowed(null))
    }

    @Test
    fun `empty package query without clipSyncUid rejects an app uid`() {
        assertFalse(
            PrivilegedHostAccess.callerOwnsPackage(
                packageName = "com.clipsync.android",
                callingUid = 10417,
                hostUid = 2000,
                packagesForUid = emptySet(),
                clipSyncUid = null,
            ),
        )
    }

    @Test
    fun `empty package query with matching clipSyncUid allows`() {
        assertTrue(
            PrivilegedHostAccess.callerOwnsPackage(
                packageName = "com.clipsync.android",
                callingUid = 10417,
                hostUid = 2000,
                packagesForUid = emptySet(),
                clipSyncUid = 10417,
            ),
        )
    }

    @Test
    fun `empty package query with mismatched clipSyncUid rejects`() {
        assertFalse(
            PrivilegedHostAccess.callerOwnsPackage(
                packageName = "com.clipsync.android",
                callingUid = 10417,
                hostUid = 2000,
                packagesForUid = emptySet(),
                clipSyncUid = 10418,
            ),
        )
    }

    @Test
    fun `package query still allows when clipSyncUid is stale`() {
        assertTrue(
            PrivilegedHostAccess.callerOwnsPackage(
                packageName = "com.clipsync.android",
                callingUid = 10419,
                hostUid = 2000,
                packagesForUid = setOf("com.clipsync.android"),
                clipSyncUid = 10417,
            ),
        )
    }

    @Test
    fun `host uid is allowed even when the package query is empty`() {
        assertTrue(
            PrivilegedHostAccess.callerOwnsPackage(
                packageName = "com.clipsync.android",
                callingUid = 2000,
                hostUid = 2000,
                packagesForUid = emptySet(),
                clipSyncUid = null,
            ),
        )
        assertTrue(
            PrivilegedHostAccess.uidMayUseHost(
                callingUid = 2000,
                hostUid = 2000,
                packagesForUid = emptySet(),
                clipSyncUid = null,
            ),
        )
    }

    @Test
    fun `non-empty package query must list ClipSync`() {
        assertFalse(
            PrivilegedHostAccess.callerOwnsPackage(
                packageName = "com.clipsync.android",
                callingUid = 10417,
                hostUid = 2000,
                packagesForUid = setOf("com.android.shell"),
                clipSyncUid = null,
            ),
        )
        assertTrue(
            PrivilegedHostAccess.callerOwnsPackage(
                packageName = "com.clipsync.android",
                callingUid = 10417,
                hostUid = 2000,
                packagesForUid = setOf("com.clipsync.android"),
                clipSyncUid = null,
            ),
        )
    }

    @Test
    fun `uidMayUseHost is fail-closed when the package query is empty`() {
        assertFalse(
            PrivilegedHostAccess.uidMayUseHost(
                callingUid = 10417,
                hostUid = 2000,
                packagesForUid = emptySet(),
                clipSyncUid = null,
            ),
        )
        assertTrue(
            PrivilegedHostAccess.uidMayUseHost(
                callingUid = 10417,
                hostUid = 2000,
                packagesForUid = emptySet(),
                clipSyncUid = 10417,
            ),
        )
        assertFalse(
            PrivilegedHostAccess.uidMayUseHost(
                callingUid = 10417,
                hostUid = 2000,
                packagesForUid = emptySet(),
                clipSyncUid = 10418,
            ),
        )
        assertTrue(
            PrivilegedHostAccess.uidMayUseHost(
                callingUid = 10417,
                hostUid = 2000,
                packagesForUid = setOf("com.clipsync.android"),
                clipSyncUid = null,
            ),
        )
        assertFalse(
            PrivilegedHostAccess.uidMayUseHost(
                callingUid = 10417,
                hostUid = 2000,
                packagesForUid = setOf("com.other.app"),
                clipSyncUid = null,
            ),
        )
    }

    @Test
    fun `client match falls back from uid+pid to uid`() {
        val clients = listOf(
            PrivilegedHostAccess.ClientKey(10417, 11275),
            PrivilegedHostAccess.ClientKey(10418, 99),
        )
        assertEquals(
            PrivilegedHostAccess.ClientKey(10417, 11275),
            PrivilegedHostAccess.matchClient(10417, 11275, clients),
        )
        assertEquals(
            PrivilegedHostAccess.ClientKey(10417, 11275),
            PrivilegedHostAccess.matchClient(10417, 0, clients),
        )
        assertNull(PrivilegedHostAccess.matchClient(10417, 99999, clients))
        assertNull(PrivilegedHostAccess.matchClient(1, 1, clients))
    }
}
