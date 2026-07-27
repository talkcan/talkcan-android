package io.talkcan.resource

import io.talkcan.dependency.PackageMountDeclaration
import io.talkcan.model.ChannelImplementationId
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * 2.3: Load result. A missing or empty file is a fresh empty store; a corrupt
 * document leaves the store unloaded so no later write can overwrite it.
 */
public sealed interface MountBindingLoadResult {
    public data class Loaded(val bindings: List<MountBinding>) : MountBindingLoadResult
    public data class Corrupt(val error: MountBindingDecodeError) : MountBindingLoadResult
}

/**
 * 2.3: Adapter-supplied candidate validation hook executed inside the
 * replacement transaction before any write. Reasons are portable; platform
 * detail never enters them.
 */
public fun interface MountBindingCandidateValidator {
    public fun validate(candidate: MountBinding): MountBindingCandidateOutcome
}

public sealed interface MountBindingCandidateOutcome {
    public data object Accepted : MountBindingCandidateOutcome
    public data class Rejected(val reason: String) : MountBindingCandidateOutcome
}

/**
 * 2.3: Shared failure vocabulary for transactional mutations. Every failure
 * preserves the previously committed binding in memory and on disk.
 */
public sealed interface MountBindingFailure {
    /** The store has not successfully loaded; mutations are refused. */
    public data object NotLoaded : MountBindingFailure

    /** The adapter-provided candidate validator rejected the candidate. */
    public data class CandidateRejected(val reason: String) : MountBindingFailure

    /** No binding exists at the addressed key. */
    public data class BindingNotFound(val key: MountBindingKey) : MountBindingFailure

    /** The binding document could not be persisted; the prior file remains. */
    public data class WriteFailed(val operation: String, val cause: IOException) : MountBindingFailure
}

/** 2.3: Replacement result; [replaced] is the displaced prior binding, if any. */
public sealed interface MountBindingWriteResult {
    public data class Committed(
        val binding: MountBinding,
        val replaced: MountBinding?,
        val snapshot: List<MountBinding>,
    ) : MountBindingWriteResult

    public data class Failed(val failure: MountBindingFailure) : MountBindingWriteResult
}

/** 2.3: Status refresh result. */
public sealed interface MountBindingStatusResult {
    public data class Committed(val binding: MountBinding, val previous: MountBinding) : MountBindingStatusResult
    public data object Unchanged : MountBindingStatusResult
    public data class Failed(val failure: MountBindingFailure) : MountBindingStatusResult
}

/** 2.4: Update/rollback application result. */
public sealed interface MountBindingUpdateResult {
    public data class Committed(
        val outcome: MountBindingUpdateOutcome,
        val snapshot: List<MountBinding>,
    ) : MountBindingUpdateResult

    public data object Unchanged : MountBindingUpdateResult
    public data class Failed(val failure: MountBindingFailure) : MountBindingUpdateResult
}

/** 2.5: Removal result with reference-aware grant cleanup classification. */
public sealed interface MountBindingRemovalResult {
    public data class Committed(
        val removal: MountBindingRemoval,
        val snapshot: List<MountBinding>,
    ) : MountBindingRemovalResult

    /** Nothing matched the address; no write occurred. */
    public data object Unchanged : MountBindingRemovalResult

    public data class Failed(val failure: MountBindingFailure) : MountBindingRemovalResult
}

/**
 * 2.2/2.3/2.4/2.5: Repository-persistent mount-binding store.
 *
 * Bindings are keyed by channel instance, provider implementation identity,
 * and declaration ID, and encoded through [MountBindingCodec]. Every mutation
 * is transactional: the next document is fully encoded and validated, written
 * to a temporary file, fsynced, and atomically moved over the current file.
 * On failed selection, validation, update, rollback, or write, the previously
 * committed bindings remain active in memory and on disk.
 *
 * The store never interprets grant bytes and never releases platform
 * permissions; it only reports which opaque grants become unreferenced so an
 * adapter can apply explicit lifecycle policy.
 */
public class MountBindingStore(private val file: File) {
    private val lock = Any()
    private var loaded: Boolean = false
    private var bindings: LinkedHashMap<MountBindingKey, MountBinding> = LinkedHashMap()

    public val isLoaded: Boolean
        get() = synchronized(lock) { loaded }

