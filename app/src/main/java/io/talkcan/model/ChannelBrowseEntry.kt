package io.talkcan.model

import io.talkcan.service.ChannelPreparationAvailability

/**
 * Host-owned Android Auto browse projection for one persisted channel instance.
 * The identifier and order originate in the catalogue and remain stable even if the
 * implementation provider cannot currently produce a runtime.
 */
data class ChannelBrowseEntry(
    val id: String,
    val name: String,
    val statusKind: ChannelStatusKind,
    val pendingCount: Int,
    val isPlayable: Boolean,
    val recoveryMessage: String? = null,
    val playbackPaused: Boolean = false,
)

enum class ChannelStatusKind { Active, Ready, Standby, Unavailable }

/**
 * Projects every runtime aggregate entry in its existing catalogue order. Every persisted entry
 * is playable because Android Auto uses the play command to select it; preparation remains
 * separately represented by status and recovery metadata and is enforced only when PTT starts.
 */
fun projectChannelBrowseEntries(
    appState: AppState,
    pendingCounts: Map<String, Int> = emptyMap(),
): List<ChannelBrowseEntry> = appState.channels.map { channel ->
    val isReady = channel.preparation is ChannelPreparationAvailability.Available
    val reason = when (val preparation = channel.preparation) {
        ChannelPreparationAvailability.Available -> null
        is ChannelPreparationAvailability.Recoverable -> preparation.reason.message
        is ChannelPreparationAvailability.Unavailable -> preparation.reason.message
    }
    ChannelBrowseEntry(
        id = channel.id,
        name = channel.name,
        statusKind = when {
            !isReady -> ChannelStatusKind.Unavailable
            appState.activeChannelId == channel.id -> ChannelStatusKind.Active
            else -> ChannelStatusKind.Ready
        },
        pendingCount = pendingCounts[channel.id] ?: 0,
        playbackPaused = channel.playbackPaused,
        isPlayable = true,
        recoveryMessage = reason?.let { "$it. Recover or remove this channel on your phone." },
    )
}

/** Stable full catalogue ordering shared by the phone and Android Auto. */
fun orderedChannelIds(appState: AppState): List<String> =
    projectChannelBrowseEntries(appState).map(ChannelBrowseEntry::id)

/**
 * Selects a catalogue ID by stable order, saturating at the bounds. Unavailable IDs intentionally
 * remain in the sequence because preparation state does not affect selection eligibility.
 */
fun selectChannelByOffset(
    orderedChannelIds: List<String>,
    currentChannelId: String,
    offset: Int,
): String? {
    if (orderedChannelIds.isEmpty()) return null
    val currentIndex = orderedChannelIds.indexOf(currentChannelId).let { index ->
        if (index < 0) 0 else index
    }
    return (currentIndex + offset).coerceIn(0, orderedChannelIds.lastIndex)
        .let(orderedChannelIds::get)
}