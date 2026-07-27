package io.talkcan.lua.kernel

import io.talkcan.lua.HostOperationClaim
import io.talkcan.lua.LuaCallbackHandle
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaSpawnAdmission
import io.talkcan.lua.LuaStateGeneration
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaStateId
import io.talkcan.lua.LuaValue
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Shared conformance-test harness for the pure-Kotlin [LuaKernelBridge]
 * implementation. Instantiates the production bridge reflectively by its exact
 * FQCN so the test source set compiles before the class exists; every test
 * fails with an explicit [AssertionError] (never a skip) when the class is
 * absent.
 *
 * Owns every state it creates and closes them all through [closeAll].
 */
internal class KotlinLuaKernelConformanceSupport {

    val bridge: LuaKernelBridge = instantiateBridge()

    private val openStates = mutableListOf<LuaStateHandle>()

    // ------------------------------------------------------------------
    // Reflective instantiation
    // ------------------------------------------------------------------

    private fun instantiateBridge(): LuaKernelBridge {
        val fqcn = "io.talkcan.lua.kernel.KotlinLuaKernelBridge"
        val clazz = try {
            Class.forName(fqcn)
        } catch (e: ClassNotFoundException) {
            throw AssertionError(
                "$fqcn not found on the test classpath. " +
                    "Implement the production class before running module conformance tests.",
                e,
            )
        }
        val instance = try {
            val ctor = clazz.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance()
        } catch (e: Exception) {
            throw AssertionError("Failed to instantiate $fqcn", e)
        }
        if (instance !is LuaKernelBridge) {
            throw AssertionError(
                "$fqcn does not implement LuaKernelBridge; actual type: ${instance::class.qualifiedName}",
            )
        }
        return instance
    }

    // ------------------------------------------------------------------
    // Configuration factories (mirror Rust fixture constants)
    // ------------------------------------------------------------------

    /** conformance.rs / json_conformance.rs default engine. */
    fun defaultConfig(): LuaKernelConfig = LuaKernelConfig(
        hookInterval = 100,
        instructionBudget = 50_000,
    )

    /** runtime_v1_conformance.rs engine(4, 4). */
    fun runtimeV1Config(): LuaKernelConfig = LuaKernelConfig(
        hookInterval = 100,
        instructionBudget = 50_000,
        maxConcurrentTasks = 4,
        maxTimerSlots = 4,
    )

    /** fs / keyboard conformance engine. */
    fun channelConfig(): LuaKernelConfig = LuaKernelConfig(
        hookInterval = 1000,
        instructionBudget = 10_000_000,
        maxConcurrentTasks = 8,
        maxTimerSlots = 8,
    )

    /** resolver_conformance.rs new_resolver. */
    fun resolverConfig(): LuaKernelConfig = LuaKernelConfig(
        hookInterval = 1000,
        instructionBudget = 10_000_000,
    )

    // ------------------------------------------------------------------
    // State lifecycle — support owns every handle
    // ------------------------------------------------------------------

    fun createState(config: LuaKernelConfig = defaultConfig()): LuaStateHandle {
        val outcome = bridge.create(config)
        val created = outcome as? LuaKernelOutcome.Created
            ?: throw AssertionError("create must return Created but was $outcome")
        val handle = LuaStateHandle(
            stateId = LuaStateId(created.stateId),
            generation = LuaStateGeneration(created.generation),
        )
        openStates.add(handle)
        return handle
    }

    fun createResolverState(config: LuaKernelConfig = resolverConfig()): LuaStateHandle {
        val outcome = bridge.createResolver(config)
        val created = outcome as? LuaKernelOutcome.Created
            ?: throw AssertionError("createResolver must return Created but was $outcome")
        val handle = LuaStateHandle(
            stateId = LuaStateId(created.stateId),
            generation = LuaStateGeneration(created.generation),
        )
        openStates.add(handle)
        return handle
    }

    fun closeState(handle: LuaStateHandle): LuaKernelOutcome {
        openStates.remove(handle)
        return bridge.close(handle)
    }

