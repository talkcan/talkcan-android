package io.talkcan.lua.resolver

import io.talkcan.lua.HostOperationClaim
import io.talkcan.lua.HostOperationKind
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaSpawnAdmission
import io.talkcan.lua.LuaStateHandle
import io.talkcan.model.DynamicChoiceSourceKind
import io.talkcan.model.DynamicConfigurationChoiceRequest
import io.talkcan.model.DynamicConfigurationChoiceResolution
import io.talkcan.model.DynamicConfigurationChoiceSourceId
import io.talkcan.model.DynamicConfigurationChoiceSourceRegistry
import io.talkcan.model.DynamicConfigurationChoiceUnavailableReason
import io.talkcan.model.ProviderRevisionFingerprint
import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.profile.ProfileAvailability
import io.talkcan.profile.ProfileId
import io.talkcan.profile.ProfileRecord
import io.talkcan.profile.ProfileScalarValue
import io.talkcan.profile.ProfileTypeIdentity
import io.talkcan.profile.SecretReferenceState
import io.talkcan.secret.PreparedSecretMutation
import io.talkcan.secret.ProtectedSecretError
import io.talkcan.secret.ProtectedSecretReference
import io.talkcan.secret.ProtectedSecretResult
import io.talkcan.secret.ProtectedSecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tasks 10.5–10.8/10.10: JVM integration coverage for the one-shot package
 * resolver orchestrator, publication-bound factory, and generic source
 * registry routing. The scripted fake bridge stands in for the native
 * resolver state; every test defends an observable contract: exact grant
 * narrowing, typed suspension answers, all-or-nothing publication, revision
 * identity gating, quota serialization, cancellation/invalidation, and
 * stale-result suppression.
 */
class PackageResolverRuntimeTest {

    private val revision = ProviderRevisionFingerprint("rev-1")

    private fun profileRecord(
        id: String = "profile-1",
        repositoryId: String = "77",
        revision: Long = 7L,
        availability: ProfileAvailability = ProfileAvailability.AVAILABLE,
    ): ProfileRecord = ProfileRecord(
        profileId = ProfileId(id),
        typeIdentity = ProfileTypeIdentity(GitHubRepositoryIdentity(repositoryId), "openai_compatible"),
        displayName = "Connection",
        schemaVersion = 1,
        scalarPayload = mapOf("base_url" to ProfileScalarValue.StringValue("https://api.example/v1")),
        secretReferences = mapOf("api_key" to SecretReferenceState.Present("ref-1")),
        revision = revision,
        availability = availability,
    )

    private fun publication(
        resolvers: Map<String, ResolverDeclarationBinding> = mapOf(
            "models" to ResolverDeclarationBinding(
                resolverId = "models",
                moduleId = "pkg.models",
                secretsRead = true,
                networkHttp = false,
            ),
        ),
        repositoryId: Long = 77L,
    ): PackageResolverPublication = PackageResolverPublication(
        repositoryId = repositoryId,
        packageRevision = revision,
        sourceMap = mapOf("pkg.models" to "return { resolve = function(req) end }"),
        resolvers = resolvers,
    )

    private fun request(
        resolverId: String = "models",
        profileId: String? = "profile-1",
        profileRevision: Long? = 7L,
        packageRevision: ProviderRevisionFingerprint? = revision,
        callerRequestId: String = "req-1",
        repositoryId: GitHubRepositoryIdentity? = null,
    ): DynamicConfigurationChoiceRequest = DynamicConfigurationChoiceRequest(
        source = DynamicConfigurationChoiceSourceId("resolver:$resolverId"),
        dependencyValue = profileId,
        sourceKind = DynamicChoiceSourceKind.PackageResolver(resolverId, repositoryId),
        packageRevision = packageRevision,
        requestingFieldId = "model",
        dependencyFieldId = "profile",
        profileRevision = profileRevision,
        callerRequestId = callerRequestId,
    )

    private fun choicesJson(vararg pairs: Pair<String, String>): String = JSONObject()
        .put("kind", "completed")
        .put("operation", "invokeResolver")
        .put("resultKind", "choices")
        .put("choices", JSONArray().apply {
            pairs.forEach { (value, label) -> put(JSONObject().put("value", value).put("label", label)) }
        })
        .toString()

    private fun packageErrorJson(error: String): String = JSONObject()
        .put("kind", "completed")
        .put("operation", "invokeResolver")
        .put("resultKind", "package_error")
        .put("error", error)
        .toString()

