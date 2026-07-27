package io.talkcan.resource

import io.talkcan.dependency.PackageMountAccess
import io.talkcan.dependency.PackageMountDeclaration
import io.talkcan.dependency.PackageMountKind
import io.talkcan.dependency.PackageResourceLimits
import io.talkcan.dependency.PackageResourcesDeclaration
import io.talkcan.model.ChannelImplementationId
import java.nio.charset.StandardCharsets

/**
 * 2.1/2.2: Finite bounds for the persistent mount-binding subsystem.
 *
 * These are host policy limits for the binding document and its identifiers,
 * not Lua compatibility promises.
 */
public object MountBindingLimits {
    /** Maximum opaque platform-grant blob size (SAF tree URI + flags fit comfortably). */
    public const val MAX_GRANT_BLOB_BYTES: Int = 8192

    /** Maximum UTF-8 byte length of a channel instance identifier. */
    public const val MAX_INSTANCE_ID_BYTES: Int = 128

    /** Maximum number of bindings persisted in one binding document. */
    public const val MAX_BINDINGS: Int = 128

    /** Maximum encoded binding-document size accepted by the strict decoder. */
    public const val MAX_DOCUMENT_BYTES: Int = 4 * 1024 * 1024
}

/**
 * 2.1: Opaque immutable platform permission bytes.
 *
 * Android stores the exact persisted SAF tree URI and granted flags behind
 * this type; a future iOS adapter may store security-scoped bookmark bytes.
 * The bytes never enter logs, snapshots, Lua values, catalogue configuration,
 * provider objects, or error detail: [toString] exposes only the size.
 *
 * Identity is exact byte-content equality; the adapter defines the canonical
 * encoding, so two bindings referencing byte-identical blobs reference the
 * same underlying platform grant for reference-counted release.
 */
public class PlatformGrantBlob(bytes: ByteArray) {
    private val bytes: ByteArray = bytes.copyOf()

    init {
        require(this.bytes.isNotEmpty()) { "Platform grant blob must not be empty" }
        require(this.bytes.size <= MountBindingLimits.MAX_GRANT_BLOB_BYTES) {
            "Platform grant blob exceeds ${MountBindingLimits.MAX_GRANT_BLOB_BYTES} bytes: ${this.bytes.size}"
        }
    }

    public val sizeBytes: Int get() = bytes.size

    /** Defensive copy; the platform adapter is the only intended consumer. */
    public fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || (other is PlatformGrantBlob && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "PlatformGrantBlob(${sizeBytes} bytes)"
}

/**
 * 2.1: Portable mount status projected to readiness and the generic editor.
 *
 * Exactly the four portable values; platform adapters map platform grant
 * facts onto these and never expose Android or iOS detail.
 */
public enum class MountBindingStatus(public val portable: String) {
    AVAILABLE("available"),
    READ_ONLY("read-only"),
    NEEDS_REAUTHORIZATION("needs-reauthorization"),
    UNAVAILABLE("unavailable");

    public companion object {
        public fun fromPortable(value: String): MountBindingStatus? =
            entries.firstOrNull { it.portable == value }
    }
}

/**
 * 2.1: Store-level lifecycle state of a persisted binding.
 *
 * [ACTIVE] bindings are eligible for runtime use after live validation;
 * [DORMANT] bindings are preserved for an explicit package rollback but are
 * never exposed to Lua. Dormancy is distinct from portable [MountBindingStatus].
 */
public enum class MountBindingState(public val encoded: String) {
    ACTIVE("active"),
    DORMANT("dormant");

