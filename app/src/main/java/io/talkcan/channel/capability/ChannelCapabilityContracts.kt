package io.talkcan.channel.capability
import io.talkcan.model.AgentOperationId
import io.talkcan.model.AgentRunId
import io.talkcan.model.DelayedPlaybackOutcome
import io.talkcan.http.GenericHttpRequest
import io.talkcan.http.GenericHttpResult

/**
 * Stable semantic capabilities that a channel implementation may declare.
 * These names describe host-domain operations; they are not platform handles.
 */
sealed interface ChannelCapability {
    val stableId: String

    data object Transcription : ChannelCapability { override val stableId = "transcription" }
    data object Synthesis : ChannelCapability { override val stableId = "synthesis" }
    data object AudioOperation : ChannelCapability { override val stableId = "audio-operation" }
    data object TextOutput : ChannelCapability { override val stableId = "text-output" }
    data object DeferredAudioPlayback : ChannelCapability { override val stableId = "deferred-audio-playback" }
    data object StorageFiles : ChannelCapability { override val stableId = "storage-files" }
    data object AudioFiles : ChannelCapability { override val stableId = "audio-files" }
    data object NetworkHttp : ChannelCapability { override val stableId = "network.http" }
    data object ProfilesRead : ChannelCapability { override val stableId = "profiles.read" }
    data object SecretsRead : ChannelCapability { override val stableId = "secrets.read" }
    data object WorkQueue : ChannelCapability { override val stableId = "work.queue" }
}

/**
 * A monotonically assigned runtime generation. It is scoped to one channel instance
 * but allocated from a host process-wide counter so that reconstructed registries
 * cannot reuse prior generation IDs.
 */
@JvmInline
value class RuntimeGeneration(val value: Long) {
    init {
        require(value >= 0) { "Runtime generation must be non-negative" }
    }

    companion object {
        private val counter = java.util.concurrent.atomic.AtomicLong(0)

        /**
         * Allocates the next process-wide monotonic generation. The returned
         * value is always greater than every previously allocated generation
         * in this process, even across registry reconstruction.
         */
        fun next(): RuntimeGeneration = RuntimeGeneration(counter.getAndIncrement())
    }
}

/** Identity attached to every capability acquisition, operation, and diagnostic. */
data class CapabilityScopeIdentity(
    val channelInstanceId: String,
    val runtimeGeneration: RuntimeGeneration,
) {
    init {
        require(channelInstanceId.isNotBlank()) { "Channel instance ID must not be blank" }
    }
}

/** The host-visible availability state, deliberately independent of platform state. */
sealed interface CapabilityAvailability {
    data object Available : CapabilityAvailability
    data object Recoverable : CapabilityAvailability
    data class Unavailable(val reason: CapabilityUnavailableReason) : CapabilityAvailability
}

enum class CapabilityUnavailableReason {
    NOT_CONFIGURED,
    MODEL_NOT_READY,
    RESOURCE_BUSY,
    HOST_NOT_READY,
    UNSUPPORTED,
    POLICY_REFUSED,
}

enum class CapabilityDeniedReason {
    UNDECLARED,
}

enum class CapabilityFailureReason {
    HOST_FAILURE,
    PREPARATION_FAILED,
    CLEANUP_FAILED,
    INVALID_REQUEST,
}

enum class CapabilityLeaseState {
    ACTIVE,
    REVOKED,
    RELEASED,
}

enum class CapabilityLeaseTermination {
    REVOKED,
    RELEASED,
}

/**
 * A typed key prevents a capability port from being acquired as a different port.
 * Only the host package defines the keys.
 */
sealed class CapabilityKey<T : ChannelCapabilityPort>(val capability: ChannelCapability) {
    data object Transcription : CapabilityKey<TranscriptionCapability>(ChannelCapability.Transcription)
    data object Synthesis : CapabilityKey<SynthesisCapability>(ChannelCapability.Synthesis)
    data object AudioOperation : CapabilityKey<AudioOperationCapability>(ChannelCapability.AudioOperation)
    data object TextOutput : CapabilityKey<TextOutputCapability>(ChannelCapability.TextOutput)
    data object DeferredAudioPlayback : CapabilityKey<DeferredAudioPlaybackCapability>(ChannelCapability.DeferredAudioPlayback)
    data object NetworkHttp : CapabilityKey<GenericHttpCapability>(ChannelCapability.NetworkHttp)
}

/** A semantic port. It never carries Android, hardware, route, filesystem, or coroutine ownership. */
interface ChannelCapabilityPort

/**
 * Generation-bound authorization for one host-owned agent operation. Its identifiers are opaque
 * to runtimes: only the host may associate them with persistence, clients, or worker ownership.
 */
