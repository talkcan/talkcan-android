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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Category H (interruption/hooks) conformance, ported one-for-one from the Rust
 * `#[test]` functions in `rust/talkcan-lua-actor/talkcan-lua-actor/tests/`
 * (conformance.rs, keyboard_conformance.rs, resolver_conformance.rs,
 * runtime_v1_conformance.rs, work_conformance.rs) against [LuaKernelBridge].
 *
 * The production bridge is instantiated reflectively by
 * [KotlinLuaKernelConformanceSupport]; every test fails with an explicit
 * [AssertionError] (never a skip) while the class is absent. Every state is
 * created through the support and closed deterministically (explicitly where
 * the Rust test asserts `Closed`, and again via [KotlinLuaKernelConformanceSupport.closeAll]
 * in [tearDown]).
 *
 * The Rust `concurrent_interrupt_resume_is_linearizable_per_state` test uses an
 * internal lock-release seam that does not exist in the confined JVM engine.
 * This port races `resume` and `interrupt` through latches and asserts the same
 * observable linearizability envelope: bounded classified outcomes, exactly-once
 * operation consumption, deterministic closure, and peer isolation.
 */
internal class KotlinLuaKernelInterruptionConformanceTest {

    private val support = KotlinLuaKernelConformanceSupport()
    private val bridge get() = support.bridge

    /** Spawn admission that always accepts (mirrors Rust `AcceptAdmitter`). */
    private val accepting = object : LuaSpawnAdmission {
        override fun admitTask(coroutineId: Long): Int = 0
    }

    @After
    fun tearDown() {
        support.closeAll()
    }

    // ------------------------------------------------------------------
    // conformance.rs
    // ------------------------------------------------------------------

    @Test
    fun `cancellation_and_close_races_admit_one_terminal_result`() {
        // resume-vs-cancel: both callers report the same accepted terminal.
        val state = support.createState()
        support.loadSourceOk(state, YIELDING_COUNTER, "main")
        val started = assertYielded(support.startEntry(state), "start")
        val op = operation(state, started)
        val (resumed, cancelled) = race(
            Callable { bridge.resume(op, true, "race") },
            Callable { bridge.cancel(op) },
        )
        val racers = listOf(resumed, cancelled)
        assertTrue(
            "race returned a non-terminal outcome: $racers",
            racers.all { it is LuaKernelOutcome.Completed || it is LuaKernelOutcome.Cancelled },
        )
        assertEquals(
            "the losing terminal request did not report the accepted terminal state: $racers",
            kindTag(resumed),
            kindTag(cancelled),
        )

        support.loadSourceOk(state, "function observe() return tostring(hits) end", "observe")
        val observed = support.startEntryOk(state)
        val expectedHits = if (resumed is LuaKernelOutcome.Completed) "1" else "0"
        assertEquals("hits must match the race winner", expectedHits, support.resultScalar(observed))
        support.assertClosed(support.closeState(state), "close")

        // resume-vs-close: close wins; resume lands in a bounded terminal set;
        // a later snapshot is stale.
        val raced = support.createState()
        support.loadSourceOk(raced, YIELDING_COUNTER, "main")
        val racedStart = assertYielded(support.startEntry(raced), "start")
        val racedOp = operation(raced, racedStart)
        val (lateResume, closeOutcome) = race(
            Callable { bridge.resume(racedOp, true, "late") },
            Callable { bridge.close(raced) },
        )
        support.assertClosed(closeOutcome, "close raced with resume")
        assertTrue(
            "completion raced with close unexpectedly: $lateResume",
            lateResume is LuaKernelOutcome.Completed ||
                lateResume is LuaKernelOutcome.Closed ||
                lateResume is LuaKernelOutcome.Stale,
        )
        val snapshot = bridge.snapshot(raced)
        assertTrue("snapshot after close must be Stale: $snapshot", snapshot is LuaKernelOutcome.Stale)
    }

