package io.talkcan.lua

import io.talkcan.profile.ProfileAvailability
import io.talkcan.profile.ProfileRecord
import io.talkcan.profile.ProfileScalarValue
import io.talkcan.profile.SecretReferenceState
import io.talkcan.secret.ProtectedSecretReference
import io.talkcan.work.WorkValue
import io.talkcan.work.encodeValue
import org.json.JSONObject
import java.util.UUID

/**
 * One generation/state-local secret reference grant binding (task 8.1).
 *
 * The [referenceToken] is Kotlin-minted, opaque, and the only value that
 * crosses JNI into the native SecretReference userdata. It carries no
 * keystore alias, storage path, profile content, or platform object. The
 * protected [reference] never appears in Lua-visible data; the native kernel
 * confines the token and echoes it verbatim in SECRET_READ claims, and the
 * host maps it back here before any protected-store access.
 */
internal data class SecretReferenceBinding(
    val referenceToken: String,
    val reference: ProtectedSecretReference,
    val packageRepositoryId: Long,
    val profileId: String,
    val fieldId: String,
    val profileRevision: Long,
) {
    init {
        require(referenceToken.isNotBlank()) { "Secret reference token must not be blank" }
    }
}

/**
 * Immutable token registry for one runtime generation or resolver invocation.
 *
 * Created alongside [ProfileGrantSnapshot]s; discarded wholesale when the
 * generation is replaced, grants are rebuilt, or the resolver closes, which
 * is how predecessor tokens become unresolvable (the host-side mirror of the
 * native stale stamp). An unknown or predecessor token resolves to `null`,
 * and adapters normalize `null` to a nondisclosing denial.
 */
internal class SecretReferenceTokenRegistry(bindings: List<SecretReferenceBinding>) {
    private val byToken: Map<String, SecretReferenceBinding> = bindings.associateBy { it.referenceToken }

    init {
        require(bindings.map { it.referenceToken }.distinct().size == bindings.size) {
            "Secret reference tokens must be unique within one generation"
        }
    }

    /** Resolve one opaque token; `null` for unknown, foreign, or stale tokens. */
    fun resolve(referenceToken: String?): SecretReferenceBinding? =
        referenceToken?.let { byToken[it] }

    val size: Int get() = byToken.size
}

/**
 * One detached selected-profile snapshot for grant injection (task 8.2).
 *
 * Contains exactly the profile identity Lua may observe through
 * `talkcan.profiles.get` (`id`, `type`, `name`), scalar field values in the
 * shared tagged [WorkValue] encoding, and opaque secret reference bindings.
 * No plaintext, no mutable repository object, and no persistence handle is
 * reachable from this type; mutating a copy never touches the profile store.
 */
internal data class ProfileGrantSnapshot(
    val profileId: String,
    val typeLocalId: String,
    val displayName: String,
    val profileRevision: Long,
    val values: Map<String, WorkValue>,
    val secretBindings: Map<String, SecretReferenceBinding>,
) {
    init {
        require(profileId.isNotBlank()) { "Grant profile id must not be blank" }
        require(typeLocalId.isNotBlank()) { "Grant profile type id must not be blank" }
        val overlap = values.keys.intersect(secretBindings.keys)
        require(overlap.isEmpty()) { "Scalar and secret grant fields must be disjoint" }
    }

    internal fun toJson(): JSONObject = JSONObject().apply {
        put("profileId", profileId)
        put("typeLocalId", typeLocalId)
        put("displayName", displayName)
        put("values", JSONObject().apply {
            values.forEach { (fieldId, value) -> put(fieldId, encodeValue(value)) }
        })
        put("secretReferences", JSONObject().apply {
            secretBindings.forEach { (fieldId, binding) -> put(fieldId, binding.referenceToken) }
        })
    }
}

/**
 * One selected profile plus the host identity needed to build its detached
 * grant snapshot. [record] is an immutable repository snapshot; [typeLocalId]
 * is the package-local profile type id; [packageRepositoryId] is the durable
 * repository database id that owns the type.
 */
internal data class ProfileGrantInput(
    val record: ProfileRecord,
    val typeLocalId: String,
    val packageRepositoryId: Long,
)

/**
 * Detached grant set for one generation or resolver invocation (task 8.2).
 *
 * Built once from immutable profile records; never mutated in place. Profile
 * edit/delete or package replacement produce a new generation with fresh
 * tokens, so a snapshot never silently changes under a live state. The exact
 * [grantsJson] matches the native `setProfileGrants` schema.
 */
internal class ProfileGrantGeneration private constructor(
    val snapshots: List<ProfileGrantSnapshot>,
    val registry: SecretReferenceTokenRegistry,
) {

    /** Exact grants document for `nativeSetProfileGrants`. */
    fun grantsJson(): String = JSONObject().apply {
        put("profiles", org.json.JSONArray().apply {
            snapshots.forEach { put(it.toJson()) }
        })
    }.toString()

    companion object {

        /**
         * Build a detached generation from selected profiles. Only AVAILABLE
         * records are granted; unavailable records are skipped so incompatible
         * or removed profiles never produce runtime authority. Secret fields
         * with a present protected reference receive a fresh opaque token;
         * absent secrets are simply not granted.
         * [includeSecrets] false narrows the grant to scalar fields only
         * (used when the consuming capability subset excludes secrets.read).
         */
        fun build(
            inputs: List<ProfileGrantInput>,
            includeSecrets: Boolean = true,
            tokenGenerator: () -> String = { UUID.randomUUID().toString() },
        ): ProfileGrantGeneration {
            val snapshots = ArrayList<ProfileGrantSnapshot>(inputs.size)
            val bindings = ArrayList<SecretReferenceBinding>()
            for (input in inputs) {
                val record = input.record
                if (record.availability != ProfileAvailability.AVAILABLE) continue
                val values = LinkedHashMap<String, WorkValue>(record.scalarPayload.size)
                for ((fieldId, scalar) in record.scalarPayload) {
                    values[fieldId] = scalar.toWorkValue()
                }
                val secrets = LinkedHashMap<String, SecretReferenceBinding>()
                if (includeSecrets) {
                    for ((fieldId, state) in record.secretReferences) {
                        val present = state as? SecretReferenceState.Present ?: continue
                        val binding = SecretReferenceBinding(
                            referenceToken = tokenGenerator(),
                            reference = ProtectedSecretReference(present.referenceId),
                            packageRepositoryId = input.packageRepositoryId,
                            profileId = record.profileId.value,
                            fieldId = fieldId,
                            profileRevision = record.revision,
                        )
                        secrets[fieldId] = binding
                        bindings.add(binding)
                    }
                }
                snapshots.add(
                    ProfileGrantSnapshot(
                        profileId = record.profileId.value,
                        typeLocalId = input.typeLocalId,
                        displayName = record.displayName,
                        profileRevision = record.revision,
                        values = values,
                        secretBindings = secrets,
                    ),
                )
            }
            return ProfileGrantGeneration(snapshots, SecretReferenceTokenRegistry(bindings))
        }
    }
}

/** Exact scalar mapping into the shared tagged value encoding. */
internal fun ProfileScalarValue.toWorkValue(): WorkValue = when (this) {
    is ProfileScalarValue.StringValue -> WorkValue.Text(value)
    is ProfileScalarValue.BooleanValue -> WorkValue.Bool(value)
    is ProfileScalarValue.IntegerValue -> WorkValue.Integer(value)
}
