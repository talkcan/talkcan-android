package io.talkcan.dependency

import io.talkcan.lua.LuaPackageMaterializer
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.LuaRuntimeResourcesFactory
import io.talkcan.lua.PluginLogSink
import io.talkcan.lua.NoOpPluginLogSink
import io.talkcan.lua.actor.ActorPolicy
import io.talkcan.channel.capability.CapabilityPreparerRegistry
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.KeyboardOutputAdapter
import io.talkcan.channel.capability.OutputExecutionOwner
import io.talkcan.model.DynamicConfigurationChoiceResolver
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.InstalledProviderBinding
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import io.talkcan.profile.ProfileFieldDeclaration
import io.talkcan.profile.ProfileSchema
import io.talkcan.profile.ProfileUiChoice
import io.talkcan.profile.ProfileUiControl
import io.talkcan.profile.ProfileUiFieldDeclaration

/**
 * 3.1: Stored revision representing a snapshot of a package metadata and artifact digest.
 *
 * The cached manifest always carries the complete revised-v1 declaration set;
 * predecessor artifacts that omit the required declarations are never indexed
 * and remain exact immutable bytes until a compatible update is installed.
 */
public data class StoredPackageRevision(
    val digest: ArtifactDigest,
    val manifest: PackageManifest,
    val sourceRecord: PackageSourceRecord
) {
    init {
        require(manifest.repositoryId == sourceRecord.repositoryId) {
            "Inconsistent repository ID: manifest=${manifest.repositoryId.value}, sourceRecord=${sourceRecord.repositoryId.value}"
        }
    }
}

/**
 * 3.1: Provider record mapping a single provider's active and optional rollback revision.
 */
public data class StoredProviderRecord(
    val active: StoredPackageRevision,
    val rollback: StoredPackageRevision?
)

/**
 * 3.1: Complete index schema containing schema version and all registered provider records.
 */
