package com.clipsync.android.sync

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [SyncTransport] over an established bt1 channel, so the unchanged [SyncEngine] session
 * runs on Bluetooth exactly as it does on a WebSocket. Each v1 text message is exactly one
 * encrypted bt1 frame. bt1 has no wire-level close: a graceful close simply closes the
 * stream, and the peer sees [TransportFrame.Closed]. Any frame violation (bad declared
 * length, failed tag) is fatal per docs/protocol-bt1.md section 5 — the link dies and the
 * reconnect loop owns recovery, mirroring the IP path.
 */
class Bt1SyncTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val channel: Bt1SecureChannel,
    private val closeLink: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SyncTransport {
    private val writeMutex = Mutex()

    @Volatile
    private var disposed = false

    override suspend fun receive(): TransportFrame =
        withContext(ioDispatcher) {
            val payload =
                try {
                    Bt1StreamFraming.readEncryptedPayload(input)
                } catch (_: IOException) {
                    null
                } ?: return@withContext closed()
            // A failed tag means loss, reorder, tampering, or a peer bug: the channel state
            // is unrecoverable, so the link dies and the supervisor redials.
            val plaintext = channel.decryptor.tryDecryptPayload(payload) ?: return@withContext closed()
            TransportFrame.Text(String(plaintext, StandardCharsets.UTF_8))
        }

    override suspend fun send(text: String) {
        if (disposed) {
            throw IOException("bt1 link is closed")
        }
        val frame =
            try {
                channel.encryptor.encryptFrame(text.toByteArray(StandardCharsets.UTF_8))
            } catch (failure: IllegalArgumentException) {
                throw IOException("bt1 frame plaintext is outside the accepted window", failure)
            } catch (failure: IllegalStateException) {
                throw IOException("bt1 send counter is exhausted", failure)
            }
        withContext(ioDispatcher) {
            writeMutex.withLock {
                try {
                    Bt1StreamFraming.writeEncryptedFrame(output, frame)
                } catch (failure: IOException) {
                    dispose()
                    throw failure
                }
            }
        }
    }

    override suspend fun close(
        code: Int,
        reason: String,
    ) {
        // bt1 defines no post-handshake close message; closing the stream is the close.
        dispose()
    }

    override fun dispose() {
        disposed = true
        runCatching { closeLink() }
    }

    private fun closed(): TransportFrame {
        dispose()
        return TransportFrame.Closed
    }
}
