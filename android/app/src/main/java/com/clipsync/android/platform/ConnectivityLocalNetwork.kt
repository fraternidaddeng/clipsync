package com.clipsync.android.platform

import android.content.Context
import android.net.ConnectivityManager
import com.clipsync.android.pairing.LocalIpv4
import com.clipsync.android.pairing.LocalNetworkSource
import java.net.Inet4Address

/**
 * The phone's IPv4 on the active network, read through ConnectivityManager (needs only
 * ACCESS_NETWORK_STATE). Every failure — no network, no IPv4 address, a refused query —
 * reads as null so the pairing failure screen states "unknown" instead of guessing.
 */
class ConnectivityLocalNetwork(
    private val context: Context,
) : LocalNetworkSource {
    override fun currentIpv4(): LocalIpv4? =
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val network = manager?.activeNetwork
            val link = network?.let(manager::getLinkProperties)
            link
                ?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                ?.let { linkAddress ->
                    linkAddress.address.hostAddress?.let { LocalIpv4(it, linkAddress.prefixLength) }
                }
        }.getOrNull()
}
