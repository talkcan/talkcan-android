package io.talkcan.lua.kernel

import io.talkcan.lua.HostOperationClaim
import io.talkcan.lua.HostOperationKind
import io.talkcan.lua.LuaCallbackHandle
import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaOperationId
import io.talkcan.lua.LuaSpawnAdmission
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaValue
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.After
import org.junit.Test

/**
 * Fixture conformance (inventory category C, fixture-driven), ported
 * one-for-one from `debug_fixture.rs` and `diagnostics_fixture.rs`.
 *
 * Executes the real channel packages from the existing JVM test resources
 * (`debug-channel`, `diagnostics-channel`) through the program-image path —
 * no mocks, no source-text assertions.
 */
internal class KotlinLuaKernelFixtureConformanceTest {

    private val support = KotlinLuaKernelConformanceSupport()
    private val bridge get() = support.bridge

    private val accepting = object : LuaSpawnAdmission {
        override fun admitTask(coroutineId: Long): Int = 0
    }

    @After
    fun closeStates() = support.closeAll()

    // ------------------------------------------------------------------
    // Local helpers
    // ------------------------------------------------------------------

    private fun pluginSource(resourcePath: String): String {
        val stream = javaClass.getResourceAsStream(resourcePath)
            ?: throw AssertionError("test resource missing on classpath: $resourcePath")
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "lua/plugin.lua") {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        throw AssertionError("$resourcePath omitted lua/plugin.lua")
    }

    private fun loadFixture(resourcePath: String): LuaStateHandle {
        val source = pluginSource(resourcePath)
        val state = support.createState()
        support.loadProgramImageOk(state, "plugin", mapOf("plugin" to source))
        return state
    }

    private fun str(value: String): LuaValue = LuaValue.StringValue(value)
    private fun int(value: Long): LuaValue = LuaValue.Integer(value)

    private fun lv(vararg pairs: Pair<String, LuaValue>): LuaValue.Map =
        LuaValue.Map(linkedMapOf(*pairs))

    private fun startupOk(state: LuaStateHandle, values: LuaValue = LuaValue.Map(emptyMap())): LuaKernelOutcome.Completed {
        val config = lv("schema_version" to int(1), "values" to values)
        return support.assertCompleted(
            bridge.invokeStartupCallback(state, LuaCallbackHandle(state, "startup"), config, accepting),
            "startup",
        )
    }

    private fun startupConfigOk(state: LuaStateHandle, config: LuaValue): LuaKernelOutcome.Completed =
        support.assertCompleted(
            bridge.invokeStartupCallback(state, LuaCallbackHandle(state, "startup"), config, accepting),
            "startup",
        )

    private fun resultJson(outcome: LuaKernelOutcome.Completed): JSONObject =
        support.resultObject(outcome)

    private fun assertNoError(outcome: LuaKernelOutcome.Completed, context: String) {
        val value = resultJson(outcome)
        if (value.has("error")) {
            throw AssertionError("$context must not produce error: ${outcome.value}")
        }
    }

    private fun assertErrorCode(outcome: LuaKernelOutcome.Completed, code: String, context: String) {
        val value = resultJson(outcome)
        val actual = value.optJSONObject("error")?.optString("code")
        if (actual != code) {
            throw AssertionError("$context: expected error code $code but was $actual (${outcome.value})")
        }
    }

    private fun invokeInput(state: LuaStateHandle, args: LuaValue): LuaKernelOutcome =
        bridge.invokeInputCallback(state, LuaCallbackHandle(state, "handle_input"), args, "", accepting)

    private fun invokeSos(state: LuaStateHandle, args: LuaValue): LuaKernelOutcome =
        bridge.invokeSosCallback(state, LuaCallbackHandle(state, "handle_sos"), args, accepting)

    private fun operation(handle: LuaStateHandle, yielded: LuaKernelOutcome.Yielded): LuaOperationHandle =
        LuaOperationHandle(handle, LuaCoroutineId(yielded.coroutineId), LuaOperationId(yielded.operationId))

    private fun requestId(yielded: LuaKernelOutcome.Yielded): Long =
        (yielded.value ?: throw AssertionError("yielded outcome carries no request identity: $yielded")).toLong()

    private fun claimAdmitted(state: LuaStateHandle, yielded: LuaKernelOutcome.Yielded): HostOperationClaim.Admitted {
        val claim = bridge.claimHostOperation(state, requestId(yielded))
        return claim as? HostOperationClaim.Admitted ?: throw AssertionError("claim rejected: $claim")
    }

