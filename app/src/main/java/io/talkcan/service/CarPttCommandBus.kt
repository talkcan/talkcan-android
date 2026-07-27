package io.talkcan.service

/**
 * Car -> phone command bus: a thin single-listener relay so the Android Auto
 * `MediaSession.Callback` (running on the media service's main thread) can ask
 * the foreground service to actuate without holding a direct reference. The
 * listener is the [PttForegroundService]; the callbacks are best-effort and
 * failure-safe (design D9) — failed commands surface back via the
 * `NotReady` playback state.
 */
internal object CarPttCommandBus {
    private var listener: CarPttCommandListener? = null

    fun setListener(listener: CarPttCommandListener?) {
        this.listener = listener
    }

    fun startTelecomCapture() {
        listener?.onCarPttStart()
    }

    fun release() {
        listener?.onCarPttRelease()
    }

    fun setActiveChannel(id: String) {
        listener?.onCarSetActiveChannel(id)
    }

    /**
     * Advance the active channel by [offset] positions in the stable channel
     * ordering, saturating at the bounds (no wraparound — see spec
     * `car-contextual-skip-controls`). Negative [offset] retreats the
     * channel cursor.
     */
    fun setActiveChannelOffset(offset: Int) {
        listener?.onCarSetActiveChannelOffset(offset)
    }

    /**
     * Skip the currently-playing inbound message on the active channel.
     * **Future wiring**: until inbound backlog tracking lands
     * (`pending unheard message state` is not yet implemented),
     * the service implementation no-ops safely (design D9).
     */
    fun skipCurrentMessage() {
        listener?.onCarSkipMessage()
    }

    /**
     * Replay the last heard inbound message on the active channel. **Future
     * wiring**: same as [skipCurrentMessage] — no-ops today pending the
     * `last-heard message state` capability.
     */
    fun replayLastHeard() {
        listener?.onCarReplayMessage()
    }
}

internal interface CarPttCommandListener {
    fun onCarPttStart()
    fun onCarPttRelease()
    fun onCarSetActiveChannel(id: String) {}
    fun onCarSetActiveChannelOffset(offset: Int) {}
    fun onCarSkipMessage() {}
    fun onCarReplayMessage() {}
}