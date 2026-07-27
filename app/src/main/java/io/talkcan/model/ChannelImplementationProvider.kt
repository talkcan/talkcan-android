package io.talkcan.model

import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.ChannelCapability
import io.talkcan.channel.capability.ChannelCapabilityScope
import io.talkcan.lua.actor.ActorGenerationGate
import io.talkcan.service.ChannelRuntime
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import io.talkcan.dependency.PackageResourcesDeclaration
import io.talkcan.dependency.PackageConfigurationLimits
import java.nio.charset.StandardCharsets
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

/** Typed failures returned by provider registration, configuration, and construction. */
sealed interface ChannelProviderError {
    val implementationId: ChannelImplementationId
    val message: String

    data class DuplicateRegistration(
        override val implementationId: ChannelImplementationId,
    ) : ChannelProviderError {
        override val message = "Implementation provider $implementationId is already registered"
    }

    data class MissingProvider(
        override val implementationId: ChannelImplementationId,
    ) : ChannelProviderError {
        override val message = "Implementation provider $implementationId is not registered"
    }

    data class UnsupportedSchemaVersion(
        override val implementationId: ChannelImplementationId,
        val schemaVersion: Int,
        val currentSchemaVersion: Int,
    ) : ChannelProviderError {
        override val message =
            "Provider $implementationId does not support schema $schemaVersion (current $currentSchemaVersion)"
    }

    data class InvalidConfiguration(
        override val implementationId: ChannelImplementationId,
        val schemaVersion: Int,
        val detail: String,
    ) : ChannelProviderError {
        override val message = "Invalid configuration for $implementationId schema $schemaVersion: $detail"
    }

    data class MigrationFailed(
        override val implementationId: ChannelImplementationId,
        val fromSchemaVersion: Int,
        val detail: String,
    ) : ChannelProviderError {
        override val message =
            "Could not migrate $implementationId configuration from schema $fromSchemaVersion: $detail"
    }

    data class RuntimeConstructionFailed(
        override val implementationId: ChannelImplementationId,
        val detail: String,
    ) : ChannelProviderError {
        override val message = "Could not construct runtime for $implementationId: $detail"
    }

    data class RuntimeCompatibilityFailure(
        override val implementationId: ChannelImplementationId,
        val requirement: String,
        val requiredVersion: String,
        val supportedVersion: String,
    ) : ChannelProviderError {
        override val message =
            "Runtime compatibility failure for $implementationId: $requirement requires $requiredVersion (supported $supportedVersion)"
    }

    /**
     * An installed package provider that was committed to the installed-package index but
     * could not be materialized (archive corruption, compatibility failure, integrity
     * mismatch, etc.). The provider remains in the durable catalogue but cannot construct
     * a runtime until the user explicitly updates, rolls back, or removes the package.
     *
     * [category] and [detail] are an exhaustive normalized projection of the underlying
     * typed package failure; the model layer does not import or depend on the package
     * failure sealed hierarchy. An adapter in the dependency layer performs the mapping.
     */
    data class PackageUnavailable(
        override val implementationId: ChannelImplementationId,
        val category: PackageUnavailableCategory,
        val detail: PackageUnavailableDetail,
    ) : ChannelProviderError {
        override val message =
            "Installed package provider $implementationId is unavailable: $category/$detail"
    }

    /**
     * Normalized package-unavailability category mirroring the package failure hierarchy.
     * Adding a new package failure category REQUIRES adding a matching entry here.
     */
    enum class PackageUnavailableCategory {
        FORMAT,
        IDENTITY,
        COMPATIBILITY,
        INTEGRITY,
        STORAGE,
        RECOVERY,
        MUTATION,
        ROLLBACK,
        LOADING,
        SHUTDOWN,
    }

    /**
     * Exhaustive normalized failure-code enum. Every package failure detail across all
     * categories MUST have exactly one matching entry. The adapter enforces exhaustiveness
     * at compile time via a sealed `when` over the source hierarchy.
     */
    enum class PackageUnavailableDetail {
        INVALID_ZIP,
        UNEXPECTED_ENTRY,
        MISSING_MANIFEST,
        MALFORMED_MANIFEST,
        DUPLICATE_KEYS,
        UNKNOWN_FIELDS,
        INVALID_ENTRY_MODULE,
        INVALID_MODULE_GRAMMAR,
        COLLISION,
        BYTECODE_PROHIBITED,
        UNSUPPORTED_COMPRESSION,
        ENCRYPTED_ENTRY,
        BOUNDS_EXCEEDED,
        MISSING_RESOLVER_MODULE,
        REPOSITORY_ID_MISMATCH,
        RESERVED_NAMESPACE_CLAIM,
        UNSUPPORTED_MANIFEST_VERSION,
        LUA_VERSION_INCOMPATIBLE,
        API_VERSION_INCOMPATIBLE,
        DIGEST_MISMATCH,
        CORRUPTED_ARCHIVE,
        HASH_COMPUTATION_FAILED,
        WRITE_FAILED,
        COMMIT_FAILED,
        INSUFFICIENT_SPACE,
        INDEX_CORRUPT,
        RECOVERY_INDEX_INVALID,
        ORPHAN_CLEANUP_FAILED,
        COMMIT_STATE_AMBIGUOUS,
        SERIALIZATION_VIOLATION,
        CONCURRENT_MUTATION,
        STAGE_FAILED,
        NOT_INSTALLED,
        NO_ROLLBACK_REVISION,
        ROLLBACK_VALIDATION_FAILED,
        LOAD_CANCELLED,
        STALE_PUBLICATION,
        LOAD_TIMEOUT,
        RECONCILIATION_FAILED,
        PUBLICATION_REJECTED,
        SHUTDOWN_IN_PROGRESS,
        TRANSACTION_ABORTED,
    }
}

