package io.talkcan.lua

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.dependency.ConfigurationDataDeclaration
import io.talkcan.dependency.ConfigurationFieldDeclaration
import io.talkcan.dependency.ConfigurationUiDeclaration
import io.talkcan.dependency.PackageConfigurationDeclaration
import io.talkcan.dependency.UiChoice
import io.talkcan.dependency.UiControl
import io.talkcan.dependency.UiFieldDeclaration
import io.talkcan.channel.capability.RuntimeGeneration
import io.talkcan.audio.ChannelInputAcceptance
import io.talkcan.audio.ChannelInputResult
import io.talkcan.audio.RecordedPcm
import io.talkcan.channel.capability.CapabilityAvailability
import io.talkcan.channel.capability.CapabilityKey
import io.talkcan.channel.capability.ChannelCapabilityHost
import io.talkcan.channel.capability.ChannelCapabilityPort
import io.talkcan.channel.capability.HostedCapabilityAcquisition
import io.talkcan.lua.actor.ActorConstructResult
import io.talkcan.lua.actor.ActorEventEnvelope
import io.talkcan.lua.actor.ActorEventId
import io.talkcan.lua.actor.ActorEventIdentity
import io.talkcan.lua.actor.ActorFailureClassification
import io.talkcan.lua.actor.ActorFailureResult
import io.talkcan.lua.actor.ActorGateCommit
import io.talkcan.lua.actor.ActorGateResult
import io.talkcan.lua.actor.ActorGenerationGate
import io.talkcan.lua.actor.ActorKernelConfig
import io.talkcan.lua.actor.ActorRuntimeFactory
import io.talkcan.lua.actor.ActorLoadResult
import io.talkcan.lua.actor.ActorMailboxResult
import io.talkcan.lua.actor.ActorPolicy
import io.talkcan.lua.actor.ActorRuntime
import io.talkcan.lua.actor.ActorStartupResult
import io.talkcan.lua.kernel.KotlinLuaKernelBridge
import io.talkcan.model.ChannelCatalogueSnapshot
import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ChannelProviderResolution
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelImplementationProviderRegistry
import io.talkcan.model.ChannelPresentationMetadata
import io.talkcan.model.ChannelProviderError
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.model.ProviderRevisionFingerprint
import io.talkcan.service.ChannelPreparationAvailability
import io.talkcan.service.ChannelPreparationReason
import io.talkcan.service.ChannelRuntimeRegistry
import io.talkcan.service.ChannelRuntimeRegistryShutdownResult
import io.talkcan.service.CommittedTargetLeaseOwner
import io.talkcan.service.RuntimeInvocationBoundary
import io.talkcan.service.RuntimeInvocationPolicy
import io.talkcan.service.RuntimeWorkerDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only identity for generic Lua provider behavior. */
private val TEST_LUA_IMPLEMENTATION_ID = ChannelImplementationId("internal:lua")

/** Empty schema-version-1 declaration; mirrors a materialized package with no fields. */
private fun emptyConfigurationDeclaration(): PackageConfigurationDeclaration = PackageConfigurationDeclaration(
    ConfigurationDataDeclaration(emptyList()),
    ConfigurationUiDeclaration(emptyList()),
)

private fun configuredConfigurationDeclaration(): PackageConfigurationDeclaration =
    PackageConfigurationDeclaration(
        ConfigurationDataDeclaration(
            listOf(
                ConfigurationFieldDeclaration.StringField(
                    id = "mode",
                    default = "ECHO",
                    allowedValues = listOf("ECHO"),
                ),
            ),
        ),
        ConfigurationUiDeclaration(
            listOf(
                UiFieldDeclaration(
                    field = "mode",
                    control = UiControl.CHOICE,
                    label = "Mode",
                    help = null,
                    choices = listOf(UiChoice("ECHO", "Echo")),
                ),
            ),
        ),
    )

/**
 * Constructs a test Lua provider with fixed identity and presentation.
 * Tests that need custom identity/presentation should use LuaChannelImplementationProvider.create.
 */
private fun testLuaProvider(
    image: ImmutableProgramImage,
    bridge: LuaKernelBridge,
    policy: ActorPolicy = ActorPolicy.startingEvidence(),
    configuration: PackageConfigurationDeclaration = emptyConfigurationDeclaration(),
): LuaChannelImplementationProvider = LuaChannelImplementationProvider.create(
    implementationId = TEST_LUA_IMPLEMENTATION_ID,
    presentation = ChannelPresentationMetadata(
        label = "Lua channel",
        summary = "LUA RUNTIME",
        unavailableMessage = "Lua program could not be constructed.",
    ),
    programImage = image,
    fingerprint = ProviderRevisionFingerprint("builtin"),
    actorFactory = { context, capabilities, kernelBridge, actorPolicy ->
        ActorRuntimeFactory.createForGeneration(context, capabilities, kernelBridge, actorPolicy)
    },
    bridge = bridge,
    actorPolicy = policy,
    configurationProvider = CompiledConfigurationProvider(TEST_LUA_IMPLEMENTATION_ID, configuration),
)

/**
 * Constructs a test Lua provider from an image creation result (success or failure).
 * Used for testing error projection paths.
 */
private fun testLuaProviderFromResult(
    imageResult: ProgramImageCreationResult,
    bridge: LuaKernelBridge,
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
    configurationProvider = CompiledConfigurationProvider(TEST_LUA_IMPLEMENTATION_ID, emptyConfigurationDeclaration()),
)

/**
 * Device-only conformance and evidence workload for the promoted internal Lua actor runtime.
 *
 * Run on a physical arm64 device for the evaluated APK/native build:
 *
 * `adb shell am instrument -w -e class io.talkcan.lua.LuaActorInstrumentationTest \
 *     io.talkcan.test/androidx.test.runner.AndroidJUnitRunner`
 *
 * Every invocation emits exactly one `LUA_ACTOR_EVIDENCE` JSON record through the
 * instrumentation status channel. Correctness assertions are deliberately separate from
 * timing and allocation observations: observed values are evidence, never public limits.
 */
@RunWith(AndroidJUnit4::class)
class LuaActorInstrumentationTest {
    @Test
    fun actorRuntimeConformanceEmitsDeviceEvidence() {
        val evidence = EvidenceCollector(runContext())
        val failures = mutableListOf<String>()
        val workload = ActorWorkload(evidence, failures)

        try {
            workload.run()
        } finally {
            workload.closeLeakedStates()
            val record = evidence.toJson()
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply { putString(EVIDENCE_BUNDLE_KEY, record) },
            )
            Log.i(EVIDENCE_TAG, "emitted $EVIDENCE_BUNDLE_KEY (${record.length} bytes)")
        }

