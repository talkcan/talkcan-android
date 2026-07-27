package io.talkcan.profile

import io.talkcan.dependency.GitHubRepositoryIdentity
import java.util.Collections

// Reuse the same canonical field-ID grammar as the configuration contract.
private val PROFILE_FIELD_ID_REGEX = Regex("^[a-z][a-z0-9_]*$")

/**
 * Immutable snapshot of all profiles and published schemas for coordination.
 */
public data class ProfileRepositorySnapshot(
    val profiles: List<ProfileRecord>,
    val publishedTypes: Map<ProfileTypeIdentity, ProfileSchema>,
)

/**
 * 3.1: Finite host-configured profile bounds.
 *
 * Every bound is positive and enforced at construction and at each mutation
 * boundary. Bounds protect per-repository/type and process-wide capacity.
 */
public object ProfileLimits {
    public const val SCHEMA_VERSION: Int = 1
    public const val STORE_VERSION: Int = 1

    public const val MAX_TYPES_PER_REPOSITORY: Int = 32
    public const val MAX_PROFILES_PER_TYPE: Int = 64
    public const val MAX_PROFILES_PER_REPOSITORY: Int = 256
    public const val MAX_FIELDS_PER_SCHEMA: Int = 32

    public const val MAX_TYPE_ID_BYTES: Int = 128
    public const val MAX_PROFILE_ID_BYTES: Int = 64
    public const val MAX_FIELD_ID_BYTES: Int = 64
    public const val MAX_DISPLAY_NAME_BYTES: Int = 256
    public const val MAX_LABEL_BYTES: Int = 128
    public const val MAX_HELP_BYTES: Int = 512
    public const val MAX_STRING_VALUE_BYTES: Int = 4096
    public const val MAX_CHOICE_VALUE_BYTES: Int = 256
    public const val MAX_CHOICE_LABEL_BYTES: Int = 128
    public const val MAX_SECRET_BYTES: Int = 8192
    public const val MAX_DOCUMENT_BYTES: Int = 1_048_576
}

/**
 * 3.1: Repository-scoped profile-type identity.
 *
 * Identity is exactly the tuple `(repositoryDatabaseId, localTypeId)`.
 * Package name, owner coordinates, release tag, display label, digest, and
 * installation order do NOT establish identity. A repository rename that
 * retains its database ID preserves type identity.
 */
public data class ProfileTypeIdentity(
    val repositoryId: GitHubRepositoryIdentity,
    val localTypeId: String,
) {
    init {
        require(localTypeId.matches(PROFILE_FIELD_ID_REGEX)) {
            "Profile type ID does not match pattern ^[a-z][a-z0-9_]*$: $localTypeId"
        }
        require(localTypeId.toByteArray(Charsets.UTF_8).size <= ProfileLimits.MAX_TYPE_ID_BYTES) {
            "Profile type ID must not exceed ${ProfileLimits.MAX_TYPE_ID_BYTES} bytes: $localTypeId"
        }
    }
}

/**
 * 3.1: Stable host-generated profile ID. Nonblank, bounded, never reassigned.
 */
@JvmInline
public value class ProfileId(val value: String) {
    init {
        require(value.isNotBlank()) { "Profile ID must not be blank" }
        require(value.toByteArray(Charsets.UTF_8).size <= ProfileLimits.MAX_PROFILE_ID_BYTES) {
            "Profile ID must not exceed ${ProfileLimits.MAX_PROFILE_ID_BYTES} bytes"
        }
    }
}

/** 3.1/3.2: Profile field types. `SECRET` carries no scalar payload. */
public enum class ProfileFieldType {
    STRING,
    BOOLEAN,
    INTEGER,
    SECRET,
}

/** 3.2: Profile UI controls. `SECRET` is the only protected control. */
public enum class ProfileUiControl(public val value: String) {
    TEXT("text"),
    MULTILINE("multiline"),
    TOGGLE("toggle"),
    NUMBER("number"),
    CHOICE("choice"),
    SECRET("secret"),
}

/**
 * 3.1/3.2: One field declaration in a profile data schema.
 *
 * Scalar fields mirror the bounded flat configuration machinery. A
 * [SecretField] has no default, no allowed values, and no range by
 * construction; its plaintext never enters the profile document.
 */