    @Test
    fun `pure_lua_interruptions_are_state_local_and_compute_still_completes`() {
        val workloads = listOf(
            "function main() while true do end end",
            "local function recurse(n) return recurse(n + 1) end function main() return recurse(0) end",
            """
            local proxy = setmetatable({}, { __index = function() return 1 end })
            function main() while true do local value = proxy.missing end end
            """.trimIndent(),
        )
        val budget1000 = support.defaultConfig().copy(instructionBudget = 1_000)
        for (workload in workloads) {
            val interrupted = support.createState(budget1000)
            support.loadSourceOk(interrupted, workload, "main")
            val outcome = assertInterrupted(support.startEntry(interrupted), "start($workload)")
            assertNotNull("interruption omitted elapsed-time evidence: $outcome", outcome.elapsedNanos)
            support.assertClosed(support.closeState(interrupted), "close interrupted")

            val unaffected = support.createState()
            support.loadSourceOk(unaffected, "function main() return 'unaffected' end", "main")
            val healthy = support.startEntryOk(unaffected)
            assertEquals(
                "peer state must be unaffected by a sibling interruption",
                "unaffected",
                support.resultScalar(healthy),
            )
            support.assertClosed(support.closeState(unaffected), "close unaffected")
        }

        val compute = support.createState(support.defaultConfig().copy(instructionBudget = 100_000))
        support.loadSourceOk(
            compute,
            "function main() local sum = 0 for i = 1, 500 do sum = sum + i end return sum end",
            "main",
        )
        val computed = support.startEntryOk(compute)
        assertEquals(
            "budgeted compute must complete",
            "125250",
            support.resultScalar(computed),
        )
        support.assertClosed(support.closeState(compute), "close compute")
    }

    @Test
    fun `interrupting_a_suspended_operation_suppresses_its_later_lua_and_native_effects`() {
        val interrupted = support.createState()
        support.loadSourceOk(
            interrupted,
            """
            native_effects = 0
            function main()
              local _, value = talkcan.yield_operation("interrupt-me")
              native_effects = native_effects + 1
              return talkcan.host_hash(value)
            end
            function observe() return tostring(native_effects) end
            """.trimIndent(),
            "main",
        )
        val yielded = assertYielded(support.startEntry(interrupted), "start")
        val op = operation(interrupted, yielded)
        assertInterrupted(bridge.interrupt(interrupted), "interrupt suspended state")

        val survivor = support.createState()
        support.loadSourceOk(survivor, "function main() return 'survives-suspended-interrupt' end", "main")
        val survivorOutcome = support.startEntryOk(survivor)
        assertEquals("survives-suspended-interrupt", support.resultScalar(survivorOutcome))

        val late = bridge.resume(op, true, "foobar")
        assertInterrupted(
            late,
            "interrupted suspended operation terminal",
        )

        support.loadSourceOk(interrupted, "function observe() return tostring(native_effects) end", "observe")
        val observed = support.startEntryOk(interrupted)
        assertEquals(
            "the interrupted continuation entered Lua and produced its later effect",
            "0",
            support.resultScalar(observed),
        )
        support.assertClosed(support.closeState(interrupted), "close interrupted")
        support.assertClosed(support.closeState(survivor), "close survivor")
    }