sealed interface ProviderConfigurationResult {
    data class Success(val configuration: ValidatedChannelConfiguration) : ProviderConfigurationResult
    data class Failure(val error: ChannelProviderError) : ProviderConfigurationResult
}

data class ValidatedChannelConfiguration(
    val implementationId: ChannelImplementationId,
    val schemaVersion: Int,
    val payload: OpaqueJsonObject,
)

/** A provider-controlled, one-step schema migration result. */
sealed interface ChannelConfigurationMigrationStep {
    data class Success(val payload: OpaqueJsonObject) : ChannelConfigurationMigrationStep
    data class Failure(val error: ChannelProviderError) : ChannelConfigurationMigrationStep
}

/**
 * Provider-owned deterministic configuration boundary. It accepts and returns opaque objects
 * so host catalogue persistence cannot discard fields added by a newer provider.
 */
interface ChannelConfigurationProvider {
    val implementationId: ChannelImplementationId
    val currentSchemaVersion: Int

    fun defaultPayload(): OpaqueJsonObject

    fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult

    /** Migrates exactly [fromSchemaVersion] to its next integer version. */
    fun migrateStep(
        fromSchemaVersion: Int,
        payload: OpaqueJsonObject,
    ): ChannelConfigurationMigrationStep

    fun migrateAndValidate(
        schemaVersion: Int,
        payload: OpaqueJsonObject,
    ): ProviderConfigurationResult {
        if (schemaVersion > currentSchemaVersion || schemaVersion < 1) {
            return ProviderConfigurationResult.Failure(
                ChannelProviderError.UnsupportedSchemaVersion(
                    implementationId,
                    schemaVersion,
                    currentSchemaVersion,
                ),
            )
        }
        var version = schemaVersion
        var migratedPayload = payload
        while (version < currentSchemaVersion) {
            when (val step = migrateStep(version, migratedPayload)) {
                is ChannelConfigurationMigrationStep.Success -> {
                    migratedPayload = step.payload
                    version += 1
                }
                is ChannelConfigurationMigrationStep.Failure -> return ProviderConfigurationResult.Failure(step.error)
            }
        }
        return validate(version, migratedPayload)
    }
}

data class ChannelPresentationMetadata(
    val label: String,
    val summary: String,
    val unavailableMessage: String,
)

sealed interface ChannelConfigurationField {
    val id: String
    val label: String
    val help: String?
    val required: Boolean

    data class BooleanField(
        override val id: String,
        override val label: String,
        override val help: String? = null,
        override val required: Boolean = true,
    ) : ChannelConfigurationField

    data class TextField(
        override val id: String,
        override val label: String,
        override val help: String? = null,
        override val required: Boolean = true,
        val multiline: Boolean = false,
    ) : ChannelConfigurationField

    data class ChoiceField(
        override val id: String,
        override val label: String,
        override val help: String? = null,
        override val required: Boolean = true,
        val choices: List<Choice>,
    ) : ChannelConfigurationField {
        data class Choice(val id: String, val label: String)
    }

    /**
     * Provider-declared choice metadata resolved by the host at editor time. A provider retains
     * only the selected scalar ID; it never receives a repository, SDK client, or UI state.
     */
    data class DynamicChoiceField(
        override val id: String,
        override val label: String,
        val source: DynamicConfigurationChoiceSourceId,
        override val help: String? = null,
        val dependsOnFieldId: String? = null,
        /** Optional scalar condition for rendering a dependent field. */
        val visibleWhenFieldId: String? = null,
        val visibleWhenValue: String? = null,
        override val required: Boolean = true,
        /**
         * Runtime source-kind metadata preserved verbatim from the declaration without resolution.
         * Null for plain host sources, whose kind derives from [source]; profile-type and
         * package-resolver sources carry their exact kind here so editors and readiness can route
         * resolution without re-parsing the scalar or touching provider objects.
         */
        val sourceKind: DynamicChoiceSourceKind? = null,
    ) : ChannelConfigurationField {
        /** Effective runtime source kind; host sources default to a host kind over [source]. */
        val effectiveSourceKind: DynamicChoiceSourceKind
            get() = sourceKind ?: DynamicChoiceSourceKind.Host(source)

        init {
            require(dependsOnFieldId?.isNotBlank() != false) {
                "Dynamic choice dependency field ID must not be blank"
            }
            require(visibleWhenFieldId?.isNotBlank() != false) {
                "Dynamic choice visibility field ID must not be blank"
            }
            require((visibleWhenFieldId == null) == (visibleWhenValue == null)) {
                "Dynamic choice visibility requires both field ID and expected value"
            }
            require(sourceKind !is DynamicChoiceSourceKind.Host || sourceKind.sourceId == source) {
                "Dynamic choice host source kind must match its scalar source ID"
            }
        }
    }

    data class NumberField(
        override val id: String,
        override val label: String,
        override val help: String? = null,
        override val required: Boolean = true,
        val minimum: Long? = null,
        val maximum: Long? = null,
    ) : ChannelConfigurationField

