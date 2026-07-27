package io.talkcan.lua

import io.talkcan.channel.capability.ChannelCapability
import io.talkcan.channel.capability.CapabilityPreparerRegistry
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.KeyboardOutputAdapter
import io.talkcan.channel.capability.OutputExecutionOwner
import io.talkcan.dependency.ConfigurationFieldDeclaration
import io.talkcan.dependency.DynamicChoiceSourceReference
import io.talkcan.dependency.InstalledProviderId
import io.talkcan.dependency.PackageCapability
import io.talkcan.dependency.PackageConfigurationDeclaration
import io.talkcan.dependency.PackageConfigurationLimits
import io.talkcan.dependency.UiChoice
import io.talkcan.dependency.UiControl
import io.talkcan.dependency.UiFieldDeclaration
import io.talkcan.dependency.ValidatedPackageRevision
import io.talkcan.lua.actor.ActorPolicy
import io.talkcan.lua.actor.ActorRuntimeFactory
import io.talkcan.model.ChannelConfigurationField
import io.talkcan.model.ChannelConfigurationMigrationStep
import io.talkcan.model.ChannelConfigurationProvider
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelImplementationProvider
import io.talkcan.model.ChannelProviderError
import io.talkcan.model.DynamicChoiceSourceKind
import io.talkcan.model.DynamicConfigurationChoiceSourceId
import io.talkcan.model.InstalledProviderBinding
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.model.ChannelPresentationMetadata
import io.talkcan.model.ProviderConfigurationResult
import io.talkcan.model.ValidatedChannelConfiguration
import org.json.JSONObject
import java.util.Collections
import java.util.LinkedHashSet

internal object LuaPackageMaterializer {
    /**
     * Production publication path (task 10.7): materializes the provider binding
     * plus optional resolver publication. Internal to keep resolver publication
     * out-of-band from the public materialize() contract.
     */
    internal fun materializeEntry(
        revision: ValidatedPackageRevision,
        bridge: LuaKernelBridge,
        actorPolicy: ActorPolicy = ActorPolicy.startingEvidence(),
        validationBounds: ValidationBounds = ValidationBounds.DEFAULT,
        logSink: PluginLogSink = NoOpPluginLogSink,
        runtimeResourcesFactory: LuaRuntimeResourcesFactory? = null,
        preparerRegistry: CapabilityPreparerRegistry = CapabilityPreparerRegistry.empty(),
        keyboardOutputAdapterFactory:
            ((CapabilityScopeIdentity, OutputExecutionOwner) -> KeyboardOutputAdapter)? = null,
        dynamicChoiceResolver: io.talkcan.model.DynamicConfigurationChoiceResolver? = null,
        // ── Task 13.3/13.4: adapter composition parameters ──────────────
        // Declaration-only pass-through; no Lua execution, no unused adapter
        // construction. Stores/transports are wired per generation in
        // constructRuntime, not here.
        secretStore: io.talkcan.secret.ProtectedSecretStore? = null,
        httpTransport: io.talkcan.http.GenericHttpTransport? = null,
        workStore: io.talkcan.work.DurableWorkStore? = null,
        workCoordinator: io.talkcan.work.DurableWorkCoordinator? = null,
        profileRecordProvider: (suspend (String) -> io.talkcan.profile.ProfileRecord?)? = null,
    ): MaterializationEntry {
        val implementationId = InstalledProviderId.derive(revision.manifest.repositoryId)
        val presentation = ChannelPresentationMetadata(
            label = revision.manifest.presentation.label,
            summary = revision.manifest.presentation.summary,
            unavailableMessage = "Lua package is unavailable or failed to initialize."
        )
        val declaration = revision.manifest.configuration
        val configurationProvider = CompiledConfigurationProvider(
            implementationId = implementationId,
            declaration = declaration,
        )
        val fields = compileFields(declaration, revision.manifest.repositoryId)
        val capabilities = compileCapabilities(revision.manifest.capabilities)
        // Task 13.3: compile declared work queue IDs from the validated manifest.
        // Declaration-only — no store access, no Lua, no adapter construction.
        val declaredWorkQueueIds = revision.manifest.workQueues.map { it.id }.toSet()
        val provider = LuaChannelImplementationProvider.create(
            implementationId = implementationId,
            presentation = presentation,
            programImage = revision.programImage,
            fingerprint = revision.fingerprint,
            actorFactory = { context, capabilities, kernelBridge, policy ->
                ActorRuntimeFactory.createForGeneration(context, capabilities, kernelBridge, policy)
            },
            bridge = bridge,
            actorPolicy = actorPolicy,
            validationBounds = validationBounds,
            logSink = logSink,
            configurationProvider = configurationProvider,
            configurationFields = fields,
            requiredCapabilities = capabilities,
            resourceDeclarations = revision.manifest.resources,
            runtimeResourcesFactory = runtimeResourcesFactory,
            preparerRegistry = preparerRegistry,
            keyboardOutputAdapterFactory = keyboardOutputAdapterFactory,
            dynamicChoiceResolver = dynamicChoiceResolver,
            declaredPublicCapabilities = revision.manifest.capabilities,
            secretStore = secretStore,
            httpTransport = httpTransport,
            workStore = workStore,
            workCoordinator = workCoordinator,
            profileRecordProvider = profileRecordProvider,
            declaredWorkQueueIds = declaredWorkQueueIds,
            packageRepositoryId = revision.manifest.repositoryId.value.toLong(),
        )
        // Task 10.7: build resolver publication if manifest declares resolvers.
        val resolverPublication = if (revision.manifest.choiceResolvers.isNotEmpty()) {
            io.talkcan.lua.resolver.PackageResolverPublication(
                repositoryId = revision.manifest.repositoryId.value.toLong(),
                packageRevision = revision.fingerprint,
                sourceMap = revision.sourceMap,
                resolvers = revision.manifest.choiceResolvers.associate { resolver ->
                    resolver.id to io.talkcan.lua.resolver.resolverBindingOf(resolver)
                }
            )
        } else null
        val binding = InstalledProviderBinding(
            repositoryId = revision.manifest.repositoryId,
            expectedDigest = revision.digest,
            provider = provider,
        )
        return MaterializationEntry(binding, resolverPublication)
    }

