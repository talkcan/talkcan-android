package io.talkcan.service

import io.talkcan.channel.capability.CapabilityFailureReason
import io.talkcan.channel.capability.CapabilityLeaseTermination
import io.talkcan.channel.capability.CapabilityOperationResult
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.CapabilityUnavailableReason
import io.talkcan.channel.capability.LiveConversationCapability
import io.talkcan.channel.capability.LiveConversationRequest
import io.talkcan.channel.capability.LiveConversationSession
import io.talkcan.live.GptLiveChannelProvider
import io.talkcan.live.GptLiveSession
import io.talkcan.live.LiveAudioDevice
import io.talkcan.live.LiveChannelReader
import io.talkcan.live.LiveChannelTools
import io.talkcan.live.LiveConversationView
import io.talkcan.live.LiveSessionPhase
import io.talkcan.live.LiveSessionState
import io.talkcan.live.LiveSettingsRepository
import io.talkcan.model.ChannelCatalogueSnapshot
import io.talkcan.secret.ProtectedSecretResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One service-owned Live session, independent of the selected half-duplex channel. */
internal class ServiceLiveConversationManager(
    private val scope: CoroutineScope,
    private val settings: LiveSettingsRepository,
    private val catalogue: () -> ChannelCatalogueSnapshot,
    private val snapshots: () -> List<ChannelRuntimeSnapshot>,
    private val selectChannel: suspend (String) -> Boolean,
    private val audioFactory: () -> LiveAudioDevice,
    private val readerFactory: (() -> Boolean) -> LiveChannelReader,
    private val beforeStart: suspend () -> Boolean,
    private val onStopped: suspend () -> Unit,
) {
    private val mutex = Mutex()
    @Volatile private var active: Entry? = null
    @Volatile private var shuttingDown = false
    private val mutableView = MutableStateFlow(LiveConversationView())
    val view = mutableView.asStateFlow()
    private val mutableConversations = MutableStateFlow<Map<String, LiveConversationView>>(emptyMap())
    val conversations = mutableConversations.asStateFlow()

    private fun publishView(value: LiveConversationView) {
        mutableView.value = value
        value.channelId?.let { id ->
            mutableConversations.value = mutableConversations.value + (id to value)
        }
    }
    val hasSession: Boolean get() = active != null
    val acceptsTools: Boolean get() = active?.permitted?.get() == true && !shuttingDown

    fun capability(identity: CapabilityScopeIdentity): LiveConversationCapability? =
        if (settings.state.value.keyConfigured && !shuttingDown) Port(identity) else null

    fun reportUnavailable(channelId: String, message: String) {
        if (active == null) publishView(LiveConversationView(
            channelId = channelId,
            channelName = catalogue().definitions.firstOrNull { it.id == channelId }?.name,
            state = LiveSessionState(LiveSessionPhase.FAILED, message),
        ))
    }

    suspend fun closeCurrent() {
        active?.let { finish(it) }
    }

    suspend fun shutdown() {
        shuttingDown = true
        closeCurrent()
    }

    private suspend fun start(
        identity: CapabilityScopeIdentity,
        request: LiveConversationRequest,
    ): CapabilityOperationResult<LiveConversationSession> = mutex.withLock {
        if (shuttingDown) return CapabilityOperationResult.Closed
        if (active != null) return CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.RESOURCE_BUSY)
        val definition = catalogue().definitions.firstOrNull { it.id == identity.channelInstanceId }
            ?: return CapabilityOperationResult.Closed
        if (!definition.enabled || definition.implementationId != GptLiveChannelProvider.ID) {
            return CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.POLICY_REFUSED)
        }
        val payload = definition.configPayload.toJsonObject()
        if (request.allowChannelControl != payload.optBoolean("allow_channel_control", false) ||
            request.allowChannelRead != payload.optBoolean("allow_channel_read", false)
        ) return CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.POLICY_REFUSED)
        if (!beforeStart()) return CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY)
        val permitted = AtomicBoolean(true)
        val isCurrent = { permitted.get() && active?.permitted === permitted && !shuttingDown }
        val reader = readerFactory(isCurrent)
        val tools = LiveChannelTools(
            catalogue = catalogue,
            snapshots = snapshots,
            selectChannel = selectChannel,
            reader = reader,
            allowControl = request.allowChannelControl,
            allowRead = request.allowChannelRead,
            sessionIsActive = isCurrent,
        )
        val created = settings.useKey { apiKey ->
            GptLiveSession(scope, audioFactory(), tools, request.configuration, apiKey)
        }
        if (created !is ProtectedSecretResult.Success) {
            reader.close()
            onStopped()
            return CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
        }
        val entry = Entry(identity, created.value, reader, permitted)
        active = entry
        publishView(LiveConversationView(definition.id, definition.name, LiveSessionState(LiveSessionPhase.CONNECTING)))
        entry.observer = scope.launch {
            var observedStart = false
            entry.engine.state.collect { state ->
                if (active !== entry || !entry.permitted.get()) return@collect
                if (state.phase != LiveSessionPhase.IDLE) observedStart = true
                if (!observedStart) return@collect
                entry.state.value = state
                publishView(LiveConversationView(definition.id, definition.name, state))
                if (state.phase == LiveSessionPhase.IDLE || state.phase == LiveSessionPhase.FAILED) {
                    scope.launch { finish(entry) }
                }
            }
        }
        entry.starter = scope.launch {
            try {
                entry.engine.start()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (active === entry) publishView(mutableView.value.copy(
                    state = LiveSessionState(LiveSessionPhase.FAILED, "Could not start the live conversation"),
                ))
                scope.launch { finish(entry) }
            }
        }
        CapabilityOperationResult.Success(object : LiveConversationSession {
            override val state = entry.state.asStateFlow()
            override suspend fun close() = finish(entry)
        })
    }

    private suspend fun finish(entry: Entry) {
        if (!entry.permitted.compareAndSet(true, false)) {
            entry.finished.await()
            return
        }
        withContext(NonCancellable) {
            try {
                if (entry.state.value.phase != LiveSessionPhase.FAILED) {
                    entry.state.value = entry.state.value.copy(phase = LiveSessionPhase.CLOSING)
                }
                if (active === entry && mutableView.value.state.phase != LiveSessionPhase.FAILED) {
                    publishView(mutableView.value.copy(state = mutableView.value.state.copy(phase = LiveSessionPhase.CLOSING)))
                }
                entry.engine.close()
                entry.starter?.cancelAndJoin()
                entry.observer?.cancelAndJoin()
                entry.reader.close()
            } finally {
                mutex.withLock {
                    if (active === entry) {
                        active = null
                        val final = entry.engine.state.value
                        publishView(mutableView.value.copy(state = if (final.phase == LiveSessionPhase.FAILED) final else
                            mutableView.value.state.copy(phase = LiveSessionPhase.IDLE, message = final.message)))
                        entry.state.value = mutableView.value.state
                    }
                }
                entry.finished.complete(Unit)
                if (!shuttingDown) onStopped()
            }
        }
    }

    private inner class Port(private val identity: CapabilityScopeIdentity) : LiveConversationCapability, GenerationCapabilityResource {
        override suspend fun start(request: LiveConversationRequest) = this@ServiceLiveConversationManager.start(identity, request)
        override suspend fun onGenerationTermination(identity: CapabilityScopeIdentity, termination: CapabilityLeaseTermination) {
            active?.takeIf { it.identity == identity }?.let { finish(it) }
        }
    }

    private class Entry(
        val identity: CapabilityScopeIdentity,
        val engine: GptLiveSession,
        val reader: LiveChannelReader,
        val permitted: AtomicBoolean,
        val finished: CompletableDeferred<Unit> = CompletableDeferred(),
        val state: MutableStateFlow<LiveSessionState> = MutableStateFlow(LiveSessionState(LiveSessionPhase.CONNECTING)),
        var observer: Job? = null,
        var starter: Job? = null,
    )
}