public class StoredInstalledIndex(
    val version: Int,
    providers: Map<GitHubRepositoryIdentity, StoredProviderRecord>
) {
    val providers: Map<GitHubRepositoryIdentity, StoredProviderRecord> =
        Collections.unmodifiableMap(LinkedHashMap(providers))

    init {
        require(version == 1) { "Version must be exactly 1: $version" }
        for ((key, record) in this.providers) {
            require(key == record.active.manifest.repositoryId) {
                "Inconsistent active repository ID: key=${key.value}, active=${record.active.manifest.repositoryId.value}"
            }
            require(key == record.active.sourceRecord.repositoryId) {
                "Inconsistent active source repository ID: key=${key.value}, active=${record.active.sourceRecord.repositoryId.value}"
            }
            if (record.rollback != null) {
                require(key == record.rollback.manifest.repositoryId) {
                    "Inconsistent rollback repository ID: key=${key.value}, rollback=${record.rollback.manifest.repositoryId.value}"
                }
                require(key == record.rollback.sourceRecord.repositoryId) {
                    "Inconsistent rollback source repository ID: key=${key.value}, rollback=${record.rollback.sourceRecord.repositoryId.value}"
                }
                require(record.active.digest != record.rollback.digest) {
                    "Active and rollback digests must be unique for repository ${key.value}"
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredInstalledIndex) return false
        return version == other.version && providers == other.providers
    }

    override fun hashCode(): Int {
        return 31 * version + providers.hashCode()
    }

    override fun toString(): String {
        return "StoredInstalledIndex(version=$version, providers=$providers)"
    }
}

/**
 * 3.2: Strict complete-index encoding and decoding using the extracted StrictJsonParser.
 */
internal object StrictIndexCodec {

    fun encodeIndex(index: StoredInstalledIndex): String {
        val sb = java.lang.StringBuilder()
        sb.append("{\n")
        sb.append("  \"version\": ").append(index.version).append(",\n")
        sb.append("  \"providers\": {\n")
        val sortedProviders = index.providers.entries.sortedBy { it.key.value }
        for ((i, entry) in sortedProviders.withIndex()) {
            sb.append("    ").append(encodeJsonString(entry.key.value)).append(": {\n")
            sb.append("      \"active\": ").append(encodeRevision(entry.value.active).replace("\n", "\n      ")).append(",\n")
            sb.append("      \"rollback\": ").append(
                if (entry.value.rollback != null) {
                    encodeRevision(entry.value.rollback!!).replace("\n", "\n      ")
                } else {
                    "null"
                }
            ).append("\n")
            sb.append("    }")
            if (i < sortedProviders.size - 1) {
                sb.append(",")
            }
            sb.append("\n")
        }
        sb.append("  }\n")
        sb.append("}")
        return sb.toString()
    }

    private fun encodeRevision(rev: StoredPackageRevision): String {
        return """{
  "digest": ${encodeJsonString(rev.digest.value)},
  "manifest": ${encodeManifest(rev.manifest).replace("\n", "\n  ")},
  "sourceRecord": ${encodeSourceRecord(rev.sourceRecord).replace("\n", "\n  ")}
}"""
    }

    private fun encodeManifest(manifest: PackageManifest): String {
        return """{
  "manifestVersion": ${manifest.manifestVersion},
  "repositoryId": ${encodeJsonString(manifest.repositoryId.value)},
  "packageVersion": ${encodeJsonString(manifest.packageVersion)},
  "entryModule": ${encodeJsonString(manifest.entryModule)},
  "presentation": {
    "label": ${encodeJsonString(manifest.presentation.label)},
    "summary": ${encodeJsonString(manifest.presentation.summary)}
  },
  "runtime": {
    "luaVersion": ${encodeJsonString(manifest.runtime.luaVersion)},
    "apiVersion": ${encodeJsonString(manifest.runtime.apiVersion)}
  },
  "configuration": ${encodeConfiguration(manifest.configuration).replace("\n", "\n  ")},
  "resources": ${encodeResources(manifest.resources).replace("\n", "\n  ")},
  "profileTypes": ${encodeProfileTypes(manifest.profileTypes).replace("\n", "\n  ")},
  "choiceResolvers": ${encodeChoiceResolvers(manifest.choiceResolvers).replace("\n", "\n  ")},
  "workQueues": ${encodeWorkQueues(manifest.workQueues).replace("\n", "\n  ")},
  "capabilities": ${encodeCapabilities(manifest.capabilities)}
}"""
    }

    private fun encodeConfiguration(config: PackageConfigurationDeclaration): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schemaVersion\": 1,\n")
        sb.append("  \"data\": {\n")
        sb.append("    \"additionalProperties\": false,\n")
        sb.append("    \"fields\": [\n")
        for ((idx, field) in config.data.fields.withIndex()) {
            sb.append("      ").append(encodeDataField(field).replace("\n", "\n      "))
            if (idx < config.data.fields.size - 1) {
                sb.append(",")
            }
            sb.append("\n")
        }
        sb.append("    ]\n")
        sb.append("  },\n")
        sb.append("  \"ui\": {\n")
        sb.append("    \"fields\": [\n")
        for ((idx, field) in config.ui.fields.withIndex()) {
            sb.append("      ").append(encodeUiField(field).replace("\n", "\n      "))
            if (idx < config.ui.fields.size - 1) {
                sb.append(",")
            }
            sb.append("\n")
        }
        sb.append("    ]\n")
        sb.append("  }\n")
        sb.append("}")
        return sb.toString()
    }

    private fun encodeDataField(field: ConfigurationFieldDeclaration): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"id\": ").append(encodeJsonString(field.id)).append(",\n")
        when (field) {
            is ConfigurationFieldDeclaration.StringField -> {
                sb.append("  \"type\": \"string\",\n")
                sb.append("  \"default\": ").append(encodeJsonString(field.default))
                if (field.allowedValues != null) {
                    sb.append(",\n  \"allowedValues\": [\n")
                    for ((idx, v) in field.allowedValues.withIndex()) {
                        sb.append("    ").append(encodeJsonString(v))
                        if (idx < field.allowedValues.size - 1) sb.append(",")
                        sb.append("\n")
                    }
                    sb.append("  ]")
                }
                sb.append("\n")
            }
            is ConfigurationFieldDeclaration.BooleanField -> {
                sb.append("  \"type\": \"boolean\",\n")
                sb.append("  \"default\": ").append(field.default).append("\n")
            }
            is ConfigurationFieldDeclaration.IntegerField -> {
                sb.append("  \"type\": \"integer\",\n")
                sb.append("  \"default\": ").append(field.default)
                if (field.minimum != null) {
                    sb.append(",\n  \"minimum\": ").append(field.minimum)
                }
                if (field.maximum != null) {
                    sb.append(",\n  \"maximum\": ").append(field.maximum)
                }
                sb.append("\n")
            }
        }
        sb.append("}")
        return sb.toString()
    }

    private fun encodeUiField(field: UiFieldDeclaration): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"field\": ").append(encodeJsonString(field.field)).append(",\n")
        sb.append("  \"control\": ").append(encodeJsonString(field.control.value)).append(",\n")
        sb.append("  \"label\": ").append(encodeJsonString(field.label))
        if (field.help != null) {
            sb.append(",\n  \"help\": ").append(encodeJsonString(field.help))
        }
        if (field.choices != null) {
            sb.append(",\n  \"choices\": [\n")
            for ((idx, choice) in field.choices.withIndex()) {
                sb.append("    {\n")
                sb.append("      \"value\": ").append(encodeJsonString(choice.value)).append(",\n")
                sb.append("      \"label\": ").append(encodeJsonString(choice.label)).append("\n")
                sb.append("    }")
                if (idx < field.choices.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("  ]")
        }
        if (field.source != null) {
            sb.append(",\n  \"source\": ").append(encodeJsonString(field.source))
        }
        if (field.dependsOnFieldId != null) {
            sb.append(",\n  \"dependsOn\": ").append(encodeJsonString(field.dependsOnFieldId))
        }
        sb.append("\n}")
        return sb.toString()
    }

    private fun encodeCapabilities(caps: Set<String>): String {
        val sb = StringBuilder()
        sb.append("[\n")
        for ((idx, cap) in caps.withIndex()) {
            sb.append("  ").append(encodeJsonString(cap))
            if (idx < caps.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("]")
        return sb.toString()
    }


    private fun encodeResources(resources: PackageResourcesDeclaration): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"mounts\": [")
        if (resources.mounts.isNotEmpty()) {
            sb.append("\n")
            for ((idx, mount) in resources.mounts.withIndex()) {
                sb.append("    {\n")
                sb.append("      \"id\": ").append(encodeJsonString(mount.id)).append(",\n")
                sb.append("      \"kind\": ").append(encodeJsonString(mount.kind.value)).append(",\n")
                sb.append("      \"access\": ").append(encodeJsonString(mount.access.value)).append(",\n")
                sb.append("      \"required\": ").append(mount.required).append(",\n")
                sb.append("      \"label\": ").append(encodeJsonString(mount.label))
                if (mount.help != null) {
                    sb.append(",\n      \"help\": ").append(encodeJsonString(mount.help))
                }
                sb.append("\n    }")
                if (idx < resources.mounts.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("  ")
        }
        sb.append("]\n")
        sb.append("}")
        return sb.toString()
    }

    private fun encodeProfileTypes(profileTypes: List<ProfileTypeDeclaration>): String {
        val sb = StringBuilder()
        sb.append("[")
        if (profileTypes.isNotEmpty()) {
            sb.append("\n")
            for ((idx, type) in profileTypes.withIndex()) {
                sb.append("  ").append(encodeProfileType(type).replace("\n", "\n  "))
                if (idx < profileTypes.size - 1) sb.append(",")
                sb.append("\n")
            }
        }
        sb.append("]")
        return sb.toString()
    }

    private fun encodeProfileType(type: ProfileTypeDeclaration): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"id\": ").append(encodeJsonString(type.id)).append(",\n")
        sb.append("  \"label\": ").append(encodeJsonString(type.label)).append(",\n")
        if (type.help != null) {
            sb.append("  \"help\": ").append(encodeJsonString(type.help)).append(",\n")
        }
        sb.append("  \"schemaVersion\": ").append(type.schemaVersion).append(",\n")
        sb.append("  \"data\": ").append(encodeProfileData(type.schema).replace("\n", "\n  ")).append(",\n")
        sb.append("  \"ui\": ").append(encodeProfileUi(type.schema).replace("\n", "\n  ")).append("\n")
        sb.append("}")
        return sb.toString()
    }

    private fun encodeProfileData(schema: ProfileSchema): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"additionalProperties\": false,\n")
        sb.append("  \"fields\": [\n")
        for ((idx, field) in schema.dataFields.withIndex()) {
            sb.append("    ").append(encodeProfileDataField(field).replace("\n", "\n    "))
            if (idx < schema.dataFields.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}")
        return sb.toString()
    }

    private fun encodeProfileDataField(field: ProfileFieldDeclaration): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"id\": ").append(encodeJsonString(field.id)).append(",\n")
        sb.append("  \"required\": ").append(field.required).append(",\n")
        when (field) {
            is ProfileFieldDeclaration.StringField -> {
                sb.append("  \"type\": \"string\"")
                if (field.default != null) {
                    sb.append(",\n  \"default\": ").append(encodeJsonString(field.default))
                }
                if (field.allowedValues != null) {
                    sb.append(",\n  \"allowedValues\": [\n")
                    for ((idx, v) in field.allowedValues.withIndex()) {
                        sb.append("    ").append(encodeJsonString(v))
                        if (idx < field.allowedValues.size - 1) sb.append(",")
                        sb.append("\n")
                    }
                    sb.append("  ]")
                }
                sb.append("\n")
            }
            is ProfileFieldDeclaration.BooleanField -> {
                sb.append("  \"type\": \"boolean\"")
                if (field.default != null) {
                    sb.append(",\n  \"default\": ").append(field.default)
                }
                sb.append("\n")
            }
            is ProfileFieldDeclaration.IntegerField -> {
                sb.append("  \"type\": \"integer\"")
                if (field.default != null) {
                    sb.append(",\n  \"default\": ").append(field.default)
                }
                if (field.minimum != null) {
                    sb.append(",\n  \"minimum\": ").append(field.minimum)
                }
                if (field.maximum != null) {
                    sb.append(",\n  \"maximum\": ").append(field.maximum)
                }
                sb.append("\n")
            }
            is ProfileFieldDeclaration.SecretField -> {
                sb.append("  \"type\": \"secret\"\n")
            }
        }
        sb.append("}")
        return sb.toString()
    }

    private fun encodeProfileUi(schema: ProfileSchema): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"fields\": [\n")
        for ((idx, field) in schema.uiFields.withIndex()) {
            sb.append("    ").append(encodeProfileUiField(field).replace("\n", "\n    "))
            if (idx < schema.uiFields.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}")
        return sb.toString()
    }

    private fun encodeProfileUiField(field: ProfileUiFieldDeclaration): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"field\": ").append(encodeJsonString(field.field)).append(",\n")
        sb.append("  \"control\": ").append(encodeJsonString(field.control.value)).append(",\n")
        sb.append("  \"label\": ").append(encodeJsonString(field.label))
        if (field.help != null) {
            sb.append(",\n  \"help\": ").append(encodeJsonString(field.help))
        }
        if (field.choices != null) {
            sb.append(",\n  \"choices\": [\n")
            for ((idx, choice) in field.choices.withIndex()) {
                sb.append("    {\n")
                sb.append("      \"value\": ").append(encodeJsonString(choice.value)).append(",\n")
                sb.append("      \"label\": ").append(encodeJsonString(choice.label)).append("\n")
                sb.append("    }")
                if (idx < field.choices.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("  ]")
        }
        sb.append("\n}")
        return sb.toString()
    }

    private fun encodeChoiceResolvers(resolvers: List<ChoiceResolverDeclaration>): String {
        val sb = StringBuilder()
        sb.append("[")
        if (resolvers.isNotEmpty()) {
            sb.append("\n")
            for ((idx, resolver) in resolvers.withIndex()) {
                sb.append("  {\n")
                sb.append("    \"id\": ").append(encodeJsonString(resolver.id)).append(",\n")
                sb.append("    \"module\": ").append(encodeJsonString(resolver.module)).append(",\n")
                sb.append("    \"capabilities\": ")
                val caps = resolver.capabilities
                if (caps.isEmpty()) {
                    sb.append("[]")
                } else {
                    sb.append("[\n")
                    for ((cIdx, cap) in caps.withIndex()) {
                        sb.append("      ").append(encodeJsonString(cap))
                        if (cIdx < caps.size - 1) sb.append(",")
                        sb.append("\n")
                    }
                    sb.append("    ]")
                }
                sb.append("\n  }")
                if (idx < resolvers.size - 1) sb.append(",")
                sb.append("\n")
            }
        }
        sb.append("]")
        return sb.toString()
    }

    private fun encodeWorkQueues(queues: List<WorkQueueDeclaration>): String {
        val sb = StringBuilder()
        sb.append("[")
        if (queues.isNotEmpty()) {
            sb.append("\n")
            for ((idx, queue) in queues.withIndex()) {
                sb.append("  ").append(encodeJsonString(queue.id))
                if (idx < queues.size - 1) sb.append(",")
                sb.append("\n")
            }
        }
        sb.append("]")
        return sb.toString()
    }
    private fun encodeSourceRecord(source: PackageSourceRecord): String {
        return """{
  "repositoryId": ${encodeJsonString(source.repositoryId.value)},
  "coordinates": {
    "owner": ${encodeJsonString(source.coordinates.owner)},
    "repository": ${encodeJsonString(source.coordinates.repository)}
  },
  "release": {
    "releaseId": ${encodeJsonString(source.release.releaseId)},
    "tag": ${encodeJsonString(source.release.tag)},
    "isPrerelease": ${source.release.isPrerelease}
  },
  "asset": {
    "assetId": ${encodeJsonString(source.asset.assetId)},
    "name": ${encodeJsonString(source.asset.name)}
  },
  "ownerId": ${encodeJsonString(source.ownerId)}
}"""
    }

    private fun encodeJsonString(str: String): String {
        val sb = java.lang.StringBuilder()
        sb.append('"')
        for (i in 0 until str.length) {
            val c = str[i]
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '/' -> sb.append("\\/")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun decodeIndex(json: String): StoredInstalledIndex {
        val root = try {
            PackageValidator.StrictJsonParser(json).parse()
        } catch (e: Exception) {
            throw IllegalArgumentException("JSON parsing failed: ${e.message}", e)
        }
        validateNoUnknownKeys(root, setOf("version", "providers"))
        val version = requireNonNullKey(root, "version").asInt()
        if (version != 1) {
            throw IllegalArgumentException("Unsupported index version: $version")
        }
        val providersRaw = requireNonNullKey(root, "providers").asMap()
        val providers = LinkedHashMap<GitHubRepositoryIdentity, StoredProviderRecord>()
        for ((repoIdStr, providerVal) in providersRaw) {
            val repoId = GitHubRepositoryIdentity(repoIdStr)
            val derivedId = InstalledProviderId.derive(repoId)
            if (!InstalledProviderId.isInstalled(derivedId)) {
                throw IllegalArgumentException("Reserved or invalid namespace for provider: $repoIdStr")
            }
            val recMap = providerVal.asMap()
            validateNoUnknownKeys(recMap, setOf("active", "rollback"))

            val activeVal = requireNonNullKey(recMap, "active")
            val active = decodeRevision(activeVal, repoId)

            val rollback = if (recMap.containsKey("rollback")) {
                val r = recMap["rollback"]
                if (r != null && r != "null") {
                    decodeRevision(r, repoId)
                } else {
                    null
                }
            } else {
                null
            }

            providers[repoId] = StoredProviderRecord(active, rollback)
        }
        return StoredInstalledIndex(version, providers)
    }

    private fun decodeRevision(value: Any?, expectedRepoId: GitHubRepositoryIdentity): StoredPackageRevision {
        val map = value.asMap()
        validateNoUnknownKeys(map, setOf("digest", "manifest", "sourceRecord"))

        val digestVal = requireNonNullKey(map, "digest").asString()
        val digest = ArtifactDigest(digestVal)

        val manifestVal = requireNonNullKey(map, "manifest").asMap()
        val decodedManifest = decodeManifest(manifestVal, expectedRepoId)

        val sourceVal = requireNonNullKey(map, "sourceRecord").asMap()
        val sourceRecord = decodeSourceRecord(sourceVal, expectedRepoId)

        return StoredPackageRevision(
            digest = digest,
            manifest = decodedManifest,
            sourceRecord = sourceRecord
        )
    }


    private fun decodeManifest(map: Map<String, Any?>, expectedRepoId: GitHubRepositoryIdentity): PackageManifest {
        validateNoUnknownKeys(map, setOf("manifestVersion", "repositoryId", "packageVersion", "entryModule", "presentation", "runtime", "configuration", "resources", "profileTypes", "choiceResolvers", "workQueues", "capabilities"))

        val manifestVersion = requireNonNullKey(map, "manifestVersion").asInt()
        val repositoryIdStr = requireNonNullKey(map, "repositoryId").asString()
        val repositoryId = GitHubRepositoryIdentity(repositoryIdStr)
        if (repositoryId != expectedRepoId) {
            throw IllegalArgumentException("Inconsistent repository ID in manifest: expected ${expectedRepoId.value} but got ${repositoryId.value}")
        }

        val packageVersion = requireNonNullKey(map, "packageVersion").asString()
        val entryModule = requireNonNullKey(map, "entryModule").asString()

        val presentationMap = requireNonNullKey(map, "presentation").asMap()
        validateNoUnknownKeys(presentationMap, setOf("label", "summary"))
        val label = requireNonNullKey(presentationMap, "label").asString()
        val summary = requireNonNullKey(presentationMap, "summary").asString()
        val presentation = PackagePresentation(label, summary)

        val runtimeMap = requireNonNullKey(map, "runtime").asMap()
        validateNoUnknownKeys(runtimeMap, setOf("luaVersion", "apiVersion"))
        val luaVersion = requireNonNullKey(runtimeMap, "luaVersion").asString()
        val apiVersion = requireNonNullKey(runtimeMap, "apiVersion").asString()
        val runtime = RuntimeRequirements(luaVersion, apiVersion)

        val configuration = decodeConfiguration(requireNonNullKey(map, "configuration").asMap())
        val resources = decodeResources(requireNonNullKey(map, "resources").asMap())
        val profileTypes = decodeProfileTypes(requireNonNullKey(map, "profileTypes"))
        val choiceResolvers = decodeChoiceResolvers(requireNonNullKey(map, "choiceResolvers"))
        val workQueues = decodeWorkQueues(requireNonNullKey(map, "workQueues"))

        val capsVal = requireNonNullKey(map, "capabilities")
        if (capsVal !is List<*>) {
            throw IllegalArgumentException("Expected JSON array for capabilities")
        }
        val decodedCapabilities = LinkedHashSet<String>()
        for (cap in capsVal) {
            if (cap !is String) {
                throw IllegalArgumentException("Expected string in capabilities list")
            }
            if (!PackageCapability.ALL.contains(cap)) {
                throw IllegalArgumentException("Unknown capability ID: $cap")
            }
            if (!decodedCapabilities.add(cap)) {
                throw IllegalArgumentException("Duplicate capability: $cap")
            }
        }

        return PackageManifest(
            manifestVersion = manifestVersion,
            repositoryId = repositoryId,
            packageVersion = packageVersion,
            entryModule = entryModule,
            presentation = presentation,
            runtime = runtime,
            configuration = configuration,
            resources = resources,
            profileTypes = profileTypes,
            choiceResolvers = choiceResolvers,
            workQueues = workQueues,
            capabilities = decodedCapabilities
        )
    }

    private fun decodeConfiguration(map: Map<String, Any?>): PackageConfigurationDeclaration {
        validateNoUnknownKeys(map, setOf("schemaVersion", "data", "ui"))
        val schemaVersion = requireNonNullKey(map, "schemaVersion").asInt()
        if (schemaVersion != 1) {
            throw IllegalArgumentException("Unsupported configuration schema version: $schemaVersion")
        }
        val dataVal = requireNonNullKey(map, "data").asMap()
        val data = decodeConfigurationData(dataVal)

        val uiVal = requireNonNullKey(map, "ui").asMap()
        val ui = decodeConfigurationUi(uiVal)

        return PackageConfigurationDeclaration(data, ui)
    }

    private fun decodeResources(map: Map<String, Any?>): PackageResourcesDeclaration {
        validateNoUnknownKeys(map, setOf("mounts"))
        val mountsVal = requireNonNullKey(map, "mounts")
        if (mountsVal !is List<*>) {
            throw IllegalArgumentException("Expected mounts to be array")
        }
        val mounts = ArrayList<PackageMountDeclaration>()
        for (mountVal in mountsVal) {
            val mountMap = mountVal.asMap()
            validateNoUnknownKeys(mountMap, setOf("id", "kind", "access", "required", "label", "help"))
            val id = requireNonNullKey(mountMap, "id").asString()
            val kindStr = requireNonNullKey(mountMap, "kind").asString()
            val kind = PackageMountKind.entries.firstOrNull { it.value == kindStr }
                ?: throw IllegalArgumentException("Unsupported mount kind: $kindStr")
            val accessStr = requireNonNullKey(mountMap, "access").asString()
            val access = PackageMountAccess.entries.firstOrNull { it.value == accessStr }
                ?: throw IllegalArgumentException("Unsupported mount access: $accessStr")
            val required = requireNonNullKey(mountMap, "required").asBoolean()
            val label = requireNonNullKey(mountMap, "label").asString()
            val help = if (mountMap.containsKey("help")) {
                mountMap["help"].asString()
            } else {
                null
            }
            mounts.add(PackageMountDeclaration(id, kind, access, required, label, help))
        }
        return PackageResourcesDeclaration(mounts)
    }

    private fun decodeProfileTypes(value: Any?): List<ProfileTypeDeclaration> {
        if (value !is List<*>) {
            throw IllegalArgumentException("Expected profileTypes to be array")
        }
        val types = ArrayList<ProfileTypeDeclaration>()
        for (typeVal in value) {
            val typeMap = typeVal.asMap()
            validateNoUnknownKeys(typeMap, setOf("id", "label", "help", "schemaVersion", "data", "ui"))
            val id = requireNonNullKey(typeMap, "id").asString()
            val typeLabel = requireNonNullKey(typeMap, "label").asString()
            val help = if (typeMap.containsKey("help")) {
                requireNonNullKey(typeMap, "help").asString()
            } else {
                null
            }
            val schemaVersion = requireNonNullKey(typeMap, "schemaVersion").asInt()
            val data = decodeProfileData(requireNonNullKey(typeMap, "data").asMap())
            val ui = decodeProfileUi(requireNonNullKey(typeMap, "ui").asMap())
            types.add(ProfileTypeDeclaration(id, typeLabel, help, schemaVersion, ProfileSchema(data, ui)))
        }
        return types
    }

    private fun decodeProfileData(map: Map<String, Any?>): List<ProfileFieldDeclaration> {
        validateNoUnknownKeys(map, setOf("fields", "additionalProperties"))
        if (map.containsKey("additionalProperties")) {
            val addProp = map["additionalProperties"]
            if (addProp !is Boolean) {
                throw IllegalArgumentException("additionalProperties must be boolean")
            }
        }
        val fieldsVal = requireNonNullKey(map, "fields")
        if (fieldsVal !is List<*>) {
            throw IllegalArgumentException("Expected fields to be array")
        }
        val fields = ArrayList<ProfileFieldDeclaration>()
        for (fieldVal in fieldsVal) {
            val fieldMap = fieldVal.asMap()
            val typeStr = requireNonNullKey(fieldMap, "type").asString()
            val allowedKeys = when (typeStr) {
                "string" -> setOf("id", "type", "required", "default", "allowedValues")
                "boolean" -> setOf("id", "type", "required", "default")
                "integer" -> setOf("id", "type", "required", "default", "minimum", "maximum")
                "secret" -> setOf("id", "type", "required")
                else -> throw IllegalArgumentException("Unknown profile field type: $typeStr")
            }
            validateNoUnknownKeys(fieldMap, allowedKeys)
            val id = requireNonNullKey(fieldMap, "id").asString()
            val required = requireNonNullKey(fieldMap, "required").asBoolean()
            val decl = when (typeStr) {
                "string" -> {
                    val default = if (fieldMap.containsKey("default")) {
                        requireNonNullKey(fieldMap, "default").asString()
                    } else {
                        null
                    }
                    val allowedValues = if (fieldMap.containsKey("allowedValues")) {
                        val avVal = requireNonNullKey(fieldMap, "allowedValues")
                        if (avVal !is List<*>) {
                            throw IllegalArgumentException("allowedValues must be array")
                        }
                        val strings = ArrayList<String>()
                        for (av in avVal) {
                            strings.add(av.asString())
                        }
                        strings
                    } else {
                        null
                    }
                    ProfileFieldDeclaration.StringField(id, required, default, allowedValues)
                }
                "boolean" -> {
                    val default = if (fieldMap.containsKey("default")) {
                        requireNonNullKey(fieldMap, "default").asBoolean()
                    } else {
                        null
                    }
                    ProfileFieldDeclaration.BooleanField(id, required, default)
                }
                "integer" -> {
                    val default = if (fieldMap.containsKey("default")) {
                        asLong(requireNonNullKey(fieldMap, "default"))
                            ?: throw IllegalArgumentException("default must be integer for integer profile field")
                    } else {
                        null
                    }
                    val minimum = if (fieldMap.containsKey("minimum")) {
                        asLong(requireNonNullKey(fieldMap, "minimum"))
                            ?: throw IllegalArgumentException("minimum must be integer for integer profile field")
                    } else {
                        null
                    }
                    val maximum = if (fieldMap.containsKey("maximum")) {
                        asLong(requireNonNullKey(fieldMap, "maximum"))
                            ?: throw IllegalArgumentException("maximum must be integer for integer profile field")
                    } else {
                        null
                    }
                    ProfileFieldDeclaration.IntegerField(id, required, default, minimum, maximum)
                }
                else -> ProfileFieldDeclaration.SecretField(id, required)
            }
            fields.add(decl)
        }
        return fields
    }

    private fun decodeProfileUi(map: Map<String, Any?>): List<ProfileUiFieldDeclaration> {
        validateNoUnknownKeys(map, setOf("fields"))
        val fieldsVal = requireNonNullKey(map, "fields")
        if (fieldsVal !is List<*>) {
            throw IllegalArgumentException("Expected fields to be array")
        }
        val fields = ArrayList<ProfileUiFieldDeclaration>()
        for (fieldVal in fieldsVal) {
            val fieldMap = fieldVal.asMap()
            val controlStr = requireNonNullKey(fieldMap, "control").asString()
            val control = ProfileUiControl.entries.firstOrNull { it.value == controlStr }
                ?: throw IllegalArgumentException("Unknown profile ui control: $controlStr")
            val allowedKeys = if (control == ProfileUiControl.CHOICE) {
                setOf("field", "control", "label", "help", "choices")
            } else {
                setOf("field", "control", "label", "help")
            }
            validateNoUnknownKeys(fieldMap, allowedKeys)
            val field = requireNonNullKey(fieldMap, "field").asString()
            val uiLabel = requireNonNullKey(fieldMap, "label").asString()
            val help = if (fieldMap.containsKey("help")) {
                requireNonNullKey(fieldMap, "help").asString()
            } else {
                null
            }
            val choices = if (control == ProfileUiControl.CHOICE) {
                val choicesVal = requireNonNullKey(fieldMap, "choices")
                if (choicesVal !is List<*>) {
                    throw IllegalArgumentException("choices must be array")
                }
                val list = ArrayList<ProfileUiChoice>()
                for (choiceVal in choicesVal) {
                    val choiceMap = choiceVal.asMap()
                    validateNoUnknownKeys(choiceMap, setOf("value", "label"))
                    val cVal = requireNonNullKey(choiceMap, "value").asString()
                    val cLabel = requireNonNullKey(choiceMap, "label").asString()
                    list.add(ProfileUiChoice(cVal, cLabel))
                }
                list
            } else {
                null
            }
            fields.add(ProfileUiFieldDeclaration(field, control, uiLabel, help, choices))
        }
        return fields
    }

    private fun decodeChoiceResolvers(value: Any?): List<ChoiceResolverDeclaration> {
        if (value !is List<*>) {
            throw IllegalArgumentException("Expected choiceResolvers to be array")
        }
        val resolvers = ArrayList<ChoiceResolverDeclaration>()
        for (resolverVal in value) {
            val resolverMap = resolverVal.asMap()
            validateNoUnknownKeys(resolverMap, setOf("id", "module", "capabilities"))
            val id = requireNonNullKey(resolverMap, "id").asString()
            val module = requireNonNullKey(resolverMap, "module").asString()
            val capsVal = requireNonNullKey(resolverMap, "capabilities")
            if (capsVal !is List<*>) {
                throw IllegalArgumentException("Expected resolver capabilities to be array")
            }
            val caps = LinkedHashSet<String>()
            for (cap in capsVal) {
                val capStr = cap.asString()
                if (!PackageCapability.RESOLVER_ELIGIBLE.contains(capStr)) {
                    throw IllegalArgumentException("Resolver capability not eligible: $capStr")
                }
                if (!caps.add(capStr)) {
                    throw IllegalArgumentException("Duplicate resolver capability: $capStr")
                }
            }
            resolvers.add(ChoiceResolverDeclaration(id, module, caps))
        }
        return resolvers
    }

    private fun decodeWorkQueues(value: Any?): List<WorkQueueDeclaration> {
        if (value !is List<*>) {
            throw IllegalArgumentException("Expected workQueues to be array")
        }
        val queues = ArrayList<WorkQueueDeclaration>()
        for (queueVal in value) {
            queues.add(WorkQueueDeclaration(queueVal.asString()))
        }
        return queues
    }

    private fun decodeConfigurationData(map: Map<String, Any?>): ConfigurationDataDeclaration {
        validateNoUnknownKeys(map, setOf("fields", "additionalProperties"))
        if (map.containsKey("additionalProperties")) {
            val addProp = map["additionalProperties"]
            if (addProp !is Boolean) {
                throw IllegalArgumentException("additionalProperties must be boolean")
            }
        }
        val fieldsVal = requireNonNullKey(map, "fields")
        if (fieldsVal !is List<*>) {
            throw IllegalArgumentException("Expected fields to be array")
        }
        val fields = ArrayList<ConfigurationFieldDeclaration>()
        for (fieldVal in fieldsVal) {
            val fieldMap = fieldVal.asMap()
            validateNoUnknownKeys(fieldMap, setOf("id", "type", "default", "allowedValues", "minimum", "maximum"))
            val id = requireNonNullKey(fieldMap, "id").asString()
            val typeStr = requireNonNullKey(fieldMap, "type").asString()
            val defaultVal = requireNonNullKey(fieldMap, "default")

            val decl = when (typeStr) {
                "string" -> {
                    if (fieldMap.containsKey("minimum") || fieldMap.containsKey("maximum")) {
                        throw IllegalArgumentException("String field cannot have minimum or maximum")
                    }
                    val defaultStr = defaultVal.asString()
                    val allowedValues = if (fieldMap.containsKey("allowedValues")) {
                        val avList = requireNonNullKey(fieldMap, "allowedValues")
                        if (avList !is List<*>) {
                            throw IllegalArgumentException("allowedValues must be array")
                        }
                        val strings = ArrayList<String>()
                        for (av in avList) {
                            strings.add(av.asString())
                        }
                        strings
                    } else {
                        null
                    }
                    ConfigurationFieldDeclaration.StringField(id, defaultStr, allowedValues)
                }
                "boolean" -> {
                    if (fieldMap.containsKey("allowedValues") || fieldMap.containsKey("minimum") || fieldMap.containsKey("maximum")) {
                        throw IllegalArgumentException("Boolean field cannot have allowedValues, minimum, or maximum")
                    }
                    val defaultBool = defaultVal.asBoolean()
                    ConfigurationFieldDeclaration.BooleanField(id, defaultBool)
                }
                "integer" -> {
                    if (fieldMap.containsKey("allowedValues")) {
                        throw IllegalArgumentException("Integer field cannot have allowedValues")
                    }
                    val defaultLong = asLong(defaultVal)
                        ?: throw IllegalArgumentException("default must be integer for integer field")
                    val minimum = if (fieldMap.containsKey("minimum")) {
                        asLong(fieldMap["minimum"])
                            ?: throw IllegalArgumentException("minimum must be integer for integer field")
                    } else {
                        null
                    }
                    val maximum = if (fieldMap.containsKey("maximum")) {
                        asLong(fieldMap["maximum"])
                            ?: throw IllegalArgumentException("maximum must be integer for integer field")
                    } else {
                        null
                    }
                    ConfigurationFieldDeclaration.IntegerField(id, defaultLong, minimum, maximum)
                }
                else -> {
                    throw IllegalArgumentException("Unknown field type: $typeStr")
                }
            }
            fields.add(decl)
        }
        return ConfigurationDataDeclaration(fields)
    }

    private fun decodeConfigurationUi(map: Map<String, Any?>): ConfigurationUiDeclaration {
        validateNoUnknownKeys(map, setOf("fields"))
        val fieldsVal = requireNonNullKey(map, "fields")
        if (fieldsVal !is List<*>) {
            throw IllegalArgumentException("Expected fields to be array")
        }
        val fields = ArrayList<UiFieldDeclaration>()
        for (fieldVal in fieldsVal) {
            val fieldMap = fieldVal.asMap()
            val controlStr = requireNonNullKey(fieldMap, "control").asString()
            val control = when (controlStr) {
                "text" -> UiControl.TEXT
                "toggle" -> UiControl.TOGGLE
                "number" -> UiControl.NUMBER
                "choice" -> UiControl.CHOICE
                "multiline" -> UiControl.MULTILINE
                "dynamic-choice" -> UiControl.DYNAMIC_CHOICE
                else -> throw IllegalArgumentException("Unknown ui control: $controlStr")
            }
            // Per-control exact-key validation mirrors the manifest validator: a
            // dynamic-choice entry carries a bounded `source` and no static `choices`,
            // while every static control carries optional `choices` and no `source`.
            val allowedKeys = if (control == UiControl.DYNAMIC_CHOICE) {
                setOf("field", "control", "label", "help", "source", "dependsOn")
            } else {
                setOf("field", "control", "label", "help", "choices")
            }
            validateNoUnknownKeys(fieldMap, allowedKeys)
            val field = requireNonNullKey(fieldMap, "field").asString()
            val label = requireNonNullKey(fieldMap, "label").asString()
            val help = if (fieldMap.containsKey("help")) {
                val h = fieldMap["help"]
                if (h != null) {
                    val hs = h.asString()
                    if (hs.isBlank()) throw IllegalArgumentException("help must not be blank")
                    hs
                } else null
            } else {
                null
            }
            val choices = if (fieldMap.containsKey("choices")) {
                val choicesVal = fieldMap["choices"]
                if (choicesVal != null && choicesVal != "null") {
                    if (choicesVal !is List<*>) {
                        throw IllegalArgumentException("choices must be array")
                    }
                    val list = ArrayList<UiChoice>()
                    for (choiceVal in choicesVal) {
                        val choiceMap = choiceVal.asMap()
                        validateNoUnknownKeys(choiceMap, setOf("value", "label"))
                        val value = requireNonNullKey(choiceMap, "value").asString()
                        val cLabel = requireNonNullKey(choiceMap, "label").asString()
                        list.add(UiChoice(value, cLabel))
                    }
                    list
                } else {
                    null
                }
            } else {
                null
            }
            // Round-trip the raw dynamic-choice source exactly. Authority over which
            // sources are valid belongs to reparse-revalidation (PackageValidator) and
            // the immutable UiFieldDeclaration invariant, not to this index codec.
            val source = if (control == UiControl.DYNAMIC_CHOICE) {
                requireNonNullKey(fieldMap, "source").asString()
            } else {
                null
            }
            val dependsOn = if (control == UiControl.DYNAMIC_CHOICE && fieldMap.containsKey("dependsOn")) {
                requireNonNullKey(fieldMap, "dependsOn").asString()
            } else {
                null
            }
            fields.add(UiFieldDeclaration(field, control, label, help, choices, source, dependsOn))
        }
        return ConfigurationUiDeclaration(fields)
    }

    private fun asLong(value: Any?): Long? {
        if (value is Number) {
            val doubleVal = value.toDouble()
            val longVal = value.toLong()
            if (doubleVal == longVal.toDouble()) {
                return longVal
            }
        }
        return null
    }

    private fun decodeSourceRecord(map: Map<String, Any?>, expectedRepoId: GitHubRepositoryIdentity): PackageSourceRecord {
        validateNoUnknownKeys(map, setOf("repositoryId", "coordinates", "release", "asset", "ownerId"))

        val repositoryIdStr = requireNonNullKey(map, "repositoryId").asString()
        val repositoryId = GitHubRepositoryIdentity(repositoryIdStr)
        if (repositoryId != expectedRepoId) {
            throw IllegalArgumentException("Inconsistent repository ID in sourceRecord: expected ${expectedRepoId.value} but got ${repositoryId.value}")
        }

        val coordinatesMap = requireNonNullKey(map, "coordinates").asMap()
        validateNoUnknownKeys(coordinatesMap, setOf("owner", "repository"))
        val owner = requireNonNullKey(coordinatesMap, "owner").asString()
        val repository = requireNonNullKey(coordinatesMap, "repository").asString()
        val coordinates = GitHubRepositoryCoordinates(owner, repository)

        val releaseMap = requireNonNullKey(map, "release").asMap()
        validateNoUnknownKeys(releaseMap, setOf("releaseId", "tag", "isPrerelease"))
        val releaseId = requireNonNullKey(releaseMap, "releaseId").asString()
        val tag = requireNonNullKey(releaseMap, "tag").asString()
        val isPrerelease = requireNonNullKey(releaseMap, "isPrerelease").asBoolean()
        val release = GitHubReleaseIdentity(releaseId, tag, isPrerelease)

        val assetMap = requireNonNullKey(map, "asset").asMap()
        validateNoUnknownKeys(assetMap, setOf("assetId", "name"))
        val assetId = requireNonNullKey(assetMap, "assetId").asString()
        val name = requireNonNullKey(assetMap, "name").asString()
        val asset = GitHubAssetIdentity(assetId, name)

        val ownerId = requireNonNullKey(map, "ownerId").asString()

        return PackageSourceRecord(repositoryId, coordinates, release, asset, ownerId)
    }

    // Helper functions
    private fun Any?.asMap(): Map<String, Any?> {
        if (this !is Map<*, *>) {
            throw IllegalArgumentException("Expected JSON object")
        }
        @Suppress("UNCHECKED_CAST")
        return this as Map<String, Any?>
    }

    private fun Any?.asString(): String {
        if (this !is String) {
            throw IllegalArgumentException("Expected JSON string")
        }
        return this
    }

    private fun Any?.asInt(): Int {
        if (this !is Number) {
            throw IllegalArgumentException("Expected JSON number (integer)")
        }
        val doubleVal = this.toDouble()
        if (doubleVal != this.toLong().toDouble()) {
            throw IllegalArgumentException("Expected integer, got float/decimal: $this")
        }
        val intVal = this.toInt()
        if (intVal.toLong() != this.toLong()) {
            throw IllegalArgumentException("Integer value out of bounds: $this")
        }
        return intVal
    }

    private fun Any?.asBoolean(): Boolean {
        if (this !is Boolean) {
            throw IllegalArgumentException("Expected JSON boolean")
        }
        return this
    }

    private fun requireNonNullKey(map: Map<String, Any?>, key: String): Any {
        if (!map.containsKey(key)) {
            throw IllegalArgumentException("Missing key: $key")
        }
        val value = map[key]
        if (value == null) {
            throw IllegalArgumentException("Key '$key' must not be null")
        }
        return value
    }

    private fun validateNoUnknownKeys(map: Map<String, Any?>, allowedKeys: Set<String>) {
        for (k in map.keys) {
            if (!allowedKeys.contains(k)) {
                throw IllegalArgumentException("Unknown key: $k")
            }
        }
    }
}

/**
 * enum representing the loadIndex selected recovery generation.
 */
public enum class SelectedGeneration {
    CURRENT,
    BACKUP,
    EMPTY
}

/**
 * Result data class for loadIndex.
 */
public data class LoadIndexResult(
    val index: StoredInstalledIndex,
    val generation: SelectedGeneration
)

/**
 * Result data class for cleanup.
 */
public data class CleanupResult(
    val inspectedCount: Int,
    val deletedCount: Int
)

/**
 * Immutable result carrying valid bindings, typed per-provider failures, and stored
 * revision records for every indexed active provider (both valid and failed).
 *
 * The [records] map supplies source identity (release ID, asset ID, digest) for
 * bounded diagnostics without exposing mutable filesystem paths, archive bytes,
 * Lua source, tag names, or coordinates.
 *
 * Invariants enforced at construction:
 * - `records.keys` must equal `bindings.keys ∪ failures.keys`
 * - `bindings.keys` and `failures.keys` must be disjoint
 */
public class MaterializationResult internal constructor(
    bindings: Map<ChannelImplementationId, InstalledProviderBinding>,
    failures: Map<ChannelImplementationId, PackageFailure>,
    records: Map<ChannelImplementationId, StoredPackageRevision>,
    resolverPublications: Map<ChannelImplementationId, io.talkcan.lua.resolver.PackageResolverPublication> = emptyMap(),
) {
    init {
        val bindingKeys = bindings.keys
        val failureKeys = failures.keys
        val recordKeys = records.keys
        val bindingFailureIntersection = bindingKeys.intersect(failureKeys)
        require(bindingFailureIntersection.isEmpty()) {
            "bindings and failures keys must be disjoint; intersection: $bindingFailureIntersection"
        }
        val expectedRecordKeys = bindingKeys + failureKeys
        require(recordKeys == expectedRecordKeys) {
            "records.keys must equal bindings.keys ∪ failures.keys; " +
                "expected=$expectedRecordKeys actual=$recordKeys"
        }
    }

    val bindings: Map<ChannelImplementationId, InstalledProviderBinding> =
        Collections.unmodifiableMap(LinkedHashMap(bindings))

    val failures: Map<ChannelImplementationId, PackageFailure> =
        Collections.unmodifiableMap(LinkedHashMap(failures))

    val records: Map<ChannelImplementationId, StoredPackageRevision> =
        Collections.unmodifiableMap(LinkedHashMap(records))

    /**
     * Task 10.7: Resolver publications keyed by provider ID. Internal because
     * [io.talkcan.lua.resolver.PackageResolverPublication] is internal.
     * Consumed by the coordinator to publish/invalidate resolver factories.
     */
    internal val resolverPublications: Map<ChannelImplementationId, io.talkcan.lua.resolver.PackageResolverPublication> =
        Collections.unmodifiableMap(LinkedHashMap(resolverPublications))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MaterializationResult) return false
        return bindings == other.bindings && failures == other.failures && records == other.records
    }

    override fun hashCode(): Int {
        return 31 * (31 * bindings.hashCode() + failures.hashCode()) + records.hashCode()
    }

    override fun toString(): String {
        return "MaterializationResult(bindings=${bindings.size}, failures=${failures.size}, records=${records.size})"
    }
}

