package com.clipsync.android.pairing

import android.system.ErrnoException
import android.system.OsConstants
import java.io.IOException

/**
 * Classifies a connectivity failure by Android's errno when the cause chain carries one
 * (libcore wraps every failed connect in an [ErrnoException]) and by exception type
 * otherwise. `OsConstants` is read only after an [ErrnoException] was actually found, so
 * plain-JVM unit tests — whose sockets throw JDK exceptions — never touch the Android stubs.
 */
internal object AndroidConnectFailures {
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
        return UnreachableReasons.classify(exception, errno, constants)
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
