package com.clipsync.android.platform

import android.content.Context
import android.net.wifi.WifiManager
import com.clipsync.android.pairing.BeaconExpectation
import com.clipsync.android.pairing.BeaconListener
import com.clipsync.android.pairing.BeaconStatus
import com.clipsync.android.pairing.DiscoveryBeacons
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * Hears the Windows listener's discovery broadcasts on UDP 47653 while a pairing is in hand.
 * Bound only between [start] and [stop]; a multicast lock is held for exactly that window so
 * Wi-Fi chipsets that filter broadcast frames still deliver them. Every failure (port taken,
 * no Wi-Fi service, socket errors) reads as [BeaconStatus.Unavailable] and is otherwise
 * silent: the beacon is a diagnostic aid, never a step the pairing depends on.
 */
class UdpDiscoveryListener(
    context: Context,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BeaconListener {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val session = AtomicReference<Session?>(null)

    private class Session(
        val socket: DatagramSocket,
        val lock: WifiManager.MulticastLock?,
    ) {
        var job: Job? = null

        fun close() {
            job?.cancel()
            runCatching { socket.close() }
            runCatching { lock?.takeIf { it.isHeld }?.release() }
        }
    }

    override fun start(
        expectation: BeaconExpectation,
        onStatus: (BeaconStatus) -> Unit,
    ) {
        stop()
        val socket =
            runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(DiscoveryBeacons.UDP_PORT))
                }
            }.getOrNull()
        if (socket == null) {
            onStatus(BeaconStatus.Unavailable)
            return
        }
        val lock =
            runCatching {
                appContext
                    .getSystemService(WifiManager::class.java)
                    ?.createMulticastLock(LOCK_TAG)
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }.getOrNull()
        val current = Session(socket, lock)
        session.set(current)
        onStatus(BeaconStatus.Listening)
        current.job =
            scope.launch {
                receiveLoop(current, expectation) { status ->
                    if (session.get() === current) {
                        onStatus(status)
                    }
                }
            }
    }

    override fun stop() {
        session.getAndSet(null)?.close()
    }

    private fun CoroutineScope.receiveLoop(
        current: Session,
        expectation: BeaconExpectation,
        onStatus: (BeaconStatus) -> Unit,
    ) {
        val buffer = ByteArray(DiscoveryBeacons.MAX_DATAGRAM_BYTES)
        try {
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                current.socket.receive(packet)
                sighting(packet, expectation)?.let(onStatus)
            }
        } catch (_: IOException) {
            // stop() closed the socket, or the network dropped it: either way the session is over.
        }
    }

    /** The datagram as evidence: only an IPv4 sender carrying exactly the expected identity counts. */
    private fun sighting(
        packet: DatagramPacket,
        expectation: BeaconExpectation,
    ): BeaconStatus.Seen? {
        val source = (packet.address as? Inet4Address)?.hostAddress
        val beacon = DiscoveryBeacons.parse(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
        return if (source != null && beacon != null && DiscoveryBeacons.matches(beacon, expectation)) {
            BeaconStatus.Seen(source, beacon.port)
        } else {
            null
        }
    }

    private companion object {
        const val LOCK_TAG = "clipsync-pairing"
    }
}
