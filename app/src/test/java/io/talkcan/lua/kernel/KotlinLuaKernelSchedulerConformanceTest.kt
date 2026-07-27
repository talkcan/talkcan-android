package io.talkcan.lua.kernel

import io.talkcan.lua.HostOperationClaim
import io.talkcan.lua.HostOperationKind
import io.talkcan.lua.LuaCallbackHandle
import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaOperationId
import io.talkcan.lua.LuaSpawnAdmission
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaValue
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM conformance port of every category-S (coroutine-scheduling) Rust test
 * from the `replace-rust-lua-kernel` inventory. Each [Test] method maps
 * one-for-one to a Rust `#[test]` fn, preserving the exact name and
 * table-driven variants. All tests drive [io.talkcan.lua.LuaKernelBridge]
 * through [KotlinLuaKernelConformanceSupport] and assert observable outcomes,
 * values, and state transitions: independent yield/resume ownership,
 * descendant spawning, timer-capacity sleeps, spawn-admission slot release,
 * deferred-work commit/discard ordering, and sibling-task isolation.
 *
 * Rust's plain `StateEngine` dispatch calls run with the engine's accept-all
 * spawn-admission default; the JVM bridge defaults to rejecting, so every port
 * passes [acceptAll] explicitly wherever the Rust original relied on the
 * accepting default.
 */
class KotlinLuaKernelSchedulerConformanceTest {

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
    // diagnostics_fixture.rs — heartbeat managed-task spawn
    // ==================================================================

    @Test
    fun `diagnostics_startup_spawns_heartbeat`() {
        val handle = s.createState()
        s.loadProgramImageOk(
            handle,
            "plugin",
            mapOf("plugin" to diagnosticsPluginSource()),
        )
        val startup = s.assertCompleted(
            s.invokeStartupCallback(handle, spawnAdmission = acceptAll),
            "startup",
        )
        val value = s.resultObject(startup)
        assertFalse("valid config must not produce error: $value", value.has("error"))
        // The heartbeat coroutine is spawned during startup and immediately
        // yields on runtime.sleep(30.0); it must surface as an admitted
        // managed task.
        val spawned = startup.spawnedCoroutines
        assertTrue(
            "startup must spawn the heartbeat managed task: $startup",
            !spawned.isNullOrEmpty(),
        )
        s.assertClosed(s.closeState(handle))
    }

    // ==================================================================
    // fs_conformance.rs — independent suspensions
    // ==================================================================

