package com.clipsync.android.notify

enum class InboundNotifyDecision {
    NONE,
    COPY_ACTION,
}

object InboundNotifyPolicy {
    fun decide(autoApplyRemote: Boolean, writeSucceeded: Boolean): InboundNotifyDecision =
        if (!autoApplyRemote || !writeSucceeded) {
            InboundNotifyDecision.COPY_ACTION
        } else {
            InboundNotifyDecision.NONE
        }
}

class SafeNotificationPoster(
    private val notificationsAllowed: () -> Boolean,
    private val post: () -> Unit,
) {
    fun tryPost(): Boolean {
        if (!notificationsAllowed()) {
            return false
        }
        return try {
            post()
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}
