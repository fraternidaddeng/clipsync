package com.clipsync.android.pairing

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Why one QR host could not be reached at the TCP/TLS level. */
enum class UnreachableReason {
    /** The host answered, but nothing listens on the port (ECONNREFUSED). */
    REFUSED,

    /** The connect attempt got no answer at all — typically a firewall or AP isolation. */
    TIMEOUT,

    /** No route to that network, or the name could not be resolved. */
    NO_ROUTE,

    /** The network is there but that host is not (EHOSTUNREACH). */
    HOST_UNREACHABLE,

    UNKNOWN,
}

/**
 * The connect errnos the classifier tells apart. Supplied by the caller (Android reads them
 * from `OsConstants`) so [UnreachableReasons.fromErrno] stays a pure function on the JVM.
 */
data class ConnectErrnos(
    val connectionRefused: Int,
    val timedOut: Int,
    val networkUnreachable: Int,
    val hostUnreachable: Int,
)

object UnreachableReasons {
    /** errno → reason; null when the errno says nothing about reachability. */
    fun fromErrno(
        errno: Int,
        errnos: ConnectErrnos,
    ): UnreachableReason? =
        when (errno) {
            errnos.connectionRefused -> UnreachableReason.REFUSED
            errnos.timedOut -> UnreachableReason.TIMEOUT
            errnos.networkUnreachable -> UnreachableReason.NO_ROUTE
            errnos.hostUnreachable -> UnreachableReason.HOST_UNREACHABLE
            else -> null
        }

    /**
     * Fallback on the exception type when no errno is available. Only connectivity failures
     * (see [PinnedTls.isConnectivityFailure]) reach this, so a [SocketTimeoutException] here
     * is always a connect-phase timeout, never a stalled approval wait.
     */
    fun fromExceptionType(exception: IOException): UnreachableReason =
        when (exception) {
            is ConnectException ->
                if (anyMessageContains(exception, "refused")) {
                    UnreachableReason.REFUSED
                } else {
                    UnreachableReason.UNKNOWN
                }
            is SocketTimeoutException -> UnreachableReason.TIMEOUT
            is NoRouteToHostException -> UnreachableReason.HOST_UNREACHABLE
            is UnknownHostException -> UnreachableReason.NO_ROUTE
            else -> UnreachableReason.UNKNOWN
        }

    /** The errno verdict when one is known and recognised; otherwise the type-based fallback. */
    fun classify(
        exception: IOException,
        errno: Int?,
        errnos: ConnectErrnos?,
    ): UnreachableReason {
        if (errno != null && errnos != null) {
            fromErrno(errno, errnos)?.let { return it }
        }
        return fromExceptionType(exception)
    }

    /**
     * One reason to explain a whole failed attempt. A refusal anywhere means the address is
     * right and nothing listens, which beats a timeout elsewhere; a timeout beats a routing
     * failure because it points at a firewall the user can actually fix.
     */
    fun primary(reasons: Collection<UnreachableReason>): UnreachableReason =
        when {
            UnreachableReason.REFUSED in reasons -> UnreachableReason.REFUSED
            UnreachableReason.TIMEOUT in reasons -> UnreachableReason.TIMEOUT
            UnreachableReason.HOST_UNREACHABLE in reasons -> UnreachableReason.HOST_UNREACHABLE
            UnreachableReason.NO_ROUTE in reasons -> UnreachableReason.NO_ROUTE
            else -> UnreachableReason.UNKNOWN
        }

    private fun anyMessageContains(
        exception: Throwable,
        needle: String,
    ): Boolean {
        var current: Throwable? = exception
        while (current != null) {
            if (current.message?.contains(needle, ignoreCase = true) == true) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
