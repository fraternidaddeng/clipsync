package com.clipsync.android.platform.clipboard.shizuku.host

/**
 * Who may talk to [PrivilegedHostService]. The host only serves ClipSync.
 * An empty PackageManager uid query is deny unless the caller is the host
 * or ClipSync's ApplicationInfo.uid.
 */
internal object PrivilegedHostAccess {
    fun packageAllowed(packageName: String?): Boolean =
        packageName == PrivilegedHostConstants.PACKAGE_NAME

    fun callerOwnsPackage(
        packageName: String,
        callingUid: Int,
        hostUid: Int,
        packagesForUid: Set<String>,
        clipSyncUid: Int?,
    ): Boolean {
        return uidAllowed(callingUid, hostUid, packagesForUid, clipSyncUid, packageName)
    }

    fun uidMayUseHost(
        callingUid: Int,
        hostUid: Int,
        packagesForUid: Set<String>,
        clipSyncUid: Int?,
    ): Boolean {
        return uidAllowed(
            callingUid,
            hostUid,
            packagesForUid,
            clipSyncUid,
            PrivilegedHostConstants.PACKAGE_NAME,
        )
    }

    private fun uidAllowed(
        callingUid: Int,
        hostUid: Int,
        packagesForUid: Set<String>,
        clipSyncUid: Int?,
        requiredPackage: String,
    ): Boolean {
        if (callingUid == hostUid) {
            return true
        }
        if (clipSyncUid != null && callingUid == clipSyncUid) {
            return true
        }
        if (packagesForUid.isEmpty()) {
            return false
        }
        return requiredPackage in packagesForUid
    }

    fun matchClient(uid: Int, pid: Int, clients: List<ClientKey>): ClientKey? {
        return clients.firstOrNull { it.uid == uid && it.pid == pid }
            ?: clients.firstOrNull { it.uid == uid && (pid == 0 || it.pid == 0) }
    }

    data class ClientKey(val uid: Int, val pid: Int)
}
