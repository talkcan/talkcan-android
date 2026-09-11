package io.talkcan.live

import java.io.IOException
import java.util.Base64
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Socket-level regression tests for [GptLiveSession] against a scripted Live server.
 *
 * Each test drives the real WebSocket, audio, and tool paths end to end and asserts
 * only observable behavior: which tools ran, which bytes reached the speaker, which
 * frames reached the server, and which lifecycle phase resulted. Nothing here pins
 * internal wiring.
 */
class GptLiveSessionSocketTest {

    private class FakeLiveAudioStream : LiveAudioStream {
        val written = Collections.synchronizedList(mutableListOf<ByteArray>())
        @Volatile var closed = false
        @Volatile var writeGate: CompletableDeferred<Unit>? = null

        override suspend fun read(buffer: ByteArray): Int {
            while (!closed) {
                delay(5)
                if (closed) break
                val count = minOf(buffer.size, 960)
                java.util.Arrays.fill(buffer, 0, count, 0.toByte())
                return count
            }
            return -1
        }

        override suspend fun write(bytes: ByteArray) {
            writeGate?.await()
            if (closed) throw IOException("closed")
            written.add(bytes.copyOf())
        }

        override suspend fun close() {
            closed = true
        }

        fun writtenBytes(): Long = written.sumOf { it.size.toLong() }
    }

    private class FakeLiveAudioDevice : LiveAudioDevice {
        val opens = AtomicInteger(0)
        @Volatile var last: FakeLiveAudioStream? = null

        override suspend fun open(): LiveAudioStream {
            opens.incrementAndGet()
            return FakeLiveAudioStream().also { last = it }
        }
    }

    private class FakeLiveAppTools : LiveAppTools {
        data class Call(val name: String, val arguments: JSONObject)

        val calls = Collections.synchronizedList(mutableListOf<Call>())
        @Volatile var handler: (String, JSONObject) -> JSONObject =
            { _, _ -> JSONObject().put("ok", true) }

        override fun definitions(): JSONArray = JSONArray()

        override suspend fun execute(name: String, arguments: JSONObject): JSONObject {
            calls.add(Call(name, arguments))
            return handler(name, arguments)
        }
    }

    private class ScriptedServer {
        val server = MockWebServer()
        val received = LinkedBlockingQueue<String>()
        @Volatile var socket: WebSocket? = null