    private fun assertYielded(outcome: LuaKernelOutcome, context: String): LuaKernelOutcome.Yielded {
        if (outcome !is LuaKernelOutcome.Yielded) {
            throw AssertionError("$context: expected Yielded but was $outcome")
        }
        return outcome
    }

    private fun captureMetadata(durationMs: Long): LuaValue.Map =
        LuaValue.Map(
            mapOf(
                "duration_ms" to int(durationMs),
                "sample_rate" to int(16_000),
                "channels" to int(1),
            ),
        )

    // ------------------------------------------------------------------
    // debug_fixture.rs
    // ------------------------------------------------------------------

    @Test
    fun debug_fixture_real_state_engine_covers_dependency_sets_sequences_constants_and_errors() {
        val modes = listOf("ECHO", "DELAYED_ECHO", "STT", "TTS", "STT_TTS")
        val capabilities = listOf("audio.transcription", "audio.synthesis", "audio.playback")
        val state = loadFixture(
            "/debug-channel/talkcan-channel.zip",
        )

        fun startup(mode: String) = startupOk(state, lv("mode" to str(mode)))

        fun readiness(available: List<String>): Boolean {
            val capabilityMap = LuaValue.Map(
                capabilities.associateWith { capability ->
                    if (available.contains(capability)) str("available") else str("unavailable")
                },
            )
            val outcome = support.invokeCallbackOk(state, "handle_readiness", lv("capabilities" to capabilityMap))
            return resultJson(outcome).optBoolean("ready", false)
        }

        // Every startup mode is checked against every subset of the three declared capabilities.
        for (mode in modes) {
            startup(mode)
            for (mask in 0 until (1 shl capabilities.size)) {
                val available = capabilities.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                val expected = when (mode) {
                    "ECHO", "DELAYED_ECHO" -> available.contains("audio.playback")
                    "STT" -> available.contains("audio.transcription")
                    "TTS" -> available.contains("audio.synthesis") && available.contains("audio.playback")
                    "STT_TTS" -> available.size == 3
                    else -> false
                }
                val actual = readiness(available)
                if (actual != expected) {
                    throw AssertionError("readiness mode=$mode available=$available: expected $expected but was $actual")
                }
                val statusOutcome = support.invokeCallbackOk(state, "handle_readiness", lv("capabilities" to LuaValue.Map(emptyMap())))
                if (resultJson(statusOutcome).optString("status") != mode) {
                    throw AssertionError("readiness status for mode $mode: ${statusOutcome.value}")
                }
            }
        }

        // Invalid capture events are rejected before any host operation is claimed:
        // the outcome completes (never yields), so no request can be claimed.
        startup("ECHO")
        val invalid = support.invokeCallbackOk(
            state,
            "handle_input",
            lv("event" to str("wrong")),
        )
        assertErrorCode(invalid, "E_INVALID_ARGUMENT", "invalid capture event")

        fun debugInput(token: String): LuaKernelOutcome =
            bridge.invokeInputCallback(
                state,
                LuaCallbackHandle(state, "handle_input"),
                lv(
                    "event" to str("capture"),
                    "session" to str("session-1"),
                    "metadata" to LuaValue.Map(
                        mapOf(
                            "duration_ms" to int(1),
                            "sample_rate" to int(16_000),
                            "channels" to int(1),
                            "pcm_bytes" to int(32),
                        ),
                    ),
                ),
                token,
                accepting,
            )

        /// Drive a Debug input to completion: while the package yields host
        /// operations, claim each one and resume by kind. Returns the terminal
        /// outcome and the ordered admitted claims.
        fun driveInput(
            initial: LuaKernelOutcome,
            transcript: String,
            synthToken: String,
        ): Pair<LuaKernelOutcome.Completed, List<HostOperationClaim.Admitted>> {
            var outcome = initial
            val claims = mutableListOf<HostOperationClaim.Admitted>()
            for (step in 0 until 8) {
                val yielded = outcome as? LuaKernelOutcome.Yielded
                    ?: return support.assertCompleted(outcome, "debug input terminal") to claims
                val admitted = claimAdmitted(state, yielded)
                claims.add(admitted)
                val resumeValue = when (admitted.kind) {
                    HostOperationKind.TRANSCRIBE -> transcript
                    HostOperationKind.SYNTHESIZE -> synthToken
                    HostOperationKind.PLAYBACK -> "ignored"
                    else -> throw AssertionError("unexpected host operation kind: ${admitted.kind}")
                }
                outcome = bridge.resume(operation(state, yielded), true, resumeValue, accepting)
            }
            throw AssertionError("Debug fixture operation did not terminate: $outcome")
        }

        // ECHO schedules captured audio immediately (one PLAYBACK claim, zero delay).
        startup("ECHO")
        val echoInitial = debugInput("echo-token")
        assertYielded(echoInitial, "ECHO should yield its playback operation")
        val (echoDone, echoOps) = driveInput(echoInitial, "", "synth")
        val echoResult = resultJson(echoDone)
        if (echoResult.length() != 1 || !echoResult.optBoolean("ok", false)) {
            throw AssertionError("ECHO did not complete with exactly {\"ok\":true}: ${echoDone.value}")
        }
        if (echoOps.size != 1) {
            throw AssertionError("ECHO must claim exactly one host operation: $echoOps")
        }
        if (echoOps[0].kind != HostOperationKind.PLAYBACK) {
            throw AssertionError("ECHO claim kind: ${echoOps[0].kind}")
        }
        if (echoOps[0].audioToken != "echo-token") {
            throw AssertionError("ECHO audioToken: ${echoOps[0].audioToken}")
        }
        if (echoOps[0].delaySeconds != 0.0) {
            throw AssertionError("ECHO delay: ${echoOps[0].delaySeconds}")
        }

        // DELAYED_ECHO retains both captures and claims them FIFO at exactly five seconds.
        startup("DELAYED_ECHO")
        val (_, delayedFirst) = driveInput(debugInput("first-token"), "", "synth")
        val (_, delayedSecond) = driveInput(debugInput("second-token"), "", "synth")
        val delayed = delayedFirst + delayedSecond
        if (delayed.size != 2) {
            throw AssertionError("DELAYED_ECHO must claim two playback operations: $delayed")
        }
        if (!delayed.all { it.kind == HostOperationKind.PLAYBACK }) {
            throw AssertionError("DELAYED_ECHO claim kinds: ${delayed.map { it.kind }}")
        }
        if (!delayed.all { it.delaySeconds == 5.0 }) {
            throw AssertionError("DELAYED_ECHO delays: ${delayed.map { it.delaySeconds }}")
        }
        if (delayed[0].audioToken != "first-token" || delayed[1].audioToken != "second-token") {
            throw AssertionError("DELAYED_ECHO FIFO order: ${delayed.map { it.audioToken }}")
        }

        // STT claims one transcription (no playback) and logs the exact transcript.
        startup("STT")
        val (sttDone, sttOps) = driveInput(debugInput("stt-token"), "exact transcript", "synth")
        if (resultJson(sttDone).optBoolean("ok", false).not()) {
            throw AssertionError("STT did not complete ok: ${sttDone.value}")
        }
        if (sttOps.size != 1) {
            throw AssertionError("STT must claim exactly one host operation: $sttOps")
        }
        if (sttOps[0].kind != HostOperationKind.TRANSCRIBE) {
            throw AssertionError("STT claim kind: ${sttOps[0].kind}")
        }
        if (sttOps[0].audioToken != "stt-token") {
            throw AssertionError("STT audioToken: ${sttOps[0].audioToken}")
        }
        if (sttOps.any { it.kind == HostOperationKind.PLAYBACK }) {
            throw AssertionError("STT must not claim playback: $sttOps")
        }
        val sttLogs = sttDone.logs ?: throw AssertionError("STT transcript log missing: $sttDone")
        if (!sttLogs.any { it.contains("exact transcript") }) {
            throw AssertionError("STT transcript not logged: $sttLogs")
        }

        // TTS uses exact synthesis constants, then schedules the synthesized audio.
        startup("TTS")
        val (ttsDone, ttsOps) = driveInput(debugInput("tts-token"), "", "synth")
        if (resultJson(ttsDone).optBoolean("ok", false).not()) {
            throw AssertionError("TTS did not complete ok: ${ttsDone.value}")
        }
        if (ttsOps.size != 2) {
            throw AssertionError("TTS must claim synthesis then playback: $ttsOps")
        }
        if (ttsOps[0].kind != HostOperationKind.SYNTHESIZE) {
            throw AssertionError("TTS first claim kind: ${ttsOps[0].kind}")
        }
        if (ttsOps[0].text != "Debug synthesis test") {
            throw AssertionError("TTS synthesis text: ${ttsOps[0].text}")
        }
        if (ttsOps[0].language != "en") {
            throw AssertionError("TTS synthesis language: ${ttsOps[0].language}")
        }
        if (ttsOps[0].voice != "default") {
            throw AssertionError("TTS synthesis voice: ${ttsOps[0].voice}")
        }
        if (ttsOps[0].speed != 1.0) {
            throw AssertionError("TTS synthesis speed: ${ttsOps[0].speed}")
        }
        if (ttsOps[1].kind != HostOperationKind.PLAYBACK) {
            throw AssertionError("TTS second claim kind: ${ttsOps[1].kind}")
        }
        if (ttsOps[1].delaySeconds != 0.0) {
            throw AssertionError("TTS playback delay: ${ttsOps[1].delaySeconds}")
        }

        // STT_TTS chains the exact transcript into synthesis and then zero-delay playback.
        startup("STT_TTS")
        val (chainDone, chainOps) = driveInput(debugInput("stt-tts-token"), "chained transcript", "synth")
        if (resultJson(chainDone).optBoolean("ok", false).not()) {
            throw AssertionError("STT_TTS did not complete ok: ${chainDone.value}")
        }
        if (chainOps.size != 3) {
            throw AssertionError("STT_TTS must claim transcribe, synthesize, playback: $chainOps")
        }
        if (chainOps[0].kind != HostOperationKind.TRANSCRIBE) {
            throw AssertionError("STT_TTS first claim kind: ${chainOps[0].kind}")
        }
        if (chainOps[1].kind != HostOperationKind.SYNTHESIZE) {
            throw AssertionError("STT_TTS second claim kind: ${chainOps[1].kind}")
        }
        if (chainOps[1].text != "chained transcript") {
            throw AssertionError("STT_TTS synthesis text: ${chainOps[1].text}")
        }
        if (chainOps[2].kind != HostOperationKind.PLAYBACK) {
            throw AssertionError("STT_TTS third claim kind: ${chainOps[2].kind}")
        }
        if (chainOps[2].delaySeconds != 0.0) {
            throw AssertionError("STT_TTS playback delay: ${chainOps[2].delaySeconds}")
        }

        // Every mode normalizes a host failure without fallback or substitution.
        for (mode in modes) {
            startup(mode)
            val failed = assertYielded(debugInput("failure-token"), "$mode should yield its first operation")
            val failedDone = support.assertCompleted(
                bridge.resume(operation(state, failed), false, "E_BUSY", accepting),
                "$mode failure resume",
            )
            val failedValue = resultJson(failedDone)
            if (failedValue.optJSONObject("error")?.optString("code") != "E_BUSY") {
                throw AssertionError("$mode failure must normalize to E_BUSY: ${failedDone.value}")
            }
            val detail = failedValue.optJSONObject("error")?.optString("detail").orEmpty()
            if (detail.isEmpty()) {
                throw AssertionError("$mode failure must carry a normalized detail: ${failedDone.value}")
            }
        }

        support.assertClosed(support.closeState(state), "close debug state")
    }