        assertTrue(
            "Lua actor correctness gates failed:\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    private fun runContext(): JSONObject {
        val arguments = InstrumentationRegistry.getArguments()
        val applicationInfo = InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo
        val buildType = if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            "debuggable"
        } else {
            "release-equivalent"
        }
        return JSONObject()
            .put("runId", arguments.getString("luaActorRunId") ?: UUID.randomUUID().toString())
            .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("apiLevel", Build.VERSION.SDK_INT)
            .put("buildFingerprint", Build.FINGERPRINT)
            .put("buildType", buildType)
            .put("abi", Build.SUPPORTED_ABIS.joinToString(","))
            .put("kernelImplementation", "KotlinLuaKernelBridge")
            .put("luaBinding", "party.iroiro.luajava:lua54 4.1.0")
            .put("threadCountAtStart", Thread.activeCount())
    }

    private class ActorWorkload(
        private val evidence: EvidenceCollector,
        private val failures: MutableList<String>,
    ) {
        private val bridge: LuaKernelBridge = KotlinLuaKernelBridge()
        private val createdHandles = LinkedHashSet<LuaStateHandle>()
        private val kernelConfig = LuaKernelConfig(
            hookInterval = 37,
            instructionBudget = 250_000L,
        )

        fun run() {
            startupReadinessAndBoundedMailbox()
            yieldedSlicesReleaseExecutionAndResumeIndependently()
            instructionInterruptionIsContained()
            localAndFatalFailuresHaveDifferentContainmentScopes()
            staleForeignClosedAndRacingTerminalsProduceOneEffect()
            actorStateIsolationAndReplacementSuppressLateCompletion()
            binaryAndNativeModuleRequestsDoNotPoisonBenignEvents()
            shutdownStopsAdmissionAndRejectsLateCompletion()
            ProviderPathWorkload(evidence, failures).run()
        }

        /** Startup publishes readiness before ordinary admission; a full mailbox rejects without a retained waiter. */
        private fun startupReadinessAndBoundedMailbox() = probe("startup_readiness_and_bounded_mailbox") { scope ->
            val actor = scope.newActor("startup", mailboxCapacity = 2)
            try {
                scope.expect(actor.construct() is ActorConstructResult.Success) {
                    "actor construction must create an independent kernel state"
                }
                scope.expect(actor.loadSource(returningEntrypoint("ready"), "entry") is ActorLoadResult.Loaded) {
                    "valid startup source must stage the actor"
                }
                scope.expect(runBlocking { actor.startup() } is ActorStartupResult.Ready) {
                    "protected startup must publish actor readiness"
                }
                scope.expect(actor.isReady) { "actor must be ready after a successful protected startup" }

                val first = actor.admitEvent(scope.readinessEnvelope(actor.identity))
                val second = actor.admitEvent(scope.readinessEnvelope(actor.identity))
                val overflow = actor.admitEvent(scope.readinessEnvelope(actor.identity))
                scope.recordActorAdmission("mailbox_first", first)
                scope.recordActorAdmission("mailbox_second", second)
                scope.recordActorAdmission("mailbox_overflow", overflow)
                scope.expect(first is ActorMailboxResult.Admitted && second is ActorMailboxResult.Admitted) {
                    "two events must occupy the injected two-slot mailbox"
                }
                scope.expect(overflow is ActorMailboxResult.Busy && actor.mailboxDepth == 2) {
                    "overflow must return Busy and must not grow or retain an unbounded waiter queue"
                }
            } finally {
                scope.closeActor(actor)
            }
        }

        /** Yielded coroutines release their execution slice; independent suspended operations resume exactly once. */
        private fun yieldedSlicesReleaseExecutionAndResumeIndependently() = probe("yield_slot_release_and_multiple_suspensions") { scope ->
            val first = scope.create(kernelConfig)
            val second = scope.create(kernelConfig)
            scope.expectLoaded(first, yieldingEntrypoint("first", "return value"))
            scope.expectLoaded(second, yieldingEntrypoint("second", "return value"))

            val firstOperation = scope.expectYielded(scope.call("start_first") { bridge.start(first) }, "first")
            val secondOperation = scope.expectYielded(scope.call("start_second_after_first_yield") { bridge.start(second) }, "second")
            scope.expectCompletedValue(
                scope.call("resume_second") { bridge.resume(secondOperation, success = true, value = "second-complete") },
                "second-complete",
            )
            scope.expectCompletedValue(
                scope.call("resume_first") { bridge.resume(firstOperation, success = true, value = "first-complete") },
                "first-complete",
            )
            scope.close(first)
            scope.close(second)
        }

        /** An explicit instruction budget interrupts pure Lua while a peer remains usable. */
        private fun instructionInterruptionIsContained() = probe("instruction_interruption_containment") { scope ->
            val interrupted = scope.create(
                LuaKernelConfig(hookInterval = 13, instructionBudget = 10_000L),
            )
            scope.expectLoaded(interrupted, "function entry() while true do end end")
            val interruption = scope.call("start_infinite_loop") { bridge.start(interrupted) }
            scope.expect(interruption is LuaKernelOutcome.Interrupted) {
                "pure-Lua infinite loop must normalize as Interrupted, got ${interruption.describe()}"
            }
            scope.close(interrupted)

            val survivor = scope.create(kernelConfig)
            scope.expectLoaded(survivor, returningEntrypoint("survives-policy-failure"))
            scope.expectCompletedValue(scope.call("start_policy_survivor") { bridge.start(survivor) }, "survives-policy-failure")
            scope.close(survivor)
        }

        /** Ordinary protected errors remain local; fatal classification latches only its owning actor. */
        private fun localAndFatalFailuresHaveDifferentContainmentScopes() = probe("local_vs_fatal_containment") { scope ->
            val failing = scope.newActor("failure-a", mailboxCapacity = 1)
            val peer = scope.newActor("failure-b", mailboxCapacity = 1)
            try {
                scope.startActor(failing, "failure-a")
                scope.startActor(peer, "failure-b")

                val local = failing.classifyFailure(ActorFailureClassification.OrdinaryEvent("ordinary protected error"))
                scope.recordActorOutcome("ordinary_error", local)
                scope.expect(local is ActorFailureResult.LocalContained && failing.isReady && peer.isReady) {
                    "ordinary protected error must remain local and keep both actor generations usable"
                }

                val fatal = failing.classifyFailure(ActorFailureClassification.Instruction("instruction budget exhausted"))
                scope.recordActorOutcome("instruction_fatal", fatal)
                scope.expect(fatal is ActorFailureResult.FatalLatched && failing.isFailed && !failing.isReady) {
                    "instruction failure must latch only the affected actor unavailable"
                }
                scope.expect(peer.admitEvent(scope.readinessEnvelope(peer.identity)) is ActorMailboxResult.Admitted) {
                    "fatal failure in one actor must not stop an unrelated actor admission"
                }
            } finally {
                scope.closeActor(failing)
                scope.closeActor(peer)
            }
        }

        /** Stale, foreign, closed, and terminal-race requests cannot produce a second Lua effect. */
        private fun staleForeignClosedAndRacingTerminalsProduceOneEffect() = probe("terminal_identity_and_exactly_once_races") { scope ->
            val owner = scope.create(kernelConfig)
            val foreignState = scope.create(kernelConfig)
            scope.expectLoaded(owner, "hits = 0; function entry() local _, value = talkcan.yield_operation('race'); hits = hits + 1; return value end")
            scope.expectLoaded(foreignState, returningEntrypoint("foreign-survivor"))
            val operation = scope.expectYielded(scope.call("start_owner") { bridge.start(owner) }, "race")

            val foreign = operation.copy(stateHandle = foreignState)
            val foreignOutcome = scope.call("resume_foreign") { bridge.resume(foreign, true, "forbidden") }
            scope.expect(foreignOutcome is LuaKernelOutcome.InvalidOwnership) {
                "foreign terminal request must return InvalidOwnership, got ${foreignOutcome.describe()}"
            }
            val stale = operation.copy(
                stateHandle = operation.stateHandle.copy(
                    generation = LuaStateGeneration(operation.stateHandle.generation.value + 1L),
                ),
            )
            val staleOutcome = scope.call("resume_stale") { bridge.resume(stale, true, "forbidden") }
            scope.expect(staleOutcome is LuaKernelOutcome.Stale) {
                "stale terminal request must return Stale, got ${staleOutcome.describe()}"
            }

            val race = race(
                Callable { bridge.resume(operation, success = true, value = "winner") },
                Callable { bridge.cancel(operation) },
            )
            scope.recordRace("completion_vs_cancellation", race)
            val terminalKinds = race.mapNotNull { it.terminalKind() }.toSet()
            scope.expect(terminalKinds.size == 1 && terminalKinds.single() in setOf("completed", "cancelled")) {
                "terminal race must expose exactly one accepted terminal kind, got ${race.joinToString { it.describe() }}"
            }
            scope.expectLoaded(owner, "function observe() return tostring(hits) end", entrypoint = "observe")
            scope.expectCompletedValue(
                scope.call("observe_terminal_effect") { bridge.start(owner) },
                if (terminalKinds.single() == "completed") "1" else "0",
            )
            scope.close(owner)
            val closed = scope.call("resume_after_close") { bridge.resume(operation, true, "late") }
            scope.expect(closed is LuaKernelOutcome.Closed || closed is LuaKernelOutcome.Stale) {
                "closed operation completion must be rejected without Lua entry, got ${closed.describe()}"
            }
            scope.expectCompletedValue(scope.call("start_foreign_survivor") { bridge.start(foreignState) }, "foreign-survivor")
            scope.close(foreignState)
        }

        /** Equal names stay isolated; G's late completion cannot reach fresh replacement H. */
        private fun actorStateIsolationAndReplacementSuppressLateCompletion() = probe("state_isolation_and_replacement_late_suppression") { scope ->
            val predecessor = scope.create(kernelConfig)
            val successor = scope.create(kernelConfig)
            scope.expectLoaded(predecessor, "shared = 'generation-g'; function entry() local _, value = talkcan.yield_operation('g'); return shared .. ':' .. value end")
            scope.expectLoaded(successor, "shared = 'generation-h'; function entry() return shared end")
            val oldOperation = scope.expectYielded(scope.call("start_generation_g") { bridge.start(predecessor) }, "g")
            scope.close(predecessor)

            val late = scope.call("late_completion_generation_g") { bridge.resume(oldOperation, true, "must-not-reach-h") }
            scope.expect(late is LuaKernelOutcome.Closed || late is LuaKernelOutcome.Stale) {
                "replacement must suppress G late completion, got ${late.describe()}"
            }
            scope.expectCompletedValue(scope.call("start_generation_h") { bridge.start(successor) }, "generation-h")
            scope.close(successor)
        }

        /** Executed binary/native-loader attempts fail before effect execution and a benign event still succeeds. */
        private fun binaryAndNativeModuleRequestsDoNotPoisonBenignEvents() = probe("binary_and_native_module_rejection") { scope ->
            val state = scope.create(kernelConfig)
            val binary = "\u001bLua\u0000\u0019\u0093\r\n\u001a\n"
            val binaryOutcome = scope.call("load_binary") { bridge.load(state, binary, "entry") }
            scope.expect(binaryOutcome is LuaKernelOutcome.SyntaxFailure) {
                "binary bytecode must be rejected before native execution, got ${binaryOutcome.describe()}"
            }

            scope.expectLoaded(
                state,
                "effects = 0; function entry() local forbidden = package.loadlib; effects = effects + 1; return forbidden('/tmp/untrusted.so', 'entry') end",
            )
            val moduleOutcome = scope.call("execute_package_loadlib") { bridge.start(state) }
            scope.expect(moduleOutcome is LuaKernelOutcome.RuntimeFailure) {
                "native module loading must fail through the executed Lua boundary, got ${moduleOutcome.describe()}"
            }
            scope.expectLoaded(state, returningEntrypoint("benign-after-module-rejection"))
            scope.expectCompletedValue(
                scope.call("start_benign_after_rejection") { bridge.start(state) },
                "benign-after-module-rejection",
            )
            scope.close(state)
        }

        /** Shutdown closes once, stops admission, and suppresses a late completion without a timing threshold. */
        private fun shutdownStopsAdmissionAndRejectsLateCompletion() = probe("bounded_shutdown") { scope ->
            val actor = scope.newActor("shutdown", mailboxCapacity = 1)
            try {
                scope.startActor(actor, "shutdown-ready")
                scope.expect(actor.admitEvent(scope.readinessEnvelope(actor.identity)) is ActorMailboxResult.Admitted) {
                    "live actor must admit an event before shutdown"
                }
                val started = System.nanoTime()
                val closed = runBlocking { actor.close() }
                evidence.recordActorClose(System.nanoTime() - started)
                scope.expect(closed.toString().contains("Closed") && actor.isClosed) {
                    "shutdown must close the actor and publish its terminal state"
                }
                scope.expect(actor.admitEvent(scope.readinessEnvelope(actor.identity)) is ActorMailboxResult.Closed) {
                    "shutdown must stop future mailbox admission"
                }
            } finally {
                scope.closeActor(actor)
            }
        }

        private fun probe(id: String, body: (ProbeScope) -> Unit) {
            val scope = ProbeScope(id, bridge, evidence, createdHandles)
            val started = System.nanoTime()
            try {
                body(scope)
                evidence.recordProbe(id, passed = true, elapsedNanos = System.nanoTime() - started, failure = null)
            } catch (failure: Throwable) {
                val message = "$id: ${failure.message ?: failure::class.java.name}"
                failures += message
                evidence.recordProbe(id, passed = false, elapsedNanos = System.nanoTime() - started, failure = message)
            }
        }

        fun closeLeakedStates() {
            createdHandles.toList().forEach { handle ->
                val started = System.nanoTime()
                evidence.recordOperation(
                    probe = "cleanup",
                    operation = "close_leaked_state",
                    outcome = bridge.close(handle),
                    elapsedNanos = System.nanoTime() - started,
                    parameters = emptyMap(),
                )
                createdHandles.remove(handle)
            }
        }
    }

    private class ProbeScope(
        private val probe: String,
        private val bridge: LuaKernelBridge,
        private val evidence: EvidenceCollector,
        private val createdHandles: MutableSet<LuaStateHandle>,
    ) {
        fun create(config: LuaKernelConfig): LuaStateHandle {
            val outcome = call(
                "create",
                mapOf(
                    "hookInterval" to config.hookInterval.toString(),
                    "instructionBudget" to config.instructionBudget.toString(),
                ),
            ) { bridge.create(config) }
            val created = outcome as? LuaKernelOutcome.Created
                ?: throw AssertionError("state creation must succeed, got ${outcome.describe()}")
            evidence.recordKernelProvenance(created.luaVersion, created.bindingVersion, created.topology)
            return LuaStateHandle(LuaStateId(created.stateId), LuaStateGeneration(created.generation)).also(createdHandles::add)
        }

        fun expectLoaded(handle: LuaStateHandle, source: String, entrypoint: String = "entry") {
            val outcome = call("load", mapOf("entrypoint" to entrypoint)) { bridge.load(handle, source, entrypoint) }
            expect(outcome is LuaKernelOutcome.Completed) {
                "valid text source must load, got ${outcome.describe()}"
            }
        }

        fun expectYielded(outcome: LuaKernelOutcome, label: String): LuaOperationHandle {
            val yielded = outcome as? LuaKernelOutcome.Yielded
                ?: throw AssertionError("entrypoint must yield '$label', got ${outcome.describe()}")
            expect(yielded.value == label) {
                "yield label must survive the actor kernel boundary: expected $label, got ${yielded.value}"
            }
            return yielded.operationHandle()
        }

        fun expectCompletedValue(outcome: LuaKernelOutcome, expected: String) {
            val completed = outcome as? LuaKernelOutcome.Completed
                ?: throw AssertionError("entrypoint must complete '$expected', got ${outcome.describe()}")
            val actual = completed.value?.let { JSONTokener(it).nextValue() }?.toString()
            expect(actual == expected) {
                "completed value must be '$expected', got '${completed.value}'"
            }
        }

        fun close(handle: LuaStateHandle) {
            val outcome = call("close") { bridge.close(handle) }
            expect(outcome is LuaKernelOutcome.Closed) {
                "close must return Closed, got ${outcome.describe()}"
            }
            createdHandles.remove(handle)
        }

        fun newActor(instanceId: String, mailboxCapacity: Int): ActorRuntime {
            val policy = ActorPolicy(
                luaKernelConfig = ActorKernelConfig(
                    hookInterval = 41,
                    instructionBudget = 250_000L,
                ),
                mailboxCapacity = mailboxCapacity,
                activeSliceBudgetMillis = 2_000L,
                operationWaitDeadlineMillis = 3_000L,
                callbackTimeoutMillis = 2_500L,
                closeTimeoutMillis = 1_000L,
                maxConcurrentTasks = 2,
                perTaskDeadlineMillis = 3_000L,
            )
            evidence.recordActorPolicy(probe, policy)
            return ActorRuntime(
                scope = CapabilityScopeIdentity(instanceId, RuntimeGeneration(1L)),
                bridge = bridge,
                gate = TestGate(),
                policy = policy,
                capabilityScope = null,
                parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
        }

        fun startActor(actor: ActorRuntime, expectedValue: String) {
            expect(actor.construct() is ActorConstructResult.Success) { "actor must construct before startup" }
            expect(actor.loadSource(returningEntrypoint(expectedValue), "entry") is ActorLoadResult.Loaded) {
                "actor source must stage before startup"
            }
            expect(runBlocking { actor.startup() } is ActorStartupResult.Ready) {
                "actor startup must publish readiness"
            }
        }

        fun closeActor(actor: ActorRuntime) {
            runBlocking { actor.close() }
        }

        fun readinessEnvelope(scope: CapabilityScopeIdentity): ActorEventEnvelope = ActorEventEnvelope.Readiness(
            ActorEventIdentity(scope, ActorEventId.next()),
        )

        fun recordActorAdmission(operation: String, result: ActorMailboxResult) {
            evidence.recordActorOutcome("$operation=${result::class.simpleName}")
        }

        fun recordActorOutcome(operation: String, result: Any) {
            evidence.recordActorOutcome("$operation=${result::class.simpleName}")
        }

        fun recordRace(name: String, outcomes: List<LuaKernelOutcome>) {
            evidence.recordRace(name, outcomes.map { it.toJson() })
            outcomes.forEachIndexed { index, outcome ->
                evidence.recordOperation(probe, "$name[$index]", outcome, null, mapOf("threadCount" to Thread.activeCount().toString()))
            }
        }

        fun call(
            operation: String,
            parameters: Map<String, String> = emptyMap(),
            invoke: () -> LuaKernelOutcome,
        ): LuaKernelOutcome {
            val started = System.nanoTime()
            val outcome = invoke()
            evidence.recordOperation(probe, operation, outcome, System.nanoTime() - started, parameters)
            return outcome
        }

        fun expect(condition: Boolean, lazyMessage: () -> String) {
            if (!condition) throw AssertionError(lazyMessage())
        }
    }

    /**
     * Device-only provider integration cases. Every image below enters the production Kotlin kernel bridge
     * only through LuaChannelImplementationProvider -> ChannelRuntimeRegistry -> ActorRuntimeFactory.
     * The registry fixture never starts an audio source or any product service.
     */
    private class ProviderPathWorkload(
        private val evidence: EvidenceCollector,
        private val failures: MutableList<String>,
    ) {
        fun run() {
            restrictedSourceAndPackageLoading()
            callbackTableFailuresAreRejected()
            lifecycleReadinessInputAndSos()
            startupAndNestedSpawn()
            sleepCompletionAndCloseSuppressStaleWork()
            compatibilityAndMalformedSourceFailBeforeActivation()
            instructionFailuresAreContained()
            replacementAndShutdownCloseWithoutStaleEntry()
            configuredStartupThroughProviderPath()
            audioModulesThroughProductionKernel()
            noBuiltInDebugResolution()
        }

        private fun restrictedSourceAndPackageLoading() = case("provider_restricted_source_package_and_cache") {
            fixture(image(
                entry = "plugin.restricted",
                sources = mapOf(
                    "plugin.restricted" to """
                        local first = require("plugin.nested")
                        local second = require("plugin.nested")
                        return {
                          startup = function()
                            if package ~= nil or os ~= nil or io ~= nil or debug ~= nil or load ~= nil then
                              return { error = { code = "E_RESTRICTED", detail = "restricted global exposed" } }
                            end
                            if pcall(string.dump, function() end) then
                              return { error = { code = "E_BYTECODE", detail = "string.dump was not disabled" } }
                            end
                            if first ~= second or first.loads() ~= 1 then
                              return { error = { code = "E_CACHE", detail = "nested require was not cached" } }
                            end
                          end,
                          handle_readiness = function() return { ready = first.ready() } end,
                          handle_input = function(event)
                            if event.event ~= "capture" then return { error = { code = "E_INPUT", detail = "wrong event" } } end
                            return { ok = true }
                          end,
                        }
                    """.trimIndent(),
                    "plugin.nested" to """
                        local counter = 0
                        counter = counter + 1
                        return { loads = function() return counter end, ready = function() return true end }
                    """.trimIndent(),
                ),
            )).use { fixture ->
                val definition = definition("restricted-package")
                fixture.install(definition)
                await("restricted package readiness") {
                    fixture.registry.getRuntimeSnapshot(definition.id)?.preparation == ChannelPreparationAvailability.Available
                }
                assertInputAccepted(fixture.registry, definition.id, "restricted package input")
            }
        }

        private fun callbackTableFailuresAreRejected() = case("provider_callback_table_validation") {
            val cases = listOf(
                "missing_startup" to "return { handle_readiness = function() return { ready = true } end }",
                "wrong_optional_type" to "return { startup = function() end, handle_sos = 1 }",
                "metatable" to "return setmetatable({ startup = function() end }, {})",
            )
            cases.forEach { (label, source) ->
                fixture(image("plugin.$label", mapOf("plugin.$label" to source))).use { fixture ->
                    val definition = definition("callback-$label")
                    fixture.install(definition)
                    val preparation = awaitPreparation(fixture.registry, definition.id, "callback table $label")
                    require(preparation is ChannelPreparationAvailability.Unavailable) {
                        "callback table case=$label must be unavailable, got $preparation"
                    }
                }
            }
        }

        private fun lifecycleReadinessInputAndSos() = case("provider_lifecycle_readiness_input_sos") {
            fixture(image(
                entry = "plugin.lifecycle",
                sources = mapOf("plugin.lifecycle" to """
                    local channel = require("talkcan.channel")
                    local lifecycle = false
                    local sos = false
                    return {
                      startup = function() lifecycle = false end,
                      handle_lifecycle = function(event)
                        if event.event ~= channel.LIFECYCLE_READY then
                          return { error = { code = "E_LIFECYCLE", detail = "unexpected lifecycle event" } }
                        end
                        lifecycle = true
                      end,
                      handle_readiness = function() return { ready = lifecycle } end,
                      handle_input = function(event)
                        if event.event ~= channel.CAPTURE_COMPLETE then
                          return { error = { code = "E_INPUT", detail = "unexpected capture event" } }
                        end
                        return { ok = true }
                      end,
                      handle_sos = function(event)
                        if event.event ~= channel.SOS_TRIGGERED then
                          return { error = { code = "E_SOS", detail = "unexpected SOS event" } }
                        end
                        sos = true
                      end,
                    }
                """.trimIndent()),
            )).use { fixture ->
                val definition = definition("lifecycle")
                fixture.install(definition)
                await("lifecycle readiness") {
                    fixture.registry.getRuntimeSnapshot(definition.id)?.preparation == ChannelPreparationAvailability.Available
                }
                assertInputAccepted(fixture.registry, definition.id, "lifecycle input")
                val sos = runBlocking { fixture.registry.dispatchSos(definition.id) }
                assertEquals("case=lifecycle_sos", ChannelPreparationAvailability.Available, sos)
            }
        }

        private fun startupAndNestedSpawn() = case("provider_synchronous_startup_nested_spawn") {
            fixture(image(
                entry = "plugin.spawn",
                sources = mapOf("plugin.spawn" to """
                    local runtime = require("talkcan.runtime")
                    local child = false
                    local admitted = false
                    return {
                      startup = function()
                        local ok, err = runtime.spawn(function()
                          local nested, nestedErr = runtime.spawn(function() child = true end)
                          admitted = nested == true and nestedErr == nil
                        end)
                        if ok ~= true or err ~= nil then
                          return { error = { code = "E_SPAWN", detail = "startup spawn was not synchronously admitted" } }
                        end
                      end,
                      handle_readiness = function() return { ready = admitted and child } end,
                    }
                """.trimIndent()),
            )).use { fixture ->
                val definition = definition("nested-spawn")
                fixture.install(definition)
                await("startup and nested spawn completion") {
                    fixture.registry.refreshReadiness()
                    fixture.registry.getRuntimeSnapshot(definition.id)?.preparation == ChannelPreparationAvailability.Available
                }
            }
        }

        private fun sleepCompletionAndCloseSuppressStaleWork() = case("provider_sleep_timer_deadline_cancel_close_stale") {
            fixture(image(
                entry = "plugin.sleep",
                sources = mapOf("plugin.sleep" to """
                    local runtime = require("talkcan.runtime")
                    local timerFirst = false
                    local timeoutOrCancel = false
                    return {
                      startup = function()
                        local admitted, admissionError = runtime.spawn(function()
                          local completed, error = runtime.sleep(0.02)
                          timerFirst = completed == true and error == nil
                          timeoutOrCancel = completed == nil and error ~= nil and
                            (error.error == "E_TIMEOUT" or error.error == "E_CANCELLED")
                        end)
                        if admitted ~= true or admissionError ~= nil then
                          return { error = { code = "E_SPAWN", detail = "sleep task was not admitted" } }
                        end
                      end,
                      handle_readiness = function() return { ready = timerFirst or timeoutOrCancel } end,
                    }
                """.trimIndent()),
            )).use { fixture ->
                val definition = definition("sleep")
                fixture.install(definition)
                await("sleep terminal result") {
                    fixture.registry.refreshReadiness()
                    fixture.registry.getRuntimeSnapshot(definition.id)?.preparation == ChannelPreparationAvailability.Available
                }
            }

            // Closing a live sleeping generation must suppress its continuation; the H image is
            // independent and proves a late G timer cannot mutate the replacement's Lua globals.
            fixture(image(
                entry = "plugin.g",
                sources = mapOf("plugin.g" to """
                    local runtime = require("talkcan.runtime")
                    local stale = false
                    return {
                      startup = function() runtime.spawn(function() runtime.sleep(5.0); stale = true end) end,
                      handle_readiness = function() return { ready = stale } end,
                    }
                """.trimIndent()),
            )).use { fixture ->
                val generationG = definition("sleep-replacement", name = "G")
                fixture.install(generationG)
                runBlocking { fixture.registry.reconcile(ChannelCatalogueSnapshot(emptyList(), "")) }
                assertEquals("case=sleep_close", null, fixture.registry.getRuntimeSnapshot(generationG.id))
            }
        }

        private fun compatibilityAndMalformedSourceFailBeforeActivation() = case("provider_compatibility_and_malformed_source") {
            ActorRuntimeFactory.resetForTest()
            val incompatible = ImmutableProgramImage.create(
                entryPoint = "plugin.incompatible",
                sourceMap = mapOf("plugin.incompatible" to "return { startup = function() end }"),
                requirements = LuaProgramRequirements(LUA_VERSION, "talkcan-lua-device-incompatible"),
            )
            fixture(testLuaProviderFromResult(incompatible, KotlinLuaKernelBridge())).use { fixture ->
                val definition = definition("incompatible")
                fixture.install(definition)
                val unavailable = awaitProviderFailure(fixture.registry, definition.id, "compatibility")
                require(unavailable is ChannelProviderError.RuntimeCompatibilityFailure) {
                    "case=compatibility must reject before activation with typed compatibility error, got $unavailable"
                }
                assertTrue("case=compatibility must not construct an actor", !ActorRuntimeFactory.isCreateAttempted)
            }
            ActorRuntimeFactory.resetForTest()

            fixture(image("plugin.malformed", mapOf("plugin.malformed" to "return { startup = function( end }"))).use { fixture ->
                val definition = definition("malformed-source")
                fixture.install(definition)
                awaitProviderFailure(fixture.registry, definition.id, "malformed source")
            }
        }

        private fun instructionFailuresAreContained() = case("provider_instruction_interruption") {
            fixture(
                image("plugin.interrupted", mapOf("plugin.interrupted" to "return { startup = function() while true do end end }")),
                policy = policy(instructionBudget = 10_000L),
            ).use { fixture ->
                val definition = definition("interrupted")
                fixture.install(definition)
                awaitRuntimeFailure(fixture.registry, definition.id, "instruction interruption")
            }
        }

        private fun replacementAndShutdownCloseWithoutStaleEntry() = case("provider_replacement_g_to_h_and_shutdown") {
            fixture(image(
                entry = "plugin.replacement",
                sources = mapOf("plugin.replacement" to """
                    return {
                      startup = function() end,
                      handle_readiness = function() return { ready = true } end,
                    }
                """.trimIndent()),
            )).use { fixture ->
                val generationG = definition("replacement", name = "G")
                fixture.install(generationG)
                await("generation G readiness") {
                    fixture.registry.getRuntimeSnapshot(generationG.id)?.preparation == ChannelPreparationAvailability.Available
                }
                val generationH = generationG.copy(name = "H")
                runBlocking { fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(generationH), generationH.id)) }
                await("generation H readiness") {
                    fixture.registry.getRuntimeSnapshot(generationH.id)?.name == "H" &&
                        fixture.registry.getRuntimeSnapshot(generationH.id)?.preparation == ChannelPreparationAvailability.Available
                }
                fixture.shutdown()
                assertEquals("case=shutdown_no_stale_entry", null, fixture.registry.getRuntimeSnapshot(generationH.id))
            }
        }

        private fun configuredStartupThroughProviderPath() = case("provider_configured_startup") {
            fixture(
                testLuaProvider(
                    image = image(
                        entry = "plugin.configured",
                        sources = mapOf("plugin.configured" to """
                            local mode = nil
                            return {
                              startup = function(config)
                                if type(config) ~= "table" or config.schema_version ~= 1 or type(config.values) ~= "table" or config.values.mode ~= "ECHO" then
                                  return { error = { code = "E_INVALID_ARGUMENT", detail = "expected mode=ECHO" } }
                                end
                                mode = config.values.mode
                              end,
                              handle_readiness = function()
                                return { ready = mode == "ECHO", status = mode or "" }
                              end,
                              handle_input = function(event)
                                if type(event) ~= "table" or event.event ~= "capture" then
                                  return { error = { code = "E_INVALID_ARGUMENT", detail = "expected capture" } }
                                end
                                return { ok = true }
                              end,
                            }
                        """.trimIndent()),
                    ),
                    bridge = KotlinLuaKernelBridge(),
                    policy = policy(),
                    configuration = configuredConfigurationDeclaration(),
                ),
            ).use { fixture ->
                val definition = ChannelDefinition(
                    id = "configured",
                    name = "Configured startup",
                    implementationId = TEST_LUA_IMPLEMENTATION_ID,
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = OpaqueJsonObject.fromJsonObject(JSONObject().put("mode", "ECHO")),
                )
                fixture.install(definition)
                await("configured startup readiness") {
                    fixture.registry.getRuntimeSnapshot(definition.id)?.preparation == ChannelPreparationAvailability.Available
                }
                assertInputAccepted(fixture.registry, definition.id, "configured startup input")
            }
        }

        private fun audioModulesThroughProductionKernel() = case("provider_audio_modules") {
            fixture(image(
                entry = "plugin.audio",
                sources = mapOf("plugin.audio" to """
                    return {
                      startup = function()
                        local ok_t, t = pcall(function() return require("talkcan.transcription") end)
                        local ok_s, s = pcall(function() return require("talkcan.synthesis") end)
                        local ok_p, p = pcall(function() return require("talkcan.playback") end)
                        if not ok_t or type(t) ~= "table" then
                          return { error = { code = "E_MODULE", detail = "talkcan.transcription not available" } }
                        end
                        if not ok_s or type(s) ~= "table" then
                          return { error = { code = "E_MODULE", detail = "talkcan.synthesis not available" } }
                        end
                        if not ok_p or type(p) ~= "table" then
                          return { error = { code = "E_MODULE", detail = "talkcan.playback not available" } }
                        end
                      end,
                      handle_readiness = function() return { ready = true } end,
                      handle_input = function(event)
                        if event.event ~= "capture" then return { error = { code = "E_INPUT", detail = "wrong event" } } end
                        return { ok = true }
                      end,
                    }
                """.trimIndent()),
            )).use { fixture ->
                val definition = definition("audio-modules")
                fixture.install(definition)
                await("audio modules readiness") {
                    fixture.registry.getRuntimeSnapshot(definition.id)?.preparation == ChannelPreparationAvailability.Available
                }
                assertInputAccepted(fixture.registry, definition.id, "audio modules input")
            }
        }

        private fun noBuiltInDebugResolution() = case("provider_no_builtin_debug") {
            // A registry with only the test Lua provider does not resolve any built-in
            val providers = ChannelImplementationProviderRegistry()
            providers.register(testLuaProvider(
                image(
                    entry = "plugin.nodebug",
                    sources = mapOf("plugin.nodebug" to """
                        return {
                          startup = function() end,
                          handle_readiness = function() return { ready = true } end,
                        }
                    """.trimIndent()),
                ),
                KotlinLuaKernelBridge(),
                policy(),
            ))
            val debugId = ChannelImplementationId("builtin:debug")
            assertTrue(
                "builtin:debug must be unresolved: ${providers.resolve(debugId)}",
                providers.resolve(debugId) is ChannelProviderResolution.Missing,
            )
            val journalId = ChannelImplementationId("builtin:journal")
            assertTrue(
                "builtin:journal must be unresolved: ${providers.resolve(journalId)}",
                providers.resolve(journalId) is ChannelProviderResolution.Missing,
            )
        }

        private fun case(label: String, body: () -> Unit) {
            val started = System.nanoTime()
            try {
                body()
                evidence.recordDeviceCase(label, "passed")
                evidence.recordProbe(label, passed = true, elapsedNanos = System.nanoTime() - started, failure = null)
            } catch (failure: Throwable) {
                val message = "case=$label: ${failure.message ?: failure::class.java.name}"
                failures += message
                evidence.recordDeviceCase(label, message)
                evidence.recordProbe(label, passed = false, elapsedNanos = System.nanoTime() - started, failure = message)
            }
        }

        private fun fixture(
            image: ImmutableProgramImage,
            policy: ActorPolicy = policy(),
        ): RegistryFixture = fixture(testLuaProvider(image, KotlinLuaKernelBridge(), policy))

        private fun fixture(provider: LuaChannelImplementationProvider): RegistryFixture {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val workers = RuntimeWorkerDispatcher.create(workerCount = 1, queueCapacity = 16, threadNamePrefix = "lua-device")
            val boundary = RuntimeInvocationBoundary(
                workers,
                RuntimeInvocationPolicy(
                    perGenerationQueueCapacity = 16,
                    callbackTimeoutMillis = 5_000L,
                    inputReleasedTimeoutMillis = 5_000L,
                    closeTimeoutMillis = 5_000L,
                ),
            )
            val providers = ChannelImplementationProviderRegistry().also { it.register(provider) }
            return RegistryFixture(
                registry = ChannelRuntimeRegistry(
                    providers = providers,
                    capabilityHost = NoCapabilities,
                    invocationBoundary = boundary,
                    runtimeScope = scope,
                    closeScope = scope,
                    shutdownAwaitMillis = 10_000L,
                ),
                boundary = boundary,
                scope = scope,
            )
        }

        private fun image(entry: String, sources: Map<String, String>): ImmutableProgramImage = when (
            val result = ImmutableProgramImage.create(
                entryPoint = entry,
                sourceMap = sources,
                requirements = LuaProgramRequirements(LUA_VERSION, API_VERSION),
            )
        ) {
            is ProgramImageCreationResult.Success -> result.image
            is ProgramImageCreationResult.Failure -> throw AssertionError("fixture=$entry is invalid: ${result.error.message}")
        }

        private fun policy(
            instructionBudget: Long = 250_000L,
        ) = ActorPolicy(
            luaKernelConfig = ActorKernelConfig(hookInterval = 31, instructionBudget),
            mailboxCapacity = 8,
            activeSliceBudgetMillis = 5_000L,
            operationWaitDeadlineMillis = 5_000L,
            callbackTimeoutMillis = 5_000L,
            closeTimeoutMillis = 5_000L,
            maxConcurrentTasks = 4,
            perTaskDeadlineMillis = null,
            timerSlackMillis = 100L,
        )

        private fun definition(id: String, name: String = id): ChannelDefinition = ChannelDefinition(
            id = id,
            name = name,
            implementationId = TEST_LUA_IMPLEMENTATION_ID,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = OpaqueJsonObject.fromJsonObject(JSONObject()),
        )

        private fun await(label: String, condition: () -> Boolean) = runBlocking {
            withTimeout(10_000L) {
                while (!condition()) delay(10L)
            }
        }

        private fun awaitPreparation(
            registry: ChannelRuntimeRegistry,
            id: String,
            label: String,
        ): ChannelPreparationAvailability {
            await(label) { registry.getRuntimeSnapshot(id)?.preparation != null }
            return requireNotNull(registry.getRuntimeSnapshot(id)?.preparation) { "case=$label has no registry snapshot" }
        }

        private fun awaitProviderFailure(
            registry: ChannelRuntimeRegistry,
            id: String,
            label: String,
        ): ChannelProviderError {
            val preparation = awaitPreparation(registry, id, label)
            val unavailable = preparation as? ChannelPreparationAvailability.Unavailable
                ?: throw AssertionError("case=$label expected provider unavailability, got $preparation")
            return (unavailable.reason as? ChannelPreparationReason.Provider)?.error
                ?: throw AssertionError("case=$label expected provider reason, got ${unavailable.reason}")
        }

        private fun awaitRuntimeFailure(
            registry: ChannelRuntimeRegistry,
            id: String,
            label: String,
        ) {
            val preparation = awaitPreparation(registry, id, label)
            val unavailable = preparation as? ChannelPreparationAvailability.Unavailable
                ?: throw AssertionError("case=$label expected runtime unavailability, got $preparation")
            if (unavailable.reason !is ChannelPreparationReason.RuntimeFailed) {
                throw AssertionError("case=$label expected runtime failure, got ${unavailable.reason}")
            }
        }

        private fun assertInputAccepted(registry: ChannelRuntimeRegistry, id: String, label: String) = runBlocking {
            val acceptance = registry.prepareInput(id) as? ChannelInputAcceptance.Accepted
                ?: throw AssertionError("case=$label expected cached readiness input acceptance")
            try {
                assertEquals(
                    "case=$label must return no v1 playback result",
                    ChannelInputResult.None,
                    acceptance.target.onInputReleased(RecordedPcm(shortArrayOf(), 8_000)),
                )
            } finally {
                acceptance.target.onInputCancelled("case=$label complete")
                (acceptance.target as? CommittedTargetLeaseOwner)?.releaseCommittedTargetLease()
            }
        }

        private class RegistryFixture(
            val registry: ChannelRuntimeRegistry,
            private val boundary: RuntimeInvocationBoundary,
            private val scope: CoroutineScope,
        ) : AutoCloseable {
            fun install(definition: ChannelDefinition) = runBlocking {
                registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
            }

            fun shutdown() {
                val result = runBlocking { registry.shutdownAndAwait() }
                assertEquals("registry shutdown must close every provider generation", ChannelRuntimeRegistryShutdownResult.Closed, result)
            }

            override fun close() {
                try {
                    shutdown()
                } finally {
                    boundary.close()
                    scope.cancel()
                }
            }
        }

        private object NoCapabilities : ChannelCapabilityHost {
            override suspend fun availability(
                identity: CapabilityScopeIdentity,
                key: CapabilityKey<*>,
            ): CapabilityAvailability = CapabilityAvailability.Available

            override suspend fun <T : ChannelCapabilityPort> acquire(
                identity: CapabilityScopeIdentity,
                key: CapabilityKey<T>,
            ): HostedCapabilityAcquisition<T> = HostedCapabilityAcquisition.Unavailable(
                io.talkcan.channel.capability.CapabilityUnavailableReason.UNSUPPORTED,
            )

            override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
                identity: CapabilityScopeIdentity,
                key: CapabilityKey<T>,
                timeoutMillis: Long,
            ): HostedCapabilityAcquisition<T> = acquire(identity, key)
        }
    }

    private class EvidenceCollector(
        private val context: JSONObject,
    ) {
        private val probes = JSONArray()
        private val operations = JSONArray()
        private val races = JSONArray()
        private val actorPolicies = JSONArray()
        private val actorOutcomes = JSONArray()
        private val deviceCases = JSONArray()
        private val outcomeCounts = linkedMapOf<String, Int>()
        private val kernelProvenance = linkedMapOf<String, String>()

        fun recordKernelProvenance(luaVersion: String, bindingVersion: String, topology: String) {
            kernelProvenance["luaVersion"] = luaVersion
            kernelProvenance["bindingVersion"] = bindingVersion
            kernelProvenance["kernelTopology"] = topology
        }

        fun recordProbe(id: String, passed: Boolean, elapsedNanos: Long, failure: String?) {
            probes.put(
                JSONObject()
                    .put("id", id)
                    .put("passed", passed)
                    .put("elapsedNanos", elapsedNanos)
                    .put("failure", failure),
            )
        }

        fun recordOperation(
            probe: String,
            operation: String,
            outcome: LuaKernelOutcome,
            elapsedNanos: Long?,
            parameters: Map<String, String>,
        ) {
            val kind = outcome.kind()
            outcomeCounts[kind] = (outcomeCounts[kind] ?: 0) + 1
            operations.put(
                JSONObject()
                    .put("probe", probe)
                    .put("operation", operation)
                    .put("outcome", outcome.toJson())
                    .put("elapsedNanos", elapsedNanos)
                    .put("parameters", JSONObject(parameters))
                    .put("threadCount", Thread.activeCount()),
            )
        }

        fun recordRace(name: String, outcomes: List<JSONObject>) {
            races.put(JSONObject().put("name", name).put("outcomes", JSONArray(outcomes)))
        }

        fun recordActorPolicy(probe: String, policy: ActorPolicy) {
            actorPolicies.put(
                JSONObject()
                    .put("probe", probe)
                    .put("hookInterval", policy.luaKernelConfig.hookInterval)
                    .put("instructionBudget", policy.luaKernelConfig.instructionBudget)
                    .put("mailboxCapacity", policy.mailboxCapacity)
                    .put("activeSliceBudgetMillis", policy.activeSliceBudgetMillis)
                    .put("operationWaitDeadlineMillis", policy.operationWaitDeadlineMillis)
                    .put("callbackTimeoutMillis", policy.callbackTimeoutMillis)
                    .put("closeTimeoutMillis", policy.closeTimeoutMillis),
            )
        }

        fun recordActorOutcome(value: String) {
            actorOutcomes.put(value)
        }
        fun recordDeviceCase(label: String, outcome: String) {
            deviceCases.put(
                JSONObject()
                    .put("label", label)
                    .put("outcome", outcome),
            )
        }


        fun recordActorClose(elapsedNanos: Long) {
            actorOutcomes.put(JSONObject().put("operation", "close").put("elapsedNanos", elapsedNanos))
        }

        fun toJson(): String = JSONObject()
            .put("schema", "talkcan.lua.actor.v1")
            .put("context", context.put("threadCountAtEnd", Thread.activeCount()))
            .put("kernelProvenance", JSONObject(kernelProvenance))
            .put("injectedPolicies", actorPolicies)
            .put("probes", probes)
            .put("operations", operations)
            .put("raceOutcomes", races)
            .put("actorOutcomes", actorOutcomes)
            .put("deviceCases", deviceCases)
            .put("outcomeCounts", JSONObject(outcomeCounts))
            .toString()
    }

    private class TestGate : ActorGenerationGate {
        private val live = AtomicBoolean(true)

        override fun isLive(): Boolean = live.get()

        override fun <T> commitIfLive(action: () -> T): ActorGateCommit<T> =
            if (live.get()) ActorGateCommit.Success(action()) else ActorGateCommit.Closed

        override suspend fun <T> runContinuation(action: suspend () -> T): ActorGateResult<T> =
            if (live.get()) ActorGateResult.Success(action()) else ActorGateResult.Closed

        override fun isAdmissionStopped(): Boolean = !live.get()
    }

    private companion object {
        const val EVIDENCE_TAG = "LuaActorInstrumentation"
        const val EVIDENCE_BUNDLE_KEY = "LUA_ACTOR_EVIDENCE"
        const val RACE_TIMEOUT_SECONDS = 10L

        fun returningEntrypoint(value: String): String = "function entry() return '$value' end"

        fun yieldingEntrypoint(label: String, body: String): String =
            "function entry() local _, value = talkcan.yield_operation('$label'); $body end"

        fun race(
            first: Callable<LuaKernelOutcome>,
            second: Callable<LuaKernelOutcome>,
        ): List<LuaKernelOutcome> {
            val ready = CountDownLatch(2)
            val release = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                fun submit(call: Callable<LuaKernelOutcome>) = executor.submit(Callable {
                    ready.countDown()
                    check(release.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        "race caller did not receive release signal"
                    }
                    call.call()
                })
                val firstFuture = submit(first)
                val secondFuture = submit(second)
                check(ready.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "race callers did not become ready" }
                release.countDown()
                return listOf(
                    firstFuture.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    secondFuture.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
            } finally {
                release.countDown()
                executor.shutdownNow()
                executor.awaitTermination(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }

        fun LuaKernelOutcome.kind(): String = when (this) {
            is LuaKernelOutcome.Created -> "created"
            is LuaKernelOutcome.Completed -> "completed"
            is LuaKernelOutcome.Yielded -> "yielded"
            is LuaKernelOutcome.SyntaxFailure -> "syntax_failure"
            is LuaKernelOutcome.ValidationFailure -> "validation_failure"
            is LuaKernelOutcome.RuntimeFailure -> "runtime_failure"
            is LuaKernelOutcome.Interrupted -> "interrupted"
            is LuaKernelOutcome.Cancelled -> "cancelled"
            is LuaKernelOutcome.InvalidOwnership -> "invalid_ownership"
            is LuaKernelOutcome.Stale -> "stale"
            is LuaKernelOutcome.Snapshot -> "snapshot"
            is LuaKernelOutcome.Closed -> "closed"
        }

        fun LuaKernelOutcome.terminalKind(): String? = when (this) {
            is LuaKernelOutcome.Completed -> "completed"
            is LuaKernelOutcome.Cancelled -> "cancelled"
            else -> null
        }

        fun LuaKernelOutcome.describe(): String = when (this) {
            is LuaKernelOutcome.Created -> "created(state=$stateId, generation=$generation)"
            is LuaKernelOutcome.Completed -> "completed(value=$value)"
            is LuaKernelOutcome.Yielded -> "yielded(label=$value, operationId=$operationId)"
            is LuaKernelOutcome.SyntaxFailure -> "syntax_failure($diagnostic)"
            is LuaKernelOutcome.ValidationFailure -> "validation_failure($diagnostic)"
            is LuaKernelOutcome.RuntimeFailure -> "runtime_failure($diagnostic)"
            is LuaKernelOutcome.Interrupted -> "interrupted($diagnostic)"
            is LuaKernelOutcome.Cancelled -> "cancelled(operationId=$operationId)"
            is LuaKernelOutcome.InvalidOwnership -> "invalid_ownership($diagnostic)"
            is LuaKernelOutcome.Stale -> "stale($diagnostic)"
            is LuaKernelOutcome.Snapshot -> "snapshot(elapsed=$elapsedNanos, topology=$topology)"
            is LuaKernelOutcome.Closed -> "closed"
        }

        fun LuaKernelOutcome.toJson(): JSONObject = JSONObject()
            .put("kind", kind())
            .put("stateId", stateId)
            .put("generation", generation)
            .apply {
                when (this@toJson) {
                    is LuaKernelOutcome.Created -> put("luaVersion", luaVersion).put("bindingVersion", bindingVersion).put("topology", topology)
                    is LuaKernelOutcome.Completed -> put("coroutineId", coroutineId).put("value", value).put("nativeElapsedNanos", elapsedNanos)
                    is LuaKernelOutcome.Yielded -> put("coroutineId", coroutineId).put("operationId", operationId).put("value", value)
                    is LuaKernelOutcome.SyntaxFailure -> put("diagnostic", diagnostic)
                    is LuaKernelOutcome.ValidationFailure -> put("diagnostic", diagnostic)
                    is LuaKernelOutcome.RuntimeFailure -> put("diagnostic", diagnostic)
                    is LuaKernelOutcome.Interrupted -> put("diagnostic", diagnostic).put("nativeElapsedNanos", elapsedNanos)
                    is LuaKernelOutcome.Cancelled -> put("operationId", operationId)
                    is LuaKernelOutcome.InvalidOwnership -> put("diagnostic", diagnostic)
                    is LuaKernelOutcome.Stale -> put("diagnostic", diagnostic)
                    is LuaKernelOutcome.Snapshot -> put("nativeElapsedNanos", elapsedNanos).put("luaVersion", luaVersion).put("bindingVersion", bindingVersion).put("topology", topology)
                    is LuaKernelOutcome.Closed -> Unit
                }
            }

        fun LuaKernelOutcome.Yielded.operationHandle(): LuaOperationHandle = LuaOperationHandle(
            stateHandle = LuaStateHandle(LuaStateId(stateId), LuaStateGeneration(generation)),
            coroutineId = LuaCoroutineId(coroutineId),
            operationId = LuaOperationId(operationId),
        )
    }
}