    public companion object {
        public fun fromEncoded(value: String): MountBindingState? =
            entries.firstOrNull { it.encoded == value }
    }
}

/**
 * 2.2: Binding identity: channel instance + provider implementation identity +
 * declared mount ID. Provider revision updates never rekey bindings.
 */
public data class MountBindingKey(
    val channelInstanceId: String,
    val implementationId: ChannelImplementationId,
    val declarationId: String,
)

/**
 * 2.1: One immutable persisted mount binding.
 *
 * A binding retains the declared kind/access captured at selection time, the
 * opaque platform grant, the portable status, and the store lifecycle state,
 * independently of scalar channel configuration and runtime generations.
 */
public data class MountBinding(
    val channelInstanceId: String,
    val implementationId: ChannelImplementationId,
    val declarationId: String,
    val kind: PackageMountKind,
    val access: PackageMountAccess,
    val grant: PlatformGrantBlob,
    val status: MountBindingStatus,
    val state: MountBindingState,
) {
    init {
        require(channelInstanceId.isNotBlank()) { "Channel instance ID must not be blank" }
        require(
            channelInstanceId.toByteArray(StandardCharsets.UTF_8).size <=
                MountBindingLimits.MAX_INSTANCE_ID_BYTES
        ) {
            "Channel instance ID exceeds ${MountBindingLimits.MAX_INSTANCE_ID_BYTES} bytes"
        }
        require(DECLARATION_ID_REGEX.matches(declarationId)) {
            "Declaration ID does not match pattern ^[a-z][a-z0-9_]*$: $declarationId"
        }
        require(
            declarationId.toByteArray(StandardCharsets.UTF_8).size <=
                PackageResourceLimits.MAX_MOUNT_ID_BYTES
        ) {
            "Declaration ID exceeds ${PackageResourceLimits.MAX_MOUNT_ID_BYTES} bytes: $declarationId"
        }
    }

    public val key: MountBindingKey
        get() = MountBindingKey(channelInstanceId, implementationId, declarationId)

    private companion object {
        private val DECLARATION_ID_REGEX = Regex("^[a-z][a-z0-9_]*$")
    }
}

/**
 * 2.4: Pure per-binding compatibility against a candidate provider revision.
 *
 * A binding is retained only under the same provider implementation identity
 * with the same declaration ID, kind, and requested access. Compatibility
 * classification never retargets a grant or constructs a new binding.
 */
public sealed interface MountBindingCompatibility {
    public data object Compatible : MountBindingCompatibility

    public sealed interface Incompatible : MountBindingCompatibility {
        public data class ForeignImplementation(
            val bindingImplementation: ChannelImplementationId,
            val requiredImplementation: ChannelImplementationId,
        ) : Incompatible

        public data object DeclarationRemoved : Incompatible
        public data class KindChanged(
            val bindingKind: PackageMountKind,
            val declaredKind: PackageMountKind,
        ) : Incompatible

        public data class AccessChanged(
            val bindingAccess: PackageMountAccess,
            val declaredAccess: PackageMountAccess,
        ) : Incompatible
    }
}

/** 2.4: Classify this binding against [implementationId] and its candidate [declaration]. */
public fun MountBinding.compatibilityWith(
    implementationId: ChannelImplementationId,
    declaration: PackageMountDeclaration?,
): MountBindingCompatibility = when {
    this.implementationId != implementationId ->
        MountBindingCompatibility.Incompatible.ForeignImplementation(this.implementationId, implementationId)
    declaration == null -> MountBindingCompatibility.Incompatible.DeclarationRemoved
    declaration.kind != kind -> MountBindingCompatibility.Incompatible.KindChanged(kind, declaration.kind)
    declaration.access != access -> MountBindingCompatibility.Incompatible.AccessChanged(access, declaration.access)
    else -> MountBindingCompatibility.Compatible
}

/**
 * 2.4: Result of classifying one implementation's bindings against an updated
 * declaration set. [retained] bindings carry [MountBindingState.ACTIVE];
 * [dormant] bindings carry [MountBindingState.DORMANT] and remain persisted
 * for an explicit rollback. Grants are never rewritten or retargeted.
 */
public data class MountBindingUpdateOutcome(
    val implementationId: ChannelImplementationId,
    val retained: List<MountBinding>,
    val dormant: List<MountBinding>,
) {
    init {
        require(retained.all { it.implementationId == implementationId }) {
            "Retained bindings must belong to the classified implementation"
        }
        require(dormant.all { it.implementationId == implementationId }) {
            "Dormant bindings must belong to the classified implementation"
        }
        val keys = HashSet<MountBindingKey>(retained.size + dormant.size)
        for (binding in retained + dormant) {
            require(keys.add(binding.key)) { "Duplicate binding key in update outcome: ${binding.key}" }
        }
    }
}

/**
 * 2.4: Pure update/rollback compatibility rules.
 *
 * Retains only bindings whose declaration ID still exists with the same kind
 * and access under the same provider identity; everything else becomes
 * dormant while preserved for rollback. No grant is retargeted and no new
 * binding is created.
 */
public object MountBindingUpdateRules {
    public fun classify(
        implementationId: ChannelImplementationId,
        updatedDeclarations: PackageResourcesDeclaration,
        current: Collection<MountBinding>,
    ): MountBindingUpdateOutcome {
        val declarationsById = updatedDeclarations.mounts.associateBy { it.id }
        val retained = ArrayList<MountBinding>(current.size)
        val dormant = ArrayList<MountBinding>()
        for (binding in current) {
            require(binding.implementationId == implementationId) {
                "Binding for ${binding.implementationId} classified under $implementationId"
            }
            when (binding.compatibilityWith(implementationId, declarationsById[binding.declarationId])) {
                MountBindingCompatibility.Compatible -> retained += binding.withState(MountBindingState.ACTIVE)
                is MountBindingCompatibility.Incompatible -> dormant += binding.withState(MountBindingState.DORMANT)
            }
        }
        return MountBindingUpdateOutcome(implementationId, retained, dormant)
    }