    // ------------------------------------------------------------------
    // diagnostics_fixture.rs — startup configuration validation
    // ------------------------------------------------------------------

    @Test
    fun diagnostics_valid_configuration_succeeds() {
        val state = loadFixture("/diagnostics-channel/talkcan-channel.zip")
        val outcome = startupOk(state)
        assertNoError(outcome, "valid config")
        support.assertClosed(support.closeState(state), "close")
    }

    @Test
    fun diagnostics_invalid_configuration_rejected() {
        val state = loadFixture("/diagnostics-channel/talkcan-channel.zip")
        val cases = listOf<LuaValue>(
            lv("schema_version" to int(2), "values" to LuaValue.Map(emptyMap())),
            lv("schema_version" to int(1), "values" to lv("extra" to LuaValue.Bool(true))),
            lv("schema_version" to int(1)),
            lv("values" to LuaValue.Map(emptyMap())),
            LuaValue.Map(emptyMap()),
            LuaValue.Nil,
        )
        for (config in cases) {
            val outcome = startupConfigOk(state, config)
            assertErrorCode(outcome, "E_CONFIGURATION", "invalid config $config")
        }
        support.assertClosed(support.closeState(state), "close")
    }

    @Test
    fun diagnostics_schema_version_mismatch_is_configuration_error() {
        val state = loadFixture("/diagnostics-channel/talkcan-channel.zip")
        val outcome = startupConfigOk(state, lv("schema_version" to int(2), "values" to LuaValue.Map(emptyMap())))
        assertErrorCode(outcome, "E_CONFIGURATION", "schema_version mismatch")
        support.assertClosed(support.closeState(state), "close")
    }

