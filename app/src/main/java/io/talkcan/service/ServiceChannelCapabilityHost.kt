package io.talkcan.service

import io.talkcan.channel.SleepwalkerTextOutputService
import io.talkcan.channel.TextOutputAvailability
import io.talkcan.channel.capability.CapabilityFailureReason
import io.talkcan.channel.capability.CapabilityKey
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.CapabilityUnavailableReason
import io.talkcan.channel.capability.ChannelCapabilityHost
import io.talkcan.channel.capability.ChannelCapabilityPort
import io.talkcan.channel.capability.HostedCapabilityAcquisition
import io.talkcan.channel.capability.CapabilityLeaseTermination
import io.talkcan.channel.capability.TextOutputCapability
import io.talkcan.channel.capability.TranscriptionCapability
import io.talkcan.channel.capability.SynthesisCapability
import io.talkcan.channel.capability.AudioOperationCapability
import io.talkcan.channel.capability.CapabilityAvailability
import io.talkcan.channel.capability.DeferredAudioPlaybackCapability
import io.talkcan.channel.capability.GenericHttpCapability
import io.talkcan.channel.capability.LiveConversationCapability
import kotlinx.coroutines.withTimeoutOrNull

internal interface GenerationCapabilityResource {
    suspend fun onGenerationTermination(identity: CapabilityScopeIdentity, termination: CapabilityLeaseTermination)
}

/**
 * Composition root for instance-scoped capability leases.
 *
 * Every factory receives the requesting instance/generation identity. The only shared resource
 * here is [textOutputService]; a lease may detach its own work but can never close that service.
 */
