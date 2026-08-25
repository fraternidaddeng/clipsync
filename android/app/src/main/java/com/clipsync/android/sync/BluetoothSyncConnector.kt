package com.clipsync.android.sync

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.storage.SyncSettingsStore
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The real bt1 fallback dial (ADR 0005 phase 3): connects an RFCOMM socket to the
 * user-selected bonded device, runs the bt1 client handshake with the existing pair secret,
 * and hands the session engine a [Bt1SyncTransport]. Every prerequisite is re-checked per
 * dial — the toggle, the selected device, the runtime permission, the adapter state — so
 * revoking any of them takes effect on the next reconnect cycle without a restart. No
 * discovery, no scanning: only the device the user already bonded in system settings and
 * explicitly selected in preferences is ever dialed.
 */
class BluetoothSyncConnector(
    private val context: Context,
    private val settings: SyncSettingsStore,
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BluetoothFallbackDialer {
    override suspend fun dial(
        localDeviceId: String,
        peer: PairedPeer,
        pairSecret: ByteArray,
    ): SyncTransport? {
        if (!settings.bluetoothFallbackEnabled) {
            return null
        }
        val address = settings.bluetoothPeerAddress ?: return null
        if (!hasConnectPermission(context)) {
            return null
        }
        val adapter = adapter(context) ?: return null
        if (!adapter.isEnabled) {
            return null
        }
        val secret = pairSecret.copyOf()
        return try {
            connectAndHandshake(adapter, address, localDeviceId, peer, secret)
        } finally {
            secret.fill(0)
        }
    }

    private suspend fun connectAndHandshake(
        adapter: BluetoothAdapter,
        address: String,
        localDeviceId: String,
        peer: PairedPeer,
        secret: ByteArray,
    ): SyncTransport? =
        withContext(ioDispatcher) {
            val socket = openSocket(adapter, address) ?: return@withContext null
            try {
                coroutineScope {
                    // Blocking Bluetooth IO cannot be cancelled directly; the watchdog closes
                    // the socket instead, which unblocks connect/read with an IOException.
                    // This also enforces the 30-second handshake abort the protocol requires.
                    val watchdog =
                        launch {
                            delay(handshakeTimeoutMs)
                            runCatching { socket.close() }
                        }
                    try {
                        openTransport(socket, localDeviceId, peer, secret)
                    } finally {
                        watchdog.cancel()
                    }
                }
            } catch (_: IOException) {
                runCatching { socket.close() }
                null
            } catch (_: Bt1HandshakeException) {
                // The listener refused (auth failure, rate limit, schema): the supervisor
                // treats this like an unreachable host and backs off.
                runCatching { socket.close() }
                null
            } catch (_: SecurityException) {
                runCatching { socket.close() }
                null
            }
        }

    private fun openSocket(
        adapter: BluetoothAdapter,
        address: String,
    ): BluetoothSocket? =
        try {
            adapter
                .getRemoteDevice(address)
                .createRfcommSocketToServiceRecord(UUID.fromString(SERVICE_UUID))
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    private fun openTransport(
        socket: BluetoothSocket,
        localDeviceId: String,
        peer: PairedPeer,
        secret: ByteArray,
    ): SyncTransport {
        socket.connect()
        val channel =
            Bt1ClientHandshake.run(
                input = socket.inputStream,
                output = socket.outputStream,
                localDeviceId = localDeviceId,
                peerDeviceId = peer.deviceId,
                trustEpoch = peer.trustEpoch,
                pairSecret = secret,
            )
        return Bt1SyncTransport(
            input = socket.inputStream,
            output = socket.outputStream,
            channel = channel,
            closeLink = { runCatching { socket.close() } },
        )
    }

    companion object {
        /**
         * ClipSync's own SDP service UUID; must stay byte-identical to the Windows
         * RfcommContract.ServiceUuid and is frozen once a release ships.
         */
        const val SERVICE_UUID = "5f7f1d9c-2d6b-4e8d-9f1b-ef9ed49b0bec"

        /** Covers the RFCOMM connect plus the four-message bt1 handshake. */
        const val HANDSHAKE_TIMEOUT_MS = 12_000L

        /** True when this process may call connect-time Bluetooth APIs right now. */
        fun hasConnectPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        fun adapter(context: Context): BluetoothAdapter? =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
}
