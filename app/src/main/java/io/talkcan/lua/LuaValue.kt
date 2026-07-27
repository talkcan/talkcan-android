package io.talkcan.lua

import java.util.IdentityHashMap

/**
 * Values exchanged with the native Lua boundary. Serialization and parsing are
 * bounded deliberately: this is an untrusted-value boundary, not a general
 * purpose JSON implementation.
 */
internal sealed class LuaValue {
    object Nil : LuaValue()
    data class Bool(val value: Boolean) : LuaValue()
    data class Integer(val value: Long) : LuaValue()
    data class Real(val value: Double) : LuaValue()
    data class StringValue(val value: String) : LuaValue()
    data class Array(val values: List<LuaValue>) : LuaValue()
    data class Map(val pairs: kotlin.collections.Map<String, LuaValue>) : LuaValue()

    /** Returns a typed failure rather than allowing malformed values to cross JNI. */
    fun toJsonString(policy: LuaValuePolicy = LuaValuePolicy.DEFAULT): JsonEncodingResult {
        return LuaValueJsonCodec.encode(this, policy)
    }

    companion object {
        /** Parses native JSON without recursive descent or unchecked JSON exceptions. */
        fun fromJsonString(
            json: String,
            policy: LuaValuePolicy = LuaValuePolicy.DEFAULT,
        ): JsonParsingResult = LuaValueJsonCodec.decode(json, policy)
    }
}

/**
 * Internal boundary limits. They are intentionally non-normative and may be
 * configured by an internal caller that needs a tighter local budget.
 */
internal data class LuaValuePolicy(
    val maxDepth: Int = 32,
    val maxEntries: Int = 4_096,
    val maxUtf8Bytes: Int = 1_048_576,
    val maxStringUtf8Bytes: Int = 262_144,
    val maxKeyUtf8Bytes: Int = 16_384,
) {
    init {
        require(maxDepth >= 0)
        require(maxEntries >= 0)
        require(maxUtf8Bytes >= 0)
        require(maxStringUtf8Bytes >= 0)
        require(maxKeyUtf8Bytes >= 0)
    }

    internal companion object {
        val DEFAULT = LuaValuePolicy()
    }
}

internal sealed class JsonEncodingResult {
    data class Success(val json: String) : JsonEncodingResult()
    data class Failure(val diagnostic: String) : JsonEncodingResult()
}

internal sealed class JsonParsingResult {
    data class Success(val value: LuaValue) : JsonParsingResult()
    data class Failure(val diagnostic: String) : JsonParsingResult()
}

private object LuaValueJsonCodec {
    private const val INVALID_VALUE = "E_INVALID_VALUE"

    fun encode(root: LuaValue, policy: LuaValuePolicy): JsonEncodingResult {
        return try {
            val output = StringBuilder()
            val active = IdentityHashMap<LuaValue, Boolean>()
            val frames = ArrayDeque<EncodeFrame>()
            frames.addLast(EncodeFrame.Value(root, 0))
            var entries = 0
            var encodedBytes = 0

            fun append(text: String): Boolean {
                encodedBytes += utf8Length(text) ?: return false
                if (encodedBytes > policy.maxUtf8Bytes) return false
                output.append(text)
                return true
            }

            while (frames.isNotEmpty()) {
                when (val frame = frames.removeLast()) {
                    is EncodeFrame.Value -> {
                        when (val value = frame.value) {
                            LuaValue.Nil -> if (!append("null")) return encodingFailure("encoded value exceeds byte limit")
                            is LuaValue.Bool -> if (!append(if (value.value) "true" else "false")) return encodingFailure("encoded value exceeds byte limit")
                            is LuaValue.Integer -> {
                                if (!append(value.value.toString())) return encodingFailure("encoded value exceeds byte limit")
                            }
                            is LuaValue.Real -> {
                                if (!value.value.isFinite()) return encodingFailure("number must be finite")
                                if (!append(value.value.toString())) return encodingFailure("encoded value exceeds byte limit")
                            }
                            is LuaValue.StringValue -> {
                                if (utf8Length(value.value, policy.maxStringUtf8Bytes) == null) {
                                    return encodingFailure("string exceeds byte limit or has invalid UTF-16")
                                }
                                val quoted = quote(value.value) ?: return encodingFailure("string has invalid UTF-16")
                                if (!append(quoted)) return encodingFailure("encoded value exceeds byte limit")
                            }
                            is LuaValue.Array -> {
                                if (frame.depth >= policy.maxDepth) return encodingFailure("value exceeds depth limit")
                                if (active.put(value, true) != null) return encodingFailure("value contains a cycle")
                                entries += value.values.size
                                if (entries > policy.maxEntries) return encodingFailure("value exceeds entry limit")
                                if (!append("[")) return encodingFailure("encoded value exceeds byte limit")
                                frames.addLast(EncodeFrame.Close(value, ']'))
                                for (index in value.values.indices.reversed()) {
                                    frames.addLast(EncodeFrame.Value(value.values[index], frame.depth + 1))
                                    if (index > 0) frames.addLast(EncodeFrame.Text(","))
                                }
                            }
                            is LuaValue.Map -> {
                                if (frame.depth >= policy.maxDepth) return encodingFailure("value exceeds depth limit")
                                if (active.put(value, true) != null) return encodingFailure("value contains a cycle")
                                entries += value.pairs.size
                                if (entries > policy.maxEntries) return encodingFailure("value exceeds entry limit")
                                if (!append("{")) return encodingFailure("encoded value exceeds byte limit")
                                frames.addLast(EncodeFrame.Close(value, '}'))
                                frames.addLast(
                                    EncodeFrame.MapEntries(
                                        value,
                                        value.pairs.entries.sortedBy { it.key }.iterator(),
                                        frame.depth,
                                        true,
                                    ),
                                )
                            }
                        }
                    }
                    is EncodeFrame.Text -> if (!append(frame.value)) return encodingFailure("encoded value exceeds byte limit")
                    is EncodeFrame.Close -> {
                        active.remove(frame.value)
                        if (!append(frame.character.toString())) return encodingFailure("encoded value exceeds byte limit")
                    }
                    is EncodeFrame.MapEntries -> {
                        if (!frame.iterator.hasNext()) continue
                        val (key, nested) = frame.iterator.next()
                        if (utf8Length(key, policy.maxKeyUtf8Bytes) == null) {
                            return encodingFailure("map key exceeds byte limit or has invalid UTF-16")
                        }
                        frames.addLast(EncodeFrame.MapEntries(frame.value, frame.iterator, frame.depth, false))
                        frames.addLast(EncodeFrame.Value(nested, frame.depth + 1))
                        frames.addLast(EncodeFrame.Text(":"))
                        frames.addLast(EncodeFrame.Text(quote(key) ?: return encodingFailure("map key has invalid UTF-16")))
                        if (!frame.first) frames.addLast(EncodeFrame.Text(","))
                    }
                }
            }
            JsonEncodingResult.Success(output.toString())
        } catch (_: RuntimeException) {
            encodingFailure("malformed value")
        }
    }

