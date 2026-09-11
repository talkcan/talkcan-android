package io.talkcan.live

data class LiveSettingsState(
    val keyConfigured: Boolean = false,
    val sosChannelId: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
)

data class LiveConversationView(
    val channelId: String? = null,
    val channelName: String? = null,
    val state: LiveSessionState = LiveSessionState(LiveSessionPhase.IDLE),
) {
    val isRunning: Boolean
        get() = state.phase == LiveSessionPhase.CONNECTING || state.phase == LiveSessionPhase.ACTIVE ||
            state.phase == LiveSessionPhase.CLOSING
}
