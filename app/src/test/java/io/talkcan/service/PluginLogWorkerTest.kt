package io.talkcan.service

import io.talkcan.lua.PluginLogSinkImpl
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginLogWorkerTest {
    @Test
    fun `downstream rejection of a dequeued plugin record increments sink loss exactly once`() = runTest {
        val sink = PluginLogSinkImpl(maxQueueSize = 1, maxMessageBytes = 512)
        var admissionAttempts = 0

        assertTrue(
            sink.tryPublish(
                instanceId = "diagnostics",
                generation = 9,
                timestampMillis = 1_726_000_000_123L,
                level = "warn",
                payloadJson = "{\"message\":\"rejected downstream\"}",
            ),
        )
        assertEquals(0L, sink.getLossCount())

        assertTrue(
            forwardNextPluginLog(sink) {
                admissionAttempts += 1
                false
            },
        )

        assertEquals(1, admissionAttempts)
        assertEquals(1L, sink.getLossCount())
    }
}
