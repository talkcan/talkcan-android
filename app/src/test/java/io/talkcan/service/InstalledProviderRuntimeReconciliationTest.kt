package io.talkcan.service

import io.talkcan.audio.ChannelAudioInputSession
import io.talkcan.audio.ChannelInputAcceptance
import io.talkcan.audio.ChannelInputResult
import io.talkcan.audio.ChannelInputTarget
import io.talkcan.audio.RecordedPcm
import io.talkcan.channel.capability.CapabilityAvailability
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.ChannelCapabilityHost
import io.talkcan.channel.capability.ChannelCapabilityPort
import io.talkcan.channel.capability.HostedCapabilityAcquisition
import io.talkcan.dependency.ArtifactDigest
import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.dependency.InstalledProviderId
import io.talkcan.model.ChannelCatalogueSnapshot
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
import io.talkcan.model.ChannelProviderResolution
import io.talkcan.model.ChannelRuntimeConstructionRequest
import io.talkcan.model.ChannelRuntimeConstructionResult
import io.talkcan.model.GenerationAdmission
import io.talkcan.model.GenerationAdmissionRejection
import io.talkcan.model.GenerationExecutionContext
import io.talkcan.model.InstalledProviderBinding
import io.talkcan.model.InstalledProvidersPublicationResult
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.model.ProviderConfigurationResult
import io.talkcan.model.ProviderRevisionFingerprint
import io.talkcan.model.ValidatedChannelConfiguration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider-snapshot reconciliation coverage. The providers are deliberately small contract fakes:
 * they expose only the public provider/runtime interfaces while the real registry owns generation,
 * replacement, drain, and admission semantics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstalledProviderRuntimeReconciliationTest {
    @Test
    fun `provider addition equal fingerprint publication update rollback and multi instance cutover preserve sibling`() = runTest {
        val providers = ChannelImplementationProviderRegistry()
        val fixture = fixture(providers)
        val aId = installedId("101")
        val bId = installedId("202")
        val aLeft = definition("a-left", aId)
        val aRight = definition("a-right", aId)
        val bOnly = definition("b-only", bId)
        val b = RecordingInstalledProvider("202", digest('b'))

        publish(providers, b.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(aLeft, aRight, bOnly), aLeft.id))
        runCurrent()
        assertTypedMissing(fixture.registry, aLeft.id, aId)
        assertTypedMissing(fixture.registry, aRight.id, aId)
        assertEquals(1, b.runtimes.size)

        val aV1 = RecordingInstalledProvider("101", digest('a'))
        publish(providers, aV1.binding(), b.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(aLeft, aRight, bOnly), aLeft.id))
        runCurrent()

        val aV1Records = aV1.records.toList()
        val bRecord = b.records.single()
        assertEquals(listOf(aV1.fingerprint, aV1.fingerprint), aV1Records.map(ConstructionRecord::fingerprint))
        assertNotEquals(aV1Records[0].generation, aV1Records[1].generation)
        assertEquals(ChannelPreparationAvailability.Available, fixture.registry.preparation(aLeft.id))
        assertEquals(ChannelPreparationAvailability.Available, fixture.registry.preparation(aRight.id))

        aV1.runtimes.first { it.id == aLeft.id }.contaminate("old-global")
        publish(providers, aV1.binding(), b.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(aLeft, aRight, bOnly), aLeft.id))
        runCurrent()

        assertEquals("equal fingerprint must retain the exact actor generation", aV1Records, aV1.records)
        assertEquals(0, aV1.runtimes.sumOf { it.closeCount.get() })
        assertEquals(0, b.runtimes.single().closeCount.get())
        assertEquals(bRecord.generation, b.records.single().generation)
        assertTrue(aV1.runtimes.first { it.id == aLeft.id }.volatileMarkers.contains("old-global"))

        val aV2 = RecordingInstalledProvider("101", digest('c'))
        publish(providers, aV2.binding(), b.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(aLeft, aRight, bOnly), aLeft.id))
        runCurrent()

        assertEquals(listOf(aV2.fingerprint, aV2.fingerprint), aV2.records.map(ConstructionRecord::fingerprint))
        assertEquals(1, aV1.runtimes.first { it.id == aLeft.id }.closeCount.get())
        assertEquals(1, aV1.runtimes.first { it.id == aRight.id }.closeCount.get())
        assertEquals(0, b.runtimes.single().closeCount.get())
        assertTrue(aV2.records.none { it.stateId in aV1Records.map(ConstructionRecord::stateId) })

        val rollback = RecordingInstalledProvider("101", digest('a'))
        publish(providers, rollback.binding(), b.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(aLeft, aRight, bOnly), aLeft.id))
        runCurrent()

        assertEquals(listOf(rollback.fingerprint, rollback.fingerprint), rollback.records.map(ConstructionRecord::fingerprint))
        assertEquals(1, aV2.runtimes.first { it.id == aLeft.id }.closeCount.get())
        assertEquals(1, aV2.runtimes.first { it.id == aRight.id }.closeCount.get())
        assertEquals(0, b.runtimes.single().closeCount.get())
        assertEquals(bRecord.generation, b.records.single().generation)
        shutdown(fixture.registry)
    }

    @Test
    fun `provider revision cutover drains committed terminal callback and closes predecessor before successor starts`() = runTest {
        val providers = ChannelImplementationProviderRegistry()
        val fixture = fixture(providers)
        val id = installedId("303")
        val definition = definition("drained", id)
        val events = mutableListOf<String>()
        val v1 = RecordingInstalledProvider("303", digest('d'), events)
        publish(providers, v1.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        val predecessor = v1.runtimes.single()
        val terminalMayFinish = CompletableDeferred<Unit>()
        predecessor.prepare = {
            ChannelInputAcceptance.Accepted(object : ChannelInputTarget {
                override fun onInputStarted(session: ChannelAudioInputSession) = Unit

                override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                    events += "G:terminal-started"
                    terminalMayFinish.await()
                    events += "G:terminal-finished"
                    return ChannelInputResult.None
                }

                override fun onInputCancelled(reason: String) = Unit
                override fun onInputFailed(reason: String) = Unit
            })
        }
        val committed = committed(fixture.registry.prepareInput(definition.id))
        val terminal = async { committed.target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }
        runCurrent()
        assertEquals(listOf("G:${predecessor.stateId}:activate", "G:terminal-started"), events)

        val v2 = RecordingInstalledProvider("303", digest('e'), events)
        publish(providers, v2.binding())
        val cutover = async { fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id)) }
        runCurrent()
        assertEquals(1, v2.runtimes.size)
        assertFalse(cutover.isCompleted)
        assertEquals(0, predecessor.closeCount.get())
        assertEquals(0, events.count { it.startsWith("H:") })

        terminalMayFinish.complete(Unit)
        assertEquals(ChannelInputResult.None, terminal.await())
        committed.lease.releaseCommittedTargetLease()
        cutover.await()
        runCurrent()

        val successor = v2.runtimes.single()
        assertEquals(
            listOf(
                "G:${predecessor.stateId}:activate",
                "G:terminal-started",
                "G:terminal-finished",
                "G:${predecessor.stateId}:close",
                "H:${successor.stateId}:activate",
            ),
            events,
        )
        assertEquals(1, predecessor.closeCount.get())
        assertEquals(ChannelPreparationAvailability.Available, fixture.registry.preparation(definition.id))
        shutdown(fixture.registry)
    }

    @Test
    fun `configuration payload change drains predecessor before successor activation`() = runTest {
        val providers = ChannelImplementationProviderRegistry()
        val fixture = fixture(providers)
        val id = installedId("313")
        val initialDefinition = definition("config-change", id)
        val events = mutableListOf<String>()
        val provider = RecordingInstalledProvider("313", digest('c'), events)
        publish(providers, provider.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(initialDefinition), initialDefinition.id))
        runCurrent()

        val predecessor = provider.runtimes.single()
        val terminalMayFinish = CompletableDeferred<Unit>()
        predecessor.prepare = {
            ChannelInputAcceptance.Accepted(object : ChannelInputTarget {
                override fun onInputStarted(session: ChannelAudioInputSession) = Unit

                override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                    events += "G:terminal-started"
                    terminalMayFinish.await()
                    events += "G:terminal-finished"
                    return ChannelInputResult.None
                }

                override fun onInputCancelled(reason: String) = Unit
                override fun onInputFailed(reason: String) = Unit
            })
        }
        val committed = committed(fixture.registry.prepareInput(initialDefinition.id))
        val terminal = async { committed.target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }
        runCurrent()
        assertEquals(listOf("G:${predecessor.stateId}:activate", "G:terminal-started"), events)

        // Same provider, same fingerprint, different config payload.
        val replacedDefinition = initialDefinition.copy(
            configPayload = OpaqueJsonObject.fromJsonObject(JSONObject().put("revision", 2)),
        )
        val cutover = async {
            fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(replacedDefinition), replacedDefinition.id))
        }
        runCurrent()
        assertEquals(2, provider.runtimes.size)
        val successor = provider.runtimes.last()
        assertNotEquals(predecessor.generation, successor.generation)
        assertFalse(cutover.isCompleted)
        assertEquals(0, predecessor.closeCount.get())
        assertEquals(0, events.count { it.startsWith("H:") })
        assertEquals(
            listOf("G:${predecessor.stateId}:activate", "G:terminal-started"),
            events,
        )

        terminalMayFinish.complete(Unit)
        assertEquals(ChannelInputResult.None, terminal.await())
        committed.lease.releaseCommittedTargetLease()
        cutover.await()
        runCurrent()

        assertEquals(
            listOf(
                "G:${predecessor.stateId}:activate",
                "G:terminal-started",
                "G:terminal-finished",
                "G:${predecessor.stateId}:close",
                "H:${successor.stateId}:activate",
            ),
            events,
        )
        assertEquals(1, predecessor.closeCount.get())
        assertEquals(ChannelPreparationAvailability.Available, fixture.registry.preparation(initialDefinition.id))
        shutdown(fixture.registry)
    }

    @Test
    fun `stale generation timer task callback and operation completion cannot mutate successor`() = runTest {
        val providers = ChannelImplementationProviderRegistry()
        val fixture = fixture(providers)
        val id = installedId("404")
        val definition = definition("stale", id)
        val old = RecordingInstalledProvider("404", digest('f'), scheduleBackgroundWork = true)
        publish(providers, old.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        val predecessor = old.runtimes.single()
        assertTrue(predecessor.timerWasAccepted)
        assertTrue(predecessor.taskWasAccepted)
        val successorProvider = RecordingInstalledProvider("404", digest('0'))
        publish(providers, successorProvider.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()
        val successor = successorProvider.runtimes.single()

        predecessor.completeStaleCallback()
        predecessor.completeStaleOperation()
        predecessor.releaseTask.complete(Unit)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(ChannelPreparationAvailability.Available, fixture.registry.preparation(definition.id))
        assertEquals(ChannelExecutionStatus.IDLE, fixture.registry.getRuntimeSnapshot(definition.id)?.executionStatus)
        assertTrue("old timer and task callbacks must be cancelled at retirement", predecessor.backgroundCompletions.isEmpty())
        assertEquals(
            GenerationAdmission.Rejected(GenerationAdmissionRejection.CLOSED),
            predecessor.context.scheduleTimer(0.0) { error("old timer must never run") },
        )
        assertEquals(
            GenerationAdmission.Rejected(GenerationAdmissionRejection.CLOSED),
            predecessor.context.admitTask { error("old task must never run") },
        )
        assertTrue("old callback/operation completions must not alter successor state", successor.completionMarkers.isEmpty())
        assertEquals(1, predecessor.closeCount.get())
        shutdown(fixture.registry)
    }

    @Test
    fun `removal failed successor and reinstall preserve definition unavailable typing and fresh volatile state`() = runTest {
        val providers = ChannelImplementationProviderRegistry()
        val fixture = fixture(providers)
        val id = installedId("505")
        val definition = definition("recovery", id)
        val v1 = RecordingInstalledProvider("505", digest('1'))
        publish(providers, v1.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        val predecessor = v1.runtimes.single()
        predecessor.contaminate("old-global", "old-cache", "old-timer", "old-coroutine", "old-token", "old-latch", "old-queue", "old-log", "old-authorisation")
        publish(providers)
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        assertEquals(1, predecessor.closeCount.get())
        assertTypedMissing(fixture.registry, definition.id, id)

        val failed = RecordingInstalledProvider("505", digest('2'), constructionFailure = "new revision rejected")
        publish(providers, failed.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        assertEquals(1, predecessor.closeCount.get())
        assertEquals(0, failed.runtimes.size)
        assertEquals(
            ChannelProviderError.RuntimeConstructionFailed(id, "new revision rejected"),
            providerError(requireNotNull(fixture.registry.getRuntimeSnapshot(definition.id))),
        )

        val recovered = RecordingInstalledProvider("505", digest('3'))
        publish(providers, recovered.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        val fresh = recovered.runtimes.single()
        assertNotEquals(predecessor.stateId, fresh.stateId)
        assertNotEquals(predecessor.generation, fresh.generation)
        assertFalse("reinstall must not restore any predecessor volatile marker", fresh.volatileMarkers.any { it.startsWith("old-") })
        assertTrue(fresh.context.isActive())
        assertFalse(predecessor.context.isActive())
        assertEquals(ChannelPreparationAvailability.Available, fixture.registry.preparation(definition.id))
        shutdown(fixture.registry)
    }

    @Test
    fun `disabled installed definition remains dormant through revisions and enables once`() = runTest {
        val providers = ChannelImplementationProviderRegistry()
        val fixture = fixture(providers)
        val id = installedId("606")
        val disabled = definition("dormant", id, enabled = false)
        val v1 = RecordingInstalledProvider("606", digest('6'))
        val v2 = RecordingInstalledProvider("606", digest('7'))

        publish(providers, v1.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(disabled), disabled.id))
        runCurrent()

        assertEquals(
            ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.Disabled),
            fixture.registry.preparation(disabled.id),
        )
        assertEquals("disabled definitions must not invoke provider construction through a generation gate", 0, v1.constructionAttempts.get())
        assertTrue("disabled definitions must not allocate an actor or state", v1.records.isEmpty())
        assertTrue("disabled definitions must not run runtime startup", v1.runtimes.isEmpty())

        publish(providers, v2.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(disabled), disabled.id))
        runCurrent()

        assertEquals(
            ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.Disabled),
            fixture.registry.preparation(disabled.id),
        )
        assertEquals("a disabled old revision must remain dormant", 0, v1.constructionAttempts.get())
        assertEquals("a disabled new revision must remain dormant", 0, v2.constructionAttempts.get())

        val enabled = disabled.copy(enabled = true)
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(enabled), enabled.id))
        runCurrent()
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(enabled), enabled.id))
        runCurrent()

        assertEquals(0, v1.constructionAttempts.get())
        assertEquals("enabling must construct the current revision exactly once", 1, v2.constructionAttempts.get())
        assertEquals(listOf(v2.fingerprint), v2.records.map(ConstructionRecord::fingerprint))
        assertEquals(1, v2.runtimes.single().activationCount.get())
        assertEquals(ChannelPreparationAvailability.Available, fixture.registry.preparation(enabled.id))
        shutdown(fixture.registry)
    }

    @Test
    fun `construction failure is retained until its provider fingerprint changes`() = runTest {
        val providers = ChannelImplementationProviderRegistry()
        val fixture = fixture(providers)
        val id = installedId("707")
        val definition = definition("construction-failure", id)
        val failed = RecordingInstalledProvider("707", digest('8'), constructionFailure = "entry rejected")
        publish(providers, failed.binding())

        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        assertEquals(1, failed.constructionAttempts.get())
        assertEquals(
            ChannelProviderError.RuntimeConstructionFailed(id, "entry rejected"),
            providerError(requireNotNull(fixture.registry.getRuntimeSnapshot(definition.id))),
        )

        val unrelated = definition("unrelated", installedId("708"))
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition, unrelated), definition.id))
        runCurrent()

        assertEquals("an unrelated catalogue reconciliation must not retry the failed revision", 1, failed.constructionAttempts.get())

        val retried = RecordingInstalledProvider("707", digest('9'), constructionFailure = "replacement rejected")
        publish(providers, retried.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition, unrelated), definition.id))
        runCurrent()

        assertEquals(1, failed.constructionAttempts.get())
        assertEquals("a changed fingerprint must receive one fresh construction attempt", 1, retried.constructionAttempts.get())
        assertEquals(
            ChannelProviderError.RuntimeConstructionFailed(id, "replacement rejected"),
            providerError(requireNotNull(fixture.registry.getRuntimeSnapshot(definition.id))),
        )
        shutdown(fixture.registry)
    }

    @Test
    fun `startup failure is retained until its provider fingerprint changes`() = runTest {
        val providers = ChannelImplementationProviderRegistry()
        val fixture = fixture(providers)
        val id = installedId("808")
        val definition = definition("startup-failure", id)
        val failed = RecordingInstalledProvider("808", digest('a'), activationFailure = "startup rejected")
        publish(providers, failed.binding())

        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        assertEquals(1, failed.constructionAttempts.get())
        assertEquals(1, failed.runtimes.single().activationCount.get())
        assertEquals(
            ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeFailed("startup rejected")),
            fixture.registry.preparation(definition.id),
        )

        val unrelated = definition("unrelated", installedId("809"))
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition, unrelated), definition.id))
        runCurrent()

        assertEquals("an unrelated catalogue reconciliation must not retry failed startup", 1, failed.constructionAttempts.get())
        assertEquals(1, failed.runtimes.single().activationCount.get())

        val retried = RecordingInstalledProvider("808", digest('b'), activationFailure = "replacement startup rejected")
        publish(providers, retried.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition, unrelated), definition.id))
        runCurrent()

        assertEquals(1, failed.constructionAttempts.get())
        assertEquals(1, retried.constructionAttempts.get())
        assertEquals(1, retried.runtimes.single().activationCount.get())
        assertEquals(
            ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeFailed("replacement startup rejected")),
            fixture.registry.preparation(definition.id),
        )
        shutdown(fixture.registry)
    }

    @Test
    fun `removal replaces package unavailable and construction failure with exact missing provider`() = runTest {
        val providers = ChannelImplementationProviderRegistry()
        val fixture = fixture(providers)
        val id = installedId("909")
        val definition = definition("removed", id)
        val packageUnavailable = ChannelProviderError.PackageUnavailable(
            id,
            ChannelProviderError.PackageUnavailableCategory.LOADING,
            ChannelProviderError.PackageUnavailableDetail.LOAD_TIMEOUT,
        )

        publishUnavailable(providers, id to packageUnavailable)
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()
        assertEquals(packageUnavailable, providerError(requireNotNull(fixture.registry.getRuntimeSnapshot(definition.id))))

        publish(providers)
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()
        assertTypedMissing(fixture.registry, definition.id, id)

        val constructionFailure = RecordingInstalledProvider("909", digest('c'), constructionFailure = "entry rejected")
        publish(providers, constructionFailure.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()
        assertEquals(
            ChannelProviderError.RuntimeConstructionFailed(id, "entry rejected"),
            providerError(requireNotNull(fixture.registry.getRuntimeSnapshot(definition.id))),
        )

        publish(providers)
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()
        assertTypedMissing(fixture.registry, definition.id, id)
        shutdown(fixture.registry)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Task 2.7: Incompatible update preserves payload, projects unavailable,
    //           no successor/default, rollback restores availability.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `incompatible update preserves exact payload and definition and projects unavailable`() = runTest {
        val providers = ChannelImplementationProviderRegistry()
        val fixture = fixture(providers)
        val id = installedId("1007")
        val definition = definition("incompatible-update", id)
        val events = mutableListOf<String>()
        val v1 = RecordingInstalledProvider("1007", digest('1'), events)
        publish(providers, v1.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        val v1Runtime = v1.runtimes.single()
        assertEquals(ChannelPreparationAvailability.Available, fixture.registry.preparation(definition.id))
        assertEquals(v1.fingerprint, v1.records.single().fingerprint)

        // Update to v2 with a configuration provider that rejects the existing payload.
        val v2 = ConfigurationRejectingInstalledProvider("1007", digest('2'), events)
        publish(providers, v2.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        // The predecessor must have been closed.
        assertEquals(1, v1Runtime.closeCount.get())
        // No successor runtime must exist: configuration was rejected by migrateAndValidate
        // before constructRuntime is ever called.
        assertEquals(0, v2.runtimes.size)
        assertEquals(0, v2.constructionAttempts.get())
        // The entry must project a typed InvalidConfiguration error.
        val error = providerError(requireNotNull(fixture.registry.getRuntimeSnapshot(definition.id)))
        assertTrue("Expected InvalidConfiguration error, got ${error::class.simpleName}: $error",
            error is ChannelProviderError.InvalidConfiguration)
        assertTrue(error.message.contains("rejected"))
        // No successor runtime should be created.
        assertTrue("v2 must not have constructed any runtime", v2.runtimes.isEmpty())
        shutdown(fixture.registry)
    }

    @Test
    fun `incompatible update does not create default or successor substitute`() = runTest {
        val providers = ChannelImplementationProviderRegistry()
        val fixture = fixture(providers)
        val id = installedId("2007")
        val definition = definition("no-default-substitute", id)
        val v1 = RecordingInstalledProvider("2007", digest('1'))
        publish(providers, v1.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        val predecessor = v1.runtimes.single()

        // v2 rejects the existing payload; v3 also rejects it (simulating two updates
        // with incompatible declarations). Neither should create a runtime.
        val v2 = ConfigurationRejectingInstalledProvider("2007", digest('2'))
        val v3 = ConfigurationRejectingInstalledProvider("2007", digest('3'))
        publish(providers, v2.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()
        assertEquals("v2 must not construct a successor runtime", 0, v2.runtimes.size)
        assertEquals(0, v2.constructionAttempts.get())

        publish(providers, v3.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()
        assertEquals("v3 must not construct a successor runtime either", 0, v3.runtimes.size)
        assertEquals(0, v3.constructionAttempts.get())

        // The provider error must carry the expected typed failure, not a
        // default runtime, fallback provider, or migration.
        val error = providerError(requireNotNull(fixture.registry.getRuntimeSnapshot(definition.id)))
        assertTrue("Expected InvalidConfiguration error, got ${error::class.simpleName}: $error",
            error is ChannelProviderError.InvalidConfiguration)
        // Predecessor must remain retired (closed).
        assertEquals(1, predecessor.closeCount.get())
        shutdown(fixture.registry)
    }

    @Test
    fun `explicit rollback restores availability after incompatible update`() = runTest {
        val providers = ChannelImplementationProviderRegistry()
        val fixture = fixture(providers)
        val id = installedId("3007")
        val definition = definition("rollback-restores", id)
        val events = mutableListOf<String>()
        val v1 = RecordingInstalledProvider("3007", digest('1'), events)
        publish(providers, v1.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        val v1Runtime = v1.runtimes.single()
        val v1Generation = v1Runtime.generation
        assertEquals(ChannelPreparationAvailability.Available, fixture.registry.preparation(definition.id))

        // Update to v2 with rejecting configuration.
        val v2 = ConfigurationRejectingInstalledProvider("3007", digest('2'), events)
        publish(providers, v2.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        assertEquals(1, v1Runtime.closeCount.get())
        assertEquals(0, v2.runtimes.size)
        assertTrue(providerError(requireNotNull(fixture.registry.getRuntimeSnapshot(definition.id))) is ChannelProviderError.InvalidConfiguration)

        // Rollback: publish v1 again (simulating repository rollback to a compatible revision).
        val rollback = RecordingInstalledProvider("3007", digest('1'), events)
        publish(providers, rollback.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()

        // Availability must be restored with a fresh runtime generation.
        assertEquals(ChannelPreparationAvailability.Available, fixture.registry.preparation(definition.id))
        assertEquals(1, rollback.runtimes.size)
        val restored = rollback.runtimes.single()
        assertNotEquals("rollback must produce a new generation, distinct from the original", v1Generation, restored.generation)
        assertNotEquals("rollback must produce a fresh state ID", v1Runtime.stateId, restored.stateId)
        assertEquals(0, v2.runtimes.size)
        assertEquals(listOf(rollback.fingerprint), rollback.records.map(ConstructionRecord::fingerprint))
        shutdown(fixture.registry)
    }

    @Test
    fun `incompatible update on multiple instances with same provider projects each independently`() = runTest {
        val providers = ChannelImplementationProviderRegistry()
        val fixture = fixture(providers)
        val id = installedId("4007")
        val leftDef = definition("left-instance", id)
        val rightDef = definition("right-instance", id)
        val v1 = RecordingInstalledProvider("4007", digest('a'))
        publish(providers, v1.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(leftDef, rightDef), leftDef.id))
        runCurrent()

        assertEquals(2, v1.runtimes.size)
        assertEquals(ChannelPreparationAvailability.Available, fixture.registry.preparation(leftDef.id))
        assertEquals(ChannelPreparationAvailability.Available, fixture.registry.preparation(rightDef.id))

        // Update to rejecting provider.
        val rejector = ConfigurationRejectingInstalledProvider("4007", digest('b'))
        publish(providers, rejector.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(leftDef, rightDef), leftDef.id))
        runCurrent()

        // Both instances must project the same typed error independently.
        assertEquals(ChannelProviderError.InvalidConfiguration::class.java, providerError(requireNotNull(fixture.registry.getRuntimeSnapshot(leftDef.id)))::class.java)
        assertEquals(ChannelProviderError.InvalidConfiguration::class.java, providerError(requireNotNull(fixture.registry.getRuntimeSnapshot(rightDef.id)))::class.java)
        assertEquals(0, rejector.runtimes.size)
        assertEquals(0, rejector.constructionAttempts.get())

        // Each predecessor was closed independently.
        assertEquals(1, v1.runtimes[0].closeCount.get())
        assertEquals(1, v1.runtimes[1].closeCount.get())

        // Rollback restores both.
        val rollback = RecordingInstalledProvider("4007", digest('a'))
        publish(providers, rollback.binding())
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(leftDef, rightDef), leftDef.id))
        runCurrent()

        assertEquals(2, rollback.runtimes.size)
        assertEquals(ChannelPreparationAvailability.Available, fixture.registry.preparation(leftDef.id))
        assertEquals(ChannelPreparationAvailability.Available, fixture.registry.preparation(rightDef.id))
        assertNotEquals(rollback.runtimes[0].generation, rollback.runtimes[1].generation)
        shutdown(fixture.registry)
    }

    private fun TestScope.fixture(providers: ChannelImplementationProviderRegistry): Fixture = Fixture(
        ChannelRuntimeRegistry(
            providers = providers,
            capabilityHost = NoCapabilities,
            invocationBoundary = RuntimeInvocationBoundary(
                RuntimeWorkerDispatcher.fromDispatcher(StandardTestDispatcher(testScheduler)),
                RuntimeInvocationPolicy(
                    perGenerationQueueCapacity = 16,
                    callbackTimeoutMillis = 1_000,
                    inputReleasedTimeoutMillis = 1_000,
                    closeTimeoutMillis = 1_000,
                ),
            ),
            runtimeScope = backgroundScope,
            closeScope = backgroundScope,
            shutdownAwaitMillis = 2_000,
        ),
    )

    private suspend fun TestScope.shutdown(registry: ChannelRuntimeRegistry) {
        val closing = async { registry.shutdownAndAwait() }
        runCurrent()
        assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, closing.await())
    }

    private fun publish(
        registry: ChannelImplementationProviderRegistry,
        vararg bindings: InstalledProviderBinding,
    ) {
        assertTrue(
            "installed provider snapshot must publish atomically",
            registry.publishInstalledProviders(bindings.associateBy { it.provider.descriptor.implementationId }) is
                InstalledProvidersPublicationResult.Success,
        )
    }

    private fun publishUnavailable(
        registry: ChannelImplementationProviderRegistry,
        vararg unavailable: Pair<ChannelImplementationId, ChannelProviderError.PackageUnavailable>,
    ) {
        assertTrue(
            "installed unavailable snapshot must publish atomically",
            registry.publishInstalledProviders(emptyMap(), unavailable.toMap()) is InstalledProvidersPublicationResult.Success,
        )
    }

    private fun installedId(repositoryId: String): ChannelImplementationId =
        InstalledProviderId.derive(GitHubRepositoryIdentity(repositoryId))

    private fun definition(
        id: String,
        implementationId: ChannelImplementationId,
        enabled: Boolean = true,
    ): ChannelDefinition = ChannelDefinition(
        id = id,
        name = "Channel $id",
        implementationId = implementationId,
        enabled = enabled,
        configSchemaVersion = 1,
        configPayload = OpaqueJsonObject.fromJsonObject(JSONObject()),
    )

    private fun digest(character: Char): ArtifactDigest = ArtifactDigest(character.toString().repeat(64))

    private fun committed(acceptance: ChannelInputAcceptance): CommittedInput {
        val target = (acceptance as? ChannelInputAcceptance.Accepted)?.target
            ?: throw AssertionError("Expected an accepted committed input target, got $acceptance")
        val lease = target as? CommittedTargetLeaseOwner
            ?: throw AssertionError("Accepted target did not carry a committed lease")
        return CommittedInput(target, lease)
    }

    private fun assertTypedMissing(
        registry: ChannelRuntimeRegistry,
        channelId: String,
        id: ChannelImplementationId,
    ) {
        assertEquals(ChannelProviderError.MissingProvider(id), providerError(requireNotNull(registry.getRuntimeSnapshot(channelId))))
    }

    private fun providerError(snapshot: ChannelRuntimeSnapshot): ChannelProviderError {
        val unavailable = snapshot.preparation as? ChannelPreparationAvailability.Unavailable
            ?: throw AssertionError("Expected typed unavailable projection, got ${snapshot.preparation}")
        return when (val reason = unavailable.reason) {
            is ChannelPreparationReason.Provider -> reason.error
            is ChannelPreparationReason.ConfigurationIncompatible -> reason.error
            else -> throw AssertionError("Expected Provider or ConfigurationIncompatible reason, got $reason")
        }
    }

    private data class Fixture(val registry: ChannelRuntimeRegistry)
    private data class CommittedInput(
        val target: ChannelInputTarget,
        val lease: CommittedTargetLeaseOwner,
    )

    private data class ConstructionRecord(
        val fingerprint: ProviderRevisionFingerprint,
        val generation: Long,
        val stateId: Int,
    )

    private class RecordingInstalledProvider(
        repositoryId: String,
        private val digest: ArtifactDigest,
        private val events: MutableList<String>? = null,
        private val constructionFailure: String? = null,
        private val activationFailure: String? = null,
        private val scheduleBackgroundWork: Boolean = false,
    ) : ChannelImplementationProvider {
        private val repository = GitHubRepositoryIdentity(repositoryId)
        private val implementationId = InstalledProviderId.derive(repository)
        private val nextStateId = AtomicInteger()
        val constructionAttempts = AtomicInteger()
        val records = mutableListOf<ConstructionRecord>()
        val runtimes = mutableListOf<RecordingRuntime>()

        override val fingerprint: ProviderRevisionFingerprint = ProviderRevisionFingerprint.fromDigest(digest)
        override val descriptor: ChannelImplementationDescriptor = ChannelImplementationDescriptor(
            implementationId = implementationId,
            presentation = ChannelPresentationMetadata("Installed $repositoryId", "installed provider", "unavailable"),
            configuration = EmptyConfiguration(implementationId),
            configurationFields = emptyList(),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )

        fun binding(): InstalledProviderBinding = InstalledProviderBinding(repository, digest, this)

        override suspend fun constructRuntime(request: ChannelRuntimeConstructionRequest): ChannelRuntimeConstructionResult {
            constructionAttempts.incrementAndGet()
            constructionFailure?.let { return ChannelRuntimeConstructionResult.Failure(ChannelProviderError.RuntimeConstructionFailed(implementationId, it)) }
            val stateId = nextStateId.incrementAndGet() + stateNamespace.getAndIncrement() * 10_000
            val runtime = RecordingRuntime(request, stateId, events, activationFailure, scheduleBackgroundWork)
            runtimes += runtime
            records += ConstructionRecord(fingerprint, runtime.generation, stateId)
            return ChannelRuntimeConstructionResult.Success(runtime)
        }
    }

    private class EmptyConfiguration(
        override val implementationId: ChannelImplementationId,
    ) : ChannelConfigurationProvider {
        override val currentSchemaVersion: Int = 1

        override fun defaultPayload(): OpaqueJsonObject = OpaqueJsonObject.fromJsonObject(JSONObject())

        override fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult = valid(schemaVersion, payload)

        override fun migrateStep(
            fromSchemaVersion: Int,
            payload: OpaqueJsonObject,
        ): ChannelConfigurationMigrationStep = ChannelConfigurationMigrationStep.Success(payload)

        override fun migrateAndValidate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult =
            valid(schemaVersion, payload)

        private fun valid(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult =
            ProviderConfigurationResult.Success(ValidatedChannelConfiguration(implementationId, schemaVersion, payload))
    }

    /**
     * Configuration provider that always rejects validation, simulating an incompatible
     * package declaration update. Used to test incompatible update preservation and rollback.
     */
    private class RejectingConfiguration(
        override val implementationId: ChannelImplementationId,
    ) : ChannelConfigurationProvider {
        override val currentSchemaVersion: Int = 1

        override fun defaultPayload(): OpaqueJsonObject = OpaqueJsonObject.fromJsonObject(JSONObject())

        override fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult = reject()

        override fun migrateStep(
            fromSchemaVersion: Int,
            payload: OpaqueJsonObject,
        ): ChannelConfigurationMigrationStep = ChannelConfigurationMigrationStep.Success(payload)

        override fun migrateAndValidate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult = reject()

        private fun reject(): ProviderConfigurationResult = ProviderConfigurationResult.Failure(
            ChannelProviderError.InvalidConfiguration(
                implementationId, 1, "existing configuration rejected by updated declaration",
            ),
        )
    }

    /**
     * Provider that refuses any existing configuration, simulating a package revision whose
     * updated declaration is incompatible with preserved instance payloads.
     */
    private class ConfigurationRejectingInstalledProvider(
        repositoryId: String,
        private val digest: ArtifactDigest,
        private val events: MutableList<String>? = null,
    ) : ChannelImplementationProvider {
        private val repository = GitHubRepositoryIdentity(repositoryId)
        private val implementationId = InstalledProviderId.derive(repository)
        val constructionAttempts = AtomicInteger()
        val records = mutableListOf<ConstructionRecord>()
        val runtimes = mutableListOf<RecordingRuntime>()

        override val fingerprint: ProviderRevisionFingerprint = ProviderRevisionFingerprint.fromDigest(digest)
        override val descriptor: ChannelImplementationDescriptor = ChannelImplementationDescriptor(
            implementationId = implementationId,
            presentation = ChannelPresentationMetadata("Rejecting $repositoryId", "rejecting provider", "unavailable"),
            configuration = RejectingConfiguration(implementationId),
            configurationFields = emptyList(),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )

        fun binding(): InstalledProviderBinding = InstalledProviderBinding(repository, digest, this)

        override suspend fun constructRuntime(request: ChannelRuntimeConstructionRequest): ChannelRuntimeConstructionResult {
            constructionAttempts.incrementAndGet()
            val stateId = nextStateId.incrementAndGet() + stateNamespace.getAndIncrement() * 10_000
            val runtime = RecordingRuntime(request, stateId, events, null, false)
            runtimes += runtime
            records += ConstructionRecord(fingerprint, runtime.generation, stateId)
            return ChannelRuntimeConstructionResult.Success(runtime)
        }

        private companion object {
            private val nextStateId = AtomicInteger()
        }
    }

    private class RecordingRuntime(
        private val request: ChannelRuntimeConstructionRequest,
        val stateId: Int,
        private val events: MutableList<String>?,
        private val activationFailure: String?,
        scheduleBackgroundWork: Boolean,
    ) : ChannelRuntime {
        override val id: String = request.definition.id
        val context: GenerationExecutionContext = request.generationContext
        val generation: Long = (request.generationContext as io.talkcan.model.ActorRuntimeHostContext)
            .actorIdentity.runtimeGeneration.value
        val closeCount = AtomicInteger()
        val volatileMarkers = linkedSetOf<String>()
        val backgroundCompletions = mutableListOf<String>()
        val completionMarkers = mutableListOf<String>()
        val releaseTask = CompletableDeferred<Unit>()
        private val snapshots = MutableStateFlow(
            ChannelRuntimeSnapshot(
                id = id,
                name = request.definition.name,
                implementationId = request.definition.implementationId,
                enabled = request.definition.enabled,
                preparation = ChannelPreparationAvailability.Available,
                executionStatus = ChannelExecutionStatus.IDLE,
            ),
        )
        override val snapshot: StateFlow<ChannelRuntimeSnapshot> = snapshots.asStateFlow()
        var prepare: suspend () -> ChannelInputAcceptance = { ChannelInputAcceptance.Refused("not configured") }
        val activationCount = AtomicInteger()
        var timerWasAccepted = false
        var taskWasAccepted = false

        init {
            if (scheduleBackgroundWork) {
                timerWasAccepted = context.scheduleTimer(1.0) { backgroundCompletions += "timer" } is GenerationAdmission.Accepted
                taskWasAccepted = context.admitTask {
                    releaseTask.await()
                    backgroundCompletions += "task"
                } is GenerationAdmission.Accepted
            }
        }

        override suspend fun activate(): ChannelActivationResult {
            activationCount.incrementAndGet()
            events?.add("${if (events.any { it.startsWith("G:") }) "H" else "G"}:$stateId:activate")
            return activationFailure?.let(ChannelActivationResult::Failed) ?: ChannelActivationResult.Ready
        }

        override suspend fun prepareInput(): ChannelInputAcceptance = prepare()

        override suspend fun close() {
            closeCount.incrementAndGet()
            events?.add("G:$stateId:close")
        }

        fun contaminate(vararg markers: String) {
            volatileMarkers += markers
        }

        fun completeStaleCallback() {
            completionMarkers += "callback"
            snapshots.value = snapshots.value.copy(
                preparation = ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeFailed("old callback")),
                executionStatus = ChannelExecutionStatus.FAILED,
            )
        }

        fun completeStaleOperation() {
            completionMarkers += "operation"
            snapshots.value = snapshots.value.copy(executionStatus = ChannelExecutionStatus.SUCCESS)
        }
    }

    private object NoCapabilities : ChannelCapabilityHost {
        override suspend fun availability(identity: CapabilityScopeIdentity, key: io.talkcan.channel.capability.CapabilityKey<*>): CapabilityAvailability =
            CapabilityAvailability.Available

        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: CapabilityScopeIdentity,
            key: io.talkcan.channel.capability.CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> = HostedCapabilityAcquisition.Unavailable(
            io.talkcan.channel.capability.CapabilityUnavailableReason.UNSUPPORTED,
        )

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: io.talkcan.channel.capability.CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = acquire(identity, key)
    }

    private companion object {
        val stateNamespace = AtomicInteger()
    }
}
