package io.talkcan.lua

import io.talkcan.channel.capability.ChannelCapability
import io.talkcan.dependency.PackageResourcesDeclaration
import io.talkcan.channel.capability.ChannelCapabilityScope
import io.talkcan.model.GenerationExecutionContext
import io.talkcan.model.ProviderRevisionFingerprint
import io.talkcan.lua.actor.ActorConstructResult
import io.talkcan.lua.actor.ActorPolicy
import io.talkcan.lua.actor.ActorRuntimeCreationResult
import io.talkcan.lua.actor.ActorRuntimeFactory
import io.talkcan.model.ChannelConfigurationMigrationStep
import io.talkcan.model.ChannelConfigurationProvider
import io.talkcan.model.ChannelConfigurationField
import io.talkcan.model.ChannelImplementationDescriptor
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelImplementationProvider
import io.talkcan.model.ChannelPreparationTraits
import io.talkcan.model.ChannelPresentationMetadata
import io.talkcan.model.ChannelProviderError
import io.talkcan.model.ChannelRuntimeConstructionRequest
import io.talkcan.model.ChannelRuntimeConstructionResult
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.model.ProviderConfigurationResult
import io.talkcan.model.ValidatedChannelConfiguration
import org.json.JSONArray
import org.json.JSONObject
import io.talkcan.channel.capability.CapabilityPreparerRegistry
import io.talkcan.dependency.PackageCapability
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.KeyboardOutputAdapter
import io.talkcan.channel.capability.OutputExecutionOwner

internal data class LuaRuntimeResources(
    val storagePort: io.talkcan.storage.MountedStoragePort,
    val audioFilePortFactory: LuaAudioFilePortFactory,
    val mountReadinessStatus: LuaMountReadinessStatus,
    val close: () -> Unit,
)

internal fun interface LuaRuntimeResourcesFactory {
    fun create(
        request: ChannelRuntimeConstructionRequest,
        declarations: PackageResourcesDeclaration,
    ): LuaRuntimeResources
}

/**
 * Host-supplied Lua implementation provider.
 *
 * Program source and identity remain outside catalogue configuration: [programImage]
 * is supplied to this constructor, is immutable, and is never persisted. Constructing
 * the provider itself is lazy and creates neither a Lua actor nor a Lua state.
 */