/**
 * Internal durable boundary identifiers for fault injection in durable commit operations.
 * Each constant represents a specific named operation boundary that tests can target
 * to simulate I/O failures deterministically.
 */
internal enum class DurableBoundary {
    STAGED_CONTENT_FSYNC,
    CONTENT_ATOMIC_MOVE,
    CONTENT_DIR_FSYNC,
    STAGING_DIR_FSYNC_AFTER_CONTENT,
    INDEX_TEMP_WRITE,
    INDEX_TEMP_FSYNC,
    CURRENT_TO_BACKUP_MOVE,
    TEMP_TO_CURRENT_MOVE,
    STORE_DIR_FSYNC,
    STAGING_DIR_FSYNC_AFTER_INDEX
}

/**
 * Internal fault injection callback for deterministic I/O failure testing.
 * Allocation-free on the default path when using [NOOP]; throws an exception
 * when a test installs an injector that targets a specific [DurableBoundary].
 *
 * The injected exception is caught by existing failure/rollback paths — no exception
 * escapes a public [PackageOutcome] method. Recovery and rollback fsync/move operations
 * never invoke the injector, letting tests assert that prior authoritative state survives.
 */
internal fun interface FaultInjector {
    fun inject(boundary: DurableBoundary)

    companion object {
        val NOOP: FaultInjector = FaultInjector { }
    }
}


