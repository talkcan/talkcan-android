package io.talkcan.work

import java.nio.charset.StandardCharsets

/**
 * Provider-neutral contracts for the generic durable work store.
 *
 * The store is an opaque FIFO admission and effect ledger.  It never interprets payload keys or
 * values as provider messages, protocols, models, tools, retries, or application state, and it
 * never persists OpenAI- or provider-shaped structures.  All values are bounded normalized data;
 * all failures are normalized, bounded, and content-free.
 */

// ---------------------------------------------------------------------------
// Identity
// ---------------------------------------------------------------------------

/** Durable GitHub repository database identity of the package that declared a queue. */
@JvmInline
public value class WorkRepositoryId(val value: Long) {
    init {
        require(value > 0L) { "Repository ID must be positive" }
    }
}

/** Stable channel instance identity owning one durable queue namespace per declared queue ID. */
@JvmInline
public value class WorkInstanceId(val value: String) {
    init {
        require(value.isNotBlank()) { "Instance ID must not be blank" }
    }
}

/** Canonical package-local queue ID exactly as declared in the package manifest. */
@JvmInline
public value class WorkQueueId(val value: String) {
    init {
        require(value.isNotBlank()) { "Queue ID must not be blank" }
    }
}

/** Host-generated stable work identity. */
@JvmInline
public value class WorkId(val value: String) {
    init {
        require(value.isNotBlank()) { "Work ID must not be blank" }
    }
}

/**
 * Durable work epoch for one queue partition.  Ordinary restart preserves the epoch; intentional
 * replacement (SOS, configuration/profile/package change, rollback/removal, instance deletion,
 * explicit reset) retires it before successor readiness.
 */
@JvmInline
public value class WorkEpoch(val value: Long) {
    init {
        require(value >= 0L) { "Epoch must be non-negative" }
    }
}

/** Transaction and storage partition: one durable FIFO namespace per repository/instance/queue. */
public data class WorkQueuePartition(
    val repositoryId: WorkRepositoryId,
    val instanceId: WorkInstanceId,
    val queueId: WorkQueueId,
)

// ---------------------------------------------------------------------------
// Opaque normalized values
// ---------------------------------------------------------------------------

/**
 * One bounded normalized value.  Payloads and effect results are stored and returned only in this
 * shape; the host never attaches provider meaning to any variant, key, or scalar.
 */
public sealed interface WorkValue {
    public data object Null : WorkValue
    public data class Bool(val value: Boolean) : WorkValue
    public data class Integer(val value: Long) : WorkValue
    public data class Real(val value: Double) : WorkValue
    public data class Text(val value: String) : WorkValue
    public data class List(val values: kotlin.collections.List<WorkValue>) : WorkValue
    public data class Map(val entries: kotlin.collections.List<WorkMapEntry>) : WorkValue
}

/** One ordered string-keyed entry inside a [WorkValue.Map]; keys are unique within their map. */
public data class WorkMapEntry(
    val key: String,
    val value: WorkValue,
)

// ---------------------------------------------------------------------------
// Queue record
// ---------------------------------------------------------------------------

/**
 * Durable queue state for one partition.  The [revisionFingerprint] is an opaque host-supplied
 * binding token (package revision, configuration revision, selected profile revisions, queue
 * declaration); the store only compares it for equality when deciding whether an epoch may be
 * preserved across restart.
 */
public data class QueueRecord(
    val partition: WorkQueuePartition,
    val epoch: WorkEpoch,
    val nextSequence: Long,
    val revisionFingerprint: String?,
)

// ---------------------------------------------------------------------------
// Work record
// ---------------------------------------------------------------------------

/** Nonterminal work states; terminal work is purged to a [WorkTombstone]. */
public enum class WorkState { QUEUED, CLAIMED }

/**
 * One durable FIFO item.  [payload] is opaque normalized data; [leaseHolder] binds a claim to one
 * task/generation token without exposing locks, leases, or database objects beyond the store.
 */
public data class WorkRecord(
    val id: WorkId,
    val partition: WorkQueuePartition,
    val sequence: Long,
    val epoch: WorkEpoch,
    val payload: WorkValue,
    val state: WorkState,
    val leaseHolder: String?,
    val submittedAtMillis: Long,
    val claimedAtMillis: Long?,
)