public sealed class ProfileFieldDeclaration {
    public abstract val id: String
    public abstract val type: ProfileFieldType
    public abstract val required: Boolean

    public class StringField(
        override val id: String,
        override val required: Boolean,
        val default: String?,
        allowedValues: List<String>?,
    ) : ProfileFieldDeclaration() {
        override val type: ProfileFieldType = ProfileFieldType.STRING
        public val allowedValues: List<String>? =
            allowedValues?.let { Collections.unmodifiableList(ArrayList(it)) }

        init {
            requireFieldId(id)
            if (default != null) {
                require(default.toByteArray(Charsets.UTF_8).size <= ProfileLimits.MAX_STRING_VALUE_BYTES) {
                    "Default value size must not exceed ${ProfileLimits.MAX_STRING_VALUE_BYTES} bytes"
                }
            }
            if (this.allowedValues != null) {
                require(this.allowedValues.isNotEmpty()) { "Allowed values must not be empty if present" }
                val seen = mutableSetOf<String>()
                for (v in this.allowedValues) {
                    require(v.toByteArray(Charsets.UTF_8).size <= ProfileLimits.MAX_CHOICE_VALUE_BYTES) {
                        "Allowed value size must not exceed ${ProfileLimits.MAX_CHOICE_VALUE_BYTES} bytes"
                    }
                    require(seen.add(v)) { "Duplicate allowed value: $v" }
                }
                if (default != null) {
                    require(default in seen) { "Default value '$default' must be in allowed values" }
                }
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is StringField) return false
            return id == other.id && required == other.required &&
                default == other.default && allowedValues == other.allowedValues
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + required.hashCode()
            result = 31 * result + (default?.hashCode() ?: 0)
            result = 31 * result + (allowedValues?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "StringField(id='$id', required=$required, default=$default, allowedValues=$allowedValues)"
    }

    public class BooleanField(
        override val id: String,
        override val required: Boolean,
        val default: Boolean?,
    ) : ProfileFieldDeclaration() {
        override val type: ProfileFieldType = ProfileFieldType.BOOLEAN

        init {
            requireFieldId(id)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BooleanField) return false
            return id == other.id && required == other.required && default == other.default
        }

        override fun hashCode(): Int = 31 * (31 * id.hashCode() + required.hashCode()) + (default?.hashCode() ?: 0)

        override fun toString(): String = "BooleanField(id='$id', required=$required, default=$default)"
    }

    public class IntegerField(
        override val id: String,
        override val required: Boolean,
        val default: Long?,
        val minimum: Long?,
        val maximum: Long?,
    ) : ProfileFieldDeclaration() {
        override val type: ProfileFieldType = ProfileFieldType.INTEGER

        init {
            requireFieldId(id)
            if (minimum != null && maximum != null) {
                require(minimum <= maximum) { "minimum ($minimum) must be <= maximum ($maximum)" }
            }
            if (default != null) {
                if (minimum != null) require(default >= minimum) { "default ($default) must be >= minimum ($minimum)" }
                if (maximum != null) require(default <= maximum) { "default ($default) must be <= maximum ($maximum)" }
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IntegerField) return false
            return id == other.id && required == other.required &&
                default == other.default && minimum == other.minimum && maximum == other.maximum
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + required.hashCode()
            result = 31 * result + (default?.hashCode() ?: 0)
            result = 31 * result + (minimum?.hashCode() ?: 0)
            result = 31 * result + (maximum?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "IntegerField(id='$id', required=$required, default=$default, minimum=$minimum, maximum=$maximum)"
    }

    /**
     * A protected secret field. Has no plaintext default, no allowed values,
     * and no range. The profile document stores only a non-secret reference.
     */
    public class SecretField(
        override val id: String,
        override val required: Boolean,
    ) : ProfileFieldDeclaration() {
        override val type: ProfileFieldType = ProfileFieldType.SECRET

        init {
            requireFieldId(id)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SecretField) return false
            return id == other.id && required == other.required
        }

        override fun hashCode(): Int = 31 * id.hashCode() + required.hashCode()

        override fun toString(): String = "SecretField(id='$id', required=$required)"
    }
}

private fun requireFieldId(id: String) {
    require(id.matches(PROFILE_FIELD_ID_REGEX)) {
        "Field ID does not match pattern ^[a-z][a-z0-9_]*$: $id"
    }
    require(id.toByteArray(Charsets.UTF_8).size <= ProfileLimits.MAX_FIELD_ID_BYTES) {
        "Field ID must not exceed ${ProfileLimits.MAX_FIELD_ID_BYTES} bytes: $id"
    }
}

/** 3.2: One static UI choice for a `choice` control. */
public class ProfileUiChoice(
    val value: String,
    val label: String,
) {
    init {
        require(value.toByteArray(Charsets.UTF_8).size <= ProfileLimits.MAX_CHOICE_VALUE_BYTES) {
            "Choice value must not exceed ${ProfileLimits.MAX_CHOICE_VALUE_BYTES} bytes"
        }
        require(label.isNotBlank()) { "Choice label must not be blank" }
        require(label.toByteArray(Charsets.UTF_8).size <= ProfileLimits.MAX_CHOICE_LABEL_BYTES) {
            "Choice label must not exceed ${ProfileLimits.MAX_CHOICE_LABEL_BYTES} bytes"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProfileUiChoice) return false
        return value == other.value && label == other.label
    }

    override fun hashCode(): Int = 31 * value.hashCode() + label.hashCode()

    override fun toString(): String = "ProfileUiChoice(value='$value', label='$label')"
}

/**
 * 3.2: One UI field declaration. The control must be type-compatible with
 * its data field; `secret` fields use exactly the [ProfileUiControl.SECRET]
 * control.
 */
public class ProfileUiFieldDeclaration(
    val field: String,
    val control: ProfileUiControl,
    val label: String,
    val help: String?,
    choices: List<ProfileUiChoice>?,
) {
    public val choices: List<ProfileUiChoice>? =
        choices?.let { Collections.unmodifiableList(ArrayList(it)) }

    init {
        requireFieldId(field)
        require(label.isNotBlank()) { "UI label must not be blank" }
        require(label.toByteArray(Charsets.UTF_8).size <= ProfileLimits.MAX_LABEL_BYTES) {
            "UI label must not exceed ${ProfileLimits.MAX_LABEL_BYTES} bytes"
        }
        if (help != null) {
            require(help.toByteArray(Charsets.UTF_8).size <= ProfileLimits.MAX_HELP_BYTES) {
                "UI help must not exceed ${ProfileLimits.MAX_HELP_BYTES} bytes"
            }
        }
        if (control == ProfileUiControl.CHOICE) {
            require(this.choices != null && this.choices.isNotEmpty()) {
                "Choice control requires non-empty choices"
            }
            val seen = mutableSetOf<String>()
            for (c in this.choices!!) {
                require(seen.add(c.value)) { "Duplicate choice value: ${c.value}" }
            }
        } else {
            require(this.choices == null) {
                "Only choice control may carry choices"
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProfileUiFieldDeclaration) return false
        return field == other.field && control == other.control &&
            label == other.label && help == other.help && choices == other.choices
    }

    override fun hashCode(): Int {
        var result = field.hashCode()
        result = 31 * result + control.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + (help?.hashCode() ?: 0)
        result = 31 * result + (choices?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "ProfileUiFieldDeclaration(field='$field', control=$control, label='$label')"
}

/**
 * 3.2: A complete profile schema: bounded flat data fields plus a matching
 * UI declaration. Every data field appears exactly once in UI order with a
 * type-compatible control. Secret fields use exactly the `secret` control.
 */
public class ProfileSchema(
    dataFields: List<ProfileFieldDeclaration>,
    uiFields: List<ProfileUiFieldDeclaration>,
) {
    public val dataFields: List<ProfileFieldDeclaration> =
        Collections.unmodifiableList(ArrayList(dataFields))
    public val uiFields: List<ProfileUiFieldDeclaration> =
        Collections.unmodifiableList(ArrayList(uiFields))

    private val dataById: Map<String, ProfileFieldDeclaration>

    init {
        require(this.dataFields.size <= ProfileLimits.MAX_FIELDS_PER_SCHEMA) {
            "Profile schema fields must not exceed ${ProfileLimits.MAX_FIELDS_PER_SCHEMA}"
        }
        val ids = mutableSetOf<String>()
        for (f in this.dataFields) {
            require(ids.add(f.id)) { "Duplicate profile field ID: ${f.id}" }
        }
        require(this.uiFields.size == this.dataFields.size) {
            "UI fields must cover every data field exactly once"
        }
        dataById = this.dataFields.associateBy { it.id }
        val uiSeen = mutableSetOf<String>()
        for (ui in this.uiFields) {
            require(uiSeen.add(ui.field)) { "Duplicate UI field: ${ui.field}" }
            val data = dataById[ui.field]
                ?: throw IllegalArgumentException("UI field '${ui.field}' has no matching data field")
            require(controlCompatible(data, ui)) {
                "UI control ${ui.control.value} is incompatible with field type ${data.type}"
            }
            if (data is ProfileFieldDeclaration.StringField && ui.control == ProfileUiControl.CHOICE) {
                val allowed = data.allowedValues
                    ?: throw IllegalArgumentException("Choice control requires allowedValues on field '${ui.field}'")
                val uiValues = ui.choices!!.map { it.value }
                require(uiValues.toSet() == allowed.toSet() && uiValues.size == allowed.size) {
                    "UI choices must exactly match allowed values for field '${ui.field}'"
                }
            }
        }
    }

    public fun fieldById(id: String): ProfileFieldDeclaration? = dataById[id]

    public fun secretFieldIds(): Set<String> =
        dataFields.filterIsInstance<ProfileFieldDeclaration.SecretField>().map { it.id }.toSet()

    public fun scalarFieldIds(): Set<String> =
        dataFields.filter { it !is ProfileFieldDeclaration.SecretField }.map { it.id }.toSet()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProfileSchema) return false
        return dataFields == other.dataFields && uiFields == other.uiFields
    }

    override fun hashCode(): Int = 31 * dataFields.hashCode() + uiFields.hashCode()

    override fun toString(): String = "ProfileSchema(fields=${dataFields.size})"

    private companion object {
        fun controlCompatible(data: ProfileFieldDeclaration, ui: ProfileUiFieldDeclaration): Boolean =
            when (data.type) {
                ProfileFieldType.STRING ->
                    ui.control == ProfileUiControl.TEXT ||
                        ui.control == ProfileUiControl.MULTILINE ||
                        ui.control == ProfileUiControl.CHOICE
                ProfileFieldType.BOOLEAN -> ui.control == ProfileUiControl.TOGGLE
                ProfileFieldType.INTEGER -> ui.control == ProfileUiControl.NUMBER
                ProfileFieldType.SECRET -> ui.control == ProfileUiControl.SECRET
            }
    }
}

/** 3.1: One validated scalar payload value. Secret fields never carry a scalar. */
public sealed class ProfileScalarValue {
    public data class StringValue(val value: String) : ProfileScalarValue() {
        init {
            require(value.toByteArray(Charsets.UTF_8).size <= ProfileLimits.MAX_STRING_VALUE_BYTES) {
                "String value must not exceed ${ProfileLimits.MAX_STRING_VALUE_BYTES} bytes"
            }
        }
    }

    public data class BooleanValue(val value: Boolean) : ProfileScalarValue()
    public data class IntegerValue(val value: Long) : ProfileScalarValue()
}

/**
 * 3.1: Non-secret secret-reference state persisted in the profile document.
 * No plaintext, no keystore alias, no platform object.
 */
public sealed class SecretReferenceState {
    public data object Absent : SecretReferenceState()

    public data class Present(val referenceId: String) : SecretReferenceState() {
        init {
            require(referenceId.isNotBlank()) { "Secret reference ID must not be blank" }
            require(referenceId.toByteArray(Charsets.UTF_8).size <= ProfileLimits.MAX_PROFILE_ID_BYTES) {
                "Secret reference ID must not exceed ${ProfileLimits.MAX_PROFILE_ID_BYTES} bytes"
            }
        }
    }
}

/** 3.1: Current validation/storage availability of a profile record. */
public enum class ProfileAvailability {
    AVAILABLE,
    UNAVAILABLE_SCHEMA_INCOMPATIBLE,
    UNAVAILABLE_PACKAGE_REMOVED,
    UNAVAILABLE_STORAGE_CORRUPT,
}

/**
 * 3.1: One complete immutable profile record.
 *
 * The record carries validated scalar payload and non-secret reference state
 * only. Plaintext secrets, repository clients, and platform objects never
 * enter this type.
 */
public data class ProfileRecord(
    val profileId: ProfileId,
    val typeIdentity: ProfileTypeIdentity,
    val displayName: String,
    val schemaVersion: Int,
    val scalarPayload: Map<String, ProfileScalarValue>,
    val secretReferences: Map<String, SecretReferenceState>,
    val revision: Long,
    val availability: ProfileAvailability,
) {
    init {
        require(displayName.isNotBlank()) { "Display name must not be blank" }
        require(displayName.toByteArray(Charsets.UTF_8).size <= ProfileLimits.MAX_DISPLAY_NAME_BYTES) {
            "Display name must not exceed ${ProfileLimits.MAX_DISPLAY_NAME_BYTES} bytes"
        }
        require(schemaVersion == ProfileLimits.SCHEMA_VERSION) {
            "Schema version must be exactly ${ProfileLimits.SCHEMA_VERSION}"
        }
        require(revision >= 1L) { "Revision must be positive" }
    }
}

/** 3.1: Typed profile failures. No raw error messages or content leakage. */
public sealed interface ProfileFailure {
    public data object NotLoaded : ProfileFailure
    public data object ProfileNotFound : ProfileFailure
    public data object TypeNotPublished : ProfileFailure
    public data object DuplicateProfileId : ProfileFailure
    public data class DisplayNameInvalid(val reason: String) : ProfileFailure
    public data class ValidationFailed(val reason: String) : ProfileFailure
    public data class BoundsExceeded(val reason: String) : ProfileFailure
    public data class SchemaIncompatible(val reason: String) : ProfileFailure
    public data class SecretMutationFailed(val reason: String) : ProfileFailure
    public data class StorageFailed(val operation: String) : ProfileFailure
    public data class ForeignRepository(val repositoryId: GitHubRepositoryIdentity) : ProfileFailure
    public data class PackageRemoved(val typeIdentity: ProfileTypeIdentity) : ProfileFailure
}

/** 3.1: Typed operation result. */
public sealed interface ProfileOperationResult<out T> {
    public data class Success<T>(val value: T) : ProfileOperationResult<T>
    public data class Failure(val failure: ProfileFailure) : ProfileOperationResult<Nothing>
}

/**
 * 3.5: Secret mutation action for profile editing. Plaintext is a
 * [CharSequence] matching the protected-secret store boundary; the store
 * enforces per-secret byte bounds and UTF-8 validity (task 4.4).
 */
public sealed class SecretEditAction {
    /** Keep the existing reference unchanged. */
    public data object Retain : SecretEditAction()

    /** Replace an existing secret value with new plaintext. */
    public data class Replace(val plaintext: CharSequence) : SecretEditAction() {
        init {
            require(plaintext.isNotBlank()) { "Replacement plaintext must not be blank" }
        }

        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** Clear the secret, removing its reference. */
    public data object Clear : SecretEditAction()

    /** Set a new secret where none existed before. */
    public data class Set(val plaintext: CharSequence) : SecretEditAction() {
        init {
            require(plaintext.isNotBlank()) { "Secret plaintext must not be blank" }
        }

        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }
}

/**
 * 3.6: A completed protected-secret cleanup that could not be finalized and
 * needs a retry. The reference token is non-secret; no plaintext is carried.
 */
public data class ProfileSecretCleanupPending(
    val referenceToken: String,
)

/** 3.7: Result of revalidating profiles of one type against a new schema. */
public data class ProfileRevalidationResult(
    val revalidatedCount: Int,
    val availableCount: Int,
    val incompatibleCount: Int,
    val incompatibleProfileIds: List<ProfileId>,
)
