package io.talkcan.lua

import io.talkcan.audio.ChannelInputAcceptance
import io.talkcan.audio.RecordedPcm
import io.talkcan.channel.capability.CapabilityAvailability
import io.talkcan.channel.capability.CapabilityKey
import io.talkcan.channel.capability.ChannelCapabilityHost
import io.talkcan.channel.capability.ChannelCapabilityPort
import io.talkcan.channel.capability.HostedCapabilityAcquisition
import io.talkcan.lua.actor.ActorRuntimeFactory
import io.talkcan.model.ChannelCatalogueSnapshot
import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ChannelImplementationProviderRegistry
import io.talkcan.model.ChannelProviderError
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.service.ChannelPreparationAvailability
import io.talkcan.service.ChannelPreparationReason
import io.talkcan.service.ChannelRuntimeRegistry
import io.talkcan.service.CommittedTargetLeaseOwner
import io.talkcan.service.ChannelRuntimeRegistryShutdownResult
import io.talkcan.service.RuntimeInvocationBoundary
import io.talkcan.service.RuntimeInvocationPolicy
import io.talkcan.service.ChannelActivationResult
import io.talkcan.service.ChannelExecutionStatus
import io.talkcan.service.ChannelRuntime
import io.talkcan.service.ChannelRuntimeSnapshot
import io.talkcan.model.ChannelConfigurationMigrationStep
import io.talkcan.model.ChannelConfigurationProvider
import io.talkcan.dependency.ConfigurationDataDeclaration
import io.talkcan.dependency.ConfigurationFieldDeclaration
import io.talkcan.dependency.ConfigurationUiDeclaration
import io.talkcan.dependency.PackageConfigurationDeclaration
import io.talkcan.dependency.UiChoice
import io.talkcan.dependency.UiControl
import io.talkcan.dependency.UiFieldDeclaration
import io.talkcan.model.ChannelImplementationDescriptor
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelImplementationProvider
import io.talkcan.model.ChannelPreparationTraits
import io.talkcan.model.ChannelPresentationMetadata
import io.talkcan.model.ChannelRuntimeConstructionRequest
import io.talkcan.model.ChannelRuntimeConstructionResult
import io.talkcan.model.ProviderConfigurationResult
import io.talkcan.model.ProviderRevisionFingerprint
import io.talkcan.model.ValidatedChannelConfiguration
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineDispatcher
import io.talkcan.service.RuntimeWorkerDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Test-only identity for generic Lua provider behavior. */
private val TEST_LUA_IMPLEMENTATION_ID = ChannelImplementationId("internal:lua")

/** Empty schema-version-1 declaration; mirrors a materialized package with no fields. */
private fun emptyConfigurationDeclaration(): PackageConfigurationDeclaration = PackageConfigurationDeclaration(
    ConfigurationDataDeclaration(emptyList()),
    ConfigurationUiDeclaration(emptyList()),
)

/**
 * Constructs a test Lua provider with fixed identity and presentation.
 * Tests that need custom identity/presentation should use LuaChannelImplementationProvider.create.
 */
private fun testLuaProvider(
    image: ImmutableProgramImage,
    bridge: LuaKernelBridge,
    declaration: PackageConfigurationDeclaration = emptyConfigurationDeclaration(),
): LuaChannelImplementationProvider = LuaChannelImplementationProvider.create(
    implementationId = TEST_LUA_IMPLEMENTATION_ID,
    presentation = ChannelPresentationMetadata(
        label = "Lua channel",
        summary = "LUA RUNTIME",
        unavailableMessage = "Lua program could not be constructed.",
    ),
    programImage = image,
    fingerprint = ProviderRevisionFingerprint("builtin"),
    actorFactory = { context, capabilities, kernelBridge, policy ->
        ActorRuntimeFactory.createForGeneration(context, capabilities, kernelBridge, policy)
    },
    bridge = bridge,
    configurationProvider = CompiledConfigurationProvider(TEST_LUA_IMPLEMENTATION_ID, declaration),
)

/** Schema used by configured black-box fixtures; the value crosses the real startup path. */
private fun configuredDeclaration(): PackageConfigurationDeclaration {
    val mode = ConfigurationFieldDeclaration.StringField(
        id = "mode",
        default = "ECHO",
        allowedValues = listOf("ECHO", "STT"),
    )
    return PackageConfigurationDeclaration(
        ConfigurationDataDeclaration(listOf(mode)),
        ConfigurationUiDeclaration(listOf(
            UiFieldDeclaration("mode", UiControl.CHOICE, "Mode", null, listOf(
                UiChoice("ECHO", "Echo"),
                UiChoice("STT", "Speech to text"),
            )),
        )),
    )
}

