package io.talkcan.secret

/**
 * A generation/state-local grant authorizing one Lua state to resolve one protected secret
 * field. Bound to package, profile, field, and profile revision so that profile edits or
 * package replacement invalidate predecessor grants. The [reference] is an opaque token;
 * no keystore alias or platform object appears in Lua-visible data.
 */
data class SecretFieldGrant(
    val packageRepositoryId: Long,
    val profileId: String,
    val fieldId: String,
    val profileRevision: Long,
    val reference: ProtectedSecretReference,
)

/**
 * Immutable snapshot of secret grants for one runtime generation or resolver invocation.
 * Created at construction time from selected profiles; never mutated in place. Profile edits,
 * deletions, or generation replacement produce a new snapshot.
 */
class GenerationSecretGrants(grants: List<SecretFieldGrant>) {
    private val byField: Map<String, SecretFieldGrant> = grants.associateBy { it.fieldId }

    init {
        require(grants.map { it.fieldId }.distinct().size == grants.size) {
            "Secret field grants must have unique field IDs"
        }
    }

    fun grantFor(fieldId: String): SecretFieldGrant? = byField[fieldId]

    val fields: Set<String>
        get() = byField.keys

    val references: Set<ProtectedSecretReference>
        get() = byField.values.mapTo(mutableSetOf()) { it.reference }
}