    /** Host UI owns directory acquisition; providers receive only the resulting string value. */
    data class DirectoryField(
        override val id: String,
        override val label: String,
        override val help: String? = null,
        override val required: Boolean = true,
    ) : ChannelConfigurationField
}

/**
 * Public identifier for a host-owned dynamic choice source. Configuration schema references a
 * source by this bounded scalar ID; the host registry maps it to a resolver without exposing
 * repository, SDK, keymap, or profile objects to providers or packages.
 */
@JvmInline
value class DynamicConfigurationChoiceSourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "Dynamic choice source ID must not be blank" }
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= PackageConfigurationLimits.MAX_FIELD_ID_BYTES) {
            "Dynamic choice source ID must not exceed ${PackageConfigurationLimits.MAX_FIELD_ID_BYTES} bytes"
        }
        require(String(bytes, StandardCharsets.UTF_8) == value) {
            "Dynamic choice source ID must be valid UTF-8"
        }
    }

    companion object {
        val KEYBOARD_OUTPUT_PROFILES = DynamicConfigurationChoiceSourceId("keyboard-output-profiles")
    }
}

/**
 * Runtime source-kind classification for a dynamic-choice field (task 6.1). The host routes
 * resolution by this kind: [Host] through the bounded host-source registry, [ProfileType]
 * through the same-repository profile-type registry, and [PackageResolver] through a one-shot
 * package resolver actor. It carries only scalar identity — never provider, repository, profile,
 * SDK, keymap, or transport objects — so it can be persisted, diffed, and bound to revisions.
 */
sealed interface DynamicChoiceSourceKind {
    /** A bounded host-owned source resolved through the host-source registry by [sourceId]. */
    data class Host(val sourceId: DynamicConfigurationChoiceSourceId) : DynamicChoiceSourceKind

    /**
     * A same-repository profile type. Materialized package fields carry the
     * declaring repository identity so equal local type IDs from different
     * repositories cannot expose each other's profiles. Null is retained only
     * for unscoped legacy/test callers and fails closed in generic resolution.
     */
    data class ProfileType(
        val typeId: String,
        val repositoryId: io.talkcan.dependency.GitHubRepositoryIdentity? = null,
    ) : DynamicChoiceSourceKind {
        init {
            require(typeId.isNotBlank()) { "Dynamic choice profile type ID must not be blank" }
        }
    }

    /** A same-package choice resolver; choices are produced by the declared resolver [resolverId]. */
    data class PackageResolver(
        val resolverId: String,
        /**
         * Canonical identity of the repository that declared this resolver. Carried verbatim
         * from the materialized field so the host source registry can route a `resolver:<id>`
         * request to the exact installed repository's factory; two repositories declaring the
         * same local resolver ID can never collide. Null only for legacy/host callers that
         * predate scoped publication.
         */
        val repositoryId: io.talkcan.dependency.GitHubRepositoryIdentity? = null,
    ) : DynamicChoiceSourceKind {
        init {
            require(resolverId.isNotBlank()) { "Dynamic choice resolver ID must not be blank" }
        }
    }
}

/**
 * Identity carried by one dynamic-choice resolution request (task 6.1). Fully scalar — no
 * provider, repository, profile, SDK, keymap, or transport objects — so editors, readiness, and
 * the host registry can bind a result to its exact source revision without holding runtime
 * authority, and suppress late predecessor results via [callerRequestId].
 *
 * [source] and [dependencyValue] remain the host-source registry key and dependency scalar used
 * by existing host sources. The remaining fields generalize the request for profile-type and
 * package-resolver sources: the active package revision, the requesting field, the dependency
 * field ID, an optional selected-profile revision, and a caller request identity.
 */
data class DynamicConfigurationChoiceRequest(
    val source: DynamicConfigurationChoiceSourceId,
    val dependencyValue: String? = null,
    val sourceKind: DynamicChoiceSourceKind? = null,
    val packageRevision: ProviderRevisionFingerprint? = null,
    val requestingFieldId: String? = null,
    val dependencyFieldId: String? = null,
    val profileRevision: Long? = null,
    val callerRequestId: String? = null,
) {
    /** Effective runtime source kind; defaults to a host source over [source]. */
    val effectiveSourceKind: DynamicChoiceSourceKind
        get() = sourceKind ?: DynamicChoiceSourceKind.Host(source)
}

/**
 * Host-facing resolver for declarative choice sources. Providers declare sources but never hold
 * this resolver, preventing repository, SDK, and UI-state injection into provider code.
 */
fun interface DynamicConfigurationChoiceResolver {
    suspend fun resolve(request: DynamicConfigurationChoiceRequest): DynamicConfigurationChoiceResolution
}

data class DynamicConfigurationChoice(
    val id: String,
    val label: String,
) {
    init {
        require(id.isNotBlank()) { "Dynamic configuration choice ID must not be blank" }
        require(label.isNotBlank()) { "Dynamic configuration choice label must not be blank" }
    }
}

/** Host-normalized state for asynchronous or unavailable choice sources. */
sealed interface DynamicConfigurationChoiceResolution {
    data object Loading : DynamicConfigurationChoiceResolution
    data class Available(val choices: List<DynamicConfigurationChoice>) : DynamicConfigurationChoiceResolution
    data class Unavailable(val reason: DynamicConfigurationChoiceUnavailableReason) : DynamicConfigurationChoiceResolution
}