    /**
     * Existing contract: materializes the provider binding only. Delegates to
     * materializeEntry() and discards the resolver publication.
     */
    internal fun materialize(
        revision: ValidatedPackageRevision,
        bridge: LuaKernelBridge,
        actorPolicy: ActorPolicy = ActorPolicy.startingEvidence(),
        validationBounds: ValidationBounds = ValidationBounds.DEFAULT,
        logSink: PluginLogSink = NoOpPluginLogSink,
        runtimeResourcesFactory: LuaRuntimeResourcesFactory? = null,
        preparerRegistry: CapabilityPreparerRegistry = CapabilityPreparerRegistry.empty(),
        keyboardOutputAdapterFactory:
            ((CapabilityScopeIdentity, OutputExecutionOwner) -> KeyboardOutputAdapter)? = null,
        dynamicChoiceResolver: io.talkcan.model.DynamicConfigurationChoiceResolver? = null,
        secretStore: io.talkcan.secret.ProtectedSecretStore? = null,
        httpTransport: io.talkcan.http.GenericHttpTransport? = null,
        workStore: io.talkcan.work.DurableWorkStore? = null,
        workCoordinator: io.talkcan.work.DurableWorkCoordinator? = null,
        profileRecordProvider: (suspend (String) -> io.talkcan.profile.ProfileRecord?)? = null,
    ): InstalledProviderBinding {
        return materializeEntry(
            revision, bridge, actorPolicy, validationBounds, logSink,
            runtimeResourcesFactory, preparerRegistry, keyboardOutputAdapterFactory,
            dynamicChoiceResolver, secretStore, httpTransport, workStore,
            workCoordinator, profileRecordProvider
        ).binding
    }

