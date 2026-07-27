package io.talkcan.profile

import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.secret.PreparedSecretMutation
import io.talkcan.secret.ProtectedSecretError
import io.talkcan.secret.ProtectedSecretReference
import io.talkcan.secret.ProtectedSecretResult
import io.talkcan.secret.ProtectedSecretStore

/** Repository database IDs used across profile tests. */
internal val REPO_A = GitHubRepositoryIdentity("123456")
internal val REPO_B = GitHubRepositoryIdentity("999999")

internal val TYPE_A = ProfileTypeIdentity(REPO_A, "openai_compatible")
internal val TYPE_B = ProfileTypeIdentity(REPO_B, "openai_compatible")

/**
 * Builds the canonical `openai_compatible` schema: one required string
 * `base_url` and one required secret `api_key`. Toggle [baseUrlRequired] /
 * [apiKeyRequired] or supply [extraFields] to craft incompatible schemas for
 * update/rollback tests.
 */
internal fun openAiSchema(
    baseUrlRequired: Boolean = true,
    apiKeyRequired: Boolean = true,
    extraFields: List<ProfileFieldDeclaration> = emptyList(),
): ProfileSchema {
    val dataFields = buildList {
        add(ProfileFieldDeclaration.StringField("base_url", baseUrlRequired, null, null))
        addAll(extraFields)
        add(ProfileFieldDeclaration.SecretField("api_key", apiKeyRequired))
    }
    val uiFields = buildList {
        add(ProfileUiFieldDeclaration("base_url", ProfileUiControl.TEXT, "Base URL", null, null))
        for (extra in extraFields) {
            val control = when (extra) {
                is ProfileFieldDeclaration.BooleanField -> ProfileUiControl.TOGGLE
                is ProfileFieldDeclaration.IntegerField -> ProfileUiControl.NUMBER
                is ProfileFieldDeclaration.SecretField -> ProfileUiControl.SECRET
                is ProfileFieldDeclaration.StringField ->
                    if (extra.allowedValues != null) ProfileUiControl.CHOICE else ProfileUiControl.TEXT
            }
            val choices = if (extra is ProfileFieldDeclaration.StringField && extra.allowedValues != null) {
                extra.allowedValues.map { ProfileUiChoice(it, it) }
            } else {
                null
            }
            add(ProfileUiFieldDeclaration(extra.id, control, extra.id, null, choices))
        }
        add(ProfileUiFieldDeclaration("api_key", ProfileUiControl.SECRET, "API Key", null, null))
    }
    return ProfileSchema(dataFields, uiFields)
}

internal fun basePayload(url: String = "https://api.openai.com/v1"): Map<String, ProfileScalarValue> =
    mapOf("base_url" to ProfileScalarValue.StringValue(url))

/**
 * In-memory [ProtectedSecretStore] faithfully modelling the
 * prepare/commit/rollback protocol of the production keystore store, with
 * deterministic fault injection. Active values are keyed by reference token;
 * cleared/deleted ciphertext is retained for rollback until commit.
 */
internal class FakeProtectedSecretStore : ProtectedSecretStore {
    val active: LinkedHashMap<String, String> = LinkedHashMap()
    private val retained: LinkedHashMap<String, String> = LinkedHashMap()

    /** Reference tokens retired (old value deleted) on a replace commit. */
    val retiredOnCommit: MutableList<String> = mutableListOf()

    /** Reference tokens whose prepared write was rolled back. */
    val rolledBack: MutableList<String> = mutableListOf()

    var failCreate: Boolean = false
    var failReplace: Boolean = false
    var failClear: Boolean = false
    var failDelete: Boolean = false
    var throwOnPrepare: Boolean = false

    override fun prepareCreate(
        reference: ProtectedSecretReference,
        plaintext: CharSequence,
    ): ProtectedSecretResult<PreparedSecretMutation> {
        if (throwOnPrepare) throw IllegalStateException("prepare failed")
        if (failCreate) return ProtectedSecretResult.Failure(ProtectedSecretError.StorageUnavailable)
        active[reference.token] = plaintext.toString()
        return ProtectedSecretResult.Success(object : PreparedSecretMutation {
            override fun commit(): ProtectedSecretResult<Unit> = ProtectedSecretResult.Success(Unit)
            override fun rollback(): ProtectedSecretResult<Unit> {
                active.remove(reference.token)
                rolledBack.add(reference.token)
                return ProtectedSecretResult.Success(Unit)
            }
        })
    }

    override fun prepareReplace(
        oldReference: ProtectedSecretReference,
        newReference: ProtectedSecretReference,
        plaintext: CharSequence,
    ): ProtectedSecretResult<PreparedSecretMutation> {
        if (throwOnPrepare) throw IllegalStateException("prepare failed")
        if (failReplace) return ProtectedSecretResult.Failure(ProtectedSecretError.StorageUnavailable)
        // New value is written immediately; the old value is held until commit.
        active[newReference.token] = plaintext.toString()
        return ProtectedSecretResult.Success(object : PreparedSecretMutation {
            override fun commit(): ProtectedSecretResult<Unit> {
                active.remove(oldReference.token)
                retiredOnCommit.add(oldReference.token)
                return ProtectedSecretResult.Success(Unit)
            }
            override fun rollback(): ProtectedSecretResult<Unit> {
                active.remove(newReference.token)
                rolledBack.add(newReference.token)
                return ProtectedSecretResult.Success(Unit)
            }
        })
    }

    override fun prepareClear(
        reference: ProtectedSecretReference,
    ): ProtectedSecretResult<PreparedSecretMutation> {
        if (throwOnPrepare) throw IllegalStateException("prepare failed")
        if (failClear) return ProtectedSecretResult.Failure(ProtectedSecretError.StorageUnavailable)
        return ProtectedSecretResult.Success(removal(reference))
    }

    override fun prepareDelete(
        reference: ProtectedSecretReference,
    ): ProtectedSecretResult<PreparedSecretMutation> {
        if (throwOnPrepare) throw IllegalStateException("prepare failed")
        if (failDelete) return ProtectedSecretResult.Failure(ProtectedSecretError.StorageUnavailable)
        return ProtectedSecretResult.Success(removal(reference))
    }

    private fun removal(reference: ProtectedSecretReference): PreparedSecretMutation {
        val removed = active.remove(reference.token)
        if (removed != null) retained[reference.token] = removed
        return object : PreparedSecretMutation {
            override fun commit(): ProtectedSecretResult<Unit> {
                retained.remove(reference.token)
                return ProtectedSecretResult.Success(Unit)
            }
            override fun rollback(): ProtectedSecretResult<Unit> {
                retained.remove(reference.token)?.let { active[reference.token] = it }
                rolledBack.add(reference.token)
                return ProtectedSecretResult.Success(Unit)
            }
        }
    }

    override fun contains(reference: ProtectedSecretReference): Boolean =
        active.containsKey(reference.token)

    override fun <T> use(
        reference: ProtectedSecretReference,
        block: (CharSequence) -> T,
    ): ProtectedSecretResult<T> {
        val value = active[reference.token]
            ?: return ProtectedSecretResult.Failure(ProtectedSecretError.NotFound)
        return ProtectedSecretResult.Success(block(value))
    }
}
