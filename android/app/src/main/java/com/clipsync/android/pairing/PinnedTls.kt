package com.clipsync.android.pairing

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * TLS that trusts exactly one certificate: the peer whose lowercase SHA-256 fingerprint was
 * pinned at pairing time. Chain and hostname are ignored by design; the pin is the identity.
 * Shared by the pairing confirm call and the peer health probe.
 */
internal object PinnedTls {
    const val PIN_MISMATCH_MARKER = "clipsync.pin.mismatch"

    fun client(pin: String, connectTimeoutMs: Long, readTimeoutMs: Long): OkHttpClient {
        val trustManager = PinnedTrustManager(pin)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // The pin is the whole identity; hostnames are meaningless for LAN IPs.
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun shutdown(client: OkHttpClient) {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    fun isPinRejection(exception: Throwable): Boolean {
        var current: Throwable? = exception
        while (current != null) {
            if (current is CertificateException && current.message == PIN_MISMATCH_MARKER) {
                return true
            }
            current = current.cause
        }
        return false
    }

    fun isConnectivityFailure(exception: IOException): Boolean = when (exception) {
        is ConnectException, is UnknownHostException, is NoRouteToHostException -> true
        // A timeout while WAITING for a response must not roll over to the next host when the
        // request has side effects. Only the connect phase may fail over.
        is SocketTimeoutException -> exception.message?.contains("connect", ignoreCase = true) == true
        is SSLException -> false
        is SocketException -> true
        else -> false
    }

    private class PinnedTrustManager(pin: String) : X509TrustManager {
        private val expected = pin.lowercase()

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("client certificates are not used")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException(PIN_MISMATCH_MARKER)
            val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
            val fingerprint = digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
            if (fingerprint != expected) {
                throw CertificateException(PIN_MISMATCH_MARKER)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