/**
 * Production local storage client managing the files and metadata in io.talkcan.dependency.
 */
public class InstalledPackageStore internal constructor(
    private val storeRoot: File,
    private val faultInjector: FaultInjector,
    private val logSink: PluginLogSink = NoOpPluginLogSink,
    private val runtimeResourcesFactory: LuaRuntimeResourcesFactory? = null,
    private val preparerRegistry: CapabilityPreparerRegistry = CapabilityPreparerRegistry.empty(),
    private val dynamicChoiceResolver: DynamicConfigurationChoiceResolver? = null,
    private val keyboardOutputAdapterFactory:
        ((CapabilityScopeIdentity, OutputExecutionOwner) -> KeyboardOutputAdapter)? = null,
    // Task 13.3/13.4: adapter composition pass-through
    private val secretStore: io.talkcan.secret.ProtectedSecretStore? = null,
    private val httpTransport: io.talkcan.http.GenericHttpTransport? = null,
    private val workStore: io.talkcan.work.DurableWorkStore? = null,
    private val workCoordinator: io.talkcan.work.DurableWorkCoordinator? = null,
    private val profileRecordProvider: (suspend (String) -> io.talkcan.profile.ProfileRecord?)? = null,
) {
    public constructor(storeRoot: File) : this(storeRoot, FaultInjector.NOOP)
    internal constructor(storeRoot: File, logSink: PluginLogSink) : this(storeRoot, FaultInjector.NOOP, logSink)
    internal constructor(
        storeRoot: File,
        logSink: PluginLogSink,
        runtimeResourcesFactory: LuaRuntimeResourcesFactory,
    ) : this(storeRoot, FaultInjector.NOOP, logSink, runtimeResourcesFactory)
    internal constructor(
        storeRoot: File,
        logSink: PluginLogSink,
        runtimeResourcesFactory: LuaRuntimeResourcesFactory?,
        preparerRegistry: CapabilityPreparerRegistry,
        dynamicChoiceResolver: DynamicConfigurationChoiceResolver?,
        keyboardOutputAdapterFactory: ((CapabilityScopeIdentity, OutputExecutionOwner) -> KeyboardOutputAdapter)?,
        secretStore: io.talkcan.secret.ProtectedSecretStore? = null,
        httpTransport: io.talkcan.http.GenericHttpTransport? = null,
        workStore: io.talkcan.work.DurableWorkStore? = null,
        workCoordinator: io.talkcan.work.DurableWorkCoordinator? = null,
        profileRecordProvider: (suspend (String) -> io.talkcan.profile.ProfileRecord?)? = null,
    ) : this(
        storeRoot,
        FaultInjector.NOOP,
        logSink,
        runtimeResourcesFactory,
        preparerRegistry,
        dynamicChoiceResolver,
        keyboardOutputAdapterFactory,
        secretStore,
        httpTransport,
        workStore,
        workCoordinator,
        profileRecordProvider,
    )
    internal val stagingDir = File(storeRoot, "staging")
    internal val contentDir = File(storeRoot, "content/sha256")
    private val currentFile = File(storeRoot, "index.json")
    private val backupFile = File(storeRoot, "index.backup.json")

    // 8: Private operation lock to serialize all database/file operations
    private val operationLock = Any()

    // 4: Do not perform I/O in initialization block; lazily check/fail at boundaries.
    private fun ensureDirectories(): PackageOutcome<Unit> {
        try {
            if (!storeRoot.exists() && !storeRoot.mkdirs() && !storeRoot.exists()) {
                return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.WRITE_FAILED))
            }
            if (!stagingDir.exists() && !stagingDir.mkdirs() && !stagingDir.exists()) {
                return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.WRITE_FAILED))
            }
            if (!contentDir.exists() && !contentDir.mkdirs() && !contentDir.exists()) {
                return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.WRITE_FAILED))
            }
        } catch (e: Exception) {
            return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.WRITE_FAILED))
        }
        return PackageOutcome.Success(Unit)
    }

    /**
     * 3.5, 3.6 & 9: Bounded UTF-8 index selection and loading.
     */
    public fun loadIndex(): PackageOutcome<LoadIndexResult> = synchronized(operationLock) {
        val dirResult = ensureDirectories()
        if (dirResult is PackageOutcome.Failure) return dirResult

        if (!currentFile.exists() && !backupFile.exists()) {
            return PackageOutcome.Success(
                LoadIndexResult(StoredInstalledIndex(1, emptyMap()), SelectedGeneration.EMPTY)
            )
        }

        val maxBytes = 10 * 1024 * 1024 // 10MB index limit

        if (currentFile.exists()) {
            try {
                val json = readBoundedUtf8(currentFile, maxBytes)
                val index = StrictIndexCodec.decodeIndex(json)
                return PackageOutcome.Success(LoadIndexResult(index, SelectedGeneration.CURRENT))
            } catch (e: Exception) {
                // fall back to backup
            }
        }

        if (backupFile.exists()) {
            try {
                val json = readBoundedUtf8(backupFile, maxBytes)
                val index = StrictIndexCodec.decodeIndex(json)
                return PackageOutcome.Success(LoadIndexResult(index, SelectedGeneration.BACKUP))
            } catch (e: Exception) {
                // index is globally corrupt
            }
        }

        return PackageOutcome.Failure(
            PackageFailure.Recovery(PackageFailure.RecoveryDetail.INDEX_CORRUPT)
        )
    }

    /**
     * 3.4 & 6: Atomic complete-index replacement with ATOMIC_MOVE.
     */
    public fun commitIndex(index: StoredInstalledIndex): PackageOutcome<Unit> = synchronized(operationLock) {
        val dirResult = ensureDirectories()
        if (dirResult is PackageOutcome.Failure) return dirResult

        val jsonStr = try {
            StrictIndexCodec.encodeIndex(index)
        } catch (e: Exception) {
            return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.WRITE_FAILED))
        }

        val tmpFile = File(stagingDir, "index.tmp.json")
        try {
            FileOutputStream(tmpFile).use { fos ->
                faultInjector.inject(DurableBoundary.INDEX_TEMP_WRITE)
                fos.write(jsonStr.toByteArray(Charsets.UTF_8))
                faultInjector.inject(DurableBoundary.INDEX_TEMP_FSYNC)
                fos.fd.sync()
            }
        } catch (e: Exception) {
            tmpFile.delete()
            return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.WRITE_FAILED))
        }

        // Rename current to backup (must not silently ignore delete/move failure)
        if (currentFile.exists()) {
            if (backupFile.exists()) {
                val deleted = backupFile.delete()
                if (!deleted && backupFile.exists()) {
                    tmpFile.delete()
                    return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.COMMIT_FAILED))
                }
            }
            try {
                faultInjector.inject(DurableBoundary.CURRENT_TO_BACKUP_MOVE)
                java.nio.file.Files.move(
                    currentFile.toPath(),
                    backupFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: Exception) {
                tmpFile.delete()
                return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.COMMIT_FAILED))
            }
        }

        // Rename tmp to current (must not silently ignore restore failure on exception)
        try {
            faultInjector.inject(DurableBoundary.TEMP_TO_CURRENT_MOVE)
            java.nio.file.Files.move(
                tmpFile.toPath(),
                currentFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: Exception) {
            tmpFile.delete()
            if (backupFile.exists()) {
                try {
                    java.nio.file.Files.move(
                        backupFile.toPath(),
                        currentFile.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE
                    )
                    val fsyncRestore = fsyncDirectory(storeRoot)
                    if (fsyncRestore is PackageOutcome.Failure) {
                        return PackageOutcome.Failure(PackageFailure.Recovery(PackageFailure.RecoveryDetail.COMMIT_STATE_AMBIGUOUS))
                    }
                } catch (restoreException: Exception) {
                    return PackageOutcome.Failure(PackageFailure.Recovery(PackageFailure.RecoveryDetail.COMMIT_STATE_AMBIGUOUS))
                }
            }
            return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.COMMIT_FAILED))
        }

        val fsyncStoreResult = fsyncDirectory(storeRoot, DurableBoundary.STORE_DIR_FSYNC)
        val fsyncStagingResult = fsyncDirectory(stagingDir, DurableBoundary.STAGING_DIR_FSYNC_AFTER_INDEX)
        if (fsyncStoreResult is PackageOutcome.Failure || fsyncStagingResult is PackageOutcome.Failure) {
            // Final sync failed: rollback to backup index and sync
            try {
                if (currentFile.exists()) {
                    val deleted = currentFile.delete()
                    if (!deleted && currentFile.exists()) {
                        return PackageOutcome.Failure(PackageFailure.Recovery(PackageFailure.RecoveryDetail.COMMIT_STATE_AMBIGUOUS))
                    }
                }
                if (backupFile.exists()) {
                    java.nio.file.Files.move(
                        backupFile.toPath(),
                        currentFile.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE
                    )
                    val r1 = fsyncDirectory(storeRoot)
                    val r2 = fsyncDirectory(stagingDir)
                    if (r1 is PackageOutcome.Failure || r2 is PackageOutcome.Failure) {
                        return PackageOutcome.Failure(PackageFailure.Recovery(PackageFailure.RecoveryDetail.COMMIT_STATE_AMBIGUOUS))
                    }
                } else {
                    // No backup exists (first install), delete succeeded, sync storeRoot and stagingDir
                    val r1 = fsyncDirectory(storeRoot)
                    val r2 = fsyncDirectory(stagingDir)
                    if (r1 is PackageOutcome.Failure || r2 is PackageOutcome.Failure) {
                        return PackageOutcome.Failure(PackageFailure.Recovery(PackageFailure.RecoveryDetail.COMMIT_STATE_AMBIGUOUS))
                    }
                }
                return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.COMMIT_FAILED))
            } catch (rollbackEx: Exception) {
                return PackageOutcome.Failure(PackageFailure.Recovery(PackageFailure.RecoveryDetail.COMMIT_STATE_AMBIGUOUS))
            }
        }
        return PackageOutcome.Success(Unit)
    }
    /**
     * 3.3 & 5: App-private digest-addressed immutable content commit with strict validations, atomic move and fsyncs.
     */
    public fun commitContent(stagingFile: File, expectedDigest: ArtifactDigest): PackageOutcome<File> = synchronized(operationLock) {
        val dirResult = ensureDirectories()
        if (dirResult is PackageOutcome.Failure) return dirResult

        // Check stagingFile is a canonical child of stagingDir
        val canonicalStagingDir = stagingDir.canonicalFile
        val canonicalStagingFile = stagingFile.canonicalFile
        if (canonicalStagingFile.parentFile != canonicalStagingDir) {
            return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.WRITE_FAILED))
        }

        val computedDigest = try {
            computeFileSha256(canonicalStagingFile)
        } catch (e: Exception) {
            return PackageOutcome.Failure(PackageFailure.Integrity(PackageFailure.IntegrityDetail.HASH_COMPUTATION_FAILED))
        }

        if (computedDigest != expectedDigest.value) {
            return PackageOutcome.Failure(PackageFailure.Integrity(PackageFailure.IntegrityDetail.DIGEST_MISMATCH))
        }

        val destFile = File(contentDir, expectedDigest.value)
        if (destFile.exists()) {
            val existingDigest = try {
                computeFileSha256(destFile)
            } catch (e: Exception) {
                return PackageOutcome.Failure(PackageFailure.Integrity(PackageFailure.IntegrityDetail.HASH_COMPUTATION_FAILED))
            }
            if (existingDigest != expectedDigest.value) {
                return PackageOutcome.Failure(PackageFailure.Format(PackageFailure.FormatDetail.COLLISION))
            }
            // Mismatch check passed; staging file must be deleted on identical/commit success and report deletion failure.
            try {
                if (canonicalStagingFile.exists()) {
                    val deleted = canonicalStagingFile.delete()
                    if (!deleted && canonicalStagingFile.exists()) {
                        return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.WRITE_FAILED))
                    }
                }
            } catch (e: Exception) {
                return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.WRITE_FAILED))
            }
            return PackageOutcome.Success(destFile)
        }

        // Fsync staging file bytes before moving
        try {
            FileOutputStream(canonicalStagingFile, true).use { fos ->
                faultInjector.inject(DurableBoundary.STAGED_CONTENT_FSYNC)
                fos.fd.sync()
            }
        } catch (e: Exception) {
            return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.WRITE_FAILED))
        }

        // Atomic move
        try {
            faultInjector.inject(DurableBoundary.CONTENT_ATOMIC_MOVE)
            java.nio.file.Files.move(
                canonicalStagingFile.toPath(),
                destFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: Exception) {
            return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.COMMIT_FAILED))
        }

        if (!destFile.setWritable(false)) {
            return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.WRITE_FAILED))
        }

        val fsyncContentResult = fsyncDirectory(contentDir, DurableBoundary.CONTENT_DIR_FSYNC)
        if (fsyncContentResult is PackageOutcome.Failure) return fsyncContentResult
        val fsyncStagingResult = fsyncDirectory(stagingDir, DurableBoundary.STAGING_DIR_FSYNC_AFTER_CONTENT)
        if (fsyncStagingResult is PackageOutcome.Failure) return fsyncStagingResult

        return PackageOutcome.Success(destFile)
    }

    /**
     * 3.7, 3.8 & 1 (1): Revalidate and materialize active installed providers, returning materialization outcome.
     */
    internal fun loadAndMaterialize(
        bridge: LuaKernelBridge,
        bounds: PackageValidationBounds = PackageValidationBounds.DEFAULT
    ): PackageOutcome<MaterializationResult> = synchronized(operationLock) {
        val index = when (val indexResult = loadIndex()) {
            is PackageOutcome.Success -> indexResult.value.index
            is PackageOutcome.Failure -> return indexResult
        }

        val bindings = LinkedHashMap<ChannelImplementationId, InstalledProviderBinding>()
        val failures = LinkedHashMap<ChannelImplementationId, PackageFailure>()
        val records = LinkedHashMap<ChannelImplementationId, StoredPackageRevision>()
        val resolverPublications = LinkedHashMap<ChannelImplementationId, io.talkcan.lua.resolver.PackageResolverPublication>()

        for ((repoId, record) in index.providers) {
            val implId = InstalledProviderId.derive(repoId)
            val active = record.active
            // Record is initialized from the index-cached revision and used for
            // diagnostics if revalidation fails. On the success path it is replaced
            // below with the reparsed, rehashed revision derived from exact stored bytes.
            records[implId] = active
            val file = File(contentDir, active.digest.value)

            try {
                if (!file.exists()) {
                    throw PackageException(PackageFailure.Integrity(PackageFailure.IntegrityDetail.CORRUPTED_ARCHIVE, implId))
                }

                val computedHash = try {
                    computeFileSha256(file)
                } catch (e: Exception) {
                    throw PackageException(PackageFailure.Integrity(PackageFailure.IntegrityDetail.HASH_COMPUTATION_FAILED, implId))
                }

                if (computedHash != active.digest.value) {
                    throw PackageException(PackageFailure.Integrity(PackageFailure.IntegrityDetail.DIGEST_MISMATCH, implId))
                }

                val outcome = PackageValidator.validateStagedZip(file, active.sourceRecord, bounds, active.digest)
                val validatedRevision = when (outcome) {
                    is PackageOutcome.Success -> outcome.value
                    is PackageOutcome.Failure -> {
                        val overriddenError = outcome.error.withImplId(implId)
                        throw PackageException(overriddenError)
                    }
                }

                // The index-cached manifest must agree exactly with the
                // declarations reparsed from the exact stored archive bytes.
                if (validatedRevision.manifest != active.manifest) {
                    throw PackageException(PackageFailure.Mutation(PackageFailure.MutationDetail.SERIALIZATION_VIOLATION, implId))
                }

                // Check source record agreement
                val valSrc = validatedRevision.sourceRecord
                val indexSrc = active.sourceRecord
                if (valSrc.repositoryId != indexSrc.repositoryId ||
                    valSrc.coordinates.owner != indexSrc.coordinates.owner ||
                    valSrc.coordinates.repository != indexSrc.coordinates.repository ||
                    valSrc.release.releaseId != indexSrc.release.releaseId ||
                    valSrc.release.tag != indexSrc.release.tag ||
                    valSrc.release.isPrerelease != indexSrc.release.isPrerelease ||
                    valSrc.asset.assetId != indexSrc.asset.assetId ||
                    valSrc.asset.name != indexSrc.asset.name) {
                    throw PackageException(PackageFailure.Mutation(PackageFailure.MutationDetail.SERIALIZATION_VIOLATION, implId))
                }

                // Identity coherence: the index key must agree with every identity in
                // the validated revision.  A corrupted index whose key disagrees with
                // the stored manifest or source identity must be rejected before
                // materialization, not at the registry (which would reject the entire
                // batch).
                if (repoId != validatedRevision.manifest.repositoryId) {
                    throw PackageException(PackageFailure.Identity(PackageFailure.IdentityDetail.REPOSITORY_ID_MISMATCH, implId))
                }
                if (repoId != validatedRevision.sourceRecord.repositoryId) {
                    throw PackageException(PackageFailure.Identity(PackageFailure.IdentityDetail.REPOSITORY_ID_MISMATCH, implId))
                }


                val entry = LuaPackageMaterializer.materializeEntry(
                    validatedRevision,
                    bridge,
                    ActorPolicy.startingEvidence(),
                    logSink = logSink,
                    runtimeResourcesFactory = runtimeResourcesFactory,
                    preparerRegistry = preparerRegistry,
                    keyboardOutputAdapterFactory = keyboardOutputAdapterFactory,
                    dynamicChoiceResolver = dynamicChoiceResolver,
                    secretStore = secretStore,
                    httpTransport = httpTransport,
                    workStore = workStore,
                    workCoordinator = workCoordinator,
                    profileRecordProvider = profileRecordProvider,
                )
                val binding = entry.binding
                // Binding coherence: every descriptor value (implementation ID,
                // configuration-provider ID, snapshot key, repository-derived ID,
                // fingerprint, fields, defaults, capability eligibility) derives from
                // one validated revision and agrees with the snapshot key.
                if (binding.repositoryId != repoId) {
                    throw PackageException(PackageFailure.Mutation(PackageFailure.MutationDetail.SERIALIZATION_VIOLATION, implId))
                }
                if (binding.expectedDigest != validatedRevision.digest) {
                    throw PackageException(PackageFailure.Integrity(PackageFailure.IntegrityDetail.DIGEST_MISMATCH, implId))
                }
                if (binding.provider.descriptor.implementationId != implId) {
                    throw PackageException(PackageFailure.Mutation(PackageFailure.MutationDetail.SERIALIZATION_VIOLATION, implId))
                }

                bindings[implId] = binding
                entry.resolverPublication?.let { resolverPublications[implId] = it }
                // Replace the index-cached record with the one reparsed and rehashed
                // from exact stored bytes, so exposed declarations derive from verified
                // bytes rather than the trusted index-cached model.
                records[implId] = StoredPackageRevision(
                    digest = validatedRevision.digest,
                    manifest = validatedRevision.manifest,
                    sourceRecord = validatedRevision.sourceRecord
                )
            } catch (e: PackageException) {
                failures[implId] = e.failure
            } catch (e: Exception) {
                failures[implId] = PackageFailure.Integrity(PackageFailure.IntegrityDetail.CORRUPTED_ARCHIVE, implId)
            }
        }

        return PackageOutcome.Success(MaterializationResult(bindings, failures, records, resolverPublications))
    }
    /**
     * Package-internal API: produce a collision-free staging handle for a host-generated operation id.
     */
    internal fun stagingHandle(operationId: String): File {
        return File(stagingDir, "$operationId.tmp")
    }

    /**
     * Package-internal API: discard a single staging file. Surfaces failure.
     */
    internal fun discardStaging(stagingFile: File): PackageOutcome<Unit> = synchronized(operationLock) {
        try {
            val canonicalStagingDir = stagingDir.canonicalFile
            val canonicalStagingFile = stagingFile.canonicalFile
            if (canonicalStagingFile.parentFile != canonicalStagingDir) {
                return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.WRITE_FAILED))
            }
            if (canonicalStagingFile.exists()) {
                val deleted = canonicalStagingFile.delete()
                if (!deleted && canonicalStagingFile.exists()) {
                    return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.WRITE_FAILED))
                }
            }
        } catch (e: Exception) {
            return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.WRITE_FAILED))
        }
        return PackageOutcome.Success(Unit)
    }

    /**
     * Package-internal API: revalidate an exact retained revision (rollback) from committed content.
     * Confirms stored manifest/source/digest agreement with the committed bytes.
     */
    internal fun revalidateStoredRevision(
        revision: StoredPackageRevision,
        bounds: PackageValidationBounds = PackageValidationBounds.DEFAULT
    ): PackageOutcome<ValidatedPackageRevision> = synchronized(operationLock) {
        val implId = InstalledProviderId.derive(revision.sourceRecord.repositoryId)
        val file = File(contentDir, revision.digest.value)
        try {
            if (!file.exists()) {
                return PackageOutcome.Failure(PackageFailure.Integrity(PackageFailure.IntegrityDetail.CORRUPTED_ARCHIVE, implId))
            }
            val computedHash = try {
                computeFileSha256(file)
            } catch (e: Exception) {
                return PackageOutcome.Failure(PackageFailure.Integrity(PackageFailure.IntegrityDetail.HASH_COMPUTATION_FAILED, implId))
            }
            if (computedHash != revision.digest.value) {
                return PackageOutcome.Failure(PackageFailure.Integrity(PackageFailure.IntegrityDetail.DIGEST_MISMATCH, implId))
            }
            val outcome = PackageValidator.validateStagedZip(file, revision.sourceRecord, bounds, revision.digest)
            val validatedRevision = when (outcome) {
                is PackageOutcome.Success -> outcome.value
                is PackageOutcome.Failure -> return PackageOutcome.Failure(outcome.error.withImplId(implId))
            }
            // Exact archive bytes are authoritative: the index-cached
            // declarations must agree with the revalidated manifest in full.
            if (validatedRevision.manifest != revision.manifest) {
                return PackageOutcome.Failure(PackageFailure.Mutation(PackageFailure.MutationDetail.SERIALIZATION_VIOLATION, implId))
            }
            val valSrc = validatedRevision.sourceRecord
            val revSrc = revision.sourceRecord
            if (valSrc.repositoryId != revSrc.repositoryId ||
                valSrc.coordinates.owner != revSrc.coordinates.owner ||
                valSrc.coordinates.repository != revSrc.coordinates.repository ||
                valSrc.release.releaseId != revSrc.release.releaseId ||
                valSrc.release.tag != revSrc.release.tag ||
                valSrc.release.isPrerelease != revSrc.release.isPrerelease ||
                valSrc.asset.assetId != revSrc.asset.assetId ||
                valSrc.asset.name != revSrc.asset.name) {
                return PackageOutcome.Failure(PackageFailure.Mutation(PackageFailure.MutationDetail.SERIALIZATION_VIOLATION, implId))
            }
            // Identity coherence: the stored manifest identity must agree with
            // the stored source-record identity.
            if (revision.manifest.repositoryId != revision.sourceRecord.repositoryId) {
                return PackageOutcome.Failure(PackageFailure.Identity(PackageFailure.IdentityDetail.REPOSITORY_ID_MISMATCH, implId))
            }
            if (validatedRevision.digest != revision.digest) {
                return PackageOutcome.Failure(PackageFailure.Integrity(PackageFailure.IntegrityDetail.DIGEST_MISMATCH, implId))
            }
            return PackageOutcome.Success(validatedRevision)
        } catch (e: Exception) {
            return PackageOutcome.Failure(PackageFailure.Integrity(PackageFailure.IntegrityDetail.CORRUPTED_ARCHIVE, implId))
        }
    }


    /**
     * 3.9 & 7: Bounded cleanup of incomplete staging and content unreferenced by active or rollback records.
     */
    public fun cleanup(index: StoredInstalledIndex): PackageOutcome<CleanupResult> = synchronized(operationLock) {
        val dirResult = ensureDirectories()
        if (dirResult is PackageOutcome.Failure) return dirResult

        val referencedDigests = HashSet<String>()
        for (record in index.providers.values) {
            referencedDigests.add(record.active.digest.value)
            record.rollback?.let { referencedDigests.add(it.digest.value) }
        }

        var inspected = 0
        var deleted = 0

        // Clean staging directory using lazy DirectoryStream
        try {
            java.nio.file.Files.newDirectoryStream(stagingDir.toPath()).use { stream ->
                for (entryPath in stream) {
                    if (inspected >= 1000) break
                    inspected++
                    val f = entryPath.toFile()
                    val name = f.name
                    if (name == "index.tmp.json") {
                        // 7: Preserves current index temp, counted as inspected if encountered
                        continue
                    }
                    if (!java.nio.file.Files.isRegularFile(entryPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        return PackageOutcome.Failure(PackageFailure.Recovery(PackageFailure.RecoveryDetail.ORPHAN_CLEANUP_FAILED))
                    }
                    val deletedOk = f.delete()
                    if (!deletedOk && f.exists()) {
                        return PackageOutcome.Failure(PackageFailure.Recovery(PackageFailure.RecoveryDetail.ORPHAN_CLEANUP_FAILED))
                    }
                    deleted++
                }
            }
        } catch (e: Exception) {
            return PackageOutcome.Failure(PackageFailure.Recovery(PackageFailure.RecoveryDetail.ORPHAN_CLEANUP_FAILED))
        }

        // Clean content directory using lazy DirectoryStream
        if (inspected < 1000) {
            try {
                java.nio.file.Files.newDirectoryStream(contentDir.toPath()).use { stream ->
                    for (entryPath in stream) {
                        if (inspected >= 1000) break
                        inspected++
                        val f = entryPath.toFile()
                        val name = f.name
                        if (!java.nio.file.Files.isRegularFile(entryPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                            return PackageOutcome.Failure(PackageFailure.Recovery(PackageFailure.RecoveryDetail.ORPHAN_CLEANUP_FAILED))
                        }
                        if (!referencedDigests.contains(name)) {
                            val deletedOk = f.delete()
                            if (!deletedOk && f.exists()) {
                                return PackageOutcome.Failure(PackageFailure.Recovery(PackageFailure.RecoveryDetail.ORPHAN_CLEANUP_FAILED))
                            }
                            deleted++
                        }
                    }
                }
            } catch (e: Exception) {
                return PackageOutcome.Failure(PackageFailure.Recovery(PackageFailure.RecoveryDetail.ORPHAN_CLEANUP_FAILED))
            }
        }

        return PackageOutcome.Success(CleanupResult(inspected, deleted))
    }

    /**
     * Bounded cleanup of staging directory only. Used during close when the global index is corrupt;
     * never deletes committed content. Preserves/counts index.tmp.json.
     */
    internal fun cleanupStagingOnly(): PackageOutcome<CleanupResult> = synchronized(operationLock) {
        val dirResult = ensureDirectories()
        if (dirResult is PackageOutcome.Failure) return dirResult

        var inspected = 0
        var deleted = 0

        try {
            java.nio.file.Files.newDirectoryStream(stagingDir.toPath()).use { stream ->
                for (entryPath in stream) {
                    if (inspected >= 1000) break
                    inspected++
                    val f = entryPath.toFile()
                    val name = f.name
                    if (name == "index.tmp.json") {
                        continue
                    }
                    if (!java.nio.file.Files.isRegularFile(entryPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        return PackageOutcome.Failure(PackageFailure.Recovery(PackageFailure.RecoveryDetail.ORPHAN_CLEANUP_FAILED))
                    }
                    val deletedOk = f.delete()
                    if (!deletedOk && f.exists()) {
                        return PackageOutcome.Failure(PackageFailure.Recovery(PackageFailure.RecoveryDetail.ORPHAN_CLEANUP_FAILED))
                    }
                    deleted++
                }
            }
        } catch (e: Exception) {
            return PackageOutcome.Failure(PackageFailure.Recovery(PackageFailure.RecoveryDetail.ORPHAN_CLEANUP_FAILED))
        }

        return PackageOutcome.Success(CleanupResult(inspected, deleted))
    }

    private fun readBoundedUtf8(file: File, maxBytes: Int): String {
        val fileLength = file.length()
        if (fileLength > maxBytes) {
            throw IllegalArgumentException("File size $fileLength exceeds limit of $maxBytes bytes")
        }
        val size = fileLength.toInt()
        val bytes = ByteArray(size)
        file.inputStream().use { fis ->
            var totalRead = 0
            while (totalRead < size) {
                val read = fis.read(bytes, totalRead, size - totalRead)
                if (read == -1) break
                totalRead += read
            }
            if (totalRead != size) {
                throw IOException("Failed to read complete file")
            }
            val extra = fis.read()
            if (extra != -1) {
                throw IllegalArgumentException("File size changed during read or exceeds expectation")
            }
        }
        val decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val charBuffer = decoder.decode(java.nio.ByteBuffer.wrap(bytes))
        return charBuffer.toString()
    }

    private fun fsyncDirectory(dir: File, boundary: DurableBoundary? = null): PackageOutcome<Unit> {
        try {
            if (boundary != null) {
                faultInjector.inject(boundary)
            }
            java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { ch ->
                ch.force(true)
            }
        } catch (e: Exception) {
            return PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.WRITE_FAILED))
        }
        return PackageOutcome.Success(Unit)
    }

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        file.inputStream().use { fis ->
            while (true) {
                val read = fis.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun PackageFailure.withImplId(implId: ChannelImplementationId): PackageFailure {
        return when (this) {
            is PackageFailure.Format -> this.copy(implementationId = implId)
            is PackageFailure.Identity -> this.copy(implementationId = implId)
            is PackageFailure.Compatibility -> this.copy(implementationId = implId)
            is PackageFailure.Integrity -> this.copy(implementationId = implId)
            is PackageFailure.Storage -> this.copy(implementationId = implId)
            is PackageFailure.Recovery -> this.copy(implementationId = implId)
            is PackageFailure.Mutation -> this.copy(implementationId = implId)
            is PackageFailure.Rollback -> this.copy(implementationId = implId)
            is PackageFailure.Loading -> this.copy(implementationId = implId)
            is PackageFailure.Shutdown -> this.copy(implementationId = implId)
            is PackageFailure.Capability -> this.copy(implementationId = implId)
        }
    }

    private class PackageException(val failure: PackageFailure) : Exception()
}
