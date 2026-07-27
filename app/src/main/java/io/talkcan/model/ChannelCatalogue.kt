package io.talkcan.model
import io.talkcan.voice.VoiceProfileId

/** A stable provider-owned implementation reference, independent of a channel instance ID. */
@JvmInline
value class ChannelImplementationId(val value: String) {
    init {
        require(IMPLEMENTATION_ID.matches(value)) {
            "Implementation ID must be a non-blank namespaced identifier: $value"
        }
    }

    override fun toString(): String = value

    companion object {
        private val IMPLEMENTATION_ID = Regex("[a-z][a-z0-9._-]*:[a-z0-9][a-z0-9._-]*")
    }
}

/**
 * Immutable, provider-owned JSON object. The catalogue never decodes its fields.
 *
 * The encoded object is retained rather than a mutable [org.json.JSONObject], so callers
 * cannot mutate persisted configuration through a definition they already hold.
 */
class OpaqueJsonObject private constructor(private val encoded: String) {
    fun toJsonString(): String = encoded

    fun toJsonObject(): org.json.JSONObject = org.json.JSONObject(encoded)

    override fun equals(other: Any?): Boolean =
        other is OpaqueJsonObject && encoded == other.encoded

    override fun hashCode(): Int = encoded.hashCode()

    override fun toString(): String = encoded

    companion object {
        fun parse(encoded: String): Result<OpaqueJsonObject> = runCatching {
            val value = org.json.JSONTokener(encoded).nextValue()
            require(value is org.json.JSONObject) { "Configuration payload must be a JSON object" }
            OpaqueJsonObject(value.toString())
        }

        fun fromJsonObject(value: org.json.JSONObject): OpaqueJsonObject =
            OpaqueJsonObject(value.toString())
    }
}

/**
 * Host-owned channel preferences, independent of the opaque provider configuration payload.
 *
 * The catalogue host — not a channel provider — owns these fields. They never enter provider
 * configuration migration or validation, and changing them never replaces a channel's runtime
 * generation.
 */
data class ChannelHostPreferences(
    val synthesisVoiceProfileId: VoiceProfileId? = null,
)

data class ChannelDefinition(
    val id: String,
    val name: String,
    val implementationId: ChannelImplementationId,
    val enabled: Boolean,
    val configSchemaVersion: Int,
    val configPayload: OpaqueJsonObject,
    val hostPreferences: ChannelHostPreferences = ChannelHostPreferences(),
)

data class ChannelCatalogueSnapshot(
    val definitions: List<ChannelDefinition>,
    val activeChannelId: String?,
)

sealed interface ChannelCatalogueValidationResult {
    data object Valid : ChannelCatalogueValidationResult
    data class Invalid(val error: ChannelCatalogueError) : ChannelCatalogueValidationResult
}

sealed interface ChannelCatalogueError {
    val message: String

    data object EmptyDefinitions : ChannelCatalogueError {
        override val message = "Catalogue definitions must not be empty"
    }

    data class BlankChannelId(val index: Int) : ChannelCatalogueError {
        override val message = "Channel ID at index $index must not be blank"
    }

    data class DuplicateChannelId(val id: String) : ChannelCatalogueError {
        override val message = "Duplicate channel ID: $id"
    }

    data class BlankChannelName(val id: String) : ChannelCatalogueError {
        override val message = "Channel $id must have a non-blank name"
    }

    data class InvalidConfigSchemaVersion(val id: String, val version: Int) : ChannelCatalogueError {
        override val message = "Channel $id has invalid configuration schema version $version"
    }

    data class UnknownActiveChannelId(val id: String) : ChannelCatalogueError {
        override val message = "Active channel ID $id does not exist in the catalogue"
    }

    data class UnknownChannelId(val id: String) : ChannelCatalogueError {
        override val message = "Channel ID $id does not exist in catalogue"
    }

    data class ChangedChannelId(val expected: String, val actual: String) : ChannelCatalogueError {
        override val message = "Cannot change channel ID from $expected to $actual during update"
    }

    data class InvalidMoveIndex(val index: Int, val size: Int) : ChannelCatalogueError {
        override val message = "Target index $index is outside catalogue size $size"
    }

    data object CannotRemoveFinalChannel : ChannelCatalogueError {
        override val message = "Cannot remove the final channel"
    }
}

sealed interface ChannelCatalogueMutationResult {
    data class Success(val snapshot: ChannelCatalogueSnapshot) : ChannelCatalogueMutationResult
    data class Failure(val error: ChannelCatalogueError) : ChannelCatalogueMutationResult
}

