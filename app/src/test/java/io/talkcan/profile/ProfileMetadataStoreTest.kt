package io.talkcan.profile

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.3: Versioned atomic profile metadata store contract tests. */
class ProfileMetadataStoreTest {

    private fun newStore(): ProfileMetadataStore =
        ProfileMetadataStore(createTempDirectory(prefix = "profile-store-").toFile())

    private fun record(
        id: String,
        typeLocalId: String = "openai_compatible",
        name: String = "Profile $id",
        revision: Long = 1L,
        availability: ProfileAvailability = ProfileAvailability.AVAILABLE,
    ): ProfileRecord = ProfileRecord(
        profileId = ProfileId(id),
        typeIdentity = ProfileTypeIdentity(REPO_A, typeLocalId),
        displayName = name,
        schemaVersion = ProfileLimits.SCHEMA_VERSION,
        scalarPayload = mapOf(
            "base_url" to ProfileScalarValue.StringValue("https://api.openai.com/v1"),
            "streaming" to ProfileScalarValue.BooleanValue(true),
            "timeout" to ProfileScalarValue.IntegerValue(30L),
        ),
        secretReferences = mapOf("api_key" to SecretReferenceState.Present("ref-$id")),
        revision = revision,
        availability = availability,
    )

    @Test
    fun roundTripPreservesRecordsExactly() {
        val store = newStore()
        val records = listOf(record("p1"), record("p2", revision = 7L))
        val save = store.saveRepository(REPO_A, records)
        assertTrue("Save must commit: $save", save is ProfileStoreSaveResult.Committed)

        val loaded = store.loadRepository(REPO_A) as? ProfileStoreLoadResult.Loaded
            ?: throw AssertionError("Load must succeed")
        assertEquals(records.sortedBy { it.profileId.value }, loaded.records.sortedBy { it.profileId.value })
    }

    @Test
    fun missingFileLoadsEmpty() {
        val store = newStore()
        val loaded = store.loadRepository(REPO_A) as? ProfileStoreLoadResult.Loaded
            ?: throw AssertionError("Missing file must load empty")
        assertTrue(loaded.records.isEmpty())
    }

    @Test
    fun overwriteIsAtomicAndComplete() {
        val store = newStore()
        store.saveRepository(REPO_A, listOf(record("p1"), record("p2")))
        store.saveRepository(REPO_A, listOf(record("p1", revision = 2L)))

        val loaded = store.loadRepository(REPO_A) as ProfileStoreLoadResult.Loaded
        assertEquals(1, loaded.records.size)
        assertEquals(2L, loaded.records[0].revision)
    }

    @Test
    fun corruptDocumentIsIsolatedByRepository() {
        val root = createTempDirectory(prefix = "profile-store-").toFile()
        val store = ProfileMetadataStore(root)
        store.saveRepository(REPO_A, listOf(record("p1")))
        store.saveRepository(REPO_B, listOf(record("p2").copy(typeIdentity = ProfileTypeIdentity(REPO_B, "openai_compatible"))))

        // Corrupt repository A's document on disk.
        File(root, "${REPO_A.value}.json").writeText("{ this is not valid json ")

        val corrupted = store.loadRepository(REPO_A)
        assertTrue("Repo A must be corrupt: $corrupted", corrupted is ProfileStoreLoadResult.Corrupt)

        // Repository B is unaffected.
        val loadedB = store.loadRepository(REPO_B) as? ProfileStoreLoadResult.Loaded
            ?: throw AssertionError("Repo B must load despite repo A corruption")
        assertEquals(1, loadedB.records.size)
    }

    @Test
    fun duplicateJsonKeyRejected() {
        val json = """
            {"version": 1, "version": 1, "repositoryId": "${REPO_A.value}", "profiles": []}
        """.trimIndent()
        val result = ProfileMetadataCodec.decode(json, REPO_A)
        assertTrue("Duplicate key must be rejected: $result", result is ProfileDecodeResult.Failure)
    }

    @Test
    fun unsupportedVersionRejected() {
        val json = """{"version": 99, "repositoryId": "${REPO_A.value}", "profiles": []}"""
        val result = ProfileMetadataCodec.decode(json, REPO_A)
        assertTrue(result is ProfileDecodeResult.Failure)
        assertTrue((result as ProfileDecodeResult.Failure).error is ProfileDecodeError.UnsupportedVersion)
    }

    @Test
    fun repositoryIdMismatchRejected() {
        val json = """{"version": 1, "repositoryId": "555555", "profiles": []}"""
        val result = ProfileMetadataCodec.decode(json, REPO_A)
        assertTrue(result is ProfileDecodeResult.Failure)
    }

    @Test
    fun trailingDataRejected() {
        val json = """{"version": 1, "repositoryId": "${REPO_A.value}", "profiles": []} extra"""
        val result = ProfileMetadataCodec.decode(json, REPO_A)
        assertTrue(result is ProfileDecodeResult.Failure)
    }

    @Test
    fun deleteRepositoryRemovesDocument() {
        val store = newStore()
        store.saveRepository(REPO_A, listOf(record("p1")))
        val delete = store.deleteRepository(REPO_A)
        assertTrue(delete is ProfileStoreSaveResult.Committed)
        val loaded = store.loadRepository(REPO_A) as ProfileStoreLoadResult.Loaded
        assertTrue(loaded.records.isEmpty())
    }

    @Test
    fun discoverRepositoryIdsFindsPersistedRepositories() {
        val store = newStore()
        store.saveRepository(REPO_A, listOf(record("p1")))
        store.saveRepository(REPO_B, listOf(record("p2").copy(typeIdentity = ProfileTypeIdentity(REPO_B, "openai_compatible"))))
        val ids = store.discoverRepositoryIds()
        assertEquals(listOf(REPO_A, REPO_B), ids)
    }
}