    /**
     * 2.2: Load the persisted document. Missing or empty files load as an
     * empty store; corrupt documents leave the store unloaded and untouched
     * so no mutation can silently overwrite recoverable state.
     */
    public fun load(): MountBindingLoadResult = synchronized(lock) {
        if (!file.exists() || file.length() == 0L) {
            bindings = LinkedHashMap()
            loaded = true
            return MountBindingLoadResult.Loaded(emptyList())
        }
        val text = try {
            file.readText(StandardCharsets.UTF_8)
        } catch (error: IOException) {
            loaded = false
            return MountBindingLoadResult.Corrupt(
                MountBindingDecodeError.MalformedDocument(error.message ?: "Unable to read binding document")
            )
        }
        when (val decoded = MountBindingCodec.decode(text)) {
            is MountBindingDecodeResult.Success -> {
                val next = LinkedHashMap<MountBindingKey, MountBinding>(decoded.bindings.size)
                for (binding in decoded.bindings) {
                    next[binding.key] = binding
                }
                bindings = next
                loaded = true
                MountBindingLoadResult.Loaded(snapshot(next))
            }
            is MountBindingDecodeResult.Failure -> {
                loaded = false
                MountBindingLoadResult.Corrupt(decoded.error)
            }
        }
    }

    /** Deterministic snapshot of every persisted binding. */
    public fun bindings(): List<MountBinding> = synchronized(lock) { snapshot(bindings) }

    /** Deterministic snapshot of one instance's bindings (active and dormant). */
    public fun bindingsForInstance(channelInstanceId: String): List<MountBinding> = synchronized(lock) {
        snapshot(bindings).filter { it.channelInstanceId == channelInstanceId }
    }

    /** Current binding at the exact key, or null. */
    public fun currentBinding(
        channelInstanceId: String,
        implementationId: ChannelImplementationId,
        declarationId: String,
    ): MountBinding? = synchronized(lock) {
        bindings[MountBindingKey(channelInstanceId, implementationId, declarationId)]
    }

    /**
     * 2.3: Transactionally replace (or create) the binding for one declared
     * mount. Kind, access, and declaration ID come from the validated package
     * declaration; the committed binding is active. The optional
     * [candidateValidator] runs inside the transaction before any write; a
     * rejection or write failure preserves the prior binding.
     */
    public fun replaceBinding(
        declaration: PackageMountDeclaration,
        channelInstanceId: String,
        implementationId: ChannelImplementationId,
        grant: PlatformGrantBlob,
        status: MountBindingStatus,
        candidateValidator: MountBindingCandidateValidator? = null,
    ): MountBindingWriteResult = synchronized(lock) {
        if (!loaded) {
            return MountBindingWriteResult.Failed(MountBindingFailure.NotLoaded)
        }
        val candidate = MountBinding(
            channelInstanceId = channelInstanceId,
            implementationId = implementationId,
            declarationId = declaration.id,
            kind = declaration.kind,
            access = declaration.access,
            grant = grant,
            status = status,
            state = MountBindingState.ACTIVE,
        )
        when (val outcome = candidateValidator?.validate(candidate)) {
            null, MountBindingCandidateOutcome.Accepted -> Unit
            is MountBindingCandidateOutcome.Rejected ->
                return MountBindingWriteResult.Failed(MountBindingFailure.CandidateRejected(outcome.reason))
        }
        val next = LinkedHashMap(bindings)
        val replaced = next.put(candidate.key, candidate)
        val failure = persist(next)
        if (failure != null) {
            return MountBindingWriteResult.Failed(failure)
        }
        MountBindingWriteResult.Committed(candidate, replaced, snapshot(next))
    }

    /**
     * 2.3: Transactionally refresh only the portable status of an existing
     * binding (reauthorization, grant revocation, read-only transitions).
     */
    public fun updateStatus(
        channelInstanceId: String,
        implementationId: ChannelImplementationId,
        declarationId: String,
        status: MountBindingStatus,
    ): MountBindingStatusResult = synchronized(lock) {
        if (!loaded) {
            return MountBindingStatusResult.Failed(MountBindingFailure.NotLoaded)
        }
        val key = MountBindingKey(channelInstanceId, implementationId, declarationId)
        val previous = bindings[key]
            ?: return MountBindingStatusResult.Failed(MountBindingFailure.BindingNotFound(key))
        if (previous.status == status) {
            return MountBindingStatusResult.Unchanged
        }
        val updated = previous.copy(status = status)
        val next = LinkedHashMap(bindings)
        next[key] = updated
        val failure = persist(next)
        if (failure != null) {
            return MountBindingStatusResult.Failed(failure)
        }
        MountBindingStatusResult.Committed(updated, previous)
    }

