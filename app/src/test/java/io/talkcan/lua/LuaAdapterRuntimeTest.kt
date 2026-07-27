package io.talkcan.lua

import io.talkcan.audio.ChannelAudioInputSession
import io.talkcan.audio.ChannelInputAcceptance
import io.talkcan.audio.ChannelInputResult
import io.talkcan.audio.RecordedPcm
import io.talkcan.channel.capability.CapabilityAvailability
import io.talkcan.channel.capability.CapabilityKey
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.CapabilityScopeTerminationResult
import io.talkcan.channel.capability.CapabilityUnavailableReason
import io.talkcan.channel.capability.ChannelCapability
import io.talkcan.channel.capability.ChannelCapabilityHost
import io.talkcan.channel.capability.ChannelCapabilityPort
import io.talkcan.channel.capability.HostedCapabilityAcquisition
import io.talkcan.channel.capability.RevocableChannelCapabilityScope
import io.talkcan.dependency.PackageCapability
import io.talkcan.channel.capability.RuntimeGeneration
import io.talkcan.channel.capability.OpaqueSynthesizedAudio
import io.talkcan.channel.capability.CapabilityOperationResult
import io.talkcan.channel.capability.AudioOperationCapability
import io.talkcan.channel.capability.TranscriptionCapability
import io.talkcan.channel.capability.TextOutputCapability
import io.talkcan.channel.capability.KeyboardOutputAdapter
import io.talkcan.channel.capability.KeyboardOutputSubmission
import io.talkcan.channel.capability.OutputExecutionOwner
import io.talkcan.channel.capability.OutputExecutionOwnerKind
import io.talkcan.channel.capability.TextDeliveryOutcome
import io.talkcan.channel.capability.TextKeyRequest
import io.talkcan.channel.capability.TextOutputFailureReason
import io.talkcan.channel.capability.TextOutputIndeterminateReason
import io.talkcan.channel.capability.TextOutputKey
import io.talkcan.channel.capability.TextOutputProfile
import io.talkcan.channel.capability.TextOutputRejectionReason
import io.talkcan.channel.capability.TextOutputRequest
import io.talkcan.channel.capability.SynthesisCapability
import io.talkcan.channel.capability.Transcription
import io.talkcan.channel.capability.SpeechSynthesisRequest
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import io.talkcan.channel.capability.OpaqueAudioOperation
import io.talkcan.channel.capability.OpaqueAudioRecording
import io.talkcan.channel.capability.DeferredAudioPlaybackCapability
import io.talkcan.channel.capability.AgentOperationContext
import io.talkcan.channel.capability.AudioOperationArtifact
import io.talkcan.model.DelayedPlaybackOperationId
import io.talkcan.model.DelayedPlaybackOutcome
import io.talkcan.lua.actor.ActorRuntimeFactory
import io.talkcan.model.ChannelDefinition
import io.talkcan.dependency.ConfigurationDataDeclaration
import io.talkcan.dependency.ConfigurationUiDeclaration
import io.talkcan.dependency.PackageConfigurationDeclaration
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelPresentationMetadata
import io.talkcan.model.ChannelRuntimeConstructionRequest
import io.talkcan.model.ChannelRuntimeConstructionResult
import io.talkcan.model.GenerationExecutionContextImpl
import io.talkcan.model.GenerationAdmission
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.model.ProviderRevisionFingerprint
import io.talkcan.model.ValidatedChannelConfiguration
import io.talkcan.service.ChannelActivationResult
import io.talkcan.service.ChannelExecutionStatus
import io.talkcan.service.ChannelPreparationAvailability
import io.talkcan.service.ChannelPreparationReason
import io.talkcan.service.RuntimeInvocationBoundary
import io.talkcan.service.RuntimeWorkerDispatcher
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 */
private fun testLuaProvider(
    image: ImmutableProgramImage,
    bridge: LuaKernelBridge,
    logSink: PluginLogSink = NoOpPluginLogSink,
    storagePort: io.talkcan.storage.MountedStoragePort? = null,
    resourceDeclarations: io.talkcan.dependency.PackageResourcesDeclaration =
        io.talkcan.dependency.PackageResourcesDeclaration(emptyList()),
    audioFilePortFactory: LuaAudioFilePortFactory? = null,
    mountReadinessStatus: LuaMountReadinessStatus? = null,
    preparerRegistry: io.talkcan.channel.capability.CapabilityPreparerRegistry =
        io.talkcan.channel.capability.CapabilityPreparerRegistry.empty(),
    declaredPublicCapabilities: Set<String> = emptySet(),
    keyboardOutputAdapterFactory: ((CapabilityScopeIdentity, OutputExecutionOwner) -> KeyboardOutputAdapter)? = null,
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
    logSink = logSink,
    configurationProvider = CompiledConfigurationProvider(TEST_LUA_IMPLEMENTATION_ID, emptyConfigurationDeclaration()),
    resourceDeclarations = resourceDeclarations,
    storagePort = storagePort,
    audioFilePortFactory = audioFilePortFactory,
    mountReadinessStatus = mountReadinessStatus,
    preparerRegistry = preparerRegistry,
    declaredPublicCapabilities = declaredPublicCapabilities,
    keyboardOutputAdapterFactory = keyboardOutputAdapterFactory,
)