    private fun compileFields(
        declaration: PackageConfigurationDeclaration,
        repositoryId: io.talkcan.dependency.GitHubRepositoryIdentity,
    ): List<ChannelConfigurationField> {
        return declaration.ui.fields.map { uiField ->
            when (uiField.control) {
                UiControl.TEXT -> ChannelConfigurationField.TextField(
                    id = uiField.field,
                    label = uiField.label,
                    help = uiField.help,
                    required = true,
                )
                UiControl.MULTILINE -> ChannelConfigurationField.TextField(
                    id = uiField.field,
                    label = uiField.label,
                    help = uiField.help,
                    required = true,
                    multiline = true,
                )
                UiControl.TOGGLE -> ChannelConfigurationField.BooleanField(
                    id = uiField.field,
                    label = uiField.label,
                    help = uiField.help,
                    required = true,
                )
                UiControl.NUMBER -> {
                    val dataField = declaration.data.fields.find { it.id == uiField.field }
                    val integerField = dataField as? ConfigurationFieldDeclaration.IntegerField
                    ChannelConfigurationField.NumberField(
                        id = uiField.field,
                        label = uiField.label,
                        help = uiField.help,
                        required = true,
                        minimum = integerField?.minimum,
                        maximum = integerField?.maximum,
                    )
                }
                UiControl.CHOICE -> ChannelConfigurationField.ChoiceField(
                    id = uiField.field,
                    label = uiField.label,
                    help = uiField.help,
                    required = true,
                    choices = uiField.choices?.map { choice ->
                        ChannelConfigurationField.ChoiceField.Choice(
                            id = choice.value,
                            label = choice.label,
                        )
                    } ?: emptyList(),
                )
                UiControl.DYNAMIC_CHOICE -> {
                    // Preserve the exact validated source scalar and its typed source-kind
                    // metadata verbatim (task 6.2): no resolution, no Lua execution, no host
                    // profile object. Host sources leave sourceKind null so the model derives
                    // the host kind from the scalar; profile-type and package-resolver sources
                    // carry their exact kind so editors/readiness can route without re-parsing.
                    // Package-resolver sources additionally carry the declaring repository
                    // identity so the host registry routes to the exact installed repository.
                    val rawSource = requireNotNull(uiField.source) {
                        "dynamic-choice field '${uiField.field}' must carry a validated source ID"
                    }
                    ChannelConfigurationField.DynamicChoiceField(
                        id = uiField.field,
                        label = uiField.label,
                        source = DynamicConfigurationChoiceSourceId(rawSource),
                        sourceKind = when (val reference = uiField.sourceReference) {
                            is DynamicChoiceSourceReference.ProfileType ->
                                DynamicChoiceSourceKind.ProfileType(reference.typeId, repositoryId)
                            is DynamicChoiceSourceReference.PackageResolver ->
                                DynamicChoiceSourceKind.PackageResolver(reference.resolverId, repositoryId)
                            is DynamicChoiceSourceReference.Host, null -> null
                        },
                        help = uiField.help,
                        dependsOnFieldId = uiField.dependsOnFieldId,
                        required = true,
                    )
                }
            }
        }
    }

    private fun compileCapabilities(declaredCapabilities: Set<String>): Set<ChannelCapability> {
        // Deterministic public→internal semantic mapping. Public manifest IDs
        // (PackageCapability) compile to existing internal ChannelCapability
        // requirements only; no CapabilityKey names or implementation classes
        // appear in the compiled set. Declaration order is preserved and the
        // result is immutable. Unknown IDs are rejected fail-closed — the
        // manifest parser already validates against PackageCapability.ALL, but
        // this function is independently deterministic.
        val result = LinkedHashSet<ChannelCapability>()
        for (cap in declaredCapabilities) {
            when (cap) {
                PackageCapability.AUDIO_TRANSCRIPTION -> result.add(ChannelCapability.Transcription)
                PackageCapability.AUDIO_SYNTHESIS -> result.add(ChannelCapability.Synthesis)
                PackageCapability.AUDIO_PLAYBACK -> {
                    // audio.playback requires both the audio-operation mechanism
                    // (PCM playback creation) and deferred-playback eligibility
                    // (scheduled/leased delivery). Order matches design D5.
                    result.add(ChannelCapability.AudioOperation)
                    result.add(ChannelCapability.DeferredAudioPlayback)
                }
                PackageCapability.STORAGE_FILES -> result.add(ChannelCapability.StorageFiles)
                PackageCapability.AUDIO_FILES -> result.add(ChannelCapability.AudioFiles)
                PackageCapability.KEYBOARD_OUTPUT ->
                    // keyboard.output compiles to the existing channel-neutral semantic
                    // TextOutput capability (design D1); no Keyboard-package identity,
                    // CapabilityKey name, or implementation class appears in the set.
                    result.add(ChannelCapability.TextOutput)
                PackageCapability.NETWORK_HTTP ->
                    // network.http compiles to the channel-neutral generic HTTPS transport
                    // capability (design D6); no provider request shape, credential, or OpenAI
                    // contract appears in the compiled set.
                    result.add(ChannelCapability.NetworkHttp)
                PackageCapability.PROFILES_READ ->
                    // profiles.read compiles to the channel-neutral generic profile-lookup
                    // capability (design D4). Detached scalar grants plus opaque secret
                    // references only; no repository, credential, or provider object.
                    result.add(ChannelCapability.ProfilesRead)
                PackageCapability.SECRETS_READ ->
                    // secrets.read compiles to the channel-neutral protected-secret resolution
                    // capability (design D5). Plaintext only under explicit selected-profile
                    // grant; no keystore alias, credential-store object, or provider shape.
                    result.add(ChannelCapability.SecretsRead)
                PackageCapability.WORK_QUEUE ->
                    // work.queue compiles to the channel-neutral durable-work capability
                    // (design D9). Bounded opaque FIFO payloads/effects only; no database,
                    // path, transaction, or storage-provider object.
                    result.add(ChannelCapability.WorkQueue)
                else -> throw IllegalArgumentException(
                    "Unknown package capability ID: $cap. " +
                        "Expected one of ${PackageCapability.ALL}.",
                )
            }
        }
        return Collections.unmodifiableSet(result)
    }
}

