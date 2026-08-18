package com.clipsync.android.sync

import android.util.Log

/**
 * Production [SyncLogger]: logcat event tags only. Engine callers pass stable
 * event names and error-code details — never clipboard content, nonces, proofs,
 * or secrets (audited in docs/stage-6-security-audit.md). Without this logger
 * a silently-dying background loop leaves no forensic trail on the device.
 */
object AndroidSyncLogger : SyncLogger {
    private const val TAG = "ClipSyncSync"

    override fun event(name: String, detail: String) {
        Log.i(TAG, "$name $detail")
    }
}