private fun testLuaProviderFromResult(
    imageResult: ProgramImageCreationResult,
    bridge: LuaKernelBridge,
    declaration: PackageConfigurationDeclaration = emptyConfigurationDeclaration(),
): LuaChannelImplementationProvider = LuaChannelImplementationProvider.fromImageResult(
    implementationId = TEST_LUA_IMPLEMENTATION_ID,
    presentation = ChannelPresentationMetadata(
        label = "Lua channel",
        summary = "LUA RUNTIME",
        unavailableMessage = "Lua program could not be constructed.",
    ),
    imageResult = imageResult,
    fingerprint = ProviderRevisionFingerprint("builtin"),
    actorFactory = { context, capabilities, kernelBridge, policy ->
        ActorRuntimeFactory.createForGeneration(context, capabilities, kernelBridge, policy)
    },
    bridge = bridge,
    configurationProvider = CompiledConfigurationProvider(TEST_LUA_IMPLEMENTATION_ID, declaration),
)


/**
 * Exercises the Lua provider solely through the ordinary provider and runtime registries.
 *
 * The registry-level kernel boundary is observed through [RecordingKernelBridge], which
 * implements the retained semantic kernel boundary. It models state ownership and typed
 * program-image outcomes; device instrumentation executes the fixture source with the
 * production Kotlin kernel bridge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LuaProviderRegistryIntegrationTest {
    @Before
    fun resetLazyConstructionEvidence() {
        ActorRuntimeFactory.resetForTest()
    }

    @After
    fun clearLazyConstructionEvidence() {
        ActorRuntimeFactory.resetForTest()
    }

    @Test
    fun `package image activates through ordinary registries and first readiness refresh follows publication`() = runTest {
        val bridge = RecordingKernelBridge()
        val registry = fixture(testLuaProvider(LuaProviderRegistryFixtures.packageModules(), bridge))
        val definition = definition("package-channel")
        bridge.firstReadinessObserver = { instanceId ->
            assertTrue(
                "the registry must publish this generation before its first Lua readiness refresh",
                registry.getRuntimeSnapshot(instanceId) != null,
            )
        }

        registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        assertEquals(
            ChannelPreparationAvailability.Available,
            registry.getRuntimeSnapshot(definition.id)?.preparation,
        )
        assertTrue(
            "the real registry/provider/adapter path must expose the package-backed ready channel",
            prepareAndReleaseInput(registry, definition.id),
        )
        val startupConfig = bridge.callbackCalls.first { it.name == "startup" }.arguments
        assertEquals(
            LuaValue.Map(mapOf(
                "schema_version" to LuaValue.Integer(1L),
                "values" to LuaValue.Map(emptyMap()),
            )),
            startupConfig,
        )
        assertEquals(1, bridge.firstReadinessObserved)
        assertEquals("package-local helper module must be loaded by the real image path", 1, bridge.packageImagesAccepted)
        shutdownClosed(registry)
        assertEquals(
            "the state created for this explicit test provider must be closed during registry shutdown",
            bridge.createdStateIds.toSet(),
            bridge.closedStateIds.toSet(),
        )
    }

    @Test
    fun `empty and configured startup snapshots stay detached across same-provider instances`() = runTest {
        val bridge = RecordingKernelBridge()
        val declaration = configuredDeclaration()
        val registry = fixture(testLuaProvider(LuaProviderRegistryFixtures.validCallbacks(), bridge, declaration))
        val left = definition("configured-left", configPayload = payload("ECHO"))
        val right = definition("configured-right", configPayload = payload("STT"))

        registry.reconcile(ChannelCatalogueSnapshot(listOf(left, right), left.id))
        runCurrent()

        val startupCalls = bridge.callbackCalls.filter { it.name == "startup" }
        assertEquals("both configured instances must cross startup", 2, startupCalls.size)
        val leftConfig = startupCalls[0].arguments as? LuaValue.Map
            ?: throw AssertionError("left startup must receive a normalized table")
        val rightConfig = startupCalls[1].arguments as? LuaValue.Map
            ?: throw AssertionError("right startup must receive a normalized table")
        assertEquals(
            LuaValue.Map(mapOf("mode" to LuaValue.StringValue("ECHO"))),
            leftConfig.pairs["values"],
        )
        assertEquals(
            LuaValue.Map(mapOf("mode" to LuaValue.StringValue("STT"))),
            rightConfig.pairs["values"],
        )
        assertTrue("each provider generation receives a detached startup table", leftConfig !== rightConfig)
        assertTrue(
            "each provider generation receives a detached values table",
            leftConfig.pairs["values"] !== rightConfig.pairs["values"],
        )
        assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(left.id)?.preparation)
        assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(right.id)?.preparation)

        shutdownClosed(registry)
    }

    @Test
    fun `proactive startup timers are admitted for an unselected instance through the runtime registry`() = runTest {
        val bridge = RecordingKernelBridge()
        val registry = fixture(testLuaProvider(LuaProviderRegistryFixtures.proactiveTimer(), bridge))
        val selected = definition("timer-selected")
        val unselected = definition("timer-unselected")

        registry.reconcile(ChannelCatalogueSnapshot(listOf(selected, unselected), selected.id))
        runCurrent()

        assertEquals("both instances must construct independent states", 2, bridge.createdStateIds.size)
        assertEquals(
            "startup-admitted timer work must start for both selected and unselected instances",
            2,
            bridge.events.count { it.startsWith("coroutine:") },
        )
        assertEquals(
            ChannelPreparationAvailability.Unavailable(
                ChannelPreparationReason.RuntimeReadiness(),
            ),
            registry.getRuntimeSnapshot(unselected.id)?.preparation,
        )

        shutdownClosed(registry)
    }

    @Test
    fun `normalized nil error table is projected as a failed terminal without throwing`() = runTest {
        val bridge = RecordingKernelBridge()
        val registry = fixture(testLuaProvider(LuaProviderRegistryFixtures.normalizedError(), bridge))
        val definition = definition("normalized-error")

        registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()
        val accepted = registry.prepareInput(definition.id) as? ChannelInputAcceptance.Accepted
            ?: throw AssertionError("normalized-error fixture must accept input")
        accepted.target.onInputReleased(RecordedPcm(shortArrayOf(1), 8_000))
        (accepted.target as? CommittedTargetLeaseOwner)?.releaseCommittedTargetLease()

        assertEquals(ChannelExecutionStatus.FAILED, registry.getRuntimeSnapshot(definition.id)?.executionStatus)
        assertEquals(
            "normalized-error input must invoke exactly one handle_input callback through the registry path",
            1,
            bridge.callbackCalls.count { it.name == "handle_input" },
        )
        shutdownClosed(registry)
    }


    @Test
    fun `same Lua provider gives siblings independent generation scopes and closing one preserves the other`() = runTest {
        val bridge = RecordingKernelBridge()
        val registry = fixture(testLuaProvider(LuaProviderRegistryFixtures.validCallbacks(), bridge))
        val left = definition("left")
        val right = definition("right")

        registry.reconcile(ChannelCatalogueSnapshot(listOf(left, right), left.id))
        runCurrent()

        val leftScope = requireNotNull(registry.capabilityScopeIdentity(left.id))
        val rightScope = requireNotNull(registry.capabilityScopeIdentity(right.id))
        assertEquals(left.id, leftScope.channelInstanceId)
        assertEquals(right.id, rightScope.channelInstanceId)
        assertNotEquals(
            "same-provider siblings must have separate generation-bound capability scopes",
            leftScope.runtimeGeneration,
            rightScope.runtimeGeneration,
        )
        assertEquals(2, bridge.createdStateIds.size)
        assertNotEquals(
            "each provider construction must allocate a distinct kernel state",
            bridge.createdStateIds[0],
            bridge.createdStateIds[1],
        )

        registry.reconcile(ChannelCatalogueSnapshot(listOf(right), right.id))
        runCurrent()

        assertTrue("closing left must close only left's state", bridge.closedStateIds.contains(bridge.createdStateIds[0]))
        assertFalse("left retirement must not close right's state", bridge.closedStateIds.contains(bridge.createdStateIds[1]))
        assertTrue(
            "a live sibling must continue to accept input after the other instance is retired",
            prepareAndReleaseInput(registry, right.id),
        )
        shutdownClosed(registry)
        assertEquals(bridge.createdStateIds.toSet(), bridge.closedStateIds.toSet())
    }

    @Test
    fun `replacement closes G before H activation and first readiness refresh`() = runTest {
        val bridge = RecordingKernelBridge()
        val registry = fixture(testLuaProvider(LuaProviderRegistryFixtures.raceControl(), bridge))
        val generationG = definition("channel", name = "Generation G")

        registry.reconcile(ChannelCatalogueSnapshot(listOf(generationG), generationG.id))
        runCurrent()
        val gState = bridge.createdStateIds.single()

        val generationH = generationG.copy(name = "Generation H")
        registry.reconcile(ChannelCatalogueSnapshot(listOf(generationH), generationH.id))
        runCurrent()
        val hState = bridge.createdStateIds.last()

        val gClose = bridge.events.indexOf("close:$gState")
        val hStartup = bridge.events.indexOf("callback:$hState:startup")
        val hReadiness = bridge.events.indexOf("callback:$hState:handle_readiness")
        assertTrue("generation G must close before generation H starts", gClose >= 0 && gClose < hStartup)
        assertTrue(
            "generation G must close before generation H performs its first readiness refresh",
            gClose >= 0 && gClose < hReadiness,
        )
        assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(generationH.id)?.preparation)

        shutdownClosed(registry)
    }

    @Test
    fun `configuration replacement closes predecessor before fresh configured generation startup`() = runTest {
        val bridge = RecordingKernelBridge()
        val registry = fixture(testLuaProvider(LuaProviderRegistryFixtures.raceControl(), bridge, configuredDeclaration()))
        val generationG = definition("configured-replacement", configPayload = payload("ECHO"))
        registry.reconcile(ChannelCatalogueSnapshot(listOf(generationG), generationG.id))
        runCurrent()
        val gState = bridge.createdStateIds.single()

        val generationH = generationG.copy(configPayload = payload("STT"))
        registry.reconcile(ChannelCatalogueSnapshot(listOf(generationH), generationH.id))
        runCurrent()
        val hState = bridge.createdStateIds.last()
        val gClose = bridge.events.indexOf("close:$gState")
        val hStartup = bridge.events.indexOf("callback:$hState:startup")
        assertTrue("predecessor must close before replacement startup", gClose >= 0 && gClose < hStartup)
        val startupModes = bridge.callbackCalls.filter { it.name == "startup" }.map {
            val config = it.arguments as LuaValue.Map
            ((config.pairs["values"] as LuaValue.Map).pairs["mode"] as LuaValue.StringValue).value
        }
        assertEquals(listOf("ECHO", "STT"), startupModes)
        assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(generationH.id)?.preparation)
        shutdownClosed(registry)
    }

    @Test
    fun `malformed callback and effect-at-load images fail through registry and close their partial state`() = runTest {
        val cases = listOf(
            LuaProviderRegistryFixtures.malformedCallbacks() to "required callback 'startup' is missing",
            LuaProviderRegistryFixtures.entryEffectAttempt() to "E_EFFECT_DURING_LOAD",
            LuaProviderRegistryFixtures.lazyModuleEffectAttempt() to "E_EFFECT_DURING_LOAD",
        )

        cases.forEachIndexed { index, (image, expectedDetail) ->
            val bridge = RecordingKernelBridge()
            val registry = fixture(testLuaProvider(image, bridge))
            val definition = definition("rejected-$index")

            registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
            runCurrent()

            val unavailable = registry.getRuntimeSnapshot(definition.id)?.preparation
                as? ChannelPreparationAvailability.Unavailable
                ?: throw AssertionError("expected typed provider unavailability")
            val error = (unavailable.reason as? ChannelPreparationReason.Provider)?.error
                ?: throw AssertionError("expected provider failure, got ${unavailable.reason}")
            assertEquals(
                ChannelProviderError.RuntimeConstructionFailed(TEST_LUA_IMPLEMENTATION_ID, expectedDetail),
                error,
            )
            assertEquals(1, bridge.createdStateIds.size)
            assertEquals(
                "all post-state construction failures must deterministically close the partial state",
                bridge.createdStateIds.toSet(),
                bridge.closedStateIds.toSet(),
            )
            assertTrue("a rejected image must not activate callbacks", bridge.callbackNames.isEmpty())

            shutdownClosed(registry)
        }
    }

    @Test
    fun `ordinary provider composition has no Lua registration and Lua definitions have typed missing-provider unavailability`() = runTest {
        assertFalse(
            "ordinary built-in descriptor composition must not expose the test-only Lua provider",
            TEST_LUA_IMPLEMENTATION_ID.value.startsWith("builtin:"),
        )

        val registry = fixture()
        val definition = definition("unregistered-lua")
        registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        val unavailable = registry.getRuntimeSnapshot(definition.id)?.preparation
            as? ChannelPreparationAvailability.Unavailable
            ?: throw AssertionError("expected unregistered Lua definition to be unavailable")
        assertEquals(
            ChannelProviderError.MissingProvider(TEST_LUA_IMPLEMENTATION_ID),
            (unavailable.reason as? ChannelPreparationReason.Provider)?.error,
        )
        assertFalse("missing-provider resolution must not create an actor", ActorRuntimeFactory.isCreateAttempted)

        shutdownClosed(registry)
    }

    @Test
    fun `Lua provider projects exact compatibility failure without creating a kernel state`() = runTest {
        val bridge = RecordingKernelBridge()
        val registry = fixture(
            testLuaProviderFromResult(LuaProviderRegistryFixtures.incompatibleApi(), bridge),
        )
        val definition = definition("incompatible")

        registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        val unavailable = registry.getRuntimeSnapshot(definition.id)?.preparation
            as? ChannelPreparationAvailability.Unavailable
            ?: throw AssertionError("expected typed compatibility unavailability")
        assertEquals(
            ChannelProviderError.RuntimeCompatibilityFailure(
                implementationId = TEST_LUA_IMPLEMENTATION_ID,
                requirement = "apiVersion",
                requiredVersion = "talkcan-lua-v999",
                supportedVersion = API_VERSION,
            ),
            (unavailable.reason as? ChannelPreparationReason.Provider)?.error,
        )
        assertTrue("compatibility rejection must precede actor state construction", bridge.createdStateIds.isEmpty())

        shutdownClosed(registry)
    }

    @Test
    fun `source-bound image rejection projects before kernel state creation`() = runTest {
        val imageResult = LuaProviderRegistryFixtures.sourceBound()
        val expectedDetail = (imageResult as? ProgramImageCreationResult.Failure)?.error?.message
            ?: throw AssertionError("source-bound fixture must be rejected immutably")
        val bridge = RecordingKernelBridge()
        val registry = fixture(testLuaProviderFromResult(imageResult, bridge))
        val definition = definition("source-bound")
        registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()
        val unavailable = registry.getRuntimeSnapshot(definition.id)?.preparation
            as? ChannelPreparationAvailability.Unavailable
            ?: throw AssertionError("expected source-bound provider unavailability")
        assertEquals(
            ChannelProviderError.RuntimeConstructionFailed(TEST_LUA_IMPLEMENTATION_ID, expectedDetail),
            (unavailable.reason as? ChannelPreparationReason.Provider)?.error,
        )
        assertTrue("source validation must precede state creation", bridge.createdStateIds.isEmpty())
        shutdownClosed(registry)
    }

    @Test
    fun `Lua provider routes lifecycle readiness input cache and SOS through the generation gate`() = runTest {
        val bridge = RecordingKernelBridge()
        val registry = fixture(testLuaProvider(lifecycleImage(), bridge))
        val definition = definition("lifecycle")

        registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        assertTrue(
            "the first gate-owned readiness result must make input eligible without entering handle_input",
            prepareAndReleaseInput(registry, definition.id),
        )
        assertEquals(ChannelPreparationAvailability.Available, registry.dispatchSos(definition.id))
        assertEquals(
            listOf(
                CallbackCall("startup", LuaValue.Map(mapOf(
                    "schema_version" to LuaValue.Integer(1L),
                    "values" to LuaValue.Map(emptyMap()),
                ))),
                CallbackCall("handle_lifecycle", LuaValue.Map(mapOf("event" to LuaValue.StringValue("ready")))),
                CallbackCall("handle_readiness", LuaValue.Map(mapOf(
                    "capabilities" to LuaValue.Map(emptyMap()),
                    "resources" to LuaValue.Map(
                        mapOf("mounts" to LuaValue.Map(emptyMap())),
                    ),
                ))),
                CallbackCall("handle_sos", LuaValue.Map(mapOf("event" to LuaValue.StringValue("sos")))),
            ),
            bridge.callbackCalls.map { CallbackCall(it.name, it.arguments) },
        )

        shutdownClosed(registry)
    }

    @Test
    fun `periodic readiness opt-in updates the registry projection and stops with replacement and close`() = runTest {
        val provider = PeriodicReadinessProvider(refreshIntervalMillis = 25)
        val registry = fixture(provider)
        val first = definition("periodic", implementationId = provider.descriptor.implementationId)

        registry.reconcile(ChannelCatalogueSnapshot(listOf(first), first.id))
        runCurrent()

        assertEquals(
            ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeFailed("not ready")),
            registry.getRuntimeSnapshot(first.id)?.preparation,
        )

        advanceTimeBy(25)
        runCurrent()

        assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(first.id)?.preparation)

        val firstRuntime = provider.runtimes.single()
        val replacement = first.copy(name = "periodic replacement")
        registry.reconcile(ChannelCatalogueSnapshot(listOf(replacement), replacement.id))
        runCurrent()
        val retiredRefreshes = firstRuntime.refreshes

        advanceTimeBy(100)
        runCurrent()
        assertEquals("retired periodic jobs must not refresh the replaced runtime", retiredRefreshes, firstRuntime.refreshes)

        val replacementRuntime = provider.runtimes.last()
        shutdownClosed(registry)
        val closedRefreshes = replacementRuntime.refreshes
        advanceTimeBy(100)
        runCurrent()
        assertEquals("closed periodic jobs must not refresh after registry shutdown", closedRefreshes, replacementRuntime.refreshes)
    }

    @Test
    fun `registry shutdown falls back after the outer gate timeout and closes kernel state once`() = runTest {
        val bridge = RecordingKernelBridge()
        val pausedWorker = PausableWorkerDispatcher(StandardTestDispatcher(testScheduler))
        val registry = fixture(
            provider = testLuaProvider(LuaProviderRegistryFixtures.validCallbacks(), bridge),
            worker = RuntimeWorkerDispatcher.fromDispatcher(pausedWorker),
        )
        val definition = definition("shutdown")

        registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()
        val stateId = bridge.createdStateIds.single()
        pausedWorker.pause()

        val shutdown = async { withTimeout(6_000) { registry.shutdownAndAwait() } }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(
            "the terminal worker must be queued so the outer close gate takes its timeout path",
            1,
            pausedWorker.pendingDispatches,
        )
        assertEquals(
            "the registry fallback must close the actor immediately after the outer timeout",
            1,
            bridge.closeCalls.count { it == stateId },
        )
        assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown.await())

        pausedWorker.resumeQueued()
        runCurrent()
        assertEquals(
            "resuming the barred terminal-worker dispatch must not close the kernel state a second time",
            1,
            bridge.closeCalls.count { it == stateId },
        )
    }
    private suspend fun TestScope.shutdownClosed(registry: ChannelRuntimeRegistry) {
        val shutdown = async { registry.shutdownAndAwait() }
        runCurrent()
        assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown.await())
    }



    private suspend fun TestScope.prepareAndReleaseInput(
        registry: ChannelRuntimeRegistry,
        channelId: String,
    ): Boolean {
        val accepted = registry.prepareInput(channelId) as? ChannelInputAcceptance.Accepted ?: return false
        accepted.target.onInputCancelled("test input completed")
        (accepted.target as? CommittedTargetLeaseOwner)?.releaseCommittedTargetLease()
        return true
    }

    private fun TestScope.fixture(
        provider: ChannelImplementationProvider? = null,
        worker: RuntimeWorkerDispatcher = RuntimeWorkerDispatcher.fromDispatcher(StandardTestDispatcher(testScheduler)),
    ): ChannelRuntimeRegistry {
        val providers = ChannelImplementationProviderRegistry()
        provider?.let { registered -> providers.register(registered) }
        return ChannelRuntimeRegistry(
            providers = providers,
            capabilityHost = NoCapabilities,
            invocationBoundary = RuntimeInvocationBoundary(
                worker,
                RuntimeInvocationPolicy(
                    perGenerationQueueCapacity = 16,
                    callbackTimeoutMillis = 1_000,
                    inputReleasedTimeoutMillis = 1_000,
                    closeTimeoutMillis = 1_000,
                ),
            ),
            runtimeScope = backgroundScope,
            closeScope = backgroundScope,
            shutdownAwaitMillis = 6_000,
        )
    }

    private fun definition(
        id: String,
        name: String = id,
        implementationId: ChannelImplementationId = TEST_LUA_IMPLEMENTATION_ID,
        configPayload: OpaqueJsonObject = OpaqueJsonObject.fromJsonObject(org.json.JSONObject()),
    ): ChannelDefinition = ChannelDefinition(
        id = id,
        name = name,
        implementationId = implementationId,
        enabled = true,
        configSchemaVersion = 1,
        configPayload = configPayload,
    )

    private fun payload(mode: String): OpaqueJsonObject =
        OpaqueJsonObject.fromJsonObject(org.json.JSONObject().put("mode", mode))

    private fun lifecycleImage(): ImmutableProgramImage = when (val image = ImmutableProgramImage.create(
        entryPoint = "plugin.lifecycle",
        sourceMap = mapOf(
            "plugin.lifecycle" to """
                return {
                    startup = function() end,
                    handle_lifecycle = function(event) end,
                    handle_readiness = function() return { ready = true } end,
                    handle_input = function(event) return { ok = true } end,
                    handle_sos = function(event) end,
                }
            """.trimIndent(),
        ),
        requirements = LuaProgramRequirements(LUA_VERSION, API_VERSION),
    )) {
        is ProgramImageCreationResult.Success -> image.image
        is ProgramImageCreationResult.Failure -> throw AssertionError(image.error.message)
    }

    private class PeriodicReadinessProvider(
        private val refreshIntervalMillis: Long,
    ) : ChannelImplementationProvider {
        override val fingerprint = ProviderRevisionFingerprint("test-fingerprint")
        override val descriptor = ChannelImplementationDescriptor(
            implementationId = IMPLEMENTATION_ID,
            presentation = ChannelPresentationMetadata("Periodic", "periodic readiness", "unavailable"),
            configuration = PeriodicConfiguration,
            configurationFields = emptyList(),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )
        val runtimes = mutableListOf<PeriodicReadinessRuntime>()

        override suspend fun constructRuntime(
            request: ChannelRuntimeConstructionRequest,
        ): ChannelRuntimeConstructionResult {
            return PeriodicReadinessRuntime(request.definition, refreshIntervalMillis).also(runtimes::add)
                .let(ChannelRuntimeConstructionResult::Success)
        }
        companion object {
            val IMPLEMENTATION_ID = ChannelImplementationId("test:periodic-readiness")
        }
    }

    private object PeriodicConfiguration : ChannelConfigurationProvider {
        override val implementationId: ChannelImplementationId = PeriodicReadinessProvider.IMPLEMENTATION_ID
        override val currentSchemaVersion: Int = 1

        override fun defaultPayload(): OpaqueJsonObject = OpaqueJsonObject.fromJsonObject(org.json.JSONObject())

        override fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult =
            ProviderConfigurationResult.Success(
                ValidatedChannelConfiguration(implementationId, schemaVersion, payload),
            )

        override fun migrateStep(
            fromSchemaVersion: Int,
            payload: OpaqueJsonObject,
        ): ChannelConfigurationMigrationStep = ChannelConfigurationMigrationStep.Failure(
            ChannelProviderError.UnsupportedSchemaVersion(implementationId, fromSchemaVersion, currentSchemaVersion),
        )
    }

    private class PeriodicReadinessRuntime(
        definition: ChannelDefinition,
        override val readinessRefreshIntervalMillis: Long,
    ) : ChannelRuntime {
        override val id: String = definition.id
        private val snapshots = MutableStateFlow(
            ChannelRuntimeSnapshot(
                id = definition.id,
                name = definition.name,
                implementationId = definition.implementationId,
                enabled = definition.enabled,
                preparation = ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeFailed("not ready")),
                executionStatus = ChannelExecutionStatus.IDLE,
            ),
        )
        override val snapshot: StateFlow<ChannelRuntimeSnapshot> = snapshots.asStateFlow()
        var refreshes = 0
            private set

        override suspend fun prepareInput(): ChannelInputAcceptance = ChannelInputAcceptance.Refused("periodic fixture")

        override suspend fun refreshReadiness() {
            refreshes += 1
            if (refreshes == 2) {
                snapshots.value = snapshots.value.copy(preparation = ChannelPreparationAvailability.Available)
            }
        }

        override suspend fun close() = Unit
    }

    private class PausableWorkerDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        private data class Pending(val context: CoroutineContext, val block: Runnable)

        private val pending = ArrayDeque<Pending>()
        private var paused = false

        val pendingDispatches: Int
            get() = pending.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (paused) {
                pending += Pending(context, block)
            } else {
                delegate.dispatch(context, block)
            }
        }

        fun pause() {
            paused = true
        }

        fun resumeQueued() {
            paused = false
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                delegate.dispatch(next.context, next.block)
            }
        }
    }
    private data class CallbackCall(val name: String, val arguments: LuaValue?)


    /**
     * Contract-faithful JVM kernel boundary. It owns distinct opaque states, validates the package
     * image shape that must reach the bridge, returns typed load failures, and rejects late work on
     * a closed state. It deliberately does not parse or execute Lua; Lua source semantics stay
     * in the Kotlin kernel conformance suite and device instrumentation.
     */
    private class RecordingKernelBridge : LuaKernelBridge {
        private data class State(val id: Long, var closed: Boolean = false)

        private var nextStateId = 1L
        private val states = linkedMapOf<Long, State>()
        private val scenarios = mutableMapOf<Long, String>()
        private val readinessSeen = mutableSetOf<Long>()

        val createdStateIds = mutableListOf<Long>()
        val closedStateIds = mutableListOf<Long>()
        val callbackNames = mutableListOf<String>()
        val events = mutableListOf<String>()
        val closeCalls = mutableListOf<Long>()
        val callbackCalls = mutableListOf<CallbackCall>()
        var packageImagesAccepted = 0
            private set
        var firstReadinessObserved = 0
            private set
        var firstReadinessObserver: ((String) -> Unit)? = null

        override fun create(config: LuaKernelConfig): LuaKernelOutcome {
            val id = nextStateId++
            states[id] = State(id)
            createdStateIds += id
            events += "create:$id"
            return LuaKernelOutcome.Created(id, id, LUA_VERSION, API_VERSION, "recording")
        }

        override fun setResourceContext(
            handle: LuaStateHandle,
            resourceContextJson: String,
        ): LuaKernelOutcome = completed(handle)
        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome = completed(handle)

        override fun start(handle: LuaStateHandle): LuaKernelOutcome = completed(handle)

        override fun resume(
            operation: LuaOperationHandle,
            success: Boolean,
            value: String,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            val handle = operation.stateHandle
            return if (state(handle).closed) closed(handle) else admitSpawned("resume", completed(handle), spawnAdmission)
        }

        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome =
            if (state(operation.stateHandle).closed) closed(operation.stateHandle) else completed(operation.stateHandle)

        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = completed(handle)

        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome {
            val id = handle.stateId.value
            return LuaKernelOutcome.Snapshot(id, handle.generation.value, null, LUA_VERSION, API_VERSION, "recording")
        }

        override fun close(handle: LuaStateHandle): LuaKernelOutcome {
            closeCalls += handle.stateId.value
            val state = state(handle)
            if (!state.closed) {
                state.closed = true
                closedStateIds += state.id
                events += "close:${state.id}"
            }
            return closed(handle)
        }

        override fun loadProgramImage(
            handle: LuaStateHandle,
            entryPoint: String,
            sourceMap: Map<String, String>,
        ): LuaKernelOutcome {
            val state = state(handle)
            scenarios[state.id] = entryPoint
            events += "load:${state.id}:$entryPoint"
            if (entryPoint == "plugin.package_entry") {
                val helper = sourceMap["plugin.helpers"]
                if (helper == null || !helper.contains("initialize")) {
                    return LuaKernelOutcome.ValidationFailure(
                        state.id,
                        handle.generation.value,
                        "E_MODULE_NOT_FOUND",
                    )
                }
                packageImagesAccepted += 1
            }
            return when (entryPoint) {
                "plugin.entry_effect", "plugin.lazy_effect_entry" -> LuaKernelOutcome.ValidationFailure(
                    state.id,
                    handle.generation.value,
                    "E_EFFECT_DURING_LOAD",
                )
                "plugin.malformed_callbacks" -> completed(handle, "[\"handle_readiness\"]")
                "plugin.lifecycle" -> completed(
                    handle,
                    "[\"startup\",\"handle_lifecycle\",\"handle_readiness\",\"handle_input\",\"handle_sos\"]",
                )
                "plugin.proactive" -> completed(handle, "[\"startup\"]")
                else -> completed(handle, "[\"startup\",\"handle_readiness\",\"handle_input\"]")
            }
        }

        override fun invokeStartupCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            config: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            val state = state(handle)
            if (state.closed) return closed(handle)
            callbackNames += callbackHandle.name
            callbackCalls += CallbackCall(callbackHandle.name, config)
            events += "callback:${state.id}:${callbackHandle.name}"
            val isProactive = scenarios[state.id] == "plugin.proactive"
            val value = if (isProactive) null else "{\"input\":{\"max_duration_ms\":60000}}"
            return admitSpawned(
                "startup",
                completed(
                    handle,
                    value = value,
                    spawnedCoroutines = if (isProactive) listOf(state.id * 100L) else null,
                ),
                spawnAdmission,
            )
        }

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            val state = state(handle)
            if (state.closed) return closed(handle)
            callbackNames += callbackHandle.name
            callbackCalls += CallbackCall(callbackHandle.name, arguments)
            events += "callback:${state.id}:${callbackHandle.name}"
            if (callbackHandle.name == "handle_readiness" && readinessSeen.add(state.id)) {
                firstReadinessObserved += 1
                firstReadinessObserver?.invoke("channel".takeIf { scenarios[state.id] == "plugin.race_control" } ?: "package-channel")
            }
            return admitSpawned(
                callbackHandle.name,
                when (callbackHandle.name) {
                    "handle_readiness" -> completed(handle, "{\"ready\":true}")
                    "handle_input" -> if (scenarios[state.id] == "plugin.normalized_error") {
                        completed(handle, "{\"error\":{\"code\":\"E_INVALID_VALUE\",\"detail\":\"invalid input\"}}")
                    } else {
                        completed(handle, "{\"ok\":true}")
                    }
                    else -> completed(handle)
                },
                spawnAdmission,
            )
        }
        override fun invokeSosCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = invokeCallback(handle, callbackHandle, arguments, spawnAdmission)
        override fun invokeInputCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            capturedAudioToken: String,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            val state = state(handle)
            if (state.closed) return closed(handle)
            callbackNames += callbackHandle.name
            callbackCalls += CallbackCall(callbackHandle.name, arguments)
            events += "callback:${state.id}:${callbackHandle.name}"
            return admitSpawned(
                callbackHandle.name,
                if (scenarios[state.id] == "plugin.normalized_error") {
                    completed(handle, "{\"error\":{\"code\":\"E_INVALID_VALUE\",\"detail\":\"invalid input\"}}")
                } else {
                    completed(handle, "{\"ok\":true}")
                },
                spawnAdmission,
            )
        }


        override fun startCoroutine(
            handle: LuaStateHandle,
            coroutineId: LuaCoroutineId,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            events += "coroutine:${handle.stateId.value}:${coroutineId.value}"
            return admitSpawned("start:${coroutineId.value}", completed(handle), spawnAdmission)
        }

        private fun state(handle: LuaStateHandle): State = states[handle.stateId.value]
            ?: error("unknown recording state ${handle.stateId.value}")

        private fun completed(
            handle: LuaStateHandle,
            value: String? = null,
            spawnedCoroutines: List<Long>? = null,
        ): LuaKernelOutcome.Completed = LuaKernelOutcome.Completed(
            stateId = handle.stateId.value,
            generation = handle.generation.value,
            coroutineId = null,
            value = value,
            elapsedNanos = null,
            luaVersion = LUA_VERSION,
            bindingVersion = API_VERSION,
            topology = "recording",
            spawnedCoroutines = spawnedCoroutines,
        )

        private fun admitSpawned(
            caller: String,
            outcome: LuaKernelOutcome.Completed,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome.Completed {
            val spawned = outcome.spawnedCoroutines ?: run {
                events += "admission:$caller:0"
                return outcome
            }
            val admitted = ArrayList<Long>(spawned.size)
            spawned.forEach { coroutineId ->
                if (spawnAdmission.admitTask(coroutineId) == 0) admitted += coroutineId
            }
            events += "admission:$caller:${admitted.size}"
            return if (admitted == spawned) outcome else outcome.copy(spawnedCoroutines = admitted)
        }

        private fun closed(handle: LuaStateHandle): LuaKernelOutcome.Closed = LuaKernelOutcome.Closed(
            handle.stateId.value,
            handle.generation.value,
        )
    }


    private object NoCapabilities : ChannelCapabilityHost {
        override suspend fun availability(identity: io.talkcan.channel.capability.CapabilityScopeIdentity, key: CapabilityKey<*>) =
            CapabilityAvailability.Available

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
}