    @Test
    fun diagnostics_nonempty_values_configuration_rejected() {
        val state = loadFixture("/diagnostics-channel/talkcan-channel.zip")
        val outcome = startupConfigOk(
            state,
            lv("schema_version" to int(1), "values" to lv("unexpected" to str("field"))),
        )
        assertErrorCode(outcome, "E_CONFIGURATION", "nonempty values")
        support.assertClosed(support.closeState(state), "close")
    }

    // ------------------------------------------------------------------
    // diagnostics_fixture.rs — readiness
    // ------------------------------------------------------------------

    @Test
    fun diagnostics_readiness_returns_ready() {
        val state = loadFixture("/diagnostics-channel/talkcan-channel.zip")
        startupOk(state)
        val outcome = support.invokeCallbackOk(state, "handle_readiness")
        if (!resultJson(outcome).optBoolean("ready", false)) {
            throw AssertionError("readiness must return ready=true: ${outcome.value}")
        }
        support.assertClosed(support.closeState(state), "close")
    }

    // ------------------------------------------------------------------
    // diagnostics_fixture.rs — input capture
    // ------------------------------------------------------------------

    @Test
    fun diagnostics_input_valid_capture_accepted() {
        val state = loadFixture("/diagnostics-channel/talkcan-channel.zip")
        startupOk(state)
        val outcome = support.assertCompleted(
            invokeInput(
                state,
                lv(
                    "event" to str("capture"),
                    "session" to str("session-1"),
                    "metadata" to captureMetadata(500),
                ),
            ),
            "valid capture",
        )
        val value = resultJson(outcome)
        if (!value.optBoolean("ok", false) || value.has("error")) {
            throw AssertionError("valid capture must return ok: ${outcome.value}")
        }
        support.assertClosed(support.closeState(state), "close")
    }

