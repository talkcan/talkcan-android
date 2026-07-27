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
import io.talkcan.lua.LuaStateId
import io.talkcan.lua.LuaValue
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.After
import org.junit.Test

/**
 * Lifecycle conformance (inventory category L), ported one-for-one from the
 * Rust suite in `rust/talkcan-lua-actor/talkcan-lua-actor/tests/` against
 * the pure-Kotlin `KotlinLuaKernelBridge`.
 *
 * Allocator-denial telemetry (the Rust `lower_memory_limit` hook) is an accepted
 * loss of the Kotlin substrate: the binding exposes no per-state allocator hook.
 * The surviving lifecycle contract is typed-outcome normalization, failed staging
 * retains nothing, and every state stays closable.
 */
internal class KotlinLuaKernelLifecycleConformanceTest {

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

    private fun assertYielded(outcome: LuaKernelOutcome, context: String = ""): LuaKernelOutcome.Yielded {
        if (outcome !is LuaKernelOutcome.Yielded) {
            throw AssertionError("${context.ifEmpty { "outcome" }}: expected Yielded but was $outcome")
        }
        return outcome
    }

    private fun assertStale(outcome: LuaKernelOutcome, context: String = ""): LuaKernelOutcome.Stale {
        if (outcome !is LuaKernelOutcome.Stale) {
            throw AssertionError("${context.ifEmpty { "outcome" }}: expected Stale but was $outcome")
        }
        return outcome
    }

    private fun assertSnapshot(outcome: LuaKernelOutcome, context: String = ""): LuaKernelOutcome.Snapshot {
        if (outcome !is LuaKernelOutcome.Snapshot) {
            throw AssertionError("${context.ifEmpty { "outcome" }}: expected Snapshot but was $outcome")
        }
        return outcome
    }

    private fun operation(handle: LuaStateHandle, yielded: LuaKernelOutcome.Yielded): LuaOperationHandle =
        LuaOperationHandle(handle, LuaCoroutineId(yielded.coroutineId), LuaOperationId(yielded.operationId))

    private fun requestId(yielded: LuaKernelOutcome.Yielded): Long =
        (yielded.value ?: throw AssertionError("yielded outcome carries no request identity: $yielded")).toLong()

    private fun resultJson(outcome: LuaKernelOutcome.Completed): JSONObject =
        support.resultObject(outcome)

    private fun invokeInput(handle: LuaStateHandle, args: LuaValue, token: String): LuaKernelOutcome =
        bridge.invokeInputCallback(handle, LuaCallbackHandle(handle, "handle_input"), args, token, accepting)

    private fun captureEvent(extra: kotlin.collections.Map<String, LuaValue> = emptyMap()): LuaValue =
        LuaValue.Map(
            linkedMapOf<String, LuaValue>().apply {
                putAll(extra)
                put(
                    "metadata",
                    LuaValue.Map(
                        mapOf(
                            "sample_rate" to LuaValue.Integer(16_000L),
                            "channels" to LuaValue.Integer(1L),
                            "duration_ms" to LuaValue.Integer(1L),
                            "pcm_bytes" to LuaValue.Integer(32L),
                        ),
                    ),
                )
            },
        )

    private fun installRc(handle: LuaStateHandle, resourceContextJson: String) =
        support.installResourceContext(handle, resourceContextJson)

