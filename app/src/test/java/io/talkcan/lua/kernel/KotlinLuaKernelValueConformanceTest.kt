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
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Test

/**
 * Value/codec conformance (inventory category C minus the fixture packages),
 * ported one-for-one from the Rust suite: value normalization and invalid
 * whole-value rejection, JSON module behavior, work-value envelopes,
 * profile/secret/HTTP envelopes, resolver envelopes, structured logs, and
 * failure normalization.
 */
internal class KotlinLuaKernelValueConformanceTest {

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

    private fun str(value: String): LuaValue = LuaValue.StringValue(value)
    private fun int(value: Long): LuaValue = LuaValue.Integer(value)

    private fun lv(vararg pairs: Pair<String, LuaValue>): LuaValue.Map =
        LuaValue.Map(linkedMapOf(*pairs))

    private fun assertYielded(outcome: LuaKernelOutcome, context: String): LuaKernelOutcome.Yielded {
        if (outcome !is LuaKernelOutcome.Yielded) {
            throw AssertionError("$context: expected Yielded but was $outcome")
        }
        return outcome
    }

    private fun operation(handle: LuaStateHandle, yielded: LuaKernelOutcome.Yielded): LuaOperationHandle =
        LuaOperationHandle(handle, LuaCoroutineId(yielded.coroutineId), LuaOperationId(yielded.operationId))

    private fun requestId(yielded: LuaKernelOutcome.Yielded): Long =
        (yielded.value ?: throw AssertionError("yielded outcome carries no request identity: $yielded")).toLong()

    private fun claimAdmitted(state: LuaStateHandle, yielded: LuaKernelOutcome.Yielded, expected: HostOperationKind): HostOperationClaim.Admitted {
        val claim = bridge.claimHostOperation(state, requestId(yielded))
        val admitted = claim as? HostOperationClaim.Admitted ?: throw AssertionError("claim rejected: $claim")
        if (admitted.kind != expected) {
            throw AssertionError("expected $expected claim but was ${admitted.kind}")
        }
        return admitted
    }

    private fun invokeInput(state: LuaStateHandle, args: LuaValue, token: String = "tok"): LuaKernelOutcome =
        bridge.invokeInputCallback(state, LuaCallbackHandle(state, "handle_input"), args, token, accepting)

    private fun invokeSos(state: LuaStateHandle, args: LuaValue): LuaKernelOutcome =
        bridge.invokeSosCallback(state, LuaCallbackHandle(state, "handle_sos"), args, accepting)

    private fun captureEvent(): LuaValue =
        LuaValue.Map(
            mapOf(
                "metadata" to LuaValue.Map(
                    mapOf(
                        "duration_ms" to int(1),
                        "sample_rate" to int(16_000),
                        "channels" to int(1),
                        "pcm_bytes" to int(32),
                    ),
                ),
            ),
        )

    private fun resultJson(outcome: LuaKernelOutcome.Completed): JSONObject =
        support.resultObject(outcome)

    private fun parseJson(text: String): Any = JSONTokener(text).nextValue()

    /** Structural JSON equality with lenient (double-based) number comparison. */
    private fun assertJsonEquals(expected: Any?, actual: Any?, context: String) {
        fun cmp(e: Any?, a: Any?) {
            when {
                (e == null || e === JSONObject.NULL) && (a == null || a === JSONObject.NULL) -> Unit
                e is JSONObject && a is JSONObject -> {
                    val eKeys = e.keys().asSequence().toSortedSet()
                    val aKeys = a.keys().asSequence().toSortedSet()
                    if (eKeys != aKeys) {
                        throw AssertionError("$context: keys differ: $eKeys vs $aKeys")
                    }
                    for (key in eKeys) {
                        cmp(e.get(key), a.get(key))
                    }
                }
                e is JSONArray && a is JSONArray -> {
                    if (e.length() != a.length()) {
                        throw AssertionError("$context: array length ${e.length()} vs ${a.length()}")
                    }
                    for (index in 0 until e.length()) {
                        cmp(e.get(index), a.get(index))
                    }
                }
                e is Number && a is Number ->
                    if (e.toDouble() != a.toDouble()) {
                        throw AssertionError("$context: number $e vs $a")
                    }
                e is Boolean && a is Boolean ->
                    if (e != a) {
                        throw AssertionError("$context: boolean $e vs $a")
                    }
                e is String && a is String ->
                    if (e != a) {
                        throw AssertionError("$context: string \"$e\" vs \"$a\"")
                    }
                else -> throw AssertionError("$context: type mismatch ${e?.javaClass} ($e) vs ${a?.javaClass} ($a)")
            }
        }
        cmp(expected, actual)
    }

    /** Canonical rendering of a tagged WorkValue with map pair order normalized. */
    private fun canonicalWorkValue(node: Any?): String = when {
        node == null || node === JSONObject.NULL -> "null"
        node is JSONObject -> {
            if (node.optString("t") == "map" && node.opt("v") is JSONArray) {
                val pairs = node.getJSONArray("v")
                (0 until pairs.length())
                    .map { index ->
                        val pair = pairs.getJSONArray(index)
                        pair.getString(0) to canonicalWorkValue(pair.get(1))
                    }
                    .sortedBy { it.first }
                    .joinToString(",", "map[", "]") { "${it.first}=${it.second}" }
            } else {
                node.keys().asSequence().toSortedSet()
                    .joinToString(",", "{", "}") { key -> "$key=${canonicalWorkValue(node.get(key))}" }
            }
        }
        node is JSONArray ->
            (0 until node.length()).joinToString(",", "[", "]") { index -> canonicalWorkValue(node.get(index)) }
        node is Number -> node.toDouble().toString()
        node is Boolean -> node.toString()
        node is String -> "\"$node\""
        else -> node.toString()
    }

    private fun assertWorkValueEquals(expectedJson: String, actualJson: String, context: String) {
        val expected = canonicalWorkValue(JSONTokener(expectedJson).nextValue())
        val actual = canonicalWorkValue(JSONTokener(actualJson).nextValue())
        if (expected != actual) {
            throw AssertionError("$context: work value mismatch\n expected: $expected\n actual:   $actual")
        }
    }

    /** RuntimeFailure whose diagnostic carries E_INVALID_VALUE and no partial value. */
    private fun assertInvalidValue(outcome: LuaKernelOutcome, context: String) {
        val failure = support.assertRuntimeFailure(outcome, context)
        if (!failure.diagnostic.contains("E_INVALID_VALUE")) {
            throw AssertionError("$context: invalid Lua value escaped as a different error: ${failure.diagnostic}")
        }
    }

    private fun installRc(handle: LuaStateHandle, resourceContextJson: String) =
        support.installResourceContext(handle, resourceContextJson)

    private fun channelState(resourceContext: String): LuaStateHandle {
        val state = support.createState(support.channelConfig())
        installRc(state, resourceContext)
        return state
    }

    private fun spawnManagedTask(handle: LuaStateHandle): LuaKernelOutcome.Yielded {
        val startup = support.assertCompleted(
            bridge.invokeCallback(handle, LuaCallbackHandle(handle, "startup"), LuaValue.Nil, accepting),
            "startup spawn",
        )
        val spawned = startup.spawnedCoroutines
        if (spawned == null || spawned.size != 1) {
            throw AssertionError("startup must spawn exactly one managed task: $startup")
        }
        return assertYielded(bridge.startCoroutine(handle, LuaCoroutineId(spawned[0]), accepting), "start managed task")
    }

    // ------------------------------------------------------------------
    // conformance.rs
    // ------------------------------------------------------------------

