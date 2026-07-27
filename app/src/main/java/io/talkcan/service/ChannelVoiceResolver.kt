package io.talkcan.service

import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.model.ChannelCatalogueSnapshot
import io.talkcan.voice.VoiceProfileId

/**
 * Host policy resolving the semantic `default` synthesis voice for one channel request.
 *
 * Every call reads the requesting instance's current catalogue definition, so a host
 * synthesis voice preference change affects the next resolution without replacing the
 * channel runtime generation or touching the provider configuration payload. A request
 * that has already obtained a complete style path keeps it for its lifetime; the string
 * returned here is immutable and detached from later catalogue updates.
 *
 * Resolution rules:
 * - No assignment — including an instance whose definition is absent — resolves to the
 *   verified shipped application fallback.
 * - An explicit assignment resolves only through [synthesisStylePath], which yields a
 *   complete path exactly when the profile is available and compatible.
 * - An explicit assignment that is unknown, missing, corrupt, incompatible, or otherwise
 *   unavailable resolves to null and never silently substitutes the fallback or any
 *   other profile; the synthesis adapter maps that to typed NOT_CONFIGURED behavior.
 *
 * Profile identities and filesystem paths remain host-side: they never enter Lua
 * configuration, runtime state, or UI action payloads.
 */
internal class ChannelVoiceResolver(
    private val channelCatalogue: () -> ChannelCatalogueSnapshot,
    private val synthesisStylePath: (VoiceProfileId) -> String?,
    private val fallbackProfileId: VoiceProfileId,
) {
    /**
     * Resolves the style document path for a `voice="default"` request from [identity],
     * or null when the request cannot be configured.
     */
    fun resolveStylePath(identity: CapabilityScopeIdentity): String? {
        val assignment = channelCatalogue().definitions
            .find { it.id == identity.channelInstanceId }
            ?.hostPreferences
            ?.synthesisVoiceProfileId
            ?: fallbackProfileId
        return synthesisStylePath(assignment)
    }
}
