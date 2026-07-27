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
import io.talkcan.lua.LuaStateGeneration
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaStateId
import io.talkcan.lua.LuaValue
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM conformance port of every category-R (request-registry/keyboard/ownership)
 * Rust test from the `replace-rust-lua-kernel` inventory. Each [Test] method maps
 * one-for-one to a Rust `#[test]` fn, preserving the exact name and table-driven
 * variants. All tests drive [io.talkcan.lua.LuaKernelBridge] through
 * [KotlinLuaKernelConformanceSupport] and assert observable claim kinds/payloads
 * and ownership transitions — never source text or implementation internals.
 *
 * The four Rust tests gated behind `#[cfg(feature = "registry-test")]` exercise a
 * test-only global registry that has no bridge surface; those cases are ported to
 * their public bridge-observable equivalents (stale/unknown close rejection,
 * idempotent tombstone terminals, cross-state independence, bounded-history
 * retention of fresh state ids) without any test-only production hook.
 */
class KotlinLuaKernelRequestOwnershipConformanceTest {

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
    // Shared helpers (file-local; the support harness owns every state)
    // ==================================================================

    /** Spawn admission that accepts every spawned coroutine (mirrors Rust AcceptAdmitter). */
    private val accepting: LuaSpawnAdmission = object : LuaSpawnAdmission {
        override fun admitTask(coroutineId: Long): Int = 0
    }

    /** Build the owner-bearing operation handle carried by a yielded outcome. */
    private fun op(handle: LuaStateHandle, yielded: LuaKernelOutcome.Yielded): LuaOperationHandle =
        LuaOperationHandle(
            stateHandle = handle,
            coroutineId = LuaCoroutineId(yielded.coroutineId),
            operationId = LuaOperationId(yielded.operationId),
        )

    /** The opaque request identity a yield exposes — a bare integer label, nothing more. */
    private fun requestId(yielded: LuaKernelOutcome.Yielded): Long {
        val label = yielded.value
            ?: throw AssertionError("yielded outcome carries no opaque request identity: $yielded")
        return label.toLongOrNull()
            ?: throw AssertionError("yielded label is not a bare opaque request identity: $label")
    }

    /** Claim a yielded request and require an admitted, typed payload. */
    private fun admitClaim(
        handle: LuaStateHandle,
        requestId: Long,
        context: String = "",
    ): HostOperationClaim.Admitted {
        val claim = s.bridge.claimHostOperation(handle, requestId)
        if (claim !is HostOperationClaim.Admitted) {
            throw AssertionError("${context.ifEmpty { "claim" }}: expected Admitted but was $claim")
        }
        return claim
    }

    private fun assertYielded(outcome: LuaKernelOutcome, context: String = ""): LuaKernelOutcome.Yielded {
        if (outcome !is LuaKernelOutcome.Yielded) {
            throw AssertionError("${context.ifEmpty { "outcome" }}: expected Yielded but was $outcome")
        }
        return outcome
    }

    private fun assertCancelled(outcome: LuaKernelOutcome, context: String = ""): LuaKernelOutcome.Cancelled {
        if (outcome !is LuaKernelOutcome.Cancelled) {
            throw AssertionError("${context.ifEmpty { "outcome" }}: expected Cancelled but was $outcome")
        }
        return outcome
    }

    private fun assertStale(outcome: LuaKernelOutcome, context: String = ""): LuaKernelOutcome.Stale {
        if (outcome !is LuaKernelOutcome.Stale) {
            throw AssertionError("${context.ifEmpty { "outcome" }}: expected Stale but was $outcome")
        }
        return outcome
    }

    private fun assertInvalidOwnership(outcome: LuaKernelOutcome, context: String = ""): LuaKernelOutcome.InvalidOwnership {
        if (outcome !is LuaKernelOutcome.InvalidOwnership) {
            throw AssertionError("${context.ifEmpty { "outcome" }}: expected InvalidOwnership but was $outcome")
        }
        return outcome
    }

    /** Invoke handle_input with a captured-audio event (owner-eligible execution context). */
    private fun invokeInput(
        handle: LuaStateHandle,
        arguments: LuaValue = captureEvent(),
        token: String = "tok",
    ): LuaKernelOutcome = s.bridge.invokeInputCallback(
        handle,
        LuaCallbackHandle(handle, "handle_input"),
        arguments,
        token,
        accepting,
    )

    /** Invoke handle_sos with a reason event (bounded SOS execution owner). */
    private fun invokeSos(handle: LuaStateHandle): LuaKernelOutcome = s.bridge.invokeSosCallback(
        handle,
        LuaCallbackHandle(handle, "handle_sos"),
        LuaValue.Map(mapOf("reason" to LuaValue.StringValue("test"))),
        accepting,
    )

    /** A captured-audio input event; [extra] layers additional top-level fields. */
    private fun captureEvent(
        pcmBytes: Int = 32,
        durationMs: Int = 1,
        extra: Map<String, LuaValue> = emptyMap(),
    ): LuaValue = LuaValue.Map(
        extra + mapOf(
            "metadata" to LuaValue.Map(
                mapOf(
                    "duration_ms" to LuaValue.Integer(durationMs.toLong()),
                    "sample_rate" to LuaValue.Integer(16000L),
                    "channels" to LuaValue.Integer(1L),
                    "pcm_bytes" to LuaValue.Integer(pcmBytes.toLong()),
                ),
            ),
        ),
    )

    /** Load a program image whose single `entry` module is [source], then invoke handle_input. */
    private fun driveInput(handle: LuaStateHandle, source: String, token: String = "tok"): LuaKernelOutcome {
        s.loadProgramImageOk(handle, "entry", mapOf("entry" to source))
        return invokeInput(handle, token = token)
    }

    /** Drive one yielded fs operation: claim it, assert the kind, resume with [resumeJson]. */
    private fun driveFs(
        handle: LuaStateHandle,
        yielded: LuaKernelOutcome.Yielded,
        expectedKind: HostOperationKind,
        resumeJson: String,
    ): LuaKernelOutcome {
        val claim = admitClaim(handle, requestId(yielded), "fs claim")
        assertEquals("fs host operation kind", expectedKind, claim.kind)
        return s.bridge.resume(op(handle, yielded), true, resumeJson, accepting)
    }

