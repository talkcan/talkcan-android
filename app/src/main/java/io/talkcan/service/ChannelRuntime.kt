package io.talkcan.service

import io.talkcan.audio.ChannelInputAcceptance
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelProviderError
import kotlinx.coroutines.flow.StateFlow

enum class ChannelExecutionStatus {
    IDLE, RECORDING, PROCESSING, SUCCESS, FAILED
}

/**
 * Generic durable-work phase projected from work metadata only (task 14.1/14.2,
 * design D12). Deliberately content-free: it carries a phase, a bounded queued
 * count, and active presence — never a payload, effect body/result, transcript,
 * prompt, response, tool argument, profile field, secret value, or ledger entry.
 * Delayed playback remains the authority for pending/heard response content; this
 * projection only summarizes queue state for host-owned presentation.
 */
enum class GenericWorkPhase {
    /** No nonterminal work and no failed/indeterminate terminal needing attention. */
    IDLE,
    /** Nonterminal work is queued but none is actively claimed. */
    QUEUED,
    /** Exactly one work item is actively claimed/running. */
    ACTIVE,
    /** A terminal work item failed; bounded class/reason metadata only, no content. */
    FAILED,
    /** A started effect closed before commit; ambiguous, never replayed (design D10/D11). */
    INDETERMINATE,
}

/**
 * Bounded, content-free snapshot of one instance's generic durable-work state.
 *
 * @property phase the highest-priority work phase (indeterminate > failed > active >
 *   queued > idle) derived from queue metadata.
 * @property queuedCount count of nonterminal queued work items, clamped to a finite
 *   bound; a count is a number, never item content.
 * @property activePresent whether at least one work item is actively claimed.
 */
data class GenericWorkProjection(
    val phase: GenericWorkPhase,
    val queuedCount: Int,
    val activePresent: Boolean,
) {
    init {
        require(queuedCount >= 0) { "Queued count must not be negative" }
        require(queuedCount <= MAX_QUEUED_COUNT) {
            "Queued count must not exceed $MAX_QUEUED_COUNT: $queuedCount"
        }
    }

    companion object {
        /** Finite projection bound; the durable store enforces its own queue capacity. */
        const val MAX_QUEUED_COUNT: Int = 1024

        /** The neutral projection for instances with no generic work activity. */
        val Idle = GenericWorkProjection(GenericWorkPhase.IDLE, queuedCount = 0, activePresent = false)
    }
}

/**
 * Folds per-queue durable-work metadata into one content-free instance projection
 * (task 14.2, design D12). Input [WorkQueueProjection]s carry only identity, epoch,
 * availability, counts, active presence, and a bounded terminal class — never a
 * payload, effect key/result, transcript, prompt, response, tool argument, profile
 * field, secret value, or ledger entry — so the derived [GenericWorkProjection]
 * cannot leak such content into a channel snapshot.
 *
 * Phase priority (highest first): a corrupted partition or an indeterminate terminal
 * is INDETERMINATE (ambiguous, never replayed); otherwise a failed terminal is FAILED;
 * otherwise an active claim is ACTIVE; otherwise queued work is QUEUED; otherwise IDLE.
 * Queued counts sum across queues and clamp to [GenericWorkProjection.MAX_QUEUED_COUNT].
 */
internal fun projectGenericWork(
    projections: List<io.talkcan.work.WorkQueueProjection>,
): GenericWorkProjection {
    var queuedTotal = 0L
    var activePresent = false
    var failed = false
    var indeterminate = false
    for (projection in projections) {
        queuedTotal += projection.queuedCount.coerceAtLeast(0)
        activePresent = activePresent || projection.activePresent
        if (projection.availability == io.talkcan.work.WorkQueueAvailability.CORRUPTED) {
            indeterminate = true
        }
        when (projection.lastTerminalClass) {
            io.talkcan.work.WorkTerminalClass.INDETERMINATE -> indeterminate = true
            io.talkcan.work.WorkTerminalClass.FAILED -> failed = true
            else -> Unit
        }
    }
    val queuedCount = queuedTotal.coerceAtMost(GenericWorkProjection.MAX_QUEUED_COUNT.toLong()).toInt()
    val phase = when {
        indeterminate -> GenericWorkPhase.INDETERMINATE
        failed -> GenericWorkPhase.FAILED
        activePresent -> GenericWorkPhase.ACTIVE
        queuedCount > 0 -> GenericWorkPhase.QUEUED
        else -> GenericWorkPhase.IDLE
    }
    return GenericWorkProjection(phase = phase, queuedCount = queuedCount, activePresent = activePresent)
}

