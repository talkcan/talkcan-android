package io.talkcan.profile

import io.talkcan.dependency.GitHubRepositoryIdentity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * 3.3: Typed strict-decode failures for the profile metadata document.
 */
public sealed interface ProfileDecodeError {
    public val message: String

    public data class UnsupportedVersion(val version: Int) : ProfileDecodeError {
        override val message: String = "Unsupported profile document version: $version"
    }

    public data class MalformedDocument(override val message: String) : ProfileDecodeError
    public data class InvalidRecord(override val message: String) : ProfileDecodeError
}

/** 3.3: Strict decode result for one repository's profile document. */
public sealed interface ProfileDecodeResult {
    public data class Success(val records: List<ProfileRecord>) : ProfileDecodeResult
    public data class Failure(val error: ProfileDecodeError) : ProfileDecodeResult
}

/** 3.3: Load result for a repository's profile document. */
public sealed interface ProfileStoreLoadResult {
    public data class Loaded(val records: List<ProfileRecord>) : ProfileStoreLoadResult
    public data class Corrupt(val error: ProfileDecodeError) : ProfileStoreLoadResult
}

/** 3.3: Save result for a repository's profile document. */
public sealed interface ProfileStoreSaveResult {
    public data object Committed : ProfileStoreSaveResult
    public data class Failed(val failure: ProfileFailure) : ProfileStoreSaveResult
}

/**
 * 3.3: Deterministic versioned codec for the persistent profile metadata
 * store.
 *
 * Encoding is byte-deterministic: fixed key order, canonical enum wire
 * values, and records sorted by profile ID. Decoding is strict: exact
 * document version, exact key sets, exact scalar types, canonical enum
 * values, duplicate JSON keys rejected, and domain bounds revalidated.
 * The document carries no plaintext secrets, keystore aliases, platform
 * objects, or repository client references.
 */
public object ProfileMetadataCodec {
    public const val CURRENT_VERSION: Int = ProfileLimits.STORE_VERSION

    private const val I1 = "  "
    private const val I2 = "    "
    private const val I3 = "      "

