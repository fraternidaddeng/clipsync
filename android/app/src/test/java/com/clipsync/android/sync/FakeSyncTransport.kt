package com.clipsync.android.sync

import com.clipsync.android.protocol.ParsedSyncMessage
import com.clipsync.android.protocol.SyncMessages
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout

/** In-memory transport so handshake tests never open a socket. */
class FakeSyncTransport : ISyncTransport {
    private val inbound = Channel<TransportFrame>(Channel.UNLIMITED)
    private val outbound = Channel<String>(Channel.UNLIMITED)

    @Volatile
    var closeReason: String? = null
        private set

    override suspend fun receive(): TransportFrame = inbound.receive()

    override suspend fun sendText(payload: String) {
        outbound.send(payload)
    }

    override suspend fun close(reason: String) {
        closeReason = reason
        inbound.trySend(TransportFrame.Closed)
    }

    suspend fun peerSends(json: String) {
        inbound.send(TransportFrame.Text(json))
    }

    fun peerSendsFrame(frame: TransportFrame) {
        inbound.trySend(frame)
    }

    suspend fun awaitSent(): ParsedSyncMessage =
        withTimeout(5_000) { SyncMessages.parse(outbound.receive()) }

    fun tryTakeSent(): ParsedSyncMessage? =
        outbound.tryReceive().getOrNull()?.let { SyncMessages.parse(it) }

    fun drainSent(): List<ParsedSyncMessage> {
        val frames = mutableListOf<ParsedSyncMessage>()
        while (true) {
            frames += tryTakeSent() ?: break
        }
        return frames
    }
}