    @Test
    fun diagnostics_input_malformed_metadata_rejected() {
        val state = loadFixture("/diagnostics-channel/talkcan-channel.zip")
        startupOk(state)
        val metadata = captureMetadata(500)
        val cases = listOf(
            // Missing metadata
            lv("event" to str("capture"), "session" to str("session-1")),
            // Metadata with wrong type
            lv("event" to str("capture"), "session" to str("session-1"), "metadata" to str("string")),
            // Negative duration
            lv(
                "event" to str("capture"),
                "session" to str("session-1"),
                "metadata" to LuaValue.Map(metadata.pairs + ("duration_ms" to int(-1))),
            ),
            // Zero sample rate
            lv(
                "event" to str("capture"),
                "session" to str("session-1"),
                "metadata" to LuaValue.Map(metadata.pairs + ("sample_rate" to int(0))),
            ),
            // Wrong event
            lv("event" to str("wrong"), "session" to str("session-1"), "metadata" to metadata),
            // Missing session
            lv("event" to str("capture"), "metadata" to metadata),
            // Non-numeric channels
            lv(
                "event" to str("capture"),
                "session" to str("session-1"),
                "metadata" to LuaValue.Map(metadata.pairs + ("channels" to str("stereo"))),
            ),
        )
        for (input in cases) {
            val outcome = support.assertCompleted(invokeInput(state, input), "malformed input $input")
            val value = resultJson(outcome)
            val code = value.optJSONObject("error")?.optString("code").orEmpty()
            if (code != "E_INPUT" && code != "E_INPUT_MALFORMED") {
                throw AssertionError("malformed input must produce E_INPUT or E_INPUT_MALFORMED: input=$input output=${outcome.value}")
            }
        }
        support.assertClosed(support.closeState(state), "close")
    }

