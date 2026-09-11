package io.talkcan.channel.capability

import io.talkcan.live.LiveSessionConfiguration
import io.talkcan.live.LiveSessionState
import kotlinx.coroutines.flow.StateFlow

/** A full-duplex conversation owns capture and playback until its session is closed. */
interface LiveConversationCapability : ChannelCapabilityPort {
    suspend fun start(request: LiveConversationRequest): CapabilityOperationResult<LiveConversationSession>
}

data class LiveConversationRequest(
    val configuration: LiveSessionConfiguration,
    val allowChannelControl: Boolean,
    val allowChannelRead: Boolean,
    val keyboardProfile: String?,
)

/** Session authority remains bound to the capability lease and runtime generation. */
interface LiveConversationSession {
    val state: StateFlow<LiveSessionState>
    suspend fun close()
}
