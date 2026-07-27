package io.talkcan.service

import io.talkcan.channel.capability.CapabilityAvailability
import io.talkcan.channel.capability.CapabilityKey
import io.talkcan.channel.capability.ChannelCapabilityHost
import io.talkcan.channel.capability.ChannelCapabilityPort
import io.talkcan.channel.capability.HostedCapabilityAcquisition
import io.talkcan.dependency.GitHubAssetIdentity
import io.talkcan.dependency.GitHubReleaseIdentity
import io.talkcan.dependency.GitHubRepositoryCoordinates
import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.dependency.InstalledProviderId
import io.talkcan.dependency.MutationResult
import io.talkcan.dependency.PackageFailure
import io.talkcan.dependency.PackageOutcome
import io.talkcan.dependency.PackageSourceRecord
import io.talkcan.lua.API_VERSION
import io.talkcan.lua.LUA_VERSION
import io.talkcan.lua.NoOpPluginLogSink
import io.talkcan.lua.LogRecord
import io.talkcan.lua.PluginLogSink
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
import io.talkcan.model.ChannelCatalogueSnapshot
import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ChannelImplementationProviderRegistry
import io.talkcan.model.ChannelProviderError
import io.talkcan.model.ChannelProviderRegistrationResult
import io.talkcan.model.ChannelProviderResolution
import io.talkcan.model.OpaqueJsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.CRC32
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Service-owned installed-package lifecycle coverage. These tests use the coordinator's public
 * host operations with a real package store; Android service/UI construction is deliberately not
 * involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstalledPackagesServiceCompositionTest {
    @Test
    fun `start is non-blocking and publishes the complete installed snapshot before catalogue reconciliation`() = runTest {
        withTemporaryDirectory { root ->
            seedInstalledPackage(root)
            val implementationId = InstalledProviderId.derive(sourceRecord().repositoryId)
            val providers = ChannelImplementationProviderRegistry()
            val bridge = RecordingLuaKernelBridge()
            val runtimeRegistry = runtimeRegistry(providers, backgroundScope)
            val gate = GateDispatcher()
            val opaqueDefinition = ChannelDefinition(
                id = "existing-installed-channel",
                name = "Existing installed channel",
                implementationId = implementationId,
                enabled = true,
                configSchemaVersion = 1,
                configPayload = OpaqueJsonObject.parse("""{"futureProviderField":{"kept":true}}""").getOrThrow(),
            )
            val catalogue = ChannelCatalogueSnapshot(listOf(opaqueDefinition), opaqueDefinition.id)
            val reconciliationEvents = mutableListOf<String>()
            val serviceJob = SupervisorJob(coroutineContext[Job])
            val serviceScope = CoroutineScope(coroutineContext + serviceJob)
            lateinit var facade: InstalledPackagesFacade
            val coordinator = InstalledPackagesCoordinator(
                storeRoot = root,
                providerRegistry = providers,
                bridge = bridge,
                logSink = NoOpPluginLogSink,
                onCatalogueReconcile = {
                    // Publication must be committed before reconciliation observes the registry.
                    assertTrue(providers.resolve(implementationId) is ChannelProviderResolution.Available)
                    assertTrue(providers.descriptors().any { it.implementationId == implementationId })
                    assertTrue(facade.state.value is InstalledPackagesState.Loading)
                    reconciliationEvents += "published"
                    runtimeRegistry.reconcile(catalogue)
                    reconciliationEvents += "reconciled"
                },
                serviceScope = serviceScope,
                ioDispatcher = gate,
            )
            facade = InstalledPackagesFacade(coordinator)
            val transitions = mutableListOf<InstalledPackagesState>()
            val stateCollection = backgroundScope.launch {
                facade.state.collect(transitions::add)
            }
            try {
                runCurrent()

                coordinator.start()
                assertEquals(InstalledPackagesState.Loading(0L), facade.state.value)

                // The service scope reaches the I/O boundary, but the injected dispatcher has not
                // admitted package recovery. Existing catalogue definitions remain explicitly unusable.
                runCurrent()
                assertEquals(InstalledPackagesState.Loading(1L), facade.state.value)
                runtimeRegistry.reconcile(catalogue)
                val loadingProjection = requireNotNull(runtimeRegistry.getRuntimeSnapshot(opaqueDefinition.id))
                assertTrue(loadingProjection.preparation is ChannelPreparationAvailability.Unavailable)
                val loadingReason = (loadingProjection.preparation as ChannelPreparationAvailability.Unavailable).reason
                assertTrue(loadingReason is ChannelPreparationReason.Provider)
                assertTrue((loadingReason as ChannelPreparationReason.Provider).error is io.talkcan.model.ChannelProviderError.MissingProvider)
                assertTrue(providers.resolve(implementationId) is ChannelProviderResolution.Missing)
                assertTrue(providers.descriptors().none { it.implementationId == implementationId })
                assertEquals(0, bridge.createdStateIds.size)

                drainGate(gate)
                assertTrue(gate.isEmpty())

                val ready = facade.state.value as? InstalledPackagesState.Ready
                    ?: throw AssertionError("Expected installed packages to become ready, got ${facade.state.value}")
                assertEquals(1L, ready.generation)
                assertEquals(providers.snapshotRevision, ready.snapshotRevision)
                assertEquals(listOf("published", "reconciled"), reconciliationEvents)
                assertTrue(providers.resolve(implementationId) is ChannelProviderResolution.Available)
                assertTrue(providers.descriptors().any { it.implementationId == implementationId })
                assertEquals(
                    listOf(InstalledPackagesState.Loading(0L), InstalledPackagesState.Loading(1L), ready),
                    transitions,
                )
                // The opaque catalogue payload is retained by the catalogue seam; the installed Lua
                // provider rejects it at runtime rather than silently rewriting or dropping the entry.
                val reconciledProjection = requireNotNull(runtimeRegistry.getRuntimeSnapshot(opaqueDefinition.id))
                assertTrue(reconciledProjection.preparation is ChannelPreparationAvailability.Unavailable)
                assertEquals(opaqueDefinition.id, reconciledProjection.id)
                assertEquals(opaqueDefinition.implementationId, reconciledProjection.implementationId)
                assertEquals(0, bridge.createdStateIds.size)

                shutdown(coordinator, gate)
                assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(runtimeRegistry))
            } finally {
                stateCollection.cancelAndJoin()
                serviceJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun `empty store loads without entering Lua and reports a completed empty publication`() = runTest {
        withTemporaryDirectory { root ->
            val providers = ChannelImplementationProviderRegistry()
            val bridge = RecordingLuaKernelBridge()
            var reconciliations = 0
            val coordinator = InstalledPackagesCoordinator(
                storeRoot = root,
                providerRegistry = providers,
                bridge = bridge,
                logSink = NoOpPluginLogSink,
                onCatalogueReconcile = { reconciliations += 1 },
                serviceScope = this,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )
            val facade = InstalledPackagesFacade(coordinator)

            coordinator.start()
            advanceUntilIdle()

            val ready = facade.state.value as? InstalledPackagesState.Ready
                ?: throw AssertionError("Expected empty store publication to finish, got ${facade.state.value}")
            assertEquals(1L, ready.generation)
            assertEquals(providers.snapshotRevision, ready.snapshotRevision)
            assertEquals(1, reconciliations)
            assertTrue(providers.descriptors().isEmpty())
            assertEquals(0, bridge.createdStateIds.size)
            assertEquals(0, bridge.closedStateIds.size)

            shutdown(coordinator)
        }
    }

    @Test
    fun `non no-op sink receives native Lua logs from an installed runtime after install and recovery`() = runTest {
        withTemporaryDirectory { root ->
            val sink = RecordingPluginLogSink()
            try {
                val source = sourceRecord()
                val implementationId = InstalledProviderId.derive(source.repositoryId)
                val initialBridge = RecordingLuaKernelBridge().apply {
                    startupLogs = listOf("""{"level":"info","payload":{"message":"installed-after-install"}}""")
                }
                val initialProviders = ChannelImplementationProviderRegistry()
                val initialCoordinator = InstalledPackagesCoordinator(
                    storeRoot = root,
                    providerRegistry = initialProviders,
                    bridge = initialBridge,
                    logSink = sink,
                    onCatalogueReconcile = {},
                    serviceScope = this,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
                val initialRuntime = runtimeRegistry(initialProviders, backgroundScope)
                try {
                    val installed = InstalledPackagesFacade(initialCoordinator).installOrUpdate(
                        ByteArrayInputStream(packageArchive()),
                        source,
                    )
                    assertEquals(MutationResult.Installed(implementationId), success(installed))
                    val definition = definition(implementationId)
                    initialRuntime.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
                    runCurrent()
                    assertInstalledRuntimeLog(sink, "installed-after-install", "info")
                } finally {
                    assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(initialRuntime))
                    initialCoordinator.shutdown()
                }

                val recoveredBridge = RecordingLuaKernelBridge().apply {
                    startupLogs = listOf("""{"level":"warn","payload":{"message":"installed-after-recovery"}}""")
                }
                val recoveredProviders = ChannelImplementationProviderRegistry()
                val recoveredCoordinator = InstalledPackagesCoordinator(
                    storeRoot = root,
                    providerRegistry = recoveredProviders,
                    bridge = recoveredBridge,
                    logSink = sink,
                    onCatalogueReconcile = {},
                    serviceScope = this,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
                val recoveredRuntime = runtimeRegistry(recoveredProviders, backgroundScope)
                try {
                    recoveredCoordinator.start()
                    advanceUntilIdle()
                    val definition = definition(implementationId)
                    recoveredRuntime.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
                    runCurrent()
                    assertInstalledRuntimeLog(sink, "installed-after-recovery", "warn")
                } finally {
                    assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(recoveredRuntime))
                    recoveredCoordinator.shutdown()
                }
            } finally {
                sink.close()
            }
        }
    }

    @Test
    fun `mutation failure before start does not publish global fallback`() = runTest {
        withTemporaryDirectory { root ->
            val installedId = InstalledProviderId.derive(sourceRecord().repositoryId)
            val providers = ChannelImplementationProviderRegistry()
            val snapshotRevisionBeforeMutation = providers.snapshotRevision
            val descriptorsBeforeMutation = providers.descriptors()
            val coordinator = InstalledPackagesCoordinator(
                storeRoot = root,
                providerRegistry = providers,
                bridge = RecordingLuaKernelBridge(),
                logSink = NoOpPluginLogSink,
                onCatalogueReconcile = { throw AssertionError("A rejected install must not reconcile providers") },
                serviceScope = this,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )
            val facade = InstalledPackagesFacade(coordinator)

            val outcome = facade.installOrUpdate(
                ByteArrayInputStream("not a ZIP archive".toByteArray(UTF_8)),
                sourceRecord(),
            )

            val failure = outcome as? PackageOutcome.Failure
                ?: throw AssertionError("Expected invalid archive failure, got $outcome")
            assertEquals(PackageFailure.Format(PackageFailure.FormatDetail.INVALID_ZIP), failure.error)
            assertEquals(snapshotRevisionBeforeMutation, providers.snapshotRevision)
            assertEquals(descriptorsBeforeMutation, providers.descriptors())
            val resolution = providers.resolve(installedId)
            assertTrue(resolution is ChannelProviderResolution.Missing)

            shutdown(coordinator)
        }
    }

    @Test
    fun `cold corrupt index publishes a typed global fallback`() = runTest {
        withTemporaryDirectory { root ->
            File(root, "index.json").writeText("not a package index")
            File(root, "index.backup.json").writeText("also not a package index")
            val installedId = InstalledProviderId.derive(sourceRecord().repositoryId)
            val providers = ChannelImplementationProviderRegistry()
            val runtimeJob = SupervisorJob(coroutineContext[Job])
            val runtimeScope = CoroutineScope(StandardTestDispatcher(testScheduler) + runtimeJob)
            val runtimeRegistry = runtimeRegistry(providers, runtimeScope)
            val installedDefinition = ChannelDefinition(
                id = "installed-index-corruption",
                name = "Installed index corruption",
                implementationId = installedId,
                enabled = true,
                configSchemaVersion = 1,
                configPayload = OpaqueJsonObject.parse("{}").getOrThrow(),
            )
            val catalogue = ChannelCatalogueSnapshot(
                listOf(installedDefinition),
                installedDefinition.id,
            )
            var reconciliations = 0
            val coordinator = InstalledPackagesCoordinator(
                storeRoot = root,
                providerRegistry = providers,
                bridge = RecordingLuaKernelBridge(),
                logSink = NoOpPluginLogSink,
                onCatalogueReconcile = {
                    reconciliations += 1
                    runtimeRegistry.reconcile(catalogue)
                },
                serviceScope = this,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )
            val facade = InstalledPackagesFacade(coordinator)

            coordinator.start()
            advanceUntilIdle()

            val failed = facade.state.value as? InstalledPackagesState.Failed
                ?: throw AssertionError("Expected corrupt store failure, got ${facade.state.value}")
            assertEquals(1L, failed.generation)
            assertEquals(
                PackageFailure.Recovery(PackageFailure.RecoveryDetail.INDEX_CORRUPT),
                failed.error,
            )
            assertEquals(1, reconciliations)
            val resolution = providers.resolve(installedId) as? ChannelProviderResolution.Unavailable
                ?: throw AssertionError("Expected typed installed fallback, got ${providers.resolve(installedId)}")
            assertEquals(
                ChannelProviderError.PackageUnavailableCategory.RECOVERY,
                resolution.error.category,
            )
            assertEquals(
                ChannelProviderError.PackageUnavailableDetail.INDEX_CORRUPT,
                resolution.error.detail,
            )
            val installedPreparation = runtimeRegistry.preparation(installedDefinition.id)
                as? ChannelPreparationAvailability.Unavailable
                ?: throw AssertionError("Expected unavailable installed runtime, got ${runtimeRegistry.preparation(installedDefinition.id)}")
            val preparationError = (installedPreparation.reason as? ChannelPreparationReason.Provider)?.error
                as? ChannelProviderError.PackageUnavailable
                ?: throw AssertionError("Expected typed package-unavailable preparation, got ${installedPreparation.reason}")
            assertEquals(resolution.error, preparationError)

            shutdown(coordinator)
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(runtimeRegistry))
            runtimeJob.cancelAndJoin()
        }
    }

    @Test
    fun `corrupt reload removes the published runtime and a later valid index restores it`() = runTest {
        withTemporaryDirectory { root ->
            seedInstalledPackage(root)
            val installedId = InstalledProviderId.derive(sourceRecord().repositoryId)
            val validIndex = File(root, "index.json").readBytes()
            val providers = ChannelImplementationProviderRegistry()
            val bridge = RecordingLuaKernelBridge()
            val runtimeJob = SupervisorJob(coroutineContext[Job])
            val runtimeScope = CoroutineScope(StandardTestDispatcher(testScheduler) + runtimeJob)
            val runtimeRegistry = runtimeRegistry(providers, runtimeScope)
            val installedDefinition = ChannelDefinition(
                id = "installed-reload-corruption",
                name = "Installed reload corruption",
                implementationId = installedId,
                enabled = true,
                configSchemaVersion = 1,
                configPayload = OpaqueJsonObject.parse("{}").getOrThrow(),
            )
            val catalogue = ChannelCatalogueSnapshot(listOf(installedDefinition), installedDefinition.id)
            var reconciliations = 0
            val coordinator = InstalledPackagesCoordinator(
                storeRoot = root,
                providerRegistry = providers,
                bridge = bridge,
                logSink = NoOpPluginLogSink,
                onCatalogueReconcile = {
                    reconciliations += 1
                    runtimeRegistry.reconcile(catalogue)
                },
                serviceScope = this,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )
            val facade = InstalledPackagesFacade(coordinator)

            coordinator.start()
            runCurrent()
            val ready = facade.state.value as? InstalledPackagesState.Ready
                ?: throw AssertionError("Expected initial installed publication to be ready, got ${facade.state.value}")
            assertEquals(providers.snapshotRevision, ready.snapshotRevision)
            assertTrue(providers.resolve(installedId) is ChannelProviderResolution.Available)
            assertEquals(ChannelPreparationAvailability.Available, runtimeRegistry.preparation(installedDefinition.id))
            assertEquals(listOf(1L), bridge.createdStateIds)

            File(root, "index.json").writeText("corrupt current index")
            File(root, "index.backup.json").writeText("corrupt backup index")
            val corruptReload = async { facade.reload() }
            runCurrent()
            assertTrue(corruptReload.isCompleted)
            corruptReload.await()

            val failed = facade.state.value as? InstalledPackagesState.Failed
                ?: throw AssertionError("Expected corrupt reload failure, got ${facade.state.value}")
            assertEquals(2L, failed.generation)
            assertEquals(
                PackageFailure.Recovery(PackageFailure.RecoveryDetail.INDEX_CORRUPT),
                failed.error,
            )
            assertEquals(2, reconciliations)
            val unavailable = providers.resolve(installedId) as? ChannelProviderResolution.Unavailable
                ?: throw AssertionError("Expected corrupt reload fallback, got ${providers.resolve(installedId)}")
            assertEquals(ChannelProviderError.PackageUnavailableCategory.RECOVERY, unavailable.error.category)
            assertEquals(ChannelProviderError.PackageUnavailableDetail.INDEX_CORRUPT, unavailable.error.detail)
            val fallbackPreparation = runtimeRegistry.preparation(installedDefinition.id)
                as? ChannelPreparationAvailability.Unavailable
                ?: throw AssertionError("Expected fallback runtime projection, got ${runtimeRegistry.preparation(installedDefinition.id)}")
            assertEquals(unavailable.error, (fallbackPreparation.reason as ChannelPreparationReason.Provider).error)
            assertEquals(listOf(1L), bridge.closedStateIds)

            File(root, "index.json").writeBytes(validIndex)
            File(root, "index.backup.json").delete()
            val recoveredReload = async { facade.reload() }
            runCurrent()
            assertTrue(recoveredReload.isCompleted)
            recoveredReload.await()

            assertTrue(facade.state.value is InstalledPackagesState.Ready)
            assertEquals(3, reconciliations)
            assertTrue(providers.resolve(installedId) is ChannelProviderResolution.Available)
            assertEquals(ChannelPreparationAvailability.Available, runtimeRegistry.preparation(installedDefinition.id))
            assertEquals(listOf(1L, 2L), bridge.createdStateIds)

            shutdown(coordinator)
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(runtimeRegistry))
            runtimeJob.cancelAndJoin()
        }
    }

    @Test
    fun `shutdown winning the gated publication boundary leaves no late snapshot reconciliation or runtime`() = runTest {
        withTemporaryDirectory { root ->
            seedInstalledPackage(root)
            val implementationId = InstalledProviderId.derive(sourceRecord().repositoryId)
            val providers = ChannelImplementationProviderRegistry()
            val bridge = RecordingLuaKernelBridge()
            val runtimeRegistry = runtimeRegistry(providers, backgroundScope)
            val definition = ChannelDefinition(
                id = "shutdown-publication-race",
                name = "Shutdown publication race",
                implementationId = implementationId,
                enabled = true,
                configSchemaVersion = 1,
                configPayload = OpaqueJsonObject.parse("{}").getOrThrow(),
            )
            val catalogue = ChannelCatalogueSnapshot(listOf(definition), definition.id)
            val gate = GateDispatcher()
            var reconciliations = 0
            val coordinator = InstalledPackagesCoordinator(
                storeRoot = root,
                providerRegistry = providers,
                bridge = bridge,
                logSink = NoOpPluginLogSink,
                onCatalogueReconcile = {
                    reconciliations += 1
                    runtimeRegistry.reconcile(catalogue)
                },
                serviceScope = this,
                ioDispatcher = gate,
            )
            val facade = InstalledPackagesFacade(coordinator)

            coordinator.start()
            runCurrent()
            assertEquals(InstalledPackagesState.Loading(1L), facade.state.value)

            shutdown(coordinator, gate)

            assertTrue(facade.state.value is InstalledPackagesState.Closed)
            assertEquals(0L, providers.snapshotRevision)
            assertEquals(0, reconciliations)
            assertTrue(providers.resolve(implementationId) is ChannelProviderResolution.Missing)
            assertTrue(providers.descriptors().none { it.implementationId == implementationId })
            assertEquals(0, bridge.createdStateIds.size)
            assertEquals(null, runtimeRegistry.getRuntimeSnapshot(definition.id))
            facade.reload()
            assertTrue(facade.state.value is InstalledPackagesState.Closed)

            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(runtimeRegistry))
        }
    }

    private suspend fun TestScope.seedInstalledPackage(root: File) {
        val providers = ChannelImplementationProviderRegistry()
        val coordinator = InstalledPackagesCoordinator(
            storeRoot = root,
            providerRegistry = providers,
            bridge = RecordingLuaKernelBridge(),
            logSink = NoOpPluginLogSink,
            onCatalogueReconcile = {},
            serviceScope = this,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val installed = InstalledPackagesFacade(coordinator).installOrUpdate(
            ByteArrayInputStream(packageArchive()),
            sourceRecord(),
        )
        assertEquals(MutationResult.Installed(InstalledProviderId.derive(sourceRecord().repositoryId)), success(installed))
        coordinator.shutdown()
    }

    private fun TestScope.runtimeRegistry(
        providers: ChannelImplementationProviderRegistry,
        runtimeScope: CoroutineScope,
    ): ChannelRuntimeRegistry =
        ChannelRuntimeRegistry(
            providers = providers,
            capabilityHost = AvailableButUnimplementedCapabilities,
            invocationBoundary = RuntimeInvocationBoundary(
                RuntimeWorkerDispatcher.fromDispatcher(StandardTestDispatcher(testScheduler)),
            ),
            runtimeScope = runtimeScope,
            closeScope = backgroundScope,
            shutdownAwaitMillis = 2_000,
        )

    private suspend fun TestScope.shutdown(coordinator: InstalledPackagesCoordinator) {
        coordinator.shutdown()
    }

    private suspend fun TestScope.shutdown(coordinator: InstalledPackagesCoordinator, gate: GateDispatcher) {
        val closing = async(start = CoroutineStart.UNDISPATCHED) { coordinator.shutdown() }
        drainGate(gate)
        assertTrue(closing.isCompleted)
        closing.await()
    }

    private suspend fun TestScope.shutdown(registry: ChannelRuntimeRegistry): ChannelRuntimeRegistryShutdownResult =
        registry.shutdownAndAwait()

    private fun TestScope.drainGate(gate: GateDispatcher) {
        do {
            runCurrent()
            val released = gate.releaseAll()
            runCurrent()
        } while (released > 0 || !gate.isEmpty())
    }

    private fun <T> success(outcome: PackageOutcome<T>): T = when (outcome) {
        is PackageOutcome.Success -> outcome.value
        is PackageOutcome.Failure -> throw AssertionError("Expected package operation success, got ${outcome.error}")
    }

    private fun assertInstalledRuntimeLog(sink: RecordingPluginLogSink, marker: String, level: String) {
        val record = if (sink.records.isEmpty()) {
            throw AssertionError("Installed runtime did not publish its native Lua log")
        } else {
            sink.records.removeFirst()
        }
        assertEquals(level, record.level)
        assertTrue("sink received the wrong installed runtime payload: ${record.payloadJson}", record.payloadJson.contains("\"message\":\"$marker\""))
    }

    private suspend fun <T> withTemporaryDirectory(block: suspend (File) -> T): T {
        val directory = createTempDirectory("installed-packages-service-composition-").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun sourceRecord(): PackageSourceRecord = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity("123"),
        coordinates = GitHubRepositoryCoordinates("service-test-owner", "service-test-repository"),
        release = GitHubReleaseIdentity("456", "v1", false),
        asset = GitHubAssetIdentity("789", "service-test-package.zip"),
        ownerId = "9000001",
    )

    private fun definition(implementationId: io.talkcan.model.ChannelImplementationId): ChannelDefinition = ChannelDefinition(
        id = "installed-log-propagation",
        name = "Installed log propagation",
        implementationId = implementationId,
        enabled = true,
        configSchemaVersion = 1,
        configPayload = OpaqueJsonObject.parse("{}").getOrThrow(),
    )

    private fun packageArchive(): ByteArray {
        val source = "return { startup = function() end, handle_readiness = function() return { ready = true } end }"
        val manifest = """{"manifestVersion":1,"repositoryId":"123","packageVersion":"1.0.0","entryModule":"plugin","presentation":{"label":"Service test package","summary":"Installed Lua service composition package"},"runtime":{"luaVersion":"$LUA_VERSION","apiVersion":"$API_VERSION"},"configuration":{"schemaVersion":1,"data":{"fields":[],"additionalProperties":false},"ui":{"fields":[]}},"resources":{"mounts":[]},"profileTypes":[],"choiceResolvers":[],"workQueues":[],"capabilities":[]}"""
        return strictUnixStoredZip(
            listOf(
                ZipFixtureEntry("manifest.json", manifest.toByteArray(UTF_8), 0b1000000110100100),
                ZipFixtureEntry("lua/", ByteArray(0), 0b0100000111101101),
                ZipFixtureEntry("lua/plugin.lua", source.toByteArray(UTF_8), 0b1000000110100100),
            ),
        )
    }

    /** Raw ZIP fixture with matching local/central metadata and Unix mode bits. */
    private fun strictUnixStoredZip(entries: List<ZipFixtureEntry>): ByteArray {
        val output = ByteArrayOutputStream()
        val centralEntries = ArrayList<CentralFixtureEntry>(entries.size)
        entries.forEach { entry ->
            val name = entry.name.toByteArray(UTF_8)
            val crc = CRC32().apply { update(entry.bytes) }.value
            val offset = output.size().toLong()
            output.u32(0x04034b50)
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
            centralEntries += CentralFixtureEntry(entry, name, crc, offset)
        }
        val centralOffset = output.size().toLong()
        centralEntries.forEach { central ->
            output.u32(0x02014b50)
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
        output.u32(0x06054b50)
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

    private data class ZipFixtureEntry(val name: String, val bytes: ByteArray, val unixMode: Int)
    private data class CentralFixtureEntry(val entry: ZipFixtureEntry, val name: ByteArray, val crc: Long, val localOffset: Long)

    private class GateDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }

        fun releaseAll(): Int {
            var released = 0
            while (queued.isNotEmpty()) {
                queued.removeFirst().run()
                released += 1
            }
            return released
        }

        fun isEmpty(): Boolean = queued.isEmpty()
    }

    private object AvailableButUnimplementedCapabilities : ChannelCapabilityHost {
        override suspend fun availability(
            identity: io.talkcan.channel.capability.CapabilityScopeIdentity,
            key: CapabilityKey<*>,
        ): CapabilityAvailability = CapabilityAvailability.Available

        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: io.talkcan.channel.capability.CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> = HostedCapabilityAcquisition.Unavailable(
            io.talkcan.channel.capability.CapabilityUnavailableReason.UNSUPPORTED,
        )

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: io.talkcan.channel.capability.CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = acquire(identity, key)
    }

    private class RecordingPluginLogSink : PluginLogSink {
        val records = ArrayDeque<PublishedPluginLog>()

        override fun tryPublish(
            instanceId: String,
            generation: Long,
            timestampMillis: Long,
            level: String,
            payloadJson: String,
        ): Boolean {
            records.addLast(PublishedPluginLog(instanceId, generation, timestampMillis, level, payloadJson))
            return true
        }

        override fun close() = Unit

        override fun recordProjectionLoss() = Unit

        override fun getLossCount(): Long = 0L

        override suspend fun receive(): LogRecord? = null
    }

    private data class PublishedPluginLog(
        val instanceId: String,
        val generation: Long,
        val timestampMillis: Long,
        val level: String,
        val payloadJson: String,
    )

    private class RecordingLuaKernelBridge : LuaKernelBridge {
        private val states = mutableMapOf<Long, Boolean>()
        private var nextStateId = 1L
        val createdStateIds = mutableListOf<Long>()
        val closedStateIds = mutableListOf<Long>()
        var startupLogs: List<String> = emptyList()

        override fun create(config: LuaKernelConfig): LuaKernelOutcome {
            val id = nextStateId++
            states[id] = false
            createdStateIds += id
            return LuaKernelOutcome.Created(id, id, LUA_VERSION, API_VERSION, "service-composition")
        }

        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome = completed(handle)
        override fun setResourceContext(
            handle: LuaStateHandle,
            resourceContextJson: String,
        ): LuaKernelOutcome = completed(handle)
        override fun start(handle: LuaStateHandle): LuaKernelOutcome = completed(handle)
        override fun resume(operation: LuaOperationHandle, success: Boolean, value: String, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = completed(operation.stateHandle)
        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = completed(operation.stateHandle)
        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = completed(handle)

        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = LuaKernelOutcome.Snapshot(
            handle.stateId.value,
            handle.generation.value,
            null,
            LUA_VERSION,
            API_VERSION,
            "service-composition",
        )

        override fun close(handle: LuaStateHandle): LuaKernelOutcome {
            if (states[handle.stateId.value] != true) {
                states[handle.stateId.value] = true
                closedStateIds += handle.stateId.value
            }
            return LuaKernelOutcome.Closed(handle.stateId.value, handle.generation.value)
        }

        override fun loadProgramImage(handle: LuaStateHandle, entryPoint: String, sourceMap: Map<String, String>): LuaKernelOutcome =
            completed(handle, "[\"startup\",\"handle_readiness\"]")

        override fun invokeStartupCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, config: LuaValue, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = completed(handle, logs = startupLogs)

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = completed(handle, if (callbackHandle.name == "handle_readiness") "{\"ready\":true}" else null)

        override fun startCoroutine(handle: LuaStateHandle, coroutineId: LuaCoroutineId, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = completed(handle)

        private fun completed(handle: LuaStateHandle, value: String? = null, logs: List<String>? = null): LuaKernelOutcome.Completed = LuaKernelOutcome.Completed(
            stateId = handle.stateId.value,
            generation = handle.generation.value,
            coroutineId = null,
            value = value,
            elapsedNanos = null,
            luaVersion = LUA_VERSION,
            bindingVersion = API_VERSION,
            topology = "service-composition",
            spawnedCoroutines = null,
            logs = logs,
        )
    }
}
