package com.clipsync.android.platform.clipboard.overlay

internal class ManualOverlayPollScheduler : OverlayPollScheduler {
    var started: Boolean = false
        private set
    var lastIntervalMillis: Long? = null
        private set
    var startCount: Int = 0
        private set
    var stopCount: Int = 0
        private set

    private var onTick: (() -> Unit)? = null

    override fun start(intervalMillis: Long, onTick: () -> Unit) {
        started = true
        startCount += 1
        lastIntervalMillis = intervalMillis
        this.onTick = onTick
    }

    override fun stop() {
        started = false
        stopCount += 1
        onTick = null
    }

    fun fire() {
        onTick?.invoke()
    }
}