        private val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                received.add(text)
            }
        }

        fun start(): String {
            server.enqueue(MockResponse().withWebSocketUpgrade(listener))
            server.start()
            return server.url("/live").toString().replaceFirst("http", "ws")
        }

        fun send(text: String) {
            socket?.send(text)
        }

        suspend fun awaitText(timeoutMs: Long, match: (JSONObject) -> Boolean): JSONObject =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) throw AssertionError("Timed out waiting for server frame")
                val text = received.poll(remaining, TimeUnit.MILLISECONDS)
                    ?: throw AssertionError("Timed out waiting for server frame")
                val json = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    continue
                }
                if (match(json)) return@withContext json
            }
            error("Unreachable")
        }

        fun drainTypes(): List<String> {
            val types = mutableListOf<String>()
            while (true) {
                val next = received.poll() ?: return types
                runCatching { types.add(JSONObject(next).optString("type")) }
            }
        }

        fun stop() {
            runCatching { socket?.close(1000, null) }
            runCatching { server.shutdown() }
        }
    }

    private data class Fixture(
        val backend: ScriptedServer,
        val audio: FakeLiveAudioDevice,
        val tools: FakeLiveAppTools,
        val session: GptLiveSession,
    )

    private fun fixture(scope: CoroutineScope, backend: ScriptedServer, url: String): Fixture {
        val audio = FakeLiveAudioDevice()
        val tools = FakeLiveAppTools()
        val session = GptLiveSession(scope, audio, tools, LiveSessionConfiguration(), "test-key")
        session.endpointOverride = url
        return Fixture(backend, audio, tools, session)
    }

    private fun responseEnvelope(delegationId: String, nested: JSONObject): String {
        return JSONObject()
            .put("type", "response.event")
            .put("event_id", "evt_test")
            .put("delegation_id", delegationId)
            .put("event", nested)
            .toString()
    }

    private suspend fun awaitPhase(
        session: GptLiveSession,
        phase: LiveSessionPhase,
        timeoutMs: Long = 10_000L,
    ) {
        withTimeout(timeoutMs) {
            while (session.state.value.phase != phase) delay(25)
        }
    }

    private suspend fun runDelegatedToolFlow(backend: ScriptedServer) {
        backend.awaitText(10_000) { it.optString("type") == "session.start" }
        backend.send(JSONObject().put("type", "session.started").toString())
        backend.send(
            responseEnvelope(
                "dlg_1",
                JSONObject()
                    .put("type", "response.created")
                    .put("response", JSONObject().put("id", "rsp_1")),
            ),
        )
        backend.send(
            responseEnvelope(
                "dlg_1",
                JSONObject()
                    .put("type", "response.output_item.done")
                    .put(
                        "item",
                        JSONObject()
                            .put("type", "function_call")
                            .put("call_id", "call_1")
                            .put("name", "lookup")
                            .put("arguments", JSONObject().put("id", 7)),
                    ),
            ),
        )
        backend.send(
            responseEnvelope(
                "dlg_1",
                JSONObject().put("type", "response.completed"),
            ),
        )
    }

    private fun CoroutineScope.activate(fixture: Fixture): Deferred<Unit> {
        val starting = async { fixture.session.start() }
        return starting
    }

    private suspend fun CoroutineScope.gracefulClose(fixture: Fixture, closed: String) {
        val closing = async { fixture.session.close() }
        fixture.backend.awaitText(10_000) { it.optString("type") == "session.close" }
        fixture.backend.send(closed)
        closing.await()
    }

    @Test
    fun startupRejectionPreservesServerCodeWithoutOpeningMicrophone() = runBlocking {
        val backend = ScriptedServer()
        val url = backend.start()
        val fixture = fixture(this, backend, url)
        try {
            val starting = activate(fixture)
            backend.awaitText(10_000) { it.optString("type") == "session.start" }
            backend.send(
                JSONObject()
                    .put("type", "error")
                    .put("error", JSONObject()
                        .put("code", "output_creation_failed")
                        .put("message", "Sensitive server detail must not reach the UI"))
                    .toString(),
            )
            starting.await()
            awaitPhase(fixture.session, LiveSessionPhase.FAILED)
            assertTrue(fixture.session.state.value.message?.contains("output_creation_failed") == true)
            assertTrue(fixture.session.state.value.message?.contains("Sensitive") == false)
            assertEquals(0, fixture.audio.opens.get())
        } finally {
            fixture.session.close()
            backend.stop()
        }
    }

    @Test
    fun delegatedToolWithoutNestedResponseIdRunsOnceAndContinues() = runBlocking {
        val backend = ScriptedServer()
        val url = backend.start()
        try {
            val fixture = fixture(this, backend, url)
            val starting = activate(fixture)

            runDelegatedToolFlow(backend)
            starting.await()
            awaitPhase(fixture.session, LiveSessionPhase.ACTIVE)

            withTimeout(10_000) {
                while (fixture.tools.calls.isEmpty()) delay(25)
            }
            assertEquals(1, fixture.tools.calls.size)
            assertEquals("lookup", fixture.tools.calls[0].name)
            assertEquals(7, fixture.tools.calls[0].arguments.getInt("id"))
            val output = backend.awaitText(10_000) {
                it.optString("type") == "response.item.create"
            }
            assertEquals("call_1", output.getJSONObject("item").getString("call_id"))
            backend.awaitText(10_000) { it.optString("type") == "response.create" }

            gracefulClose(
                fixture,
                JSONObject().put("type", "session.closed").put("reason", "close_requested").toString(),
            )
            awaitPhase(fixture.session, LiveSessionPhase.IDLE)
        } finally {
            backend.stop()
        }
    }

    @Test
    fun duplicateTerminalCausesNoSecondToolEffect() = runBlocking {
        val backend = ScriptedServer()
        val url = backend.start()
        try {
            val fixture = fixture(this, backend, url)
            val starting = activate(fixture)

            runDelegatedToolFlow(backend)
            starting.await()
            awaitPhase(fixture.session, LiveSessionPhase.ACTIVE)
            backend.awaitText(10_000) { it.optString("type") == "response.create" }
            backend.send(
                responseEnvelope("dlg_1", JSONObject().put("type", "response.completed")),
            )
            delay(1_000)

            assertEquals(1, fixture.tools.calls.size)

            gracefulClose(
                fixture,
                JSONObject().put("type", "session.closed").put("reason", "close_requested").toString(),
            )
        } finally {
            backend.stop()
        }
    }

    @Test
    fun audioStreamsBothDirectionsSimultaneously() = runBlocking {
        val backend = ScriptedServer()
        val url = backend.start()
        try {
            val fixture = fixture(this, backend, url)
            val starting = activate(fixture)
            backend.awaitText(10_000) { it.optString("type") == "session.start" }
            backend.send(JSONObject().put("type", "session.started").toString())
            starting.await()
            awaitPhase(fixture.session, LiveSessionPhase.ACTIVE)

            val deltas = listOf(ByteArray(480) { 1 }, ByteArray(960) { 2 }, ByteArray(240) { 3 })
            for (delta in deltas) {
                backend.send(
                    JSONObject()
                        .put("type", "session.output_audio.delta")
                        .put("delta", Base64.getEncoder().encodeToString(delta))
                        .toString(),
                )
            }
            val expected = deltas.sumOf { it.size.toLong() }
            withTimeout(10_000) {
                while ((fixture.audio.last?.writtenBytes() ?: 0) < expected) delay(25)
            }
            backend.awaitText(10_000) { it.optString("type") == "session.input_audio.append" }

            gracefulClose(
                fixture,
                JSONObject().put("type", "session.closed").put("reason", "close_requested").toString(),
            )
            awaitPhase(fixture.session, LiveSessionPhase.IDLE)
        } finally {
            backend.stop()
        }
    }

    @Test
    fun closeDuringStartupNeverOpensMicrophone() = runBlocking {
        val backend = ScriptedServer()
        val url = backend.start()
        try {
            val fixture = fixture(this, backend, url)
            val starting = activate(fixture)
            backend.awaitText(10_000) { it.optString("type") == "session.start" }
            awaitPhase(fixture.session, LiveSessionPhase.CONNECTING)

            gracefulClose(
                fixture,
                JSONObject().put("type", "session.closed").put("reason", "close_requested").toString(),
            )
            starting.await()

            assertEquals(0, fixture.audio.opens.get())
            awaitPhase(fixture.session, LiveSessionPhase.IDLE)
        } finally {
            backend.stop()
        }
    }

    @Test
    fun cleanCloseKeepsReportedVoiceUsage() = runBlocking {
        val backend = ScriptedServer()
        val url = backend.start()
        try {
            val fixture = fixture(this, backend, url)
            val starting = activate(fixture)
            backend.awaitText(10_000) { it.optString("type") == "session.start" }
            backend.send(JSONObject().put("type", "session.started").toString())
            starting.await()
            awaitPhase(fixture.session, LiveSessionPhase.ACTIVE)

            gracefulClose(
                fixture,
                JSONObject()
                    .put("type", "session.closed")
                    .put("reason", "close_requested")
                    .put("usage", JSONObject().put("seconds", 9))
                    .toString(),
            )

            awaitPhase(fixture.session, LiveSessionPhase.IDLE)
            assertTrue(fixture.session.state.value.message?.contains("9") == true)
        } finally {
            backend.stop()
        }
    }

    @Test
    fun cleanCloseSucceedsWithoutOptionalUsage() = runBlocking {
        val backend = ScriptedServer()
        val url = backend.start()
        try {
            val fixture = fixture(this, backend, url)
            val starting = activate(fixture)
            backend.awaitText(10_000) { it.optString("type") == "session.start" }
            backend.send(JSONObject().put("type", "session.started").toString())
            starting.await()
            awaitPhase(fixture.session, LiveSessionPhase.ACTIVE)

            gracefulClose(
                fixture,
                JSONObject().put("type", "session.closed").put("reason", "close_requested").toString(),
            )

            awaitPhase(fixture.session, LiveSessionPhase.IDLE)
        } finally {
            backend.stop()
        }
    }

    @Test
    fun inboundOverloadFailsSessionInsteadOfDroppingAudio() = runBlocking {
        val backend = ScriptedServer()
        val url = backend.start()
        try {
            val fixture = fixture(this, backend, url)
            fixture.session.audioQueueChunks = 1
            val starting = activate(fixture)
            backend.awaitText(10_000) { it.optString("type") == "session.start" }
            backend.send(JSONObject().put("type", "session.started").toString())
            starting.await()
            awaitPhase(fixture.session, LiveSessionPhase.ACTIVE)

            fixture.audio.last?.writeGate = CompletableDeferred()
            repeat(20) {
                backend.send(
                    JSONObject()
                        .put("type", "session.output_audio.delta")
                        .put("delta", Base64.getEncoder().encodeToString(ByteArray(960) { 5 }))
                        .toString(),
                )
            }

            awaitPhase(fixture.session, LiveSessionPhase.FAILED)
            fixture.audio.last?.writeGate?.complete(Unit)
            fixture.session.close()
        } finally {
            backend.stop()
        }
    }

    @Test
    fun lateToolResultAfterCloseIsNeverSent() = runBlocking {
        val backend = ScriptedServer()
        val url = backend.start()
        try {
            val fixture = fixture(this, backend, url)
            val releaseTool = CompletableDeferred<Unit>()
            fixture.tools.handler = { _, _ ->
                runBlocking { releaseTool.await() }
                JSONObject().put("ok", true)
            }
            val starting = activate(fixture)

            runDelegatedToolFlow(backend)
            starting.await()
            awaitPhase(fixture.session, LiveSessionPhase.ACTIVE)
            withTimeout(10_000) {
                while (fixture.tools.calls.isEmpty()) delay(25)
            }

            gracefulClose(
                fixture,
                JSONObject().put("type", "session.closed").put("reason", "close_requested").toString(),
            )
            releaseTool.complete(Unit)
            delay(500)

            val sentTypes = backend.drainTypes()
            assertTrue(!sentTypes.contains("response.item.create"))
            assertTrue(!sentTypes.contains("response.create"))
        } finally {
            backend.stop()
        }
    }
}
