package io.talkcan.service

import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelImplementationProviderRegistry
import io.talkcan.model.ChannelProviderResolution
import io.talkcan.model.ChannelRepository
import io.talkcan.model.ChannelRepositoryError
import io.talkcan.model.ChannelRepositoryMutationResult
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.model.ProviderConfigurationResult
import io.talkcan.model.ChannelHostPreferences
import io.talkcan.voice.VoiceProfileAvailability
import io.talkcan.voice.VoiceProfileCatalogue
import io.talkcan.voice.VoiceProfileCompatibility
import io.talkcan.voice.VoiceProfileId

/** Typed reason a channel voice preference mutation was refused. */
internal sealed interface ChannelVoicePreferenceFailure {
    val diagnostic: String

    /** The requested profile ID is not present in the current voice profile catalogue. */
    data class UnknownProfile(val profileId: String, override val diagnostic: String) : ChannelVoicePreferenceFailure

    /** The requested profile is present but unavailable (missing, corrupt, or incompatible document). */
    data class UnavailableProfile(val profileId: String, override val diagnostic: String) : ChannelVoicePreferenceFailure

    /** The requested profile is compatibility-incompatible with the current model. */
    data class IncompatibleProfile(val profileId: String, override val diagnostic: String) : ChannelVoicePreferenceFailure

    /** First assignment of an unverified (untagged import) profile requires explicit acknowledgement. */
    data class UnverifiedRequiresAcknowledgement(
        val profileId: String,
        val displayName: String,
        override val diagnostic: String,
    ) : ChannelVoicePreferenceFailure
}

/**
 * Typed result of a channel voice preference mutation.
 *
 * [Committed] covers both preference-only updates and atomic channel creation with a
 * voice assignment. [VoiceRefused] carries a typed validation failure that leaves the
 * catalogue unchanged. [ChannelRefused] wraps a repository-level failure from the
 * underlying create/update transaction.
 */
internal sealed interface ChannelVoicePreferenceMutation {
    data object Committed : ChannelVoicePreferenceMutation
    data class VoiceRefused(val failure: ChannelVoicePreferenceFailure) : ChannelVoicePreferenceMutation
    data class ChannelRefused(val error: ChannelRepositoryError) : ChannelVoicePreferenceMutation
}

/**
 * Focused owner of provider-backed channel creation, configuration update,
 * playback coordinators. It holds no service reference and does not mutate
 * app state; selection offset dispatch and PTT dispatch remain in the service.
 *
 * Selection notification order is immediate then deferred, with the selection
 * diagnostic emitted only after both notifications. A failed selection notifies
 * neither coordinator.
 *
 * Configuration replacement is triggered atomically through [onConfigurationCommitted]
 * after a valid payload is persisted: the predecessor is stopped, drained, and closed
 * before a fresh generation with the new payload is started.
 */