internal class LuaChannelImplementationProvider private constructor(
    private val implementationId: ChannelImplementationId,
    private val presentation: ChannelPresentationMetadata,
    private val imageResult: ProgramImageCreationResult,
    override val fingerprint: ProviderRevisionFingerprint,
    private val actorFactory: (GenerationExecutionContext, ChannelCapabilityScope, LuaKernelBridge, ActorPolicy) -> ActorRuntimeCreationResult,
    private val bridge: LuaKernelBridge,
    private val actorPolicy: ActorPolicy,
    private val validationBounds: ValidationBounds,
    private val logSink: PluginLogSink = NoOpPluginLogSink,
    private val configurationProvider: ChannelConfigurationProvider,
    private val configurationProviderFields: List<ChannelConfigurationField> = emptyList(),
    private val configurationRequiredCapabilities: Set<ChannelCapability> = emptySet(),
    private val resourceDeclarations: PackageResourcesDeclaration = PackageResourcesDeclaration(emptyList()),
    private val storagePort: io.talkcan.storage.MountedStoragePort? = null,
    private val audioFilePortFactory: LuaAudioFilePortFactory? = null,
    private val mountReadinessStatus: LuaMountReadinessStatus? = null,
    private val runtimeResourcesFactory: LuaRuntimeResourcesFactory? = null,
    private val preparerRegistry: CapabilityPreparerRegistry = CapabilityPreparerRegistry.empty(),
    private val declaredPublicCapabilities: Set<String> = emptySet(),
    private val keyboardOutputAdapterFactory:
        ((CapabilityScopeIdentity, OutputExecutionOwner) -> KeyboardOutputAdapter)? = null,
    private val dynamicChoiceResolver: io.talkcan.model.DynamicConfigurationChoiceResolver? = null,
    // ── Task 13.4/13.5/9.7: adapter composition parameters ─────────────
    // Stores/transports are shared across generations; per-generation adapters
    // are created in constructRuntime. No provider object, credential, or OpenAI
    // shape is referenced.
    private val secretStore: io.talkcan.secret.ProtectedSecretStore? = null,
    private val httpTransport: io.talkcan.http.GenericHttpTransport? = null,
    private val workStore: io.talkcan.work.DurableWorkStore? = null,
    private val workCoordinator: io.talkcan.work.DurableWorkCoordinator? = null,
    private val profileRecordProvider: (suspend (String) -> io.talkcan.profile.ProfileRecord?)? = null,
    private val declaredWorkQueueIds: Set<String> = emptySet(),
    private val packageRepositoryId: Long = 0L,
) : ChannelImplementationProvider {

    companion object {
        private val RECOGNIZED_CALLBACKS = setOf(
            "startup", "handle_lifecycle", "handle_input", "handle_sos", "handle_readiness",
        )

        fun create(
            implementationId: ChannelImplementationId,
            presentation: ChannelPresentationMetadata,
            programImage: ImmutableProgramImage,
            fingerprint: ProviderRevisionFingerprint,
            actorFactory: (GenerationExecutionContext, ChannelCapabilityScope, LuaKernelBridge, ActorPolicy) -> ActorRuntimeCreationResult,
            bridge: LuaKernelBridge,
            actorPolicy: ActorPolicy = ActorPolicy.startingEvidence(),
            validationBounds: ValidationBounds = ValidationBounds.DEFAULT,
            logSink: PluginLogSink = NoOpPluginLogSink,
            configurationProvider: ChannelConfigurationProvider,
            configurationFields: List<ChannelConfigurationField> = emptyList(),
            requiredCapabilities: Set<ChannelCapability> = emptySet(),
            resourceDeclarations: PackageResourcesDeclaration = PackageResourcesDeclaration(emptyList()),
            storagePort: io.talkcan.storage.MountedStoragePort? = null,
            audioFilePortFactory: LuaAudioFilePortFactory? = null,
            mountReadinessStatus: LuaMountReadinessStatus? = null,
            runtimeResourcesFactory: LuaRuntimeResourcesFactory? = null,
            preparerRegistry: CapabilityPreparerRegistry = CapabilityPreparerRegistry.empty(),
            declaredPublicCapabilities: Set<String> = emptySet(),
            keyboardOutputAdapterFactory:
                ((CapabilityScopeIdentity, OutputExecutionOwner) -> KeyboardOutputAdapter)? = null,
            dynamicChoiceResolver: io.talkcan.model.DynamicConfigurationChoiceResolver? = null,
            secretStore: io.talkcan.secret.ProtectedSecretStore? = null,
            httpTransport: io.talkcan.http.GenericHttpTransport? = null,
            workStore: io.talkcan.work.DurableWorkStore? = null,
            workCoordinator: io.talkcan.work.DurableWorkCoordinator? = null,
            profileRecordProvider: (suspend (String) -> io.talkcan.profile.ProfileRecord?)? = null,
            declaredWorkQueueIds: Set<String> = emptySet(),
            packageRepositoryId: Long = 0L,
        ) = LuaChannelImplementationProvider(
            implementationId = implementationId,
            presentation = presentation,
            imageResult = ProgramImageCreationResult.Success(programImage),
            fingerprint = fingerprint,
            actorFactory = actorFactory,
            bridge = bridge,
            actorPolicy = actorPolicy,
            validationBounds = validationBounds,
            logSink = logSink,
            configurationProvider = configurationProvider,
            configurationProviderFields = configurationFields,
            configurationRequiredCapabilities = requiredCapabilities,
            resourceDeclarations = resourceDeclarations,
            storagePort = storagePort,
            audioFilePortFactory = audioFilePortFactory,
            mountReadinessStatus = mountReadinessStatus,
            runtimeResourcesFactory = runtimeResourcesFactory,
            preparerRegistry = preparerRegistry,
            declaredPublicCapabilities = declaredPublicCapabilities,
            keyboardOutputAdapterFactory = keyboardOutputAdapterFactory,
            dynamicChoiceResolver = dynamicChoiceResolver,
            secretStore = secretStore,
            httpTransport = httpTransport,
            workStore = workStore,
            workCoordinator = workCoordinator,
            profileRecordProvider = profileRecordProvider,
            declaredWorkQueueIds = declaredWorkQueueIds,
            packageRepositoryId = packageRepositoryId,
        )

        /**
         * Test-visible factory that accepts a [ProgramImageCreationResult] directly,
         * allowing tests to exercise failure-projection paths with incompatible images.
         */
        internal fun fromImageResult(
            implementationId: ChannelImplementationId,
            presentation: ChannelPresentationMetadata,
            imageResult: ProgramImageCreationResult,
            fingerprint: ProviderRevisionFingerprint,
            actorFactory: (GenerationExecutionContext, ChannelCapabilityScope, LuaKernelBridge, ActorPolicy) -> ActorRuntimeCreationResult,
            bridge: LuaKernelBridge,
            actorPolicy: ActorPolicy = ActorPolicy.startingEvidence(),
            validationBounds: ValidationBounds = ValidationBounds.DEFAULT,
            logSink: PluginLogSink = NoOpPluginLogSink,
            configurationProvider: ChannelConfigurationProvider,
            configurationFields: List<ChannelConfigurationField> = emptyList(),
            requiredCapabilities: Set<ChannelCapability> = emptySet(),
            resourceDeclarations: PackageResourcesDeclaration = PackageResourcesDeclaration(emptyList()),
            storagePort: io.talkcan.storage.MountedStoragePort? = null,
            preparerRegistry: CapabilityPreparerRegistry = CapabilityPreparerRegistry.empty(),
            declaredPublicCapabilities: Set<String> = emptySet(),
            keyboardOutputAdapterFactory:
                ((CapabilityScopeIdentity, OutputExecutionOwner) -> KeyboardOutputAdapter)? = null,
            dynamicChoiceResolver: io.talkcan.model.DynamicConfigurationChoiceResolver? = null,
        ) = LuaChannelImplementationProvider(
            implementationId = implementationId,
            presentation = presentation,
            imageResult = imageResult,
            fingerprint = fingerprint,
            actorFactory = actorFactory,
            bridge = bridge,
            actorPolicy = actorPolicy,
            validationBounds = validationBounds,
            logSink = logSink,
            configurationProvider = configurationProvider,
            configurationProviderFields = configurationFields,
            configurationRequiredCapabilities = requiredCapabilities,
            resourceDeclarations = resourceDeclarations,
            storagePort = storagePort,
            preparerRegistry = preparerRegistry,
            declaredPublicCapabilities = declaredPublicCapabilities,
            keyboardOutputAdapterFactory = keyboardOutputAdapterFactory,
            dynamicChoiceResolver = dynamicChoiceResolver,
        )
    }

    override val descriptor = ChannelImplementationDescriptor(
        implementationId = implementationId,
        presentation = presentation,
        configuration = configurationProvider,
        configurationFields = configurationProviderFields,
        requiredCapabilities = configurationRequiredCapabilities,
        preparationTraits = ChannelPreparationTraits(
            supportsRecoverablePreparation =
                declaredPublicCapabilities.any { preparerRegistry.isPreparable(it) },
        ),
        resourceDeclarations = resourceDeclarations,
    )

    /**
     * Validates requirements and immutable image before actor/state creation,
     * then creates exactly one actor and one state for this generation.
     */
    override suspend fun constructRuntime(
        request: ChannelRuntimeConstructionRequest,
    ): ChannelRuntimeConstructionResult {
        val programImage = when (val result = imageResult) {
            is ProgramImageCreationResult.Success -> result.image
            is ProgramImageCreationResult.Failure -> {
                return ChannelRuntimeConstructionResult.Failure(result.error.toProviderError())
            }
        }
        compatibilityFailure(programImage)?.let { return ChannelRuntimeConstructionResult.Failure(it) }
        when (val validation = ProgramImageValidator.validate(programImage, validationBounds)) {
            ProgramImageValidationResult.Success -> Unit
            is ProgramImageValidationResult.Failure -> {
                return ChannelRuntimeConstructionResult.Failure(validation.error.toProviderError())
            }
        }

        val actor = when (
            val result = actorFactory(
                request.generationContext,
                request.capabilities,
                bridge,
                actorPolicy,
            )
        ) {
            is ActorRuntimeCreationResult.Success -> result.actor
            is ActorRuntimeCreationResult.Failure -> {
                return ChannelRuntimeConstructionResult.Failure(constructionFailure(result.detail))
            }
        }

        val constructResult = try {
            actor.construct()
        } catch (error: Throwable) {
            actor.close()
            return ChannelRuntimeConstructionResult.Failure(constructionFailure(error.message ?: "actor construction failed"))
        }
        val stateHandle = when (val result = constructResult) {
            is ActorConstructResult.Success -> result.stateHandle
            ActorConstructResult.AlreadyConstructed -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(constructionFailure("actor state was already constructed"))
            }
            is ActorConstructResult.FatalFailure -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(constructionFailure(result.diagnostic))
            }
        }

        // Install the immutable instance identity and declared resource
        // authority before any package source is evaluated.
        when (val rc = bridge.setResourceContext(stateHandle, buildResourceContextJson(request))) {
            is LuaKernelOutcome.Completed -> Unit
            is LuaKernelOutcome.ValidationFailure -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(constructionFailure(rc.diagnostic))
            }
            else -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(
                    constructionFailure("resource context installation failed"),
                )
            }
        }


        val imageLoad = try {
            bridge.loadProgramImage(
                handle = stateHandle,
                entryPoint = programImage.entryPoint,
                sourceMap = programImage.sourceMap,
            )
        } catch (error: Throwable) {
            actor.close()
            return ChannelRuntimeConstructionResult.Failure(
                constructionFailure(error.message ?: "program image loading failed"),
            )
        }
        val callbacks = when (val result = imageLoad) {
            is LuaKernelOutcome.Completed -> {
                val parsedCallbacks = callbackHandles(stateHandle, result.value)
                if (parsedCallbacks.isFailure) {
                    actor.close()
                    return ChannelRuntimeConstructionResult.Failure(
                        constructionFailure(parsedCallbacks.exceptionOrNull()?.message ?: "invalid callback list"),
                    )
                }
                parsedCallbacks.getOrThrow()
            }
            is LuaKernelOutcome.ValidationFailure -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(constructionFailure(result.diagnostic))
            }
            is LuaKernelOutcome.SyntaxFailure -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(constructionFailure(result.diagnostic))
            }
            is LuaKernelOutcome.RuntimeFailure -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(constructionFailure(result.diagnostic))
            }
            is LuaKernelOutcome.Interrupted -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(
                    constructionFailure(result.diagnostic ?: "program image evaluation interrupted"),
                )
            }
            else -> {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(
                    constructionFailure("unexpected program image outcome: ${result::class.simpleName}"),
                )
            }
        }

        // ── Task 13.4: Build detached profile grants and generic adapters ──
        // Profile grants are built from selected profiles in the validated
        // configuration. Only AVAILABLE records produce grants; unavailable
        // or missing profiles are silently skipped (the runtime reports
        // unavailability through its capability context). No provider object,
        // credential, or OpenAI shape is consulted.
        val profileGrants = buildProfileGrants(request)
        profileGrants?.let { generation ->
            val grantOutcome = bridge.setProfileGrants(stateHandle, generation.grantsJson())
            if (grantOutcome !is LuaKernelOutcome.Completed) {
                actor.close()
                return ChannelRuntimeConstructionResult.Failure(
                    constructionFailure("profile grant installation failed"),
                )
            }
        }

        // Task 13.5/9.7: Create per-generation adapters from shared stores.
        // Each adapter is bound to this generation's token registry and
        // declared authority; generation revocation (close) discards them.
        val secretAdapter = if (secretStore != null && profileGrants != null) {
            SecretReadHostAdapter(secretStore, profileGrants.registry)
        } else null
        val httpAdapter = httpTransport?.let { HttpHostAdapter(it) }
        val workAdapter = if (workStore != null && workCoordinator != null && declaredWorkQueueIds.isNotEmpty()) {
            WorkHostAdapter(
                store = workStore,
                coordinator = workCoordinator,
                repositoryId = io.talkcan.work.WorkRepositoryId(packageRepositoryId),
                instanceId = io.talkcan.work.WorkInstanceId(request.definition.id),
                holderToken = java.util.UUID.randomUUID().toString(),
                declaredQueues = declaredWorkQueueIds,
            )
        } else null

        val runtimeResources = runtimeResourcesFactory?.create(request, resourceDeclarations)
        return ChannelRuntimeConstructionResult.Success(
            LuaAdapterRuntime(
                definition = request.definition,
                actor = actor,
                generationContext = request.generationContext,
                stateHandle = stateHandle,
                bridge = bridge,
                callbacks = callbacks,
                configuration = request.configuration,
                logSink = logSink,
                capabilities = request.capabilities,
                initialSummary = presentation.summary,
                storagePort = runtimeResources?.storagePort ?: storagePort,
                audioFilePortFactory =
                    runtimeResources?.audioFilePortFactory ?: audioFilePortFactory,
                resourceDeclarations = resourceDeclarations,
                mountReadinessStatus =
                    runtimeResources?.mountReadinessStatus ?: mountReadinessStatus,
                resourceClose = runtimeResources?.close,
                preparerRegistry = preparerRegistry,
                keyboardOutputAdapterFactory = keyboardOutputAdapterFactory,
                dynamicChoiceResolver = dynamicChoiceResolver,
                requiredDynamicFields = configurationProviderFields
                    .filterIsInstance<ChannelConfigurationField.DynamicChoiceField>()
                    .filter { it.required },
                packageRevision = fingerprint,
                secretReadAdapter = secretAdapter,
                httpHostAdapter = httpAdapter,
                workHostAdapter = workAdapter,
            ),
        )
    }

    /**
     * Build detached profile grants from selected profiles in the validated
     * configuration (task 13.4). For each profile-type dynamic-choice field,
     * the selected profile ID is looked up through [profileRecordProvider];
     * only AVAILABLE records produce grants. Returns null when no provider is
     * wired or no profile-type fields are declared. No provider object,
     * credential, or OpenAI shape is consulted.
     */
    private suspend fun buildProfileGrants(
        request: ChannelRuntimeConstructionRequest,
    ): ProfileGrantGeneration? {
        val provider = profileRecordProvider ?: return null
        val profileFields = configurationProviderFields
            .filterIsInstance<ChannelConfigurationField.DynamicChoiceField>()
            .filter { it.sourceKind is io.talkcan.model.DynamicChoiceSourceKind.ProfileType }
        if (profileFields.isEmpty()) return null
        val inputs = ArrayList<ProfileGrantInput>(profileFields.size)
        val payload = request.configuration.payload.toJsonObject()
        for (field in profileFields) {
            val selectedId = payload
                .optString(field.id, "")
                .takeIf { it.isNotBlank() } ?: continue
            val record = provider(selectedId) ?: continue
            val typeLocalId = (field.sourceKind as io.talkcan.model.DynamicChoiceSourceKind.ProfileType).typeId
            inputs.add(
                ProfileGrantInput(
                    record = record,
                    typeLocalId = typeLocalId,
                    packageRepositoryId = packageRepositoryId,
                ),
            )
        }
        if (inputs.isEmpty()) return null
        val includeSecrets = PackageCapability.SECRETS_READ in declaredPublicCapabilities
        return ProfileGrantGeneration.build(inputs, includeSecrets = includeSecrets)
    }

    /**
     * Build the kernel resource-context JSON from the declared capabilities and
     * `resources.mounts`. Mount `status` reflects the resolved live binding; the
     * generic binding layer supplies it per generation — declared mounts default
     * to `available` here and fail closed at operation time via the storage port
     * when no live grant backs them.
     */
    private fun buildResourceContextJson(request: ChannelRuntimeConstructionRequest): String {
        val storageDeclared = ChannelCapability.StorageFiles in request.capabilities.declaredCapabilities
        val audioFilesDeclared =
            ChannelCapability.AudioFiles in request.capabilities.declaredCapabilities
        val keyboardOutputDeclared = PackageCapability.KEYBOARD_OUTPUT in declaredPublicCapabilities
        val secretsReadDeclared =
            ChannelCapability.SecretsRead in request.capabilities.declaredCapabilities
        val networkHttpDeclared =
            ChannelCapability.NetworkHttp in request.capabilities.declaredCapabilities
        val workQueueDeclared =
            ChannelCapability.WorkQueue in request.capabilities.declaredCapabilities
        val workQueues = JSONArray()
        if (workQueueDeclared) {
            declaredWorkQueueIds.sorted().forEach(workQueues::put)
        }
        val mounts = JSONObject()
        for (mount in resourceDeclarations.mounts) {
            mounts.put(
                mount.id,
                JSONObject()
                    .put("access", mount.access.value)
                    .put("status", "available"),
            )
        }
        return JSONObject()
            .put("instanceId", request.definition.id)
            .put("storageFiles", storageDeclared)
            .put("audioFiles", audioFilesDeclared)
            .put("keyboardOutput", keyboardOutputDeclared)
            .put("secretsRead", secretsReadDeclared)
            .put("networkHttp", networkHttpDeclared)
            .put("workQueue", workQueueDeclared)
            .put("workQueues", workQueues)
            .put("mounts", mounts)
            .toString()
    }

    /** Exact version mapping happens before all actor/state work. */
    private fun compatibilityFailure(programImage: ImmutableProgramImage): ChannelProviderError.RuntimeCompatibilityFailure? = when {
        programImage.requirements.luaVersion != LUA_VERSION -> ChannelProviderError.RuntimeCompatibilityFailure(
            implementationId = descriptor.implementationId,
            requirement = "luaVersion",
            requiredVersion = programImage.requirements.luaVersion,
            supportedVersion = LUA_VERSION,
        )
        programImage.requirements.apiVersion != API_VERSION -> ChannelProviderError.RuntimeCompatibilityFailure(
            implementationId = descriptor.implementationId,
            requirement = "apiVersion",
            requiredVersion = programImage.requirements.apiVersion,
            supportedVersion = API_VERSION,
        )
        else -> null
    }

    private fun constructionFailure(detail: String): ChannelProviderError.RuntimeConstructionFailed =
        ChannelProviderError.RuntimeConstructionFailed(descriptor.implementationId, detail)

    private fun callbackHandles(
        stateHandle: LuaStateHandle,
        encodedNames: String?,
    ): Result<Map<String, LuaCallbackHandle>> = runCatching {
        val names = JSONArray(encodedNames ?: error("missing callback list"))
        val callbacks = buildMap {
            for (index in 0 until names.length()) {
                val name = names.opt(index) as? String ?: error("callback name at index $index is not a string")
                if (name !in RECOGNIZED_CALLBACKS) continue
                check(put(name, LuaCallbackHandle(stateHandle, name)) == null) { "duplicate callback '$name'" }
            }
        }
        require("startup" in callbacks) { "required callback 'startup' is missing" }
        callbacks
    }

    private fun ProgramImageValidationError.toProviderError(): ChannelProviderError = when (this) {
        is ProgramImageValidationError.IncompatibleRequirements -> ChannelProviderError.RuntimeCompatibilityFailure(
            implementationId = descriptor.implementationId,
            requirement = requirement,
            requiredVersion = requiredVersion,
            supportedVersion = supportedVersion,
        )
        else -> constructionFailure(message)
    }

}


