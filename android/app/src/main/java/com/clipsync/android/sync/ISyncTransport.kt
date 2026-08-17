package com.clipsync.android.sync

/**
 * One received WebSocket message, already assembled from fragments.
 * Mirrors windows/ClipSync.Peer/Transport/ISyncTransport.cs so the session
 * engine can run against a fake in unit tests.
 */
sealed interface TransportFrame {
    data class Text(val payload: String) : TransportFrame

    data object Closed : TransportFrame

    data object TooLarge : TransportFrame

    data object Binary : TransportFrame
}

/**
 * Transport abstraction for one sync session. The engine never talks to OkHttp
 * directly; tests inject a fake.
 */
interface ISyncTransport {
    suspend fun receive(): TransportFrame

    suspend fun sendText(payload: String)

    suspend fun close(reason: String)
}
