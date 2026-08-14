package io.talkcan.lua.kernel

import io.talkcan.lua.HostOperationClaim
import io.talkcan.lua.HostOperationKind
import io.talkcan.lua.JsonParsingResult
import io.talkcan.lua.LuaCallbackHandle
import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaOperationId
import io.talkcan.lua.LuaSpawnAdmission
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaValue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM conformance port of every category-E (eligibility/admission) Rust test
 * from the `replace-rust-lua-kernel` inventory. Each [Test] method maps
 * one-for-one to a Rust `#[test]` fn, preserving the exact name and
 * table-driven variants. All tests drive [io.talkcan.lua.LuaKernelBridge]
 * through [KotlinLuaKernelConformanceSupport] and assert observable outcomes.
 */
class KotlinLuaKernelAdmissionConformanceTest {

    private lateinit var s: KotlinLuaKernelConformanceSupport

    @Before
    fun createSupport() {
        s = KotlinLuaKernelConformanceSupport()
    }

    @After
    fun closeAllStates() {
        s.closeAll()
    }

    // ==================================================================
    // Shared helpers
    // ==================================================================

    private val captureEventJson =
        """{"metadata":{"duration_ms":1,"sample_rate":16000,"channels":1,"pcm_bytes":32}}"""

    private fun acceptAll(): LuaSpawnAdmission = object : LuaSpawnAdmission {
        override fun admitTask(coroutineId: Long): Int = 0
    }

    private fun luaValue(json: String): LuaValue {
        val result = LuaValue.fromJsonString(json)
        assertTrue("failed to parse LuaValue from: $json", result is JsonParsingResult.Success)
        return (result as JsonParsingResult.Success).value
    }

    /** Build an event JSON object with metadata injected (mirrors Rust invoke_input helper). */
    private fun eventWithMetadata(vararg fields: Pair<String, Any?>): String {
        val obj = JSONObject()
        for ((k, v) in fields) obj.put(k, v)
        obj.put("metadata", JSONObject().apply {
            put("sample_rate", 16000)
            put("channels", 1)
            put("duration_ms", 1)
            put("pcm_bytes", 32)
        })
        return obj.toString()
    }

    private fun invokeInput(
        handle: LuaStateHandle,
        eventJson: String = captureEventJson,
        token: String = "tok",
        admission: LuaSpawnAdmission = acceptAll(),
    ): LuaKernelOutcome =
        s.bridge.invokeInputCallback(
            handle,
            LuaCallbackHandle(handle, "handle_input"),
            luaValue(eventJson),
            token,
            admission,
        )

    private fun invokeSos(
        handle: LuaStateHandle,
        argsJson: String = """{"reason":"test"}""",
        admission: LuaSpawnAdmission = acceptAll(),
    ): LuaKernelOutcome =
        s.bridge.invokeSosCallback(
            handle,
            LuaCallbackHandle(handle, "handle_sos"),
            luaValue(argsJson),
            admission,
        )

    private fun installGrants(handle: LuaStateHandle, grantsJson: String) {
        s.assertCompleted(s.bridge.setProfileGrants(handle, grantsJson), "setProfileGrants")
    }

    private fun yielded(outcome: LuaKernelOutcome): LuaKernelOutcome.Yielded {
        assertTrue("expected Yielded but was $outcome", outcome is LuaKernelOutcome.Yielded)
        return outcome as LuaKernelOutcome.Yielded
    }

    private fun opHandle(
        handle: LuaStateHandle,
        coroutineId: Long,
        operationId: Long,
    ): LuaOperationHandle =
        LuaOperationHandle(handle, LuaCoroutineId(coroutineId), LuaOperationId(operationId))

    private fun resumeOp(
        handle: LuaStateHandle,
        coroutineId: Long,
        operationId: Long,
        success: Boolean,
        value: String,
        admission: LuaSpawnAdmission = acceptAll(),
    ): LuaKernelOutcome =
        s.bridge.resume(opHandle(handle, coroutineId, operationId), success, value, admission)

    private fun cancelOp(
        handle: LuaStateHandle,
        coroutineId: Long,
        operationId: Long,
    ): LuaKernelOutcome =
        s.bridge.cancel(opHandle(handle, coroutineId, operationId))

    private fun claimAdmitted(handle: LuaStateHandle, requestId: Long): HostOperationClaim.Admitted {
        val claim = s.bridge.claimHostOperation(handle, requestId)
        assertTrue("expected Admitted claim but was $claim", claim is HostOperationClaim.Admitted)
        return claim as HostOperationClaim.Admitted
    }

    private fun resultJson(outcome: LuaKernelOutcome): JSONObject =
        JSONObject(s.resultString(s.assertCompleted(outcome)))

    private fun loadSosImage(handle: LuaStateHandle, body: String) {
        val source = buildString {
            appendLine("local kb = require(\"talkcan.keyboard_output\")")
            appendLine("return {")
            appendLine("  startup = function() end,")
            appendLine("  handle_sos = function(event)")
            appendLine("    $body")
            appendLine("  end,")
            appendLine("}")
        }
        s.loadProgramImageOk(handle, "entry", mapOf("entry" to source))
    }

    private val oneGrantJson = JSONObject().apply {
        put("profiles", JSONArray().put(JSONObject().apply {
            put("profileId", "p1")
            put("typeLocalId", "openai-account")
            put("displayName", "Primary")
            put("values", JSONObject().apply {
                put("apiKey", JSONObject().apply { put("t", "text"); put("v", "public-123") })
            })
            put("secretReferences", JSONObject().put("token", "kotlin-minted-ref-token"))
        }))
    }.toString()

    private val resolverOneGrantJson = JSONObject().apply {
        put("profiles", JSONArray().put(JSONObject().apply {
            put("profileId", "p1")
            put("typeLocalId", "openai-account")
            put("displayName", "Primary")
            put("values", JSONObject())
            put("secretReferences", JSONObject().put("token", "kotlin-minted-ref-token"))
        }))
    }.toString()

    // ==================================================================
    // conformance.rs — bounded native host
    // ==================================================================

    @Test
    fun `bounded_native_host_completes_and_blocking_candidate_rejects_before_work`() {
        val handle = s.createState()
        s.loadSourceOk(
            handle,
            """
            function hash() return talkcan.host_hash("foobar") end
            function blocking()
              local success, value = talkcan.host_call("network")
              return tostring(success) .. ":" .. value
            end
            """.trimIndent(),
            "hash",
        )
        val hash = s.startEntryOk(handle)
        assertEquals(
            "the bounded native host must return the FNV-1a value without yielding",
            "bf9cf968",
            s.resultString(hash),
        )

        s.loadSourceOk(
            handle,
            "function blocking() local success, value = talkcan.host_call('network'); return tostring(success) .. ':' .. value end",
            "blocking",
        )
        val rejected = s.startEntryOk(handle)
        assertEquals(
            "a potentially blocking host operation must reject before external work rather than yield or run it",
            "false:rejected:network",
            s.resultString(rejected),
        )
        s.assertClosed(s.closeState(handle))
    }

    // ==================================================================
    // fs_conformance.rs — filesystem eligibility/admission
    // ==================================================================

