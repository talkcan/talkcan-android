package io.talkcan.lua

import io.talkcan.channel.capability.OpaqueAudioRecording
import io.talkcan.channel.capability.OpaqueSynthesizedAudio
import java.security.SecureRandom
import java.util.Base64

/**
 * Host-only ownership registry for audio values handed to one Lua state.
 *
 * Tokens are random capabilities, never registry indexes. They are valid only
 * for the state/generation and execution owner that admitted them.
 *
 * The registry enforces finite positive quotas at every admit:
 *   - per-artifact retained-byte and duration bounds;
 *   - live-token and retained-byte bounds per execution owner, generation,
 *     and process.
 *
 * On any quota failure the rejected artifact is disposed before any token
 * is created or accounting mutated; sibling owners, the generation, and the
 * process counters are left untouched.
 */
internal class LuaOpaqueAudioRegistry(
    private val stateHandle: LuaStateHandle,
    private val limits: LuaOpaqueAudioRegistry.Limits = LuaOpaqueAudioRegistry.Limits.DEFAULT,
    private val processQuota: LuaOpaqueAudioRegistry.ProcessQuota =
        LuaOpaqueAudioRegistry.ProcessQuota.DEFAULT,
) {
    /**
     * Finite positive quota configuration for a single registry generation.
     *
     * Process-wide bounds live on the shared [ProcessQuota] accountant, not
     * here; [Limits] describes the per-artifact, per-owner, and per-generation
     * bounds the registry checks locally on every admit.
     */
    data class Limits(
        val maxBytesPerArtifact: Long,
        val maxDurationPerArtifactMillis: Long,
        val maxTokensPerOwner: Int,
        val maxBytesPerOwner: Long,
        val maxTokensPerGeneration: Int,
        val maxBytesPerGeneration: Long,
    ) {
        init {
            require(maxBytesPerArtifact > 0L) {
                "maxBytesPerArtifact must be positive: $maxBytesPerArtifact"
            }
            require(maxDurationPerArtifactMillis > 0L) {
                "maxDurationPerArtifactMillis must be positive: $maxDurationPerArtifactMillis"
            }
            require(maxTokensPerOwner > 0) {
                "maxTokensPerOwner must be positive: $maxTokensPerOwner"
            }
            require(maxBytesPerOwner > 0L) {
                "maxBytesPerOwner must be positive: $maxBytesPerOwner"
            }
            require(maxTokensPerGeneration > 0) {
                "maxTokensPerGeneration must be positive: $maxTokensPerGeneration"
            }
            require(maxBytesPerGeneration > 0L) {
                "maxBytesPerGeneration must be positive: $maxBytesPerGeneration"
            }
        }

        companion object {
            /**
             * Conservative defaults; not a Lua compatibility promise. The host
             * supplies explicit [Limits] (and an explicit [ProcessQuota]) in
             * production wiring.
             */
            val DEFAULT = Limits(
                maxBytesPerArtifact = 19_200_000L,
                maxDurationPerArtifactMillis = 600_000L,
                maxTokensPerOwner = 32,
                maxBytesPerOwner = 32L * 1024 * 1024,
                maxTokensPerGeneration = 64,
                maxBytesPerGeneration = 48L * 1024 * 1024,
            )
        }
    }

    /**
     * Shared mutable accountant for the JVM-process-wide live-token and
     * retained-byte bounds. All registries constructed without an explicit
     * accountant share [DEFAULT]; tests construct a fresh instance per case
     * for isolation.
     *
     * [tryReserve] is the only mutating entry point that may reject; it
     * atomically increments both counters only when both stay within bounds.
     * [forceReserve] is the revert path used by [restore] so a failed
     * deferred-queue admission can re-claim an accounting slot the registry
     * had previously owned.
     */
    class ProcessQuota(
        maxTokens: Int,
        maxBytes: Long,
    ) {
        init {
            require(maxTokens > 0) { "maxTokens must be positive: $maxTokens" }
            require(maxBytes > 0L) { "maxBytes must be positive: $maxBytes" }
        }

        private val maxTokens = maxTokens
        private val maxBytes = maxBytes

        @Volatile private var tokensUsed: Int = 0
        @Volatile private var bytesUsed: Long = 0L

        @Synchronized
        internal fun tryReserve(tokens: Int, bytes: Long): Boolean {
            if (tokens < 0 || bytes < 0L) return false
            if (tokensUsed + tokens > maxTokens) return false
            if (bytesUsed + bytes > maxBytes) return false
            tokensUsed += tokens
            bytesUsed += bytes
            return true
        }

        @Synchronized
        internal fun forceReserve(tokens: Int, bytes: Long) {
            tokensUsed += tokens
            bytesUsed += bytes
        }

        @Synchronized
        internal fun release(tokens: Int, bytes: Long) {
            tokensUsed -= tokens
            bytesUsed -= bytes
        }

        @Synchronized
        fun liveTokens(): Int = tokensUsed

        @Synchronized
        fun retainedBytes(): Long = bytesUsed

        companion object {
            /**
             * Process-wide shared accountant. Defaults mirror the production
             * intention; not a Lua compatibility promise.
             */
            val DEFAULT: ProcessQuota = ProcessQuota(
                maxTokens = 256,
                maxBytes = 64L * 1024 * 1024,
            )
        }
    }

    sealed interface Owner {
        val id: String

        data class Input(override val id: String) : Owner
        data class Task(override val id: String) : Owner
    }

    sealed interface Kind {
        data object Captured : Kind
        data object Synthesized : Kind
    }

    class Token internal constructor(internal val value: String)

    sealed interface Resolution {
        data class Captured(val recording: OpaqueAudioRecording) : Resolution
        data class Synthesized(val audio: OpaqueSynthesizedAudio) : Resolution
        data object Foreign : Resolution
        data object Stale : Resolution
        data object Closed : Resolution
        data object WrongKind : Resolution
    }

    /** Snapshot of registry accounting, for host invariant checks. */
    internal data class Accounting(
        val liveTokens: Int,
        val retainedBytes: Long,
        val perOwner: Map<Owner, OwnerAccounting>,
    )

    internal data class OwnerAccounting(
        val tokens: Int,
        val bytes: Long,
    )

    internal sealed interface Entry {
        val owner: Owner
        val kind: Kind
        val retainedBytes: Long
        val durationMillis: Long?

        data class Captured(
            override val owner: Owner,
            val recording: OpaqueAudioRecording,
        ) : Entry {
            override val kind: Kind = Kind.Captured
            override val retainedBytes: Long = recording.retainedBytes
            override val durationMillis: Long? = recording.durationMillis
        }

        data class Synthesized(
            override val owner: Owner,
            val audio: OpaqueSynthesizedAudio,
        ) : Entry {
            override val kind: Kind = Kind.Synthesized
            override val retainedBytes: Long = audio.retainedBytes
            override val durationMillis: Long? = audio.durationMillis
        }
    }

    private val random = SecureRandom()
    private val entries = mutableMapOf<String, Entry>()
    private val ownerTokens = mutableMapOf<Owner, Int>()
    private val ownerBytes = mutableMapOf<Owner, Long>()
    private var generationTokens: Int = 0
    private var generationBytes: Long = 0L
    private var closed = false

    @Synchronized
    fun admitCaptured(owner: Owner, recording: OpaqueAudioRecording): Token? =
        admit(Entry.Captured(owner, recording))

    @Synchronized
    fun admitSynthesized(owner: Owner, audio: OpaqueSynthesizedAudio): Token? =
        admit(Entry.Synthesized(owner, audio))

    @Synchronized
    fun resolve(token: Token, owner: Owner, kind: Kind): Resolution {
        if (closed) return Resolution.Closed
        val entry = entries[token.value] ?: return Resolution.Stale
        if (entry.owner != owner) return Resolution.Foreign
        if (entry.kind != kind) return Resolution.WrongKind
        return when (entry) {
            is Entry.Captured -> Resolution.Captured(entry.recording)
            is Entry.Synthesized -> Resolution.Synthesized(entry.audio)
        }
    }

    /**
     * Disposes one token without requiring its execution owner. Used only by
     * the host-side RecordingHost adapter after an audio-file result loses its
     * terminal race; Lua cannot invoke this path or name a token.
     */
    @Synchronized
    internal fun dispose(token: Token, kind: Kind) {
        val entry = entries[token.value] ?: return
        if (entry.kind != kind) return
        entries.remove(token.value)
        releaseAccounting(entry)
        disposeEntry(entry)
    }

    /**
     * Transfer an entry out of the registry on successful deferred-queue
     * admission. Releases its owner, generation, and process accounting.
     */
    @Synchronized
    internal fun consume(token: Token, owner: Owner, kind: Kind): Entry? {
        if (closed) return null
        val entry = entries[token.value] ?: return null
        if (entry.owner != owner) return null
        if (entry.kind != kind) return null
        entries.remove(token.value)
        releaseAccounting(entry)
        return entry
    }

    /**
     * Re-admit an entry whose deferred-queue admission failed. Re-claims its
     * owner, generation, and process accounting. Always succeeds against an
     * open registry: the artifact was validly admitted before its consume, so
     * its process claim is re-taken even under concurrent pressure via
     * [ProcessQuota.forceReserve].
     */
    @Synchronized
    internal fun restore(token: Token, entry: Entry) {
        if (closed) return
        entries[token.value] = entry
        reaccount(entry)
    }

    @Synchronized
    fun invalidateOwner(owner: Owner) {
        // Dispose every unconsumed artifact this execution owned, then drop
        // the mappings. Consumed artifacts (removed via [consume]) were
        // transferred to the queue and are not disposed here.
        val removed = mutableListOf<Entry>()
        entries.entries.removeIf { it.value.owner == owner && removed.add(it.value) }
        removed.forEach { entry ->
            releaseAccounting(entry)
            disposeEntry(entry)
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        // Dispose every remaining unconsumed artifact exactly once. Artifacts
        // already consumed (schedule admission succeeded) were removed from
        // the registry and own their lifecycle via the deferred queue.
        val snapshot = entries.values.toList()
        entries.clear()
        snapshot.forEach { entry ->
            releaseAccounting(entry)
            disposeEntry(entry)
        }
    }

    @Synchronized
    internal fun owns(token: Token): Boolean = !closed && token.value in entries

    /**
     * Snapshot of this generation's accounting. After [close] the snapshot is
     * identically zero. Per-owner accounting is omitted for owners with no
     * live artifacts.
     */
    @Synchronized
    internal fun accounting(): Accounting = Accounting(
        liveTokens = generationTokens,
        retainedBytes = generationBytes,
        perOwner = ownerTokens.mapValues { (owner, tokens) ->
            OwnerAccounting(tokens = tokens, bytes = ownerBytes[owner] ?: 0L)
        },
    )

    /**
     * Dispose one unconsumed artifact's host-owned data. The artifact's
     * [OpaqueAudioRecording.dispose] / [OpaqueSynthesizedAudio.dispose] is
     * idempotent, so redundant calls are safe; the registry guarantees each
     * entry reaches this path at most once via map removal.
     */
    private fun disposeEntry(entry: Entry) {
        when (entry) {
            is Entry.Captured -> entry.recording.dispose()
            is Entry.Synthesized -> entry.audio.dispose()
        }
    }

    /**
     * Validate every quota for a candidate entry. On any violation the
     * candidate artifact is disposed and `null` is returned; no token is
     * created, no entry is stored, and no accounting is mutated so sibling
     * owners, the generation, and the process counters remain intact.
     */
    private fun admit(candidate: Entry): Token? {
        if (closed) return null
        val owner = candidate.owner
        val bytes = candidate.retainedBytes
        val duration = candidate.durationMillis

        if (bytes < 0L) {
            disposeEntry(candidate); return null
        }

        // Per-artifact bounds. Always enforced for bytes; for duration only
        // when the backend surfaced metadata.
        if (bytes > limits.maxBytesPerArtifact) {
            disposeEntry(candidate); return null
        }
        if (duration != null && duration > limits.maxDurationPerArtifactMillis) {
            disposeEntry(candidate); return null
        }

        val ownerTokensNew = (ownerTokens[owner] ?: 0) + 1
        val ownerBytesNew = (ownerBytes[owner] ?: 0L) + bytes
        val generationTokensNew = generationTokens + 1
        val generationBytesNew = generationBytes + bytes

        if (ownerTokensNew > limits.maxTokensPerOwner ||
            ownerBytesNew > limits.maxBytesPerOwner
        ) {
            disposeEntry(candidate); return null
        }
        if (generationTokensNew > limits.maxTokensPerGeneration ||
            generationBytesNew > limits.maxBytesPerGeneration
        ) {
            disposeEntry(candidate); return null
        }
        // Process accountant reserves atomically; reject on exhaustion.
        if (!processQuota.tryReserve(1, bytes)) {
            disposeEntry(candidate); return null
        }

        val token = Token(nextToken())
        entries[token.value] = candidate
        ownerTokens[owner] = ownerTokensNew
        ownerBytes[owner] = ownerBytesNew
        generationTokens = generationTokensNew
        generationBytes = generationBytesNew
        return token
    }

    /**
     * Release owner + generation + process accounting for an entry that has
     * just left the registry (consumed, invalidated, or closed).
     */
    private fun releaseAccounting(entry: Entry) {
        val owner = entry.owner
        val bytes = entry.retainedBytes
        val tokens = (ownerTokens[owner] ?: 0) - 1
        val ownerBytesAfter = (ownerBytes[owner] ?: 0L) - bytes
        if (tokens <= 0) {
            ownerTokens.remove(owner)
        } else {
            ownerTokens[owner] = tokens
        }
        if (ownerBytesAfter <= 0L) {
            ownerBytes.remove(owner)
        } else {
            ownerBytes[owner] = ownerBytesAfter
        }
        generationTokens -= 1
        generationBytes -= bytes
        processQuota.release(1, bytes)
    }

    /**
     * Re-claim owner + generation + process accounting for a restored entry.
     * Owner/generation counters are local and always accept the revert; the
     * process accountant is force-reserved so a previously-admitted artifact
     * retains its process claim even under concurrent pressure.
     */
    private fun reaccount(entry: Entry) {
        val owner = entry.owner
        val bytes = entry.retainedBytes
        ownerTokens[owner] = (ownerTokens[owner] ?: 0) + 1
        ownerBytes[owner] = (ownerBytes[owner] ?: 0L) + bytes
        generationTokens += 1
        generationBytes += bytes
        processQuota.forceReserve(1, bytes)
    }

    private fun nextToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
