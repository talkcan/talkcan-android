package io.talkcan.service

internal sealed interface PttDispatchDecision {
    val channelId: String

    data class Dispatch(override val channelId: String) : PttDispatchDecision
    data class ErrorBeep(override val channelId: String) : PttDispatchDecision
}

/**
 * Decides PTT admission exclusively from the registry's generic, ordered runtime projection.
 * Both ordinary and car PTT supply the same snapshot before reserving input.
 */
internal fun decidePttDispatch(
    runtimeSnapshot: RuntimeRegistrySnapshot,
    targetChannelId: String = runtimeSnapshot.activeChannelId,
): PttDispatchDecision? {
    val channelId = targetChannelId.takeIf(String::isNotBlank) ?: return null
    val preparation = runtimeSnapshot.entries
        .firstOrNull { it.id == channelId }
        ?.preparation
        ?: ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.UnknownInstance)

    return when (preparation) {
        ChannelPreparationAvailability.Available,
        is ChannelPreparationAvailability.Recoverable -> PttDispatchDecision.Dispatch(channelId)
        is ChannelPreparationAvailability.Unavailable -> PttDispatchDecision.ErrorBeep(channelId)
    }
}