    @Test
    fun `concurrent_interrupt_resume_is_linearizable_per_state`() {
        val state = support.createState()
        support.loadSourceOk(state, DOUBLE_YIELDING_COUNTER, "main")
        val started = assertYielded(support.startEntry(state), "start")
        val op1 = started.operationId
        val op1Handle = operation(state, started)

        // Race resume(op1) against interrupt(state) through a release latch so
        // both callers are in flight together. Under the serialized engine
        // thread either ordering is a legal linearization.
        val (resumeOutcome, interruptOutcome) = race(
            Callable { bridge.resume(op1Handle, true, "first-resume") },
            Callable { bridge.interrupt(state) },
        )

        // Bounded, classified outcomes for both racers.
        assertTrue(
            "interrupt of a suspended/active state must be Interrupted or Stale: $interruptOutcome",
            interruptOutcome is LuaKernelOutcome.Interrupted || interruptOutcome is LuaKernelOutcome.Stale,
        )
        assertTrue(
            "resume of the raced operation must advance or be rejected: $resumeOutcome",
            resumeOutcome is LuaKernelOutcome.Yielded ||
                resumeOutcome is LuaKernelOutcome.RuntimeFailure ||
                resumeOutcome is LuaKernelOutcome.Interrupted ||
                resumeOutcome is LuaKernelOutcome.InvalidOwnership ||
                resumeOutcome is LuaKernelOutcome.Stale,
        )
        // Exactly one racer consumed op1 (the other observed it already gone).
        assertTrue(
            "op1 was consumed by neither racer: resume=$resumeOutcome interrupt=$interruptOutcome",
            resumeOutcome is LuaKernelOutcome.Yielded || interruptOutcome is LuaKernelOutcome.Interrupted,
        )
        // Advancing resume replaces op1 with a fresh operation identity.
        if (resumeOutcome is LuaKernelOutcome.Yielded) {
            assertNotEquals("resume did not replace op1 with a fresh operation", op1, resumeOutcome.operationId)
        }

        // Exactly-once: op1 is terminally consumed, so a late resume is rejected.
        val lateOp1 = bridge.resume(op1Handle, true, "late-op1")
        assertRejectedCompletion(lateOp1, "late resume of consumed op1")

        // If resume advanced to op2, its later resume is a bounded terminal:
        // it completes with the joined value (interrupt lost) or is rejected
        // (interrupt invalidated the new suspension). Either is linearizable.
        if (resumeOutcome is LuaKernelOutcome.Yielded) {
            val op2Handle = operation(state, resumeOutcome)
            val completed = bridge.resume(op2Handle, true, "second-resume")
            assertTrue(
                "op2 resume must be a bounded terminal: $completed",
                completed is LuaKernelOutcome.Completed ||
                    completed is LuaKernelOutcome.RuntimeFailure ||
                    completed is LuaKernelOutcome.Interrupted ||
                    completed is LuaKernelOutcome.InvalidOwnership ||
                    completed is LuaKernelOutcome.Stale,
            )
            if (completed is LuaKernelOutcome.Completed) {
                assertEquals(
                    "success:first-resume:second-resume",
                    support.resultScalar(completed),
                )
            }
        }

        // A peer state remains independently usable after the race.
        val peer = support.createState()
        support.loadSourceOk(peer, "function main() return 'peer-survives' end", "main")
        val peerOutcome = support.startEntryOk(peer)
        assertEquals("peer-survives", support.resultScalar(peerOutcome))
        support.assertClosed(support.closeState(state), "close raced state")
        support.assertClosed(support.closeState(peer), "close peer")
    }

    // ------------------------------------------------------------------
    // keyboard_conformance.rs
    // ------------------------------------------------------------------