data class AgentOperationContext(
    val scope: CapabilityScopeIdentity,
    val runId: AgentRunId,
    val operationId: AgentOperationId,
)


/**
 * Host-owned admission-time playback of an opaque pre-synthesized/captured audio operation.
 * The artifact is held in memory only; the host retries after terminal cleanup using the same
 * half-duplex admission and current-mode routing as text-based delayed playback.
 */
interface DeferredAudioPlaybackCapability : ChannelCapabilityPort {
    /**
     * Schedules [audio] for host-owned, selection-aware playback.
     *
     * [eligibilityDelayMillis] is a host-side delay before the entry becomes eligible for
     * admission. A value of zero (the default) preserves immediate-eligibility behavior.
     * The call returns promptly after scheduling; the delay is enforced by the coordinator's
     * pump, never by blocking the caller.
     */
    suspend fun scheduleAudio(
        context: AgentOperationContext,
        audio: OpaqueAudioOperation,
        eligibilityDelayMillis: Long = 0L,
    ): DelayedPlaybackOutcome
}

/**
 * Generic HTTPS transport capability port (task 9.7). Transport-level and provider-neutral:
 * normalized method, absolute HTTPS URL, headers, optional UTF-8 body, and a complete bounded
 * response. The host owns platform networking, TLS, admission, ownership accounting, redirects,
 * deadlines, and bounds; the port constructs no OpenAI/provider request, injects no credential,
 * interprets no JSON, and retains no conversation. A non-2xx status is a transport success.
 */
interface GenericHttpCapability : ChannelCapabilityPort {
    suspend fun request(request: GenericHttpRequest): GenericHttpResult
}

/**
 * Result of acquiring a capability. Recoverable is returned only when the host can
 * prepare the resource according to its own bounded policy; channel code cannot pick
 * a connection or retry mechanism.
 */
sealed interface CapabilityAcquisition<out T : ChannelCapabilityPort> {
    data class Available<T : ChannelCapabilityPort>(val lease: CapabilityLease<T>) : CapabilityAcquisition<T>
    data class Recoverable(val reason: CapabilityUnavailableReason) : CapabilityAcquisition<Nothing>
    data class Unavailable(val reason: CapabilityUnavailableReason) : CapabilityAcquisition<Nothing>
    data class Denied(val reason: CapabilityDeniedReason) : CapabilityAcquisition<Nothing>
    data object Closed : CapabilityAcquisition<Nothing>
    data object Cancelled : CapabilityAcquisition<Nothing>
    data class Failed(val reason: CapabilityFailureReason) : CapabilityAcquisition<Nothing>
}

/** Semantic completion outcomes for a capability operation. */
sealed interface CapabilityOperationResult<out T> {
    data class Success<T>(val value: T) : CapabilityOperationResult<T>
    data class Unavailable(val reason: CapabilityUnavailableReason) : CapabilityOperationResult<Nothing>
    data class Denied(val reason: CapabilityDeniedReason) : CapabilityOperationResult<Nothing>
    data object Closed : CapabilityOperationResult<Nothing>
    data object Cancelled : CapabilityOperationResult<Nothing>
    data class Failed(val reason: CapabilityFailureReason) : CapabilityOperationResult<Nothing>
}

sealed interface CapabilityReleaseResult {
    data object Released : CapabilityReleaseResult
    data object AlreadyTerminated : CapabilityReleaseResult
    data class CleanupFailed(val reason: CapabilityFailureReason) : CapabilityReleaseResult
}

/**
 * A revocable lease deliberately does not expose [T] as a property. A caller can use
 * a port only inside [use], which lets the boundary reject a lease after revocation.
 */
interface CapabilityLease<T : ChannelCapabilityPort> {
    val capability: ChannelCapability
    val identity: CapabilityScopeIdentity
    val state: CapabilityLeaseState

    suspend fun <R> use(
        operation: suspend (T) -> CapabilityOperationResult<R>,
    ): CapabilityOperationResult<R>

    suspend fun release(): CapabilityReleaseResult
}

sealed interface CapabilityAcquisitionPolicy {
    data object Immediate : CapabilityAcquisitionPolicy

    /** The timeout is enforced by host preparation, not by channel code. */
    data class PrepareRecoverable(val timeoutMillis: Long) : CapabilityAcquisitionPolicy {
        init {
            require(timeoutMillis > 0) { "Preparation timeout must be positive" }
        }
    }
}

/** A provider/runtime-facing scope. It contains only capabilities authorized by its descriptor. */
interface ChannelCapabilityScope {
    val identity: CapabilityScopeIdentity
    val declaredCapabilities: Set<ChannelCapability>
    val isClosed: Boolean