internal class ServiceChannelManager(
    private val channelRepository: ChannelRepository,
    private val providerRegistry: ChannelImplementationProviderRegistry,
    private val immediateSelection: (channelInstanceId: String) -> Unit,
    private val deferredSelection: (channelInstanceId: String) -> Unit,
    private val newChannelId: () -> String,
    private val log: (String) -> Unit,
    private val onConfigurationCommitted: (channelId: String) -> Unit = {},
    private val voiceProfileCatalogue: () -> VoiceProfileCatalogue? = { null },
) {
    fun createChannel(
        implementationId: ChannelImplementationId,
        name: String,
        payload: OpaqueJsonObject? = null,
        hostPreferences: ChannelHostPreferences = ChannelHostPreferences(),
    ): ChannelRepositoryMutationResult {
        val provider = when (val resolution = providerRegistry.resolve(implementationId)) {
            is ChannelProviderResolution.Available -> resolution.provider
            is ChannelProviderResolution.Missing -> {
                return ChannelRepositoryMutationResult.Failure(
                    ChannelRepositoryError.ProviderMigration(
                        definitionId = "new",
                        error = resolution.error,
                    ),
                )
            }
            is ChannelProviderResolution.Unavailable -> {
                return ChannelRepositoryMutationResult.Failure(
                    ChannelRepositoryError.ProviderMigration(
                        definitionId = "new",
                        error = resolution.error,
                    ),
                )
            }
        }
        return channelRepository.addChannel(
            ChannelDefinition(
                id = newChannelId(),
                name = name,
                implementationId = implementationId,
                enabled = true,
                configSchemaVersion = provider.descriptor.configuration.currentSchemaVersion,
                configPayload = payload ?: provider.descriptor.configuration.defaultPayload(),
                hostPreferences = hostPreferences,
            ),
        )
    }

    /**
     * Atomically creates a channel with an optional voice profile assignment. The provider
     * payload and host preferences commit in one [ChannelRepository.addChannel] transaction
     * under the newly allocated instance ID. Voice validation runs before any catalogue
     * mutation; a refused assignment leaves the catalogue unchanged.
     */
    fun createChannelWithVoice(
        implementationId: ChannelImplementationId,
        name: String,
        payload: OpaqueJsonObject? = null,
        voiceProfileId: String? = null,
        acknowledgeUnverified: Boolean = false,
    ): ChannelVoicePreferenceMutation {
        if (voiceProfileId != null) {
            when (val failure = validateVoiceProfile(voiceProfileId, acknowledgeUnverified)) {
                null -> { /* valid */ }
                else -> return ChannelVoicePreferenceMutation.VoiceRefused(failure)
            }
        }
        val result = createChannel(
            implementationId = implementationId,
            name = name,
            payload = payload,
            hostPreferences = ChannelHostPreferences(
                synthesisVoiceProfileId = voiceProfileId?.let(::VoiceProfileId),
            ),
        )
        return when (result) {
            is ChannelRepositoryMutationResult.Success -> ChannelVoicePreferenceMutation.Committed
            is ChannelRepositoryMutationResult.Failure -> ChannelVoicePreferenceMutation.ChannelRefused(result.error)
        }
    }

    /**
     * Updates only the host-owned synthesis voice preference of an existing channel.
     * Bypasses provider configuration migration and never triggers runtime generation
     * reconciliation. Validation runs before any catalogue mutation; a refused or
     * cancelled assignment leaves the catalogue unchanged.
     *
     * A null [profileId] clears the assignment unconditionally.
     */
    fun updateChannelVoicePreference(
        channelId: String,
        profileId: String?,
        acknowledgeUnverified: Boolean = false,
    ): ChannelVoicePreferenceMutation {
        if (profileId != null) {
            when (val failure = validateVoiceProfile(profileId, acknowledgeUnverified)) {
                null -> { /* valid */ }
                else -> return ChannelVoicePreferenceMutation.VoiceRefused(failure)
            }
        }
        val result = channelRepository.updateChannelHostPreferences(
            channelId,
            ChannelHostPreferences(synthesisVoiceProfileId = profileId?.let(::VoiceProfileId)),
        )
        return when (result) {
            is ChannelRepositoryMutationResult.Success -> ChannelVoicePreferenceMutation.Committed
            is ChannelRepositoryMutationResult.Failure -> ChannelVoicePreferenceMutation.ChannelRefused(result.error)
        }
    }

    /**
     * Validates a voice profile ID against the current catalogue. Returns null when the
     * assignment is permitted, or a typed failure. Validation applies only to a requested
     * new assignment; persisted unavailable selections are never rewritten at load.
     */
    private fun validateVoiceProfile(
        profileId: String,
        acknowledgeUnverified: Boolean,
    ): ChannelVoicePreferenceFailure? {
        val catalogue = voiceProfileCatalogue()
            ?: return ChannelVoicePreferenceFailure.UnknownProfile(
                profileId,
                "Voice profile catalogue is unavailable",
            )
        val id = VoiceProfileId(profileId)
        val summary = catalogue.summaryFor(id)
            ?: return ChannelVoicePreferenceFailure.UnknownProfile(
                profileId,
                "Voice profile '$profileId' is not in the catalogue",
            )
        if (summary.compatibility == VoiceProfileCompatibility.INCOMPATIBLE) {
            return ChannelVoicePreferenceFailure.IncompatibleProfile(
                profileId,
                "Voice profile '${summary.displayName}' is incompatible with the current model",
            )
        }
        if (summary.availability is VoiceProfileAvailability.Unavailable) {
            return ChannelVoicePreferenceFailure.UnavailableProfile(
                profileId,
                "Voice profile '${summary.displayName}' is unavailable: " +
                    (summary.availability as VoiceProfileAvailability.Unavailable).diagnostic,
            )
        }
        if (summary.compatibility == VoiceProfileCompatibility.UNVERIFIED && !acknowledgeUnverified) {
            return ChannelVoicePreferenceFailure.UnverifiedRequiresAcknowledgement(
                profileId,
                summary.displayName,
                "Voice profile '${summary.displayName}' is compatibility-unverified; " +
                    "acknowledge before first assignment",
            )
        }
        return null
    }

    fun updateChannelConfiguration(
        channelId: String,
        payload: OpaqueJsonObject,
    ): ChannelRepositoryMutationResult {
        val definition = channelRepository.catalogueState.value.definitions.find { it.id == channelId }
            ?: return channelRepository.updateChannel(channelId) { it }
        val provider = when (val resolution = providerRegistry.resolve(definition.implementationId)) {
            is ChannelProviderResolution.Available -> resolution.provider
            is ChannelProviderResolution.Missing -> {
                return ChannelRepositoryMutationResult.Failure(
                    ChannelRepositoryError.ProviderMigration(channelId, resolution.error),
                )
            }
            is ChannelProviderResolution.Unavailable -> {
                return ChannelRepositoryMutationResult.Failure(
                    ChannelRepositoryError.ProviderMigration(channelId, resolution.error),
                )
            }
        }
        // Reject invalid payloads before touching the catalogue.
        when (val validation = provider.descriptor.configuration.migrateAndValidate(
            provider.descriptor.configuration.currentSchemaVersion,
            payload,
        )) {
            is ProviderConfigurationResult.Failure -> {
                return ChannelRepositoryMutationResult.Failure(
                    ChannelRepositoryError.ProviderMigration(channelId, validation.error),
                )
            }
            is ProviderConfigurationResult.Success -> { /* proceed */ }
        }
        val result = channelRepository.updateChannel(channelId) { old ->
            old.copy(
                configSchemaVersion = provider.descriptor.configuration.currentSchemaVersion,
                configPayload = payload,
            )
        }
        if (result is ChannelRepositoryMutationResult.Success) {
            onConfigurationCommitted(channelId)
        }
        return result
    }

    fun selectChannel(id: String): Boolean {
        val previousId = channelRepository.catalogueState.value.activeChannelId
        val result = channelRepository.selectChannel(id)
        val selected = result is ChannelRepositoryMutationResult.Success
        if (selected) {
            immediateSelection(id)
            deferredSelection(id)
        }
        log(
            "CHANNEL_SELECT requested=$id previous=$previousId selected=$selected " +
                "active=${channelRepository.catalogueState.value.activeChannelId}",
        )
        return selected
    }
}