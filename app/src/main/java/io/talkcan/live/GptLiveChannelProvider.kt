package io.talkcan.live

import io.talkcan.audio.ChannelInputAcceptance
import io.talkcan.channel.capability.CapabilityAcquisition
import io.talkcan.channel.capability.CapabilityAvailability
import io.talkcan.channel.capability.CapabilityAvailabilityResult
import io.talkcan.channel.capability.CapabilityKey
import io.talkcan.channel.capability.CapabilityLease
import io.talkcan.channel.capability.CapabilityOperationResult
import io.talkcan.channel.capability.ChannelCapability
import io.talkcan.channel.capability.LiveConversationCapability
import io.talkcan.channel.capability.LiveConversationRequest
import io.talkcan.channel.capability.LiveConversationSession
import io.talkcan.model.ChannelConfigurationField
import io.talkcan.model.ChannelConfigurationMigrationStep
import io.talkcan.model.ChannelConfigurationProvider
import io.talkcan.model.ChannelImplementationDescriptor
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelImplementationProvider
import io.talkcan.model.ChannelInteractionMode
import io.talkcan.model.ChannelPreparationTraits
import io.talkcan.model.ChannelPresentationMetadata
import io.talkcan.model.ChannelProviderError
import io.talkcan.model.ChannelRuntimeConstructionRequest
import io.talkcan.model.ChannelRuntimeConstructionResult
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.model.ProviderConfigurationResult
import io.talkcan.model.ProviderRevisionFingerprint
import io.talkcan.model.ValidatedChannelConfiguration
import io.talkcan.service.ChannelExecutionStatus
import io.talkcan.service.ChannelPreparationAvailability
import io.talkcan.service.ChannelPreparationReason
import io.talkcan.service.ChannelRuntime
import io.talkcan.service.ChannelRuntimeSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Host-native conversational assistant. Transport, audio, credentials, and tools stay behind its lease. */
class GptLiveChannelProvider : ChannelImplementationProvider {
    override val fingerprint = ProviderRevisionFingerprint("gpt-live-1-channel-1")
    override val descriptor = ChannelImplementationDescriptor(
        implementationId = ID,
        presentation = ChannelPresentationMetadata(
            label = "GPT-Live",
            summary = "Full-duplex voice assistant. Click Talk to start or stop.",
            unavailableMessage = "Configure the OpenAI account in this channel's settings.",
        ),
        configuration = Configuration,
        configurationFields = listOf(
            ChannelConfigurationField.TextField("backend_model", "Backend model", "Model for reasoning and app tools."),
            ChannelConfigurationField.ChoiceField(
                "voice", "Live voice", choices = VOICES.map { ChannelConfigurationField.ChoiceField.Choice(it, it) },
            ),
            ChannelConfigurationField.TextField(
                "instructions", "Conversation instructions", "Conversation style and when to delegate.", multiline = true,
            ),
            ChannelConfigurationField.BooleanField(
                "allow_channel_control", "Allow channel switching",
                "The assistant can change the active channel. It cannot transmit, delete channels, or change credentials.",
            ),
            ChannelConfigurationField.BooleanField(
                "allow_channel_read", "Allow reading channel files",
                "Share text from channel-mounted folders with OpenAI when requested. Host secrets are never exposed.",
            ),
        ),
        requiredCapabilities = setOf(ChannelCapability.LiveConversation),
        preparationTraits = ChannelPreparationTraits(false),
        interactionMode = ChannelInteractionMode.FULL_DUPLEX,
    )

    override suspend fun constructRuntime(request: ChannelRuntimeConstructionRequest): ChannelRuntimeConstructionResult =
        ChannelRuntimeConstructionResult.Success(Runtime(request))

    private class Runtime(private val request: ChannelRuntimeConstructionRequest) : ChannelRuntime {
        override val id = request.definition.id
        private val mutableSnapshot = MutableStateFlow(ChannelRuntimeSnapshot(
            id = id,
            name = request.definition.name,
            implementationId = ID,
            enabled = request.definition.enabled,
            preparation = ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.ProviderInitialising),
            executionStatus = ChannelExecutionStatus.IDLE,
            summary = "Click Talk to start a conversation",
        ))
        override val snapshot = mutableSnapshot.asStateFlow()
        override val readinessRefreshIntervalMillis = 500L
        private var lease: CapabilityLease<LiveConversationCapability>? = null
        private var session: LiveConversationSession? = null
        private var closed = false

        override suspend fun prepareInput(): ChannelInputAcceptance =
            ChannelInputAcceptance.Refused("Click Talk to start this full-duplex conversation.")