    @Test
    fun string_nonstrings_and_nested_lua_errors_are_normalized_and_recoverable() {
        val sources = listOf(
            "function main() error('string-regression') end",
            "function main() error({ kind = 'structured-regression' }) end",
            """
            function main()
              local ok, err = pcall(function() error("nested-regression") end)
              if ok then error("protected call unexpectedly succeeded") end
              error("outer-regression:" .. err)
            end
            """.trimIndent(),
        )
        for (source in sources) {
            val state = support.createState()
            support.loadSourceOk(state, source, "main")
            val failed = support.assertRuntimeFailure(support.startEntry(state), "failing start")
            if (source.contains("nested-regression") && !failed.diagnostic.contains("nested-regression")) {
                throw AssertionError("nested protected error lost its diagnostic context: ${failed.diagnostic}")
            }
            support.loadSourceOk(state, "function healthy() return 'recovered' end", "healthy")
            val recovered = support.startEntryOk(state)
            if (support.resultString(recovered) != "\"recovered\"") {
                throw AssertionError("state did not recover after $source: ${recovered.value}")
            }
            support.assertClosed(support.closeState(state), "close")
        }
    }

    // ------------------------------------------------------------------
    // fs_conformance.rs
    // ------------------------------------------------------------------

    private fun fsState(): LuaStateHandle =
        channelState("""{"storageFiles":true,"mounts":{"data":{"access":"read-write","status":"available"}}}""")