/** One claimed job delivered to a waiter, with its detached opaque payload. */
public data class WorkClaim(
    val work: WorkRecord,
    val payload: WorkValue,
)

// ---------------------------------------------------------------------------
// Effects
// ---------------------------------------------------------------------------

/**
 * Effect lifecycle.  Once [STARTED] is durably committed, interruption before a committed result
 * makes the effect [INDETERMINATE]; a started effect is never automatically replayed.
 */
public enum class WorkEffectState { STARTED, COMMITTED, INDETERMINATE }

/** Committed normalized effect result; success and error are both bounded [WorkValue]s. */
public sealed interface WorkEffectResult {
    public data class Success(val value: WorkValue) : WorkEffectResult
    public data class Error(val value: WorkValue) : WorkEffectResult
}

/**
 * One effect memoized inside one job.  [argumentFingerprint] is an opaque host-supplied token
 * (for example a hash of key and normalized arguments) used only to detect incompatible duplicate
 * use of a key; the store never interprets it.
 */
public data class EffectRecord(
    val workId: WorkId,
    val key: String,
    val argumentFingerprint: String,
    val state: WorkEffectState,
    val result: WorkEffectResult?,
)

/** Outcome of a safe claim release. */
public sealed interface WorkClaimRelease {
    /** The claim returned to QUEUED in its original FIFO position. */
    public data class Released(val work: WorkRecord) : WorkClaimRelease

    /** The claim held a started effect and became terminally indeterminate. */
    public data class Indeterminate(val tombstone: WorkTombstone) : WorkClaimRelease
}

/** Outcome of an atomic effect begin. */
public sealed interface WorkEffectBegin {
    /** A new effect was durably reserved and marked started; the caller may invoke its function. */
    public data class Started(val effect: EffectRecord) : WorkEffectBegin

    /** The same key and fingerprint already committed this result; the function is never invoked. */
    public data class Replay(val result: WorkEffectResult) : WorkEffectBegin
}

// ---------------------------------------------------------------------------
// Terminal classes and tombstones
// ---------------------------------------------------------------------------

/** Bounded non-sensitive terminal classification. */
public enum class WorkTerminalClass { COMPLETED, FAILED, INDETERMINATE, CANCELLED }

/**
 * Bounded tombstone retained after terminal purge.  Payload, committed effect bodies, and
 * checkpoints are deleted; only identity, FIFO position, terminal class, a bounded reason tag,
 * and timing/accounting metadata survive.
 */
public data class WorkTombstone(
    val workId: WorkId,
    val sequence: Long,
    val epoch: WorkEpoch,
    val terminalClass: WorkTerminalClass,
    val reasonTag: String?,
    val submittedAtMillis: Long,
    val terminatedAtMillis: Long,
    val effectCount: Int,
    val playbackHandedOff: Boolean,
)

// ---------------------------------------------------------------------------
// Epoch causes and recovery
// ---------------------------------------------------------------------------

/** Cause recorded in diagnostics when an epoch is intentionally retired. */
public enum class WorkEpochResetCause {
    SOS,
    CONFIGURATION_REPLACED,
    PROFILE_REPLACED,
    PACKAGE_REPLACED,
    PACKAGE_ROLLBACK,
    PACKAGE_REMOVED,
    INSTANCE_DELETED,
    EXPLICIT_RESET,
}

/** Result of one partition epoch retirement. */
public data class WorkEpochRetirement(
    val partition: WorkQueuePartition,
    val cause: WorkEpochResetCause,
    val newEpoch: WorkEpoch,
    val cancelled: kotlin.collections.List<WorkId>,
    val indeterminate: kotlin.collections.List<WorkId>,
)

/** Result of process-restart reconciliation across all partitions. */
public data class WorkRecoveryPlan(
    val reclaimed: kotlin.collections.Map<WorkQueuePartition, kotlin.collections.List<WorkId>>,
    val indeterminate: kotlin.collections.Map<WorkQueuePartition, kotlin.collections.List<WorkId>>,
    val corrupted: kotlin.collections.List<String>,
)