    /**
     * 2.4: Apply a pure update/rollback classification. Retained bindings
     * become active; dormant bindings are preserved with their grants for an
     * explicit rollback. Bindings not listed are untouched.
     */
    public fun applyUpdate(outcome: MountBindingUpdateOutcome): MountBindingUpdateResult = synchronized(lock) {
        if (!loaded) {
            return MountBindingUpdateResult.Failed(MountBindingFailure.NotLoaded)
        }
        val next = LinkedHashMap(bindings)
        for (binding in outcome.retained + outcome.dormant) {
            if (!next.containsKey(binding.key)) {
                return MountBindingUpdateResult.Failed(MountBindingFailure.BindingNotFound(binding.key))
            }
            next[binding.key] = binding
        }
        if (next == bindings) {
            return MountBindingUpdateResult.Unchanged
        }
        val failure = persist(next)
        if (failure != null) {
            return MountBindingUpdateResult.Failed(failure)
        }
        MountBindingUpdateResult.Committed(outcome, snapshot(next))
    }

    /**
     * 2.5: Remove one binding. The result classifies whether its opaque grant
     * became unreferenced or is still referenced by another binding.
     */
    public fun removeBinding(
        channelInstanceId: String,
        implementationId: ChannelImplementationId,
        declarationId: String,
    ): MountBindingRemovalResult = synchronized(lock) {
        if (!loaded) {
            return MountBindingRemovalResult.Failed(MountBindingFailure.NotLoaded)
        }
        val key = MountBindingKey(channelInstanceId, implementationId, declarationId)
        val removed = bindings[key] ?: return MountBindingRemovalResult.Unchanged
        removeInternal(listOf(removed))
    }

    /**
     * 2.5: Remove every binding of one channel instance. Grants shared with
     * bindings of other instances are reported as still referenced and MUST
     * NOT be released by the adapter.
     */
    public fun removeInstance(channelInstanceId: String): MountBindingRemovalResult = synchronized(lock) {
        if (!loaded) {
            return MountBindingRemovalResult.Failed(MountBindingFailure.NotLoaded)
        }
        val removed = bindings.values.filter { it.channelInstanceId == channelInstanceId }
        if (removed.isEmpty()) {
            return MountBindingRemovalResult.Unchanged
        }
        removeInternal(removed)
    }

    private fun removeInternal(removed: List<MountBinding>): MountBindingRemovalResult {
        val next = LinkedHashMap(bindings)
        for (binding in removed) {
            next.remove(binding.key)
        }
        val removal = mountBindingRemoval(removed, next.values)
        val failure = persist(next)
        if (failure != null) {
            return MountBindingRemovalResult.Failed(failure)
        }
        return MountBindingRemovalResult.Committed(removal, snapshot(next))
    }

    /**
     * Persist [next] atomically; on success commit it as the in-memory state
     * and return null, otherwise leave all state untouched and report the
     * failure.
     */
    private fun persist(next: LinkedHashMap<MountBindingKey, MountBinding>): MountBindingFailure? {
        val json = try {
            MountBindingCodec.encode(next.values.toList())
        } catch (error: IllegalArgumentException) {
            return MountBindingFailure.CandidateRejected(error.message ?: "Binding document failed validation")
        }
        val parent = file.absoluteFile.parentFile
        val temporary = File(parent, "${file.name}.tmp")
        return try {
            parent.mkdirs()
            FileOutputStream(temporary).use { stream ->
                stream.write(json.toByteArray(StandardCharsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            fsyncDirectory(parent)
            bindings = next
            null
        } catch (error: IOException) {
            if (temporary.exists()) {
                try {
                    temporary.delete()
                } catch (_: IOException) {
                    // Best-effort cleanup; the committed document is untouched.
                }
            }
            MountBindingFailure.WriteFailed("save mount bindings", error)
        }
    }

    private fun fsyncDirectory(directory: File) {
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        } catch (_: IOException) {
            // Best-effort directory durability; not supported by every filesystem.
        }
    }

    private fun snapshot(state: LinkedHashMap<MountBindingKey, MountBinding>): List<MountBinding> =
        state.values.sortedWith(
            compareBy(
                { it.channelInstanceId },
                { it.implementationId.value },
                { it.declarationId },
            )
        )
}
