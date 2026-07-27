package io.talkcan.lua

import io.talkcan.audio.ChannelAudioInputSession
import io.talkcan.audio.ChannelInputAcceptance
import io.talkcan.audio.ChannelInputResult
import io.talkcan.audio.RecordedPcm
import io.talkcan.channel.capability.CapabilityAvailability
import io.talkcan.channel.capability.CapabilityKey
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.CapabilityUnavailableReason
import io.talkcan.channel.capability.ChannelCapability
import io.talkcan.channel.capability.ChannelCapabilityHost
import io.talkcan.channel.capability.ChannelCapabilityPort
import io.talkcan.channel.capability.HostedCapabilityAcquisition
import io.talkcan.channel.capability.RevocableChannelCapabilityScope
import io.talkcan.channel.capability.RuntimeGeneration
import io.talkcan.dependency.ConfigurationDataDeclaration
import io.talkcan.dependency.ConfigurationUiDeclaration
import io.talkcan.dependency.PackageConfigurationDeclaration
import io.talkcan.lua.actor.ActorRuntimeFactory
import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelPresentationMetadata
import io.talkcan.model.ChannelRuntimeConstructionRequest
import io.talkcan.model.ChannelRuntimeConstructionResult
import io.talkcan.model.GenerationExecutionContextImpl
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.model.ProviderRevisionFingerprint
import io.talkcan.model.ValidatedChannelConfiguration
import io.talkcan.service.ChannelActivationResult
import io.talkcan.service.ChannelExecutionStatus
import io.talkcan.service.GenericWorkPhase
import io.talkcan.service.RuntimeInvocationBoundary
import io.talkcan.service.RuntimeWorkerDispatcher
import io.talkcan.work.DurableWorkBounds
import io.talkcan.work.DurableWorkCoordinator
import io.talkcan.work.DurableWorkStore
import io.talkcan.work.WorkInstanceId
import io.talkcan.work.WorkQueueId
import io.talkcan.work.WorkQueuePartition
import io.talkcan.work.WorkRepositoryId
import io.talkcan.work.WorkValue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Task 13.1/13.2 behavioral coverage for the Lua adapter input/startup/managed-task
 * lifecycle:
 *
 * - a durable `WORK_SUBMIT` that commits during `handle_input` finalizes the input
 *   phase with exact success and releases Recording/route/callback/input-execution
 *   ownership BEFORE the generation-owned callback continuation runs;
 * - a submission that fails before commit rejects under input ownership with no
 *   detach and no partial queue item;
 * - cancellation while a durable operation is in flight rejects, and a late durable
 *   admission result can neither re-enter Lua nor reattach input ownership;
 * - startup may admit worker closures before Ready publication, but their
 *   `WORK_RECEIVE` dispatch is gated until publication succeeds;
 * - close is exactly-once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LuaAdapterRuntimeWorkLifecycleTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val workIds = AtomicInteger()
    private val repositoryId = WorkRepositoryId(13L)
    private val instanceId = WorkInstanceId("lua-adapter")
    private val turnsPartition = WorkQueuePartition(repositoryId, instanceId, WorkQueueId("turns"))

    private fun newStore(bounds: DurableWorkBounds = DurableWorkBounds()): DurableWorkStore =
        DurableWorkStore(folder.root, bounds) { "work-${workIds.getAndIncrement()}" }.also { it.load() }

    private fun textPayload(value: String): String =
        JSONObject().put("t", "text").put("v", value).toString()

    @Test
    fun `resource context carries declared protected host capabilities and exact work queues`() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)
        val bridge = RecordingWorkBridge()
        val harness = harness(
            bridge = bridge,
            callbacks = setOf("startup"),
            workStore = store,
            workCoordinator = coordinator,
            declaredWorkQueueIds = setOf("turns", "followups"),
            declaredCapabilities = setOf(
                ChannelCapability.SecretsRead,
                ChannelCapability.NetworkHttp,
                ChannelCapability.WorkQueue,
            ),
        )
        try {
            val context = JSONObject(bridge.lastResourceContextJson!!)
            assertTrue(context.getBoolean("secretsRead"))
            assertTrue(context.getBoolean("networkHttp"))
            assertTrue(context.getBoolean("workQueue"))
            assertEquals(listOf("followups", "turns"), context.getJSONArray("workQueues").let { queues ->
                List(queues.length()) { queues.getString(it) }
            })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `committed input submission finalizes exact success and releases ownership before the continuation`() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)
        val bridge = RecordingWorkBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_input", yielded(value = registerWorkClaim(
                HostOperationKind.WORK_SUBMIT,
                queue = "turns",
                payloadJson = textPayload("turn one"),
            )))
            // The detached continuation terminates the callback with exact success.
            resumeOutcomes.addLast(completed("""{"ok":true}"""))
        }
        lateinit var runtime: LuaAdapterRuntime
        var statusAtContinuationResume: ChannelExecutionStatus? = null
        var pendingAtContinuationResume: LuaInputPending? = LuaInputPending("sentinel", -1, "sentinel")
        bridge.onCoroutineResumed = {
            statusAtContinuationResume = runtime.snapshot.value.executionStatus
            pendingAtContinuationResume = runtime.pendingInput()
        }
        val harness = harness(
            bridge,
            setOf("startup", "handle_readiness", "handle_input"),
            workStore = store,
            workCoordinator = coordinator,
        )
        runtime = harness.runtime
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime)
            target.onInputStarted(session(16_000))
            val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(2), 16_000)) }
            runCurrent()

            // The input phase finalized deterministically: exact success published,
            // input execution slot retired, submission durable.
            assertTrue("input phase must finalize", release.isCompleted)
            assertEquals(ChannelExecutionStatus.SUCCESS, runtime.snapshot.value.executionStatus)
            assertNull(runtime.pendingInput())

            advanceUntilIdle()

            // Exactly one continuation resume carrying the durable submission result;
            // at the moment the continuation entered the actor (captured by the resume
            // hook), the input execution slot was already retired and exact success
            // already published — input ownership finalized before the continuation.
            assertEquals(1, bridge.resumeCalls.size)
            assertTrue(bridge.resumeCalls[0].first)
            assertTrue(JSONObject(bridge.resumeCalls[0].second).has("sequence"))
            assertEquals(ChannelExecutionStatus.SUCCESS, statusAtContinuationResume)
            assertNull(pendingAtContinuationResume)

            // The submission is durable: exactly one queued item, projected
            // content-free on the snapshot.
            assertEquals(1, store.projection(turnsPartition).queuedCount)
            assertEquals(GenericWorkPhase.QUEUED, runtime.snapshot.value.workProjection?.phase)
            assertEquals(1, runtime.snapshot.value.workProjection?.queuedCount)
            assertEquals(ChannelInputResult.None, release.await())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `accepted submission detaches input ownership before a later continuation yield and close releases once`() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)
        val followupsPartition = WorkQueuePartition(repositoryId, instanceId, WorkQueueId("followups"))
        val bridge = RecordingWorkBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_input", yielded(value = registerWorkClaim(
                HostOperationKind.WORK_SUBMIT,
                queue = "turns",
                payloadJson = textPayload("turn one"),
            )))
            // A second PTT press completes its callback directly while the first
            // submission's continuation is still parked.
            enqueue("handle_input", completed("""{"ok":true}"""))
            // The detached continuation yields a receive on an empty queue and parks
            // as a managed worker after input ownership was released.
            resumeOutcomes.addLast(yielded(
                coroutineId = 9,
                operationId = 21,
                value = registerWorkClaim(HostOperationKind.WORK_RECEIVE, queue = "followups"),
            ))
        }
        val harness = harness(
            bridge,
            setOf("startup", "handle_readiness", "handle_input"),
            workStore = store,
            workCoordinator = coordinator,
            declaredWorkQueueIds = setOf("turns", "followups"),
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            harness.runtime.refreshReadiness()

            val target = acceptedTarget(harness.runtime)
            target.onInputStarted(session(16_000))
            val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(2), 16_000)) }
            runCurrent()
            assertTrue(release.isCompleted)
            advanceUntilIdle()

            // The continuation resumed once with the commit result and then parked on
            // the empty followups queue as a managed task.
            assertEquals(1, bridge.resumeCalls.size)
            assertTrue(bridge.resumeCalls[0].first)
            assertNull(harness.runtime.pendingInput())
            assertEquals(ChannelExecutionStatus.SUCCESS, harness.runtime.snapshot.value.executionStatus)
            assertEquals(1, coordinator.waitingCount(followupsPartition))

            // Input execution ownership was released with the submission: a second PTT
            // press is accepted and processed while the continuation is still parked.
            val target2 = acceptedTarget(harness.runtime)
            target2.onInputStarted(session(16_000))
            val release2 = async { target2.onInputReleased(RecordedPcm(shortArrayOf(3), 16_000)) }
            runCurrent()
            assertEquals(ChannelInputResult.None, release2.await())
            assertEquals(ChannelExecutionStatus.SUCCESS, harness.runtime.snapshot.value.executionStatus)
            // The parked continuation did not consume the second callback's path.
            assertEquals(1, bridge.resumeCalls.size)

            // Close is exactly-once: a second close suppresses every late effect.
            harness.runtime.close()
            harness.runtime.close()
            assertEquals(1, bridge.closeCalls.get())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `submission rejected before commit resumes failure without detach or partial item`() = runTest {
        val store = newStore(DurableWorkBounds(maxNonterminalItemsPerQueue = 1))
        val coordinator = DurableWorkCoordinator(store)
        // Fill the queue's single nonterminal slot directly; the input submission
        // must then reject with E_BUSY before any durable mutation.
        val seed = store.submit(turnsPartition, WorkValue.Text("seed"), 1L)
        assertTrue(seed is io.talkcan.work.WorkStoreResult.Success)
        val bridge = RecordingWorkBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_input", yielded(value = registerWorkClaim(
                HostOperationKind.WORK_SUBMIT,
                queue = "turns",
                payloadJson = textPayload("turn one"),
            )))
            resumeOutcomes.addLast(
                completed("""{"error":{"code":"E_BUSY","detail":"queue is at capacity"}}"""),
            )
        }
        val harness = harness(
            bridge,
            setOf("startup", "handle_readiness", "handle_input"),
            workStore = store,
            workCoordinator = coordinator,
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime)
            target.onInputStarted(session(16_000))
            val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(2), 16_000)) }
            runCurrent()
            advanceUntilIdle()

            // Rejection resumes under input ownership: exactly one actor entry, a
            // failure, no detached continuation.
            assertEquals(listOf(false to "E_BUSY"), bridge.resumeCalls)
            assertEquals(ChannelExecutionStatus.FAILED, harness.runtime.snapshot.value.executionStatus)
            assertNull(harness.runtime.pendingInput())
            assertEquals(ChannelInputResult.None, release.await())

            // No partial item: the queue still holds exactly the pre-existing seed.
            assertEquals(1, store.projection(turnsPartition).queuedCount)
            assertEquals(1, harness.runtime.snapshot.value.workProjection?.queuedCount)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `cancellation while a durable operation is in flight rejects and a late admission cannot reattach`() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)
        val bridge = RecordingWorkBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            // handle_input suspends on an empty-queue receive: a durable operation in
            // flight whose commit has not happened yet.
            enqueue("handle_input", yielded(value = registerWorkClaim(
                HostOperationKind.WORK_RECEIVE,
                queue = "turns",
            )))
            resumeOutcomes.addLast(
                completed("""{"error":{"code":"E_CANCELLED","detail":"cancelled by user"}}"""),
            )
        }
        val harness = harness(
            bridge,
            setOf("startup", "handle_readiness", "handle_input"),
            workStore = store,
            workCoordinator = coordinator,
        )
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime)
            target.onInputStarted(session(16_000))
            val release = async { target.onInputReleased(RecordedPcm(shortArrayOf(2), 16_000)) }
            runCurrent()
            assertTrue(harness.runtime.pendingInput() != null)

            // Host cancellation rejects the in-flight operation with E_CANCELLED and
            // retires the input slot before any durable commit.
            target.onInputCancelled("user")
            assertEquals(listOf(false to "E_CANCELLED"), bridge.resumeCalls)
            assertNull(harness.runtime.pendingInput())
            assertEquals(ChannelExecutionStatus.IDLE, harness.runtime.snapshot.value.executionStatus)

            // A late durable admission (the queue mutates and the parked receive
            // claims) cannot re-enter Lua or reattach the retired input ownership.
            val late = store.submit(turnsPartition, WorkValue.Text("late"), 2L)
            assertTrue(late is io.talkcan.work.WorkStoreResult.Success)
            runCurrent()
            advanceUntilIdle()
            assertEquals(
                "late durable admission must not resume Lua a second time",
                1,
                bridge.resumeCalls.size,
            )
            assertEquals(ChannelInputResult.None, release.await())
            assertNull(harness.runtime.pendingInput())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `startup admitted worker closures defer receive until successful ready publication`() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)
        // One claimable item exists before the worker runs; a receive dispatched
        // before Ready publication would claim it during activation.
        val seed = store.submit(turnsPartition, WorkValue.Text("turn-1"), 1L)
        assertTrue(seed is io.talkcan.work.WorkStoreResult.Success)
        val bridge = RecordingWorkBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(55)))
            startCoroutineOutcomes.addLast(yielded(
                coroutineId = 55,
                operationId = 56,
                value = registerWorkClaim(HostOperationKind.WORK_RECEIVE, queue = "turns"),
            ))
            resumeOutcomes.addLast(completed("""{"ok":true}"""))
        }
        val harness = harness(
            bridge,
            setOf("startup"),
            workStore = store,
            workCoordinator = coordinator,
        )
        try {
            // Startup admits the worker closure, but it stays staged: neither the
            // worker nor its receive may run before successful Ready publication,
            // and the queued item stays unclaimed.
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            assertTrue("worker must not run before Ready publication", bridge.startedCoroutines.isEmpty())
            assertTrue("receive must not dispatch before Ready publication", bridge.resumeCalls.isEmpty())
            assertEquals(1, coordinator.projection(turnsPartition).queuedCount)

            // Authorization after Ready publication starts the worker; its receive
            // dispatches under the live generation and claims the FIFO item.
            harness.authorizeStagedTasks()
            advanceUntilIdle()
            assertEquals(listOf(55L), bridge.startedCoroutines)
            assertEquals(1, bridge.resumeCalls.size)
            assertTrue(bridge.resumeCalls[0].first)
            val doc = JSONObject(bridge.resumeCalls[0].second)
            assertTrue(doc.has("jobId"))
            assertTrue(doc.has("payloadJson"))
            assertEquals(0, coordinator.projection(turnsPartition).queuedCount)
            assertTrue(coordinator.projection(turnsPartition).activePresent)
        } finally {
            harness.close()
        }
    }

    // ── Harness ──────────────────────────────────────────────────────────

    private suspend fun acceptedTarget(runtime: LuaAdapterRuntime) =
        (runtime.prepareInput() as? ChannelInputAcceptance.Accepted)?.target
            ?: throw AssertionError("Expected input acceptance")

    private fun session(sampleRate: Int): ChannelAudioInputSession = object : ChannelAudioInputSession {
        override val frames = emptyFlow<ShortArray>()
        override val sampleRate: Int = sampleRate
    }

    private class WorkHarness(
        val runtime: LuaAdapterRuntime,
        private val capabilityScope: RevocableChannelCapabilityScope,
        private val context: GenerationExecutionContextImpl,
        private val boundary: RuntimeInvocationBoundary,
        private val parentJob: Job,
    ) {
        private var closed = false

        fun authorizeStagedTasks() {
            context.authorizeStagedTasksAfterReady().forEach { it.start() }
        }

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

    private suspend fun harness(
        bridge: RecordingWorkBridge,
        callbacks: Set<String>,
        workStore: DurableWorkStore,
        workCoordinator: DurableWorkCoordinator,
        declaredWorkQueueIds: Set<String> = setOf("turns"),
        declaredCapabilities: Set<ChannelCapability> = setOf(ChannelCapability.WorkQueue),
    ): WorkHarness {
        val implementationId = ChannelImplementationId("internal:lua-work-lifecycle")
        val instanceIdText = "lua-adapter"
        val generation = RuntimeGeneration(7)
        val parentJob = SupervisorJob()
        val parentScope = CoroutineScope(Dispatchers.Unconfined + parentJob)
        val workers = RuntimeWorkerDispatcher.fromDispatcher(Dispatchers.Unconfined)
        val boundary = RuntimeInvocationBoundary(workers)
        val gate = boundary.openGeneration(instanceIdText, generation, parentScope)
        val context = GenerationExecutionContextImpl(instanceIdText, gate, parentScope) { kotlinx.coroutines.delay(it) }
        val definition = ChannelDefinition(
            id = instanceIdText,
            name = "Lua adapter",
            implementationId = implementationId,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = OpaqueJsonObject.fromJsonObject(JSONObject()),
        )
        val image = (ImmutableProgramImage.create(
            entryPoint = "main",
            sourceMap = mapOf("main" to "return { startup = function() end }"),
            requirements = LuaProgramRequirements(LUA_VERSION, API_VERSION),
        ) as? ProgramImageCreationResult.Success)?.image
            ?: throw AssertionError("Test image must validate")
        val capabilities = RevocableChannelCapabilityScope(
            identity = CapabilityScopeIdentity(instanceIdText, generation),
            declaredCapabilities = declaredCapabilities,
            host = NoWorkCapabilitiesHost,
        )
        bridge.retainedCallbacks = callbacks
        val provider = LuaChannelImplementationProvider.create(
            implementationId = implementationId,
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
            configurationProvider = CompiledConfigurationProvider(
                implementationId,
                PackageConfigurationDeclaration(
                    ConfigurationDataDeclaration(emptyList()),
                    ConfigurationUiDeclaration(emptyList()),
                ),
            ),
            workStore = workStore,
            workCoordinator = workCoordinator,
            declaredWorkQueueIds = declaredWorkQueueIds,
            packageRepositoryId = repositoryId.value,
        )
        val result = provider.constructRuntime(
            ChannelRuntimeConstructionRequest(
                definition = definition,
                configuration = ValidatedChannelConfiguration(
                    implementationId = implementationId,
                    schemaVersion = 1,
                    payload = definition.configPayload,
                ),
                capabilities = capabilities,
                generationContext = context,
            ),
        )
        val runtime = (result as? ChannelRuntimeConstructionResult.Success)?.runtime as? LuaAdapterRuntime
            ?: throw AssertionError("Expected a validated Lua adapter runtime, got $result")
        return WorkHarness(runtime, capabilities, context, boundary, parentJob)
    }

    private object NoWorkCapabilitiesHost : ChannelCapabilityHost {
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

    // ── Scripted bridge ─────────────────────────────────────────────────

    /**
     * Bridge scripted only at the native boundary: callback outcomes are enqueued
     * per name, host-operation claims are registered by request id, and every
     * resume/start/close is recorded for ordering assertions.
     */
    private class RecordingWorkBridge : LuaKernelBridge {
        val claims = mutableMapOf<Long, HostOperationClaim>()
        private var nextRequestId = 100L
        val closeCalls = AtomicInteger()
        val startedCoroutines = mutableListOf<Long>()
        val resumeCalls = mutableListOf<Pair<Boolean, String>>()
        val startCoroutineOutcomes = ArrayDeque<LuaKernelOutcome>()
        val resumeOutcomes = ArrayDeque<LuaKernelOutcome>()
        var onCoroutineStarted: ((Long) -> Unit)? = null
        var onCoroutineResumed: (() -> Unit)? = null
        var retainedCallbacks: Set<String> = setOf("startup")
        var lastResourceContextJson: String? = null
        private val scriptedCallbacks = mutableMapOf<String, ArrayDeque<LuaKernelOutcome>>()

        fun enqueue(name: String, outcome: LuaKernelOutcome) {
            scriptedCallbacks.getOrPut(name) { ArrayDeque() }.addLast(outcome)
        }

        /** Registers a durable-work claim; returns its request-id string for yields. */
        fun registerWorkClaim(
            kind: HostOperationKind,
            queue: String? = "turns",
            payloadJson: String? = null,
            job: String? = null,
        ): String {
            val id = nextRequestId++
            claims[id] = HostOperationClaim.Admitted(
                requestId = id,
                kind = kind,
                audioToken = null,
                text = null,
                language = null,
                voice = null,
                speed = 1.0,
                delaySeconds = 0.0,
                queue = queue,
                payloadJson = payloadJson,
                job = job,
            )
            return id.toString()
        }

        override fun claimHostOperation(handle: LuaStateHandle, requestId: Long): HostOperationClaim =
            claims[requestId] ?: HostOperationClaim.Rejected("E_STALE")

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
            return admitSpawned(resumeOutcomes.removeFirstOrNull() ?: completed(), spawnAdmission)
        }

        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome =
            LuaKernelOutcome.Cancelled(41, 7, operation.operationId.value)

        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome =
            LuaKernelOutcome.Interrupted(41, 7, "interrupted", 0)

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

        override fun loadProgramImage(
            handle: LuaStateHandle,
            entryPoint: String,
            sourceMap: Map<String, String>,
        ): LuaKernelOutcome = completed(org.json.JSONArray(retainedCallbacks.toList()).toString())

        override fun invokeStartupCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            config: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = admitSpawned(
            scriptedCallbacks[callbackHandle.name]?.removeFirstOrNull() ?: completed(),
            spawnAdmission,
        )

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = admitSpawned(
            scriptedCallbacks[callbackHandle.name]?.removeFirstOrNull() ?: completed(),
            spawnAdmission,
        )

        override fun invokeSosCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = admitSpawned(
            scriptedCallbacks[callbackHandle.name]?.removeFirstOrNull() ?: completed(),
            spawnAdmission,
        )

        override fun invokeInputCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            capturedAudioToken: String,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = admitSpawned(
            scriptedCallbacks[callbackHandle.name]?.removeFirstOrNull() ?: completed(),
            spawnAdmission,
        )

        override fun startCoroutine(
            handle: LuaStateHandle,
            coroutineId: LuaCoroutineId,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            startedCoroutines += coroutineId.value
            onCoroutineStarted?.invoke(coroutineId.value)
            return admitSpawned(startCoroutineOutcomes.removeFirstOrNull() ?: completed(), spawnAdmission)
        }

        override fun setResourceContext(handle: LuaStateHandle, resourceContextJson: String): LuaKernelOutcome {
            lastResourceContextJson = resourceContextJson
            return completed()
        }

        private fun admitSpawned(
            outcome: LuaKernelOutcome,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            val spawned = when (outcome) {
                is LuaKernelOutcome.Completed -> outcome.spawnedCoroutines.orEmpty()
                is LuaKernelOutcome.Yielded -> outcome.spawnedCoroutines.orEmpty()
                else -> return outcome
            }
            val accepted = spawned.filter { coroutineId -> spawnAdmission.admitTask(coroutineId) == 0 }
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
            logs = null,
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
