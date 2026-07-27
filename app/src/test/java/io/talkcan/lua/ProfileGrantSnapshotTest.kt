package io.talkcan.lua

import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.profile.ProfileAvailability
import io.talkcan.profile.ProfileId
import io.talkcan.profile.ProfileLimits
import io.talkcan.profile.ProfileRecord
import io.talkcan.profile.ProfileScalarValue
import io.talkcan.profile.ProfileTypeIdentity
import io.talkcan.profile.SecretReferenceState
import io.talkcan.secret.ProtectedSecretReference
import io.talkcan.work.WorkValue
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PROTECTED_REFERENCE_ID = "keystore-ref-1"
private const val PACKAGE_REPOSITORY_ID = 42L

private fun countingTokens(prefix: String): () -> String {
    var count = 0
    return {
        count += 1
        "$prefix-$count"
    }
}

private fun JSONObject.keySet(): Set<String> {
    val result = LinkedHashSet<String>()
    val iterator = keys()
    while (iterator.hasNext()) {
        result.add(iterator.next())
    }
    return result
}

private fun defaultScalarPayload(): Map<String, ProfileScalarValue> = linkedMapOf(
    "model" to ProfileScalarValue.StringValue("gpt-x"),
    "stream" to ProfileScalarValue.BooleanValue(true),
    "timeout_seconds" to ProfileScalarValue.IntegerValue(7L),
)

private fun profileRecord(
    profileId: String = "profile-1",
    displayName: String = "Primary profile",
    revision: Long = 3L,
    availability: ProfileAvailability = ProfileAvailability.AVAILABLE,
    scalarPayload: Map<String, ProfileScalarValue> = defaultScalarPayload(),
    secretReferences: Map<String, SecretReferenceState> = mapOf(
        "api_key" to SecretReferenceState.Present(PROTECTED_REFERENCE_ID),
    ),
): ProfileRecord = ProfileRecord(
    profileId = ProfileId(profileId),
    typeIdentity = ProfileTypeIdentity(GitHubRepositoryIdentity("42"), "openai"),
    displayName = displayName,
    schemaVersion = ProfileLimits.SCHEMA_VERSION,
    scalarPayload = scalarPayload,
    secretReferences = secretReferences,
    revision = revision,
    availability = availability,
)

private fun grantInput(record: ProfileRecord): ProfileGrantInput = ProfileGrantInput(
    record = record,
    typeLocalId = "openai",
    packageRepositoryId = PACKAGE_REPOSITORY_ID,
)

/**
 * Contract tests for the detached profile grant snapshot, the native
 * `setProfileGrants` document, and the opaque secret reference token
 * registry (contract: local://kotlin-bridge-contract.md sections 4 and 5).
 */
class ProfileGrantSnapshotTest {

    @Test
    fun grantsJsonMatchesNativeSetProfileGrantsSchema() {
        val generation = ProfileGrantGeneration.build(
            inputs = listOf(grantInput(profileRecord())),
            tokenGenerator = countingTokens("token"),
        )

        val doc = JSONObject(generation.grantsJson())
        assertEquals(setOf("profiles"), doc.keySet())
        val profiles = doc.getJSONArray("profiles")
        assertEquals(1, profiles.length())

        val profile = profiles.getJSONObject(0)
        assertEquals(
            setOf("profileId", "typeLocalId", "displayName", "values", "secretReferences"),
            profile.keySet(),
        )
        assertEquals("profile-1", profile.getString("profileId"))
        assertEquals("openai", profile.getString("typeLocalId"))
        assertEquals("Primary profile", profile.getString("displayName"))

        val values = profile.getJSONObject("values")
        assertEquals(setOf("model", "stream", "timeout_seconds"), values.keySet())

        val model = values.getJSONObject("model")
        assertEquals(setOf("t", "v"), model.keySet())
        assertEquals("text", model.getString("t"))
        assertEquals("gpt-x", model.getString("v"))

        val stream = values.getJSONObject("stream")
        assertEquals(setOf("t", "v"), stream.keySet())
        assertEquals("bool", stream.getString("t"))
        assertTrue(stream.getBoolean("v"))

        val timeout = values.getJSONObject("timeout_seconds")
        assertEquals(setOf("t", "v"), timeout.keySet())
        assertEquals("int", timeout.getString("t"))
        assertEquals(7L, timeout.getLong("v"))

        val secretReferences = profile.getJSONObject("secretReferences")
        assertEquals(setOf("api_key"), secretReferences.keySet())
        assertEquals("token-1", secretReferences.getString("api_key"))
    }

