package io.talkcan.lua.kernel

import io.talkcan.lua.LuaKernelOutcome
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
 * JVM conformance port of every category-M (module-surface/shadowing) Rust test
 * from the `replace-rust-lua-kernel` inventory. Each [Test] method maps
 * one-for-one to a Rust `#[test]` fn, preserving the exact name and
 * table-driven variants. All tests drive [io.talkcan.lua.LuaKernelBridge]
 * through [KotlinLuaKernelConformanceSupport] and assert observable outcomes.
 */
class KotlinLuaKernelModuleConformanceTest {

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
    // conformance.rs — legacy single-source load/start surface
    // ==================================================================

    @Test
    fun `binary_and_dynamic_loader_inputs_reject_before_lua_effects_and_leave_state_usable`() {
        data class Case(val name: String, val source: String, val expectSyntaxFailure: Boolean)

        val cases = listOf(
            Case(
                "binary bytecode",
                "\u001bLua\u0000binary-chunk",
                expectSyntaxFailure = true,
            ),
            Case(
                "package.loadlib",
                """
                effects = 0
                function main()
                  local forbidden = package.loadlib
                  effects = effects + 1
                  return forbidden("/tmp/libuntrusted.so", "entry")
                end
                """.trimIndent(),
                expectSyntaxFailure = false,
            ),
            Case(
                "C module searcher",
                """
                effects = 0
                function main()
                  local forbidden = package.searchers[3]
                  effects = effects + 1
                  return forbidden("untrusted")
                end
                """.trimIndent(),
                expectSyntaxFailure = false,
            ),
            Case(
                "shared-library require",
                """
                effects = 0
                function main()
                  require("untrusted_shared_library")
                  effects = effects + 1
                  return "should-not-run"
                end
                """.trimIndent(),
                expectSyntaxFailure = false,
            ),
        )

        for (case in cases) {
            val handle = s.createState()
            val loaded = s.loadSource(handle, case.source, "main")
            if (case.expectSyntaxFailure) {
                s.assertSyntaxFailure(loaded, case.name)
            } else {
                s.assertCompleted(loaded, "${case.name} load")
                s.assertRuntimeFailure(s.startEntry(handle), case.name)
            }

            // Observed Lua effects must be zero.
            s.loadSourceOk(handle, "function observe() return tostring(effects or 0) end", "observe")
            val observed = s.startEntryOk(handle)
            assertEquals(
                "${case.name} reached Lua code after the rejected dynamic-loading boundary",
                "0",
                s.resultScalar(observed),
            )

            // State remains usable.
            s.loadSourceOk(handle, "function healthy() return 'still-usable' end", "healthy")
            val healthy = s.startEntryOk(handle)
            assertEquals("still-usable", s.resultScalar(healthy))
            s.assertClosed(s.closeState(handle), case.name)
        }
    }

    @Test
    fun `source_only_lua_cannot_use_base_load_for_constructed_bytecode`() {
        data class Case(val source: String, val expectedPrefix: String)

        val cases = listOf(
            Case(
                """
                function main()
                  local chunk = string.char(27) .. "nonsense"
                  local fn, err = load(chunk)
                  if fn then
                    fn()
                    return "executed-binary"
                  end
                  return "rejected:" .. tostring(err)
                end
                """.trimIndent(),
                "rejected:",
            ),
            Case(
                """
                function main()
                  local ok, err = pcall(dofile, "anywhere")
                  return "dofile-error:" .. tostring(err)
                end
                """.trimIndent(),
                "dofile-error:",
            ),
            Case(
                """
                function main()
                  local ok, err = pcall(loadfile, "anywhere")
                  return "loadfile-error:" .. tostring(err)
                end
                """.trimIndent(),
                "loadfile-error:",
            ),
        )

        for (case in cases) {
            val handle = s.createState()
            s.loadSourceOk(handle, case.source, "main")
            val outcome = s.startEntryOk(handle)
            val value = s.resultScalar(outcome)

            if (case.expectedPrefix == "rejected:") {
                assertTrue(
                    "binary load was not handled: $value",
                    value.startsWith("rejected:"),
                )
                assertTrue(
                    "binary rejection did not contain 'binary': $value",
                    value.contains("binary"),
                )
            } else {
                assertTrue(
                    "dofile/loadfile error unexpected: $value",
                    value.contains("nil") || value.contains("attempt"),
                )
            }

            // State remains usable.
            s.loadSourceOk(handle, "function healthy() return 'still-usable' end", "healthy")
            val healthy = s.startEntryOk(handle)
            assertEquals("still-usable", s.resultScalar(healthy))
            s.assertClosed(s.closeState(handle))
        }
    }