    private fun loadFixturePlugin(resourcePath: String): String {
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

    private fun startupOk(handle: LuaStateHandle, values: LuaValue = LuaValue.Map(emptyMap())): LuaKernelOutcome.Completed {
        val config = LuaValue.Map(mapOf("schema_version" to LuaValue.Integer(1L), "values" to values))
        return support.assertCompleted(
            bridge.invokeStartupCallback(handle, LuaCallbackHandle(handle, "startup"), config, accepting),
            "startup",
        )
    }

    // ------------------------------------------------------------------
    // conformance.rs
    // ------------------------------------------------------------------

    @Test
    fun states_isolate_globals_and_close_effects() {
        val right = support.createState()
        val left = support.createState()

        support.loadSourceOk(
            right,
            """
            side = "right"
            function main() return side end
            """.trimIndent(),
            "main",
        )
        val rightResult = support.startEntryOk(right)
        if (support.resultString(rightResult) != "\"right\"") {
            throw AssertionError("right state returned ${rightResult.value}")
        }
        val beforeRight = assertSnapshot(bridge.snapshot(right), "snapshot before left work")

        support.loadSourceOk(
            left,
            """
            local allocated = {}
            for i = 1, 2000 do allocated[i] = string.rep("l", 32) end
            side = "left"
            function main() return side end
            """.trimIndent(),
            "main",
        )
        val leftResult = support.startEntryOk(left)
        if (support.resultString(leftResult) != "\"left\"") {
            throw AssertionError("left state returned ${leftResult.value}")
        }

        // Isolation is observed behaviorally: allocations in one state change neither
        // the identity nor the behavior of the peer.
        val afterRight = assertSnapshot(bridge.snapshot(right), "snapshot after left work")
        if (afterRight.stateId != beforeRight.stateId || afterRight.generation != beforeRight.generation) {
            throw AssertionError("peer identity changed under another state's allocations: $beforeRight -> $afterRight")
        }

        support.assertClosed(support.closeState(left), "close left")
        val rejectedLeft = bridge.snapshot(left)
        if (rejectedLeft !is LuaKernelOutcome.Stale && rejectedLeft !is LuaKernelOutcome.Closed) {
            throw AssertionError("closed state accepted a snapshot: $rejectedLeft")
        }

        support.loadSourceOk(right, "function surviving() return side end", "surviving")
        val rightAfterClose = support.startEntryOk(right)
        if (support.resultString(rightAfterClose) != "\"right\"") {
            throw AssertionError("peer global changed by sibling lifecycle: ${rightAfterClose.value}")
        }
        support.assertClosed(support.closeState(right), "close right")
    }

    @Test
    fun states_keep_module_caches_and_suspended_coroutines_independent() {
        val left = support.createState()
        val right = support.createState()

        support.loadSourceOk(
            left,
            """
            shared = "left"
            talkcan.module_put("shared-module", "left-module")
            function main()
              local _, continuation = talkcan.yield_operation("left-operation")
              return shared .. ":" .. talkcan.module_get("shared-module") .. ":" .. continuation
            end
            """.trimIndent(),
            "main",
        )
        support.loadSourceOk(
            right,
            """
            shared = "right"
            talkcan.module_put("shared-module", "right-module")
            function main()
              local _, continuation = talkcan.yield_operation("right-operation")
              return shared .. ":" .. talkcan.module_get("shared-module") .. ":" .. continuation
            end
            """.trimIndent(),
            "main",
        )

        val leftYielded = assertYielded(support.startEntry(left), "left start")
        val rightYielded = assertYielded(support.startEntry(right), "right start")

        // A cross-state operation handle must be rejected before any Lua effect.
        val foreign = LuaOperationHandle(
            left,
            LuaCoroutineId(rightYielded.coroutineId),
            LuaOperationId(rightYielded.operationId),
        )
        val cross = bridge.resume(foreign, true, "forbidden")
        if (cross !is LuaKernelOutcome.InvalidOwnership) {
            throw AssertionError("cross-state resume accepted: $cross")
        }
        support.assertClosed(support.closeState(left), "close left")

        val rightResumed = support.assertCompleted(
            bridge.resume(operation(right, rightYielded), true, "continued"),
            "right resume",
        )
        if (support.resultString(rightResumed) != "\"right:right-module:continued\"") {
            throw AssertionError(
                "closing a sibling state or submitting its coroutine operation changed this state: " +
                    rightResumed.value,
            )
        }
        val leftLate = bridge.resume(operation(left, leftYielded), true, "late")
        if (leftLate !is LuaKernelOutcome.Stale && leftLate !is LuaKernelOutcome.Closed) {
            throw AssertionError("a closed coroutine accepted continuation: $leftLate")
        }
        support.assertClosed(support.closeState(right), "close right")
    }

    @Test
    fun snapshots_publish_identity_versions_and_elapsed_evidence() {
        val state = support.createState()
        val snapshot = assertSnapshot(bridge.snapshot(state), "snapshot")
        if (snapshot.stateId != state.stateId.value) {
            throw AssertionError("snapshot stateId ${snapshot.stateId} != handle ${state.stateId.value}")
        }
        if (snapshot.generation != state.generation.value) {
            throw AssertionError("snapshot generation ${snapshot.generation} != handle ${state.generation.value}")
        }
        if (snapshot.topology != "jvm_owned") {
            throw AssertionError("snapshot topology: ${snapshot.topology}")
        }
        if (snapshot.luaVersion != "5.4.8") {
            throw AssertionError("snapshot luaVersion: ${snapshot.luaVersion}")
        }
        if (snapshot.bindingVersion != "4.1.0") {
            throw AssertionError("snapshot bindingVersion: ${snapshot.bindingVersion}")
        }
        if (snapshot.elapsedNanos == null) {
            throw AssertionError("snapshot omitted elapsedNanos evidence: $snapshot")
        }
        support.assertClosed(support.closeState(state), "close")
    }

    @Test
    fun malformed_source_and_invalid_entrypoint_are_normalized_and_closable() {
        val cases = listOf(
            arrayOf("function main( return 1 end", "main", true),
            arrayOf("\u001bLua\u0000binary-chunk", "main", true),
            arrayOf("not_an_entrypoint = 3", "not_an_entrypoint", false),
            arrayOf("function other() return 'ok' end", "missing", false),
        )
        for (case in cases) {
            val source = case[0] as String
            val entrypoint = case[1] as String
            val syntax = case[2] as Boolean
            val state = support.createState()
            val outcome = support.loadSource(state, source, entrypoint)
            if (syntax) {
                support.assertSyntaxFailure(outcome, "syntax case $entrypoint")
            } else {
                support.assertValidationFailure(outcome, "validation case $entrypoint")
            }
            support.assertClosed(support.closeState(state), "close after failure")
        }
    }

    @Test
    fun protected_ordinary_lua_errors_leave_peer_state_usable() {
        // Observes isolation for ordinary protected Lua errors only; no containment
        // claim for native corruption, process OOM, or process death.
        val failing = support.createState()
        val peer = support.createState()

        support.loadSourceOk(failing, "function main() error('ordinary-protected-error') end", "main")
        val failed = support.assertRuntimeFailure(support.startEntry(failing), "failing start")
        if (!failed.diagnostic.contains("ordinary-protected-error")) {
            throw AssertionError("ordinary Lua error lost its diagnostic: ${failed.diagnostic}")
        }

        support.loadSourceOk(
            peer,
            "peer_counter = (peer_counter or 0) + 1; function main() return tostring(peer_counter) end",
            "main",
        )
        val peerResult = support.startEntryOk(peer)
        if (support.resultString(peerResult) != "\"1\"") {
            throw AssertionError("a protected error in one state changed a peer state's execution: ${peerResult.value}")
        }

        support.assertClosed(support.closeState(failing), "close failing")
        support.assertClosed(support.closeState(peer), "close peer")
    }

    @Test
    fun repeated_lifecycle_cycles_leave_old_handles_unusable() {
        val yieldingCounter = """
            hits = 0
            function main()
              local success, value = talkcan.yield_operation("external-work")
              hits = hits + 1
              if success then return "success:" .. value end
              return "failure:" .. value
            end
            function observe() return tostring(hits) end
        """.trimIndent()
        for (cycle in 0 until 24) {
            val state = support.createState()
            support.loadSourceOk(state, yieldingCounter, "main")
            val yielded = assertYielded(support.startEntry(state), "cycle $cycle start")
            val terminal = if (cycle % 2 == 0) {
                bridge.resume(operation(state, yielded), true, "cycle")
            } else {
                bridge.cancel(operation(state, yielded))
            }
            if (terminal !is LuaKernelOutcome.Completed && terminal !is LuaKernelOutcome.Cancelled) {
                throw AssertionError("cycle $cycle failed terminal admission: $terminal")
            }
            // Identity evidence stays stable across cycles.
            assertSnapshot(bridge.snapshot(state), "cycle $cycle snapshot")
            support.assertClosed(support.closeState(state), "cycle $cycle close")
            assertStale(bridge.resume(operation(state, yielded), true, "late"), "cycle $cycle late resume")
        }
    }

    @Test
    fun allocation_heavy_state_closes_deterministically_and_idempotently() {
        val state = support.createState()
        support.loadSourceOk(
            state,
            "allocated = string.rep('x', 16384); function main() return #allocated end",
            "main",
        )
        val completed = support.startEntryOk(state)
        if (support.resultString(completed) != "16384") {
            throw AssertionError("allocation probe returned ${completed.value}")
        }
        assertSnapshot(bridge.snapshot(state), "snapshot before close")
        support.assertClosed(support.closeState(state), "close")
        // The surviving contract is idempotent deterministic closure.
        val reclosed = bridge.close(state)
        if (reclosed !is LuaKernelOutcome.Closed) {
            throw AssertionError("terminal close was not idempotent: $reclosed")
        }
    }


    // ------------------------------------------------------------------
    // diagnostics_fixture.rs
    // ------------------------------------------------------------------

    @Test
    fun diagnostics_independent_states_isolate_modules_and_close_independently() {
        val source = loadFixturePlugin("/diagnostics-channel/talkcan-channel.zip")
        val left = support.createState()
        val right = support.createState()

        support.loadProgramImageOk(left, "plugin", mapOf("plugin" to source))
        support.loadProgramImageOk(right, "plugin", mapOf("plugin" to source))

        val leftOut = startupOk(left)
        val rightOut = startupOk(right)
        if (resultJson(leftOut).has("error")) {
            throw AssertionError("left startup errored: ${leftOut.value}")
        }
        if (resultJson(rightOut).has("error")) {
            throw AssertionError("right startup errored: ${rightOut.value}")
        }

        support.assertClosed(support.closeState(left), "close left")
        val rightReadiness = support.invokeCallbackOk(right, "handle_readiness")
        if (!resultJson(rightReadiness).optBoolean("ready", false)) {
            throw AssertionError("right state must remain usable after left close: ${rightReadiness.value}")
        }
        support.assertClosed(support.closeState(right), "close right")
    }

    // ------------------------------------------------------------------
    // json_conformance.rs
    // ------------------------------------------------------------------

    @Test
    fun sibling_states_have_independent_json_policy_and_sentinels() {
        // The Rust engine's `set_json_policy` clamp is a test-only engine hook with
        // no LuaKernelBridge counterpart; the ported contract pins per-state
        // independence of encoding and null-sentinel round-tripping.
        val script = """
            local json = require("talkcan.json")
            return {
              startup = function() end,
              probe = function()
                local text, err = json.encode({ greeting = "hello" })
                return {
                  text = text,
                  err = err,
                  null_roundtrip = json.decode('null') == json.null,
                }
              end,
            }
        """.trimIndent()
        val left = support.createState()
        val right = support.createState()
        support.loadProgramImageOk(left, "entry", mapOf("entry" to script))
        support.loadProgramImageOk(right, "entry", mapOf("entry" to script))

        val leftProbe = support.invokeCallbackOk(left, "probe")
        val leftResult = resultJson(leftProbe)
        if (leftResult.optString("text") != "{\"greeting\":\"hello\"}") {
            throw AssertionError("left encode failed: ${leftProbe.value}")
        }
        if (!leftResult.isNull("err")) {
            throw AssertionError("left encode errored: ${leftProbe.value}")
        }
        if (!leftResult.optBoolean("null_roundtrip", false)) {
            throw AssertionError("left sentinel round trip failed: ${leftProbe.value}")
        }

        val rightProbe = support.invokeCallbackOk(right, "probe")
        val rightResult = resultJson(rightProbe)
        if (rightResult.optString("text") != "{\"greeting\":\"hello\"}") {
            throw AssertionError("right encode must be unaffected by the sibling state: ${rightProbe.value}")
        }
        if (!rightResult.isNull("err")) {
            throw AssertionError("right encode errored: ${rightProbe.value}")
        }
        if (!rightResult.optBoolean("null_roundtrip", false)) {
            throw AssertionError("right sentinel round trip must be independent: ${rightProbe.value}")
        }
        support.assertClosed(support.closeState(left), "close left")
        support.assertClosed(support.closeState(right), "close right")
    }

    // ------------------------------------------------------------------
    // keyboard_conformance.rs
    // ------------------------------------------------------------------

    @Test
    fun close_revokes_yielded_keyboard_operation() {
        val state = support.createState(support.channelConfig())
        installRc(state, """{"keyboardOutput":true}""")
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local kb = require("talkcan.keyboard_output")
                    return {
                      startup = function() end,
                      handle_input = function(event)
                        kb.send_key({ key = "enter", profile = "p" })
                        return { ok = true }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val yielded = assertYielded(invokeInput(state, captureEvent(), "tok"), "keyboard yield")
        val request = requestId(yielded)
        support.assertClosed(support.closeState(state), "close")

        assertStale(bridge.resume(operation(state, yielded), true, """{"status":"delivered"}"""), "late resume")
        support.assertClaimRejected(bridge.claimHostOperation(state, request), "late claim")
    }

    // ------------------------------------------------------------------
    // profile_secret_http_conformance.rs
    // ------------------------------------------------------------------

    @Test
    fun http_close_during_suspension() {
        val state = support.createState(support.channelConfig())
        installRc(state, """{"networkHttp":true}""")
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local http = require("talkcan.http")
                    return {
                      startup = function() end,
                      handle_input = function()
                        local r = http.request({ method = "GET", url = "https://x.example.com" })
                        return { ok = true }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val yielded = assertYielded(invokeInput(state, captureEvent(), "tok"), "http yield")
        support.assertClosed(support.closeState(state), "close during suspension")
        support.assertClosedOrStale(
            bridge.resume(operation(state, yielded), true, """{"status":200,"headers":{},"body":"x"}"""),
            "late resume",
        )
    }

    @Test
    fun http_sibling_state_isolation() {
        val stateA = support.createState(support.channelConfig())
        val stateB = support.createState(support.channelConfig())
        installRc(stateA, """{"networkHttp":true}""")
        installRc(stateB, """{"networkHttp":true}""")

        support.loadProgramImageOk(
            stateA,
            "entry",
            mapOf(
                "entry" to """
                    local http = require("talkcan.http")
                    return {
                      startup = function() end,
                      handle_input = function()
                        local r = http.request({ method = "GET", url = "https://a.example.com" })
                        return { ok = true, status = r.status }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val inputA = assertYielded(invokeInput(stateA, captureEvent(), "tok"), "state A yield")

        support.loadProgramImageOk(
            stateB,
            "entry",
            mapOf(
                "entry" to """
                    return {
                      startup = function() end,
                      handle_input = function()
                        return { ok = true, independent = true }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val inputB = support.assertCompleted(invokeInput(stateB, captureEvent(), "tok"), "state B input")
        if (!resultJson(inputB).optBoolean("independent", false)) {
            throw AssertionError("state B must complete independently: ${inputB.value}")
        }

        // State A is still suspended and unaffected by B.
        val doneA = support.assertCompleted(
            bridge.resume(operation(stateA, inputA), true, """{"status":200,"headers":{},"body":"a"}""", accepting),
            "state A resume",
        )
        if (resultJson(doneA).optInt("status") != 200) {
            throw AssertionError("state A suspension was disturbed by state B: ${doneA.value}")
        }
    }

    // ------------------------------------------------------------------
    // resolver_conformance.rs
    // ------------------------------------------------------------------

    private fun resolverInvocation(moduleSource: String, capabilities: JSONObject = JSONObject()): String =
        support.resolverInvocation(moduleSource, capabilities)

    @Test
    fun resolver_second_invocation_is_rejected() {
        val state = support.createResolverState()
        val source = """
            return { resolve = function() return { choices = {} }, nil end }
        """.trimIndent()
        val first = support.assertCompleted(bridge.invokeResolver(state, resolverInvocation(source)), "first invocation")
        if (resultJson(first).optString("resultKind") != "choices") {
            throw AssertionError("first invocation must publish choices: ${first.value}")
        }
        val second = support.assertCompleted(bridge.invokeResolver(state, resolverInvocation(source)), "second invocation")
        if (resultJson(second).optString("resultKind") == "choices") {
            throw AssertionError("second invocation must not republish choices: ${second.value}")
        }
    }

    @Test
    fun resolver_close_during_suspension() {
        val state = support.createResolverState()
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
            bridge.invokeResolver(state, resolverInvocation(source, JSONObject().put("networkHttp", true))),
            "resolver yield",
        )
        support.assertClosed(support.closeState(state), "close during suspension")
        support.assertClosedOrStale(
            bridge.resume(operation(state, yielded), true, """{"status":200,"headers":{},"body":"x"}"""),
            "late resume",
        )
    }

    @Test
    fun resolver_no_state_leakage_between_separate_states() {
        val stateA = support.createResolverState()
        val stateB = support.createResolverState()
        val sourceA = """
            return { resolve = function() return { choices = { { value = "a", label = "A" } } }, nil end }
        """.trimIndent()
        val sourceB = """
            return { resolve = function() return { choices = { { value = "b", label = "B" } } }, nil end }
        """.trimIndent()
        val outcomeA = support.assertCompleted(bridge.invokeResolver(stateA, resolverInvocation(sourceA)), "resolver A")
        val outcomeB = support.assertCompleted(bridge.invokeResolver(stateB, resolverInvocation(sourceB)), "resolver B")
        val choiceA = resultJson(outcomeA).getJSONArray("choices").getJSONObject(0).optString("value")
        val choiceB = resultJson(outcomeB).getJSONArray("choices").getJSONObject(0).optString("value")
        if (choiceA != "a" || choiceB != "b" || choiceA == choiceB) {
            throw AssertionError("choices leaked across resolver states: A=$choiceA B=$choiceB")
        }
    }

    // ------------------------------------------------------------------
    // runtime_v1_conformance.rs
    // ------------------------------------------------------------------

    @Test
    fun early_image_staging_failure_rolls_back_and_closes() {
        // A failed image stage retains no partial install, the state accepts a
        // valid image afterward, and close stays deterministic.

        val state = support.createState(support.runtimeV1Config())
        assertSnapshot(bridge.snapshot(state), "snapshot before staging")
        val denied = support.loadProgramImage(
            state,
            "entry",
            mapOf("plugin" to "return { startup = function() end }"),
        )
        if (denied is LuaKernelOutcome.Completed || denied is LuaKernelOutcome.Yielded) {
            throw AssertionError("a staging failure must not install an image: $denied")
        }
        // Rollback evidence: the state remains fully usable after a failed stage.
        support.loadProgramImageOk(state, "entry", mapOf("entry" to "return { startup = function() end }"))
        support.invokeCallbackOk(state, "startup")
        val after = assertSnapshot(bridge.snapshot(state), "snapshot after rolled-back staging")
        if (after.stateId != state.stateId.value || after.generation != state.generation.value) {
            throw AssertionError("staging failure disturbed state identity: $after")
        }
        support.assertClosed(support.closeState(state), "close")
    }

    @Test
    fun module_caches_are_isolated_between_independent_state_generations() {
        val image = mapOf(
            "entry" to """
                local mutable = require("plugin.mutable")
                return {
                  startup = function() end,
                  increment = function()
                    mutable.count = mutable.count + 1
                    return { count = mutable.count }
                  end,
                }
            """.trimIndent(),
            "plugin.mutable" to "return { count = 0 }",
        )
        val left = support.createState(support.runtimeV1Config())
        val right = support.createState(support.runtimeV1Config())
        support.loadProgramImageOk(left, "entry", image)
        support.loadProgramImageOk(right, "entry", image)

        val leftFirst = support.invokeCallbackOk(left, "increment")
        if (resultJson(leftFirst).optInt("count") != 1) {
            throw AssertionError("left first increment: ${leftFirst.value}")
        }
        val leftSecond = support.invokeCallbackOk(left, "increment")
        if (resultJson(leftSecond).optInt("count") != 2) {
            throw AssertionError("left second increment: ${leftSecond.value}")
        }
        val rightFirst = support.invokeCallbackOk(right, "increment")
        if (resultJson(rightFirst).optInt("count") != 1) {
            throw AssertionError("a module-table mutation in one state leaked through the per-generation cache: ${rightFirst.value}")
        }
        support.assertClosed(support.closeState(left), "close left")
        support.assertClosed(support.closeState(right), "close right")
    }

    @Test
    fun detached_snapshots_reflect_lifecycle_and_reject_foreign_generations() {
        val state = support.createState(support.runtimeV1Config())

        val fresh = assertSnapshot(bridge.snapshot(state), "fresh snapshot")
        support.loadProgramImageOk(state, "entry", mapOf("entry" to "return { startup = function() end }"))
        val loaded = assertSnapshot(bridge.snapshot(state), "snapshot after load")
        if (loaded.stateId != fresh.stateId || loaded.generation != fresh.generation) {
            throw AssertionError("snapshot identity drifted across load: $fresh -> $loaded")
        }

        support.assertClosed(support.closeState(state), "close")
        assertStale(bridge.snapshot(state), "snapshot after close")

        val other = support.createState(support.runtimeV1Config())
        val foreignGeneration = LuaStateHandle(state.stateId, other.generation)
        assertStale(bridge.snapshot(foreignGeneration), "foreign generation")
        val foreignState = LuaStateHandle(LuaStateId(9_999_999L), state.generation)
        val foreign = bridge.snapshot(foreignState)
        if (foreign !is LuaKernelOutcome.InvalidOwnership) {
            throw AssertionError("foreign state snapshot accepted: $foreign")
        }
        support.assertClosed(support.closeState(other), "close other")
    }

    @Test
    fun generation_close_during_audio_operation_suppresses_late_completion() {
        val state = support.createState(support.runtimeV1Config())
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local s = require("talkcan.synthesis")
                    return {
                      startup = function() end,
                      handle_input = function(event)
                        local syn, err = s.synthesize({text="hello", language="en-US", voice="v"})
                        if err then return {error = "synthesis-failed:" .. err.error} end
                        return {synthesized = tostring(syn)}
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val yielded = assertYielded(invokeInput(state, captureEvent(), "captured-token"), "synthesis yield")
        support.assertClosed(support.closeState(state), "close during suspension")
        assertStale(
            bridge.resume(operation(state, yielded), true, "synthesized:late-after-close", accepting),
            "late completion",
        )
        val reclosed = bridge.close(state)
        if (reclosed !is LuaKernelOutcome.Closed) {
            throw AssertionError("second close must stay idempotent: $reclosed")
        }
    }

    @Test
    fun runtime_instance_id_is_host_owned_and_excludes_generation_identity() {
        val state = support.createState(
            LuaKernelConfig(
                hookInterval = 100,
                instructionBudget = 50_000L,
                maxConcurrentTasks = 1,
                maxTimerSlots = 1,
            ),
        )
        installRc(state, """{"instanceId":"journal-instance","storageFiles":false,"mounts":{}}""")
        support.loadProgramImageOk(
            state,
            "entry",
            mapOf(
                "entry" to """
                    local runtime = require("talkcan.runtime")
                    return {
                      startup = function() end,
                      probe = function()
                        local changed = pcall(function()
                          runtime.INSTANCE_ID = "forged"
                        end)
                        return {
                          instance_id = runtime.INSTANCE_ID,
                          mutation_succeeded = changed,
                        }
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val probe = support.invokeCallbackOk(state, "probe")
        val result = resultJson(probe)
        if (result.optString("instance_id") != "journal-instance" || result.optBoolean("mutation_succeeded", true)) {
            throw AssertionError("instance id must be host-owned and read-only: ${probe.value}")
        }
        support.assertClosed(support.closeState(state), "close")
    }

    // ------------------------------------------------------------------
    // work_conformance.rs
    // ------------------------------------------------------------------

    private fun workResourceContext(): String = """{"workQueue":true,"workQueues":["turns"]}"""

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

    @Test
    fun work_close_during_receive_suspension() {
        val state = support.createState(support.channelConfig())
        installRc(state, workResourceContext())
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
                          if job then job:complete() end
                        end)
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val started = spawnManagedTask(state)
        support.assertClosed(support.closeState(state), "close during receive suspension")
        support.assertClosedOrStale(
            bridge.resume(operation(state, started), true, """{"jobId":"j1","payloadJson":"{\"t\":\"null\"}"}"""),
            "late resume",
        )
    }

    @Test
    fun work_close_during_effect_suspension() {
        val state = support.createState(support.channelConfig())
        installRc(state, workResourceContext())
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
                          job:effect("k", function() return 1 end)
                          job:complete()
                        end)
                      end,
                    }
                """.trimIndent(),
            ),
        )
        val started = spawnManagedTask(state)
        val claim = bridge.claimHostOperation(state, requestId(started))
        val admitted = claim as? HostOperationClaim.Admitted ?: throw AssertionError("WORK_RECEIVE claim rejected: $claim")
        if (admitted.kind != HostOperationKind.WORK_RECEIVE) {
            throw AssertionError("expected WORK_RECEIVE but was ${admitted.kind}")
        }
        val afterReceive = assertYielded(
            bridge.resume(
                operation(state, started),
                true,
                """{"jobId":"j1","payloadJson":"{\"t\":\"null\"}"}""",
                accepting,
            ),
            "resume receive into effect suspension",
        )
        support.assertClosed(support.closeState(state), "close during effect suspension")
        support.assertClaimRejected(bridge.claimHostOperation(state, requestId(afterReceive)), "late effect claim")
    }
}