    @Test
    fun `cancel_discards_without_reentry_and_late_claim_fails`() {
        val state = support.createState(support.channelConfig())
        support.installResourceContext(state, """{"keyboardOutput":true}""")
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local kb = require("talkcan.keyboard_output")
                    return {
                      startup = function() end,
                      handle_input = function(event)
                        kb.send_text({ text = "x", profile = "p" })
                        return { ok = true }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val yielded = assertYielded(
            bridge.invokeInputCallback(
                state,
                LuaCallbackHandle(state, "handle_input"),
                captureEvent(),
                "tok",
                accepting,
            ),
            "invokeInputCallback",
        )
        val requestId = (yielded.value ?: throw AssertionError("missing request id: $yielded")).toLong()
        val op = operation(state, yielded)

        val cancelled = bridge.cancel(op)
        assertCancelled(cancelled, "cancel keyboard operation")

        // The request is gone: no capability acquisition can start from it.
        support.assertClaimRejected(bridge.claimHostOperation(state, requestId), "late claim")

        // Late completion echoes the exact terminal without re-entering Lua.
        val lateResume = bridge.resume(op, true, """{"status":"delivered"}""", accepting)
        assertCancelled(lateResume, "late resume echoes terminal")
    }

    @Test
    fun `managed_task_cancellation_never_reenters_lua`() {
        val state = support.createState(support.channelConfig())
        support.installResourceContext(state, """{"keyboardOutput":true}""")
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local kb = require("talkcan.keyboard_output")
                    _G.entered = 0
                    return {
                      startup = function()
                        talkcan.runtime.spawn(function()
                          local result = kb.send_text({ text = "task-text", profile = "p" })
                          _G.entered = _G.entered + 1
                        end)
                        return nil
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val startup = support.assertCompleted(
            support.invokeCallback(state, "startup", LuaValue.Nil, accepting),
            "startup",
        )
        val taskId = (startup.spawnedCoroutines ?: throw AssertionError("startup spawned nothing: $startup"))
            .firstOrNull() ?: throw AssertionError("startup spawned nothing: $startup")
        val taskOutcome = assertYielded(
            bridge.startCoroutine(state, LuaCoroutineId(taskId), accepting),
            "startCoroutine",
        )
        val requestId = (taskOutcome.value ?: throw AssertionError("missing request id: $taskOutcome")).toLong()
        val op = operation(state, taskOutcome)

        // Cancel the task while the physical operation may still be pending host-side.
        assertCancelled(bridge.cancel(op), "cancel task operation")

        // Late claim and late completion both fail without Lua re-entry.
        support.assertClaimRejected(bridge.claimHostOperation(state, requestId), "late claim")
        val lateResume = bridge.resume(op, true, """{"status":"delivered"}""", accepting)
        assertCancelled(lateResume, "late resume echoes terminal")
    }

    @Test
    fun `sos_suspension_cancel_close_and_late_completion`() {
        // Cancel: exactly-once terminal, no re-entry, late resume echoes.
        val cancelled = support.createState(support.channelConfig())
        support.installResourceContext(cancelled, """{"keyboardOutput":true}""")
        loadSosImage(cancelled, "kb.send_key({ key = \"enter\", profile = \"p\" })\nreturn { ok = true }")
        val cancelYielded = assertYielded(
            bridge.invokeSosCallback(cancelled, LuaCallbackHandle(cancelled, "handle_sos"), LuaValue.Nil, accepting),
            "invokeSosCallback",
        )
        val cancelRequestId =
            (cancelYielded.value ?: throw AssertionError("missing request id: $cancelYielded")).toLong()
        val cancelOp = operation(cancelled, cancelYielded)
        assertCancelled(bridge.cancel(cancelOp), "cancel SOS suspension")
        support.assertClaimRejected(bridge.claimHostOperation(cancelled, cancelRequestId), "late claim")
        val cancelLateResume = bridge.resume(cancelOp, true, """{"status":"delivered"}""", accepting)
        assertCancelled(cancelLateResume, "late resume echoes terminal")

        // Close: generation revocation suppresses every late resume/claim.
        val closed = support.createState(support.channelConfig())
        support.installResourceContext(closed, """{"keyboardOutput":true}""")
        loadSosImage(closed, "kb.send_key({ key = \"enter\", profile = \"p\" })\nreturn { ok = true }")
        val closeYielded = assertYielded(
            bridge.invokeSosCallback(closed, LuaCallbackHandle(closed, "handle_sos"), LuaValue.Nil, accepting),
            "invokeSosCallback",
        )
        val closeRequestId =
            (closeYielded.value ?: throw AssertionError("missing request id: $closeYielded")).toLong()
        val closeOp = operation(closed, closeYielded)
        support.assertClosed(support.closeState(closed), "close SOS state")
        val closeLateResume = bridge.resume(closeOp, true, """{"status":"delivered"}""", accepting)
        assertTrue("late resume after close must be Stale: $closeLateResume", closeLateResume is LuaKernelOutcome.Stale)
        support.assertClaimRejected(bridge.claimHostOperation(closed, closeRequestId), "late claim after close")
    }

    // ------------------------------------------------------------------
    // resolver_conformance.rs
    // ------------------------------------------------------------------

    @Test
    fun `resolver_instruction_budget_exhaustion`() {
        val state = support.createResolverState(
            LuaKernelConfig(hookInterval = 1000, instructionBudget = 5_000),
        )
        val source = """
            return {
              resolve = function()
                local x = 0
                while true do x = x + 1 end
              end,
            }
        """.trimIndent()
        val outcome = support.invokeResolver(state, support.resolverInvocation(source))
        // Instruction-budget exhaustion in resolver normalizes to a package_error
        // completion or an Interrupted outcome, never a bridge fault.
        assertTrue(
            "expected Completed (package_error) or Interrupted, got: $outcome",
            outcome is LuaKernelOutcome.Completed || outcome is LuaKernelOutcome.Interrupted,
        )
        if (outcome is LuaKernelOutcome.Completed) {
            val result = JSONObject(outcome.value ?: throw AssertionError("Completed carries no value: $outcome"))
            assertEquals("package_error", result.getString("resultKind"))
        }
        // Resolver states auto-close after one terminal result; closure must not strand.
        support.assertClosedOrStale(support.closeState(state), "close resolver")
    }

    // ------------------------------------------------------------------
    // runtime_v1_conformance.rs
    // ------------------------------------------------------------------

    @Test
    fun `program_image_callbacks_interrupt_without_stranding_close`() {
        // Infinite callback under budget 1000 -> Interrupted, then closable.
        val interrupted = support.createState(support.runtimeV1Config().copy(instructionBudget = 1_000))
        support.loadProgramImageOk(
            interrupted,
            "entry",
            mapOf(
                "entry" to """
                    return {
                      startup = function() end,
                      loop = function() while true do end end,
                    }
                """.trimIndent(),
            ),
        )
        assertInterrupted(support.invokeCallback(interrupted, "loop"), "invoke infinite loop callback")
        support.assertClosed(support.closeState(interrupted), "close interrupted")

    }

    @Test
    fun `spawned_task_audio_operation_cancellation_discards_coroutine`() {
        val state = support.createState(
            LuaKernelConfig(
                hookInterval = 100,
                instructionBudget = 50_000,
                maxConcurrentTasks = 2,
                maxTimerSlots = 1,
            ),
        )
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local runtime = require("talkcan.runtime")
                    local s = require("talkcan.synthesis")
                    return {
                      startup = function()
                        runtime.spawn(function()
                          local syn, err = s.synthesize({text="hello", language="en-US", voice="v"})
                          if err then return "synthesis-failed" end
                          return "completed:" .. tostring(syn)
                        end)
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val startup = support.assertCompleted(
            support.invokeCallback(state, "startup", LuaValue.Nil, accepting),
            "startup",
        )
        val taskId = (startup.spawnedCoroutines ?: throw AssertionError("startup spawned nothing: $startup"))
            .firstOrNull() ?: throw AssertionError("startup spawned nothing: $startup")

        // Start the spawned task: it calls synthesis.synthesize and yields.
        val yielded = assertYielded(
            bridge.startCoroutine(state, LuaCoroutineId(taskId), accepting),
            "startCoroutine",
        )
        val op = operation(state, yielded)

        // Cancel the spawned task while its audio operation is pending.
        assertCancelled(bridge.cancel(op), "cancel task audio operation")

        // Late resume echoes the Cancelled terminal without re-entering Lua.
        val late = bridge.resume(op, true, "synthesized:late-after-cancel", accepting)
        assertCancelled(late, "late resume echoes terminal")

        support.assertClosed(support.closeState(state), "close")
    }

    // ------------------------------------------------------------------
    // work_conformance.rs
    // ------------------------------------------------------------------

    @Test
    fun `work_instruction_budget_exhaustion`() {
        val state = support.createState(
            LuaKernelConfig(
                hookInterval = 1000,
                instructionBudget = 5_000,
                maxConcurrentTasks = 8,
                maxTimerSlots = 8,
            ),
        )
        support.installResourceContext(state, """{"workQueue":true,"workQueues":["turns"]}""")
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
                          job:effect("k", function()
                            local x = 0
                            while true do x = x + 1 end
                          end)
                          job:complete()
                        end)
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val startup = support.assertCompleted(
            support.invokeCallback(state, "startup", LuaValue.Nil, accepting),
            "startup",
        )
        val taskId = (startup.spawnedCoroutines ?: throw AssertionError("startup spawned nothing: $startup"))
            .firstOrNull() ?: throw AssertionError("startup spawned nothing: $startup")
        val started = assertYielded(
            bridge.startCoroutine(state, LuaCoroutineId(taskId), accepting),
            "startCoroutine",
        )

        // receive -> begin_effect -> (effect spins, budget error caught by pcall,
        // committed) -> commit_effect -> complete. The infinite effect body is
        // interrupted inside its pcall; the work flow still drives to a terminal
        // Completed, proving budget exhaustion never wedges or strands the state.
        val receiveJson = JSONObject()
            .put("jobId", "j1")
            .put("payloadJson", JSONObject().put("t", "null").toString())
            .toString()
        val afterReceive = assertYielded(
            claimAndResume(state, started, HostOperationKind.WORK_RECEIVE, receiveJson),
            "after WORK_RECEIVE",
        )
        val afterBegin = assertYielded(
            claimAndResume(
                state,
                afterReceive,
                HostOperationKind.WORK_BEGIN_EFFECT,
                JSONObject().put("replay", false).toString(),
            ),
            "after WORK_BEGIN_EFFECT",
        )
        val afterCommit = assertYielded(
            claimAndResume(
                state,
                afterBegin,
                HostOperationKind.WORK_COMMIT_EFFECT,
                JSONObject().put("ok", true).toString(),
            ),
            "after WORK_COMMIT_EFFECT",
        )
        val finalOutcome = claimAndResume(
            state,
            afterCommit,
            HostOperationKind.WORK_COMPLETE,
            JSONObject().put("ok", true).toString(),
        )
        support.assertCompleted(finalOutcome, "work task terminal")

        // Closable after budget-contained completion.
        support.assertClosed(support.closeState(state), "close")
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Claim the yielded request, assert its typed kind, and resume the owner. */
    private fun claimAndResume(
        handle: LuaStateHandle,
        yielded: LuaKernelOutcome.Yielded,
        expectedKind: HostOperationKind,
        resumeJson: String,
    ): LuaKernelOutcome {
        val requestId = (yielded.value ?: throw AssertionError("missing request id: $yielded")).toLong()
        val claim = bridge.claimHostOperation(handle, requestId)
        val admitted = claim as? HostOperationClaim.Admitted
            ?: throw AssertionError("claim expected Admitted($expectedKind) but was $claim")
        assertEquals("claimed host operation kind", expectedKind, admitted.kind)
        return bridge.resume(operation(handle, yielded), true, resumeJson, accepting)
    }

    private fun loadSosImage(handle: LuaStateHandle, body: String) {
        support.loadProgramImageOk(
            handle,
            "entry",
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
            ),
        )
    }

    private fun operation(handle: LuaStateHandle, yielded: LuaKernelOutcome.Yielded): LuaOperationHandle =
        LuaOperationHandle(
            stateHandle = handle,
            coroutineId = LuaCoroutineId(yielded.coroutineId),
            operationId = LuaOperationId(yielded.operationId),
        )

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

    /**
     * Run two bridge calls concurrently behind a release latch (mirrors the
     * established `LuaActorInstrumentationTest.race` convention) and return both
     * outcomes. Bounded by [RACE_TIMEOUT_SECONDS] so a wedge fails, never hangs.
     */
    private fun race(
        first: Callable<LuaKernelOutcome>,
        second: Callable<LuaKernelOutcome>,
    ): Pair<LuaKernelOutcome, LuaKernelOutcome> {
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            fun submit(call: Callable<LuaKernelOutcome>) = executor.submit(
                Callable {
                    ready.countDown()
                    check(release.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        "race caller did not receive release signal"
                    }
                    call.call()
                },
            )
            val firstFuture = submit(first)
            val secondFuture = submit(second)
            check(ready.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "race callers did not become ready" }
            release.countDown()
            return firstFuture.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS) to
                secondFuture.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun assertYielded(outcome: LuaKernelOutcome, context: String): LuaKernelOutcome.Yielded =
        outcome as? LuaKernelOutcome.Yielded
            ?: throw AssertionError("$context: expected Yielded but was $outcome")

    private fun assertInterrupted(outcome: LuaKernelOutcome, context: String): LuaKernelOutcome.Interrupted =
        outcome as? LuaKernelOutcome.Interrupted
            ?: throw AssertionError("$context: expected Interrupted but was $outcome")

    private fun assertCancelled(outcome: LuaKernelOutcome, context: String): LuaKernelOutcome.Cancelled =
        outcome as? LuaKernelOutcome.Cancelled
            ?: throw AssertionError("$context: expected Cancelled but was $outcome")


    private fun assertRejectedCompletion(outcome: LuaKernelOutcome, context: String) {
        if (
            outcome !is LuaKernelOutcome.RuntimeFailure &&
            outcome !is LuaKernelOutcome.Interrupted &&
            outcome !is LuaKernelOutcome.InvalidOwnership &&
            outcome !is LuaKernelOutcome.Stale
        ) {
            throw AssertionError(
                "$context: expected terminal rejection but was $outcome",
            )
        }
    }

    private fun kindTag(outcome: LuaKernelOutcome): String = when (outcome) {
        is LuaKernelOutcome.Created -> "created"
        is LuaKernelOutcome.Completed -> "completed"
        is LuaKernelOutcome.Yielded -> "yielded"
        is LuaKernelOutcome.SyntaxFailure -> "syntax_failure"
        is LuaKernelOutcome.ValidationFailure -> "validation_failure"
        is LuaKernelOutcome.RuntimeFailure -> "runtime_failure"
        is LuaKernelOutcome.Interrupted -> "interrupted"
        is LuaKernelOutcome.Cancelled -> "cancelled"
        is LuaKernelOutcome.InvalidOwnership -> "invalid_ownership"
        is LuaKernelOutcome.Stale -> "stale"
        is LuaKernelOutcome.Snapshot -> "snapshot"
        is LuaKernelOutcome.Closed -> "closed"
    }

    private companion object {
        const val RACE_TIMEOUT_SECONDS = 10L

        val YIELDING_COUNTER = """
            hits = 0
            function main()
              local success, value = talkcan.yield_operation("external-work")
              hits = hits + 1
              if success then return "success:" .. value end
              return "failure:" .. value
            end
            function observe() return tostring(hits) end
        """.trimIndent()

        val DOUBLE_YIELDING_COUNTER = """
            hits = 0
            function main()
              local success1, value1 = talkcan.yield_operation("first-work")
              hits = hits + 1
              local success2, value2 = talkcan.yield_operation("second-work")
              hits = hits + 1
              if success1 and success2 then return "success:" .. value1 .. ":" .. value2 end
              return "failure:" .. value1 .. ":" .. value2
            end
            function observe() return tostring(hits) end
        """.trimIndent()
    }
}
