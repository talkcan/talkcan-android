package io.talkcan.resource

import io.talkcan.dependency.PackageMountAccess
import io.talkcan.dependency.PackageMountKind
import io.talkcan.model.ChannelImplementationId
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 2.2: Typed strict-decode failures for the mount-binding document.
 */
public sealed interface MountBindingDecodeError {
    public val message: String

    public data class UnsupportedDocumentVersion(val version: Long) : MountBindingDecodeError {
        override val message: String = "Unsupported mount-binding document version: $version"
    }

    public data class MalformedDocument(override val message: String) : MountBindingDecodeError
    public data class InvalidBinding(override val message: String) : MountBindingDecodeError
}

/**
 * 2.2: Strict decode result for the mount-binding document.
 */
public sealed interface MountBindingDecodeResult {
    public data class Success(val bindings: List<MountBinding>) : MountBindingDecodeResult
    public data class Failure(val error: MountBindingDecodeError) : MountBindingDecodeResult
}

/**
 * 2.2: Deterministic versioned codec for the persistent mount-binding store.
 *
 * Encoding is byte-deterministic: fixed key order, canonical enum wire
 * values, strict Base64 grants, and bindings sorted by (channel instance,
 * implementation, declaration). Decoding is strict: exact document version,
 * exact key sets, exact scalar types, canonical enum values, duplicate JSON
 * keys rejected, and domain bounds revalidated. The document carries no
 * platform path, URI scheme, or Android/iOS class reference; grants are
 * opaque Base64 bytes only.
 */
public object MountBindingCodec {
    public const val CURRENT_DOCUMENT_VERSION: Int = 1

    private const val INDENT_1 = "  "
    private const val INDENT_2 = "    "

    /** Deterministically encode bindings into the current document version. */
    public fun encode(bindings: List<MountBinding>): String {
        require(bindings.size <= MountBindingLimits.MAX_BINDINGS) {
            "Binding count exceeds ${MountBindingLimits.MAX_BINDINGS}: ${bindings.size}"
        }
        val sorted = bindings.sortedWith(
            compareBy(
                { it.channelInstanceId },
                { it.implementationId.value },
                { it.declarationId },
            )
        )
        val sb = StringBuilder(256 + sorted.size * 192)
        sb.append("{\n")
        sb.append(INDENT_1).append("\"version\": ").append(CURRENT_DOCUMENT_VERSION).append(",\n")
        if (sorted.isEmpty()) {
            sb.append(INDENT_1).append("\"bindings\": []\n")
        } else {
            sb.append(INDENT_1).append("\"bindings\": [\n")
            sorted.forEachIndexed { index, binding ->
                sb.append(INDENT_2).append("{\n")
                appendField(sb, "channelInstanceId", binding.channelInstanceId, more = true)
                appendField(sb, "implementationId", binding.implementationId.value, more = true)
                appendField(sb, "declarationId", binding.declarationId, more = true)
                appendField(sb, "kind", binding.kind.value, more = true)
                appendField(sb, "access", binding.access.value, more = true)
                appendField(sb, "status", binding.status.portable, more = true)
                appendField(sb, "state", binding.state.encoded, more = true)
                appendField(sb, "grant", Base64.getEncoder().encodeToString(binding.grant.toByteArray()), more = false)
                sb.append(INDENT_2).append("}")
                if (index != sorted.lastIndex) sb.append(",")
                sb.append("\n")
            }
            sb.append(INDENT_1).append("]\n")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun appendField(sb: StringBuilder, name: String, value: String, more: Boolean) {
        sb.append(INDENT_2).append(INDENT_1)
            .append('"').append(name).append("\": ")
            .append('"').append(escapeJson(value)).append('"')
        if (more) sb.append(",")
        sb.append("\n")
    }

    private fun escapeJson(value: String): String = buildString(value.length) {
        for (char in value) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\b' -> append("\\b")
                char == '\u000C' -> append("\\f")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char < ' ' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else -> append(char)
            }
        }
    }