    @Test
    fun `fs_actor_work_continues_while_io_suspended`() {
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
                    local runtime = require("talkcan.runtime")
                    local task_done = false
                    return {
                      startup = function()
                        runtime.spawn(function()
                          local mount = fs.mount("data")
                          fs.mkdir(mount, "task_dir", {parents=false})
                          task_done = true
                        end)
                      end,
                      handle_input = function(event)
                        local mount = fs.mount("data")
                        fs.write_text(mount, "input.txt", "data", {mode="replace"})
                        return {ok=true}
                      end,
                    }
                """.trimIndent(),
            ),
        )

        // Startup admits the managed task.
        val start = startupCompleted(handle, LuaValue.Map(emptyMap()))
        val taskCo = spawnedIds(start, "startup spawn")[0]

        // Start the task coroutine: it yields on mkdir.
        val taskStart = assertYielded(startTask(handle, taskCo), "task mkdir suspension")
        val taskOperation = operationHandle(handle, taskStart)
        val taskRequest = requestIdOf(taskStart)

        // Meanwhile, handle_input yields on write_text.
        val input = assertYielded(
            invokeInput(handle, inputEvent(), "tok", acceptAll),
            "handle_input write_text suspension",
        )
        val inputOperation = operationHandle(handle, input)
        val inputRequest = requestIdOf(input)

        // Both operations are independently claimable.
        claimAdmitted(handle, taskRequest, HostOperationKind.FS_MKDIR, "task claim")
        claimAdmitted(handle, inputRequest, HostOperationKind.FS_WRITE_TEXT, "input claim")

        // Resume task first.
        s.assertCompleted(
            s.bridge.resume(taskOperation, true, """{"status":"created"}""", acceptAll),
            "task mkdir resume",
        )

        // Then resume input.
        val inputDone = s.assertCompleted(
            s.bridge.resume(inputOperation, true, """{"status":"written","bytes":4}""", acceptAll),
            "input write_text resume",
        )
        assertTrue(
            "handle_input result: ${s.resultString(inputDone)}",
            s.resultObject(inputDone).getBoolean("ok"),
        )
        s.assertClosed(s.closeState(handle))
    }

    // ==================================================================
    // keyboard_conformance.rs — distinct owners complete independently
    // ==================================================================

    @Test
    fun `input_and_task_keyboard_operations_complete_independently`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"keyboardOutput":true}""")
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    local kb = require("talkcan.keyboard_output")
                    return {
                      startup = function()
                        talkcan.runtime.spawn(function()
                          local result, err = kb.send_text({ text = "task-text", profile = "task-profile" })
                          if not result or result.status ~= "delivered" then error("task delivery failed") end
                        end)
                        return nil
                      end,
                      handle_input = function(event)
                        local result, err = kb.send_text({ text = "input-text", profile = "input-profile" })
                        if not result then return { error = { code = "SEND", detail = err.error } } end
                        if result.status ~= "delivered" then
                          return { error = { code = "STATUS", detail = result.status } }
                        end
                        return { ok = true }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val startup = startupCompleted(handle)
        val taskId = spawnedIds(startup, "startup spawn")[0]

        // Input yields its keyboard request first.
        val inputOutcome = assertYielded(
            invokeInput(handle, inputEvent(), "tok", acceptAll),
            "input keyboard suspension",
        )
        val inputOperation = operationHandle(handle, inputOutcome)

        // The task starts and yields its own independent request.
        val taskOutcome = assertYielded(startTask(handle, taskId), "task keyboard suspension")
        val taskOperation = operationHandle(handle, taskOutcome)

        // Each claim returns only its owner's typed payload.
        val inputClaim = claimAdmitted(
            handle,
            requestIdOf(inputOutcome),
            HostOperationKind.KEYBOARD_SEND_TEXT,
            "input claim",
        )
        assertEquals("input claim text", "input-text", inputClaim.text)
        assertEquals("input claim profile", "input-profile", inputClaim.profile)
        val taskClaim = claimAdmitted(
            handle,
            requestIdOf(taskOutcome),
            HostOperationKind.KEYBOARD_SEND_TEXT,
            "task claim",
        )
        assertEquals("task claim text", "task-text", taskClaim.text)
        assertEquals("task claim profile", "task-profile", taskClaim.profile)

        // Completing the input does not disturb the task's suspended request.
        val inputDone = s.assertCompleted(
            s.bridge.resume(inputOperation, true, """{"status":"delivered"}""", acceptAll),
            "input keyboard resume",
        )
        assertTrue(
            "handle_input result: ${s.resultString(inputDone)}",
            s.resultObject(inputDone).getBoolean("ok"),
        )
        s.assertClaimRejected(
            s.bridge.claimHostOperation(handle, taskClaim.requestId),
            "the task request was already claimed exactly once",
        )
        s.assertCompleted(
            s.bridge.resume(taskOperation, true, """{"status":"delivered"}""", acceptAll),
            "task keyboard resume",
        )
        s.assertClosed(s.closeState(handle))
    }

    // ==================================================================
    // runtime_v1_conformance.rs — spawn/sleep bounds and raw-yield boundary
    // ==================================================================