    fun decode(json: String, policy: LuaValuePolicy): JsonParsingResult {
        return try {
            if (utf8Length(json, policy.maxUtf8Bytes) == null) {
                return parsingFailure("input exceeds byte limit or has invalid UTF-16")
            }
            JsonParser(json, policy).parse()
        } catch (_: RuntimeException) {
            parsingFailure("malformed JSON")
        }
    }

    private fun encodingFailure(reason: String) = JsonEncodingResult.Failure("$INVALID_VALUE: $reason")
    private fun parsingFailure(reason: String) = JsonParsingResult.Failure("$INVALID_VALUE: $reason")

    private sealed class EncodeFrame {
        data class Value(val value: LuaValue, val depth: Int) : EncodeFrame()
        data class Text(val value: String) : EncodeFrame()
        data class Close(val value: LuaValue, val character: Char) : EncodeFrame()
        data class MapEntries(
            val value: LuaValue.Map,
            val iterator: Iterator<kotlin.collections.Map.Entry<String, LuaValue>>,
            val depth: Int,
            val first: Boolean,
        ) : EncodeFrame()
    }

    private fun quote(value: String): String? {
        val result = StringBuilder(value.length + 2).append('"')
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when (character) {
                '"' -> result.append("\\\"")
                '\\' -> result.append("\\\\")
                '\b' -> result.append("\\b")
                '\u000C' -> result.append("\\f")
                '\n' -> result.append("\\n")
                '\r' -> result.append("\\r")
                '\t' -> result.append("\\t")
                else -> when {
                    character.code < 0x20 -> result.append("\\u%04x".format(character.code))
                    character.isHighSurrogate() -> {
                        if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return null
                        result.append(character).append(value[++index])
                    }
                    character.isLowSurrogate() -> return null
                    else -> result.append(character)
                }
            }
            index++
        }
        return result.append('"').toString()
    }

    private fun utf8Length(value: String, maximum: Int = Int.MAX_VALUE): Int? {
        var length = 0L
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                character.code < 0x80 -> length++
                character.code < 0x800 -> length += 2
                character.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return null
                    length += 4
                    index++
                }
                character.isLowSurrogate() -> return null
                else -> length += 3
            }
            if (length > maximum) return null
            index++
        }
        return length.toInt()
    }

    private class JsonParser(private val source: String, private val policy: LuaValuePolicy) {
        private var index = 0
        private var entries = 0

        fun parse(): JsonParsingResult {
            skipWhitespace()
            val root = parseValue(0) ?: return parsingFailure("expected JSON value")
            skipWhitespace()
            if (index != source.length) return parsingFailure("trailing JSON content")
            return JsonParsingResult.Success(root)
        }

        private fun parseValue(depth: Int): LuaValue? {
            if (depth > policy.maxDepth || index >= source.length) return null
            return when (source[index]) {
                'n' -> literal("null", LuaValue.Nil)
                't' -> literal("true", LuaValue.Bool(true))
                'f' -> literal("false", LuaValue.Bool(false))
                '"' -> parseString()?.let(LuaValue::StringValue)
                '[' -> parseArray(depth + 1)
                '{' -> parseMap(depth + 1)
                '-', in '0'..'9' -> parseNumber()
                else -> null
            }
        }

        private fun literal(literal: String, value: LuaValue): LuaValue? {
            if (!source.regionMatches(index, literal, 0, literal.length)) return null
            index += literal.length
            return value
        }

        private fun parseNumber(): LuaValue? {
            val start = index
            if (source[index] == '-') index++
            if (index >= source.length) return null
            if (source[index] == '0') index++ else {
                if (source[index] !in '1'..'9') return null
                while (index < source.length && source[index] in '0'..'9') index++
            }
            var fractional = false
            if (index < source.length && source[index] == '.') {
                fractional = true
                index++
                val fractionalStart = index
                while (index < source.length && source[index] in '0'..'9') index++
                if (fractionalStart == index) return null
            }
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                fractional = true
                index++
                if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
                val exponentStart = index
                while (index < source.length && source[index] in '0'..'9') index++
                if (exponentStart == index) return null
            }
            val lexeme = source.substring(start, index)
            if (!fractional) {
                lexeme.toLongOrNull()?.let { return LuaValue.Integer(it) }
            }
            val number = lexeme.toDoubleOrNull() ?: return null
            return if (number.isFinite()) LuaValue.Real(number) else null
        }

        private fun parseString(): String? {
            check(source[index] == '"')
            index++
            val result = StringBuilder()
            while (index < source.length) {
                val character = source[index++]
                when (character) {
                    '"' -> {
                        val byteLength = utf8Length(result.toString()) ?: return null
                        return if (byteLength <= policy.maxStringUtf8Bytes) result.toString() else null
                    }
                    '\\' -> {
                        if (index >= source.length) return null
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> appendUnicodeEscape(result) ?: return null
                            else -> return null
                        }
                    }
                    in '\u0000'..'\u001F' -> return null
                    else -> {
                        if (character.isLowSurrogate()) return null
                        if (character.isHighSurrogate()) {
                            if (index >= source.length || !source[index].isLowSurrogate()) return null
                            result.append(character).append(source[index++])
                        } else {
                            result.append(character)
                        }
                    }
                }
            }
            return null
        }

        private fun appendUnicodeEscape(output: StringBuilder): Unit? {
            if (index + 4 > source.length) return null
            val first = source.substring(index, index + 4).toIntOrNull(16)?.toChar() ?: return null
            index += 4
            if (first.isHighSurrogate()) {
                if (index + 6 > source.length || source[index] != '\\' || source[index + 1] != 'u') return null
                val second = source.substring(index + 2, index + 6).toIntOrNull(16)?.toChar() ?: return null
                if (!second.isLowSurrogate()) return null
                index += 6
                output.append(first).append(second)
            } else {
                if (first.isLowSurrogate()) return null
                output.append(first)
            }
            return Unit
        }

        private fun parseArray(depth: Int): LuaValue? {
            if (depth > policy.maxDepth) return null
            index++
            skipWhitespace()
            val values = mutableListOf<LuaValue>()
            if (consume(']')) return LuaValue.Array(values)
            while (true) {
                val value = parseValue(depth) ?: return null
                values += value
                if (!incrementEntries()) return null
                skipWhitespace()
                if (consume(']')) return LuaValue.Array(values)
                if (!consume(',')) return null
                skipWhitespace()
            }
        }

        private fun parseMap(depth: Int): LuaValue? {
            if (depth > policy.maxDepth) return null
            index++
            skipWhitespace()
            val pairs = LinkedHashMap<String, LuaValue>()
            if (consume('}')) return LuaValue.Map(pairs)
            while (true) {
                if (index >= source.length || source[index] != '"') return null
                val key = parseString() ?: return null
                if ((utf8Length(key) ?: return null) > policy.maxKeyUtf8Bytes) return null
                skipWhitespace()
                if (!consume(':')) return null
                skipWhitespace()
                val value = parseValue(depth) ?: return null
                if (pairs.put(key, value) != null) return null
                if (!incrementEntries()) return null
                skipWhitespace()
                if (consume('}')) return LuaValue.Map(pairs)
                if (!consume(',')) return null
                skipWhitespace()
            }
        }

        private fun incrementEntries(): Boolean = ++entries <= policy.maxEntries

        private fun consume(character: Char): Boolean {
            if (index >= source.length || source[index] != character) return false
            index++
            return true
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in " \t\r\n") index++
        }
    }
}