    @Test
    fun referenceTokenIsOpaqueAndResolvesToProtectedBinding() {
        val generation = ProfileGrantGeneration.build(
            inputs = listOf(grantInput(profileRecord(revision = 5L))),
            tokenGenerator = countingTokens("token"),
        )
        val token = generation.snapshots.single().secretBindings.getValue("api_key").referenceToken

        assertNotEquals(PROTECTED_REFERENCE_ID, token)
        assertFalse(token.contains(PROTECTED_REFERENCE_ID))

        val binding = generation.registry.resolve(token)
        assertNotNull(binding)
        assertEquals(ProtectedSecretReference(PROTECTED_REFERENCE_ID), binding!!.reference)
        assertEquals("api_key", binding.fieldId)
        assertEquals("profile-1", binding.profileId)
        assertEquals(5L, binding.profileRevision)
        assertEquals(PACKAGE_REPOSITORY_ID, binding.packageRepositoryId)

        assertNull(generation.registry.resolve(PROTECTED_REFERENCE_ID))
        assertNull(generation.registry.resolve("$token-suffix"))
        assertNull(generation.registry.resolve(null))
    }

    @Test
    fun includeSecretsFalseDropsSecretReferencesButKeepsScalars() {
        val generation = ProfileGrantGeneration.build(
            inputs = listOf(grantInput(profileRecord())),
            includeSecrets = false,
            tokenGenerator = countingTokens("token"),
        )

        assertTrue(generation.snapshots.single().secretBindings.isEmpty())
        assertEquals(0, generation.registry.size)

        val profile = JSONObject(generation.grantsJson()).getJSONArray("profiles").getJSONObject(0)
        assertEquals(0, profile.getJSONObject("secretReferences").length())
        val values = profile.getJSONObject("values")
        assertEquals(setOf("model", "stream", "timeout_seconds"), values.keySet())
        assertEquals("gpt-x", values.getJSONObject("model").getString("v"))
    }

    @Test
    fun unavailableRecordsAreSkippedEntirely() {
        val available = profileRecord(profileId = "profile-1")
        val incompatible = profileRecord(
            profileId = "profile-2",
            availability = ProfileAvailability.UNAVAILABLE_SCHEMA_INCOMPATIBLE,
        )
        val removed = profileRecord(
            profileId = "profile-3",
            availability = ProfileAvailability.UNAVAILABLE_PACKAGE_REMOVED,
        )

        val generation = ProfileGrantGeneration.build(
            inputs = listOf(grantInput(available), grantInput(incompatible), grantInput(removed)),
            tokenGenerator = countingTokens("token"),
        )

        assertEquals(listOf("profile-1"), generation.snapshots.map { it.profileId })
        assertEquals(1, generation.registry.size)

        val profiles = JSONObject(generation.grantsJson()).getJSONArray("profiles")
        assertEquals(1, profiles.length())
        assertEquals("profile-1", profiles.getJSONObject(0).getString("profileId"))
    }

    @Test
    fun staleGenerationTokensDoNotResolveInSuccessorRegistry() {
        val inputs = listOf(grantInput(profileRecord()))
        val oldGeneration = ProfileGrantGeneration.build(inputs = inputs, tokenGenerator = countingTokens("old"))
        val newGeneration = ProfileGrantGeneration.build(inputs = inputs, tokenGenerator = countingTokens("new"))

        val oldToken = oldGeneration.snapshots.single().secretBindings.getValue("api_key").referenceToken
        val newToken = newGeneration.snapshots.single().secretBindings.getValue("api_key").referenceToken
        assertNotEquals(oldToken, newToken)

        assertNotNull(oldGeneration.registry.resolve(oldToken))
        assertNull(newGeneration.registry.resolve(oldToken))
        assertNotNull(newGeneration.registry.resolve(newToken))
    }

    @Test
    fun scalarAndSecretFieldOverlapIsRejected() {
        val binding = SecretReferenceBinding(
            referenceToken = "token-1",
            reference = ProtectedSecretReference(PROTECTED_REFERENCE_ID),
            packageRepositoryId = PACKAGE_REPOSITORY_ID,
            profileId = "profile-1",
            fieldId = "api_key",
            profileRevision = 1L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            ProfileGrantSnapshot(
                profileId = "profile-1",
                typeLocalId = "openai",
                displayName = "Primary profile",
                profileRevision = 1L,
                values = mapOf("api_key" to WorkValue.Text("leaked-plaintext")),
                secretBindings = mapOf("api_key" to binding),
            )
        }

        val disjoint = ProfileGrantSnapshot(
            profileId = "profile-1",
            typeLocalId = "openai",
            displayName = "Primary profile",
            profileRevision = 1L,
            values = mapOf("model" to WorkValue.Text("gpt-x")),
            secretBindings = mapOf("api_key" to binding),
        )
        assertEquals(setOf("model"), disjoint.values.keys)
        assertEquals(setOf("api_key"), disjoint.secretBindings.keys)
    }
}