enum class DynamicConfigurationChoiceUnavailableReason {
    DEPENDENCY_MISSING,
    SOURCE_UNAVAILABLE,
    DISCOVERY_FAILED,
    HOST_NOT_READY,
    RESOLUTION_TIMED_OUT,
}

/** Detached state for one required dynamic reference, projected without host profile objects. */
enum class DynamicConfigurationReferenceState {
    AVAILABLE,
    UNAVAILABLE,
}

/**
 * Projects the detached reference state of a persisted scalar selection against this resolution.
 * A selection is AVAILABLE only when the resolved source currently contains its exact scalar ID;
 * loading, unavailable, blank, or missing-membership outcomes project UNAVAILABLE without
 * mutating configuration, so callers preserve the scalar for repair or later recovery.
 */
fun DynamicConfigurationChoiceResolution.referenceState(
    selectedValue: String?,
): DynamicConfigurationReferenceState {
    if (selectedValue.isNullOrBlank()) return DynamicConfigurationReferenceState.UNAVAILABLE
    val available = this as? DynamicConfigurationChoiceResolution.Available
        ?: return DynamicConfigurationReferenceState.UNAVAILABLE
    return if (available.choices.any { it.id == selectedValue }) {
        DynamicConfigurationReferenceState.AVAILABLE
    } else {
        DynamicConfigurationReferenceState.UNAVAILABLE
    }
}

/**
 * Generic host registry keyed by (public dynamic source ID, declaring repository identity).
 * Host/profile/legacy sources register under a null repository identity; package-resolver
 * sources register under their exact declaring repository so two installed repositories
 * declaring the same local resolver ID never collide. Registration validates positive
 * deadlines and rejects an exact (source, repository) duplicate. Resolution enforces each
 * source deadline, maps source failure and timeout to typed unavailable states, and validates
 * published choices all-or-nothing: an over-bound, duplicate, or unrepresentable publication
 * becomes a typed unavailable state and never a partial choice list.
 */
class DynamicConfigurationChoiceSourceRegistry : DynamicConfigurationChoiceResolver {
    private data class Registration(
        val deadline: Duration,
        val resolver: DynamicConfigurationChoiceResolver,
    )

    /**
     * Composite registration key. Host/profile/legacy sources register under a null
     * repository identity; package-resolver sources register under the canonical identity
     * of the declaring repository so that two installed repositories declaring the same
     * local resolver ID can never collide on a single scalar source ID.
     */
    private data class RegistrationKey(
        val source: DynamicConfigurationChoiceSourceId,
        val repositoryId: io.talkcan.dependency.GitHubRepositoryIdentity?,
    )

    private val registrations = java.util.concurrent.ConcurrentHashMap<RegistrationKey, Registration>()

    fun register(
        source: DynamicConfigurationChoiceSourceId,
        deadline: Duration,
        resolver: DynamicConfigurationChoiceResolver,
    ) {
        register(source, deadline, null, resolver)
    }

    /**
     * Register one resolver under [source] scoped to [repositoryId] (null for host sources).
     * Validates a positive deadline and rejects a duplicate registration with the exact same
     * (source, repository) key, while allowing the same scalar source to be registered under
     * distinct repositories.
     */
    fun register(
        source: DynamicConfigurationChoiceSourceId,
        deadline: Duration,
        repositoryId: io.talkcan.dependency.GitHubRepositoryIdentity?,
        resolver: DynamicConfigurationChoiceResolver,
    ) {
        require(deadline.isPositive()) {
            "Dynamic choice source deadline must be positive: ${source.value}"
        }
        val key = RegistrationKey(source, repositoryId)
        require(registrations.putIfAbsent(key, Registration(deadline, resolver)) == null) {
            "Dynamic choice source already registered: ${source.value}"
        }
    }

    /**
     * Remove the registration for [source] scoped to [repositoryId]. Only that exact scoped
     * registration is removed; sibling repositories and host sources are undisturbed.
     */
    fun unregister(
        source: DynamicConfigurationChoiceSourceId,
        repositoryId: io.talkcan.dependency.GitHubRepositoryIdentity? = null,
    ) {
        registrations.remove(RegistrationKey(source, repositoryId))
    }

    override suspend fun resolve(request: DynamicConfigurationChoiceRequest): DynamicConfigurationChoiceResolution {
        val registration = registrationFor(request)
            ?: return DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
            )
        val raw = try {
            withContext(Dispatchers.Default) {
                withTimeout(registration.deadline) { registration.resolver.resolve(request) }
            }
        } catch (timeout: TimeoutCancellationException) {
            return DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.RESOLUTION_TIMED_OUT,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.DISCOVERY_FAILED,
            )
        }
        if (raw is DynamicConfigurationChoiceResolution.Available && !isValidPublication(raw.choices)) {
            return DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
            )
        }
        return raw
    }

    /**
     * Route one request to its registration. A request carrying package-resolver repository
     * authority matches only the registration scoped to that exact repository. A request
     * without repository authority (legacy host callers) matches the unique registration for
     * its source and fails closed when zero or multiple candidates exist, so an ambiguous
     * multi-repository source never routes to the wrong factory.
     */
    private fun registrationFor(request: DynamicConfigurationChoiceRequest): Registration? {
        val repositoryId =
            (request.effectiveSourceKind as? DynamicChoiceSourceKind.PackageResolver)?.repositoryId
        return if (repositoryId != null) {
            registrations[RegistrationKey(request.source, repositoryId)]
        } else {
            registrations.entries.singleOrNull { it.key.source == request.source }?.value
        }
    }

    /**
     * All-or-nothing validation of a source publication: UTF-8 representable
     * IDs and labels within byte bounds, and unique IDs and labels. Any violation discards
     * the complete publication.
     */
    private fun isValidPublication(choices: List<DynamicConfigurationChoice>): Boolean {
        val uniqueIds = HashSet<String>(choices.size)
        val uniqueLabels = HashSet<String>(choices.size)
        return choices.all { choice ->
            isBoundedUtf8(choice.id, PackageConfigurationLimits.MAX_STRING_VALUE_BYTES) &&
                isBoundedUtf8(choice.label, PackageConfigurationLimits.MAX_LABEL_BYTES) &&
                uniqueIds.add(choice.id) &&
                uniqueLabels.add(choice.label)
        }
    }

    private fun isBoundedUtf8(value: String, maximumBytes: Int): Boolean {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        return bytes.size <= maximumBytes && String(bytes, StandardCharsets.UTF_8) == value
    }
}

