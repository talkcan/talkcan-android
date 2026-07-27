package io.talkcan.lua

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginLogSinkTest {
    @Test
    fun `accepted record preserves host envelope and escaped instance identity exactly`() = runBlocking {
        val sink = PluginLogSinkImpl(maxQueueSize = 2, maxMessageBytes = 512)

        assertTrue(
            sink.tryPublish(
                instanceId = "channel-\"alpha\"\\beta",
                generation = 42,
                timestampMillis = 1_726_000_000_123L,
                level = "info",
                payloadJson = "{\"message\":\"received\"}",
            ),
        )

        assertEquals(
            LogRecord(
                timestampMillis = 1_726_000_000_123L,
                level = "info",
                message = "{\"generation\":42,\"instance_id\":\"channel-\\\"alpha\\\"\\\\beta\",\"payload\":{\"message\":\"received\"}}",
            ),
            sink.receive(),
        )
    }

    @Test
    fun `every supported native level is admitted with its original label`() = runBlocking {
        val sink = PluginLogSinkImpl(maxQueueSize = 4, maxMessageBytes = 512)
        val levels = listOf("debug", "info", "warn", "error")

        levels.forEachIndexed { index, level ->
            assertTrue(sink.tryPublish("channel", 1, index.toLong(), level, "{}"))
        }

        assertEquals(levels, levels.map { sink.receive()?.level })
    }

    @Test
    fun `invalid level and UTF-8 payload beyond bound are dropped and counted`() = runBlocking {
        val acceptedMessage = "{\"generation\":7,\"instance_id\":\"id\",\"payload\":{\"message\":\"é\"}}"
        val sink = PluginLogSinkImpl(
            maxQueueSize = 2,
            maxMessageBytes = acceptedMessage.toByteArray(Charsets.UTF_8).size,
        )

        assertFalse(sink.tryPublish("id", 7, 1, "trace", "{}"))
        assertTrue(sink.tryPublish("id", 7, 2, "debug", "{\"message\":\"é\"}"))
        val tooSmall = PluginLogSinkImpl(
            maxQueueSize = 2,
            maxMessageBytes = acceptedMessage.toByteArray(Charsets.UTF_8).size - 1,
        )
        assertFalse(tooSmall.tryPublish("id", 7, 2, "debug", "{\"message\":\"é\"}"))
        assertEquals(1L, sink.getLossCount())
        assertEquals(1L, tooSmall.getLossCount())
    }

    @Test
    fun `bounded queue drops overflow without displacing the accepted predecessor`() = runBlocking {
        val sink = PluginLogSinkImpl(maxQueueSize = 1, maxMessageBytes = 512)

        assertTrue(sink.tryPublish("channel", 3, 10, "warn", "{\"sequence\":1}"))
        assertFalse(sink.tryPublish("channel", 3, 11, "warn", "{\"sequence\":2}"))

        assertEquals(
            "{\"generation\":3,\"instance_id\":\"channel\",\"payload\":{\"sequence\":1}}",
            sink.receive()?.message,
        )
        assertEquals(1L, sink.getLossCount())
    }

    @Test
    fun `close is idempotent drains accepted records and rejects later publication`() = runBlocking {
        val sink = PluginLogSinkImpl(maxQueueSize = 2, maxMessageBytes = 512)
        assertTrue(sink.tryPublish("channel", 3, 10, "error", "{\"sequence\":1}"))
        assertTrue(sink.tryPublish("channel", 3, 11, "error", "{\"sequence\":2}"))

        sink.close()
        sink.close()

        assertEquals(10L, sink.receive()?.timestampMillis)
        assertEquals(11L, sink.receive()?.timestampMillis)
        assertNull(sink.receive())
        assertFalse(sink.tryPublish("channel", 3, 12, "error", "{\"sequence\":3}"))
        assertEquals(1L, sink.getLossCount())
    }
}
