package io.talkcan.service

import io.talkcan.audio.ChannelAudioInputSession
import io.talkcan.audio.ChannelInputAcceptance
import io.talkcan.audio.ChannelInputResult
import io.talkcan.audio.ChannelInputTarget
import io.talkcan.audio.RecordedPcm
import io.talkcan.channel.capability.AgentOperationContext
import io.talkcan.channel.capability.AudioOperationArtifact
import io.talkcan.channel.capability.OpaqueAudioOperation
import io.talkcan.channel.capability.CapabilityAvailability
import io.talkcan.channel.capability.CapabilityKey
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.ChannelCapabilityHost
import io.talkcan.channel.capability.ChannelCapabilityPort
import io.talkcan.channel.capability.HostedCapabilityAcquisition
import io.talkcan.channel.capability.CapabilityAcquisition
import io.talkcan.channel.capability.CapabilityOperationResult
import io.talkcan.channel.capability.CapabilityUnavailableReason
import io.talkcan.channel.capability.ChannelCapability
import io.talkcan.channel.capability.ChannelCapabilityScope
import io.talkcan.channel.capability.CapabilityLeaseTermination
import io.talkcan.channel.capability.TextDeliveryOutcome
import io.talkcan.channel.capability.TextOutputCapability
import io.talkcan.channel.capability.TextOutputProfile
import io.talkcan.channel.capability.TextOutputRequest
import io.talkcan.model.ActorRuntimeHostContext
import io.talkcan.model.AgentOperationId
import io.talkcan.model.AgentRunId
import io.talkcan.model.ChannelCatalogueSnapshot
import io.talkcan.model.ChannelConfigurationField
import io.talkcan.model.ChannelConfigurationMigrationStep
import io.talkcan.model.ChannelConfigurationProvider
import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ChannelImplementationDescriptor
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelImplementationProvider
import io.talkcan.model.ChannelImplementationProviderRegistry
import io.talkcan.model.ChannelPreparationTraits
import io.talkcan.model.ChannelPresentationMetadata
import io.talkcan.model.ChannelProviderError
import io.talkcan.model.ChannelRuntimeConstructionRequest
import io.talkcan.model.ChannelRuntimeConstructionResult
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.model.ProviderConfigurationResult
import io.talkcan.model.ValidatedChannelConfiguration
import io.talkcan.model.ProviderRevisionFingerprint
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNotEquals
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelRuntimeRegistryTest {
    @Test
    fun unavailableDefinitionsRemainOrderedWithTheirTypedProviderFailures() = runTest {
        val incompatible = TestProvider(ChannelImplementationId("test:incompatible")).apply {
            configuration.mode = ConfigurationMode.UNSUPPORTED
        }
        val migration = TestProvider(ChannelImplementationId("test:migration")).apply {
            configuration.mode = ConfigurationMode.MIGRATION_FAILURE
        }
        val invalid = TestProvider(ChannelImplementationId("test:invalid")).apply {
            configuration.mode = ConfigurationMode.INVALID
        }
        val construction = TestProvider(ChannelImplementationId("test:construction")).apply {
            constructResult = { request ->
                ChannelRuntimeConstructionResult.Failure(
                    ChannelProviderError.RuntimeConstructionFailed(
                        request.definition.implementationId,
                        "constructor rejected request",
                    ),
                )
            }
        }
        val fixture = fixture(incompatible, migration, invalid, construction)
        val missing = definition("missing", "test:missing")
        val incompatibleDefinition = definition("incompatible", "test:incompatible")
        val migrationDefinition = definition("migration", "test:migration", version = 1)
        val invalidDefinition = definition("invalid", "test:invalid")
        val constructionDefinition = definition("construction", "test:construction")

        fixture.registry.reconcile(
            ChannelCatalogueSnapshot(
                listOf(
                    missing,
                    incompatibleDefinition,
                    migrationDefinition,
                    invalidDefinition,
                    constructionDefinition,
                ),
                missing.id,
            ),
        )

        val snapshots = fixture.registry.runtimeSnapshots.value.entries
        assertEquals(
            listOf("missing", "incompatible", "migration", "invalid", "construction"),
            snapshots.map(ChannelRuntimeSnapshot::id),
        )
        assertTrue(providerError(snapshots[0]) is ChannelProviderError.MissingProvider)
        assertTrue(providerError(snapshots[1]) is ChannelProviderError.UnsupportedSchemaVersion)
        assertTrue(providerError(snapshots[2]) is ChannelProviderError.MigrationFailed)
        assertTrue(providerError(snapshots[3]) is ChannelProviderError.InvalidConfiguration)
        assertTrue(providerError(snapshots[4]) is ChannelProviderError.RuntimeConstructionFailed)
        assertEquals(
            ChannelInputAcceptance.Unavailable("Implementation provider test:missing is not registered"),
            fixture.registry.prepareInput(missing.id),
        )
    }
    @Test
    fun unavailableActiveSelectionRemainsOrderedInTheAggregateProjection() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:available"))
        val fixture = fixture(provider)
        val available = definition("available", "test:available")
        val unavailable = definition("unavailable", "test:missing")
        val definitions = listOf(available, unavailable)

        fixture.registry.reconcile(ChannelCatalogueSnapshot(definitions, available.id))
        runCurrent()

        val initial = fixture.registry.runtimeSnapshots.value
        assertEquals(available.id, initial.activeChannelId)
        assertEquals(definitions.map(ChannelDefinition::id), initial.entries.map(ChannelRuntimeSnapshot::id))
        assertEquals(ChannelPreparationAvailability.Available, initial.entries[0].preparation)
        assertTrue(providerError(initial.entries[1]) is ChannelProviderError.MissingProvider)
        val unavailablePreparation = initial.entries[1].preparation

        fixture.registry.reconcile(ChannelCatalogueSnapshot(definitions, unavailable.id))

        val selectedUnavailable = fixture.registry.runtimeSnapshots.value
        assertEquals(unavailable.id, selectedUnavailable.activeChannelId)
        assertEquals(definitions.map(ChannelDefinition::id), selectedUnavailable.entries.map(ChannelRuntimeSnapshot::id))
        assertEquals(ChannelPreparationAvailability.Available, selectedUnavailable.entries[0].preparation)
        assertEquals(unavailablePreparation, selectedUnavailable.entries[1].preparation)
    }


    @Test
    fun preparationCanReenterRegistryWithoutHoldingItsStructuralLock() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:reentrant"))
        val second = TestProvider(ChannelImplementationId("test:second"))
        val fixture = fixture(provider, second)
        val firstDefinition = definition("first", "test:reentrant")
        val secondDefinition = definition("second", "test:second")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(firstDefinition, secondDefinition), firstDefinition.id))
        val observed = mutableListOf<String>()
        provider.runtimes.single().prepareResult = {
            observed += requireNotNull(fixture.registry.getRuntimeSnapshot(secondDefinition.id)).id
            ChannelInputAcceptance.Refused("not accepting")
        }

        assertEquals(ChannelInputAcceptance.Refused("not accepting"), fixture.registry.prepareInput(firstDefinition.id))
        assertEquals(listOf(secondDefinition.id), observed)
    }

    @Test
    fun slowConstructionForOneInstanceDoesNotBlockAnotherInstanceRead() = runTest {
        val slow = TestProvider(ChannelImplementationId("test:slow"))
        val fast = TestProvider(ChannelImplementationId("test:fast"))
        val fixture = fixture(slow, fast)
        val slowDefinition = definition("slow", "test:slow", payload = "{\"version\":1}")
        val fastDefinition = definition("fast", "test:fast")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(slowDefinition, fastDefinition), fastDefinition.id))
        val slowConstructionStarted = CompletableDeferred<Unit>()
        val releaseSlowConstruction = CompletableDeferred<Unit>()
        slow.constructResult = { request ->
            slowConstructionStarted.complete(Unit)
            releaseSlowConstruction.await()
            ChannelRuntimeConstructionResult.Success(TestRuntime(request.definition))
        }

        val reconciliation = async {
            fixture.registry.reconcile(
                ChannelCatalogueSnapshot(
                    listOf(slowDefinition.copy(configPayload = opaque("{\"version\":2}")), fastDefinition),
                    fastDefinition.id,
                ),
            )
        }
        runCurrent()
        assertTrue(slowConstructionStarted.isCompleted)
        assertEquals(fastDefinition.id, fixture.registry.getRuntimeSnapshot(fastDefinition.id)?.id)
        assertEquals(listOf("slow", "fast"), fixture.registry.getAllRuntimeSnapshots().map(ChannelRuntimeSnapshot::id))

        releaseSlowConstruction.complete(Unit)
        reconciliation.await()
    }

    @Test
    fun staleConstructionCannotPublishOverTheCurrentGeneration() = runTest {
        val first = TestProvider(ChannelImplementationId("test:first"))
        val second = TestProvider(ChannelImplementationId("test:second"))
        val fixture = fixture(first, second)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        first.constructResult = { request ->
            firstStarted.complete(Unit)
            releaseFirst.await()
            ChannelRuntimeConstructionResult.Success(TestRuntime(request.definition))
        }
        val initial = definition("channel", "test:first")
        val replacement = definition("channel", "test:second")

        val staleReconciliation = async {
            fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(initial), initial.id))
        }
        runCurrent()
        assertTrue(firstStarted.isCompleted)

        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(replacement), replacement.id))
        val current = second.runtimes.single()
        releaseFirst.complete(Unit)
        staleReconciliation.await()

        assertEquals(replacement.implementationId, fixture.registry.getRuntimeSnapshot(replacement.id)?.implementationId)
        assertEquals(0, first.runtimes.size)
        assertEquals(0, current.closeCount.get())
    }

    @Test
    fun committedTargetSurvivesSelectionReorderReplacementAndRemovalUntilReleased() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:provider"))
        val other = TestProvider(ChannelImplementationId("test:other"))
        val fixture = fixture(provider, other)
        val original = definition("original", "test:provider")
        val selectedLater = definition("later", "test:other")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original, selectedLater), original.id))
        runCurrent()
        val originalRuntime = provider.runtimes.single()
        val events = mutableListOf<String>()
        originalRuntime.onClose = { events += "G:closed" }
        originalRuntime.prepareResult = {
            ChannelInputAcceptance.Accepted(RecordingTarget(events))
        }
        val committed = accepted(fixture.registry.prepareInput(original.id))

        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original, selectedLater), selectedLater.id))
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(selectedLater, original), selectedLater.id))
        val replacementDefinition = original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))
        val replacementReconciliation = async {
            fixture.registry.reconcile(
                ChannelCatalogueSnapshot(listOf(selectedLater, replacementDefinition), selectedLater.id),
            )
        }
        runCurrent()
        val replacementRuntime = provider.runtimes.last()
        replacementRuntime.onClose = { events += "H:closed" }

        assertFalse(replacementReconciliation.isCompleted)
        assertEquals(0, originalRuntime.closeCount.get())
        assertEquals(
            ChannelInputAcceptance.Unavailable(ChannelPreparationReason.ProviderInitialising.message),
            fixture.registry.prepareInput(original.id),
        )

        committed.target.onInputCancelled("catalogue changed")
        committed.lease.releaseCommittedTargetLease()
        replacementReconciliation.await()
        runCurrent()

        replacementRuntime.prepareResult = { ChannelInputAcceptance.Refused("replacement received preparation") }
        assertEquals(
            ChannelInputAcceptance.Refused("replacement received preparation"),
            fixture.registry.prepareInput(original.id),
        )

        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(selectedLater), selectedLater.id))
        runCurrent()

        assertEquals(listOf("cancelled:catalogue changed", "G:closed", "H:closed"), events)
        assertEquals(1, originalRuntime.closeCount.get())
        assertEquals(1, replacementRuntime.closeCount.get())
    }

    @Test
    fun committedReleaseOutlivesGenericCallbackDeadlineAndRetiredRuntimeClosesAfterLeaseRelease() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:provider"))
        val fixture = fixture(
            provider,
            callbackTimeoutMillis = 5_000,
            inputReleasedTimeoutMillis = 7_000,
        )
        val original = definition("channel", "test:provider")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
        runCurrent()
        val runtime = provider.runtimes.single()
        val events = mutableListOf<String>()
        runtime.onClose = { events += "runtime-closed" }
        runtime.prepareResult = {
            ChannelInputAcceptance.Accepted(object : ChannelInputTarget {
                override fun onInputStarted(session: ChannelAudioInputSession) = Unit

                override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                    events += "release-started"
                    delay(6_000)
                    events += "release-effect-returned"
                    return ChannelInputResult.None
                }

                override fun onInputCancelled(reason: String) = Unit

                override fun onInputFailed(reason: String) = Unit
            })
        }
        val committed = accepted(fixture.registry.prepareInput(original.id))

        val replacementReconciliation = async {
            fixture.registry.reconcile(
                ChannelCatalogueSnapshot(
                    listOf(original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))),
                    original.id,
                ),
            )
        }
        runCurrent()
        val replacementRuntime = provider.runtimes.last()
        assertFalse(replacementReconciliation.isCompleted)
        assertEquals(0, runtime.closeCount.get())

        val release = async {
            committed.target.onInputReleased(RecordedPcm(shortArrayOf(7), 16_000))
        }
        runCurrent()
        assertEquals(listOf("release-started"), events)

        advanceTimeBy(5_001)
        runCurrent()
        assertFalse(release.isCompleted)
        assertFalse(replacementReconciliation.isCompleted)
        assertEquals(0, runtime.closeCount.get())

        advanceTimeBy(999)
        runCurrent()
        assertEquals(ChannelInputResult.None, release.await())
        assertEquals(listOf("release-started", "release-effect-returned"), events)
        assertFalse(replacementReconciliation.isCompleted)
        assertEquals(0, runtime.closeCount.get())

        committed.lease.releaseCommittedTargetLease()
        replacementReconciliation.await()
        runCurrent()
        assertEquals(
            listOf("release-started", "release-effect-returned", "runtime-closed"),
            events,
        )
        assertEquals(1, runtime.closeCount.get())

        replacementRuntime.prepareResult = {
            ChannelInputAcceptance.Refused("replacement received preparation")
        }
        assertEquals(
            ChannelInputAcceptance.Refused("replacement received preparation"),
            fixture.registry.prepareInput(original.id),
        )
    }

    @Test
    fun staleAcceptedPreparationIsCancelledAndNeverLeasesTheRetiredGeneration() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:provider"))
        val fixture = fixture(provider)
        val original = definition("channel", "test:provider")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
        runCurrent()
        val runtime = provider.runtimes.single()
        val events = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runtime.prepareResult = {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            ChannelInputAcceptance.Accepted(RecordingTarget(events))
        }

        val preparation = async { fixture.registry.prepareInput(original.id) }
        runCurrent()
        assertTrue(started.isCompleted)
        val reconciliation = async {
            fixture.registry.reconcile(
                ChannelCatalogueSnapshot(
                    listOf(original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))),
                    original.id,
                ),
            )
        }
        runCurrent()
        release.complete(Unit)
        reconciliation.await()

        assertEquals(
            ChannelInputAcceptance.Unavailable(ChannelPreparationReason.RuntimeClosed.message),
            preparation.await(),
        )
        assertEquals(listOf("cancelled:Channel channel changed during preparation"), events)
        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun refusedPreparationReleasesAReplacedRuntimeWithoutCreatingALease() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:provider"))
        val fixture = fixture(provider)
        val original = definition("channel", "test:provider")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
        runCurrent()
        val runtime = provider.runtimes.single()
        runtime.prepareResult = { ChannelInputAcceptance.Refused("dependency unavailable") }

        assertEquals(
            ChannelInputAcceptance.Refused("dependency unavailable"),
            fixture.registry.prepareInput(original.id),
        )
        fixture.registry.reconcile(
            ChannelCatalogueSnapshot(
                listOf(original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))),
                original.id,
            ),
        )
        runCurrent()

        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun thrownPreparationReleasesAReplacedRuntimeWithoutCreatingALease() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:provider"))
        val fixture = fixture(provider)
        val original = definition("channel", "test:provider")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
        runCurrent()
        val runtime = provider.runtimes.single()
        runtime.prepareResult = { throw IllegalStateException("provider bug") }

        assertEquals(
            ChannelInputAcceptance.Unavailable(ChannelPreparationReason.RuntimeFailed().message),
            fixture.registry.prepareInput(original.id),
        )
        fixture.registry.reconcile(
            ChannelCatalogueSnapshot(
                listOf(original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))),
                original.id,
            ),
        )
        runCurrent()

        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun timedOutPreparationReleasesAReplacedRuntimeWithoutCreatingALease() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:provider"))
        val fixture = fixture(provider, callbackTimeoutMillis = 10)
        val original = definition("channel", "test:provider")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
        runCurrent()
        val runtime = provider.runtimes.single()
        val started = CompletableDeferred<Unit>()
        runtime.prepareResult = {
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
            ChannelInputAcceptance.Refused("unreachable")
        }

        val preparation = async { fixture.registry.prepareInput(original.id) }
        runCurrent()
        assertTrue(started.isCompleted)
        advanceTimeBy(10)
        runCurrent()
        assertEquals(
            ChannelInputAcceptance.Unavailable(ChannelPreparationReason.RuntimeTimedOut.message),
            preparation.await(),
        )
        fixture.registry.reconcile(
            ChannelCatalogueSnapshot(
                listOf(original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))),
                original.id,
            ),
        )
        runCurrent()

        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun aggregateProjectionEmitsRuntimeChangesWithoutRefreshSampling() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:provider"))
        val fixture = fixture(provider)
        val definition = definition("channel", "test:provider")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        val runtime = provider.runtimes.single()
        runCurrent()

        runtime.publish(
            preparation = ChannelPreparationAvailability.Unavailable(
                ChannelPreparationReason.RuntimeFailed("transcriber stopped"),
            ),
            status = ChannelExecutionStatus.FAILED,
        )
        runCurrent()

        val projected = fixture.registry.runtimeSnapshots.value.entries.single()
        assertEquals(ChannelExecutionStatus.FAILED, projected.executionStatus)
        assertEquals(
            ChannelPreparationReason.RuntimeFailed("transcriber stopped"),
            (projected.preparation as ChannelPreparationAvailability.Unavailable).reason,
        )
    }

    @Test
    fun shutdownWaitsForTerminalCallbackBeforeClosingAndRepeatedShutdownDoesNotCloseAgain() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:provider"))
        val order = mutableListOf<String>()
        val fixture = fixture(
            provider,
            onPttSessionCancelRequested = { order += "session-cancel-requested" },
            shutdownAwaitMillis = 1_000,
        )
        val definition = definition("channel", "test:provider")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()
        val runtime = provider.runtimes.single()
        runtime.onClose = { order += "closed" }
        runtime.prepareResult = { ChannelInputAcceptance.Accepted(RecordingTarget(order)) }
        val committed = accepted(fixture.registry.prepareInput(definition.id))

        val shutdown = async { fixture.registry.shutdownAndAwait() }
        runCurrent()
        assertEquals(listOf("session-cancel-requested"), order)
        assertFalse(shutdown.isCompleted)
        committed.target.onInputCancelled("service stopping")
        committed.lease.releaseCommittedTargetLease()

        assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown.await())
        assertEquals(
            listOf("session-cancel-requested", "cancelled:service stopping", "closed"),
            order,
        )
        assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, fixture.registry.shutdownAndAwait())
        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun lateSnapshotsFromARetiredRuntimeCannotOverwriteTheReplacementProjection() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:provider"))
        val fixture = fixture(provider)
        val original = definition("channel", "test:provider")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
        runCurrent()
        val retired = provider.runtimes.single()
        fixture.registry.reconcile(
            ChannelCatalogueSnapshot(
                listOf(original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))),
                original.id,
            ),
        )
        val replacement = provider.runtimes.last()
        runCurrent()

        retired.publish(
            preparation = ChannelPreparationAvailability.Unavailable(
                ChannelPreparationReason.RuntimeFailed("late retired failure"),
            ),
            status = ChannelExecutionStatus.FAILED,
        )
        replacement.publish(ChannelPreparationAvailability.Available, ChannelExecutionStatus.SUCCESS)
        runCurrent()

        val projected = fixture.registry.runtimeSnapshots.value.entries.single()
        assertEquals(ChannelExecutionStatus.SUCCESS, projected.executionStatus)
        assertEquals(ChannelPreparationAvailability.Available, projected.preparation)
        assertEquals(1, retired.closeCount.get())
    }

    @Test
    fun replacementDrainsCommittedGenerationBeforeSuccessorAdmitsEffectsOrInput() = runTest {
        val events = mutableListOf<String>()
        val capabilityHost = RecordingTextCapabilityHost(events)
        val provider = TestProvider(ChannelImplementationId("test:actor")).apply {
            requiredCapabilities = setOf(ChannelCapability.TextOutput)
        }
        val runtimes = mutableListOf<RecordingActorRuntime>()
        val terminalMayComplete = CompletableDeferred<Unit>()
        provider.constructResult = { request ->
            val isSuccessor = runtimes.isNotEmpty()
            RecordingActorRuntime(
                request.definition,
                request.capabilities,
                events,
                label = if (isSuccessor) "H" else "G",
                startupEvent = if (isSuccessor) "H:startup" else null,
                preparationEvent = if (isSuccessor) "H:prepare" else null,
                readinessEvent = if (isSuccessor) "H:readiness" else null,
            ).also(runtimes::add).let(ChannelRuntimeConstructionResult::Success)
        }
        val fixture = fixture(provider, capabilityHost = capabilityHost)
        val original = definition("channel", "test:actor")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
        runCurrent()
        val predecessor = runtimes.single()
        val predecessorGeneration = predecessor.scopeIdentity.runtimeGeneration.value
        predecessor.prepareResult = {
            ChannelInputAcceptance.Accepted(object : ChannelInputTarget {
                override fun onInputStarted(session: ChannelAudioInputSession) = Unit

                override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                    events += "G:terminal-started"
                    terminalMayComplete.await()
                    events += "G:terminal-completed"
                    return ChannelInputResult.None
                }

                override fun onInputCancelled(reason: String) = Unit

                override fun onInputFailed(reason: String) = Unit
            })
        }
        assertEquals(CapabilityOperationResult.Success(Unit), predecessor.emitText("before-replacement"))
        val committed = accepted(fixture.registry.prepareInput(original.id))
        val target = committed.target as ChannelInputTarget
        val terminal = async {
            target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000))
        }
        runCurrent()
        assertEquals(listOf("effect:channel:$predecessorGeneration:before-replacement", "G:terminal-started"), events)

        val replacementDefinition = original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))
        val replacement = backgroundScope.async {
            fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(replacementDefinition), replacementDefinition.id))
        }
        runCurrent()
        val successor = runtimes.last()
        val successorGeneration = successor.scopeIdentity.runtimeGeneration.value
        assertNotEquals(predecessor.scopeIdentity, successor.scopeIdentity)

        assertFalse(replacement.isCompleted)
        assertEquals(
            ChannelInputAcceptance.Unavailable(ChannelPreparationReason.ProviderInitialising.message),
            fixture.registry.prepareInput(original.id),
        )
        assertEquals(
            CapabilityOperationResult.Closed,
            successor.emitText("must-not-run-while-staged"),
        )
        assertEquals(
            listOf(
                "effect:channel:$predecessorGeneration:before-replacement",
                "G:terminal-started",
            ),
            events,
        )

        terminalMayComplete.complete(Unit)
        runCurrent()
        assertTrue("Terminal callback must complete", terminal.isCompleted)
        assertEquals(ChannelInputResult.None, terminal.await())
        committed.lease.releaseCommittedTargetLease()
        replacement.await()
        runCurrent()

        successor.prepareResult = { ChannelInputAcceptance.Refused("successor admitted") }
        assertEquals(
            ChannelInputAcceptance.Refused("successor admitted"),
            fixture.registry.prepareInput(original.id),
        )
        assertEquals(CapabilityOperationResult.Closed, predecessor.emitText("late-predecessor-effect"))
        assertEquals(CapabilityOperationResult.Success(Unit), successor.emitText("successor-effect"))
        fixture.registry.refreshReadiness()
        runCurrent()
        assertEquals(
            listOf(
                "effect:channel:$predecessorGeneration:before-replacement",
                "G:terminal-started",
                "G:terminal-completed",
                "effects-revoked:channel:$predecessorGeneration:REVOKED",
                "G:descendants-drained",
                "G:closed",
                "H:startup",
                "H:readiness",
                "H:prepare",
                "effect:channel:$successorGeneration:successor-effect",
                "H:readiness",
            ),
            events,
        )
        assertEquals(1, predecessor.closeCount.get())
    }


    @Test
    fun detachedRuntimeCloseThatOutlivesGateTimeoutClosesOnceBeforeSuccessorActivation() = runTest {
        val events = mutableListOf<String>()
        val provider = TestProvider(ChannelImplementationId("test:detached-close"))
        val runtimes = mutableListOf<TestRuntime>()
        provider.constructResult = { request ->
            val runtime = when (runtimes.size) {
                0 -> TestRuntime(request.definition)
                1 -> BlockingDetachedRuntime(request.definition, events)
                else -> RecordingActorRuntime(
                    request.definition,
                    request.capabilities,
                    events,
                    label = "I",
                    startupEvent = "I:startup",
                )
            }
            runtimes += runtime
            ChannelRuntimeConstructionResult.Success(runtime)
        }
        val fixture = fixture(provider, closeTimeoutMillis = 10)
        val original = definition("channel", "test:detached-close")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
        runCurrent()

        val h = original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))
        val hReconciliation = async {
            fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(h), h.id))
        }
        runCurrent()
        val detached = runtimes[1] as BlockingDetachedRuntime
        assertTrue(detached.activationStarted.isCompleted)

        val i = original.copy(configPayload = opaque("{\"version\":2,\"revision\":3}"))
        val iReconciliation = async {
            fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(i), i.id))
        }
        runCurrent()
        assertFalse(iReconciliation.isCompleted)

        detached.activationMayFinish.complete(Unit)
        runCurrent()
        assertTrue(detached.closeStarted.isCompleted)
        assertEquals(1, detached.closeCount.get())
        assertEquals(1, detached.maximumConcurrentCloseBodies.get())

        advanceTimeBy(10)
        runCurrent()
        assertFalse(detached.closeCompleted.isCompleted)
        assertFalse(iReconciliation.isCompleted)
        assertEquals(1, detached.closeCount.get())
        assertEquals(1, detached.maximumConcurrentCloseBodies.get())

        detached.closeMayFinish.complete(Unit)
        hReconciliation.await()
        iReconciliation.await()
        runCurrent()

        assertTrue(detached.closeCompleted.isCompleted)
        assertEquals(1, detached.closeCount.get())
        assertEquals(1, detached.maximumConcurrentCloseBodies.get())
        assertEquals(1, events.count { it == "H:close-completed" })
        assertTrue(events.indexOf("H:close-completed") < events.indexOf("I:startup"))
        assertEquals((runtimes[2] as RecordingActorRuntime).scopeIdentity, fixture.registry.capabilityScopeIdentity(i.id))
        assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, fixture.registry.shutdownAndAwait())
        assertEquals(1, (runtimes[2] as RecordingActorRuntime).closeCount.get())
    }

    @Test
    fun cancelledLeaseReleaseWaitsForCapabilityCleanupBeforeSuccessorActivation() = runTest {
        val events = mutableListOf<String>()
        val cleanupHost = SuspendingCleanupCapabilityHost(events)
        val provider = TestProvider(ChannelImplementationId("test:cleanup-cancellation")).apply {
            requiredCapabilities = setOf(ChannelCapability.TextOutput)
        }
        val runtimes = mutableListOf<RecordingActorRuntime>()
        var predecessorScope: ChannelCapabilityScope? = null
        provider.constructResult = { request ->
            RecordingActorRuntime(
                request.definition,
                request.capabilities,
                events,
                label = if (runtimes.isEmpty()) "G" else "H",
                startupEvent = if (runtimes.isEmpty()) null else "H:startup",
            ).also { runtime ->
                if (runtimes.isEmpty()) predecessorScope = request.capabilities
                runtimes += runtime
            }.let(ChannelRuntimeConstructionResult::Success)
        }
        val fixture = fixture(provider, capabilityHost = cleanupHost)
        val original = definition("channel", "test:cleanup-cancellation")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
        runCurrent()
        val predecessor = runtimes.single()
        val lease = (predecessorScope!!.acquire(CapabilityKey.TextOutput) as CapabilityAcquisition.Available).lease
        predecessor.prepareResult = { ChannelInputAcceptance.Accepted(RecordingTarget(events)) }
        val committed = accepted(fixture.registry.prepareInput(original.id))

        val successor = original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))
        val stageSuccessor = async {
            fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(successor), successor.id))
        }
        runCurrent()
        assertFalse(stageSuccessor.isCompleted)
        assertEquals(2, runtimes.size)

        val release = async { committed.lease.releaseCommittedTargetLease() }
        runCurrent()
        assertTrue(cleanupHost.cleanupStarted.isCompleted)
        release.cancel()
        runCurrent()
        assertFalse(stageSuccessor.isCompleted)
        assertEquals(1, cleanupHost.cleanupCount.get())
        assertEquals(0, events.count { it == "H:startup" })

        cleanupHost.cleanupMayFinish.complete(Unit)
        stageSuccessor.await()
        runCurrent()

        assertTrue(release.isCancelled)
        assertEquals(1, cleanupHost.cleanupCount.get())
        assertEquals(1, predecessor.closeCount.get())
        assertEquals(1, events.count { it == "H:startup" })
        assertTrue(events.indexOf("cleanup-completed") < events.indexOf("H:startup"))
        assertEquals(
            CapabilityOperationResult.Closed,
            lease.use { port ->
                port.sendText(TextOutputRequest("late-effect", TextOutputProfile("test")))
                CapabilityOperationResult.Success(Unit)
            },
        )
        assertEquals(0, cleanupHost.effects.count { it == "late-effect" })
    }
    @Test
    fun rapidSuccessorReplacementPreservesOldestPredecessorBarrier() = runTest {
        val events = mutableListOf<String>()
        val capabilityHost = RecordingTextCapabilityHost(events)
        val provider = TestProvider(ChannelImplementationId("test:actor")).apply {
            requiredCapabilities = setOf(ChannelCapability.TextOutput)
        }
        val runtimes = mutableListOf<RecordingActorRuntime>()
        val terminalMayComplete = CompletableDeferred<Unit>()
        val hStartupAttempted = CompletableDeferred<Unit>()
        val hStartupMayComplete = CompletableDeferred<Unit>()
        provider.constructResult = { request ->
            when (runtimes.size) {
                0 -> RecordingActorRuntime(
                    request.definition, request.capabilities, events, label = "G",
                ).also(runtimes::add).let(ChannelRuntimeConstructionResult::Success)
                1 -> RecordingActorRuntime(
                    request.definition, request.capabilities, events,
                    label = "H", startupEvent = "H:startup", readinessEvent = "H:readiness",
                ).also { runtime ->
                    runtime.activationResult = {
                        hStartupAttempted.complete(Unit)
                        hStartupMayComplete.await()
                        ChannelActivationResult.Ready
                    }
                    runtimes += runtime
                }.let(ChannelRuntimeConstructionResult::Success)
                else -> RecordingActorRuntime(
                    request.definition, request.capabilities, events,
                    label = "I",
                    startupEvent = "I:startup",
                    preparationEvent = "I:prepare",
                    readinessEvent = "I:readiness",
                ).also(runtimes::add).let(ChannelRuntimeConstructionResult::Success)
            }
        }
        val fixture = fixture(provider, capabilityHost = capabilityHost)
        val original = definition("channel", "test:actor")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
        runCurrent()
        val predecessor = runtimes.single()
        val predecessorGeneration = predecessor.scopeIdentity.runtimeGeneration.value
        predecessor.prepareResult = {
            ChannelInputAcceptance.Accepted(object : ChannelInputTarget {
                override fun onInputStarted(session: ChannelAudioInputSession) = Unit

                override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                    events += "G:terminal-started"
                    terminalMayComplete.await()
                    events += "G:terminal-completed"
                    return ChannelInputResult.None
                }

                override fun onInputCancelled(reason: String) = Unit
                override fun onInputFailed(reason: String) = Unit
            })
        }
        assertEquals(
            CapabilityOperationResult.Success(Unit),
            predecessor.emitText("before-replacement"),
        )
        val committed = accepted(fixture.registry.prepareInput(original.id))
        val target = committed.target as ChannelInputTarget
        val terminal = async {
            target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000))
        }
        runCurrent()
        assertEquals(
            listOf("effect:channel:$predecessorGeneration:before-replacement", "G:terminal-started"),
            events,
        )

        // Stage H — installConstructedRuntime blocks on G.closed.
        val stageH = async {
            fixture.registry.reconcile(
                ChannelCatalogueSnapshot(
                    listOf(original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))),
                    original.id,
                ),
            )
        }
        runCurrent()
        assertFalse(stageH.isCompleted)
        assertEquals(2, runtimes.size)
        val staged = runtimes[1]
        val stagedGeneration = staged.scopeIdentity.runtimeGeneration.value
        // Replace H with I before G closes — I must inherit G.closed barrier, not H.closed.
        val stageI = async {
            fixture.registry.reconcile(
                ChannelCatalogueSnapshot(
                    listOf(original.copy(configPayload = opaque("{\"version\":2,\"revision\":3}"))),
                    original.id,
                ),
            )
        }
        runCurrent()
        assertFalse(stageI.isCompleted)
        assertEquals(3, runtimes.size)
        val pending = runtimes[2]

        // While G lives: H never started, I unauthorized/unstarted/unready, zero effects.
        assertEquals(
            listOf("effect:channel:$predecessorGeneration:before-replacement", "G:terminal-started"),
            events,
        )
        assertEquals(
            ChannelInputAcceptance.Unavailable(ChannelPreparationReason.ProviderInitialising.message),
            fixture.registry.prepareInput(original.id),
        )
        assertEquals(CapabilityOperationResult.Closed, staged.emitText("must-not-run-while-G-lives"))
        assertEquals(CapabilityOperationResult.Closed, pending.emitText("must-not-run-while-G-lives"))
        assertEquals(
            listOf("effect:channel:$predecessorGeneration:before-replacement", "G:terminal-started"),
            events,
        )
        assertFalse(hStartupAttempted.isCompleted)
        assertEquals(0, events.count { it == "H:startup" })
        assertEquals(0, events.count { it.startsWith("effect:channel:$stagedGeneration:") })

        // Release G's terminal callback and committed lease.
        // Complete G only after H has been constructed, parked on G.closed, and retired by I.
        terminalMayComplete.complete(Unit)
        assertEquals(ChannelInputResult.None, terminal.await())
        committed.lease.releaseCommittedTargetLease()
        runCurrent()
        val hStartedAfterBarrier = hStartupAttempted.isCompleted
        hStartupMayComplete.complete(Unit)
        assertFalse("retired H must not reach activate after G.closed", hStartedAfterBarrier)
        stageH.await()
        stageI.await()
        runCurrent()

        // After G closes: retired H closes exactly once without activation; I promotes, starts, and becomes ready.
        val successor = runtimes.last()
        val successorGeneration = successor.scopeIdentity.runtimeGeneration.value
        assertNotEquals(predecessor.scopeIdentity, successor.scopeIdentity)
        assertEquals(1, predecessor.closeCount.get())
        assertEquals(1, staged.closeCount.get())
        assertEquals(CapabilityOperationResult.Closed, staged.emitText("retired-H-must-stay-unauthorized"))
        assertEquals(0, events.count { it == "H:startup" })
        assertEquals(0, events.count { it.startsWith("effect:channel:$stagedGeneration:") })

        successor.prepareResult = { ChannelInputAcceptance.Refused("successor admitted") }
        assertEquals(
            ChannelInputAcceptance.Refused("successor admitted"),
            fixture.registry.prepareInput(original.id),
        )
        assertEquals(CapabilityOperationResult.Success(Unit), successor.emitText("successor-effect"))
        fixture.registry.refreshReadiness()
        runCurrent()

        assertEquals(
            listOf(
                "effect:channel:$predecessorGeneration:before-replacement",
                "G:terminal-started",
                "G:terminal-completed",
                "effects-revoked:channel:$predecessorGeneration:REVOKED",
                "G:descendants-drained",
                "G:closed",
                "H:descendants-drained",
                "H:closed",
                "I:startup",
                "I:readiness",
                "I:prepare",
                "effect:channel:$successorGeneration:successor-effect",
                "I:readiness",
            ),
            events,
        )
        assertEquals(
            "I must start only after G closes and retired H closes its detached runtime.",
            events.indexOf("G:closed") + 3,
            events.indexOf("I:startup"),
        )
        assertEquals(0, pending.closeCount.get())
        assertEquals(pending.scopeIdentity, fixture.registry.capabilityScopeIdentity(original.id))
    }

    @Test
    fun failedSuccessorStartupClosesAndNeverPublishesTheStagedGeneration() = runTest {
        val events = mutableListOf<String>()
        val provider = TestProvider(ChannelImplementationId("test:actor")).apply {
            requiredCapabilities = setOf(ChannelCapability.TextOutput)
        }
        val runtimes = mutableListOf<RecordingActorRuntime>()
        provider.constructResult = { request ->
            val isSuccessor = runtimes.isNotEmpty()
            RecordingActorRuntime(
                request.definition,
                request.capabilities,
                events,
                label = if (isSuccessor) "H" else "G",
                startupEvent = if (isSuccessor) "H:startup" else null,
                readinessEvent = if (isSuccessor) "H:readiness" else null,
            ).also { runtime ->
                if (isSuccessor) {
                    runtime.activationResult = {
                        ChannelActivationResult.Failed("successor startup rejected")
                    }
                }
                runtimes += runtime
            }.let(ChannelRuntimeConstructionResult::Success)
        }
        val fixture = fixture(provider, capabilityHost = RecordingTextCapabilityHost(events))
        val original = definition("channel", "test:actor")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
        runCurrent()

        val replacement = original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(replacement), replacement.id))
        runCurrent()
        fixture.registry.refreshReadiness()
        runCurrent()

        val successor = runtimes.last()
        assertEquals(
            ChannelPreparationAvailability.Unavailable(
                ChannelPreparationReason.RuntimeFailed("successor startup rejected"),
            ),
            fixture.registry.preparation(replacement.id),
        )
        assertEquals(
            ChannelInputAcceptance.Unavailable("successor startup rejected"),
            fixture.registry.prepareInput(replacement.id),
        )
        assertEquals(CapabilityOperationResult.Closed, successor.emitText("must-not-survive-failed-startup"))
        assertEquals(1, successor.closeCount.get())
        assertEquals(
            listOf(
                "G:descendants-drained",
                "G:closed",
                "H:startup",
                "H:descendants-drained",
                "H:closed",
            ),
            events,
        )
    }

    @Test
    fun shutdownStopsPreparationDrainsEveryCommittedRuntimeAndClosesEachOnce() = runTest {
        val events = mutableListOf<String>()
        val provider = TestProvider(ChannelImplementationId("test:actor"))
        val committedInputs = mutableListOf<CommittedInput>()
        val fixture = fixture(
            provider,
            onPttSessionCancelRequested = {
                events += "shutdown-terminal-requested"
                committedInputs.forEach { input ->
                    input.target.onInputCancelled("service stopping")
                    input.lease.releaseCommittedTargetLease()
                }
            },
        )
        val firstDefinition = definition("first", "test:actor")
        val secondDefinition = definition("second", "test:actor")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(firstDefinition, secondDefinition), firstDefinition.id))
        runCurrent()
        val first = provider.runtimes.first()
        val second = provider.runtimes.last()
        first.onClose = { events += "first:closed" }
        second.onClose = { events += "second:closed" }
        first.prepareResult = { ChannelInputAcceptance.Accepted(RecordingTarget(events, "first:")) }
        second.prepareResult = { ChannelInputAcceptance.Accepted(RecordingTarget(events, "second:")) }
        committedInputs += accepted(fixture.registry.prepareInput(firstDefinition.id))
        committedInputs += accepted(fixture.registry.prepareInput(secondDefinition.id))

        assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, fixture.registry.shutdownAndAwait())
        assertEquals(
            ChannelInputAcceptance.Unavailable(ChannelPreparationReason.RegistryShutDown.message),
            fixture.registry.prepareInput(firstDefinition.id),
        )
        assertEquals(
            listOf(
                "shutdown-terminal-requested",
                "cancelled:first:service stopping",
                "first:closed",
                "cancelled:second:service stopping",
                "second:closed",
            ),
            events,
        )
        assertEquals(1, first.closeCount.get())
        assertEquals(1, second.closeCount.get())
        assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, fixture.registry.shutdownAndAwait())
        assertEquals(1, first.closeCount.get())
        assertEquals(1, second.closeCount.get())
    }

    @Test
    fun selectionRoutesPttToTheActiveRuntimeWithoutCancellingThirdActorBackgroundWork() = runTest {
        val events = mutableListOf<String>()
        val provider = TestProvider(ChannelImplementationId("test:actor"))
        val runtimes = mutableListOf<RecordingActorRuntime>()
        provider.constructResult = { request ->
            RecordingActorRuntime(
                definition = request.definition,
                capabilityScope = request.capabilities,
                events = events,
                label = request.definition.id,
            ).also(runtimes::add).let(ChannelRuntimeConstructionResult::Success)
        }
        val fixture = fixture(provider)
        val firstDefinition = definition("first", "test:actor")
        val secondDefinition = definition("second", "test:actor")
        val thirdDefinition = definition("third", "test:actor")
        fixture.registry.reconcile(
            ChannelCatalogueSnapshot(listOf(firstDefinition, secondDefinition, thirdDefinition), firstDefinition.id),
        )
        runCurrent()
        val first = runtimes.first { it.id == firstDefinition.id }
        val second = runtimes.first { it.id == secondDefinition.id }
        val third = runtimes.first { it.id == thirdDefinition.id }
        val backgroundMayFinish = CompletableDeferred<Unit>()
        val background = async { third.runBackground(backgroundMayFinish) }
        first.prepareResult = {
            events += "first:ptt"
            ChannelInputAcceptance.Refused("first handled ptt")
        }
        second.prepareResult = {
            events += "second:ptt"
            ChannelInputAcceptance.Refused("second handled ptt")
        }

        assertEquals(ChannelInputAcceptance.Refused("first handled ptt"), fixture.registry.prepareInput(firstDefinition.id))
        fixture.registry.reconcile(
            ChannelCatalogueSnapshot(listOf(firstDefinition, secondDefinition, thirdDefinition), secondDefinition.id),
        )
        backgroundMayFinish.complete(Unit)
        runCurrent()
        background.await()
        assertEquals(ChannelInputAcceptance.Refused("second handled ptt"), fixture.registry.prepareInput(secondDefinition.id))

        assertEquals(
            listOf("third:background-started", "first:ptt", "third:background-finished", "second:ptt"),
            events,
        )
        assertEquals(0, third.closeCount.get())
    }

    @Test
    fun restartCreatesAFreshGenerationAndAdmitsOnlyExplicitDurableRecovery() = runTest {
        val events = mutableListOf<String>()
        val capabilityHost = RecordingTextCapabilityHost(events)
        val provider = TestProvider(ChannelImplementationId("test:actor")).apply {
            requiredCapabilities = setOf(ChannelCapability.TextOutput)
        }
        val runtimes = mutableListOf<RecordingActorRuntime>()
        provider.constructResult = { request ->
            RecordingActorRuntime(request.definition, request.capabilities, events).also(runtimes::add)
                .let(ChannelRuntimeConstructionResult::Success)
        }
        val definition = definition("channel", "test:actor")
        val beforeRestart = fixture(provider, capabilityHost = capabilityHost)
        beforeRestart.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()
        val predecessor = runtimes.single()
        val predecessorGeneration = predecessor.scopeIdentity.runtimeGeneration.value
        assertEquals(CapabilityOperationResult.Success(Unit), predecessor.emitText("durable-recorded-before-restart"))
        assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, beforeRestart.registry.shutdownAndAwait())

        val afterRestart = fixture(provider, capabilityHost = capabilityHost)
        afterRestart.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()
        val successor = runtimes.last()
        val successorGeneration = successor.scopeIdentity.runtimeGeneration.value

        assertNotEquals(predecessor.scopeIdentity, successor.scopeIdentity)
        assertEquals(CapabilityOperationResult.Closed, predecessor.emitText("late-volatile-completion"))
        assertEquals(CapabilityOperationResult.Success(Unit), successor.recoverDurable("durable-record"))
        assertEquals(
            listOf(
                "effect:channel:$predecessorGeneration:durable-recorded-before-restart",
                "effects-revoked:channel:$predecessorGeneration:REVOKED",
                "G:descendants-drained",
                "G:closed",
                "effect:channel:$successorGeneration:durable-record",
            ),
            events,
        )
    }


    @Test
    fun capabilityScopeIdentityMatchesTheGenerationObservedByCapabilityEffects() = runTest {
        val events = mutableListOf<String>()
        val capabilityHost = RecordingTextCapabilityHost(events)
        val provider = TestProvider(ChannelImplementationId("test:actor")).apply {
            requiredCapabilities = setOf(ChannelCapability.TextOutput)
        }
        val fixture = fixture(provider, capabilityHost = capabilityHost)
        val definition = definition("channel", "test:actor")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        val exposed = fixture.registry.capabilityScopeIdentity(definition.id)
        assertTrue("exposed scope must be non-null for live entry", exposed != null)
        assertEquals("channel", exposed!!.channelInstanceId)

        // Observe the generation via a capability effect.
        val acquisition = capabilityHost.acquire(exposed, CapabilityKey.TextOutput)
        val port = (acquisition as HostedCapabilityAcquisition.Available).port
        port.sendText(TextOutputRequest("probe", TextOutputProfile("test")))
        val effectLine = events.last()
        // Effect line format: "effect:channel:$generation:$text"
        val observedGeneration = effectLine.split(":")[2].toLong()
        assertEquals("exposed generation must match effect-observed generation", exposed.runtimeGeneration.value, observedGeneration)

        fixture.registry.shutdownAndAwait()
    }

    @Test
    fun capabilityScopeIdentityIsNullForUnavailableOrMissingEntries() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:actor"))
        val fixture = fixture(provider)
        val missing = definition("missing", "test:unregistered")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(missing), missing.id))
        runCurrent()

        assertTrue("capabilityScopeIdentity must be null for missing provider", fixture.registry.capabilityScopeIdentity("missing") == null)

        fixture.registry.shutdownAndAwait()
    }

    @Test
    fun `two same provider instances both become available after reconciliation`() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:shared"))
        val fixture = fixture(provider)
        val first = definition("first", "test:shared")
        val second = definition("second", "test:shared")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(first, second), first.id))
        runCurrent()

        assertEquals(2, provider.runtimes.size)
        val snapshots = fixture.registry.getAllRuntimeSnapshots()
        assertEquals(first.id, snapshots[0].id)
        assertEquals(second.id, snapshots[1].id)
        assertEquals(ChannelPreparationAvailability.Available, snapshots[0].preparation)
        assertEquals(ChannelPreparationAvailability.Available, snapshots[1].preparation)
        assertEquals(0, provider.runtimes[0].closeCount.get())
        assertEquals(0, provider.runtimes[1].closeCount.get())

        fixture.registry.shutdownAndAwait()
        assertEquals(1, provider.runtimes[0].closeCount.get())
        assertEquals(1, provider.runtimes[1].closeCount.get())
    }
    @Test
    fun `reconciliation revokes removed generation and preserves reinstalled successor and sibling`() = runTest {
        val backend = RegistryGatedAudio()
        val quota = DeferredAudioPlaybackCoordinator.ProcessQuota(Int.MAX_VALUE, Long.MAX_VALUE)
        val deferred = DeferredAudioPlaybackCoordinator(
            scope = this,
            selectedChannel = { "alpha" },
            operationIsCurrent = { true },
            audio = backend,
            processQuota = quota,
        )
        val host = RegistryDeferredCapabilityHost(deferred)
        val artifacts = mutableListOf<AudioOperationArtifact>()
        val provider = TestProvider(ChannelImplementationId("test:deferred")).apply {
            requiredCapabilities = setOf(ChannelCapability.DeferredAudioPlayback)
            constructResult = { request ->
                val identity = (request.generationContext as ActorRuntimeHostContext).actorIdentity
                val acquisition = request.capabilities.acquire(CapabilityKey.DeferredAudioPlayback)
                val lease = (acquisition as? CapabilityAcquisition.Available)?.lease
                    ?: error("deferred capability must be available")
                val artifact = AudioOperationArtifact(
                    RecordedPcm(shortArrayOf(1), 16_000),
                    operationId = "${identity.channelInstanceId}-${identity.runtimeGeneration.value}",
                    generation = identity.runtimeGeneration,
                )
                artifacts += artifact
                val context = AgentOperationContext(
                    scope = identity,
                    runId = AgentRunId("run-${identity.channelInstanceId}-${identity.runtimeGeneration.value}"),
                    operationId = AgentOperationId("op-${identity.channelInstanceId}-${identity.runtimeGeneration.value}"),
                )
                lease.use { capability ->
                    CapabilityOperationResult.Success(capability.scheduleAudio(context, artifact))
                }
                val runtime = TestRuntime(request.definition)
                runtimes += runtime
                ChannelRuntimeConstructionResult.Success(runtime)
            }
        }
        val fixture = fixture(provider, capabilityHost = host)
        val alpha = definition("alpha", "test:deferred")
        val sibling = definition("sibling", "test:deferred")

        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(alpha, sibling), alpha.id))
        runCurrent()
        backend.started.await()
        val predecessor = artifacts.first { it.operationId.startsWith("alpha-") }
        val siblingArtifact = artifacts.first { it.operationId.startsWith("sibling-") }

        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(sibling), sibling.id))
        runCurrent()
        assertTrue("removal must revoke predecessor generation", predecessor.isDisposed)
        assertFalse("removal of alpha must preserve sibling", siblingArtifact.isDisposed)
        assertEquals(1, deferred.accounting().liveEntries)

        // The late completion belongs to the retired predecessor and must not consume sibling state.
        backend.complete(DelayedPlaybackAudioResult.Busy)
        runCurrent()

        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(alpha, sibling), alpha.id))
        runCurrent()
        assertEquals("reinstall must construct exactly one successor", 3, artifacts.size)
        val successor = artifacts.last { it.operationId.startsWith("alpha-") }
        assertFalse("reinstalled generation must retain its queued artifact", successor.isDisposed)
        assertFalse("sibling generation must remain operable", siblingArtifact.isDisposed)
        assertEquals(2, deferred.accounting().liveEntries)

        fixture.registry.shutdownAndAwait()
        assertTrue("service/registry shutdown must revoke sibling generation", siblingArtifact.isDisposed)
        assertEquals(0, quota.liveEntries())
    }

    @Test
    fun `reconcileResourceBinding forces one fresh generation for a single unchanged instance`() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:resource"))
        val fixture = fixture(provider)
        val definition = definition("resource-channel", "test:resource")
        val snapshot = ChannelCatalogueSnapshot(listOf(definition), definition.id)

        fixture.registry.reconcile(snapshot)
        runCurrent()
        assertEquals(1, provider.runtimes.size)
        val firstGeneration = fixture.registry.capabilityScopeIdentity(definition.id)
        assertTrue(firstGeneration != null)

        // The catalogue definition is unchanged; a resource binding replacement must
        // still spawn exactly one fresh generation and revoke the predecessor.
        fixture.registry.reconcileResourceBinding(snapshot, definition.id)
        runCurrent()

        assertEquals(2, provider.runtimes.size)
        assertEquals(1, provider.runtimes[0].closeCount.get())
        assertEquals(0, provider.runtimes[1].closeCount.get())
        val secondGeneration = fixture.registry.capabilityScopeIdentity(definition.id)
        assertTrue(secondGeneration != null)
        assertNotEquals(firstGeneration!!.runtimeGeneration, secondGeneration!!.runtimeGeneration)

        fixture.registry.shutdownAndAwait()
    }

    @Test
    fun `reconcileResourceBinding is a no-op for unknown or disabled instances`() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:resource"))
        val fixture = fixture(provider)
        val definition = definition("resource-channel", "test:resource")
        val snapshot = ChannelCatalogueSnapshot(listOf(definition), definition.id)

        fixture.registry.reconcile(snapshot)
        runCurrent()
        assertEquals(1, provider.runtimes.size)

        // Unknown instance: no construction.
        fixture.registry.reconcileResourceBinding(snapshot, "does-not-exist")
        runCurrent()
        assertEquals(1, provider.runtimes.size)

        // Disabled instance: no construction.
        val disabled = ChannelCatalogueSnapshot(listOf(definition.copy(enabled = false)), definition.id)
        fixture.registry.reconcileResourceBinding(disabled, definition.id)
        runCurrent()
        assertEquals(1, provider.runtimes.size)

        fixture.registry.shutdownAndAwait()
    }

    private fun TestScope.fixture(
        vararg providers: TestProvider,
        callbackTimeoutMillis: Long = 1_000,
        inputReleasedTimeoutMillis: Long = 1_000,
        closeTimeoutMillis: Long = 1_000,
        shutdownAwaitMillis: Long = 1_000,
        capabilityHost: ChannelCapabilityHost = NoCapabilities,
        onPttSessionCancelRequested: suspend () -> Unit = {},
    ): RegistryFixture {
        val providerRegistry = ChannelImplementationProviderRegistry()
        providers.forEach { provider -> providerRegistry.register(provider) }
        val worker = RuntimeWorkerDispatcher.fromDispatcher(StandardTestDispatcher(testScheduler))
        val invocationBoundary = RuntimeInvocationBoundary(
            worker,
            RuntimeInvocationPolicy(
                perGenerationQueueCapacity = 16,
                callbackTimeoutMillis = callbackTimeoutMillis,
                inputReleasedTimeoutMillis = inputReleasedTimeoutMillis,
                closeTimeoutMillis = closeTimeoutMillis,
            ),
        )
        return RegistryFixture(
            registry = ChannelRuntimeRegistry(
                providers = providerRegistry,
                capabilityHost = capabilityHost,
                invocationBoundary = invocationBoundary,
                runtimeScope = backgroundScope,
                closeScope = backgroundScope,
                onPttSessionCancelRequested = onPttSessionCancelRequested,
                shutdownAwaitMillis = shutdownAwaitMillis,
            ),
        )
    }

    private fun definition(
        id: String,
        implementationId: String,
        version: Int = 2,
        payload: String = "{\"version\":2}",
    ): ChannelDefinition = ChannelDefinition(
        id = id,
        name = "Name for $id",
        implementationId = ChannelImplementationId(implementationId),
        enabled = true,
        configSchemaVersion = version,
        configPayload = opaque(payload),
    )

    private fun opaque(value: String): OpaqueJsonObject = OpaqueJsonObject.parse(value).getOrThrow()

    private fun accepted(acceptance: ChannelInputAcceptance): CommittedInput {
        val target = (acceptance as? ChannelInputAcceptance.Accepted)?.target
            ?: throw AssertionError("Expected an accepted leased target, got $acceptance")
        val lease = target as? CommittedTargetLeaseOwner
            ?: throw AssertionError("Accepted target did not carry a committed lease")
        return CommittedInput(target, lease)
    }

    private fun providerError(snapshot: ChannelRuntimeSnapshot): ChannelProviderError {
        val unavailable = snapshot.preparation as? ChannelPreparationAvailability.Unavailable
            ?: throw AssertionError("Expected unavailable provider entry, got ${snapshot.preparation}")
        return when (val reason = unavailable.reason) {
            is ChannelPreparationReason.Provider -> reason.error
            is ChannelPreparationReason.ConfigurationIncompatible -> reason.error
            else -> throw AssertionError("Expected provider or configuration incompatible reason, got $reason")
        }
    }

    private data class RegistryFixture(val registry: ChannelRuntimeRegistry)
    private data class CommittedInput(
        val target: ChannelInputTarget,
        val lease: CommittedTargetLeaseOwner,
    )

    private enum class ConfigurationMode {
        VALID,
        UNSUPPORTED,
        MIGRATION_FAILURE,
        INVALID,
    }

    private class TestProvider(
        private val implementationId: ChannelImplementationId,
    ) : ChannelImplementationProvider {
        override val fingerprint = ProviderRevisionFingerprint("test-fingerprint")
        val configuration = TestConfigurationProvider(implementationId)
        val runtimes = mutableListOf<TestRuntime>()
        var requiredCapabilities: Set<ChannelCapability> = emptySet()
        var constructResult: suspend (ChannelRuntimeConstructionRequest) -> ChannelRuntimeConstructionResult = { request ->
            val runtime = TestRuntime(request.definition)
            runtimes += runtime
            ChannelRuntimeConstructionResult.Success(runtime)
        }

        override val descriptor: ChannelImplementationDescriptor
            get() = ChannelImplementationDescriptor(
                implementationId = implementationId,
                presentation = ChannelPresentationMetadata("Test", "test summary", "test unavailable"),
                configuration = configuration,
                configurationFields = listOf(ChannelConfigurationField.TextField("value", "Value")),
                requiredCapabilities = requiredCapabilities,
                preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
            )

        override suspend fun constructRuntime(
            request: ChannelRuntimeConstructionRequest,
        ): ChannelRuntimeConstructionResult {
            val result = constructResult(request)
            if (result is ChannelRuntimeConstructionResult.Success && result.runtime is TestRuntime) {
                if (result.runtime !in runtimes) runtimes += result.runtime
            }
            return result
        }
    }

    private class TestConfigurationProvider(
        override val implementationId: ChannelImplementationId,
    ) : ChannelConfigurationProvider {
        private fun opaque(value: String): OpaqueJsonObject = OpaqueJsonObject.parse(value).getOrThrow()
        var mode = ConfigurationMode.VALID
        override val currentSchemaVersion: Int = 2

        override fun defaultPayload(): OpaqueJsonObject = opaque("{\"version\":2}")

        override fun validate(
            schemaVersion: Int,
            payload: OpaqueJsonObject,
        ): ProviderConfigurationResult = validated(schemaVersion, payload)

        override fun migrateStep(
            fromSchemaVersion: Int,
            payload: OpaqueJsonObject,
        ): ChannelConfigurationMigrationStep = ChannelConfigurationMigrationStep.Success(
            opaque("{\"version\":${fromSchemaVersion + 1}}"),
        )

        override fun migrateAndValidate(
            schemaVersion: Int,
            payload: OpaqueJsonObject,
        ): ProviderConfigurationResult = when (mode) {
            ConfigurationMode.UNSUPPORTED -> ProviderConfigurationResult.Failure(
                ChannelProviderError.UnsupportedSchemaVersion(implementationId, schemaVersion, currentSchemaVersion),
            )
            ConfigurationMode.MIGRATION_FAILURE -> ProviderConfigurationResult.Failure(
                ChannelProviderError.MigrationFailed(implementationId, schemaVersion, "migration rejected"),
            )
            ConfigurationMode.INVALID -> ProviderConfigurationResult.Failure(
                ChannelProviderError.InvalidConfiguration(implementationId, schemaVersion, "value rejected"),
            )
            ConfigurationMode.VALID -> {
                if (schemaVersion < currentSchemaVersion) {
                    validated(currentSchemaVersion, opaque("{\"version\":$currentSchemaVersion}"))
                } else {
                    validated(schemaVersion, payload)
                }
            }
        }

        private fun validated(
            schemaVersion: Int,
            payload: OpaqueJsonObject,
        ): ProviderConfigurationResult = ProviderConfigurationResult.Success(
            ValidatedChannelConfiguration(implementationId, schemaVersion, payload),
        )
    }

    private open class TestRuntime(
        private val definition: ChannelDefinition,
    ) : ChannelRuntime {
        override val id: String = definition.id
        private val snapshots = MutableStateFlow(
            ChannelRuntimeSnapshot(
                id = definition.id,
                name = definition.name,
                implementationId = definition.implementationId,
                enabled = definition.enabled,
                preparation = ChannelPreparationAvailability.Available,
                executionStatus = ChannelExecutionStatus.IDLE,
            ),
        )
        override val snapshot: StateFlow<ChannelRuntimeSnapshot> = snapshots.asStateFlow()
        val closeCount = AtomicInteger(0)
        var prepareResult: suspend () -> ChannelInputAcceptance = {
            ChannelInputAcceptance.Refused("no test input configured")
        }
        var onClose: suspend () -> Unit = {}

        open override suspend fun prepareInput(): ChannelInputAcceptance = prepareResult()

        open override suspend fun close() {
            closeCount.incrementAndGet()
            onClose()
        }

        fun publish(
            preparation: ChannelPreparationAvailability,
            status: ChannelExecutionStatus,
        ) {
            snapshots.value = snapshots.value.copy(preparation = preparation, executionStatus = status)
        }
    }

    private class BlockingDetachedRuntime(
        definition: ChannelDefinition,
        private val events: MutableList<String>,
    ) : TestRuntime(definition) {
        val activationStarted = CompletableDeferred<Unit>()
        val activationMayFinish = CompletableDeferred<Unit>()
        val closeStarted = CompletableDeferred<Unit>()
        val closeMayFinish = CompletableDeferred<Unit>()
        val closeCompleted = CompletableDeferred<Unit>()
        private val concurrentCloseBodies = AtomicInteger(0)
        val maximumConcurrentCloseBodies = AtomicInteger(0)

        override suspend fun activate(): ChannelActivationResult {
            events += "H:activation-started"
            activationStarted.complete(Unit)
            activationMayFinish.await()
            return ChannelActivationResult.Ready
        }

        override suspend fun close() {
            val concurrent = concurrentCloseBodies.incrementAndGet()
            maximumConcurrentCloseBodies.updateAndGet { maxOf(it, concurrent) }
            events += "H:close-started"
            closeStarted.complete(Unit)
            try {
                super.close()
                withContext(NonCancellable) { closeMayFinish.await() }
                events += "H:close-completed"
                closeCompleted.complete(Unit)
            } finally {
                concurrentCloseBodies.decrementAndGet()
            }
        }
    }

    private class RecordingActorRuntime(
        definition: ChannelDefinition,
        private val capabilityScope: ChannelCapabilityScope,
        private val events: MutableList<String>,
        private val label: String = "G",
        private val startupEvent: String? = null,
        private val preparationEvent: String? = null,
        private val readinessEvent: String? = null,
    ) : TestRuntime(definition) {
        var activationResult: suspend () -> ChannelActivationResult = { ChannelActivationResult.Ready }

        override suspend fun activate(): ChannelActivationResult {
            startupEvent?.let(events::add)
            return activationResult()
        }

        override suspend fun prepareInput(): ChannelInputAcceptance {
            preparationEvent?.let(events::add)
            return super.prepareInput()
        }

        override suspend fun refreshReadiness() {
            readinessEvent?.let(events::add)
        }
        suspend fun emitText(text: String): CapabilityOperationResult<Unit> = when (
            val acquisition = capabilityScope.acquire(CapabilityKey.TextOutput)
        ) {
            is CapabilityAcquisition.Available -> acquisition.lease.use { port ->
                when (port.sendText(TextOutputRequest(text, TextOutputProfile("test")))) {
                    is TextDeliveryOutcome.Delivered -> CapabilityOperationResult.Success(Unit)
                    else -> CapabilityOperationResult.Failed(
                        io.talkcan.channel.capability.CapabilityFailureReason.HOST_FAILURE,
                    )
                }
            }
            CapabilityAcquisition.Closed -> CapabilityOperationResult.Closed
            CapabilityAcquisition.Cancelled -> CapabilityOperationResult.Cancelled
            is CapabilityAcquisition.Unavailable -> CapabilityOperationResult.Unavailable(acquisition.reason)
            is CapabilityAcquisition.Denied -> CapabilityOperationResult.Denied(acquisition.reason)
            is CapabilityAcquisition.Failed -> CapabilityOperationResult.Failed(acquisition.reason)
            is CapabilityAcquisition.Recoverable -> CapabilityOperationResult.Unavailable(acquisition.reason)
        }
        val scopeIdentity: CapabilityScopeIdentity
            get() = capabilityScope.identity

        suspend fun recoverDurable(record: String): CapabilityOperationResult<Unit> = emitText(record)


        suspend fun runBackground(until: CompletableDeferred<Unit>) {
            events += "$id:background-started"
            until.await()
            events += "$id:background-finished"
        }

        override suspend fun close() {
            events += "$label:descendants-drained"
            super.close()
            events += "$label:closed"
        }
    }

    private class RecordingTarget(
        private val events: MutableList<String>,
        private val prefix: String = "",
    ) : ChannelInputTarget {
        override fun onInputStarted(session: ChannelAudioInputSession) = Unit

        override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult = ChannelInputResult.None

        override fun onInputCancelled(reason: String) {
            events += "cancelled:$prefix$reason"
        }

        override fun onInputFailed(reason: String) {
            events += "failed:$prefix$reason"
        }

        override fun onInputPlaybackCompleted() {
            events += "playback-completed"
        }
    }

    private class RecordingTextCapabilityHost(
        private val events: MutableList<String>,
    ) : ChannelCapabilityHost {
        override suspend fun availability(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<*>,
        ): CapabilityAvailability = CapabilityAvailability.Available

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> {
            if (key != CapabilityKey.TextOutput) {
                return HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
            }
            val port = object : TextOutputCapability {
                override suspend fun sendText(request: TextOutputRequest): TextDeliveryOutcome {
                    events += "effect:${identity.channelInstanceId}:${identity.runtimeGeneration.value}:${request.text}"
                    return TextDeliveryOutcome.Delivered("${identity.channelInstanceId}:${request.text}")
                }

                override suspend fun sendKey(
                    request: io.talkcan.channel.capability.TextKeyRequest,
                ): TextDeliveryOutcome = TextDeliveryOutcome.Delivered("unused")
            }
            return HostedCapabilityAcquisition.Available(port as T) { termination ->
                events += "effects-revoked:${identity.channelInstanceId}:${identity.runtimeGeneration.value}:$termination"
            }
        }

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = acquire(identity, key)
    }

    private class SuspendingCleanupCapabilityHost(
        private val events: MutableList<String>,
    ) : ChannelCapabilityHost {
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupMayFinish = CompletableDeferred<Unit>()
        val cleanupCount = AtomicInteger(0)
        val effects = mutableListOf<String>()

        override suspend fun availability(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<*>,
        ): CapabilityAvailability = CapabilityAvailability.Available

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> {
            if (key != CapabilityKey.TextOutput) {
                return HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
            }
            val port = object : TextOutputCapability {
                override suspend fun sendText(request: TextOutputRequest): TextDeliveryOutcome {
                    effects += request.text
                    return TextDeliveryOutcome.Delivered(request.text)
                }

                override suspend fun sendKey(
                    request: io.talkcan.channel.capability.TextKeyRequest,
                ): TextDeliveryOutcome = TextDeliveryOutcome.Delivered("unused")
            }
            return HostedCapabilityAcquisition.Available(port as T) {
                cleanupCount.incrementAndGet()
                cleanupStarted.complete(Unit)
                cleanupMayFinish.await()
                events += "cleanup-completed"
            }
        }

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = acquire(identity, key)
    }

    private class RegistryDeferredCapabilityHost(
        private val coordinator: DeferredAudioPlaybackCoordinator,
    ) : ChannelCapabilityHost {
        override suspend fun availability(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<*>,
        ): CapabilityAvailability = if (key == CapabilityKey.DeferredAudioPlayback) {
            CapabilityAvailability.Available
        } else {
            CapabilityAvailability.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> = if (key == CapabilityKey.DeferredAudioPlayback) {
            HostedCapabilityAcquisition.Available(coordinator) { termination ->
                if (termination == CapabilityLeaseTermination.REVOKED) {
                    coordinator.onGenerationTermination(identity, termination)
                }
            } as HostedCapabilityAcquisition<T>
        } else {
            HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
        }

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = acquire(identity, key)
    }

    private class RegistryGatedAudio : DeferredAudioPlaybackAudioPort {
        val started = CompletableDeferred<Unit>()
        private val completion = CompletableDeferred<DelayedPlaybackAudioResult>()
        private var firstCall = true

        override suspend fun playOperationIfAdmitted(
            channelInstanceId: String,
            audio: OpaqueAudioOperation,
        ): DelayedPlaybackAudioResult {
            if (!firstCall) return DelayedPlaybackAudioResult.Busy
            firstCall = false
            started.complete(Unit)
            return completion.await()
        }

        fun complete(result: DelayedPlaybackAudioResult) {
            completion.complete(result)
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
            io.talkcan.channel.capability.CapabilityUnavailableReason.HOST_NOT_READY,
        )

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = HostedCapabilityAcquisition.Unavailable(
            io.talkcan.channel.capability.CapabilityUnavailableReason.HOST_NOT_READY,
        )
    }
}