/** Provider-neutral input eligibility used by routing and host-owned presentation. */
sealed interface ChannelPreparationAvailability {
    data object Available : ChannelPreparationAvailability
    data class Recoverable(val reason: ChannelPreparationReason) : ChannelPreparationAvailability
    data class Unavailable(val reason: ChannelPreparationReason) : ChannelPreparationAvailability
}

/** Typed, host-safe explanation for why a channel cannot immediately accept input. */
sealed interface ChannelPreparationReason {
    val message: String

    data object Disabled : ChannelPreparationReason { override val message = "Channel is disabled" }
    data object ProviderInitialising : ChannelPreparationReason { override val message = "Channel is initialising" }
    data class Provider(val error: ChannelProviderError) : ChannelPreparationReason {
        override val message: String = error.message
    }
    data object ModelUnavailable : ChannelPreparationReason {
        override val message: String = "Selected model is unavailable"
    }

    /**
     * The provider's configuration schema rejects the instance's preserved payload.
     * The definition and payload remain unchanged; the instance is unavailable until
     * the provider is rolled back/updated to a compatible revision.
     */
    data class ConfigurationIncompatible(
        val error: ChannelProviderError.InvalidConfiguration,
    ) : ChannelPreparationReason {
        override val message: String = "Configuration is incompatible with the current provider"
    }

    /**
     * 2.6: A required declared resource mount has no usable binding. Scalar
     * configuration remains valid and preserved; the instance is unavailable
     * until the user binds a usable directory tree. Never carries a platform
     * grant, URI, or path.
     */
    data class RequiredResourceUnavailable(
        val blockingDeclarationIds: List<String>,
    ) : ChannelPreparationReason {
        override val message: String = "A required storage resource is not available"
    }
    data object RuntimeBusy : ChannelPreparationReason { override val message = "Channel is busy" }
    data object RuntimeTimedOut : ChannelPreparationReason { override val message = "Channel operation timed out" }
    data object RuntimeCancelled : ChannelPreparationReason { override val message = "Channel operation was cancelled" }
    data object RuntimeClosed : ChannelPreparationReason { override val message = "Channel is no longer available" }
    data class RuntimeReadiness(override val message: String = "Channel is not ready") : ChannelPreparationReason
    data class RuntimeFailed(override val message: String = "Channel operation failed") : ChannelPreparationReason
    data object UnknownInstance : ChannelPreparationReason { override val message = "Channel was not found" }
    data object RegistryShutDown : ChannelPreparationReason { override val message = "Channel service is shutting down" }
}

data class ChannelRuntimeSnapshot(
    val id: String,
    val name: String,
    val implementationId: ChannelImplementationId,
    val enabled: Boolean,
    val preparation: ChannelPreparationAvailability,
    val executionStatus: ChannelExecutionStatus,
    val summary: String? = null,
    val pendingCount: Int = 0,
    val playbackPaused: Boolean = false,
    /**
     * Content-free generic durable-work projection (task 14.1/14.2). Null for
     * instances that declare no work queues (built-in/Journal/Keyboard), preserving
     * their existing snapshot shape; populated from work metadata only for packages
     * that declare `work.queue`. Carries no payload/effect/transcript/secret content.
     */
    val workProjection: GenericWorkProjection? = null,
)

/** Provider-neutral result of bounded, protected runtime startup. */
sealed interface ChannelActivationResult {
    data object Ready : ChannelActivationResult
    data class Failed(val message: String) : ChannelActivationResult
}

interface ChannelRuntime {
    val id: String
    val snapshot: StateFlow<ChannelRuntimeSnapshot>

    suspend fun prepareInput(): ChannelInputAcceptance
    suspend fun handleSos() {}
    suspend fun refreshReadiness() {}

    /**
     * Provider-neutral opt-in cadence for registry-owned readiness refresh.
     * Null preserves the existing on-demand-only behavior.
     */
    val readinessRefreshIntervalMillis: Long? get() = null

    /**
     * Bounded, host-protected runtime startup invoked after construction and, for staged
     * successors, after the predecessor has fully closed and capabilities are authorized.
     * The default returns [ChannelActivationResult.Ready] so existing Kotlin providers that
     * do not override this hook remain ready immediately.
     */
    suspend fun activate(): ChannelActivationResult = ChannelActivationResult.Ready

    /** Implementations must make terminal closure idempotent and await their child work. */
    suspend fun close()
}
