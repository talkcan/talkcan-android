package io.talkcan.service

import io.talkcan.bluetooth.SleepwalkerBleConnection
import io.talkcan.bluetooth.SleepwalkerConnectionResult
import io.talkcan.channel.SleepwalkerTextOutputService
import io.talkcan.channel.capability.CapabilityKey
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.HostedCapabilityAcquisition
import io.talkcan.channel.capability.TextDeliveryOutcome
import io.talkcan.channel.capability.TextOutputCapability
import io.talkcan.channel.capability.TextOutputProfile
import io.talkcan.channel.capability.TextOutputRejectionReason
import io.talkcan.channel.capability.TextOutputRequest
import io.talkcan.channel.capability.RuntimeGeneration
import io.talkcan.model.TextOutputTransportState
import io.sleepwalker.core.hid.LowLevelHidImpl
import io.sleepwalker.core.keymap.SeedKeymapDatabase
import io.talkcan.channel.capability.ChannelCapability
import io.talkcan.channel.capability.GenericHttpCapability
import io.talkcan.http.GenericHttpRequest
import io.talkcan.http.GenericHttpResponse
import io.talkcan.http.GenericHttpResult
import io.talkcan.dependency.ArtifactDigest
import io.talkcan.dependency.ConfigurationDataDeclaration
import io.talkcan.dependency.ConfigurationUiDeclaration
import io.talkcan.dependency.GitHubAssetIdentity
import io.talkcan.dependency.GitHubReleaseIdentity
import io.talkcan.dependency.GitHubRepositoryCoordinates
import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.dependency.PackageCapability
import io.talkcan.dependency.PackageConfigurationDeclaration
import io.talkcan.dependency.PackageManifest
import io.talkcan.dependency.PackagePresentation
import io.talkcan.dependency.PackageResourcesDeclaration
import io.talkcan.dependency.PackageSourceRecord
import io.talkcan.model.ProviderRevisionFingerprint
import io.talkcan.dependency.RuntimeRequirements
import io.talkcan.dependency.ValidatedPackageRevision
import io.talkcan.lua.API_VERSION
import io.talkcan.lua.ImmutableProgramImage
import io.talkcan.lua.LUA_VERSION
import io.talkcan.lua.kernel.KotlinLuaKernelBridge
import io.talkcan.lua.LuaPackageMaterializer
import io.talkcan.lua.LuaProgramRequirements
import io.talkcan.lua.ProgramImageCreationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.talkcan.channel.capability.AgentOperationContext
import io.talkcan.channel.capability.CapabilityLeaseTermination
import io.talkcan.channel.capability.DeferredAudioPlaybackCapability
import io.talkcan.channel.capability.KeyboardOutputSubmission
import io.talkcan.channel.capability.OpaqueAudioOperation
import io.talkcan.channel.capability.OutputAdmissionBounds
import io.talkcan.channel.capability.OutputAdmissionScheduler
import io.talkcan.channel.capability.OutputExecutionOwner
import io.talkcan.channel.capability.OutputExecutionOwnerKind
import io.talkcan.model.DelayedPlaybackOutcome
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.protocol.Opcodes
import kotlinx.coroutines.test.advanceUntilIdle

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceChannelCapabilityHostTest {

    @Test
    fun publicAudioCapabilityIdsProjectToTypedHostRequirements() {
        val identity = GitHubRepositoryIdentity("900100200")
        val digest = ArtifactDigest("a".repeat(64))
        val image = when (val created = ImmutableProgramImage.create(
            entryPoint = "plugin",
            sourceMap = mapOf("plugin" to "return {}"),
            requirements = LuaProgramRequirements(LUA_VERSION, API_VERSION),
        )) {
            is ProgramImageCreationResult.Success -> created.image
            is ProgramImageCreationResult.Failure -> throw AssertionError(created.error.message)
        }
        val revision = ValidatedPackageRevision(
            digest = digest,
            manifest = PackageManifest(
                manifestVersion = 1,
                repositoryId = identity,
                packageVersion = "1.0.0",
                entryModule = "plugin",
                presentation = PackagePresentation("Capability projection", "typed host seam"),
                runtime = RuntimeRequirements(LUA_VERSION, API_VERSION),
                configuration = PackageConfigurationDeclaration(
                    ConfigurationDataDeclaration(emptyList()),
                    ConfigurationUiDeclaration(emptyList()),
                ),
                resources = PackageResourcesDeclaration(emptyList()),
                profileTypes = emptyList(),
                choiceResolvers = emptyList(),
                workQueues = emptyList(),
                capabilities = linkedSetOf(
                    PackageCapability.AUDIO_TRANSCRIPTION,
                    PackageCapability.AUDIO_SYNTHESIS,
                    PackageCapability.AUDIO_PLAYBACK,
                ),
            ),
            sourceRecord = PackageSourceRecord(
                repositoryId = identity,
                coordinates = GitHubRepositoryCoordinates("owner", "repository"),
                release = GitHubReleaseIdentity("123", "v1.0.0", false),
                asset = GitHubAssetIdentity("456", "talkcan-channel.zip"),
                ownerId = "1224006",
            ),
            sourceMap = image.sourceMap,
            programImage = image,
            fingerprint = ProviderRevisionFingerprint.fromDigest(digest),
        )

        val descriptor = LuaPackageMaterializer.materialize(revision, KotlinLuaKernelBridge()).provider.descriptor

        assertEquals(
            setOf(
                ChannelCapability.Transcription,
                ChannelCapability.Synthesis,
                ChannelCapability.AudioOperation,
                ChannelCapability.DeferredAudioPlayback,
            ),
            descriptor.requiredCapabilities,
        )
    }

    @Test
    fun immediateAcquisitionIsRecoverableWhileTheLinkIsDisconnectedOrConnecting() = runTest {
        val connection = ControllableConnection()
        val host = host(service(connection) { error("immediate acquisition must not connect") })
        runCurrent()

        val disconnected = host.acquire(identity("disconnected"), CapabilityKey.TextOutput)

        connection.setState(TextOutputTransportState.Connecting)
        runCurrent()
        val connecting = host.acquire(identity("connecting"), CapabilityKey.TextOutput)

        assertTrue(disconnected is HostedCapabilityAcquisition.Recoverable)
        assertTrue(connecting is HostedCapabilityAcquisition.Recoverable)
    }

    @Test
    fun immediateAcquisitionIsUnavailableAfterTheTextOutputHostCloses() = runTest {
        val connection = ControllableConnection().apply { setState(TextOutputTransportState.Connected) }
        val output = service(connection) { error("closed service must not connect") }
        val host = host(output)
        runCurrent()

        output.close()

        assertTrue(
            host.acquire(identity("closed"), CapabilityKey.TextOutput) is HostedCapabilityAcquisition.Unavailable,
        )
    }

    @Test
    fun preparationReturnsAvailableOnlyAfterCurrentConnectionProofAndBindsTheRequestingInstance() = runTest {
        val connection = ControllableConnection()
        var connectCalls = 0
        val host = host(service(connection) {
            connectCalls += 1
            connection.setState(TextOutputTransportState.Connected)
            SleepwalkerConnectionResult.Connected
        })
        val requestingIdentity = identity("requesting-instance")

        val acquired = host.prepareAndAcquire(requestingIdentity, CapabilityKey.TextOutput, 50)
        val port = availablePort(acquired)
        val outcome = port.sendText(TextOutputRequest("", TextOutputProfile("linux:us")))

        assertEquals(1, connectCalls)
        assertEquals(
            TextDeliveryOutcome.Rejected(
                "requesting-instance:1",
                TextOutputRejectionReason.EMPTY_TEXT,
            ),
            outcome,
        )
    }

    @Test
    fun failedTimedOutAndStaleConnectedPreparationNeverReturnAvailable() = runTest {
        val failed = host(
            service(ControllableConnection()) { SleepwalkerConnectionResult.Failed("adapter rejected connection") },
        ).prepareAndAcquire(identity("failed"), CapabilityKey.TextOutput, 50)

        val stalledConnection = ControllableConnection()
        val stalled = CompletableDeferred<SleepwalkerConnectionResult>()
        val timedOutHost = host(
            service(stalledConnection, preparationTimeoutMs = 25) { stalled.await() },
        )
        val timedOut = async {
            timedOutHost.prepareAndAcquire(identity("timed-out"), CapabilityKey.TextOutput, 25)
        }
        runCurrent()
        advanceTimeBy(25)

        val raceConnection = ControllableConnection()
        val staleConnected = host(service(raceConnection) {
            raceConnection.setState(TextOutputTransportState.Connected)
            raceConnection.setState(TextOutputTransportState.Disconnected)
            SleepwalkerConnectionResult.Connected
        }).prepareAndAcquire(identity("stale-connected"), CapabilityKey.TextOutput, 50)

        assertNotAvailable(failed)
        assertNotAvailable(timedOut.await())
        assertNotAvailable(staleConnected)
    }

    @Test
    fun connectedImmediateAcquisitionIsAvailableWithoutStartingPreparation() = runTest {
        val connection = ControllableConnection().apply { setState(TextOutputTransportState.Connected) }
        var connectCalls = 0
        val host = host(service(connection) {
            connectCalls += 1
            error("connected acquisition must not start preparation")
        })
        runCurrent()

        val acquisition = host.acquire(identity("already-connected"), CapabilityKey.TextOutput)

        assertTrue(acquisition is HostedCapabilityAcquisition.Available)
        assertEquals(0, connectCalls)
    }

    @Test
    fun generationTerminationRevokesQueuedKeyboardGenerationWhilePreservingSiblingAndDeferredAudio() = runTest {
        val connection = HoldingConnection().apply {
            setState(TextOutputTransportState.Connected)
            holdFirstTextOperation = CompletableDeferred()
        }
        val scheduler = OutputAdmissionScheduler(OutputAdmissionBounds(maxQueuedPerProcess = 8))
        val output = holdingService(connection, scheduler)
        val terminatedIdentity = identity("lua-victim", generation = 11)
        val siblingIdentity = identity("lua-sibling", generation = 12)

        val deferredTerminations = mutableListOf<Pair<CapabilityScopeIdentity, CapabilityLeaseTermination>>()
        val deferredAudio = object : DeferredAudioPlaybackCapability, GenerationCapabilityResource {
            var queuedCountWhenTerminated = -1

            override suspend fun scheduleAudio(
                context: AgentOperationContext,
                audio: OpaqueAudioOperation,
                eligibilityDelayMillis: Long,
            ): DelayedPlaybackOutcome = error("deferred audio is not exercised by this termination test")

            override suspend fun onGenerationTermination(
                identity: CapabilityScopeIdentity,
                termination: CapabilityLeaseTermination,
            ) {
                deferredTerminations += identity to termination
                queuedCountWhenTerminated = scheduler.queuedCount()
            }
        }
        val host = host(output) { deferredAudio }

        val revoked = output.keyboardOutputAdapter(
            terminatedIdentity,
            OutputExecutionOwner(OutputExecutionOwnerKind.MANAGED_TASK, "victim-owner"),
        )
        val sibling = output.keyboardOutputAdapter(
            siblingIdentity,
            OutputExecutionOwner(OutputExecutionOwnerKind.MANAGED_TASK, "sibling-owner"),
        )

        // Occupy the single active delivery slot so both adapter operations queue deterministically.
        val builtIn = output.capabilityFor("built-in")
        val holder = async { builtIn.sendText(TextOutputRequest("aaa", TextOutputProfile("linux:us"))) }
        connection.firstTextOperation.await()
        runCurrent()
        val revokedResult = async { revoked.sendText(TextOutputRequest("bbb", TextOutputProfile("linux:us"))) }
        val siblingResult = async { sibling.sendText(TextOutputRequest("ccc", TextOutputProfile("linux:us"))) }
        runCurrent()
        assertEquals(2, scheduler.queuedCount())

        // Terminating generation A through the capability host drains only A's queued keyboard work.
        host.onGenerationTermination(terminatedIdentity, CapabilityLeaseTermination.REVOKED)
        runCurrent()

        // Queued generation-A operation is revoked effect-not-begun; sibling B stays queued.
        assertEquals(KeyboardOutputSubmission.Revoked, revokedResult.await())
        assertEquals(1, scheduler.queuedCount())
        // Existing deferred-audio termination forwarding is preserved and runs before the keyboard drain.
        assertEquals(listOf(terminatedIdentity to CapabilityLeaseTermination.REVOKED), deferredTerminations)
        assertEquals(2, deferredAudio.queuedCountWhenTerminated)

        // Sibling B remains admitted and deliverable once the active slot frees.
        checkNotNull(connection.holdFirstTextOperation).complete(Unit)
        advanceUntilIdle()
        val siblingOutcome = siblingResult.await()
        assertTrue(siblingOutcome is KeyboardOutputSubmission.Completed)
        assertTrue((siblingOutcome as KeyboardOutputSubmission.Completed).outcome is TextDeliveryOutcome.Delivered)
        assertTrue(holder.await() is TextDeliveryOutcome.Delivered)
    }

    @Test
    fun networkHttpCapabilityIsUnavailableUnlessComposedAndAvailableWhenComposed() = runTest {
        fun connected(): ControllableConnection =
            ControllableConnection().apply { setState(TextOutputTransportState.Connected) }

        val withoutHttp = host(service(connected()) { SleepwalkerConnectionResult.Connected })
        assertTrue(
            withoutHttp.acquire(identity("without-http"), CapabilityKey.NetworkHttp) is HostedCapabilityAcquisition.Unavailable,
        )

        val httpCapability = object : GenericHttpCapability {
            override suspend fun request(request: GenericHttpRequest): GenericHttpResult =
                GenericHttpResult.Success(GenericHttpResponse(200, emptyMap(), "ok"))
        }
        val withHttp = ServiceChannelCapabilityHost(
            textOutputService = service(connected()) { SleepwalkerConnectionResult.Connected },
            transcription = { null },
            synthesis = { null },
            audioOperation = { null },
            networkHttp = { httpCapability },
        )

        val acquired = withHttp.acquire(identity("with-http"), CapabilityKey.NetworkHttp)
        assertTrue(acquired is HostedCapabilityAcquisition.Available)
    }

    private fun TestScope.service(
        connection: ControllableConnection,
        preparationTimeoutMs: Long = 1_000,
        connect: suspend () -> SleepwalkerConnectionResult,
    ): SleepwalkerTextOutputService = SleepwalkerTextOutputService(
        scope = backgroundScope,
        connection = connection,
        hid = LowLevelHidImpl(),
        keymapDatabase = SeedKeymapDatabase,
        connect = { connect() },
        preparationTimeoutMs = preparationTimeoutMs,
        deliveryTimeoutMs = 1_000,
    )

    private fun TestScope.holdingService(
        connection: HoldingConnection,
        scheduler: OutputAdmissionScheduler,
    ): SleepwalkerTextOutputService = SleepwalkerTextOutputService(
        scope = backgroundScope,
        connection = connection,
        hid = LowLevelHidImpl(),
        keymapDatabase = SeedKeymapDatabase,
        connect = { _: Long -> SleepwalkerConnectionResult.Connected },
        preparationTimeoutMs = 1_000,
        deliveryTimeoutMs = 1_000,
        admission = scheduler,
    )

    private fun host(textOutputService: SleepwalkerTextOutputService): ServiceChannelCapabilityHost =
        ServiceChannelCapabilityHost(
            textOutputService = textOutputService,
            transcription = { null },
            synthesis = { null },
            audioOperation = { null },
        )

    private fun host(
        textOutputService: SleepwalkerTextOutputService,
        deferredAudioPlayback: (CapabilityScopeIdentity) -> DeferredAudioPlaybackCapability?,
    ): ServiceChannelCapabilityHost =
        ServiceChannelCapabilityHost(
            textOutputService = textOutputService,
            transcription = { null },
            synthesis = { null },
            audioOperation = { null },
            deferredAudioPlayback = deferredAudioPlayback,
        )

    private fun identity(instanceId: String, generation: Long = 0): CapabilityScopeIdentity =
        CapabilityScopeIdentity(instanceId, RuntimeGeneration(generation))

    private fun availablePort(
        acquisition: HostedCapabilityAcquisition<TextOutputCapability>,
    ): TextOutputCapability {
        assertTrue(acquisition is HostedCapabilityAcquisition.Available)
        return (acquisition as HostedCapabilityAcquisition.Available).port
    }

    private fun assertNotAvailable(acquisition: HostedCapabilityAcquisition<TextOutputCapability>) {
        assertFalse(acquisition is HostedCapabilityAcquisition.Available)
    }

    private class ControllableConnection : SleepwalkerBleConnection() {
        fun setState(state: TextOutputTransportState) {
            _connectionState.value = state
        }
    }

    private class HoldingConnection : SleepwalkerBleConnection() {
        val sent = mutableListOf<LowLevelOp>()
        val firstTextOperation = CompletableDeferred<Unit>()
        var holdFirstTextOperation: CompletableDeferred<Unit>? = null
        private var sawTextOperation = false

        fun setState(state: TextOutputTransportState) {
            _connectionState.value = state
        }

        override fun disconnect() {
            _connectionState.value = TextOutputTransportState.Disconnected
        }

        override suspend fun sendOp(op: LowLevelOp) {
            val safetyOperation = op.opcode == Opcodes.ARM || op.opcode == Opcodes.KILL || op.opcode == Opcodes.DISARM
            if (!safetyOperation && !sawTextOperation) {
                sawTextOperation = true
                firstTextOperation.complete(Unit)
                sent += op
                holdFirstTextOperation?.await()
                return
            }
            sent += op
        }

        override suspend fun awaitAck(seqId: Int, timeoutMs: Long): Boolean = true
    }
}
