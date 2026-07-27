package io.talkcan.lua.resolver

import io.talkcan.dependency.ChoiceResolverDeclaration
import io.talkcan.dependency.PackageCapability
import io.talkcan.dependency.PackageConfigurationLimits
import io.talkcan.http.GenericHttpTransport
import io.talkcan.lua.HostOperationClaim
import io.talkcan.lua.HostOperationKind
import io.talkcan.lua.HttpHostAdapter
import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaOperationId
import io.talkcan.lua.LuaStateGeneration
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaStateId
import io.talkcan.lua.ProfileGrantGeneration
import io.talkcan.lua.ProfileGrantInput
import io.talkcan.lua.SecretReadHostAdapter
import io.talkcan.lua.TypedHostCompletion
import io.talkcan.model.DynamicChoiceSourceKind
import io.talkcan.model.DynamicConfigurationChoice
import io.talkcan.model.DynamicConfigurationChoiceRequest
import io.talkcan.model.DynamicConfigurationChoiceResolution
import io.talkcan.model.DynamicConfigurationChoiceResolver
import io.talkcan.model.DynamicConfigurationChoiceSourceId
import io.talkcan.model.DynamicConfigurationChoiceSourceRegistry
import io.talkcan.model.DynamicConfigurationChoiceUnavailableReason
import io.talkcan.model.ProviderRevisionFingerprint
import io.talkcan.profile.ProfileAvailability
import io.talkcan.profile.ProfileRecord
import io.talkcan.secret.ProtectedSecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * One resolver declaration projected to its runtime authority (task 10.5).
 *
 * Carries only scalar identity plus the declared capability subset; no
 * provider, SDK, transport, or profile objects. Capability booleans are
 * derived from the manifest declaration's eligible capability set.
 */
internal data class ResolverDeclarationBinding(
    val resolverId: String,
    val moduleId: String,
    val secretsRead: Boolean,
    val networkHttp: Boolean,
)

/** Build one runtime binding from its manifest declaration. */
internal fun resolverBindingOf(declaration: ChoiceResolverDeclaration): ResolverDeclarationBinding =
    ResolverDeclarationBinding(
        resolverId = declaration.id,
        moduleId = declaration.module,
        secretsRead = PackageCapability.SECRETS_READ in declaration.capabilities,
        networkHttp = PackageCapability.NETWORK_HTTP in declaration.capabilities,
    )

/**
 * Atomically published resolver authority for one installed package revision
 * (task 10.7). Immutable; replacement publishes a fresh publication and
 * cancels executions bound to the predecessor.
 */
internal data class PackageResolverPublication(
    val repositoryId: Long,
    val packageRevision: ProviderRevisionFingerprint,
    val sourceMap: Map<String, String>,
    val resolvers: Map<String, ResolverDeclarationBinding>,
) {
    init {
        require(sourceMap.isNotEmpty()) { "Resolver publication source map must not be empty" }
        resolvers.values.forEach { binding ->
            require(binding.moduleId in sourceMap) {
                "Resolver module must exist in the published source map: ${binding.moduleId}"
            }
        }
    }
}

/**
 * One-shot restricted resolver execution (tasks 10.1–10.4 host side).
 *
 * Creates a fresh resolver-mode state per invocation, grants at most the one
 * selected dependency profile at its exact revision, invokes the declared
 * module once, answers any SECRET_READ/HTTP_REQUEST suspensions through the
 * same typed claim/resume path, validates the terminal publication
 * all-or-nothing, and always closes the state. The orchestrator holds no
 * cross-invocation authority: no cache, no mailbox, no channel actor, and no
 * work creation.
 */