    /** Create a state declaring keyboard-output eligibility. */
    private fun keyboardState(): LuaStateHandle {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, """{"keyboardOutput":true}""")
        return handle
    }

    /** Load a program image + invoke handle_input, requiring a yielded keyboard request. */
    private fun invokeInputYielded(handle: LuaStateHandle, source: String): LuaKernelOutcome.Yielded {
        s.loadProgramImageOk(handle, "entry", mapOf("entry" to source))
        return assertYielded(invokeInput(handle), "keyboard input yield")
    }

    /** Load an SOS program image wrapping [body] as the handle_sos body. */
    private fun loadSosImage(handle: LuaStateHandle, body: String) {
        val source = """
            local kb = require("talkcan.keyboard_output")
            return {
              startup = function() end,
              handle_sos = function(event)
                $body
              end,
            }
        """.trimIndent()
        s.loadProgramImageOk(handle, "entry", mapOf("entry" to source))
    }

    /** Create a state with one declared resource capability and install profile grants. */
    private fun secretHttpState(resourceContextJson: String): LuaStateHandle {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(handle, resourceContextJson)
        return handle
    }

    private fun installGrants(handle: LuaStateHandle, grantsJson: String) {
        s.assertCompleted(s.bridge.setProfileGrants(handle, grantsJson), "setProfileGrants")
    }

    /** runtime_v1_conformance.rs engine(4, 4). */
    private fun runtimeState(): LuaStateHandle = s.createState(s.runtimeV1Config())

    /** runtime_v1 audio fixtures use engine(2, 1). */
    private fun audioState(): LuaStateHandle = s.createState(
        LuaKernelConfig(
            hookInterval = 100,
            instructionBudget = 50_000,
            maxConcurrentTasks = 2,
            maxTimerSlots = 1,
        ),
    )

    // Legacy single-source fixture (conformance.rs YIELDING_COUNTER).
    private val yieldingCounter = """
        hits = 0
        function main()
          local success, value = talkcan.yield_operation("external-work")
          hits = hits + 1
          if success then return "success:" .. value end
          return "failure:" .. value
        end
        function observe() return tostring(hits) end
    """.trimIndent()

    /** Load [source] as `main` and start, requiring a yielded operation. */
    private fun yieldedOperation(handle: LuaStateHandle, source: String = yieldingCounter): LuaKernelOutcome.Yielded {
        s.loadSourceOk(handle, source, "main")
        return assertYielded(s.startEntry(handle), "yielded operation")
    }

    // ==================================================================
    // conformance.rs — request identity, exactly-once terminals, registry
    // ==================================================================

    @Test
    fun `foreign_unknown_stale_and_closed_handles_cannot_resume_lua`() {
        val owner = s.createState()
        val other = s.createState()
        val ownerYielded = yieldedOperation(owner)
        val otherYielded = yieldedOperation(other)
        assertNotEquals(
            "operation identifiers must remain opaque and unambiguous across state ownership domains",
            ownerYielded.operationId,
            otherYielded.operationId,
        )

        // Foreign: another state's handle cannot resume this state's operation.
        assertInvalidOwnership(
            s.bridge.resume(op(other, ownerYielded), true, "should-not-run"),
            "foreign resume",
        )
        // Unknown: this state's handle cannot resume another state's operation.
        assertInvalidOwnership(
            s.bridge.resume(op(owner, otherYielded), true, "should-not-run"),
            "own-state unknown resume",
        )

        // Each rightful owner completes its own operation exactly once.
        val otherCompleted = s.bridge.resume(op(other, otherYielded), true, "other")
        assertEquals("success:other", s.resultScalar(s.assertCompleted(otherCompleted, "other resume")))
        val completed = s.bridge.resume(op(owner, ownerYielded), true, "accepted")
        assertEquals("success:accepted", s.resultScalar(s.assertCompleted(completed, "owner resume")))

        // A duplicate resume echoes the terminal without re-entering Lua (hits stays 1).
        val duplicate = s.bridge.resume(op(owner, ownerYielded), true, "must-not-run")
        s.assertCompleted(duplicate, "duplicate resume echoes terminal")
        s.loadSourceOk(owner, "function observe() return tostring(hits) end", "observe")
        assertEquals("1", s.resultScalar(s.startEntryOk(owner)))

        // After close, a late completion is rejected and close is idempotent.
        s.assertClosed(s.bridge.close(owner), "owner close")
        s.assertClosedOrStale(s.bridge.resume(op(owner, ownerYielded), true, "late"), "late resume after close")
        s.assertClosed(s.bridge.close(owner), "idempotent owner close")
        s.assertClosed(s.bridge.close(other), "other close")
    }

    @Test
    fun `success_failure_and_cancel_resumption_have_exactly_once_effects`() {
        val successState = s.createState()
        val successYielded = yieldedOperation(successState)
        val success = s.bridge.resume(op(successState, successYielded), true, "payload")
        assertEquals("success:payload", s.resultScalar(s.assertCompleted(success, "success resume")))
        s.assertClosed(s.closeState(successState), "success close")

        val failureState = s.createState()
        val failureYielded = yieldedOperation(failureState)
        val failure = s.bridge.resume(op(failureState, failureYielded), false, "denied")
        assertEquals("failure:denied", s.resultScalar(s.assertCompleted(failure, "failure resume")))
        s.assertClosed(s.closeState(failureState), "failure close")

        val cancelledState = s.createState()
        val cancelledYielded = yieldedOperation(cancelledState)
        val cancelHandle = op(cancelledState, cancelledYielded)
        assertCancelled(s.bridge.cancel(cancelHandle), "cancel")
        assertCancelled(s.bridge.cancel(cancelHandle), "duplicate cancel echoes terminal")
        s.loadSourceOk(cancelledState, "function observe() return tostring(hits) end", "observe")
        assertEquals("0", s.resultScalar(s.startEntryOk(cancelledState)))
        s.assertClosed(s.closeState(cancelledState), "cancelled close")
    }

    @Test
    fun `yielding_host_boundary_returns_before_external_completion`() {
        val handle = s.createState()
        s.loadSourceOk(handle, yieldingCounter, "main")
        val yielded = assertYielded(s.startEntry(handle), "start yields before external completion")
        val resumed = s.bridge.resume(op(handle, yielded), true, "completed-later")
        assertEquals("success:completed-later", s.resultScalar(s.assertCompleted(resumed, "resume")))
        s.assertClosed(s.closeState(handle), "close")
    }

    @Test
    fun `concurrent_callers_serialize_without_double_execution`() {
        val handle = s.createState()
        s.loadSourceOk(
            handle,
            "hits = 0 function main() hits = hits + 1 return tostring(hits) end",
            "main",
        )
        val gate = CyclicBarrier(3)
        var first: LuaKernelOutcome? = null
        var second: LuaKernelOutcome? = null
        val firstThread = thread { gate.await(); first = s.bridge.start(handle) }
        val secondThread = thread { gate.await(); second = s.bridge.start(handle) }
        gate.await()
        firstThread.join()
        secondThread.join()

        val outcomes = listOf(first!!, second!!)
        assertEquals(
            "concurrent callers entered one Lua state more than once: $outcomes",
            1,
            outcomes.count { it is LuaKernelOutcome.Completed },
        )
        assertEquals(
            "serialized second start was not rejected after terminal completion: $outcomes",
            1,
            outcomes.count { it is LuaKernelOutcome.ValidationFailure },
        )
        val completed = outcomes.filterIsInstance<LuaKernelOutcome.Completed>().single()
        assertEquals("1", s.resultScalar(completed))
        s.assertClosed(s.closeState(handle), "close")
    }

    @Test
    fun `stale_close_does_not_tombstone_live_engine`() {
        val handle = s.createState()
        val staleHandle = LuaStateHandle(handle.stateId, LuaStateGeneration(handle.generation.value + 1))

        // A close with a stale generation must not tombstone the live engine.
        assertStale(s.bridge.close(staleHandle), "stale-generation close")

        // The live engine is still usable.
        s.loadSourceOk(handle, "function probe() return 'alive' end", "probe")
        assertEquals("alive", s.resultScalar(s.startEntryOk(handle)))

        // A rightful close succeeds; a later close is idempotent and a stale
        // close on the tombstone still reports the closed terminal.
        s.assertClosed(s.bridge.close(handle), "rightful close")
        s.assertClosed(s.bridge.close(handle), "idempotent re-close")
        s.assertClosed(s.bridge.close(staleHandle), "stale close on tombstone")
    }

    @Test
    fun `unknown_close_does_not_create_or_mutate_registry_entry`() {
        // A close with an unknown state id must be rejected and create nothing.
        val unknownHandle = LuaStateHandle(LuaStateId(999_999_999L), LuaStateGeneration(1))
        assertInvalidOwnership(s.bridge.close(unknownHandle), "unknown-state close")

        // A totally separate state still registers and dispatches fine: the
        // unknown id does not poison the bridge for fresh states.
        val fresh = s.createState()
        s.loadSourceOk(fresh, "function main() return 'fresh' end", "main")
        assertEquals("fresh", s.resultScalar(s.startEntryOk(fresh)))
        s.assertClosed(s.closeState(fresh), "fresh close")
    }

    @Test
    fun `cross_state_dispatch_not_serialized_by_global_registry`() {
        // Two independent states execute concurrently: a quick state completes
        // while a long state runs to its instruction-budget interrupt. The
        // observable contract is that one state's long execution does not block
        // or corrupt another state's independent dispatch.
        val longHandle = s.createState(
            LuaKernelConfig(
                hookInterval = 100,
                instructionBudget = 5_000,
                maxConcurrentTasks = 16,
                maxTimerSlots = 16,
            ),
        )
        val quickHandle = s.createState()
        s.loadSourceOk(longHandle, "function main() while true do end end", "main")
        s.loadSourceOk(quickHandle, "function main() return 'quick' end", "main")

        val gate = CyclicBarrier(2)
        var longOutcome: LuaKernelOutcome? = null
        val longThread = thread { gate.await(); longOutcome = s.bridge.start(longHandle) }
        gate.await()

        val quickOutcome = s.bridge.start(quickHandle)
        assertEquals("quick", s.resultScalar(s.assertCompleted(quickOutcome, "quick start")))

        longThread.join(30_000)
        assertTrue(
            "long state must be interrupted by its instruction budget but was $longOutcome",
            longOutcome is LuaKernelOutcome.Interrupted,
        )
        s.assertClosed(s.closeState(longHandle), "long close")
        s.assertClosed(s.closeState(quickHandle), "quick close")
    }

    @Test
    fun `bounded_tombstone_history_evicts_oldest_and_allows_new_ids`() {
        // Registry tombstone eviction is an internal detail of the Rust
        // test-only global registry. The bridge-observable contract is that
        // closing a bounded history of states keeps typed closed/stale
        // terminals for recent closes and never blocks fresh state ids.
        val bound = 16
        val closed = ArrayList<LuaStateHandle>()
        for (index in 0..bound) {
            val handle = s.createState()
            s.assertClosed(s.bridge.close(handle), "close #$index")
            closed += handle
        }

        // The newest tombstone preserves typed terminal semantics.
        val newest = closed.last()
        s.assertClosed(s.bridge.close(newest), "newest tombstone re-close")
        s.assertClosedOrStale(
            s.bridge.close(LuaStateHandle(newest.stateId, LuaStateGeneration(newest.generation.value + 1))),
            "stale generation on newest tombstone",
        )

        // A fresh state id registers and runs: bounded history does not block new ids.
        val fresh = s.createState()
        s.loadSourceOk(fresh, "function main() return 'fresh' end", "main")
        assertEquals("fresh", s.resultScalar(s.startEntryOk(fresh)))
        s.assertClosed(s.closeState(fresh), "fresh close")
    }

    @Test
    fun `terminal_operation_outcomes_are_bounded_and_evictions_remain_typed`() {
        val handle = s.createState()
        // Contract: the terminal-outcome cache retains a bounded window of
        // recent operations (TERMINAL_OPERATION_CACHE_CAPACITY) and a bounded
        // id-only tombstone for just-evicted owned operations.
        val capacity = 64
        val operationCount = capacity + 1
        val source = """
            hits = 0
            function main()
              for i = 1, $operationCount do
                local success, value = talkcan.yield_operation("bounded-" .. i)
                hits = hits + 1
              end
              return tostring(hits)
            end
        """.trimIndent()
        s.loadSourceOk(handle, source, "main")

        val first = assertYielded(s.startEntry(handle), "first bounded yield")
        var current = first
        var recent = first
        var finalOutcome: LuaKernelOutcome.Completed? = null
        for (index in 0 until operationCount) {
            val outcome = s.bridge.resume(op(handle, current), true, "completed")
            if (index + 1 == operationCount) {
                finalOutcome = s.assertCompleted(outcome, "final resume completes")
                recent = current
            } else {
                current = assertYielded(outcome, "resume #$index yields the next operation")
            }
        }
        assertEquals(
            "all real resumes must execute exactly once",
            operationCount.toString(),
            s.resultScalar(finalOutcome!!),
        )

        // A recent duplicate is an exact typed echo and does not re-enter Lua.
        val recentDuplicate = s.bridge.resume(op(handle, recent), true, "must-not-reenter")
        assertEquals(
            "recent duplicate did not echo its exact terminal outcome",
            s.resultScalar(finalOutcome),
            s.resultScalar(s.assertCompleted(recentDuplicate, "recent duplicate")),
        )

        // The first operation aged out: a typed Stale (never InvalidOwnership
        // and never a second Lua entry) that explains the eviction.
        val evicted = s.bridge.resume(op(handle, first), true, "old-retry")
        val stale = assertStale(evicted, "evicted duplicate")
        assertTrue(
            "evicted duplicate did not explain typed stale retention: ${stale.diagnostic}",
            stale.diagnostic.contains("evicted"),
        )

        // Both the exercised state and an independent peer remain usable.
        s.loadSourceOk(handle, "function healthy() return 'state-usable' end", "healthy")
        assertEquals("state-usable", s.resultScalar(s.startEntryOk(handle)))
        val peer = s.createState()
        s.loadSourceOk(peer, "function main() return 'peer-usable' end", "main")
        assertEquals("peer-usable", s.resultScalar(s.startEntryOk(peer)))
        s.assertClosed(s.closeState(handle), "state close")
        s.assertClosed(s.closeState(peer), "peer close")
    }

    // ==================================================================
    // fs_conformance.rs — filesystem ownership through handle_input
    // ==================================================================

    @Test
    fun `fs_all_operations_through_handle_input`() {
        val handle = s.createState(s.channelConfig())
        s.installResourceContext(
            handle,
            """{"storageFiles":true,"mounts":{"data":{"access":"read-write","status":"available"}}}""",
        )
        s.loadProgramImageOk(handle, "entry", mapOf("entry" to FS_TOUR_SOURCE))

        val input = assertYielded(invokeInput(handle), "first fs yield")
        val afterMkdir = driveFs(handle, input, HostOperationKind.FS_MKDIR, """{"status":"created"}""")
        val afterWrite = driveFs(
            handle,
            assertYielded(afterMkdir, "mkdir yields write"),
            HostOperationKind.FS_WRITE_TEXT,
            """{"status":"written","bytes":5}""",
        )
        val afterStat = driveFs(
            handle,
            assertYielded(afterWrite, "write yields stat"),
            HostOperationKind.FS_STAT,
            """{"kind":"file","size":5}""",
        )
        val afterRead = driveFs(
            handle,
            assertYielded(afterStat, "stat yields read"),
            HostOperationKind.FS_READ_TEXT,
            """{"text":"hello","bytes":5}""",
        )
        val afterList = driveFs(
            handle,
            assertYielded(afterRead, "read yields list"),
            HostOperationKind.FS_LIST,
            """{"entries":[{"name":"f.txt","kind":"file"}]}""",
        )
        val afterRemove = driveFs(
            handle,
            assertYielded(afterList, "list yields remove"),
            HostOperationKind.FS_REMOVE,
            """{"status":"removed"}""",
        )
        assertTrue(s.resultObject(s.assertCompleted(afterRemove, "remove completes")).getBoolean("ok"))
    }

    @Test
    fun `fs_mount_handle_invalidated_after_resource_context_replaced`() {
        val handle = s.createState(s.channelConfig())
        val resourceContext =
            """{"storageFiles":true,"mounts":{"data":{"access":"read-write","status":"available"}}}"""
        s.installResourceContext(handle, resourceContext)
        s.loadProgramImageOk(handle, "entry", mapOf("entry" to FS_HOLDER_SOURCE))

        // First input mints a live mount handle into the module-local holder.
        val fresh = invokeInput(handle, captureEvent(extra = mapOf("fresh" to LuaValue.Bool(true))))
        assertTrue(s.resultObject(s.assertCompleted(fresh, "fresh mount")).getBoolean("ok"))

        // Replacing the resource context clears the mount registry, so the
        // stored handle is stale; a subsequent I/O call fails E_STALE before effect.
        s.installResourceContext(handle, resourceContext)
        val stale = invokeInput(handle)
        val error = s.resultObject(s.assertCompleted(stale, "stale mount io")).getJSONObject("error")
        assertEquals("GOT", error.getString("code"))
        assertEquals("E_STALE", error.getString("detail"))
    }

    // ==================================================================
    // keyboard_conformance.rs — typed keyboard broker + SOS owner
    // ==================================================================

    @Test
    fun `send_text_yields_only_opaque_identity_and_claims_typed_payload`() {
        val handle = keyboardState()
        val yielded = invokeInputYielded(handle, KB_SEND_TEXT_SOURCE)

        // The yielded label is exactly the opaque request identity — no payload leaks.
        val label = yielded.value ?: throw AssertionError("yielded keyboard request carries no label")
        assertNotNull("label must be the bare opaque request identity", label.toLongOrNull())
        assertFalse("label leaks content: $label", label.contains("secret"))
        assertFalse("label leaks profile: $label", label.contains("linux"))

        // The typed claim is the only way to obtain the payload.
        val request = label.toLong()
        val claim = admitClaim(handle, request, "send_text claim")
        assertEquals(HostOperationKind.KEYBOARD_SEND_TEXT, claim.kind)
        assertEquals("secret hello", claim.text)
        assertEquals("linux:us", claim.profile)
        assertEquals(request, claim.requestId)

        val completed = s.bridge.resume(op(handle, yielded), true, """{"status":"delivered"}""", accepting)
        assertTrue(s.resultObject(s.assertCompleted(completed, "delivered")).getBoolean("ok"))
    }

    @Test
    fun `send_key_yields_and_claims_semantic_enter`() {
        val handle = keyboardState()
        val yielded = invokeInputYielded(handle, KB_SEND_KEY_ENTER_SOURCE)
        val claim = admitClaim(handle, requestId(yielded), "send_key claim")
        assertEquals(HostOperationKind.KEYBOARD_SEND_KEY, claim.kind)
        assertEquals("enter", claim.key)
        assertEquals("mac:iso", claim.profile)
        assertNull("send_key claim carries no text payload", claim.text)
        val completed = s.bridge.resume(op(handle, yielded), true, """{"status":"delivered"}""", accepting)
        s.assertCompleted(completed, "delivered")
    }

    @Test
    fun `semantic_non_delivered_outcomes_reach_lua_as_result_tables`() {
        val cases = listOf(
            "rejected" to "E_POLICY",
            "failed" to "E_TRANSPORT",
            "indeterminate" to "E_TIMEOUT",
        )
        for ((status, reason) in cases) {
            val handle = keyboardState()
            val yielded = invokeInputYielded(handle, KB_SEND_KEY_ESCAPE_SOURCE)
            admitClaim(handle, requestId(yielded), "claim for $status")
            val resumeJson = JSONObject().put("status", status).put("reason", reason).toString()
            val completed = s.bridge.resume(op(handle, yielded), true, resumeJson, accepting)
            val result = s.resultObject(s.assertCompleted(completed, "resume $status"))
            assertEquals("status $status", status, result.getString("status"))
            assertEquals("reason for $status", reason, result.getString("reason"))
        }
    }

    @Test
    fun `duplicate_unknown_and_foreign_claims_rejected`() {
        val handle = keyboardState()
        val yielded = invokeInputYielded(handle, KB_SEND_TEXT_SIMPLE_SOURCE)
        val request = requestId(yielded)

        // Unknown request identity.
        assertTrue(
            "unknown claim must be rejected",
            s.bridge.claimHostOperation(handle, request + 999) is HostOperationClaim.Rejected,
        )
        // First claim admits exactly once.
        admitClaim(handle, request, "first claim")
        // Duplicate claim never re-enters Lua or re-admits.
        assertTrue(
            "duplicate claim must be rejected",
            s.bridge.claimHostOperation(handle, request) is HostOperationClaim.Rejected,
        )
        // Foreign state: the same identity is unknown to another engine.
        val other = keyboardState()
        assertTrue(
            "foreign claim must be rejected",
            s.bridge.claimHostOperation(other, request) is HostOperationClaim.Rejected,
        )
        // Completion consumes the request: a late claim after resume is rejected.
        val completed = s.bridge.resume(op(handle, yielded), true, """{"status":"delivered"}""", accepting)
        s.assertCompleted(completed, "delivered")
        assertTrue(
            "late claim after resume must be rejected",
            s.bridge.claimHostOperation(handle, request) is HostOperationClaim.Rejected,
        )
    }

    @Test
    fun `duplicate_resume_echoes_exact_terminal`() {
        val handle = keyboardState()
        val yielded = invokeInputYielded(handle, KB_SEND_TEXT_SIMPLE_SOURCE)
        admitClaim(handle, requestId(yielded), "claim")
        val opHandle = op(handle, yielded)
        val first = s.assertCompleted(
            s.bridge.resume(opHandle, true, """{"status":"delivered"}""", accepting),
            "first resume",
        )
        val second = s.assertCompleted(
            s.bridge.resume(opHandle, true, """{"status":"delivered"}""", accepting),
            "second resume",
        )
        assertEquals("duplicate resume must echo the exact terminal value", first.value, second.value)
    }

    @Test
    fun `sos_no_yield_completes_in_one_slice_like_synchronous_callbacks`() {
        // A table terminal completes in one slice.
        val tableState = keyboardState()
        loadSosImage(tableState, "return { ok = true }")
        assertTrue(s.resultObject(s.assertCompleted(invokeSos(tableState), "table sos")).getBoolean("ok"))

        // Nil remains the valid no-op terminal.
        val nilState = keyboardState()
        loadSosImage(nilState, "return nil")
        assertNull("nil SOS terminal carries no value", s.assertCompleted(invokeSos(nilState), "nil sos").value)

        // Application failure shape passes through unchanged.
        val failureState = keyboardState()
        loadSosImage(
            failureState,
            """return { error = { code = "SOS_BUSY", detail = "transport unavailable" } }""",
        )
        val error = s.resultObject(s.assertCompleted(invokeSos(failureState), "failure sos")).getJSONObject("error")
        assertEquals("SOS_BUSY", error.getString("code"))
        assertEquals("transport unavailable", error.getString("detail"))
    }

    @Test
    fun `sos_yields_keyboard_operation_through_typed_broker`() {
        val handle = keyboardState()
        loadSosImage(
            handle,
            """
            local result, err = kb.send_key({ key = "enter", profile = "sos-profile" })
            if not result then return { error = { code = "SEND", detail = err.error } } end
            if result.status ~= "delivered" then
              return { error = { code = "STATUS", detail = result.status } }
            end
            return { ok = true }
            """.trimIndent(),
        )
        val yielded = assertYielded(invokeSos(handle), "SOS keyboard yield")
        val label = yielded.value ?: throw AssertionError("SOS yield carries no label")
        assertNotNull("SOS yield must be opaque: $label", label.toLongOrNull())
        assertFalse("SOS label leaks profile: $label", label.contains("sos-profile"))

        val claim = admitClaim(handle, label.toLong(), "SOS claim")
        assertEquals(HostOperationKind.KEYBOARD_SEND_KEY, claim.kind)
        assertEquals("enter", claim.key)
        assertEquals("sos-profile", claim.profile)

        val completed = s.bridge.resume(op(handle, yielded), true, """{"status":"delivered"}""", accepting)
        assertTrue(s.resultObject(s.assertCompleted(completed, "SOS delivered")).getBoolean("ok"))
    }

    @Test
    fun `sos_chains_multiple_keyboard_operations`() {
        val handle = keyboardState()
        loadSosImage(
            handle,
            """
            local r1, e1 = kb.send_text({ text = "sos-text", profile = "p" })
            if not r1 then return { error = { code = "FIRST", detail = e1.error } } end
            local r2, e2 = kb.send_key({ key = "enter", profile = "p" })
            if not r2 then return { error = { code = "SECOND", detail = e2.error } } end
            return { ok = true }
            """.trimIndent(),
        )
        val first = assertYielded(invokeSos(handle), "first SOS yield")
        val claim1 = admitClaim(handle, requestId(first), "first claim")
        assertEquals(HostOperationKind.KEYBOARD_SEND_TEXT, claim1.kind)
        assertEquals("sos-text", claim1.text)

        val second = assertYielded(
            s.bridge.resume(op(handle, first), true, """{"status":"delivered"}""", accepting),
            "second SOS yield",
        )
        val claim2 = admitClaim(handle, requestId(second), "second claim")
        assertEquals(HostOperationKind.KEYBOARD_SEND_KEY, claim2.kind)
        assertEquals("enter", claim2.key)

        val completed = s.bridge.resume(op(handle, second), true, """{"status":"delivered"}""", accepting)
        assertTrue(s.resultObject(s.assertCompleted(completed, "SOS chain delivered")).getBoolean("ok"))
    }

    // ==================================================================
    // profile_secret_http_conformance.rs — secret + http ownership
    // ==================================================================

    @Test
    fun `secret_read_yields_secret_read_and_resumes_plaintext`() {
        val handle = secretHttpState("""{"secretsRead":true}""")
        installGrants(handle, ONE_GRANT)
        val input = assertYielded(driveInput(handle, SECRET_READ_SOURCE), "secret read yield")

        // The claim carries the Kotlin-minted opaque reference token verbatim.
        val claim = admitClaim(handle, requestId(input), "secret claim")
        assertEquals(HostOperationKind.SECRET_READ, claim.kind)
        assertEquals("kotlin-minted-ref-token", claim.referenceToken)

        val done = s.bridge.resume(op(handle, input), true, """{"plaintext":"sk-live-abc123"}""", accepting)
        val value = s.resultObject(s.assertCompleted(done, "secret resume"))
        assertTrue(value.getBoolean("ok"))
        assertEquals("sk-live-abc123", value.getString("plaintext"))
    }

    @Test
    fun `http_request_yields_http_request_and_resumes_table`() {
        val handle = secretHttpState("""{"networkHttp":true}""")
        val input = assertYielded(driveInput(handle, HTTP_REQUEST_SOURCE), "http yield")

        // The claim carries bounded typed fields; nothing crosses in the yielded label.
        val claim = admitClaim(handle, requestId(input), "http claim")
        assertEquals(HostOperationKind.HTTP_REQUEST, claim.kind)
        assertEquals("POST", claim.method)
        assertEquals("https://api.example.com/v1/chat", claim.url)
        assertEquals(5000L, claim.timeoutMs)
        assertEquals("""{"x":1}""", claim.body)
        assertEquals("application/json", JSONObject(claim.headersJson!!).getString("content-type"))

        val done = s.bridge.resume(
            op(handle, input),
            true,
            """{"status":200,"headers":{"content-type":"application/json"},"body":"{\"ok\":true}"}""",
            accepting,
        )
        val value = s.resultObject(s.assertCompleted(done, "http resume"))
        assertTrue(value.getBoolean("ok"))
        assertEquals(200, value.getInt("status"))
        assertEquals("application/json", value.getString("ctype"))
        assertEquals("""{"ok":true}""", value.getString("body"))
    }

    @Test
    fun `http_multi_suspension_ownership`() {
        val handle = secretHttpState("""{"networkHttp":true}""")
        val input = assertYielded(driveInput(handle, HTTP_MULTI_SOURCE), "first http yield")
        val claim1 = admitClaim(handle, requestId(input), "first claim")
        assertEquals("https://a.example.com", claim1.url)

        val second = assertYielded(
            s.bridge.resume(op(handle, input), true, """{"status":200,"headers":{},"body":"a"}""", accepting),
            "second http yield",
        )
        assertNotEquals("distinct suspensions own distinct operation ids", input.operationId, second.operationId)
        val claim2 = admitClaim(handle, requestId(second), "second claim")
        assertEquals("https://b.example.com", claim2.url)

        val done = s.bridge.resume(op(handle, second), true, """{"status":201,"headers":{},"body":"b"}""", accepting)
        val value = s.resultObject(s.assertCompleted(done, "http multi resume"))
        assertEquals(200, value.getInt("s1"))
        assertEquals(201, value.getInt("s2"))
    }

    @Test
    fun `http_stale_generation_resume_denied`() {
        val handle = secretHttpState("""{"networkHttp":true}""")
        val input = assertYielded(driveInput(handle, HTTP_SIMPLE_SOURCE), "http yield")
        val opHandle = op(handle, input)
        s.assertClosed(s.closeState(handle), "close to advance generation")
        val stale = s.bridge.resume(opHandle, true, """{"status":200,"headers":{},"body":"x"}""", accepting)
        assertStale(stale, "stale-generation resume")
    }

    @Test
    fun `http_duplicate_claim_denied`() {
        val handle = secretHttpState("""{"networkHttp":true}""")
        val input = assertYielded(driveInput(handle, HTTP_SIMPLE_SOURCE), "http yield")
        val request = requestId(input)
        val first = admitClaim(handle, request, "first claim")
        assertEquals("first payload stays intact", HostOperationKind.HTTP_REQUEST, first.kind)
        assertTrue(
            "duplicate claim must be denied",
            s.bridge.claimHostOperation(handle, request) is HostOperationClaim.Rejected,
        )
    }

    // ==================================================================
    // resolver_conformance.rs — resolver suspensions through claim/resume
    // ==================================================================

    @Test
    fun `resolver_yields_secret_read_then_completes`() {
        val handle = s.createResolverState()
        installGrants(handle, RESOLVER_ONE_GRANT)
        val source = """
            local profiles = require("talkcan.profiles")
            local secrets = require("talkcan.secrets")
            return {
              resolve = function()
                local p = profiles.get("p1")
                local key = secrets.read(p.secrets.token)
                return { choices = { { value = key, label = "Authenticated model" } } }, nil
              end,
            }
        """.trimIndent()
        val yielded = assertYielded(
            s.invokeResolver(handle, s.resolverInvocation(source, JSONObject().put("secretsRead", true))),
            "resolver secret yield",
        )
        val claim = admitClaim(handle, requestId(yielded), "resolver secret claim")
        assertEquals(HostOperationKind.SECRET_READ, claim.kind)
        assertEquals("kotlin-minted-ref-token", claim.referenceToken)

        val done = s.bridge.resume(op(handle, yielded), true, """{"plaintext":"sk-live-abc"}""")
        val value = s.resultObject(s.assertCompleted(done, "resolver secret resume"))
        assertEquals("choices", value.getString("resultKind"))
        assertEquals("sk-live-abc", value.getJSONArray("choices").getJSONObject(0).getString("value"))
    }

    @Test
    fun `resolver_yields_http_request_then_completes`() {
        val handle = s.createResolverState()
        val source = """
            local http = require("talkcan.http")
            local json = require("talkcan.json")
            return {
              resolve = function()
                local resp = http.request({ method = "GET", url = "https://api.example.com/models" })
                local doc = json.decode(resp.body)
                local out = {}
                for i, m in ipairs(doc.models) do
                  out[i] = { value = m.id, label = m.id }
                end
                return { choices = out }, nil
              end,
            }
        """.trimIndent()
        val yielded = assertYielded(
            s.invokeResolver(handle, s.resolverInvocation(source, JSONObject().put("networkHttp", true))),
            "resolver http yield",
        )
        val claim = admitClaim(handle, requestId(yielded), "resolver http claim")
        assertEquals(HostOperationKind.HTTP_REQUEST, claim.kind)
        assertEquals("https://api.example.com/models", claim.url)

        val done = s.bridge.resume(
            op(handle, yielded),
            true,
            """{"status":200,"headers":{},"body":"{\"models\":[{\"id\":\"gpt-4o\"}]}"}""",
        )
        val value = s.resultObject(s.assertCompleted(done, "resolver http resume"))
        assertEquals("choices", value.getString("resultKind"))
        assertEquals("gpt-4o", value.getJSONArray("choices").getJSONObject(0).getString("value"))
    }

    @Test
    fun `resolver_repeated_suspension_two_http_yields`() {
        val handle = s.createResolverState()
        val source = """
            local http = require("talkcan.http")
            local json = require("talkcan.json")
            return {
              resolve = function()
                local r1 = http.request({ method = "GET", url = "https://api.example.com/a" })
                local r2 = http.request({ method = "GET", url = "https://api.example.com/b" })
                local d1 = json.decode(r1.body)
                local d2 = json.decode(r2.body)
                return { choices = {
                  { value = d1.id, label = d1.id },
                  { value = d2.id, label = d2.id },
                } }, nil
              end,
            }
        """.trimIndent()
        val yielded1 = assertYielded(
            s.invokeResolver(handle, s.resolverInvocation(source, JSONObject().put("networkHttp", true))),
            "first resolver yield",
        )
        assertEquals("https://api.example.com/a", admitClaim(handle, requestId(yielded1), "claim1").url)

        val yielded2 = assertYielded(
            s.bridge.resume(op(handle, yielded1), true, """{"status":200,"headers":{},"body":"{\"id\":\"model-a\"}"}"""),
            "second resolver yield",
        )
        assertEquals("https://api.example.com/b", admitClaim(handle, requestId(yielded2), "claim2").url)

        val done = s.bridge.resume(op(handle, yielded2), true, """{"status":200,"headers":{},"body":"{\"id\":\"model-b\"}"}""")
        val value = s.resultObject(s.assertCompleted(done, "resolver two-yield resume"))
        assertEquals("choices", value.getString("resultKind"))
        val choices = value.getJSONArray("choices")
        assertEquals("model-a", choices.getJSONObject(0).getString("value"))
        assertEquals("model-b", choices.getJSONObject(1).getString("value"))
    }

    @Test
    fun `resolver_stale_generation_resume_denied`() {
        val handle = s.createResolverState()
        val source = """
            local http = require("talkcan.http")
            return {
              resolve = function()
                local r = http.request({ method = "GET", url = "https://api.example.com/models" })
                return { choices = {} }, nil
              end,
            }
        """.trimIndent()
        val yielded = assertYielded(
            s.invokeResolver(handle, s.resolverInvocation(source, JSONObject().put("networkHttp", true))),
            "resolver yield",
        )
        val opHandle = op(handle, yielded)
        s.assertClosed(s.closeState(handle), "close to advance generation")
        assertStale(
            s.bridge.resume(opHandle, true, """{"status":200,"headers":{},"body":"x"}"""),
            "stale-generation resume",
        )
    }

    // ==================================================================
    // runtime_v1_conformance.rs — audio/transcribe/synth/playback ownership
    // ==================================================================

    @Test
    fun `transcribe_claim_carries_foreign_token_verbatim_without_ownership_check`() {
        val handle = runtimeState()
        s.loadProgramImageOk(handle, "entry", mapOf("entry" to TRANSCRIBE_SOURCE))
        val out = assertYielded(invokeInput(handle, token = "foreign-owner"), "transcribe yield")

        // The kernel validates the userdata kind synchronously; token ownership
        // is a host concern, so the opaque token is carried verbatim into the claim.
        val claim = admitClaim(handle, requestId(out), "transcribe claim")
        assertEquals(HostOperationKind.TRANSCRIBE, claim.kind)
        assertEquals("foreign-owner", claim.audioToken)

        val resumed = s.bridge.resume(op(handle, out), true, "verbatim", accepting)
        assertEquals("verbatim", s.resultObject(s.assertCompleted(resumed, "transcribe resume")).getString("text"))
        s.assertClosed(s.closeState(handle), "close")
    }

    @Test
    fun `host_operation_claim_rejects_busy_stale_closed`() {
        // Busy / host-work failure: a valid op yields; the host completes it as a
        // failure and Lua observes the normalized code (no synchronous pre-yield map).
        val busyState = runtimeState()
        s.loadProgramImageOk(busyState, "entry", mapOf("entry" to TRANSCRIBE_SOURCE))
        val busyOut = assertYielded(invokeInput(busyState, token = "busy-token"), "busy yield")
        val failed = s.bridge.resume(op(busyState, busyOut), false, "E_BUSY", accepting)
        assertEquals("E_BUSY", s.resultObject(s.assertCompleted(failed, "busy failure")).getString("error"))
        s.assertClosed(s.closeState(busyState), "busy close")

        // Stale: once the owning coroutine is cancelled, its typed request is
        // dropped — a late claim is rejected and a late resume is suppressed.
        val staleState = runtimeState()
        s.loadProgramImageOk(staleState, "entry", mapOf("entry" to TRANSCRIBE_SOURCE))
        val staleOut = assertYielded(invokeInput(staleState, token = "stale-token"), "stale yield")
        val staleOp = op(staleState, staleOut)
        val staleRequest = requestId(staleOut)
        assertCancelled(s.bridge.cancel(staleOp), "cancel")
        assertTrue(
            "claim after cancel must be rejected",
            s.bridge.claimHostOperation(staleState, staleRequest) is HostOperationClaim.Rejected,
        )
        assertTrue(
            "late resume after cancel must be suppressed",
            s.bridge.resume(staleOp, true, "late", accepting) !is LuaKernelOutcome.Completed,
        )
        s.assertClosed(s.closeState(staleState), "stale close")

        // Closed: after the generation is closed, a claim is rejected as closed.
        val closedState = runtimeState()
        s.loadProgramImageOk(closedState, "entry", mapOf("entry" to TRANSCRIBE_SOURCE))
        val closedOut = assertYielded(invokeInput(closedState, token = "closed-token"), "closed yield")
        val closedRequest = requestId(closedOut)
        val closed = s.assertClosed(s.closeState(closedState), "close")
        val closedHandle = LuaStateHandle(LuaStateId(closed.stateId), LuaStateGeneration(closed.generation))
        assertTrue(
            "claim against a closed generation must be rejected",
            s.bridge.claimHostOperation(closedHandle, closedRequest) is HostOperationClaim.Rejected,
        )
    }

    @Test
    fun `audio_operation_duplicate_terminal_echoes_without_reentry`() {
        val handle = runtimeState()
        s.loadProgramImageOk(handle, "entry", mapOf("entry" to TRANSCRIBE_SOURCE))
        val out = assertYielded(invokeInput(handle, token = "captured-token"), "transcribe yield")
        val opHandle = op(handle, out)

        val first = s.bridge.resume(opHandle, true, "hello world", accepting)
        assertEquals("hello world", s.resultObject(s.assertCompleted(first, "first resume")).getString("text"))

        // The duplicate echoes the exact terminal without re-entering Lua: the
        // "must not execute" value never reaches the callback.
        val duplicate = s.bridge.resume(opHandle, true, "must not execute", accepting)
        assertEquals(
            "duplicate resume must echo the exact terminal outcome without re-entry",
            "hello world",
            s.resultObject(s.assertCompleted(duplicate, "duplicate resume")).getString("text"),
        )
        s.assertClosed(s.closeState(handle), "close")
    }

    @Test
    fun `host_operation_yield_is_opaque_and_typed_claim_returns_payload`() {
        val handle = runtimeState()
        s.loadProgramImageOk(handle, "entry", mapOf("entry" to OPAQUE_OPS_SOURCE))

        // TRANSCRIBE: opaque yield, typed claim carries the captured token.
        val transcribe = assertYielded(
            invokeInput(handle, captureEvent(extra = mapOf("op" to LuaValue.StringValue("transcribe"))), "cap-token"),
            "transcribe yield",
        )
        val transcribeLabel = transcribe.value!!
        assertNotNull("bare integer request id", transcribeLabel.toLongOrNull())
        assertFalse("opaque label leaked structure: $transcribeLabel", transcribeLabel.contains("{"))
        assertFalse("opaque label leaked synth marker: $transcribeLabel", transcribeLabel.contains("synthesized"))
        assertFalse("opaque label leaked the audio token: $transcribeLabel", transcribeLabel.contains("cap-token"))
        val transcribeClaim = admitClaim(handle, transcribeLabel.toLong(), "transcribe claim")
        assertEquals(HostOperationKind.TRANSCRIBE, transcribeClaim.kind)
        assertEquals("cap-token", transcribeClaim.audioToken)
        s.bridge.resume(op(handle, transcribe), true, "ok", accepting)

        // SYNTHESIZE: opaque yield, typed claim carries exact parameters.
        val synthesize = assertYielded(
            invokeInput(handle, captureEvent(extra = mapOf("op" to LuaValue.StringValue("synthesize"))), "cap-token"),
            "synthesize yield",
        )
        val synthesizeLabel = synthesize.value!!
        assertNotNull("bare integer request id", synthesizeLabel.toLongOrNull())
        assertFalse("opaque label leaked synthesis text: $synthesizeLabel", synthesizeLabel.contains("hello"))
        assertFalse("opaque label leaked structure: $synthesizeLabel", synthesizeLabel.contains("{"))
        val synthesizeClaim = admitClaim(handle, synthesizeLabel.toLong(), "synthesize claim")
        assertEquals(HostOperationKind.SYNTHESIZE, synthesizeClaim.kind)
        assertEquals("hello", synthesizeClaim.text)
        assertEquals("en-US", synthesizeClaim.language)
        assertEquals("v", synthesizeClaim.voice)
        assertEquals(1.0, synthesizeClaim.speed, 0.0)
        s.bridge.resume(op(handle, synthesize), true, "tok", accepting)

        // PLAYBACK: opaque yield, typed claim carries token + delay.
        val playback = assertYielded(
            invokeInput(handle, captureEvent(extra = mapOf("op" to LuaValue.StringValue("playback"))), "cap-token"),
            "playback yield",
        )
        val playbackLabel = playback.value!!
        assertNotNull("bare integer request id", playbackLabel.toLongOrNull())
        assertFalse("opaque label leaked structure: $playbackLabel", playbackLabel.contains("{"))
        assertFalse("opaque label leaked the audio token: $playbackLabel", playbackLabel.contains("cap-token"))
        val playbackClaim = admitClaim(handle, playbackLabel.toLong(), "playback claim")
        assertEquals(HostOperationKind.PLAYBACK, playbackClaim.kind)
        assertEquals("cap-token", playbackClaim.audioToken)
        assertEquals(2.0, playbackClaim.delaySeconds, 0.0)
        val scheduled = s.bridge.resume(op(handle, playback), true, "ignored", accepting)
        assertEquals("scheduled", s.resultObject(s.assertCompleted(scheduled, "playback resume")).getString("status"))

        s.assertClosed(s.closeState(handle), "close")
    }

    @Test
    fun `host_operation_duplicate_claim_is_rejected`() {
        val handle = runtimeState()
        s.loadProgramImageOk(handle, "entry", mapOf("entry" to TRANSCRIBE_SOURCE))
        val out = assertYielded(invokeInput(handle, token = "dup-token"), "transcribe yield")
        val request = requestId(out)
        val first = admitClaim(handle, request, "first claim")
        assertEquals(HostOperationKind.TRANSCRIBE, first.kind)
        assertTrue(
            "duplicate claim must be rejected as invalid ownership",
            s.bridge.claimHostOperation(handle, request) is HostOperationClaim.Rejected,
        )
        s.assertClosed(s.closeState(handle), "close")
    }

    @Test
    fun `host_operation_foreign_claim_is_rejected`() {
        val handle = runtimeState()
        s.loadProgramImageOk(handle, "entry", mapOf("entry" to TRANSCRIBE_SOURCE))
        assertYielded(invokeInput(handle, token = "token"), "transcribe yield")
        // A request id that was never yielded has no owner.
        assertTrue(
            "foreign claim must be rejected as invalid ownership",
            s.bridge.claimHostOperation(handle, 999_999L) is HostOperationClaim.Rejected,
        )
        s.assertClosed(s.closeState(handle), "close")
    }

    @Test
    fun `host_operation_stale_claim_after_cancel`() {
        val handle = runtimeState()
        s.loadProgramImageOk(handle, "entry", mapOf("entry" to TRANSCRIBE_SOURCE))
        val out = assertYielded(invokeInput(handle, token = "token"), "transcribe yield")
        val request = requestId(out)
        assertCancelled(s.bridge.cancel(op(handle, out)), "cancel")
        assertTrue(
            "claim after cancel must be rejected",
            s.bridge.claimHostOperation(handle, request) is HostOperationClaim.Rejected,
        )
        s.assertClosed(s.closeState(handle), "close")
    }

    @Test
    fun `host_operation_closed_claim_after_close`() {
        val handle = runtimeState()
        s.loadProgramImageOk(handle, "entry", mapOf("entry" to TRANSCRIBE_SOURCE))
        val out = assertYielded(invokeInput(handle, token = "token"), "transcribe yield")
        val request = requestId(out)
        val closed = s.assertClosed(s.closeState(handle), "close")
        val closedHandle = LuaStateHandle(LuaStateId(closed.stateId), LuaStateGeneration(closed.generation))
        assertTrue(
            "claim after close must be rejected as closed",
            s.bridge.claimHostOperation(closedHandle, request) is HostOperationClaim.Rejected,
        )
    }

    @Test
    fun `host_operation_synthesis_completion_publishes_opaque_audio`() {
        val handle = runtimeState()
        s.loadProgramImageOk(handle, "entry", mapOf("entry" to SYNTH_SOURCE))
        val out = assertYielded(invokeInput(handle, token = "token"), "synthesis yield")
        val resumed = s.bridge.resume(op(handle, out), true, "tok", accepting)
        val value = s.resultObject(s.assertCompleted(resumed, "synthesis resume"))
        assertEquals("opaque_audio", value.getString("text"))
        assertFalse("opaque audio userdata must have no metatable", value.getBoolean("meta"))
        s.assertClosed(s.closeState(handle), "close")
    }

    @Test
    fun `audio_open_yields_typed_request_and_resumes_as_opaque_recording`() {
        val handle = audioState()
        s.installResourceContext(handle, AUDIO_RC)
        s.loadProgramImageOk(handle, "entry", mapOf("entry" to AUDIO_OPEN_SOURCE))
        val yielded = assertYielded(invokeInput(handle, token = "captured-token"), "audio open yield")

        val claim = admitClaim(handle, requestId(yielded), "audio open claim")
        assertEquals(HostOperationKind.AUDIO_OPEN, claim.kind)
        assertEquals("output", claim.declarationId)
        assertEquals("stored/input.wav", claim.path)
        assertEquals("wav-pcm-s16le", claim.format)

        val completed = s.bridge.resume(
            op(handle, yielded),
            true,
            """{"token":"opened-token","metadata":{"sample_rate":16000,"channels":1,"duration_ms":250,"pcm_bytes":8000}}""",
            accepting,
        )
        val value = s.resultObject(s.assertCompleted(completed, "audio open resume"))
        assertEquals(16000, value.getInt("sample_rate"))
        assertEquals(1, value.getInt("channels"))
        assertEquals(250, value.getInt("duration_ms"))
        assertEquals(8000, value.getInt("pcm_bytes"))
        s.assertClosed(s.closeState(handle), "close")
    }

    @Test
    fun `audio_export_claim_preserves_recording_identity_and_portable_options`() {
        val handle = audioState()
        s.installResourceContext(handle, AUDIO_RC)
        s.loadProgramImageOk(handle, "entry", mapOf("entry" to AUDIO_EXPORT_SOURCE))
        val yielded = assertYielded(invokeInput(handle, token = "captured-token"), "audio export yield")

        val claim = admitClaim(handle, requestId(yielded), "audio export claim")
        assertEquals(HostOperationKind.AUDIO_EXPORT, claim.kind)
        assertEquals("captured-token", claim.audioToken)
        assertEquals("output", claim.declarationId)
        assertEquals("daily/capture.wav", claim.path)
        assertEquals("wav-pcm-s16le", claim.format)
        assertEquals("replace", claim.mode)

        val completed = s.bridge.resume(
            op(handle, yielded),
            true,
            """{"status":"written","format":"wav-pcm-s16le","sample_rate":16000,"channels":1,"duration_ms":250,"bytes":1234}""",
            accepting,
        )
        val value = s.resultObject(s.assertCompleted(completed, "audio export resume"))
        assertEquals("written", value.getString("status"))
        assertEquals("wav-pcm-s16le", value.getString("format"))
        assertEquals(16000, value.getInt("sample_rate"))
        assertEquals(1, value.getInt("channels"))
        assertEquals(250, value.getInt("duration_ms"))
        assertEquals(1234, value.getInt("bytes"))
        s.assertClosed(s.closeState(handle), "close")
    }

    @Test
    fun `captured_recording_round_trips_through_export_open_transcription_and_export`() {
        val handle = audioState()
        s.installResourceContext(handle, AUDIO_RC)
        s.loadProgramImageOk(handle, "entry", mapOf("entry" to AUDIO_ROUNDTRIP_SOURCE))

        var outcome = assertYielded(
            invokeInput(handle, captureEvent(pcmBytes = 16000, durationMs = 500), "captured-token"),
            "first export yield",
        )

        // export(captured) → open → transcribe(reopened) → export(reopened),
        // with recording identity preserved end to end.
        val export1 = admitClaim(handle, requestId(outcome), "export captured")
        assertEquals(HostOperationKind.AUDIO_EXPORT, export1.kind)
        assertEquals("captured-token", export1.audioToken)
        outcome = assertYielded(
            s.bridge.resume(
                op(handle, outcome),
                true,
                """{"status":"written","format":"wav-pcm-s16le","sample_rate":16000,"channels":1,"duration_ms":500,"bytes":16044}""",
                accepting,
            ),
            "open yield",
        )

        val open = admitClaim(handle, requestId(outcome), "open recording")
        assertEquals(HostOperationKind.AUDIO_OPEN, open.kind)
        outcome = assertYielded(
            s.bridge.resume(
                op(handle, outcome),
                true,
                """{"token":"reopened-token","metadata":{"sample_rate":16000,"channels":1,"duration_ms":500,"pcm_bytes":16000}}""",
                accepting,
            ),
            "transcribe yield",
        )

        val transcribe = admitClaim(handle, requestId(outcome), "transcribe reopened")
        assertEquals(HostOperationKind.TRANSCRIBE, transcribe.kind)
        assertEquals("reopened-token", transcribe.audioToken)
        outcome = assertYielded(
            s.bridge.resume(op(handle, outcome), true, "portable transcript", accepting),
            "second export yield",
        )

        val export2 = admitClaim(handle, requestId(outcome), "export reopened")
        assertEquals(HostOperationKind.AUDIO_EXPORT, export2.kind)
        assertEquals("reopened-token", export2.audioToken)
        val completed = s.bridge.resume(
            op(handle, outcome),
            true,
            """{"status":"written","format":"wav-pcm-s16le","sample_rate":16000,"channels":1,"duration_ms":500,"bytes":1234}""",
            accepting,
        )
        val value = s.resultObject(s.assertCompleted(completed, "round trip completes"))
        assertTrue(value.getBoolean("ok"))
        assertEquals("portable transcript", value.getString("text"))
        s.assertClosed(s.closeState(handle), "close")
    }

    // ==================================================================
    // Fixture sources
    // ==================================================================

    private companion object {
        const val AUDIO_RC =
            """{"instanceId":"journal","storageFiles":true,"audioFiles":true,"mounts":{"output":{"access":"read-write","status":"available"}}}"""

        const val ONE_GRANT =
            """{"profiles":[{"profileId":"p1","typeLocalId":"openai-account","displayName":"Primary","values":{"apiKey":{"t":"text","v":"public-123"}},"secretReferences":{"token":"kotlin-minted-ref-token"}}]}"""

        const val RESOLVER_ONE_GRANT =
            """{"profiles":[{"profileId":"p1","typeLocalId":"openai-account","displayName":"Primary","values":{},"secretReferences":{"token":"kotlin-minted-ref-token"}}]}"""

        val FS_TOUR_SOURCE = """
            local fs = require("talkcan.fs")
            return {
              startup = function() end,
              handle_input = function(event)
                local mount, me = fs.mount("data")
                if not mount then return {error={code="MOUNT_FAIL",detail=me.error}} end
                local r1, e1 = fs.mkdir(mount, "a/b", {parents=true})
                if not r1 then return {error={code="MKDIR",detail=e1.error}} end
                local r2, e2 = fs.write_text(mount, "a/b/f.txt", "hello", {mode="create-new"})
                if not r2 then return {error={code="WRITE",detail=e2.error}} end
                local r3, e3 = fs.stat(mount, "a/b/f.txt")
                if not r3 then return {error={code="STAT",detail=e3.error}} end
                local r4, e4 = fs.read_text(mount, "a/b/f.txt", {max_bytes=1024})
                if not r4 then return {error={code="READ",detail=e4.error}} end
                local r5, e5 = fs.list(mount, "a/b", {limit=10})
                if not r5 then return {error={code="LIST",detail=e5.error}} end
                local r6, e6 = fs.remove(mount, "a/b/f.txt", {missing_ok=false})
                if not r6 then return {error={code="REMOVE",detail=e6.error}} end
                return {ok=true}
              end,
            }
        """.trimIndent()

        val FS_HOLDER_SOURCE = """
            local fs = require("talkcan.fs")
            local holder = {}
            return {
              startup = function() end,
              handle_input = function(event)
                if event and event.fresh then
                  holder.mount = fs.mount("data")
                  return {ok=true}
                end
                local r, e = fs.stat(holder.mount, "f.txt")
                return {error={code="GOT",detail=e and e.error or "nil"}}
              end,
            }
        """.trimIndent()

        val KB_SEND_TEXT_SOURCE = """
            local kb = require("talkcan.keyboard_output")
            return {
              startup = function() end,
              handle_input = function(event)
                local result, err = kb.send_text({ text = "secret hello", profile = "linux:us" })
                if not result then return { error = { code = "SEND", detail = err.error } } end
                if result.status ~= "delivered" then
                  return { error = { code = "STATUS", detail = result.status } }
                end
                return { ok = true }
              end,
            }
        """.trimIndent()

        val KB_SEND_TEXT_SIMPLE_SOURCE = """
            local kb = require("talkcan.keyboard_output")
            return {
              startup = function() end,
              handle_input = function(event)
                local result = kb.send_text({ text = "x", profile = "p" })
                return { ok = true }
              end,
            }
        """.trimIndent()

        val KB_SEND_KEY_ENTER_SOURCE = """
            local kb = require("talkcan.keyboard_output")
            return {
              startup = function() end,
              handle_input = function(event)
                local result, err = kb.send_key({ key = "enter", profile = "mac:iso" })
                if not result then return { error = { code = "SEND", detail = err.error } } end
                if result.status ~= "delivered" then
                  return { error = { code = "STATUS", detail = result.status } }
                end
                return { ok = true }
              end,
            }
        """.trimIndent()

        val KB_SEND_KEY_ESCAPE_SOURCE = """
            local kb = require("talkcan.keyboard_output")
            return {
              startup = function() end,
              handle_input = function(event)
                local result, err = kb.send_key({ key = "escape", profile = "p" })
                if not result then return { error = { code = "SEND", detail = err.error } } end
                return { status = result.status, reason = result.reason }
              end,
            }
        """.trimIndent()

        val SECRET_READ_SOURCE = """
            local profiles = require("talkcan.profiles")
            local secrets = require("talkcan.secrets")
            return {
              startup = function() end,
              handle_input = function()
                local p = profiles.get("p1")
                local plaintext, e = secrets.read(p.secrets.token)
                if not plaintext then return { error = { code = "READ", detail = e.error } } end
                return { ok = true, plaintext = plaintext }
              end,
            }
        """.trimIndent()

        val HTTP_REQUEST_SOURCE = """
            local http = require("talkcan.http")
            return {
              startup = function() end,
              handle_input = function()
                local resp, e = http.request({
                  method = "POST",
                  url = "https://api.example.com/v1/chat",
                  headers = { ["content-type"] = "application/json" },
                  body = "{\"x\":1}",
                  timeout_ms = 5000,
                })
                if not resp then return { error = { code = "HTTP", detail = e.error } } end
                return {
                  ok = true,
                  status = resp.status,
                  ctype = resp.headers["content-type"],
                  body = resp.body,
                }
              end,
            }
        """.trimIndent()

        val HTTP_MULTI_SOURCE = """
            local http = require("talkcan.http")
            return {
              startup = function() end,
              handle_input = function()
                local r1 = http.request({ method = "GET", url = "https://a.example.com" })
                local r2 = http.request({ method = "GET", url = "https://b.example.com" })
                return { ok = true, s1 = r1.status, s2 = r2.status }
              end,
            }
        """.trimIndent()

        val HTTP_SIMPLE_SOURCE = """
            local http = require("talkcan.http")
            return {
              startup = function() end,
              handle_input = function()
                local r = http.request({ method = "GET", url = "https://x.example.com" })
                return { ok = true }
              end,
            }
        """.trimIndent()

        val TRANSCRIBE_SOURCE = """
            local t = require("talkcan.transcription")
            return {
              startup = function() end,
              handle_input = function(event)
                local x, e = t.transcribe(event.audio)
                if e then return {error = e.error} end
                return {text = x.text}
              end,
            }
        """.trimIndent()

        val SYNTH_SOURCE = """
            local s = require("talkcan.synthesis")
            return {
              startup = function() end,
              handle_input = function(event)
                local x, e = s.synthesize({text="hello", language="en-US", voice="v"})
                if e then return {error = e.error} end
                return {text = tostring(x), meta = getmetatable(x)}
              end,
            }
        """.trimIndent()

        val OPAQUE_OPS_SOURCE = """
            local t = require("talkcan.transcription")
            local s = require("talkcan.synthesis")
            local p = require("talkcan.playback")
            return {
              startup = function() end,
              handle_input = function(event)
                local a = event or {}
                if a.op == "transcribe" then
                  local x, e = t.transcribe(event.audio)
                  if e then return {error = e.error} end
                  return {text = x.text}
                elseif a.op == "synthesize" then
                  local x, e = s.synthesize({text="hello", language="en-US", voice="v", speed=1.0})
                  if e then return {error = e.error} end
                  return {text = tostring(x), meta = getmetatable(x)}
                elseif a.op == "playback" then
                  local r, e = p.schedule(event.audio, {delay_seconds=2})
                  if e then return {error = e.error} end
                  return r
                end
                return {error = "unknown-op"}
              end,
            }
        """.trimIndent()

        val AUDIO_OPEN_SOURCE = """
            local audio = require("talkcan.audio")
            local fs = require("talkcan.fs")
            return {
              startup = function() end,
              handle_input = function(event)
                local mount = assert(fs.mount("output"))
                local recording, open_error = audio.open(
                  mount,
                  "stored/input.wav",
                  { format = "wav-pcm-s16le" }
                )
                if not recording then return { error = open_error } end
                return audio.describe(recording)
              end,
            }
        """.trimIndent()

        val AUDIO_EXPORT_SOURCE = """
            local audio = require("talkcan.audio")
            local fs = require("talkcan.fs")
            return {
              startup = function() end,
              handle_input = function(event)
                local mount = assert(fs.mount("output"))
                return audio.export(
                  event.audio,
                  mount,
                  "daily/capture.wav",
                  { format = "wav-pcm-s16le", mode = "replace" }
                )
              end,
            }
        """.trimIndent()

        val AUDIO_ROUNDTRIP_SOURCE = """
            local audio = require("talkcan.audio")
            local fs = require("talkcan.fs")
            local transcription = require("talkcan.transcription")
            return {
              startup = function() end,
              handle_input = function(event)
                local mount = assert(fs.mount("output"))
                local captured = assert(audio.describe(event.audio))
                assert(captured.pcm_bytes == 16000)
                assert(audio.export(
                  event.audio,
                  mount,
                  "daily/capture.wav",
                  { format = "wav-pcm-s16le", mode = "create-new" }
                ))
                local reopened = assert(audio.open(
                  mount,
                  "daily/capture.wav",
                  { format = "wav-pcm-s16le" }
                ))
                local transcript = assert(transcription.transcribe(reopened))
                assert(audio.export(
                  reopened,
                  mount,
                  "daily/capture.wav",
                  { format = "wav-pcm-s16le", mode = "replace" }
                ))
                return { ok = true, text = transcript.text }
              end,
            }
        """.trimIndent()
    }
}
