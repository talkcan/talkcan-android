package io.talkcan.lua.actor

import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.RuntimeGeneration
import java.util.concurrent.atomic.AtomicLong

/**
 * Opaque identity for one actor-owned mailbox event.
 *
 * Carries the channel instance ID, runtime generation, and a process-local
 * monotonic event identifier. No native pointer, Lua registry index, or raw
 * Kotlin object crosses this boundary. The identity layers actor ownership on
 * the existing [CapabilityScopeIdentity] and registry generation contracts
 * rather than creating a competing generation space.
 */
internal data class ActorEventIdentity(
    val scope: CapabilityScopeIdentity,
    val eventId: ActorEventId,
)

/**
 * Opaque identity for one actor-owned background task.
 *
 * Background tasks are generation-bound and do not outlive their owning
 * generation. The identity is a language-neutral host-domain value validated
 * against the owning actor generation.
 */
internal data class ActorTaskIdentity(
    val scope: CapabilityScopeIdentity,
    val taskId: ActorTaskId,
)

/**
 * Opaque identity for one actor-owned suspended operation token.
 *
 * Each operation token accepts at most one terminal completion, cancellation,
 * or close outcome. A resume, cancel, or close for a foreign or stale token
 * returns a typed outcome without entering Lua.
 */
internal data class ActorOperationIdentity(
    val scope: CapabilityScopeIdentity,
    val taskId: ActorTaskIdentity,
    val operationId: ActorOperationId,
)

/**
 * Optional durable-run identity carried only by durable channel operations.
 *
 * Ordinary timers, startup work, and background operations do not receive
 * fabricated durable-run identities. A durable operation carries its durable
 * run identity so the host can associate the completion with a persisted
 * record; the actor never interprets the durable run identity itself.
 */
internal data class ActorDurableRunIdentity(
    val scope: CapabilityScopeIdentity,
    val durableRunId: ActorDurableRunId,
)

/**
 * Opaque mailbox event kind. The host decodes envelopes internally so no
 * Kotlin class, Android callback, Compose state, SDK type, or transport
 * object is exposed in the envelope payload.
 */
internal enum class ActorEventKind {
    LIFECYCLE,
    INPUT,
    READINESS,
    SOS,
    DURABLE_RUN_CALLBACK,
}

/**
 * Opaque mailbox envelope carrying event kind, owning generation, and an
 * internal host-domain payload. The payload is decoded inside the actor; the
 * host does not expose platform types through it.
 */
internal sealed class ActorEventEnvelope {
    abstract val identity: ActorEventIdentity
    abstract val kind: ActorEventKind

    data class Lifecycle(
        override val identity: ActorEventIdentity,
        val phase: ActorLifecyclePhase,
    ) : ActorEventEnvelope() {
        override val kind: ActorEventKind = ActorEventKind.LIFECYCLE
    }

    data class Input(
        override val identity: ActorEventIdentity,
        val payload: ActorInputPayload,
    ) : ActorEventEnvelope() {
        override val kind: ActorEventKind = ActorEventKind.INPUT
    }

    data class Readiness(
        override val identity: ActorEventIdentity,
    ) : ActorEventEnvelope() {
        override val kind: ActorEventKind = ActorEventKind.READINESS
    }

    data class Sos(
        override val identity: ActorEventIdentity,
    ) : ActorEventEnvelope() {
        override val kind: ActorEventKind = ActorEventKind.SOS
    }

    data class DurableRunCallback(
        override val identity: ActorEventIdentity,
        val durableRun: ActorDurableRunIdentity,
        val payload: ActorDurableRunPayload,
    ) : ActorEventEnvelope() {
        override val kind: ActorEventKind = ActorEventKind.DURABLE_RUN_CALLBACK
    }
}

/**
 * Internal host-domain input payload. Decoded by the actor; the host does not
 * expose Kotlin classes, Android callbacks, Compose state, SDK types, or
 * transport objects in the payload.
 */
internal sealed class ActorInputPayload {
    data class Prepare(val inputId: String) : ActorInputPayload()
    data class Release(val inputId: String, val outcome: ActorInputReleaseOutcome) : ActorInputPayload()
}

internal enum class ActorInputReleaseOutcome {
    COMPLETED,
    CANCELLED,
    FAILED,
}

/**
 * Internal host-domain durable-run callback payload.
 */
internal sealed class ActorDurableRunPayload {
    data class Completed(val value: String) : ActorDurableRunPayload()
    data class Failed(val diagnostic: String) : ActorDurableRunPayload()
    data object Cancelled : ActorDurableRunPayload()
}

/**
 * Opaque process-local monotonic identifier for one mailbox event.
 */
@JvmInline
internal value class ActorEventId(val value: Long) {
    init {
        require(value > 0L) { "Event id must be positive" }
    }

    companion object {
        private val counter = AtomicLong(0L)

        fun next(): ActorEventId = ActorEventId(counter.incrementAndGet())
    }
}

/**
 * Opaque process-local monotonic identifier for one actor background task.
 */
@JvmInline
internal value class ActorTaskId(val value: Long) {
    init {
        require(value > 0L) { "Task id must be positive" }
    }

    companion object {
        private val counter = AtomicLong(0L)

        fun next(): ActorTaskId = ActorTaskId(counter.incrementAndGet())
    }
}

/**
 * Opaque process-local monotonic identifier for one suspended operation token.
 */
@JvmInline
internal value class ActorOperationId(val value: Long) {
    init {
        require(value > 0L) { "Operation id must be positive" }
    }

    companion object {
        private val counter = AtomicLong(0L)

        fun next(): ActorOperationId = ActorOperationId(counter.incrementAndGet())
    }
}

/**
 * Opaque process-local monotonic identifier for one durable run. Only durable
 * channel operations carry this; ordinary work does not receive a fabricated
 * durable-run identity.
 */
@JvmInline
internal value class ActorDurableRunId(val value: Long) {
    init {
        require(value > 0L) { "Durable run id must be positive" }
    }

    companion object {
        private val counter = AtomicLong(0L)

        fun next(): ActorDurableRunId = ActorDurableRunId(counter.incrementAndGet())
    }
}

/**
 * Authoritative generation accessor layered on [CapabilityScopeIdentity].
 * The actor uses [CapabilityScopeIdentity] as the authoritative host
 * generation; the kernel-internal [LuaStateGeneration] stays kernel-internal.
 */
internal val CapabilityScopeIdentity.runtimeGenerationOrThrow: RuntimeGeneration
    get() = runtimeGeneration