data class ChannelPreparationTraits(
    val supportsRecoverablePreparation: Boolean,
)

data class ChannelImplementationDescriptor(
    val implementationId: ChannelImplementationId,
    val presentation: ChannelPresentationMetadata,
    val configuration: ChannelConfigurationProvider,
    val configurationFields: List<ChannelConfigurationField>,
    val requiredCapabilities: Set<ChannelCapability>,
    val preparationTraits: ChannelPreparationTraits,
    val resourceDeclarations: PackageResourcesDeclaration = PackageResourcesDeclaration(emptyList()),
) {
    init {
        require(configuration.implementationId == implementationId) {
            "Configuration provider ID must match descriptor ID"
        }
        require(configuration.currentSchemaVersion > 0) {
            "Configuration provider version must be positive"
        }
        require(configurationFields.map { it.id }.all { it.isNotBlank() }) {
            "Configuration field IDs must not be blank"
        }
        require(configurationFields.map { it.id }.distinct().size == configurationFields.size) {
            "Configuration field IDs must be unique"
        }
        val fieldIds = configurationFields.map(ChannelConfigurationField::id).toSet()
        configurationFields.filterIsInstance<ChannelConfigurationField.DynamicChoiceField>().forEach { field ->
            field.dependsOnFieldId?.let { dependencyId ->
                require(dependencyId != field.id && dependencyId in fieldIds) {
                    "Dynamic choice field ${field.id} must depend on another declared field"
                }
            }
        }
    }

    val capabilities: Set<ChannelCapability>
        get() = requiredCapabilities
}
/**
 * Provider-neutral generation execution context.
 *
 * Supplies typed generation-bound admission for internal timers and
 * background tasks plus a liveness query. Does NOT expose CoroutineScope,
 * RuntimeGenerationInvocationGate, CapabilityScopeIdentity,
 * RuntimeGeneration, or any other internal type.
 *
 * The context is bound to a single generation and rejects operations
 * initiated after that generation is closed or replaced.
 *
 * Sealed so only host-owned implementation types exist.
 */
sealed interface GenerationExecutionContext {
    /** Stable channel instance identifier. Consistent across generations. */
    val instanceId: String

    /**
     * Check whether the generation is still active (not closed or replaced).
     * Functions return false/throw after the generation closes.
     */
    fun isActive(): Boolean

    /**
     * Schedule a one-shot timer bound to this generation. The callback fires
     * at most once while live and is suppressed after close. The accepted
     * handle cancels the timer idempotently.
     */
    fun scheduleTimer(
        delaySeconds: Double,
        callback: suspend () -> Unit,
    ): GenerationAdmission<Disposable>

    /**
     * Admit a generation-bound background task. During construction and
     * activation, admission reserves bounded capacity and stages the task;
     * it cannot execute yet. After the registry publishes generation
     * readiness, later admissions become runnable after the current
     * invocation slice.
     */
    fun admitTask(task: suspend () -> Unit): GenerationAdmission<Unit>
}

/** Internal actor adapter port; never exposed through the provider-facing context contract. */
internal interface ActorRuntimeHostContext : GenerationExecutionContext {
    val actorIdentity: CapabilityScopeIdentity
    val actorParentScope: CoroutineScope
    val actorGate: ActorGenerationGate
    fun discardActorStagedTasks()
}

sealed interface GenerationAdmission<out T> {
    data class Accepted<T>(val value: T) : GenerationAdmission<T>
    data class Rejected(val reason: GenerationAdmissionRejection) : GenerationAdmission<Nothing>
}

enum class GenerationAdmissionRejection {
    CLOSED,
    CAPACITY_EXHAUSTED,
}

interface Disposable {
    fun dispose()  // cancel the timer; idempotent
}

data class ChannelRuntimeConstructionRequest(
    val definition: ChannelDefinition,
    val configuration: ValidatedChannelConfiguration,
    val capabilities: ChannelCapabilityScope,
    val generationContext: GenerationExecutionContext,
    val resourceDeclarations: PackageResourcesDeclaration = PackageResourcesDeclaration(emptyList()),
) {
    init {
        require(definition.implementationId == configuration.implementationId) {
            "Validated configuration provider must match its definition"
        }
        require(definition.configSchemaVersion == configuration.schemaVersion) {
            "Runtime construction requires the definition's current validated schema"
        }
        require(definition.configPayload == configuration.payload) {
            "Runtime construction requires the definition's validated payload"
        }
    }
}