    /** Strictly decode a mount-binding document. */
    public fun decode(json: String): MountBindingDecodeResult {
        return try {
        require(json.toByteArray(StandardCharsets.UTF_8).size <= MountBindingLimits.MAX_DOCUMENT_BYTES) {
            "Binding document exceeds ${MountBindingLimits.MAX_DOCUMENT_BYTES} bytes"
        }
        val root = JsonParser(json).parse() as? JsonObject
            ?: return MountBindingDecodeResult.Failure(
                MountBindingDecodeError.MalformedDocument("Document root must be a JSON object")
            )
        require(root.keys == DOCUMENT_KEYS) {
            "Document must contain exactly $DOCUMENT_KEYS, found ${root.keys}"
        }
        val version = (root["version"] as? JsonLong)?.value
            ?: return MountBindingDecodeResult.Failure(
                MountBindingDecodeError.MalformedDocument("version must be an integer")
            )
        if (version != CURRENT_DOCUMENT_VERSION.toLong()) {
            return MountBindingDecodeResult.Failure(
                MountBindingDecodeError.UnsupportedDocumentVersion(version)
            )
        }
        val array = root["bindings"] as? JsonArray
            ?: return MountBindingDecodeResult.Failure(
                MountBindingDecodeError.MalformedDocument("bindings must be an array")
            )
        require(array.items.size <= MountBindingLimits.MAX_BINDINGS) {
            "Binding count exceeds ${MountBindingLimits.MAX_BINDINGS}: ${array.items.size}"
        }
        val bindings = ArrayList<MountBinding>(array.items.size)
        val seenKeys = HashSet<MountBindingKey>(array.items.size)
        for (item in array.items) {
            val binding = decodeBinding(item)
            require(seenKeys.add(binding.key)) { "Duplicate binding key: ${binding.key}" }
            bindings += binding
        }
        MountBindingDecodeResult.Success(bindings)
    } catch (error: JsonParseException) {
        MountBindingDecodeResult.Failure(
            MountBindingDecodeError.MalformedDocument(error.message ?: "Malformed binding document")
        )
    } catch (error: IllegalArgumentException) {
        MountBindingDecodeResult.Failure(
            MountBindingDecodeError.InvalidBinding(error.message ?: "Invalid binding record")
        )
    }
    }

    private fun decodeBinding(value: JsonValue): MountBinding {
        val obj = value as? JsonObject
            ?: throw IllegalArgumentException("Binding record must be a JSON object")
        require(obj.keys == BINDING_KEYS) {
            "Binding record must contain exactly $BINDING_KEYS, found ${obj.keys}"
        }
        val grantText = obj.string("grant")
        val grantBytes = try {
            Base64.getDecoder().decode(grantText)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Grant is not strict Base64: ${error.message}")
        }
        return MountBinding(
            channelInstanceId = obj.string("channelInstanceId"),
            implementationId = ChannelImplementationId(obj.string("implementationId")),
            declarationId = obj.string("declarationId"),
            kind = PackageMountKind.entries.firstOrNull { it.value == obj.string("kind") }
                ?: throw IllegalArgumentException("Unsupported mount kind: ${obj.string("kind")}"),
            access = PackageMountAccess.entries.firstOrNull { it.value == obj.string("access") }
                ?: throw IllegalArgumentException("Unsupported mount access: ${obj.string("access")}"),
            grant = PlatformGrantBlob(grantBytes),
            status = MountBindingStatus.fromPortable(obj.string("status"))
                ?: throw IllegalArgumentException("Unsupported mount status: ${obj.string("status")}"),
            state = MountBindingState.fromEncoded(obj.string("state"))
                ?: throw IllegalArgumentException("Unsupported mount binding state: ${obj.string("state")}"),
        )
    }

    private val DOCUMENT_KEYS = linkedSetOf("version", "bindings")

    private val BINDING_KEYS = linkedSetOf(
        "channelInstanceId",
        "implementationId",
        "declarationId",
        "kind",
        "access",
        "status",
        "state",
        "grant",
    )
}

/*
 * Minimal strict JSON reader used only by [MountBindingCodec].
 *
 * Rejects duplicate object keys, non-integer numbers, and trailing content;
 * preserves member order so exact-key-set validation is meaningful.
 */

private sealed interface JsonValue

private class JsonObject(val members: LinkedHashMap<String, JsonValue>) : JsonValue {
    val keys: Set<String> get() = members.keys