    /** Close every state the support created; safe to call multiple times. */
    fun closeAll() {
        val snapshot = openStates.toList()
        openStates.clear()
        for (handle in snapshot) {
            bridge.close(handle)
        }
    }

    // ------------------------------------------------------------------
    // Legacy single-source load / start
    // ------------------------------------------------------------------

    fun loadSource(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome =
        bridge.load(handle, source, entrypoint)

    fun loadSourceOk(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome.Completed =
        assertCompleted(loadSource(handle, source, entrypoint), "load($entrypoint)")

    fun startEntry(handle: LuaStateHandle): LuaKernelOutcome =
        bridge.start(handle)

    fun startEntryOk(handle: LuaStateHandle): LuaKernelOutcome.Completed =
        assertCompleted(startEntry(handle), "start")

    // ------------------------------------------------------------------
    // Program-image loading
    // ------------------------------------------------------------------

    fun loadProgramImage(
        handle: LuaStateHandle,
        entryPoint: String,
        sourceMap: Map<String, String>,
    ): LuaKernelOutcome = bridge.loadProgramImage(handle, entryPoint, sourceMap)

    fun loadProgramImageOk(
        handle: LuaStateHandle,
        entryPoint: String,
        sourceMap: Map<String, String>,
    ): LuaKernelOutcome.Completed =
        assertCompleted(loadProgramImage(handle, entryPoint, sourceMap), "loadProgramImage($entryPoint)")

    // ------------------------------------------------------------------
    // Callback invocation
    // ------------------------------------------------------------------

    fun invokeCallback(
        handle: LuaStateHandle,
        name: String,
        arguments: LuaValue = LuaValue.Nil,
        spawnAdmission: LuaSpawnAdmission = LuaSpawnAdmission.rejecting(),
    ): LuaKernelOutcome =
        bridge.invokeCallback(handle, LuaCallbackHandle(handle, name), arguments, spawnAdmission)

    fun invokeCallbackOk(
        handle: LuaStateHandle,
        name: String,
        arguments: LuaValue = LuaValue.Nil,
        spawnAdmission: LuaSpawnAdmission = LuaSpawnAdmission.rejecting(),
    ): LuaKernelOutcome.Completed =
        assertCompleted(invokeCallback(handle, name, arguments, spawnAdmission), "invokeCallback($name)")

    fun invokeStartupCallback(
        handle: LuaStateHandle,
        config: LuaValue = LuaValue.Map(
            mapOf(
                "schema_version" to LuaValue.Integer(1L),
                "values" to LuaValue.Map(emptyMap()),
            ),
        ),
        spawnAdmission: LuaSpawnAdmission = LuaSpawnAdmission.rejecting(),
    ): LuaKernelOutcome =
        bridge.invokeStartupCallback(handle, LuaCallbackHandle(handle, "startup"), config, spawnAdmission)

    // ------------------------------------------------------------------
    // Resource context
    // ------------------------------------------------------------------

    fun installResourceContext(handle: LuaStateHandle, resourceContextJson: String) {
        val outcome = bridge.setResourceContext(handle, resourceContextJson)
        assertCompleted(outcome, "setResourceContext")
    }

    // ------------------------------------------------------------------
    // Resolver invocation
    // ------------------------------------------------------------------

    fun invokeResolver(handle: LuaStateHandle, invocationJson: String): LuaKernelOutcome =
        bridge.invokeResolver(handle, invocationJson)

    /** Build the standard resolver invocation JSON (mirrors Rust `invocation()`). */
    fun resolverInvocation(moduleSource: String, capabilities: JSONObject = JSONObject()): String =
        JSONObject().apply {
            put("sourceMap", JSONObject().put("choices.models", moduleSource))
            put("moduleId", "choices.models")
            put("capabilities", capabilities)
            put("request", JSONObject().apply {
                put("schema_version", 1)
                put("resolver", "models")
                put("dependency", JSONObject().apply {
                    put("field", "profile_id")
                    put("value", "p1")
                })
                put("profile", "p1")
            })
        }.toString()

    // ------------------------------------------------------------------
    // JSON result extraction
    // ------------------------------------------------------------------

    /** Parse a normalized result as an object, including a Lua string containing JSON. */
    fun resultObject(outcome: LuaKernelOutcome.Completed): JSONObject {
        val raw = outcome.value ?: return JSONObject()
        return when (val decoded = JSONTokener(raw).nextValue()) {
            is JSONObject -> decoded
            is String -> JSONObject(decoded)
            JSONObject.NULL -> JSONObject()
            else -> throw AssertionError("Completed result is not an object: $raw")
        }
    }

    /** Return [LuaKernelOutcome.Completed.value] as a raw string. */
    fun resultString(outcome: LuaKernelOutcome.Completed): String =
        outcome.value ?: throw AssertionError("Completed outcome carries no value: $outcome")

    /** Decode a normalized scalar result into its Lua-facing textual value. */
    fun resultScalar(outcome: LuaKernelOutcome.Completed): String {
        val decoded = JSONTokener(resultString(outcome)).nextValue()
        return if (decoded === JSONObject.NULL) "null" else decoded.toString()
    }

    /** Parse a nested JSON object field from a completed result. */
    fun resultField(obj: JSONObject, field: String): Any =
        if (!obj.has(field) || obj.isNull(field)) {
            throw AssertionError("result missing field '$field': $obj")
        } else {
            obj.get(field)
        }

    // ------------------------------------------------------------------
    // Outcome-type assertions (fail, never skip)
    // ------------------------------------------------------------------

    fun assertCompleted(outcome: LuaKernelOutcome, context: String = ""): LuaKernelOutcome.Completed {
        if (outcome !is LuaKernelOutcome.Completed) {
            throw AssertionError("${context.ifEmpty { "outcome" }}: expected Completed but was $outcome")
        }
        return outcome
    }

    fun assertSyntaxFailure(outcome: LuaKernelOutcome, context: String = ""): LuaKernelOutcome.SyntaxFailure {
        if (outcome !is LuaKernelOutcome.SyntaxFailure) {
            throw AssertionError("${context.ifEmpty { "outcome" }}: expected SyntaxFailure but was $outcome")
        }
        return outcome
    }

    fun assertValidationFailure(outcome: LuaKernelOutcome, context: String = ""): LuaKernelOutcome.ValidationFailure {
        if (outcome !is LuaKernelOutcome.ValidationFailure) {
            throw AssertionError("${context.ifEmpty { "outcome" }}: expected ValidationFailure but was $outcome")
        }
        return outcome
    }

    fun assertRuntimeFailure(outcome: LuaKernelOutcome, context: String = ""): LuaKernelOutcome.RuntimeFailure {
        if (outcome !is LuaKernelOutcome.RuntimeFailure) {
            throw AssertionError("${context.ifEmpty { "outcome" }}: expected RuntimeFailure but was $outcome")
        }
        return outcome
    }

    fun assertClosed(outcome: LuaKernelOutcome, context: String = ""): LuaKernelOutcome.Closed {
        if (outcome !is LuaKernelOutcome.Closed) {
            throw AssertionError("${context.ifEmpty { "outcome" }}: expected Closed but was $outcome")
        }
        return outcome
    }

    fun assertClosedOrStale(outcome: LuaKernelOutcome, context: String = "") {
        if (outcome !is LuaKernelOutcome.Closed && outcome !is LuaKernelOutcome.Stale) {
            throw AssertionError("${context.ifEmpty { "outcome" }}: expected Closed or Stale but was $outcome")
        }
    }

    /** Assert the claim was rejected (mirrors Rust typed-error expectations). */
    fun assertClaimRejected(claim: HostOperationClaim, context: String = ""): HostOperationClaim.Rejected {
        if (claim !is HostOperationClaim.Rejected) {
            throw AssertionError("${context.ifEmpty { "claim" }}: expected Rejected but was $claim")
        }
        return claim
    }
}