    private fun completed(value: String?): LuaKernelOutcome = LuaKernelOutcome.Completed(
        stateId = 1L,
        generation = 0L,
        coroutineId = null,
        value = value,
        elapsedNanos = null,
        luaVersion = null,
        bindingVersion = null,
        topology = null,
    )

    private fun yielded(operationId: Long = 9L, requestId: String = "42"): LuaKernelOutcome =
        LuaKernelOutcome.Yielded(
            stateId = 1L,
            generation = 0L,
            coroutineId = 5L,
            operationId = operationId,
            value = requestId,
        )

    private fun secretClaim(referenceToken: String): HostOperationClaim = HostOperationClaim.Admitted(
        requestId = 42L,
        kind = HostOperationKind.SECRET_READ,
        audioToken = null,
        text = null,
        language = null,
        voice = null,
        speed = 1.0,
        delaySeconds = 0.0,
        referenceToken = referenceToken,
    )

    private fun httpClaim(): HostOperationClaim = HostOperationClaim.Admitted(
        requestId = 42L,
        kind = HostOperationKind.HTTP_REQUEST,
        audioToken = null,
        text = null,
        language = null,
        voice = null,
        speed = 1.0,
        delaySeconds = 0.0,
        method = "GET",
        url = "https://api.example/v1/models",
        timeoutMs = 5_000L,
    )

    private fun workClaim(): HostOperationClaim = HostOperationClaim.Admitted(
        requestId = 42L,
        kind = HostOperationKind.WORK_SUBMIT,
        audioToken = null,
        text = null,
        language = null,
        voice = null,
        speed = 1.0,
        delaySeconds = 0.0,
        queue = "turns",
        payloadJson = "{\"t\":\"text\",\"v\":\"x\"}",
    )

    private class InMemorySecretStore(
        private val values: Map<String, String> = emptyMap(),
    ) : ProtectedSecretStore {
        override fun prepareCreate(reference: ProtectedSecretReference, plaintext: CharSequence) =
            denied<PreparedSecretMutation>()

        override fun prepareReplace(
            oldReference: ProtectedSecretReference,
            newReference: ProtectedSecretReference,
            plaintext: CharSequence,
        ) = denied<PreparedSecretMutation>()

        override fun prepareClear(reference: ProtectedSecretReference) = denied<PreparedSecretMutation>()
        override fun prepareDelete(reference: ProtectedSecretReference) = denied<PreparedSecretMutation>()
        override fun contains(reference: ProtectedSecretReference): Boolean = reference.token in values
        override fun <T> use(reference: ProtectedSecretReference, block: (CharSequence) -> T): ProtectedSecretResult<T> {
            val plaintext = values[reference.token]
                ?: return ProtectedSecretResult.Failure(ProtectedSecretError.NotFound)
            return ProtectedSecretResult.Success(block(plaintext))
        }

        private fun <T> denied(): ProtectedSecretResult<T> =
            ProtectedSecretResult.Failure(ProtectedSecretError.Denied)
    }

    /**
     * Scripted native bridge stand-in. The first scripted outcome answers
     * `invokeResolver`; each resume consumes the next. Claims are answered
     * from a queue; an empty queue rejects like a closed state.
     */
    private class FakeBridge : LuaKernelBridge {
        val scriptedOutcomes = ArrayDeque<LuaKernelOutcome>()
        val claims = ArrayDeque<HostOperationClaim>()
        val resumed = mutableListOf<Triple<Long, Boolean, String>>()
        val grantsJsons = mutableListOf<String>()
        val invocationJsons = mutableListOf<String>()
        val closedHandles = mutableListOf<LuaStateHandle>()
        @Volatile var createCount = 0
        var gate: CompletableDeferred<Unit>? = null

        override fun createResolver(config: LuaKernelConfig): LuaKernelOutcome {
            createCount++
            return LuaKernelOutcome.Created(
                stateId = 1L,
                generation = 0L,
                luaVersion = "Lua 5.4",
                bindingVersion = "test",
                topology = "resolver",
            )
        }

        override fun setProfileGrants(handle: LuaStateHandle, grantsJson: String): LuaKernelOutcome {
            grantsJsons.add(grantsJson)
            return LuaKernelOutcome.Completed(
                stateId = 1L,
                generation = 0L,
                coroutineId = null,
                value = null,
                elapsedNanos = null,
                luaVersion = null,
                bindingVersion = null,
                topology = null,
            )
        }

        override fun invokeResolver(handle: LuaStateHandle, invocationJson: String): LuaKernelOutcome {
            invocationJsons.add(invocationJson)
            gate?.let { runBlocking { it.await() } }
            return scriptedOutcomes.removeFirst()
        }