    @Test
    fun `runtime_spawn_sleep_context_bounds_and_raw_yield_boundary_are_observable_through_state_engine`() {
        val handle = s.createState(config(tasks = 2, timers = 2))
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    local runtime = require("talkcan.runtime")
                    return {
                      startup = function() end,
                      invalid_context = function()
                        local spawn_value, spawn_error = runtime.spawn(function() end)
                        local sleep_value, sleep_error = runtime.sleep(1)
                        local invalid_spawn, invalid_spawn_error = runtime.spawn("not a function")
                        local invalid_sleep, invalid_sleep_error = runtime.sleep(-1)
                        return {
                          spawn_value_is_nil = spawn_value == nil,
                          spawn_error = spawn_error.error,
                          sleep_value_is_nil = sleep_value == nil,
                          sleep_error = sleep_error.error,
                          invalid_spawn_is_nil = invalid_spawn == nil,
                          invalid_spawn_error = invalid_spawn_error.error,
                          invalid_sleep_is_nil = invalid_sleep == nil,
                          invalid_sleep_error = invalid_sleep_error.error,
                        }
                      end,
                      child_spawn_observed = function()
                        local child = coroutine.create(function()
                          return runtime.spawn(function() end)
                        end)
                        local resumed, result, error = coroutine.resume(child)
                        return {
                          resumed = resumed,
                          result_is_nil = result == nil,
                          error = error.error,
                        }
                      end,
                      child_sleep_observed = function()
                        local child = coroutine.create(function()
                          return runtime.sleep(1)
                        end)
                        local resumed, result, error = coroutine.resume(child)
                        return {
                          resumed = resumed,
                          result_is_nil = result == nil,
                          error = error.error,
                        }
                      end,
                      ignored_invalid_spawn = function()
                        runtime.spawn(function() end)
                        return { plugin_tried_to_hide = true }
                      end,
                      composition = function()
                        local child = coroutine.create(function()
                          coroutine.yield("child-yield")
                          return "child-complete"
                        end)
                        local first_ok, first = coroutine.resume(child)
                        local second_ok, second = coroutine.resume(child)
                        return {
                          first_ok = first_ok,
                          first = first,
                          second_ok = second_ok,
                          second = second,
                        }
                      end,
                      raw_yield = function() return coroutine.yield("escapes actor boundary") end,
                    }
                """.trimIndent(),
            ),
        )

        val invalidContext = s.resultObject(
            s.assertCompleted(invokeAccepting(handle, "invalid_context"), "invalid_context"),
        )
        assertTrue("spawn_value_is_nil: $invalidContext", invalidContext.getBoolean("spawn_value_is_nil"))
        assertEquals("E_INVALID_CONTEXT", invalidContext.getString("spawn_error"))
        assertTrue("sleep_value_is_nil: $invalidContext", invalidContext.getBoolean("sleep_value_is_nil"))
        assertEquals("E_INVALID_CONTEXT", invalidContext.getString("sleep_error"))
        assertTrue("invalid_spawn_is_nil: $invalidContext", invalidContext.getBoolean("invalid_spawn_is_nil"))
        assertEquals("E_INVALID_ARGUMENT", invalidContext.getString("invalid_spawn_error"))
        assertTrue("invalid_sleep_is_nil: $invalidContext", invalidContext.getBoolean("invalid_sleep_is_nil"))
        assertEquals("E_INVALID_ARGUMENT", invalidContext.getString("invalid_sleep_error"))

        val childSpawn = s.resultObject(
            s.assertCompleted(invokeAccepting(handle, "child_spawn_observed"), "child_spawn_observed"),
        )
        assertTrue(
            "an observed child-coroutine spawn violation must remain a normal E_INVALID_CONTEXT pair: $childSpawn",
            childSpawn.getBoolean("resumed"),
        )
        assertTrue("child spawn result_is_nil: $childSpawn", childSpawn.getBoolean("result_is_nil"))
        assertEquals("E_INVALID_CONTEXT", childSpawn.getString("error"))

        val childSleep = s.resultObject(
            s.assertCompleted(invokeAccepting(handle, "child_sleep_observed"), "child_sleep_observed"),
        )
        assertTrue(
            "a plugin-created child coroutine must not be able to start a managed sleep: $childSleep",
            childSleep.getBoolean("resumed"),
        )
        assertTrue("child sleep result_is_nil: $childSleep", childSleep.getBoolean("result_is_nil"))
        assertEquals("E_INVALID_CONTEXT", childSleep.getString("error"))

        val ignored = s.assertCompleted(invokeAccepting(handle, "ignored_invalid_spawn"), "ignored_invalid_spawn")
        val ignoredValue = s.resultObject(ignored)
        assertTrue(
            "a synchronous callback may continue after an invalid-context pair: $ignoredValue",
            ignoredValue.getBoolean("plugin_tried_to_hide"),
        )
        assertTrue("a rejected spawn must not be retained: $ignored", ignored.spawnedCoroutines.isNullOrEmpty())

        val composition = s.resultObject(
            s.assertCompleted(invokeAccepting(handle, "composition"), "composition"),
        )
        assertTrue("composition first_ok: $composition", composition.getBoolean("first_ok"))
        assertEquals("child-yield", composition.getString("first"))
        assertTrue("composition second_ok: $composition", composition.getBoolean("second_ok"))
        assertEquals("child-complete", composition.getString("second"))

        val rawYield = s.assertRuntimeFailure(invokeAccepting(handle, "raw_yield"), "raw_yield")
        assertTrue(
            "raw talkcan yield must normalize as E_INVALID_YIELD: ${rawYield.diagnostic}",
            rawYield.diagnostic.contains("E_INVALID_YIELD"),
        )
        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `managed_tasks_can_spawn_descendants_and_invalid_runtime_arguments_do_not_suspend`() {
        val handle = s.createState(config(tasks = 2, timers = 1))
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    local runtime = require("talkcan.runtime")
                    return {
                      startup = function()
                        local admitted = runtime.spawn(function()
                          local child, child_error = runtime.spawn(function() return "grandchild" end)
                          return child and "child-spawned" or (child_error and child_error.error or "failed")
                        end)
                        return {admitted = admitted}
                      end,
                      invalid_arguments = function()
                        local spawn_string, spawn_string_error = runtime.spawn("not-a-function")
                        local spawn_number, spawn_number_error = runtime.spawn(42)
                        local spawn_extra, spawn_extra_error = runtime.spawn(function() end, "extra")
                        local negative, negative_error = runtime.sleep(-1)
                        local nan, nan_error = runtime.sleep(0 / 0)
                        local positive_infinity, positive_infinity_error = runtime.sleep(math.huge)
                        local too_long, too_long_error = runtime.sleep(86401)
                        return {
                          spawn_string = spawn_string,
                          spawn_string_error = spawn_string_error.error,
                          spawn_number = spawn_number,
                          spawn_number_error = spawn_number_error.error,
                          spawn_extra_error = spawn_extra_error.error,
                          negative = negative,
                          negative_error = negative_error.error,
                          nan = nan,
                          nan_error = nan_error.error,
                          positive_infinity = positive_infinity,
                          positive_infinity_error = positive_infinity_error.error,
                          too_long = too_long,
                          too_long_error = too_long_error.error,
                        }
                      end,
                    }
                """.trimIndent(),
            ),
        )

        val startup = startupCompleted(handle)
        assertTrue("startup admission: ${s.resultString(startup)}", s.resultObject(startup).getBoolean("admitted"))
        val parentId = spawnedIds(startup, "startup spawn")[0]

        val parent = s.assertCompleted(startTask(handle, parentId), "parent task")
        assertEquals("child-spawned", resultScalar(parent))
        val childId = spawnedIds(parent, "descendant spawn")[0]
        val child = s.assertCompleted(startTask(handle, childId), "grandchild task")
        assertEquals("grandchild", resultScalar(child))

        val invalid = s.assertCompleted(invokeAccepting(handle, "invalid_arguments"), "invalid_arguments")
        val invalidValue = s.resultObject(invalid)
        for (field in listOf(
            "spawn_string_error",
            "spawn_number_error",
            "spawn_extra_error",
            "negative_error",
            "nan_error",
            "positive_infinity_error",
            "too_long_error",
        )) {
            assertEquals(
                "$field must reject without suspending: $invalidValue",
                "E_INVALID_ARGUMENT",
                invalidValue.getString(field),
            )
        }
        for (field in listOf("spawn_string", "spawn_number", "negative", "nan", "positive_infinity", "too_long")) {
            assertTrue("$field must be nil: $invalidValue", invalidValue.isNull(field))
        }
        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `managed_sleep_uses_timer_capacity_and_live_cancellation_delivery`() {
        val handle = s.createState(config(tasks = 2, timers = 1))
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    local runtime = require("talkcan.runtime")
                    return {
                      startup = function()
                        local first = runtime.spawn(function()
                          local ok, err = runtime.sleep(1)
                          return ok and "ok" or (err and err.error or "failed")
                        end)
                        local second = runtime.spawn(function()
                          local ok, err = runtime.sleep(1)
                          return ok and "ok" or (err and err.error or "failed")
                        end)
                        return {first = first, second = second}
                      end,
                    }
                """.trimIndent(),
            ),
        )

        val startup = startupCompleted(handle)
        val ids = spawnedIds(startup, "startup spawn")
        assertEquals("startup must admit both sleepers: $startup", 2, ids.size)

        val first = assertYielded(startTask(handle, ids[0]), "first sleep consumes the only timer slot")
        val firstOperation = operationHandle(handle, first)

        val second = s.assertCompleted(
            startTask(handle, ids[1]),
            "second task observes exhausted timer capacity",
        )
        assertEquals("E_BUSY", resultScalar(second))

        val cancelled = s.assertCompleted(
            s.bridge.resume(firstOperation, false, "E_CANCELLED", acceptAll),
            "live cancellation delivery",
        )
        assertEquals("E_CANCELLED", resultScalar(cancelled))

        val duplicate = s.assertCompleted(
            s.bridge.resume(firstOperation, true, "", acceptAll),
            "duplicate resume echoes the terminal outcome",
        )
        assertEquals("E_CANCELLED", resultScalar(duplicate))
        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `repeated_operation_specific_sleeps_survive_without_generic_task_expiry_and_close_blocks_resume`() {
        val handle = s.createState(config(tasks = 1, timers = 1))
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    local runtime = require("talkcan.runtime")
                    return {
                      startup = function()
                        return {admitted = runtime.spawn(function()
                          for _ = 1, 8 do
                            local ok, err = runtime.sleep(0)
                            if not ok then return err.error end
                          end
                          return "completed"
                        end)}
                      end,
                    }
                """.trimIndent(),
            ),
        )

        val startup = startupCompleted(handle)
        val coroutineId = spawnedIds(startup, "startup spawn")[0]
        var outcome = startTask(handle, coroutineId)
        repeat(8) { slice ->
            val yielded = assertYielded(outcome, "per-operation sleep slice $slice")
            outcome = s.bridge.resume(operationHandle(handle, yielded), true, "", acceptAll)
        }
        val finished = s.assertCompleted(outcome, "eighth slice completes the task")
        assertEquals("completed", resultScalar(finished))

        // A still-suspended operation is discarded by close; a late resume
        // may not re-enter the Lua state (CL/S).
        val secondStart = startupCompleted(handle)
        val secondId = spawnedIds(secondStart, "second startup spawn")[0]
        val second = assertYielded(startTask(handle, secondId), "second task sleep")
        s.assertClosed(s.closeState(handle))
        s.assertClosedOrStale(
            s.bridge.resume(operationHandle(handle, second), true, "", acceptAll),
            "late resume after close",
        )
    }

    @Test
    fun `yielded_audio_operation_releases_execution_slot`() {
        val handle = s.createState(s.runtimeV1Config())
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    local s = require("talkcan.synthesis")
                    return {
                      startup = function() return {ready = true} end,
                      handle_input = function(event)
                        local syn, err = s.synthesize({text="hello", language="en-US", voice="v"})
                        if err then return {error = "synthesis-failed:" .. err.error} end
                        return {synthesized = tostring(syn)}
                      end,
                    }
                """.trimIndent(),
            ),
        )

        val yieldOut = assertYielded(
            invokeInput(handle, inputEvent(), "captured-token", acceptAll),
            "handle_input synthesis suspension",
        )
        val operation = operationHandle(handle, yieldOut)

        // While handle_input is yielded on an audio operation, another
        // callback must be invocable — proving the serialized actor slot was
        // released.
        val interleaved = startupCompleted(handle)
        assertTrue(
            "another callback must run while handle_input is suspended: ${s.resultString(interleaved)}",
            s.resultObject(interleaved).getBoolean("ready"),
        )

        // Resume the suspended audio operation.
        s.assertCompleted(
            s.bridge.resume(operation, true, "synthesized:slot-release-token", acceptAll),
            "audio resume after slot release",
        )
        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `input_defer_commits_only_after_terminal_success_and_releases_every_failure_path`() {
        val handle = s.createState(config(tasks = 1, timers = 4))
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    local runtime = require("talkcan.runtime")
                    local transcription = require("talkcan.transcription")
                    local ran = 0
                    local busy = ""
                    return {
                      startup = function() end,
                      outside = function()
                        local _, context_error = runtime.defer(function() end)
                        local _, argument_error = runtime.defer("not-a-function")
                        return {
                          context = context_error.error,
                          argument = argument_error.error,
                        }
                      end,
                      probe = function()
                        return { ran = ran, busy = busy }
                      end,
                      handle_input = function(event)
                        local ok, defer_error = runtime.defer(function()
                          ran = ran + 1
                        end)
                        if not ok then
                          return { error = { code = defer_error.error, detail = "defer" } }
                        end
                        if event.mode == "two" then
                          local _, second_error = runtime.defer(function()
                            ran = ran + 100
                          end)
                          busy = second_error.error
                        end
                        if event.mode == "yield" then
                          local _, transcription_error =
                            transcription.transcribe(event.audio)
                          if transcription_error then
                            return {
                              error = {
                                code = transcription_error.error,
                                detail = "transcription",
                              },
                            }
                          end
                        end
                        if event.mode == "fail" then
                          return { error = { code = "E_IO", detail = "failed" } }
                        end
                        return { ok = true }
                      end,
                    }
                """.trimIndent(),
            ),
        )

        val outside = s.resultObject(
            s.assertCompleted(invokeAccepting(handle, "outside"), "outside"),
        )
        assertEquals("E_INVALID_CONTEXT", outside.getString("context"))
        assertEquals("E_INVALID_ARGUMENT", outside.getString("argument"))

        val admitter = CountingAdmission()

        // Error path releases the defer slot without admitting work.
        val failed = s.assertCompleted(
            invokeInput(handle, inputEvent("mode" to "fail"), "captured", admitter),
            "fail path",
        )
        assertTrue("fail path must admit nothing: $failed", failed.spawnedCoroutines.isNullOrEmpty())
        assertEquals("fail path admissions", 0, admitter.admissions.get())
        assertProbe(handle, expectedRan = 0, expectedBusy = "")

        // Yield path holds the deferred work until terminal success.
        val yielded = assertYielded(
            invokeInput(handle, inputEvent("mode" to "yield"), "captured", admitter),
            "yield path suspension",
        )
        assertTrue("yield path must admit nothing before resume: $yielded", yielded.spawnedCoroutines.isNullOrEmpty())
        assertEquals("yield path admissions before resume", 0, admitter.admissions.get())
        assertProbe(handle, expectedRan = 0, expectedBusy = "")
        val committed = s.assertCompleted(
            s.bridge.resume(operationHandle(handle, yielded), true, "", admitter),
            "yield path terminal resume",
        )
        val deferredId = spawnedIds(committed, "deferred admission on terminal success")[0]
        assertEquals("terminal success admits exactly the deferred task", 1, admitter.admissions.get())
        assertProbe(handle, expectedRan = 0, expectedBusy = "")
        s.assertCompleted(startTask(handle, deferredId, admitter), "deferred task execution")
        assertProbe(handle, expectedRan = 1, expectedBusy = "")

        // A second defer while the first is still unstarted is bounded.
        val bounded = s.assertCompleted(
            invokeInput(handle, inputEvent("mode" to "two"), "captured", admitter),
            "two-defer path",
        )
        val boundedId = spawnedIds(bounded, "first defer admission")[0]
        assertProbe(handle, expectedRan = 1, expectedBusy = "E_BUSY")
        s.assertCompleted(startTask(handle, boundedId, admitter), "bounded deferred task execution")
        assertProbe(handle, expectedRan = 2, expectedBusy = "E_BUSY")

        // Cancellation discards the suspended owner and releases the slot.
        val cancelled = assertYielded(
            invokeInput(handle, inputEvent("mode" to "yield"), "captured", admitter),
            "cancel path suspension",
        )
        assertCancelled(
            s.bridge.cancel(operationHandle(handle, cancelled)),
            "cancel of suspended input coroutine",
        )
        val afterCancel = s.assertCompleted(
            invokeInput(handle, inputEvent(), "captured", admitter),
            "post-cancel input",
        )
        assertEquals(
            "post-cancel input must admit exactly one deferred task: $afterCancel",
            1,
            spawnedIds(afterCancel, "post-cancel defer admission").size,
        )
        s.assertClosed(s.closeState(handle))
    }

    @Test
    fun `durable_input_write_precedes_deferred_mount_work`() {
        val handle = s.createState(config(tasks = 2, timers = 1))
        s.installResourceContext(
            handle,
            """{"instanceId":"journal","storageFiles":true,"audioFiles":false,"mounts":{"output":{"access":"read-write","status":"available"}}}""",
        )
        s.loadProgramImageOk(
            handle,
            "entry",
            mapOf(
                "entry" to """
                    local fs = require("talkcan.fs")
                    local runtime = require("talkcan.runtime")
                    return {
                      startup = function() end,
                      handle_input = function()
                        local mount = assert(fs.mount("output"))
                        assert(fs.write_text(
                          mount, "pending.json", "{}", { mode = "replace" }
                        ))
                        assert(runtime.defer(function()
                          assert(fs.stat(mount, "pending.json"))
                        end))
                        return { ok = true }
                      end,
                    }
                """.trimIndent(),
            ),
        )

        val admitter = CountingAdmission()
        val write = assertYielded(
            invokeInput(handle, inputEvent(), "captured-token", admitter),
            "handle_input write suspension",
        )
        claimAdmitted(handle, requestIdOf(write), HostOperationKind.FS_WRITE_TEXT, "write claim")
        assertEquals("no admission before the write completes", 0, admitter.admissions.get())

        val committed = s.assertCompleted(
            s.bridge.resume(operationHandle(handle, write), true, """{"status":"written","bytes":2}""", admitter),
            "write resume",
        )
        assertTrue(
            "handle_input result: ${s.resultString(committed)}",
            s.resultObject(committed).getBoolean("ok"),
        )
        assertEquals("terminal success admits exactly the deferred task", 1, admitter.admissions.get())
        val deferredId = spawnedIds(committed, "deferred admission on terminal success")[0]

        val deferred = assertYielded(startTask(handle, deferredId, admitter), "deferred stat suspension")
        claimAdmitted(handle, requestIdOf(deferred), HostOperationKind.FS_STAT, "deferred stat claim")
        s.assertClosed(s.closeState(handle))
    }

    // ==================================================================
    // work_conformance.rs — sibling task isolation
    // ==================================================================

    @Test
    fun `work_sibling_task_isolation`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"workQueue":true,"workQueues":["turns"]}""")
        s.loadProgramImageOk(
            handle,
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
                          job:complete({ task = "first" })
                        end)
                        runtime.spawn(function()
                          local q = work.open("turns")
                          local job = q:receive()
                          job:complete({ task = "second" })
                        end)
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val start = startupCompleted(handle)
        val spawned = spawnedIds(start, "startup spawn")
        assertEquals("startup must admit both sibling tasks: $start", 2, spawned.size)
        val task1 = spawned[0]
        val task2 = spawned[1]

        // Start both tasks; each suspends on its own receive.
        val started1 = assertYielded(startTask(handle, task1), "task1 receive suspension")
        val started2 = assertYielded(startTask(handle, task2), "task2 receive suspension")

        // Drive task1 to completion.
        val after1 = assertYielded(
            claimAndResume(handle, started1, HostOperationKind.WORK_RECEIVE, """{"jobId":"j1","payloadJson":"{\"t\":\"null\"}" }"""),
            "task1 complete suspension",
        )
        val done1 = claimAndResume(handle, after1, HostOperationKind.WORK_COMPLETE, """{"ok":true}""")
        s.assertCompleted(done1, "task1 completion")

        // Task2 remains independently drivable and never crosses task1's jobs.
        val after2 = assertYielded(
            claimAndResume(handle, started2, HostOperationKind.WORK_RECEIVE, """{"jobId":"j2","payloadJson":"{\"t\":\"null\"}" }"""),
            "task2 complete suspension",
        )
        val done2 = claimAndResume(handle, after2, HostOperationKind.WORK_COMPLETE, """{"ok":true}""")
        s.assertCompleted(done2, "task2 completion")
        s.assertClosed(s.closeState(handle))
    }

    // ==================================================================
    // Port helpers (file-private; mirror the Rust file-private helpers)
    // ==================================================================

    /** runtime_v1_conformance.rs `engine(tasks, timers)` constants. */
    private fun config(tasks: Int, timers: Int): LuaKernelConfig = LuaKernelConfig(
        hookInterval = 100,
        instructionBudget = 50_000,
        maxConcurrentTasks = tasks,
        maxTimerSlots = timers,
    )

    /** Rust's engine-default accepting spawn admission (port code 0). */
    private val acceptAll: LuaSpawnAdmission = object : LuaSpawnAdmission {
        override fun admitTask(coroutineId: Long): Int = 0
    }

    /** Counting accepting admitter mirroring the Rust AtomicUsize fixtures. */
    private class CountingAdmission : LuaSpawnAdmission {
        val admissions = AtomicInteger(0)

        override fun admitTask(coroutineId: Long): Int {
            admissions.incrementAndGet()
            return 0
        }
    }

    /**
     * Capture event fixture mirroring the Rust `CAPTURE_EVENT` constant and
     * the runtime_v1 `invoke_input` metadata injection, plus optional event
     * fields (e.g. `mode`).
     */
    private fun inputEvent(vararg fields: Pair<String, String>): LuaValue {
        val pairs = LinkedHashMap<String, LuaValue>()
        for ((key, value) in fields) {
            pairs[key] = LuaValue.StringValue(value)
        }
        pairs["metadata"] = LuaValue.Map(
            mapOf(
                "sample_rate" to LuaValue.Integer(16_000L),
                "channels" to LuaValue.Integer(1L),
                "duration_ms" to LuaValue.Integer(1L),
                "pcm_bytes" to LuaValue.Integer(32L),
            ),
        )
        return LuaValue.Map(pairs)
    }

    /** Plain synchronous callback invocation with the accepting default. */
    private fun invokeAccepting(handle: LuaStateHandle, name: String): LuaKernelOutcome =
        s.bridge.invokeCallback(handle, LuaCallbackHandle(handle, name), LuaValue.Nil, acceptAll)

    /** Startup invocation with an explicit config argument and admission. */
    private fun startupCompleted(
        handle: LuaStateHandle,
        config: LuaValue = LuaValue.Nil,
    ): LuaKernelOutcome.Completed =
        s.assertCompleted(
            s.bridge.invokeStartupCallback(handle, LuaCallbackHandle(handle, "startup"), config, acceptAll),
            "invokeStartupCallback",
        )

    /** handle_input invocation in a host-managed coroutine. */
    private fun invokeInput(
        handle: LuaStateHandle,
        event: LuaValue,
        token: String,
        admission: LuaSpawnAdmission,
    ): LuaKernelOutcome =
        s.bridge.invokeInputCallback(
            handle,
            LuaCallbackHandle(handle, "handle_input"),
            event,
            token,
            admission,
        )

    private fun startTask(
        handle: LuaStateHandle,
        coroutineId: Long,
        admission: LuaSpawnAdmission = acceptAll,
    ): LuaKernelOutcome =
        s.bridge.startCoroutine(handle, LuaCoroutineId(coroutineId), admission)

    private fun assertYielded(outcome: LuaKernelOutcome, context: String): LuaKernelOutcome.Yielded {
        if (outcome !is LuaKernelOutcome.Yielded) {
            throw AssertionError("$context: expected Yielded but was $outcome")
        }
        return outcome
    }

    private fun operationHandle(
        handle: LuaStateHandle,
        yielded: LuaKernelOutcome.Yielded,
    ): LuaOperationHandle =
        LuaOperationHandle(
            stateHandle = handle,
            coroutineId = LuaCoroutineId(yielded.coroutineId),
            operationId = LuaOperationId(yielded.operationId),
        )

    private fun requestIdOf(yielded: LuaKernelOutcome.Yielded): Long =
        (yielded.value ?: throw AssertionError("yielded outcome carries no request identity: $yielded"))
            .toLong()

    private fun spawnedIds(completed: LuaKernelOutcome.Completed, context: String): List<Long> =
        completed.spawnedCoroutines?.takeIf { it.isNotEmpty() }
            ?: throw AssertionError("$context: completed outcome admitted no coroutines: $completed")

    private fun claimAdmitted(
        handle: LuaStateHandle,
        requestId: Long,
        expectedKind: HostOperationKind,
        context: String,
    ): HostOperationClaim.Admitted {
        val claim = s.bridge.claimHostOperation(handle, requestId)
        if (claim !is HostOperationClaim.Admitted) {
            throw AssertionError("$context: expected Admitted $expectedKind claim but was $claim")
        }
        assertEquals("$context: claim kind", expectedKind, claim.kind)
        return claim
    }

    /** Claim one yielded operation, assert its typed kind, and resume success. */
    private fun claimAndResume(
        handle: LuaStateHandle,
        yielded: LuaKernelOutcome.Yielded,
        expectedKind: HostOperationKind,
        resumeJson: String,
    ): LuaKernelOutcome {
        claimAdmitted(handle, requestIdOf(yielded), expectedKind, "claim $expectedKind")
        return s.bridge.resume(operationHandle(handle, yielded), true, resumeJson, acceptAll)
    }

    /** Completed scalar-string result (Lua `return "x"` → JSON `"x"`). */
    private fun resultScalar(completed: LuaKernelOutcome.Completed): String =
        JSONArray("[" + s.resultString(completed) + "]").getString(0)

    private fun assertProbe(handle: LuaStateHandle, expectedRan: Int, expectedBusy: String) {
        val probe = s.resultObject(
            s.assertCompleted(invokeAccepting(handle, "probe"), "probe"),
        )
        assertEquals("probe ran: $probe", expectedRan, probe.getInt("ran"))
        assertEquals("probe busy: $probe", expectedBusy, probe.getString("busy"))
    }

    private fun assertCancelled(outcome: LuaKernelOutcome, context: String): LuaKernelOutcome.Cancelled {
        if (outcome !is LuaKernelOutcome.Cancelled) {
            throw AssertionError("$context: expected Cancelled but was $outcome")
        }
        return outcome
    }

    /**
     * Exact `lua/plugin.lua` source from the canonical diagnostics-channel
     * test archive, mirroring Rust `source_from_exact_archive()`.
     */
    private fun diagnosticsPluginSource(): String {
        val archive = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("diagnostics-channel/talkcan-channel.zip"),
        ) {
            "Missing diagnostics channel fixture: diagnostics-channel/talkcan-channel.zip"
        }.use { it.readBytes() }
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "lua/plugin.lua") {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        throw AssertionError("exact Diagnostics fixture omitted lua/plugin.lua")
    }
}
