package com.clipsync.android.sync

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A fake device-wide proxy environment: a live listener standing in for the proxy that a
 * Wi-Fi proxy setting or Clash/Surge in system-proxy mode would inject, installed as the
 * JVM-default [ProxySelector]. Unlike the JDK default selector (which never proxies
 * loopback), this one routes EVERY URI through the fake proxy, so a client that still
 * consulted the selector could not connect anywhere without leaving a trace here.
 *
 * The listener accepts and immediately closes each connection: a proxy on the peer path
 * must visibly break the tunnel, never silently pass traffic through.
 */
class FakeProxyEnvironment : AutoCloseable {
    private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val connections = AtomicInteger(0)
    private val previousSelector: ProxySelector? = ProxySelector.getDefault()
    private val proxyAddress: SocketAddress = InetSocketAddress("127.0.0.1", listener.localPort)

    /** Every URI the poisoned default selector was asked to route. */
    val selectedUris = CopyOnWriteArrayList<URI>()

    @Volatile
    private var closed = false

    private val acceptLoop =
        thread(isDaemon = true, name = "fake-proxy-accept") {
            while (!closed) {
                try {
                    listener.accept().use { connections.incrementAndGet() }
                } catch (_: IOException) {
                    // The listener was closed; the loop is done.
                }
            }
        }

    init {
        ProxySelector.setDefault(
            object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    // OpenJDK consults the default selector once more at the raw-socket layer
                    // with a `socket://` URI (SocksSocketImpl deciding whether a SOCKS proxy
                    // applies). That JDK-internal query is not OkHttp behaviour, an HTTP proxy
                    // can never apply there, and Android's runtime does not make it at all, so
                    // it is answered "direct" and left unrecorded. What these tests pin down is
                    // that OkHttp itself never asks how to route an http(s)/ws(s) request.
                    if (uri.scheme == "socket") {
                        return listOf(Proxy.NO_PROXY)
                    }
                    selectedUris += uri
                    return listOf(Proxy(Proxy.Type.HTTP, proxyAddress))
                }

                override fun connectFailed(
                    uri: URI,
                    sa: SocketAddress,
                    ioe: IOException,
                ) = Unit
            },
        )
    }

    /** How many TCP connections actually reached the fake proxy. */
    val proxyConnectionCount: Int
        get() = connections.get()

    override fun close() {
        ProxySelector.setDefault(previousSelector)
        closed = true
        listener.close()
        acceptLoop.join(5_000)
    }
}
