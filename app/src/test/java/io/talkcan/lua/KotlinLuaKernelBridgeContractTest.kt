package io.talkcan.lua

import io.talkcan.lua.kernel.KotlinLuaKernelBridge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class KotlinLuaKernelBridgeContractTest {

    @Test
    fun `kernel bridge exposes only approved modules and absent require has no side effects`() {
        val bridge: LuaKernelBridge = KotlinLuaKernelBridge()
        val createdOutcome = bridge.create(
            LuaKernelConfig(
                hookInterval = 100,
                instructionBudget = 10_000_000,
            ),
        )
        assertTrue("the Kotlin kernel must create the test state: $createdOutcome", createdOutcome is LuaKernelOutcome.Created)
        val created = createdOutcome as LuaKernelOutcome.Created
        val handle = LuaStateHandle(
            stateId = LuaStateId(created.stateId),
            generation = LuaStateGeneration(created.generation),
        )
        try {
            val loaded = bridge.loadProgramImage(
                handle = handle,
                entryPoint = "entry",
                sourceMap = mapOf(
                    "entry" to """
                        local function keys(value)
                          local result = {}
                          for key in pairs(value) do result[#result + 1] = key end
                          table.sort(result)
                          return result
                        end
                        local preloaded = keys(talkcan._preloaded)
                        local globals = keys(_G)
                        local loadedBefore = keys(talkcan._modules)
                        local ok, errorValue = pcall(require, "missing.native.namespace")
                        local loadedAfter = keys(talkcan._modules)
                        local forbidden = {}
                        local roots = {
                          "http", "https", "filesystem", "fs", "file", "path", "lfs",
                          "socket", "tcp", "udp", "net", "network", "dns", "websocket",
                          "event", "events", "event_loop", "eventloop", "uv", "async",
                          "package", "persistent", "state", "storage", "database", "db",
                          "sqlite", "os", "io", "ffi", "debug",
                        }
                        local function inspect(names, source)
                          for _, name in ipairs(names) do
                            for _, root in ipairs(roots) do
                              if name == root or string.sub(name, 1, #root + 1) == root .. "." then
                                forbidden[#forbidden + 1] = source .. ":" .. name
                              end
                            end
                          end
                        end
                        inspect(preloaded, "preload")
                        inspect(globals, "global")
                        inspect(loadedAfter, "loaded")
                        return {
                          startup = function()
                            return {
                              preloaded = preloaded,
                              globals = globals,
                              loadedBefore = loadedBefore,
                              loadedAfter = loadedAfter,
                              forbidden = forbidden,
                              missingOk = ok,
                              missingError = tostring(errorValue),
                            }
                          end,
                          handle_lifecycle = function() end,
                          handle_capture_lifecycle = function() end,
                          handle_input = function() end,
                          handle_sos = function() end,
                          handle_readiness = function() end,
                        }
                    """.trimIndent(),
                ),
            )
            val loadedCallbacks = (loaded as? LuaKernelOutcome.Completed)?.value
                ?: throw AssertionError("program image omitted callback list: $loaded")
            assertEquals(
                setOf(
                    "startup",
                    "handle_lifecycle",
                    "handle_capture_lifecycle",
                    "handle_input",
                    "handle_sos",
                    "handle_readiness",
                ),
                (0 until org.json.JSONArray(loadedCallbacks).length())
                    .map { org.json.JSONArray(loadedCallbacks).getString(it) }
                    .toSet(),
            )

            val startup = bridge.invokeCallback(
                handle,
                LuaCallbackHandle(handle, "startup"),
                LuaValue.Nil,
            ) as? LuaKernelOutcome.Completed ?: error("startup callback failed")
            val result = org.json.JSONObject(startup.value ?: error("startup returned no value"))
            assertEquals(
                "[\"coroutine\",\"math\",\"string\",\"table\",\"talkcan.audio\",\"talkcan.channel\",\"talkcan.feedback\",\"talkcan.fs\",\"talkcan.http\",\"talkcan.json\",\"talkcan.keyboard_output\",\"talkcan.log\",\"talkcan.playback\",\"talkcan.profiles\",\"talkcan.runtime\",\"talkcan.secrets\",\"talkcan.synthesis\",\"talkcan.transcription\",\"talkcan.work\",\"utf8\"]",
                result.getJSONArray("preloaded").toString(),
            )
            assertEquals(0, result.getJSONArray("forbidden").length())
            assertEquals(result.getJSONArray("loadedBefore").toString(), result.getJSONArray("loadedAfter").toString())
            assertFalse(result.getBoolean("missingOk"))
            assertTrue(result.getString("missingError").contains("E_MODULE_NOT_FOUND"))
        } finally {
            bridge.close(handle)
        }
    }
    private fun keyboardBridge(): Pair<LuaKernelBridge, LuaStateHandle> {
        val bridge: LuaKernelBridge = KotlinLuaKernelBridge()
        val createdOutcome = bridge.create(
            LuaKernelConfig(
                hookInterval = 100,
                instructionBudget = 10_000_000,
            ),
        )
        assertTrue(
            "the Kotlin kernel must create the keyboard state: $createdOutcome",
            createdOutcome is LuaKernelOutcome.Created,
        )
        val created = createdOutcome as LuaKernelOutcome.Created
        val handle = LuaStateHandle(
            stateId = LuaStateId(created.stateId),
            generation = LuaStateGeneration(created.generation),
        )
        return bridge to handle
    }

    private fun captureEvent(): LuaValue = LuaValue.Map(
        mapOf(
            "metadata" to LuaValue.Map(
                mapOf(
                    "duration_ms" to LuaValue.Integer(1L),
                    "sample_rate" to LuaValue.Integer(16000L),
                    "channels" to LuaValue.Integer(1L),
                    "pcm_bytes" to LuaValue.Integer(32L),
                ),
            ),
        ),
    )

    @Test
    fun `keyboard send_text yields opaque request and claims typed payload exactly once`() {
        val (bridge, handle) = keyboardBridge()
        try {
            val rc = bridge.setResourceContext(handle, """{"keyboardOutput":true}""")
            assertTrue("resource context install: $rc", rc is LuaKernelOutcome.Completed)
            val loaded = bridge.loadProgramImage(
                handle = handle,
                entryPoint = "entry",
                sourceMap = mapOf(
                    "entry" to """
                        local kb = require("talkcan.keyboard_output")
                        return {
                          startup = function() end,
                          handle_input = function(event)
                            local r, e = kb.send_text({ text = "bridge-secret-text", profile = "linux:us" })
                            if not r then return { error = { code = e.error, detail = "send" } } end
                            if r.status ~= "delivered" then
                              return { error = { code = "STATUS", detail = tostring(r.status) } }
                            end
                            return { ok = true }
                          end,
                        }
                    """.trimIndent(),
                ),
            )
            assertTrue("program image must load: $loaded", loaded is LuaKernelOutcome.Completed)
            val yielded = bridge.invokeInputCallback(
                handle,
                LuaCallbackHandle(handle, "handle_input"),
                captureEvent(),
                "tok",
            ) as? LuaKernelOutcome.Yielded ?: error("expected yielded keyboard request")
            val requestId = (yielded.value ?: error("missing request id")).toLong()
            assertEquals(yielded.value, requestId.toString())
            assertFalse("yield label leaks text", yielded.value!!.contains("bridge-secret-text"))
            assertFalse("yield label leaks profile", yielded.value!!.contains("linux:us"))

            val claim = bridge.claimHostOperation(handle, requestId) as? HostOperationClaim.Admitted
                ?: error("expected admitted claim")
            assertEquals(HostOperationKind.KEYBOARD_SEND_TEXT, claim.kind)
            assertEquals("bridge-secret-text", claim.text)
            assertEquals("linux:us", claim.profile)
            assertNull(claim.key)

            val duplicate = bridge.claimHostOperation(handle, requestId)
            assertTrue("duplicate claim must be rejected", duplicate is HostOperationClaim.Rejected)

            val operation = LuaOperationHandle(
                stateHandle = handle,
                coroutineId = LuaCoroutineId(yielded.coroutineId),
                operationId = LuaOperationId(yielded.operationId),
            )
            val completed = bridge.resume(operation, true, """{"status":"delivered"}""")
                as? LuaKernelOutcome.Completed ?: error("expected completed resume")
            assertEquals("""{"ok":true}""", org.json.JSONObject(completed.value!!).toString())
        } finally {
            bridge.close(handle)
        }
    }

    @Test
    fun `keyboard send_key claim carries semantic key and passes through non-delivered outcomes`() {
        val (bridge, handle) = keyboardBridge()
        try {
            val rc = bridge.setResourceContext(handle, """{"keyboardOutput":true}""")
            assertTrue("resource context install: $rc", rc is LuaKernelOutcome.Completed)
            val loaded = bridge.loadProgramImage(
                handle = handle,
                entryPoint = "entry",
                sourceMap = mapOf(
                    "entry" to """
                        local kb = require("talkcan.keyboard_output")
                        return {
                          startup = function() end,
                          handle_input = function(event)
                            local r, e = kb.send_key({ key = "enter", profile = "mac:iso" })
                            if not r then return { error = { code = e.error, detail = "send" } } end
                            return { status = r.status, reason = r.reason }
                          end,
                        }
                    """.trimIndent(),
                ),
            )
            assertTrue("program image must load: $loaded", loaded is LuaKernelOutcome.Completed)
            val yielded = bridge.invokeInputCallback(
                handle,
                LuaCallbackHandle(handle, "handle_input"),
                captureEvent(),
                "tok",
            ) as? LuaKernelOutcome.Yielded ?: error("expected yielded keyboard request")
            val requestId = (yielded.value ?: error("missing request id")).toLong()
            val claim = bridge.claimHostOperation(handle, requestId) as? HostOperationClaim.Admitted
                ?: error("expected admitted claim")
            assertEquals(HostOperationKind.KEYBOARD_SEND_KEY, claim.kind)
            assertEquals("enter", claim.key)
            assertEquals("mac:iso", claim.profile)
            assertNull(claim.text)

            val operation = LuaOperationHandle(
                stateHandle = handle,
                coroutineId = LuaCoroutineId(yielded.coroutineId),
                operationId = LuaOperationId(yielded.operationId),
            )
            val completed = bridge.resume(
                operation,
                true,
                """{"status":"indeterminate","reason":"ack-lost"}""",
            ) as? LuaKernelOutcome.Completed ?: error("expected completed resume")
            val result = org.json.JSONObject(completed.value!!)
            assertEquals("indeterminate", result.getString("status"))
            assertEquals("ack-lost", result.getString("reason"))
        } finally {
            bridge.close(handle)
        }
    }

    @Test
    fun `keyboard output without declared capability fails inline without yielding`() {
        val (bridge, handle) = keyboardBridge()
        try {
            val rc = bridge.setResourceContext(handle, "{}")
            assertTrue("resource context install: $rc", rc is LuaKernelOutcome.Completed)
            val loaded = bridge.loadProgramImage(
                handle = handle,
                entryPoint = "entry",
                sourceMap = mapOf(
                    "entry" to """
                        local kb = require("talkcan.keyboard_output")
                        return {
                          startup = function() end,
                          handle_input = function(event)
                            local r, e = kb.send_text({ text = "x", profile = "p" })
                            return { error = { code = r == nil and e.error or "ok", detail = "send" } }
                          end,
                        }
                    """.trimIndent(),
                ),
            )
            assertTrue("program image must load: $loaded", loaded is LuaKernelOutcome.Completed)
            val completed = bridge.invokeInputCallback(
                handle,
                LuaCallbackHandle(handle, "handle_input"),
                captureEvent(),
                "tok",
            ) as? LuaKernelOutcome.Completed ?: error("undeclared capability must fail inline, not yield")
            val result = org.json.JSONObject(completed.value!!)
            assertEquals("E_CAPABILITY_UNDECLARED", result.getJSONObject("error").getString("code"))
        } finally {
            bridge.close(handle)
        }
    }

    @Test
    fun `sos callback yields keyboard operation through the typed broker`() {
        val (bridge, handle) = keyboardBridge()
        try {
            val rc = bridge.setResourceContext(handle, """{"keyboardOutput":true}""")
            assertTrue("resource context install: $rc", rc is LuaKernelOutcome.Completed)
            val loaded = bridge.loadProgramImage(
                handle = handle,
                entryPoint = "entry",
                sourceMap = mapOf(
                    "entry" to """
                        local kb = require("talkcan.keyboard_output")
                        return {
                          startup = function() end,
                          handle_sos = function(event)
                            local r, e = kb.send_key({ key = "enter", profile = "sos:profile" })
                            if not r then return { error = { code = e.error, detail = "sos" } } end
                            if r.status ~= "delivered" then
                              return { error = { code = "STATUS", detail = tostring(r.status) } }
                            end
                            return { ok = true }
                          end,
                        }
                    """.trimIndent(),
                ),
            )
            assertTrue("program image must load: $loaded", loaded is LuaKernelOutcome.Completed)
            val yielded = bridge.invokeSosCallback(
                handle,
                LuaCallbackHandle(handle, "handle_sos"),
                LuaValue.Nil,
            ) as? LuaKernelOutcome.Yielded ?: error("expected yielded SOS keyboard request")
            val requestId = (yielded.value ?: error("missing request id")).toLong()
            val claim = bridge.claimHostOperation(handle, requestId) as? HostOperationClaim.Admitted
                ?: error("expected admitted claim")
            assertEquals(HostOperationKind.KEYBOARD_SEND_KEY, claim.kind)
            assertEquals("enter", claim.key)
            assertEquals("sos:profile", claim.profile)

            val operation = LuaOperationHandle(
                stateHandle = handle,
                coroutineId = LuaCoroutineId(yielded.coroutineId),
                operationId = LuaOperationId(yielded.operationId),
            )
            val completed = bridge.resume(operation, true, """{"status":"delivered"}""")
                as? LuaKernelOutcome.Completed ?: error("expected completed resume")
            assertEquals("""{"ok":true}""", org.json.JSONObject(completed.value!!).toString())
        } finally {
            bridge.close(handle)
        }
    }

    @Test
    fun `sos callback that never yields completes in one slice like the synchronous path`() {
        val (bridge, handle) = keyboardBridge()
        try {
            val rc = bridge.setResourceContext(handle, """{"keyboardOutput":true}""")
            assertTrue("resource context install: $rc", rc is LuaKernelOutcome.Completed)
            val loaded = bridge.loadProgramImage(
                handle = handle,
                entryPoint = "entry",
                sourceMap = mapOf(
                    "entry" to """
                        return {
                          startup = function() end,
                          handle_sos = function(event)
                            return { ok = true }
                          end,
                        }
                    """.trimIndent(),
                ),
            )
            assertTrue("program image must load: $loaded", loaded is LuaKernelOutcome.Completed)
            val completed = bridge.invokeSosCallback(
                handle,
                LuaCallbackHandle(handle, "handle_sos"),
                LuaValue.Nil,
            ) as? LuaKernelOutcome.Completed ?: error("non-yielding SOS must complete synchronously")
            assertEquals("""{"ok":true}""", org.json.JSONObject(completed.value!!).toString())
        } finally {
            bridge.close(handle)
        }
    }

    @Test
    fun `sos callback rejects foreign callback handles and raw yields`() {
        val (bridge, handle) = keyboardBridge()
        try {
            val rc = bridge.setResourceContext(handle, """{"keyboardOutput":true}""")
            assertTrue("resource context install: $rc", rc is LuaKernelOutcome.Completed)
            val loaded = bridge.loadProgramImage(
                handle = handle,
                entryPoint = "entry",
                sourceMap = mapOf(
                    "entry" to """
                        return {
                          startup = function() end,
                          handle_input = function(event) return { ok = true } end,
                          handle_sos = function(event)
                            coroutine.yield("raw")
                            return { ok = true }
                          end,
                        }
                    """.trimIndent(),
                ),
            )
            assertTrue("program image must load: $loaded", loaded is LuaKernelOutcome.Completed)
            val foreign = bridge.invokeSosCallback(
                handle,
                LuaCallbackHandle(handle, "handle_input"),
                LuaValue.Nil,
            )
            assertTrue(
                "foreign callback handle must be rejected: $foreign",
                foreign is LuaKernelOutcome.InvalidOwnership,
            )
            val rawYield = bridge.invokeSosCallback(
                handle,
                LuaCallbackHandle(handle, "handle_sos"),
                LuaValue.Nil,
            )
            assertTrue(
                "raw yield must fail the SOS owner: $rawYield",
                rawYield is LuaKernelOutcome.RuntimeFailure,
            )
        } finally {
            bridge.close(handle)
        }
    }
}
