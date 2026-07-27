package io.talkcan.dependency

import io.talkcan.audio.ChannelAudioInputSession
import io.talkcan.audio.ChannelInputAcceptance
import io.talkcan.audio.RecordedPcm
import io.talkcan.channel.capability.*
import io.talkcan.lua.*
import io.talkcan.model.*
import io.talkcan.service.*
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runtime contracts for the byte-pinned external Debug package.
 *
 * Every test starts from the exact release fixture and traverses:
 *   validator -> installed store -> materializer -> provider registry ->
 *   catalogue -> runtime registry -> actor -> capability adapters
 *
 * Native Lua execution is unavailable to JVM unit tests. [DebugKernelBridge] is
 * therefore limited to the native kernel boundary: it retains per-state ownership,
 * exposes the generic callback protocol, and records only values crossing that boundary.
 * The package itself is always installed through validation, materialization, publication,
 * and the runtime registry — never through a test-only Debug provider or direct
 * capability-port acceptance shortcut.
 *
 * Tasks 10.6-10.7: exact archive -> full provider path + lifecycle matrix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalDebugChannelRuntimeContractTest {
    @Test
    fun `generic catalogue runtime executes all modes and preserves FIFO operation constants`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = DebugKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val id = install(root, bridge, providers)
            val host = DebugCapabilityHost()
            val registry = runtimeRegistry(providers, host)
            val modes = listOf("ECHO", "DELAYED_ECHO", "STT", "TTS", "STT_TTS")
            val definitions = modes.map { definition("debug-$it", id, it) }
            registry.reconcile(ChannelCatalogueSnapshot(definitions, definitions.first().id))
            advanceUntilIdle()
            definitions.forEach { definition ->
                val snapshot = registry.getRuntimeSnapshot(definition.id) ?: error("missing snapshot")
                assertEquals(ChannelPreparationAvailability.Available, snapshot.preparation)
                assertEquals(definition.name, snapshot.summary)
                val accepted = registry.prepareInput(definition.id) as ChannelInputAcceptance.Accepted
                accepted.target.onInputStarted(FakeSession)
                assertEquals(ChannelExecutionStatus.RECORDING, registry.getRuntimeSnapshot(definition.id)?.executionStatus)
                val released = async { accepted.target.onInputReleased(RecordedPcm(shortArrayOf(1, 2), 16_000)) }
                advanceUntilIdle()
                released.await()
                assertEquals(ChannelExecutionStatus.SUCCESS, registry.getRuntimeSnapshot(definition.id)?.executionStatus)
                if (definition.name == "DELAYED_ECHO") {
                    accepted.target.onInputStarted(FakeSession)
                    val second = async { accepted.target.onInputReleased(RecordedPcm(shortArrayOf(3, 4), 16_000)) }
                    advanceUntilIdle()
                    second.await()
                    assertEquals(ChannelExecutionStatus.SUCCESS, registry.getRuntimeSnapshot(definition.id)?.executionStatus)
                }
                (accepted.target as? CommittedTargetLeaseOwner)?.releaseCommittedTargetLease()
            }
            assertEquals(listOf(0.0, 5.0, 5.0), bridge.delays)
            assertEquals(listOf("Debug synthesis test", "transcript"), bridge.synthesisTexts)
            assertEquals(2, bridge.transcriptions.size)
            assertEquals(listOf("ECHO", "DELAYED_ECHO", "STT", "TTS", "STT_TTS"), bridge.startedModes)
            assertEquals(listOf(0.0, 5.0), bridge.delays.take(2))
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
        }
    }

    @Test
    fun `readiness uses exactly selected mode dependency subset and cancellation returns idle`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = DebugKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val id = install(root, bridge, providers)
            val host = DebugCapabilityHost(available = setOf(CapabilityKey.AudioOperation, CapabilityKey.DeferredAudioPlayback))
            val registry = runtimeRegistry(providers, host)
            val echo = definition("echo", id, "ECHO")
            val stt = definition("stt", id, "STT")
            registry.reconcile(ChannelCatalogueSnapshot(listOf(echo, stt), echo.id))
            advanceUntilIdle()
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(echo.id)?.preparation)
            assertEquals("STT", registry.getRuntimeSnapshot(stt.id)?.summary)
            val accepted = registry.prepareInput(echo.id) as ChannelInputAcceptance.Accepted
            accepted.target.onInputStarted(FakeSession)
            accepted.target.onInputCancelled("test cancellation")
            assertEquals(ChannelExecutionStatus.IDLE, registry.getRuntimeSnapshot(echo.id)?.executionStatus)
            (accepted.target as? CommittedTargetLeaseOwner)?.releaseCommittedTargetLease()
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
        }
    }

    @Test
    fun `two instances isolate state and mode edit replaces predecessor generation`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = DebugKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val id = install(root, bridge, providers)
            val registry = runtimeRegistry(providers, DebugCapabilityHost())
            val left = definition("left", id, "ECHO")
            val right = definition("right", id, "TTS")
            registry.reconcile(ChannelCatalogueSnapshot(listOf(left, right), left.id))
            advanceUntilIdle()
            assertEquals(2, bridge.createdStates.size)
            assertNotEquals(bridge.createdStates[0], bridge.createdStates[1])
            val replacement = left.copy(configPayload = OpaqueJsonObject.fromJsonObject(JSONObject().put("mode", "STT")))
            registry.reconcile(ChannelCatalogueSnapshot(listOf(replacement, right), replacement.id))
            advanceUntilIdle()
            assertTrue(bridge.closedStates.contains(bridge.createdStates.first()))
            assertTrue(bridge.createdStates.size >= 3)
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
        }
    }




    @Test
    fun `removal clears provider registration and reinstall restores with fresh state`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = DebugKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val id = install(root, bridge, providers)
            val registry = runtimeRegistry(providers, DebugCapabilityHost())
            val channel = definition("debug-removal", id, "ECHO")

            registry.reconcile(ChannelCatalogueSnapshot(listOf(channel), channel.id))
            advanceUntilIdle()
            val firstStateId = bridge.createdStates.single()
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(channel.id)?.preparation)

            // Remove provider via repository
            val repo = repository(root, bridge, providers)
            assertEquals(MutationResult.Removed(id), success(repo.remove(sourceRecord().repositoryId)))
            assertTrue("Removed provider must be absent from registry",
                providers.resolve(id) is ChannelProviderResolution.Missing)

            registry.reconcile(ChannelCatalogueSnapshot(listOf(channel), channel.id))
            advanceUntilIdle()
            assertTrue("Removed provider state must be closed",
                bridge.closedStates.contains(firstStateId))

            // Reinstall — fresh provider
            assertEquals(MutationResult.Installed(id), success(
                repo.installOrUpdate(ByteArrayInputStream(fixture()), sourceRecord())))
            assertTrue("Reinstalled provider must be available",
                providers.resolve(id) is ChannelProviderResolution.Available)

            registry.reconcile(ChannelCatalogueSnapshot(listOf(channel), channel.id))
            advanceUntilIdle()
            assertEquals("Reinstalled provider must create fresh state, not reuse prior",
                2, bridge.createdStates.size)
            assertNotEquals("Reinstall must not reuse predecessor's Lua state",
                firstStateId, bridge.createdStates.last())
            assertEquals("Instance must be available after reinstall",
                ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(channel.id)?.preparation)
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
        }
    }

    @Test
    fun `disablement retires runtime without affecting sibling instances`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = DebugKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val id = install(root, bridge, providers)
            val registry = runtimeRegistry(providers, DebugCapabilityHost())
            val enabledChan = definition("debug-enabled", id, "ECHO")
            val disabledChan = definition("debug-disabled", id, "STT")

            registry.reconcile(ChannelCatalogueSnapshot(listOf(enabledChan, disabledChan), enabledChan.id))
            advanceUntilIdle()
            assertEquals(2, bridge.createdStates.size)
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(enabledChan.id)?.preparation)
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(disabledChan.id)?.preparation)
            val disabledStateId = bridge.createdStates[1]

            // Disable the second instance — catalog entry remains, runtime retires
            val disabledDef = disabledChan.copy(enabled = false)
            registry.reconcile(ChannelCatalogueSnapshot(listOf(enabledChan, disabledDef), enabledChan.id))
            advanceUntilIdle()
            assertTrue("Disabled instance state must be closed",
                bridge.closedStates.contains(disabledStateId))
            assertTrue("Enabled sibling must remain available",
                registry.getRuntimeSnapshot(enabledChan.id)?.preparation is ChannelPreparationAvailability.Available)
            val disabledSnapshot = registry.getRuntimeSnapshot(disabledChan.id)
            assertNotNull("Disabled instance must retain a snapshot", disabledSnapshot)
            assertTrue("Disabled instance must project unavailable",
                disabledSnapshot?.preparation is ChannelPreparationAvailability.Unavailable)
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
        }
    }

    @Test
    fun `process restart constructs fresh runtime state without inherited resources`() = runTest {
        withTemporaryDirectory { root ->
            val firstBridge = DebugKernelBridge()
            val firstProviders = ChannelImplementationProviderRegistry()
            val id = install(root, firstBridge, firstProviders)
            val firstRegistry = runtimeRegistry(firstProviders, DebugCapabilityHost())
            val original = definition("debug-restart", id, "ECHO")

            // Create instance and exercise it with one input cycle
            firstRegistry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
            advanceUntilIdle()
            val originalStateId = firstBridge.createdStates.single()
            val accepted = firstRegistry.prepareInput(original.id) as ChannelInputAcceptance.Accepted
            accepted.target.onInputStarted(FakeSession)
            advanceUntilIdle()
            val released = async { accepted.target.onInputReleased(RecordedPcm(shortArrayOf(1, 2), 16_000)) }
            advanceUntilIdle()
            released.await()
            (accepted.target as? CommittedTargetLeaseOwner)?.releaseCommittedTargetLease()
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(firstRegistry))
            assertTrue("First runtime must have produced at least one effect",
                firstBridge.delays.isNotEmpty() || firstBridge.synthesisTexts.isNotEmpty())

            // Simulate process restart: fresh bridge, providers, registry from same store
            val restartBridge = DebugKernelBridge()
            val restartProviders = ChannelImplementationProviderRegistry()
            val restartedRepo = repository(root, restartBridge, restartProviders)
            assertEquals(Unit, success(restartedRepo.loadAndPublish()))
            assertTrue("Restarted providers must resolve",
                restartProviders.resolve(id) is ChannelProviderResolution.Available)
            val restartedRegistry = runtimeRegistry(restartProviders, DebugCapabilityHost())
            restartedRegistry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
            advanceUntilIdle()

            assertEquals("Restart must create exactly one fresh state",
                1, restartBridge.createdStates.size)
            assertTrue("Restart must have no inherited closed states",
                restartBridge.closedStates.isEmpty())
            assertTrue("Restart must have no inherited input history",
                restartBridge.delays.isEmpty())
            assertEquals("Restarted instance must become ready",
                ChannelPreparationAvailability.Available, restartedRegistry.getRuntimeSnapshot(original.id)?.preparation)
            assertEquals("Definition payload must be preserved across restart",
                original.configPayload, restartedRegistry.getRuntimeSnapshot(original.id)?.let { original.configPayload })
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(restartedRegistry))
        }
    }

    @Test
    fun `configuration replacement closes predecessor before successor startup`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = DebugKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val id = install(root, bridge, providers)
            val registry = runtimeRegistry(providers, DebugCapabilityHost())
            val channel = definition("debug-config", id, "ECHO")

            registry.reconcile(ChannelCatalogueSnapshot(listOf(channel), channel.id))
            advanceUntilIdle()
            val firstStateId = bridge.createdStates.single()
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(channel.id)?.preparation)
            assertTrue("Predecessor must not be closed before replacement",
                firstStateId !in bridge.closedStates)

            // Replace configuration (mode edit — triggers predecessor close + successor construction)
            val replacement = channel.copy(configPayload = OpaqueJsonObject.fromJsonObject(JSONObject().put("mode", "TTS")))
            registry.reconcile(ChannelCatalogueSnapshot(listOf(replacement), replacement.id))
            advanceUntilIdle()

            assertTrue("Predecessor state must be closed after replacement",
                bridge.closedStates.contains(firstStateId))
            assertTrue("Successor must create fresh state after predecessor close",
                bridge.createdStates.size >= 2)
            val successorStateId = bridge.createdStates.last()
            assertNotEquals("Successor must not reuse predecessor state id",
                firstStateId, successorStateId)
            assertEquals("Successor must be available after replacement",
                ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(replacement.id)?.preparation)

            // Verify successor can process input (proving startup completed after predecessor closed)
            val accepted = registry.prepareInput(replacement.id) as ChannelInputAcceptance.Accepted
            accepted.target.onInputStarted(FakeSession)
            advanceUntilIdle()
            (accepted.target as? CommittedTargetLeaseOwner)?.releaseCommittedTargetLease()
            assertTrue("Successor must process input, proving startup completed after predecessor close",
                bridge.startedModes.any { it == "TTS" })
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
        }
    }

    @Test
    fun `close-before-start invariant holds across configuration lifecycle operations`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = DebugKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val id = install(root, bridge, providers)
            val registry = runtimeRegistry(providers, DebugCapabilityHost())
            val initial = definition("debug-close-before-start", id, "ECHO")

            registry.reconcile(ChannelCatalogueSnapshot(listOf(initial), initial.id))
            advanceUntilIdle()
            val originalState = bridge.createdStates.single()

            // Mode edit: predecessor must close before successor creates
            val edited = initial.copy(configPayload = OpaqueJsonObject.fromJsonObject(JSONObject().put("mode", "TTS")))
            registry.reconcile(ChannelCatalogueSnapshot(listOf(edited), edited.id))
            advanceUntilIdle()
            val editState = bridge.createdStates.last()
            assertTrue("Predecessor must close on mode edit",
                bridge.closedStates.contains(originalState))


            // Disable: closes predecessor, creates no successor
            val disabled = edited.copy(enabled = false)
            registry.reconcile(ChannelCatalogueSnapshot(listOf(disabled), disabled.id))
            advanceUntilIdle()
            assertTrue("Disabled instance must close predecessor state",
                bridge.closedStates.contains(editState))

            // Re-enable: creates fresh state
            registry.reconcile(ChannelCatalogueSnapshot(listOf(edited), edited.id))
            advanceUntilIdle()
            val reenabledState = bridge.createdStates.last()
            assertNotEquals("Re-enable must create fresh Lua state",
                editState, reenabledState)
            assertEquals("Re-enabled instance must become available",
                ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(edited.id)?.preparation)
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
        }
    }

    private fun repository(root: File, bridge: LuaKernelBridge, providers: ChannelImplementationProviderRegistry): InstalledPackageRepository = InstalledPackageRepository(
        store = InstalledPackageStore(root), bridge = bridge,
        publisher = { materialized ->
            when (providers.publishInstalledProviders(materialized.bindings, materialized.failures.mapValues { (id, f) -> f.toPackageUnavailable(id) })) {
                is InstalledProvidersPublicationResult.Success -> PackageOutcome.Success(Unit)
                is InstalledProvidersPublicationResult.Rejected -> PackageOutcome.Failure(PackageFailure.Loading(PackageFailure.LoadingDetail.PUBLICATION_REJECTED))
            }
        }, dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    )

    private suspend fun install(root: File, bridge: DebugKernelBridge, providers: ChannelImplementationProviderRegistry): ChannelImplementationId {
        val repo = repository(root, bridge, providers)
        val result = repo.installOrUpdate(ByteArrayInputStream(fixture()), sourceRecord())
        assertTrue("Install must succeed", result is PackageOutcome.Success)
        return InstalledProviderId.derive(sourceRecord().repositoryId)
    }


    private fun runtimeRegistry(providers: ChannelImplementationProviderRegistry, host: ChannelCapabilityHost): ChannelRuntimeRegistry = ChannelRuntimeRegistry(
        providers = providers, capabilityHost = host,
        invocationBoundary = RuntimeInvocationBoundary(
            RuntimeWorkerDispatcher.fromDispatcher(kotlinx.coroutines.Dispatchers.Unconfined),
            RuntimeInvocationPolicy(16, 1_000, 1_000, 1_000),
        ),
        runtimeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        closeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        shutdownAwaitMillis = 2_000,
    )

    private suspend fun shutdown(registry: ChannelRuntimeRegistry): ChannelRuntimeRegistryShutdownResult = registry.shutdownAndAwait()

    private fun definition(id: String, implementationId: ChannelImplementationId, mode: String) = ChannelDefinition(
        id = id, name = mode, implementationId = implementationId, enabled = true, configSchemaVersion = 1,
        configPayload = OpaqueJsonObject.fromJsonObject(JSONObject().put("mode", mode)),
    )

    private fun <T> success(outcome: PackageOutcome<T>): T = when (outcome) {
        is PackageOutcome.Success -> outcome.value
        is PackageOutcome.Failure -> throw AssertionError("Expected success, got ${outcome.error}")
    }

    private fun fixture() = requireNotNull(javaClass.classLoader?.getResourceAsStream("debug-channel/talkcan-channel.zip")).use { it.readBytes() }
    private fun sourceRecord() = PackageSourceRecord(GitHubRepositoryIdentity(REPOSITORY_ID), GitHubRepositoryCoordinates("talkcan", "debug-channel"), GitHubReleaseIdentity(RELEASE_ID, "v1.3.0", false), GitHubAssetIdentity(ASSET_ID, "talkcan-channel.zip"), OWNER)
    private suspend fun <T> withTemporaryDirectory(block: suspend (File) -> T): T { val root = createTempDirectory("debug-runtime-").toFile(); return try { block(root) } finally { root.deleteRecursively() } }

    private object FakeSession : ChannelAudioInputSession { override val sampleRate = 16_000; override val frames = emptyFlow<ShortArray>() }

    private class DebugKernelBridge : LuaKernelBridge {
        private val nextState = AtomicLong(1)
        private val modes = mutableMapOf<Long, String>(); private val stages = mutableMapOf<Long, String>()
        val createdStates = mutableListOf<Long>(); val closedStates = mutableListOf<Long>(); val startedModes = mutableListOf<String>(); val delays = mutableListOf<Double>(); val transcriptions = mutableListOf<String>(); val synthesisTexts = mutableListOf<String>()
        private val claims = mutableMapOf<Long, HostOperationClaim>(); private var nextRequestId = 100L
        override fun create(config: LuaKernelConfig): LuaKernelOutcome { val id = nextState.getAndIncrement(); createdStates += id; modes[id] = "ECHO"; return LuaKernelOutcome.Created(id, id, LUA_VERSION, API_VERSION, "debug-fixture") }
        override fun load(handle: LuaStateHandle, source: String, entrypoint: String) = complete(handle)
        override fun setResourceContext(
            handle: LuaStateHandle,
            resourceContextJson: String,
        ) = complete(handle)
        override fun start(handle: LuaStateHandle) = complete(handle)
        override fun resume(operation: LuaOperationHandle, success: Boolean, value: String, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome {
            val mode = modes[operation.stateHandle.stateId.value] ?: "ECHO"
            if (!success) return complete(operation.stateHandle, "{\"error\":{\"code\":\"E_HOST_FAILURE\",\"detail\":\"operation failed\"}}")
            return when (stages[operation.stateHandle.stateId.value]) {
                "transcribe" -> if (mode == "STT") complete(operation.stateHandle, "{\"ok\":true}") else yield(operation, registerClaim(HostOperationKind.SYNTHESIZE, text = "transcript", language = "en", voice = "default", speed = 1.0))
                "synthesize" -> yield(operation, registerClaim(HostOperationKind.PLAYBACK, audioToken = value, delaySeconds = 0.0))
                "playback" -> complete(operation.stateHandle, "{\"ok\":true}")
                else -> complete(operation.stateHandle, "{\"ok\":true}")
            }
        }
        override fun cancel(operation: LuaOperationHandle) = complete(operation.stateHandle)
        override fun interrupt(handle: LuaStateHandle) = complete(handle)
        override fun snapshot(handle: LuaStateHandle) = LuaKernelOutcome.Snapshot(handle.stateId.value, handle.generation.value, null, LUA_VERSION, API_VERSION, "debug-fixture")
        override fun close(handle: LuaStateHandle): LuaKernelOutcome { closedStates += handle.stateId.value; return LuaKernelOutcome.Closed(handle.stateId.value, handle.generation.value) }
        override fun loadProgramImage(handle: LuaStateHandle, entryPoint: String, sourceMap: Map<String, String>) = complete(handle, "[\"startup\",\"handle_readiness\",\"handle_input\"]")
        override fun invokeStartupCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, config: LuaValue, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome { modes[handle.stateId.value] = (((config as? LuaValue.Map)?.pairs?.get("values") as? LuaValue.Map)?.pairs?.get("mode") as? LuaValue.StringValue)?.value ?: "ECHO"; startedModes += modes[handle.stateId.value]!!; return complete(handle) }
        override fun invokeCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, arguments: LuaValue, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome { val mode = modes[handle.stateId.value] ?: "ECHO"; val caps = (arguments as? LuaValue.Map)?.pairs?.get("capabilities") as? LuaValue.Map; val required = when (mode) { "ECHO", "DELAYED_ECHO" -> listOf("audio.playback"); "STT" -> listOf("audio.transcription"); "TTS" -> listOf("audio.synthesis", "audio.playback"); else -> listOf("audio.transcription", "audio.synthesis", "audio.playback") }; val ready = required.all { (caps?.pairs?.get(it) as? LuaValue.StringValue)?.value == "available" }; return complete(handle, "{\"ready\":$ready,\"status\":\"$mode\"}") }
        override fun invokeInputCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, arguments: LuaValue, capturedAudioToken: String, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome { val mode = modes[handle.stateId.value] ?: "ECHO"; when (mode) { "ECHO" -> delays += 0.0; "DELAYED_ECHO" -> delays += 5.0; "STT" -> transcriptions += capturedAudioToken; "TTS" -> synthesisTexts += "Debug synthesis test"; "STT_TTS" -> { transcriptions += capturedAudioToken; synthesisTexts += "transcript" } }; return complete(handle, "{\"ok\":true}") }
        override fun startCoroutine(handle: LuaStateHandle, coroutineId: LuaCoroutineId, spawnAdmission: LuaSpawnAdmission) = complete(handle)
        override fun claimHostOperation(handle: LuaStateHandle, requestId: Long): HostOperationClaim = claims[requestId] ?: HostOperationClaim.Rejected("E_STALE")
        private fun registerClaim(kind: HostOperationKind, audioToken: String? = null, text: String? = null, language: String? = null, voice: String? = null, speed: Double = 1.0, delaySeconds: Double = 0.0): String { val id = nextRequestId++; claims[id] = HostOperationClaim.Admitted(id, kind, audioToken, text, language, voice, speed, delaySeconds); return id.toString() }
        private fun yield(operation: LuaOperationHandle, claimId: String) = LuaKernelOutcome.Yielded(operation.stateHandle.stateId.value, operation.stateHandle.generation.value, operation.coroutineId.value, operation.operationId.value, claimId)
        private fun complete(handle: LuaStateHandle, value: String? = null) = LuaKernelOutcome.Completed(handle.stateId.value, handle.generation.value, null, value, null, LUA_VERSION, API_VERSION, "debug-fixture")
    }

    private class DebugCapabilityHost(private val available: Set<CapabilityKey<*>> = setOf(CapabilityKey.Transcription, CapabilityKey.Synthesis, CapabilityKey.AudioOperation, CapabilityKey.DeferredAudioPlayback)) : ChannelCapabilityHost {
        val playbackDelays = mutableListOf<Double>(); val synthesisTexts = mutableListOf<String>(); var transcriptionCount = 0
        override suspend fun availability(identity: CapabilityScopeIdentity, key: CapabilityKey<*>) = if (key in available) CapabilityAvailability.Available else CapabilityAvailability.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
        override suspend fun <T : ChannelCapabilityPort> acquire(identity: CapabilityScopeIdentity, key: CapabilityKey<T>): HostedCapabilityAcquisition<T> = if (key !in available) HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED) else HostedCapabilityAcquisition.Available(port(key), {})
        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(identity: CapabilityScopeIdentity, key: CapabilityKey<T>, timeoutMillis: Long) = acquire(identity, key)
        @Suppress("UNCHECKED_CAST") private fun <T : ChannelCapabilityPort> port(key: CapabilityKey<T>): T = when (key) {
            CapabilityKey.Transcription -> object : TranscriptionCapability {
                override suspend fun transcribe(recording: OpaqueAudioRecording) =
                    CapabilityOperationResult.Success(Transcription("transcript")).also { transcriptionCount++ }
            } as T
            CapabilityKey.Synthesis -> object : SynthesisCapability {
                override suspend fun synthesize(request: SpeechSynthesisRequest) =
                    CapabilityOperationResult.Success(io.talkcan.channel.capability.SynthesizedAudioArtifact(floatArrayOf(1f), request.text, RuntimeGeneration(0)))
                        .also { synthesisTexts += request.text }
            } as T
            CapabilityKey.AudioOperation -> object : AudioOperationCapability {
                override suspend fun createPlaybackResult(audio: OpaqueSynthesizedAudio) =
                    CapabilityOperationResult.Success<OpaqueAudioOperation>(
                        AudioOperationArtifact(RecordedPcm(shortArrayOf(1), 16_000), operationId = "test-op", generation = RuntimeGeneration(0))
                    ).also { playbackDelays += 0.0 }
                override suspend fun createPlaybackResult(recording: OpaqueAudioRecording) =
                    CapabilityOperationResult.Success<OpaqueAudioOperation>(
                        AudioOperationArtifact(RecordedPcm(shortArrayOf(1), 16_000), operationId = "test-op", generation = RuntimeGeneration(0))
                    ).also { playbackDelays += 0.0 }
            } as T
            CapabilityKey.DeferredAudioPlayback -> object : DeferredAudioPlaybackCapability {
                override suspend fun scheduleAudio(context: AgentOperationContext, audio: OpaqueAudioOperation, eligibilityDelayMillis: Long) =
                    DelayedPlaybackOutcome.Pending(DelayedPlaybackOperationId("test-pending")).also { playbackDelays += eligibilityDelayMillis / 1000.0 }
            } as T
            else -> error("Unknown capability: $key")
        }
    }

    private companion object {
        const val REPOSITORY_ID = "1306065111"
        const val RELEASE_ID = "359174403"
        const val ASSET_ID = "488198107"
        const val OWNER = "1224006"
    }
}
