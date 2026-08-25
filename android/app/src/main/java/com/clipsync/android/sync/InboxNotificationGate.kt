package com.clipsync.android.sync

/**
 * Fixed-window flood cap for the inbox notification surface (hardening): a runaway or
 * malicious peer pushing hundreds of clips must not turn the shade into a wall — Android
 * would rate-limit the posts anyway and drop some silently. The first [maxPerWindow]
 * per-event notifications of a window post as usual; everything after coalesces into one
 * counting summary notification. Recording and auto-apply are never gated — only which
 * notification shape announces them.
 */
class InboxNotificationGate(
    private val maxPerWindow: Int = DEFAULT_MAX_PER_WINDOW,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    init {
        require(maxPerWindow > 0) { "The per-window budget must be positive." }
        require(windowMillis > 0) { "The window must be positive." }
    }

    sealed interface Verdict {
        /** Post the per-event notification as usual. */
        data object Post : Verdict

        /** Update the single coalesced notification with this window's suppressed count. */
        data class Coalesce(
            val suppressedInWindow: Int,
        ) : Verdict
    }

    private val lock = Any()
    private var windowStartMs = 0L
    private var posted = 0
    private var suppressed = 0

    fun admit(nowMs: Long): Verdict =
        synchronized(lock) {
            if (nowMs - windowStartMs >= windowMillis) {
                windowStartMs = nowMs
                posted = 0
                suppressed = 0
            }
            if (posted < maxPerWindow) {
                posted++
                Verdict.Post
            } else {
                suppressed++
                Verdict.Coalesce(suppressed)
            }
        }

    companion object {
        /** Per-event notifications allowed per window before coalescing. */
        const val DEFAULT_MAX_PER_WINDOW = 5

        /** Window length; a backlog burst stays one counting card instead of a wall. */
        const val DEFAULT_WINDOW_MILLIS = 30_000L
    }
}
