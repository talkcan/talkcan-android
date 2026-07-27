package io.talkcan.lua

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

public data class LogRecord(
    val timestampMillis: Long,
    val level: String,
    val message: String
)

public interface PluginLogSink {
    fun tryPublish(
        instanceId: String,
        generation: Long,
        timestampMillis: Long,
        level: String,
        payloadJson: String
    ): Boolean

    fun close()
    fun recordProjectionLoss()
    fun getLossCount(): Long
    suspend fun receive(): LogRecord?
}

public object NoOpPluginLogSink : PluginLogSink {
    override fun tryPublish(
        instanceId: String,
        generation: Long,
        timestampMillis: Long,
        level: String,
        payloadJson: String
    ): Boolean = false

    override fun close() {}
    override fun recordProjectionLoss() {}
    override fun getLossCount(): Long = 0L
    override suspend fun receive(): LogRecord? = null
}

public class PluginLogSinkImpl(
    private val maxQueueSize: Int = 1000,
    private val maxMessageBytes: Int = 262_144 // 256 KB
) : PluginLogSink {
    init {
        require(maxQueueSize > 0) { "Queue size must be positive" }
        require(maxMessageBytes > 0) { "Message size limit must be positive" }
    }

    private val channel = Channel<LogRecord>(capacity = maxQueueSize)
    private val lossCount = AtomicLong(0L)
    private val isClosed = AtomicBoolean(false)

    private fun incrementLossCount() {
        while (true) {
            val current = lossCount.get()
            if (current == Long.MAX_VALUE) break
            val next = if (current < 0) Long.MAX_VALUE else current + 1
            if (lossCount.compareAndSet(current, next)) break
        }
    }

    override fun recordProjectionLoss() {
        incrementLossCount()
    }

    override suspend fun receive(): LogRecord? {
        return try {
            channel.receive()
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            null
        }
    }

    override fun tryPublish(
        instanceId: String,
        generation: Long,
        timestampMillis: Long,
        level: String,
        payloadJson: String
    ): Boolean {
        if (isClosed.get()) {
            incrementLossCount()
            return false
        }

        if (level !in setOf("debug", "info", "warn", "error")) {
            incrementLossCount()
            return false
        }

        val escapedInstanceId = escapeJsonString(instanceId)
        val recordJson = """{"generation":$generation,"instance_id":"$escapedInstanceId","payload":$payloadJson}"""

        val recordBytes = recordJson.toByteArray(Charsets.UTF_8).size
        if (recordBytes > maxMessageBytes) {
            incrementLossCount()
            return false
        }

        val record = LogRecord(
            timestampMillis = timestampMillis,
            level = level,
            message = recordJson
        )

        val result = channel.trySend(record)
        return if (result.isSuccess) {
            true
        } else {
            incrementLossCount()
            false
        }
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            channel.close()
        }
    }

    override fun getLossCount(): Long = lossCount.get()

    private fun escapeJsonString(value: String): String {
        val sb = StringBuilder()
        for (i in 0 until value.length) {
            val ch = value[i]
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }
}

internal fun canonicalSerialize(value: LuaValue, maxLength: Int): String {
    val sb = StringBuilder()
    fun serialize(v: LuaValue) {
        if (sb.length > maxLength) {
            throw IllegalArgumentException("Serialized length exceeds limit")
        }
        when (v) {
            is LuaValue.Nil -> sb.append("null")
            is LuaValue.Bool -> sb.append(if (v.value) "true" else "false")
            is LuaValue.Integer -> sb.append(v.value.toString())
            is LuaValue.Real -> {
                if (!v.value.isFinite()) throw IllegalArgumentException("number must be finite")
                sb.append(v.value.toString())
            }
            is LuaValue.StringValue -> {
                val quoted = when (val res = v.toJsonString()) {
                    is JsonEncodingResult.Success -> res.json
                    is JsonEncodingResult.Failure -> throw IllegalArgumentException(res.diagnostic)
                }
                sb.append(quoted)
            }
            is LuaValue.Array -> {
                sb.append("[")
                for (i in v.values.indices) {
                    if (i > 0) sb.append(",")
                    serialize(v.values[i])
                }
                sb.append("]")
            }
            is LuaValue.Map -> {
                sb.append("{")
                val sortedPairs = v.pairs.toSortedMap()
                var first = true
                for ((key, valueNode) in sortedPairs) {
                    if (!first) sb.append(",")
                    first = false
                    val escapedKey = when (val res = LuaValue.StringValue(key).toJsonString()) {
                        is JsonEncodingResult.Success -> res.json
                        is JsonEncodingResult.Failure -> throw IllegalArgumentException(res.diagnostic)
                    }
                    sb.append(escapedKey).append(":")
                    serialize(valueNode)
                }
                sb.append("}")
            }
        }
    }
    serialize(value)
    return sb.toString()
}
