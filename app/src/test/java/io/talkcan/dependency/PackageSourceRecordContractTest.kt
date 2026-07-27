package io.talkcan.dependency

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageSourceRecordContractTest {
    @Test
    fun `ownerId rejects every noncanonical publisher identity selector`() {
        val cases = listOf(
            OwnerIdCase("empty", ""),
            OwnerIdCase("zero", "0"),
            OwnerIdCase("leading-zero", "01"),
            OwnerIdCase("negative", "-1"),
            OwnerIdCase("signed", "+1"),
            OwnerIdCase("decimal", "1.0"),
            OwnerIdCase("whitespace", " 1 "),
            OwnerIdCase("nonnumeric", "publisher"),
        )

        cases.forEach { case ->
            assertThrows(
                "ownerId selector '${case.selector}' must reject '${case.value}'",
                IllegalArgumentException::class.java,
            ) {
                sourceRecord(case.value)
            }
        }
    }

    @Test
    fun `persisted index preserves a large canonical ownerId without numeric coercion`() {
        val ownerId = "922337203685477580812345678901234567890"
        val source = sourceRecord(ownerId)
        val index = StoredInstalledIndex(
            version = 1,
            providers = mapOf(
                source.repositoryId to StoredProviderRecord(
                    active = StoredPackageRevision(
                        digest = ArtifactDigest("a".repeat(64)),
                        manifest = PackageManifest(
                            manifestVersion = 1,
                            repositoryId = source.repositoryId,
                            packageVersion = "1.0.0",
                            entryModule = "plugin",
                            presentation = PackagePresentation("Package", "Package summary"),
                            runtime = RuntimeRequirements("lua-test", "api-test"),
                            configuration = PackageConfigurationDeclaration(
                                ConfigurationDataDeclaration(emptyList()),
                                ConfigurationUiDeclaration(emptyList())
                            ),
                            resources = PackageResourcesDeclaration(emptyList()),
                            capabilities = emptySet(),
                        ),
                        sourceRecord = source,
                    ),
                    rollback = null,
                ),
            ),
        )

        withTemporaryDirectory { root ->
            val store = InstalledPackageStore(root)
            assertSuccess(store.commitIndex(index))

            val restored = assertSuccess(store.loadIndex()).index
            assertEquals(ownerId, restored.providers.getValue(source.repositoryId).active.sourceRecord.ownerId)
        }
    }

    @Test
    fun `persisted index rejects a zero ownerId in its source record`() {
        withTemporaryDirectory { root ->
            File(root, "index.json").writeText(
                """{
                  "version": 1,
                  "providers": {
                    "123": {
                      "active": {
                        "digest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "manifest": {
                          "manifestVersion": 1,
                          "repositoryId": "123",
                          "packageVersion": "1.0.0",
                          "entryModule": "plugin",
                          "presentation": {"label": "Package", "summary": "Package summary"},
                          "runtime": {"luaVersion": "lua-test", "apiVersion": "api-test"}
                        },
                        "sourceRecord": {
                          "repositoryId": "123",
                          "coordinates": {"owner": "owner", "repository": "repository"},
                          "release": {"releaseId": "456", "tag": "v1", "isPrerelease": false},
                          "asset": {"assetId": "789", "name": "package.zip"},
                          "ownerId": "0"
                        }
                      },
                      "rollback": null
                    }
                  }
                }""".trimIndent(),
            )

            val failure = InstalledPackageStore(root).loadIndex() as? PackageOutcome.Failure
                ?: throw AssertionError("persisted ownerId selector 'zero' must reject the index")
            val recovery = failure.error as? PackageFailure.Recovery
                ?: throw AssertionError("expected corrupt-index recovery failure, got ${failure.error}")
            assertEquals(PackageFailure.RecoveryDetail.INDEX_CORRUPT, recovery.detail)
        }
    }

    private fun sourceRecord(ownerId: String): PackageSourceRecord = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity("123"),
        coordinates = GitHubRepositoryCoordinates("owner", "repository"),
        release = GitHubReleaseIdentity("456", "v1", false),
        asset = GitHubAssetIdentity("789", "package.zip"),
        ownerId = ownerId,
    )

    private fun <T> assertSuccess(outcome: PackageOutcome<T>): T = when (outcome) {
        is PackageOutcome.Success -> outcome.value
        is PackageOutcome.Failure -> throw AssertionError("expected success, got ${outcome.error}")
    }

    private inline fun <T> withTemporaryDirectory(block: (File) -> T): T {
        val root = createTempDirectory("package-source-record-").toFile()
        return try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private data class OwnerIdCase(val selector: String, val value: String)
}