internal class PackageResolverOrchestrator(
    private val bridge: LuaKernelBridge,
    private val kernelConfig: LuaKernelConfig,
    private val secretStore: ProtectedSecretStore?,
    private val httpTransportFactory: ((resolverToken: String) -> GenericHttpTransport)?,
    private val profileProvider: (profileId: String) -> ProfileRecord?,
    private val tokenGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * Run one resolver invocation under [deadline]. The result is bound to
     * the exact publication, resolver declaration, dependency profile
     * revision, and caller request identity; stale requests are denied
     * before any native state is created.
     */
    suspend fun resolve(
        publication: PackageResolverPublication,
        binding: ResolverDeclarationBinding,
        request: DynamicConfigurationChoiceRequest,
        deadline: Duration,
    ): DynamicConfigurationChoiceResolution {
        // ── Dependency profile + narrowed grant (D4/D8) ─────────────────
        val profileId = request.dependencyValue?.takeIf { it.isNotBlank() }
        var grants: ProfileGrantGeneration? = null
        if (profileId != null) {
            val record = profileProvider(profileId)
                ?: return unavailable(DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING)
            if (record.availability != ProfileAvailability.AVAILABLE ||
                record.typeIdentity.repositoryId.value.toLongOrNull() != publication.repositoryId
            ) {
                return unavailable(DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING)
            }
            if (request.profileRevision != null && record.revision != request.profileRevision) {
                // Stale request bound to a predecessor profile revision.
                return unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE)
            }
            grants = ProfileGrantGeneration.build(
                inputs = listOf(
                    ProfileGrantInput(
                        record = record,
                        typeLocalId = record.typeIdentity.localTypeId,
                        packageRepositoryId = publication.repositoryId,
                    ),
                ),
                includeSecrets = binding.secretsRead && secretStore != null,
                tokenGenerator = tokenGenerator,
            )
            if (grants.snapshots.isEmpty()) {
                return unavailable(DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING)
            }
        }

        // ── One-shot state lifecycle ────────────────────────────────────
        val created = bridge.createResolver(kernelConfig) as? LuaKernelOutcome.Created
            ?: return unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE)
        val handle = LuaStateHandle(
            stateId = LuaStateId(created.stateId),
            generation = LuaStateGeneration(created.generation),
        )
        try {
            return withTimeout(deadline.inWholeMilliseconds) {
                runInvocation(publication, binding, request, profileId, grants, handle)
            }
        } catch (_: TimeoutCancellationException) {
            return unavailable(DynamicConfigurationChoiceUnavailableReason.RESOLUTION_TIMED_OUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            bridge.close(handle)
        }
    }

    private suspend fun runInvocation(
        publication: PackageResolverPublication,
        binding: ResolverDeclarationBinding,
        request: DynamicConfigurationChoiceRequest,
        profileId: String?,
        grants: ProfileGrantGeneration?,
        handle: LuaStateHandle,
    ): DynamicConfigurationChoiceResolution {
        grants?.let { generation ->
            val grantOutcome = bridge.setProfileGrants(handle, generation.grantsJson())
            if (grantOutcome !is LuaKernelOutcome.Completed) {
                return unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE)
            }
        }

        val secretAdapter = grants?.registry?.let { registry ->
            secretStore?.let { store -> SecretReadHostAdapter(store, registry) }
        }
        val httpAdapter = if (binding.networkHttp) {
            httpTransportFactory?.let { factory -> HttpHostAdapter(factory(tokenGenerator())) }
        } else {
            null
        }

        var outcome = bridge.invokeResolver(handle, invocationJson(publication, binding, request, profileId))
        while (outcome is LuaKernelOutcome.Yielded) {
            val operation = LuaOperationHandle(
                stateHandle = handle,
                coroutineId = LuaCoroutineId(outcome.coroutineId),
                operationId = LuaOperationId(outcome.operationId),
            )
            val completion = dispatchClaim(handle, outcome, secretAdapter, httpAdapter)
            outcome = bridge.resume(operation, completion.success, completion.value)
        }
        return terminalResolution(outcome)
    }

    private suspend fun dispatchClaim(
        handle: LuaStateHandle,
        yielded: LuaKernelOutcome.Yielded,
        secretAdapter: SecretReadHostAdapter?,
        httpAdapter: HttpHostAdapter?,
    ): TypedHostCompletion {
        val requestId = yielded.value?.toLongOrNull()
            ?: return TypedHostCompletion.failure("E_HOST_FAILURE")
        return when (val claim = bridge.claimHostOperation(handle, requestId)) {
            is HostOperationClaim.Rejected -> TypedHostCompletion.failure(claim.errorCode)
            is HostOperationClaim.Admitted -> when (claim.kind) {
                HostOperationKind.SECRET_READ ->
                    secretAdapter?.complete(claim)
                        ?: TypedHostCompletion.failure("E_DENIED")
                HostOperationKind.HTTP_REQUEST ->
                    httpAdapter?.complete(claim)
                        ?: TypedHostCompletion.failure("E_DENIED")
                // Resolvers deny every other host operation family even if a
                // compromised module somehow yielded one past the kernel.
                else -> TypedHostCompletion.failure("E_DENIED")
            }
        }
    }

    private fun invocationJson(
        publication: PackageResolverPublication,
        binding: ResolverDeclarationBinding,
        request: DynamicConfigurationChoiceRequest,
        profileId: String?,
    ): String = JSONObject().apply {
        put("sourceMap", JSONObject().apply {
            publication.sourceMap.forEach { (moduleId, source) -> put(moduleId, source) }
        })
        put("moduleId", binding.moduleId)
        put("capabilities", JSONObject().apply {
            put("secretsRead", binding.secretsRead)
            put("networkHttp", binding.networkHttp)
        })
        put("request", JSONObject().apply {
            put("schema_version", 1)
            put("resolver", binding.resolverId)
            val dependencyField = request.dependencyFieldId
            val dependencyValue = request.dependencyValue
            if (!dependencyField.isNullOrBlank() && !dependencyValue.isNullOrBlank()) {
                put("dependency", JSONObject().apply {
                    put("field", dependencyField)
                    put("value", dependencyValue)
                })
            }
            if (profileId != null) put("profile", profileId)
        })
    }.toString()

    /**
     * Validate the terminal outcome and re-validate the publication
     * all-or-nothing before the editor or readiness can observe it. The kernel is
     * authoritative for actor safety and bounds; the host re-validates
     * representability for the generic choice registry.
     */
    private fun terminalResolution(outcome: LuaKernelOutcome): DynamicConfigurationChoiceResolution =
        when (outcome) {
            is LuaKernelOutcome.Completed -> parseCompleted(outcome.value)
            is LuaKernelOutcome.Interrupted ->
                unavailable(DynamicConfigurationChoiceUnavailableReason.RESOLUTION_TIMED_OUT)
            is LuaKernelOutcome.Cancelled ->
                unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE)
            else -> unavailable(DynamicConfigurationChoiceUnavailableReason.DISCOVERY_FAILED)
        }

    private fun parseCompleted(value: String?): DynamicConfigurationChoiceResolution {
        val root = value?.let { raw ->
            try {
                JSONObject(raw)
            } catch (_: Exception) {
                null
            }
        } ?: return unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE)
        if (root.optString("operation") != "invokeResolver") {
            return unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE)
        }
        return when (root.optString("resultKind")) {
            "choices" -> {
                val array = root.optJSONArray("choices")
                    ?: return unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE)
                publishChoices(array)
            }
            "package_error" ->
                unavailable(DynamicConfigurationChoiceUnavailableReason.DISCOVERY_FAILED)
            else -> unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE)
        }
    }

    /** All-or-nothing choice publication revalidation (mirrors the registry). */
    private fun publishChoices(array: JSONArray): DynamicConfigurationChoiceResolution {
        val choices = ArrayList<DynamicConfigurationChoice>(array.length())
        val uniqueIds = HashSet<String>(array.length())
        val uniqueLabels = HashSet<String>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index)
                ?: return unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE)
            val choiceValue = entry.opt("value") as? String
            val label = entry.opt("label") as? String
            if (choiceValue.isNullOrBlank() || label.isNullOrBlank()) {
                return unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE)
            }
            if (!boundedUtf8(choiceValue, PackageConfigurationLimits.MAX_STRING_VALUE_BYTES) ||
                !boundedUtf8(label, PackageConfigurationLimits.MAX_LABEL_BYTES)
            ) {
                return unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE)
            }
            if (!uniqueIds.add(choiceValue) || !uniqueLabels.add(label)) {
                return unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE)
            }
            choices.add(DynamicConfigurationChoice(id = choiceValue, label = label))
        }
        return DynamicConfigurationChoiceResolution.Available(choices)
    }

    private fun boundedUtf8(value: String, maximumBytes: Int): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return bytes.size <= maximumBytes && String(bytes, Charsets.UTF_8) == value
    }

    private fun unavailable(
        reason: DynamicConfigurationChoiceUnavailableReason,
    ): DynamicConfigurationChoiceResolution = DynamicConfigurationChoiceResolution.Unavailable(reason)
}