        override fun claimHostOperation(
            handle: LuaStateHandle,
            requestId: Long,
        ): HostOperationClaim =
            claims.removeFirstOrNull() ?: HostOperationClaim.Rejected("E_CLOSED")

        override fun resume(
            operation: LuaOperationHandle,
            success: Boolean,
            value: String,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            resumed.add(Triple(operation.operationId.value, success, value))
            return scriptedOutcomes.removeFirst()
        }

        override fun close(handle: LuaStateHandle): LuaKernelOutcome {
            closedHandles.add(handle)
            return LuaKernelOutcome.Closed(handle.stateId.value, handle.generation.value)
        }

        override fun create(config: LuaKernelConfig): LuaKernelOutcome = unused()
        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome = unused()
        override fun start(handle: LuaStateHandle): LuaKernelOutcome = unused()
        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = unused()
        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = unused()
        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = unused()
        override fun loadProgramImage(
            handle: LuaStateHandle,
            entryPoint: String,
            sourceMap: Map<String, String>,
        ): LuaKernelOutcome = unused()

        override fun invokeStartupCallback(
            handle: LuaStateHandle,
            callbackHandle: io.talkcan.lua.LuaCallbackHandle,
            config: io.talkcan.lua.LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = unused()

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: io.talkcan.lua.LuaCallbackHandle,
            arguments: io.talkcan.lua.LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = unused()

        override fun startCoroutine(
            handle: LuaStateHandle,
            coroutineId: io.talkcan.lua.LuaCoroutineId,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = unused()

        private fun unused(): Nothing =
            throw AssertionError("Resolver tests never touch channel-actor bridge paths")
    }

    private fun orchestrator(
        bridge: FakeBridge,
        store: ProtectedSecretStore? = InMemorySecretStore(mapOf("ref-1" to "sk-test")),
        profiles: Map<String, ProfileRecord> = mapOf("profile-1" to profileRecord()),
        tokenGenerator: () -> String = { "tok-1" },
    ): PackageResolverOrchestrator = PackageResolverOrchestrator(
        bridge = bridge,
        kernelConfig = LuaKernelConfig(
            hookInterval = 100,
            instructionBudget = 100_000L,
            maxConcurrentTasks = 1,
        ),
        secretStore = store,
        httpTransportFactory = null,
        profileProvider = { id -> profiles[id] },
        tokenGenerator = tokenGenerator,
    )

    // ── Terminal publication ─────────────────────────────────────────────

    @Test
    fun choicesPublishExactlyAndCloseAlwaysRuns() = runBlocking {
        val bridge = FakeBridge()
        bridge.scriptedOutcomes.addLast(
            completed(choicesJson("gpt-4" to "GPT-4", "gpt-3.5" to "GPT-3.5")),
        )
        val result = orchestrator(bridge).resolve(publication(), binding(), request(), deadline)
        val available = result as DynamicConfigurationChoiceResolution.Available
        assertEquals(
            listOf("gpt-4" to "GPT-4", "gpt-3.5" to "GPT-3.5"),
            available.choices.map { it.id to it.label },
        )
        assertEquals(1, bridge.createCount)
        assertEquals(1, bridge.closedHandles.size)
    }

    @Test
    fun emptyChoicesAreAValidPublication() = runBlocking {
        val bridge = FakeBridge()
        bridge.scriptedOutcomes.addLast(completed(choicesJson()))
        val result = orchestrator(bridge).resolve(publication(), binding(), request(), deadline)
        val available = result as DynamicConfigurationChoiceResolution.Available
        assertTrue(available.choices.isEmpty())
    }

    @Test
    fun packageErrorProjectsDiscoveryFailed() = runBlocking {
        val bridge = FakeBridge()
        bridge.scriptedOutcomes.addLast(completed(packageErrorJson("upstream auth failed")))
        val result = orchestrator(bridge).resolve(publication(), binding(), request(), deadline)
        assertEquals(
            unavailable(DynamicConfigurationChoiceUnavailableReason.DISCOVERY_FAILED),
            result,
        )
        assertEquals(1, bridge.closedHandles.size)
    }

    @Test
    fun bridgeFaultsMapToTypedUnavailableStatesAndClose() = runBlocking {
        val runtimeFault = FakeBridge()
        runtimeFault.scriptedOutcomes.addLast(
            LuaKernelOutcome.RuntimeFailure(null, null, "module shape"),
        )
        assertEquals(
            unavailable(DynamicConfigurationChoiceUnavailableReason.DISCOVERY_FAILED),
            orchestrator(runtimeFault).resolve(publication(), binding(), request(), deadline),
        )
        assertEquals(1, runtimeFault.closedHandles.size)

        val interrupted = FakeBridge()
        interrupted.scriptedOutcomes.addLast(
            LuaKernelOutcome.Interrupted(1L, 0L, "budget", null),
        )
        assertEquals(
            unavailable(DynamicConfigurationChoiceUnavailableReason.RESOLUTION_TIMED_OUT),
            orchestrator(interrupted).resolve(publication(), binding(), request(), deadline),
        )
        assertEquals(1, interrupted.closedHandles.size)
    }

    @Test
    fun malformedChoiceEntriesDiscardTheWholePublication() = runBlocking {
        val blank = FakeBridge()
        blank.scriptedOutcomes.addLast(completed(choicesJson("" to "Label")))
        assertEquals(
            unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE),
            orchestrator(blank).resolve(publication(), binding(), request(), deadline),
        )

        val duplicate = FakeBridge()
        duplicate.scriptedOutcomes.addLast(
            completed(choicesJson("a" to "A", "a" to "A2")),
        )
        assertEquals(
            unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE),
            orchestrator(duplicate).resolve(publication(), binding(), request(), deadline),
        )
    }