/**
 * Behavioral tests for the provider-neutral adapter seam. The retained bridge
 * is scripted only at the native boundary: all assertions exercise adapter
 * callbacks, snapshots, input targets, and close behavior through public
 * [io.talkcan.service.ChannelRuntime] operations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LuaAdapterRuntimeTest {

    @Test
    fun `activation invokes startup before ready lifecycle callback and publishes ready`() = runTest {
        val bridge = RecordingBridge()
        val harness = harness(bridge, setOf("startup", "handle_lifecycle"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            assertEquals(
                "startup must cross the bridge with one normalized configuration argument: {schema_version=1, values={}}.",
                LuaValue.Map(mapOf(
                    "schema_version" to LuaValue.Integer(1L),
                    "values" to LuaValue.Map(emptyMap()),
                )),
                bridge.callbackCalls[0].arguments,
            )

            assertEquals(
                listOf("startup", "handle_lifecycle"),
                bridge.callbackCalls.map { it.name },
            )
            assertEquals(
                LuaValue.Map(mapOf("event" to LuaValue.StringValue("ready"))),
                bridge.callbackCalls[1].arguments,
            )
            assertEquals(ChannelPreparationAvailability.Available, harness.runtime.snapshot.value.preparation)
            assertEquals(ChannelExecutionStatus.IDLE, harness.runtime.snapshot.value.executionStatus)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `startup bridge runtime failure fails activation atomically without lifecycle`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("startup", LuaKernelOutcome.RuntimeFailure(41, 7, "bridge error"))
        }
        val harness = harness(bridge, setOf("startup", "handle_lifecycle"))
        try {
            assertTrue(
                "A bridge RuntimeFailure during startup must fail activation.",
                harness.runtime.activate() is ChannelActivationResult.Failed,
            )
            assertEquals(
                "startup must be the only callback invoked; handle_lifecycle must not run after startup failure.",
                listOf("startup"),
                bridge.callbackCalls.map { it.name },
            )
            assertEquals(
                ChannelExecutionStatus.FAILED,
                harness.runtime.snapshot.value.executionStatus,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `activation publishes ready after startup and lifecycle before authorizing staged tasks`() = runTest {
        val events = mutableListOf<String>()
        val taskStarted = CompletableDeferred<Unit>()
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(301)))
            beforeCallback = { name -> events += name }
            onCoroutineStarted = { coroutineId ->
                events += "task:$coroutineId"
                taskStarted.complete(Unit)
            }
        }
        val harness = harness(bridge, setOf("startup", "handle_lifecycle"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            assertEquals(
                "Ready must be published only after both activation callbacks complete.",
                ChannelPreparationAvailability.Available,
                harness.runtime.snapshot.value.preparation,
            )
            assertEquals(
                "Startup-admitted work must remain staged until the Ready publication is complete.",
                listOf("startup", "handle_lifecycle"),
                events,
            )
            assertTrue(bridge.startedCoroutines.isEmpty())

            events += "ready"
            harness.authorizeStagedTasks()
            taskStarted.await()

            assertEquals(
                listOf("startup", "handle_lifecycle", "ready", "task:301"),
                events,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `startup activation failure discards every admitted task without publishing ready`() = runTest {
        lateinit var harness: AdapterHarness
        val events = mutableListOf<String>()
        val taskRan = AtomicInteger()
        val bridge = RecordingBridge().apply {
            enqueue("startup", LuaKernelOutcome.RuntimeFailure(41, 7, "startup failed"))
            beforeCallback = { name ->
                events += name
                if (name == "startup") {
                    assertTrue(
                        "A task admitted during startup must be accepted before startup reports failure.",
                        harness.stageTask { taskRan.incrementAndGet() } is GenerationAdmission.Accepted,
                    )
                }
            }
        }
        harness = harness(bridge, setOf("startup", "handle_lifecycle"))
        try {
            assertTrue(harness.runtime.activate() is ChannelActivationResult.Failed)
            assertEquals(listOf("startup"), events)
            assertFalse(harness.runtime.snapshot.value.preparation is ChannelPreparationAvailability.Available)

            harness.authorizeStagedTasks()
            assertEquals(0, taskRan.get())
            assertTrue(bridge.startedCoroutines.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `lifecycle activation failure discards startup tasks without publishing ready`() = runTest {
        val taskStarted = AtomicInteger()
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(302)))
            enqueue("handle_lifecycle", LuaKernelOutcome.RuntimeFailure(41, 7, "lifecycle failed"))
            onCoroutineStarted = { taskStarted.incrementAndGet() }
        }
        val harness = harness(bridge, setOf("startup", "handle_lifecycle"))
        try {
            assertTrue(harness.runtime.activate() is ChannelActivationResult.Failed)
            assertEquals(
                listOf("startup", "handle_lifecycle"),
                bridge.callbackCalls.map { it.name },
            )
            assertFalse(harness.runtime.snapshot.value.preparation is ChannelPreparationAvailability.Available)

            harness.authorizeStagedTasks()
            assertEquals(0, taskStarted.get())
            assertTrue(
                "A startup-admitted task must be discarded before any coroutine can start.",
                bridge.startedCoroutines.isEmpty(),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `startup configuration is a normalized detached snapshot with schema_version and values`() = runTest {
        val bridge = RecordingBridge()
        val harness = harness(bridge, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            val config = bridge.callbackCalls[0].arguments
            val map = when (config) {
                is LuaValue.Map -> config.pairs
                else -> throw AssertionError(
                    "Startup argument must be a LuaValue.Map, got ${config?.let { it::class.simpleName }}.",
                )
            }
            assertTrue(
                "Startup config must contain schema_version key.",
                map.containsKey("schema_version"),
            )
            assertEquals(
                "Startup config schema_version must be 1.",
                LuaValue.Integer(1L),
                map["schema_version"],
            )
            assertTrue(
                "Startup config must contain values key.",
                map.containsKey("values"),
            )
            val values = map["values"]
            assertTrue(
                "Startup config values must be a LuaValue.Map: got ${values?.let { it::class.simpleName }}.",
                values is LuaValue.Map,
            )
            assertEquals(
                "Startup config must have exactly two keys (schema_version, values).",
                2,
                map.size,
            )
        } finally {
            harness.close()
        }
    }


    @Test
    fun `startup snapshot values map is empty for a payload-free configuration`() = runTest {
        val bridge = RecordingBridge()
        val harness = harness(bridge, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            val config = bridge.callbackCalls[0].arguments as? LuaValue.Map
                ?: throw AssertionError("Startup argument must be a LuaValue.Map")
            val values = config.pairs["values"] as? LuaValue.Map
                ?: throw AssertionError("Startup config 'values' must be a LuaValue.Map")
            assertEquals(
                "Values map must be empty for a payload-free configuration.",
                0,
                values.pairs.size,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `independent runtimes receive distinct isolated startup snapshots`() = runTest {
        val bridge1 = RecordingBridge()
        val harness1 = harness(bridge1, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness1.runtime.activate())
            val config1 = bridge1.callbackCalls[0].arguments as? LuaValue.Map
                ?: throw AssertionError("Harness 1: startup argument must be a LuaValue.Map")

            val bridge2 = RecordingBridge()
            val harness2 = harness(bridge2, setOf("startup"))
            try {
                assertEquals(ChannelActivationResult.Ready, harness2.runtime.activate())
                val config2 = bridge2.callbackCalls[0].arguments as? LuaValue.Map
                    ?: throw AssertionError("Harness 2: startup argument must be a LuaValue.Map")

                assertTrue(
                    "Each runtime must receive its own detached config instance.",
                    config1 !== config2,
                )
                assertEquals(
                    "Independent configs must be structurally identical.",
                    config1,
                    config2,
                )

                val values1 = config1.pairs["values"] as? LuaValue.Map
                    ?: throw AssertionError("Config 1: 'values' must be a LuaValue.Map")
                val values2 = config2.pairs["values"] as? LuaValue.Map
                    ?: throw AssertionError("Config 2: 'values' must be a LuaValue.Map")
                assertTrue(
                    "Each runtime's values sub-map must be independently allocated.",
                    values1 !== values2,
                )
            } finally {
                harness2.close()
            }
        } finally {
            harness1.close()
        }
    }

    @Test
    fun `different host runtime generations isolate their startup snapshot copies`() = runTest {
        val bridge1 = RecordingBridge()
        val harness1 = harness(
            bridge = bridge1,
            callbacks = setOf("startup"),
            hostRuntimeGeneration = RuntimeGeneration(7),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness1.runtime.activate())
            val config1 = bridge1.callbackCalls[0].arguments as? LuaValue.Map
                ?: throw AssertionError("Harness 1: startup argument must be a LuaValue.Map")

            val bridge2 = RecordingBridge()
            val harness2 = harness(
                bridge = bridge2,
                callbacks = setOf("startup"),
                hostRuntimeGeneration = RuntimeGeneration(8),
            )
            try {
                assertEquals(ChannelActivationResult.Ready, harness2.runtime.activate())
                val config2 = bridge2.callbackCalls[0].arguments as? LuaValue.Map
                    ?: throw AssertionError("Harness 2: startup argument must be a LuaValue.Map")

                assertTrue(
                    "Each host runtime generation must produce a fresh detached config.",
                    config1 !== config2,
                )
                assertEquals(
                    "Generation-isolated configs must be structurally identical.",
                    config1,
                    config2,
                )
            } finally {
                harness2.close()
            }
        } finally {
            harness1.close()
        }
    }

    @Test
    fun `Lua mutation of startup snapshot cannot affect host sibling or later generation`() = runTest {
        val payload = OpaqueJsonObject.parse(
            """{"mode":"host","nested":{"enabled":true}}""",
        ).getOrThrow()
        val originalPayload = payload.toJsonString()
        val bridge1 = RecordingBridge()
        val harness1 = harness(
            bridge = bridge1,
            callbacks = setOf("startup"),
            configPayload = payload,
            hostRuntimeGeneration = RuntimeGeneration(7),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness1.runtime.activate())
            val config1 = bridge1.callbackCalls[0].arguments as? LuaValue.Map
                ?: throw AssertionError("Generation 1 startup argument must be a LuaValue.Map")
            val values1 = config1.pairs["values"] as? LuaValue.Map
                ?: throw AssertionError("Generation 1 values must be a LuaValue.Map")
            val nested1 = values1.pairs["nested"] as? LuaValue.Map
                ?: throw AssertionError("Generation 1 nested value must be a LuaValue.Map")

            // The recording bridge exposes the detached normalized tree. Mutating its
            // parser-backed maps models Lua writes to the startup table.
            @Suppress("UNCHECKED_CAST")
            val mutableNested = nested1.pairs as MutableMap<String, LuaValue>
            mutableNested["enabled"] = LuaValue.Bool(false)
            mutableNested["lua_only"] = LuaValue.StringValue("mutated")

            assertEquals(
                "Lua mutation must not alter the host-owned encoded payload.",
                originalPayload,
                payload.toJsonString(),
            )

            val bridge2 = RecordingBridge()
            val harness2 = harness(
                bridge = bridge2,
                callbacks = setOf("startup"),
                configPayload = payload,
                hostRuntimeGeneration = RuntimeGeneration(7),
            )
            try {
                assertEquals(ChannelActivationResult.Ready, harness2.runtime.activate())
                val config2 = bridge2.callbackCalls[0].arguments as? LuaValue.Map
                    ?: throw AssertionError("Sibling startup argument must be a LuaValue.Map")
                assertEquals(
                    "Lua mutation must not leak into a sibling generation.",
                    LuaValue.Map(mapOf(
                        "schema_version" to LuaValue.Integer(1L),
                        "values" to LuaValue.Map(mapOf(
                            "mode" to LuaValue.StringValue("host"),
                            "nested" to LuaValue.Map(mapOf("enabled" to LuaValue.Bool(true))),
                        )),
                    )),
                    config2,
                )
            } finally {
                harness2.close()
            }
        } finally {
            harness1.close()
        }

        val bridge3 = RecordingBridge()
        val harness3 = harness(
            bridge = bridge3,
            callbacks = setOf("startup"),
            configPayload = payload,
            hostRuntimeGeneration = RuntimeGeneration(8),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness3.runtime.activate())
            val config3 = bridge3.callbackCalls[0].arguments as? LuaValue.Map
                ?: throw AssertionError("Later generation startup argument must be a LuaValue.Map")
            assertEquals(
                "Lua mutation must not leak into a later generation.",
                LuaValue.Map(mapOf(
                    "schema_version" to LuaValue.Integer(1L),
                    "values" to LuaValue.Map(mapOf(
                        "mode" to LuaValue.StringValue("host"),
                        "nested" to LuaValue.Map(mapOf("enabled" to LuaValue.Bool(true))),
                    )),
                )),
                config3,
            )
        } finally {
            harness3.close()
        }
    }

    @Test
    fun `startup bridge validation failure fails activation atomically`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("startup", LuaKernelOutcome.ValidationFailure(41, 7, "invalid callback ownership"))
        }
        val harness = harness(bridge, setOf("startup"))
        try {
            assertTrue(
                "A bridge ValidationFailure during startup must fail activation.",
                harness.runtime.activate() is ChannelActivationResult.Failed,
            )
            assertEquals(
                ChannelExecutionStatus.FAILED,
                harness.runtime.snapshot.value.executionStatus,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `prepare input uses only an explicit cached readiness projection`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
        }
        val harness = harness(bridge, setOf("startup", "handle_readiness", "handle_input"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val callbacksAfterRefresh = bridge.callbackCalls.size

            val accepted = harness.runtime.prepareInput()

            assertTrue("A ready callback plus handle_input must permit capture.", accepted is ChannelInputAcceptance.Accepted)
            assertEquals(
                "prepareInput must decide from the cached readiness projection without another Lua callback.",
                callbacksAfterRefresh,
                bridge.callbackCalls.size,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `readiness refresh publishes recoverable unavailable and available runtime state`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue(
                "handle_readiness",
                completed("""{"ready":false,"status":"keyboard preparing","prepare":["keyboard.output"]}"""),
            )
            enqueue("handle_readiness", completed("""{"ready":false,"status":"keyboard unavailable"}"""))
            enqueue("handle_readiness", completed("""{"ready":true,"status":"ready"}"""))
        }
        val harness = harness(
            bridge = bridge,
            callbacks = setOf("startup", "handle_readiness", "handle_input"),
            declaredCapabilities = setOf(ChannelCapability.TextOutput),
            declaredPublicCapabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
            preparerRegistry = io.talkcan.channel.capability.CapabilityPreparerRegistry.default(),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())

            harness.runtime.refreshReadiness()
            val recoverable = harness.runtime.snapshot.value.preparation
                as? ChannelPreparationAvailability.Recoverable
                ?: throw AssertionError("Expected recoverable readiness")
            assertEquals("keyboard preparing", recoverable.reason.message)

            harness.runtime.refreshReadiness()
            val unavailable = harness.runtime.snapshot.value.preparation
                as? ChannelPreparationAvailability.Unavailable
                ?: throw AssertionError("Expected unavailable readiness")
            assertEquals("keyboard unavailable", unavailable.reason.message)

            harness.runtime.refreshReadiness()
            assertEquals(
                ChannelPreparationAvailability.Available,
                harness.runtime.snapshot.value.preparation,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `readiness context contains exactly declared public capabilities and projects valid status`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true,"status":"STT"}"""))
        }
        val harness = harness(
            bridge = bridge,
            callbacks = setOf("startup", "handle_readiness", "handle_input"),
            declaredCapabilities = setOf(
                ChannelCapability.Transcription,
                ChannelCapability.Synthesis,
                ChannelCapability.AudioOperation,
                ChannelCapability.DeferredAudioPlayback,
                ChannelCapability.StorageFiles,
            ),
            capabilityAvailability = mapOf(
                ChannelCapability.Transcription to CapabilityAvailability.Available,
                ChannelCapability.Synthesis to CapabilityAvailability.Recoverable,
                ChannelCapability.AudioOperation to CapabilityAvailability.Unavailable(
                    io.talkcan.channel.capability.CapabilityUnavailableReason.HOST_NOT_READY,
                ),
            ),
            storagePort = io.talkcan.storage.Vfs().fs,
            resourceDeclarations = fsResourceDeclarations(),
            mountReadinessStatus = LuaMountReadinessStatus { "read-only" },
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()

            val context = bridge.callbackCalls.last { it.name == "handle_readiness" }.arguments as? LuaValue.Map
                ?: throw AssertionError("Readiness callback must receive a context map")
            assertEquals(setOf("capabilities", "resources"), context.pairs.keys)
            val capabilities = context.pairs["capabilities"] as? LuaValue.Map
                ?: throw AssertionError("Readiness context must contain a capabilities map")
            assertEquals(
                setOf(
                    PackageCapability.AUDIO_TRANSCRIPTION,
                    PackageCapability.AUDIO_SYNTHESIS,
                    PackageCapability.AUDIO_PLAYBACK,
                    PackageCapability.STORAGE_FILES,
                ),
                capabilities.pairs.keys,
            )
            assertEquals(
                LuaValue.StringValue("available"),
                capabilities.pairs[PackageCapability.AUDIO_TRANSCRIPTION],
            )
            assertEquals(
                LuaValue.StringValue("recoverable"),
                capabilities.pairs[PackageCapability.AUDIO_SYNTHESIS],
            )
            assertEquals(
                LuaValue.StringValue("unavailable"),
                capabilities.pairs[PackageCapability.AUDIO_PLAYBACK],
            )
            assertEquals(
                LuaValue.StringValue("available"),
                capabilities.pairs[PackageCapability.STORAGE_FILES],
            )
            val resources = context.pairs["resources"] as? LuaValue.Map
                ?: throw AssertionError("Readiness context must contain resources")
            val mounts = resources.pairs["mounts"] as? LuaValue.Map
                ?: throw AssertionError("Readiness resources must contain mounts")
            assertEquals(
                mapOf("output" to LuaValue.StringValue("read-only")),
                mounts.pairs,
            )
            assertEquals("STT", harness.runtime.snapshot.value.summary)
            assertTrue(harness.runtime.prepareInput() is ChannelInputAcceptance.Accepted)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `malformed readiness results cache not-ready and one bounded diagnostic`() = runTest {
        val malformedResults = listOf(
            "non-table" to "true",
            "error-table" to """{"error":{"code":"E_READY","detail":"failed"}}""",
            "missing-ready" to "{}",
            "non-boolean-ready" to """{"ready":"true"}""",
            "unknown-key" to """{"ready":true,"extra":1}""",
            "non-string-status" to """{"ready":true,"status":1}""",
            "over-bound-status" to """{"ready":true,"status":"${"x".repeat(65_537)}"}""",
        )
        for ((name, malformed) in malformedResults) {
            val bridge = RecordingBridge().apply {
                enqueue("handle_readiness", completed("""{"ready":true,"status":"prior"}"""))
                enqueue("handle_readiness", completed(malformed))
            }
            val harness = harness(
                bridge = bridge,
                callbacks = setOf("startup", "handle_readiness", "handle_input"),
                declaredCapabilities = setOf(ChannelCapability.Transcription),
                capabilityAvailability = mapOf(ChannelCapability.Transcription to CapabilityAvailability.Available),
            )
            try {
                assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
                harness.runtime.refreshReadiness()
                assertEquals("prior", harness.runtime.snapshot.value.summary)
                harness.runtime.refreshReadiness()
                assertTrue(
                    "$name: malformed readiness must publish unavailable runtime state",
                    harness.runtime.snapshot.value.preparation is ChannelPreparationAvailability.Unavailable,
                )
                assertTrue(
                    "$name: malformed readiness must refuse input",
                    harness.runtime.prepareInput() is ChannelInputAcceptance.Refused,
                )
                assertEquals("$name: invalid result must retain prior bounded summary", "prior", harness.runtime.snapshot.value.summary)
                assertEquals("$name: exactly one local diagnostic per malformed refresh", 1, harness.runtime.localDiagnosticSnapshot.size)
                assertTrue(
                    "$name: diagnostic must be bounded",
                    harness.runtime.localDiagnosticSnapshot.single().length <= 512,
                )
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun `proactive only runtime remains active but exposes neither input nor SOS callback`() = runTest {
        val bridge = RecordingBridge()
        val harness = harness(bridge, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            assertTrue(
                "A plugin without readiness must publish unavailable runtime state.",
                harness.runtime.snapshot.value.preparation is ChannelPreparationAvailability.Unavailable,
            )
            val snapshotBeforeSos = harness.runtime.snapshot.value
            val acceptance = harness.runtime.prepareInput()
            harness.runtime.handleSos()

            assertTrue("A plugin without handle_input must refuse capture.", acceptance is ChannelInputAcceptance.Refused)
            assertEquals(
                "Missing optional callbacks are neutral and must not enter Lua.",
                listOf("startup"),
                bridge.callbackCalls.map { it.name },
            )
            assertEquals(snapshotBeforeSos, harness.runtime.snapshot.value)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `input outcomes are strict and host capture terminal paths never play audio`() = runTest {
        val cases = listOf(
            InputCase("exact success", """{"ok":true}""", ChannelExecutionStatus.SUCCESS),
            InputCase(
                "exact application failure",
                """{"error":{"code":"E_CAPTURE_FAILURE","detail":"processing failed"}}""",
                ChannelExecutionStatus.FAILED,
            ),
            InputCase("ambiguous success and failure", """{"ok":true,"error":{"code":"E","detail":"d"}}""", ChannelExecutionStatus.FAILED),
            InputCase("false success", """{"ok":false}""", ChannelExecutionStatus.FAILED),
            InputCase("malformed failure detail", """{"error":{"code":"E","detail":""}}""", ChannelExecutionStatus.FAILED),
            InputCase("unrecognized table", """{"action":"confirm"}""", ChannelExecutionStatus.FAILED),
            InputCase("non table result", "true", ChannelExecutionStatus.FAILED),
        )

        for (case in cases) {
            val bridge = RecordingBridge().apply {
                enqueue("handle_readiness", completed("""{"ready":true}"""))
                enqueue("handle_input", completed(case.callbackResult))
            }
            val harness = harness(bridge, setOf("startup", "handle_readiness", "handle_input"))
            try {
                assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
                harness.runtime.refreshReadiness()
                val target = (harness.runtime.prepareInput() as? ChannelInputAcceptance.Accepted)?.target
                    ?: throw AssertionError("${case.name}: expected accepted input")

                target.onInputStarted(session(sampleRate = 16_000))
                val result = target.onInputReleased(RecordedPcm(ShortArray(8_000), 16_000))

                assertEquals("${case.name}: v1 never returns playback", ChannelInputResult.None, result)
                assertEquals(case.expectedStatus, harness.runtime.snapshot.value.executionStatus)

                val inputEvent = bridge.callbackCalls.last { it.name == "handle_input" }.arguments as LuaValue.Map
                assertEquals(
                    "Only host-domain event identity, per-capture session, timestamp, and metadata may cross into Lua.",
                    setOf("event", "session", "timestamp", "metadata"),
                    inputEvent.pairs.keys,
                )
                assertEquals(LuaValue.StringValue("capture"), inputEvent.pairs["event"])
                assertTrue(inputEvent.pairs["session"] is LuaValue.StringValue)
                val timestamp = inputEvent.pairs["timestamp"] as? LuaValue.Map
                    ?: throw AssertionError("${case.name}: timestamp must be a map")
                assertEquals(setOf("unix_ms", "local_time"), timestamp.pairs.keys)
                val localTime = timestamp.pairs["local_time"] as? LuaValue.Map
                    ?: throw AssertionError("${case.name}: local_time must be a map")
                assertEquals(
                    setOf(
                        "year", "month", "day", "hour", "minute", "second",
                        "millisecond", "utc_offset_minutes",
                    ),
                    localTime.pairs.keys,
                )
                fun number(name: String): Int =
                    ((localTime.pairs[name] as? LuaValue.Integer)?.value
                        ?: throw AssertionError("${case.name}: $name must be a Lua integer")).toInt()
                val offset = java.time.ZoneOffset.ofTotalSeconds(number("utc_offset_minutes") * 60)
                val reconstructed = java.time.OffsetDateTime.of(
                    number("year"),
                    number("month"),
                    number("day"),
                    number("hour"),
                    number("minute"),
                    number("second"),
                    number("millisecond") * 1_000_000,
                    offset,
                ).toInstant().toEpochMilli()
                assertEquals(
                    "${case.name}: unix_ms must exactly match the reconstructed wall-clock instant",
                    reconstructed,
                    (timestamp.pairs["unix_ms"] as LuaValue.Integer).value,
                )
                val metadata = inputEvent.pairs["metadata"] as? LuaValue.Map
                    ?: throw AssertionError("${case.name}: metadata must be a map")
                assertEquals(
                    setOf("duration_ms", "sample_rate", "channels", "pcm_bytes"),
                    metadata.pairs.keys,
                )
                assertEquals(LuaValue.Integer(500L), metadata.pairs["duration_ms"])
                assertEquals(LuaValue.Integer(16_000L), metadata.pairs["sample_rate"])
                assertEquals(LuaValue.Integer(1L), metadata.pairs["channels"])
                assertEquals(LuaValue.Integer(16_000L), metadata.pairs["pcm_bytes"])
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun `capture admits opaque recording before handing token to handle input`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_input", completed("""{"ok":true}"""))
        }
        val harness = harness(bridge, setOf("startup", "handle_readiness", "handle_input"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime)
            target.onInputStarted(session(16_000))
            assertEquals(ChannelInputResult.None, target.onInputReleased(RecordedPcm(ShortArray(8), 16_000)))

            val callback = bridge.callbackCalls.last { it.name == "handle_input" }
            assertTrue("A captured recording must be represented by a non-empty opaque token.", !callback.capturedAudioToken.isNullOrBlank())
            val event = callback.arguments as? LuaValue.Map
                ?: throw AssertionError("handle_input must receive a capture event")
            assertEquals(LuaValue.StringValue("capture"), event.pairs["event"])
        } finally {
            harness.close()
        }
    }


    @Test
    fun `failed input callback disposes admitted capture token`() = runTest {
        val bridge = RecordingBridge().apply {
            throwInputCallback = true
            enqueue("handle_readiness", completed("""{"ready":true}"""))
        }
        val harness = harness(bridge, setOf("startup", "handle_readiness", "handle_input"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime)
            target.onInputStarted(session(16_000))
            target.onInputReleased(RecordedPcm(ShortArray(8), 16_000))
            assertEquals(ChannelExecutionStatus.FAILED, harness.runtime.snapshot.value.executionStatus)

            val callback = bridge.callbackCalls.last { it.name == "handle_input" }
            val token = checkNotNull(callback.capturedAudioToken)
            val event = callback.arguments as? LuaValue.Map
                ?: throw AssertionError("handle_input must receive a capture event")
            val sessionId = (event.pairs["session"] as? LuaValue.StringValue)?.value
                ?: throw AssertionError("capture event must contain its session owner")
            val registryField = LuaAdapterRuntime::class.java.getDeclaredField("audioRegistry")
            registryField.isAccessible = true
            val registry = checkNotNull(registryField.get(harness.runtime)) as LuaOpaqueAudioRegistry
            assertEquals(
                LuaOpaqueAudioRegistry.Resolution.Stale,
                registry.resolve(
                    LuaOpaqueAudioRegistry.Token(token),
                    LuaOpaqueAudioRegistry.Owner.Input(sessionId),
                    LuaOpaqueAudioRegistry.Kind.Captured,
                ),
            )
        } finally {
            harness.close()
        }
    }
    @Test
    fun `host capture cancellation and failure change snapshots without entering handle input`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
        }
        val harness = harness(bridge, setOf("startup", "handle_readiness", "handle_input"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()

            val cancelled = acceptedTarget(harness.runtime)
            cancelled.onInputStarted(session(16_000))
            cancelled.onInputCancelled("host cancellation")
            assertEquals(ChannelExecutionStatus.IDLE, harness.runtime.snapshot.value.executionStatus)

            val failed = acceptedTarget(harness.runtime)
            failed.onInputStarted(session(16_000))
            failed.onInputFailed("microphone failed")
            assertEquals(ChannelExecutionStatus.FAILED, harness.runtime.snapshot.value.executionStatus)
            assertFalse(
                "Host-terminal capture paths must not invoke Lua input processing.",
                bridge.callbackCalls.any { it.name == "handle_input" },
            )
        } finally {
            harness.close()
        }
    }
    @Test
    fun `cancelled semantic input resumes once with E_CANCELLED and disposes capture before late playback`() = runTest {
        val playback = ControlledDeferredPlayback()
        val operation = AudioOperationArtifact(RecordedPcm(shortArrayOf(3, -3), 16_000), operationId = "cancelled-input", generation = RuntimeGeneration(7))
        val audio = object : AudioOperationCapability {
            override suspend fun createPlaybackResult(audio: OpaqueSynthesizedAudio) = CapabilityOperationResult.Success<OpaqueAudioOperation>(operation)
            override suspend fun createPlaybackResult(audio: OpaqueAudioRecording) = CapabilityOperationResult.Success<OpaqueAudioOperation>(operation)
        }
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_input", yielded(value = registerClaim(HostOperationKind.PLAYBACK, audioToken = "{token}", delaySeconds = 0.0)))
        }
        val harness = harness(
            bridge,
            setOf("startup", "handle_readiness", "handle_input"),
            audioOperation = audio,
            deferredAudioPlayback = playback,
            declaredCapabilities = setOf(ChannelCapability.AudioOperation, ChannelCapability.DeferredAudioPlayback),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime)
            target.onInputStarted(session(16_000))
            val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }
            runCurrent()

            val pending = checkNotNull(harness.runtime.pendingInput())
            val callback = bridge.callbackCalls.last { it.name == "handle_input" }
            val token = LuaOpaqueAudioRegistry.Token(checkNotNull(callback.capturedAudioToken))
            val registry = audioRegistry(harness.runtime)
            val entriesField = LuaOpaqueAudioRegistry::class.java.getDeclaredField("entries").apply { isAccessible = true }
            val entry = ((entriesField.get(registry) as Map<*, *>)[token.value])
                ?: throw AssertionError("capture token must be retained while playback is suspended")
            val captured = entry.javaClass.getDeclaredField("recording").apply { isAccessible = true }.get(entry)
            assertEquals(1, playback.calls)

            target.onInputCancelled("host cancellation")
            runCurrent()
            assertEquals(listOf(false to "E_CANCELLED"), bridge.resumeCalls)
            assertNull(harness.runtime.pendingInput())
            assertEquals(ChannelInputResult.None, release.await())
            assertTrue("Live cancellation must dispose the unconsumed capture.", captured.javaClass.getDeclaredField("disposed").apply { isAccessible = true }.getBoolean(captured))
            assertFalse("Cancelled capture must not remain queued for consumption.", registry.owns(token))
            assertEquals(
                LuaOpaqueAudioRegistry.Resolution.Stale,
                registry.resolve(token, LuaOpaqueAudioRegistry.Owner.Input("late"), LuaOpaqueAudioRegistry.Kind.Captured),
            )

            playback.release(DelayedPlaybackOutcome.Heard(DelayedPlaybackOperationId("late")))
            runCurrent()
            assertEquals("Late playback must not re-enter Lua.", listOf(false to "E_CANCELLED"), bridge.resumeCalls)
            assertEquals("Late playback must not consume an invalidated capture.", 0, registry.accounting().liveTokens)
            assertEquals(
                LuaInputResumeResult.Stale,
                harness.runtime.resumeInput(pending.ownerToken, pending.operationId, true, "late"),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `yielded synchronous callbacks are contained by their callback specific host policy`() = runTest {
        val startupBridge = RecordingBridge().apply {
            enqueue("startup", yielded())
        }
        val startupHarness = harness(startupBridge, setOf("startup"))
        try {
            assertTrue(startupHarness.runtime.activate() is ChannelActivationResult.Failed)
            assertEquals(ChannelExecutionStatus.FAILED, startupHarness.runtime.snapshot.value.executionStatus)
        } finally {
            startupHarness.close()
        }

        val readinessBridge = RecordingBridge().apply {
            enqueue("handle_readiness", yielded())
        }
        val readinessHarness = harness(readinessBridge, setOf("startup", "handle_readiness", "handle_input"))
        try {
            assertEquals(ChannelActivationResult.Ready, readinessHarness.runtime.activate())
            readinessHarness.runtime.refreshReadiness()
            assertTrue(
                "A yielded readiness callback must be locally contained as not-ready.",
                readinessHarness.runtime.prepareInput() is ChannelInputAcceptance.Refused,
            )
            assertEquals(ChannelExecutionStatus.IDLE, readinessHarness.runtime.snapshot.value.executionStatus)
        } finally {
            readinessHarness.close()
        }

    }

    @Test
    fun `input coroutine releases slot and resumes only current owner with exact terminal`() = runTest {
        val playback = ControlledDeferredPlayback()
        val operation = AudioOperationArtifact(RecordedPcm(shortArrayOf(2), 16_000), operationId = "owner-terminal", generation = RuntimeGeneration(7))
        val audio = object : AudioOperationCapability {
            override suspend fun createPlaybackResult(audio: OpaqueSynthesizedAudio) = CapabilityOperationResult.Success<OpaqueAudioOperation>(operation)
            override suspend fun createPlaybackResult(audio: OpaqueAudioRecording) = CapabilityOperationResult.Success<OpaqueAudioOperation>(operation)
        }
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_input", yielded(coroutineId = 51, operationId = 601, value = registerClaim(HostOperationKind.PLAYBACK, audioToken = "{token}", delaySeconds = 0.0)))
            resumeOutcomes += yielded(coroutineId = 52, operationId = 602, value = registerClaim(HostOperationKind.PLAYBACK, audioToken = "{token}", delaySeconds = 0.0))
            resumeOutcomes += completed("""{"ok":true}""")
        }
        val harness = harness(
            bridge,
            setOf("startup", "handle_readiness", "handle_input"),
            audioOperation = audio,
            deferredAudioPlayback = playback,
            declaredCapabilities = setOf(ChannelCapability.AudioOperation, ChannelCapability.DeferredAudioPlayback),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime)
            target.onInputStarted(session(16_000))
            val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }
            runCurrent()

            val firstPending = checkNotNull(harness.runtime.pendingInput())
            assertEquals(601L, firstPending.operationId)
            assertEquals(
                LuaInputResumeResult.ForeignOwner,
                harness.runtime.resumeInput("foreign-owner", firstPending.operationId, true, "{}"),
            )
            assertEquals(
                LuaInputResumeResult.Stale,
                harness.runtime.resumeInput(firstPending.ownerToken, firstPending.operationId + 1L, true, "{}"),
            )
            assertTrue("Rejected owner/operation terminals must not enter the bridge.", bridge.resumeCalls.isEmpty())

            assertEquals(
                LuaInputResumeResult.Accepted,
                harness.runtime.resumeInput(firstPending.ownerToken, firstPending.operationId, true, "{}"),
            )
            runCurrent()
            val chainedPending = checkNotNull(harness.runtime.pendingInput())
            assertEquals(firstPending.ownerToken, chainedPending.ownerToken)
            assertEquals(602L, chainedPending.operationId)
            assertEquals(
                LuaInputResumeResult.Accepted,
                harness.runtime.resumeInput(chainedPending.ownerToken, chainedPending.operationId, true, "{}"),
            )

            playback.release(DelayedPlaybackOutcome.Pending(DelayedPlaybackOperationId("queued")))
            runCurrent()
            assertEquals(ChannelInputResult.None, release.await())
            assertNull(harness.runtime.pendingInput())
            assertEquals(ChannelExecutionStatus.SUCCESS, harness.runtime.snapshot.value.executionStatus)
            assertEquals(listOf(true, true), bridge.resumeCalls.map { it.first })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `semantic playback duplicate and revoked completions consume and resume at most once`() = runTest {
        val playback = ControlledDeferredPlayback()
        val operation = AudioOperationArtifact(RecordedPcm(shortArrayOf(3, -3), 16_000), operationId = "race-audio", generation = RuntimeGeneration(7))
        val audio = object : AudioOperationCapability {
            override suspend fun createPlaybackResult(audio: OpaqueSynthesizedAudio) = CapabilityOperationResult.Success<OpaqueAudioOperation>(operation)
            override suspend fun createPlaybackResult(audio: OpaqueAudioRecording) = CapabilityOperationResult.Success<OpaqueAudioOperation>(operation)
        }
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_input", yielded(value = registerClaim(HostOperationKind.PLAYBACK, audioToken = "{token}", delaySeconds = 0.0)))
        }
        val harness = harness(
            bridge,
            setOf("startup", "handle_readiness", "handle_input"),
            audioOperation = audio,
            deferredAudioPlayback = playback,
            declaredCapabilities = setOf(ChannelCapability.AudioOperation, ChannelCapability.DeferredAudioPlayback),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate()); harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime); target.onInputStarted(session(16_000))
            val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }; runCurrent()
            val pending = checkNotNull(harness.runtime.pendingInput())
            playback.release(DelayedPlaybackOutcome.Pending(DelayedPlaybackOperationId("queued")))
            assertEquals(ChannelInputResult.None, release.await()); assertEquals(1, playback.calls); assertEquals(1, bridge.resumeCalls.size)
            assertEquals(LuaInputResumeResult.Stale, harness.runtime.resumeInput(pending.ownerToken, pending.operationId, false, "E_HOST_FAILURE"))
            assertEquals(1, bridge.resumeCalls.size); assertEquals(1, playback.consumeCount)
            harness.retireGeneration()
            assertEquals(LuaInputResumeResult.Closed, harness.runtime.resumeInput(pending.ownerToken, pending.operationId, true, "late")); assertEquals(1, bridge.resumeCalls.size)
        } finally { harness.close() }
    }

    @Test
    fun `semantic audio error boundary preserves allowlisted codes and bounds unknown detail`() {
        val normalizer = Class.forName("io.talkcan.lua.LuaAdapterRuntimeKt")
            .getDeclaredMethod("normalizeSemanticAudioError", String::class.java)
            .apply { isAccessible = true }
        val allowed = listOf(
            "E_INVALID_ARGUMENT", "E_INVALID_VALUE", "E_INVALID_CONTEXT",
            "E_CAPABILITY_UNDECLARED", "E_UNAVAILABLE", "E_BUSY", "E_TIMEOUT",
            "E_CANCELLED", "E_CLOSED", "E_STALE", "E_HOST_FAILURE",
        )
        for (code in allowed) assertEquals(code, normalizer.invoke(null, code))
        for (detail in listOf(
            "exception-like /endpoint=https://secret.example credential=top-secret transport reset",
            "unknown host failure detail",
        )) {
            val normalized = normalizer.invoke(null, detail) as String
            assertEquals("E_HOST_FAILURE", normalized)
            assertFalse(normalized.contains("secret.example"))
            assertFalse(normalized.contains("top-secret"))
            assertFalse(normalized.contains("transport reset"))
        }
    }

    @Test
    fun `lifecycle readiness and SOS remain synchronous when their bridge callback yields`() = runTest {
        val lifecycleBridge = RecordingBridge().apply {
            enqueue("startup", completed())
            enqueue("handle_lifecycle", yielded())
        }
        val lifecycleHarness = harness(lifecycleBridge, setOf("startup", "handle_lifecycle"))
        try {
            assertTrue("A yielded lifecycle callback must fail activation synchronously.", lifecycleHarness.runtime.activate() is ChannelActivationResult.Failed)
            assertEquals(listOf("startup", "handle_lifecycle"), lifecycleBridge.callbackCalls.map { it.name })
        } finally {
            lifecycleHarness.close()
        }

        val readinessBridge = RecordingBridge().apply {
            enqueue("handle_readiness", yielded())
        }
        val readinessHarness = harness(readinessBridge, setOf("startup", "handle_readiness", "handle_input"))
        try {
            assertEquals(ChannelActivationResult.Ready, readinessHarness.runtime.activate())
            readinessHarness.runtime.refreshReadiness()
            assertTrue(
                "A yielded readiness callback must be locally contained as not-ready.",
                readinessHarness.runtime.prepareInput() is ChannelInputAcceptance.Refused,
            )
            assertEquals(ChannelExecutionStatus.IDLE, readinessHarness.runtime.snapshot.value.executionStatus)
        } finally {
            readinessHarness.close()
        }

        val sosBridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_sos", yielded())
        }
        val sosHarness = harness(sosBridge, setOf("startup", "handle_readiness", "handle_sos"))
        try {
            assertEquals(ChannelActivationResult.Ready, sosHarness.runtime.activate())
            sosHarness.runtime.refreshReadiness()
            val before = sosHarness.runtime.snapshot.value
            sosHarness.runtime.handleSos()
            assertEquals("SOS yield must be contained without a second entry.", before, sosHarness.runtime.snapshot.value)
            assertEquals(1, sosBridge.callbackCalls.count { it.name == "handle_sos" })
        } finally {
            sosHarness.close()
        }
    }

    @Test
    fun `SOS failure is contained and repeated close suppresses every late callback`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_sos", LuaKernelOutcome.RuntimeFailure(41, 7, "sos failure"))
        }
        val harness = harness(bridge, setOf("startup", "handle_readiness", "handle_input", "handle_sos"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime)
            val beforeSos = harness.runtime.snapshot.value

            harness.runtime.handleSos()

            assertEquals("SOS callback failure must not mutate the runtime snapshot.", beforeSos, harness.runtime.snapshot.value)
            assertEquals(1, bridge.callbackCalls.count { it.name == "handle_sos" })

            harness.runtime.close()
            harness.runtime.close()
            val callbacksAtClose = bridge.callbackCalls.size

            assertTrue(harness.runtime.prepareInput() is ChannelInputAcceptance.Unavailable)
            harness.runtime.refreshReadiness()
            harness.runtime.handleSos()
            target.onInputStarted(session(16_000))
            assertEquals(ChannelInputResult.None, target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)))

            assertEquals("Late adapter operations must not re-enter Lua after close.", callbacksAtClose, bridge.callbackCalls.size)
            assertEquals(1, bridge.closeCalls.get())
            assertEquals(
                ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed),
                harness.runtime.snapshot.value.preparation,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `activation close race has one startup entry and preserves the closed snapshot`() = runTest {
        val enteredStartup = CountDownLatch(1)
        val releaseStartup = CountDownLatch(1)
        val bridge = RecordingBridge().apply {
            beforeCallback = { name ->
                if (name == "startup") {
                    enteredStartup.countDown()
                    releaseStartup.await()
                }
            }
        }
        val harness = harness(bridge, setOf("startup"))
        try {
            val activation = AtomicReference<ChannelActivationResult>()
            val thread = Thread {
                activation.set(runBlocking { harness.runtime.activate() })
            }
            thread.start()
            enteredStartup.await()

            harness.runtime.close()
            releaseStartup.countDown()
            thread.join()

            assertTrue(activation.get() is ChannelActivationResult.Failed)
            assertEquals(1, bridge.callbackCalls.count { it.name == "startup" })
            assertEquals(
                ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed),
                harness.runtime.snapshot.value.preparation,
            )
            assertEquals(ChannelExecutionStatus.IDLE, harness.runtime.snapshot.value.executionStatus)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `generation retirement during lifecycle callback cannot publish ready`() = runTest {
        val enteredLifecycle = CountDownLatch(1)
        val releaseLifecycle = CountDownLatch(1)
        val bridge = RecordingBridge().apply {
            beforeCallback = { name ->
                if (name == "handle_lifecycle") {
                    enteredLifecycle.countDown()
                    releaseLifecycle.await()
                }
            }
        }
        val harness = harness(bridge, setOf("startup", "handle_lifecycle"))
        try {
            val activation = AtomicReference<ChannelActivationResult>()
            val thread = Thread {
                activation.set(runBlocking { harness.runtime.activate() })
            }
            thread.start()
            enteredLifecycle.await()

            harness.retireGeneration()
            releaseLifecycle.countDown()
            thread.join()

            assertTrue(
                "Retirement after lifecycle dispatch but before the Ready claim must fail activation.",
                activation.get() is ChannelActivationResult.Failed,
            )
            assertFalse(harness.runtime.snapshot.value.preparation is ChannelPreparationAvailability.Available)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `input close race cannot overwrite the terminal closed snapshot`() = runTest {
        val enteredInput = CountDownLatch(1)
        val releaseInput = CountDownLatch(1)
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            beforeCallback = { name ->
                if (name == "handle_input") {
                    enteredInput.countDown()
                    releaseInput.await()
                }
            }
        }
        val harness = harness(bridge, setOf("startup", "handle_readiness", "handle_input"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime)
            target.onInputStarted(session(16_000))
            val input = Thread {
                runBlocking { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }
            }
            input.start()
            enteredInput.await()

            harness.runtime.close()
            releaseInput.countDown()
            input.join()

            assertEquals(
                ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed),
                harness.runtime.snapshot.value.preparation,
            )
            assertEquals(ChannelExecutionStatus.IDLE, harness.runtime.snapshot.value.executionStatus)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `readiness without an explicit true revokes a prior cached input acceptance`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_readiness", completed("{}"))
        }
        val harness = harness(bridge, setOf("startup", "handle_readiness", "handle_input"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            assertTrue(harness.runtime.prepareInput() is ChannelInputAcceptance.Accepted)

            harness.runtime.refreshReadiness()

            assertTrue(
                "Only an explicit ready=true projection may keep input acceptance cached.",
                harness.runtime.prepareInput() is ChannelInputAcceptance.Refused,
            )
        } finally {
            harness.close()
        }
    }

    // ── Readiness-declared capability preparation (5.1-5.9) ──────────────

    @Test
    fun `recoverable keyboard output prepares once then accepts on refreshed readiness`() = runTest {
        val textOutput = NoOpTextOutput()
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":false,"prepare":["keyboard.output"]}"""))
            enqueue("handle_readiness", completed("""{"ready":true}"""))
        }
        val harness = harness(
            bridge, setOf("startup", "handle_readiness", "handle_input"),
            declaredCapabilities = setOf(ChannelCapability.TextOutput),
            preparerRegistry = io.talkcan.channel.capability.CapabilityPreparerRegistry.default(),
            declaredPublicCapabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
            textOutput = textOutput,
            textOutputAvailability = CapabilityAvailability.Recoverable,
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            harness.capabilityHost.prepareTextOutputOutcome =
                HostedCapabilityAcquisition.Available(textOutput) { }
            val acceptance = harness.runtime.prepareInput()
            assertTrue(
                "Successful preparation plus refreshed ready=true must accept input.",
                acceptance is ChannelInputAcceptance.Accepted,
            )
            assertEquals("Exactly one host preparation call", 1, harness.capabilityHost.prepareCalls)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `failed preparation refuses input without a second readiness refresh`() = runTest {
        val textOutput = NoOpTextOutput()
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":false,"prepare":["keyboard.output"]}"""))
        }
        val harness = harness(
            bridge, setOf("startup", "handle_readiness", "handle_input"),
            declaredCapabilities = setOf(ChannelCapability.TextOutput),
            preparerRegistry = io.talkcan.channel.capability.CapabilityPreparerRegistry.default(),
            declaredPublicCapabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
            textOutput = textOutput,
            textOutputAvailability = CapabilityAvailability.Recoverable,
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            harness.capabilityHost.prepareTextOutputOutcome =
                HostedCapabilityAcquisition.Failed(io.talkcan.channel.capability.CapabilityFailureReason.PREPARATION_FAILED)
            val acceptance = harness.runtime.prepareInput()
            assertTrue(
                "Failed preparation must refuse input.",
                acceptance is ChannelInputAcceptance.Refused,
            )
            assertEquals(
                "Failed preparation must not trigger a second readiness refresh.",
                1,
                bridge.callbackCalls.count { it.name == "handle_readiness" },
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `preparation that leaves readiness not ready refuses input`() = runTest {
        val textOutput = NoOpTextOutput()
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":false,"prepare":["keyboard.output"]}"""))
            enqueue("handle_readiness", completed("""{"ready":false}"""))
        }
        val harness = harness(
            bridge, setOf("startup", "handle_readiness", "handle_input"),
            declaredCapabilities = setOf(ChannelCapability.TextOutput),
            preparerRegistry = io.talkcan.channel.capability.CapabilityPreparerRegistry.default(),
            declaredPublicCapabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
            textOutput = textOutput,
            textOutputAvailability = CapabilityAvailability.Recoverable,
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            harness.capabilityHost.prepareTextOutputOutcome =
                HostedCapabilityAcquisition.Available(textOutput) { }
            val acceptance = harness.runtime.prepareInput()
            assertTrue(
                "Preparation that leaves readiness not-ready must refuse input.",
                acceptance is ChannelInputAcceptance.Refused,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `readiness prepare naming an undeclared capability is malformed and refuses`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":false,"prepare":["keyboard.output"]}"""))
        }
        val harness = harness(
            bridge, setOf("startup", "handle_readiness", "handle_input"),
            declaredCapabilities = emptySet(),
            preparerRegistry = io.talkcan.channel.capability.CapabilityPreparerRegistry.default(),
            declaredPublicCapabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val acceptance = harness.runtime.prepareInput()
            assertTrue(
                "A prepare list naming an undeclared capability must be malformed and refuse.",
                acceptance is ChannelInputAcceptance.Refused,
            )
            assertEquals(
                "Malformed readiness must record exactly one diagnostic.",
                1,
                harness.runtime.localDiagnosticSnapshot.size,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `readiness prepare naming a non-preparable capability is malformed`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":false,"prepare":["audio.transcription"]}"""))
        }
        val harness = harness(
            bridge, setOf("startup", "handle_readiness", "handle_input"),
            declaredCapabilities = setOf(ChannelCapability.Transcription),
            preparerRegistry = io.talkcan.channel.capability.CapabilityPreparerRegistry.default(),
            declaredPublicCapabilities = setOf(PackageCapability.AUDIO_TRANSCRIPTION),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            assertTrue(
                "A prepare list naming a non-preparable capability must be malformed.",
                harness.runtime.prepareInput() is ChannelInputAcceptance.Refused,
            )
            assertEquals(1, harness.runtime.localDiagnosticSnapshot.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `ready result carrying a preparation request is malformed`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true,"prepare":["keyboard.output"]}"""))
        }
        val harness = harness(
            bridge, setOf("startup", "handle_readiness", "handle_input"),
            declaredCapabilities = setOf(ChannelCapability.TextOutput),
            preparerRegistry = io.talkcan.channel.capability.CapabilityPreparerRegistry.default(),
            declaredPublicCapabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            assertTrue(
                "A ready=true result with a nonempty prepare list must be malformed.",
                harness.runtime.prepareInput() is ChannelInputAcceptance.Refused,
            )
            assertEquals(1, harness.runtime.localDiagnosticSnapshot.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `duplicate preparation capability ids are malformed`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":false,"prepare":["keyboard.output","keyboard.output"]}"""))
        }
        val harness = harness(
            bridge, setOf("startup", "handle_readiness", "handle_input"),
            declaredCapabilities = setOf(ChannelCapability.TextOutput),
            preparerRegistry = io.talkcan.channel.capability.CapabilityPreparerRegistry.default(),
            declaredPublicCapabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            assertTrue(
                "Duplicate prepare capability IDs must be malformed.",
                harness.runtime.prepareInput() is ChannelInputAcceptance.Refused,
            )
            assertEquals(1, harness.runtime.localDiagnosticSnapshot.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `over-bound preparation list is malformed`() = runTest {
        val ids = (1..5).joinToString(",") { "\"cap.$it\"" }
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":false,"prepare":[$ids]}"""))
        }
        val harness = harness(
            bridge, setOf("startup", "handle_readiness", "handle_input"),
            declaredCapabilities = setOf(ChannelCapability.TextOutput),
            preparerRegistry = io.talkcan.channel.capability.CapabilityPreparerRegistry.default(),
            declaredPublicCapabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            assertTrue(
                "A prepare list exceeding the bound must be malformed.",
                harness.runtime.prepareInput() is ChannelInputAcceptance.Refused,
            )
            assertEquals(1, harness.runtime.localDiagnosticSnapshot.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `non-array preparation value is malformed`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":false,"prepare":"keyboard.output"}"""))
        }
        val harness = harness(
            bridge, setOf("startup", "handle_readiness", "handle_input"),
            declaredCapabilities = setOf(ChannelCapability.TextOutput),
            preparerRegistry = io.talkcan.channel.capability.CapabilityPreparerRegistry.default(),
            declaredPublicCapabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            assertTrue(
                "A non-array prepare value must be malformed.",
                harness.runtime.prepareInput() is ChannelInputAcceptance.Refused,
            )
            assertEquals(1, harness.runtime.localDiagnosticSnapshot.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a later not-ready refresh clears a cached preparation request`() = runTest {
        val textOutput = NoOpTextOutput()
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":false,"prepare":["keyboard.output"]}"""))
            enqueue("handle_readiness", completed("""{"ready":false}"""))
        }
        val harness = harness(
            bridge, setOf("startup", "handle_readiness", "handle_input"),
            declaredCapabilities = setOf(ChannelCapability.TextOutput),
            preparerRegistry = io.talkcan.channel.capability.CapabilityPreparerRegistry.default(),
            declaredPublicCapabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
            textOutput = textOutput,
            textOutputAvailability = CapabilityAvailability.Recoverable,
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            harness.runtime.refreshReadiness()
            harness.capabilityHost.prepareTextOutputOutcome =
                HostedCapabilityAcquisition.Available(textOutput) { }
            val acceptance = harness.runtime.prepareInput()
            assertTrue(
                "A later not-ready refresh without prepare must clear the cached preparation request.",
                acceptance is ChannelInputAcceptance.Refused,
            )
            assertEquals(
                "No preparation may run once the cached prepare list is cleared.",
                0,
                harness.capabilityHost.prepareCalls,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `available output accepts input without preparation`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
        }
        val harness = harness(
            bridge, setOf("startup", "handle_readiness", "handle_input"),
            declaredCapabilities = setOf(ChannelCapability.TextOutput),
            preparerRegistry = io.talkcan.channel.capability.CapabilityPreparerRegistry.default(),
            declaredPublicCapabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            assertTrue(
                "A ready=true result must accept input without any preparation.",
                harness.runtime.prepareInput() is ChannelInputAcceptance.Accepted,
            )
            assertEquals("No preparation may run for an already-ready result.", 0, harness.capabilityHost.prepareCalls)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `keyboard claim bounds revalidate profile and text bytes before adapter effects`() = runTest {
        data class BoundaryCase(
            val label: String,
            val text: String,
            val profile: String,
            val expectedFactoryCalls: Int,
            val accepted: Boolean,
        )

        val cases = listOf(
            BoundaryCase("profile-256", "x", "p".repeat(256), 1, true),
            BoundaryCase("profile-257", "x", "p".repeat(257), 0, false),
            BoundaryCase("text-16384", "é".repeat(8_192), "linux:us", 1, true),
            BoundaryCase("text-16385", "é".repeat(8_192) + "b", "linux:us", 1, false),
        )
        cases.forEach { case ->
            val bridge = RecordingBridge()
            bridge.enqueue(
                "handle_sos",
                yielded(
                    value = bridge.registerKeyboardClaim(
                        HostOperationKind.KEYBOARD_SEND_TEXT,
                        text = case.text,
                        profile = case.profile,
                    ),
                ),
            )
            val factoryCalls = AtomicInteger()
            val adapters = mutableListOf<RecordingKeyboardOutputAdapter>()
            val harness = harness(
                bridge = bridge,
                callbacks = setOf("startup", "handle_sos"),
                declaredCapabilities = setOf(ChannelCapability.TextOutput),
                declaredPublicCapabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
                keyboardOutputAdapterFactory = { identity, owner ->
                    factoryCalls.incrementAndGet()
                    RecordingKeyboardOutputAdapter(identity, owner).also(adapters::add)
                },
            )
            try {
                assertEquals(case.label, ChannelActivationResult.Ready, harness.runtime.activate())
                harness.runtime.handleSos()

                assertEquals(case.label, case.expectedFactoryCalls, factoryCalls.get())
                val sent = adapters.flatMap { it.textRequests }
                if (case.accepted) {
                    assertEquals(case.label, listOf(TextOutputRequest(case.text, TextOutputProfile(case.profile))), sent)
                    val (success, value) = bridge.resumeCalls.single()
                    assertTrue(case.label, success)
                    assertEquals(case.label, "delivered", org.json.JSONObject(value).getString("status"))
                } else {
                    assertTrue("$case must have no adapter effect", sent.isEmpty())
                    assertEquals(case.label, listOf(false to "E_INVALID_ARGUMENT"), bridge.resumeCalls)
                }
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun `keyboard outcomes normalize under SOS ownership without exposing content`() = runTest {
        data class OutcomeCase(
            val label: String,
            val submission: KeyboardOutputSubmission,
            val success: Boolean,
            val status: String?,
            val reason: String?,
        )

        val cases = listOf(
            OutcomeCase(
                "delivered",
                KeyboardOutputSubmission.Completed(TextDeliveryOutcome.Delivered("op-delivered")),
                true,
                "delivered",
                null,
            ),
            OutcomeCase(
                "rejected",
                KeyboardOutputSubmission.Completed(
                    TextDeliveryOutcome.Rejected("op-rejected", TextOutputRejectionReason.INVALID_PROFILE),
                ),
                true,
                "rejected",
                "invalid_profile",
            ),
            OutcomeCase(
                "failed",
                KeyboardOutputSubmission.Completed(
                    TextDeliveryOutcome.Failed("op-failed", TextOutputFailureReason.HOST_FAILURE),
                ),
                true,
                "failed",
                "host_failure",
            ),
            OutcomeCase(
                "indeterminate",
                KeyboardOutputSubmission.Completed(
                    TextDeliveryOutcome.Indeterminate(
                        "op-indeterminate",
                        TextOutputIndeterminateReason.DISCONNECTED,
                    ),
                ),
                true,
                "indeterminate",
                "disconnected",
            ),
            OutcomeCase("busy", KeyboardOutputSubmission.Busy, false, null, null),
            OutcomeCase("closed", KeyboardOutputSubmission.Closed, false, null, null),
            OutcomeCase("revoked", KeyboardOutputSubmission.Revoked, false, null, null),
        )
        cases.forEach { case ->
            val secretText = "secret-${case.label}"
            val secretProfile = "private:${case.label}"
            val bridge = RecordingBridge()
            bridge.enqueue(
                "handle_sos",
                yielded(
                    value = bridge.registerKeyboardClaim(
                        HostOperationKind.KEYBOARD_SEND_TEXT,
                        text = secretText,
                        profile = secretProfile,
                    ),
                ),
            )
            val adapters = mutableListOf<RecordingKeyboardOutputAdapter>()
            val harness = harness(
                bridge = bridge,
                callbacks = setOf("startup", "handle_sos"),
                declaredCapabilities = setOf(ChannelCapability.TextOutput),
                declaredPublicCapabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
                keyboardOutputAdapterFactory = { identity, owner ->
                    RecordingKeyboardOutputAdapter(identity, owner).also {
                        it.nextSubmission = case.submission
                        adapters += it
                    }
                },
            )
            try {
                assertEquals(case.label, ChannelActivationResult.Ready, harness.runtime.activate())
                harness.runtime.handleSos()

                val adapter = adapters.single()
                assertEquals(case.label, OutputExecutionOwnerKind.SOS, adapter.owner.kind)
                assertEquals(case.label, harness.capabilityScope.identity, adapter.identity)
                assertEquals(case.label, listOf(TextOutputRequest(secretText, TextOutputProfile(secretProfile))), adapter.textRequests)
                val (success, value) = bridge.resumeCalls.single()
                assertEquals(case.label, case.success, success)
                assertFalse(case.label, value.contains(secretText))
                assertFalse(case.label, value.contains(secretProfile))
                if (case.success) {
                    val json = org.json.JSONObject(value)
                    assertTrue(case.label, json.keySet().all { it == "status" || it == "reason" })
                    assertEquals(case.label, case.status, json.getString("status"))
                    if (case.reason == null) assertFalse(case.label, json.has("reason"))
                    else assertEquals(case.label, case.reason, json.getString("reason"))
                } else {
                    val expectedError = if (case.submission == KeyboardOutputSubmission.Busy) "E_BUSY" else "E_CLOSED"
                    assertEquals(case.label, expectedError, value)
                }
            } finally {
                harness.close()
            }
        }

        val bridge = RecordingBridge()
        bridge.enqueue(
            "handle_sos",
            yielded(
                value = bridge.registerKeyboardClaim(
                    HostOperationKind.KEYBOARD_SEND_TEXT,
                    text = "undeclared",
                ),
            ),
        )
        val factoryCalls = AtomicInteger()
        val harness = harness(
            bridge = bridge,
            callbacks = setOf("startup", "handle_sos"),
            keyboardOutputAdapterFactory = { identity, owner ->
                factoryCalls.incrementAndGet()
                RecordingKeyboardOutputAdapter(identity, owner)
            },
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.handleSos()
            assertEquals(0, factoryCalls.get())
            assertEquals(listOf(false to "E_CAPABILITY_UNDECLARED"), bridge.resumeCalls)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `managed keyboard output keeps its owner and replacement suppresses late effects`() = runTest {
        val bridge = RecordingBridge()
        val claim = bridge.registerKeyboardClaim(
            HostOperationKind.KEYBOARD_SEND_TEXT,
            text = "managed",
        )
        bridge.enqueue("startup", completed(spawnedCoroutines = listOf(201)))
        bridge.startCoroutineOutcomes += yielded(coroutineId = 201, operationId = 301, value = claim)
        val adapters = mutableListOf<RecordingKeyboardOutputAdapter>()
        val harness = harness(
            bridge = bridge,
            callbacks = setOf("startup"),
            declaredCapabilities = setOf(ChannelCapability.TextOutput),
            declaredPublicCapabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
            keyboardOutputAdapterFactory = { identity, owner ->
                RecordingKeyboardOutputAdapter(identity, owner).also(adapters::add)
            },
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            runCurrent()

            assertEquals(OutputExecutionOwnerKind.MANAGED_TASK, adapters.single().owner.kind)
            assertEquals(listOf(TextOutputRequest("managed", TextOutputProfile("linux:us"))), adapters.single().textRequests)
            assertTrue(bridge.resumeCalls.single().first)
        } finally {
            harness.close()
        }

        val replacementBridge = RecordingBridge()
        val replacementClaim = replacementBridge.registerKeyboardClaim(
            HostOperationKind.KEYBOARD_SEND_TEXT,
            text = "must-not-escape",
        )
        replacementBridge.enqueue("startup", completed(spawnedCoroutines = listOf(202)))
        replacementBridge.startCoroutineOutcomes += yielded(
            coroutineId = 202,
            operationId = 302,
            value = replacementClaim,
        )
        val sendStarted = CompletableDeferred<Unit>()
        val allowSend = CompletableDeferred<Unit>()
        lateinit var replacementAdapter: RecordingKeyboardOutputAdapter
        val replacementHarness = harness(
            bridge = replacementBridge,
            callbacks = setOf("startup"),
            declaredCapabilities = setOf(ChannelCapability.TextOutput),
            declaredPublicCapabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
            keyboardOutputAdapterFactory = { identity, owner ->
                RecordingKeyboardOutputAdapter(identity, owner).also {
                    it.beforeSendText = {
                        sendStarted.complete(Unit)
                        allowSend.await()
                    }
                    replacementAdapter = it
                }
            },
        )
        try {
            assertEquals(ChannelActivationResult.Ready, replacementHarness.runtime.activate())
            replacementHarness.authorizeStagedTasks()
            sendStarted.await()

            replacementHarness.retireGeneration()
            allowSend.complete(Unit)
            runCurrent()

            assertTrue("Retirement must cancel the suspended adapter call before its effect.", replacementAdapter.textRequests.isEmpty())
            assertTrue("Retirement must suppress every late managed-task resume.", replacementBridge.resumeCalls.isEmpty())
        } finally {
            allowSend.complete(Unit)
            replacementHarness.close()
        }
    }

    @Test
    fun `SOS malformed failures raw yields and late completions remain contained`() = runTest {
        data class ContainmentCase(
            val label: String,
            val outcome: LuaKernelOutcome,
            val expectedResume: List<Pair<Boolean, String>>,
        )

        val cases = listOf(
            ContainmentCase("malformed-return", completed("not-json"), emptyList()),
            ContainmentCase(
                "application-failure",
                LuaKernelOutcome.RuntimeFailure(41, 7, "application failure"),
                emptyList(),
            ),
            ContainmentCase(
                "phase-timeout",
                LuaKernelOutcome.Interrupted(41, 7, "instruction budget exceeded", 1),
                emptyList(),
            ),
            ContainmentCase("raw-yield", yielded(value = "not-an-admitted-claim"), listOf(false to "E_INVALID_YIELD")),
        )
        cases.forEach { case ->
            val bridge = RecordingBridge().apply { enqueue("handle_sos", case.outcome) }
            val harness = harness(bridge, setOf("startup", "handle_sos"))
            try {
                assertEquals(case.label, ChannelActivationResult.Ready, harness.runtime.activate())
                val before = harness.runtime.snapshot.value
                harness.runtime.handleSos()
                assertEquals(case.label, before, harness.runtime.snapshot.value)
                assertEquals(case.label, 1, bridge.callbackCalls.count { it.name == "handle_sos" })
                assertEquals(case.label, case.expectedResume, bridge.resumeCalls)
            } finally {
                harness.close()
            }
        }

        val throwingBridge = RecordingBridge().apply {
            beforeCallback = { name -> if (name == "handle_sos") throw IllegalStateException("callback failure") }
        }
        val throwingHarness = harness(throwingBridge, setOf("startup", "handle_sos"))
        try {
            assertEquals(ChannelActivationResult.Ready, throwingHarness.runtime.activate())
            val before = throwingHarness.runtime.snapshot.value
            throwingHarness.runtime.handleSos()
            assertEquals(before, throwingHarness.runtime.snapshot.value)
            assertEquals(1, throwingBridge.callbackCalls.count { it.name == "handle_sos" })
        } finally {
            throwingHarness.close()
        }

        val lateBridge = RecordingBridge()
        lateBridge.enqueue(
            "handle_sos",
            yielded(
                value = lateBridge.registerKeyboardClaim(
                    HostOperationKind.KEYBOARD_SEND_TEXT,
                    text = "late",
                ),
            ),
        )
        val sendStarted = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()
        val lateHarness = harness(
            bridge = lateBridge,
            callbacks = setOf("startup", "handle_sos"),
            declaredCapabilities = setOf(ChannelCapability.TextOutput),
            declaredPublicCapabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
            keyboardOutputAdapterFactory = { identity, owner ->
                RecordingKeyboardOutputAdapter(identity, owner).also {
                    it.beforeSendText = {
                        sendStarted.complete(Unit)
                        allowCompletion.await()
                    }
                }
            },
        )
        try {
            assertEquals(ChannelActivationResult.Ready, lateHarness.runtime.activate())
            val sos = async { lateHarness.runtime.handleSos() }
            sendStarted.await()
            lateHarness.runtime.close()
            allowCompletion.complete(Unit)
            sos.await()

            assertEquals(1, lateBridge.callbackCalls.count { it.name == "handle_sos" })
            assertTrue("A completion after close must not re-enter the SOS coroutine.", lateBridge.resumeCalls.isEmpty())
            assertEquals(
                ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed),
                lateHarness.runtime.snapshot.value.preparation,
            )
        } finally {
            allowCompletion.complete(Unit)
            lateHarness.close()
        }
    }

    @Test
    fun `every preparation race suppresses acceptance and post preparation refresh`() = runTest {
        data class PreparationRace(
            val label: String,
            val action: suspend (
                AdapterHarness,
                kotlinx.coroutines.Deferred<ChannelInputAcceptance>,
            ) -> Unit,
        )

        val races = listOf(
            PreparationRace("release") { harness, _ -> harness.capabilityScope.revoke() },
            PreparationRace("cancellation") { _, preparation -> preparation.cancel() },
            PreparationRace("disconnect") { harness, _ ->
                harness.capabilityHost.prepareTextOutputOutcome =
                    HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY)
            },
            PreparationRace("replacement") { harness, _ -> harness.retireGeneration() },
            PreparationRace("disablement") { harness, _ -> harness.stopAdmission() },
            PreparationRace("runtime-close") { harness, _ -> harness.runtime.close() },
            PreparationRace("service-shutdown") { harness, _ -> harness.close() },
        )
        races.forEach { race ->
            val textOutput = NoOpTextOutput()
            val bridge = RecordingBridge().apply {
                enqueue("handle_readiness", completed("""{"ready":false,"prepare":["keyboard.output"]}"""))
                enqueue("handle_readiness", completed("""{"ready":true}"""))
            }
            val gate = CompletableDeferred<Unit>()
            val harness = harness(
                bridge = bridge,
                callbacks = setOf("startup", "handle_readiness", "handle_input"),
                declaredCapabilities = setOf(ChannelCapability.TextOutput),
                preparerRegistry = io.talkcan.channel.capability.CapabilityPreparerRegistry.default(),
                declaredPublicCapabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
                textOutput = textOutput,
                textOutputAvailability = CapabilityAvailability.Recoverable,
            )
            harness.capabilityHost.prepareGate = gate
            harness.capabilityHost.prepareTextOutputOutcome =
                HostedCapabilityAcquisition.Available(textOutput) { }
            try {
                assertEquals(race.label, ChannelActivationResult.Ready, harness.runtime.activate())
                harness.runtime.refreshReadiness()
                val preparation = async { harness.runtime.prepareInput() }
                runCurrent()
                assertEquals(race.label, 1, harness.capabilityHost.prepareCalls)

                val raceAction = async { race.action(harness, preparation) }
                runCurrent()
                gate.complete(Unit)
                raceAction.await()
                runCurrent()

                if (race.label == "cancellation") {
                    assertTrue(race.label, preparation.isCancelled)
                } else {
                    assertFalse(race.label, preparation.await() is ChannelInputAcceptance.Accepted)
                }
                assertEquals(
                    "${race.label}: stale preparation must not run the queued success refresh.",
                    1,
                    bridge.callbackCalls.count { it.name == "handle_readiness" },
                )
            } finally {
                gate.complete(Unit)
                harness.close()
            }
        }
    }

    @Test
    fun `startup spawned coroutine remains staged until generation authorization`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(91)))
        }
        val started = CompletableDeferred<Unit>()
        bridge.onCoroutineStarted = { if (it == 91L) started.complete(Unit) }
        val harness = harness(bridge, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            assertTrue(
                "A startup-admitted coroutine must not execute before the registry authorizes staged tasks.",
                bridge.startedCoroutines.isEmpty(),
            )

            harness.authorizeStagedTasks()
            started.await()

            assertEquals(listOf(91L), bridge.startedCoroutines)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `startup spawn admission returns typed busy or closed without starting a child`() = runTest {
        val capacityBridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(96)))
        }
        val capacityHarness = harness(capacityBridge, setOf("startup"))
        try {
            capacityHarness.saturateTaskCapacity()

            assertEquals(ChannelActivationResult.Ready, capacityHarness.runtime.activate())
            assertEquals(listOf(SpawnAdmission("startup", 96, 2)), capacityBridge.spawnAdmissions)
            assertTrue(capacityBridge.startedCoroutines.isEmpty())
        } finally {
            capacityHarness.close()
        }

        lateinit var closedHarness: AdapterHarness
        val closedBridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(97)))
            beforeCallback = { name ->
                if (name == "startup") runBlocking { closedHarness.retireGeneration() }
            }
        }
        closedHarness = harness(closedBridge, setOf("startup"))
        try {
            assertTrue(closedHarness.runtime.activate() is ChannelActivationResult.Failed)
            assertEquals(listOf(SpawnAdmission("startup", 97, 1)), closedBridge.spawnAdmissions)
            assertTrue(closedBridge.startedCoroutines.isEmpty())
        } finally {
            closedHarness.close()
        }
    }

    @Test
    fun `timer saturation resumes the exact sleeping coroutine with E_BUSY`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(98)))
            startCoroutineOutcomes += yielded(coroutineId = 98, operationId = 18, value = "sleep:1")
        }
        val resumed = CompletableDeferred<Unit>()
        bridge.onCoroutineResumed = { resumed.complete(Unit) }
        val harness = harness(bridge, setOf("startup"))
        try {
            harness.saturateTimerCapacity()
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()

            resumed.await()
            assertEquals(listOf(false to "E_BUSY"), bridge.resumeCalls)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `deadline first resumes timeout once and a late requested timer cannot resume again`() = runTest {
        val timers = ControlledTimerDelay()
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(110)))
            startCoroutineOutcomes += yielded(coroutineId = 110, operationId = 20, value = "sleep:1")
        }
        val harness = harness(bridge, setOf("startup"), timerDelay = { delayMillis -> timers.await(delayMillis) })
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            timers.awaitPending()
            timers.assertPending(listOf(1_000L, 1_100L))

            timers.release(1)
            assertEquals(listOf(false to "E_TIMEOUT"), bridge.resumeCalls)

            timers.releaseAll()
            assertEquals(listOf(false to "E_TIMEOUT"), bridge.resumeCalls)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `requested timer first resumes success once and close suppresses both pending timer paths`() = runTest {
        val successTimers = ControlledTimerDelay()
        val successBridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(111)))
            startCoroutineOutcomes += yielded(coroutineId = 111, operationId = 21, value = "sleep:1")
        }
        val successHarness = harness(successBridge, setOf("startup"), timerDelay = { delayMillis -> successTimers.await(delayMillis) })
        try {
            assertEquals(ChannelActivationResult.Ready, successHarness.runtime.activate())
            successHarness.authorizeStagedTasks()
            successTimers.awaitPending()
            successTimers.release(0)
            assertEquals(listOf(true to ""), successBridge.resumeCalls)
            successTimers.releaseAll()
            assertEquals(listOf(true to ""), successBridge.resumeCalls)
        } finally {
            successHarness.close()
        }

        val closeTimers = ControlledTimerDelay()
        val closeBridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(112)))
            startCoroutineOutcomes += yielded(coroutineId = 112, operationId = 22, value = "sleep:1")
        }
        val closeHarness = harness(closeBridge, setOf("startup"), timerDelay = { delayMillis -> closeTimers.await(delayMillis) })
        try {
            assertEquals(ChannelActivationResult.Ready, closeHarness.runtime.activate())
            closeHarness.authorizeStagedTasks()
            closeTimers.awaitPending()
            closeTimers.assertPending(listOf(1_000L, 1_100L))
            closeHarness.runtime.close()
            closeTimers.releaseAll()
            assertTrue(closeBridge.resumeCalls.isEmpty())
        } finally {
            closeHarness.close()
        }
    }


    @Test
    fun `resume-slice nested spawn is admitted before success and cannot start before the resume returns`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(113)))
            startCoroutineOutcomes += yielded(coroutineId = 113, operationId = 23, value = "not-a-sleep")
            resumeOutcomes += completed(spawnedCoroutines = listOf(114))
            startCoroutineOutcomes += completed()
            beforeNativeSliceReturns = { caller, spawned ->
                if (caller == "resume") {
                    assertEquals(listOf(114L), spawned)
                    assertEquals(listOf(113L), startedCoroutines)
                }
            }
        }
        val childStarted = CompletableDeferred<Unit>()
        bridge.onCoroutineStarted = { if (it == 114L) childStarted.complete(Unit) }
        val harness = harness(bridge, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            childStarted.await()
            assertEquals(listOf(113L, 114L), bridge.startedCoroutines)
            assertEquals(
                listOf(
                    SpawnAdmission("startup", 113, 0),
                    SpawnAdmission("resume", 114, 0),
                ),
                bridge.spawnAdmissions,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a capacity-rejected resume-slice spawn returns E_BUSY and starts no child`() = runTest {
        lateinit var harness: AdapterHarness
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(115)))
            startCoroutineOutcomes += yielded(coroutineId = 115, operationId = 24, value = "not-a-sleep")
            resumeOutcomes += completed(spawnedCoroutines = listOf(116))
            beforeNativeSliceReturns = { caller, _ ->
                if (caller == "start:115") harness.saturateActiveTaskCapacity()
            }
        }
        harness = harness(bridge, setOf("startup"))
        val resumed = CompletableDeferred<Unit>()
        bridge.onCoroutineResumed = { resumed.complete(Unit) }
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            resumed.await()
            assertEquals(listOf(115L), bridge.startedCoroutines)
            assertEquals(listOf(false to "E_INVALID_YIELD"), bridge.resumeCalls)
            assertEquals(
                listOf(
                    SpawnAdmission("startup", 115, 0),
                    SpawnAdmission("resume", 116, 2),
                ),
                bridge.spawnAdmissions,
            )
        } finally {
            harness.close()
        }
    }
    @Test
    fun `nested spawned coroutine IDs are admitted and run after authorization`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(99)))
            beforeNativeSliceReturns = { caller, spawned ->
                if (caller == "start:99") {
                    assertEquals(listOf(100L), spawned)
                    assertEquals(
                        "A child admitted in the parent native slice must remain gated until that slice returns.",
                        listOf(99L),
                        startedCoroutines,
                    )
                }
            }
            startCoroutineOutcomes += completed(spawnedCoroutines = listOf(100))
            startCoroutineOutcomes += completed()
        }
        val childStarted = CompletableDeferred<Unit>()
        bridge.onCoroutineStarted = { if (it == 100L) childStarted.complete(Unit) }
        val harness = harness(bridge, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            childStarted.await()
            assertEquals(listOf(99L, 100L), bridge.startedCoroutines)
            assertEquals(
                listOf(
                    SpawnAdmission("startup", 99, 0),
                    SpawnAdmission("start:99", 100, 0),
                ),
                bridge.spawnAdmissions,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `native logs are bounded enriched and cleared by close while malformed records are contained`() = runTest {
        val validRecords = (0..128).map { index ->
            """{"level":"info","payload":{"index":$index}}"""
        }
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(logs = listOf("not-json") + validRecords))
        }
        val harness = harness(bridge, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            val records = harness.runtime.logSnapshot

            assertEquals(128, records.size)
            assertEquals("lua-adapter", records.first().instanceId)
            assertEquals(7L, records.first().generation)
            assertEquals("info", records.first().level)
            assertTrue(records.all { it.timestampMillis > 0L })
            assertEquals(LuaValue.Integer(1L), records.first().payload.pairs["index"])
            assertEquals(LuaValue.Integer(128L), records.last().payload.pairs["index"])

            harness.runtime.close()
            assertTrue(harness.runtime.logSnapshot.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `native logs publish every valid level once with host-owned metadata and canonical payload bytes`() = runTest {
        val sink = RecordingLogSink()
        val startedAt = System.currentTimeMillis()
        val bridge = RecordingBridge().apply {
            enqueue(
                "startup",
                completed(
                    logs = listOf(
                        """{"level":"debug","payload":{"z":{"k":true,"a":[2,"x"]},"a":null,"instance_id":"forged","generation":999}}""",
                        """{"level":"info","payload":{"sequence":2}}""",
                        """{"level":"warn","payload":{"sequence":3}}""",
                        """{"level":"error","payload":{"sequence":4}}""",
                    ),
                ),
            )
        }
        val harness = harness(bridge, setOf("startup"), logSink = sink)
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            val finishedAt = System.currentTimeMillis()

            assertEquals(listOf("debug", "info", "warn", "error"), sink.records.map { it.level })
            assertEquals(1, sink.records.count { it.level == "debug" })
            sink.records.forEach { record ->
                assertEquals("lua-adapter", record.instanceId)
                assertEquals(7L, record.generation)
                assertTrue(record.timestampMillis in startedAt..finishedAt)
            }
            assertEquals(
                "{\"a\":null,\"generation\":999,\"instance_id\":\"forged\",\"z\":{\"a\":[2,\"x\"],\"k\":true}}",
                sink.records.first().payloadJson,
            )
            assertEquals(
                listOf("{\"sequence\":2}", "{\"sequence\":3}", "{\"sequence\":4}"),
                sink.records.drop(1).map { it.payloadJson },
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `structured native logs carry the host runtime generation rather than the Lua state generation`() = runTest {
        val hostGeneration = RuntimeGeneration(113)
        val sink = RecordingLogSink()
        val bridge = RecordingBridge().apply {
            enqueue(
                "startup",
                completed(logs = listOf("""{"level":"info","payload":{"message":"host-generation"}}""")),
            )
        }
        val harness = harness(
            bridge = bridge,
            callbacks = setOf("startup"),
            logSink = sink,
            hostRuntimeGeneration = hostGeneration,
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())

            assertEquals(listOf(113L), harness.runtime.logSnapshot.map { it.generation })
            assertEquals(listOf(113L), sink.records.map { it.generation })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `malformed foreign and stale native logs are not published`() = runTest {
        val invalidSink = RecordingLogSink()
        val invalidBridge = RecordingBridge().apply {
            enqueue(
                "startup",
                completed(
                    logs = listOf(
                        "not-json",
                        """{"level":"trace","payload":{}}""",
                        """{"level":"info","payload":[]}""",
                        """{"level":"info","payload":{},"instance_id":"forged"}""",
                    ),
                ),
            )
        }
        val invalidHarness = harness(invalidBridge, setOf("startup"), logSink = invalidSink)
        try {
            assertEquals(ChannelActivationResult.Ready, invalidHarness.runtime.activate())
            assertTrue(invalidSink.records.isEmpty())
        } finally {
            invalidHarness.close()
        }

        val staleSink = RecordingLogSink()
        lateinit var staleHarness: AdapterHarness
        val staleBridge = RecordingBridge().apply {
            enqueue("startup", completed(logs = listOf("""{"level":"info","payload":{"late":true}}""")))
            beforeCallback = { callback -> if (callback == "startup") staleHarness.stopAdmission() }
        }
        staleHarness = harness(staleBridge, setOf("startup"), logSink = staleSink)
        try {
            assertTrue(staleHarness.runtime.activate() is ChannelActivationResult.Failed)
            assertTrue(staleSink.records.isEmpty())
        } finally {
            staleHarness.close()
        }
    }

    @Test
    fun `authorized background sleep resumes exactly once and invalid yields are rejected locally`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(92, 93)))
            startCoroutineOutcomes += yielded(coroutineId = 92, operationId = 12, value = "sleep:0")
            startCoroutineOutcomes += yielded(coroutineId = 93, operationId = 13, value = "not-a-sleep")
        }
        val harness = harness(bridge, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()

            assertTrue(bridge.resumeCalls.contains(true to ""))
            assertTrue(
                "A non-sleep native yield must be resumed as a local invalid-yield error rather than treated as a timer.",
                bridge.resumeCalls.contains(false to "E_INVALID_YIELD"),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `authorized background filesystem operation resumes through generic storage`() = runTest {
        val vfs = io.talkcan.storage.Vfs()
        val bridge = RecordingBridge().apply {
            val listId = registerFsClaim(HostOperationKind.FS_LIST, path = "", limit = 10)
            enqueue("startup", completed(spawnedCoroutines = listOf(130)))
            startCoroutineOutcomes += yielded(
                coroutineId = 130,
                operationId = 40,
                value = listId,
            )
            resumeOutcomes += completed()
        }
        val harness = harness(
            bridge,
            setOf("startup"),
            declaredCapabilities = setOf(ChannelCapability.StorageFiles),
            storagePort = vfs.fs,
            resourceDeclarations = fsResourceDeclarations(),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            runCurrent()

            val (success, value) = bridge.resumeCalls.single()
            assertTrue(success)
            assertEquals(0, org.json.JSONObject(value).getJSONArray("entries").length())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `authorized background audio open resumes through generic port`() = runTest {
        val vfs = io.talkcan.storage.Vfs()
        lateinit var audioFiles: FakeAudioFilePort
        val bridge = RecordingBridge().apply {
            val openId = registerAudioFileClaim(
                kind = HostOperationKind.AUDIO_OPEN,
                path = "stored/capture.wav",
                format = "wav-pcm-s16le",
            )
            enqueue("startup", completed(spawnedCoroutines = listOf(131)))
            startCoroutineOutcomes += yielded(
                coroutineId = 131,
                operationId = 41,
                value = openId,
            )
            resumeOutcomes += completed()
        }
        val harness = harness(
            bridge = bridge,
            callbacks = setOf("startup"),
            declaredCapabilities = setOf(
                ChannelCapability.StorageFiles,
                ChannelCapability.AudioFiles,
            ),
            storagePort = vfs.fs,
            resourceDeclarations = fsResourceDeclarations(),
            audioFilePortFactory = LuaAudioFilePortFactory { recordings ->
                FakeAudioFilePort(recordings).also { audioFiles = it }
            },
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            runCurrent()

            val (success, value) = bridge.resumeCalls.single()
            assertTrue(success)
            assertEquals(
                16_000,
                org.json.JSONObject(value)
                    .getJSONObject("metadata")
                    .getInt("sample_rate"),
            )
            assertEquals(0, audioFiles.exportCalls)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `background task interruption is isolated from the program image`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(132)))
            startCoroutineOutcomes += LuaKernelOutcome.Interrupted(
                stateId = 41,
                generation = 7,
                diagnostic = "instruction budget exceeded",
                elapsedNanos = 1,
            )
        }
        val harness = harness(bridge, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            runCurrent()

            assertEquals(
                ChannelPreparationAvailability.Available,
                harness.runtime.snapshot.value.preparation,
            )
            assertEquals(
                ChannelExecutionStatus.IDLE,
                harness.runtime.snapshot.value.executionStatus,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `close before a background timer fires suppresses its coroutine resume`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(94)))
            startCoroutineOutcomes += yielded(coroutineId = 94, operationId = 14, value = "sleep:3600")
        }
        val harness = harness(bridge, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            harness.runtime.close()

            assertTrue(
                "Closing a generation with a pending sleep must discard it without a Lua resume.",
                bridge.resumeCalls.isEmpty(),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `malformed and out-of-range native sleep labels are rejected without timer admission`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(120, 121, 122, 123)))
            startCoroutineOutcomes += yielded(coroutineId = 120, operationId = 30, value = "sleep:-1")
            startCoroutineOutcomes += yielded(coroutineId = 121, operationId = 31, value = "sleep:NaN")
            startCoroutineOutcomes += yielded(coroutineId = 122, operationId = 32, value = "sleep:Infinity")
            startCoroutineOutcomes += yielded(coroutineId = 123, operationId = 33, value = "sleep:86401")
        }
        val harness = harness(bridge, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()

            assertEquals(
                listOf(
                    false to "E_INVALID_YIELD",
                    false to "E_INVALID_YIELD",
                    false to "E_INVALID_YIELD",
                    false to "E_INVALID_YIELD",
                ),
                bridge.resumeCalls,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `sleep timeout resumes a still-live task which may issue another operation`() = runTest {
        val timers = ControlledTimerDelay()
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(124)))
            startCoroutineOutcomes += yielded(coroutineId = 124, operationId = 34, value = "sleep:1")
            resumeOutcomes += yielded(coroutineId = 124, operationId = 35, value = "sleep:0")
        }
        val harness = harness(bridge, setOf("startup"), timerDelay = { delayMillis -> timers.await(delayMillis) })
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            timers.awaitPending()
            timers.release(1)
            runCurrent()
            assertEquals(listOf(false to "E_TIMEOUT"), bridge.resumeCalls)

            timers.awaitPending()
            timers.assertPending(listOf(1_000L, 0L, 100L))
            timers.release(1)
            assertEquals(
                "The timeout continuation remains owned by the live task and may schedule a subsequent sleep.",
                listOf(false to "E_TIMEOUT", true to ""),
                bridge.resumeCalls,
            )
            timers.releaseAll()
        } finally {
            harness.close()
        }
    }

    // ── Callback table construction (fail closed) ────────────────────────

    @Test
    fun `missing required startup callback fails construction`() = runTest {
        val bridge = RecordingBridge()
        bridge.retainedCallbacks = setOf("handle_readiness")
        val result = constructRuntimeResult(bridge)
        assertTrue(
            "Construction must fail when 'startup' callback is absent from the returned callback list.",
            result is ChannelRuntimeConstructionResult.Failure,
        )
        assertEquals("The partially constructed actor must be closed on callback validation failure.", 1, bridge.closeCalls.get())
    }

    @Test
    fun `empty callback list without startup fails construction`() = runTest {
        val bridge = RecordingBridge()
        bridge.retainedCallbacks = emptySet()
        val result = constructRuntimeResult(bridge)
        assertTrue(
            "Construction must fail when the callback list is empty and contains no 'startup'.",
            result is ChannelRuntimeConstructionResult.Failure,
        )
    }

    @Test
    fun `unknown callback keys are silently ignored during callback table construction`() = runTest {
        val bridge = RecordingBridge()
        bridge.retainedCallbacks = setOf("startup", "unknown_1", "unknown_2", "foo", "bar")
        val result = constructRuntimeResult(bridge)
        assertTrue(
            "Construction must succeed when 'startup' is present alongside unknown keys.",
            result is ChannelRuntimeConstructionResult.Success,
        )
    }

    @Test
    fun `all recognized optional callbacks are accepted during construction`() = runTest {
        val bridge = RecordingBridge()
        bridge.retainedCallbacks = setOf(
            "startup", "handle_lifecycle", "handle_input", "handle_sos", "handle_readiness",
        )
        val result = constructRuntimeResult(bridge)
        assertTrue(
            "Construction must succeed when all recognized callbacks are present.",
            result is ChannelRuntimeConstructionResult.Success,
        )
    }

    @Test
    fun `a loadProgramImage bridge failure fails construction without a partial runtime`() = runTest {
        val bridge = RecordingBridge()
        bridge.failImageLoad = true
        val result = constructRuntimeResult(bridge)
        assertTrue(
            "A bridge RuntimeFailure during loadProgramImage must fail construction without creating a runtime.",
            result is ChannelRuntimeConstructionResult.Failure,
        )
        assertEquals("The state must be closed after image loading failure.", 1, bridge.closeCalls.get())
    }

    @Test
    fun `duplicate callback name in bridge result fails construction`() = runTest {
        val bridge = RecordingBridge()
        bridge.rawCallbackNamesJson = """["startup","startup"]"""
        val result = constructRuntimeResult(bridge)
        assertTrue(
            "Construction must fail when a callback name is duplicated.",
            result is ChannelRuntimeConstructionResult.Failure,
        )
    }

    @Test
    fun `undeclared transcription rejects before host acquisition or backend call`() = runTest {
        val backend = CountingTranscription()
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_input", yielded(value = registerClaim(HostOperationKind.TRANSCRIBE)))
        }
        val harness = harness(bridge, setOf("startup", "handle_readiness", "handle_input"), transcription = backend)
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime)
            target.onInputStarted(session(16_000))
            val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }
            runCurrent()
            assertEquals(ChannelInputResult.None, release.await())
            assertEquals(listOf(false to "E_CAPABILITY_UNDECLARED"), bridge.resumeCalls)
            assertEquals(0, harness.capabilityHost.acquireCalls)
            assertEquals(0, harness.capabilityHost.availabilityCalls)
            assertEquals(0, harness.capabilityHost.prepareCalls)
            assertEquals(0, backend.calls)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `undeclared synthesis rejects before host acquisition or backend call`() = runTest {
        val backend = CountingSynthesis()
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_input", yielded(value = registerClaim(HostOperationKind.SYNTHESIZE, text = "hi", language = "en", voice = "v", speed = 1.0)))
        }
        val harness = harness(bridge, setOf("startup", "handle_readiness", "handle_input"), synthesis = backend)
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime)
            target.onInputStarted(session(16_000))
            val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }
            runCurrent()
            assertEquals(ChannelInputResult.None, release.await())
            assertEquals(listOf(false to "E_CAPABILITY_UNDECLARED"), bridge.resumeCalls)
            assertEquals(0, harness.capabilityHost.acquireCalls)
            assertEquals(0, harness.capabilityHost.availabilityCalls)
            assertEquals(0, harness.capabilityHost.prepareCalls)
            assertEquals(0, backend.calls)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `playback requires both typed capabilities and rejects before either acquire`() = runTest {
        val declarations: List<Set<ChannelCapability>> = listOf(
            emptySet(),
            setOf(ChannelCapability.AudioOperation),
            setOf(ChannelCapability.DeferredAudioPlayback),
        )
        declarations.forEachIndexed { index, declared ->
            val audio = CountingAudioOperation()
            val playback = ControlledDeferredPlayback()
            val bridge = RecordingBridge().apply {
                enqueue("handle_readiness", completed("""{"ready":true}"""))
                enqueue("handle_input", yielded(coroutineId = 710L + index, operationId = 810L + index, value = registerClaim(HostOperationKind.PLAYBACK, audioToken = "{token}", delaySeconds = 0.0)))
            }
            val harness = harness(
                bridge,
                setOf("startup", "handle_readiness", "handle_input"),
                audioOperation = audio,
                deferredAudioPlayback = playback,
                declaredCapabilities = declared,
            )
            try {
                assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
                harness.runtime.refreshReadiness()
                val target = acceptedTarget(harness.runtime)
                target.onInputStarted(session(16_000))
                val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }
                runCurrent()
                assertEquals(ChannelInputResult.None, release.await())
                assertEquals(listOf(false to "E_CAPABILITY_UNDECLARED"), bridge.resumeCalls)
                assertEquals(0, harness.capabilityHost.acquireCalls)
                assertEquals(0, harness.capabilityHost.availabilityCalls)
                assertEquals(0, harness.capabilityHost.prepareCalls)
                assertEquals(0, audio.calls)
                assertEquals(0, playback.calls)
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun `declared unavailable transcription normalizes without fallback`() = runTest {
        val backend = CountingTranscription()
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_input", yielded(value = registerClaim(HostOperationKind.TRANSCRIBE)))
        }
        val harness = harness(
            bridge,
            setOf("startup", "handle_readiness", "handle_input"),
            transcription = backend,
            declaredCapabilities = setOf(ChannelCapability.Transcription),
            capabilityAvailability = mapOf(ChannelCapability.Transcription to CapabilityAvailability.Available),
            unavailableCapabilities = mapOf(ChannelCapability.Transcription to CapabilityUnavailableReason.MODEL_NOT_READY),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime)
            target.onInputStarted(session(16_000))
            val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }
            runCurrent()
            assertEquals(ChannelInputResult.None, release.await())
            assertEquals(listOf(false to "E_UNAVAILABLE"), bridge.resumeCalls)
            assertEquals(1, harness.capabilityHost.acquireCalls)
            assertEquals(0, backend.calls)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `declared unavailable synthesis normalizes without fallback`() = runTest {
        val backend = CountingSynthesis()
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_input", yielded(value = registerClaim(HostOperationKind.SYNTHESIZE, text = "hi", language = "en", voice = "v", speed = 1.0)))
        }
        val harness = harness(
            bridge,
            setOf("startup", "handle_readiness", "handle_input"),
            synthesis = backend,
            declaredCapabilities = setOf(ChannelCapability.Synthesis),
            capabilityAvailability = mapOf(ChannelCapability.Synthesis to CapabilityAvailability.Available),
            unavailableCapabilities = mapOf(ChannelCapability.Synthesis to CapabilityUnavailableReason.RESOURCE_BUSY),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime)
            target.onInputStarted(session(16_000))
            val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }
            runCurrent()
            assertEquals(ChannelInputResult.None, release.await())
            assertEquals(listOf(false to "E_UNAVAILABLE"), bridge.resumeCalls)
            assertEquals(1, harness.capabilityHost.acquireCalls)
            assertEquals(0, backend.calls)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `declared unavailable playback does not fall back to deferred scheduling`() = runTest {
        val audio = CountingAudioOperation()
        val playback = ControlledDeferredPlayback()
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_input", yielded(value = registerClaim(HostOperationKind.PLAYBACK, audioToken = "{token}", delaySeconds = 0.0)))
        }
        val harness = harness(
            bridge,
            setOf("startup", "handle_readiness", "handle_input"),
            audioOperation = audio,
            deferredAudioPlayback = playback,
            declaredCapabilities = setOf(ChannelCapability.AudioOperation, ChannelCapability.DeferredAudioPlayback),
            capabilityAvailability = mapOf(
                ChannelCapability.AudioOperation to CapabilityAvailability.Available,
                ChannelCapability.DeferredAudioPlayback to CapabilityAvailability.Available,
            ),
            unavailableCapabilities = mapOf(ChannelCapability.AudioOperation to CapabilityUnavailableReason.HOST_NOT_READY),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime)
            target.onInputStarted(session(16_000))
            val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }
            runCurrent()
            assertEquals(ChannelInputResult.None, release.await())
            assertEquals(listOf(false to "E_UNAVAILABLE"), bridge.resumeCalls)
            assertEquals(1, harness.capabilityHost.acquireCalls)
            assertEquals(0, audio.calls)
            assertEquals(0, playback.calls)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `revocation updates readiness projection and closes a previously accepted call`() = runTest {
        val backend = CountingTranscription()
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_readiness", completed("""{"ready":false}"""))
            enqueue("handle_input", yielded(value = registerClaim(HostOperationKind.TRANSCRIBE)))
        }
        val harness = harness(
            bridge,
            setOf("startup", "handle_readiness", "handle_input"),
            transcription = backend,
            declaredCapabilities = setOf(ChannelCapability.Transcription),
            capabilityAvailability = mapOf(ChannelCapability.Transcription to CapabilityAvailability.Available),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime)
            val initialContext = bridge.callbackCalls.last { it.name == "handle_readiness" }.arguments as LuaValue.Map
            val initialCapabilities = initialContext.pairs["capabilities"] as LuaValue.Map
            assertEquals(LuaValue.StringValue("available"), initialCapabilities.pairs[PackageCapability.AUDIO_TRANSCRIPTION])

            assertEquals(CapabilityScopeTerminationResult.Revoked, harness.capabilityScope.revoke())
            harness.runtime.refreshReadiness()
            val revokedContext = bridge.callbackCalls.last { it.name == "handle_readiness" }.arguments as LuaValue.Map
            val revokedCapabilities = revokedContext.pairs["capabilities"] as LuaValue.Map
            assertEquals(LuaValue.StringValue("unavailable"), revokedCapabilities.pairs[PackageCapability.AUDIO_TRANSCRIPTION])
            assertTrue(harness.runtime.prepareInput() is ChannelInputAcceptance.Refused)

            target.onInputStarted(session(16_000))
            val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }
            runCurrent()
            assertEquals(ChannelInputResult.None, release.await())
            assertEquals(listOf(false to "E_CLOSED"), bridge.resumeCalls)
            assertEquals(0, harness.capabilityHost.acquireCalls)
            assertEquals(0, backend.calls)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `configuration mode changes cannot authorize undeclared semantic operations`() = runTest {
        listOf("host", "fallback").forEachIndexed { index, mode ->
            val backend = CountingTranscription()
            val bridge = RecordingBridge().apply {
                enqueue("handle_readiness", completed("""{"ready":true}"""))
                enqueue("handle_input", yielded(coroutineId = 900L + index, operationId = 901L + index, value = registerClaim(HostOperationKind.TRANSCRIBE)))
            }
            val harness = harness(
                bridge,
                setOf("startup", "handle_readiness", "handle_input"),
                transcription = backend,
                configPayload = OpaqueJsonObject.parse("""{"mode":"$mode"}""").getOrThrow(),
            )
            try {
                assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
                harness.runtime.refreshReadiness()
                val target = acceptedTarget(harness.runtime)
                target.onInputStarted(session(16_000))
                val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }
                runCurrent()
                assertEquals(ChannelInputResult.None, release.await())
                assertEquals(listOf(false to "E_CAPABILITY_UNDECLARED"), bridge.resumeCalls)
                assertEquals(0, harness.capabilityHost.acquireCalls)
                assertEquals(0, harness.capabilityHost.availabilityCalls)
                assertEquals(0, backend.calls)
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun `transcription timeout preserves captured token and late completion is stale`() = runTest {
        val backend = LateTranscription()
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_input", yielded(coroutineId = 601, operationId = 701, value = registerClaim(HostOperationKind.TRANSCRIBE)))
        }
        val harness = harness(bridge, setOf("startup", "handle_readiness", "handle_input"), transcription = backend,
            declaredCapabilities = setOf(ChannelCapability.Transcription),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate()); harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime); target.onInputStarted(session(16_000))
            val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }; runCurrent()
            val pending = checkNotNull(harness.runtime.pendingInput())
            testScheduler.advanceTimeBy(30_001L); runCurrent()
            assertEquals(listOf(false to "E_TIMEOUT"), bridge.resumeCalls)
            val registry = audioRegistry(harness.runtime)
            assertEquals(1, registry.accounting().liveTokens)
            assertTrue(registry.accounting().retainedBytes > 0L)
            backend.complete(); runCurrent(); release.await()
            assertEquals(listOf(false to "E_TIMEOUT"), bridge.resumeCalls)
            assertEquals(LuaInputResumeResult.Stale, harness.runtime.resumeInput(pending.ownerToken, pending.operationId, true, "late"))
        } finally { harness.close() }
    }

    @Test
    fun `synthesis timeout disposes late artifact without registry delivery`() = runTest {
        val backend = LateSynthesis()
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_input", yielded(coroutineId = 602, operationId = 702, value = registerClaim(HostOperationKind.SYNTHESIZE, text = "hi", language = "en", voice = "v", speed = 1.0)))
        }
        val harness = harness(bridge, setOf("startup", "handle_readiness", "handle_input"), synthesis = backend,
            declaredCapabilities = setOf(ChannelCapability.Synthesis),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate()); harness.runtime.refreshReadiness(); val target = acceptedTarget(harness.runtime)
            target.onInputStarted(session(16_000)); val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }; runCurrent()
            val pending = checkNotNull(harness.runtime.pendingInput()); testScheduler.advanceTimeBy(30_001L); runCurrent()
            assertEquals(listOf(false to "E_TIMEOUT"), bridge.resumeCalls); backend.complete(); runCurrent(); release.await()
            assertTrue(backend.artifact.javaClass.getDeclaredField("disposed").apply { isAccessible = true }.getBoolean(backend.artifact)); assertEquals(listOf(false to "E_TIMEOUT"), bridge.resumeCalls)
            assertEquals(LuaInputResumeResult.Stale, harness.runtime.resumeInput(pending.ownerToken, pending.operationId, true, "late"))
        } finally { harness.close() }
    }

    @Test
    fun `playback timeout does not consume token or issue a second resume`() = runTest {
        val playback = ControlledDeferredPlayback()
        val operation = AudioOperationArtifact(RecordedPcm(shortArrayOf(2), 16_000), operationId = "op", generation = RuntimeGeneration(7))
        val audio = object : AudioOperationCapability {
            override suspend fun createPlaybackResult(audio: OpaqueSynthesizedAudio) = CapabilityOperationResult.Success<OpaqueAudioOperation>(operation)
            override suspend fun createPlaybackResult(audio: OpaqueAudioRecording) = CapabilityOperationResult.Success<OpaqueAudioOperation>(operation)
        }
        val bridge = RecordingBridge().apply { enqueue("handle_readiness", completed("""{"ready":true}""")); enqueue("handle_input", yielded(coroutineId = 603, operationId = 703, value = registerClaim(HostOperationKind.PLAYBACK, audioToken = "{token}", delaySeconds = 0.0))) }
        val harness = harness(
            bridge,
            setOf("startup", "handle_readiness", "handle_input"),
            audioOperation = audio,
            deferredAudioPlayback = playback,
            declaredCapabilities = setOf(ChannelCapability.AudioOperation, ChannelCapability.DeferredAudioPlayback),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate()); harness.runtime.refreshReadiness(); val target = acceptedTarget(harness.runtime); target.onInputStarted(session(16_000))
            val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }; runCurrent(); val pending = checkNotNull(harness.runtime.pendingInput()); val registry = audioRegistry(harness.runtime); testScheduler.advanceTimeBy(30_001L); runCurrent()
            assertEquals(listOf(false to "E_TIMEOUT"), bridge.resumeCalls); assertEquals(0, registry.accounting().liveTokens)
            playback.release(DelayedPlaybackOutcome.Heard(DelayedPlaybackOperationId("late"))); runCurrent(); release.await(); assertEquals(listOf(false to "E_TIMEOUT"), bridge.resumeCalls); assertEquals(0, registry.accounting().liveTokens)
            assertEquals(LuaInputResumeResult.Stale, harness.runtime.resumeInput(pending.ownerToken, pending.operationId, true, "late"))
        } finally { harness.close() }
    }

    private fun audioRegistry(runtime: LuaAdapterRuntime): LuaOpaqueAudioRegistry {
        val field = LuaAdapterRuntime::class.java.getDeclaredField("audioRegistry"); field.isAccessible = true
        return checkNotNull(field.get(runtime)) as LuaOpaqueAudioRegistry
    }

    private class CountingTranscription : TranscriptionCapability {
        var calls = 0
        override suspend fun transcribe(recording: OpaqueAudioRecording): CapabilityOperationResult<Transcription> {
            calls++
            return CapabilityOperationResult.Success(Transcription("counted"))
        }
    }

    private class CountingSynthesis : SynthesisCapability {
        var calls = 0
        private val artifact = io.talkcan.channel.capability.SynthesizedAudioArtifact(floatArrayOf(1f), generation = RuntimeGeneration(7))
        override suspend fun synthesize(request: SpeechSynthesisRequest): CapabilityOperationResult<OpaqueSynthesizedAudio> {
            calls++
            return CapabilityOperationResult.Success(artifact)
        }
    }

    private class CountingAudioOperation : AudioOperationCapability {
        var calls = 0
        private val operation = AudioOperationArtifact(RecordedPcm(shortArrayOf(1), 16_000), operationId = "counted", generation = RuntimeGeneration(7))
        override suspend fun createPlaybackResult(audio: OpaqueSynthesizedAudio): CapabilityOperationResult<OpaqueAudioOperation> {
            calls++
            return CapabilityOperationResult.Success(operation)
        }
        override suspend fun createPlaybackResult(audio: OpaqueAudioRecording): CapabilityOperationResult<OpaqueAudioOperation> {
            calls++
            return CapabilityOperationResult.Success(operation)
        }
    }

    private class LateTranscription : TranscriptionCapability {
        private var continuation: kotlin.coroutines.Continuation<CapabilityOperationResult<Transcription>>? = null
        override suspend fun transcribe(recording: OpaqueAudioRecording): CapabilityOperationResult<Transcription> = suspendCoroutine { continuation = it }
        fun complete() { continuation?.resume(CapabilityOperationResult.Success(Transcription("late"))) }
    }
    private class LateSynthesis : SynthesisCapability {
        val artifact = io.talkcan.channel.capability.SynthesizedAudioArtifact(floatArrayOf(1f), generation = RuntimeGeneration(7)); private var continuation: kotlin.coroutines.Continuation<CapabilityOperationResult<OpaqueSynthesizedAudio>>? = null
        override suspend fun synthesize(request: SpeechSynthesisRequest): CapabilityOperationResult<OpaqueSynthesizedAudio> = suspendCoroutine { continuation = it }
        fun complete() { continuation?.resume(CapabilityOperationResult.Success(artifact)) }
    }


    // ── talkcan.fs generic mounted-storage dispatch (non-Journal fixture) ──

    private fun fsResourceDeclarations() = io.talkcan.dependency.PackageResourcesDeclaration(
        listOf(
            io.talkcan.dependency.PackageMountDeclaration(
                id = "output",
                kind = io.talkcan.dependency.PackageMountKind.DIRECTORY_TREE,
                access = io.talkcan.dependency.PackageMountAccess.READ_WRITE,
                required = true,
                label = "Output",
                help = null,
            ),
        ),
    )

    private class FakeAudioFilePort(
        private val recordings: io.talkcan.audiofile.RecordingHost,
    ) : io.talkcan.audiofile.AudioFilePort {
        var exportCalls: Int = 0
            private set

        override fun describe(
            handle: io.talkcan.audiofile.RecordingHandle,
            owner: io.talkcan.audiofile.ExecutionOwner,
        ): io.talkcan.audiofile.AudioFileOutcome<
            io.talkcan.audiofile.AudioMediaMetadata,
        > = when (val borrowed = recordings.borrow(handle, owner)) {
            is io.talkcan.audiofile.RecordingBorrow.Borrowed ->
                io.talkcan.audiofile.AudioFileOutcome.Success(
                    io.talkcan.audiofile.AudioMediaMetadata(
                        sampleRate = borrowed.pcm.sampleRate,
                        channels = 1,
                        durationMs = borrowed.pcm.durationMs,
                        pcmBytes = borrowed.pcm.pcmBytes,
                    ),
                )
            else -> audioFileFailure(
                io.talkcan.audiofile.AudioFileErrorCode.E_INVALID_ARGUMENT,
            )
        }

        override suspend fun open(
            owner: io.talkcan.audiofile.ExecutionOwner,
            mount: io.talkcan.storage.MountHandle,
            path: String,
            options: io.talkcan.audiofile.AudioOpenOptions,
        ): io.talkcan.audiofile.AudioFileOutcome<
            io.talkcan.audiofile.RecordingHandle,
        > {
            val handle = recordings.admit(
                io.talkcan.audiofile.PcmMonoS16Le(
                    ShortArray(16) { it.toShort() },
                    16_000,
                ),
                owner,
            ) ?: return audioFileFailure(
                io.talkcan.audiofile.AudioFileErrorCode.E_BUSY,
            )
            return io.talkcan.audiofile.AudioFileOutcome.Success(handle)
        }

        override suspend fun export(
            owner: io.talkcan.audiofile.ExecutionOwner,
            recording: io.talkcan.audiofile.RecordingHandle,
            mount: io.talkcan.storage.MountHandle,
            path: String,
            options: io.talkcan.audiofile.AudioExportOptions,
        ): io.talkcan.audiofile.AudioFileOutcome<
            io.talkcan.audiofile.AudioExportResult,
        > {
            val pcm = when (val borrowed = recordings.borrow(recording, owner)) {
                is io.talkcan.audiofile.RecordingBorrow.Borrowed -> borrowed.pcm
                else -> return audioFileFailure(
                    io.talkcan.audiofile.AudioFileErrorCode.E_INVALID_ARGUMENT,
                )
            }
            exportCalls += 1
            return io.talkcan.audiofile.AudioFileOutcome.Success(
                io.talkcan.audiofile.AudioExportResult(
                    status = io.talkcan.audiofile.AudioExportStatus.WRITTEN,
                    format = options.format,
                    sampleRate = pcm.sampleRate,
                    channels = pcm.channels,
                    durationMs = pcm.durationMs,
                    bytes = 23,
                ),
            )
        }

        override fun advanceGeneration(newGeneration: Long) = Unit

        override fun close() = Unit

        private fun <T> audioFileFailure(
            code: io.talkcan.audiofile.AudioFileErrorCode,
        ): io.talkcan.audiofile.AudioFileOutcome<T> =
            io.talkcan.audiofile.AudioFileOutcome.Failure(
                io.talkcan.audiofile.AudioFileError(code, "test failure"),
            )
    }

    @Test
    fun `audio file claims dispatch through generic ports with opaque recording ownership`() = runTest {
        val vfs = io.talkcan.storage.Vfs()
        lateinit var audioFiles: FakeAudioFilePort
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
        }
        val harness = harness(
            bridge = bridge,
            callbacks = setOf("startup", "handle_readiness", "handle_input"),
            declaredCapabilities = setOf(
                ChannelCapability.StorageFiles,
                ChannelCapability.AudioFiles,
            ),
            storagePort = vfs.fs,
            resourceDeclarations = fsResourceDeclarations(),
            audioFilePortFactory = LuaAudioFilePortFactory { recordings ->
                FakeAudioFilePort(recordings).also { audioFiles = it }
            },
            mountReadinessStatus = LuaMountReadinessStatus { "available" },
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val resourceContext = org.json.JSONObject(bridge.lastResourceContextJson!!)
            assertTrue(resourceContext.getBoolean("storageFiles"))
            assertTrue(resourceContext.getBoolean("audioFiles"))

            val openId = bridge.registerAudioFileClaim(
                kind = HostOperationKind.AUDIO_OPEN,
                path = "stored/input.wav",
                format = "wav-pcm-s16le",
            )
            bridge.resumeOutcomes.add(completed("""{"opened":true}"""))
            driveFsInput(bridge, harness, openId)
            val openResume = org.json.JSONObject(bridge.resumeCalls.single().second)
            assertTrue(bridge.resumeCalls.single().first)
            assertTrue(openResume.getString("token").isNotBlank())
            assertEquals(
                16_000,
                openResume.getJSONObject("metadata").getInt("sample_rate"),
            )

            bridge.resumeCalls.clear()
            val exportId = bridge.registerAudioFileClaim(
                kind = HostOperationKind.AUDIO_EXPORT,
                audioToken = "{token}",
                path = "daily/capture.wav",
                format = "wav-pcm-s16le",
                mode = "replace",
            )
            bridge.resumeOutcomes.add(completed("""{"exported":true}"""))
            driveFsInput(bridge, harness, exportId)
            val exportResume = org.json.JSONObject(bridge.resumeCalls.single().second)
            assertTrue(bridge.resumeCalls.single().first)
            assertEquals("written", exportResume.getString("status"))
            assertEquals("wav-pcm-s16le", exportResume.getString("format"))
            assertEquals(1, audioFiles.exportCalls)
        } finally {
            harness.close()
        }
    }

    /** Drive one handle_input that yields a single FS claim and runs it to completion. */
    private suspend fun TestScope.driveFsInput(
        bridge: RecordingBridge,
        harness: AdapterHarness,
        claimLabel: String,
    ) {
        bridge.enqueue("handle_input", yielded(value = claimLabel))
        val target = acceptedTarget(harness.runtime)
        target.onInputStarted(session(16_000))
        val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }
        runCurrent()
        assertEquals(ChannelInputResult.None, release.await())
    }

    private fun lastResumeJson(bridge: RecordingBridge): org.json.JSONObject {
        val (success, value) = bridge.resumeCalls.last()
        assertTrue("FS operation must resume successfully, got ($success, $value)", success)
        return org.json.JSONObject(value)
    }

    @Test
    fun `fs operations dispatch through the generic storage port and resume with portable json`() = runTest {
        val vfs = io.talkcan.storage.Vfs()
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
        }
        val harness = harness(
            bridge, setOf("startup", "handle_readiness", "handle_input"),
            declaredCapabilities = setOf(ChannelCapability.StorageFiles),
            storagePort = vfs.fs,
            resourceDeclarations = fsResourceDeclarations(),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()

            // The resource context installed into the kernel must carry the
            // declared storage capability and the declared mount — generically,
            // with no Journal-specific branch.
            val rc = org.json.JSONObject(bridge.lastResourceContextJson!!)
            assertEquals("lua-adapter", rc.getString("instanceId"))
            assertTrue(rc.getBoolean("storageFiles"))
            assertEquals("read-write", rc.getJSONObject("mounts").getJSONObject("output").getString("access"))

            // Chain all six operations through one input: each resume yields the
            // next FS request until the callback terminates.
            val mkdirId = bridge.registerFsClaim(HostOperationKind.FS_MKDIR, path = "a/b", parents = true)
            val writeId = bridge.registerFsClaim(HostOperationKind.FS_WRITE_TEXT, path = "a/b/f.txt", text = "hello", mode = "create-new")
            val statId = bridge.registerFsClaim(HostOperationKind.FS_STAT, path = "a/b/f.txt")
            val readId = bridge.registerFsClaim(HostOperationKind.FS_READ_TEXT, path = "a/b/f.txt", maxBytes = 1024)
            val listId = bridge.registerFsClaim(HostOperationKind.FS_LIST, path = "a/b", limit = 10)
            val removeId = bridge.registerFsClaim(HostOperationKind.FS_REMOVE, path = "a/b/f.txt")
            bridge.enqueue("handle_input", yielded(value = mkdirId))
            bridge.resumeOutcomes.add(yielded(operationId = 11, value = writeId))
            bridge.resumeOutcomes.add(yielded(operationId = 12, value = statId))
            bridge.resumeOutcomes.add(yielded(operationId = 13, value = readId))
            bridge.resumeOutcomes.add(yielded(operationId = 14, value = listId))
            bridge.resumeOutcomes.add(yielded(operationId = 15, value = removeId))
            bridge.resumeOutcomes.add(completed("""{"ok":true}"""))

            val target = acceptedTarget(harness.runtime)
            target.onInputStarted(session(16_000))
            val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }
            runCurrent()
            assertEquals(ChannelInputResult.None, release.await())

            assertEquals(6, bridge.resumeCalls.size)
            assertEquals("created", org.json.JSONObject(bridge.resumeCalls[0].second).getString("status"))
            val write = org.json.JSONObject(bridge.resumeCalls[1].second)
            assertEquals("written", write.getString("status"))
            assertEquals(5L, write.getLong("bytes"))
            val stat = org.json.JSONObject(bridge.resumeCalls[2].second)
            assertEquals("file", stat.getString("kind"))
            assertEquals(5L, stat.getLong("size"))
            val read = org.json.JSONObject(bridge.resumeCalls[3].second)
            assertEquals("hello", read.getString("text"))
            assertEquals(5L, read.getLong("bytes"))
            val list = org.json.JSONObject(bridge.resumeCalls[4].second)
            assertEquals("f.txt", list.getJSONArray("entries").getJSONObject(0).getString("name"))
            assertEquals("file", list.getJSONArray("entries").getJSONObject(0).getString("kind"))
            assertEquals("removed", org.json.JSONObject(bridge.resumeCalls[5].second).getString("status"))
            assertTrue(bridge.resumeCalls.all { it.first })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `fs mount resolution failure resumes with the portable error code verbatim`() = runTest {
        val vfs = io.talkcan.storage.Vfs()
        vfs.resolver.mount = null // no live binding for the declaration
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_input", yielded(value = registerFsClaim(HostOperationKind.FS_STAT, path = "a/b")))
        }
        val harness = harness(
            bridge, setOf("startup", "handle_readiness", "handle_input"),
            declaredCapabilities = setOf(ChannelCapability.StorageFiles),
            storagePort = vfs.fs,
            resourceDeclarations = fsResourceDeclarations(),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            driveFsInput(bridge, harness, bridge.claims.keys.last().toString())
            assertEquals(listOf(false to "E_CAPABILITY_UNDECLARED"), bridge.resumeCalls)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `fs operation failure resumes with the portable error code without audio normalization`() = runTest {
        val vfs = io.talkcan.storage.Vfs()
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            // read_text on a missing file: the port reports E_NOT_FOUND, which is
            // NOT in the audio vocabulary and must pass through verbatim (audio
            // normalization would collapse it to E_HOST_FAILURE).
            enqueue("handle_input", yielded(value = registerFsClaim(HostOperationKind.FS_READ_TEXT, path = "missing.txt", maxBytes = 64)))
        }
        val harness = harness(
            bridge, setOf("startup", "handle_readiness", "handle_input"),
            declaredCapabilities = setOf(ChannelCapability.StorageFiles),
            storagePort = vfs.fs,
            resourceDeclarations = fsResourceDeclarations(),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            driveFsInput(bridge, harness, bridge.claims.keys.last().toString())
            assertEquals(listOf(false to "E_NOT_FOUND"), bridge.resumeCalls)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `fs operation in flight is suppressed when the runtime closes`() = runTest {
        val vfs = io.talkcan.storage.Vfs()
        val gate = CompletableDeferred<Unit>()
        val port = GatedMkdirPort(vfs.fs, gate)
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_input", yielded(value = registerFsClaim(HostOperationKind.FS_MKDIR, path = "a/b", parents = true)))
        }
        val harness = harness(
            bridge, setOf("startup", "handle_readiness", "handle_input"),
            declaredCapabilities = setOf(ChannelCapability.StorageFiles),
            storagePort = port,
            resourceDeclarations = fsResourceDeclarations(),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime)
            target.onInputStarted(session(16_000))
            val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }
            runCurrent()
            // The mkdir is suspended behind the gate; the input is pending.
            assertTrue(harness.runtime.pendingInput() != null)

            // Close terminalizes the pending input and claims the exactly-once gate.
            harness.runtime.close()
            // Release the backend; the late FS result must be suppressed.
            gate.complete(Unit)
            runCurrent()
            assertEquals(ChannelInputResult.None, release.await())
            assertTrue("late FS completion must not resume Lua after close", bridge.resumeCalls.isEmpty())
            assertEquals(1, bridge.closeCalls.get())
        } finally {
            harness.close()
        }
    }

    /** A storage port whose mkdir suspends on a gate so a close race can be staged. */
    private class GatedMkdirPort(
        private val delegate: io.talkcan.storage.MountedStoragePort,
        private val gate: CompletableDeferred<Unit>,
    ) : io.talkcan.storage.MountedStoragePort by delegate {
        override suspend fun mkdir(
            mount: io.talkcan.storage.MountHandle,
            path: String,
            options: io.talkcan.storage.MkdirOptions,
        ): io.talkcan.storage.FilesystemOutcome<io.talkcan.storage.MkdirResult> {
            gate.await()
            return delegate.mkdir(mount, path, options)
        }
    }

    /** Replicates [harness] setup but returns the raw result instead of asserting success. */
    private suspend fun constructRuntimeResult(
        bridge: RecordingBridge,
        hostRuntimeGeneration: RuntimeGeneration = RuntimeGeneration(7),
    ): ChannelRuntimeConstructionResult {
        val instanceId = "lua-adapter"
        val generation = hostRuntimeGeneration
        val parentJob = SupervisorJob()
        val parentScope = CoroutineScope(Dispatchers.Unconfined + parentJob)
        val workers = RuntimeWorkerDispatcher.fromDispatcher(Dispatchers.Unconfined)
        val boundary = RuntimeInvocationBoundary(workers)
        val gate = boundary.openGeneration(instanceId, generation, parentScope)
        val context = GenerationExecutionContextImpl(instanceId, gate, parentScope, { delay(it) })
        val definition = ChannelDefinition(
            id = instanceId,
            name = "Lua adapter",
            implementationId = TEST_LUA_IMPLEMENTATION_ID,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = OpaqueJsonObject.fromJsonObject(org.json.JSONObject()),
        )
        val image = (ImmutableProgramImage.create(
            entryPoint = "main",
            sourceMap = mapOf("main" to "return { startup = function() end }"),
            requirements = LuaProgramRequirements(LUA_VERSION, API_VERSION),
        ) as? ProgramImageCreationResult.Success)?.image
            ?: throw AssertionError("Test image must validate")
        val capabilities = RevocableChannelCapabilityScope(
            identity = CapabilityScopeIdentity(instanceId, generation),
            declaredCapabilities = emptySet(),
            host = NoCapabilitiesHost,
        )
        val provider = testLuaProvider(image, bridge)
        return provider.constructRuntime(
            ChannelRuntimeConstructionRequest(
                definition = definition,
                configuration = ValidatedChannelConfiguration(
                    implementationId = TEST_LUA_IMPLEMENTATION_ID,
                    schemaVersion = 1,
                    payload = definition.configPayload,
                ),
                capabilities = capabilities,
                generationContext = context,
            ),
        )
    }

    private suspend fun acceptedTarget(runtime: LuaAdapterRuntime) =
        (runtime.prepareInput() as? ChannelInputAcceptance.Accepted)?.target
            ?: throw AssertionError("Expected input acceptance")

    private fun session(sampleRate: Int): ChannelAudioInputSession = object : ChannelAudioInputSession {
        override val frames = emptyFlow<ShortArray>()
        override val sampleRate: Int = sampleRate
    }
    private suspend fun harness(
        bridge: RecordingBridge,
        callbacks: Set<String>,
        transcription: TranscriptionCapability? = null,
        synthesis: SynthesisCapability? = null,
        audioOperation: AudioOperationCapability? = null,
        deferredAudioPlayback: DeferredAudioPlaybackCapability? = null,
        timerDelay: suspend (Long) -> Unit = { delay(it) },
        logSink: PluginLogSink = NoOpPluginLogSink,
        hostRuntimeGeneration: RuntimeGeneration = RuntimeGeneration(7),
        configPayload: OpaqueJsonObject = OpaqueJsonObject.fromJsonObject(org.json.JSONObject()),
        declaredCapabilities: Set<ChannelCapability> = emptySet(),
        capabilityAvailability: Map<ChannelCapability, CapabilityAvailability> = emptyMap(),
        unavailableCapabilities: Map<ChannelCapability, CapabilityUnavailableReason> = emptyMap(),
        storagePort: io.talkcan.storage.MountedStoragePort? = null,
        resourceDeclarations: io.talkcan.dependency.PackageResourcesDeclaration =
            io.talkcan.dependency.PackageResourcesDeclaration(emptyList()),
        audioFilePortFactory: LuaAudioFilePortFactory? = null,
        mountReadinessStatus: LuaMountReadinessStatus? = null,
        preparerRegistry: io.talkcan.channel.capability.CapabilityPreparerRegistry =
            io.talkcan.channel.capability.CapabilityPreparerRegistry.empty(),
        declaredPublicCapabilities: Set<String> = emptySet(),
        textOutput: TextOutputCapability? = null,
        textOutputAvailability: CapabilityAvailability = CapabilityAvailability.Recoverable,
        keyboardOutputAdapterFactory: ((CapabilityScopeIdentity, OutputExecutionOwner) -> KeyboardOutputAdapter)? = null,
        enabled: Boolean = true,
    ): AdapterHarness {
        val instanceId = "lua-adapter"
        val generation = hostRuntimeGeneration
        val parentJob = SupervisorJob()
        val parentScope = CoroutineScope(Dispatchers.Unconfined + parentJob)
        val workers = RuntimeWorkerDispatcher.fromDispatcher(Dispatchers.Unconfined)
        val boundary = RuntimeInvocationBoundary(workers)
        val gate = boundary.openGeneration(instanceId, generation, parentScope)
        val context = GenerationExecutionContextImpl(instanceId, gate, parentScope, timerDelay)
        val definition = ChannelDefinition(
            id = instanceId,
            name = "Lua adapter",
            implementationId = TEST_LUA_IMPLEMENTATION_ID,
            enabled = enabled,
            configSchemaVersion = 1,
            configPayload = configPayload,
        )
        val image = (ImmutableProgramImage.create(
            entryPoint = "main",
            sourceMap = mapOf("main" to "return { startup = function() end }"),
            requirements = LuaProgramRequirements(LUA_VERSION, API_VERSION),
        ) as? ProgramImageCreationResult.Success)?.image
            ?: throw AssertionError("Test image must validate")
        val capabilityHost = ScriptedCapabilitiesHost(
            availabilityByCapability = capabilityAvailability,
            transcription = transcription,
            synthesis = synthesis,
            audioOperation = audioOperation,
            deferredAudioPlayback = deferredAudioPlayback,
            unavailableByCapability = unavailableCapabilities,
            textOutput = textOutput,
            textOutputAvailability = textOutputAvailability,
        )
        val capabilities = RevocableChannelCapabilityScope(
            identity = CapabilityScopeIdentity(instanceId, generation),
            declaredCapabilities = declaredCapabilities,
            host = capabilityHost,
        )
        bridge.retainedCallbacks = callbacks
        val result = testLuaProvider(
            image,
            bridge,
            logSink,
            storagePort = storagePort,
            resourceDeclarations = resourceDeclarations,
            audioFilePortFactory = audioFilePortFactory,
            mountReadinessStatus = mountReadinessStatus,
            preparerRegistry = preparerRegistry,
            declaredPublicCapabilities = declaredPublicCapabilities,
            keyboardOutputAdapterFactory = keyboardOutputAdapterFactory,
        ).constructRuntime(
            ChannelRuntimeConstructionRequest(
                definition = definition,
                configuration = ValidatedChannelConfiguration(
                    implementationId = TEST_LUA_IMPLEMENTATION_ID,
                    schemaVersion = 1,
                    payload = definition.configPayload,
                ),
                capabilities = capabilities,
                generationContext = context,
            ),
        )
        val runtime = (result as? ChannelRuntimeConstructionResult.Success)?.runtime as? LuaAdapterRuntime
            ?: throw AssertionError("Expected a validated Lua adapter runtime, got $result")
        return AdapterHarness(runtime, capabilities, capabilityHost, context, boundary, parentJob)
    }

    private class ControlledTimerDelay {
        private data class Pending(
            val delayMillis: Long,
            val release: CompletableDeferred<Unit>,
        )

        private val pending = mutableListOf<Pending>()
        private val twoPending = CompletableDeferred<Unit>()

        suspend fun await(delayMillis: Long) {
            val release = CompletableDeferred<Unit>()
            pending += Pending(delayMillis, release)
            if (pending.size == 2) twoPending.complete(Unit)
            release.await()
        }

        suspend fun awaitPending() {
            twoPending.await()
        }

        fun assertPending(delays: List<Long>) {
            assertEquals(delays, pending.map(Pending::delayMillis))
        }

        fun release(index: Int) {
            pending.removeAt(index).release.complete(Unit)
        }

        fun releaseAll() {
            while (pending.isNotEmpty()) release(0)
        }
    }

    private class AdapterHarness(
        val runtime: LuaAdapterRuntime,
        val capabilityScope: RevocableChannelCapabilityScope,
        val capabilityHost: ScriptedCapabilitiesHost,
        private val context: GenerationExecutionContextImpl,
        private val boundary: RuntimeInvocationBoundary,
        private val parentJob: Job,
    ) {
        private var closed = false
        private val heldTasks = mutableListOf<CompletableDeferred<Unit>>()

        suspend fun retireGeneration() {
            context.closeAndDrain()
        }

        fun stopAdmission() {
            context.stopAdmission()
        }

        fun saturateTaskCapacity() {
            repeat(256) {
                if (context.admitTask { } is GenerationAdmission.Rejected) return
            }
            throw AssertionError("Generation task admission did not enforce a capacity bound")
        }

        fun saturateActiveTaskCapacity() {
            repeat(256) {
                val release = CompletableDeferred<Unit>()
                when (context.admitTask { release.await() }) {
                    is GenerationAdmission.Accepted -> heldTasks += release
                    is GenerationAdmission.Rejected -> return
                }
            }
            throw AssertionError("Generation task admission did not enforce an active capacity bound")
        }

        fun saturateTimerCapacity() {
            repeat(256) {
                if (context.scheduleTimer(3_600.0) { } is GenerationAdmission.Rejected) return
            }
            throw AssertionError("Generation timer admission did not enforce a capacity bound")
        }

        fun authorizeStagedTasks() {
            context.authorizeStagedTasksAfterReady().forEach { it.start() }
        }
        fun stageTask(task: suspend () -> Unit): GenerationAdmission<Unit> = context.admitTask(task)

        suspend fun close() {
            if (closed) return
            closed = true
            runtime.close()
            context.closeAndDrain()
            capabilityScope.revoke()
            boundary.close()
            parentJob.cancel()
        }
    }

    private object NoCapabilitiesHost : ChannelCapabilityHost {
        override suspend fun availability(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<*>,
        ): CapabilityAvailability = CapabilityAvailability.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)

        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> = HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
    }
    private class ScriptedCapabilitiesHost(
        private val availabilityByCapability: Map<ChannelCapability, CapabilityAvailability>,
        private val transcription: TranscriptionCapability? = null,
        private val synthesis: SynthesisCapability? = null,
        private val audioOperation: AudioOperationCapability? = null,
        private val deferredAudioPlayback: DeferredAudioPlaybackCapability? = null,
        private val unavailableByCapability: Map<ChannelCapability, CapabilityUnavailableReason> = emptyMap(),
        private val textOutput: TextOutputCapability? = null,
        private val textOutputAvailability: CapabilityAvailability = CapabilityAvailability.Recoverable,
    ) : ChannelCapabilityHost {
        var availabilityCalls = 0
            private set
        var acquireCalls = 0
            private set
        var prepareCalls = 0
            private set

        override suspend fun availability(identity: CapabilityScopeIdentity, key: CapabilityKey<*>): CapabilityAvailability {
            availabilityCalls++
            if (key == CapabilityKey.TextOutput) return textOutputAvailability
            return availabilityByCapability[key.capability] ?: CapabilityAvailability.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> {
            unavailableByCapability[key.capability]?.let { reason ->
                acquireCalls++
                return HostedCapabilityAcquisition.Unavailable(reason) as HostedCapabilityAcquisition<T>
            }
            acquireCalls++
            return when (key) {
                CapabilityKey.TextOutput -> textOutputAcquisition() as HostedCapabilityAcquisition<T>
                CapabilityKey.Transcription -> transcription?.let { HostedCapabilityAcquisition.Available(it) { } }
                    ?: HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
                CapabilityKey.Synthesis -> synthesis?.let { HostedCapabilityAcquisition.Available(it) { } }
                    ?: HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
                CapabilityKey.AudioOperation -> audioOperation?.let { HostedCapabilityAcquisition.Available(it) { } }
                    ?: HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
                CapabilityKey.DeferredAudioPlayback -> deferredAudioPlayback?.let { HostedCapabilityAcquisition.Available(it) { } }
                    ?: HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
                else -> HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
            } as HostedCapabilityAcquisition<T>
        }

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> {
            prepareCalls++
            if (key == CapabilityKey.TextOutput) {
                prepareGate?.await()
                return (prepareTextOutputOutcome ?: textOutputAcquisition()) as HostedCapabilityAcquisition<T>
            }
            return acquire(identity, key)
        }

        var prepareTextOutputOutcome: HostedCapabilityAcquisition<TextOutputCapability>? = null
        /** 5.8: when set, suspends TextOutput preparation until completed so a race can be triggered mid-flight. */
        var prepareGate: CompletableDeferred<Unit>? = null

        private fun textOutputAcquisition(): HostedCapabilityAcquisition<TextOutputCapability> =
            when (textOutputAvailability) {
                CapabilityAvailability.Available -> textOutput?.let { HostedCapabilityAcquisition.Available(it) { } }
                    ?: HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY)
                CapabilityAvailability.Recoverable ->
                    HostedCapabilityAcquisition.Recoverable(CapabilityUnavailableReason.HOST_NOT_READY)
                is CapabilityAvailability.Unavailable ->
                    HostedCapabilityAcquisition.Unavailable(textOutputAvailability.reason)
            }
    }
    private class ControlledDeferredPlayback : DeferredAudioPlaybackCapability {
        var calls = 0
        var consumeCount = 0
        private var waiter: CompletableDeferred<DelayedPlaybackOutcome>? = null
        override suspend fun scheduleAudio(context: AgentOperationContext, audio: OpaqueAudioOperation, eligibilityDelayMillis: Long): DelayedPlaybackOutcome {
            calls++
            val deferred = CompletableDeferred<DelayedPlaybackOutcome>(); waiter = deferred
            return deferred.await()
        }
        fun release(outcome: DelayedPlaybackOutcome) { waiter?.complete(outcome); consumeCount++ }
    }
    private class NoOpTextOutput : TextOutputCapability {
        override suspend fun sendText(request: io.talkcan.channel.capability.TextOutputRequest) =
            io.talkcan.channel.capability.TextDeliveryOutcome.Delivered("op-1")
        override suspend fun sendKey(request: io.talkcan.channel.capability.TextKeyRequest) =
            io.talkcan.channel.capability.TextDeliveryOutcome.Delivered("op-1")
    }
    /** 8.9/9.7: records keyboard-output submissions and the execution-owner kind that authorized them. */
    private class RecordingKeyboardOutputAdapter(
        override val identity: CapabilityScopeIdentity,
        val owner: OutputExecutionOwner,
    ) : KeyboardOutputAdapter {
        val textRequests = mutableListOf<TextOutputRequest>()
        val keyRequests = mutableListOf<TextKeyRequest>()
        var nextSubmission: KeyboardOutputSubmission =
            KeyboardOutputSubmission.Completed(TextDeliveryOutcome.Delivered("op-1"))
        var beforeSendText: suspend () -> Unit = { }
        override suspend fun sendText(request: TextOutputRequest): KeyboardOutputSubmission {
            beforeSendText()
            textRequests += request
            return nextSubmission
        }
        override suspend fun sendKey(request: TextKeyRequest): KeyboardOutputSubmission {
            keyRequests += request
            return nextSubmission
        }
    }

    private data class InputCase(
        val name: String,
        val callbackResult: String,
        val expectedStatus: ChannelExecutionStatus,
    )

    private data class PublishedLog(
        val instanceId: String,
        val generation: Long,
        val timestampMillis: Long,
        val level: String,
        val payloadJson: String,
    )

    private class RecordingLogSink : PluginLogSink {
        val records = mutableListOf<PublishedLog>()

        override fun tryPublish(
            instanceId: String,
            generation: Long,
            timestampMillis: Long,
            level: String,
            payloadJson: String,
        ): Boolean {
            records += PublishedLog(instanceId, generation, timestampMillis, level, payloadJson)
            return true
        }

        override fun close() = Unit

        override fun recordProjectionLoss() = Unit

        override fun getLossCount(): Long = 0L

        override suspend fun receive(): LogRecord? = null
    }

    private data class CallbackCall(
        val name: String,
        val arguments: LuaValue?,
        val capturedAudioToken: String? = null,
    )
    private data class SpawnAdmission(
        val caller: String,
        val coroutineId: Long,
        val result: Int,
    )
    private class RecordingBridge : LuaKernelBridge {
        val callbackCalls = mutableListOf<CallbackCall>()
        val claims = mutableMapOf<Long, HostOperationClaim>()
        private var nextRequestId = 100L
        var lastCapturedToken: String? = null
        var lastResourceContextJson: String? = null
        val closeCalls = AtomicInteger()
        val startedCoroutines = mutableListOf<Long>()
        val resumeCalls = mutableListOf<Pair<Boolean, String>>()
        val startCoroutineOutcomes = ArrayDeque<LuaKernelOutcome>()
        val spawnAdmissions = mutableListOf<SpawnAdmission>()
        val resumeOutcomes = ArrayDeque<LuaKernelOutcome>()
        var beforeNativeSliceReturns: ((String, List<Long>) -> Unit)? = null
        var onCoroutineStarted: ((Long) -> Unit)? = null
        var onCoroutineResumed: (() -> Unit)? = null
        var retainedCallbacks: Set<String> = setOf("startup")
        var failImageLoad: Boolean = false
        /** When non-null, overrides [retainedCallbacks] for the raw JSON callback list. */
        var rawCallbackNamesJson: String? = null
        var beforeCallback: ((String) -> Unit)? = null
        var throwInputCallback: Boolean = false
        private val scriptedCallbacks = mutableMapOf<String, ArrayDeque<LuaKernelOutcome>>()

        fun enqueue(name: String, outcome: LuaKernelOutcome) {
            scriptedCallbacks.getOrPut(name) { ArrayDeque() }.addLast(outcome)
        }

        override fun claimHostOperation(handle: LuaStateHandle, requestId: Long): HostOperationClaim {
            val claim = claims[requestId] ?: return HostOperationClaim.Rejected("E_STALE")
            // Preserve the old {token} substitution: resolve a placeholder audioToken
            // to the token captured by the most recent input callback.
            if (claim !is HostOperationClaim.Admitted) return claim
            val audioToken = claim.audioToken ?: return claim
            if (!audioToken.contains("{token}")) return claim
            return claim.copy(audioToken = audioToken.replace("{token}", lastCapturedToken.orEmpty()))
        }

        /** Registers a claim; returns its opaque request-id string for use as the yielded value. */
        fun registerClaim(
            kind: HostOperationKind,
            audioToken: String? = null,
            text: String? = null,
            language: String? = null,
            voice: String? = null,
            speed: Double = 1.0,
            delaySeconds: Double = 0.0,
        ): String {
            val id = nextRequestId++
            claims[id] = HostOperationClaim.Admitted(id, kind, audioToken, text, language, voice, speed, delaySeconds)
            return id.toString()
        }

        /** Registers a keyboard-output claim (text/key + logical profile); returns its request-id string. */
        fun registerKeyboardClaim(
            kind: HostOperationKind,
            text: String? = null,
            profile: String? = "linux:us",
            key: String? = null,
        ): String {
            val id = nextRequestId++
            claims[id] = HostOperationClaim.Admitted(
                requestId = id,
                kind = kind,
                audioToken = null,
                text = text,
                language = null,
                voice = null,
                speed = 1.0,
                delaySeconds = 0.0,
                profile = profile,
                key = key,
            )
            return id.toString()
        }

        /** Registers a filesystem claim; returns its opaque request-id string for the yielded value. */
        fun registerFsClaim(
            kind: HostOperationKind,
            declarationId: String = "output",
            mountToken: String = "fs-mount-1",
            path: String = "a/b",
            parents: Boolean = false,
            limit: Long = 0,
            cursor: String? = null,
            maxBytes: Long = 0,
            text: String? = null,
            mode: String? = null,
            missingOk: Boolean = false,
        ): String {
            val id = nextRequestId++
            claims[id] = HostOperationClaim.Admitted(
                requestId = id,
                kind = kind,
                audioToken = null,
                text = text,
                language = null,
                voice = null,
                speed = 1.0,
                delaySeconds = 0.0,
                declarationId = declarationId,
                mountToken = mountToken,
                path = path,
                parents = parents,
                limit = limit,
                cursor = cursor,
                maxBytes = maxBytes,
                mode = mode,
                missingOk = missingOk,
            )
            return id.toString()
        }

        fun registerAudioFileClaim(
            kind: HostOperationKind,
            audioToken: String? = null,
            path: String,
            format: String,
            mode: String? = null,
        ): String {
            val id = nextRequestId++
            claims[id] = HostOperationClaim.Admitted(
                requestId = id,
                kind = kind,
                audioToken = audioToken,
                text = null,
                language = null,
                voice = null,
                speed = 1.0,
                delaySeconds = 0.0,
                declarationId = "output",
                mountToken = "audio-mount-1",
                path = path,
                mode = mode,
                format = format,
            )
            return id.toString()
        }

        override fun setResourceContext(handle: LuaStateHandle, resourceContextJson: String): LuaKernelOutcome {
            lastResourceContextJson = resourceContextJson
            return completed()
        }

        override fun create(config: LuaKernelConfig): LuaKernelOutcome = LuaKernelOutcome.Created(
            stateId = 41,
            generation = 7,
            luaVersion = LUA_VERSION,
            bindingVersion = "recording",
            topology = "recording",
        )

        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome = completed()
        override fun start(handle: LuaStateHandle): LuaKernelOutcome = completed()
        override fun resume(
            operation: LuaOperationHandle,
            success: Boolean,
            value: String,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            resumeCalls += success to value
            onCoroutineResumed?.invoke()
            return admitSpawned("resume", resumeOutcomes.removeFirstOrNull() ?: completed(), spawnAdmission)
        }
        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = LuaKernelOutcome.Cancelled(41, 7, operation.operationId.value)
        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = LuaKernelOutcome.Interrupted(41, 7, "interrupted", 0)
        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = LuaKernelOutcome.Snapshot(
            stateId = 41,
            generation = 7,
            elapsedNanos = 0,
            luaVersion = LUA_VERSION,
            bindingVersion = "recording",
            topology = "recording",
        )
        override fun close(handle: LuaStateHandle): LuaKernelOutcome {
            closeCalls.incrementAndGet()
            return LuaKernelOutcome.Closed(41, 7)
        }
        override fun loadProgramImage(handle: LuaStateHandle, entryPoint: String, sourceMap: Map<String, String>): LuaKernelOutcome =
            if (failImageLoad) {
                LuaKernelOutcome.RuntimeFailure(41, 7, "simulated image load failure")
            } else {
                completed(
                    rawCallbackNamesJson ?: org.json.JSONArray(retainedCallbacks.toList()).toString()
                )
            }
        override fun invokeStartupCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            config: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            callbackCalls += CallbackCall(callbackHandle.name, config)
            beforeCallback?.invoke(callbackHandle.name)
            return admitSpawned(
                "startup",
                scriptedCallbacks[callbackHandle.name]?.removeFirstOrNull() ?: completed(),
                spawnAdmission,
            )
        }

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            callbackCalls += CallbackCall(callbackHandle.name, arguments)
            beforeCallback?.invoke(callbackHandle.name)
            return admitSpawned(
                callbackHandle.name,
                scriptedCallbacks[callbackHandle.name]?.removeFirstOrNull() ?: completed(),
                spawnAdmission,
            )
        }
        override fun invokeSosCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            callbackCalls += CallbackCall(callbackHandle.name, arguments)
            beforeCallback?.invoke(callbackHandle.name)
            return admitSpawned(
                callbackHandle.name,
                scriptedCallbacks[callbackHandle.name]?.removeFirstOrNull() ?: completed(),
                spawnAdmission,
            )
        }
        override fun invokeInputCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            capturedAudioToken: String,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            lastCapturedToken = capturedAudioToken
            callbackCalls += CallbackCall(callbackHandle.name, arguments, capturedAudioToken)
            if (throwInputCallback) throw IllegalStateException("simulated input bridge failure")
            beforeCallback?.invoke(callbackHandle.name)
            val scripted = scriptedCallbacks[callbackHandle.name]?.removeFirstOrNull() ?: completed()
            val outcome = if (scripted is LuaKernelOutcome.Yielded && scripted.value?.contains("{token}") == true) {
                scripted.copy(value = scripted.value.replace("{token}", capturedAudioToken))
            } else scripted
            return admitSpawned("input", outcome, spawnAdmission)
        }
        override fun startCoroutine(
            handle: LuaStateHandle,
            coroutineId: LuaCoroutineId,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            startedCoroutines += coroutineId.value
            onCoroutineStarted?.invoke(coroutineId.value)
            return admitSpawned(
                "start:${coroutineId.value}",
                startCoroutineOutcomes.removeFirstOrNull() ?: completed(),
                spawnAdmission,
            )
        }

        private fun admitSpawned(
            caller: String,
            outcome: LuaKernelOutcome,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            val spawned = when (outcome) {
                is LuaKernelOutcome.Completed -> outcome.spawnedCoroutines.orEmpty()
                is LuaKernelOutcome.Yielded -> outcome.spawnedCoroutines.orEmpty()
                else -> return outcome
            }
            val accepted = spawned.filter { coroutineId ->
                val result = spawnAdmission.admitTask(coroutineId)
                spawnAdmissions += SpawnAdmission(caller, coroutineId, result)
                result == 0
            }
            beforeNativeSliceReturns?.invoke(caller, spawned)
            if (accepted == spawned) return outcome
            return when (outcome) {
                is LuaKernelOutcome.Completed -> outcome.copy(spawnedCoroutines = accepted)
                is LuaKernelOutcome.Yielded -> outcome.copy(spawnedCoroutines = accepted)
                else -> outcome
            }
        }
    }

    private companion object {
        fun completed(
            value: String? = null,
            spawnedCoroutines: List<Long>? = null,
            logs: List<String>? = null,
        ): LuaKernelOutcome.Completed = LuaKernelOutcome.Completed(
            stateId = 41,
            generation = 7,
            coroutineId = null,
            value = value,
            elapsedNanos = 0,
            luaVersion = LUA_VERSION,
            bindingVersion = "recording",
            topology = "recording",
            spawnedCoroutines = spawnedCoroutines,
            logs = logs,
        )

        fun yielded(
            coroutineId: Long = 9,
            operationId: Long = 10,
            value: String? = null,
        ): LuaKernelOutcome.Yielded = LuaKernelOutcome.Yielded(
            stateId = 41,
            generation = 7,
            coroutineId = coroutineId,
            operationId = operationId,
            value = value,
        )
    }
}