/**
 * One materialized package entry: the provider binding plus optional resolver
 * publication (task 10.7). The resolver publication carries the validated
 * source map needed by the orchestrator; it is internal because
 * [io.talkcan.lua.resolver.PackageResolverPublication] is internal.
 */
internal data class MaterializationEntry(
    val binding: InstalledProviderBinding,
    val resolverPublication: io.talkcan.lua.resolver.PackageResolverPublication? = null,
)

/**
 * Package-specific declaration-compiled configuration provider for schema version 1.
 *
 * Produces the complete default [OpaqueJsonObject] from declared field defaults and
 * validates submitted payloads against the declaration schema. Construction performs
 * no Lua execution, module loading, actor creation, or state allocation.
 */
internal class CompiledConfigurationProvider(
    override val implementationId: ChannelImplementationId,
    private val declaration: PackageConfigurationDeclaration,
) : ChannelConfigurationProvider {

    override val currentSchemaVersion: Int = 1

    override fun defaultPayload(): OpaqueJsonObject {
        val obj = JSONObject()
        for (field in declaration.data.fields) {
            when (field) {
                is ConfigurationFieldDeclaration.StringField -> obj.put(field.id, field.default)
                is ConfigurationFieldDeclaration.BooleanField -> obj.put(field.id, field.default)
                is ConfigurationFieldDeclaration.IntegerField -> obj.put(field.id, field.default)
            }
        }
        return OpaqueJsonObject.fromJsonObject(obj)
    }

    override fun validate(
        schemaVersion: Int,
        payload: OpaqueJsonObject,
    ): ProviderConfigurationResult {
        if (schemaVersion != currentSchemaVersion) {
            return ProviderConfigurationResult.Failure(
                ChannelProviderError.UnsupportedSchemaVersion(
                    implementationId, schemaVersion, currentSchemaVersion,
                ),
            )
        }

        val obj = try {
            payload.toJsonObject()
        } catch (_: Exception) {
            return ProviderConfigurationResult.Failure(
                ChannelProviderError.InvalidConfiguration(
                    implementationId, schemaVersion,
                    "Configuration payload is not a valid JSON object",
                ),
            )
        }

        val declaredFields = declaration.data.fields.associateBy { it.id }

        // Reject undeclared keys
        val keyIterator = obj.keys()
        while (keyIterator.hasNext()) {
            val key = keyIterator.next()
            if (key !in declaredFields) {
                return ProviderConfigurationResult.Failure(
                    ChannelProviderError.InvalidConfiguration(
                        implementationId, schemaVersion,
                        "Undeclared configuration field: $key",
                    ),
                )
            }
        }

        // Require every declared field present and validate scalar type/value
        for (field in declaration.data.fields) {
            if (!obj.has(field.id)) {
                return ProviderConfigurationResult.Failure(
                    ChannelProviderError.InvalidConfiguration(
                        implementationId, schemaVersion,
                        "Missing required field: ${field.id}",
                    ),
                )
            }

            val value = obj.get(field.id)

            // Reject null values
            if (value === JSONObject.NULL) {
                return ProviderConfigurationResult.Failure(
                    ChannelProviderError.InvalidConfiguration(
                        implementationId, schemaVersion,
                        "Field '${field.id}' must not be null",
                    ),
                )
            }

            // Reject nested objects and arrays
            if (value is JSONObject || value is org.json.JSONArray) {
                return ProviderConfigurationResult.Failure(
                    ChannelProviderError.InvalidConfiguration(
                        implementationId, schemaVersion,
                        "Field '${field.id}' must be a scalar, got ${value::class.simpleName}",
                    ),
                )
            }

            when (field) {
                is ConfigurationFieldDeclaration.StringField -> {
                    if (value !is String) {
                        return ProviderConfigurationResult.Failure(
                            ChannelProviderError.InvalidConfiguration(
                                implementationId, schemaVersion,
                                "Field '${field.id}' must be a string, got ${value::class.simpleName}",
                            ),
                        )
                    }
                    if (value.toByteArray(Charsets.UTF_8).size > PackageConfigurationLimits.MAX_STRING_VALUE_BYTES) {
                        return ProviderConfigurationResult.Failure(
                            ChannelProviderError.InvalidConfiguration(
                                implementationId, schemaVersion,
                                "Field '${field.id}' string value exceeds ${PackageConfigurationLimits.MAX_STRING_VALUE_BYTES} bytes",
                            ),
                        )
                    }
                    if (field.allowedValues != null && value !in field.allowedValues) {
                        return ProviderConfigurationResult.Failure(
                            ChannelProviderError.InvalidConfiguration(
                                implementationId, schemaVersion,
                                "Field '${field.id}' has value '$value' not in allowed values",
                            ),
                        )
                    }
                }
                is ConfigurationFieldDeclaration.BooleanField -> {
                    if (value !is Boolean) {
                        return ProviderConfigurationResult.Failure(
                            ChannelProviderError.InvalidConfiguration(
                                implementationId, schemaVersion,
                                "Field '${field.id}' must be a boolean, got ${value::class.simpleName}",
                            ),
                        )
                    }
                }
                is ConfigurationFieldDeclaration.IntegerField -> {
                    // Reject floating-point numbers: only Int/Long are valid integers
                    if (value !is Int && value !is Long) {
                        return ProviderConfigurationResult.Failure(
                            ChannelProviderError.InvalidConfiguration(
                                implementationId, schemaVersion,
                                "Field '${field.id}' must be an integer, got ${value::class.simpleName}",
                            ),
                        )
                    }
                    val longValue = (value as Number).toLong()
                    if (field.minimum != null && longValue < field.minimum) {
                        return ProviderConfigurationResult.Failure(
                            ChannelProviderError.InvalidConfiguration(
                                implementationId, schemaVersion,
                                "Field '${field.id}' value $longValue is below minimum ${field.minimum}",
                            ),
                        )
                    }
                    if (field.maximum != null && longValue > field.maximum) {
                        return ProviderConfigurationResult.Failure(
                            ChannelProviderError.InvalidConfiguration(
                                implementationId, schemaVersion,
                                "Field '${field.id}' value $longValue exceeds maximum ${field.maximum}",
                            ),
                        )
                    }
                }
            }
        }

        // Canonical 64 KiB total payload bound
        if (payload.toJsonString().toByteArray(Charsets.UTF_8).size > PackageConfigurationLimits.MAX_PAYLOAD_BYTES) {
            return ProviderConfigurationResult.Failure(
                ChannelProviderError.InvalidConfiguration(
                    implementationId, schemaVersion,
                    "Configuration payload exceeds ${PackageConfigurationLimits.MAX_PAYLOAD_BYTES} bytes",
                ),
            )
        }

        return ProviderConfigurationResult.Success(
            ValidatedChannelConfiguration(implementationId, schemaVersion, payload),
        )
    }

    override fun migrateStep(
        fromSchemaVersion: Int,
        payload: OpaqueJsonObject,
    ): ChannelConfigurationMigrationStep = ChannelConfigurationMigrationStep.Failure(
        ChannelProviderError.UnsupportedSchemaVersion(
            implementationId, fromSchemaVersion, currentSchemaVersion,
        ),
    )
}