    suspend fun availability(key: CapabilityKey<*>): CapabilityAvailabilityResult

    suspend fun <T : ChannelCapabilityPort> acquire(
        key: CapabilityKey<T>,
        policy: CapabilityAcquisitionPolicy = CapabilityAcquisitionPolicy.Immediate,
    ): CapabilityAcquisition<T>
}

sealed interface CapabilityAvailabilityResult {
    data class State(val availability: CapabilityAvailability) : CapabilityAvailabilityResult
    data class Denied(val reason: CapabilityDeniedReason) : CapabilityAvailabilityResult
    data object Closed : CapabilityAvailabilityResult
    data object Cancelled : CapabilityAvailabilityResult
    data class Failed(val reason: CapabilityFailureReason) : CapabilityAvailabilityResult
}

/**
 * Host-only acquisition bridge. Composition supplies an implementation; runtimes never
 * receive it and therefore cannot locate a global capability service.
 */
interface ChannelCapabilityHost {
    suspend fun availability(
        identity: CapabilityScopeIdentity,
        key: CapabilityKey<*>,
    ): CapabilityAvailability

    suspend fun <T : ChannelCapabilityPort> acquire(
        identity: CapabilityScopeIdentity,
        key: CapabilityKey<T>,
    ): HostedCapabilityAcquisition<T>

    suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
        identity: CapabilityScopeIdentity,
        key: CapabilityKey<T>,
        timeoutMillis: Long,
    ): HostedCapabilityAcquisition<T>
    /**
     * Notifies host-owned generation resources when a runtime scope is revoked. The default is
     * intentionally empty so capability hosts that do not own deferred resources remain source
     * compatible. Implementations MUST make this callback idempotent.
     */
    suspend fun onGenerationTermination(
        identity: CapabilityScopeIdentity,
        termination: CapabilityLeaseTermination,
    ) {}
}

/**
 * Host-internal acquisition outcome before it is bound to a revocable runtime lease.
 * [Available.cleanup] MUST cancel or detach outstanding host operations before it
 * returns for [CapabilityLeaseTermination.REVOKED], so a revoked generation cannot
 * produce a later platform, persistence, playback, or publication effect.
 */
sealed interface HostedCapabilityAcquisition<out T : ChannelCapabilityPort> {
    data class Available<T : ChannelCapabilityPort>(
        val port: T,
        val cleanup: suspend (CapabilityLeaseTermination) -> Unit,
    ) : HostedCapabilityAcquisition<T>

    data class Recoverable(val reason: CapabilityUnavailableReason) : HostedCapabilityAcquisition<Nothing>
    data class Unavailable(val reason: CapabilityUnavailableReason) : HostedCapabilityAcquisition<Nothing>
    data object Cancelled : HostedCapabilityAcquisition<Nothing>
    data class Failed(val reason: CapabilityFailureReason) : HostedCapabilityAcquisition<Nothing>
}

enum class CapabilityDiagnosticPhase {
    AVAILABILITY,
    ACQUISITION,
    PREPARATION,
    OPERATION,
    REVOCATION,
    RELEASE,
    CLEANUP,
}

enum class CapabilityDiagnosticOutcome {
    AVAILABLE,
    RECOVERABLE,
    UNAVAILABLE,
    DENIED,
    CLOSED,
    CANCELLED,
    FAILED,
    RELEASED,
}

/**
 * Deliberately normalized diagnostic metadata. It has no free-form detail field, so
 * payloads, text, addresses, secrets, and platform exception messages cannot cross
 * this boundary.
 */
data class CapabilityDiagnostic(
    val identity: CapabilityScopeIdentity,
    val capability: ChannelCapability,
    val phase: CapabilityDiagnosticPhase,
    val outcome: CapabilityDiagnosticOutcome,
)

fun interface CapabilityDiagnosticSink {
    fun record(diagnostic: CapabilityDiagnostic)
}

/** An opaque captured recording chosen and owned by the host audio lifecycle. */
sealed interface OpaqueAudioRecording {
    val operationId: String

    /**
     * Host-owned retained byte cost of this artifact, as measured against
     * the registry's per-artifact and aggregate byte quotas. Always known
     * for a fully captured recording.
     */
    val retainedBytes: Long

    /**
     * Capture duration in milliseconds, or `null` when the underlying
     * backend did not surface duration metadata. The registry enforces the
     * per-artifact duration bound only when this value is non-null.
     */
    val durationMillis: Long?