sealed interface ChannelRuntimeConstructionResult {
    data class Success(val runtime: ChannelRuntime) : ChannelRuntimeConstructionResult
    data class Failure(val error: ChannelProviderError) : ChannelRuntimeConstructionResult
}

@JvmInline
public value class ProviderRevisionFingerprint(val value: String) {
    init {
        require(value.isNotBlank()) {
            "Fingerprint must not be blank"
        }
    }

    public companion object {
        public fun fromDigest(digest: io.talkcan.dependency.ArtifactDigest): ProviderRevisionFingerprint {
            return ProviderRevisionFingerprint(digest.value)
        }
    }
}

interface ChannelImplementationProvider {
    val descriptor: ChannelImplementationDescriptor
    val fingerprint: ProviderRevisionFingerprint

    suspend fun constructRuntime(request: ChannelRuntimeConstructionRequest): ChannelRuntimeConstructionResult
}

sealed interface ChannelProviderRegistrationResult {
    data object Registered : ChannelProviderRegistrationResult
    data class Rejected(val error: ChannelProviderError) : ChannelProviderRegistrationResult
}

sealed interface ChannelProviderResolution {
    data class Available(val provider: ChannelImplementationProvider) : ChannelProviderResolution
    data class Missing(val error: ChannelProviderError.MissingProvider) : ChannelProviderResolution
    data class Unavailable(val error: ChannelProviderError.PackageUnavailable) : ChannelProviderResolution
}

sealed interface ChannelDescriptorResolution {
    data class Available(val descriptor: ChannelImplementationDescriptor) : ChannelDescriptorResolution
    data class Missing(val error: ChannelProviderError.MissingProvider) : ChannelDescriptorResolution
}

interface ChannelImplementationDescriptorResolver {
    fun resolveDescriptor(implementationId: ChannelImplementationId): ChannelDescriptorResolution
}

public data class InstalledProviderBinding(
    val repositoryId: io.talkcan.dependency.GitHubRepositoryIdentity,
    val expectedDigest: io.talkcan.dependency.ArtifactDigest,
    val provider: ChannelImplementationProvider,
)

public sealed interface InstalledProvidersPublicationResult {
    public data class Success(val snapshotRevision: Long) : InstalledProvidersPublicationResult
    public data class Rejected(val error: InstalledProvidersRejectionReason) : InstalledProvidersPublicationResult
}

public sealed interface InstalledProvidersRejectionReason {
    public data class InvalidId(val id: ChannelImplementationId, val detail: String) : InstalledProvidersRejectionReason
    public data class ReservedCollision(val id: ChannelImplementationId) : InstalledProvidersRejectionReason
    public data class AgreementMismatch(val id: ChannelImplementationId, val detail: String) : InstalledProvidersRejectionReason
    public data class MissingRevision(val id: ChannelImplementationId) : InstalledProvidersRejectionReason
    public data class DuplicateValue(val id: ChannelImplementationId) : InstalledProvidersRejectionReason
    public data class RevisionOverflow(val message: String) : InstalledProvidersRejectionReason
}

private data class InstalledProviderGlobalFallback(
    val category: ChannelProviderError.PackageUnavailableCategory,
    val detail: ChannelProviderError.PackageUnavailableDetail,
)

private data class RegistryState(
    val revision: Long,
    val resolutionMap: Map<ChannelImplementationId, ChannelImplementationProvider>,
    val installedBindings: Map<ChannelImplementationId, InstalledProviderBinding>,
    val installedFailures: Map<ChannelImplementationId, ChannelProviderError.PackageUnavailable>,
    val globalFallback: InstalledProviderGlobalFallback? = null,
)

private data class ValidatedCandidateInfo(
    val id: ChannelImplementationId,
    val binding: InstalledProviderBinding,
    val expectedFingerprint: ProviderRevisionFingerprint,
)

/** Deterministic insertion-ordered registry; a duplicate never replaces the original provider. */
class ChannelImplementationProviderRegistry : ChannelImplementationDescriptorResolver {
    private val builtIns = LinkedHashMap<ChannelImplementationId, ChannelImplementationProvider>()

    @Volatile
    private var state: RegistryState = RegistryState(
        revision = 0L,
        resolutionMap = emptyMap(),
        installedBindings = emptyMap(),
        installedFailures = emptyMap(),
        globalFallback = null,
    )

    val snapshotRevision: Long
        get() = state.revision

    fun register(provider: ChannelImplementationProvider): ChannelProviderRegistrationResult {
        val id = provider.descriptor.implementationId
        return registerInternal(id, provider)
    }

    @Synchronized
    private fun registerInternal(id: ChannelImplementationId, provider: ChannelImplementationProvider): ChannelProviderRegistrationResult {
        if (state.revision > 0L) {
            return ChannelProviderRegistrationResult.Rejected(ChannelProviderError.DuplicateRegistration(id))
        }
        if (io.talkcan.dependency.InstalledProviderId.isInstalled(id)) {
            return ChannelProviderRegistrationResult.Rejected(ChannelProviderError.DuplicateRegistration(id))
        }
        if (builtIns.containsKey(id)) {
            return ChannelProviderRegistrationResult.Rejected(ChannelProviderError.DuplicateRegistration(id))
        }
        builtIns[id] = provider
        updateState()
        return ChannelProviderRegistrationResult.Registered
    }

