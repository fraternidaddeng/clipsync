package com.clipsync.android.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The connect-failure classifier is a pure function over an errno (supplied as plain ints, the
 * way Android would read them from OsConstants) with an exception-type fallback; the primary
 * reason folds a whole attempt into the one explanation the failure screen shows.
 */
class UnreachableReasonsTest {
    // Bionic's values, as an Android device would hand them over.
    private val errnos =
        ConnectErrnos(
            connectionRefused = 111,
            timedOut = 110,
            networkUnreachable = 101,
            hostUnreachable = 113,
        )

    @Test
    fun `each connect errno maps to its reason and unrelated errnos to nothing`() {
        assertEquals(UnreachableReason.REFUSED, UnreachableReasons.fromErrno(111, errnos))
        assertEquals(UnreachableReason.TIMEOUT, UnreachableReasons.fromErrno(110, errnos))
        assertEquals(UnreachableReason.NO_ROUTE, UnreachableReasons.fromErrno(101, errnos))
        assertEquals(UnreachableReason.HOST_UNREACHABLE, UnreachableReasons.fromErrno(113, errnos))
        // EACCES: real, but says nothing about reachability.
        assertNull(UnreachableReasons.fromErrno(13, errnos))
    }

    @Test
    fun `the exception type fallback reads refusal anywhere in the cause chain`() {
        // OkHttp's wrapper hides the JDK's "Connection refused" one level down.
        val wrapped = ConnectException("Failed to connect to /192.168.1.23:47654")
        wrapped.initCause(ConnectException("Connection refused: connect"))
        assertEquals(UnreachableReason.REFUSED, UnreachableReasons.fromExceptionType(wrapped))

        assertEquals(UnreachableReason.UNKNOWN, typeOf(ConnectException("no detail")))
        assertEquals(UnreachableReason.TIMEOUT, typeOf(SocketTimeoutException("connect timed out")))
        assertEquals(UnreachableReason.HOST_UNREACHABLE, typeOf(NoRouteToHostException()))
        assertEquals(UnreachableReason.NO_ROUTE, typeOf(UnknownHostException("pc.local")))
        assertEquals(UnreachableReason.UNKNOWN, typeOf(SocketException("reset")))
        assertEquals(UnreachableReason.UNKNOWN, typeOf(IOException("odd")))
    }

    @Test
    fun `a recognised errno wins over the exception type, an unrecognised one defers to it`() {
        val refusedLooking = ConnectException("Connection refused")
        assertEquals(UnreachableReason.TIMEOUT, UnreachableReasons.classify(refusedLooking, errno = 110, errnos))
        assertEquals(UnreachableReason.REFUSED, UnreachableReasons.classify(refusedLooking, errno = 13, errnos))
        assertEquals(UnreachableReason.REFUSED, UnreachableReasons.classify(refusedLooking, errno = null, errnos))
        assertEquals(UnreachableReason.REFUSED, UnreachableReasons.classify(refusedLooking, errno = 110, errnos = null))
    }

    @Test
    fun `the primary reason prefers refused, then timeout, then routing, then unknown`() {
        assertEquals(
            UnreachableReason.REFUSED,
            primaryOf(UnreachableReason.TIMEOUT, UnreachableReason.REFUSED, UnreachableReason.NO_ROUTE),
        )
        assertEquals(
            UnreachableReason.TIMEOUT,
            primaryOf(UnreachableReason.NO_ROUTE, UnreachableReason.TIMEOUT, UnreachableReason.UNKNOWN),
        )
        assertEquals(
            UnreachableReason.HOST_UNREACHABLE,
            primaryOf(UnreachableReason.NO_ROUTE, UnreachableReason.HOST_UNREACHABLE),
        )
        assertEquals(UnreachableReason.NO_ROUTE, primaryOf(UnreachableReason.NO_ROUTE))
        assertEquals(UnreachableReason.UNKNOWN, primaryOf(UnreachableReason.UNKNOWN))
        assertEquals(UnreachableReason.UNKNOWN, primaryOf())
    }

    private fun typeOf(exception: IOException) = UnreachableReasons.fromExceptionType(exception)

    private fun primaryOf(vararg reasons: UnreachableReason) = UnreachableReasons.primary(reasons.toList())
}