    /**
     * Release this artifact's host-owned audio data exactly once. Idempotent.
     *
     * Invoked by the host audio registry when the owning execution terminates
     * or the runtime generation closes without consuming this recording.
     * After disposal the artifact holds no usable audio data; subsequent
     * disposal is a no-op.
     */
    fun dispose()
}

/** An opaque synthesized audio artifact. Its samples and route stay host-owned. */
sealed interface OpaqueSynthesizedAudio {
    val operationId: String

    /**
     * Host-owned retained byte cost of this artifact, as measured against
     * the registry's per-artifact and aggregate byte quotas. Always known
     * for a fully synthesized artifact.
     */
    val retainedBytes: Long

    /**
     * Synthesis duration in milliseconds, or `null` when the synthesizing
     * backend did not surface duration metadata (for example, when no sample
     * rate is attached). The registry enforces the per-artifact duration
     * bound only when this value is non-null.
     */
    val durationMillis: Long?

    /**
     * Release this artifact's host-owned audio data exactly once. Idempotent.
     *
     * Invoked by the host audio registry when the owning execution terminates
     * or the runtime generation closes without consuming this artifact.
     * After disposal the artifact holds no usable audio data; subsequent
     * disposal is a no-op.
     */
    fun dispose()
}

/** A lifecycle-bound audio operation whose platform resources remain host-owned. */
sealed interface OpaqueAudioOperation {
    val operationId: String
}

interface TranscriptionCapability : ChannelCapabilityPort {
    suspend fun transcribe(recording: OpaqueAudioRecording): CapabilityOperationResult<Transcription>
}

data class Transcription(val text: String)

interface SynthesisCapability : ChannelCapabilityPort {
    suspend fun synthesize(request: SpeechSynthesisRequest): CapabilityOperationResult<OpaqueSynthesizedAudio>
}

data class SpeechSynthesisRequest(
    val text: String,
    val languageTag: String,
    val voice: SpeechVoice,
    val speed: Float = 1f,
) {
    init {
        require(languageTag.isNotBlank()) { "Language tag must not be blank" }
        require(speed > 0f) { "Speech speed must be positive" }
    }
}

@JvmInline
value class SpeechVoice(val id: String) {
    init {
        require(id.isNotBlank()) { "Voice ID must not be blank" }
    }
}

interface AudioOperationCapability : ChannelCapabilityPort {
    suspend fun createPlaybackResult(audio: OpaqueSynthesizedAudio): CapabilityOperationResult<OpaqueAudioOperation>
    suspend fun createPlaybackResult(recording: OpaqueAudioRecording): CapabilityOperationResult<OpaqueAudioOperation>
}


interface TextOutputCapability : ChannelCapabilityPort {
    suspend fun sendText(request: TextOutputRequest): TextDeliveryOutcome
    suspend fun sendKey(request: TextKeyRequest): TextDeliveryOutcome
}

data class TextOutputRequest(
    val text: String,
    val profile: TextOutputProfile,
)

@JvmInline
value class TextOutputProfile(val id: String) {
    init {
        require(id.isNotBlank()) { "Text output profile must not be blank" }
    }

    companion object {
        /**
         * 1.4: Shared keyboard-output logical-profile UTF-8 byte bound. Mirrors the
         * native kernel's KEYBOARD_MAX_PROFILE_BYTES so host call-time revalidation
         * and the native pre-yield check agree on one named constant.
         */
        const val MAX_BYTES: Int = 256
    }
}

data class TextKeyRequest(
    val key: TextOutputKey,
    val profile: TextOutputProfile,
)

enum class TextOutputKey {
    ENTER,
    ESCAPE,
}

/** Each submission has one terminal outcome and one host operation ID; hosts must never replay it automatically. */
sealed interface TextDeliveryOutcome {
    data class Delivered(val operationId: String) : TextDeliveryOutcome
    data class Rejected(val operationId: String, val reason: TextOutputRejectionReason) : TextDeliveryOutcome
    data class Failed(val operationId: String, val reason: TextOutputFailureReason) : TextDeliveryOutcome
    data class Indeterminate(
        val operationId: String,
        val reason: TextOutputIndeterminateReason,
    ) : TextDeliveryOutcome
}

enum class TextOutputRejectionReason {
    EMPTY_TEXT,
    UNSUPPORTED_CHARACTER,
    INVALID_PROFILE,
    POLICY_REFUSED,
}

enum class TextOutputFailureReason {
    UNAVAILABLE,
    TIMED_OUT,
    CANCELLED,
    HOST_FAILURE,
}

enum class TextOutputIndeterminateReason {
    DISCONNECTED,
    ACKNOWLEDGEMENT_LOST,
    CANCELLED,
    TIMED_OUT,
}