    @Test
    fun fs_list_accepts_empty_mount_root_selector() {
        val state = fsState()
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local fs = require("talkcan.fs")
                    return {
                      startup = function() end,
                      handle_input = function(event)
                        local mount = fs.mount("data")
                        local page, err = fs.list(mount, "", {limit=10})
                        if not page then return {error=err.error} end
                        return {ok=true}
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val yielded = assertYielded(invokeInput(state, captureEvent()), "fs.list yield")
        val admitted = claimAdmitted(state, yielded, HostOperationKind.FS_LIST)
        if (admitted.path != "") {
            throw AssertionError("empty mount-root selector must claim an empty path: ${admitted.path}")
        }
    }

    @Test
    fun fs_mount_userdata_not_serializable_in_callback_result() {
        val state = fsState()
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local fs = require("talkcan.fs")
                    return {
                      startup = function() end,
                      handle_input = function(event)
                        local mount = fs.mount("data")
                        return {ok=true, mount=mount}
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val outcome = invokeInput(state, captureEvent())
        support.assertRuntimeFailure(outcome, "mount userdata in callback result")
    }

    @Test
    fun fs_mount_userdata_not_loggable() {
        val state = fsState()
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local fs = require("talkcan.fs")
                    local log = require("talkcan.log")
                    return {
                      startup = function() end,
                      handle_input = function(event)
                        local mount = fs.mount("data")
                        local ok, err = log.info({mount=mount})
                        return {ok=true, log_ok=ok, log_err=err and err.error or "nil"}
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val outcome = support.assertCompleted(invokeInput(state, captureEvent()), "log mount userdata")
        val value = resultJson(outcome)
        if (!value.isNull("log_ok") || value.optString("log_err") != "E_INVALID_VALUE") {
            throw AssertionError("log.info with mount userdata must return (nil, E_INVALID_VALUE): ${outcome.value}")
        }
    }

    @Test
    fun fs_error_normalization_unknown_collapses_to_e_io() {
        val state = fsState()
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local fs = require("talkcan.fs")
                    return {
                      startup = function() end,
                      handle_input = function(event)
                        local mount = fs.mount("data")
                        local r, e = fs.stat(mount, "f.txt")
                        return {error={code="GOT",detail=e and e.error or "nil"}}
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val yielded = assertYielded(invokeInput(state, captureEvent()), "fs.stat yield")
        claimAdmitted(state, yielded, HostOperationKind.FS_STAT)
        val done = support.assertCompleted(
            bridge.resume(operation(state, yielded), false, "E_UNKNOWN_PLATFORM_ERROR", accepting),
            "unknown failure resume",
        )
        assertJsonEquals(
            parseJson("""{"error":{"code":"GOT","detail":"E_IO"}}"""),
            parseJson(support.resultString(done)),
            "unknown host failure must collapse to E_IO",
        )
    }

    // ------------------------------------------------------------------
    // json_conformance.rs
    // ------------------------------------------------------------------

    @Test
    fun json_module_is_required_without_capability_and_round_trips() {
        val state = support.createState()
        // No resource context is installed: requiring talkcan.json must need no
        // capability, because encode/decode are state-local computation.
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local json = require("talkcan.json")
                    return {
                      startup = function() end,
                      probe = function()
                        local text, encode_err = json.encode({ b = 2, a = 1, list = { 1, 2, 3 }, nothing = json.null })
                        local decoded, decode_err = json.decode('{"k":[true,null,7]}')
                        return {
                          encode_type = type(text),
                          encode_err = encode_err,
                          text = text,
                          decode_err = decode_err,
                          k1 = decoded.k[1],
                          k2_is_null = decoded.k[2] == json.null,
                          k3 = decoded.k[3],
                          null_is_userdata = type(json.null) == "userdata",
                          surface_locked = getmetatable(json) == false,
                        }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val probe = support.invokeCallbackOk(state, "probe")
        val result = resultJson(probe)
        if (result.optString("encode_type") != "string") {
            throw AssertionError("encode_type: ${probe.value}")
        }
        if (!result.isNull("encode_err")) {
            throw AssertionError("encode failed: ${probe.value}")
        }
        // Deterministic key order and exact value-class preservation.
        if (result.optString("text") != "{\"a\":1,\"b\":2,\"list\":[1,2,3],\"nothing\":null}") {
            throw AssertionError("encode text: ${probe.value}")
        }
        if (!result.isNull("decode_err")) {
            throw AssertionError("decode failed: ${probe.value}")
        }
        if (result.optBoolean("k1", false).not()) {
            throw AssertionError("k1: ${probe.value}")
        }
        if (result.optBoolean("k2_is_null", false).not()) {
            throw AssertionError("k2_is_null: ${probe.value}")
        }
        if (result.optDouble("k3", Double.NaN) != 7.0) {
            throw AssertionError("k3: ${probe.value}")
        }
        if (result.optBoolean("null_is_userdata", false).not()) {
            throw AssertionError("null_is_userdata: ${probe.value}")
        }
        if (result.optBoolean("surface_locked", false).not()) {
            throw AssertionError("surface_locked: ${probe.value}")
        }
    }

    @Test
    fun json_null_in_a_log_payload_is_rejected_as_invalid_value() {
        val state = support.createState()
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local json = require("talkcan.json")
                    local log = require("talkcan.log")
                    return {
                      startup = function() end,
                      probe = function()
                        local ok, err = log.info({ nested = { value = json.null } })
                        return { ok = ok, err = err }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val probe = support.invokeCallbackOk(state, "probe")
        val result = resultJson(probe)
        // The whole payload is rejected; the sentinel is neither stringified nor replaced.
        if (!result.isNull("ok")) {
            throw AssertionError("log accepted the sentinel: ${probe.value}")
        }
        if (result.optJSONObject("err")?.optString("error") != "E_INVALID_VALUE") {
            throw AssertionError("sentinel log payload must reject with E_INVALID_VALUE: ${probe.value}")
        }
    }

    @Test
    fun json_null_debug_stringification_exposes_no_identifiers() {
        val state = support.createState()
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local json = require("talkcan.json")
                    return {
                      startup = function() end,
                      probe = function()
                        return {
                          label = tostring(json.null),
                          metatable_hidden = getmetatable(json.null) == false,
                        }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val probe = support.invokeCallbackOk(state, "probe")
        val result = resultJson(probe)
        val label = result.optString("label")
        if (label != "null") {
            throw AssertionError("null sentinel label: $label")
        }
        // No state token, address, hex digits, or markers may leak into the label.
        if (label.any { it.isDigit() }) {
            throw AssertionError("label leaks a number: $label")
        }
        if (label.contains("0x")) {
            throw AssertionError("label leaks an address: $label")
        }
        if (label.contains(':')) {
            throw AssertionError("label leaks a marker: $label")
        }
        if (result.optBoolean("metatable_hidden", false).not()) {
            throw AssertionError("metatables must stay hidden: ${probe.value}")
        }
    }

    // ------------------------------------------------------------------
    // keyboard_conformance.rs
    // ------------------------------------------------------------------

    private fun keyboardState(): LuaStateHandle = channelState("""{"keyboardOutput":true}""")

    @Test
    fun failure_resume_normalizes_to_stable_error_codes() {
        val state = keyboardState()
        val sendTextImage = mapOf(
            "entry" to """
                local kb = require("talkcan.keyboard_output")
                return {
                  startup = function() end,
                  handle_input = function(event)
                    local result, err = kb.send_text({ text = "x", profile = "p" })
                    return { code = result == nil and err.error or "ok" }
                  end,
                }
            """.trimIndent(),
        )
        fun round(injected: String): LuaKernelOutcome.Completed {
            support.loadProgramImageOk(state, "entry", sendTextImage)
            val yielded = assertYielded(invokeInput(state, captureEvent()), "keyboard yield")
            claimAdmitted(state, yielded, HostOperationKind.KEYBOARD_SEND_TEXT)
            return support.assertCompleted(
                bridge.resume(operation(state, yielded), false, injected, accepting),
                "failure resume with $injected",
            )
        }

        // Stable codes pass through.
        val stable = round("E_BUSY")
        assertJsonEquals(parseJson("""{"code":"E_BUSY"}"""), parseJson(support.resultString(stable)), "stable code")

        // Unknown diagnostics collapse to E_HOST_FAILURE and never transport raw detail.
        val unknown = round("gatt-disconnect-0x3e transport detail")
        assertJsonEquals(
            parseJson("""{"code":"E_HOST_FAILURE"}"""),
            parseJson(support.resultString(unknown)),
            "unknown diagnostics must collapse and never transport raw detail",
        )
    }

    private fun sosImage(body: String): Map<String, String> =
        mapOf(
            "entry" to """
                local kb = require("talkcan.keyboard_output")
                return {
                  startup = function() end,
                  handle_sos = function(event)
                    $body
                  end,
                }
            """.trimIndent(),
        )

    @Test
    fun sos_terminal_result_shape_is_validated() {
        val state = keyboardState()
        support.loadProgramImageOk(state, "entry", sosImage("return 42"))
        val outcome = invokeSos(state, lv("reason" to str("test")))
        val failure = support.assertRuntimeFailure(outcome, "SOS returning 42")
        if (!failure.diagnostic.contains("callback contract violation")) {
            throw AssertionError("invalid SOS terminal must report a contract violation: ${failure.diagnostic}")
        }
    }

    @Test
    fun sos_terminal_shape_validated_after_keyboard_resume() {
        val state = keyboardState()
        support.loadProgramImageOk(
            state,
            "entry",
            sosImage(
                """
                kb.send_key({ key = "enter", profile = "p" })
                return "not-a-table"
                """.trimIndent(),
            ),
        )
        val yielded = assertYielded(invokeSos(state, lv("reason" to str("test"))), "SOS keyboard yield")
        claimAdmitted(state, yielded, HostOperationKind.KEYBOARD_SEND_KEY)
        val completed = bridge.resume(operation(state, yielded), true, """{"status":"delivered"}""", accepting)
        val failure = support.assertRuntimeFailure(completed, "SOS terminal after resume")
        if (!failure.diagnostic.contains("callback contract violation")) {
            throw AssertionError("invalid SOS terminal after resume must report a contract violation: ${failure.diagnostic}")
        }
    }

    // ------------------------------------------------------------------
    // profile_secret_http_conformance.rs
    // ------------------------------------------------------------------

    private val oneGrant = """
        {"profiles":[{"profileId":"p1","typeLocalId":"openai-account","displayName":"Primary","values":{"apiKey":{"t":"text","v":"public-123"}},"secretReferences":{"token":"kotlin-minted-ref-token"}}]}
    """.trimIndent()

    private fun installGrants(handle: LuaStateHandle) {
        support.assertCompleted(bridge.setProfileGrants(handle, oneGrant), "setProfileGrants")
    }

    @Test
    fun profile_get_returns_detached_table_with_opaque_secret() {
        val state = channelState("""{"secretsRead":true}""")
        installGrants(state)
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local profiles = require("talkcan.profiles")
                    return {
                      startup = function() end,
                      handle_input = function()
                        local p, e = profiles.get("p1")
                        if not p then return { error = { code = "GET", detail = e.error } } end
                        local ref = p.secrets.token
                        return {
                          ok = true,
                          id = p.id,
                          typ = p.type,
                          name = p.name,
                          apiKey = p.values.apiKey,
                          ref_userdata = (type(ref) == "userdata"),
                          ref_tostring = tostring(ref),
                          ref_mt_locked = (getmetatable(ref) == false),
                        }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val outcome = support.assertCompleted(invokeInput(state, captureEvent()), "profile.get")
        val value = resultJson(outcome)
        if (!value.optBoolean("ok", false) ||
            value.optString("id") != "p1" ||
            value.optString("typ") != "openai-account" ||
            value.optString("name") != "Primary" ||
            value.optString("apiKey") != "public-123" ||
            !value.optBoolean("ref_userdata", false) ||
            value.optString("ref_tostring") != "opaque_secret_reference" ||
            !value.optBoolean("ref_mt_locked", false)
        ) {
            throw AssertionError("profile.get must return a detached table with an opaque secret: ${outcome.value}")
        }
    }

    @Test
    fun profile_get_mutation_does_not_reach_grant() {
        val state = channelState("""{"secretsRead":true}""")
        installGrants(state)
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local profiles = require("talkcan.profiles")
                    return {
                      startup = function() end,
                      handle_input = function()
                        local p1 = profiles.get("p1")
                        p1.values.apiKey = "MUTATED"
                        p1.name = "MUTATED"
                        local p2 = profiles.get("p1")
                        return { ok = true, value = p2.values.apiKey, name = p2.name }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val outcome = support.assertCompleted(invokeInput(state, captureEvent()), "profile mutation")
        val value = resultJson(outcome)
        if (value.optString("value") != "public-123" || value.optString("name") != "Primary") {
            throw AssertionError("grant snapshot must be detached: ${outcome.value}")
        }
    }

    @Test
    fun secret_read_failure_normalizes_unknown_to_e_storage() {
        val state = channelState("""{"secretsRead":true}""")
        installGrants(state)
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local profiles = require("talkcan.profiles")
                    local secrets = require("talkcan.secrets")
                    return {
                      startup = function() end,
                      handle_input = function()
                        local p = profiles.get("p1")
                        local plaintext, e = secrets.read(p.secrets.token)
                        return { ok = (plaintext == nil), code = e and e.error or "none" }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val yielded = assertYielded(invokeInput(state, captureEvent()), "secret read yield")
        val admitted = claimAdmitted(state, yielded, HostOperationKind.SECRET_READ)
        if (admitted.referenceToken != "kotlin-minted-ref-token") {
            throw AssertionError("secret claim must carry the opaque reference token: ${admitted.referenceToken}")
        }
        val done = support.assertCompleted(
            bridge.resume(operation(state, yielded), false, "E_SOMETHING_PLATFORM_SPECIFIC", accepting),
            "secret failure resume",
        )
        val value = resultJson(done)
        if (!value.optBoolean("ok", false) || value.optString("code") != "E_STORAGE") {
            throw AssertionError("unknown secret failure must normalize to E_STORAGE: ${done.value}")
        }
    }

    @Test
    fun http_request_failure_normalizes_unknown_to_e_transport() {
        val state = channelState("""{"networkHttp":true}""")
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local http = require("talkcan.http")
                    return {
                      startup = function() end,
                      handle_input = function()
                        local resp, e = http.request({ method = "GET", url = "https://api.example.com" })
                        return { ok = (resp == nil), code = e and e.error or "none" }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val yielded = assertYielded(invokeInput(state, captureEvent()), "http yield")
        claimAdmitted(state, yielded, HostOperationKind.HTTP_REQUEST)
        val done = support.assertCompleted(
            bridge.resume(operation(state, yielded), false, "E_SOCKET_PLATFORM_42", accepting),
            "http failure resume",
        )
        val value = resultJson(done)
        if (!value.optBoolean("ok", false) || value.optString("code") != "E_TRANSPORT") {
            throw AssertionError("unknown http failure must normalize to E_TRANSPORT: ${done.value}")
        }
    }

    @Test
    fun http_status_crosses_the_lua_boundary_as_an_integer() {
        // OpenAI-style packages normalize completions only when
        // `math.type(status) == "integer"`; the host transport serializes the
        // status as a signed integer token and the kernel must push it through
        // the Lua integer subtype instead of erasing it to a float.
        val state = channelState("""{"networkHttp":true}""")
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local http = require("talkcan.http")
                    return {
                      startup = function() end,
                      handle_input = function()
                        local resp, e = http.request({ method = "GET", url = "https://api.example.com" })
                        if not resp then
                          return { error = { code = e and e.error or "E_TRANSPORT", detail = "request failed" } }
                        end
                        return { status_type = math.type(resp.status), status = resp.status }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val yielded = assertYielded(invokeInput(state, captureEvent()), "http yield")
        claimAdmitted(state, yielded, HostOperationKind.HTTP_REQUEST)
        val done = support.assertCompleted(
            bridge.resume(
                operation(state, yielded),
                true,
                """{"status":200,"headers":{},"body":"ok"}""",
                accepting,
            ),
            "http success resume",
        )
        val value = resultJson(done)
        if (value.optString("status_type") != "integer") {
            throw AssertionError("HTTP status must reach Lua as an integer subtype: ${done.value}")
        }
        if (value.optLong("status") != 200L) {
            throw AssertionError("HTTP status value must survive the round trip exactly: ${done.value}")
        }
    }

    @Test
    fun json_decode_delivers_timestamps_as_lua_integers_and_fractions_as_floats() {
        // Journal-style packages reject `timestamp.unix_ms` unless
        // `math.type` reports integer; full signed-64-bit timestamps must not
        // pass through Double on the decode path.
        val state = channelState("{}")
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local json = require("talkcan.json")
                    return {
                      startup = function() end,
                      handle_input = function()
                        local v, err = json.decode('{"unix_ms":1784796437093,"ratio":1.5}')
                        if not v then
                          return { error = { code = "E_DECODE", detail = tostring(err) } }
                        end
                        return {
                          unix_ms_type = math.type(v.unix_ms),
                          unix_ms = v.unix_ms,
                          ratio_type = math.type(v.ratio),
                        }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val done = support.assertCompleted(
            invokeInput(state, captureEvent()),
            "json decode input",
        )
        val value = resultJson(done)
        if (value.optString("unix_ms_type") != "integer") {
            throw AssertionError("json.decode must deliver integer lexemes as Lua integers: ${done.value}")
        }
        if (value.optLong("unix_ms") != 1784796437093L) {
            throw AssertionError("timestamps must round trip without precision loss: ${done.value}")
        }
        if (value.optString("ratio_type") != "float") {
            throw AssertionError("json.decode must keep fractional lexemes as Lua floats: ${done.value}")
        }
    }

    // ------------------------------------------------------------------
    // resolver_conformance.rs
    // ------------------------------------------------------------------

    private fun invokeResolver(moduleSource: String, capabilities: JSONObject = JSONObject()): LuaKernelOutcome.Completed {
        val state = support.createResolverState()
        return support.assertCompleted(
            bridge.invokeResolver(state, support.resolverInvocation(moduleSource, capabilities)),
            "invokeResolver",
        )
    }

    private fun assertPackageError(outcome: LuaKernelOutcome.Completed, context: String) {
        if (resultJson(outcome).optString("resultKind") != "package_error") {
            throw AssertionError("$context: expected package_error but was ${outcome.value}")
        }
    }

    @Test
    fun resolver_returns_choices_envelope() {
        val outcome = invokeResolver(
            """
            return {
              resolve = function(request)
                return { choices = {
                  { value = "gpt-4o", label = "GPT-4o" },
                  { value = "gpt-4o-mini", label = "GPT-4o mini" },
                } }, nil
              end,
            }
            """.trimIndent(),
        )
        val value = resultJson(outcome)
        if (value.optString("operation") != "invokeResolver") {
            throw AssertionError("resolver envelope operation: ${outcome.value}")
        }
        if (value.optString("resultKind") != "choices") {
            throw AssertionError("resolver envelope resultKind: ${outcome.value}")
        }
        val choices = value.getJSONArray("choices")
        if (choices.length() != 2 ||
            choices.getJSONObject(0).optString("value") != "gpt-4o" ||
            choices.getJSONObject(0).optString("label") != "GPT-4o" ||
            choices.getJSONObject(1).optString("value") != "gpt-4o-mini"
        ) {
            throw AssertionError("resolver choices envelope: ${outcome.value}")
        }
    }

    @Test
    fun resolver_empty_choices_is_valid() {
        val outcome = invokeResolver(
            """
            return { resolve = function() return { choices = {} }, nil end }
            """.trimIndent(),
        )
        val value = resultJson(outcome)
        val choices = value.optJSONArray("choices")
        if (value.optString("resultKind") != "choices" || choices == null || choices.length() != 0) {
            throw AssertionError("empty choices must be valid: ${outcome.value}")
        }
    }

    @Test
    fun resolver_choices_above_former_count_cap_reach_envelope() {
        // 400 choices exceeds the removed 256 resolver-specific count cap; the
        // generic aggregate byte bound replaces it and publication must succeed.
        val outcome = invokeResolver(
            """
            return {
              resolve = function(request)
                local choices = {}
                for i = 1, 400 do
                  choices[i] = { value = "model-" .. i, label = "Model " .. i }
                end
                return { choices = choices }, nil
              end,
            }
            """.trimIndent(),
        )
        val value = resultJson(outcome)
        if (value.optString("operation") != "invokeResolver" || value.optString("resultKind") != "choices") {
            throw AssertionError("400-choice envelope: ${outcome.value}")
        }
        val choices = value.getJSONArray("choices")
        if (choices.length() != 400 ||
            choices.getJSONObject(0).optString("value") != "model-1" ||
            choices.getJSONObject(0).optString("label") != "Model 1" ||
            choices.getJSONObject(399).optString("value") != "model-400" ||
            choices.getJSONObject(399).optString("label") != "Model 400"
        ) {
            throw AssertionError("400-choice envelope bounds: ${outcome.value}")
        }
    }

    @Test
    fun resolver_lua_error_is_package_error() {
        val outcome = invokeResolver(
            """
            return { resolve = function() error("boom") end }
            """.trimIndent(),
        )
        assertPackageError(outcome, "Lua error in resolve()")
    }

    @Test
    fun resolver_malformed_choices_is_package_error() {
        // Choice missing the `label` key -> all-or-nothing rejection.
        val outcome = invokeResolver(
            """
            return { resolve = function() return { choices = { { value = "x" } } }, nil end }
            """.trimIndent(),
        )
        assertPackageError(outcome, "choice missing label")
    }

    @Test
    fun resolver_duplicate_choice_values_is_package_error() {
        val outcome = invokeResolver(
            """
            return {
              resolve = function()
                return { choices = {
                  { value = "dup", label = "A" },
                  { value = "dup", label = "B" },
                } }, nil
              end,
            }
            """.trimIndent(),
        )
        assertPackageError(outcome, "duplicate choice values")
    }

    @Test
    fun resolver_non_table_return_is_package_error() {
        val outcome = invokeResolver(
            """
            return { resolve = function() return "not a table", nil end }
            """.trimIndent(),
        )
        assertPackageError(outcome, "non-table resolve() return")
    }

    // ------------------------------------------------------------------
    // runtime_v1_conformance.rs — callback tables and value normalization
    // ------------------------------------------------------------------

    @Test
    fun callback_tables_reject_metatables_and_wrong_recognized_keys_but_retain_valid_handles() {
        val cases = listOf(
            arrayOf(
                "local callbacks = {}; return setmetatable(callbacks, { __index = { startup = function() end } })",
                "callback table has metatable",
            ),
            arrayOf(
                "return { handle_readiness = function() return {ready = true} end }",
                "required callback 'startup' is missing",
            ),
            arrayOf(
                "return { startup = 'not a function' }",
                "expected function for callback 'startup', got string",
            ),
            arrayOf(
                "return { startup = function() end, handle_readiness = 7 }",
                "expected function for callback 'handle_readiness', got integer",
            ),
        )
        for (case in cases) {
            val entry = case[0]
            val expected = case[1]
            val state = support.createState(support.runtimeV1Config())
            val outcome = support.loadProgramImage(state, "entry", mapOf("entry" to entry))
            val failure = support.assertValidationFailure(outcome, "callback table: $expected")
            if (!failure.diagnostic.contains(expected)) {
                throw AssertionError("wrong validation detail for '$expected': ${failure.diagnostic}")
            }
            support.assertClosed(support.closeState(state), "close after validation failure")
        }

        val state = support.createState(support.runtimeV1Config())
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local starts = 0
                    return {
                      startup = function() starts = starts + 1 end,
                      handle_readiness = function() return { ready = starts == 2 } end,
                      unrecognized = "ignored during validation",
                    }
                """.trimIndent(),
            ),
        )
        support.invokeCallbackOk(state, "startup")
        support.invokeCallbackOk(state, "startup")
        val readiness = support.invokeCallbackOk(state, "handle_readiness")
        assertJsonEquals(parseJson("""{"ready":true}"""), parseJson(support.resultString(readiness)), "readiness after two starts")
        support.assertClosed(support.closeState(state), "close")
    }

    @Test
    fun callback_normalization_preserves_allowed_values_and_rejects_each_invalid_value_whole() {
        val state = support.createState(support.runtimeV1Config())
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local function nested(depth)
                      local root = {}
                      local current = root
                      for _ = 1, depth do
                        local child = {}
                        current.child = child
                        current = child
                      end
                      return root
                    end
                    return {
                      startup = function() end,
                      nil_value = function() return nil end,
                      bool_value = function() return true end,
                      number_value = function() return 12.5 end,
                      utf8_value = function() return "Καλημέρα" end,
                      string_value = function() return "bounded string" end,
                      array_value = function() return { "first", false, 3 } end,
                      map_value = function() return { alpha = 1, nested = { beta = "two" } } end,
                      cycle = function() local value = {}; value.self = value; return value end,
                      metatable = function() return setmetatable({ safe = true }, {}) end,
                      function_value = function() return function() end end,
                      thread = function() return coroutine.create(function() end) end,
                      non_finite = function() return 0 / 0 end,
                      invalid_utf8 = function() return string.char(255) end,
                      mixed = function() return { "array", named = "map" } end,
                      sparse = function() return { [1] = "first", [3] = "third" } end,
                      deep = function() return nested(12) end,
                      too_many_entries = function()
                        local value = {}
                        for index = 1, 1001 do value["key" .. index] = index end
                        return value
                      end,
                      too_long_string = function() return string.rep("x", 65537) end,
                      shared_table_alias = function()
                        local leaf = { value = "shared" }
                        return { leaf, leaf }
                      end,
                      aggregate_string_bytes = function()
                        local values = {}
                        for index = 1, 128 do values[index] = string.rep("x", 1024) end
                        return values
                      end,
                    }
                """.trimIndent(),
            ),
        )

        val allowedScalars = listOf(
            "nil_value" to "null",
            "bool_value" to "true",
            "number_value" to "12.5",
            "utf8_value" to "\"Καλημέρα\"",
            "string_value" to "\"bounded string\"",
        )
        for ((callback, expected) in allowedScalars) {
            val outcome = support.invokeCallbackOk(state, callback)
            if (support.resultString(outcome) != expected) {
                throw AssertionError("$callback was normalized incorrectly: ${outcome.value} (expected $expected)")
            }
        }
        val arrayOutcome = support.invokeCallbackOk(state, "array_value")
        assertJsonEquals(
            parseJson("""["first",false,3]"""),
            parseJson(support.resultString(arrayOutcome)),
            "array_value normalization",
        )
        val mapOutcome = support.invokeCallbackOk(state, "map_value")
        assertJsonEquals(
            parseJson("""{"alpha":1,"nested":{"beta":"two"}}"""),
            parseJson(support.resultString(mapOutcome)),
            "map_value normalization",
        )

        for (callback in listOf(
            "cycle",
            "metatable",
            "function_value",
            "thread",
            "non_finite",
            "invalid_utf8",
            "mixed",
            "sparse",
            "deep",
            "too_many_entries",
            "too_long_string",
            "shared_table_alias",
            "aggregate_string_bytes",
        )) {
            assertInvalidValue(support.invokeCallback(state, callback), callback)
        }

        val recovery = support.invokeCallbackOk(state, "map_value")
        assertJsonEquals(
            parseJson("""{"alpha":1,"nested":{"beta":"two"}}"""),
            parseJson(support.resultString(recovery)),
            "state must recover after invalid-value rejections",
        )
        support.assertClosed(support.closeState(state), "close")
    }

    // ------------------------------------------------------------------
    // runtime_v1_conformance.rs — structured logs
    // ------------------------------------------------------------------

    @Test
    fun structured_logs_silently_drop_at_bound_and_reject_invalid_payloads_atomically() {
        val state = support.createState(support.runtimeV1Config())
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local log = require("talkcan.log")
                    return {
                      startup = function() end,
                      probe = function()
                        local accepted = 0
                        for sequence = 1, 256 do
                          local ok, err = log.info({ message = "logged", sequence = sequence })
                          if ok and err == nil then accepted = accepted + 1 end
                        end
                        local rejected, rejected_error = log.warn({ bad = function() end })
                        return {
                          accepted = accepted,
                          rejected_is_nil = rejected == nil,
                          rejected_error = rejected_error.error,
                        }
                      end,
                      healthy = function() return { ready = true } end,
                    }
                """.trimIndent(),
            ),
        )
        val probe = support.invokeCallbackOk(state, "probe")
        val probeValue = resultJson(probe)
        // Rate-dropped logs must still report (true, nil); invalid payloads reject.
        if (probeValue.optInt("accepted") != 256 ||
            !probeValue.optBoolean("rejected_is_nil", false) ||
            probeValue.optString("rejected_error") != "E_INVALID_VALUE"
        ) {
            throw AssertionError("log bound behavior: ${probe.value}")
        }
        val logs = probe.logs ?: throw AssertionError("accepted structured log was not observable: $probe")
        if (logs.isEmpty() || logs.size >= 256 || logs.size > 128) {
            throw AssertionError("valid logs were not bounded and silently dropped: ${logs.size} entries")
        }
        for (encoded in logs) {
            val recorded = JSONObject(encoded)
            if (recorded.optString("level") != "info" ||
                recorded.optJSONObject("payload")?.optString("message") != "logged"
            ) {
                throw AssertionError("recorded log shape: $encoded")
            }
        }
        val healthy = support.invokeCallbackOk(state, "healthy")
        assertJsonEquals(parseJson("""{"ready":true}"""), parseJson(support.resultString(healthy)), "healthy after logs")
        support.assertClosed(support.closeState(state), "close")
    }

    // ------------------------------------------------------------------
    // runtime_v1_conformance.rs — opaque audio userdata
    // ------------------------------------------------------------------

    @Test
    fun opaque_audio_userdata_is_rejected_atomically_across_callback_config_errors_and_logs() {
        val state = support.createState(support.runtimeV1Config())
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    saved_audio = nil

                    return {
                      startup = function(config)
                        -- Startup is also a generic configuration/result boundary.
                        return { config = saved_audio }
                      end,
                      handle_input = function(event)
                        local audio = event.audio
                        saved_audio = audio
                        return { ok = true }
                      end,
                      handle_readiness = function()
                        -- A plain callback table must not be partially serialized.
                        return { ready = true, payload = saved_audio, after = "must not escape" }
                      end,
                      handle_lifecycle = function()
                        -- Structured callback errors are subject to the same rejection.
                        return { error = { code = "E_AUDIO", detail = saved_audio } }
                      end,
                      handle_sos = function()
                        local ok, err = require("talkcan.log").info({
                          message = "before opaque audio",
                          audio = saved_audio,
                        })
                        return {
                          log_rejected = ok == nil,
                          log_error = err and err.error,
                        }
                      end,
                    }
                """.trimIndent(),
            ),
        )

        val inputArgs = LuaValue.Map(
            linkedMapOf<String, LuaValue>(
                "source" to str("captured"),
                "metadata" to LuaValue.Map(
                    mapOf(
                        "sample_rate" to int(16_000),
                        "channels" to int(1),
                        "duration_ms" to int(1),
                        "pcm_bytes" to int(32),
                    ),
                ),
            ),
        )
        val input = support.assertCompleted(
            invokeInput(state, inputArgs, "opaque-token-for-rejection-test"),
            "opaque audio capture",
        )
        assertJsonEquals(parseJson("""{"ok":true}"""), parseJson(support.resultString(input)), "capture")

        // Config boundary: startup result carrying the userdata rejects whole.
        assertInvalidValue(
            bridge.invokeStartupCallback(
                state,
                LuaCallbackHandle(state, "startup"),
                lv("schema_version" to int(1)),
                accepting,
            ),
            "startup config/result boundary",
        )

        // Callback result boundary: no partial serialization.
        assertInvalidValue(support.invokeCallback(state, "handle_readiness"), "readiness result boundary")

        // Structured callback error boundary.
        assertInvalidValue(support.invokeCallback(state, "handle_lifecycle"), "lifecycle error boundary")

        // Structured logging returns an error pair without failing the callback,
        // and the invalid payload is never partially persisted as a log.
        val sos = support.assertCompleted(invokeSos(state, LuaValue.Nil), "SOS log boundary")
        assertJsonEquals(
            parseJson("""{"log_rejected":true,"log_error":"E_INVALID_VALUE"}"""),
            parseJson(support.resultString(sos)),
            "structured logging must return an error pair without failing the callback",
        )
        if (sos.logs != null) {
            throw AssertionError("invalid userdata payload must not be partially persisted as a log: ${sos.logs}")
        }

        support.assertClosed(support.closeState(state), "close")
    }

    // ------------------------------------------------------------------
    // runtime_v1_conformance.rs — host failure normalization
    // ------------------------------------------------------------------

    @Test
    fun synthesis_host_failure_resumes_normalized_error() {
        val state = support.createState(support.runtimeV1Config())
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to "local s=require(\"talkcan.synthesis\"); return {startup=function()end,handle_input=function() local x,e=s.synthesize({text=\"x\",language=\"en-US\",voice=\"v\"}); return {error=e and e.error} end}",
            ),
        )
        val allowed = listOf(
            "E_INVALID_ARGUMENT",
            "E_INVALID_VALUE",
            "E_INVALID_CONTEXT",
            "E_CAPABILITY_UNDECLARED",
            "E_UNAVAILABLE",
            "E_BUSY",
            "E_TIMEOUT",
            "E_CANCELLED",
            "E_CLOSED",
            "E_STALE",
            "E_HOST_FAILURE",
        )
        val injected = allowed + listOf(
            "exception-like: /endpoint=https://secret.example credential=top-secret transport reset",
            "provider failure detail",
        )
        for (code in injected) {
            val yielded = assertYielded(invokeInput(state, captureEvent(), "token"), "synthesis yield for $code")
            val failed = support.assertCompleted(
                bridge.resume(operation(state, yielded), false, code, accepting),
                "failure resume for $code",
            )
            val expected = if (code.startsWith("E_")) code else "E_HOST_FAILURE"
            val rendered = support.resultString(failed)
            assertJsonEquals(parseJson("""{"error":"$expected"}"""), parseJson(rendered), "injected=$code")
            for (leak in listOf("secret.example", "top-secret", "transport reset")) {
                if (rendered.contains(leak)) {
                    throw AssertionError("raw failure detail leaked for $code: $rendered")
                }
            }
        }
        support.assertClosed(support.closeState(state), "close")
    }

    @Test
    fun host_operation_failure_normalizes_unknown_to_host_failure() {
        val state = support.createState(support.runtimeV1Config())
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to "local t=require(\"talkcan.transcription\"); return {startup=function()end,handle_input=function(event) local x,e=t.transcribe(event.audio); if e then return {error=e.error} end; return {text=x.text} end}",
            ),
        )
        val yielded = assertYielded(invokeInput(state, captureEvent(), "token"), "transcription yield")
        val failed = support.assertCompleted(
            bridge.resume(operation(state, yielded), false, "provider exploded https://secret.example", accepting),
            "unknown failure resume",
        )
        val rendered = support.resultString(failed)
        assertJsonEquals(parseJson("""{"error":"E_HOST_FAILURE"}"""), parseJson(rendered), "unknown host failure")
        if (rendered.contains("secret.example") || rendered.contains("provider exploded")) {
            throw AssertionError("raw failure detail leaked: $rendered")
        }
        support.assertClosed(support.closeState(state), "close")
    }

    // ------------------------------------------------------------------
    // work_conformance.rs
    // ------------------------------------------------------------------

    private fun workState(queues: List<String> = listOf("turns"), networkHttp: Boolean = false): LuaStateHandle {
        val queuesJson = queues.joinToString(",", "[", "]") { "\"$it\"" }
        val http = if (networkHttp) ",\"networkHttp\":true" else ""
        return channelState("""{"workQueue":true,"workQueues":$queuesJson$http}""")
    }

    private fun driveWorkInput(state: LuaStateHandle, source: String): LuaKernelOutcome {
        support.loadProgramImageOk(state, "entry", mapOf("entry" to source))
        return invokeInput(state, captureEvent())
    }

    private val jobNull = """{"jobId":"j1","payloadJson":"{\"t\":\"null\"}"}"""
    private val jobHello =
        """{"jobId":"j1","payloadJson":"{\"t\":\"map\",\"v\":[[\"text\",{\"t\":\"text\",\"v\":\"hello\"}]]}"}"""

    @Test
    fun work_open_returns_queue_userdata() {
        val state = workState()
        val outcome = support.assertCompleted(
            driveWorkInput(
                state,
                """
                local work = require("talkcan.work")
                return {
                  startup = function() end,
                  handle_input = function()
                    local q, e = work.open("turns")
                    if not q then return { error = { code = "OPEN", detail = e.error } } end
                    return {
                      ok = true,
                      is_userdata = (type(q) == "userdata"),
                      tostring_val = tostring(q),
                      mt_locked = (getmetatable(q) == false),
                    }
                  end,
                }
                """.trimIndent(),
            ),
            "work.open",
        )
        val value = resultJson(outcome)
        if (!value.optBoolean("ok", false) ||
            !value.optBoolean("is_userdata", false) ||
            value.optString("tostring_val") != "opaque_queue" ||
            !value.optBoolean("mt_locked", false)
        ) {
            throw AssertionError("work.open must return opaque queue userdata: ${outcome.value}")
        }
    }

    @Test
    fun work_open_wrong_arity() {
        val state = workState()
        val outcome = support.assertCompleted(
            driveWorkInput(
                state,
                """
                local work = require("talkcan.work")
                return {
                  startup = function() end,
                  handle_input = function()
                    local q, e = work.open("turns", "extra")
                    if q then return { ok = true, unexpected = true } end
                    return { ok = true, error_code = e.error }
                  end,
                }
                """.trimIndent(),
            ),
            "work.open wrong arity",
        )
        if (resultJson(outcome).optString("error_code") != "E_INVALID_ARGUMENT") {
            throw AssertionError("wrong arity must produce E_INVALID_ARGUMENT: ${outcome.value}")
        }
    }

    @Test
    fun work_open_wrong_type() {
        val state = workState()
        val outcome = support.assertCompleted(
            driveWorkInput(
                state,
                """
                local work = require("talkcan.work")
                return {
                  startup = function() end,
                  handle_input = function()
                    local q, e = work.open(42)
                    if q then return { ok = true, unexpected = true } end
                    return { ok = true, error_code = e.error }
                  end,
                }
                """.trimIndent(),
            ),
            "work.open wrong type",
        )
        if (resultJson(outcome).optString("error_code") != "E_INVALID_ARGUMENT") {
            throw AssertionError("non-string queue must produce E_INVALID_ARGUMENT: ${outcome.value}")
        }
    }

    @Test
    fun work_submit_yields_and_completes() {
        val state = workState()
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local work = require("talkcan.work")
                    return {
                      startup = function() end,
                      handle_input = function()
                        local q = work.open("turns")
                        local ok, e = q:submit({ text = "hello" })
                        if not ok then return { error = { code = "SUBMIT", detail = e.error } } end
                        return { ok = true }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val yielded = assertYielded(invokeInput(state, captureEvent()), "submit yield")
        claimAdmitted(state, yielded, HostOperationKind.WORK_SUBMIT)
        val done = support.assertCompleted(
            bridge.resume(operation(state, yielded), true, """{"ok":true}""", accepting),
            "submit resume",
        )
        if (!resultJson(done).optBoolean("ok", false)) {
            throw AssertionError("submit ack: ${done.value}")
        }
    }

    @Test
    fun work_submit_claim_payload_fields() {
        val state = workState()
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local work = require("talkcan.work")
                    return {
                      startup = function() end,
                      handle_input = function()
                        local q = work.open("turns")
                        q:submit({ text = "hello", count = 5 })
                        return { ok = true }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val yielded = assertYielded(invokeInput(state, captureEvent()), "submit yield")
        val admitted = claimAdmitted(state, yielded, HostOperationKind.WORK_SUBMIT)
        if (admitted.queue != "turns") {
            throw AssertionError("submit claim queue: ${admitted.queue}")
        }
        assertWorkValueEquals(
            """{"t":"map","v":[["count",{"t":"int","v":5}],["text",{"t":"text","v":"hello"}]]}""",
            admitted.payloadJson ?: throw AssertionError("submit claim carries no payloadJson"),
            "submit claim payload",
        )
        val done = support.assertCompleted(
            bridge.resume(operation(state, yielded), true, """{"ok":true}""", accepting),
            "submit resume",
        )
        support.assertCompleted(done, "submit terminal")
    }

    @Test
    fun work_receive_yields_and_returns_job() {
        val state = workState()
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local work = require("talkcan.work")
                    return {
                      startup = function()
                        runtime = require("talkcan.runtime")
                        runtime.spawn(function()
                          local q = work.open("turns")
                          local job, e = q:receive()
                          if not job then
                            talkcan.log.error({ error = e.error })
                            return
                          end
                          local payload = job:payload()
                          job:complete(payload)
                        end)
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val started = spawnManagedTask(state)
        claimAdmitted(state, started, HostOperationKind.WORK_RECEIVE)
        val afterReceive = assertYielded(
            bridge.resume(operation(state, started), true, jobHello, accepting),
            "receive resume",
        )
        claimAdmitted(state, afterReceive, HostOperationKind.WORK_COMPLETE)
        val done = support.assertCompleted(
            bridge.resume(operation(state, afterReceive), true, """{"ok":true}""", accepting),
            "complete resume",
        )
        support.assertCompleted(done, "managed task terminal")
    }

    @Test
    fun work_job_payload_detached_copy() {
        val state = workState()
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local work = require("talkcan.work")
                    return {
                      startup = function()
                        runtime = require("talkcan.runtime")
                        runtime.spawn(function()
                          local q = work.open("turns")
                          local job = q:receive()
                          local p1 = job:payload()
                          p1.text = "MUTATED"
                          local p2 = job:payload()
                          job:complete({ original = p2.text })
                        end)
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val started = spawnManagedTask(state)
        claimAdmitted(state, started, HostOperationKind.WORK_RECEIVE)
        val afterReceive = assertYielded(
            bridge.resume(operation(state, started), true, jobHello, accepting),
            "receive resume",
        )
        val completeClaim = claimAdmitted(state, afterReceive, HostOperationKind.WORK_COMPLETE)
        // Mutation of a delivered payload must never reach the kernel-retained value.
        assertWorkValueEquals(
            """{"t":"map","v":[["original",{"t":"text","v":"hello"}]]}""",
            completeClaim.resultJson ?: throw AssertionError("complete claim carries no resultJson"),
            "detached payload copy",
        )
        val done = support.assertCompleted(
            bridge.resume(operation(state, afterReceive), true, """{"ok":true}""", accepting),
            "complete resume",
        )
        support.assertCompleted(done, "managed task terminal")
    }

    @Test
    fun work_job_tostring_nonrevealing() {
        val state = workState()
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local work = require("talkcan.work")
                    return {
                      startup = function()
                        runtime = require("talkcan.runtime")
                        runtime.spawn(function()
                          local q = work.open("turns")
                          local job = q:receive()
                          local ts = tostring(job)
                          local mt = getmetatable(job)
                          job:complete({ tostring_val = ts, mt_locked = (mt == false) })
                        end)
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val started = spawnManagedTask(state)
        claimAdmitted(state, started, HostOperationKind.WORK_RECEIVE)
        val afterReceive = assertYielded(
            bridge.resume(operation(state, started), true, jobNull, accepting),
            "receive resume",
        )
        val completeClaim = claimAdmitted(state, afterReceive, HostOperationKind.WORK_COMPLETE)
        assertWorkValueEquals(
            """{"t":"map","v":[["mt_locked",{"t":"bool","v":true}],["tostring_val",{"t":"text","v":"opaque_job"}]]}""",
            completeClaim.resultJson ?: throw AssertionError("complete claim carries no resultJson"),
            "opaque job tostring",
        )
        val done = support.assertCompleted(
            bridge.resume(operation(state, afterReceive), true, """{"ok":true}""", accepting),
            "complete resume",
        )
        support.assertCompleted(done, "managed task terminal")
    }

    @Test
    fun work_effect_begin_commit_yields() {
        val state = workState()
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local work = require("talkcan.work")
                    return {
                      startup = function()
                        runtime = require("talkcan.runtime")
                        runtime.spawn(function()
                          local q = work.open("turns")
                          local job = q:receive()
                          local result = job:effect("key1", function()
                            return "effect_result"
                          end)
                          job:complete({ result = result })
                        end)
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val started = spawnManagedTask(state)
        claimAdmitted(state, started, HostOperationKind.WORK_RECEIVE)
        val afterReceive = assertYielded(
            bridge.resume(operation(state, started), true, jobNull, accepting),
            "receive resume",
        )
        claimAdmitted(state, afterReceive, HostOperationKind.WORK_BEGIN_EFFECT)
        val afterBegin = assertYielded(
            bridge.resume(operation(state, afterReceive), true, """{"replay":false}""", accepting),
            "begin effect resume",
        )
        claimAdmitted(state, afterBegin, HostOperationKind.WORK_COMMIT_EFFECT)
        val afterCommit = assertYielded(
            bridge.resume(operation(state, afterBegin), true, """{"ok":true}""", accepting),
            "commit effect resume",
        )
        claimAdmitted(state, afterCommit, HostOperationKind.WORK_COMPLETE)
        val done = support.assertCompleted(
            bridge.resume(operation(state, afterCommit), true, """{"ok":true}""", accepting),
            "complete resume",
        )
        support.assertCompleted(done, "managed task terminal")
    }

    @Test
    fun work_effect_committed_replay_skips_function() {
        val state = workState()
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local work = require("talkcan.work")
                    return {
                      startup = function()
                        runtime = require("talkcan.runtime")
                        runtime.spawn(function()
                          local q = work.open("turns")
                          local job = q:receive()
                          local called = false
                          local result = job:effect("key1", function()
                            called = true
                            return "new_result"
                          end)
                          job:complete({ result = result, called = called })
                        end)
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val started = spawnManagedTask(state)
        claimAdmitted(state, started, HostOperationKind.WORK_RECEIVE)
        val afterReceive = assertYielded(
            bridge.resume(operation(state, started), true, jobNull, accepting),
            "receive resume",
        )
        claimAdmitted(state, afterReceive, HostOperationKind.WORK_BEGIN_EFFECT)
        // Replay with a committed result skips the effect body and proceeds
        // straight to WORK_COMPLETE (no WORK_COMMIT_EFFECT).
        val afterBegin = assertYielded(
            bridge.resume(
                operation(state, afterReceive),
                true,
                """{"replay":true,"resultOk":true,"resultJson":"{\"t\":\"text\",\"v\":\"cached_result\"}"}""",
                accepting,
            ),
            "begin effect replay resume",
        )
        val completeClaim = claimAdmitted(state, afterBegin, HostOperationKind.WORK_COMPLETE)
        assertWorkValueEquals(
            """{"t":"map","v":[["called",{"t":"bool","v":false}],["result",{"t":"text","v":"cached_result"}]]}""",
            completeClaim.resultJson ?: throw AssertionError("complete claim carries no resultJson"),
            "replay skips effect function",
        )
        val done = support.assertCompleted(
            bridge.resume(operation(state, afterBegin), true, """{"ok":true}""", accepting),
            "complete resume",
        )
        support.assertCompleted(done, "managed task terminal")
    }

    @Test
    fun work_effect_multi_yield_function() {
        val state = workState(networkHttp = true)
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local work = require("talkcan.work")
                    local http = require("talkcan.http")
                    return {
                      startup = function()
                        runtime = require("talkcan.runtime")
                        runtime.spawn(function()
                          local q = work.open("turns")
                          local job = q:receive()
                          local result = job:effect("key1", function()
                            local resp = http.request({ method = "GET", url = "https://api.example.com/data" })
                            return resp.status
                          end)
                          job:complete({ result = result })
                        end)
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val started = spawnManagedTask(state)
        claimAdmitted(state, started, HostOperationKind.WORK_RECEIVE)
        val afterReceive = assertYielded(
            bridge.resume(operation(state, started), true, jobNull, accepting),
            "receive resume",
        )
        claimAdmitted(state, afterReceive, HostOperationKind.WORK_BEGIN_EFFECT)
        val afterBegin = assertYielded(
            bridge.resume(operation(state, afterReceive), true, """{"replay":false}""", accepting),
            "begin effect resume",
        )
        // HTTP_REQUEST yielded from inside the effect function.
        claimAdmitted(state, afterBegin, HostOperationKind.HTTP_REQUEST)
        val afterHttp = assertYielded(
            bridge.resume(operation(state, afterBegin), true, """{"status":200,"headers":{},"body":"ok"}""", accepting),
            "http resume",
        )
        claimAdmitted(state, afterHttp, HostOperationKind.WORK_COMMIT_EFFECT)
        val afterCommit = assertYielded(
            bridge.resume(operation(state, afterHttp), true, """{"ok":true}""", accepting),
            "commit effect resume",
        )
        claimAdmitted(state, afterCommit, HostOperationKind.WORK_COMPLETE)
        val done = support.assertCompleted(
            bridge.resume(operation(state, afterCommit), true, """{"ok":true}""", accepting),
            "complete resume",
        )
        support.assertCompleted(done, "managed task terminal")
    }

    @Test
    fun work_fail_yields_and_completes() {
        val state = workState()
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local work = require("talkcan.work")
                    return {
                      startup = function()
                        runtime = require("talkcan.runtime")
                        runtime.spawn(function()
                          local q = work.open("turns")
                          local job = q:receive()
                          job:fail("something went wrong")
                        end)
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val started = spawnManagedTask(state)
        claimAdmitted(state, started, HostOperationKind.WORK_RECEIVE)
        val afterReceive = assertYielded(
            bridge.resume(operation(state, started), true, jobNull, accepting),
            "receive resume",
        )
        claimAdmitted(state, afterReceive, HostOperationKind.WORK_FAIL)
        val done = support.assertCompleted(
            bridge.resume(operation(state, afterReceive), true, """{"ok":true}""", accepting),
            "fail resume",
        )
        support.assertCompleted(done, "managed task terminal")
    }

    @Test
    fun work_queue_userdata_locked_and_nonrevealing() {
        val state = workState()
        val outcome = support.assertCompleted(
            driveWorkInput(
                state,
                """
                local work = require("talkcan.work")
                return {
                  startup = function() end,
                  handle_input = function()
                    local q = work.open("turns")
                    local mt_locked = (getmetatable(q) == false)
                    local ts = tostring(q)
                    local field_fails = not pcall(function() return q.queue_id end)
                    local write_fails = not pcall(function() q.queue_id = "x" end)
                    return {
                      ok = true,
                      mt_locked = mt_locked,
                      tostring_val = ts,
                      field_fails = field_fails,
                      write_fails = write_fails,
                    }
                  end,
                }
                """.trimIndent(),
            ),
            "queue userdata probe",
        )
        val value = resultJson(outcome)
        // __index routes to the queue methods, so unknown reads return nil; writes
        // are denied by the locked metatable and tostring stays opaque.
        if (!value.optBoolean("mt_locked", false) ||
            value.optString("tostring_val") != "opaque_queue" ||
            !value.optBoolean("write_fails", false)
        ) {
            throw AssertionError("queue userdata must stay locked and nonrevealing: ${outcome.value}")
        }
    }

    @Test
    fun work_submit_failure_normalized() {
        val state = workState()
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local work = require("talkcan.work")
                    return {
                      startup = function() end,
                      handle_input = function()
                        local q = work.open("turns")
                        local ok, e = q:submit({ text = "hello" })
                        if ok then return { ok = true, unexpected = true } end
                        return { ok = true, error_code = e.error }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val yielded = assertYielded(invokeInput(state, captureEvent()), "submit yield")
        claimAdmitted(state, yielded, HostOperationKind.WORK_SUBMIT)
        val done = support.assertCompleted(
            bridge.resume(operation(state, yielded), false, "E_STORE", accepting),
            "submit failure resume",
        )
        if (resultJson(done).optString("error_code") != "E_STORE") {
            throw AssertionError("known submit failure must normalize exactly: ${done.value}")
        }
    }

    @Test
    fun work_submit_unknown_error_normalized_to_e_store() {
        val state = workState()
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local work = require("talkcan.work")
                    return {
                      startup = function() end,
                      handle_input = function()
                        local q = work.open("turns")
                        local ok, e = q:submit({ text = "hello" })
                        if ok then return { ok = true, unexpected = true } end
                        return { ok = true, error_code = e.error }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val yielded = assertYielded(invokeInput(state, captureEvent()), "submit yield")
        claimAdmitted(state, yielded, HostOperationKind.WORK_SUBMIT)
        val done = support.assertCompleted(
            bridge.resume(operation(state, yielded), false, "SOME_UNKNOWN_ERROR", accepting),
            "submit unknown failure resume",
        )
        if (resultJson(done).optString("error_code") != "E_STORE") {
            throw AssertionError("unknown submit failure must collapse to E_STORE: ${done.value}")
        }
    }

    @Test
    fun work_effect_wrong_arity() {
        val state = workState()
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local work = require("talkcan.work")
                    return {
                      startup = function()
                        runtime = require("talkcan.runtime")
                        runtime.spawn(function()
                          local q = work.open("turns")
                          local job = q:receive()
                          local ok, e = pcall(function()
                            job:effect("key1")
                          end)
                          job:complete({ pcall_ok = ok })
                        end)
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val started = spawnManagedTask(state)
        claimAdmitted(state, started, HostOperationKind.WORK_RECEIVE)
        val afterReceive = assertYielded(
            bridge.resume(operation(state, started), true, jobNull, accepting),
            "receive resume",
        )
        // Wrong-arity effect call is rejected in Lua (pcall) before any
        // suspension: the next yield is WORK_COMPLETE, never WORK_BEGIN_EFFECT.
        claimAdmitted(state, afterReceive, HostOperationKind.WORK_COMPLETE)
        val done = support.assertCompleted(
            bridge.resume(operation(state, afterReceive), true, """{"ok":true}""", accepting),
            "complete resume",
        )
        support.assertCompleted(done, "managed task terminal")
    }
}