/**
 * Publication-bound resolver factory (tasks 10.5–10.7).
 *
 * Implements the generic [DynamicConfigurationChoiceResolver] for
 * package-resolver sources: one bounded concurrency quota shared by all
 * resolver executions, deadline enforcement, request-identity gating against
 * the published package revision, and post-completion stale suppression when
 * the publication is replaced mid-flight. [publish] and [invalidate] cancel
 * every in-flight resolver execution so predecessor authority never
 * completes after package update/rollback/removal or dependency-profile
 * change.
 */
internal class PackageResolverFactory(
    private val orchestrator: PackageResolverOrchestrator,
    private val defaultDeadline: Duration = DEFAULT_DEADLINE,
    private val maxConcurrentResolvers: Int = DEFAULT_MAX_CONCURRENT,
) : DynamicConfigurationChoiceResolver {
    init {
        require(maxConcurrentResolvers > 0) { "Resolver concurrency quota must be positive" }
        require(defaultDeadline > Duration.ZERO) {
            "Resolver deadline must be positive"
        }
    }

    private val publicationRef = AtomicReference<PackageResolverPublication?>(null)
    private val quota = Semaphore(maxConcurrentResolvers)
    private val inflight: MutableSet<Job> =
        Collections.newSetFromMap(ConcurrentHashMap<Job, Boolean>())

    /** Currently published authority, if any. */
    val publication: PackageResolverPublication? get() = publicationRef.get()

    /**
     * Atomically publish resolver authority for one installed package
     * revision, cancelling executions bound to any predecessor publication.
     */
    fun publish(publication: PackageResolverPublication) {
        publicationRef.set(publication)
        cancelInflight()
    }

    /**
     * Drop resolver authority and cancel every in-flight execution (package
     * removal/rollback or dependency-profile invalidation).
     */
    fun invalidate() {
        publicationRef.set(null)
        cancelInflight()
    }

    val inflightCount: Int get() = inflight.size

    private fun cancelInflight() {
        inflight.forEach { it.cancel() }
    }

    override suspend fun resolve(
        request: DynamicConfigurationChoiceRequest,
    ): DynamicConfigurationChoiceResolution {
        val kind = request.effectiveSourceKind as? DynamicChoiceSourceKind.PackageResolver
            ?: return DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
            )
        val publication = publicationRef.get()
            ?: return DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
            )
        val binding = publication.resolvers[kind.resolverId]
            ?: return DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
            )
        // Stale request identity: a request bound to a predecessor package
        // revision never executes against the successor publication.
        if (request.packageRevision != null && request.packageRevision != publication.packageRevision) {
            return DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
            )
        }

        val job = coroutineContext[Job]
        if (job != null) inflight.add(job)
        try {
            val deadlineMillis = defaultDeadline.inWholeMilliseconds
            val startNanos = System.nanoTime()
            val acquired = try {
                withTimeout(deadlineMillis) { quota.acquire(); true }
            } catch (_: TimeoutCancellationException) {
                false
            }
            if (!acquired) {
                return DynamicConfigurationChoiceResolution.Unavailable(
                    DynamicConfigurationChoiceUnavailableReason.RESOLUTION_TIMED_OUT,
                )
            }
            return try {
                val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L
                val remainingMillis = deadlineMillis - elapsedMillis
                if (remainingMillis <= 0L) {
                    DynamicConfigurationChoiceResolution.Unavailable(
                        DynamicConfigurationChoiceUnavailableReason.RESOLUTION_TIMED_OUT,
                    )
                } else {
                    val result = orchestrator.resolve(
                        publication, binding, request, remainingMillis.milliseconds,
                    )
                    // Post-completion stale suppression: a publication replaced
                    // during execution invalidates its result before publication.
                    if (publicationRef.get() !== publication) {
                        DynamicConfigurationChoiceResolution.Unavailable(
                            DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
                        )
                    } else {
                        result
                    }
                }
            } finally {
                quota.release()
            }
        } finally {
            if (job != null) inflight.remove(job)
        }
    }

    companion object {
        val DEFAULT_DEADLINE: Duration = 20.seconds
        const val DEFAULT_MAX_CONCURRENT: Int = 2
    }
}

/**
 * Register one package-resolver factory under its conventional source id
 * (`resolver:<resolverId>`) in the generic dynamic-choice source registry
 * (task 10.7), scoped to the declaring [repositoryId]. The exact prefixed source
 * format matches what manifest/configuration fields emit, and the repository scope
 * keeps two installed repositories declaring the same local resolver ID from
 * colliding on one scalar source. The registry owns the per-source deadline and
 * all-or-nothing publication validation above the factory.
 */
internal fun PackageResolverFactory.registerResolverSource(
    registry: DynamicConfigurationChoiceSourceRegistry,
    resolverId: String,
    deadline: Duration = PackageResolverFactory.DEFAULT_DEADLINE,
    repositoryId: io.talkcan.dependency.GitHubRepositoryIdentity? = null,
) {
    registry.register(
        DynamicConfigurationChoiceSourceId(io.talkcan.dependency.DynamicChoiceSource.RESOLVER_PREFIX + resolverId),
        deadline,
        repositoryId,
        this,
    )
}
