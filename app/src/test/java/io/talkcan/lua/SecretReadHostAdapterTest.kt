package io.talkcan.lua

import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.profile.ProfileAvailability
import io.talkcan.profile.ProfileId
import io.talkcan.profile.ProfileLimits
import io.talkcan.profile.ProfileRecord
import io.talkcan.profile.ProfileScalarValue
import io.talkcan.profile.ProfileTypeIdentity
import io.talkcan.profile.SecretReferenceState
import io.talkcan.secret.PreparedSecretMutation
import io.talkcan.secret.ProtectedSecretError
import io.talkcan.secret.ProtectedSecretReference
import io.talkcan.secret.ProtectedSecretResult
import io.talkcan.secret.ProtectedSecretStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PLAINTEXT = "s3cr3t-plaintext-value"
private const val PROTECTED_REFERENCE_ID = "keystore-ref-1"

/** In-memory protected store: token -> plaintext with injectable `use` failure. */
private class FakeProtectedSecretStore : ProtectedSecretStore {
    private val secrets = HashMap<String, String>()
    val usedReferences = mutableListOf<ProtectedSecretReference>()
    var useFailure: ProtectedSecretError? = null

    fun put(reference: ProtectedSecretReference, plaintext: String) {
        secrets[reference.token] = plaintext
    }

    override fun <T> use(
        reference: ProtectedSecretReference,
        block: (CharSequence) -> T,
    ): ProtectedSecretResult<T> {
        usedReferences += reference
        useFailure?.let { return ProtectedSecretResult.Failure(it) }
        val plaintext = secrets[reference.token]
            ?: return ProtectedSecretResult.Failure(ProtectedSecretError.NotFound)
        return ProtectedSecretResult.Success(block(plaintext))
    }

    override fun contains(reference: ProtectedSecretReference): Boolean =
        secrets.containsKey(reference.token)

    override fun prepareCreate(
        reference: ProtectedSecretReference,
        plaintext: CharSequence,
    ): ProtectedSecretResult<PreparedSecretMutation> = notUsed()

    override fun prepareReplace(
        oldReference: ProtectedSecretReference,
        newReference: ProtectedSecretReference,
        plaintext: CharSequence,
    ): ProtectedSecretResult<PreparedSecretMutation> = notUsed()

    override fun prepareClear(
        reference: ProtectedSecretReference,
    ): ProtectedSecretResult<PreparedSecretMutation> = notUsed()

    override fun prepareDelete(
        reference: ProtectedSecretReference,
    ): ProtectedSecretResult<PreparedSecretMutation> = notUsed()

    private fun notUsed(): Nothing =
        throw UnsupportedOperationException("mutations are not exercised in secret-read tests")
}

private fun grantGeneration(tokenGenerator: () -> String): ProfileGrantGeneration {
    val record = ProfileRecord(
        profileId = ProfileId("profile-1"),
        typeIdentity = ProfileTypeIdentity(GitHubRepositoryIdentity("42"), "openai"),
        displayName = "Primary profile",
        schemaVersion = ProfileLimits.SCHEMA_VERSION,
        scalarPayload = mapOf("model" to ProfileScalarValue.StringValue("gpt-x")),
        secretReferences = mapOf("api_key" to SecretReferenceState.Present(PROTECTED_REFERENCE_ID)),
        revision = 1L,
        availability = ProfileAvailability.AVAILABLE,
    )
    return ProfileGrantGeneration.build(
        inputs = listOf(ProfileGrantInput(record, typeLocalId = "openai", packageRepositoryId = 42L)),
        tokenGenerator = tokenGenerator,
    )
}

private fun secretReadClaim(
    referenceToken: String?,
    kind: HostOperationKind = HostOperationKind.SECRET_READ,
): HostOperationClaim.Admitted = HostOperationClaim.Admitted(
    requestId = 7L,
    kind = kind,
    audioToken = null,
    text = null,
    language = null,
    voice = null,
    speed = 1.0,
    delaySeconds = 0.0,
    referenceToken = referenceToken,
)

private fun JSONObject.keySet(): Set<String> {
    val result = LinkedHashSet<String>()
    val iterator = keys()
    while (iterator.hasNext()) {
        result.add(iterator.next())
    }
    return result
}

/**
 * Contract tests for the SECRET_READ host adapter: opaque-token resolution,
 * the exact `{"plaintext": ...}` resume envelope, and nondisclosing E_*
 * normalization (contract: local://kotlin-bridge-contract.md sections 2, 3).
 */
class SecretReadHostAdapterTest {