    operator fun get(name: String): JsonValue? = members[name]

    fun string(name: String): String =
        (members[name] as? JsonString)?.value
            ?: throw IllegalArgumentException("$name must be a string")
}

private class JsonArray(val items: List<JsonValue>) : JsonValue

private class JsonString(val value: String) : JsonValue

private class JsonLong(val value: Long) : JsonValue

private class JsonParseException(message: String) : IllegalArgumentException(message)

private class JsonParser(private val input: String) {
    private var position = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        if (position != input.length) {
            throw JsonParseException("Trailing content at offset $position")
        }
        return value
    }

    private fun parseValue(): JsonValue {
        if (position >= input.length) throw JsonParseException("Unexpected end of document")
        return when (val char = input[position]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonString(parseString())
            't' -> parseLiteral("true", JsonBool(true))
            'f' -> parseLiteral("false", JsonBool(false))
            'n' -> parseLiteral("null", JsonNull)
            '-', in '0'..'9' -> parseNumber()
            else -> throw JsonParseException("Unexpected character '$char' at offset $position")
        }
    }

    private fun parseObject(): JsonValue {
        expect('{')
        val members = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            position++
            return JsonObject(members)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') throw JsonParseException("Object key must be a string at offset $position")
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            val value = parseValue()
            if (members.put(key, value) != null) {
                throw JsonParseException("Duplicate object key: $key")
            }
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    position++
                }
                '}' -> {
                    position++
                    return JsonObject(members)
                }
                else -> throw JsonParseException("Expected ',' or '}' at offset $position")
            }
        }
    }

    private fun parseArray(): JsonValue {
        expect('[')
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            position++
            return JsonArray(items)
        }
        while (true) {
            skipWhitespace()
            items += parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    position++
                }
                ']' -> {
                    position++
                    return JsonArray(items)
                }
                else -> throw JsonParseException("Expected ',' or ']' at offset $position")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (position >= input.length) throw JsonParseException("Unterminated string")
            when (val char = input[position++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (position >= input.length) throw JsonParseException("Unterminated escape")
                    when (val escaped = input[position++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (position + 4 > input.length) throw JsonParseException("Truncated \\u escape")
                            val hex = input.substring(position, position + 4)
                            val code = hex.toIntOrNull(16)
                                ?: throw JsonParseException("Invalid \\u escape: $hex")
                            sb.append(code.toChar())
                            position += 4
                        }
                        else -> throw JsonParseException("Invalid escape: \\$escaped")
                    }
                }
                else -> {
                    if (char < ' ') throw JsonParseException("Unescaped control character in string")
                    sb.append(char)
                }
            }
        }
    }

    private fun parseNumber(): JsonValue {
        val start = position
        if (peek() == '-') position++
        if (position >= input.length || input[position] !in '0'..'9') {
            throw JsonParseException("Invalid number at offset $start")
        }
        if (input[position] == '0') {
            position++
        } else {
            while (position < input.length && input[position] in '0'..'9') position++
        }
        if (position < input.length) {
            val next = input[position]
            if (next == '.' || next == 'e' || next == 'E') {
                throw JsonParseException("Non-integer numbers are not permitted at offset $position")
            }
        }
        val text = input.substring(start, position)
        val value = text.toLongOrNull()
            ?: throw JsonParseException("Number out of range: $text")
        return JsonLong(value)
    }

    private fun parseLiteral(literal: String, value: JsonValue): JsonValue {
        if (!input.startsWith(literal, position)) {
            throw JsonParseException("Invalid literal at offset $position")
        }
        position += literal.length
        return value
    }

    private fun peek(): Char =
        if (position < input.length) input[position] else throw JsonParseException("Unexpected end of document")

    private fun expect(char: Char) {
        if (position >= input.length || input[position] != char) {
            throw JsonParseException("Expected '$char' at offset $position")
        }
        position++
    }

    private fun skipWhitespace() {
        while (position < input.length && input[position] in WHITESPACE) position++
    }

    private companion object {
        private val WHITESPACE = setOf(' ', '\t', '\n', '\r')
    }
}

private data class JsonBool(val value: Boolean) : JsonValue

private data object JsonNull : JsonValue