    // ── Request identity gating ──────────────────────────────────────────

    @Test
    fun staleProfileRevisionDeniedBeforeAnyStateCreation() = runBlocking {
        val bridge = FakeBridge()
        val result = orchestrator(bridge).resolve(
            publication(), binding(), request(profileRevision = 99L), deadline,
        )
        assertEquals(
            unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE),
            result,
        )
        assertEquals(0, bridge.createCount)
        assertTrue(bridge.closedHandles.isEmpty())
    }

    @Test
    fun missingDependencyProfileIsDependencyMissing() = runBlocking {
        val bridge = FakeBridge()
        val result = orchestrator(bridge, profiles = emptyMap())
            .resolve(publication(), binding(), request(), deadline)
        assertEquals(
            unavailable(DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING),
            result,
        )
        assertEquals(0, bridge.createCount)
    }

    @Test
    fun foreignRepositoryProfileIsDependencyMissing() = runBlocking {
        val bridge = FakeBridge()
        val result = orchestrator(
            bridge,
            profiles = mapOf("profile-1" to profileRecord(repositoryId = "5")),
        ).resolve(publication(), binding(), request(), deadline)
        assertEquals(
            unavailable(DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING),
            result,
        )
        assertEquals(0, bridge.createCount)
    }

    // ── Grant narrowing + typed suspension ───────────────────────────────

    @Test
    fun grantIsNarrowedToTheSingleDependencyProfile() = runBlocking {
        val bridge = FakeBridge()
        bridge.scriptedOutcomes.addLast(completed(choicesJson()))
        orchestrator(bridge).resolve(publication(), binding(), request(), deadline)
        assertEquals(1, bridge.grantsJsons.size)
        val profiles = JSONObject(bridge.grantsJsons.single()).getJSONArray("profiles")
        assertEquals(1, profiles.length())
        val grant = profiles.getJSONObject(0)
        assertEquals("profile-1", grant.getString("profileId"))
        assertEquals("openai_compatible", grant.getString("typeLocalId"))
        assertEquals("Connection", grant.getString("displayName"))
        assertEquals(
            "https://api.example/v1",
            grant.getJSONObject("values").getJSONObject("base_url").getString("v"),
        )
        assertEquals("tok-1", grant.getJSONObject("secretReferences").getString("api_key"))
    }

    @Test
    fun noGrantInjectedWhenNoDependencyProfile() = runBlocking {
        val bridge = FakeBridge()
        bridge.scriptedOutcomes.addLast(completed(choicesJson()))
        orchestrator(bridge).resolve(
            publication(), binding(), request(profileId = null, profileRevision = null), deadline,
        )
        assertTrue(bridge.grantsJsons.isEmpty())
        val invocationRequest = JSONObject(bridge.invocationJsons.single()).getJSONObject("request")
        assertFalse(invocationRequest.has("profile"))
        assertFalse(invocationRequest.has("dependency"))
    }

    @Test
    fun invocationJsonCarriesExactContractShape() = runBlocking {
        val bridge = FakeBridge()
        bridge.scriptedOutcomes.addLast(completed(choicesJson()))
        orchestrator(bridge).resolve(publication(), binding(), request(), deadline)
        val invocation = JSONObject(bridge.invocationJsons.single())
        assertEquals("pkg.models", invocation.getString("moduleId"))
        assertTrue(invocation.getJSONObject("sourceMap").has("pkg.models"))
        assertFalse(invocation.getJSONObject("capabilities").getBoolean("networkHttp"))
        assertTrue(invocation.getJSONObject("capabilities").getBoolean("secretsRead"))
        val resolverRequest = invocation.getJSONObject("request")
        assertEquals(1, resolverRequest.getInt("schema_version"))
        assertEquals("models", resolverRequest.getString("resolver"))
        assertEquals("profile", resolverRequest.getJSONObject("dependency").getString("field"))
        assertEquals("profile-1", resolverRequest.getJSONObject("dependency").getString("value"))
        assertEquals("profile-1", resolverRequest.getString("profile"))
    }

    @Test
    fun secretSuspensionIsAnsweredThroughTheNarrowedGrant() = runBlocking {
        val bridge = FakeBridge()
        bridge.scriptedOutcomes.addLast(yielded(operationId = 9L))
        bridge.scriptedOutcomes.addLast(completed(choicesJson("m" to "Model")))
        bridge.claims.addLast(secretClaim("tok-1"))
        val result = orchestrator(bridge).resolve(publication(), binding(), request(), deadline)
        assertTrue(result is DynamicConfigurationChoiceResolution.Available)
        assertEquals(1, bridge.resumed.size)
        val (operationId, success, value) = bridge.resumed.single()
        assertEquals(9L, operationId)
        assertTrue(success)
        assertEquals("sk-test", JSONObject(value).getString("plaintext"))
    }

    @Test
    fun guessedSecretTokenResumesDeniedWithoutPlaintext() = runBlocking {
        val bridge = FakeBridge()
        bridge.scriptedOutcomes.addLast(yielded())
        bridge.scriptedOutcomes.addLast(completed(choicesJson()))
        bridge.claims.addLast(secretClaim("guessed-token"))
        orchestrator(bridge).resolve(publication(), binding(), request(), deadline)
        val (_, success, value) = bridge.resumed.single()
        assertFalse(success)
        assertEquals("E_DENIED", value)
    }

    @Test
    fun httpSuspensionDeniedWhenCapabilitySubsetExcludesIt() = runBlocking {
        val bridge = FakeBridge()
        bridge.scriptedOutcomes.addLast(yielded())
        bridge.scriptedOutcomes.addLast(completed(choicesJson()))
        bridge.claims.addLast(httpClaim())
        orchestrator(bridge).resolve(publication(), binding(), request(), deadline)
        val (_, success, value) = bridge.resumed.single()
        assertFalse(success)
        assertEquals("E_DENIED", value)
    }

    @Test
    fun workSuspensionIsAlwaysDeniedInResolvers() = runBlocking {
        val bridge = FakeBridge()
        bridge.scriptedOutcomes.addLast(yielded())
        bridge.scriptedOutcomes.addLast(completed(choicesJson()))
        bridge.claims.addLast(workClaim())
        orchestrator(bridge).resolve(publication(), binding(), request(), deadline)
        val (_, success, value) = bridge.resumed.single()
        assertFalse(success)
        assertEquals("E_DENIED", value)
    }

    @Test
    fun rejectedClaimIsResumedWithItsErrorCode() = runBlocking {
        val bridge = FakeBridge()
        bridge.scriptedOutcomes.addLast(yielded())
        bridge.scriptedOutcomes.addLast(completed(choicesJson()))
        // Empty claim queue → Rejected("E_CLOSED").
        orchestrator(bridge).resolve(publication(), binding(), request(), deadline)
        val (_, success, value) = bridge.resumed.single()
        assertFalse(success)
        assertEquals("E_CLOSED", value)
    }

    // ── Factory: publication, quota, invalidation ────────────────────────

    @Test
    fun unpublishedFactoryDeniesWithoutOrchestratorUse() = runBlocking {
        val bridge = FakeBridge()
        val factory = PackageResolverFactory(orchestrator(bridge))
        assertEquals(
            unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE),
            factory.resolve(request()),
        )
        assertEquals(0, bridge.createCount)
    }

    @Test
    fun unknownResolverIdIsSourceUnavailable() = runBlocking {
        val bridge = FakeBridge()
        val factory = PackageResolverFactory(orchestrator(bridge))
        factory.publish(publication())
        assertEquals(
            unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE),
            factory.resolve(request(resolverId = "other")),
        )
        assertEquals(0, bridge.createCount)
    }

    @Test
    fun stalePackageRevisionRequestNeverExecutes() = runBlocking {
        val bridge = FakeBridge()
        val factory = PackageResolverFactory(orchestrator(bridge))
        factory.publish(publication())
        assertEquals(
            unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE),
            factory.resolve(request(packageRevision = ProviderRevisionFingerprint("999999"))),
        )
        assertEquals(0, bridge.createCount)
    }

    @Test
    fun nonResolverSourceKindIsRejected() = runBlocking {
        val bridge = FakeBridge()
        val factory = PackageResolverFactory(orchestrator(bridge))
        factory.publish(publication())
        val hostRequest = DynamicConfigurationChoiceRequest(
            source = DynamicConfigurationChoiceSourceId("resolver:models"),
        )
        assertEquals(
            unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE),
            factory.resolve(hostRequest),
        )
    }

    @Test
    fun quotaSerializesAndSecondWaitsForFirst() = runBlocking {
        val bridge = FakeBridge()
        val gate = CompletableDeferred<Unit>()
        bridge.gate = gate
        bridge.scriptedOutcomes.addLast(completed(choicesJson("m" to "Model")))
        bridge.scriptedOutcomes.addLast(completed(choicesJson("n" to "Next")))
        val factory = PackageResolverFactory(
            orchestrator(bridge),
            defaultDeadline = 10.seconds,
            maxConcurrentResolvers = 1,
        )
        factory.publish(publication())

        val first = async(Dispatchers.Default) { factory.resolve(request()) }
        val second = async(Dispatchers.Default) { factory.resolve(request(callerRequestId = "req-2")) }
        // The first holds the sole permit inside the scripted invocation;
        // the second must remain suspended on the quota.
        repeat(50) { yield() }
        assertFalse(second.isCompleted)

        gate.complete(Unit)
        assertTrue(first.await() is DynamicConfigurationChoiceResolution.Available)
        assertTrue(second.await() is DynamicConfigurationChoiceResolution.Available)
        assertEquals(2, bridge.createCount)
        assertEquals(0, factory.inflightCount)
    }

    @Test
    fun quotaExhaustionTimesOutAsResolutionTimedOut() = runBlocking {
        val bridge = FakeBridge()
        val gate = CompletableDeferred<Unit>()
        bridge.gate = gate
        bridge.scriptedOutcomes.addLast(completed(choicesJson()))
        val factory = PackageResolverFactory(
            orchestrator(bridge),
            defaultDeadline = 150.milliseconds,
            maxConcurrentResolvers = 1,
        )
        factory.publish(publication())
        val first = async(Dispatchers.Default) { factory.resolve(request()) }
        withTimeout(5_000) {
            while (bridge.createCount < 1) delay(10)
        }
        val second = factory.resolve(request(callerRequestId = "req-2"))
        assertEquals(
            unavailable(DynamicConfigurationChoiceUnavailableReason.RESOLUTION_TIMED_OUT),
            second,
        )
        gate.complete(Unit)
        first.await()
        Unit
    }

    @Test
    fun invalidateCancelsInflightAndQuotaWaiters() = runBlocking {
        val bridge = FakeBridge()
        val gate = CompletableDeferred<Unit>()
        bridge.gate = gate
        bridge.scriptedOutcomes.addLast(completed(choicesJson()))
        val factory = PackageResolverFactory(
            orchestrator(bridge),
            defaultDeadline = 10.seconds,
            maxConcurrentResolvers = 1,
        )
        factory.publish(publication())

        val first = async(Dispatchers.Default) { factory.resolve(request()) }
        val second = async(Dispatchers.Default) { factory.resolve(request(callerRequestId = "req-2")) }
        // Deterministic barrier: wait until first has created its resolver
        // state (past quota + createResolver, now blocked in invokeResolver)
        // and second is registered and suspended on the sole quota permit.
        withTimeout(5_000) {
            while (bridge.createCount < 1 || factory.inflightCount < 2) delay(10)
        }

        factory.invalidate()
        gate.complete(Unit)
        assertThrows(CancellationException::class.java) { runBlocking { second.await() } }
        assertThrows(CancellationException::class.java) { runBlocking { first.await() } }
        // The one-shot state is always closed, even on cancellation.
        assertTrue(bridge.closedHandles.isNotEmpty())
        assertEquals(0, factory.inflightCount)
        assertNull(factory.publication)
    }

    @Test
    fun publishReplacementCancelsPredecessorExecutions() = runBlocking {
        val bridge = FakeBridge()
        val gate = CompletableDeferred<Unit>()
        bridge.gate = gate
        bridge.scriptedOutcomes.addLast(completed(choicesJson("old" to "Old")))
        bridge.scriptedOutcomes.addLast(completed(choicesJson("new" to "New")))
        val factory = PackageResolverFactory(
            orchestrator(bridge),
            defaultDeadline = 10.seconds,
            maxConcurrentResolvers = 2,
        )
        factory.publish(publication())
        val stale = async(Dispatchers.Default) { factory.resolve(request()) }
        withTimeout(1_000) { while (factory.inflightCount == 0) delay(10) }

        factory.publish(publication())
        gate.complete(Unit)
        assertThrows(CancellationException::class.java) { runBlocking { stale.await() } }

        val fresh = factory.resolve(request())
        val available = fresh as DynamicConfigurationChoiceResolution.Available
        assertEquals("new", available.choices.single().id)
    }

    // ── Registry routing (10.7/10.8) ─────────────────────────────────────

    @Test
    fun registryRoutesResolverSourceThroughTheFactory() = runBlocking {
        val bridge = FakeBridge()
        bridge.scriptedOutcomes.addLast(completed(choicesJson("m" to "Model")))
        val factory = PackageResolverFactory(orchestrator(bridge))
        factory.publish(publication())
        val registry = DynamicConfigurationChoiceSourceRegistry()
        factory.registerResolverSource(registry, "models", 5.seconds)

        val resolution = registry.resolve(request())
        val available = resolution as DynamicConfigurationChoiceResolution.Available
        assertEquals(listOf("m"), available.choices.map { it.id })
    }

    @Test
    fun duplicateResolverSourceRegistrationIsRejected() {
        val bridge = FakeBridge()
        val factory = PackageResolverFactory(orchestrator(bridge))
        val registry = DynamicConfigurationChoiceSourceRegistry()
        factory.registerResolverSource(registry, "models")
        assertThrows(IllegalArgumentException::class.java) {
            factory.registerResolverSource(registry, "models")
        }
    }

    @Test
    fun scopedResolverSourceRoutesEachRepositoryToItsOwnFactory() = runBlocking {
        val bridgeA = FakeBridge()
        bridgeA.scriptedOutcomes.addLast(completed(choicesJson("a" to "Repo A model")))
        val bridgeB = FakeBridge()
        bridgeB.scriptedOutcomes.addLast(completed(choicesJson("b" to "Repo B model")))
        val repoA = GitHubRepositoryIdentity("1")
        val repoB = GitHubRepositoryIdentity("2")
        val factoryA = PackageResolverFactory(orchestrator(bridgeA))
        val factoryB = PackageResolverFactory(orchestrator(bridgeB))
        factoryA.publish(publication(repositoryId = 1L))
        factoryB.publish(publication(repositoryId = 2L))
        val registry = DynamicConfigurationChoiceSourceRegistry()
        factoryA.registerResolverSource(registry, "models", 5.seconds, repoA)
        factoryB.registerResolverSource(registry, "models", 5.seconds, repoB)

        val resolutionA = registry.resolve(request(profileId = null, profileRevision = null, repositoryId = repoA))
        val resolutionB = registry.resolve(request(profileId = null, profileRevision = null, repositoryId = repoB))

        assertEquals(listOf("a"), (resolutionA as DynamicConfigurationChoiceResolution.Available).choices.map { it.id })
        assertEquals(listOf("b"), (resolutionB as DynamicConfigurationChoiceResolution.Available).choices.map { it.id })
    }

    @Test
    fun sameLocalResolverIdRegistersUnderBothRepositoriesWithoutCollision() {
        val factoryA = PackageResolverFactory(orchestrator(FakeBridge()))
        val factoryB = PackageResolverFactory(orchestrator(FakeBridge()))
        val registry = DynamicConfigurationChoiceSourceRegistry()
        factoryA.registerResolverSource(registry, "models", repositoryId = GitHubRepositoryIdentity("1"))
        factoryB.registerResolverSource(registry, "models", repositoryId = GitHubRepositoryIdentity("2"))
        // A duplicate under the exact same (source, repository) is still rejected.
        assertThrows(IllegalArgumentException::class.java) {
            factoryA.registerResolverSource(registry, "models", repositoryId = GitHubRepositoryIdentity("1"))
        }
    }

    @Test
    fun unscopedRequestResolvesTheUniqueScopedRegistration() = runBlocking {
        val bridge = FakeBridge()
        bridge.scriptedOutcomes.addLast(completed(choicesJson("m" to "Model")))
        val factory = PackageResolverFactory(orchestrator(bridge))
        factory.publish(publication())
        val registry = DynamicConfigurationChoiceSourceRegistry()
        factory.registerResolverSource(registry, "models", 5.seconds, GitHubRepositoryIdentity("77"))

        // A legacy/editor request without repository authority still resolves the single
        // installed repository's source.
        val resolution = registry.resolve(request(profileId = null, profileRevision = null))
        val available = resolution as DynamicConfigurationChoiceResolution.Available
        assertEquals(listOf("m"), available.choices.map { it.id })
    }

    @Test
    fun ambiguousUnscopedRequestFailsClosedAcrossRepositories() = runBlocking {
        val factoryA = PackageResolverFactory(orchestrator(FakeBridge()))
        val factoryB = PackageResolverFactory(orchestrator(FakeBridge()))
        factoryA.publish(publication(repositoryId = 1L))
        factoryB.publish(publication(repositoryId = 2L))
        val registry = DynamicConfigurationChoiceSourceRegistry()
        factoryA.registerResolverSource(registry, "models", repositoryId = GitHubRepositoryIdentity("1"))
        factoryB.registerResolverSource(registry, "models", repositoryId = GitHubRepositoryIdentity("2"))

        assertEquals(
            unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE),
            registry.resolve(request(profileId = null, profileRevision = null)),
        )
    }

    @Test
    fun scopedRequestForAnUnregisteredRepositoryIsUnavailable() = runBlocking {
        val factory = PackageResolverFactory(orchestrator(FakeBridge()))
        factory.publish(publication())
        val registry = DynamicConfigurationChoiceSourceRegistry()
        factory.registerResolverSource(registry, "models", repositoryId = GitHubRepositoryIdentity("1"))

        assertEquals(
            unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE),
            registry.resolve(request(profileId = null, profileRevision = null, repositoryId = GitHubRepositoryIdentity("2"))),
        )
    }

    @Test
    fun removalUnregistersOnlyTheRemovedRepository() = runBlocking {
        val bridgeB = FakeBridge()
        bridgeB.scriptedOutcomes.addLast(completed(choicesJson("b" to "Repo B model")))
        val repoA = GitHubRepositoryIdentity("1")
        val repoB = GitHubRepositoryIdentity("2")
        val factoryA = PackageResolverFactory(orchestrator(FakeBridge()))
        val factoryB = PackageResolverFactory(orchestrator(bridgeB))
        factoryA.publish(publication(repositoryId = 1L))
        factoryB.publish(publication(repositoryId = 2L))
        val registry = DynamicConfigurationChoiceSourceRegistry()
        factoryA.registerResolverSource(registry, "models", repositoryId = repoA)
        factoryB.registerResolverSource(registry, "models", repositoryId = repoB)

        // Remove repository A: invalidate its factory and drop its scoped registration.
        factoryA.invalidate()
        registry.unregister(DynamicConfigurationChoiceSourceId("resolver:models"), repoA)

        assertEquals(
            unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE),
            registry.resolve(request(profileId = null, profileRevision = null, repositoryId = repoA)),
        )
        val resolutionB = registry.resolve(request(profileId = null, profileRevision = null, repositoryId = repoB))
        assertEquals(listOf("b"), (resolutionB as DynamicConfigurationChoiceResolution.Available).choices.map { it.id })
    }

    @Test
    fun republishUnderTheSameRepositoryReusesTheRegistration() = runBlocking {
        val bridge = FakeBridge()
        bridge.scriptedOutcomes.addLast(completed(choicesJson("old" to "Old")))
        bridge.scriptedOutcomes.addLast(completed(choicesJson("new" to "New")))
        val repo = GitHubRepositoryIdentity("77")
        val factory = PackageResolverFactory(orchestrator(bridge))
        val registry = DynamicConfigurationChoiceSourceRegistry()
        factory.registerResolverSource(registry, "models", 5.seconds, repo)
        factory.publish(publication())

        val first = registry.resolve(request(profileId = null, profileRevision = null, repositoryId = repo))
        assertEquals(listOf("old"), (first as DynamicConfigurationChoiceResolution.Available).choices.map { it.id })

        // Update republishes a fresh authority under the same repository; the scoped source
        // is registered exactly once, so re-registering the same (source, repository) throws.
        factory.publish(publication())
        assertThrows(IllegalArgumentException::class.java) {
            factory.registerResolverSource(registry, "models", 5.seconds, repo)
        }
        val second = registry.resolve(request(profileId = null, profileRevision = null, repositoryId = repo))
        assertEquals(listOf("new"), (second as DynamicConfigurationChoiceResolution.Available).choices.map { it.id })
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun binding(): ResolverDeclarationBinding = ResolverDeclarationBinding(
        resolverId = "models",
        moduleId = "pkg.models",
        secretsRead = true,
        networkHttp = false,
    )

    private val deadline: Duration = 5.seconds

    private fun unavailable(
        reason: DynamicConfigurationChoiceUnavailableReason,
    ): DynamicConfigurationChoiceResolution = DynamicConfigurationChoiceResolution.Unavailable(reason)
}