    @Test
    fun diagnostics_input_excessive_values_are_bounded() {
        val state = loadFixture("/diagnostics-channel/talkcan-channel.zip")
        startupOk(state)
        // Duration exceeds 86400000 ms (24 hours)
        var outcome = support.assertCompleted(
            invokeInput(
                state,
                lv(
                    "event" to str("capture"),
                    "session" to str("session-1"),
                    "metadata" to captureMetadata(86_400_001),
                ),
            ),
            "excessive duration",
        )
        assertErrorCode(outcome, "E_INPUT_MALFORMED", "excessive duration")

        // Sample rate exceeds 384000
        outcome = support.assertCompleted(
            invokeInput(
                state,
                lv(
                    "event" to str("capture"),
                    "session" to str("session-1"),
                    "metadata" to LuaValue.Map(
                        mapOf(
                            "duration_ms" to int(500),
                            "sample_rate" to int(384_001),
                            "channels" to int(1),
                        ),
                    ),
                ),
            ),
            "excessive sample rate",
        )
        assertErrorCode(outcome, "E_INPUT_MALFORMED", "excessive sample rate")

        // Channel count exceeds 32
        outcome = support.assertCompleted(
            invokeInput(
                state,
                lv(
                    "event" to str("capture"),
                    "session" to str("session-1"),
                    "metadata" to LuaValue.Map(
                        mapOf(
                            "duration_ms" to int(500),
                            "sample_rate" to int(16_000),
                            "channels" to int(33),
                        ),
                    ),
                ),
            ),
            "excessive channels",
        )
        assertErrorCode(outcome, "E_INPUT_MALFORMED", "excessive channels")
        support.assertClosed(support.closeState(state), "close")
    }

    @Test
    fun diagnostics_input_nan_is_rejected() {
        val state = loadFixture("/diagnostics-channel/talkcan-channel.zip")
        startupOk(state)
        // null sample rate stands in for the Rust NaN literal at the JSON boundary.
        val metadata = LuaValue.Map(
            mapOf(
                "duration_ms" to int(500),
                "sample_rate" to LuaValue.Nil,
                "channels" to int(1),
            ),
        )
        val outcome = support.assertCompleted(
            invokeInput(state, lv("event" to str("capture"), "session" to str("session-1"), "metadata" to metadata)),
            "null sample rate",
        )
        val value = resultJson(outcome)
        if (!value.has("error") || value.isNull("error")) {
            throw AssertionError("NaN/null sample rate must be rejected: ${outcome.value}")
        }
        support.assertClosed(support.closeState(state), "close")
    }

    // ------------------------------------------------------------------
    // diagnostics_fixture.rs — SOS dispatch
    // ------------------------------------------------------------------

    @Test
    fun diagnostics_sos_dispatch_completes() {
        val state = loadFixture("/diagnostics-channel/talkcan-channel.zip")
        startupOk(state)
        val outcome = support.assertCompleted(invokeSos(state, lv("event" to str("sos"))), "SOS dispatch")
        assertNoError(outcome, "valid SOS")
        support.assertClosed(support.closeState(state), "close")
    }

    @Test
    fun diagnostics_sos_unexpected_event_rejected() {
        val state = loadFixture("/diagnostics-channel/talkcan-channel.zip")
        startupOk(state)
        val outcome = support.assertCompleted(invokeSos(state, lv("event" to str("something_else"))), "unexpected SOS")
        assertErrorCode(outcome, "E_SOS", "unexpected SOS event")
        support.assertClosed(support.closeState(state), "close")
    }

    // ------------------------------------------------------------------
    // diagnostics_fixture.rs — lifecycle
    // ------------------------------------------------------------------

    @Test
    fun diagnostics_lifecycle_ready_accepted() {
        val state = loadFixture("/diagnostics-channel/talkcan-channel.zip")
        startupOk(state)
        val outcome = support.invokeCallbackOk(state, "handle_lifecycle", lv("event" to str("ready")))
        assertNoError(outcome, "valid lifecycle")
        support.assertClosed(support.closeState(state), "close")
    }

    @Test
    fun diagnostics_lifecycle_unexpected_event_rejected() {
        val state = loadFixture("/diagnostics-channel/talkcan-channel.zip")
        startupOk(state)
        val outcome = support.invokeCallbackOk(state, "handle_lifecycle", lv("event" to str("invalid")))
        assertErrorCode(outcome, "E_LIFECYCLE", "unexpected lifecycle event")
        support.assertClosed(support.closeState(state), "close")
    }
}