    fun publishInstalledProviders(
        candidate: Map<ChannelImplementationId, InstalledProviderBinding>,
        unavailable: Map<ChannelImplementationId, ChannelProviderError.PackageUnavailable> = emptyMap(),
    ): InstalledProvidersPublicationResult {
        if (state.revision == Long.MAX_VALUE) {
            return InstalledProvidersPublicationResult.Rejected(
                InstalledProvidersRejectionReason.RevisionOverflow("Monotonic revision overflowed")
            )
        }

        val validatedCandidates = ArrayList<ValidatedCandidateInfo>(candidate.size)
        val uniqueProviders = java.util.HashSet<ChannelImplementationProvider>()
        val uniqueRepoIds = java.util.HashSet<io.talkcan.dependency.GitHubRepositoryIdentity>()
        val allIds = java.util.HashSet<ChannelImplementationId>()

        for ((id, binding) in candidate) {
            if (isBuiltInId(id)) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.ReservedCollision(id)
                )
            }
            val provider = binding.provider
            val providerId = provider.descriptor.implementationId
            val configId = provider.descriptor.configuration.implementationId
            val providerFingerprint = provider.fingerprint
            val expectedFingerprint = ProviderRevisionFingerprint.fromDigest(binding.expectedDigest)

            val expectedDerivedId = io.talkcan.dependency.InstalledProviderId.derive(binding.repositoryId)
            if (id != expectedDerivedId) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.InvalidId(id, "ID does not match derived identity: expected $expectedDerivedId")
                )
            }
            if (providerId != id) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.AgreementMismatch(id, "Provider descriptor ID does not match key")
                )
            }
            if (configId != id) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.AgreementMismatch(id, "Provider configuration ID does not match key")
                )
            }
            if (providerFingerprint != expectedFingerprint) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.AgreementMismatch(id, "Provider fingerprint does not match expected fingerprint")
                )
            }
            if (!uniqueProviders.add(provider)) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.DuplicateValue(id)
                )
            }
            if (!uniqueRepoIds.add(binding.repositoryId)) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.DuplicateValue(id)
                )
            }
            if (!allIds.add(id)) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.DuplicateValue(id)
                )
            }

            validatedCandidates.add(ValidatedCandidateInfo(id, binding, expectedFingerprint))
        }

        // Validate unavailable entries: canonical, installed-namespace, disjoint from candidate,
        // implementationId agrees with the key, and no duplicate IDs.
        for ((id, error) in unavailable) {
            if (isBuiltInId(id)) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.ReservedCollision(id)
                )
            }
            if (!io.talkcan.dependency.InstalledProviderId.isInstalled(id)) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.InvalidId(id, "Unavailable entry is not a canonical installed provider ID")
                )
            }
            if (error.implementationId != id) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.AgreementMismatch(id, "Unavailable error implementationId does not match key")
                )
            }
            if (!allIds.add(id)) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.DuplicateValue(id)
                )
            }
        }

        return publishInternal(validatedCandidates, unavailable)
    }

    /**
     * Fail-closed publication: atomically replaces every installed binding and failure
     * entry with empty maps and sets an immutable global installed-store failure template.
     * The revision is incremented so observers see a new generation. Any canonical
     * github-repository ID that has no explicit entry will resolve to a [PackageUnavailable]
     * using the supplied category/detail.
     *
     * Normal [publishInstalledProviders] clears the global fallback.
     */
    fun publishFailClosed(
        category: ChannelProviderError.PackageUnavailableCategory,
        detail: ChannelProviderError.PackageUnavailableDetail,
    ): InstalledProvidersPublicationResult {
        if (state.revision == Long.MAX_VALUE) {
            return InstalledProvidersPublicationResult.Rejected(
                InstalledProvidersRejectionReason.RevisionOverflow("Monotonic revision overflowed")
            )
        }
        return publishFailClosedInternal(category, detail)
    }

    @Synchronized
    private fun publishFailClosedInternal(
        category: ChannelProviderError.PackageUnavailableCategory,
        detail: ChannelProviderError.PackageUnavailableDetail,
    ): InstalledProvidersPublicationResult {
        if (state.revision == Long.MAX_VALUE) {
            return InstalledProvidersPublicationResult.Rejected(
                InstalledProvidersRejectionReason.RevisionOverflow("Monotonic revision overflowed")
            )
        }

        val newRevision = state.revision + 1L
        val newResolutionMap = LinkedHashMap<ChannelImplementationId, ChannelImplementationProvider>()
        newResolutionMap.putAll(builtIns)

        state = RegistryState(
            revision = newRevision,
            resolutionMap = java.util.Collections.unmodifiableMap(newResolutionMap),
            installedBindings = emptyMap(),
            installedFailures = emptyMap(),
            globalFallback = InstalledProviderGlobalFallback(
                category = category,
                detail = detail,
            ),
        )
        return InstalledProvidersPublicationResult.Success(newRevision)
    }

    @Synchronized
    private fun publishInternal(
        validatedCandidates: List<ValidatedCandidateInfo>,
        unavailable: Map<ChannelImplementationId, ChannelProviderError.PackageUnavailable>,
    ): InstalledProvidersPublicationResult {
        if (state.revision == Long.MAX_VALUE) {
            return InstalledProvidersPublicationResult.Rejected(
                InstalledProvidersRejectionReason.RevisionOverflow("Monotonic revision overflowed")
            )
        }

        for (info in validatedCandidates) {
            if (isBuiltInId(info.id)) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.ReservedCollision(info.id)
                )
            }
        }
        for ((id, _) in unavailable) {
            if (isBuiltInId(id)) {
                return InstalledProvidersPublicationResult.Rejected(
                    InstalledProvidersRejectionReason.ReservedCollision(id)
                )
            }
        }

        val newRevision = state.revision + 1L
        val newResolutionMap = LinkedHashMap<ChannelImplementationId, ChannelImplementationProvider>()
        newResolutionMap.putAll(builtIns)
        val newInstalledBindings = LinkedHashMap<ChannelImplementationId, InstalledProviderBinding>()
        for (info in validatedCandidates) {
            newResolutionMap[info.id] = info.binding.provider
            newInstalledBindings[info.id] = info.binding
        }
        val newInstalledFailures = java.util.Collections.unmodifiableMap(LinkedHashMap(unavailable))
        state = RegistryState(
            revision = newRevision,
            resolutionMap = java.util.Collections.unmodifiableMap(newResolutionMap),
            installedBindings = java.util.Collections.unmodifiableMap(newInstalledBindings),
            installedFailures = newInstalledFailures,
            globalFallback = null,
        )
        return InstalledProvidersPublicationResult.Success(newRevision)
    }

    private fun isBuiltInId(id: ChannelImplementationId): Boolean {
        return id.value.startsWith("builtin:")
    }

    private fun updateState() {
        val newResolutionMap = LinkedHashMap<ChannelImplementationId, ChannelImplementationProvider>()
        newResolutionMap.putAll(builtIns)
        for ((id, binding) in state.installedBindings) {
            newResolutionMap[id] = binding.provider
        }
        state = RegistryState(
            revision = state.revision,
            resolutionMap = java.util.Collections.unmodifiableMap(newResolutionMap),
            installedBindings = state.installedBindings,
            installedFailures = state.installedFailures,
            globalFallback = state.globalFallback,
        )
    }

    fun resolve(implementationId: ChannelImplementationId): ChannelProviderResolution =
        state.resolutionMap[implementationId]?.let(ChannelProviderResolution::Available)
            ?: state.installedFailures[implementationId]?.let(ChannelProviderResolution::Unavailable)
            ?: state.globalFallback?.let { fallback ->
                if (io.talkcan.dependency.InstalledProviderId.isInstalled(implementationId)) {
                    ChannelProviderResolution.Unavailable(
                        ChannelProviderError.PackageUnavailable(implementationId, fallback.category, fallback.detail)
                    )
                } else null
            }
            ?: ChannelProviderResolution.Missing(ChannelProviderError.MissingProvider(implementationId))

    fun descriptors(): List<ChannelImplementationDescriptor> = state.resolutionMap.values.map { it.descriptor }

    override fun resolveDescriptor(implementationId: ChannelImplementationId): ChannelDescriptorResolution =
        state.resolutionMap[implementationId]?.descriptor?.let(ChannelDescriptorResolution::Available)
            ?: ChannelDescriptorResolution.Missing(ChannelProviderError.MissingProvider(implementationId))
}

