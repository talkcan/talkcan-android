package io.talkcan.live

import io.talkcan.channel.capability.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveKeyboardToolsTest {
    private class Keyboard : KeyboardOutputAdapter {
        override val identity = CapabilityScopeIdentity("live", RuntimeGeneration(1))
        val typed = mutableListOf<String>()
        var submissions = 0
        var enterCount = 0
        var gate: CompletableDeferred<Unit>? = null
        var outcome: TextDeliveryOutcome = TextDeliveryOutcome.Delivered("operation")
        override suspend fun sendText(request: TextOutputRequest): KeyboardOutputSubmission {
            submissions++
            gate?.await()
            typed += request.text
            return KeyboardOutputSubmission.Completed(outcome)
        }
        override suspend fun sendKey(request: TextKeyRequest): KeyboardOutputSubmission {
            enterCount++
            return KeyboardOutputSubmission.Completed(outcome)
        }
    }

    @Test
    fun textDoesNotSubmitUntilEnterIsExplicitlyRequested() = runTest {
        val keyboard = Keyboard()
        val tools = LiveKeyboardTools(this, keyboard, TextOutputProfile("linux:us")) { true }
        try {
            tools.execute(LiveKeyboardTools.SEND_TEXT, JSONObject().put("text", "draft"))
            assertEquals(0, keyboard.enterCount)
            tools.execute(LiveKeyboardTools.SEND_KEY, JSONObject().put("key", "ENTER"))
            assertEquals(1, keyboard.enterCount)
        } finally { tools.close() }
    }

    @Test
    fun utf8BoundAndUnsupportedKeysAreRejectedBeforeOutput() = runTest {
        val keyboard = Keyboard()
        val tools = LiveKeyboardTools(this, keyboard, TextOutputProfile("linux:us")) { true }
        try {
            assertEquals("invalid_text", tools.execute(LiveKeyboardTools.SEND_TEXT,
                JSONObject().put("text", "界".repeat(6000))).getJSONObject("error").getString("code"))
            assertEquals("invalid_key", tools.execute(LiveKeyboardTools.SEND_KEY,
                JSONObject().put("key", "ESCAPE")).getJSONObject("error").getString("code"))
            assertEquals("invalid_arguments", tools.execute(LiveKeyboardTools.SEND_TEXT,
                JSONObject().put("text", "draft").put("profile", "foreign")).getJSONObject("error").getString("code"))
            assertEquals(0, keyboard.submissions)
            assertEquals(0, keyboard.enterCount)
        } finally { tools.close() }
    }

    @Test
    fun uncertainDeliveryNeverBecomesSuccessOrAutomaticRetry() = runTest {
        val keyboard = Keyboard().apply {
            outcome = TextDeliveryOutcome.Indeterminate("partial", TextOutputIndeterminateReason.ACKNOWLEDGEMENT_LOST)
        }
        val tools = LiveKeyboardTools(this, keyboard, TextOutputProfile("linux:us")) { true }
        try {
            val result = tools.execute(LiveKeyboardTools.SEND_TEXT, JSONObject().put("text", "draft"))
            assertFalse(result.getBoolean("ok"))
            assertEquals("indeterminate", result.getString("status"))
            assertFalse(result.getBoolean("retry_automatically"))
            assertEquals(1, keyboard.submissions)
        } finally { tools.close() }
    }

    @Test
    fun closeCancelsQueuedOutputWithoutClosingSiblingAuthority() = runTest {
        val keyboard = Keyboard().apply { gate = CompletableDeferred() }
        val first = LiveKeyboardTools(this, keyboard, TextOutputProfile("linux:us")) { true }
        val sibling = LiveKeyboardTools(this, keyboard, TextOutputProfile("linux:us")) { true }
        try {
            val pending = async { first.execute(LiveKeyboardTools.SEND_TEXT, JSONObject().put("text", "cancelled")) }
            runCurrent()
            first.close()
            runCurrent()
            assertTrue(pending.isCancelled)
            keyboard.gate!!.complete(Unit)
            sibling.execute(LiveKeyboardTools.SEND_TEXT, JSONObject().put("text", "sibling"))
            assertEquals(listOf("sibling"), keyboard.typed)
            assertEquals("session_closed", first.execute(LiveKeyboardTools.SEND_KEY,
                JSONObject().put("key", "ENTER")).getJSONObject("error").getString("code"))
        } finally { first.close(); sibling.close() }
    }

    @Test
    fun revokedSessionCannotStartOutput() = runTest {
        val keyboard = Keyboard()
        val tools = LiveKeyboardTools(this, keyboard, TextOutputProfile("linux:us")) { false }
        try {
            assertEquals("session_closed", tools.execute(LiveKeyboardTools.SEND_TEXT,
                JSONObject().put("text", "draft")).getJSONObject("error").getString("code"))
            assertEquals(0, keyboard.submissions)
        } finally { tools.close() }
    }
}