/**
 * Actor-mediation adapter seam for the generic HTTP capability (task 9.7).
 *
 * The native `talkcan.http.request` mediation (task 9.2) acquires [CapabilityKey.NetworkHttp]
 * from the generation's [ChannelCapabilityScope], then routes each admitted request through a
 * [LuaGenericHttpMediator] bound to the owning package/instance/generation/execution-owner. The
 * mediator wraps the leased [GenericHttpCapability] with [AdmittedGenericHttpTransport] so
 * concurrency and retained-byte quotas are charged per owner (task 9.6). All bounds, TLS, and
 * redirect policy remain host-owned by the underlying transport; this seam constructs no provider
 * request, injects no credential, and interprets no response body.
 */
internal class LuaGenericHttpMediator private constructor(
    private val transport: io.talkcan.http.GenericHttpTransport,
) {
    suspend fun request(
        request: io.talkcan.http.GenericHttpRequest,
    ): io.talkcan.http.GenericHttpResult = transport.request(request)

    companion object {
        /**
         * Build an admitted, owner-scoped mediator over a leased [GenericHttpCapability].
         * Per-owner admission accounting is charged to [owner]; all bounds/TLS remain host-owned
         * by the underlying transport. No provider request is constructed and no credential is injected.
         */
        fun create(
            capability: io.talkcan.channel.capability.GenericHttpCapability,
            owner: io.talkcan.http.GenericHttpOwner,
            controller: io.talkcan.http.GenericHttpAdmissionController,
        ): LuaGenericHttpMediator = LuaGenericHttpMediator(
            io.talkcan.http.AdmittedGenericHttpTransport(
                owner,
                controller,
                CapabilityAsTransport(capability),
            ),
        )
    }
}

private class CapabilityAsTransport(
    private val capability: io.talkcan.channel.capability.GenericHttpCapability,
) : io.talkcan.http.GenericHttpTransport {
    override suspend fun request(
        request: io.talkcan.http.GenericHttpRequest,
    ): io.talkcan.http.GenericHttpResult = capability.request(request)
}