    /** Deterministically encode records for one repository. */
    public fun encode(repositoryId: GitHubRepositoryIdentity, records: List<ProfileRecord>): String {
        require(records.size <= ProfileLimits.MAX_PROFILES_PER_REPOSITORY) {
            "Profile count exceeds ${ProfileLimits.MAX_PROFILES_PER_REPOSITORY}: ${records.size}"
        }
        val sorted = records.sortedBy { it.profileId.value }
        val sb = StringBuilder(256 + sorted.size * 256)
        sb.append("{\n")
        sb.append(I1).append("\"version\": ").append(CURRENT_VERSION).append(",\n")
        sb.append(I1).append("\"repositoryId\": ").append(escapeJson(repositoryId.value)).append(",\n")
        if (sorted.isEmpty()) {
            sb.append(I1).append("\"profiles\": []\n")
        } else {
            sb.append(I1).append("\"profiles\": [\n")
            for ((index, record) in sorted.withIndex()) {
                encodeRecord(sb, record)
                if (index < sorted.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append(I1).append("]\n")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun encodeRecord(sb: StringBuilder, record: ProfileRecord) {
        sb.append(I2).append("{\n")
        sb.append(I3).append("\"profileId\": ").append(escapeJson(record.profileId.value)).append(",\n")
        sb.append(I3).append("\"typeLocalId\": ").append(escapeJson(record.typeIdentity.localTypeId)).append(",\n")
        sb.append(I3).append("\"displayName\": ").append(escapeJson(record.displayName)).append(",\n")
        sb.append(I3).append("\"schemaVersion\": ").append(record.schemaVersion).append(",\n")
        sb.append(I3).append("\"revision\": ").append(record.revision).append(",\n")
        sb.append(I3).append("\"availability\": ").append(escapeJson(record.availability.name.lowercase())).append(",\n")
        // Scalar payload
        sb.append(I3).append("\"scalarPayload\": {")
        val scalars = record.scalarPayload.entries.sortedBy { it.key }
        if (scalars.isNotEmpty()) {
            sb.append("\n")
            for ((i, entry) in scalars.withIndex()) {
                sb.append(I3).append(I1).append(escapeJson(entry.key)).append(": ")
                encodeScalarValue(sb, entry.value)
                if (i < scalars.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append(I3)
        }
        sb.append("},\n")
        // Secret references
        sb.append(I3).append("\"secretReferences\": {")
        val secrets = record.secretReferences.entries.sortedBy { it.key }
        if (secrets.isNotEmpty()) {
            sb.append("\n")
            for ((i, entry) in secrets.withIndex()) {
                sb.append(I3).append(I1).append(escapeJson(entry.key)).append(": ")
                encodeSecretRef(sb, entry.value)
                if (i < secrets.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append(I3)
        }
        sb.append("}\n")
        sb.append(I2).append("}")
    }

    private fun encodeScalarValue(sb: StringBuilder, value: ProfileScalarValue) {
        when (value) {
            is ProfileScalarValue.StringValue -> {
                sb.append("{\"type\": \"string\", \"value\": ").append(escapeJson(value.value)).append("}")
            }
            is ProfileScalarValue.BooleanValue -> {
                sb.append("{\"type\": \"boolean\", \"value\": ").append(value.value).append("}")
            }
            is ProfileScalarValue.IntegerValue -> {
                sb.append("{\"type\": \"integer\", \"value\": ").append(value.value).append("}")
            }
        }
    }

    private fun encodeSecretRef(sb: StringBuilder, state: SecretReferenceState) {
        when (state) {
            is SecretReferenceState.Absent -> {
                sb.append("{\"state\": \"absent\"}")
            }
            is SecretReferenceState.Present -> {
                sb.append("{\"state\": \"present\", \"referenceId\": ")
                    .append(escapeJson(state.referenceId)).append("}")
            }
        }
    }

    /** Strictly decode one repository's profile document. */
    public fun decode(json: String, expectedRepositoryId: GitHubRepositoryIdentity): ProfileDecodeResult {
        val root = try {
            StrictProfileJsonParser(json).parse()
        } catch (e: Exception) {
            return ProfileDecodeResult.Failure(
                ProfileDecodeError.MalformedDocument(e.message ?: "Invalid JSON")
            )
        }

        val version = root["version"]
        if (version !is Long || version != CURRENT_VERSION.toLong()) {
            return ProfileDecodeResult.Failure(
                ProfileDecodeError.UnsupportedVersion((version as? Long)?.toInt() ?: -1)
            )
        }

        val repoId = root["repositoryId"]
        if (repoId !is String || repoId != expectedRepositoryId.value) {
            return ProfileDecodeResult.Failure(
                ProfileDecodeError.MalformedDocument("Repository ID mismatch or missing")
            )
        }

        val profilesRaw = root["profiles"]
        if (profilesRaw !is List<*>) {
            return ProfileDecodeResult.Failure(
                ProfileDecodeError.MalformedDocument("Missing or invalid 'profiles' array")
            )
        }

        val records = ArrayList<ProfileRecord>(profilesRaw.size)
        for (entry in profilesRaw) {
            if (entry !is Map<*, *>) {
                return ProfileDecodeResult.Failure(
                    ProfileDecodeError.InvalidRecord("Profile entry is not an object")
                )
            }
            @Suppress("UNCHECKED_CAST")
            val record = try {
                decodeRecord(entry as Map<String, Any?>, expectedRepositoryId)
            } catch (e: Exception) {
                return ProfileDecodeResult.Failure(
                    ProfileDecodeError.InvalidRecord(e.message ?: "Invalid profile record")
                )
            }
            records.add(record)
        }

        return ProfileDecodeResult.Success(records)
    }

    private fun decodeRecord(obj: Map<String, Any?>, repositoryId: GitHubRepositoryIdentity): ProfileRecord {
        val profileId = requireString(obj, "profileId")
        val typeLocalId = requireString(obj, "typeLocalId")
        val displayName = requireString(obj, "displayName")
        val schemaVersion = requireLong(obj, "schemaVersion").toInt()
        val revision = requireLong(obj, "revision")
        val availabilityStr = requireString(obj, "availability")
        val availability = try {
            ProfileAvailability.valueOf(availabilityStr.uppercase())
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Unknown availability: $availabilityStr")
        }

        val scalarPayload = decodeScalarPayload(obj["scalarPayload"])
        val secretReferences = decodeSecretReferences(obj["secretReferences"])

        return ProfileRecord(
            profileId = ProfileId(profileId),
            typeIdentity = ProfileTypeIdentity(repositoryId, typeLocalId),
            displayName = displayName,
            schemaVersion = schemaVersion,
            scalarPayload = scalarPayload,
            secretReferences = secretReferences,
            revision = revision,
            availability = availability,
        )
    }

    private fun decodeScalarPayload(raw: Any?): Map<String, ProfileScalarValue> {
        if (raw == null) return emptyMap()
        if (raw !is Map<*, *>) throw IllegalArgumentException("scalarPayload must be an object")
        @Suppress("UNCHECKED_CAST")
        val map = raw as Map<String, Any?>
        val result = LinkedHashMap<String, ProfileScalarValue>(map.size)
        for ((key, value) in map) {
            if (value !is Map<*, *>) throw IllegalArgumentException("Scalar value for '$key' must be an object")
            @Suppress("UNCHECKED_CAST")
            val valueObj = value as Map<String, Any?>
            val type = valueObj["type"] as? String
                ?: throw IllegalArgumentException("Scalar value for '$key' missing type")
            val rawValue = valueObj["value"]
                ?: throw IllegalArgumentException("Scalar value for '$key' missing value")
            result[key] = when (type) {
                "string" -> ProfileScalarValue.StringValue(rawValue as? String
                    ?: throw IllegalArgumentException("String value for '$key' must be a string"))
                "boolean" -> ProfileScalarValue.BooleanValue(rawValue as? Boolean
                    ?: throw IllegalArgumentException("Boolean value for '$key' must be a boolean"))
                "integer" -> ProfileScalarValue.IntegerValue(rawValue as? Long
                    ?: throw IllegalArgumentException("Integer value for '$key' must be an integer"))
                else -> throw IllegalArgumentException("Unknown scalar type '$type' for field '$key'")
            }
        }
        return result
    }

    private fun decodeSecretReferences(raw: Any?): Map<String, SecretReferenceState> {
        if (raw == null) return emptyMap()
        if (raw !is Map<*, *>) throw IllegalArgumentException("secretReferences must be an object")
        @Suppress("UNCHECKED_CAST")
        val map = raw as Map<String, Any?>
        val result = LinkedHashMap<String, SecretReferenceState>(map.size)
        for ((key, value) in map) {
            if (value !is Map<*, *>) throw IllegalArgumentException("Secret reference for '$key' must be an object")
            @Suppress("UNCHECKED_CAST")
            val refObj = value as Map<String, Any?>
            val state = refObj["state"] as? String
                ?: throw IllegalArgumentException("Secret reference for '$key' missing state")
            result[key] = when (state) {
                "absent" -> SecretReferenceState.Absent
                "present" -> {
                    val refId = refObj["referenceId"] as? String
                        ?: throw IllegalArgumentException("Present secret reference for '$key' missing referenceId")
                    SecretReferenceState.Present(refId)
                }
                else -> throw IllegalArgumentException("Unknown secret reference state '$state' for field '$key'")
            }
        }
        return result
    }

    private fun requireString(obj: Map<String, Any?>, key: String): String =
        obj[key] as? String ?: throw IllegalArgumentException("Missing or invalid '$key'")

    private fun requireLong(obj: Map<String, Any?>, key: String): Long =
        obj[key] as? Long ?: throw IllegalArgumentException("Missing or invalid '$key'")

    private fun escapeJson(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

/**
 * 3.3: Strict JSON parser for profile documents. Rejects duplicate keys,
 * trailing data, trailing commas, and malformed escapes.
 */
internal class StrictProfileJsonParser(private val json: String) {
    private var pos = 0

    fun parse(): Map<String, Any?> {
        skipWhitespace()
        if (pos >= json.length || json[pos] != '{') {
            throw IllegalArgumentException("Expected '{' at start of JSON object")
        }
        val obj = parseObject()
        skipWhitespace()
        if (pos < json.length) {
            throw IllegalArgumentException("Extra characters after JSON root object")
        }
        return obj
    }

    private fun parseObject(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        pos++ // skip '{'
        skipWhitespace()
        if (pos < json.length && json[pos] == '}') {
            pos++
            return map
        }
        while (pos < json.length) {
            skipWhitespace()
            if (pos >= json.length || json[pos] != '"') {
                throw IllegalArgumentException("Expected string key")
            }
            val key = parseString()
            skipWhitespace()
            if (pos >= json.length || json[pos] != ':') {
                throw IllegalArgumentException("Expected ':' after key '$key'")
            }
            pos++ // skip ':'
            val value = parseValue()
            if (map.containsKey(key)) {
                throw IllegalArgumentException("Duplicate key '$key'")
            }
            map[key] = value
            skipWhitespace()
            if (pos < json.length && json[pos] == ',') {
                pos++
                skipWhitespace()
                if (pos < json.length && json[pos] == '}') {
                    throw IllegalArgumentException("Trailing comma prohibited")
                }
            } else if (pos < json.length && json[pos] == '}') {
                pos++
                return map
            } else {
                throw IllegalArgumentException("Expected ',' or '}'")
            }
        }
        throw IllegalArgumentException("Unterminated JSON object")
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        if (pos >= json.length) throw IllegalArgumentException("Unexpected EOF")
        return when (val c = json[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            else -> {
                if (c == '-' || c.isDigit()) parseNumber()
                else throw IllegalArgumentException("Unexpected character '$c'")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        val list = ArrayList<Any?>()
        pos++ // skip '['
        skipWhitespace()
        if (pos < json.length && json[pos] == ']') {
            pos++
            return list
        }
        while (pos < json.length) {
            list.add(parseValue())
            skipWhitespace()
            if (pos < json.length && json[pos] == ',') {
                pos++
                skipWhitespace()
                if (pos < json.length && json[pos] == ']') {
                    throw IllegalArgumentException("Trailing comma prohibited")
                }
            } else if (pos < json.length && json[pos] == ']') {
                pos++
                return list
            } else {
                throw IllegalArgumentException("Expected ',' or ']'")
            }
        }
        throw IllegalArgumentException("Unterminated JSON array")
    }

    private fun parseString(): String {
        pos++ // skip '"'
        val sb = StringBuilder()
        while (pos < json.length) {
            val c = json[pos]
            if (c == '"') {
                pos++
                return sb.toString()
            } else if (c == '\\') {
                if (pos + 1 >= json.length) throw IllegalArgumentException("Unterminated escape")
                val next = json[pos + 1]
                pos += 2
                when (next) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (pos + 4 > json.length) throw IllegalArgumentException("Invalid unicode escape")
                        val hex = json.substring(pos, pos + 4)
                        pos += 4
                        val code = hex.toIntOrNull(16)
                            ?: throw IllegalArgumentException("Invalid unicode escape: \\u$hex")
                        val charVal = code.toChar()
                        if (charVal.isHighSurrogate()) {
                            if (pos + 6 > json.length || json[pos] != '\\' || json[pos + 1] != 'u') {
                                throw IllegalArgumentException("Unpaired high surrogate")
                            }
                            pos += 2
                            val hexLow = json.substring(pos, pos + 4)
                            pos += 4
                            val lowCode = hexLow.toIntOrNull(16)
                                ?: throw IllegalArgumentException("Invalid unicode escape: \\u$hexLow")
                            val charLow = lowCode.toChar()
                            if (!charLow.isLowSurrogate()) throw IllegalArgumentException("Unpaired low surrogate")
                            sb.append(charVal)
                            sb.append(charLow)
                        } else if (charVal.isLowSurrogate()) {
                            throw IllegalArgumentException("Unpaired low surrogate")
                        } else {
                            sb.append(charVal)
                        }
                    }
                    else -> throw IllegalArgumentException("Invalid escape character: \\$next")
                }
            } else {
                sb.append(c)
                pos++
            }
        }
        throw IllegalArgumentException("Unterminated string")
    }

    private fun parseBoolean(): Boolean {
        if (json.startsWith("true", pos)) {
            pos += 4
            return true
        }
        if (json.startsWith("false", pos)) {
            pos += 5
            return false
        }
        throw IllegalArgumentException("Invalid boolean literal")
    }

    private fun parseNull(): Any? {
        if (json.startsWith("null", pos)) {
            pos += 4
            return null
        }
        throw IllegalArgumentException("Invalid null literal")
    }

    private fun parseNumber(): Long {
        val start = pos
        if (pos < json.length && json[pos] == '-') pos++
        if (pos >= json.length || !json[pos].isDigit()) throw IllegalArgumentException("Invalid number")
        while (pos < json.length && json[pos].isDigit()) pos++
        // Reject floats, exponents — profile documents use only integers
        if (pos < json.length && (json[pos] == '.' || json[pos] == 'e' || json[pos] == 'E')) {
            throw IllegalArgumentException("Floating-point numbers not allowed in profile documents")
        }
        val numStr = json.substring(start, pos)
        return numStr.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid integer: $numStr")
    }

    private fun skipWhitespace() {
        while (pos < json.length && json[pos].isWhitespace()) pos++
    }
}

/**
 * 3.3: Versioned app-private profile metadata store with atomic replace,
 * strict decoding, bounded records, and corruption isolation by repository.
 *
 * Each repository's profiles are stored in a separate file
 * `<storeRoot>/<repositoryId>.json`, so corruption of one repository's
 * document does not affect others. Within a repository, a corrupt document
 * fails closed: no mutation can overwrite recoverable state until the
 * corruption is resolved.
 *
 * Every mutation is transactional: the next document is fully encoded and
 * validated, written to a temporary file, fsynced, and atomically moved
 * over the current file. On failure, the previously committed document
 * remains authoritative in memory and on disk.
 */
public class ProfileMetadataStore(private val storeRoot: File) {
    private val lock = Any()

    /**
     * Load one repository's profile document. A missing file loads as an
     * empty list; a corrupt document returns [ProfileStoreLoadResult.Corrupt]
     * and leaves no in-memory state for that repository.
     */
    public fun loadRepository(repositoryId: GitHubRepositoryIdentity): ProfileStoreLoadResult =
        synchronized(lock) {
            val file = repositoryFile(repositoryId)
            if (!file.exists() || file.length() == 0L) {
                return ProfileStoreLoadResult.Loaded(emptyList())
            }
            val text = try {
                val bytes = file.readBytes()
                if (bytes.size > ProfileLimits.MAX_DOCUMENT_BYTES) {
                    return ProfileStoreLoadResult.Corrupt(
                        ProfileDecodeError.MalformedDocument("Document exceeds ${ProfileLimits.MAX_DOCUMENT_BYTES} bytes")
                    )
                }
                String(bytes, StandardCharsets.UTF_8)
            } catch (error: IOException) {
                return ProfileStoreLoadResult.Corrupt(
                    ProfileDecodeError.MalformedDocument(error.message ?: "Unable to read profile document")
                )
            }
            when (val decoded = ProfileMetadataCodec.decode(text, repositoryId)) {
                is ProfileDecodeResult.Success -> ProfileStoreLoadResult.Loaded(decoded.records)
                is ProfileDecodeResult.Failure -> ProfileStoreLoadResult.Corrupt(decoded.error)
            }
        }

    /**
     * Atomically replace one repository's profile document. On success the
     * new records are committed; on failure the prior document remains
     * authoritative.
     */
    public fun saveRepository(
        repositoryId: GitHubRepositoryIdentity,
        records: List<ProfileRecord>,
    ): ProfileStoreSaveResult = synchronized(lock) {
        val json = try {
            ProfileMetadataCodec.encode(repositoryId, records)
        } catch (error: IllegalArgumentException) {
            return ProfileStoreSaveResult.Failed(
                ProfileFailure.StorageFailed("encode profile document: ${error.message}")
            )
        }

        val file = repositoryFile(repositoryId)
        val parent = file.absoluteFile.parentFile
        val temporary = File(parent, "${file.name}.tmp")
        try {
            parent.mkdirs()
            FileOutputStream(temporary).use { stream ->
                stream.write(json.toByteArray(StandardCharsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            fsyncDirectory(parent)
            ProfileStoreSaveResult.Committed
        } catch (error: IOException) {
            if (temporary.exists()) {
                try {
                    temporary.delete()
                } catch (_: IOException) {
                    // Best-effort cleanup; the committed document is untouched.
                }
            }
            ProfileStoreSaveResult.Failed(
                ProfileFailure.StorageFailed("save profile document")
            )
        }
    }

    /**
     * Delete one repository's profile document. Used when a repository's
     * profiles are explicitly purged (not on package removal, which preserves
     * records).
     */
    public fun deleteRepository(repositoryId: GitHubRepositoryIdentity): ProfileStoreSaveResult =
        synchronized(lock) {
            val file = repositoryFile(repositoryId)
            if (!file.exists()) return ProfileStoreSaveResult.Committed
            try {
                file.delete()
                fsyncDirectory(file.absoluteFile.parentFile)
                ProfileStoreSaveResult.Committed
            } catch (error: IOException) {
                ProfileStoreSaveResult.Failed(
                    ProfileFailure.StorageFailed("delete profile document")
                )
            }
        }

    /**
     * Discover all repository IDs that have profile documents on disk.
     * Does not decode them; use [loadRepository] for that.
     */
    public fun discoverRepositoryIds(): List<GitHubRepositoryIdentity> = synchronized(lock) {
        val files = storeRoot.listFiles() ?: return emptyList()
        files
            .filter { it.isFile && it.name.endsWith(".json") && !it.name.endsWith(".tmp") }
            .mapNotNull { file ->
                val id = file.name.removeSuffix(".json")
                try {
                    GitHubRepositoryIdentity(id)
                } catch (_: IllegalArgumentException) {
                    null // Skip files with non-canonical names
                }
            }
            .sortedBy { it.value }
    }

    private fun repositoryFile(repositoryId: GitHubRepositoryIdentity): File =
        File(storeRoot, "${repositoryId.value}.json")

    private fun fsyncDirectory(directory: File) {
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        } catch (_: IOException) {
            // Best-effort directory durability; not supported by every filesystem.
        }
    }
}