    @Test
    fun `fs_capability_undeclared_rejects_mount`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"storageFiles":false,"mounts":{}}""")
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local fs = require("talkcan.fs")
                return {
                  startup = function() end,
                  handle_input = function(event)
                    local mount, err = fs.mount("data")
                    return {error={code="GOT",detail=err and err.error or "nil"}}
                  end,
                }
            """.trimIndent()),
        )
        val input = invokeInput(handle)
        val v = resultJson(input)
        assertEquals("E_CAPABILITY_UNDECLARED", v.getJSONObject("error").getString("detail"))
    }

    @Test
    fun `fs_invalid_path_rejected_before_effect`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(
            handle,
            """{"storageFiles":true,"mounts":{"data":{"access":"read-write","status":"available"}}}""",
        )
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local fs = require("talkcan.fs")
                return {
                  startup = function() end,
                  handle_input = function(event)
                    local mount = fs.mount("data")
                    local r, e = fs.mkdir(mount, "../escape", {parents=false})
                    return {error={code="GOT",detail=e and e.error or "nil"}}
                  end,
                }
            """.trimIndent()),
        )
        val input = invokeInput(handle)
        val v = resultJson(input)
        assertEquals("E_INVALID_PATH", v.getJSONObject("error").getString("detail"))
    }

    @Test
    fun `fs_read_only_mount_rejects_write`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(
            handle,
            """{"storageFiles":true,"mounts":{"ro":{"access":"read-only","status":"available"}}}""",
        )
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local fs = require("talkcan.fs")
                return {
                  startup = function() end,
                  handle_input = function(event)
                    local mount = fs.mount("ro")
                    local r, e = fs.write_text(mount, "f.txt", "data", {mode="create-new"})
                    return {error={code="GOT",detail=e and e.error or "nil"}}
                  end,
                }
            """.trimIndent()),
        )
        val input = invokeInput(handle)
        val v = resultJson(input)
        assertEquals("E_READ_ONLY", v.getJSONObject("error").getString("detail"))
    }

    @Test
    fun `fs_mount_unavailable_rejects_mount`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(
            handle,
            """{"storageFiles":true,"mounts":{"data":{"access":"read-write","status":"unavailable"}}}""",
        )
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local fs = require("talkcan.fs")
                return {
                  startup = function() end,
                  handle_input = function(event)
                    local mount, err = fs.mount("data")
                    return {error={code="GOT",detail=err and err.error or "nil"}}
                  end,
                }
            """.trimIndent()),
        )
        val input = invokeInput(handle)
        val v = resultJson(input)
        assertEquals("E_MOUNT_UNAVAILABLE", v.getJSONObject("error").getString("detail"))
    }

    @Test
    fun `fs_effect_call_during_load_rejected`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(
            handle,
            """{"storageFiles":true,"mounts":{"data":{"access":"read-write","status":"available"}}}""",
        )
        val outcome = s.loadProgramImage(
            handle,
            "entry",
            mapOf("entry" to """
                local fs = require("talkcan.fs")
                local mount = fs.mount("data")
                return {
                  startup = function() end,
                  handle_input = function(event) return {ok=true} end,
                }
            """.trimIndent()),
        )
        s.assertRuntimeFailure(outcome, "fs effect during module eval")
    }

    @Test
    fun `fs_io_from_startup_context_rejected`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(
            handle,
            """{"storageFiles":true,"mounts":{"data":{"access":"read-write","status":"available"}}}""",
        )
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local fs = require("talkcan.fs")
                return {
                  startup = function()
                    local mount = fs.mount("data")
                    local r, e = fs.stat(mount, "f.txt")
                    startup_result = { r = r, e = e and e.error or "nil" }
                  end,
                  probe = function() return startup_result end,
                  handle_input = function(event) return {ok=true} end,
                }
            """.trimIndent()),
        )
        // mount() is a synchronous lookup allowed in startup; stat() is I/O and
        // must fail E_INVALID_CONTEXT in a synchronous callback.
        s.assertCompleted(s.invokeCallback(handle, "startup"), "startup")
        val probe = resultJson(s.invokeCallback(handle, "probe"))
        assertTrue("r must be null", probe.isNull("r"))
        assertEquals("E_INVALID_CONTEXT", probe.getString("e"))
    }

    @Test
    fun `fs_unknown_mount_id_rejected`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(
            handle,
            """{"storageFiles":true,"mounts":{"data":{"access":"read-write","status":"available"}}}""",
        )
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local fs = require("talkcan.fs")
                return {
                  startup = function() end,
                  handle_input = function(event)
                    local mount, err = fs.mount("nonexistent")
                    return {error={code="GOT",detail=err and err.error or "nil"}}
                  end,
                }
            """.trimIndent()),
        )
        val input = invokeInput(handle)
        val v = resultJson(input)
        assertEquals("E_INVALID_ARGUMENT", v.getJSONObject("error").getString("detail"))
    }

    @Test
    fun `fs_needs_reauthorization_status_rejected`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(
            handle,
            """{"storageFiles":true,"mounts":{"data":{"access":"read-write","status":"needs-reauthorization"}}}""",
        )
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local fs = require("talkcan.fs")
                return {
                  startup = function() end,
                  handle_input = function(event)
                    local mount, err = fs.mount("data")
                    return {error={code="GOT",detail=err and err.error or "nil"}}
                  end,
                }
            """.trimIndent()),
        )
        val input = invokeInput(handle)
        val v = resultJson(input)
        assertEquals("E_REAUTHORIZATION_REQUIRED", v.getJSONObject("error").getString("detail"))
    }

    // ==================================================================
    // json_conformance.rs — pure calls during source evaluation
    // ==================================================================

    @Test
    fun `json_pure_calls_are_permitted_during_source_evaluation`() {
        val handle = s.createState()
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local json = require("talkcan.json")
                local encoded = json.encode({ ready = true })
                local decoded = json.decode('{"value":41}')
                local bumped = decoded.value + 1
                return {
                  startup = function() end,
                  probe = function()
                    return { encoded = encoded, bumped = bumped }
                  end,
                }
            """.trimIndent()),
        )
        val probe = resultJson(s.invokeCallback(handle, "probe"))
        assertEquals("""{"ready":true}""", probe.getString("encoded"))
        assertEquals(42, probe.getInt("bumped"))
    }

    // ==================================================================
    // keyboard_conformance.rs — keyboard-output admission
    // ==================================================================

    @Test
    fun `keyboard_output_call_during_module_evaluation_rejected`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"keyboardOutput":true}""")
        val outcome = s.loadProgramImage(
            handle,
            "entry",
            mapOf("entry" to """
                local kb = require("talkcan.keyboard_output")
                kb.send_text({ text = "during-load", profile = "linux:us" })
                return { startup = function() end }
            """.trimIndent()),
        )
        val rf = s.assertRuntimeFailure(outcome, "keyboard output during module eval")
        assertTrue(
            "diagnostic must mention effect-call-during-load: ${rf.diagnostic}",
            rf.diagnostic.contains("effect-call-during-load"),
        )
    }

    @Test
    fun `send_text_validates_exact_request_shape_and_bounds`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"keyboardOutput":true}""")
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to buildString {
                appendLine("local kb = require(\"talkcan.keyboard_output\")")
                appendLine("return {")
                appendLine("  startup = function() end,")
                appendLine("  handle_input = function(event)")
                appendLine("""    local function code(r, e) if r == nil then return e.error else return "ok" end end""")
                appendLine("    local out = {}")
                appendLine("""    out.non_table = code(kb.send_text("nope"))""")
                appendLine("""    out.arity = code(kb.send_text({ text = "x", profile = "p" }, 1))""")
                appendLine("""    out.missing_profile = code(kb.send_text({ text = "x" }))""")
                appendLine("""    out.missing_text = code(kb.send_text({ profile = "p" }))""")
                appendLine("""    out.extra_key = code(kb.send_text({ text = "x", profile = "p", extra = 1 }))""")
                appendLine("""    out.wrong_type = code(kb.send_text({ text = 1, profile = "p" }))""")
                appendLine("""    out.metatable = code(kb.send_text(setmetatable({ text = "x", profile = "p" }, {})))""")
                appendLine("""    out.empty_text = code(kb.send_text({ text = "", profile = "p" }))""")
                appendLine("""    out.big_text = code(kb.send_text({ text = string.rep("a", 16385), profile = "p" }))""")
                appendLine("""    out.blank_profile = code(kb.send_text({ text = "x", profile = "   " }))""")
                appendLine("""    out.empty_profile = code(kb.send_text({ text = "x", profile = "" }))""")
                appendLine("""    out.big_profile = code(kb.send_text({ text = "x", profile = string.rep("p", 257) }))""")
                appendLine("""    out.bad_utf8 = code(kb.send_text({ text = string.char(255, 254), profile = "p" }))""")
                appendLine("    return { codes = out }")
                appendLine("  end,")
                appendLine("}")
            }),
        )
        val v = resultJson(invokeInput(handle))
        val codes = v.getJSONObject("codes")
        for (key in listOf(
            "non_table", "arity", "missing_profile", "missing_text", "extra_key",
            "wrong_type", "metatable", "empty_text", "big_text", "blank_profile",
            "empty_profile", "big_profile", "bad_utf8",
        )) {
            assertEquals("$key must be E_INVALID_ARGUMENT", "E_INVALID_ARGUMENT", codes.getString(key))
        }
    }

    @Test
    fun `send_key_validates_semantic_key_vocabulary`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"keyboardOutput":true}""")
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to buildString {
                appendLine("local kb = require(\"talkcan.keyboard_output\")")
                appendLine("return {")
                appendLine("  startup = function() end,")
                appendLine("  handle_input = function(event)")
                appendLine("""    local kb = require("talkcan.keyboard_output")""")
                appendLine("""    local function code(r, e) if r == nil then return e.error else return "ok" end end""")
                appendLine("    local out = {}")
                appendLine("""    out.non_table = code(kb.send_key("enter"))""")
                appendLine("""    out.arity = code(kb.send_key({ key = "enter", profile = "p" }, 1))""")
                appendLine("""    out.tab = code(kb.send_key({ key = "tab", profile = "p" }))""")
                appendLine("""    out.enter_upper = code(kb.send_key({ key = "ENTER", profile = "p" }))""")
                appendLine("""    out.wrong_type = code(kb.send_key({ key = 1, profile = "p" }))""")
                appendLine("""    out.extra_key = code(kb.send_key({ key = "enter", profile = "p", text = "x" }))""")
                appendLine("""    out.missing_key = code(kb.send_key({ profile = "p" }))""")
                appendLine("""    out.blank_profile = code(kb.send_key({ key = "enter", profile = "  " }))""")
                appendLine("    return { codes = out }")
                appendLine("  end,")
                appendLine("}")
            }),
        )
        val v = resultJson(invokeInput(handle))
        val codes = v.getJSONObject("codes")
        assertEquals("E_INVALID_ARGUMENT", codes.getString("non_table"))
        assertEquals("E_INVALID_ARGUMENT", codes.getString("arity"))
        assertEquals("E_INVALID_VALUE", codes.getString("tab"))
        assertEquals("E_INVALID_VALUE", codes.getString("enter_upper"))
        assertEquals("E_INVALID_ARGUMENT", codes.getString("wrong_type"))
        assertEquals("E_INVALID_ARGUMENT", codes.getString("extra_key"))
        assertEquals("E_INVALID_ARGUMENT", codes.getString("missing_key"))
        assertEquals("E_INVALID_ARGUMENT", codes.getString("blank_profile"))
    }

    @Test
    fun `send_text_accepts_exact_byte_bounds`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"keyboardOutput":true}""")
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local kb = require("talkcan.keyboard_output")
                return {
                  startup = function() end,
                  handle_input = function(event)
                    local r, e = kb.send_text({ text = string.rep("a", 16384), profile = string.rep("p", 256) })
                    if not r then return { error = { code = "BOUND", detail = e.error } } end
                    return { ok = true }
                  end,
                }
            """.trimIndent()),
        )
        val y = yielded(invokeInput(handle))
        val requestId = y.value!!.toLong()
        val claim = claimAdmitted(handle, requestId)
        assertEquals(HostOperationKind.KEYBOARD_SEND_TEXT, claim.kind)
        assertEquals(16384, claim.text!!.length)
        assertEquals(256, claim.profile!!.length)
    }

    @Test
    fun `keyboard_output_requires_declared_capability`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{}""")
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to buildString {
                appendLine("local kb = require(\"talkcan.keyboard_output\")")
                appendLine("return {")
                appendLine("  startup = function() end,")
                appendLine("  handle_input = function(event)")
                appendLine("""    local kb = require("talkcan.keyboard_output")""")
                appendLine("""    local r, e = kb.send_text({ text = "x", profile = "p" })""")
                appendLine("""    return { codes = { undeclared = r == nil and e.error or "ok" } }""")
                appendLine("  end,")
                appendLine("}")
            }),
        )
        val v = resultJson(invokeInput(handle))
        assertEquals("E_CAPABILITY_UNDECLARED", v.getJSONObject("codes").getString("undeclared"))
    }

    @Test
    fun `keyboard_output_rejects_ineligible_execution_contexts`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"keyboardOutput":true}""")
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local kb = require("talkcan.keyboard_output")
                local function code(r, e) if r == nil then return e.error else return "ok" end end
                return {
                  startup = function()
                    local child_code
                    local child = coroutine.create(function()
                      return code(kb.send_text({ text = "x", profile = "p" }))
                    end)
                    local ok, result = coroutine.resume(child)
                    child_code = ok and result or "resume-failed"
                    return {
                      startup = code(kb.send_text({ text = "x", profile = "p" })),
                      unmanaged = child_code,
                    }
                  end,
                  handle_readiness = function()
                    return { ready = true, kb = code(kb.send_key({ key = "enter", profile = "p" })) }
                  end,
                }
            """.trimIndent()),
        )
        val startupValue = resultJson(s.invokeCallback(handle, "startup"))
        assertEquals("E_INVALID_CONTEXT", startupValue.getString("startup"))
        assertEquals("E_INVALID_CONTEXT", startupValue.getString("unmanaged"))
        val readiness = resultJson(s.invokeCallback(handle, "handle_readiness", LuaValue.Map(emptyMap())))
        assertEquals("E_INVALID_CONTEXT", readiness.getString("kb"))
    }

    @Test
    fun `sos_owner_denies_sleep_spawn_defer_and_raw_yield`() {
        data class Case(val name: String, val call: String, val expected: String)

        val cases = listOf(
            Case(
                "sleep",
                "local r, e = talkcan.runtime.sleep(0.1)\nreturn { denied = r == nil and e.error or \"ok\" }",
                "E_INVALID_CONTEXT",
            ),
            Case(
                "spawn",
                "local r, e = talkcan.runtime.spawn(function() end)\nreturn { denied = r == nil and e.error or \"ok\" }",
                "E_INVALID_CONTEXT",
            ),
            Case(
                "defer",
                "local r, e = talkcan.runtime.defer(function() end)\nreturn { denied = r == nil and e.error or \"ok\" }",
                "E_INVALID_CONTEXT",
            ),
        )

        for (case in cases) {
            val handle = s.createState(s.channelConfig())
            s.installResourceContext(handle, """{"keyboardOutput":true}""")
            loadSosImage(handle, case.call)
            val outcome = invokeSos(handle)
            val v = resultJson(outcome)
            assertEquals("${case.name} must be denied", case.expected, v.getString("denied"))
        }

        // A raw yield is never an authorized typed operation.
        val rawHandle = s.createState(s.channelConfig())
        s.installResourceContext(rawHandle, """{"keyboardOutput":true}""")
        loadSosImage(rawHandle, """coroutine.yield("raw")""")
        val rawOutcome = invokeSos(rawHandle)
        val rf = s.assertRuntimeFailure(rawOutcome, "raw yield in SOS")
        assertTrue(
            "diagnostic must mention E_INVALID_YIELD: ${rf.diagnostic}",
            rf.diagnostic.contains("E_INVALID_YIELD"),
        )
    }

    @Test
    fun `sos_owner_denies_audio_and_filesystem_effects`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(
            handle,
            """{"keyboardOutput":true,"storageFiles":true,"mounts":{"data":{"access":"read-write","status":"available"}}}""",
        )
        loadSosImage(
            handle,
            """
            local fs = require("talkcan.fs")
            local mount_ok, mount_err = fs.mount("data")
            local transcribe_code
            local transcription = require("talkcan.transcription")
            local t_ok, t_err = transcription.transcribe(0)
            transcribe_code = t_ok == nil and t_err.error or "ok"
            local io_code
            if mount_ok then
              local r, e = fs.stat(mount_ok, "x")
              io_code = r == nil and e.error or "ok"
            else
              io_code = "mount-failed"
            end
            return { transcribe = transcribe_code, io = io_code, mount = mount_ok ~= nil }
            """.trimIndent(),
        )
        val outcome = invokeSos(handle)
        val v = resultJson(outcome)
        // Mount is a synchronous lookup (not an effect) and stays available;
        // every I/O and audio effect is denied for the SOS owner.
        assertTrue("mount must succeed", v.getBoolean("mount"))
        assertEquals("E_INVALID_CONTEXT", v.getString("transcribe"))
        assertEquals("E_INVALID_CONTEXT", v.getString("io"))
    }

    // ==================================================================
    // profile_secret_http_conformance.rs — profile/secret/http admission
    // ==================================================================

    @Test
    fun `profile_get_missing_id_denied_without_revealing_existence`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"secretsRead":true}""")
        installGrants(handle, oneGrantJson)
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local profiles = require("talkcan.profiles")
                return {
                  startup = function() end,
                  handle_input = function()
                    local p, e = profiles.get("does-not-exist")
                    return { ok = (p == nil), code = e and e.error or "none" }
                  end,
                }
            """.trimIndent()),
        )
        val v = resultJson(invokeInput(handle))
        assertTrue("p must be nil", v.getBoolean("ok"))
        assertEquals("E_DENIED", v.getString("code"))
    }

    @Test
    fun `secret_read_without_capability_is_undeclared`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{}""")
        installGrants(handle, oneGrantJson)
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
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
            """.trimIndent()),
        )
        // Denied synchronously: no host operation is yielded.
        val v = resultJson(invokeInput(handle))
        assertTrue("plaintext must be nil", v.getBoolean("ok"))
        assertEquals("E_CAPABILITY_UNDECLARED", v.getString("code"))
    }

    @Test
    fun `secret_read_rejects_string_reference`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"secretsRead":true}""")
        installGrants(handle, oneGrantJson)
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local secrets = require("talkcan.secrets")
                return {
                  startup = function() end,
                  handle_input = function()
                    local plaintext, e = secrets.read("kotlin-minted-ref-token")
                    return { ok = (plaintext == nil), code = e and e.error or "none" }
                  end,
                }
            """.trimIndent()),
        )
        val v = resultJson(invokeInput(handle))
        assertTrue("plaintext must be nil", v.getBoolean("ok"))
        assertEquals("E_INVALID_ARGUMENT", v.getString("code"))
    }

    @Test
    fun `http_request_rejects_non_https_url_before_effect`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"networkHttp":true}""")
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local http = require("talkcan.http")
                return {
                  startup = function() end,
                  handle_input = function()
                    local resp, e = http.request({ method = "GET", url = "http://insecure.example.com" })
                    return { ok = (resp == nil), code = e and e.error or "none" }
                  end,
                }
            """.trimIndent()),
        )
        val v = resultJson(invokeInput(handle))
        assertTrue("resp must be nil", v.getBoolean("ok"))
        assertEquals("E_INVALID_VALUE", v.getString("code"))
    }

    @Test
    fun `http_request_without_capability_is_undeclared`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{}""")
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local http = require("talkcan.http")
                return {
                  startup = function() end,
                  handle_input = function()
                    local resp, e = http.request({ method = "GET", url = "https://api.example.com" })
                    return { ok = (resp == nil), code = e and e.error or "none" }
                  end,
                }
            """.trimIndent()),
        )
        val v = resultJson(invokeInput(handle))
        assertTrue("resp must be nil", v.getBoolean("ok"))
        assertEquals("E_CAPABILITY_UNDECLARED", v.getString("code"))
    }

    @Test
    fun `http_source_eval_denied_via_pcall`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"networkHttp":true}""")
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local http = require("talkcan.http")
                local ok, err = pcall(function()
                  http.request({ method = "GET", url = "https://x.example.com" })
                end)
                return {
                  startup = function() end,
                  handle_input = function()
                    return { ok = true, pcall_ok = ok, err = tostring(err) }
                  end,
                }
            """.trimIndent()),
        )
        val v = resultJson(invokeInput(handle))
        assertTrue("pcall must catch the error", !v.getBoolean("pcall_ok"))
        assertTrue(
            "error must mention effect-call-during-load: ${v.getString("err")}",
            v.getString("err").contains("effect-call-during-load"),
        )
    }

    @Test
    fun `http_startup_callback_cannot_yield`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"networkHttp":true}""")
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local http = require("talkcan.http")
                return {
                  startup = function()
                    local r, e = http.request({ method = "GET", url = "https://x.example.com" })
                    return { attempted = true }
                  end,
                }
            """.trimIndent()),
        )
        val outcome = s.invokeCallback(handle, "startup", LuaValue.Nil, acceptAll())
        // Startup is synchronous; attempting to yield must not produce a Yielded outcome.
        assertTrue("HTTP in startup must not yield", outcome !is LuaKernelOutcome.Yielded)
    }

    @Test
    fun `http_sos_callback_denied`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"networkHttp":true}""")
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local http = require("talkcan.http")
                return {
                  startup = function() end,
                  handle_sos = function()
                    local r, e = http.request({ method = "GET", url = "https://x.example.com" })
                    if r then return { ok = true, unexpected = true } end
                    return { ok = true, code = e and e.error or "none" }
                  end,
                }
            """.trimIndent()),
        )
        val outcome = s.bridge.invokeSosCallback(
            handle,
            LuaCallbackHandle(handle, "handle_sos"),
            luaValue("{}"),
            acceptAll(),
        )
        val v = resultJson(outcome)
        assertEquals("E_INVALID_CONTEXT", v.getString("code"))
    }

    // ==================================================================
    // resolver_conformance.rs — resolver-mode capability denials
    // ==================================================================

    @Test
    fun `resolver_undeclared_secret_capability_is_package_error`() {
        val handle = s.createResolverState()
        installGrants(handle, resolverOneGrantJson)
        val source = """
            local profiles = require("talkcan.profiles")
            local secrets = require("talkcan.secrets")
            return {
              resolve = function()
                local p = profiles.get("p1")
                local key = secrets.read(p.secrets.token)
                return { choices = { { value = key, label = "x" } } }, nil
              end,
            }
        """.trimIndent()
        // secretsRead capability NOT granted in the invocation.
        val outcome = s.invokeResolver(handle, s.resolverInvocation(source))
        val v = resultJson(outcome)
        assertEquals("invokeResolver", v.getString("operation"))
        assertEquals("package_error", v.getString("resultKind"))
    }

    @Test
    fun `resolver_denied_spawn`() {
        val handle = s.createResolverState()
        val source = """
            local runtime = require("talkcan.runtime")
            return {
              resolve = function()
                local ok, e = runtime.spawn(function() end)
                if ok then return { choices = { { value = "spawned", label = "x" } } }, nil end
                return { choices = { { value = "denied:" .. tostring(e and e.error or "nil"), label = "x" } } }, nil
              end,
            }
        """.trimIndent()
        val outcome = s.invokeResolver(handle, s.resolverInvocation(source))
        val v = resultJson(outcome)
        assertEquals("choices", v.getString("resultKind"))
        assertTrue(
            "spawn must be denied in resolver context",
            v.getJSONArray("choices").getJSONObject(0).getString("value").startsWith("denied:"),
        )
    }

    @Test
    fun `resolver_denied_sleep`() {
        val handle = s.createResolverState()
        val source = """
            local runtime = require("talkcan.runtime")
            return {
              resolve = function()
                local ok, e = runtime.sleep(1)
                if ok then return { choices = { { value = "slept", label = "x" } } }, nil end
                return { choices = { { value = "denied:" .. tostring(e and e.error or "nil"), label = "x" } } }, nil
              end,
            }
        """.trimIndent()
        val outcome = s.invokeResolver(handle, s.resolverInvocation(source))
        val v = resultJson(outcome)
        assertEquals("choices", v.getString("resultKind"))
        assertTrue(
            "sleep must be denied in resolver context",
            v.getJSONArray("choices").getJSONObject(0).getString("value").startsWith("denied:"),
        )
    }

    @Test
    fun `resolver_denied_work_open`() {
        val handle = s.createResolverState()
        val source = """
            local work = require("talkcan.work")
            return {
              resolve = function()
                local q, e = work.open("turns")
                if q then return { choices = { { value = "opened", label = "x" } } }, nil end
                return { choices = { { value = "denied:" .. tostring(e and e.error or "nil"), label = "x" } } }, nil
              end,
            }
        """.trimIndent()
        val outcome = s.invokeResolver(handle, s.resolverInvocation(source))
        val v = resultJson(outcome)
        assertEquals("choices", v.getString("resultKind"))
        assertTrue(
            "work.open must be denied in resolver context",
            v.getJSONArray("choices").getJSONObject(0).getString("value").startsWith("denied:"),
        )
    }

    @Test
    fun `resolver_denied_audio_fs_keyboard`() {
        val handle = s.createResolverState()
        val source = """
            local fs = require("talkcan.fs")
            return {
              resolve = function()
                -- fs.mount requires storageFiles capability which resolver never has
                local m, e = fs.mount("data")
                if m then return { choices = { { value = "mounted", label = "x" } } }, nil end
                return { choices = { { value = "denied:" .. tostring(e and e.error or "nil"), label = "x" } } }, nil
              end,
            }
        """.trimIndent()
        val outcome = s.invokeResolver(handle, s.resolverInvocation(source))
        val v = resultJson(outcome)
        assertEquals("choices", v.getString("resultKind"))
        assertTrue(
            "fs/audio/keyboard must be denied in resolver context",
            v.getJSONArray("choices").getJSONObject(0).getString("value").startsWith("denied:"),
        )
    }

    // ==================================================================
    // runtime_v1_conformance.rs — spawn/timer/callback admission
    // ==================================================================

    @Test
    fun `legacy_unscoped_start_rejects_spawn_without_retaining_a_child_task`() {
        val handle = s.createState(s.runtimeV1Config())
        s.loadSourceOk(
            handle,
            """
            function main()
              local accepted, error = talkcan.runtime.spawn(function() end)
              return tostring(accepted) .. ":" .. tostring(error and error.error)
            end
            """.trimIndent(),
            "main",
        )
        val started = s.startEntryOk(handle)
        assertEquals("nil:E_INVALID_CONTEXT", s.resultString(started))
        assertTrue(
            "legacy start must not retain spawnedCoroutines",
            started.spawnedCoroutines == null,
        )
        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `host_calls_during_entry_or_lazy_module_evaluation_fail_and_discard_the_complete_image`() {
        data class Case(val name: String, val effect: String)

        val cases = listOf(
            Case("entry spawn", "local runtime = require('talkcan.runtime'); runtime.spawn(function() end)"),
            Case("entry sleep", "local runtime = require('talkcan.runtime'); runtime.sleep(1)"),
            Case("entry log", "local log = require('talkcan.log'); log.info({message = 'during load'})"),
        )

        for (case in cases) {
            val handle = s.createState(s.runtimeV1Config())
            val failed = s.loadProgramImage(
                handle,
                "entry",
                mapOf("entry" to "${case.effect}; return { startup = function() end }"),
            )
            val rf = s.assertRuntimeFailure(failed, case.name)
            assertTrue(
                "${case.name} must fail with effect-call-during-load: ${rf.diagnostic}",
                rf.diagnostic.contains("effect-call-during-load"),
            )

            // Clean reload with same names.
            s.loadProgramImageOk(handle, "entry", mapOf("entry" to "return { startup = function() end }"))
            val startup = s.assertCompleted(s.invokeCallback(handle, "startup"), "${case.name} startup")
            assertTrue(
                "failed ${case.name} image must not retain an admitted child",
                startup.spawnedCoroutines == null,
            )
            s.assertClosed(s.closeState(handle))
        }

        // Lazy-module effect also rolls back.
        val lazyHandle = s.createState(s.runtimeV1Config())
        val failedLazy = s.loadProgramImage(
            lazyHandle,
            "entry",
            mapOf(
                "entry" to """
                    local lazy = require("plugin.lazy")
                    return { startup = function() end, handle_readiness = function() return { ready = lazy.value == "failed" } end }
                """.trimIndent(),
                "plugin.lazy" to """
                    local log = require("talkcan.log")
                    log.info({message = "lazy module effect"})
                    return { value = "failed" }
                """.trimIndent(),
            ),
        )
        val lazyRf = s.assertRuntimeFailure(failedLazy, "lazy module effect")
        assertTrue(lazyRf.diagnostic.contains("effect-call-during-load"))

        // A successful replacement using the same module names proves the failed
        // lazy evaluation did not retain its callback table or module result.
        s.loadProgramImageOk(
            lazyHandle,
            "entry",
            mapOf(
                "entry" to """
                    local lazy = require("plugin.lazy")
                    return {
                        startup = function() end,
                        handle_readiness = function() return { ready = lazy.value == "reloaded" } end,
                    }
                """.trimIndent(),
                "plugin.lazy" to "return { value = 'reloaded' }",
            ),
        )
        val readiness = resultJson(s.invokeCallback(lazyHandle, "handle_readiness"))
        assertTrue("reloaded lazy module must report ready", readiness.getBoolean("ready"))
        s.assertClosed(s.closeState(lazyHandle))
    }

    @Test
    fun `invalid_callback_and_unmanaged_child_context_pairs_complete_without_effects`() {
        val handle = s.createState(
            LuaKernelConfig(
                hookInterval = 100,
                instructionBudget = 50_000,
                maxConcurrentTasks = 2,
                maxTimerSlots = 2,
            ),
        )
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local runtime = require("talkcan.runtime")
                return {
                    startup = function() end,
                    handle_sos = function()
                        local spawn_value, spawn_error = runtime.spawn(function() end)
                        local sleep_value, sleep_error = runtime.sleep(1)
                        local child = coroutine.create(function()
                            local child_spawn, child_spawn_error = runtime.spawn(function() end)
                            local child_sleep, child_sleep_error = runtime.sleep(1)
                            return {
                                spawn_nil = child_spawn == nil,
                                spawn_error = child_spawn_error.error,
                                sleep_nil = child_sleep == nil,
                                sleep_error = child_sleep_error.error,
                            }
                        end)
                        local resumed, child_result = coroutine.resume(child)
                        return {
                            spawn_nil = spawn_value == nil,
                            spawn_error = spawn_error.error,
                            sleep_nil = sleep_value == nil,
                            sleep_error = sleep_error.error,
                            child_resumed = resumed,
                            child_result = child_result,
                        }
                    end,
                }
            """.trimIndent()),
        )
        val outcome = s.invokeCallback(handle, "handle_sos")
        val v = resultJson(outcome)
        assertTrue(v.getBoolean("spawn_nil"))
        assertEquals("E_INVALID_CONTEXT", v.getString("spawn_error"))
        assertTrue(v.getBoolean("sleep_nil"))
        assertEquals("E_INVALID_CONTEXT", v.getString("sleep_error"))
        assertTrue(v.getBoolean("child_resumed"))
        val childResult = v.getJSONObject("child_result")
        assertTrue(childResult.getBoolean("spawn_nil"))
        assertEquals("E_INVALID_CONTEXT", childResult.getString("spawn_error"))
        assertTrue(childResult.getBoolean("sleep_nil"))
        assertEquals("E_INVALID_CONTEXT", childResult.getString("sleep_error"))
        val completed = s.assertCompleted(outcome)
        assertTrue("invalid contexts must not admit a managed task", completed.spawnedCoroutines == null)
        assertTrue("invalid contexts must not retain a host log effect", completed.logs == null)
        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `admitted_startup_spawn_capacity_releases_after_completion_and_cancellation`() {
        val handle = s.createState(
            LuaKernelConfig(
                hookInterval = 100,
                instructionBudget = 50_000,
                maxConcurrentTasks = 1,
                maxTimerSlots = 2,
            ),
        )
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local runtime = require("talkcan.runtime")
                local phase = 0
                return {
                  startup = function()
                    phase = phase + 1
                    local child
                    if phase == 2 then
                      child = function() return runtime.sleep(1) end
                    else
                      child = function() return "completed task" end
                    end
                    local first, first_error = runtime.spawn(child)
                    local second, second_error = runtime.spawn(function() return "must not run" end)
                    return {
                      phase = phase,
                      first = first,
                      first_error_is_nil = first_error == nil,
                      second_is_nil = second == nil,
                      second_error = second_error.error,
                    }
                  end,
                }
            """.trimIndent()),
        )

        fun invokeStartup(): LuaKernelOutcome.Completed =
            s.assertCompleted(
                s.bridge.invokeCallback(
                    handle,
                    LuaCallbackHandle(handle, "startup"),
                    LuaValue.Nil,
                    acceptAll(),
                ),
                "startup",
            )

        fun assertCapacity(outcome: LuaKernelOutcome.Completed, phase: Int) {
            val v = JSONObject(outcome.value!!)
            assertEquals(phase, v.getInt("phase"))
            assertTrue(v.getBoolean("first"))
            assertTrue(v.getBoolean("first_error_is_nil"))
            assertTrue(v.getBoolean("second_is_nil"))
            assertEquals("E_BUSY", v.getString("second_error"))
        }

        fun admittedId(outcome: LuaKernelOutcome.Completed): Long {
            val ids = outcome.spawnedCoroutines
                ?: throw AssertionError("accepted spawn omitted coroutine identity")
            assertEquals("capacity rejection retained an extra task", 1, ids.size)
            return ids[0]
        }

        val first = invokeStartup()
        assertCapacity(first, 1)
        val firstId = admittedId(first)
        val completed = s.assertCompleted(
            s.bridge.startCoroutine(handle, LuaCoroutineId(firstId), acceptAll()),
            "startCoroutine(first)",
        )
        assertEquals("completed task", s.resultString(completed))

        val sleeping = invokeStartup()
        assertCapacity(sleeping, 2)
        val sleepingId = admittedId(sleeping)
        val yieldedOutcome = s.bridge.startCoroutine(handle, LuaCoroutineId(sleepingId), acceptAll())
        val y = yielded(yieldedOutcome)
        val cancelled = cancelOp(handle, y.coroutineId, y.operationId)
        assertTrue("cancel must succeed", cancelled is LuaKernelOutcome.Cancelled)

        val afterCancel = invokeStartup()
        assertCapacity(afterCancel, 3)
        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `captured_audio_userdata_input_callback_behavior`() {
        val handle = s.createState(s.runtimeV1Config())
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                saved_userdata = nil

                return {
                  startup = function() end,
                  handle_input = function(event)
                    local audio_userdata = event.audio
                    saved_userdata = audio_userdata

                    if type(audio_userdata) ~= "userdata" then
                      return { error = { code = "E_TYPE", detail = "expected userdata, got " .. type(audio_userdata) } }
                    end
                    if tostring(audio_userdata) ~= "opaque_audio" then
                      return { error = { code = "E_TOSTRING", detail = "expected opaque_audio, got " .. tostring(audio_userdata) } }
                    end
                    if getmetatable(audio_userdata) ~= false then
                      return { error = { code = "E_METATABLE", detail = "expected locked metatable" } }
                    end

                    -- Verify no properties can be accessed or set
                    local ok, res = pcall(function() return audio_userdata.token end)
                    if ok then
                      return { error = { code = "E_PROPERTY_GET", detail = "property get succeeded" } }
                    end
                    local ok_write, res_write = pcall(function() audio_userdata.token = "new_token" end)
                    if ok_write then
                      return { error = { code = "E_PROPERTY_SET", detail = "property set succeeded" } }
                    end
                    local ok_setmt, res_setmt = pcall(function() setmetatable(audio_userdata, {}) end)
                    if ok_setmt then
                      return { error = { code = "E_SETMETATABLE", detail = "setmetatable succeeded" } }
                    end

                    return { ok = true }
                  end,

                  get_saved_tostring = function()
                    return tostring(saved_userdata)
                  end,

                  get_saved_userdata_directly = function()
                    return saved_userdata
                  end,

                  get_saved_userdata_nested = function()
                    return { ok = true, data = saved_userdata }
                  end,
                }
            """.trimIndent()),
        )

        // 1. Invoke input callback, passing arguments and a token.
        val outcome = invokeInput(
            handle,
            eventWithMetadata("foo" to "bar"),
            "secret_token_12345",
        )
        val v = resultJson(outcome)
        assertTrue("input must succeed", v.getBoolean("ok"))

        // 2. Verify that tostring does not expose the token.
        val tostringOutcome = s.invokeCallback(handle, "get_saved_tostring")
        assertEquals("opaque_audio", s.resultString(s.assertCompleted(tostringOutcome)))

        // 3. Returning invalid userdata directly or nested is rejected atomically.
        val directOutcome = s.invokeCallback(handle, "get_saved_userdata_directly")
        val directRf = s.assertRuntimeFailure(directOutcome, "direct userdata return")
        assertTrue(
            "must reject with E_INVALID_VALUE: ${directRf.diagnostic}",
            directRf.diagnostic.contains("E_INVALID_VALUE"),
        )

        val nestedOutcome = s.invokeCallback(handle, "get_saved_userdata_nested")
        val nestedRf = s.assertRuntimeFailure(nestedOutcome, "nested userdata return")
        assertTrue(
            "must reject with E_INVALID_VALUE: ${nestedRf.diagnostic}",
            nestedRf.diagnostic.contains("E_INVALID_VALUE"),
        )

        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `native_input_userdata_boundary_consults_admission_only_at_runtime_spawn_and_never_leaks_token`() {
        val handle = s.createState(s.runtimeV1Config())
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local runtime = require("talkcan.runtime")

                return {
                  startup = function() end,
                  handle_input = function(event)
                    local audio_userdata = event.audio
                    local report = {
                      invoked = true,
                      type = type(audio_userdata),
                      args_source = event.source,
                      tostring = tostring(audio_userdata),
                      metatable_locked = getmetatable(audio_userdata) == false,
                    }

                    local ok_get = pcall(function() return audio_userdata.token end)
                    report.property_get_blocked = not ok_get
                    local ok_set = pcall(function() audio_userdata.token = "leak" end)
                    report.property_set_blocked = not ok_set
                    local ok_mt = pcall(function() setmetatable(audio_userdata, {}) end)
                    report.setmetatable_blocked = not ok_mt

                    local spawn_ok, spawn_err = runtime.spawn(function() end)
                    report.spawn_admitted = (spawn_ok == true)
                    report.spawn_error = spawn_err and spawn_err.error

                    return report
                  end,
                }
            """.trimIndent()),
        )

        val secretToken = "HOST_ADMISSION_TOKEN_MUST_NOT_LEAK_1234567890"

        for (label in listOf("Accepted", "Rejected", "Closed", "Capacity")) {
            var consulted = 0
            val admission = object : LuaSpawnAdmission {
                override fun admitTask(coroutineId: Long): Int {
                    consulted++
                    return when (label) {
                        "Accepted" -> 0
                        "Rejected", "Closed" -> 1
                        else -> 2
                    }
                }
            }

            val outcome = invokeInput(
                handle,
                eventWithMetadata("source" to label),
                secretToken,
                admission,
            )

            val report = resultJson(outcome)
            assertTrue("$label: handle_input must be invoked", report.getBoolean("invoked"))
            assertEquals("$label: arguments must reach handle_input unmodified", label, report.getString("args_source"))
            assertEquals("$label: SpawnAdmitter must not be consulted", 0, consulted)
            assertEquals("$label: must receive userdata", "userdata", report.getString("type"))
            assertEquals("$label: tostring must not leak", "opaque_audio", report.getString("tostring"))
            assertTrue("$label: metatable must be locked", report.getBoolean("metatable_locked"))
            assertTrue("$label: property get must be blocked", report.getBoolean("property_get_blocked"))
            assertTrue("$label: property set must be blocked", report.getBoolean("property_set_blocked"))
            assertTrue("$label: setmetatable must be blocked", report.getBoolean("setmetatable_blocked"))
            assertTrue("$label: spawn must not be admitted", !report.getBoolean("spawn_admitted"))
            assertEquals("$label: spawn must be E_INVALID_CONTEXT", "E_INVALID_CONTEXT", report.getString("spawn_error"))

            // The raw token string must not appear anywhere in the outcome JSON.
            val outcomeStr = s.resultString(s.assertCompleted(outcome))
            assertTrue("$label: token leaked into outcome", !outcomeStr.contains(secretToken))
        }

        // Accepted admission does not grant userdata escape authority.
        val escapeHandle = s.createState(s.runtimeV1Config())
        s.loadProgramImageOk(
            escapeHandle,
            "entry",
            mapOf("entry" to """
                local saved
                return {
                  startup = function() end,
                  handle_input = function(event)
                    local audio = event.audio
                    saved = audio
                    return { ok = true }
                  end,
                  leak_direct = function() return saved end,
                  leak_nested = function() return { payload = saved } end,
                }
            """.trimIndent()),
        )
        val accepted = invokeInput(escapeHandle, eventWithMetadata("source" to "captured"), secretToken)
        val acceptedV = resultJson(accepted)
        assertTrue(acceptedV.getBoolean("ok"))

        val direct = s.invokeCallback(escapeHandle, "leak_direct")
        val directRf = s.assertRuntimeFailure(direct, "direct leak")
        assertTrue(directRf.diagnostic.contains("E_INVALID_VALUE"))

        val nested = s.invokeCallback(escapeHandle, "leak_nested")
        val nestedRf = s.assertRuntimeFailure(nested, "nested leak")
        assertTrue(nestedRf.diagnostic.contains("E_INVALID_VALUE"))

        s.assertClosed(s.closeState(handle))
        s.assertClosed(s.closeState(escapeHandle))
    }

    @Test
    fun `transcription_input_borrows_userdata_and_normalizes_validation_and_failures`() {
        val handle = s.createState(s.runtimeV1Config())
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local t = require("talkcan.transcription")
                return {
                  startup = function() end,
                  outside = function() local x,e=t.transcribe("bad") return {text=x,error=e and e.error} end,
                  handle_input = function(event)
                    if event and event.invalid then local x,e=t.transcribe("bad") return {text=x,error=e and e.error} end
                    if event and event.fail then local x,e=t.transcribe(event.audio) return {text=x,error=e and e.error} end
                    local x,e=t.transcribe(event.audio); local y,f=t.transcribe(event.audio)
                    return {first=x,first_error=e,second=y,second_error=f}
                  end,
                }
            """.trimIndent()),
        )

        // Outside input context: E_INVALID_CONTEXT.
        val outside = resultJson(s.invokeCallback(handle, "outside"))
        assertEquals("E_INVALID_CONTEXT", outside.getString("error"))

        // Valid transcription: two sequential yields.
        val input = invokeInput(handle, eventWithMetadata(), "captured-token")
        val y1 = yielded(input)
        val requestId1 = y1.value!!.toLong()
        val claim1 = claimAdmitted(handle, requestId1)
        assertEquals(HostOperationKind.TRANSCRIBE, claim1.kind)
        assertEquals("captured-token", claim1.audioToken)

        val first = resumeOp(handle, y1.coroutineId, y1.operationId, true, "héllo")
        val y2 = yielded(first)
        val second = resumeOp(handle, y2.coroutineId, y2.operationId, true, "world")
        val secondV = resultJson(second)
        assertEquals("héllo", secondV.getJSONObject("first").getString("text"))
        assertEquals("world", secondV.getJSONObject("second").getString("text"))

        // Invalid argument: non-userdata.
        val invalid = invokeInput(handle, eventWithMetadata("invalid" to true), "captured-token")
        val invalidV = resultJson(invalid)
        assertEquals("E_INVALID_ARGUMENT", invalidV.getString("error"))

        // Failure normalization: stable codes pass through, unknown collapse.
        val allowed = listOf(
            "E_INVALID_ARGUMENT", "E_INVALID_VALUE", "E_INVALID_CONTEXT",
            "E_CAPABILITY_UNDECLARED", "E_UNAVAILABLE", "E_BUSY", "E_TIMEOUT",
            "E_CANCELLED", "E_CLOSED", "E_STALE", "E_HOST_FAILURE",
        )
        val injectedList = allowed + listOf(
            "panic: /endpoint=https://secret.example credential=top-secret transport reset",
            "unknown backend detail",
        )
        for (injected in injectedList) {
            val failed = invokeInput(handle, eventWithMetadata("fail" to true), "captured-token")
            val fy = yielded(failed)
            val failedResult = resumeOp(handle, fy.coroutineId, fy.operationId, false, injected)
            val failedV = resultJson(failedResult)
            val expected = if (injected.startsWith("E_")) injected else "E_HOST_FAILURE"
            assertEquals("injected=$injected", expected, failedV.getString("error"))
            val failedStr = s.resultString(s.assertCompleted(failedResult))
            assertTrue("must not leak secret.example", !failedStr.contains("secret.example"))
            assertTrue("must not leak top-secret", !failedStr.contains("top-secret"))
            assertTrue("must not leak transport reset", !failedStr.contains("transport reset"))
        }
        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `transcription_non_userdata_in_input_is_invalid_argument_without_host_admission`() {
        val handle = s.createState(s.runtimeV1Config())
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local t = require("talkcan.transcription")
                return { startup = function() end, handle_input = function(event) local x,e=t.transcribe("bad") return {x=x,error=e and e.error} end }
            """.trimIndent()),
        )
        var consulted = 0
        val admission = object : LuaSpawnAdmission {
            override fun admitTask(coroutineId: Long): Int { consulted++; return 0 }
        }
        val outcome = invokeInput(handle, eventWithMetadata(), "token", admission)
        val v = resultJson(outcome)
        assertEquals("E_INVALID_ARGUMENT", v.getString("error"))
        assertEquals("admitter must not be consulted", 0, consulted)
        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `synthesis_operation_validates_parameters_and_resumes_opaque_userdata`() {
        val handle = s.createState(s.runtimeV1Config())
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local s = require("talkcan.synthesis")
                return {
                  startup = function() end,
                  outside = function() local x,e=s.synthesize({text="x",language="en-US",voice="v"}); return e and e.error end,
                  handle_input = function(event)
                    local a = event or {}
                    local p
                    if a.k == "missing_text" then p={language="en-US",voice="v"}
                    elseif a.k == "extra" then p={text="x",language="en-US",voice="v",extra="x"}
                    elseif a.k == "blank_text" then p={text=" ",language="en-US",voice="v"}
                    elseif a.k == "long_text" then p={text=string.rep("x",16385),language="en-US",voice="v"}
                    elseif a.k == "bad_language" then p={text="x",language="en_US",voice="v"}
                    elseif a.k == "missing_voice" then p={text="x",language="en-US"}
                    elseif a.k == "blank_voice" then p={text="x",language="en-US",voice=" "}
                    elseif a.k == "long_voice" then p={text="x",language="en-US",voice=string.rep("v",129)}
                    elseif a.k == "nan" then p={text="x",language="en-US",voice="v",speed=0/0}
                    elseif a.k == "infinite" then p={text="x",language="en-US",voice="v",speed=1/0}
                    elseif a.k == "zero" then p={text="x",language="en-US",voice="v",speed=0}
                    elseif a.k == "negative" then p={text="x",language="en-US",voice="v",speed=-1}
                    else p={text="hello",language="en-US",voice="v"} end
                    local x,e=s.synthesize(p)
                    if e then return {error=e.error} end
                    return {text=tostring(x), meta=getmetatable(x)}
                  end,
                }
            """.trimIndent()),
        )

        // Outside input context: E_INVALID_CONTEXT.
        val outside = s.invokeCallback(handle, "outside")
        assertEquals("E_INVALID_CONTEXT", s.resultString(s.assertCompleted(outside)))

        // Parameter validation cases.
        for (k in listOf(
            "missing_text", "extra", "blank_text", "long_text", "bad_language",
            "missing_voice", "blank_voice", "long_voice", "nan", "infinite", "zero", "negative",
        )) {
            val out = invokeInput(handle, eventWithMetadata("k" to k), "token")
            val v = resultJson(out)
            assertEquals("case $k", "E_INVALID_ARGUMENT", v.getString("error"))
        }

        // Valid synthesis yields an opaque request.
        val valid = invokeInput(handle, eventWithMetadata("k" to "valid"), "token")
        val vy = yielded(valid)
        val requestId = vy.value!!.toLong()
        val claim = claimAdmitted(handle, requestId)
        assertEquals(HostOperationKind.SYNTHESIZE, claim.kind)
        assertEquals("hello", claim.text)
        assertEquals("en-US", claim.language)
        assertEquals("v", claim.voice)
        assertEquals(1.0, claim.speed, 0.001)

        val resumed = resumeOp(handle, vy.coroutineId, vy.operationId, true, "result")
        val resumedV = resultJson(resumed)
        assertEquals("opaque_audio", resumedV.getString("text"))
        assertTrue("metatable must be false", resumedV.getBoolean("meta").not())
        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `playback_schedule_validates_options_before_admission_and_returns_exact_status`() {
        val handle = s.createState(s.runtimeV1Config())
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local p = require("talkcan.playback")
                return {
                  startup = function() end,
                  handle_input = function(event)
                    local audio = event.audio
                    local args = event or {}
                    local options
                    if args and args.case == "extra" then options = {delay_seconds=1, extra=true}
                    elseif args and args.case == "non_table" then options = "bad"
                    elseif args and args.case == "nan" then options = {delay_seconds=0/0}
                    elseif args and args.case == "infinite" then options = {delay_seconds=1/0}
                    elseif args and args.case == "negative" then options = {delay_seconds=-1}
                    elseif args and args.case == "oversize" then options = {delay_seconds=86401}
                    elseif args and args.case == "missing" then options = nil
                    else options = {delay_seconds=1} end
                    local result, error = p.schedule(audio, options)
                    if error then return {error=error.error} end
                    return result
                  end,
                  raw_yield = function() return coroutine.yield("untagged") end,
                }
            """.trimIndent()),
        )

        // Options validation cases.
        for (case in listOf("missing", "extra", "non_table", "nan", "infinite", "negative", "oversize")) {
            val out = invokeInput(handle, eventWithMetadata("case" to case), "captured-token")
            val v = resultJson(out)
            val expected = if (case == "negative" || case == "oversize") "E_INVALID_VALUE" else "E_INVALID_ARGUMENT"
            assertEquals("case $case", expected, v.getString("error"))
        }

        // Valid playback yields an opaque request.
        val valid = invokeInput(handle, eventWithMetadata(), "captured-token")
        val vy = yielded(valid)
        val requestId = vy.value!!.toLong()
        val claim = claimAdmitted(handle, requestId)
        assertEquals(HostOperationKind.PLAYBACK, claim.kind)
        assertEquals("captured-token", claim.audioToken)
        assertEquals(1.0, claim.delaySeconds, 0.001)

        val resumed = resumeOp(handle, vy.coroutineId, vy.operationId, true, "ignored")
        val resumedV = resultJson(resumed)
        assertEquals("scheduled", resumedV.getString("status"))

        // Failure normalization.
        val allowed = listOf(
            "E_INVALID_ARGUMENT", "E_INVALID_VALUE", "E_INVALID_CONTEXT",
            "E_CAPABILITY_UNDECLARED", "E_UNAVAILABLE", "E_BUSY", "E_TIMEOUT",
            "E_CANCELLED", "E_CLOSED", "E_STALE", "E_HOST_FAILURE",
        )
        val injectedList = allowed + listOf(
            "exception-like: /endpoint=https://secret.example credential=top-secret transport reset",
            "playback backend detail",
        )
        for (injected in injectedList) {
            val yieldedOutcome = invokeInput(handle, eventWithMetadata(), "captured-token")
            val fy = yielded(yieldedOutcome)
            val failed = resumeOp(handle, fy.coroutineId, fy.operationId, false, injected)
            val failedV = resultJson(failed)
            val expected = if (injected.startsWith("E_")) injected else "E_HOST_FAILURE"
            assertEquals("injected=$injected", expected, failedV.getString("error"))
            val failedStr = s.resultString(s.assertCompleted(failed))
            assertTrue("must not leak secret.example", !failedStr.contains("secret.example"))
            assertTrue("must not leak top-secret", !failedStr.contains("top-secret"))
            assertTrue("must not leak transport reset", !failedStr.contains("transport reset"))
        }

        // Raw yield from a plain callback is rejected.
        val untagged = s.invokeCallback(handle, "raw_yield")
        val rf = s.assertRuntimeFailure(untagged, "raw yield")
        assertTrue(rf.diagnostic.contains("E_INVALID_YIELD"))
        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `audio_wrong_kind_is_rejected_before_host_admission`() {
        val handle = s.createState(s.runtimeV1Config())
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local s = require("talkcan.synthesis")
                local t = require("talkcan.transcription")
                return {
                  startup = function() end,
                  handle_input = function(event)
                    local syn, syn_err = s.synthesize({text="hello", language="en-US", voice="v"})
                    if syn_err then return {error = "synthesis-failed:" .. syn_err.error} end
                    local x, e = t.transcribe(syn)
                    return {error = e and e.error}
                  end,
                }
            """.trimIndent()),
        )

        var consulted = 0
        val admission = object : LuaSpawnAdmission {
            override fun admitTask(coroutineId: Long): Int { consulted++; return 0 }
        }
        val outcome = invokeInput(handle, eventWithMetadata(), "captured-token", admission)
        // First yield from synthesis.synthesize.
        val y = yielded(outcome)
        // Resume synthesis: creates synthesized audio userdata.
        val resumed = resumeOp(handle, y.coroutineId, y.operationId, true, "synthesized:wrong-kind-token", admission)
        // The transcribe call with synthesized (wrong-kind) userdata must fail the
        // synchronous kind check as E_INVALID_VALUE without yielding a host operation.
        val resumedV = resultJson(resumed)
        assertEquals(
            "wrong-kind audio passed to transcription must be rejected as E_INVALID_VALUE",
            "E_INVALID_VALUE",
            resumedV.getString("error"),
        )
        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `defer_is_rejected_during_module_evaluation_before_reserving_work`() {
        val handle = s.createState(
            LuaKernelConfig(
                hookInterval = 100,
                instructionBudget = 50_000,
                maxConcurrentTasks = 1,
                maxTimerSlots = 1,
            ),
        )
        val outcome = s.loadProgramImage(
            handle,
            "entry",
            mapOf("entry" to """
                local runtime = require("talkcan.runtime")
                runtime.defer(function() end)
                return { startup = function() end }
            """.trimIndent()),
        )
        val rf = s.assertRuntimeFailure(outcome, "defer during module eval")
        assertTrue(rf.diagnostic.contains("effect-call-during-load"))
        s.assertClosed(s.closeState(handle))
    }

    // ==================================================================
    // work_conformance.rs — work eligibility/admission
    // ==================================================================

    @Test
    fun `work_open_undeclared_queue_denied`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"workQueue":true,"workQueues":["turns"]}""")
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local work = require("talkcan.work")
                return {
                  startup = function() end,
                  handle_input = function()
                    local q, e = work.open("nonexistent")
                    if q then return { ok = true, unexpected = true } end
                    return { ok = true, error_code = e.error }
                  end,
                }
            """.trimIndent()),
        )
        val v = resultJson(invokeInput(handle))
        assertEquals("E_NOT_FOUND", v.getString("error_code"))
    }

    @Test
    fun `work_open_capability_undeclared`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{}""")
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local work = require("talkcan.work")
                return {
                  startup = function() end,
                  handle_input = function()
                    local q, e = work.open("turns")
                    if q then return { ok = true, unexpected = true } end
                    return { ok = true, error_code = e.error }
                  end,
                }
            """.trimIndent()),
        )
        val v = resultJson(invokeInput(handle))
        assertEquals("E_CAPABILITY_UNDECLARED", v.getString("error_code"))
    }

    @Test
    fun `work_submit_source_eval_denied`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"workQueue":true,"workQueues":["turns"]}""")
        // work.open during source eval returns nil+error silently; the load must succeed.
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local work = require("talkcan.work")
                local q = work.open("turns")
                return {
                  startup = function() end,
                  handle_input = function()
                    return { ok = true, opened = (q ~= nil) }
                  end,
                }
            """.trimIndent()),
        )
    }

    @Test
    fun `work_receive_managed_task_only`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"workQueue":true,"workQueues":["turns"]}""")
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local work = require("talkcan.work")
                return {
                  startup = function() end,
                  handle_input = function()
                    local q = work.open("turns")
                    local job, e = q:receive()
                    if job then return { ok = true, unexpected = true } end
                    return { ok = true, error_code = e.error }
                  end,
                }
            """.trimIndent()),
        )
        val v = resultJson(invokeInput(handle))
        assertEquals("E_INVALID_CONTEXT", v.getString("error_code"))
    }

    @Test
    fun `work_open_revoked_after_resource_context_replacement`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"workQueue":true,"workQueues":["turns"]}""")
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local work = require("talkcan.work")
                return {
                  startup = function() end,
                  handle_input = function()
                    -- "turns" is no longer declared after RC replacement
                    local q, e = work.open("turns")
                    if q then return { ok = true, unexpected = true } end
                    return { ok = true, error_code = e.error }
                  end,
                }
            """.trimIndent()),
        )
        // Replace resource context without the "turns" queue.
        s.installResourceContext(handle, """{"workQueue":true,"workQueues":["other"]}""")
        val v = resultJson(invokeInput(handle))
        assertEquals("E_NOT_FOUND", v.getString("error_code"))
    }

    @Test
    fun `work_open_during_source_eval_denied`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"workQueue":true,"workQueues":["turns"]}""")
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local work = require("talkcan.work")
                local q, e = work.open("turns")
                return {
                  startup = function() end,
                  handle_input = function()
                    return { ok = true, q_nil = (q == nil), error_code = e and e.error or "none" }
                  end,
                }
            """.trimIndent()),
        )
        val v = resultJson(invokeInput(handle))
        assertTrue("q must be nil", v.getBoolean("q_nil"))
        assertEquals("E_INVALID_CONTEXT", v.getString("error_code"))
    }

    @Test
    fun `feedback_emit_conformance`() {
        val handle = s.createState(s.channelConfig())
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf("entry" to """
                local f = require("talkcan.feedback")
                return {
                  startup = function() end,
                  outside = function()
                    local x, e = f.emit("recording_limit_warning")
                    return { ok = (x == true), error_code = e and e.error or "none" }
                  end,
                  get_constants = function()
                    return {
                      warning = f.RECORDING_LIMIT_WARNING,
                      final = f.RECORDING_LIMIT_FINAL,
                    }
                  end,
                  emit_no_args = function()
                    local x, e = f.emit()
                    return { ok = (x == true), error_code = e and e.error or "none" }
                  end,
                  emit_non_string = function()
                    local x, e = f.emit(123)
                    return { ok = (x == true), error_code = e and e.error or "none" }
                  end,
                  emit_unknown_constant = function()
                    local x, e = f.emit("unknown_beep")
                    return { ok = (x == true), error_code = e and e.error or "none" }
                  end,
                  emit_extra_args = function()
                    local x, e = f.emit("recording_limit_warning", "extra")
                    return { ok = (x == true), error_code = e and e.error or "none" }
                  end,
                  handle_input = function()
                    local x, e = f.emit("recording_limit_warning")
                    return { ok = (x == true), error_code = e and e.error or "none" }
                  end,
                }
            """.trimIndent()),
        )

        // Verify constants
        val constants = resultJson(s.invokeCallback(handle, "get_constants"))
        assertEquals("recording_limit_warning", constants.getString("warning"))
        assertEquals("recording_limit_final", constants.getString("final"))

        // Verify invalid argument cases
        val noArgs = resultJson(s.invokeCallback(handle, "emit_no_args"))
        assertEquals("E_INVALID_ARGUMENT", noArgs.getString("error_code"))

        val nonString = resultJson(s.invokeCallback(handle, "emit_non_string"))
        assertEquals("E_INVALID_ARGUMENT", nonString.getString("error_code"))

        val unknownConst = resultJson(s.invokeCallback(handle, "emit_unknown_constant"))
        assertEquals("E_INVALID_ARGUMENT", unknownConst.getString("error_code"))

        val extraArgs = resultJson(s.invokeCallback(handle, "emit_extra_args"))
        assertEquals("E_INVALID_ARGUMENT", extraArgs.getString("error_code"))

        // Outside context: must fail with E_INVALID_CONTEXT
        val outside = resultJson(s.invokeCallback(handle, "outside"))
        assertEquals("E_INVALID_CONTEXT", outside.getString("error_code"))

        // Inside input context: also fails with E_INVALID_CONTEXT
        val inside = resultJson(invokeInput(handle))
        assertEquals("E_INVALID_CONTEXT", inside.getString("error_code"))
    }
}
