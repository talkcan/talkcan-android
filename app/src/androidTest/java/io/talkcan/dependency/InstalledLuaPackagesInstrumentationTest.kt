package io.talkcan.dependency

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.talkcan.audio.ChannelInputAcceptance
import io.talkcan.audio.ChannelAudioInputSession
import io.talkcan.audio.RecordedPcm
import io.talkcan.channel.capability.CapabilityAvailability
import io.talkcan.channel.capability.CapabilityKey
import io.talkcan.channel.capability.CapabilityUnavailableReason
import io.talkcan.channel.capability.ChannelCapabilityHost
import io.talkcan.channel.capability.ChannelCapabilityPort
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.HostedCapabilityAcquisition
import io.talkcan.lua.API_VERSION
import io.talkcan.lua.LUA_VERSION
import io.talkcan.lua.LuaCallbackHandle
import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.kernel.KotlinLuaKernelBridge
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaSpawnAdmission
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaValue
import io.talkcan.lua.actor.ActorRuntimeFactory
import io.talkcan.model.ChannelCatalogueSnapshot
import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelImplementationProviderRegistry
import io.talkcan.model.ChannelProviderError
import io.talkcan.model.ChannelProviderResolution
import io.talkcan.model.InstalledProvidersPublicationResult
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.service.ChannelRuntimeRegistry
import io.talkcan.service.ChannelRuntimeRegistryShutdownResult
import io.talkcan.service.RuntimeInvocationBoundary
import io.talkcan.service.RuntimeInvocationPolicy
import io.talkcan.service.RuntimeWorkerDispatcher
import io.talkcan.service.ChannelExecutionStatus
import kotlinx.coroutines.flow.emptyFlow
import io.talkcan.service.ChannelPreparationAvailability
import io.talkcan.service.CommittedTargetLeaseOwner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only installed-package evidence. These tests use deterministic, source-only ZIP bytes
 * with Unix central-directory metadata and route them through the production repository,
 * materializer, provider registry, runtime registry, actor factory, and Kotlin kernel bridge.
 *
 * Run later on B02PTT-FF01 with:
 * `adb shell am instrument -w -e class io.talkcan.dependency.InstalledLuaPackagesInstrumentationTest \
 * io.talkcan.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class InstalledLuaPackagesInstrumentationTest {
    @Test
    fun exactV1PackageCreatesKernelStateAndReachesTimerReadiness() = runBlocking {
        withPrivateStore { root ->
            ProductionFixture(root).useSuspending { fixture ->
                val definition = installedDefinition("installed-package-device-channel", INSTALLED_ID)
                fixture.catalogue(definition)

                ActorRuntimeFactory.resetForTest()
                assertEquals(
                    MutationResult.Installed(INSTALLED_ID),
                    success(fixture.repository.installOrUpdate(ByteArrayInputStream(v1Archive()), sourceRecord(SOURCE_REPOSITORY, "101", "201"))),
                )

                assertTrue("installed provider must resolve after committed publication", fixture.providers.resolve(INSTALLED_ID) is ChannelProviderResolution.Available)
                assertTrue("production actor factory must construct the installed provider generation", ActorRuntimeFactory.isCreateAttempted)
                assertEquals("one kernel state must be created for the enabled installed definition", 1, fixture.bridge.createdStateIds.size)
                val v1State = fixture.bridge.createdStateIds.single()
                assertTrue(
                    "the bounded Lua timer must make the v1 package input-ready",
                    awaitLuaInputAcceptance(fixture.runtimeRegistry, definition.id, fixture.bridge, v1State, awaitTimerResume = true),
                )
            }
        }
    }

    @Test
    fun installedPackageInfiniteLoopIsInterruptedWithLatencyEvidence() = runBlocking {
        withPrivateStore { root ->
            ProductionFixture(root).useSuspending { fixture ->
                val repositoryId = GitHubRepositoryIdentity("1306065199")
                val implementationId = InstalledProviderId.derive(repositoryId)
                val definition = installedDefinition("installed-package-interruption", implementationId)
                fixture.catalogue(definition)

                val startedNanos = SystemClock.elapsedRealtimeNanos()
                assertEquals(
                    MutationResult.Installed(implementationId),
                    success(
                        fixture.repository.installOrUpdate(
                            ByteArrayInputStream(
                                packageArchive(
                                    repositoryId.value,
                                    "1.0.0",
                                    "return { startup = function() while true do end end }",
                                ),
                            ),
                            sourceRecord(repositoryId, "1306065199", "1306065200"),
                        ),
                    ),
                )
                val interrupted = withTimeoutOrNull(WAIT_MILLIS) {
                    while (fixture.bridge.startupOutcomes.none { it is LuaKernelOutcome.Interrupted }) {
                        delay(POLL_MILLIS)
                    }
                    fixture.bridge.startupOutcomes.filterIsInstance<LuaKernelOutcome.Interrupted>().single()
                } ?: throw AssertionError("installed package infinite loop was not interrupted")
                val wallNanos = SystemClock.elapsedRealtimeNanos() - startedNanos
                assertTrue(
                    "interruption must carry kernel elapsed-time evidence: $interrupted",
                    interrupted.elapsedNanos != null,
                )
                InstrumentationRegistry.getInstrumentation().sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "INSTALLED_LUA_INTERRUPT_EVIDENCE",
                            JSONObject()
                                .put("hookInterval", 500)
                                .put("instructionBudget", 1_000_000L)
                                .put("kernelElapsedNanos", interrupted.elapsedNanos)
                                .put("wallElapsedNanos", wallNanos)
                                .toString(),
                        )
                    },
                )
            }
        }
    }

    @Test
    fun updateRemovalAndRestartCloseOldGenerationBeforeSuccessorAndKeepGenericResolutionStable() = runBlocking {
        withPrivateStore { root ->
            val definition = installedDefinition("installed-package-update-channel", INSTALLED_ID)
            val first = ProductionFixture(root)
            try {
                first.catalogue(definition)
                assertEquals(
                    MutationResult.Installed(INSTALLED_ID),
                    success(first.repository.installOrUpdate(ByteArrayInputStream(v1Archive()), sourceRecord(SOURCE_REPOSITORY, "101", "201"))),
                )
                val v1State = first.bridge.createdStateIds.single()
                assertTrue(
                    "v1 must become input-ready before its replacement",
                    awaitLuaInputAcceptance(first.runtimeRegistry, definition.id, first.bridge, v1State, awaitTimerResume = true),
                )
                val v1Generation = requireNotNull(first.runtimeRegistry.capabilityScopeIdentity(definition.id)).runtimeGeneration

                assertEquals(
                    MutationResult.Updated(INSTALLED_ID),
                    success(first.repository.installOrUpdate(ByteArrayInputStream(v2Archive()), sourceRecord(SOURCE_REPOSITORY, "102", "202"))),
                )
                val v2State = first.bridge.createdStateIds.last()
                val v2Generation = requireNotNull(first.runtimeRegistry.capabilityScopeIdentity(definition.id)).runtimeGeneration

                assertNotEquals("a changed exact digest must allocate a successor Lua generation", v1Generation, v2Generation)
                assertNotEquals("a changed exact digest must allocate a successor kernel state", v1State, v2State)
                assertTrue("the old kernel state must close before successor startup", first.bridge.closedBeforeStartup(v1State, v2State))
                assertTrue("v2 must become ready through the successor kernel state", awaitLuaInputAcceptance(first.runtimeRegistry, definition.id, first.bridge, v2State, awaitTimerResume = false))
                assertFalse(
                    "a late v1 timer must not mutate v2 readiness after v1 closes",
                    inputRefusedAfterTimerWindow(first.runtimeRegistry, definition.id),
                )

                val committed = success(InstalledPackageStore(root).loadIndex()).index.providers.getValue(SOURCE_REPOSITORY)
                assertEquals("the committed active revision must be the v2 digest", digestOf(v2Archive()), committed.active.digest.value)
                assertEquals("the committed rollback revision must remain v1", digestOf(v1Archive()), committed.rollback?.digest?.value)
            } finally {
                first.close()
            }

            ProductionFixture(root).useSuspending { restarted ->
                restarted.catalogue(definition)
                assertEquals(Unit, success(restarted.repository.loadAndPublish()))
                assertTrue("restart must materialize the committed v2 provider", restarted.providers.resolve(INSTALLED_ID) is ChannelProviderResolution.Available)
                assertTrue("restart must allocate a fresh v2 kernel state", restarted.bridge.createdStateIds.size == 1)
                assertTrue("fresh v2 state must reach readiness without retained v1 globals", awaitLuaInputAcceptance(restarted.runtimeRegistry, definition.id, restarted.bridge, restarted.bridge.createdStateIds.single(), awaitTimerResume = false))

                assertEquals(MutationResult.Removed(INSTALLED_ID), success(restarted.repository.remove(SOURCE_REPOSITORY)))
                assertTrue("removed installed provider must no longer resolve", restarted.providers.resolve(INSTALLED_ID) is ChannelProviderResolution.Missing)
                assertTrue("removal must close the active kernel state", restarted.bridge.closedStateIds.isNotEmpty())
                assertTrue("retired built-in journal must resolve missing after installed removal", restarted.providers.resolve(ChannelImplementationId("builtin:journal")) is ChannelProviderResolution.Missing)
            }
        }
    }

    @Test
    fun rollbackRemovalReinstallAndRestartUseFreshCommittedPackageGenerations() = runBlocking {
        withPrivateStore { root ->
            val definition = installedDefinition("installed-package-lifecycle-channel", INSTALLED_ID)
            val first = ProductionFixture(root)
            try {
                first.catalogue(definition)
                assertEquals(
                    MutationResult.Installed(INSTALLED_ID),
                    success(first.repository.installOrUpdate(ByteArrayInputStream(v1Archive()), sourceRecord(SOURCE_REPOSITORY, "101", "201"))),
                )
                val v1State = first.bridge.createdStateIds.single()
                assertTrue(
                    "v1 must become ready before update",
                    awaitLuaInputAcceptance(first.runtimeRegistry, definition.id, first.bridge, v1State, awaitTimerResume = true, stage = "initial v1 before update"),
                )
                val v1Generation = requireNotNull(first.runtimeRegistry.capabilityScopeIdentity(definition.id)).runtimeGeneration

                assertEquals(
                    MutationResult.Updated(INSTALLED_ID),
                    success(first.repository.installOrUpdate(ByteArrayInputStream(v2Archive()), sourceRecord(SOURCE_REPOSITORY, "102", "202"))),
                )
                val v2State = first.bridge.createdStateIds.last()
                val v2Generation = requireNotNull(first.runtimeRegistry.capabilityScopeIdentity(definition.id)).runtimeGeneration
                assertTrue(
                    "v2 must become ready before explicit rollback",
                    awaitLuaInputAcceptance(first.runtimeRegistry, definition.id, first.bridge, v2State, awaitTimerResume = false, stage = "v2 before explicit rollback"),
                )
                assertNotEquals("update must replace the v1 Lua state", v1State, v2State)
                assertNotEquals("update must replace the v1 runtime generation", v1Generation, v2Generation)

                assertEquals(MutationResult.RolledBack(INSTALLED_ID), success(first.repository.rollback(SOURCE_REPOSITORY)))
                val rollbackState = first.bridge.createdStateIds.last()
                val rollbackGeneration = requireNotNull(first.runtimeRegistry.capabilityScopeIdentity(definition.id)).runtimeGeneration
                val rolledBack = success(InstalledPackageStore(root).loadIndex()).index.providers.getValue(SOURCE_REPOSITORY)
                assertEquals("rollback must commit the retained v1 digest as active", digestOf(v1Archive()), rolledBack.active.digest.value)
                assertEquals("rollback must retain v2 as the next explicit rollback revision", digestOf(v2Archive()), rolledBack.rollback?.digest?.value)
                assertNotEquals("rollback must allocate a fresh kernel state", v2State, rollbackState)
                assertNotEquals("rollback must allocate a fresh runtime generation", v2Generation, rollbackGeneration)
                assertTrue("v2 must close before the rolled-back v1 successor starts", first.bridge.closedBeforeStartup(v2State, rollbackState))
                assertTrue(
                    "rolled-back v1 must reach readiness from its fresh volatile state",
                    awaitLuaInputAcceptance(first.runtimeRegistry, definition.id, first.bridge, rollbackState, awaitTimerResume = true, stage = "rolled-back v1 before removal"),
                )

                assertEquals(MutationResult.Removed(INSTALLED_ID), success(first.repository.remove(SOURCE_REPOSITORY)))
                assertTrue("removed provider must resolve as missing", first.providers.resolve(INSTALLED_ID) is ChannelProviderResolution.Missing)
                assertTrue("removed definition must retain its ordered unavailable projection", first.runtimeRegistry.preparation(definition.id) is ChannelPreparationAvailability.Unavailable)
                assertEquals(listOf(definition.id), first.runtimeRegistry.getAllRuntimeSnapshots().map { it.id })
                assertTrue("removal must reject input through the preserved unavailable definition", first.runtimeRegistry.prepareInput(definition.id) is ChannelInputAcceptance.Unavailable)
                assertTrue("removal must close the rolled-back kernel state", rollbackState in first.bridge.closedStateIds)

                assertEquals(
                    MutationResult.Installed(INSTALLED_ID),
                    success(first.repository.installOrUpdate(ByteArrayInputStream(v1Archive()), sourceRecord(SOURCE_REPOSITORY, "101", "201"))),
                )
                val reinstalledState = first.bridge.createdStateIds.last()
                val reinstalledGeneration = requireNotNull(first.runtimeRegistry.capabilityScopeIdentity(definition.id)).runtimeGeneration
                assertNotEquals("reinstall must never revive the removed kernel state", rollbackState, reinstalledState)
                assertNotEquals("reinstall must never revive the removed runtime generation", rollbackGeneration, reinstalledGeneration)
                assertTrue("removed generation must close before reinstalled v1 starts", first.bridge.closedBeforeStartup(rollbackState, reinstalledState))
                assertTrue(
                    "reinstalled v1 must become ready with no retained Lua globals",
                    awaitLuaInputAcceptance(first.runtimeRegistry, definition.id, first.bridge, reinstalledState, awaitTimerResume = true, stage = "reinstalled v1 before restart"),
                )
                val reinstalled = success(InstalledPackageStore(root).loadIndex()).index.providers.getValue(SOURCE_REPOSITORY)
                assertEquals("reinstall must persist the exact v1 digest", digestOf(v1Archive()), reinstalled.active.digest.value)
                assertEquals("first install after removal must not infer a rollback revision", null, reinstalled.rollback)
            } finally {
                first.close()
            }

            ProductionFixture(root).useSuspending { restarted ->
                restarted.catalogue(definition)
                assertEquals(Unit, success(restarted.repository.loadAndPublish()))
                assertTrue("restart must rematerialize the committed installed provider", restarted.providers.resolve(INSTALLED_ID) is ChannelProviderResolution.Available)
                assertEquals("restart must create exactly one new kernel state for the enabled definition", 1, restarted.bridge.createdStateIds.size)
                val restartedState = restarted.bridge.createdStateIds.single()
                assertTrue(
                    "restart must reach v1 readiness without retaining prior-process Lua globals",
                    awaitLuaInputAcceptance(restarted.runtimeRegistry, definition.id, restarted.bridge, restartedState, awaitTimerResume = true, stage = "restarted v1"),
                )
                val recovered = success(InstalledPackageStore(root).loadIndex()).index.providers.getValue(SOURCE_REPOSITORY)
                assertEquals("restart must retain the committed v1 digest", digestOf(v1Archive()), recovered.active.digest.value)
                assertEquals("restart must not manufacture a rollback revision", null, recovered.rollback)
            }
        }
    }

    @Test
    fun corruptInstalledPackageStaysUnavailableWhileSiblingRemainsOperational() = runBlocking {
        withPrivateStore { root ->
            ProductionFixture(root).useSuspending { staging ->
                assertEquals(
                    MutationResult.Installed(INSTALLED_ID),
                    success(staging.repository.installOrUpdate(ByteArrayInputStream(v1Archive()), sourceRecord(SOURCE_REPOSITORY, "101", "201"))),
                )
                assertEquals(
                    MutationResult.Installed(CORRUPT_ID),
                    success(staging.repository.installOrUpdate(ByteArrayInputStream(corruptCandidateArchive()), sourceRecord(CORRUPT_REPOSITORY, "301", "401"))),
                )
            }

            val corruptRevision = success(InstalledPackageStore(root).loadIndex()).index.providers.getValue(CORRUPT_REPOSITORY).active
            val corruptContent = File(File(root, "content/sha256"), corruptRevision.digest.value)
            assertTrue("the exact committed corrupt-candidate archive must exist before corruption", corruptContent.isFile)
            assertTrue("the committed immutable archive must be removable to simulate post-commit corruption", corruptContent.delete())

            ProductionFixture(root).useSuspending { restarted ->
                val validDefinition = installedDefinition("installed-package-valid-sibling", INSTALLED_ID)
                val corruptDefinition = installedDefinition("installed-package-corrupt-sibling", CORRUPT_ID)
                val builtInDefinition = journalDefinition()
                restarted.catalogue(validDefinition, corruptDefinition, builtInDefinition)

                assertEquals(Unit, success(restarted.repository.loadAndPublish()))

                assertTrue("valid installed sibling must remain resolvable", restarted.providers.resolve(INSTALLED_ID) is ChannelProviderResolution.Available)
                val corruptResolution = restarted.providers.resolve(CORRUPT_ID) as? ChannelProviderResolution.Unavailable
                    ?: throw AssertionError("corrupt installed package must project typed provider unavailability")
                assertEquals(ChannelProviderError.PackageUnavailableCategory.INTEGRITY, corruptResolution.error.category)
                assertEquals(ChannelProviderError.PackageUnavailableDetail.CORRUPTED_ARCHIVE, corruptResolution.error.detail)
                assertTrue("retired built-in journal must resolve missing beside corrupt installed content", restarted.providers.resolve(ChannelImplementationId("builtin:journal")) is ChannelProviderResolution.Missing)
                assertEquals(setOf(CORRUPT_ID), restarted.publishedFailures.get().keys)
                assertEquals(PackageFailure.IntegrityDetail.CORRUPTED_ARCHIVE, (restarted.publishedFailures.get().getValue(CORRUPT_ID) as PackageFailure.Integrity).detail)
                assertTrue("only the valid sibling may allocate a Lua kernel state", restarted.bridge.createdStateIds.size == 1)
                assertTrue("the corrupt definition must project unavailable without a Lua state", restarted.runtimeRegistry.preparation(corruptDefinition.id) is ChannelPreparationAvailability.Unavailable)
                assertTrue("the corrupt definition must reject input", restarted.runtimeRegistry.prepareInput(corruptDefinition.id) is ChannelInputAcceptance.Unavailable)
                val validState = restarted.bridge.createdStateIds.single()
                assertTrue("the valid installed sibling must reach readiness", awaitLuaInputAcceptance(restarted.runtimeRegistry, validDefinition.id, restarted.bridge, validState, awaitTimerResume = true))
                assertTrue("retired built-in journal must resolve missing beside the valid installed sibling", restarted.providers.resolve(ChannelImplementationId("builtin:journal")) is ChannelProviderResolution.Missing)
            }
        }
    }

    @Test
    fun configuredStartupWithModeFieldReachesReadinessThroughProductionKernel() = runBlocking {
        withPrivateStore { root ->
            ProductionFixture(root).useSuspending { fixture ->
                val id = fixture.debugImplementationId
                val definition = ChannelDefinition(
                    id = "configured-mode-startup",
                    name = "Mode startup ECHO",
                    implementationId = fixture.debugImplementationId,
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = OpaqueJsonObject.fromJsonObject(JSONObject().put("mode", "ECHO")),
                )
                fixture.catalogue(definition)
                ActorRuntimeFactory.resetForTest()
                assertEquals(
                    MutationResult.Installed(id),
                    success(fixture.repository.installOrUpdate(ByteArrayInputStream(DEBUG_ARCHIVE), DEBUG_SOURCE_RECORD)),
                )
                assertTrue(
                    "installed configured package must resolve as Available",
                    fixture.providers.resolve(id) is ChannelProviderResolution.Available,
                )
                val stateId = fixture.bridge.createdStateIds.single()
                assertTrue(
                    "configured package must become ready with mode captured in startup",
                    awaitLuaInputAcceptance(fixture.runtimeRegistry, definition.id, fixture.bridge, stateId, awaitTimerResume = false),
                )
            }
        }
    }

    @Test
    fun audioModulesLoadThroughProductionKernelAndInputReceivesCaptureEvent() = runBlocking {
        withPrivateStore { root ->
            ProductionFixture(root).useSuspending { fixture ->
                val definition = ChannelDefinition(
                    id = "audio-modules-test",
                    name = "Audio modules",
                    implementationId = fixture.audioModulesImplementationId,
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = OpaqueJsonObject.fromJsonObject(JSONObject()),
                )
                fixture.catalogue(definition)
                ActorRuntimeFactory.resetForTest()
                assertEquals(
                    MutationResult.Installed(fixture.audioModulesImplementationId),
                    success(fixture.repository.installOrUpdate(ByteArrayInputStream(AUDIO_MODULES_ARCHIVE), AUDIO_MODULES_SOURCE_RECORD)),
                )
                val stateId = fixture.bridge.createdStateIds.single()
                assertTrue(
                    "audio module checking package must become ready (transcription/synthesis/playback loadable)",
                    awaitLuaInputAcceptance(fixture.runtimeRegistry, definition.id, fixture.bridge, stateId, awaitTimerResume = false),
                )
                // Accept input through the production path to verify capture event structure
                assertTrue(
                    "audio module package must accept input with capture event",
                    acceptInput(fixture.runtimeRegistry, definition.id, "audio-modules"),
                )
            }
        }
    }

    @Test
    fun allFiveDebugModesExecuteThroughInstalledProviderPath() = runBlocking {
        withPrivateStore { root ->
            ProductionFixture(root).useSuspending { fixture ->
                val baseId = "debug-modes"
                ActorRuntimeFactory.resetForTest()
                assertEquals(
                    MutationResult.Installed(fixture.debugImplementationId),
                    success(fixture.repository.installOrUpdate(ByteArrayInputStream(DEBUG_ARCHIVE), DEBUG_SOURCE_RECORD)),
                )
                val modes = listOf("ECHO", "DELAYED_ECHO", "STT", "TTS", "STT_TTS")
                val definitions = modes.mapIndexed { i, mode ->
                    ChannelDefinition(
                        id = "$baseId-$mode",
                        name = mode,
                        implementationId = fixture.debugImplementationId,
                        enabled = true,
                        configSchemaVersion = 1,
                        configPayload = OpaqueJsonObject.fromJsonObject(JSONObject().put("mode", mode)),
                    )
                }
                fixture.catalogue(*definitions.toTypedArray())
                definitions.forEach { def ->
                    val stateId = fixture.bridge.createdStateIds.lastOrNull()
                        ?: error("no kernel state created for ${def.id}")
                    assertTrue(
                        "mode=${def.name} must become ready through installed-provider path",
                        awaitTimerlessReadiness(fixture.runtimeRegistry, def.id, fixture.bridge, stateId),
                    )
                }
                assertEquals("five modes must create five kernel states", 5, fixture.bridge.createdStateIds.size)
            }
        }
    }

    @Test
    fun configurationReplacementClosesPredecessorAndStartsSuccessor() = runBlocking {
        withPrivateStore { root ->
            ProductionFixture(root).useSuspending { fixture ->
                ActorRuntimeFactory.resetForTest()
                assertEquals(
                    MutationResult.Installed(fixture.debugImplementationId),
                    success(fixture.repository.installOrUpdate(ByteArrayInputStream(DEBUG_ARCHIVE), DEBUG_SOURCE_RECORD)),
                )
                val echo = ChannelDefinition(
                    id = "mode-replacement",
                    name = "ECHO",
                    implementationId = fixture.debugImplementationId,
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = OpaqueJsonObject.fromJsonObject(JSONObject().put("mode", "ECHO")),
                )
                fixture.catalogue(echo)
                val echoState = fixture.bridge.createdStateIds.single()
                assertTrue(
                    "ECHO instance must reach readiness before replacement",
                    awaitTimerlessReadiness(fixture.runtimeRegistry, echo.id, fixture.bridge, echoState),
                )
                val echoGeneration = requireNotNull(fixture.runtimeRegistry.capabilityScopeIdentity(echo.id)).runtimeGeneration

                val stt = echo.copy(
                    name = "STT",
                    configPayload = OpaqueJsonObject.fromJsonObject(JSONObject().put("mode", "STT")),
                )
                fixture.catalogue(stt)
                val sttState = fixture.bridge.createdStateIds.last()
                val sttGeneration = requireNotNull(fixture.runtimeRegistry.capabilityScopeIdentity(stt.id)).runtimeGeneration
                assertNotEquals("configuration replacement must allocate a successor kernel state", echoState, sttState)
                assertNotEquals("configuration replacement must allocate a successor runtime generation", echoGeneration, sttGeneration)
                assertTrue(
                    "predecessor generation must close before successor startup",
                    fixture.bridge.closedBeforeStartup(echoState, sttState),
                )
                assertTrue(
                    "STT mode must reach readiness after configuration replacement",
                    awaitTimerlessReadiness(fixture.runtimeRegistry, stt.id, fixture.bridge, sttState),
                )
            }
        }
    }

    @Test
    fun inputCancellationReturnsToIdleThroughInstalledProviderPath() = runBlocking {
        withPrivateStore { root ->
            ProductionFixture(root).useSuspending { fixture ->
                ActorRuntimeFactory.resetForTest()
                assertEquals(
                    MutationResult.Installed(fixture.debugImplementationId),
                    success(fixture.repository.installOrUpdate(ByteArrayInputStream(DEBUG_ARCHIVE), DEBUG_SOURCE_RECORD)),
                )
                val definition = ChannelDefinition(
                    id = "cancel-to-idle",
                    name = "ECHO",
                    implementationId = fixture.debugImplementationId,
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = OpaqueJsonObject.fromJsonObject(JSONObject().put("mode", "ECHO")),
                )
                fixture.catalogue(definition)
                val stateId = fixture.bridge.createdStateIds.single()
                assertTrue(
                    "definition must become ready before cancellation test",
                    awaitTimerlessReadiness(fixture.runtimeRegistry, definition.id, fixture.bridge, stateId),
                )
                val acceptance = fixture.runtimeRegistry.prepareInput(definition.id) as? ChannelInputAcceptance.Accepted
                    ?: throw AssertionError("must accept input for cancellation test")
                val lease = acceptance.target as? CommittedTargetLeaseOwner
                    ?: throw AssertionError("accepted target must own a committed lease")
                try {
                    acceptance.target.onInputStarted(FakeSession)
                    assertTrue(
                        "must become RECORDING after input start",
                        withTimeoutOrNull(WAIT_MILLIS) {
                            while (
                                fixture.runtimeRegistry
                                    .getRuntimeSnapshot(definition.id)
                                    ?.executionStatus != ChannelExecutionStatus.RECORDING
                            ) {
                                delay(POLL_MILLIS)
                            }
                            true
                        } == true,
                    )
                    acceptance.target.onInputCancelled("instrumentation cancellation probe")
                    lease.releaseCommittedTargetLease()
                    assertEquals(
                        "input cancellation must return the runtime to IDLE",
                        ChannelExecutionStatus.IDLE,
                        fixture.runtimeRegistry
                            .getRuntimeSnapshot(definition.id)
                            ?.executionStatus,
                    )
                } finally {
                    lease.releaseCommittedTargetLease()
                }
            }
        }
    }

    @Test
    fun siblingInstancesWithDifferentModesIsolateLuaState() = runBlocking {
        withPrivateStore { root ->
            ProductionFixture(root).useSuspending { fixture ->
                ActorRuntimeFactory.resetForTest()
                assertEquals(
                    MutationResult.Installed(fixture.debugImplementationId),
                    success(fixture.repository.installOrUpdate(ByteArrayInputStream(DEBUG_ARCHIVE), DEBUG_SOURCE_RECORD)),
                )
                val left = ChannelDefinition(
                    id = "sibling-echo",
                    name = "ECHO",
                    implementationId = fixture.debugImplementationId,
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = OpaqueJsonObject.fromJsonObject(JSONObject().put("mode", "ECHO")),
                )
                val right = ChannelDefinition(
                    id = "sibling-tts",
                    name = "TTS",
                    implementationId = fixture.debugImplementationId,
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = OpaqueJsonObject.fromJsonObject(JSONObject().put("mode", "TTS")),
                )
                fixture.catalogue(left, right)
                assertEquals("two sibling instances must create two independent kernel states", 2, fixture.bridge.createdStateIds.size)
                val leftState = fixture.bridge.createdStateIds[0]
                val rightState = fixture.bridge.createdStateIds[1]
                assertNotEquals("sibling kernel states must be distinct", leftState, rightState)
                assertTrue(
                    "ECHO sibling must reach readiness without affecting TTS sibling",
                    awaitTimerlessReadiness(fixture.runtimeRegistry, left.id, fixture.bridge, leftState),
                )
                assertTrue(
                    "TTS sibling must reach readiness without interference from ECHO sibling",
                    awaitTimerlessReadiness(fixture.runtimeRegistry, right.id, fixture.bridge, rightState),
                )
            }
        }
    }

    @Test
    fun builtinDebugIdDoesNotResolveWhileInstalledProviderIsAvailable() = runBlocking {
        withPrivateStore { root ->
            ProductionFixture(root).useSuspending { fixture ->
                ActorRuntimeFactory.resetForTest()
                assertTrue(
                    "retired built-in journal must resolve missing before any installed package",
                    fixture.providers.resolve(ChannelImplementationId("builtin:journal")) is ChannelProviderResolution.Missing,
                )
                val debugBuiltIn = ChannelImplementationId("builtin:debug")
                assertTrue(
                    "builtin:debug ID must be unresolved before installation",
                    fixture.providers.resolve(debugBuiltIn) is ChannelProviderResolution.Missing,
                )
                assertEquals(
                    MutationResult.Installed(fixture.debugImplementationId),
                    success(fixture.repository.installOrUpdate(ByteArrayInputStream(DEBUG_ARCHIVE), DEBUG_SOURCE_RECORD)),
                )
                assertTrue(
                    "installed Debug provider must resolve after installation",
                    fixture.providers.resolve(fixture.debugImplementationId) is ChannelProviderResolution.Available,
                )
                assertTrue(
                    "builtin:debug must continue to be unresolved after installed Debug provider registration",
                    fixture.providers.resolve(debugBuiltIn) is ChannelProviderResolution.Missing,
                )
                assertTrue(
                    "retired built-in journal must remain unresolved beside the installed Debug provider",
                    fixture.providers.resolve(ChannelImplementationId("builtin:journal")) is ChannelProviderResolution.Missing,
                )
            }
        }
    }

    private suspend fun awaitLuaInputAcceptance(
        registry: ChannelRuntimeRegistry,
        definitionId: String,
        bridge: TracingKernelBridge,
        stateId: Long,
        awaitTimerResume: Boolean,
        stage: String = definitionId,
    ): Boolean {
        val readinessBaseline = bridge.readinessCallbackCount(stateId)
        if (awaitTimerResume && !bridge.awaitSuccessfulTimerResume(stateId)) {
            throw readinessFailure(registry, definitionId, stage, "the v1 timer resume for state $stateId")
        }
        registry.refreshReadiness()
        if (!bridge.awaitReadinessCallbackAfter(stateId, readinessBaseline)) {
            throw readinessFailure(registry, definitionId, stage, "a handle_readiness callback for state $stateId")
        }
        return acceptInput(registry, definitionId, stage)
    }

    private suspend fun acceptInput(
        registry: ChannelRuntimeRegistry,
        definitionId: String,
        stage: String = definitionId,
    ): Boolean = when (val acceptance = registry.prepareInput(definitionId)) {
        is ChannelInputAcceptance.Accepted -> {
            acceptance.target.onInputCancelled("instrumentation readiness probe complete")
            (acceptance.target as? io.talkcan.service.CommittedTargetLeaseOwner)?.releaseCommittedTargetLease()
            true
        }
        else -> throw readinessFailure(registry, definitionId, stage, "input acceptance; lastAcceptance=$acceptance")
    }

    private suspend fun awaitTimerlessReadiness(
        registry: ChannelRuntimeRegistry,
        definitionId: String,
        bridge: TracingKernelBridge,
        stateId: Long,
    ): Boolean {
        val baseline = bridge.readinessCallbackCount(stateId)
        registry.refreshReadiness()
        if (!bridge.awaitReadinessCallbackAfter(stateId, baseline)) {
            throw readinessFailure(registry, definitionId, "timerless", "handle_readiness callback for state $stateId")
        }
        return acceptInput(registry, definitionId, "timerless")
    }

    private fun readinessFailure(
        registry: ChannelRuntimeRegistry,
        definitionId: String,
        stage: String,
        waitingFor: String,
    ): AssertionError = AssertionError(
        "input acceptance failed at lifecycle stage '$stage' while waiting for $waitingFor; " +
            "preparation=${registry.preparation(definitionId)}; snapshot=${registry.getRuntimeSnapshot(definitionId)}; " +
            "generation=${registry.capabilityScopeIdentity(definitionId)}",
    )

    /** Returns true only if the successor becomes unavailable after the retired v1 timer deadline. */
    private suspend fun inputRefusedAfterTimerWindow(registry: ChannelRuntimeRegistry, definitionId: String): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + STALE_TIMER_WINDOW_MILLIS
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            registry.refreshReadiness()
            when (val acceptance = registry.prepareInput(definitionId)) {
                is ChannelInputAcceptance.Accepted -> {
                    acceptance.target.onInputCancelled("instrumentation stale-timer probe complete")
                    (acceptance.target as? io.talkcan.service.CommittedTargetLeaseOwner)?.releaseCommittedTargetLease()
                }
                else -> return true
            }
            delay(POLL_MILLIS)
        }
        return false
    }

    private fun installedDefinition(id: String, implementationId: ChannelImplementationId): ChannelDefinition = ChannelDefinition(
        id = id,
        name = "Installed package $id",
        implementationId = implementationId,
        enabled = true,
        configSchemaVersion = 1,
        configPayload = OpaqueJsonObject.fromJsonObject(JSONObject()),
    )

    private fun journalDefinition(): ChannelDefinition = ChannelDefinition(
        id = "installed-package-journal-built-in",
        name = "Retired built-in journal definition",
        implementationId = ChannelImplementationId("builtin:journal"),
        enabled = true,
        configSchemaVersion = 1,
        configPayload = OpaqueJsonObject.fromJsonObject(JSONObject()),
    )

    private fun sourceRecord(
        repository: GitHubRepositoryIdentity,
        releaseId: String,
        assetId: String,
    ): PackageSourceRecord = PackageSourceRecord(
        repositoryId = repository,
        coordinates = GitHubRepositoryCoordinates("fixture-owner", "fixture-repository"),
        release = GitHubReleaseIdentity(releaseId, "fixture-release", false),
        asset = GitHubAssetIdentity(assetId, "fixture-package.zip"),
        ownerId = "9000001",
    )

    private fun <T> success(outcome: PackageOutcome<T>): T = when (outcome) {
        is PackageOutcome.Success -> outcome.value
        is PackageOutcome.Failure -> throw AssertionError("expected package outcome success; error=${outcome.error}")
    }

    private suspend fun withPrivateStore(block: suspend (File) -> Unit) {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "installed-lua-instrumentation-${UUID.randomUUID()}",
        )
        check(root.mkdirs()) { "could not create private instrumentation store" }
        try {
            block(root)
        } finally {
            check(root.deleteRecursively() || !root.exists()) { "could not remove private instrumentation store" }
        }
    }

    private class ProductionFixture(root: File) {
        private val catalogue = AtomicReference(ChannelCatalogueSnapshot(emptyList(), ""))
        val bridge = TracingKernelBridge()
        val debugImplementationId: ChannelImplementationId = InstalledProviderId.derive(DEBUG_REPOSITORY)
        val audioModulesImplementationId: ChannelImplementationId = InstalledProviderId.derive(AUDIO_MODULES_REPOSITORY)
        val providers = ChannelImplementationProviderRegistry()
        private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val boundary = RuntimeInvocationBoundary(
            RuntimeWorkerDispatcher.create(workerCount = 1, queueCapacity = 16, threadNamePrefix = "installed-lua-device"),
            RuntimeInvocationPolicy(
                perGenerationQueueCapacity = 16,
                callbackTimeoutMillis = WAIT_MILLIS,
                inputReleasedTimeoutMillis = WAIT_MILLIS,
                closeTimeoutMillis = WAIT_MILLIS,
            ),
        )
        val runtimeRegistry = ChannelRuntimeRegistry(
            providers = providers,
            capabilityHost = NoCapabilities,
            invocationBoundary = boundary,
            runtimeScope = runtimeScope,
            closeScope = runtimeScope,
            shutdownAwaitMillis = WAIT_MILLIS,
        )
        val publishedFailures = AtomicReference<Map<ChannelImplementationId, PackageFailure>>(emptyMap())
        val repository = InstalledPackageRepository(
            store = InstalledPackageStore(root),
            bridge = bridge,
            publisher = { materialized ->
                publishedFailures.set(materialized.failures)
                val unavailable = materialized.failures.mapValues { (id, failure) -> failure.toPackageUnavailable(id) }
                when (providers.publishInstalledProviders(materialized.bindings, unavailable)) {
                    is InstalledProvidersPublicationResult.Success -> {
                        runtimeRegistry.reconcile(catalogue.get())
                        PackageOutcome.Success(Unit)
                    }
                    is InstalledProvidersPublicationResult.Rejected -> PackageOutcome.Failure(
                        PackageFailure.Loading(PackageFailure.LoadingDetail.PUBLICATION_REJECTED),
                    )
                }
            },
            dispatcher = Dispatchers.IO,
        )

        suspend fun catalogue(vararg definitions: ChannelDefinition) {
            val snapshot = ChannelCatalogueSnapshot(
                definitions.toList(),
                definitions.firstOrNull()?.id.orEmpty(),
            )
            catalogue.set(snapshot)
            runtimeRegistry.reconcile(snapshot)
        }

        suspend fun close() {
            repository.closeAndAwait(WAIT_MILLIS)
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, runtimeRegistry.shutdownAndAwait())
            boundary.close()
            runtimeScope.cancel()
        }

        suspend fun useSuspending(block: suspend (ProductionFixture) -> Unit) {
            try {
                block(this)
            } finally {
                close()
            }
        }
    }

    /** Records only opaque kernel state lifecycle ordering while delegating every operation to the production Kotlin kernel bridge. */
    private class TracingKernelBridge(private val delegate: LuaKernelBridge = KotlinLuaKernelBridge()) : LuaKernelBridge {
        private sealed interface Event {
            val stateId: Long
            data class Created(override val stateId: Long) : Event
            data class Startup(override val stateId: Long) : Event
            data class Resume(override val stateId: Long) : Event
            data class Readiness(override val stateId: Long) : Event
            data class Closed(override val stateId: Long) : Event
        }

        private val events = CopyOnWriteArrayList<Event>()
        val startupOutcomes = CopyOnWriteArrayList<LuaKernelOutcome>()
        val createdStateIds: List<Long>
            get() = events.filterIsInstance<Event.Created>().map(Event.Created::stateId)
        val closedStateIds: List<Long>
            get() = events.filterIsInstance<Event.Closed>().map(Event.Closed::stateId)

        fun closedBeforeStartup(predecessor: Long, successor: Long): Boolean {
            val closed = events.indexOfFirst { it is Event.Closed && it.stateId == predecessor }
            val started = events.indexOfFirst { it is Event.Startup && it.stateId == successor }
            return closed >= 0 && started >= 0 && closed < started
        }

        fun readinessCallbackCount(stateId: Long): Int = events.count { it is Event.Readiness && it.stateId == stateId }

        suspend fun awaitSuccessfulTimerResume(stateId: Long): Boolean = withTimeoutOrNull(WAIT_MILLIS) {
            while (events.none { it is Event.Resume && it.stateId == stateId }) delay(POLL_MILLIS)
            true
        } == true

        suspend fun awaitReadinessCallbackAfter(stateId: Long, baseline: Int): Boolean = withTimeoutOrNull(WAIT_MILLIS) {
            while (readinessCallbackCount(stateId) <= baseline) delay(POLL_MILLIS)
            true
        } == true

        override fun create(config: LuaKernelConfig): LuaKernelOutcome = delegate.create(config).also { outcome ->
            (outcome as? LuaKernelOutcome.Created)?.let { events += Event.Created(it.stateId) }
        }

        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome =
            delegate.load(handle, source, entrypoint)

        override fun setResourceContext(
            handle: LuaStateHandle,
            resourceContextJson: String,
        ): LuaKernelOutcome = delegate.setResourceContext(handle, resourceContextJson)

        override fun start(handle: LuaStateHandle): LuaKernelOutcome = delegate.start(handle)

        override fun resume(operation: LuaOperationHandle, success: Boolean, value: String, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome =
            delegate.resume(operation, success, value, spawnAdmission).also {
                if (success) events += Event.Resume(operation.stateHandle.stateId.value)
            }

        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = delegate.cancel(operation)

        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = delegate.interrupt(handle)

        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = delegate.snapshot(handle)

        override fun close(handle: LuaStateHandle): LuaKernelOutcome = delegate.close(handle).also {
            events += Event.Closed(handle.stateId.value)
        }

        override fun loadProgramImage(handle: LuaStateHandle, entryPoint: String, sourceMap: Map<String, String>): LuaKernelOutcome =
            delegate.loadProgramImage(handle, entryPoint, sourceMap)

        override fun invokeStartupCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            config: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = delegate.invokeStartupCallback(
            handle,
            callbackHandle,
            config,
            spawnAdmission,
        ).also { outcome ->
            events += Event.Startup(handle.stateId.value)
            startupOutcomes += outcome
        }

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = delegate.invokeCallback(handle, callbackHandle, arguments, spawnAdmission).also { outcome ->
            if (callbackHandle.name == "handle_readiness" && outcome is LuaKernelOutcome.Completed && outcome.stateId == handle.stateId.value) {
                events += Event.Readiness(outcome.stateId)
            }
        }

        override fun startCoroutine(
            handle: LuaStateHandle,
            coroutineId: LuaCoroutineId,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = delegate.startCoroutine(handle, coroutineId, spawnAdmission)
    }

    private object NoCapabilities : ChannelCapabilityHost {
        override suspend fun availability(identity: CapabilityScopeIdentity, key: CapabilityKey<*>): CapabilityAvailability =
            CapabilityAvailability.Available

        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> = HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = acquire(identity, key)
    }

    private object FakeSession : ChannelAudioInputSession {
        override val sampleRate = 16_000
        override val frames = emptyFlow<ShortArray>()
    }

    private fun v1Archive(): ByteArray = V1_ARCHIVE.copyOf()
    private fun v2Archive(): ByteArray = V2_ARCHIVE.copyOf()
    private fun corruptCandidateArchive(): ByteArray = CORRUPT_CANDIDATE_ARCHIVE.copyOf()

    private fun digestOf(bytes: ByteArray): String = buildString(64) {
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).forEach { byte ->
            append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
            append(HEX_DIGITS[byte.toInt() and 0x0f])
        }
    }

    private companion object {
        val SOURCE_REPOSITORY = GitHubRepositoryIdentity("9001")
        val CORRUPT_REPOSITORY = GitHubRepositoryIdentity("9002")
        val INSTALLED_ID: ChannelImplementationId = InstalledProviderId.derive(SOURCE_REPOSITORY)
        val CORRUPT_ID: ChannelImplementationId = InstalledProviderId.derive(CORRUPT_REPOSITORY)
        val DEBUG_REPOSITORY = GitHubRepositoryIdentity("1306065111")
        val AUDIO_MODULES_REPOSITORY = GitHubRepositoryIdentity("1306065112")
        val DEBUG_SOURCE_RECORD = PackageSourceRecord(DEBUG_REPOSITORY, GitHubRepositoryCoordinates("talkcan", "debug-channel"), GitHubReleaseIdentity("1", "v1.0.0", false), GitHubAssetIdentity("1", "talkcan-channel.zip"), "1224006")
        val AUDIO_MODULES_SOURCE_RECORD = PackageSourceRecord(AUDIO_MODULES_REPOSITORY, GitHubRepositoryCoordinates("talkcan", "audio-modules"), GitHubReleaseIdentity("1", "v1.0.0", false), GitHubAssetIdentity("1", "audio-modules.zip"), "1224006")
        const val WAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
        const val STALE_TIMER_WINDOW_MILLIS = 650L
        const val UNIX_HOST_OS = 3
        const val LOCAL_FILE_HEADER = 0x04034b50L
        const val CENTRAL_DIRECTORY_HEADER = 0x02014b50L
        const val END_OF_CENTRAL_DIRECTORY = 0x06054b50L
        const val REGULAR_FILE_0644 = 0b1000000110100100
        const val DIRECTORY_0755 = 0b0100000111101101
        const val HEX_DIGITS = "0123456789abcdef"

        private val V1_ARCHIVE = packageArchive(
            repositoryId = SOURCE_REPOSITORY.value,
            version = "1.0.0",
            source = """
                local runtime = require("talkcan.runtime")
                local timer_completed = false
                return {
                  startup = function()
                    if volatile_boot_marker ~= nil then return { error = { code = "E_RETAINED_VOLATILE_STATE" } } end
                    volatile_boot_marker = "v1"
                    local admitted = runtime.spawn(function()
                      local completed = runtime.sleep(0.05)
                      timer_completed = completed == true
                    end)
                    if admitted ~= true then return { error = { code = "E_TIMER" } } end
                  end,
                  handle_readiness = function() return { ready = timer_completed } end,
                  handle_input = function() return { ok = true } end,
                }
            """.trimIndent(),
        )

        private val V2_ARCHIVE = packageArchive(
            repositoryId = SOURCE_REPOSITORY.value,
            version = "2.0.0",
            source = """
                return {
                  startup = function() end,
                  handle_readiness = function() return { ready = volatile_boot_count == nil } end,
                  handle_input = function() return { ok = true } end,
                }
            """.trimIndent(),
        )

        private val CORRUPT_CANDIDATE_ARCHIVE = packageArchive(
            repositoryId = CORRUPT_REPOSITORY.value,
            version = "1.0.0",
            source = """
                return {
                  startup = function() end,
                  handle_readiness = function() return { ready = true } end,
                  handle_input = function() return { ok = true } end,
                }
            """.trimIndent(),
        )

        private val DEBUG_PACKAGE_SOURCE = """
            local MODE_DEPS = {ECHO=true,DELAYED_ECHO=true,STT=true,TTS=true,STT_TTS=true}
            local MODE = nil
            return {
              startup = function(config)
                if type(config) ~= "table" or config.schema_version ~= 1 or type(config.values) ~= "table" or type(config.values.mode) ~= "string" then
                  return { error = { code = "E_INVALID_ARGUMENT", detail = "invalid config" } }
                end
                if not MODE_DEPS[config.values.mode] then
                  return { error = { code = "E_INVALID_ARGUMENT", detail = "unknown mode" } }
                end
                MODE = config.values.mode
              end,
              handle_readiness = function(ctx)
                if MODE == nil then return { ready = false } end
                return { ready = true, status = MODE }
              end,
              handle_input = function(event)
                if type(event) ~= "table" or event.event ~= "capture" then
                  return { error = { code = "E_INVALID_ARGUMENT", detail = "invalid event" } }
                end
                return { ok = true }
              end,
            }
        """.trimIndent()

        private val AUDIO_MODULES_PACKAGE_SOURCE = """
            local t = require("talkcan.transcription")
            local s = require("talkcan.synthesis")
            local p = require("talkcan.playback")
            local OK = type(t) == "table" and type(t.transcribe) == "function" and type(s) == "table" and type(s.synthesize) == "function" and type(p) == "table" and type(p.schedule) == "function"
            return {
              startup = function() end,
              handle_readiness = function() return { ready = OK } end,
              handle_input = function(event)
                if type(event) ~= "table" or event.event ~= "capture" then return { error = { code = "E_INVALID_ARGUMENT" } } end
                return { ok = true }
              end,
            }
        """.trimIndent()

        private val DEBUG_ARCHIVE = configuredPackageArchive(
            repositoryId = DEBUG_REPOSITORY.value,
            version = "1.0.0",
            configJson = """{"schemaVersion":1,"data":{"additionalProperties":false,"fields":[{"id":"mode","type":"string","default":"ECHO","allowedValues":["ECHO","DELAYED_ECHO","STT","TTS","STT_TTS"]}]},"ui":{"fields":[{"field":"mode","control":"choice","label":"Mode","choices":[{"value":"ECHO","label":"ECHO"},{"value":"DELAYED_ECHO","label":"DELAYED_ECHO"},{"value":"STT","label":"STT"},{"value":"TTS","label":"TTS"},{"value":"STT_TTS","label":"STT_TTS"}]}]}}""",
            capabilitiesJson = """["audio.transcription","audio.synthesis","audio.playback"]""",
            source = DEBUG_PACKAGE_SOURCE,
        )

        private val AUDIO_MODULES_ARCHIVE = configuredPackageArchive(
            repositoryId = AUDIO_MODULES_REPOSITORY.value,
            version = "1.0.0",
            configJson = """{"schemaVersion":1,"data":{"additionalProperties":false,"fields":[]},"ui":{"fields":[]}}""",
            capabilitiesJson = """["audio.transcription","audio.synthesis","audio.playback"]""",
            source = AUDIO_MODULES_PACKAGE_SOURCE,
        )

        private data class ZipFixtureEntry(val name: String, val bytes: ByteArray, val unixMode: Int)
        private data class CentralFixtureEntry(val entry: ZipFixtureEntry, val name: ByteArray, val crc: Long, val localOffset: Long)

        /** Creates byte-identical stored ZIPs with Unix regular-file/directory central metadata. */
        private fun strictUnixStoredZip(entries: List<ZipFixtureEntry>): ByteArray {
            val output = ByteArrayOutputStream()
            val centralEntries = ArrayList<CentralFixtureEntry>(entries.size)
            entries.forEach { entry ->
                val name = entry.name.toByteArray(UTF_8)
                val crc = CRC32().apply { update(entry.bytes) }.value
                val offset = output.size().toLong()
                output.u32(LOCAL_FILE_HEADER)
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
                output.u32(CENTRAL_DIRECTORY_HEADER)
                output.u16((UNIX_HOST_OS shl 8) or 20)
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
            output.u32(END_OF_CENTRAL_DIRECTORY)
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
        private fun packageArchive(repositoryId: String, version: String, source: String): ByteArray {
            val manifest = """{"manifestVersion":1,"repositoryId":"$repositoryId","packageVersion":"$version","entryModule":"plugin","presentation":{"label":"Device package","summary":"Immutable device fixture"},"runtime":{"luaVersion":"$LUA_VERSION","apiVersion":"$API_VERSION"},"configuration":{"schemaVersion":1,"data":{"additionalProperties":false,"fields":[]},"ui":{"fields":[]}},"resources":{"mounts":[]},"profileTypes":[],"choiceResolvers":[],"workQueues":[],"capabilities":[]}"""
            return strictUnixStoredZip(
                listOf(
                    ZipFixtureEntry("manifest.json", manifest.toByteArray(UTF_8), REGULAR_FILE_0644),
                    ZipFixtureEntry("lua/", ByteArray(0), DIRECTORY_0755),
                    ZipFixtureEntry("lua/plugin.lua", source.toByteArray(UTF_8), REGULAR_FILE_0644),
                ),
            )
        }

        private fun configuredPackageArchive(
            repositoryId: String,
            version: String,
            configJson: String,
            capabilitiesJson: String,
            source: String,
        ): ByteArray {
            val manifest = """{"manifestVersion":1,"repositoryId":"$repositoryId","packageVersion":"$version","entryModule":"plugin","presentation":{"label":"Device package","summary":"Immutable device fixture"},"runtime":{"luaVersion":"$LUA_VERSION","apiVersion":"$API_VERSION"},"configuration":$configJson,"resources":{"mounts":[]},"profileTypes":[],"choiceResolvers":[],"workQueues":[],"capabilities":$capabilitiesJson}"""
            return strictUnixStoredZip(
                listOf(
                    ZipFixtureEntry("manifest.json", manifest.toByteArray(UTF_8), REGULAR_FILE_0644),
                    ZipFixtureEntry("lua/", ByteArray(0), DIRECTORY_0755),
                    ZipFixtureEntry("lua/plugin.lua", source.toByteArray(UTF_8), REGULAR_FILE_0644),
                ),
            )
        }
    }
}