// ---------------------------------------------------------------------------
// Projection (metadata only)
// ---------------------------------------------------------------------------

/** Whether a queue partition is currently usable. */
public enum class WorkQueueAvailability { AVAILABLE, CORRUPTED }

/**
 * Generic channel projection input derived only from work metadata.  Never contains payloads,
 * effect keys/results, provider content, secrets, or ledger internals.
 */
public data class WorkQueueProjection(
    val partition: WorkQueuePartition,
    val epoch: WorkEpoch,
    val availability: WorkQueueAvailability,
    val queuedCount: Int,
    val activePresent: Boolean,
    val lastTerminalClass: WorkTerminalClass?,
)

// ---------------------------------------------------------------------------
// Results and normalized failures
// ---------------------------------------------------------------------------

public sealed interface WorkStoreResult<out T> {
    public data class Success<T>(val value: T) : WorkStoreResult<T>
    public data class Failure(val failure: WorkStoreFailure) : WorkStoreResult<Nothing>
}

/**
 * Normalized, bounded, content-free store failures.  `detail` may carry bounded identity and
 * phase tokens only; it never carries payload or effect body content.
 */
public sealed interface WorkStoreFailure {
    /** Payload/effect value failed normalized validation. */
    public data class InvalidValue(val field: String) : WorkStoreFailure

    /** A bounded byte/count quota was exceeded before any mutation. */
    public data class TooLarge(val field: String) : WorkStoreFailure

    /** Capacity exhausted or too many concurrent operations; existing state is unchanged. */
    public data object Busy : WorkStoreFailure

    /** State-machine conflict: invalid transition or incompatible duplicate identity. */
    public data class Conflict(val detail: String) : WorkStoreFailure

    /** Unknown work, queue partition, or instance. */
    public data class NotFound(val detail: String) : WorkStoreFailure

    /** Lease/generation mismatch or closed generation. */
    public data class Stale(val detail: String) : WorkStoreFailure

    /** Partition record failed strict decoding/validation and is isolated; fail closed. */
    public data class Corrupted(val detail: String) : WorkStoreFailure

    /** Underlying filesystem persistence failure. */
    public data class Storage(val detail: String) : WorkStoreFailure
}

// ---------------------------------------------------------------------------
// Bounds
// ---------------------------------------------------------------------------

/**
 * Finite quotas for the durable work subsystem.  Capacity rejection occurs before durable
 * mutation; corruption isolation and recovery are bounded by the same quotas.
 */
public data class DurableWorkBounds(
    val maxQueuesPerInstance: Int = 8,
    val maxNonterminalItemsPerQueue: Int = 64,
    val maxPayloadBytes: Int = 64 * 1024,
    val maxPayloadDepth: Int = 8,
    val maxPayloadEntries: Int = 1024,
    val maxTextBytes: Int = 8 * 1024,
    val maxEffectsPerWork: Int = 64,
    val maxEffectKeyBytes: Int = 256,
    val maxEffectFingerprintBytes: Int = 128,
    val maxEffectResultBytes: Int = 256 * 1024,
    val maxTombstonesPerQueue: Int = 128,
    val maxStorageBytesPerQueue: Int = 2 * 1024 * 1024,
    val maxRecoveryItemsPerQueue: Int = 256,
    val maxReasonTagBytes: Int = 64,
    val maxFingerprintBytes: Int = 256,
) {
    init {
        require(maxQueuesPerInstance > 0)
        require(maxNonterminalItemsPerQueue > 0)
        require(maxPayloadBytes > 0)
        require(maxPayloadDepth > 0)
        require(maxPayloadEntries > 0)
        require(maxTextBytes > 0)
        require(maxEffectsPerWork > 0)
        require(maxEffectKeyBytes > 0)
        require(maxEffectFingerprintBytes > 0)
        require(maxEffectResultBytes > 0)
        require(maxTombstonesPerQueue > 0)
        require(maxStorageBytesPerQueue > 0)
        require(maxRecoveryItemsPerQueue > 0)
        require(maxReasonTagBytes > 0)
        require(maxFingerprintBytes > 0)
    }
}

internal fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size
