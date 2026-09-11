package io.talkcan.live

import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * Real GPT-Live primary WebSocket session (`wss://api.openai.com/v1/live/sessions`).
 *
 * Lifecycle: [start] connects, sends `session.start` first (voice model `gpt-live-1`,
 * shared PCM24k audio format, configured voice, Responses delegation with the backend
 * model, distinct backend instructions, and application tools), waits bounded for
 * `session.started`, then opens the microphone bounded and streams capture and playback
 * simultaneously until [close]. Silence streams untouched; there is no input commit and
 * no Realtime turn loop. `response.create` is sent only to continue delegated backend
 * work after every pending function result for one terminal `response.completed`
 * boundary is submitted. [close] stops the microphone first, sends `session.close`,
 * finalizes gracefully with a short bounded budget, joins in-flight tool batches so no
 * late effects escape, then falls back to socket cancellation and releases all
 * resources.
 *
 * Failure surfaces as [LiveSessionPhase.FAILED] with a sanitized message and as an
 * early return from [start]; [start] never blocks past the startup budget and never
 * throws for protocol, audio, tool-definition, or network failures. Caller cancellation
 * is always rethrown. One instance runs at most one session: [start] is one-shot and a
 * second call always returns immediately.
 */
public class GptLiveSession(
    private val scope: CoroutineScope,
    private val audio: LiveAudioDevice,
    private val tools: LiveAppTools,
    private val configuration: LiveSessionConfiguration,
    apiKey: String,
) {
    private val apiKey: String = apiKey

    /** Test-only endpoint override (MockWebServer); null uses the production URL. */
    internal var endpointOverride: String? = null

    /** Test-only client factory; null builds the production client. */
    internal var clientProvider: (() -> OkHttpClient)? = null

    /** Test-only audio queue depth; production uses [LiveProtocol.AUDIO_QUEUE_CHUNKS]. */
    internal var audioQueueChunks: Int = LiveProtocol.AUDIO_QUEUE_CHUNKS

    private val _state = MutableStateFlow(LiveSessionState(LiveSessionPhase.IDLE))
    public val state: StateFlow<LiveSessionState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val toolBatchMutex = Mutex()
    private var epoch = 0
    private var startAttempted = false
    @Volatile private var currentEpoch = 0
    @Volatile private var activeSocket: WebSocket? = null
    @Volatile private var lastUsageSeconds: Int? = null

    @Volatile private var childScope: CoroutineScope? = null
    @Volatile private var toolScope: CoroutineScope? = null
    @Volatile private var resources: LiveResources? = null
    @Volatile private var pendingDefinitions: JSONArray? = null
    @Volatile private var handshake: CompletableDeferred<Unit>? = null
    @Volatile private var closedSignal: CompletableDeferred<Unit>? = null
    private var termination: CompletableDeferred<Unit>? = null
    @Volatile private var tracker = DelegationTracker()
    @Volatile private var transcripts = TranscriptAccumulator()
    private val eventIds = AtomicLong(0)

    /**
     * Connects, completes the bounded startup handshake (10s overall for handshake plus
     * microphone open, 5s for the microphone open itself), and returns once the session
     * is [LiveSessionPhase.ACTIVE] or has failed. One-shot: only the first call acts.
     */
    public suspend fun start() {
        val signal = CompletableDeferred<Unit>()
        mutex.withLock {
            if (startAttempted) return
            startAttempted = true
            epoch += 1
            currentEpoch = epoch
            tracker = DelegationTracker()
            transcripts = TranscriptAccumulator()
            lastUsageSeconds = null
            _state.value = LiveSessionState(LiveSessionPhase.CONNECTING)
            val definitions = try {
                tools.definitions()
            } catch (_: Exception) {
                null
            }
            if (definitions == null) {
                _state.value = LiveSessionState(
                    LiveSessionPhase.FAILED,
                    message = "Tools unavailable for the live session",
                )
                return
            }
            pendingDefinitions = definitions
            val parent = scope.coroutineContext[Job]
            val child = CoroutineScope(scope.coroutineContext + SupervisorJob(parent))
            childScope = child
            toolScope = CoroutineScope(child.coroutineContext + SupervisorJob(child.coroutineContext[Job]))
            handshake = signal
            closedSignal = CompletableDeferred()
            termination = null
            val client = clientProvider?.invoke() ?: defaultClient()
            val request = Request.Builder()
                .url(endpointOverride ?: LiveProtocol.SESSION_URL)
                .header("Authorization", "Bearer $apiKey")
                .build()
            val triggerEpoch = currentEpoch
            val socket = client.newWebSocket(request, LiveSocketListener(triggerEpoch))
            resources = LiveResources(socket, client, null, null, null, child)
            activeSocket = socket
        }

        try {
            withTimeout(LiveProtocol.STARTUP_TIMEOUT_MS) {
                signal.await()
                mutex.withLock {
                    if (_state.value.phase != LiveSessionPhase.CONNECTING) throw SessionAborted()
                }
                val stream = withTimeout(LiveProtocol.AUDIO_OPEN_TIMEOUT_MS) { audio.open() }
                mutex.withLock {
                    val live = resources
                    if (live == null || _state.value.phase != LiveSessionPhase.CONNECTING) {
                        runCatching { stream.close() }
                        throw SessionAborted()
                    }
                    val outbound = Channel<ByteArray>(audioQueueChunks)
                    val inbound = Channel<ByteArray>(audioQueueChunks)
                    resources = live.copy(stream = stream, outbound = outbound, inbound = inbound)
                    _state.value = LiveSessionState(LiveSessionPhase.ACTIVE)
                    val child = live.scope
                    val triggerEpoch = currentEpoch
                    child.launch { captureLoop(stream, outbound, triggerEpoch) }
                    child.launch { senderLoop(live.socket, outbound, triggerEpoch) }
                    child.launch { playbackLoop(stream, inbound, triggerEpoch) }
                }
            }
        } catch (_: TimeoutCancellationException) {
            fail("Could not start the live session")
            return
        } catch (_: SessionAborted) {
            return
        } catch (error: CancellationException) {
            abortStart()
            throw error
        } catch (error: StartupRejected) {
            fail(error.reason)
            return
        } catch (_: Exception) {
            fail("Could not start the live session")
            return
        }
    }

    /**
     * Finalizes gracefully: stops the microphone first so blocked reads and writes
     * unblock, sends `session.close`, waits bounded for `session.closed`, joins
     * in-flight tool batches so no late effects escape, then falls back to socket
     * cancellation and releases all resources. Awaited and idempotent; concurrent
     * callers share the same termination. Safe to call while [start] is suspended.
     */
    public suspend fun close() {
        val done = mutex.withLock {
            startAttempted = true
            val pending = termination
            if (pending != null) {
                pending to false
            } else {
                val fresh = CompletableDeferred<Unit>()
                termination = fresh
                if (_state.value.phase == LiveSessionPhase.CONNECTING ||
                    _state.value.phase == LiveSessionPhase.ACTIVE
                ) {
                    _state.value = _state.value.copy(phase = LiveSessionPhase.CLOSING, message = null)
                }
                handshake?.completeExceptionally(IllegalStateException("Aborted"))
                fresh to true
            }
        }
        val finalizer = done.second
        if (!finalizer) {
            done.first.await()
            return
        }
        try {
            withContext(NonCancellable) { finalizeClose() }
        } finally {
            done.first.complete(Unit)
        }
    }

    private suspend fun finalizeClose() {
        val live = mutex.withLock {
            val captured = resources
            resources = null
            pendingDefinitions = null
            captured
        }
        runCatching { live?.stream?.close() }
        live?.outbound?.close()
        live?.inbound?.close()
        if (live != null) {
            runCatching { live.socket.send(LiveProtocol.closeSessionJson()) }
        }
        val finalized = if (live != null) {
            try {
                withTimeout(LiveProtocol.CLOSE_GRACE_MS) { closedSignal?.await() }
                true
            } catch (_: Exception) {
                false
            }
        } else {
            true
        }
        joinToolBatches()
        if (live != null) {
            if (finalized) {
                runCatching { live.socket.close(1000, null) }
            } else {
                runCatching { live.socket.cancel() }
            }
            runCatching { live.client.dispatcher.executorService.shutdown() }
            runCatching { live.client.connectionPool.evictAll() }
        }
        val scopes = mutex.withLock {
            val child = childScope
            val toolsScope = toolScope
            childScope = null
            toolScope = null
            activeSocket = null
            child to toolsScope
        }
        runCatching { scopes.second?.coroutineContext?.get(Job)?.cancel() }
        runCatching { scopes.first?.coroutineContext?.get(Job)?.cancel() }
        withTimeoutOrNull(LiveProtocol.TOOL_SHUTDOWN_MS) {
            runCatching { scopes.first?.coroutineContext?.get(Job)?.join() }
        }
        mutex.withLock {
            if (_state.value.phase == LiveSessionPhase.CLOSING) {
                _state.value = if (finalized) {
                    LiveSessionState(LiveSessionPhase.IDLE, message = closedMessage(lastUsageSeconds))
                } else {
                    LiveSessionState(
                        LiveSessionPhase.FAILED,
                        message = "Could not finish closing the live session",
                    )
                }
            }
        }
    }

    private suspend fun joinToolBatches() {
        val toolsJob = toolScope?.coroutineContext?.get(Job) ?: return
        withTimeoutOrNull(LiveProtocol.TOOL_SHUTDOWN_MS) {
            toolsJob.children.toList().joinAll()
        }
        runCatching { toolsJob.cancel() }
    }

    private fun closedMessage(usageSeconds: Int?): String {
        return if (usageSeconds != null) {
            "Live session closed (${usageSeconds}s voice)"
        } else {
            "Live session closed"
        }
    }

    private suspend fun abortStart() {
        withContext(NonCancellable) { fail("Could not start the live session") }
    }

    private suspend fun fail(message: String) {
        val live = mutex.withLock {
            val phase = _state.value.phase
            if (phase == LiveSessionPhase.IDLE ||
                phase == LiveSessionPhase.CLOSING ||
                phase == LiveSessionPhase.FAILED
            ) {
                handshake?.completeExceptionally(IllegalStateException("Aborted"))
                null
            } else {
                _state.value = LiveSessionState(LiveSessionPhase.FAILED, message = message)
                handshake?.completeExceptionally(IllegalStateException("Aborted"))
                val captured = resources
                resources = null
                activeSocket = null
                pendingDefinitions = null
                captured
            }
        } ?: return
        withContext(NonCancellable) {
            runCatching { live.stream?.close() }
            live.outbound?.close()
            live.inbound?.close()
            runCatching { live.socket.cancel() }
            runCatching { live.client.dispatcher.executorService.shutdown() }
            runCatching { live.client.connectionPool.evictAll() }
            runCatching { toolScope?.coroutineContext?.get(Job)?.cancel() }
            runCatching { live.scope.coroutineContext[Job]?.cancel() }
        }
    }

    private fun backgroundFailure(message: String) {
        val child = childScope ?: scope
        child.launch {
            fail(message)
        }
    }

    private fun defaultClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private fun nextEventId(prefix: String): String {
        return prefix + currentEpoch + "_" + eventIds.incrementAndGet()
    }

    private suspend fun captureLoop(
        stream: LiveAudioStream,
        outbound: Channel<ByteArray>,
        triggerEpoch: Int,
    ) {
        val buffer = ByteArray(LiveProtocol.CAPTURE_READ_BYTES)
        var carry: Byte? = null
        try {
            while (coroutineContext.isActive && triggerEpoch == currentEpoch) {
                if (_state.value.phase != LiveSessionPhase.ACTIVE) break
                val count = try {
                    stream.read(buffer)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (_state.value.phase == LiveSessionPhase.ACTIVE) {
                        backgroundFailure("Audio capture ended")
                    }
                    break
                }
                if (count <= 0) {
                    if (_state.value.phase == LiveSessionPhase.ACTIVE) {
                        backgroundFailure("Audio capture ended")
                    }
                    break
                }
                val carried = if (carry != null) 1 else 0
                val total = carried + count
                val even = total - (total % 2)
                if (even > 0) {
                    val chunk = ByteArray(even)
                    var offset = 0
                    val held = carry
                    if (held != null) {
                        chunk[0] = held
                        offset = 1
                    }
                    System.arraycopy(buffer, 0, chunk, offset, even - offset)
                    if (_state.value.phase != LiveSessionPhase.ACTIVE) break
                    if (!outbound.trySend(chunk).isSuccess) {
                        backgroundFailure("Audio overload")
                        break
                    }
                }
                carry = if (total % 2 == 1) buffer[count - 1] else null
            }
        } catch (error: CancellationException) {
            throw error
        }
    }

    private suspend fun senderLoop(
        socket: WebSocket,
        outbound: Channel<ByteArray>,
        triggerEpoch: Int,
    ) {
        try {
            for (chunk in outbound) {
                if (!coroutineContext.isActive || triggerEpoch != currentEpoch) break
                if (_state.value.phase != LiveSessionPhase.ACTIVE) break
                val queued = try {
                    socket.queueSize()
                } catch (_: Exception) {
                    break
                }
                if (queued > LiveProtocol.SOCKET_QUEUE_BYTES) {
                    backgroundFailure("Audio overload")
                    break
                }
                val encoded = Base64.getEncoder().encodeToString(chunk)
                if (!socket.send(LiveProtocol.inputAudioAppendJson(encoded))) break
            }
        } catch (error: CancellationException) {
            throw error
        }
    }

    private suspend fun playbackLoop(
        stream: LiveAudioStream,
        inbound: Channel<ByteArray>,
        triggerEpoch: Int,
    ) {
        try {
            for (pcm in inbound) {
                if (!coroutineContext.isActive || triggerEpoch != currentEpoch) break
                try {
                    stream.write(pcm)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (_state.value.phase == LiveSessionPhase.ACTIVE) {
                        backgroundFailure("Audio playback ended")
                    }
                    break
                }
            }
        } catch (error: CancellationException) {
            throw error
        }
    }

    /**
     * Tool work may run once the handshake completes, even before audio is active:
     * delegation does not depend on the microphone, and completions can arrive
     * between `session.started` and `ACTIVE`. Closing and terminal phases stop work.
     */
    private fun canWork(): Boolean {
        val phase = _state.value.phase
        return phase == LiveSessionPhase.ACTIVE || phase == LiveSessionPhase.CONNECTING
    }

    /**
     * Executes one terminal batch of function calls off the socket and audio paths,
     * then submits every result before continuing the backend response exactly once.
     * Batches are serialized, each tool runs under a finite deadline, and every send
     * return is checked: a refused send fails the session instead of pretending the
     * outputs were submitted. Sends are dropped when the trigger is stale or the
     * session is closing, so late tool callbacks can never write into a newer session
     * or a closing socket. Typed tool results — including in-band domain failures —
     * are submitted verbatim; only a thrown tool maps to a generic failure, and calls
     * with invalid arguments submit an explicit `invalid_arguments` failure without
     * ever running the tool.
     */
    private suspend fun executeBatch(
        calls: List<DelegationTracker.PendingCall>,
        triggerEpoch: Int,
    ) {
        toolBatchMutex.withLock {
            executeAndContinue(calls, triggerEpoch)
        }
    }

    private suspend fun executeAndContinue(
        calls: List<DelegationTracker.PendingCall>,
        triggerEpoch: Int,
    ) {
        if (calls.isEmpty()) return
        val results = ArrayList<Pair<String, JSONObject>>(calls.size)
        for (call in calls) {
            if (triggerEpoch != currentEpoch) return
            if (!canWork()) return
            val outcome = if (call.argumentsInvalid) {
                JSONObject().put("error", "invalid_arguments")
            } else {
                try {
                    withTimeout(LiveProtocol.TOOL_EXECUTION_TIMEOUT_MS) {
                        tools.execute(call.name, call.arguments)
                    }
                } catch (_: TimeoutCancellationException) {
                    JSONObject().put("error", "tool_failed")
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    JSONObject().put("error", "tool_failed")
                }
            }
            results.add(call.callId to boundedToolOutput(outcome))
        }
        if (triggerEpoch != currentEpoch) return
        if (!canWork()) return
        var delivered = true
        for ((callId, output) in results) {
            val envelope = LiveProtocol.functionCallOutputJson(
                callId = callId,
                output = output,
                eventId = nextEventId("evt_tool_"),
            )
            if (!sendEvent(envelope)) {
                delivered = false
                break
            }
            if (triggerEpoch != currentEpoch) return
            if (!canWork()) return
        }
        if (delivered) {
            delivered = sendEvent(LiveProtocol.continueResponseJson(nextEventId("evt_continue_")))
        }
        if (!delivered) {
            backgroundFailure("Connection lost")
        }
    }

    private fun sendEvent(text: String): Boolean {
        val socket = activeSocket ?: return false
        return try {
            socket.send(text)
        } catch (_: Exception) {
            false
        }
    }

    private fun boundedToolOutput(output: JSONObject): JSONObject {
        return try {
            if (output.toString().length <= LiveProtocol.MAX_TOOL_OUTPUT_CHARS) {
                output
            } else {
                JSONObject().put("error", "tool_output_too_large")
            }
        } catch (_: Exception) {
            JSONObject().put("error", "tool_failed")
        }
    }

    private fun publishTranscripts() {
        _state.update {
            it.copy(inputTranscript = transcripts.input, outputTranscript = transcripts.output)
        }
    }

    private inner class LiveSocketListener(
        private val triggerEpoch: Int,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (triggerEpoch != currentEpoch) return
            if (webSocket !== activeSocket) return
            val definitions = pendingDefinitions ?: return
            val start = LiveProtocol.sessionStartJson(
                configuration = configuration,
                toolDefinitions = definitions,
                eventId = nextEventId("evt_start_"),
            )
            if (!runCatching { webSocket.send(start) }.getOrDefault(false)) {
                backgroundFailure("Connection lost")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (triggerEpoch != currentEpoch) return
            if (webSocket !== activeSocket) return
            if (text.length > LiveProtocol.MAX_INBOUND_MESSAGE_CHARS) {
                backgroundFailure("Live protocol message exceeded its size limit")
                return
            }
            handleEvent(text, triggerEpoch, webSocket)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // The server sends JSON text frames only; binary frames carry no Live event.
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (triggerEpoch != currentEpoch) return
            handleTransportClosed()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (triggerEpoch != currentEpoch) return
            handleTransportClosed()
        }

        override fun onFailure(webSocket: WebSocket, cause: Throwable, response: Response?) {
            if (triggerEpoch != currentEpoch) return
            when (_state.value.phase) {
                LiveSessionPhase.CONNECTING -> {
                    handshake?.completeExceptionally(IllegalStateException("Aborted"))
                }
                LiveSessionPhase.ACTIVE -> {
                    backgroundFailure("Connection lost")
                }
                LiveSessionPhase.CLOSING -> {
                    closedSignal?.completeExceptionally(IllegalStateException("Aborted"))
                }
                LiveSessionPhase.IDLE, LiveSessionPhase.FAILED -> Unit
            }
        }
    }

    private fun handleTransportClosed() {
        when (_state.value.phase) {
            LiveSessionPhase.CONNECTING -> {
                handshake?.completeExceptionally(IllegalStateException("Aborted"))
            }
            LiveSessionPhase.ACTIVE -> {
                backgroundFailure("Connection lost")
            }
            LiveSessionPhase.CLOSING -> {
                closedSignal?.completeExceptionally(IllegalStateException("Aborted"))
            }
            LiveSessionPhase.IDLE, LiveSessionPhase.FAILED -> Unit
        }
    }

    private fun handleEvent(text: String, triggerEpoch: Int, socket: WebSocket) {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        when (root.optString("type")) {
            "session.started" -> {
                handshake?.complete(Unit)
            }
            "session.output_audio.delta" -> {
                val encoded = root.optString("delta")
                if (encoded.isEmpty()) return
                val pcm = try {
                    Base64.getDecoder().decode(encoded)
                } catch (_: Exception) {
                    return
                }
                if (pcm.isEmpty()) return
                if (pcm.size > LiveProtocol.MAX_PCM_BYTES || pcm.size % 2 != 0) {
                    backgroundFailure("Invalid live audio data")
                    return
                }
                val live = resources ?: return
                if (triggerEpoch != currentEpoch) return
                if (socket !== activeSocket) return
                val inbound = live.inbound ?: return
                if (!inbound.trySend(pcm).isSuccess &&
                    _state.value.phase == LiveSessionPhase.ACTIVE
                ) {
                    backgroundFailure("Audio overload")
                }
            }
            "session.input_transcript.delta" -> {
                transcripts.appendInput(root.optString("delta"))
                publishTranscripts()
            }
            "session.output_transcript.delta" -> {
                transcripts.appendOutput(root.optString("delta"))
                publishTranscripts()
            }
            "session.delegation.created" -> {
                val delegation = root.optJSONObject("delegation")
                val delegationId = delegation?.optString("id") ?: ""
                val responseId = root.optString("response_id")
                    .ifEmpty { root.optString("responseId") }
                if (delegationId.isNotEmpty() && responseId.isNotEmpty()) {
                    tracker.onResponseCreated(delegationId, responseId)
                    checkQuota()
                }
            }
            "response.event" -> {
                handleResponseEnvelope(
                    envelope = root,
                    triggerEpoch = triggerEpoch,
                    socket = socket,
                )
            }
            "session.usage.updated" -> {
                val seconds = root.optJSONObject("usage")?.optInt("seconds", -1) ?: -1
                if (seconds >= 0) lastUsageSeconds = seconds
            }
            "session.closed" -> {
                val reason = sanitizedCloseReason(root.optString("reason"))
                val usageSeconds = root.optJSONObject("usage")?.optInt("seconds", -1)
                    ?.takeIf { it >= 0 }
                    ?: lastUsageSeconds
                if (usageSeconds != null) lastUsageSeconds = usageSeconds
                if (_state.value.phase == LiveSessionPhase.CLOSING) {
                    closedSignal?.complete(Unit)
                } else if (_state.value.phase == LiveSessionPhase.ACTIVE ||
                    _state.value.phase == LiveSessionPhase.CONNECTING
                ) {
                    handshake?.completeExceptionally(IllegalStateException("Aborted"))
                    backgroundFailure("Live session ended: $reason")
                }
            }
            "error" -> {
                val detail = root.optJSONObject("error")
                val code = sanitizedErrorCode(detail?.optString("code") ?: "")
                if (_state.value.phase == LiveSessionPhase.CONNECTING) {
                    val reason = if (code.isNotEmpty()) {
                        "OpenAI rejected live session startup ($code)"
                    } else {
                        "OpenAI rejected live session startup"
                    }
                    handshake?.completeExceptionally(StartupRejected(reason))
                } else if (_state.value.phase == LiveSessionPhase.ACTIVE) {
                    val message = if (code.isNotEmpty()) {
                        "Live session warning ($code)"
                    } else {
                        "Live session warning"
                    }
                    _state.update { it.copy(message = message) }
                }
            }
            else -> Unit
        }
    }

    private fun handleResponseEnvelope(envelope: JSONObject, triggerEpoch: Int, socket: WebSocket) {
        if (socket !== activeSocket) return
        val delegationId = envelope.optString("delegation_id")
        val nested = envelope.optJSONObject("event") ?: return
        when (nested.optString("type")) {
            "response.created" -> {
                val responseId = nestedResponseId(nested)
                if (responseId.isNotEmpty()) {
                    tracker.onResponseCreated(delegationId, responseId)
                    checkQuota()
                }
            }
            "response.output_item.done" -> {
                val item = nested.optJSONObject("item") ?: return
                val accepted = tracker.onOutputItemDone(
                    delegationId = delegationId,
                    responseId = nestedResponseId(nested),
                    itemType = item.optString("type"),
                    callId = item.optString("call_id"),
                    name = item.optString("name"),
                    argumentsRaw = argumentsText(item),
                )
                checkQuota()
                if (!accepted) return
            }
            "response.completed" -> {
                val calls = tracker.drainForCompletion(delegationId, nestedResponseId(nested))
                checkQuota()
                if (calls.isEmpty()) return
                if (triggerEpoch != currentEpoch) return
                if (!canWork()) return
                val runner = toolScope ?: scope
                runner.launch(Dispatchers.Default) { executeBatch(calls, triggerEpoch) }
            }
            "response.failed", "response.incomplete" -> {
                tracker.onResponseEnded(delegationId, nestedResponseId(nested))
                checkQuota()
            }
            else -> Unit
        }
    }

    private fun checkQuota() {
        if (tracker.quotaExceeded) {
            backgroundFailure("Live session tool limit reached")
        }
    }

    private fun nestedResponseId(nested: JSONObject): String {
        val direct = nested.optString("response_id")
        if (direct.isNotEmpty()) return direct
        return nested.optJSONObject("response")?.optString("id") ?: ""
    }

    private fun argumentsText(item: JSONObject): String {
        return when (val value = item.opt("arguments")) {
            is JSONObject -> value.toString()
            is String -> value
            else -> ""
        }
    }

    private fun sanitizedCloseReason(raw: String): String {
        return when (raw) {
            "close_requested", "expired", "content", "remote_hangup", "connection_lost" -> raw
            else -> "ended"
        }
    }

    private fun sanitizedErrorCode(raw: String): String {
        if (raw.isEmpty() || raw.length > 64) return ""
        return if (raw.all { it.isLowerCase() || it.isDigit() || it == '_' }) raw else ""
    }

    private class SessionAborted : Exception()
    private class StartupRejected(val reason: String) : Exception(reason)

    private data class LiveResources(
        val socket: WebSocket,
        val client: OkHttpClient,
        val stream: LiveAudioStream?,
        val outbound: Channel<ByteArray>?,
        val inbound: Channel<ByteArray>?,
        val scope: CoroutineScope,
    )
}