sealed interface ChannelCatalogueProviderMigrationResult {
    data class Success(
        val snapshot: ChannelCatalogueSnapshot,
        val changed: Boolean,
    ) : ChannelCatalogueProviderMigrationResult

    /** The original snapshot is intentionally retained and must not be committed or published. */
    data class Failure(
        val definitionId: String,
        val error: ChannelProviderError,
    ) : ChannelCatalogueProviderMigrationResult
}

/**
 * Migrates every available provider as one in-memory transaction. Missing providers are left
 * byte-for-byte opaque at the payload boundary. On configuration incompatibility the definition
 * is preserved unchanged so the runtime layer projects a typed unavailable result; no automatic
 * successor, default, or migrated payload is substituted.
 */
object ChannelCatalogueProviderMigrator {
    fun migrate(
        snapshot: ChannelCatalogueSnapshot,
        resolver: ChannelImplementationDescriptorResolver,
    ): ChannelCatalogueProviderMigrationResult {
        var changed = false
        val migrated = snapshot.definitions.map { definition ->
            when (val resolution = resolver.resolveDescriptor(definition.implementationId)) {
                is ChannelDescriptorResolution.Missing -> definition
                is ChannelDescriptorResolution.Available -> when (
                    val result = resolution.descriptor.configuration.migrateAndValidate(
                        definition.configSchemaVersion,
                        definition.configPayload,
                    )
                ) {
                    // Preserve the definition unchanged on incompatibility;
                    // runtime reconciliation projects the typed unavailable state.
                    is ProviderConfigurationResult.Failure -> definition
                    is ProviderConfigurationResult.Success -> {
                        val configuration = result.configuration
                        if (configuration.schemaVersion != definition.configSchemaVersion ||
                            configuration.payload != definition.configPayload
                        ) {
                            changed = true
                            definition.copy(
                                configSchemaVersion = configuration.schemaVersion,
                                configPayload = configuration.payload,
                            )
                        } else {
                            definition
                        }
                    }
                }
            }
        }
        return ChannelCatalogueProviderMigrationResult.Success(
            snapshot.copy(definitions = migrated),
            changed,
        )
    }
}
