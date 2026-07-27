package io.talkcan.lua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LuaKernelOutcomeCodecTest {
    @Test
    fun `decodes every normalized outcome kind with native evidence`() {
        val cases = listOf(
            DecodeCase(
                name = "completed coroutine result and latency",
                json = """{"kind":"completed","stateId":41,"generation":3,"coroutineId":17,"value":"result","elapsedNanos":9123,"luaVersion":"Lua 5.4.8","bindingVersion":"mlua 0.12.0"}""",
                expected = LuaKernelOutcome.Completed(41, 3, 17, "result", 9123, "Lua 5.4.8", "mlua 0.12.0", null),
            ),
            DecodeCase(
                name = "yielded operation owner",
                json = """{"kind":"yielded","stateId":41,"generation":3,"coroutineId":17,"operationId":29,"value":"network"}""",
                expected = LuaKernelOutcome.Yielded(41, 3, 17, 29, "network"),
            ),
            DecodeCase(
                name = "syntax failure diagnostic",
                json = """{"kind":"syntax_failure","stateId":41,"generation":3,"diagnostic":"line 7: unexpected end"}""",
                expected = LuaKernelOutcome.SyntaxFailure(41, 3, "line 7: unexpected end"),
            ),
            DecodeCase(
                name = "validation failure diagnostic",
                json = """{"kind":"validation_failure","stateId":41,"generation":3,"diagnostic":"entrypoint is not a function"}""",
                expected = LuaKernelOutcome.ValidationFailure(41, 3, "entrypoint is not a function"),
            ),
            DecodeCase(
                name = "runtime failure ownership and diagnostic",
                json = """{"kind":"runtime_failure","stateId":41,"generation":3,"diagnostic":"callback failed"}""",
                expected = LuaKernelOutcome.RuntimeFailure(41, 3, "callback failed"),
            ),
            DecodeCase(
                name = "interrupted diagnostic and latency",
                json = """{"kind":"interrupted","stateId":41,"generation":3,"diagnostic":"instruction budget exceeded","elapsedNanos":4567}""",
                expected = LuaKernelOutcome.Interrupted(41, 3, "instruction budget exceeded", 4567),
            ),
            DecodeCase(
                name = "cancelled operation",
                json = """{"kind":"cancelled","stateId":41,"generation":3,"operationId":29}""",
                expected = LuaKernelOutcome.Cancelled(41, 3, 29),
            ),
            DecodeCase(
                name = "invalid ownership diagnostic",
                json = """{"kind":"invalid_ownership","stateId":41,"generation":3,"diagnostic":"operation belongs to state 42"}""",
                expected = LuaKernelOutcome.InvalidOwnership(41, 3, "operation belongs to state 42"),
            ),
            DecodeCase(
                name = "stale terminal completion diagnostic",
                json = """{"kind":"stale","stateId":41,"generation":3,"diagnostic":"operation already completed"}""",
                expected = LuaKernelOutcome.Stale(41, 3, "operation already completed"),
            ),
            DecodeCase(
                name = "snapshot publishes identity versions and elapsed evidence",
                json = """{"kind":"completed","operation":"snapshot","stateId":41,"generation":3,"elapsedNanos":4567,"luaVersion":"Lua 5.4.8","bindingVersion":"mlua 0.12.0"}""",
                expected = LuaKernelOutcome.Snapshot(41, 3, 4567, "Lua 5.4.8", "mlua 0.12.0", null),
            ),
            DecodeCase(
                name = "closed state",
                json = """{"kind":"closed","stateId":41,"generation":3}""",
                expected = LuaKernelOutcome.Closed(41, 3),
            ),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, LuaKernelOutcomeCodec.decode(case.json))
        }
    }

    @Test
    fun `decodes created identity and version evidence`() {
        val outcome = LuaKernelOutcomeCodec.decode(
            """{"kind":"created","stateId":41,"generation":3,"luaVersion":"Lua 5.4.8","bindingVersion":"mlua 0.12.0","topology":"kernel"}""",
        )

        assertTrue("created outcome must retain its concrete kind: $outcome", outcome is LuaKernelOutcome.Created)
        outcome as LuaKernelOutcome.Created
        assertEquals(41, outcome.stateId)
        assertEquals(3, outcome.generation)
        assertEquals("Lua 5.4.8", outcome.luaVersion)
        assertEquals("mlua 0.12.0", outcome.bindingVersion)
    }

    @Test
    fun `completed callback values preserve readiness and input objects as parseable JSON`() {
        val readiness = LuaKernelOutcomeCodec.decode(
            """{"kind":"completed","stateId":41,"generation":3,"value":{"ready":true}}""",
        ) as? LuaKernelOutcome.Completed
            ?: throw AssertionError("Readiness completion must retain its completed outcome")
        val readinessValue = readiness.value
            ?: throw AssertionError("Readiness object must not be discarded by JNI decoding")
        val readinessJson = org.json.JSONObject(readinessValue)
        assertTrue("Readiness callback JSON must retain ready=true.", readinessJson.getBoolean("ready"))

        val input = LuaKernelOutcomeCodec.decode(
            """{"kind":"completed","stateId":41,"generation":3,"value":{"error":{"code":"E_CAPTURE_FAILURE","detail":"processing failed"}}}""",
        ) as? LuaKernelOutcome.Completed
            ?: throw AssertionError("Input completion must retain its completed outcome")
        val inputValue = input.value
            ?: throw AssertionError("Input result object must not be discarded by JNI decoding")
        val error = org.json.JSONObject(inputValue).getJSONObject("error")
        assertEquals("E_CAPTURE_FAILURE", error.getString("code"))
        assertEquals("processing failed", error.getString("detail"))
    }

    @Test
    fun `malformed completed and yielded spawn or log arrays normalize to runtime failure`() {
        val cases = listOf(
            "completed spawnedCoroutines object" to
                """{"kind":"completed","stateId":41,"generation":3,"spawnedCoroutines":{}}""",
            "completed logs object" to
                """{"kind":"completed","stateId":41,"generation":3,"logs":{}}""",
            "completed spawned coroutine string" to
                """{"kind":"completed","stateId":41,"generation":3,"spawnedCoroutines":["17"]}""",
            "completed spawned coroutine fractional" to
                """{"kind":"completed","stateId":41,"generation":3,"spawnedCoroutines":[17.5]}""",
            "completed spawned coroutine overflow" to
                """{"kind":"completed","stateId":41,"generation":3,"spawnedCoroutines":[9223372036854775808]}""",
            "completed spawned coroutine null" to
                """{"kind":"completed","stateId":41,"generation":3,"spawnedCoroutines":[null]}""",
            "completed log number" to
                """{"kind":"completed","stateId":41,"generation":3,"logs":[7]}""",
            "completed log null" to
                """{"kind":"completed","stateId":41,"generation":3,"logs":[null]}""",
            "yielded spawnedCoroutines object" to
                """{"kind":"yielded","stateId":41,"generation":3,"coroutineId":17,"operationId":29,"spawnedCoroutines":{}}""",
            "yielded logs object" to
                """{"kind":"yielded","stateId":41,"generation":3,"coroutineId":17,"operationId":29,"logs":{}}""",
            "yielded spawned coroutine string" to
                """{"kind":"yielded","stateId":41,"generation":3,"coroutineId":17,"operationId":29,"spawnedCoroutines":["17"]}""",
            "yielded spawned coroutine fractional" to
                """{"kind":"yielded","stateId":41,"generation":3,"coroutineId":17,"operationId":29,"spawnedCoroutines":[17.5]}""",
            "yielded spawned coroutine overflow" to
                """{"kind":"yielded","stateId":41,"generation":3,"coroutineId":17,"operationId":29,"spawnedCoroutines":[9223372036854775808]}""",
            "yielded spawned coroutine null" to
                """{"kind":"yielded","stateId":41,"generation":3,"coroutineId":17,"operationId":29,"spawnedCoroutines":[null]}""",
            "yielded log number" to
                """{"kind":"yielded","stateId":41,"generation":3,"coroutineId":17,"operationId":29,"logs":[7]}""",
            "yielded log null" to
                """{"kind":"yielded","stateId":41,"generation":3,"coroutineId":17,"operationId":29,"logs":[null]}""",
        )

        cases.forEach { (name, json) ->
            val outcome = LuaKernelOutcomeCodec.decode(json)
            assertTrue("$name must normalize to RuntimeFailure instead of throwing or accepting malformed native data: $outcome", outcome is LuaKernelOutcome.RuntimeFailure)
        }
    }

    @Test
    fun `malformed unknown missing and type-invalid native outcomes normalize to runtime failure`() {
        val cases = listOf(
            "malformed json" to "not json",
            "unknown kind" to """{"kind":"future_kind","stateId":41,"generation":3}""",
            "missing yielded operation id" to """{"kind":"yielded","stateId":41,"generation":3,"coroutineId":17}""",
            "quoted numeric state id" to """{"kind":"completed","stateId":"41","generation":3}""",
            "fractional state id" to """{"kind":"completed","stateId":41.5,"generation":3}""",
            "retired memory_failure kind" to """{"kind":"memory_failure","stateId":41,"generation":3,"diagnostic":"allocator denied request"}""",
        )

        cases.forEach { (name, json) ->
            val outcome = LuaKernelOutcomeCodec.decode(json)
            assertTrue("$name must not escape JSON decoding: $outcome", outcome is LuaKernelOutcome.RuntimeFailure)
        }
    }

    private data class DecodeCase(
        val name: String,
        val json: String,
        val expected: LuaKernelOutcome,
    )
}
