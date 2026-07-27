package io.talkcan.dependency

import io.talkcan.lua.API_VERSION
import io.talkcan.lua.LUA_VERSION
import io.talkcan.lua.LuaCallbackHandle
import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaSpawnAdmission
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaValue
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelConfigurationField
import org.json.JSONObject
import io.talkcan.model.InstalledProviderBinding
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.CRC32
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transaction-contract coverage for the installed package repository. Fixtures intentionally write
 * Unix local and central ZIP metadata so every mutation reaches storage rather than archive
 * validation. Publication is a recording host boundary: every recorded snapshot was produced only
 * after the repository had selected and materialized its committed on-disk index.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstalledPackageTransactionsTest {
    @Test
    fun `install reinstall update rollback no rollback and removal keep index content and publication in lockstep`() = runTest {
        withTemporaryDirectory { root ->
            val publications = RecordingPublisher(root)
            val repository = repository(root, publications)
            val source = sourceRecord("123", "456", "789")
            val provider = InstalledProviderId.derive(source.repositoryId)
            val v1 = packageArchive("123", "1.0.0", "first")
            val v2 = packageArchive("123", "2.0.0", "second")

            assertEquals(MutationResult.Installed(provider), success(repository.installOrUpdate(ByteArrayInputStream(v1), source)))
            val afterInstall = index(root).providers.getValue(source.repositoryId)
            assertEquals(digest(v1), afterInstall.active.digest.value)
            assertNull(afterInstall.rollback)
            assertContent(root, afterInstall.active.digest.value, v1)
            publications.assertLast(setOf(provider), emptySet())

            val publicationsAfterInstall = publications.snapshots.size
            assertEquals(MutationResult.Reinstalled(provider), success(repository.installOrUpdate(ByteArrayInputStream(v1), source)))
            val afterReinstall = index(root).providers.getValue(source.repositoryId)
            assertEquals(afterInstall, afterReinstall)
            assertEquals("an exact reinstall must not publish a replacement snapshot", publicationsAfterInstall, publications.snapshots.size)

            assertEquals(MutationResult.Updated(provider), success(repository.installOrUpdate(ByteArrayInputStream(v2), source)))
            val afterUpdate = index(root).providers.getValue(source.repositoryId)
            assertEquals(digest(v2), afterUpdate.active.digest.value)
            assertEquals(digest(v1), afterUpdate.rollback?.digest?.value)
            assertContent(root, afterUpdate.active.digest.value, v2)
            assertContent(root, requireNotNull(afterUpdate.rollback).digest.value, v1)
            publications.assertLast(setOf(provider), emptySet())

            assertEquals(MutationResult.RolledBack(provider), success(repository.rollback(source.repositoryId)))
            val afterRollback = index(root).providers.getValue(source.repositoryId)
            assertEquals(digest(v1), afterRollback.active.digest.value)
            assertEquals(digest(v2), afterRollback.rollback?.digest?.value)
            publications.assertLast(setOf(provider), emptySet())

            val noRollbackRoot = File(root, "no-rollback")
            val noRollbackPublications = RecordingPublisher(noRollbackRoot)
            val noRollbackRepository = repository(noRollbackRoot, noRollbackPublications)
            success(noRollbackRepository.installOrUpdate(ByteArrayInputStream(v1), source))
            val noRollbackIndex = index(noRollbackRoot)
            val noRollbackPublicationCount = noRollbackPublications.snapshots.size
            assertFailure(noRollbackRepository.rollback(source.repositoryId), PackageFailure.RollbackDetail.NO_ROLLBACK_REVISION)
            assertEquals(noRollbackIndex, index(noRollbackRoot))
            assertEquals(noRollbackPublicationCount, noRollbackPublications.snapshots.size)

            assertEquals(MutationResult.Removed(provider), success(repository.remove(source.repositoryId)))
            assertFalse(index(root).providers.containsKey(source.repositoryId))
            publications.assertLast(emptySet(), emptySet())
        }
    }

    @Test
    fun `staging failure preserves authoritative revision while committed publication failure recovers after restart`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("123", "456", "789")
            val provider = InstalledProviderId.derive(source.repositoryId)
            val v1 = packageArchive("123", "1.0.0", "first")
            val v2 = packageArchive("123", "2.0.0", "second")
            val publications = RecordingPublisher(root)
            val repository = repository(root, publications)
            success(repository.installOrUpdate(ByteArrayInputStream(v1), source))
            val beforeFailure = index(root)
            val publishedBeforeFailure = publications.snapshots.size

            assertFailure(repository.installOrUpdate(FailingInputStream(), source), PackageFailure.StorageDetail.WRITE_FAILED)
            assertEquals(beforeFailure, index(root))
            assertEquals(publishedBeforeFailure, publications.snapshots.size)
            assertTrue(File(root, "staging").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
            val fileInsteadOfStore = File(root, "file-instead-of-store").apply { writeText("not a directory", UTF_8) }
            val blockedPublications = RecordingPublisher(fileInsteadOfStore)
            assertFailure(
                repository(fileInsteadOfStore, blockedPublications).installOrUpdate(ByteArrayInputStream(v1), source),
                PackageFailure.StorageDetail.WRITE_FAILED,
            )
            assertTrue("a failed staging-directory write must never publish", blockedPublications.snapshots.isEmpty())

            val rejectingPublisher = RecordingPublisher(root, reject = true)
            val committingRepository = repository(root, rejectingPublisher)
            val outcome = success(committingRepository.installOrUpdate(ByteArrayInputStream(v2), source))
            val publicationFailure = outcome as? MutationResult.PublicationFailure
                ?: throw AssertionError("expected a post-commit publication failure, got $outcome")
            assertTrue(publicationFailure.error is PackageFailure.Loading)
            val committed = index(root).providers.getValue(source.repositoryId)
            assertEquals(digest(v2), committed.active.digest.value)
            assertEquals(digest(v1), committed.rollback?.digest?.value)
            assertTrue("publication is rejected after, not before, the durable index commit", rejectingPublisher.snapshots.isEmpty())

            val restartedPublisher = RecordingPublisher(root)
            val restarted = repository(root, restartedPublisher)
            assertEquals(Unit, success(restarted.loadAndPublish()))
            restartedPublisher.assertLast(setOf(provider), emptySet())
            assertEquals(digest(v2), index(root).providers.getValue(source.repositoryId).active.digest.value)
        }
    }

    @Test
    fun `recovery isolates corrupt active provider selects complete backup and leaves absent store empty without migration`() = runTest {
        withTemporaryDirectory { root ->
            val sourceA = sourceRecord("123", "456", "789")
            val sourceB = sourceRecord("124", "457", "790")
            val providerA = InstalledProviderId.derive(sourceA.repositoryId)
            val providerB = InstalledProviderId.derive(sourceB.repositoryId)
            val v1 = packageArchive("123", "1.0.0", "first")
            val v2 = packageArchive("123", "2.0.0", "second")
            val sibling = packageArchive("124", "1.0.0", "sibling")
            val publisher = RecordingPublisher(root)
            val repository = repository(root, publisher)
            success(repository.installOrUpdate(ByteArrayInputStream(v1), sourceA))
            success(repository.installOrUpdate(ByteArrayInputStream(v2), sourceA))
            success(repository.installOrUpdate(ByteArrayInputStream(sibling), sourceB))
            val activeA = index(root).providers.getValue(sourceA.repositoryId).active.digest.value
            assertTrue(File(root, "content/sha256/$activeA").delete())

            val restartedPublisher = RecordingPublisher(root)
            assertEquals(Unit, success(repository(root, restartedPublisher).loadAndPublish()))
            restartedPublisher.assertLast(setOf(providerB), setOf(providerA))
            assertEquals(digest(v2), index(root).providers.getValue(sourceA.repositoryId).active.digest.value)
            assertEquals(digest(v1), index(root).providers.getValue(sourceA.repositoryId).rollback?.digest?.value)

            val backupRoot = File(root, "backup")
            val backupPublisher = RecordingPublisher(backupRoot)
            val backupRepository = repository(backupRoot, backupPublisher)
            success(backupRepository.installOrUpdate(ByteArrayInputStream(v1), sourceA))
            success(backupRepository.installOrUpdate(ByteArrayInputStream(v2), sourceA))
            File(backupRoot, "index.json").writeText("not a complete index", UTF_8)
            assertEquals(SelectedGeneration.BACKUP, success(InstalledPackageStore(backupRoot).loadIndex()).generation)
            val backupRecoveryPublisher = RecordingPublisher(backupRoot)
            assertEquals(Unit, success(repository(backupRoot, backupRecoveryPublisher).loadAndPublish()))
            backupRecoveryPublisher.assertLast(setOf(providerA), emptySet())
            assertEquals(digest(v1), backupRecoveryPublisher.snapshots.last().bindings.getValue(providerA).provider.fingerprint.value)

            val absentRoot = File(root, "absent").apply { mkdirs() }
            val catalogue = File(absentRoot, "channel-catalogue.json").apply { writeText("{\"opaque\":true}", UTF_8) }
            val bytesBeforeLoad = catalogue.readBytes()
            val absent = InstalledPackageStore(absentRoot)
            assertEquals(SelectedGeneration.EMPTY, success(absent.loadIndex()).generation)
            assertTrue(bytesBeforeLoad.contentEquals(catalogue.readBytes()))
            assertFalse(File(absentRoot, "index.json").exists())
            assertFalse(File(absentRoot, "index.backup.json").exists())
        }
    }

    @Test
    fun `cleanup removes incomplete staging and unreferenced content within its bounded work budget`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("123", "456", "789")
            val archive = packageArchive("123", "1.0.0", "first")
            val repository = repository(root, RecordingPublisher(root))
            success(repository.installOrUpdate(ByteArrayInputStream(archive), source))
            val activeDigest = index(root).providers.getValue(source.repositoryId).active.digest.value
            val staging = File(root, "staging")
            val content = File(root, "content/sha256")
            File(staging, "incomplete.tmp").writeText("partial", UTF_8)
            val orphan = File(content, "f".repeat(64)).apply { writeText("orphan", UTF_8) }
            repeat(1_001) { ordinal -> File(staging, "leftover-$ordinal.tmp").writeText("x", UTF_8) }

            val cleanup = success(InstalledPackageStore(root).cleanup(index(root)))
            assertTrue("cleanup must cap work rather than scan unbounded stale storage", cleanup.inspectedCount < 1_003)
            assertTrue("the cleanup cap must leave work for a subsequent bounded pass", staging.listFiles().orEmpty().isNotEmpty())
            assertContent(root, activeDigest, archive)
            assertTrue("the first bounded pass may spend its budget in staging before content", orphan.exists())

            var passes = 0
            while (orphan.exists()) {
                success(InstalledPackageStore(root).cleanup(index(root)))
                passes++
                assertTrue("each cleanup pass has a finite bound", passes <= 3)
            }
            assertFalse(File(staging, "incomplete.tmp").exists())
            assertContent(root, activeDigest, archive)
        }
    }

    @Test
    fun `every durable content and index boundary preserves prior authoritative selection without publication`() = runTest {
        val cases = listOf(
            DurableFailureCase(DurableBoundary.STAGED_CONTENT_FSYNC, PackageFailure.StorageDetail.WRITE_FAILED, false),
            DurableFailureCase(DurableBoundary.CONTENT_ATOMIC_MOVE, PackageFailure.StorageDetail.COMMIT_FAILED, false),
            DurableFailureCase(DurableBoundary.CONTENT_DIR_FSYNC, PackageFailure.StorageDetail.WRITE_FAILED, true),
            DurableFailureCase(DurableBoundary.STAGING_DIR_FSYNC_AFTER_CONTENT, PackageFailure.StorageDetail.WRITE_FAILED, true),
            DurableFailureCase(DurableBoundary.INDEX_TEMP_WRITE, PackageFailure.StorageDetail.WRITE_FAILED, true),
            DurableFailureCase(DurableBoundary.INDEX_TEMP_FSYNC, PackageFailure.StorageDetail.WRITE_FAILED, true),
            DurableFailureCase(DurableBoundary.CURRENT_TO_BACKUP_MOVE, PackageFailure.StorageDetail.COMMIT_FAILED, true),
            DurableFailureCase(DurableBoundary.TEMP_TO_CURRENT_MOVE, PackageFailure.StorageDetail.COMMIT_FAILED, true),
            DurableFailureCase(DurableBoundary.STORE_DIR_FSYNC, PackageFailure.StorageDetail.COMMIT_FAILED, true),
            DurableFailureCase(DurableBoundary.STAGING_DIR_FSYNC_AFTER_INDEX, PackageFailure.StorageDetail.COMMIT_FAILED, true),
        )
        cases.forEach { case ->
            withTemporaryDirectory { root ->
                val source = sourceRecord("123", "456", "789")
                val provider = InstalledProviderId.derive(source.repositoryId)
                val v1 = packageArchive("123", "1.0.0", "first")
                val v2 = packageArchive("123", "2.0.0", "second")
                success(repository(root, RecordingPublisher(root)).installOrUpdate(ByteArrayInputStream(v1), source))
                val before = index(root)
                val publications = RecordingPublisher(root)
                val faultedStore = InstalledPackageStore(root, FaultInjector { boundary ->
                    if (boundary == case.boundary) throw IOException("injected ${case.boundary}")
                })

                assertFailure(
                    repository(faultedStore, publications).installOrUpdate(ByteArrayInputStream(v2), source),
                    case.expectedFailure,
                )
                assertEquals("${case.boundary} must retain one complete predecessor index", before, index(root))
                assertTrue("${case.boundary} must not publish a failed candidate", publications.snapshots.isEmpty())
                assertEquals(case.contentWasCommitted, File(root, "content/sha256/${digest(v2)}").exists())

                val restartPublisher = RecordingPublisher(root)
                assertEquals(Unit, success(repository(root, restartPublisher).loadAndPublish()))
                restartPublisher.assertLast(setOf(provider), emptySet())
                assertEquals(digest(v1), restartPublisher.snapshots.last().bindings.getValue(provider).provider.fingerprint.value)
            }
        }
    }

    @Test
    fun `simultaneous mutations serialize by admission order and retain both exact revisions`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("123", "456", "789")
            val provider = InstalledProviderId.derive(source.repositoryId)
            val firstGate = CompletableDeferred<Unit>()
            val firstStarted = CompletableDeferred<Unit>()
            val first = packageArchive("123", "1.0.0", "first")
            val second = packageArchive("123", "2.0.0", "second")
            val publications = RecordingPublisher(root)
            val repository = InstalledPackageRepository(
                store = InstalledPackageStore(root),
                bridge = NoopLuaKernelBridge,
                publisher = publications::publish,
                dispatcher = Dispatchers.Default,
            )

            val firstMutation = async { repository.installOrUpdate(GatedInputStream(first, firstStarted, firstGate), source) }
            firstStarted.await()
            val secondMutation = async { repository.installOrUpdate(ByteArrayInputStream(second), source) }
            firstGate.complete(Unit)

            assertEquals(MutationResult.Installed(provider), success(firstMutation.await()))
            assertEquals(MutationResult.Updated(provider), success(secondMutation.await()))
            val record = index(root).providers.getValue(source.repositoryId)
            assertEquals(digest(second), record.active.digest.value)
            assertEquals(digest(first), record.rollback?.digest?.value)
            assertEquals(2, publications.snapshots.size)
            publications.assertLast(setOf(provider), emptySet())
        }
    }

    @Test
    fun `close aborts incomplete staging cleans it and suppresses every late publication`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("123", "456", "789")
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val publications = RecordingPublisher(root)
            val repository = InstalledPackageRepository(
                store = InstalledPackageStore(root),
                bridge = NoopLuaKernelBridge,
                publisher = publications::publish,
                dispatcher = Dispatchers.Default,
            )
            val mutation = async { repository.installOrUpdate(GatedInputStream(packageArchive("123", "1.0.0", "first"), started, gate), source) }
            started.await()

            repository.requestClose()
            val close = async(Dispatchers.Default) { repository.closeAndAwait(1_000) }
            gate.complete(Unit)
            assertFailure(mutation.await(), PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
            success(close.await())
            assertTrue(index(root).providers.isEmpty())
            assertTrue(File(root, "staging").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
            assertTrue("shutdown must suppress every publication from the interrupted operation", publications.snapshots.isEmpty())
            assertFailure(repository.installOrUpdate(ByteArrayInputStream(packageArchive("123", "1.0.0", "late")), source), PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
            assertTrue(publications.snapshots.isEmpty())
        }
    }

    @Test
    fun `load index and materialize parses and rehashes stored bytes rather than trusting cached model`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("123", "456", "789")
            val provider = InstalledProviderId.derive(source.repositoryId)
            val v1 = packageArchive("123", "1.0.0", "first")
            val repository = repository(root, RecordingPublisher(root))
            success(repository.installOrUpdate(ByteArrayInputStream(v1), source))

            // Mutate index on disk to change manifest capabilities or configuration (say, add a mock capability "audio.playback")
            val oldIndex = index(root)
            val record = oldIndex.providers.getValue(source.repositoryId)
            
            val mutatedManifest = record.active.manifest.copy(
                capabilities = record.active.manifest.capabilities + "audio.playback"
            )
            val mutatedActive = record.active.copy(manifest = mutatedManifest)
            val mutatedRecord = record.copy(active = mutatedActive)
            val mutatedIndex = StoredInstalledIndex(
                version = oldIndex.version,
                providers = oldIndex.providers + (source.repositoryId to mutatedRecord)
            )

            // Commit this mutated index back to index.json manually
            File(root, "index.json").writeText(StrictIndexCodec.encodeIndex(mutatedIndex), UTF_8)

            // Verify that loadAndMaterialize detects this mismatch and throws a mutation exception
            val store = InstalledPackageStore(root)
            val matResult = success(store.loadAndMaterialize(NoopLuaKernelBridge))
            val failure = matResult.failures[provider]
            assertTrue("Expected a mutation failure but got $failure", failure is PackageFailure.Mutation)
            assertEquals(PackageFailure.MutationDetail.SERIALIZATION_VIOLATION, (failure as PackageFailure.Mutation).detail)

            val restartedPublisher = RecordingPublisher(root)
            val restarted = repository(root, restartedPublisher)
            assertEquals(Unit, success(restarted.loadAndPublish()))
            restartedPublisher.assertLast(emptySet(), setOf(provider))
        }
    }

    @Test
    fun `corrupt stored-byte isolation handles modified zip file and preserves sibling`() = runTest {
        withTemporaryDirectory { root ->
            val sourceA = sourceRecord("123", "456", "789")
            val sourceB = sourceRecord("124", "457", "790")
            val providerA = InstalledProviderId.derive(sourceA.repositoryId)
            val providerB = InstalledProviderId.derive(sourceB.repositoryId)
            val vA = packageArchive("123", "1.0.0", "first")
            val vB = packageArchive("124", "1.0.0", "second")

            val publisher = RecordingPublisher(root)
            val repository = repository(root, publisher)
            success(repository.installOrUpdate(ByteArrayInputStream(vA), sourceA))
            success(repository.installOrUpdate(ByteArrayInputStream(vB), sourceB))

            val activeA = index(root).providers.getValue(sourceA.repositoryId).active.digest.value
            // Overwrite provider A's archive bytes with corruption
            val fileA = File(root, "content/sha256/$activeA")
            fileA.setWritable(true)
            fileA.writeText("corrupt zip file contents", UTF_8)
            // Verify loadAndMaterialize detects corruption and isolates it
            val store = InstalledPackageStore(root)
            val matResult = success(store.loadAndMaterialize(NoopLuaKernelBridge))
            
            val failureA = matResult.failures[providerA]
            assertTrue("Expected integrity/reparse failure on corrupt zip but got $failureA", failureA is PackageFailure.Integrity || failureA is PackageFailure.Format)
            
            val bindingB = matResult.bindings[providerB]
            assertTrue("Sibling package B must be loaded successfully", bindingB != null)

            // Verify loadAndPublish succeeds with isolation
            val restartedPublisher = RecordingPublisher(root)
            val restarted = repository(root, restartedPublisher)
            assertEquals(Unit, success(restarted.loadAndPublish()))
            restartedPublisher.assertLast(setOf(providerB), setOf(providerA))
        }
    }

    @Test
    fun `load index and materialize does not invoke Lua or register corrupt providers`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("123", "456", "789")
            val provider = InstalledProviderId.derive(source.repositoryId)
            val v1 = packageArchive("123", "1.0.0", "first")
            val repository = repository(root, RecordingPublisher(root))
            success(repository.installOrUpdate(ByteArrayInputStream(v1), source))

            // Corrupt the ZIP archive
            val active = index(root).providers.getValue(source.repositoryId).active.digest.value
            val file = File(root, "content/sha256/$active")
            file.setWritable(true)
            file.writeText("invalid zip data", UTF_8)
            // Use NoopLuaKernelBridge to ensure no Lua creation attempts are made.
            // If it attempted to compile/materialize/register provider config in Lua,
            // NoopLuaKernelBridge.create would throw.
            val store = InstalledPackageStore(root)
            val matResult = success(store.loadAndMaterialize(NoopLuaKernelBridge))

            assertTrue("Corrupt provider must not produce binding registration", matResult.bindings.isEmpty())
            assertTrue("Corrupt provider must be in failures", matResult.failures.containsKey(provider))
        }
    }

    @Test
    fun `commit failure during rollback and removal preserves prior authoritative selection`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("123", "456", "789")
            val provider = InstalledProviderId.derive(source.repositoryId)
            val v1 = packageArchive("123", "1.0.0", "first")
            val v2 = packageArchive("123", "2.0.0", "second")
            val repository = repository(root, RecordingPublisher(root))
            success(repository.installOrUpdate(ByteArrayInputStream(v1), source))
            success(repository.installOrUpdate(ByteArrayInputStream(v2), source))

            val beforeRollback = index(root)
            assertEquals(digest(v2), beforeRollback.providers.getValue(source.repositoryId).active.digest.value)
            assertEquals(digest(v1), beforeRollback.providers.getValue(source.repositoryId).rollback?.digest?.value)

            // Inject commit failure during rollback (e.g. at CURRENT_TO_BACKUP_MOVE or TEMP_TO_CURRENT_MOVE)
            val faultedStore = InstalledPackageStore(root, FaultInjector { boundary ->
                if (boundary == DurableBoundary.TEMP_TO_CURRENT_MOVE) throw IOException("injected rollback commit failure")
            })
            val faultedRepo = repository(faultedStore, RecordingPublisher(root))
            
            assertFailure(faultedRepo.rollback(source.repositoryId), PackageFailure.StorageDetail.COMMIT_FAILED)
            assertEquals("Authoritative selection must remain unchanged on rollback commit failure", beforeRollback, index(root))

            // Inject write failure during removal
            val faultedStoreRemove = InstalledPackageStore(root, FaultInjector { boundary ->
                if (boundary == DurableBoundary.INDEX_TEMP_WRITE) throw IOException("injected removal write failure")
            })
            val faultedRepoRemove = repository(faultedStoreRemove, RecordingPublisher(root))
            
            assertFailure(faultedRepoRemove.remove(source.repositoryId), PackageFailure.StorageDetail.WRITE_FAILED)
            assertEquals("Authoritative selection must remain unchanged on removal commit failure", beforeRollback, index(root))
        }
    }

    @Test
    fun `evolved manifest round-trip preserves exact declarations capabilities and fingerprint after store load and materialize`() = runTest {
        withTemporaryDirectory { root ->
            // Package with non-empty configuration and capabilities (task 10.2)
            val source = sourceRecord("125", "751", "752")
            val provider = InstalledProviderId.derive(source.repositoryId)
            val manifest = """{"manifestVersion":1,"repositoryId":"125","packageVersion":"2.0.0","entryModule":"plugin","presentation":{"label":"Evolved Package","summary":"Non-empty declarations fixture"},"runtime":{"luaVersion":"$LUA_VERSION","apiVersion":"$API_VERSION"},"configuration":{"schemaVersion":1,"data":{"fields":[{"id":"mode","type":"string","default":"ECHO","allowedValues":["ECHO","DELAYED_ECHO","STT","TTS","STT_TTS"]},{"id":"verbose","type":"boolean","default":false},{"id":"retry_count","type":"integer","default":3,"minimum":0,"maximum":10}],"additionalProperties":false},"ui":{"fields":[{"field":"mode","control":"choice","label":"Mode","choices":[{"value":"ECHO","label":"ECHO"},{"value":"DELAYED_ECHO","label":"Delayed Echo"},{"value":"STT","label":"STT"},{"value":"TTS","label":"TTS"},{"value":"STT_TTS","label":"STT+TTS"}]},{"field":"verbose","control":"toggle","label":"Verbose"},{"field":"retry_count","control":"number","label":"Retry Count"}]}},"resources":{"mounts":[]},"profileTypes":[],"choiceResolvers":[],"workQueues":[],"capabilities":["audio.transcription","audio.synthesis","audio.playback"]}"""
            val luaSource = "-- evolved fixture\nreturn { startup = function() end, handle_readiness = function() return { ready = true } end }"
            val archive = strictUnixStoredZip(
                listOf(
                    ZipFixtureEntry("manifest.json", manifest.toByteArray(UTF_8), 0b1000000110100100),
                    ZipFixtureEntry("lua/", ByteArray(0), 0b0100000111101101),
                    ZipFixtureEntry("lua/plugin.lua", luaSource.toByteArray(UTF_8), 0b1000000110100100),
                ),
            )
            val expectedDigest = digest(archive)
            val publisher = RecordingPublisher(root)
            val repository = repository(root, publisher)

            // Install through store
            success(repository.installOrUpdate(ByteArrayInputStream(archive), source))
            val storedIndex = index(root)
            val storedRecord = storedIndex.providers.getValue(source.repositoryId)

            // Verify stored manifest preserves exact declarations
            assertEquals(expectedDigest, storedRecord.active.digest.value)
            assertEquals("125", storedRecord.active.manifest.repositoryId.value)
            assertEquals(3, storedRecord.active.manifest.configuration.data.fields.size)
            assertEquals(3, storedRecord.active.manifest.configuration.ui.fields.size)
            assertEquals(3, storedRecord.active.manifest.capabilities.size)

            // Restart and loadAndMaterialize from stored bytes
            val restartedPublisher = RecordingPublisher(root)
            val restarted = repository(root, restartedPublisher)
            success(restarted.loadAndPublish())

            // Verify publication succeeded with the evolved provider
            restartedPublisher.assertLast(setOf(provider), emptySet())
            val binding = restartedPublisher.snapshots.last().bindings.getValue(provider)

            // Verify fingerprint equals the exact digest of committed bytes
            assertEquals(expectedDigest, binding.provider.fingerprint.value)

            // Verify declaration compilation produced exact fields
            val fields = binding.provider.descriptor.configurationFields
            assertEquals(3, fields.size)
            val modeField = fields.find { it.id == "mode" } as? ChannelConfigurationField.ChoiceField
                ?: throw AssertionError("mode field must be ChoiceField")
            assertEquals("Mode", modeField.label)
            assertEquals(5, modeField.choices.size)
            assertEquals("ECHO", modeField.choices[0].id)
            val verboseField = fields.find { it.id == "verbose" } as? ChannelConfigurationField.BooleanField
                ?: throw AssertionError("verbose field must be BooleanField")
            assertEquals("Verbose", verboseField.label)
            val retryField = fields.find { it.id == "retry_count" } as? ChannelConfigurationField.NumberField
                ?: throw AssertionError("retry_count field must be NumberField")
            assertEquals("Retry Count", retryField.label)
            assertEquals(0L, retryField.minimum)
            assertEquals(10L, retryField.maximum)

            // Verify capability compilation
            val caps = binding.provider.descriptor.requiredCapabilities
            assertTrue("must contain Transcription", caps.any { it.stableId == "transcription" })
            assertTrue("must contain Synthesis", caps.any { it.stableId == "synthesis" })
            assertTrue("must contain AudioOperation (from playback)", caps.any { it.stableId == "audio-operation" })
            assertTrue("must contain DeferredAudioPlayback (from playback)", caps.any { it.stableId == "deferred-audio-playback" })

            // Verify defaults compiled correctly
            val defaults = binding.provider.descriptor.configuration.defaultPayload().toJsonObject()
            assertEquals("ECHO", defaults.getString("mode"))
            assertFalse(defaults.getBoolean("verbose"))
            assertEquals(3L, defaults.getLong("retry_count"))
        }
    }

    @Test
    fun `pre-cutover artifacts and indexes fail closed under the revised contract without rewriting stored bytes`() = runTest {
        withTemporaryDirectory { root ->
            val historicalBytes = loadResource("diagnostics-channel/historical/v1.1.0/talkcan-channel.zip")
            val historicalSource = sourceRecord("1305223892", "2", "2").copy(
                coordinates = GitHubRepositoryCoordinates("talkcan", "diagnostics-channel"),
                release = GitHubReleaseIdentity("2", "v1.1.0", false),
                asset = GitHubAssetIdentity("2", "talkcan-channel.zip"),
            )
            val historicalDigest = digest(historicalBytes)

            // The revised production validator rejects the predecessor artifact
            // before installation, publication, or execution: the new declaration
            // arrays are required and no compatibility default exists.
            val store = InstalledPackageStore(root)
            val outcome = PackageValidator.validatePackage(
                ByteArrayInputStream(historicalBytes),
                historicalSource,
                store.stagingHandle("predecessor"),
            )
            assertTrue("predecessor artifact must be rejected: $outcome", outcome is PackageOutcome.Failure)
            val failure = (outcome as PackageOutcome.Failure).error
            assertTrue("predecessor rejection must be typed FORMAT: $failure", failure is PackageFailure.Format)
            assertEquals(PackageFailure.FormatDetail.MALFORMED_MANIFEST, (failure as PackageFailure.Format).detail)

            // A persisted pre-cutover index that omits the required declarations is
            // globally undecodable under the revised strict codec; the exact
            // predecessor bytes remain immutable rather than rewritten or defaulted.
            val contentDir = File(root, "content/sha256").apply { mkdirs() }
            File(contentDir, historicalDigest).writeBytes(historicalBytes)
            writeHistoricalIndex(root, historicalBytes, historicalSource)
            val loadOutcome = InstalledPackageStore(root).loadAndMaterialize(NoopLuaKernelBridge)
            assertTrue("pre-cutover index must fail closed: $loadOutcome", loadOutcome is PackageOutcome.Failure)
            val loadError = (loadOutcome as PackageOutcome.Failure).error
            assertTrue("index rejection must be typed RECOVERY: $loadError", loadError is PackageFailure.Recovery)
            assertEquals(PackageFailure.RecoveryDetail.INDEX_CORRUPT, (loadError as PackageFailure.Recovery).detail)
            assertContent(root, historicalDigest, historicalBytes)
        }
    }

    @Test
    fun `index codec round-trips profile resolver multiline and source kind declarations exactly`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("260", "860", "861")
            val manifest = """{"manifestVersion":1,"repositoryId":"260","packageVersion":"1.0.0","entryModule":"plugin","presentation":{"label":"Declared Package","summary":"Declaration round-trip fixture"},"runtime":{"luaVersion":"$LUA_VERSION","apiVersion":"$API_VERSION"},"configuration":{"schemaVersion":1,"data":{"fields":[{"id":"profile_id","type":"string","default":""},{"id":"model","type":"string","default":""},{"id":"prompt","type":"string","default":"line one\nline two"}],"additionalProperties":false},"ui":{"fields":[{"field":"profile_id","control":"dynamic-choice","label":"Profile","source":"profile:provider"},{"field":"model","control":"dynamic-choice","label":"Model","source":"resolver:models","dependsOn":"profile_id"},{"field":"prompt","control":"multiline","label":"Prompt"}]}},"resources":{"mounts":[]},"profileTypes":[{"id":"provider","label":"Provider","help":"Connection profile","schemaVersion":1,"data":{"additionalProperties":false,"fields":[{"id":"base_url","type":"string","required":true,"default":"https://api.example.com"}]},"ui":{"fields":[{"field":"base_url","control":"text","label":"Base URL"}]}}],"choiceResolvers":[{"id":"models","module":"choices.models","capabilities":["network.http"]}],"workQueues":[],"capabilities":["network.http"]}"""
            val lua = "-- declared\nreturn { startup = function() end, handle_readiness = function() return { ready = true } end }"
            val resolverLua = "return { resolve = function() end }"
            val archive = strictUnixStoredZip(
                listOf(
                    ZipFixtureEntry("manifest.json", manifest.toByteArray(UTF_8), 0b1000000110100100),
                    ZipFixtureEntry("lua/", ByteArray(0), 0b0100000111101101),
                    ZipFixtureEntry("lua/plugin.lua", lua.toByteArray(UTF_8), 0b1000000110100100),
                    ZipFixtureEntry("lua/choices/", ByteArray(0), 0b0100000111101101),
                    ZipFixtureEntry("lua/choices/models.lua", resolverLua.toByteArray(UTF_8), 0b1000000110100100),
                ),
            )
            success(repository(root, RecordingPublisher(root)).installOrUpdate(ByteArrayInputStream(archive), source))
            val original = index(root)

            val decoded = StrictIndexCodec.decodeIndex(StrictIndexCodec.encodeIndex(original))

            assertEquals("every revised declaration must survive an exact encode/decode round-trip", original.providers, decoded.providers)
            val active = decoded.providers.getValue(source.repositoryId).active
            val profileType = active.manifest.profileTypes.single()
            assertEquals("provider", profileType.id)
            assertEquals("Provider", profileType.label)
            assertEquals("Connection profile", profileType.help)
            assertEquals(1, profileType.schemaVersion)
            assertEquals(listOf("base_url"), profileType.schema.dataFields.map { it.id })
            val resolver = active.manifest.choiceResolvers.single()
            assertEquals("models", resolver.id)
            assertEquals("choices.models", resolver.module)
            assertEquals(listOf("network.http"), resolver.capabilities.toList())
            assertTrue(active.manifest.workQueues.isEmpty())
            val uiFields = active.manifest.configuration.ui.fields
            assertEquals("profile:provider", uiFields[0].source)
            assertEquals("provider", (uiFields[0].sourceReference as DynamicChoiceSourceReference.ProfileType).typeId)
            assertEquals("resolver:models", uiFields[1].source)
            assertEquals("models", (uiFields[1].sourceReference as DynamicChoiceSourceReference.PackageResolver).resolverId)
            assertEquals("profile_id", uiFields[1].dependsOnFieldId)
            assertEquals(UiControl.MULTILINE, uiFields[2].control)
            assertEquals("line one\nline two", (active.manifest.configuration.data.fields[2] as ConfigurationFieldDeclaration.StringField).default)
            assertTrue(active.manifest.capabilities.contains(PackageCapability.NETWORK_HTTP))
        }
    }

    @Test
    fun `index codec round-trips work queues secrets and new capabilities without materialization`() {
        val identity = GitHubRepositoryIdentity("261")
        val source = sourceRecord("261", "861", "862")
        val schema = io.talkcan.profile.ProfileSchema(
            listOf(
                io.talkcan.profile.ProfileFieldDeclaration.StringField("base_url", true, "https://api.example.com", null),
                io.talkcan.profile.ProfileFieldDeclaration.SecretField("api_key", true),
            ),
            listOf(
                io.talkcan.profile.ProfileUiFieldDeclaration("base_url", io.talkcan.profile.ProfileUiControl.TEXT, "Base URL", null, null),
                io.talkcan.profile.ProfileUiFieldDeclaration("api_key", io.talkcan.profile.ProfileUiControl.SECRET, "API key", null, null),
            ),
        )
        val manifest = PackageManifest(
            manifestVersion = 1,
            repositoryId = identity,
            packageVersion = "1.0.0",
            entryModule = "plugin",
            presentation = PackagePresentation("Declared", "Work and secret declarations"),
            runtime = RuntimeRequirements(LUA_VERSION, API_VERSION),
            configuration = PackageConfigurationDeclaration(ConfigurationDataDeclaration(emptyList()), ConfigurationUiDeclaration(emptyList())),
            resources = PackageResourcesDeclaration(emptyList()),
            profileTypes = listOf(ProfileTypeDeclaration("provider", "Provider", null, 1, schema)),
            choiceResolvers = listOf(ChoiceResolverDeclaration("models", "choices.models", setOf(PackageCapability.NETWORK_HTTP, PackageCapability.SECRETS_READ))),
            workQueues = listOf(WorkQueueDeclaration("turns")),
            capabilities = setOf(PackageCapability.NETWORK_HTTP, PackageCapability.PROFILES_READ, PackageCapability.SECRETS_READ, PackageCapability.WORK_QUEUE),
        )
        val revision = StoredPackageRevision(ArtifactDigest("b".repeat(64)), manifest, source)
        val index = StoredInstalledIndex(1, mapOf(identity to StoredProviderRecord(revision, null)))

        val decoded = StrictIndexCodec.decodeIndex(StrictIndexCodec.encodeIndex(index))

        assertEquals("work queues, secret schemas, and new capabilities must round-trip exactly", index.providers, decoded.providers)
        val active = decoded.providers.getValue(identity).active
        assertEquals(listOf("turns"), active.manifest.workQueues.map { it.id })
        assertEquals(setOf("api_key"), active.manifest.profileTypes.single().schema.secretFieldIds())
        assertEquals(
            listOf("network.http", "profiles.read", "secrets.read", "work.queue"),
            active.manifest.capabilities.toList(),
        )
        assertEquals(
            setOf(PackageCapability.NETWORK_HTTP, PackageCapability.SECRETS_READ),
            active.manifest.choiceResolvers.single().capabilities,
        )
    }

    @Test
    fun `failed candidate leaves predecessor declarations authoritative and writes no candidate state`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("262", "863", "864")
            val provider = InstalledProviderId.derive(source.repositoryId)
            val goodArchive = packageArchive("262", "1.0.0", "good")
            val repo = repository(root, RecordingPublisher(root))
            success(repo.installOrUpdate(ByteArrayInputStream(goodArchive), source))
            val originalIndex = index(root)
            val contentFiles = File(root, "content/sha256").listFiles()!!.map { it.name }.toSet()

            // Candidate with an unknown capability is rejected by static validation.
            val badSource = source.copy(release = GitHubReleaseIdentity("865", "v865", false), asset = GitHubAssetIdentity("866", "package-866.zip"))
            val badManifest = """{"manifestVersion":1,"repositoryId":"262","packageVersion":"2.0.0","entryModule":"plugin","presentation":{"label":"Bad","summary":"Rejected candidate"},"runtime":{"luaVersion":"$LUA_VERSION","apiVersion":"$API_VERSION"},"configuration":{"schemaVersion":1,"data":{"fields":[],"additionalProperties":false},"ui":{"fields":[]}},"resources":{"mounts":[]},"profileTypes":[],"choiceResolvers":[],"workQueues":[],"capabilities":["totally.unknown"]}"""
            val badArchive = strictUnixStoredZip(
                listOf(
                    ZipFixtureEntry("manifest.json", badManifest.toByteArray(UTF_8), 0b1000000110100100),
                    ZipFixtureEntry("lua/", ByteArray(0), 0b0100000111101101),
                    ZipFixtureEntry("lua/plugin.lua", "-- bad\nreturn {}".toByteArray(UTF_8), 0b1000000110100100),
                ),
            )
            val outcome = repo.installOrUpdate(ByteArrayInputStream(badArchive), badSource)
            assertTrue("candidate must fail: $outcome", outcome is PackageOutcome.Failure)
            val failure = (outcome as PackageOutcome.Failure).error
            assertTrue("candidate failure must be typed CAPABILITY: $failure", failure is PackageFailure.Capability)
            assertEquals(PackageFailure.CapabilityDetail.UNKNOWN_CAPABILITY_ID, (failure as PackageFailure.Capability).detail)

            // Prior active revision, declarations, index, and content set are unchanged.
            assertEquals(originalIndex, index(root))
            assertEquals("1.0.0", index(root).providers.getValue(source.repositoryId).active.manifest.packageVersion)
            assertEquals(contentFiles, File(root, "content/sha256").listFiles()!!.map { it.name }.toSet())
            val publications = RecordingPublisher(root)
            assertEquals(Unit, success(repository(root, publications).loadAndPublish()))
            publications.assertLast(setOf(provider), emptySet())
        }
    }

    @Test
    fun `rollback restores predecessor declarations and rejects declaration tampering of the rollback slot`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("263", "867", "868")
            val provider = InstalledProviderId.derive(source.repositoryId)
            val repo = repository(root, RecordingPublisher(root))
            success(repo.installOrUpdate(ByteArrayInputStream(keyboardArchive("263", "1.0.0")), source))

            val updatedSource = source.copy(release = GitHubReleaseIdentity("869", "v869", false), asset = GitHubAssetIdentity("870", "package-870.zip"))
            success(repo.installOrUpdate(ByteArrayInputStream(packageArchive("263", "2.0.0", "updated")), updatedSource))
            assertEquals("2.0.0", index(root).providers.getValue(source.repositoryId).active.manifest.packageVersion)

            val publications = RecordingPublisher(root)
            assertEquals(MutationResult.RolledBack(provider), success(repository(root, publications).rollback(source.repositoryId)))
            val restored = index(root).providers.getValue(source.repositoryId).active
            assertEquals("1.0.0", restored.manifest.packageVersion)
            assertTrue(restored.manifest.capabilities.contains(PackageCapability.KEYBOARD_OUTPUT))
            assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES, restored.manifest.configuration.ui.fields.single().source)
            assertTrue(restored.manifest.profileTypes.isEmpty())
            assertTrue(restored.manifest.choiceResolvers.isEmpty())
            assertTrue(restored.manifest.workQueues.isEmpty())
            publications.assertLast(setOf(provider), emptySet())

            // Tampering the cached rollback declarations is detected against the
            // immutable archived bytes; the rollback itself fails typed, and the
            // predecessor archive bytes are never rewritten.
            val rolledIndex = index(root)
            val record = rolledIndex.providers.getValue(source.repositoryId)
            val tamperedRollback = record.rollback!!.copy(
                manifest = record.rollback!!.manifest.copy(
                    profileTypes = listOf(
                        ProfileTypeDeclaration(
                            "extra",
                            "Extra",
                            null,
                            1,
                            io.talkcan.profile.ProfileSchema(emptyList(), emptyList())
                        )
                    )
                )
            )
            File(root, "index.json").writeText(
                StrictIndexCodec.encodeIndex(
                    StoredInstalledIndex(rolledIndex.version, rolledIndex.providers + (source.repositoryId to record.copy(rollback = tamperedRollback)))
                ),
                UTF_8,
            )
            val tamperedOutcome = repository(root, RecordingPublisher(root)).rollback(source.repositoryId)
            assertTrue("tampered rollback must fail: $tamperedOutcome", tamperedOutcome is PackageOutcome.Failure)
            val tamperedError = (tamperedOutcome as PackageOutcome.Failure).error
            assertTrue("tampered rollback must be typed ROLLBACK: $tamperedError", tamperedError is PackageFailure.Rollback)
            assertEquals(PackageFailure.RollbackDetail.ROLLBACK_VALIDATION_FAILED, (tamperedError as PackageFailure.Rollback).detail)
            assertContent(root, digest(keyboardArchive("263", "1.0.0")), keyboardArchive("263", "1.0.0"))
        }
    }

    @Test
    fun `active archive corruption isolates the provider typed and leaves siblings and bytes untouched`() = runTest {
        withTemporaryDirectory { root ->
            val sourceA = sourceRecord("264", "871", "872")
            val sourceB = sourceRecord("265", "873", "874")
            val providerA = InstalledProviderId.derive(sourceA.repositoryId)
            val providerB = InstalledProviderId.derive(sourceB.repositoryId)
            val archiveA = packageArchive("264", "1.0.0", "corrupt-target")
            val archiveB = packageArchive("265", "1.0.0", "sibling")
            val repo = repository(root, RecordingPublisher(root))
            success(repo.installOrUpdate(ByteArrayInputStream(archiveA), sourceA))
            success(repo.installOrUpdate(ByteArrayInputStream(archiveB), sourceB))

            // Flip one byte of provider A's immutable content under its digest.
            val digestA = digest(archiveA)
            val contentFile = File(root, "content/sha256/$digestA")
            contentFile.setWritable(true)
            val bytes = contentFile.readBytes()
            bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
            contentFile.writeBytes(bytes)

            val matResult = success(InstalledPackageStore(root).loadAndMaterialize(NoopLuaKernelBridge))
            val failureA = matResult.failures[providerA]
            assertTrue("corrupted provider must fail typed: $failureA", failureA is PackageFailure.Integrity)
            assertEquals(PackageFailure.IntegrityDetail.DIGEST_MISMATCH, (failureA as PackageFailure.Integrity).detail)
            assertTrue("sibling provider must still bind", matResult.bindings.containsKey(providerB))
            // Index metadata and the corrupted bytes themselves are untouched.
            assertEquals(digestA, index(root).providers.getValue(sourceA.repositoryId).active.digest.value)
            assertTrue(contentFile.readBytes().contentEquals(bytes))
        }
    }

    @Test
    fun `manifest mutation isolation preserves sibling provider and reports typed failure`() = runTest {
        withTemporaryDirectory { root ->
            // Two providers: one will have its index mutated
            val sourceA = sourceRecord("126", "753", "754")
            val sourceB = sourceRecord("127", "755", "756")
            val providerA = InstalledProviderId.derive(sourceA.repositoryId)
            val providerB = InstalledProviderId.derive(sourceB.repositoryId)

            // Package A with non-trivial declarations
            val manifestA = """{"manifestVersion":1,"repositoryId":"126","packageVersion":"1.0.0","entryModule":"plugin","presentation":{"label":"Mutation Target","summary":"Target for manifest mutation test"},"runtime":{"luaVersion":"$LUA_VERSION","apiVersion":"$API_VERSION"},"configuration":{"schemaVersion":1,"data":{"fields":[{"id":"mode","type":"string","default":"ECHO","allowedValues":["ECHO","DELAYED_ECHO"]}],"additionalProperties":false},"ui":{"fields":[{"field":"mode","control":"choice","label":"Mode","choices":[{"value":"ECHO","label":"Echo"},{"value":"DELAYED_ECHO","label":"Delayed"}]}]}},"resources":{"mounts":[]},"profileTypes":[],"choiceResolvers":[],"workQueues":[],"capabilities":["audio.playback"]}"""
            val luaA = "-- target\nreturn { startup = function() end, handle_readiness = function() return { ready = true } end }"
            val archiveA = strictUnixStoredZip(
                listOf(
                    ZipFixtureEntry("manifest.json", manifestA.toByteArray(UTF_8), 0b1000000110100100),
                    ZipFixtureEntry("lua/", ByteArray(0), 0b0100000111101101),
                    ZipFixtureEntry("lua/plugin.lua", luaA.toByteArray(UTF_8), 0b1000000110100100),
                ),
            )

            // Package B (sibling) with different declarations
            val manifestB = """{"manifestVersion":1,"repositoryId":"127","packageVersion":"1.0.0","entryModule":"plugin","presentation":{"label":"Sibling Package","summary":"Sibling for mutation isolation test"},"runtime":{"luaVersion":"$LUA_VERSION","apiVersion":"$API_VERSION"},"configuration":{"schemaVersion":1,"data":{"fields":[],"additionalProperties":false},"ui":{"fields":[]}},"resources":{"mounts":[]},"profileTypes":[],"choiceResolvers":[],"workQueues":[],"capabilities":[]}"""
            val luaB = "-- sibling\nreturn { startup = function() end, handle_readiness = function() return { ready = true } end }"
            val archiveB = strictUnixStoredZip(
                listOf(
                    ZipFixtureEntry("manifest.json", manifestB.toByteArray(UTF_8), 0b1000000110100100),
                    ZipFixtureEntry("lua/", ByteArray(0), 0b0100000111101101),
                    ZipFixtureEntry("lua/plugin.lua", luaB.toByteArray(UTF_8), 0b1000000110100100),
                ),
            )

            val publisher = RecordingPublisher(root)
            val repository = repository(root, publisher)
            success(repository.installOrUpdate(ByteArrayInputStream(archiveA), sourceA))
            success(repository.installOrUpdate(ByteArrayInputStream(archiveB), sourceB))
            val digestB = digest(archiveB)
            val digestA = digest(archiveA)

            // Mutate provider A's index: change the manifest's capabilities (add a fake one)
            val oldIndex = index(root)
            val recordA = oldIndex.providers.getValue(sourceA.repositoryId)
            val mutatedManifest = recordA.active.manifest.copy(
                capabilities = recordA.active.manifest.capabilities + PackageCapability.AUDIO_TRANSCRIPTION
            )
            val mutatedActive = recordA.active.copy(manifest = mutatedManifest)
            val mutatedRecord = recordA.copy(active = mutatedActive)
            val mutatedIndex = StoredInstalledIndex(
                version = oldIndex.version,
                providers = oldIndex.providers + (sourceA.repositoryId to mutatedRecord)
            )
            File(root, "index.json").writeText(StrictIndexCodec.encodeIndex(mutatedIndex), UTF_8)

            // Verify loadAndMaterialize directly detects the mutation on provider A,
            // and produces a valid binding for provider B
            val store = InstalledPackageStore(root)
            val matResult = success(store.loadAndMaterialize(NoopLuaKernelBridge))
            val failureA = matResult.failures[providerA]
            assertTrue("Expected SERIALIZATION_VIOLATION but got $failureA", failureA is PackageFailure.Mutation)
            assertEquals(PackageFailure.MutationDetail.SERIALIZATION_VIOLATION, (failureA as PackageFailure.Mutation).detail)

            val bindingB = matResult.bindings[providerB]
            assertTrue("Sibling provider B must produce a valid binding", bindingB != null)
            assertEquals(digestB, bindingB!!.provider.fingerprint.value)

            // Verify loadAndPublish publishes B as binding and A as failure
            val restartedPublisher = RecordingPublisher(root)
            val restarted = repository(root, restartedPublisher)
            success(restarted.loadAndPublish())
            restartedPublisher.assertLast(setOf(providerB), setOf(providerA))

            // Verify B's content is intact
            assertContent(root, digestB, archiveB)
            // Verify A's content still exists (the mutation only changed the index, not stored bytes)
            assertContent(root, digestA, archiveA)
        }
    }
    @Test
    fun `index codec round-trips a keyboard output revision with dynamic choice exactly`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("200", "800", "801")
            success(repository(root, RecordingPublisher(root)).installOrUpdate(ByteArrayInputStream(keyboardArchive("200", "1.0.0")), source))
            val original = index(root)

            val decoded = StrictIndexCodec.decodeIndex(StrictIndexCodec.encodeIndex(original))

            assertEquals(original.version, decoded.version)
            assertEquals("dynamic-choice source and keyboard.output must survive an exact encode/decode round-trip", original.providers, decoded.providers)
            val active = decoded.providers.getValue(source.repositoryId).active
            val uiField = active.manifest.configuration.ui.fields.single()
            assertEquals(UiControl.DYNAMIC_CHOICE, uiField.control)
            assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES, uiField.source)
            assertNull(uiField.choices)
            assertTrue(active.manifest.capabilities.contains(PackageCapability.KEYBOARD_OUTPUT))
            assertTrue(active.manifest.capabilities.contains(PackageCapability.AUDIO_TRANSCRIPTION))
        }
    }
    @Test
    fun `index codec round-trips a three stage keyboard hierarchy with dependsOn exactly`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("240", "840", "841")
            success(repository(root, RecordingPublisher(root)).installOrUpdate(ByteArrayInputStream(keyboardHierarchyArchive("240", "1.0.0")), source))
            val original = index(root)

            val decoded = StrictIndexCodec.decodeIndex(StrictIndexCodec.encodeIndex(original))

            assertEquals(original.version, decoded.version)
            assertEquals("three-stage dependsOn chain must survive an exact encode/decode round-trip", original.providers, decoded.providers)
            val uiFields = decoded.providers.getValue(source.repositoryId).active.manifest.configuration.ui.fields
            assertEquals(listOf("host_os", "host_layout", "host_profile"), uiFields.map { it.field })
            assertNull(uiFields[0].dependsOnFieldId)
            assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_PLATFORMS, uiFields[0].source)
            assertEquals("host_os", uiFields[1].dependsOnFieldId)
            assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_LAYOUTS, uiFields[1].source)
            assertEquals("host_layout", uiFields[2].dependsOnFieldId)
            assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES, uiFields[2].source)
        }
    }

    @Test
    fun `keyboard output package preserves dynamic choice through install reparse and rollback revalidation`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("201", "802", "803")
            val archive = keyboardArchive("201", "1.0.0")
            success(repository(root, RecordingPublisher(root)).installOrUpdate(ByteArrayInputStream(archive), source))

            // Store-level preservation: the committed cached index retains the exact
            // dynamic-choice source declaration and keyboard.output eligibility.
            val stored = index(root).providers.getValue(source.repositoryId).active
            assertEquals(digest(archive), stored.digest.value)
            val storedUi = stored.manifest.configuration.ui.fields.single()
            assertEquals(UiControl.DYNAMIC_CHOICE, storedUi.control)
            assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES, storedUi.source)
            assertTrue(stored.manifest.capabilities.contains(PackageCapability.KEYBOARD_OUTPUT))

            // Reparse-from-artifact recovery (rollback path): revalidating the stored
            // revision reparses exact bytes into identical declarations.
            val reparsed = success(InstalledPackageStore(root).revalidateStoredRevision(stored))
            assertEquals(stored.manifest, reparsed.manifest)
            assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES, reparsed.manifest.configuration.ui.fields.single().source)
            assertTrue(reparsed.manifest.capabilities.contains(PackageCapability.KEYBOARD_OUTPUT))
        }
    }

    @Test
    fun `keyboard output package survives restart through reparse without Lua or host effect`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("202", "804", "805")
            val provider = InstalledProviderId.derive(source.repositoryId)
            val archive = keyboardArchive("202", "1.0.0")
            val expectedDigest = digest(archive)
            success(repository(root, RecordingPublisher(root)).installOrUpdate(ByteArrayInputStream(archive), source))

            // Restart: a fresh repository reparses exact stored bytes and publishes the
            // provider. NoopLuaKernelBridge throws if any Lua state creation is attempted,
            // proving catalogue restoration performs no Lua or host effect.
            val restartedPublisher = RecordingPublisher(root)
            assertEquals(Unit, success(repository(root, restartedPublisher).loadAndPublish()))
            restartedPublisher.assertLast(setOf(provider), emptySet())
            val binding = restartedPublisher.snapshots.last().bindings.getValue(provider)
            assertEquals(expectedDigest, binding.provider.fingerprint.value)
        }
    }

    @Test
    fun `cached metadata cannot add remove or alter keyboard output eligibility or dynamic source declarations`() = runTest {
        val keyboardConfig = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(listOf(ConfigurationFieldDeclaration.StringField("host_profile", "linux:us", null))),
            ConfigurationUiDeclaration(listOf(UiFieldDeclaration("host_profile", UiControl.DYNAMIC_CHOICE, "Host profile", null, null, DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES)))
        )
        val relabelled = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(listOf(ConfigurationFieldDeclaration.StringField("host_profile", "linux:us", null))),
            ConfigurationUiDeclaration(listOf(UiFieldDeclaration("host_profile", UiControl.DYNAMIC_CHOICE, "Tampered label", null, null, DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES)))
        )
        val renamedField = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(listOf(ConfigurationFieldDeclaration.StringField("tampered_profile", "linux:us", null))),
            ConfigurationUiDeclaration(listOf(UiFieldDeclaration("tampered_profile", UiControl.DYNAMIC_CHOICE, "Host profile", null, null, DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES)))
        )
        val emptyConfig = PackageConfigurationDeclaration(ConfigurationDataDeclaration(emptyList()), ConfigurationUiDeclaration(emptyList()))

        class Case(val name: String, val keyboard: Boolean, val mutate: (PackageManifest) -> PackageManifest)
        val cases = listOf(
            Case("remove keyboard.output capability", true) { it.copy(capabilities = it.capabilities - PackageCapability.KEYBOARD_OUTPUT) },
            Case("add foreign capability", true) { it.copy(capabilities = it.capabilities + PackageCapability.AUDIO_PLAYBACK) },
            Case("remove dynamic-choice declaration", true) { it.copy(configuration = emptyConfig) },
            Case("alter dynamic-choice label", true) { it.copy(configuration = relabelled) },
            Case("alter dynamic-choice field id", true) { it.copy(configuration = renamedField) },
            Case("add keyboard.output capability", false) { it.copy(capabilities = it.capabilities + PackageCapability.KEYBOARD_OUTPUT) },
            Case("add dynamic-choice declaration", false) { it.copy(configuration = keyboardConfig) },
        )

        for (case in cases) {
            withTemporaryDirectory { root ->
                val sourceA = sourceRecord("210", "810", "811")
                val sourceB = sourceRecord("211", "812", "813")
                val providerA = InstalledProviderId.derive(sourceA.repositoryId)
                val providerB = InstalledProviderId.derive(sourceB.repositoryId)
                val archiveA = if (case.keyboard) keyboardArchive("210", "1.0.0") else packageArchive("210", "1.0.0", "plain")
                val archiveB = packageArchive("211", "1.0.0", "sibling")
                val repository = repository(root, RecordingPublisher(root))
                success(repository.installOrUpdate(ByteArrayInputStream(archiveA), sourceA))
                success(repository.installOrUpdate(ByteArrayInputStream(archiveB), sourceB))

                // Tamper only the cached index metadata; the authoritative archive bytes stay untouched.
                val oldIndex = index(root)
                val recordA = oldIndex.providers.getValue(sourceA.repositoryId)
                val mutatedActive = recordA.active.copy(manifest = case.mutate(recordA.active.manifest))
                val mutatedIndex = StoredInstalledIndex(
                    version = oldIndex.version,
                    providers = oldIndex.providers + (sourceA.repositoryId to recordA.copy(active = mutatedActive))
                )
                File(root, "index.json").writeText(StrictIndexCodec.encodeIndex(mutatedIndex), UTF_8)

                val matResult = success(InstalledPackageStore(root).loadAndMaterialize(NoopLuaKernelBridge))
                val failureA = matResult.failures[providerA]
                assertTrue("${case.name}: expected SERIALIZATION_VIOLATION but got $failureA", failureA is PackageFailure.Mutation)
                assertEquals(PackageFailure.MutationDetail.SERIALIZATION_VIOLATION, (failureA as PackageFailure.Mutation).detail)
                assertTrue("${case.name}: sibling provider B must still bind from its own exact bytes", matResult.bindings.containsKey(providerB))
            }
        }
    }

    @Test
    fun `rollback revalidation rejects cached metadata that alters keyboard output or a dynamic source`() = runTest {
        withTemporaryDirectory { root ->
            val source = sourceRecord("220", "820", "821")
            val archive = keyboardArchive("220", "1.0.0")
            success(repository(root, RecordingPublisher(root)).installOrUpdate(ByteArrayInputStream(archive), source))
            val stored = index(root).providers.getValue(source.repositoryId).active
            val store = InstalledPackageStore(root)

            // A legitimate stored revision revalidates: reparse round-trips declarations.
            assertEquals(stored.manifest, success(store.revalidateStoredRevision(stored)).manifest)

            fun assertSerializationViolation(revision: StoredPackageRevision, label: String) {
                val outcome = store.revalidateStoredRevision(revision)
                assertTrue("$label must be rejected against exact bytes", outcome is PackageOutcome.Failure)
                val error = (outcome as PackageOutcome.Failure).error
                assertTrue("$label must be a Mutation failure but was $error", error is PackageFailure.Mutation)
                assertEquals(PackageFailure.MutationDetail.SERIALIZATION_VIOLATION, (error as PackageFailure.Mutation).detail)
            }

            assertSerializationViolation(
                stored.copy(manifest = stored.manifest.copy(capabilities = stored.manifest.capabilities - PackageCapability.KEYBOARD_OUTPUT)),
                "removing keyboard.output",
            )
            assertSerializationViolation(
                stored.copy(manifest = stored.manifest.copy(capabilities = stored.manifest.capabilities + PackageCapability.AUDIO_PLAYBACK)),
                "adding a foreign capability",
            )
            assertSerializationViolation(
                stored.copy(manifest = stored.manifest.copy(configuration = PackageConfigurationDeclaration(ConfigurationDataDeclaration(emptyList()), ConfigurationUiDeclaration(emptyList())))),
                "removing the dynamic source declaration",
            )
        }
    }

    @Test
    fun `cached index decode rejects unknown dynamic source stray source on static control and missing source`() {
        fun indexJson(uiFieldJson: String): String = """
{
  "version": 1,
  "providers": {
    "230": {
      "active": {
        "digest": "${"a".repeat(64)}",
        "manifest": {"manifestVersion":1,"repositoryId":"230","packageVersion":"1.0.0","entryModule":"plugin","presentation":{"label":"Keyboard Package","summary":"Keyboard output fixture"},"runtime":{"luaVersion":"$LUA_VERSION","apiVersion":"$API_VERSION"},"configuration":{"schemaVersion":1,"data":{"fields":[{"id":"host_profile","type":"string","default":"linux:us"}],"additionalProperties":false},"ui":{"fields":[$uiFieldJson]}},"resources":{"mounts":[]},"profileTypes":[],"choiceResolvers":[],"workQueues":[],"capabilities":["keyboard.output"]},
        "sourceRecord": {"repositoryId":"230","coordinates":{"owner":"owner-230","repository":"repository-230"},"release":{"releaseId":"830","tag":"v830","isPrerelease":false},"asset":{"assetId":"831","name":"package-831.zip"},"ownerId":"9000001"}
      },
      "rollback": null
    }
  }
}
"""
        fun assertDecodeRejects(json: String, label: String) {
            try {
                StrictIndexCodec.decodeIndex(json)
                throw AssertionError("expected decode rejection for $label")
            } catch (e: IllegalArgumentException) {
                // expected: cached metadata cannot grant an unknown, stray, or missing source
            }
        }

        assertDecodeRejects(indexJson("""{"field":"host_profile","control":"dynamic-choice","label":"Host profile","source":"not-a-real-source"}"""), "unknown dynamic-choice source")
        assertDecodeRejects(indexJson("""{"field":"host_profile","control":"text","label":"Host profile","source":"keyboard-output-profiles"}"""), "stray source on a static control")
        assertDecodeRejects(indexJson("""{"field":"host_profile","control":"dynamic-choice","label":"Host profile"}"""), "dynamic-choice missing source")
        assertDecodeRejects(indexJson("""{"field":"host_profile","control":"text","label":"Host profile","dependsOn":"host_profile"}"""), "dependsOn on a static control")
        assertDecodeRejects(indexJson("""{"field":"host_profile","control":"dynamic-choice","label":"Host profile","source":"keyboard-output-profiles","dependsOn":123}"""), "non-string dependsOn")
    }

    private fun repository(root: File, publisher: RecordingPublisher): InstalledPackageRepository =
        repository(InstalledPackageStore(root), publisher)

    private fun repository(store: InstalledPackageStore, publisher: RecordingPublisher): InstalledPackageRepository = InstalledPackageRepository(
        store = store,
        bridge = NoopLuaKernelBridge,
        publisher = publisher::publish,
        dispatcher = Dispatchers.Default,
    )

    private fun index(root: File): StoredInstalledIndex = success(InstalledPackageStore(root).loadIndex()).index

    private fun assertContent(root: File, digest: String, expected: ByteArray) {
        assertTrue("committed content $digest must exist", File(root, "content/sha256/$digest").readBytes().contentEquals(expected))
    }

    private fun loadResource(path: String): ByteArray = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
        "Missing pinned package fixture: $path"
    }.use { it.readBytes() }

    private fun writeHistoricalIndex(root: File, archive: ByteArray, source: PackageSourceRecord) {
        val contentDir = File(root, "content/sha256").apply { mkdirs() }
        val artifactDigest = digest(archive)
        File(contentDir, artifactDigest).writeBytes(archive)

        val historicalManifest = unzipEntry(archive, "manifest.json").toString(UTF_8)
        val sourceJson = """{
  "repositoryId": "${source.repositoryId.value}",
  "coordinates": {
    "owner": "${source.coordinates.owner}",
    "repository": "${source.coordinates.repository}"
  },
  "release": {
    "releaseId": "${source.release.releaseId}",
    "tag": "${source.release.tag}",
    "isPrerelease": ${source.release.isPrerelease}
  },
  "asset": {
    "assetId": "${source.asset.assetId}",
    "name": "${source.asset.name}"
  },
  "ownerId": "${source.ownerId}"
}"""
        val indexJson = """{
  "version": 1,
  "providers": {
    "${source.repositoryId.value}": {
      "active": {
        "digest": "$artifactDigest",
        "manifest": $historicalManifest,
        "sourceRecord": $sourceJson
      },
      "rollback": null
    }
  }
}"""
        File(root, "index.json").writeText(indexJson, UTF_8)
    }

    private fun unzipEntry(zipBytes: ByteArray, name: String): ByteArray {
        java.util.zip.ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == name) {
                    return zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        error("missing entry $name in package fixture")
    }

    private fun sourceRecord(repositoryId: String, releaseId: String, assetId: String): PackageSourceRecord = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity(repositoryId),
        coordinates = GitHubRepositoryCoordinates("owner-$repositoryId", "repository-$repositoryId"),
        release = GitHubReleaseIdentity(releaseId, "v$releaseId", false),
        asset = GitHubAssetIdentity(assetId, "package-$assetId.zip"),
        ownerId = "9000001",
    )

    private fun packageArchive(repositoryId: String, version: String, marker: String): ByteArray {
        val source = "-- $marker\\nreturn { startup = function() end, handle_readiness = function() return { ready = true } end }"
        val manifest = """{"manifestVersion":1,"repositoryId":"$repositoryId","packageVersion":"$version","entryModule":"plugin","presentation":{"label":"Transaction package","summary":"Transactional package fixture"},"runtime":{"luaVersion":"$LUA_VERSION","apiVersion":"$API_VERSION"},"configuration":{"schemaVersion":1,"data":{"fields":[],"additionalProperties":false},"ui":{"fields":[]}},"resources":{"mounts":[]},"profileTypes":[],"choiceResolvers":[],"workQueues":[],"capabilities":[]}"""
        return strictUnixStoredZip(
            listOf(
                ZipFixtureEntry("manifest.json", manifest.toByteArray(UTF_8), 0b1000000110100100),
                ZipFixtureEntry("lua/", ByteArray(0), 0b0100000111101101),
                ZipFixtureEntry("lua/plugin.lua", source.toByteArray(UTF_8), 0b1000000110100100),
            ),
        )
    }
    private fun keyboardManifest(repositoryId: String, version: String): String =
        """{"manifestVersion":1,"repositoryId":"$repositoryId","packageVersion":"$version","entryModule":"plugin","presentation":{"label":"Keyboard Package","summary":"Keyboard output fixture"},"runtime":{"luaVersion":"$LUA_VERSION","apiVersion":"$API_VERSION"},"configuration":{"schemaVersion":1,"data":{"fields":[{"id":"host_profile","type":"string","default":"linux:us"}],"additionalProperties":false},"ui":{"fields":[{"field":"host_profile","control":"dynamic-choice","label":"Host profile","source":"keyboard-output-profiles"}]}},"resources":{"mounts":[]},"profileTypes":[],"choiceResolvers":[],"workQueues":[],"capabilities":["audio.transcription","keyboard.output"]}"""
    private fun keyboardHierarchyManifest(repositoryId: String, version: String): String =
        """{"manifestVersion":1,"repositoryId":"$repositoryId","packageVersion":"$version","entryModule":"plugin","presentation":{"label":"Keyboard Package","summary":"Keyboard output fixture"},"runtime":{"luaVersion":"$LUA_VERSION","apiVersion":"$API_VERSION"},"configuration":{"schemaVersion":1,"data":{"fields":[{"id":"host_os","type":"string","default":"linux"},{"id":"host_layout","type":"string","default":"linux:us"},{"id":"host_profile","type":"string","default":"linux:us"}],"additionalProperties":false},"ui":{"fields":[{"field":"host_os","control":"dynamic-choice","label":"Host OS","source":"keyboard-output-platforms"},{"field":"host_layout","control":"dynamic-choice","label":"Host layout","source":"keyboard-output-layouts","dependsOn":"host_os"},{"field":"host_profile","control":"dynamic-choice","label":"Host profile","source":"keyboard-output-profiles","dependsOn":"host_layout"}]}},"resources":{"mounts":[]},"profileTypes":[],"choiceResolvers":[],"workQueues":[],"capabilities":["keyboard.output"]}"""

    private fun keyboardArchive(repositoryId: String, version: String): ByteArray {
        val source = "-- keyboard\\nreturn { startup = function() end, handle_readiness = function() return { ready = true } end }"
        return strictUnixStoredZip(
            listOf(
                ZipFixtureEntry("manifest.json", keyboardManifest(repositoryId, version).toByteArray(UTF_8), 0b1000000110100100),
                ZipFixtureEntry("lua/", ByteArray(0), 0b0100000111101101),
                ZipFixtureEntry("lua/plugin.lua", source.toByteArray(UTF_8), 0b1000000110100100),
            ),
        )
    }
    private fun keyboardHierarchyArchive(repositoryId: String, version: String): ByteArray {
        val source = "-- keyboard-hierarchy\\nreturn { startup = function() end, handle_readiness = function() return { ready = true } end }"
        return strictUnixStoredZip(
            listOf(
                ZipFixtureEntry("manifest.json", keyboardHierarchyManifest(repositoryId, version).toByteArray(UTF_8), 0b1000000110100100),
                ZipFixtureEntry("lua/", ByteArray(0), 0b0100000111101101),
                ZipFixtureEntry("lua/plugin.lua", source.toByteArray(UTF_8), 0b1000000110100100),
            ),
        )
    }

    private fun strictUnixStoredZip(entries: List<ZipFixtureEntry>): ByteArray {
        val output = ByteArrayOutputStream()
        val centralEntries = entries.map { entry ->
            val name = entry.name.toByteArray(UTF_8)
            val crc = CRC32().apply { update(entry.bytes) }.value
            val localOffset = output.size().toLong()
            output.u32(LOCAL_SIGNATURE)
            output.u16(20)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u32(crc)
            output.u32(entry.bytes.size.toLong())
            output.u32(entry.bytes.size.toLong())
            output.u16(name.size)
            output.u16(0)
            output.write(name)
            output.write(entry.bytes)
            CentralFixtureEntry(entry, name, crc, localOffset)
        }
        val centralOffset = output.size().toLong()
        centralEntries.forEach { central ->
            output.u32(CENTRAL_SIGNATURE)
            output.u16((3 shl 8) or 20)
            output.u16(20)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u32(central.crc)
            output.u32(central.entry.bytes.size.toLong())
            output.u32(central.entry.bytes.size.toLong())
            output.u16(central.name.size)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u32(central.entry.unixMode.toLong() shl 16)
            output.u32(central.localOffset)
            output.write(central.name)
        }
        val centralSize = output.size().toLong() - centralOffset
        output.u32(EOCD_SIGNATURE)
        output.u16(0)
        output.u16(0)
        output.u16(entries.size)
        output.u16(entries.size)
        output.u32(centralSize)
        output.u32(centralOffset)
        output.u16(0)
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.u16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.u32(value: Long) {
        write((value and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 24) and 0xff).toInt())
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun <T> success(outcome: PackageOutcome<T>): T = when (outcome) {
        is PackageOutcome.Success -> outcome.value
        is PackageOutcome.Failure -> throw AssertionError("expected success, got ${outcome.error}")
    }

    private fun assertFailure(outcome: PackageOutcome<*>, expected: Any) {
        val failure = (outcome as? PackageOutcome.Failure)?.error
        val actual = when (failure) {
            is PackageFailure.Storage -> failure.detail
            is PackageFailure.Rollback -> failure.detail
            is PackageFailure.Shutdown -> failure.detail
            else -> null
        }
        assertEquals("expected typed failure $expected, got $failure", expected, actual)
    }

    private suspend fun <T> withTemporaryDirectory(block: suspend (File) -> T): T {
        val directory = createTempDirectory("installed-package-transactions-").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private data class ZipFixtureEntry(val name: String, val bytes: ByteArray, val unixMode: Int)
    private data class CentralFixtureEntry(val entry: ZipFixtureEntry, val name: ByteArray, val crc: Long, val localOffset: Long)
    private data class DurableFailureCase(
        val boundary: DurableBoundary,
        val expectedFailure: PackageFailure.StorageDetail,
        val contentWasCommitted: Boolean,
    )

    private class RecordingPublisher(private val root: File, private val reject: Boolean = false) {
        val snapshots = Collections.synchronizedList(mutableListOf<PublishedSnapshot>())

        suspend fun publish(materialized: MaterializationResult): PackageOutcome<Unit> {
            if (reject) return PackageOutcome.Failure(PackageFailure.Loading(PackageFailure.LoadingDetail.PUBLICATION_REJECTED))
            val authoritative = when (val load = InstalledPackageStore(root).loadIndex()) {
                is PackageOutcome.Success -> load.value.index
                is PackageOutcome.Failure -> throw AssertionError("publisher observed no authoritative index: ${load.error}")
            }
            val bindingIds = materialized.bindings.keys
            val failureIds = materialized.failures.keys
            bindingIds.forEach { id ->
                val record = authoritative.providers.values.singleOrNull { InstalledProviderId.derive(it.active.sourceRecord.repositoryId) == id }
                    ?: throw AssertionError("publisher observed $id before a committed authoritative index binding")
                val binding = materialized.bindings.getValue(id)
                assertEquals(record.active.digest.value, binding.provider.fingerprint.value)
            }
            snapshots += PublishedSnapshot(bindingIds, failureIds, materialized.bindings)
            return PackageOutcome.Success(Unit)
        }

        fun assertLast(bindings: Set<ChannelImplementationId>, failures: Set<ChannelImplementationId>) {
            val last = snapshots.lastOrNull() ?: throw AssertionError("expected a publication")
            assertEquals(bindings, last.bindingIds)
            assertEquals(failures, last.failureIds)
        }
    }

    private data class PublishedSnapshot(
        val bindingIds: Set<ChannelImplementationId>,
        val failureIds: Set<ChannelImplementationId>,
        val bindings: Map<ChannelImplementationId, InstalledProviderBinding>,
    )

    private class FailingInputStream : InputStream() {
        override fun read(): Int = throw IOException("injected staging read failure")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("injected staging read failure")
    }

    private class GatedInputStream(
        private val bytes: ByteArray,
        private val started: CompletableDeferred<Unit>,
        private val gate: CompletableDeferred<Unit>,
    ) : InputStream() {
        private var offset = 0
        private var admitted = false

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!admitted) {
                admitted = true
                started.complete(Unit)
                runBlocking { gate.await() }
            }
            if (this.offset == bytes.size) return -1
            val copied = minOf(length, bytes.size - this.offset)
            bytes.copyInto(buffer, offset, this.offset, this.offset + copied)
            this.offset += copied
            return copied
        }
    }

    private object NoopLuaKernelBridge : LuaKernelBridge {
        override fun create(config: LuaKernelConfig): LuaKernelOutcome = error("materialization must not create Lua states")
        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome = error("not used")
        override fun start(handle: LuaStateHandle): LuaKernelOutcome = error("not used")
        override fun resume(operation: LuaOperationHandle, success: Boolean, value: String, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = error("not used")
        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = error("not used")
        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = error("not used")
        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = error("not used")
        override fun close(handle: LuaStateHandle): LuaKernelOutcome = error("not used")
        override fun loadProgramImage(handle: LuaStateHandle, entryPoint: String, sourceMap: Map<String, String>): LuaKernelOutcome = error("not used")
        override fun invokeStartupCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, config: LuaValue, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = error("not used")
        override fun invokeCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, arguments: LuaValue, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = error("not used")
        override fun startCoroutine(handle: LuaStateHandle, coroutineId: LuaCoroutineId, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = error("not used")
    }

    private companion object {
        const val LOCAL_SIGNATURE = 0x04034b50L
        const val CENTRAL_SIGNATURE = 0x02014b50L
        const val EOCD_SIGNATURE = 0x06054b50L
    }
}