    private fun adapterWithPlaintext(
        tokenGenerator: () -> String = { "grant-token" },
    ): Triple<SecretReadHostAdapter, FakeProtectedSecretStore, String> {
        val store = FakeProtectedSecretStore()
        val generation = grantGeneration(tokenGenerator)
        val binding = generation.snapshots.single().secretBindings.getValue("api_key")
        store.put(binding.reference, PLAINTEXT)
        return Triple(SecretReadHostAdapter(store, generation.registry), store, binding.referenceToken)
    }

    @Test
    fun admittedSecretReadReturnsExactPlaintextEnvelope() = runBlocking {
        val (adapter, store, token) = adapterWithPlaintext()

        val completion = adapter.complete(secretReadClaim(token))

        assertTrue(completion.success)
        val doc = JSONObject(completion.value)
        assertEquals(setOf("plaintext"), doc.keySet())
        assertEquals(PLAINTEXT, doc.getString("plaintext"))
        assertEquals(1, store.usedReferences.size)
    }

    @Test
    fun guessedTokenIsDeniedWithoutStoreAccess() = runBlocking {
        val (adapter, store, token) = adapterWithPlaintext()

        val completion = adapter.complete(secretReadClaim("$token-guess"))

        assertFalse(completion.success)
        assertEquals("E_DENIED", completion.value)
        assertFalse(completion.value.contains(PLAINTEXT))
        assertTrue(store.usedReferences.isEmpty())
    }

    @Test
    fun nullTokenIsDeniedWithoutStoreAccess() = runBlocking {
        val (adapter, store, _) = adapterWithPlaintext()

        val completion = adapter.complete(secretReadClaim(null))

        assertFalse(completion.success)
        assertEquals("E_DENIED", completion.value)
        assertTrue(store.usedReferences.isEmpty())
    }

    @Test
    fun nonSecretReadClaimKindIsDeniedWithoutStoreAccess() = runBlocking {
        val (adapter, store, token) = adapterWithPlaintext()

        val completion = adapter.complete(secretReadClaim(token, kind = HostOperationKind.HTTP_REQUEST))

        assertFalse(completion.success)
        assertEquals("E_DENIED", completion.value)
        assertFalse(completion.value.contains(PLAINTEXT))
        assertTrue(store.usedReferences.isEmpty())
    }

    @Test
    fun storeFailuresNormalizeToAllowlistedCodesWithoutPlaintext() = runBlocking {
        val mappings = listOf(
            ProtectedSecretError.NotFound to "E_NOT_FOUND",
            ProtectedSecretError.Denied to "E_DENIED",
            ProtectedSecretError.TooLarge to "E_TOO_LARGE",
            ProtectedSecretError.InvalidUtf8 to "E_INVALID_VALUE",
            ProtectedSecretError.InvalidValue to "E_INVALID_VALUE",
            ProtectedSecretError.StorageUnavailable to "E_STORAGE",
            ProtectedSecretError.CorruptValue to "E_STORAGE",
            ProtectedSecretError.TooManyConcurrentOperations to "E_BUSY",
        )
        for ((error, code) in mappings) {
            val (adapter, store, token) = adapterWithPlaintext()
            store.useFailure = error

            val completion = adapter.complete(secretReadClaim(token))

            assertFalse("expected failure for $error", completion.success)
            assertEquals("normalized code for $error", code, completion.value)
            assertFalse("plaintext leaked for $error", completion.value.contains(PLAINTEXT))
            assertEquals("store was consulted for $error", 1, store.usedReferences.size)
        }
    }

    @Test
    fun staleGenerationTokenIsDeniedBySuccessorAdapter() = runBlocking {
        val store = FakeProtectedSecretStore()
        val oldGeneration = grantGeneration { "old-token" }
        val newGeneration = grantGeneration { "new-token" }
        val oldToken = oldGeneration.snapshots.single().secretBindings.getValue("api_key").referenceToken
        val newBinding = newGeneration.snapshots.single().secretBindings.getValue("api_key")
        store.put(newBinding.reference, PLAINTEXT)
        val adapter = SecretReadHostAdapter(store, newGeneration.registry)

        val stale = adapter.complete(secretReadClaim(oldToken))
        assertFalse(stale.success)
        assertEquals("E_DENIED", stale.value)
        assertFalse(stale.value.contains(PLAINTEXT))
        assertTrue(store.usedReferences.isEmpty())

        val fresh = adapter.complete(secretReadClaim(newBinding.referenceToken))
        assertTrue(fresh.success)
        assertEquals(PLAINTEXT, JSONObject(fresh.value).getString("plaintext"))
    }
}