fun ChannelCatalogueSnapshot.selectChannel(id: String): ChannelCatalogueMutationResult =
    if (definitions.any { it.id == id }) {
        ChannelCatalogueMutationResult.Success(copy(activeChannelId = id))
    } else {
        ChannelCatalogueMutationResult.Failure(ChannelCatalogueError.UnknownChannelId(id))
    }

fun ChannelCatalogueSnapshot.addChannel(definition: ChannelDefinition): ChannelCatalogueMutationResult =
    when {
        definitions.any { it.id == definition.id } ->
            ChannelCatalogueMutationResult.Failure(ChannelCatalogueError.DuplicateChannelId(definition.id))
        else -> validatedMutation(copy(definitions = definitions + definition))
    }

fun ChannelCatalogueSnapshot.updateChannel(
    id: String,
    transform: (ChannelDefinition) -> ChannelDefinition,
): ChannelCatalogueMutationResult {
    val index = definitions.indexOfFirst { it.id == id }
    if (index == -1) return ChannelCatalogueMutationResult.Failure(ChannelCatalogueError.UnknownChannelId(id))
    val replacement = transform(definitions[index])
    if (replacement.id != id) {
        return ChannelCatalogueMutationResult.Failure(ChannelCatalogueError.ChangedChannelId(id, replacement.id))
    }
    return validatedMutation(copy(definitions = definitions.toMutableList().also { it[index] = replacement }))
}

fun ChannelCatalogueSnapshot.moveChannel(id: String, toIndex: Int): ChannelCatalogueMutationResult {
    val fromIndex = definitions.indexOfFirst { it.id == id }
    if (fromIndex == -1) return ChannelCatalogueMutationResult.Failure(ChannelCatalogueError.UnknownChannelId(id))
    if (toIndex !in definitions.indices) {
        return ChannelCatalogueMutationResult.Failure(ChannelCatalogueError.InvalidMoveIndex(toIndex, definitions.size))
    }
    if (fromIndex == toIndex) return ChannelCatalogueMutationResult.Success(this)
    val reordered = definitions.toMutableList()
    reordered.add(toIndex, reordered.removeAt(fromIndex))
    return ChannelCatalogueMutationResult.Success(copy(definitions = reordered))
}

fun ChannelCatalogueSnapshot.removeChannel(id: String): ChannelCatalogueMutationResult {
    if (definitions.size == 1) return ChannelCatalogueMutationResult.Failure(ChannelCatalogueError.CannotRemoveFinalChannel)
    val index = definitions.indexOfFirst { it.id == id }
    if (index == -1) return ChannelCatalogueMutationResult.Failure(ChannelCatalogueError.UnknownChannelId(id))
    val remaining = definitions.toMutableList().also { it.removeAt(index) }
    val activeId = if (activeChannelId == id) {
        remaining[index.coerceAtMost(remaining.lastIndex)].id
    } else {
        activeChannelId
    }
    return ChannelCatalogueMutationResult.Success(copy(definitions = remaining, activeChannelId = activeId))
}

private fun ChannelCatalogueSnapshot.validatedMutation(snapshot: ChannelCatalogueSnapshot): ChannelCatalogueMutationResult =
    when (val validation = ChannelCatalogueValidator.validate(snapshot)) {
        ChannelCatalogueValidationResult.Valid -> ChannelCatalogueMutationResult.Success(snapshot)
        is ChannelCatalogueValidationResult.Invalid -> ChannelCatalogueMutationResult.Failure(validation.error)
    }

object ChannelCatalogueValidator {
    /** Validates only document-envelope invariants; providers own payload semantics. */
    fun validate(snapshot: ChannelCatalogueSnapshot): ChannelCatalogueValidationResult {
        val ids = mutableSetOf<String>()
        snapshot.definitions.forEachIndexed { index, definition ->
            if (definition.id.isBlank()) {
                return ChannelCatalogueValidationResult.Invalid(ChannelCatalogueError.BlankChannelId(index))
            }
            if (!ids.add(definition.id)) {
                return ChannelCatalogueValidationResult.Invalid(ChannelCatalogueError.DuplicateChannelId(definition.id))
            }
            if (definition.name.isBlank()) {
                return ChannelCatalogueValidationResult.Invalid(ChannelCatalogueError.BlankChannelName(definition.id))
            }
            if (definition.configSchemaVersion < 1) {
                return ChannelCatalogueValidationResult.Invalid(
                    ChannelCatalogueError.InvalidConfigSchemaVersion(definition.id, definition.configSchemaVersion),
                )
            }
        }
        val activeId = snapshot.activeChannelId
        return if (activeId == null || activeId in ids) {
            ChannelCatalogueValidationResult.Valid
        } else {
            ChannelCatalogueValidationResult.Invalid(
                ChannelCatalogueError.UnknownActiveChannelId(activeId),
            )
        }
    }
}