        override suspend fun handleSos() {
            if (closed) return
            stopSession()
            when (val acquired = request.capabilities.acquire(CapabilityKey.LiveConversation)) {
                is CapabilityAcquisition.Available -> {
                    lease = acquired.lease
                    val config = request.configuration.payload.toJsonObject()
                    val outcome = acquired.lease.use { port ->
                        port.start(LiveConversationRequest(
                            configuration = LiveSessionConfiguration(
                                backendModel = config.getString("backend_model"),
                                voice = config.getString("voice"),
                                instructions = config.getString("instructions"),
                            ),
                            allowChannelControl = config.getBoolean("allow_channel_control"),
                            allowChannelRead = config.getBoolean("allow_channel_read"),
                        ))
                    }
                    if (outcome is CapabilityOperationResult.Success) {
                        session = outcome.value
                    } else {
                        stopSession()
                        mutableSnapshot.value = mutableSnapshot.value.copy(
                            executionStatus = ChannelExecutionStatus.FAILED,
                            summary = "Unable to start live conversation. Check the API key, connection, and audio route.",
                        )
                        return
                    }
                }
                else -> {
                    mutableSnapshot.value = mutableSnapshot.value.copy(
                        executionStatus = ChannelExecutionStatus.FAILED,
                        summary = "Configure the OpenAI account in this channel's settings.",
                    )
                    return
                }
            }
            refreshReadiness()
        }

        override suspend fun refreshReadiness() {
            if (closed) return
            val state = session?.state?.value
            if (state != null && state.phase in setOf(LiveSessionPhase.IDLE, LiveSessionPhase.FAILED)) {
                stopSession()
            }
            val availability = request.capabilities.availability(CapabilityKey.LiveConversation)
            val ready = availability is CapabilityAvailabilityResult.State &&
                availability.availability == CapabilityAvailability.Available
            mutableSnapshot.value = mutableSnapshot.value.copy(
                preparation = if (ready) ChannelPreparationAvailability.Available else
                    ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeReadiness(
                        "Configure the OpenAI account in this channel's settings.",
                    )),
                executionStatus = when (state?.phase) {
                    LiveSessionPhase.CONNECTING, LiveSessionPhase.CLOSING -> ChannelExecutionStatus.PROCESSING
                    LiveSessionPhase.ACTIVE -> ChannelExecutionStatus.RECORDING
                    LiveSessionPhase.FAILED -> ChannelExecutionStatus.FAILED
                    else -> ChannelExecutionStatus.IDLE
                },
                summary = when (state?.phase) {
                    LiveSessionPhase.CONNECTING -> "Connecting to GPT-Live"
                    LiveSessionPhase.ACTIVE -> "Microphone active · listening and speaking"
                    LiveSessionPhase.CLOSING -> "Ending live conversation"
                    LiveSessionPhase.FAILED -> state.message ?: "Live conversation failed"
                    else -> if (ready) "Full duplex · click Talk to start" else "Configure the OpenAI account in this channel's settings"
                },
            )
        }

        override suspend fun close() {
            if (closed) return
            closed = true
            stopSession()
        }

        private suspend fun stopSession() {
            val current = session
            session = null
            try {
                current?.close()
            } finally {
                val acquired = lease
                lease = null
                acquired?.release()
            }
        }
    }

    private object Configuration : ChannelConfigurationProvider {
        override val implementationId = ID
        override val currentSchemaVersion = 1
        override fun defaultPayload(): OpaqueJsonObject = OpaqueJsonObject.fromJsonObject(JSONObject()
            .put("backend_model", "gpt-5.6-luna")
            .put("voice", "marin")
            .put("instructions", "You are Talkcan's voice assistant. Speak briefly in the user's language. " +
                "Delegate all questions about channels and all app operations to the backend. " +
                "Only report an action as complete when a tool confirms it.")
            .put("allow_channel_control", false)
            .put("allow_channel_read", false))

        override fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult {
            if (schemaVersion != currentSchemaVersion) return ProviderConfigurationResult.Failure(
                ChannelProviderError.UnsupportedSchemaVersion(ID, schemaVersion, currentSchemaVersion),
            )
            val json = payload.toJsonObject()
            val valid = json.keys().asSequence().toSet() == FIELDS &&
                (json.opt("backend_model") as? String)?.matches(Regex("[a-zA-Z0-9._-]{1,80}")) == true &&
                json.opt("voice") in VOICES &&
                (json.opt("instructions") as? String)?.let { it.isNotBlank() && it.length <= 4_000 } == true &&
                json.opt("allow_channel_control") is Boolean && json.opt("allow_channel_read") is Boolean
            return if (valid) ProviderConfigurationResult.Success(ValidatedChannelConfiguration(ID, schemaVersion, payload))
            else ProviderConfigurationResult.Failure(ChannelProviderError.InvalidConfiguration(
                ID, schemaVersion, "Use a valid backend model, supported voice, instructions up to 4000 characters, and tool permissions.",
            ))
        }

        override fun migrateStep(fromSchemaVersion: Int, payload: OpaqueJsonObject): ChannelConfigurationMigrationStep =
            ChannelConfigurationMigrationStep.Failure(
                ChannelProviderError.UnsupportedSchemaVersion(ID, fromSchemaVersion, currentSchemaVersion),
            )
    }

    companion object {
        val ID = ChannelImplementationId("builtin:gpt-live")
        private val VOICES = listOf("marin", "quartz", "ripple", "vesper", "willow", "stone", "gleam", "meridian",
            "bossa", "tempo", "beacon", "delta", "cinder")
        private val FIELDS = setOf("backend_model", "voice", "instructions", "allow_channel_control", "allow_channel_read")
    }
}