internal class ServiceChannelCapabilityHost(
    private val textOutputService: SleepwalkerTextOutputService,
    private val transcription: (CapabilityScopeIdentity) -> TranscriptionCapability?,
    private val synthesis: (CapabilityScopeIdentity) -> SynthesisCapability?,
    private val audioOperation: (CapabilityScopeIdentity) -> AudioOperationCapability?,
    private val deferredAudioPlayback: (CapabilityScopeIdentity) -> DeferredAudioPlaybackCapability? = { null },
    private val networkHttp: (CapabilityScopeIdentity) -> GenericHttpCapability? = { null },
    private val liveConversation: (CapabilityScopeIdentity) -> LiveConversationCapability? = { null },
) : ChannelCapabilityHost {
    override suspend fun onGenerationTermination(
        identity: CapabilityScopeIdentity,
        termination: CapabilityLeaseTermination,
    ) {
        if (termination != CapabilityLeaseTermination.REVOKED) return
        // Scheduling leases are deliberately short-lived: a queued playback entry can remain
        // after its lease is released. Revoke every host-owned generation resource directly so
        // scope revocation still drains those entries. Each resource performs its own idempotent
        // instance+generation filtering.
        deferredAudioPlayback(identity)?.let { resource ->
            if (resource is GenerationCapabilityResource) {
                try {
                    resource.onGenerationTermination(identity, termination)
                } catch (_: Exception) {
                    // Resource cleanup is idempotent; a host cleanup failure must not block scope revocation.
                }
            }
        }
        // 10.6: drain queued keyboard-output operations for the revoked generation so a
        // predecessor's not-yet-effective work cannot outlive its scope or fire after a
        // successor readies. Idempotent; touches only this instance+generation, never siblings.
        try {
            textOutputService.revokeKeyboardGeneration(identity)
        } catch (_: Exception) {
            // Revocation is idempotent; a host cleanup failure must not block scope revocation.
        }
    }

    override suspend fun availability(
        identity: CapabilityScopeIdentity,
        key: CapabilityKey<*>,
    ): CapabilityAvailability = when (key) {
        CapabilityKey.TextOutput -> textOutputAvailability()
        CapabilityKey.Transcription -> transcription(identity).availability()
        CapabilityKey.Synthesis -> synthesis(identity).availability()
        CapabilityKey.AudioOperation -> audioOperation(identity).availability()
        CapabilityKey.DeferredAudioPlayback -> deferredAudioPlayback(identity).availability()
        CapabilityKey.NetworkHttp -> networkHttp(identity).availability()
        CapabilityKey.LiveConversation -> liveConversation(identity).availability()
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : ChannelCapabilityPort> acquire(
        identity: CapabilityScopeIdentity,
        key: CapabilityKey<T>,
    ): HostedCapabilityAcquisition<T> = (
        when (key) {
            CapabilityKey.TextOutput -> textOutputAcquisition(identity)
            CapabilityKey.Transcription -> availableOrUnavailable(transcription(identity), identity)
            CapabilityKey.Synthesis -> availableOrUnavailable(synthesis(identity), identity)
            CapabilityKey.AudioOperation -> availableOrUnavailable(audioOperation(identity), identity)
            CapabilityKey.DeferredAudioPlayback -> availableOrUnavailable(deferredAudioPlayback(identity), identity)
            CapabilityKey.NetworkHttp -> availableOrUnavailable(networkHttp(identity), identity)
            CapabilityKey.LiveConversation -> availableOrUnavailable(liveConversation(identity), identity)
        }
    ) as HostedCapabilityAcquisition<T>

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
        identity: CapabilityScopeIdentity,
        key: CapabilityKey<T>,
        timeoutMillis: Long,
    ): HostedCapabilityAcquisition<T> {
        if (key != CapabilityKey.TextOutput) return acquire(identity, key)
        val preparation = withTimeoutOrNull(timeoutMillis) { textOutputService.prepare() }
            ?: io.talkcan.channel.TextOutputPreparation.TimedOut
        return (when (preparation) {
            io.talkcan.channel.TextOutputPreparation.Available -> {
                if (textOutputService.isReadyForDelivery()) {
                    available(textOutputService.capabilityFor(identity.channelInstanceId), identity)
                } else {
                    HostedCapabilityAcquisition.Recoverable(CapabilityUnavailableReason.HOST_NOT_READY)
                }
            }
            io.talkcan.channel.TextOutputPreparation.TimedOut ->
                HostedCapabilityAcquisition.Failed(CapabilityFailureReason.PREPARATION_FAILED)
            is io.talkcan.channel.TextOutputPreparation.Failed ->
                HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY)
        }) as HostedCapabilityAcquisition<T>
    }
    private fun textOutputAcquisition(
        identity: CapabilityScopeIdentity,
    ): HostedCapabilityAcquisition<TextOutputCapability> = when (textOutputService.availability.value) {
        TextOutputAvailability.Available -> available(
            textOutputService.capabilityFor(identity.channelInstanceId),
            identity,
        )
        TextOutputAvailability.Preparing,
        TextOutputAvailability.Unavailable,
        -> HostedCapabilityAcquisition.Recoverable(CapabilityUnavailableReason.HOST_NOT_READY)
        TextOutputAvailability.Closed ->
            HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY)
    }


    private fun textOutputAvailability(): CapabilityAvailability = when (textOutputService.availability.value) {
        TextOutputAvailability.Available -> CapabilityAvailability.Available
        TextOutputAvailability.Preparing,
        TextOutputAvailability.Unavailable -> CapabilityAvailability.Recoverable
        TextOutputAvailability.Closed -> CapabilityAvailability.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY)
    }

    private fun ChannelCapabilityPort?.availability(): CapabilityAvailability =
        if (this == null) CapabilityAvailability.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY)
        else CapabilityAvailability.Available

    private fun <T : ChannelCapabilityPort> availableOrUnavailable(
        port: T?,
        identity: CapabilityScopeIdentity,
    ): HostedCapabilityAcquisition<T> = port?.let { available(it, identity) }
        ?: HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY)

    private fun <T : ChannelCapabilityPort> available(
        port: T,
        identity: CapabilityScopeIdentity,
    ): HostedCapabilityAcquisition<T> = HostedCapabilityAcquisition.Available(port) { termination ->
        if (port is GenerationCapabilityResource &&
            (termination == CapabilityLeaseTermination.REVOKED || port is LiveConversationCapability)
        ) {
            port.onGenerationTermination(identity, termination)
        }
    }
}
