package com.clipsync.android.pairing

import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import java.io.IOException

/**
 * Classifies a connectivity failure by Android's errno when the cause chain carries one
 * (libcore wraps every failed connect in an [ErrnoException]) and by exception type
 * otherwise. `OsConstants` is read only after an [ErrnoException] was actually found, so
 * plain-JVM unit tests — whose sockets throw JDK exceptions — never touch the Android stubs.
 */
internal object AndroidConnectFailures {
    private const val TAG = "ClipSyncPairing"

    private val errnos: ConnectErrnos by lazy {
        ConnectErrnos(
            connectionRefused = OsConstants.ECONNREFUSED,
            timedOut = OsConstants.ETIMEDOUT,
            networkUnreachable = OsConstants.ENETUNREACH,
            hostUnreachable = OsConstants.EHOSTUNREACH,
        )
    }

    fun classify(exception: IOException): UnreachableReason {
        val errno = errnoOf(exception)
        val constants = if (errno == null) null else runCatching { errnos }.getOrNull()
        val reason = UnreachableReasons.classify(exception, errno, constants)
        // Types + errno only — never the exception message (it can carry the peer host).
        // android.util.Log is an unmocked stub on the JVM unit-test classpath; swallow
        // that so classify stays usable from PairingConfirmClientTest.
        runCatching { Log.i(TAG, "classify reason=$reason errno=$errno types=${typeChain(exception)}") }
        return reason
    }

    private fun typeChain(exception: Throwable): String {
        val names = mutableListOf<String>()
        var current: Throwable? = exception
        while (current != null) {
            names += current.javaClass.simpleName
            current = current.cause
        }
        return names.joinToString(">")
    }

    private fun errnoOf(exception: Throwable): Int? {
        var current: Throwable? = exception
        while (current != null) {
            if (current is ErrnoException) {
                return current.errno
            }
            current = current.cause
        }
        return null
    }
}