    @Test
    fun `plugin_cannot_create_or_resume_an_unhooked_infinite_child_coroutine`() {
        val handle = s.createState()
        s.loadSourceOk(
            handle,
            """
            function main()
              local child = coroutine.create(function()
                while true do end
              end)
              return coroutine.resume(child)
            end
            """.trimIndent(),
            "main",
        )
        val interrupted = s.startEntry(handle) as? LuaKernelOutcome.Interrupted
            ?: throw AssertionError("child coroutine escaped instruction hook")
        assertTrue(
            "instruction-budget interruption omitted elapsed-time evidence",
            interrupted.elapsedNanos != null,
        )

        // Same state runs a normal host-owned entry coroutine afterward.
        s.loadSourceOk(handle, "function healthy() return 'same-state-usable' end", "healthy")
        val healthy = s.startEntryOk(handle)
        assertEquals("same-state-usable", s.resultScalar(healthy))
        s.assertClosed(s.closeState(handle))
    }

    // ==================================================================
    // fs_conformance.rs — reserved module shadowing
    // ==================================================================

    @Test
    fun `fs_module_cannot_be_shadowed_via_module_put`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(
            handle,
            """{"storageFiles":true,"mounts":{"data":{"access":"read-write","status":"available"}}}""",
        )
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    return {
                      startup = function()
                        local ok, err = pcall(function()
                          talkcan.module_put("talkcan.fs", {fake=true})
                        end)
                        assert(not ok, "module_put should have raised E_RESERVED_MODULE")
                      end,
                      handle_input = function(event) return {ok=true} end,
                    }
                """.trimIndent(),
            ),
        )
        val startup = s.invokeCallbackOk(handle, "startup", LuaValue.Map(emptyMap()))
        // Startup completed: the assert passed, meaning module_put raised.
        s.assertCompleted(startup, "startup (module_put must raise)")
    }

    @Test
    fun `fs_require_reserved_module_returns_real_fs`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(
            handle,
            """{"storageFiles":true,"mounts":{"data":{"access":"read-write","status":"available"}}}""",
        )
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    local fs = require("talkcan.fs")
                    return {
                      startup = function()
                        assert(type(fs.mount) == "function")
                        assert(type(fs.mkdir) == "function")
                        assert(type(fs.stat) == "function")
                        assert(type(fs.list) == "function")
                        assert(type(fs.read_text) == "function")
                        assert(type(fs.write_text) == "function")
                        assert(type(fs.remove) == "function")
                      end,
                      handle_input = function(event) return {ok=true} end,
                    }
                """.trimIndent(),
            ),
        )
        val startup = s.invokeCallbackOk(handle, "startup", LuaValue.Map(emptyMap()))
        s.assertCompleted(startup, "startup (fs surface assertions)")
    }

    // ==================================================================
    // json_conformance.rs — reserved json module shadowing
    // ==================================================================

    @Test
    fun `package_source_cannot_shadow_the_reserved_json_module`() {
        val handle = s.createState()

        // Shadow attempt must be rejected wholesale before evaluation.
        s.assertValidationFailure(
            s.loadProgramImage(
                handle,
                "entry",
                mapOf(
                    "entry" to """return { startup = function() end }""",
                    "talkcan.json" to """return { encode = function() return "forged" end }""",
                ),
            ),
            "shadow talkcan.json",
        )

        // Clean image loads afterward and resolves the genuine host module.
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    local json = require("talkcan.json")
                    return {
                      startup = function() end,
                      probe = function() return (json.encode({ ok = true })) end,
                    }
                """.trimIndent(),
            ),
        )
        val probe = s.invokeCallbackOk(handle, "probe")
        assertEquals("""{"ok":true}""", s.resultScalar(probe))
    }

    // ==================================================================
    // keyboard_conformance.rs — reserved keyboard_output surface
    // ==================================================================

    @Test
    fun `keyboard_output_module_is_reserved_with_exact_surface`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"keyboardOutput":true}""")
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    local function keys(t)
                      local out = {}
                      for k in pairs(t) do out[#out + 1] = k end
                      table.sort(out)
                      return out
                    end
                    local kb = require("talkcan.keyboard_output")
                    local preloaded = keys(talkcan._preloaded)
                    local surface = keys(kb)
                    local same = (kb == require("talkcan.keyboard_output"))
                    local writable = pcall(function() kb.send_extra = 1 end)
                    local host_fn_visible = type(talkcan.host_keyboard_output)
                    return {
                      startup = function()
                        return {
                          preloaded = preloaded,
                          surface = surface,
                          same = same,
                          writable = writable,
                          host_fn_visible = host_fn_visible,
                        }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val startup = s.invokeCallbackOk(handle, "startup")
        val result = s.resultObject(startup)

        // The reserved module is preloaded under its exact public name.
        val preloaded = result.getJSONArray("preloaded")
        var found = false
        for (i in 0 until preloaded.length()) {
            if (preloaded.getString(i) == "talkcan.keyboard_output") found = true
        }
        assertTrue("preloaded must contain talkcan.keyboard_output: $preloaded", found)

        // Exactly send_text and send_key; nothing else.
        val surface = result.getJSONArray("surface")
        assertEquals("module surface must be exactly send_key/send_text", 2, surface.length())
        assertEquals("send_key", surface.getString(0))
        assertEquals("send_text", surface.getString(1))

        assertTrue("require must cache the reserved module", result.getBoolean("same"))
        assertFalse("image module view must be read-only", result.getBoolean("writable"))
        assertEquals(
            "the native registration function must not be visible to package code",
            "nil",
            result.getString("host_fn_visible"),
        )
    }

    @Test
    fun `keyboard_output_shadow_source_name_rejected_before_state_use`() {
        val handle = s.createState(s.channelConfig())
        val outcome = s.loadProgramImage(
            handle,
            "entry",
            mapOf(
                "talkcan.keyboard_output" to "return {}",
                "entry" to "return { startup = function() end }",
            ),
        )
        s.assertValidationFailure(outcome, "shadowing a reserved module name must be rejected")
    }

    // ==================================================================
    // resolver_conformance.rs — resolver module export contract
    // ==================================================================

    @Test
    fun `resolver_module_must_export_exactly_resolve`() {
        val handle = s.createResolverState()
        val source = """
            return { resolve = function() return { choices = {} }, nil end, extra = 1 }
        """.trimIndent()
        val outcome = s.invokeResolver(handle, s.resolverInvocation(source))
        // Extra export key must not yield choices: either not Completed, or
        // Completed with resultKind == package_error.
        if (outcome is LuaKernelOutcome.Completed) {
            val value = s.resultObject(outcome)
            assertEquals(
                "extra export must produce package_error, not choices: $value",
                "package_error",
                value.optString("resultKind", ""),
            )
        }
        // Non-Completed (e.g. ValidationFailure) is also acceptable.
    }

    @Test
    fun `resolver_missing_resolve_key_is_validation_failure`() {
        val handle = s.createResolverState()
        val source = """
            return { not_resolve = function() end }
        """.trimIndent()
        val outcome = s.invokeResolver(handle, s.resolverInvocation(source))
        // Must not produce choices.
        if (outcome is LuaKernelOutcome.Completed) {
            val value = s.resultObject(outcome)
            assertFalse(
                "missing resolve key must not yield choices: $value",
                value.optString("resultKind", "") == "choices",
            )
        }
        // Non-Completed (e.g. ValidationFailure) is the expected path.
    }

    // ==================================================================
    // runtime_v1_conformance.rs — restricted globals and module surface
    // ==================================================================

    @Test
    fun `restricted_globals_are_absent_and_disabled_operations_leave_the_state_closable`() {
        val handle = s.createState(s.runtimeV1Config())
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    return {
                      startup = function() end,
                      probe = function()
                        local dump_ok, dump_error = pcall(string.dump, function() return 1 end)
                        return {
                          io_missing = io == nil,
                          os_missing = os == nil,
                          debug_missing = debug == nil,
                          package_missing = package == nil,
                          load_missing = load == nil,
                          loadfile_missing = loadfile == nil,
                          dofile_missing = dofile == nil,
                          dump_disabled = not dump_ok,
                          dump_error = tostring(dump_error),
                        }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val probe = s.invokeCallbackOk(handle, "probe")
        val result = s.resultObject(probe)

        for (field in listOf(
            "io_missing", "os_missing", "debug_missing", "package_missing",
            "load_missing", "loadfile_missing", "dofile_missing", "dump_disabled",
        )) {
            assertTrue(
                "the v1 sandbox exposed a forbidden standard-library entry ($field): $result",
                result.getBoolean(field),
            )
        }
        assertTrue(
            "string.dump did not fail with its stable disabled diagnostic: $result",
            result.getString("dump_error").contains("string.dump is disabled"),
        )

        s.assertClosed(s.closeState(handle))
        s.assertClosed(s.closeState(handle), "idempotent close")
        s.assertClosedOrStale(s.invokeCallback(handle, "probe"), "closed state must not re-enter Lua")
    }

    @Test
    fun `failed_images_cannot_mutate_host_modules_and_roll_back_before_valid_reload`() {
        val validImage = mapOf(
            "entry" to """
                return {
                  startup = function() end,
                  probe = function()
                    return {
                      root_leak_absent = talkcan.leaked == nil,
                      runtime_leak_absent = talkcan.runtime.leaked == nil,
                      lua_version = talkcan.runtime.LUA_VERSION,
                      lua_release = talkcan.runtime.LUA_RELEASE,
                      api_version = talkcan.runtime.API_VERSION,
                    }
                  end,
                }
            """.trimIndent(),
        )
        val mutations = listOf(
            "root field" to "talkcan.leaked = 'forbidden'",
            "runtime module" to "talkcan.runtime = {}",
            "runtime constant" to "talkcan.runtime.LUA_VERSION = 'forged'",
            "runtime field" to "talkcan.runtime.leaked = 'forbidden'",
        )

        for ((name, mutation) in mutations) {
            val handle = s.createState(s.runtimeV1Config())
            val failed = s.loadProgramImage(
                handle,
                "entry",
                mapOf("entry" to "$mutation; return { startup = function() end }"),
            )
            val rf = s.assertRuntimeFailure(failed, name)
            assertTrue(
                "$name mutation did not fail at the image-owned read-only boundary: ${rf.diagnostic}",
                rf.diagnostic.contains("read-only talkcan namespace"),
            )

            // Valid reload succeeds with no leaked writes.
            s.loadProgramImageOk(handle, "entry", validImage)
            val probe = s.invokeCallbackOk(handle, "probe")
            val result = s.resultObject(probe)
            assertTrue("$name: root_leak_absent", result.getBoolean("root_leak_absent"))
            assertTrue("$name: runtime_leak_absent", result.getBoolean("runtime_leak_absent"))
            assertEquals("$name: lua_version", "Lua 5.4", result.getString("lua_version"))
            assertEquals("$name: lua_release", "5.4.8", result.getString("lua_release"))
            assertEquals("$name: api_version", "talkcan-lua-v1", result.getString("api_version"))
            s.assertClosed(s.closeState(handle), name)
        }
    }

    @Test
    fun `host_modules_have_exact_constants_and_package_local_modules_cache_real_lua_values`() {
        val handle = s.createState(s.runtimeV1Config())
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    local runtime = require("talkcan.runtime")
                    local channel = require("talkcan.channel")
                    local nested = require("plugin.nested")
                    local nil_first = require("plugin.nil_return")
                    local library_first = require("plugin.library")
                    return {
                      startup = function() end,
                      probe = function()
                        local nil_second = require("plugin.nil_return")
                        local library_second = require("plugin.library")
                        return {
                          lua_version = runtime.LUA_VERSION,
                          lua_release = runtime.LUA_RELEASE,
                          api_version = runtime.API_VERSION,
                          no_version_callable = runtime.version == nil,
                          lifecycle_ready = channel.LIFECYCLE_READY,
                          capture_complete = channel.CAPTURE_COMPLETE,
                          sos_triggered = channel.SOS_TRIGGERED,
                          nested = nested.value,
                          nil_cached_as_true = nil_first == true and nil_second == true,
                          nil_load_count = nil_load_count,
                          function_identity = library_first == library_second,
                          function_value = library_first(19, 23),
                        }
                      end,
                    }
                """.trimIndent(),
                "plugin.nested" to """
                    local helper = require("plugin.helper")
                    return { value = helper.value }
                """.trimIndent(),
                "plugin.helper" to "return { value = 'nested package-local module' }",
                "plugin.nil_return" to "nil_load_count = (nil_load_count or 0) + 1",
                "plugin.library" to "return function(left, right) return left + right end",
            ),
        )
        val probe = s.invokeCallbackOk(handle, "probe")
        val result = s.resultObject(probe)

        assertEquals("Lua 5.4", result.getString("lua_version"))
        assertEquals("5.4.8", result.getString("lua_release"))
        assertEquals("talkcan-lua-v1", result.getString("api_version"))
        assertTrue("no_version_callable", result.getBoolean("no_version_callable"))
        assertEquals("ready", result.getString("lifecycle_ready"))
        assertEquals("capture", result.getString("capture_complete"))
        assertEquals("sos", result.getString("sos_triggered"))
        assertEquals("nested package-local module", result.getString("nested"))
        assertTrue("nil_cached_as_true", result.getBoolean("nil_cached_as_true"))
        assertEquals("nil_load_count", 1, result.getInt("nil_load_count"))
        assertTrue("function_identity", result.getBoolean("function_identity"))
        assertEquals("function_value", 42, result.getInt("function_value"))
        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `safe_standard_libraries_are_requireable_without_package_or_dynamic_searchers`() {
        val handle = s.createState(s.runtimeV1Config())
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    local required_coroutine = require("coroutine")
                    local required_math = require("math")
                    local required_string = require("string")
                    local required_table = require("table")
                    local required_utf8 = require("utf8")
                    local package_ok, package_error = pcall(require, "package")
                    return {
                      startup = function() end,
                      probe = function()
                        local thread = required_coroutine.create(function() end)
                        return {
                          coroutine_identity = required_coroutine == coroutine,
                          math_identity = required_math == math,
                          string_identity = required_string == string,
                          table_identity = required_table == table,
                          utf8_identity = required_utf8 == utf8,
                          coroutine_status = required_coroutine.status(thread),
                          floor = required_math.floor(4.75),
                          byte = required_string.byte("A"),
                          concat = required_table.concat({"a", "b"}, ":"),
                          codepoints = required_utf8.len("é"),
                          package_ok = package_ok,
                          package_error = tostring(package_error),
                        }
                      end,
                    }
                """.trimIndent(),
                "string" to "return { byte = function() return -1 end }",
                "table" to "return { concat = function() return 'shadowed' end }",
            ),
        )
        val probe = s.invokeCallbackOk(handle, "probe")
        val result = s.resultObject(probe)

        assertTrue("coroutine_identity", result.getBoolean("coroutine_identity"))
        assertTrue("math_identity", result.getBoolean("math_identity"))
        assertTrue("string_identity", result.getBoolean("string_identity"))
        assertTrue("table_identity", result.getBoolean("table_identity"))
        assertTrue("utf8_identity", result.getBoolean("utf8_identity"))
        assertEquals("suspended", result.getString("coroutine_status"))
        assertEquals(4, result.getInt("floor"))
        assertEquals(65, result.getInt("byte"))
        assertEquals("a:b", result.getString("concat"))
        assertEquals(1, result.getInt("codepoints"))
        assertFalse("package_ok", result.getBoolean("package_ok"))
        assertTrue(
            "dynamic package searchers became reachable: $result",
            result.getString("package_error").contains("E_MODULE_NOT_FOUND"),
        )
        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `namespace_enumeration_has_only_allowed_modules_and_missing_require_is_side_effect_free`() {
        val handle = s.createState(s.runtimeV1Config())
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    local function keys(table_value)
                      local result = {}
                      for key in pairs(table_value) do result[#result + 1] = key end
                      table.sort(result)
                      return result
                    end
                    local preloaded = keys(talkcan._preloaded)
                    local globals = keys(_G)
                    local loaded_before = keys(talkcan._modules)
                    local required, error_value = pcall(require, "missing.namespace.module")
                    local loaded_after = keys(talkcan._modules)
                    local forbidden = {}
                    local forbidden_roots = {
                      "http", "https", "filesystem", "fs", "file", "path", "lfs",
                      "socket", "tcp", "udp", "net", "network", "dns", "websocket",
                      "event", "events", "event_loop", "eventloop", "uv", "async",
                      "package", "persistent", "state", "storage", "database", "db",
                      "sqlite", "os", "io", "ffi", "debug",
                    }
                    local function inspect(names, source)
                      for _, name in ipairs(names) do
                        for _, root in ipairs(forbidden_roots) do
                          if name == root or string.sub(name, 1, #root + 1) == root .. "." then
                            forbidden[#forbidden + 1] = source .. ":" .. name
                          end
                        end
                      end
                    end
                    inspect(preloaded, "preload")
                    inspect(globals, "global")
                    inspect(loaded_after, "loaded")
                    return {
                      startup = function() end,
                      probe = function()
                        return {
                          preloaded = preloaded,
                          globals = globals,
                          loaded_before = loaded_before,
                          loaded_after = loaded_after,
                          forbidden = forbidden,
                          missing_ok = required,
                          missing_error = tostring(error_value),
                        }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val probe = s.invokeCallbackOk(handle, "probe")
        val result = s.resultObject(probe)

        val expectedPreloaded = listOf(
            "coroutine", "math", "string", "table",
            "talkcan.audio", "talkcan.channel", "talkcan.feedback", "talkcan.fs",
            "talkcan.http", "talkcan.json", "talkcan.keyboard_output",
            "talkcan.log", "talkcan.playback", "talkcan.profiles",
            "talkcan.runtime", "talkcan.secrets", "talkcan.synthesis",
            "talkcan.transcription", "talkcan.work",
            "utf8",
        )
        val preloaded = result.getJSONArray("preloaded")
        assertEquals("preloaded module count", expectedPreloaded.size, preloaded.length())
        for (i in expectedPreloaded.indices) {
            assertEquals("preloaded[$i]", expectedPreloaded[i], preloaded.getString(i))
        }

        assertEquals("forbidden namespace entries: $result", 0, result.getJSONArray("forbidden").length())
        assertEquals(
            "loaded_before must equal loaded_after",
            result.getJSONArray("loaded_before").toString(),
            result.getJSONArray("loaded_after").toString(),
        )
        assertFalse("missing_ok", result.getBoolean("missing_ok"))
        assertTrue(
            "missing module did not return the stable not-found error: $result",
            result.getString("missing_error").contains("E_MODULE_NOT_FOUND"),
        )
        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `loaded_package_images_expose_reserved_audio_module_functions`() {
        val handle = s.createState(s.runtimeV1Config())
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    local transcription = require("talkcan.transcription")
                    local synthesis = require("talkcan.synthesis")
                    local playback = require("talkcan.playback")
                    return {
                      startup = function() end,
                      probe = function()
                        return {
                          transcribe = type(transcription.transcribe),
                          synthesize = type(synthesis.synthesize),
                          schedule = type(playback.schedule),
                        }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val probe = s.invokeCallbackOk(handle, "probe")
        val result = s.resultObject(probe)
        assertEquals("function", result.getString("transcribe"))
        assertEquals("function", result.getString("synthesize"))
        assertEquals("function", result.getString("schedule"))
        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `reserved_semantic_module_names_are_rejected_from_program_images`() {
        for (invalidName in listOf(
            "talkcan",
            "talkcan.transcription",
            "talkcan.synthesis",
            "talkcan.playback",
        )) {
            val handle = s.createState(s.runtimeV1Config())
            val outcome = s.loadProgramImage(
                handle,
                "entry",
                mapOf(
                    "entry" to "return { startup = function() end }",
                    invalidName to "return { shadow = true }",
                ),
            )
            s.assertValidationFailure(outcome, "reserved name '$invalidName' must be rejected")
            s.assertClosed(s.closeState(handle), invalidName)
        }
    }

    @Test
    fun `reserved_transcription_table_requires_but_rejects_calls_without_host_effects`() {
        val handle = s.createState(s.runtimeV1Config())
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    local transcription = require("talkcan.transcription")
                    return {
                      startup = function() end,
                      probe = function()
                        local result, err = transcription.transcribe("input")
                        return {
                          resolved = type(transcription) == "table",
                          callable = type(transcription.transcribe) == "function",
                          result_nil = result == nil,
                          error = err and err.error,
                        }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val probe = s.invokeCallbackOk(handle, "probe")
        val result = s.resultObject(probe)

        assertTrue("resolved", result.getBoolean("resolved"))
        assertTrue("callable", result.getBoolean("callable"))
        assertTrue("result_nil", result.getBoolean("result_nil"))
        assertEquals("E_INVALID_CONTEXT", result.getString("error"))

        // No host effects retained.
        assertFalse(
            "reserved transcription call retained a host effect (logs)",
            probe.logs != null && probe.logs!!.isNotEmpty(),
        )
        assertTrue(
            "reserved transcription call admitted a host coroutine",
            probe.spawnedCoroutines == null || probe.spawnedCoroutines!!.isEmpty(),
        )
        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `module_resolver_returns_typed_errors_and_rejects_reserved_source_shadowing`() {
        val handle = s.createState(s.runtimeV1Config())
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    return {
                      startup = function() end,
                      probe = function()
                        local function message(name)
                          local ok, err = pcall(require, name)
                          return ok, tostring(err)
                        end
                        local exact_ok, exact = message("talkcan")
                        local reserved_ok, reserved = message("talkcan.unknown")
                        local missing_ok, missing = message("missing.module")
                        local dots_ok, dots = message("plugin..bad")
                        local path_ok, path = message("../../etc/passwd")
                        return {
                          exact_ok = exact_ok, exact = exact,
                          reserved_ok = reserved_ok, reserved = reserved,
                          missing_ok = missing_ok, missing = missing,
                          dots_ok = dots_ok, dots = dots,
                          path_ok = path_ok, path = path,
                        }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val probe = s.invokeCallbackOk(handle, "probe")
        val result = s.resultObject(probe)

        val cases = listOf(
            Triple("exact_ok", "exact", "E_RESERVED_MODULE"),
            Triple("reserved_ok", "reserved", "E_RESERVED_MODULE"),
            Triple("missing_ok", "missing", "E_MODULE_NOT_FOUND"),
            Triple("dots_ok", "dots", "E_INVALID_MODULE_NAME"),
            Triple("path_ok", "path", "E_INVALID_MODULE_NAME"),
        )
        for ((okKey, errorKey, expected) in cases) {
            assertFalse("$okKey: $result", result.getBoolean(okKey))
            assertTrue(
                "$errorKey did not retain $expected: $result",
                result.getString(errorKey).contains(expected),
            )
        }
        s.assertClosed(s.closeState(handle))

        // Reserved-name shadowing in source maps is rejected.
        for (invalidName in listOf(
            "talkcan",
            "talkcan.runtime",
            "Plugin.upper",
            "plugin..empty",
            "plugin.",
        )) {
            val rejected = s.createState(s.runtimeV1Config())
            val outcome = s.loadProgramImage(
                rejected,
                "entry",
                mapOf(
                    "entry" to "return { startup = function() end }",
                    invalidName to "return { shadow = true }",
                ),
            )
            s.assertValidationFailure(outcome, "reserved source name '$invalidName'")
            s.assertClosed(s.closeState(rejected), invalidName)
        }
    }

    @Test
    fun `recursive_and_effectful_module_loads_fail_without_partial_cache_entries`() {
        // Cycle: plugin.a <-> plugin.b
        val cycle = s.createState(s.runtimeV1Config())
        s.loadProgramImageOk(
            cycle,
            "entry",
            mapOf(
                "entry" to """
                    return {
                      startup = function() end,
                      probe = function()
                        local ok, err = pcall(require, "plugin.a")
                        return { ok = ok, error = tostring(err) }
                      end,
                    }
                """.trimIndent(),
                "plugin.a" to "return require('plugin.b')",
                "plugin.b" to "return require('plugin.a')",
            ),
        )
        val cycleOutcome = s.invokeCallbackOk(cycle, "probe")
        val cycleResult = s.resultObject(cycleOutcome)
        assertFalse("cycle ok", cycleResult.getBoolean("ok"))
        assertTrue(
            "recursive require did not return E_MODULE_CYCLE: $cycleResult",
            cycleResult.getString("error").contains("E_MODULE_CYCLE"),
        )
        s.assertClosed(s.closeState(cycle))

        // Effectful module loads fail whole; no partial cache entry.
        data class EffectCase(val name: String, val module: String, val effect: String)

        val effectCases = listOf(
            EffectCase("spawn", "talkcan.runtime", "api.spawn(function() end)"),
            EffectCase("sleep", "talkcan.runtime", "api.sleep(1)"),
            EffectCase("log", "talkcan.log", "api.info({message = 'not allowed during module load'})"),
        )

        for (case in effectCases) {
            val handle = s.createState(s.runtimeV1Config())
            val moduleSource =
                "attempts = (attempts or 0) + 1; local api = require('${case.module}'); ${case.effect}; return { cached = true }"
            s.loadProgramImageOk(
                handle,
                "entry",
                mapOf(
                    "entry" to """
                        return {
                          startup = function() end,
                          probe = function()
                            local first_ok, first_error = pcall(require, "plugin.effect")
                            local second_ok, second_error = pcall(require, "plugin.effect")
                            return {
                              first_ok = first_ok,
                              first_error = tostring(first_error),
                              second_ok = second_ok,
                              second_error = tostring(second_error),
                              attempts = attempts,
                            }
                          end,
                        }
                    """.trimIndent(),
                    "plugin.effect" to moduleSource,
                ),
            )
            val outcome = s.invokeCallbackOk(handle, "probe")
            val result = s.resultObject(outcome)

            assertFalse("${case.name} first_ok: $result", result.getBoolean("first_ok"))
            assertFalse("${case.name} second_ok: $result", result.getBoolean("second_ok"))
            assertTrue(
                "${case.name} first_error did not expose the load effect guard: $result",
                result.getString("first_error").contains("effect-call-during-load"),
            )
            assertTrue(
                "${case.name} second_error did not expose the load effect guard: $result",
                result.getString("second_error").contains("effect-call-during-load"),
            )
            assertEquals(
                "${case.name} failure left a partial cache entry instead of retrying: $result",
                2,
                result.getInt("attempts"),
            )
            s.assertClosed(s.closeState(handle), case.name)
        }
    }
}