    private fun MountBinding.withState(state: MountBindingState): MountBinding =
        if (this.state == state) this else copy(state = state)
}

/**
 * 2.1: Typed reason a declared mount is not usable, without platform detail.
 */
public sealed interface MountUnavailableReason {
    /** The provider revision does not declare this mount. */
    public data object Undeclared : MountUnavailableReason

    /** No binding has ever been committed for this declared mount. */
    public data object Unbound : MountUnavailableReason

    /** The binding is preserved for rollback but not exposed to runtimes. */
    public data object Dormant : MountUnavailableReason

    /** The declaration changed incompatibly under the same provider identity. */
    public data class IncompatibleDeclaration(
        val compatibility: MountBindingCompatibility.Incompatible,
    ) : MountUnavailableReason

    /** Portable status maps to `needs-reauthorization`. */
    public data object NeedsReauthorization : MountUnavailableReason

    /** Portable status maps to `read-only` while read-write is declared. */
    public data object ReadOnly : MountUnavailableReason

    /** Portable status maps to `unavailable`. */
    public data object GrantUnavailable : MountUnavailableReason
}

/**
 * 2.1: Projected availability of one declared mount for one instance.
 */
public sealed interface MountAvailability {
    public data class Available(val binding: MountBinding) : MountAvailability
    public data class Unavailable(val reason: MountUnavailableReason) : MountAvailability
}

/**
 * 2.1: Pure availability projection from a declaration and its current
 * binding, suitable for readiness and the generic editor. Readiness never
 * grants authority; live operations revalidate the binding.
 */
public object MountAvailabilityProjection {
    public fun project(
        implementationId: ChannelImplementationId,
        declaration: PackageMountDeclaration,
        binding: MountBinding?,
    ): MountAvailability {
        if (binding == null) {
            return MountAvailability.Unavailable(MountUnavailableReason.Unbound)
        }
        if (binding.state == MountBindingState.DORMANT) {
            return MountAvailability.Unavailable(MountUnavailableReason.Dormant)
        }
        val compatibility = binding.compatibilityWith(implementationId, declaration)
        if (compatibility is MountBindingCompatibility.Incompatible) {
            return MountAvailability.Unavailable(MountUnavailableReason.IncompatibleDeclaration(compatibility))
        }
        return when (binding.status) {
            MountBindingStatus.AVAILABLE -> MountAvailability.Available(binding)
            MountBindingStatus.READ_ONLY -> MountAvailability.Unavailable(MountUnavailableReason.ReadOnly)
            MountBindingStatus.NEEDS_REAUTHORIZATION ->
                MountAvailability.Unavailable(MountUnavailableReason.NeedsReauthorization)
            MountBindingStatus.UNAVAILABLE -> MountAvailability.Unavailable(MountUnavailableReason.GrantUnavailable)
        }
    }
}

/**
 * 2.5: Reference-aware removal summary.
 *
 * [unreferencedGrants] are the opaque grants no remaining binding references;
 * only these may be released through explicit platform lifecycle policy.
 * [stillReferencedGrants] are grants of removed bindings still referenced by
 * another binding and MUST NOT be released.
 */
public data class MountBindingRemoval(
    val removed: List<MountBinding>,
    val unreferencedGrants: List<PlatformGrantBlob>,
    val stillReferencedGrants: List<PlatformGrantBlob>,
)

/**
 * 2.5: Pure reference-counted cleanup classification. Grant identity is
 * exact byte-content equality; a grant is unreferenced only when no remaining
 * binding references that exact blob.
 */
public fun mountBindingRemoval(
    removed: List<MountBinding>,
    remaining: Collection<MountBinding>,
): MountBindingRemoval {
    val remainingGrants = HashSet<PlatformGrantBlob>(remaining.size)
    for (binding in remaining) {
        remainingGrants += binding.grant
    }
    val seen = HashSet<PlatformGrantBlob>(removed.size)
    val unreferenced = ArrayList<PlatformGrantBlob>()
    val stillReferenced = ArrayList<PlatformGrantBlob>()
    for (binding in removed) {
        val grant = binding.grant
        if (!seen.add(grant)) continue
        if (grant in remainingGrants) stillReferenced += grant else unreferenced += grant
    }
    return MountBindingRemoval(removed, unreferenced, stillReferenced)
}
