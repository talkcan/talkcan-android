package io.talkcan.live

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configuration for one GPT-Live voice session.
 *
 * The voice model is always `gpt-live-1`; [backendModel] selects the Responses delegation
 * backend independently of the voice model. Audio is mono signed PCM16 little-endian at
 * 24 kHz in both directions with a single shared format fixed at startup.
 */
public data class LiveSessionConfiguration(
    val backendModel: String = "gpt-5.6-luna",
    val voice: String = "marin",
    val instructions: String = DEFAULT_INSTRUCTIONS,
) {
    public companion object {
        public const val DEFAULT_INSTRUCTIONS: String =
            "Be concise. Speak only what the listener needs to hear. " +
                "Delegate requests that need app actions or current information to the " +
                "backend tools, and wait for their results before answering."
    }
}

/** Lifecycle phase of one [GptLiveSession]. */
public enum class LiveSessionPhase {
    IDLE,
    CONNECTING,
    ACTIVE,
    CLOSING,
    FAILED,
}

/**
 * Observable session state.
 *
 * [message] carries short sanitized diagnostics only: never API keys, never raw server
 * payloads. Transcripts are bounded tails of the spoken conversation for UI display.
 */
public data class LiveSessionState(
    val phase: LiveSessionPhase,
    val message: String? = null,
    val inputTranscript: String = "",
    val outputTranscript: String = "",
)

/**
 * Full-duplex audio endpoint for one live session.
 *
 * Audio is mono signed PCM16 little-endian at 24 kHz. [open] is called at most once per
 * session, only after the server acknowledges `session.started`. [LiveAudioStream.read] and
 * [LiveAudioStream.write] run concurrently on separate coroutines; [LiveAudioStream.close]
 * must unblock any in-flight read or write promptly.
 */
public interface LiveAudioDevice {
    public suspend fun open(): LiveAudioStream
}

/**
 * One open full-duplex audio stream.
 *
 * [read] fills [buffer] with captured bytes (silence included, never gated) and returns
 * the number of bytes read, which is always positive while capture is live. After [close]
 * a blocked or subsequent [read] returns a non-positive value or throws, and a blocked or
 * subsequent [write] returns or throws; it never blocks forever. [close] is idempotent.
 */
public interface LiveAudioStream {
    public suspend fun read(buffer: ByteArray): Int
    public suspend fun write(bytes: ByteArray)
    public suspend fun close()
}

/**
 * Application-owned tools exposed to the Responses delegation backend.
 *
 * [definitions] returns the `function` tool entries registered in
 * `delegation.responses.tools` at `session.start`. [execute] runs the authorized operation
 * for [name] with the given arguments and returns its result as a JSON object; it throws
 * for unknown or denied tools. The session dispatches each completed server-side call at
 * most once, so implementations do not need their own duplicate suppression.
 */
public interface LiveAppTools {
    public fun definitions(): JSONArray
    public suspend fun execute(name: String, arguments: JSONObject): JSONObject
}
