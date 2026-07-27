package io.talkcan.service

import io.talkcan.audio.ChannelInputAcceptance
import io.talkcan.channel.capability.CapabilityAvailability
import io.talkcan.channel.capability.CapabilityKey
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.CapabilityUnavailableReason
import io.talkcan.channel.capability.ChannelCapabilityHost
import io.talkcan.channel.capability.ChannelCapabilityPort
import io.talkcan.channel.capability.HostedCapabilityAcquisition
import io.talkcan.dependency.ConfigurationDataDeclaration
import io.talkcan.dependency.ConfigurationFieldDeclaration
import io.talkcan.dependency.ConfigurationUiDeclaration
import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.dependency.PackageConfigurationDeclaration
import io.talkcan.dependency.UiControl
import io.talkcan.dependency.UiFieldDeclaration
import io.talkcan.lua.API_VERSION
import io.talkcan.lua.CompiledConfigurationProvider
import io.talkcan.lua.ImmutableProgramImage
import io.talkcan.lua.LUA_VERSION
import io.talkcan.lua.LuaCallbackHandle
import io.talkcan.lua.LuaChannelImplementationProvider
import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaProgramRequirements
import io.talkcan.lua.LuaSpawnAdmission
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaValue
import io.talkcan.lua.ProgramImageCreationResult
import io.talkcan.lua.actor.ActorRuntimeFactory
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
import io.talkcan.model.ChannelProviderRegistrationResult
import io.talkcan.model.ChannelRuntimeConstructionRequest
import io.talkcan.model.ChannelRuntimeConstructionResult
import io.talkcan.model.DynamicChoiceSourceKind
import io.talkcan.model.DynamicConfigurationChoiceSourceId
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.model.ProviderConfigurationResult
import io.talkcan.model.ProviderRevisionFingerprint
import io.talkcan.model.ValidatedChannelConfiguration
import io.talkcan.profile.ProfileAvailability
import io.talkcan.profile.ProfileId
import io.talkcan.profile.ProfileLimits
import io.talkcan.profile.ProfileRecord
import io.talkcan.profile.ProfileScalarValue
import io.talkcan.profile.ProfileTypeIdentity
import io.talkcan.work.DurableWorkStore
import io.talkcan.work.WorkInstanceId
import io.talkcan.work.WorkQueueId
import io.talkcan.work.WorkQueuePartition
import io.talkcan.work.WorkRepositoryId
import io.talkcan.work.WorkState
import io.talkcan.work.WorkStoreResult
import io.talkcan.work.WorkTerminalClass
import io.talkcan.work.WorkValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Service-level recovery contracts owned by PttForegroundService:
 *
 * 1. A committed successful selected-profile create/edit/delete reconciles the
 *    affected channel instances through the ordinary [ChannelRuntimeRegistry]:
 *    the predecessor generation stops admission and closes BEFORE the successor
 *    publishes its fresh grant (edit) or unavailability (delete) snapshot, and
 *    instances that do not select a mutated profile — including every builtin —
 *    keep their live generation (3.8/13.4).
 *
 * 2. Process startup loads and reconciles the durable work store before any
 *    package or runtime composition: persisted queued work loads under the
 *    preserved epoch, a no-effect claim is safely reclaimed to its original
 *    FIFO position, a started-uncommitted claim becomes terminally
 *    indeterminate, and the fresh registry restores the current profile grants
 *    through a fresh actor while the builtin remains available (13.8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceProfileWorkRecoveryTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun selectedProfileEditClosesPredecessorBeforeSuccessorGrantSnapshot() = runTest {
        val bridge = GrantRecordingBridge()
        val profiles = mutableMapOf("profile-1" to profileRecord("profile-1", revision = 1, displayName = "Work"))
        val builtin = BuiltinFixtureProvider()
        val registry = registry(profileProvider(bridge) { profiles[it] }, builtin)
        val channel = definition("channel-a", PROFILE_IMPLEMENTATION_ID, JSONObject().put("model_profile", "profile-1"))
        val sibling = definition("builtin-a", BUILTIN_IMPLEMENTATION_ID)
        val catalogue = ChannelCatalogueSnapshot(listOf(channel, sibling), channel.id)

        try {
            registry.reconcile(catalogue)
            runCurrent()

            // Baseline: the first generation receives the detached grant snapshot.
            val predecessor = bridge.createdStateIds.single()
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(channel.id)?.preparation)
            val firstGrant = JSONObject(bridge.grantsByState.getValue(predecessor)).getJSONArray("profiles").getJSONObject(0)
            assertEquals("profile-1", firstGrant.getString("profileId"))
            assertEquals("Work", firstGrant.getString("displayName"))

            // An unchanged catalogue retains the live generation: the ordinary
            // reconcile retention condition alone cannot observe a profile edit.
            registry.reconcile(catalogue)
            runCurrent()
            assertEquals(listOf(predecessor), bridge.createdStateIds)

            // The committed edit of the selected profile reconciles exactly the
            // affected instance through the registry.
            assertEquals(listOf(channel.id), channelInstancesSelectingProfiles(catalogue, setOf("profile-1")))
            profiles["profile-1"] = profileRecord("profile-1", revision = 2, displayName = "Work 2")
            reconcileSelectedProfileMutation(registry, catalogue, setOf("profile-1"))
            runCurrent()

            assertEquals(2, bridge.createdStateIds.size)
            val successor = bridge.createdStateIds[1]
            assertTrue(
                "the predecessor state must close before the successor grant snapshot is installed",
                bridge.events.indexOf("close:$predecessor") in 0 until bridge.events.indexOf("grants:$successor"),
            )
            val successorGrant = JSONObject(bridge.grantsByState.getValue(successor)).getJSONArray("profiles").getJSONObject(0)
            assertEquals("profile-1", successorGrant.getString("profileId"))
            assertEquals("Work 2", successorGrant.getString("displayName"))
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(channel.id)?.preparation)

            // The unaffected builtin keeps its single generation, still available.
            assertEquals(listOf("builtin-a"), builtin.constructed)
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(sibling.id)?.preparation)
        } finally {
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, registry.shutdownAndAwait())
        }
    }

    @Test
    fun selectedProfileDeleteClosesPredecessorBeforeSuccessorUnavailableSnapshot() = runTest {
        val bridge = GrantRecordingBridge()
        val profiles = mutableMapOf("profile-1" to profileRecord("profile-1", revision = 1, displayName = "Work"))
        val registry = registry(profileProvider(bridge) { profiles[it] })
        val channel = definition("channel-a", PROFILE_IMPLEMENTATION_ID, JSONObject().put("model_profile", "profile-1"))
        val catalogue = ChannelCatalogueSnapshot(listOf(channel), channel.id)

        try {
            registry.reconcile(catalogue)
            runCurrent()
            val predecessor = bridge.createdStateIds.single()
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(channel.id)?.preparation)

            // The committed delete of the selected profile reconciles the
            // affected instance; the successor constructs without grants.
            profiles.remove("profile-1")
            reconcileSelectedProfileMutation(registry, catalogue, setOf("profile-1"))
            runCurrent()

            assertEquals(2, bridge.createdStateIds.size)
            val successor = bridge.createdStateIds[1]
            assertTrue(
                "the predecessor state must close before the successor unavailability snapshot",
                bridge.events.indexOf("close:$predecessor") in 0 until bridge.events.indexOf("readiness:$successor"),
            )
            assertFalse("the deleted profile must not produce a successor grant snapshot", successor in bridge.grantsByState)
            val preparation = registry.getRuntimeSnapshot(channel.id)?.preparation
            assertTrue("the successor must project unavailability, was $preparation",
                preparation is ChannelPreparationAvailability.Unavailable)
            val reason = (preparation as ChannelPreparationAvailability.Unavailable).reason
            assertTrue("the unavailability must come from the runtime readiness projection, was $reason",
                reason is ChannelPreparationReason.RuntimeReadiness)
            assertEquals("selected profile unavailable", (reason as ChannelPreparationReason.RuntimeReadiness).message)
        } finally {
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, registry.shutdownAndAwait())
        }
    }

    @Test
    fun unrelatedProfileMutationDoesNotReplaceAnyGeneration() = runTest {
        val bridge = GrantRecordingBridge()
        val profiles = mutableMapOf("profile-1" to profileRecord("profile-1", revision = 1, displayName = "Work"))
        val registry = registry(profileProvider(bridge) { profiles[it] })
        val channel = definition("channel-a", PROFILE_IMPLEMENTATION_ID, JSONObject().put("model_profile", "profile-1"))
        val catalogue = ChannelCatalogueSnapshot(listOf(channel), channel.id)

        try {
            registry.reconcile(catalogue)
            runCurrent()
            assertEquals(1, bridge.createdStateIds.size)

            // A profile no channel selects affects no instance: no generation is
            // replaced and the live channel stays available.
            assertTrue(channelInstancesSelectingProfiles(catalogue, setOf("profile-2")).isEmpty())
            reconcileSelectedProfileMutation(registry, catalogue, setOf("profile-2"))
            runCurrent()
            assertEquals(1, bridge.createdStateIds.size)
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(channel.id)?.preparation)
        } finally {
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, registry.shutdownAndAwait())
        }
    }

    @Test
    fun startupRecoveryLoadsQueuedWorkReclaimsNoEffectClaimAndMarksStartedUncommittedIndeterminate() {
        val dir = folder.newFolder("durable-work")
        val jobs = WorkQueuePartition(WorkRepositoryId(7L), WorkInstanceId("channel-a"), WorkQueueId("jobs"))
        val effects = WorkQueuePartition(WorkRepositoryId(7L), WorkInstanceId("channel-a"), WorkQueueId("effects"))

        // ── First process: commit work, then die holding one safe claim and one
        //    started-uncommitted claim. No shutdown, no release. ──
        val first = DurableWorkStore(dir)
        first.load()
        val queuedHead = first.submit(jobs, WorkValue.Text("queued-head"), 100L).success()
        val queuedTail = first.submit(jobs, WorkValue.Text("queued-tail"), 101L).success()
        val started = first.submit(effects, WorkValue.Text("started-uncommitted"), 102L).success()
        val originalJobsEpoch = first.queueRecord(jobs).success().epoch
        checkNotNull(first.claimNext(jobs, "holder-1", 110L).success())
        checkNotNull(first.claimNext(effects, "holder-1", 111L).success())
        assertTrue(first.beginEffect(started.id, "holder-1", "send", "fp-1") is WorkStoreResult.Success)

        // ── Restart: a fresh store over the same directory recovers before any
        //    package or runtime composition. ──
        val restarted = DurableWorkStore(dir)
        val plan = recoverDurableWorkAtStartup(restarted, 500L).success()

        // Persisted partitions load; epochs are preserved across ordinary restart.
        assertEquals(setOf(jobs, effects), restarted.partitions().toSet())
        assertEquals(originalJobsEpoch, restarted.queueRecord(jobs).success().epoch)


        // Safe reclaim: the claim with no started effect returns to QUEUED in place.
        assertEquals(listOf(queuedHead.id), plan.reclaimed[jobs])
        val jobsSnapshot = restarted.snapshot(jobs).success()
        assertEquals(2, jobsSnapshot.works.size)
        assertTrue(jobsSnapshot.works.all { it.state == WorkState.QUEUED })

        // The started-uncommitted claim is terminally indeterminate, never replayed.
        assertEquals(listOf(started.id), plan.indeterminate[effects])
        val tombstones = restarted.tombstones(effects).success()
        assertEquals(WorkTerminalClass.INDETERMINATE, tombstones.single().terminalClass)

        // FIFO order survives recovery: the reclaimed head is claimable first,
        // then the originally queued tail.
        val reclaim = checkNotNull(restarted.claimNext(jobs, "holder-2", 501L).success())
        assertEquals(queuedHead.id, reclaim.work.id)
        assertEquals(0L, reclaim.work.sequence)
        restarted.completeWork(queuedHead.id, "holder-2", 502L, playbackHandedOff = false).success()
        val tail = checkNotNull(restarted.claimNext(jobs, "holder-2", 503L).success())
        assertEquals(queuedTail.id, tail.work.id)
    }

    @Test
    fun restartRestoresCurrentProfileGrantsFreshActorAndKeepsBuiltinAvailable() = runTest {
        val dir = folder.newFolder("restart-work")
        val partition = WorkQueuePartition(WorkRepositoryId(9L), WorkInstanceId("channel-a"), WorkQueueId("turns"))

        // ── First process: one claimed no-effect work item, then process death. ──
        val first = DurableWorkStore(dir)
        first.load()
        val persisted = first.submit(partition, WorkValue.Text("turn"), 10L).success()
        checkNotNull(first.claimNext(partition, "holder-1", 11L).success())

        // ── Restart: recover the store BEFORE package/runtime composition. ──
        val workStore = DurableWorkStore(dir)
        val plan = recoverDurableWorkAtStartup(workStore, 20L).success()
        assertEquals(listOf(persisted.id), plan.reclaimed[partition])

        // Fresh composition over the recovered store: the profile-backed package
        // channel plus one builtin.
        val bridge = GrantRecordingBridge()
        val profiles = mutableMapOf("profile-1" to profileRecord("profile-1", revision = 3, displayName = "Restored"))
        val builtin = BuiltinFixtureProvider()
        val registry = registry(profileProvider(bridge) { profiles[it] }, builtin)
        val channel = definition("channel-a", PROFILE_IMPLEMENTATION_ID, JSONObject().put("model_profile", "profile-1"))
        val builtinChannel = definition("builtin-a", BUILTIN_IMPLEMENTATION_ID)
        val catalogue = ChannelCatalogueSnapshot(listOf(channel, builtinChannel), channel.id)

        try {
            registry.reconcile(catalogue)
            runCurrent()

            // Exactly one fresh actor restores the current profile grants.
            assertEquals(1, bridge.createdStateIds.size)
            val state = bridge.createdStateIds.single()
            val grant = JSONObject(bridge.grantsByState.getValue(state)).getJSONArray("profiles").getJSONObject(0)
            assertEquals("profile-1", grant.getString("profileId"))
            assertEquals("Restored", grant.getString("displayName"))
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(channel.id)?.preparation)

            // The builtin remains available in its single generation.
            assertEquals(listOf("builtin-a"), builtin.constructed)
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(builtinChannel.id)?.preparation)

            // The recovered queue is claimable: the prior-process claim no longer
            // blocks a fresh receive.
            val receive = checkNotNull(workStore.claimNext(partition, "holder-2", 21L).success())
            assertEquals(persisted.id, receive.work.id)
        } finally {
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, registry.shutdownAndAwait())
        }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun <T> WorkStoreResult<T>.success(): T = when (this) {
        is WorkStoreResult.Success -> value
        is WorkStoreResult.Failure -> throw AssertionError("Expected success, was Failure($failure)")
    }

    private fun TestScope.registry(vararg providers: ChannelImplementationProvider): ChannelRuntimeRegistry {
        val providerRegistry = ChannelImplementationProviderRegistry()
        providers.forEach { provider ->
            check(providerRegistry.register(provider) is ChannelProviderRegistrationResult.Registered)
        }
        val worker = RuntimeWorkerDispatcher.fromDispatcher(StandardTestDispatcher(testScheduler))
        return ChannelRuntimeRegistry(
            providers = providerRegistry,
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

    private fun profileProvider(
        bridge: LuaKernelBridge,
        profileLookup: suspend (String) -> ProfileRecord?,
    ): LuaChannelImplementationProvider = LuaChannelImplementationProvider.create(
        implementationId = PROFILE_IMPLEMENTATION_ID,
        presentation = ChannelPresentationMetadata(
            label = "Profile channel",
            summary = "PROFILE RUNTIME",
            unavailableMessage = "Profile channel could not be constructed.",
        ),
        programImage = programImage(),
        fingerprint = ProviderRevisionFingerprint("package-rev-1"),
        actorFactory = { context, capabilities, kernelBridge, policy ->
            ActorRuntimeFactory.createForGeneration(context, capabilities, kernelBridge, policy)
        },
        bridge = bridge,
        configurationProvider = CompiledConfigurationProvider(PROFILE_IMPLEMENTATION_ID, profileFieldDeclaration()),
        configurationFields = listOf(
            ChannelConfigurationField.DynamicChoiceField(
                id = "model_profile",
                label = "Model profile",
                source = DynamicConfigurationChoiceSourceId("profile:llm"),
                sourceKind = DynamicChoiceSourceKind.ProfileType("llm"),
                required = true,
            ),
        ),
        profileRecordProvider = profileLookup,
    )

    private fun profileFieldDeclaration(): PackageConfigurationDeclaration = PackageConfigurationDeclaration(
        ConfigurationDataDeclaration(listOf(
            ConfigurationFieldDeclaration.StringField(id = "model_profile", default = "", allowedValues = null),
        )),
        ConfigurationUiDeclaration(listOf(
            UiFieldDeclaration(
                field = "model_profile",
                control = UiControl.DYNAMIC_CHOICE,
                label = "Model profile",
                help = null,
                choices = null,
                source = "profile:llm",
            ),
        )),
    )

    private fun programImage(): ImmutableProgramImage = when (val result = ImmutableProgramImage.create(
        entryPoint = "plugin.profile_recovery",
        sourceMap = mapOf(
            "plugin.profile_recovery" to """
                return {
                    startup = function() end,
                    handle_readiness = function() return { ready = true } end,
                    handle_input = function(event) return { ok = true } end,
                }
            """.trimIndent(),
        ),
        requirements = LuaProgramRequirements(LUA_VERSION, API_VERSION),
    )) {
        is ProgramImageCreationResult.Success -> result.image
        is ProgramImageCreationResult.Failure -> throw AssertionError(result.error.message)
    }

    private fun profileRecord(id: String, revision: Long, displayName: String): ProfileRecord = ProfileRecord(
        profileId = ProfileId(id),
        typeIdentity = ProfileTypeIdentity(GitHubRepositoryIdentity("777"), "llm"),
        displayName = displayName,
        schemaVersion = ProfileLimits.SCHEMA_VERSION,
        scalarPayload = mapOf("model" to ProfileScalarValue.StringValue("gpt-test")),
        secretReferences = emptyMap(),
        revision = revision,
        availability = ProfileAvailability.AVAILABLE,
    )

    private fun definition(
        id: String,
        implementationId: ChannelImplementationId,
        payload: JSONObject = JSONObject(),
    ): ChannelDefinition = ChannelDefinition(
        id = id,
        name = id,
        implementationId = implementationId,
        enabled = true,
        configSchemaVersion = 1,
        configPayload = OpaqueJsonObject.fromJsonObject(payload),
    )

    /**
     * Contract-faithful JVM kernel boundary for grant ordering: records state
     * creation, grant installation, readiness refresh, and close in one ordered
     * event list. Readiness reports ready exactly when the state received a
     * profile grant snapshot — mirroring a package whose readiness depends on
     * its selected profile being granted.
     */
    private class GrantRecordingBridge : LuaKernelBridge {
        private data class State(val id: Long, var closed: Boolean = false)

        private var nextStateId = 1L
        private val states = linkedMapOf<Long, State>()

        val events = mutableListOf<String>()
        val createdStateIds = mutableListOf<Long>()
        val closedStateIds = mutableListOf<Long>()
        val grantsByState = linkedMapOf<Long, String>()

        override fun create(config: LuaKernelConfig): LuaKernelOutcome {
            val id = nextStateId++
            states[id] = State(id)
            createdStateIds += id
            events += "create:$id"
            return LuaKernelOutcome.Created(id, id, LUA_VERSION, API_VERSION, "profile-recovery")
        }

        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome = completed(handle)

        override fun start(handle: LuaStateHandle): LuaKernelOutcome = completed(handle)

        override fun resume(
            operation: LuaOperationHandle,
            success: Boolean,
            value: String,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = completed(operation.stateHandle)

        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = completed(operation.stateHandle)

        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = completed(handle)

        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = LuaKernelOutcome.Snapshot(
            handle.stateId.value, handle.generation.value, null,
            LUA_VERSION, API_VERSION, "profile-recovery",
        )

        override fun close(handle: LuaStateHandle): LuaKernelOutcome {
            val state = state(handle)
            if (!state.closed) {
                state.closed = true
                closedStateIds += state.id
                events += "close:${state.id}"
            }
            return LuaKernelOutcome.Closed(handle.stateId.value, handle.generation.value)
        }

        override fun loadProgramImage(
            handle: LuaStateHandle,
            entryPoint: String,
            sourceMap: Map<String, String>,
        ): LuaKernelOutcome = completed(handle, "[\"startup\",\"handle_readiness\",\"handle_input\"]")

        override fun invokeStartupCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            config: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = completed(handle)

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            val state = state(handle)
            if (callbackHandle.name == "handle_readiness") {
                events += "readiness:${state.id}"
                return completed(
                    handle,
                    if (state.id in grantsByState) "{\"ready\":true}"
                    else "{\"ready\":false,\"status\":\"selected profile unavailable\"}",
                )
            }
            return completed(handle)
        }

        override fun startCoroutine(
            handle: LuaStateHandle,
            coroutineId: LuaCoroutineId,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = completed(handle)

        override fun setResourceContext(handle: LuaStateHandle, resourceContextJson: String): LuaKernelOutcome =
            completed(handle)

        override fun setProfileGrants(handle: LuaStateHandle, grantsJson: String): LuaKernelOutcome {
            val state = state(handle)
            grantsByState[state.id] = grantsJson
            events += "grants:${state.id}"
            return completed(handle)
        }

        private fun state(handle: LuaStateHandle): State = states[handle.stateId.value]
            ?: error("unknown recording state ${handle.stateId.value}")

        private fun completed(handle: LuaStateHandle, value: String? = null): LuaKernelOutcome.Completed =
            LuaKernelOutcome.Completed(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                coroutineId = null,
                value = value,
                elapsedNanos = null,
                luaVersion = LUA_VERSION,
                bindingVersion = API_VERSION,
                topology = "profile-recovery",
                spawnedCoroutines = null,
            )
    }

    private class BuiltinFixtureProvider : ChannelImplementationProvider {
        override val fingerprint = ProviderRevisionFingerprint("test-fingerprint")
        val constructed = mutableListOf<String>()

        override val descriptor: ChannelImplementationDescriptor = ChannelImplementationDescriptor(
            implementationId = BUILTIN_IMPLEMENTATION_ID,
            presentation = ChannelPresentationMetadata("Builtin", "builtin summary", "builtin unavailable"),
            configuration = BuiltinConfigurationProvider,
            configurationFields = emptyList(),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )

        override suspend fun constructRuntime(
            request: ChannelRuntimeConstructionRequest,
        ): ChannelRuntimeConstructionResult {
            constructed += request.definition.id
            return ChannelRuntimeConstructionResult.Success(BuiltinFixtureRuntime(request.definition))
        }
    }

    private object BuiltinConfigurationProvider : ChannelConfigurationProvider {
        override val implementationId: ChannelImplementationId = BUILTIN_IMPLEMENTATION_ID
        override val currentSchemaVersion: Int = 1

        override fun defaultPayload(): OpaqueJsonObject = OpaqueJsonObject.fromJsonObject(JSONObject())

        override fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult =
            ProviderConfigurationResult.Success(ValidatedChannelConfiguration(implementationId, schemaVersion, payload))

        override fun migrateStep(fromSchemaVersion: Int, payload: OpaqueJsonObject): ChannelConfigurationMigrationStep =
            ChannelConfigurationMigrationStep.Failure(
                ChannelProviderError.UnsupportedSchemaVersion(implementationId, fromSchemaVersion, currentSchemaVersion),
            )
    }

    private class BuiltinFixtureRuntime(definition: ChannelDefinition) : ChannelRuntime {
        override val id: String = definition.id
        private val snapshots = MutableStateFlow(
            ChannelRuntimeSnapshot(
                id = definition.id,
                name = definition.name,
                implementationId = definition.implementationId,
                enabled = definition.enabled,
                preparation = ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.ProviderInitialising),
                executionStatus = ChannelExecutionStatus.IDLE,
            ),
        )
        override val snapshot: StateFlow<ChannelRuntimeSnapshot> = snapshots.asStateFlow()

        override suspend fun prepareInput(): ChannelInputAcceptance = ChannelInputAcceptance.Refused("builtin fixture")

        override suspend fun refreshReadiness() {
            snapshots.value = snapshots.value.copy(preparation = ChannelPreparationAvailability.Available)
        }

        override suspend fun close() {
            snapshots.value = snapshots.value.copy(
                preparation = ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed),
            )
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
        ): HostedCapabilityAcquisition<T> = HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = acquire(identity, key)
    }

    private companion object {
        val PROFILE_IMPLEMENTATION_ID = ChannelImplementationId("test:profile-channel")
        val BUILTIN_IMPLEMENTATION_ID = ChannelImplementationId("builtin:fixture")
    }
}
