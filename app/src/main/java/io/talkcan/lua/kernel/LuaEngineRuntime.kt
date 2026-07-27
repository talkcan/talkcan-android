package io.talkcan.lua.kernel

import io.talkcan.lua.JsonEncodingResult
import io.talkcan.lua.JsonParsingResult
import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaOperationId
import io.talkcan.lua.LuaValue
import io.talkcan.lua.LuaValuePolicy
import io.talkcan.lua.LuaSpawnAdmission
import io.talkcan.lua.HostOperationClaim
import io.talkcan.lua.HostOperationKind
import io.talkcan.work.WorkValue
import io.talkcan.work.decodeValue
import io.talkcan.storage.MountRelativePath
import io.talkcan.storage.PathParseResult
import party.iroiro.luajava.Lua
import party.iroiro.luajava.LuaException
import party.iroiro.luajava.JFunction
import party.iroiro.luajava.lua54.Lua54
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicLong

/**
 * Owner-thread Lua execution runtime for one confined [LuaEngineState].
 *
 * This object is constructed inside the engine's [LuaEngineState.runLoop] on the
 * owning thread and is the *only* holder of the [Lua54] interpreter and of any
 * state-local Lua references (registry references to loaded entrypoints). The
 * interpreter and every registry index stay inside this runtime for its whole
 * life; neither crosses the engine's command/reply boundary, so cross-thread Lua
 * access is impossible by construction. Every method MUST run on the owner thread
 * behind [LuaStateThreadGuard].
 *
 * Responsibilities:
 *  - text-only source loading with binary-chunk rejection before any Lua effect;
 *  - syntax / validation / runtime failure classification;
 *  - protected entrypoint and callback invocation (pcall boundaries);
 *  - bounded conversion between the project [LuaValue] tree and LuaJava values,
 *    returning normalized JSON through the existing [LuaValue] codecs.
 *
 * The host-operation round-trip (yield / resume / cancel) is implemented here on
 * top of the trusted bootstrap dispatch helpers; the cooperative scheduler
 * (spawn/defer/sleep) and typed host payloads remain later kernel tasks.
 */
internal class LuaEngineRuntime(
    private val lua: Lua54,
    private val state: LuaEngineState,
) {

    /** Registry reference to the loaded entrypoint function, or [NO_REF]. */
    private var entrypointRef: Int = NO_REF
    private var entrypointStarted = false
    /** Registry reference to the installed package callback table, or [NO_REF]. */
    private var programRef: Int = NO_REF

    /** Owner-thread registry of suspended host-operation coroutines and tokens. */
    private val operations = LuaOperationRegistry(
        terminalCacheCapacity = TERMINAL_CACHE_CAPACITY,
        tombstoneCapacity = OPERATION_TOMBSTONE_CAPACITY,
        liveCapacity = LIVE_COROUTINE_CAPACITY,
    )

    /** Pending background functions captured by trusted spawn/defer callbacks. */
    private val pendingTasks = LinkedHashMap<Long, Int>()

    /** Scheduler context is present only while one host-owned Lua slice executes. */
    private var activeSpawnAdmission: LuaSpawnAdmission? = null
    private var activeSchedulerContext: SchedulerContext? = null
    private var spawnedInSlice: MutableList<Long>? = null
    private var deferredInSlice: MutableList<DeferredTask>? = null
    private val deferredByCoroutine = HashMap<Long, MutableList<DeferredTask>>()

    /** Bounded sleep slots minted by host_prepare_sleep and released on completion. */
    private val sleepDeadlines = LinkedHashMap<Long, Long>()
    private val sleepOperationTokens = HashMap<Long, Long>()
    private val requestOperationIds = HashMap<Long, Long>()
    private val requestOperationClaims = HashMap<Long, HostOperationClaim.Admitted>()
    private var resourceAuthority = ResourceAuthority.EMPTY
    private var authorityGeneration = 0L
    private var resolverInvoked = false
    private val resolverCoroutines = HashSet<Long>()
    private val opaqueValues = HashMap<Long, OpaqueValue>()
    private var nextOpaqueId = 1L
    private var jsonNullOpaqueId: Long? = null
    private val pendingLogs = ArrayList<String>(MAX_LOG_ENTRIES)
    private var profileGrants = emptyMap<String, ProfileGrant>()

    private val hostRequests = LuaHostRequestBroker(
        state = state,
        resolveOpaque = ::resolveOpaqueToken,
        resolveOpaqueKind = ::resolveOpaqueKind,
        resolveWorkOwner = ::resolveWorkOwner,
        eligibilityError = ::hostEligibilityError,
    )

    fun hostHashCallback(): JFunction = JFunction { target ->
        val bytes = (target.toString(1) ?: "").toByteArray(Charsets.UTF_8)
        var hash = 0x811c9dc5L
        for (byte in bytes) {
            hash = ((hash xor (byte.toLong() and 0xffL)) * 0x01000193L) and 0xffffffffL
        }
        target.push("%08x".format(hash))
        1
    }
    fun hostCallCallback(): JFunction = JFunction { target ->
        target.push(false)
        target.push("rejected:${target.toString(1) ?: ""}")
        2
    }
    fun hostRequestCallbacks(): Map<String, JFunction> = hostRequests.callbacks()
    fun hostFsMountCallback(): JFunction = JFunction { target ->
        val id = target.toString(1)
        val error = when {
            !resourceAuthority.storageFiles -> "E_CAPABILITY_UNDECLARED"
            id == null || id !in resourceAuthority.mounts -> "E_INVALID_ARGUMENT"
            resourceAuthority.mounts.getValue(id).status == "unavailable" -> "E_MOUNT_UNAVAILABLE"
            resourceAuthority.mounts.getValue(id).status == "needs-reauthorization" ->
                "E_REAUTHORIZATION_REQUIRED"
            resourceAuthority.mounts.getValue(id).status != "available" -> "E_MOUNT_UNAVAILABLE"
            else -> null
        }
        if (error != null) {
            target.push(false)
            target.push(error)
        } else {
            target.push(true)
            pushOpaque(target, OpaqueMount(id!!, authorityGeneration))
        }
        2
    }

    fun hostWorkOpenCallback(): JFunction = JFunction { target ->
        val queueId = target.toString(1)
        val error = when {
            !resourceAuthority.workQueue -> "E_CAPABILITY_UNDECLARED"
            queueId == null || queueId !in resourceAuthority.workQueues -> "E_NOT_FOUND"
            else -> null
        }
        if (error != null) {
            target.push(false)
            target.push(error)
        } else {
            target.push(true)
            pushOpaque(target, OpaqueQueue(queueId!!))
        }
        2
    }

    fun hostProfilesGetCallback(): JFunction = JFunction { target ->
        val grant = target.toString(1)?.let(profileGrants::get)
        if (grant == null) {
            target.push(false)
            target.push("E_DENIED")
        } else {
            target.push(true)
            pushProfileGrant(target, grant)
        }
        2
    }

    fun hostAudioDescribeCallback(): JFunction = JFunction { target ->
        val pointer = target.getLuaNatives().lua_touserdata(target.getPointer(), 1)
        val audio = opaqueValues[pointer] as? OpaqueAudio
        if (audio == null) {
            target.push(false)
            target.push("E_INVALID_ARGUMENT")
        } else {
            target.push(true)
            pushProjectValue(target, audio.metadata)
        }

        2
    }

    fun hostJsonEncodeCallback(): JFunction = JFunction { target ->
        try {
            when (val encoded = readCallbackValue(target, 1, allowJsonNull = true).toJsonString()) {
                is JsonEncodingResult.Success -> {
                    target.push(encoded.json)
                    1
                }
                is JsonEncodingResult.Failure -> {
                    target.pushNil()
                    target.push(encoded.diagnostic)
                    2
                }
            }
        } catch (_: Throwable) {
            target.pushNil()
            target.push("E_INVALID_VALUE")
            2
        }
    }

    fun hostJsonDecodeCallback(): JFunction = JFunction { target ->
        val source = target.toString(1)
        if (source == null) {
            target.pushNil()
            target.push("E_INVALID_VALUE")
            2
        } else {
            when (val decoded = LuaValue.fromJsonString(source)) {
                is JsonParsingResult.Success -> {
                    pushJsonValue(target, decoded.value)
                    1
                }
                is JsonParsingResult.Failure -> {
                    target.pushNil()
                    target.push(decoded.diagnostic)
                    2
                }
            }
        }
    }

    fun hostOpaqueKindCallback(): JFunction = JFunction { target ->
        val pointer = target.getLuaNatives().lua_touserdata(target.getPointer(), 1)
        val kind = when (opaqueValues[pointer]) {
            is OpaqueAudio -> "audio"
            is OpaqueMount -> "mount"
            is OpaqueQueue -> "queue"
            is OpaqueSecret -> "secret"
            is OpaqueJob -> "job"
            is OpaqueJsonNull -> "json_null"
            else -> null
        }
        if (kind == null) target.pushNil() else target.push(kind)
        1
    }

    fun hostWorkJobPayloadCallback(): JFunction = JFunction { target ->
        val pointer = target.getLuaNatives().lua_touserdata(target.getPointer(), 1)
        val job = opaqueValues[pointer] as? OpaqueJob
        if (job == null) {
            target.pushNil()
            target.push("E_INVALID_VALUE")
            2
        } else {
            pushProjectValue(target, job.payload)
            1
        }
    }

    fun hostLogCallback(): JFunction = JFunction { target ->
        try {
            val payload = readCallbackValue(target, 2)
            require(payload is LuaValue.Map) { "log payload must be a map" }
            val encoded = payload.toJsonString()
            require(encoded is JsonEncodingResult.Success) { "invalid log payload" }
            if (pendingLogs.size < MAX_LOG_ENTRIES) {
                pendingLogs += JSONObject()
                    .put("level", target.toString(1))
                    .put("payload", JSONObject(encoded.json))
                    .toString()
            }
            target.push(true)
            target.pushNil()
        } catch (_: Throwable) {
            target.pushNil()
            target.newTable()
            target.push("E_INVALID_VALUE")
            target.setField(-2, "error")
        }
        2
    }

    fun installResourceContext(resourceContextJson: String): LuaKernelOutcome = try {
        val document = JSONObject(resourceContextJson)
        val mountsObject = document.optJSONObject("mounts") ?: JSONObject()
        val mounts = buildMap {
            val keys = mountsObject.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val mount = mountsObject.getJSONObject(id)
                put(
                    id,
                    ResourceMount(
                        access = mount.optString("access", "read-only"),
                        status = mount.optString("status", "unavailable"),
                    ),
                )
            }
        }
        val queues = buildSet {
            val array = document.optJSONArray("workQueues")
            if (array != null) {
                for (index in 0 until array.length()) add(array.getString(index))
            }
        }
        resourceAuthority = ResourceAuthority(
            instanceId = document.optString("instanceId", ""),
            storageFiles = document.optBoolean("storageFiles", false),
            audioFiles = document.optBoolean("audioFiles", false),
            keyboardOutput = document.optBoolean("keyboardOutput", false),
            secretsRead = document.optBoolean("secretsRead", false),
            networkHttp = document.optBoolean("networkHttp", false),
            workQueue = document.optBoolean("workQueue", false),
            workQueues = queues,
            mounts = mounts,
        )
        authorityGeneration++
        opaqueValues.entries.removeIf { (_, value) -> value is OpaqueQueue }
        completed("null")
    } catch (t: Throwable) {
        validationFailure("resource context: ${t.message ?: t.javaClass.simpleName}")
    }

    fun installProfileGrants(grantsJson: String): LuaKernelOutcome = try {
        val profiles = JSONObject(grantsJson).optJSONArray("profiles")
            ?: throw IllegalArgumentException("missing profiles")
        val next = LinkedHashMap<String, ProfileGrant>()
        for (index in 0 until profiles.length()) {
            val profile = profiles.getJSONObject(index)
            val valuesObject = profile.optJSONObject("values") ?: JSONObject()
            val values = LinkedHashMap<String, LuaValue>()
            val valueKeys = valuesObject.keys()
            while (valueKeys.hasNext()) {
                val field = valueKeys.next()
                values[field] = decodeValue(valuesObject.getJSONObject(field)).toLuaValue()
            }
            val secretsObject = profile.optJSONObject("secretReferences") ?: JSONObject()
            val secrets = LinkedHashMap<String, String>()
            val secretKeys = secretsObject.keys()
            while (secretKeys.hasNext()) {
                val field = secretKeys.next()
                secrets[field] = secretsObject.getString(field)
            }
            val grant = ProfileGrant(
                id = profile.getString("profileId"),
                type = profile.getString("typeLocalId"),
                name = profile.optString("displayName", ""),
                values = values,
                secrets = secrets,
            )
            require(grant.id !in next) { "duplicate profile id" }
            next[grant.id] = grant
        }
        profileGrants = next
        opaqueValues.entries.removeIf { (_, value) -> value is OpaqueSecret }
        completed("null")
    } catch (t: Throwable) {
        validationFailure("profile grants: ${t.message ?: t.javaClass.simpleName}")
    }
    fun invokeResolver(invocationJson: String): LuaKernelOutcome {
        if (!state.resolverMode) return validationFailure("invokeResolver requires resolver mode")
        if (resolverInvoked) {
            return shapeResolverOutcome(validationFailure("resolver already invoked"))
        }
        resolverInvoked = true
        val invocation = try {
            JSONObject(invocationJson)
        } catch (t: Throwable) {
            return validationFailure("invalid resolver invocation: ${t.message}")
        }
        val sourceObject = invocation.optJSONObject("sourceMap")
            ?: return validationFailure("resolver sourceMap must be an object")
        val moduleId = invocation.optString("moduleId", "")
        if (moduleId.isBlank()) return validationFailure("resolver moduleId must be nonempty")
        val sources = LinkedHashMap<String, String>()
        val sourceKeys = sourceObject.keys()
        while (sourceKeys.hasNext()) {
            val name = sourceKeys.next()
            sources[name] = sourceObject.optString(name, "")
        }
        val capabilities = invocation.optJSONObject("capabilities") ?: JSONObject()
        resourceAuthority = ResourceAuthority.EMPTY.copy(
            secretsRead = capabilities.optBoolean("secretsRead", false),
            networkHttp = capabilities.optBoolean("networkHttp", false),
        )
        val load = loadProgramImage(moduleId, sources)
        if (load !is LuaKernelOutcome.Completed) return load
        val request = invocation.opt("request")
        val requestJson = when (request) {
            null, JSONObject.NULL -> "null"
            else -> request.toString()
        }
        val outcome = invokeCallback(
            callbackName = "resolve",
            argumentsJson = requestJson,
            spawnAdmission = LuaSpawnAdmission.rejecting(),
            schedulerContext = SchedulerContext.RESOLVER,
        )
        if (outcome is LuaKernelOutcome.Yielded) {
            resolverCoroutines += outcome.coroutineId
            return outcome
        }
        return shapeResolverOutcome(outcome)
    }

    fun hostSpawnCallback(): JFunction =
        schedulerCaptureCallback(setOf(SchedulerContext.STARTUP, SchedulerContext.MANAGED))

    fun hostDeferCallback(): JFunction = JFunction { callbackLua ->
        val deferred = deferredInSlice
        if (
            deferred == null ||
            (
                activeSchedulerContext != SchedulerContext.INPUT &&
                    activeSchedulerContext != SchedulerContext.MANAGED
            )
        ) {
            callbackLua.push(false)
            callbackLua.push("E_INVALID_CONTEXT")
            2
        } else if (pendingTasks.size + deferred.size >= state.config.maxConcurrentTasks) {
            callbackLua.push(false)
            callbackLua.push("E_BUSY")
            2
        } else {
            val coroutineId = LuaCoroutineId.next()
            callbackLua.pushValue(1)
            deferred += DeferredTask(coroutineId.value, callbackLua.ref())
            callbackLua.push(true)
            callbackLua.push(coroutineId.value)
            2
        }
    }
    fun hostInstanceIdCallback(): JFunction = JFunction { callbackLua ->
        callbackLua.push(resourceAuthority.instanceId)
        1
    }

    fun hostAcknowledgeSpawnContextCallback(): JFunction = JFunction { 0 }

    fun hostPrepareSleepCallback(): JFunction = JFunction { callbackLua ->
        when {
            activeSchedulerContext != SchedulerContext.MANAGED -> {
                callbackLua.push(false)
                callbackLua.push("E_INVALID_CONTEXT")
            }
            sleepDeadlines.size >= state.config.maxTimerSlots -> {
                callbackLua.push(false)
                callbackLua.push("E_BUSY")
            }
            else -> {
                val seconds = callbackLua.toNumber(1)
                val token = NEXT_SLEEP_TOKEN.getAndIncrement()
                val delayNanos = (seconds * 1_000_000_000.0).toLong().coerceAtLeast(0L)
                val deadline = System.nanoTime().let { now ->
                    if (Long.MAX_VALUE - now < delayNanos) Long.MAX_VALUE else now + delayNanos
                }
                sleepDeadlines[token] = deadline
                callbackLua.push(true)
                callbackLua.push(token)
            }
        }
        2
    }

    private fun schedulerCaptureCallback(
        allowedContexts: Set<SchedulerContext>,
    ): JFunction = JFunction { callbackLua ->
        val admission = activeSpawnAdmission
        if (admission == null || activeSchedulerContext !in allowedContexts) {
            callbackLua.push(false)
            callbackLua.push("E_INVALID_CONTEXT")
            2
        } else if (pendingTasks.size >= state.config.maxConcurrentTasks) {
            callbackLua.push(false)
            callbackLua.push("E_BUSY")
            2
        } else {
            val coroutineId = LuaCoroutineId.next()
            when (admission.admitTask(coroutineId.value)) {
                0 -> {
                    callbackLua.pushValue(1)
                    val functionRef = callbackLua.ref()
                    pendingTasks[coroutineId.value] = functionRef
                    spawnedInSlice?.add(coroutineId.value)
                    callbackLua.push(true)
                    callbackLua.push(coroutineId.value)
                    2
                }
                2 -> {
                    callbackLua.push(false)
                    callbackLua.push("E_BUSY")
                    2
                }
                else -> {
                    callbackLua.push(false)
                    callbackLua.push("E_INVALID_CONTEXT")
                    2
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Legacy load / start seams
    // ------------------------------------------------------------------

    /**
     * Compile and run one text source as the main chunk, then validate that the
     * named global [entrypoint] is a function and store a registry reference to
     * it. Binary chunks are rejected before any Lua effect. Syntax errors become
     * [LuaKernelOutcome.SyntaxFailure]; a missing or non-function entrypoint
     * becomes [LuaKernelOutcome.ValidationFailure]; an error raised while running
     * the top-level chunk becomes [LuaKernelOutcome.RuntimeFailure]. On success
     * the previous entrypoint reference is replaced atomically.
     */
    fun load(source: String, entrypoint: String): LuaKernelOutcome {
        resetExecutionBudget()
        if (source.isNotEmpty() && source[0] == BINARY_ESCAPE) {
            return syntaxFailure("binary Lua chunk rejected; only text source is accepted")
        }
        val base = lua.getTop()
        return try {
            lua.checkStack(4)
            lua.load(source) // compile only; pushes the chunk function (syntax error throws SYNTAX)
            lua.pCall(0, 0) // execute the main chunk so its globals are defined
            lua.getGlobal(entrypoint)
            if (!lua.isFunction(lua.getTop())) {
                return validationFailure("entrypoint '$entrypoint' is not a function")
            }
            releaseEntrypoint()
            entrypointRef = lua.ref() // pops the validated function into the registry
            entrypointStarted = false
            completed(null)
        } catch (t: Throwable) {
            classify(t)
        } finally {
            lua.setTop(base)
        }
    }

    fun loadProgramImage(
        entryPoint: String,
        sourceMap: Map<String, String>,
    ): LuaKernelOutcome {
        resetExecutionBudget()
        if (!MODULE_NAME.matches(entryPoint) ||
            sourceMap.isEmpty() ||
            sourceMap.size > MAX_IMAGE_MODULES ||
            sourceMap.keys.any { !MODULE_NAME.matches(it) } ||
            sourceMap.keys.any { it == "talkcan" || it.startsWith("talkcan.") } ||
            entryPoint !in sourceMap ||
            sourceMap.values.any { it.isNotEmpty() && it[0] == BINARY_ESCAPE } ||
            sourceMap.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } >
                MAX_IMAGE_SOURCE_BYTES
        ) {
            return validationFailure("invalid source-only program image")
        }
        val base = lua.getTop()
        return try {
            lua.checkStack(8)
            pushTalkcanHelper("_install_image")
            lua.push(entryPoint)
            lua.newTable()
            val sourcesIdx = lua.getTop()
            for ((name, source) in sourceMap) {
                lua.push(source)
                lua.setField(sourcesIdx, name)
            }
            lua.pCall(2, 1)
            if (!lua.isTable(lua.getTop())) {
                validationFailure("program entrypoint must return a callback table")
            } else if (state.resolverMode && !isValidResolverProgram(lua.getTop())) {
                validationFailure("resolver module must export exactly resolve")
            } else if (!state.resolverMode) {
                val callbackNames = mutableListOf<String>()
                val validationError =
                    callbackProgramValidationError(lua.getTop(), callbackNames)
                if (validationError != null) {
                    validationFailure(validationError)
                } else {
                    callbackNames.sort()
                    releaseProgramImage()
                    programRef = lua.ref()
                    completed(JSONArray(callbackNames).toString())
                }
            } else {
                releaseProgramImage()
                programRef = lua.ref()
                completed(null)
            }
        } catch (t: Throwable) {
            classify(t)
        } finally {
            lua.setTop(base)
        }
    }

    private fun callbackProgramValidationError(
        index: Int,
        callbackNames: MutableList<String>,
    ): String? {
        if (lua.getMetatable(index) != 0) {
            lua.pop(1)
            return "callback table has metatable"
        }
        val absoluteIndex = lua.toAbsoluteIndex(index)
        var hasStartup = false
        lua.pushNil()
        while (lua.next(absoluteIndex) != 0) {
            val key = lua.toString(-2)
            if (key in CALLBACK_FIELDS) {
                if (!lua.isFunction(-1)) {
                    val type = if (
                        lua.type(-1) == Lua.LuaType.NUMBER &&
                        lua.isInteger(-1)
                    ) {
                        "integer"
                    } else {
                        lua.type(-1).name.lowercase()
                    }
                    lua.pop(1)
                    return "expected function for callback '$key', got $type"
                }
                if (key == "startup") hasStartup = true
                callbackNames += key
            }
            lua.pop(1)
        }
        return if (hasStartup) null else "required callback 'startup' is missing"
    }

    private fun isValidResolverProgram(index: Int): Boolean {
        var valid = false
        var fields = 0
        lua.pushNil()
        while (lua.next(index) != 0) {
            fields++
            val keyIndex = lua.getTop() - 1
            val valueIndex = lua.getTop()
            if (
                lua.type(keyIndex) == Lua.LuaType.STRING &&
                lua.toString(keyIndex) == "resolve" &&
                lua.isFunction(valueIndex)
            ) {
                valid = true
            }
            lua.pop(1)
        }
        return valid && fields == 1
    }

    /**
     * Invoke the loaded entrypoint with no arguments inside a fresh hooked
     * coroutine through the trusted dispatch helper. A synchronous return becomes
     * [LuaKernelOutcome.Completed] with the first result as normalized JSON; a
     * NUL-prefixed operation-protocol yield suspends the coroutine and becomes
     * [LuaKernelOutcome.Yielded]; any other yield becomes
     * [LuaKernelOutcome.RuntimeFailure] (`E_INVALID_YIELD`); a protected Lua
     * error becomes [LuaKernelOutcome.RuntimeFailure] (or
     * [LuaKernelOutcome.Interrupted]) and leaves the interpreter usable.
     */
    fun start(): LuaKernelOutcome {
        resetExecutionBudget()
        if (entrypointRef == NO_REF) {
            return validationFailure("no entrypoint has been loaded")
        }
        if (entrypointStarted) {
            return validationFailure("entrypoint already started")
        }
        entrypointStarted = true
        val base = lua.getTop()
        return try {
            lua.checkStack(8)
            pushDispatchCall() // push talkcan._dispatch_call
            lua.refGet(entrypointRef) // push the entrypoint function as the argument
            lua.pCall(1, 3) // _dispatch_call(fn) -> kind, co, value
            handleDispatchEnvelope(base)
        } catch (t: Throwable) {
            classify(t)
        } finally {
            lua.setTop(base)
        }
    }

    // ------------------------------------------------------------------
    // Synchronous callback seam
    // ------------------------------------------------------------------

    /**
     * Invoke a global callback by [callbackName] inside a fresh hooked coroutine
     * through the trusted dispatch helper with the decoded [argumentsJson] as its
     * single argument. A synchronous return becomes [LuaKernelOutcome.Completed]
     * with the first result as normalized JSON; a NUL-prefixed operation-protocol
     * yield suspends the coroutine and becomes [LuaKernelOutcome.Yielded]; any
     * other yield becomes [LuaKernelOutcome.RuntimeFailure] (`E_INVALID_YIELD`);
     * a protected Lua error becomes [LuaKernelOutcome.RuntimeFailure].
     * Argument-decoding and lookup/type problems become
     * [LuaKernelOutcome.ValidationFailure] before any Lua effect.
     */
    fun invokeCallback(
        callbackName: String,
        argumentsJson: String,
        spawnAdmission: LuaSpawnAdmission,
        schedulerContext: SchedulerContext,
        capturedAudioToken: String? = null,
    ): LuaKernelOutcome {
        resetExecutionBudget()
        val arguments = when (val parsed = LuaValue.fromJsonString(argumentsJson)) {
            is JsonParsingResult.Success -> parsed.value
            is JsonParsingResult.Failure ->
                return validationFailure("callback '$callbackName' arguments: ${parsed.diagnostic}")
        }
        return schedulerSlice(spawnAdmission, schedulerContext) {
            val base = lua.getTop()
            try {
                lua.checkStack(8)
                pushDispatchCall()
                pushCallback(callbackName)
                if (!lua.isFunction(lua.getTop())) {
                    validationFailure("callback '$callbackName' is not a function")
                } else {
                    pushProjectValue(arguments)
                    if (capturedAudioToken != null && lua.isTable(lua.getTop())) {
                        val argumentIdx = lua.getTop()
                        val metadata = (arguments as? LuaValue.Map)
                            ?.pairs
                            ?.get("metadata") as? LuaValue.Map
                            ?: LuaValue.Map(emptyMap())
                        pushOpaque(
                            OpaqueAudio(
                                capturedAudioToken,
                                metadata,
                                OpaqueValueKind.AUDIO_RECORDING,
                            ),
                        )
                        lua.setField(argumentIdx, "audio")
                    }
                    lua.pCall(2, 3)
                    handleDispatchEnvelope(base)
                }
            } catch (t: Throwable) {
                classify(t)
            } finally {
                lua.setTop(base)
            }
        }
    }

    fun startCoroutine(
        coroutineId: LuaCoroutineId,
        spawnAdmission: LuaSpawnAdmission,
    ): LuaKernelOutcome {
        val functionRef = pendingTasks.remove(coroutineId.value)
            ?: return staleOperation("coroutine is not pending")
        resetExecutionBudget()
        return schedulerSlice(spawnAdmission, SchedulerContext.MANAGED) {
            val base = lua.getTop()
            try {
                lua.checkStack(8)
                pushDispatchCall()
                lua.refGet(functionRef)
                lua.unref(functionRef)
                lua.pCall(1, 3)
                handleDispatchEnvelope(base)
            } catch (t: Throwable) {
                classify(t)
            } finally {
                lua.setTop(base)
            }
        }
    }

    private inline fun schedulerSlice(
        spawnAdmission: LuaSpawnAdmission,
        schedulerContext: SchedulerContext,
        carriedDeferred: MutableList<DeferredTask>? = null,
        block: () -> LuaKernelOutcome,
    ): LuaKernelOutcome {
        check(activeSpawnAdmission == null) { "nested scheduler slice" }
        val spawned = mutableListOf<Long>()
        val deferred = carriedDeferred ?: mutableListOf()
        pendingLogs.clear()
        activeSpawnAdmission = spawnAdmission
        activeSchedulerContext = schedulerContext
        spawnedInSlice = spawned
        deferredInSlice = deferred
        return try {
            val rawResult = block()
            val raw =
                if (
                    schedulerContext == SchedulerContext.SOS &&
                    rawResult is LuaKernelOutcome.Completed &&
                    rawResult.value == "null"
                ) {
                    rawResult.copy(value = null)
                } else {
                    rawResult
                }
            val outcome =
                if (
                    schedulerContext == SchedulerContext.SOS &&
                    raw is LuaKernelOutcome.Completed &&
                    !isValidSosTerminal(raw.value)
                ) {
                    runtimeFailure("callback contract violation: SOS must return nil or a table")
                } else {
                    raw
                }
            when (outcome) {
                is LuaKernelOutcome.Completed -> {
                    if (isSuccessfulCallbackTerminal(outcome.value)) {
                        commitDeferred(deferred, spawnAdmission, spawned)
                    } else {
                        releaseDeferred(deferred)
                    }
                }
                is LuaKernelOutcome.Yielded -> {
                    if (deferred.isNotEmpty()) {
                        deferredByCoroutine.getOrPut(outcome.coroutineId) { mutableListOf() }
                            .addAll(deferred)
                        deferred.clear()
                    }
                }
                else -> releaseDeferred(deferred)
            }
            val logs = pendingLogs.takeIf { it.isNotEmpty() }?.toList()
            when (outcome) {
                is LuaKernelOutcome.Completed ->
                    outcome.copy(
                        spawnedCoroutines = spawned.takeIf { it.isNotEmpty() },
                        logs = logs,
                    )
                is LuaKernelOutcome.Yielded ->
                    outcome.copy(
                        spawnedCoroutines = spawned.takeIf { it.isNotEmpty() },
                        logs = logs,
                    )
                else -> outcome
            }
        } finally {
            deferredInSlice = null
            spawnedInSlice = null
            activeSpawnAdmission = null
            activeSchedulerContext = null
        }
    }

    private fun commitDeferred(
        deferred: MutableList<DeferredTask>,
        admission: LuaSpawnAdmission,
        spawned: MutableList<Long>,
    ) {
        for (task in deferred) {
            if (
                pendingTasks.size < state.config.maxConcurrentTasks &&
                admission.admitTask(task.coroutineId) == 0
            ) {
                pendingTasks[task.coroutineId] = task.functionRef
                spawned += task.coroutineId
            } else {
                lua.unref(task.functionRef)
            }
        }
        deferred.clear()
    }

    private fun releaseDeferred(deferred: MutableList<DeferredTask>) {
        deferred.forEach { lua.unref(it.functionRef) }
        deferred.clear()
    }

    private fun isSuccessfulCallbackTerminal(value: String?): Boolean =
        try {
            value == null || !JSONObject(value).has("error")
        } catch (_: Throwable) {
            true
        }

    private fun isValidSosTerminal(value: String?): Boolean {
        if (value == null || value == "null") return true
        return try {
            JSONObject(value)
            true
        } catch (_: Throwable) {
            try {
                JSONArray(value)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }


    // ------------------------------------------------------------------
    // Owner-thread reference lifecycle
    // ------------------------------------------------------------------

    private fun resetExecutionBudget() {
        state.resetInterruptRequest()
        lua.run("talkcan._reset_instruction_budget()")
    }

    /** Release every registry reference and clear the operation registry; called
     *  on the owner thread before the interpreter closes. */
    fun releaseReferences() {
        releaseEntrypoint()
        releaseProgramImage()
        operations.releaseAll { threadRef -> runCatching { lua.unref(threadRef) } }
        pendingTasks.values.forEach { functionRef -> runCatching { lua.unref(functionRef) } }
        pendingTasks.clear()
        deferredByCoroutine.values.forEach { tasks ->
            tasks.forEach { task -> runCatching { lua.unref(task.functionRef) } }
        }
        deferredByCoroutine.clear()
        sleepDeadlines.clear()
        sleepOperationTokens.clear()
        requestOperationIds.clear()
        requestOperationClaims.clear()
    }

    private fun releaseEntrypoint() {
        if (entrypointRef != NO_REF) {
            runCatching { lua.unref(entrypointRef) }
            entrypointRef = NO_REF
        }
    }

    private fun releaseProgramImage() {
        if (programRef != NO_REF) {
            runCatching { lua.unref(programRef) }
            programRef = NO_REF
        }
    }

    private fun pushCallback(callbackName: String) {
        if (programRef == NO_REF) {
            lua.getGlobal(callbackName)
            return
        }
        lua.refGet(programRef)
        val tableIdx = lua.getTop()
        lua.getField(tableIdx, callbackName)
        lua.remove(tableIdx)
    }

    // ------------------------------------------------------------------
    // Host-operation round-trip (yield / resume / cancel)
    // ------------------------------------------------------------------

    /**
     * Resume a suspended operation exactly once. Terminal duplicates echo the
     * cached terminal outcome; tombstoned (evicted or superseded) tokens resolve
     * to [LuaKernelOutcome.Stale]; unknown tokens resolve to
     * [LuaKernelOutcome.InvalidOwnership] — all without entering Lua. A live
     * token whose owning coroutine does not match the handle is rejected as
     * invalid ownership before any Lua entry. A live, correctly-addressed token
     * resumes its coroutine with the normalized ([success], [value]) pair: a
     * synchronous return completes it, a protocol yield suspends it again under a
     * fresh operation identity, and any other yield or protected error terminates
     * it.
     */
    fun resume(
        operation: LuaOperationHandle,
        success: Boolean,
        value: String,
        spawnAdmission: LuaSpawnAdmission,
    ): LuaKernelOutcome =
        when (val resolution = operations.resolve(operation.operationId.value)) {
            is LuaOperationRegistry.Resolution.Terminal -> resolution.outcome
            is LuaOperationRegistry.Resolution.Tombstoned ->
                staleOperation("operation terminal outcome was evicted or token is no longer live")
            is LuaOperationRegistry.Resolution.Unknown ->
                invalidOperation("unknown operation token")
            is LuaOperationRegistry.Resolution.Live ->
                if (resolution.record.coroutineId != operation.coroutineId.value) {
                    invalidOperation("operation belongs to a different coroutine")
                } else {
                    schedulerSlice(
                        spawnAdmission,
                        resolution.record.schedulerContext,
                        deferredByCoroutine.remove(resolution.record.coroutineId),
                    ) {
                        val requestClaim =
                            releaseHostRequestOperation(operation.operationId.value)
                        releaseSleepOperation(operation.operationId.value)
                        resetExecutionBudget()
                        resumeCoroutine(resolution.record, success, value, requestClaim)
                    }
                }
        }

    /**
     * Cancel a suspended operation without re-entering Lua. The coroutine
     * reference is released (abandoned to the garbage collector) and a terminal
     * [LuaKernelOutcome.Cancelled] is cached so duplicate cancellations or
     * completions echo the exact outcome. Terminal duplicates, tombstones, and
     * unknown tokens resolve exactly as in [resume].
     */
    fun cancel(operation: LuaOperationHandle): LuaKernelOutcome =
        when (val resolution = operations.resolve(operation.operationId.value)) {
            is LuaOperationRegistry.Resolution.Terminal -> resolution.outcome
            is LuaOperationRegistry.Resolution.Tombstoned ->
                staleOperation("operation terminal outcome was evicted or token is no longer live")
            is LuaOperationRegistry.Resolution.Unknown ->
                invalidOperation("unknown operation token")
            is LuaOperationRegistry.Resolution.Live ->
                if (resolution.record.coroutineId != operation.coroutineId.value) {
                    invalidOperation("operation belongs to a different coroutine")
                } else {
                    deferredByCoroutine.remove(resolution.record.coroutineId)
                        ?.let(::releaseDeferred)
                    releaseHostRequestOperation(operation.operationId.value)
                    releaseSleepOperation(operation.operationId.value)
                    val outcome = cancelledOutcome(operation.operationId.value)
                    finalizeTerminal(resolution.record, outcome)
                    outcome
                }
        }

    /** Terminalize every suspended continuation after an external interrupt. */
    fun interruptSuspended(): LuaKernelOutcome {
        val outcome = interrupted("interrupt requested")
        for (record in operations.liveRecords()) {
            deferredByCoroutine.remove(record.coroutineId)?.let(::releaseDeferred)
            releaseHostRequestOperation(record.currentOperationId)
            releaseSleepOperation(record.currentOperationId)
            finalizeTerminal(record, outcome)
        }
        state.resetInterruptRequest()
        return outcome
    }

    /**
     * Resume [record]'s coroutine through the trusted dispatch helper with the
     * normalized ([success], [value]) pair and classify the resulting envelope.
     * Any unexpected host-side failure abandons the coroutine, caches the
     * classified terminal outcome, and returns it.
     */
    private fun resumeCoroutine(
        record: LuaOperationRegistry.SuspendedCoroutine,
        success: Boolean,
        value: String,
        requestClaim: HostOperationClaim.Admitted?,
    ): LuaKernelOutcome {
        val base = lua.getTop()
        return try {
            lua.checkStack(8)
            pushDispatchResume() // push talkcan._dispatch_resume
            lua.refGet(record.threadRef) // push the suspended coroutine
            pushHostCompletion(success, value, requestClaim)
            lua.pCall(3, 3) // _dispatch_resume(co, ok, value) -> kind, co, value
            val outcome = handleResumeEnvelope(base, record)
            if (record.coroutineId !in resolverCoroutines) {
                outcome
            } else if (outcome is LuaKernelOutcome.Yielded) {
                resolverCoroutines += outcome.coroutineId
                outcome
            } else {
                resolverCoroutines -= record.coroutineId
                shapeResolverOutcome(outcome)
            }
        } catch (t: Throwable) {
            val outcome = classify(t)
            finalizeTerminal(record, outcome)
            outcome
        } finally {
            lua.setTop(base)
        }
    }

    private fun pushHostCompletion(
        success: Boolean,
        value: String,
        claim: HostOperationClaim.Admitted?,
    ) {
        val kind = claim?.kind
        if (!success && kind != null) {
            lua.push(false)
            lua.push(normalizeHostError(kind, value))
            return
        }
        if (!success || kind == null) {
            lua.push(success)
            lua.push(value)
            return
        }
        lua.push(true)
        when (kind) {
            HostOperationKind.TRANSCRIBE -> lua.push(value)
            HostOperationKind.SYNTHESIZE ->
                pushOpaque(
                    OpaqueAudio(
                        value,
                        LuaValue.Map(emptyMap()),
                        OpaqueValueKind.AUDIO_SYNTHESIZED,
                    ),
                )
            HostOperationKind.PLAYBACK -> {
                lua.newTable()
                lua.push("scheduled")
                lua.setField(-2, "status")
            }
            HostOperationKind.SECRET_READ -> {
                val parsed = (LuaValue.fromJsonString(value) as? JsonParsingResult.Success)
                    ?.value as? LuaValue.Map
                val plaintext = parsed?.pairs?.get("plaintext") as? LuaValue.StringValue
                if (plaintext == null) {
                    lua.pop(1)
                    lua.push(false)
                    lua.push("E_STORAGE")
                } else {
                    lua.push(plaintext.value)
                }
            }
            HostOperationKind.AUDIO_OPEN -> pushOpenedAudio(value)
            HostOperationKind.WORK_RECEIVE -> pushWorkJob(claim.queue, value)
            HostOperationKind.WORK_BEGIN_EFFECT -> pushWorkEffectBegin(value)
            HostOperationKind.WORK_SUBMIT,
            HostOperationKind.WORK_COMMIT_EFFECT,
            HostOperationKind.WORK_COMPLETE,
            HostOperationKind.WORK_FAIL,
            -> lua.push(true)
            else -> {
                val parsed = LuaValue.fromJsonString(value)
                if (parsed is JsonParsingResult.Success) {
                    pushProjectValue(parsed.value)
                } else {
                    lua.pop(1)
                    lua.push(false)
                    lua.push(if (isFilesystemKind(kind)) "E_IO" else "E_HOST_FAILURE")
                }
            }
        }
    }

    private fun pushOpenedAudio(value: String) {
        val parsed = (LuaValue.fromJsonString(value) as? JsonParsingResult.Success)
            ?.value as? LuaValue.Map
        val token = parsed?.pairs?.get("token") as? LuaValue.StringValue
        if (token == null) {
            lua.pop(1)
            lua.push(false)
            lua.push("E_HOST_FAILURE")
            return
        }
        val metadata = parsed.pairs["metadata"] as? LuaValue.Map ?: LuaValue.Map(emptyMap())
        pushOpaque(OpaqueAudio(token.value, metadata, OpaqueValueKind.AUDIO_RECORDING))
    }

    private fun pushWorkJob(queue: String?, value: String) {
        val document = try {
            JSONObject(value)
        } catch (_: Throwable) {
            null
        }
        val jobId = document?.optString("jobId", "")?.takeIf(String::isNotBlank)
        val payloadJson = document?.optString("payloadJson", "")?.takeIf(String::isNotBlank)
        val payload = try {
            payloadJson?.let { decodeValue(JSONObject(it)).toLuaValue() }
        } catch (_: Throwable) {
            null
        }
        if (queue == null || jobId == null || payload == null) {
            lua.pop(1)
            lua.push(false)
            lua.push("E_STORE")
        } else {
            pushOpaque(OpaqueJob(queue, jobId, payload))
        }
    }

    private fun pushWorkEffectBegin(value: String) {
        val document = try {
            JSONObject(value)
        } catch (_: Throwable) {
            null
        }
        if (document == null) {
            lua.pop(1)
            lua.push(false)
            lua.push("E_STORE")
            return
        }
        lua.newTable()
        val tableIndex = lua.getTop()
        lua.push(document.optBoolean("replay", false))
        lua.setField(tableIndex, "replay")
        lua.push(document.optBoolean("resultOk", true))
        lua.setField(tableIndex, "result_ok")
        val resultJson = document.optString("resultJson", "")
        if (resultJson.isNotBlank()) {
            try {
                pushProjectValue(decodeValue(JSONObject(resultJson)).toLuaValue())
                lua.setField(tableIndex, "result")
            } catch (_: Throwable) {
                // A malformed replay result remains absent, matching the v1 contract.
            }
        }
    }

    private fun normalizeHostError(kind: HostOperationKind, value: String): String {
        val common = setOf(
            "E_INVALID_ARGUMENT", "E_INVALID_VALUE", "E_INVALID_CONTEXT",
            "E_CAPABILITY_UNDECLARED", "E_UNAVAILABLE", "E_BUSY", "E_TIMEOUT",
            "E_CANCELLED", "E_CLOSED", "E_STALE",
        )
        if (value in common) return value
        return when {
            isFilesystemKind(kind) -> if (value in setOf(
                "E_INVALID_PATH", "E_NOT_FOUND", "E_EXISTS", "E_NOT_EMPTY",
                "E_READ_ONLY", "E_REAUTHORIZATION_REQUIRED", "E_MOUNT_UNAVAILABLE",
                "E_UNSUPPORTED", "E_IO",
            )) value else "E_IO"
            kind == HostOperationKind.SECRET_READ -> if (value == "E_STORAGE") value else "E_STORAGE"
            kind == HostOperationKind.HTTP_REQUEST -> if (value == "E_TRANSPORT") value else "E_TRANSPORT"
            isWorkKind(kind) -> if (value in setOf(
                "E_NOT_FOUND", "E_CONFLICT", "E_INDETERMINATE", "E_STORE",
            )) value else "E_STORE"
            else -> if (value in setOf("E_UNSUPPORTED", "E_HOST_FAILURE")) value else "E_HOST_FAILURE"
        }
    }

    private fun isFilesystemKind(kind: HostOperationKind): Boolean = kind in setOf(
        HostOperationKind.FS_MKDIR,
        HostOperationKind.FS_STAT,
        HostOperationKind.FS_LIST,
        HostOperationKind.FS_READ_TEXT,
        HostOperationKind.FS_WRITE_TEXT,
        HostOperationKind.FS_REMOVE,
    )

    private fun isWorkKind(kind: HostOperationKind): Boolean = kind in setOf(
        HostOperationKind.WORK_SUBMIT,
        HostOperationKind.WORK_RECEIVE,
        HostOperationKind.WORK_BEGIN_EFFECT,
        HostOperationKind.WORK_COMMIT_EFFECT,
        HostOperationKind.WORK_COMPLETE,
        HostOperationKind.WORK_FAIL,
    )

    /**
     * Interpret a trusted dispatch envelope `(kind, co, value)` left on the stack
     * after a `pCall(_, 3)`, where [base] is the stack top captured before the
     * call. Used for the initial entrypoint/callback invocation, where a yield
     * mints a brand-new coroutine identity.
     */
    private fun handleDispatchEnvelope(base: Int): LuaKernelOutcome {
        val kindIdx = base + 1
        val valueIdx = base + 3
        return when (lua.toString(kindIdx)) {
            DISPATCH_COMPLETED -> completed(convertResult(valueIdx))
            DISPATCH_ERROR -> classifyCoroutineError(lua.toString(valueIdx))
            DISPATCH_YIELDED -> suspendInitial(base + 2, valueIdx)
            else -> runtimeFailure("internal dispatch envelope error: unrecognized kind")
        }
    }

    /**
     * Interpret a dispatch envelope produced by resuming [record]'s coroutine. A
     * synchronous return completes the operation; a protocol yield suspends it
     * again under a fresh operation identity; any other yield or protected error
     * terminates it.
     */
    private fun handleResumeEnvelope(
        base: Int,
        record: LuaOperationRegistry.SuspendedCoroutine,
    ): LuaKernelOutcome {
        val kindIdx = base + 1
        val valueIdx = base + 3
        return when (lua.toString(kindIdx)) {
            DISPATCH_COMPLETED -> {
                val outcome = completed(convertResult(valueIdx))
                finalizeTerminal(record, outcome)
                outcome
            }
            DISPATCH_ERROR -> {
                val outcome = classifyCoroutineError(lua.toString(valueIdx))
                finalizeTerminal(record, outcome)
                outcome
            }
            DISPATCH_YIELDED -> suspendSequential(record, valueIdx)
            else -> {
                val outcome = runtimeFailure("internal dispatch envelope error: unrecognized kind")
                finalizeTerminal(record, outcome)
                outcome
            }
        }
    }

    /**
     * Suspend a freshly started coroutine that yielded during its initial
     * invocation. The yielded value must carry the NUL-prefixed operation
     * protocol; anything else is a raw-yield boundary violation. Mints a fresh
     * global coroutine identity and operation token and keeps a registry
     * reference to the suspended thread on the owner thread.
     */
    private fun suspendInitial(coIdx: Int, valueIdx: Int): LuaKernelOutcome {
        val label = protocolLabel(valueIdx) ?: return invalidYield()
        lua.pushValue(coIdx)
        val threadRef = lua.ref()
        val coroutineId = LuaCoroutineId.next()
        val operationId = LuaOperationId.next()
        if (!operations.registerSuspension(
                coroutineId.value,
                operationId.value,
                threadRef,
                activeSchedulerContext ?: SchedulerContext.OTHER,
            )
        ) {
            runCatching { lua.unref(threadRef) }
            return runtimeFailure("operation registry is saturated")
        }
        noteSleepOperation(operationId.value, label)
        noteHostRequestOperation(operationId.value, label)
        return yieldedOutcome(coroutineId.value, operationId.value, label)
    }

    /**
     * Re-suspend an already-tracked coroutine that yielded again during a resume.
     * The coroutine reference is unchanged; a fresh operation token is minted and
     * the superseded token is invalidated. A non-protocol yield terminates the
     * coroutine as a raw-yield boundary violation.
     */
    private fun suspendSequential(
        record: LuaOperationRegistry.SuspendedCoroutine,
        valueIdx: Int,
    ): LuaKernelOutcome {
        val label = protocolLabel(valueIdx)
        if (label == null) {
            val outcome = invalidYield()
            finalizeTerminal(record, outcome)
            return outcome
        }
        val newOperationId = LuaOperationId.next()
        operations.supersede(record, newOperationId.value)
        noteSleepOperation(newOperationId.value, label)
        noteHostRequestOperation(newOperationId.value, label)
        return yieldedOutcome(record.coroutineId, newOperationId.value, label)
    }

    /**
     * Read the yielded value at absolute index [valueIdx] and, only if it is a
     * string carrying the NUL-prefixed operation protocol, return its label.
     * Returns null for any non-protocol raw yield (a non-string, or a string
     * without the prefix). Stack-neutral.
     */
    private fun protocolLabel(valueIdx: Int): String? {
        if (lua.type(valueIdx) != Lua.LuaType.STRING) return null
        val buffer = lua.toBuffer(valueIdx) ?: return null
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val yielded = bytes.toString(Charsets.UTF_8)
        if (!yielded.startsWith(OPERATION_YIELD_PREFIX)) return null
        return yielded.substring(OPERATION_YIELD_PREFIX.length)
    }

    /** Cache [outcome] as the terminal result of [record]'s current operation and
     *  release the suspended coroutine reference (no Lua re-entry). */
    private fun finalizeTerminal(
        record: LuaOperationRegistry.SuspendedCoroutine,
        outcome: LuaKernelOutcome,
    ) {
        operations.completeTerminal(record, outcome)
        if (record.threadRef != NO_REF) {
            runCatching { lua.unref(record.threadRef) }
            record.threadRef = NO_REF
        }
    }

    /** Push the trusted `talkcan._dispatch_call` helper onto the stack. */
    private fun pushDispatchCall() = pushTalkcanHelper("_dispatch_call")

    /** Push the trusted `talkcan._dispatch_resume` helper onto the stack. */
    private fun pushDispatchResume() = pushTalkcanHelper("_dispatch_resume")

    private fun pushTalkcanHelper(name: String) {
        lua.getGlobal("talkcan")
        lua.getField(lua.getTop(), name)
        lua.remove(lua.getTop() - 1) // drop the talkcan table, keep the helper
    }

    /** Classify an error message surfaced through a dispatch "error" envelope. */
    private fun classifyCoroutineError(message: String?): LuaKernelOutcome {
        val diagnostic = message ?: "protected Lua error"
        return if (diagnostic.contains(INTERRUPTED_SENTINEL) ||
            diagnostic.contains(INSTRUCTION_BUDGET_SENTINEL)
        ) {
            interrupted(diagnostic)
        } else {
            runtimeFailure(diagnostic)
        }
    }

    private fun invalidYield(): LuaKernelOutcome =
        runtimeFailure("E_INVALID_YIELD: raw yield escapes the actor boundary")

    private fun yieldedOutcome(coroutineId: Long, operationId: Long, label: String) =
        LuaKernelOutcome.Yielded(
            stateId = state.stateId.value,
            generation = state.generation(),
            coroutineId = coroutineId,
            operationId = operationId,
            value = label,
        )

    private fun noteSleepOperation(operationId: Long, label: String) {
        val token = label.removePrefix(SLEEP_LABEL_PREFIX)
            .takeIf { label.startsWith(SLEEP_LABEL_PREFIX) }
            ?.toLongOrNull()
            ?: return
        if (sleepDeadlines.containsKey(token)) {
            sleepOperationTokens[operationId] = token
        }
    }

    private fun releaseSleepOperation(operationId: Long) {
        val token = sleepOperationTokens.remove(operationId) ?: return
        sleepDeadlines.remove(token)
    }

    private fun noteHostRequestOperation(operationId: Long, label: String) {
        val requestId = label.toLongOrNull() ?: return
        if (state.hasHostOperation(requestId)) {
            requestOperationIds[operationId] = requestId
            state.hostOperationClaim(requestId)?.let { requestOperationClaims[operationId] = it }
        }
    }

    private fun releaseHostRequestOperation(operationId: Long): HostOperationClaim.Admitted? {
        val claim = requestOperationClaims.remove(operationId)
        val requestId = requestOperationIds.remove(operationId) ?: return claim
        state.discardHostOperation(requestId)
        return claim
    }

    private fun cancelledOutcome(operationId: Long) = LuaKernelOutcome.Cancelled(
        stateId = state.stateId.value,
        generation = state.generation(),
        operationId = operationId,
    )

    private fun staleOperation(diagnostic: String) = LuaKernelOutcome.Stale(
        stateId = state.stateId.value,
        generation = state.generation(),
        diagnostic = diagnostic,
    )

    private fun invalidOperation(diagnostic: String) = LuaKernelOutcome.InvalidOwnership(
        stateId = state.stateId.value,
        generation = state.generation(),
        diagnostic = diagnostic,
    )

    // ------------------------------------------------------------------
    // Result conversion (Lua -> project LuaValue -> JSON)
    // ------------------------------------------------------------------
    private fun convertResult(resultIdx: Int): String {
        val policy = if (activeSchedulerContext == SchedulerContext.RESOLVER) {
            RESOLVER_VALUE_POLICY
        } else {
            CALLBACK_VALUE_POLICY
        }
        val budget = ConversionBudget(policy)
        val tableRefs = mutableListOf<Int>()
        val value = try {
            toProjectValue(lua.toAbsoluteIndex(resultIdx), 0, budget, tableRefs)
        } finally {
            tableRefs.forEach(lua::unref)
        }
        if (
            value is LuaValue.StringValue &&
            LEGACY_RAW_RESULT.matches(value.value)
        ) return value.value
        return when (val encoding = value.toJsonString(policy)) {
            is JsonEncodingResult.Success -> encoding.json
            is JsonEncodingResult.Failure -> throw LuaConversionException(encoding.diagnostic)
        }
    }

    /**
     * Convert the Lua value at absolute index [absIdx] into a project [LuaValue].
     * Preserves nil / bool / integer and float numbers / UTF-8 string / dense list /
     * string-keyed map and atomically rejects cycles, metatables, functions,
     * threads, userdata, non-finite numbers, sparse or mixed tables, and values
     * exceeding the configured depth, entry, or byte limits. Stack-balanced: the
     * caller observes an unchanged stack top on return.
     */
    private fun toProjectValue(
        absIdx: Int,
        depth: Int,
        budget: ConversionBudget,
        ancestors: MutableList<Int>,
    ): LuaValue {
        budget.checkDepth(depth)
        return when (lua.type(absIdx)) {
            Lua.LuaType.NIL, Lua.LuaType.NONE -> LuaValue.Nil
            Lua.LuaType.BOOLEAN -> LuaValue.Bool(lua.toBoolean(absIdx))
            Lua.LuaType.NUMBER -> {
                if (lua.isInteger(absIdx)) {
                    LuaValue.Integer(lua.toInteger(absIdx))
                } else {
                    val number = lua.toNumber(absIdx)
                    if (!number.isFinite()) throw LuaConversionException("number must be finite")
                    LuaValue.Real(number)
                }
            }
            Lua.LuaType.STRING -> {
                val buffer = lua.toBuffer(absIdx)
                    ?: throw LuaConversionException("string conversion failed")
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val text = try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                } catch (_: Throwable) {
                    throw LuaConversionException("string is not valid UTF-8")
                }
                budget.checkStringBytes(text)
                LuaValue.StringValue(text)
            }
            Lua.LuaType.TABLE -> convertTable(absIdx, depth, budget, ancestors)
            Lua.LuaType.FUNCTION -> throw LuaConversionException("functions cannot cross the value boundary")
            Lua.LuaType.THREAD -> throw LuaConversionException("threads cannot cross the value boundary")
            Lua.LuaType.USERDATA, Lua.LuaType.LIGHTUSERDATA ->
                throw LuaConversionException("userdata cannot cross the value boundary")
            else -> throw LuaConversionException("unsupported Lua type: ${lua.type(absIdx)}")
        }
    }

    private fun convertTable(
        absIdx: Int,
        depth: Int,
        budget: ConversionBudget,
        ancestors: MutableList<Int>,
    ): LuaValue {
        if (lua.getMetatable(absIdx) != 0) {
            lua.pop(1)
            throw LuaConversionException("tables with metatables cannot cross the value boundary")
        }
        // Reject both cycles and aliases: every table identity may occur once.
        for (seenRef in ancestors) {
            lua.refGet(seenRef)
            val duplicate = lua.rawEqual(absIdx, lua.getTop())
            lua.pop(1)
            if (duplicate) {
                throw LuaConversionException("value contains a cycle or shared table alias")
            }
        }
        lua.pushValue(absIdx)
        ancestors.add(lua.ref())
        return collectEntries(absIdx, depth, budget, ancestors)
    }

    private fun collectEntries(
        absIdx: Int,
        depth: Int,
        budget: ConversionBudget,
        ancestors: MutableList<Int>,
    ): LuaValue {
        val intKeys = HashMap<Long, LuaValue>()
        val strKeys = LinkedHashMap<String, LuaValue>()
        lua.checkStack(4)
        lua.pushNil()
        while (lua.next(absIdx) != 0) {
            budget.noteEntry()
            val keyIdx = lua.getTop() - 1
            val valueIdx = lua.getTop()
            when (lua.type(keyIdx)) {
                Lua.LuaType.NUMBER -> {
                    if (!lua.isInteger(keyIdx)) {
                        throw LuaConversionException("table has a non-integer numeric key")
                    }
                    val key = lua.toInteger(keyIdx)
                    if (key < 1L) throw LuaConversionException("table has an out-of-range integer key")
                    intKeys[key] = toProjectValue(valueIdx, depth + 1, budget, ancestors)
                }
                Lua.LuaType.STRING -> {
                    val key = lua.toString(keyIdx) ?: ""
                    budget.checkStringBytes(key)
                    strKeys[key] = toProjectValue(valueIdx, depth + 1, budget, ancestors)
                }
                else -> throw LuaConversionException("table has an unsupported key type")
            }
            lua.pop(1) // drop the value; keep the key for next()
        }
        return assembleTable(intKeys, strKeys)
    }

    private fun assembleTable(
        intKeys: Map<Long, LuaValue>,
        strKeys: LinkedHashMap<String, LuaValue>,
    ): LuaValue {
        if (intKeys.isNotEmpty() && strKeys.isNotEmpty()) {
            throw LuaConversionException("table mixes integer and string keys")
        }
        if (strKeys.isNotEmpty()) {
            return LuaValue.Map(strKeys)
        }
        if (intKeys.isEmpty()) {
            return LuaValue.Array(emptyList())
        }
        val count = intKeys.size
        val values = ArrayList<LuaValue>(count)
        for (i in 1L..count.toLong()) {
            values.add(intKeys[i] ?: throw LuaConversionException("table has a sparse array part"))
        }
        return LuaValue.Array(values)
    }

    // ------------------------------------------------------------------
    // Argument conversion (project LuaValue -> Lua)
    private fun readCallbackValue(
        source: Lua,
        index: Int,
        depth: Int = 0,
        entries: IntArray = intArrayOf(0),
        ancestors: MutableList<Int> = mutableListOf(),
        allowJsonNull: Boolean = false,
    ): LuaValue {
        require(depth <= 32) { "value exceeds depth limit" }
        val absoluteIndex = index
        return when (source.type(absoluteIndex)) {
            Lua.LuaType.NIL, Lua.LuaType.NONE -> LuaValue.Nil
            Lua.LuaType.BOOLEAN -> LuaValue.Bool(source.toBoolean(absoluteIndex))
            Lua.LuaType.NUMBER -> if (source.isInteger(absoluteIndex)) {
                LuaValue.Integer(source.toInteger(absoluteIndex))
            } else {
                val number = source.toNumber(absoluteIndex)
                require(number.isFinite()) { "number must be finite" }
                LuaValue.Real(number)
            }
            Lua.LuaType.STRING -> LuaValue.StringValue(source.toString(absoluteIndex) ?: "")
            Lua.LuaType.TABLE -> readCallbackTable(
                source,
                absoluteIndex,
                depth,
                entries,
                ancestors,
                allowJsonNull,
            )
            Lua.LuaType.LIGHTUSERDATA -> {
                val pointer = source.getLuaNatives()
                    .lua_touserdata(source.getPointer(), absoluteIndex)
                if (allowJsonNull && opaqueValues[pointer] is OpaqueJsonNull) {
                    LuaValue.Nil
                } else {
                    throw IllegalArgumentException("unsupported JSON value")
                }
            }
            else -> throw IllegalArgumentException("unsupported JSON value")
        }
    }

    private fun readCallbackTable(
        source: Lua,
        index: Int,
        depth: Int,
        entries: IntArray,
        ancestors: MutableList<Int>,
        allowJsonNull: Boolean,
    ): LuaValue {
        require(source.getMetatable(index) == 0) {
            source.pop(1)
            "tables with metatables are not accepted"
        }
        for (ancestor in ancestors) {
            source.refGet(ancestor)
            val cycle = source.rawEqual(index, source.getTop())
            source.pop(1)
            require(!cycle) { "value contains a cycle" }
        }
        source.pushValue(index)
        val self = source.ref()
        ancestors += self
        return try {
            val integers = HashMap<Long, LuaValue>()
            val strings = LinkedHashMap<String, LuaValue>()
            source.pushNil()
            while (source.next(index) != 0) {
                require(++entries[0] <= 4_096) { "value exceeds entry limit" }
                val keyIndex = source.getTop() - 1
                val valueIndex = source.getTop()
                when (source.type(keyIndex)) {
                    Lua.LuaType.NUMBER -> {
                        require(source.isInteger(keyIndex)) { "non-integer table key" }
                        val key = source.toInteger(keyIndex)
                        require(key >= 1) { "out-of-range table key" }
                        integers[key] = readCallbackValue(
                            source,
                            valueIndex,
                            depth + 1,
                            entries,
                            ancestors,
                            allowJsonNull,
                        )
                    }
                    Lua.LuaType.STRING -> {
                        val key = source.toString(keyIndex) ?: ""
                        strings[key] = readCallbackValue(
                            source,
                            valueIndex,
                            depth + 1,
                            entries,
                            ancestors,
                            allowJsonNull,
                        )
                    }
                    else -> throw IllegalArgumentException("unsupported table key")
                }
                source.pop(1)
            }
            require(integers.isEmpty() || strings.isEmpty()) { "mixed table keys" }
            if (integers.isEmpty()) {
                LuaValue.Map(strings)
            } else {
                LuaValue.Array(
                    (1L..integers.size.toLong()).map { key ->
                        integers[key] ?: throw IllegalArgumentException("sparse array")
                    },
                )
            }
        } finally {
            ancestors.removeAt(ancestors.lastIndex)
            source.unref(self)
        }
    }

    // ------------------------------------------------------------------

    private fun pushProjectValue(value: LuaValue) = pushProjectValue(lua, value)

    private fun pushProjectValue(target: Lua, value: LuaValue) {
        target.checkStack(4)
        when (value) {
            is LuaValue.Nil -> target.pushNil()
            is LuaValue.Bool -> target.push(value.value)
            is LuaValue.Integer -> target.push(value.value)
            is LuaValue.Real -> target.push(value.value as Number)
            is LuaValue.StringValue -> target.push(value.value)
            is LuaValue.Array -> {
                target.newTable()
                val tableIdx = target.getTop()
                for ((i, element) in value.values.withIndex()) {
                    pushProjectValue(target, element)
                    target.rawSetI(tableIdx, i + 1)
                }
            }
            is LuaValue.Map -> {
                target.newTable()
                val tableIdx = target.getTop()
                for ((key, element) in value.pairs) {
                    pushProjectValue(target, element)
                    target.setField(tableIdx, key)
                }
            }
        }
    }
    private fun pushJsonValue(target: Lua, value: LuaValue) {
        target.checkStack(4)
        when (value) {
            LuaValue.Nil -> pushJsonNull(target)
            is LuaValue.Bool -> target.push(value.value)
            is LuaValue.Integer -> target.push(value.value)
            is LuaValue.Real -> target.push(value.value as Number)
            is LuaValue.StringValue -> target.push(value.value)
            is LuaValue.Array -> {
                target.newTable()
                val tableIdx = target.getTop()
                for ((index, element) in value.values.withIndex()) {
                    pushJsonValue(target, element)
                    target.rawSetI(tableIdx, index + 1)
                }
            }
            is LuaValue.Map -> {
                target.newTable()
                val tableIdx = target.getTop()
                for ((key, element) in value.pairs) {
                    pushJsonValue(target, element)
                    target.setField(tableIdx, key)
                }
            }
        }
    }


    // ------------------------------------------------------------------
    // Protected-boundary classification and outcome helpers
    // ------------------------------------------------------------------

    private fun classify(t: Throwable): LuaKernelOutcome = when (t) {
        is LuaConversionException ->
            runtimeFailure("E_INVALID_VALUE: ${t.message ?: "value conversion rejected"}")
        is LuaException -> {
            val diagnostic = t.message ?: "protected Lua error"
            when {
                diagnostic.contains(INTERRUPTED_SENTINEL) ||
                    diagnostic.contains(INSTRUCTION_BUDGET_SENTINEL) ->
                    interrupted(diagnostic)
                t.type == LuaException.LuaError.SYNTAX -> syntaxFailure(diagnostic)
                // LuaError.MEMORY (process-level OOM from the LuaJava natives)
                // is not a per-state allocator denial; it classifies as an
                // ordinary runtime failure under the existing failure latch.
                else -> runtimeFailure(diagnostic)
            }
        }
        is LinkageError -> runtimeFailure("native linkage failure: ${t.message ?: t.javaClass.simpleName}")
        else -> runtimeFailure(t.message ?: t.javaClass.simpleName)
    }

    private fun shapeResolverOutcome(outcome: LuaKernelOutcome): LuaKernelOutcome {
        if (outcome !is LuaKernelOutcome.Completed) {
            return completed(
                JSONObject()
                    .put("operation", "invokeResolver")
                    .put("resultKind", "package_error")
                    .put("error", "resolver error")
                    .toString(),
            )
        }
        val result = try {
            JSONObject(outcome.value ?: "{}")
        } catch (_: Throwable) {
            null
        }
        val choices = result?.optJSONArray("choices")
        var valid = choices != null
        val seenValues = HashSet<String>()
        if (choices != null) {
            for (index in 0 until choices.length()) {
                val choice = choices.optJSONObject(index)
                val value = choice?.optString("value", "") ?: ""
                if (
                    choice == null ||
                    value.isBlank() ||
                    choice.optString("label", "").isBlank() ||
                    !seenValues.add(value)
                ) {
                    valid = false
                    break
                }
            }
        }
        val envelope = JSONObject().put("operation", "invokeResolver")
        if (valid) {
            envelope.put("resultKind", "choices")
            envelope.put("choices", choices)
        } else {
            envelope.put("resultKind", "package_error")
            envelope.put("error", "malformed resolver result")
        }
        return outcome.copy(value = envelope.toString())
    }

    private fun completed(value: String?): LuaKernelOutcome.Completed = LuaKernelOutcome.Completed(
        stateId = state.stateId.value,
        generation = state.generation(),
        coroutineId = null,
        value = value,
        elapsedNanos = null,
        luaVersion = KotlinLuaKernelBridge.LUA_VERSION,
        bindingVersion = KotlinLuaKernelBridge.BINDING_VERSION,
        topology = KotlinLuaKernelBridge.TOPOLOGY,
    )

    private fun syntaxFailure(diagnostic: String) = LuaKernelOutcome.SyntaxFailure(
        stateId = state.stateId.value,
        generation = state.generation(),
        diagnostic = diagnostic,
    )

    private fun validationFailure(diagnostic: String) = LuaKernelOutcome.ValidationFailure(
        stateId = state.stateId.value,
        generation = state.generation(),
        diagnostic = diagnostic,
    )

    private fun runtimeFailure(diagnostic: String) = LuaKernelOutcome.RuntimeFailure(
        stateId = state.stateId.value,
        generation = state.generation(),
        diagnostic = diagnostic,
    )

    private fun interrupted(diagnostic: String) = LuaKernelOutcome.Interrupted(
        stateId = state.stateId.value,
        generation = state.generation(),
        diagnostic = diagnostic,
        elapsedNanos = state.elapsedNanos(),
    )

    private fun pushJsonNull(target: Lua) {
        val id = jsonNullOpaqueId ?: nextOpaqueId++.also {
            jsonNullOpaqueId = it
            opaqueValues[it] = OpaqueJsonNull(JSON_NULL_TOKEN)
        }
        target.getLuaNatives().lua_pushlightuserdata(target.getPointer(), id)
        attachOpaqueMetatable(target)
    }

    private fun pushOpaque(target: Lua, value: OpaqueValue) {
        val id = nextOpaqueId++
        opaqueValues[id] = value
        target.getLuaNatives().lua_pushlightuserdata(target.getPointer(), id)
        attachOpaqueMetatable(target)
    }

    private fun pushProfileGrant(target: Lua, grant: ProfileGrant) {
        target.newTable()
        val profileIndex = target.getTop()
        target.push(grant.id)
        target.setField(profileIndex, "id")
        target.push(grant.type)
        target.setField(profileIndex, "type")
        target.push(grant.name)
        target.setField(profileIndex, "name")
        pushProjectValue(target, LuaValue.Map(grant.values))
        target.setField(profileIndex, "values")
        target.newTable()
        val secretsIndex = target.getTop()
        for ((field, token) in grant.secrets) {
            pushOpaque(target, OpaqueSecret(token))
            target.setField(secretsIndex, field)
        }
        target.setField(profileIndex, "secrets")
    }

    private fun WorkValue.toLuaValue(): LuaValue = when (this) {
        WorkValue.Null -> LuaValue.Nil
        is WorkValue.Bool -> LuaValue.Bool(value)
        is WorkValue.Integer -> LuaValue.Integer(value)
        is WorkValue.Real -> LuaValue.Real(value)
        is WorkValue.Text -> LuaValue.StringValue(value)
        is WorkValue.List -> LuaValue.Array(values.map { it.toLuaValue() })
        is WorkValue.Map -> LuaValue.Map(
            LinkedHashMap<String, LuaValue>().also { output ->
                entries.forEach { output[it.key] = it.value.toLuaValue() }
            },
        )
    }

    private fun attachOpaqueMetatable(target: Lua) {
        target.getGlobal("talkcan")
        target.getField(-1, "_opaque_metatable")
        target.remove(-2)
        target.setMetatable(-2)
    }

    companion object {
        private val CALLBACK_VALUE_POLICY = LuaValuePolicy(
            maxDepth = 10,
            maxEntries = 1_000,
            maxUtf8Bytes = 65_536,
            maxStringUtf8Bytes = 65_536,
            maxKeyUtf8Bytes = 16_384,
        )
        /** Sentinel for "no registry reference"; matches Lua's LUA_NOREF. */
        private const val NO_REF = -1

        private val RESOLVER_VALUE_POLICY = LuaValuePolicy(
            maxDepth = 10,
            maxEntries = 4_096,
            maxUtf8Bytes = 1_048_576,
            maxStringUtf8Bytes = 65_536,
            maxKeyUtf8Bytes = 16_384,
        )
        private val LEGACY_RAW_RESULT = Regex(
            "(?:[0-9a-f]{8}|E_[A-Z_]+|opaque_[a-z_]+|nil:E_[A-Z_]+|" +
                "false:rejected:.+|completed task)",
        )
        private const val BINARY_ESCAPE = '\u001b'
        private val MODULE_NAME = Regex("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)*")
        private val CALLBACK_FIELDS = setOf(
            "startup",
            "handle_lifecycle",
            "handle_readiness",
            "handle_input",
            "handle_sos",
        )
        private const val MAX_IMAGE_MODULES = 4096
        private const val MAX_IMAGE_SOURCE_BYTES = 8L * 1024L * 1024L
        private const val INTERRUPTED_SENTINEL = "E_INTERRUPTED"
        private const val INSTRUCTION_BUDGET_SENTINEL = "E_INSTRUCTION_BUDGET"
        private const val MAX_LOG_ENTRIES = 128
        private const val JSON_NULL_TOKEN = "json-null"
        private const val STALE_OPAQUE_TOKEN = "\u0000stale-opaque"

        /** NUL-prefixed operation-protocol marker a trusted yield must carry. */
        private const val OPERATION_YIELD_PREFIX = "\u0000talkcan-operation:"
        private const val SLEEP_LABEL_PREFIX = "sleep:"
        private val NEXT_SLEEP_TOKEN = AtomicLong(1L)

        /** Trusted dispatch envelope kinds. */
        private const val DISPATCH_COMPLETED = "completed"
        private const val DISPATCH_YIELDED = "yielded"
        private const val DISPATCH_ERROR = "error"

        /** Bounded operation-registry capacities. */
        private const val TERMINAL_CACHE_CAPACITY = 1024
        private const val OPERATION_TOMBSTONE_CAPACITY = 4096
        private const val LIVE_COROUTINE_CAPACITY = 4096

    }
    private fun pushOpaque(value: OpaqueValue) = pushOpaque(lua, value)

    private fun resolveOpaqueToken(pointer: Long): String? =
        when (val value = opaqueValues[pointer]) {
            is OpaqueMount ->
                if (value.authorityGeneration == authorityGeneration) {
                    value.token
                } else {
                    STALE_OPAQUE_TOKEN
                }
            else -> value?.token
        }
    private fun resolveOpaqueKind(pointer: Long): OpaqueValueKind? =
        when (val value = opaqueValues[pointer]) {
            is OpaqueAudio -> value.kind
            null -> null
            else -> OpaqueValueKind.OTHER
        }
    private fun resolveWorkOwner(pointer: Long): WorkOpaqueOwner? =
        when (val value = opaqueValues[pointer]) {
            is OpaqueQueue -> WorkOpaqueOwner(value.token)
            is OpaqueJob -> WorkOpaqueOwner(value.queue, value.token)
            else -> null
        }

    private fun hostEligibilityError(
        claim: io.talkcan.lua.HostOperationClaim.Admitted,
    ): String? {
        val kind = claim.kind
        val context = activeSchedulerContext ?: return "E_INVALID_CONTEXT"
        val contextAllowed = when (kind) {
            HostOperationKind.KEYBOARD_SEND_TEXT, HostOperationKind.KEYBOARD_SEND_KEY ->
                context == SchedulerContext.INPUT ||
                    context == SchedulerContext.MANAGED ||
                    context == SchedulerContext.SOS
            HostOperationKind.SECRET_READ, HostOperationKind.HTTP_REQUEST ->
                context == SchedulerContext.INPUT ||
                    context == SchedulerContext.MANAGED ||
                    context == SchedulerContext.RESOLVER
            HostOperationKind.WORK_RECEIVE -> context == SchedulerContext.MANAGED
            else ->
                context == SchedulerContext.INPUT || context == SchedulerContext.MANAGED
        }
        if (!contextAllowed) return "E_INVALID_CONTEXT"
        val declared = when (kind) {
            HostOperationKind.KEYBOARD_SEND_TEXT, HostOperationKind.KEYBOARD_SEND_KEY ->
                resourceAuthority.keyboardOutput
            HostOperationKind.SECRET_READ -> resourceAuthority.secretsRead
            HostOperationKind.HTTP_REQUEST -> resourceAuthority.networkHttp
            HostOperationKind.FS_MKDIR,
            HostOperationKind.FS_STAT,
            HostOperationKind.FS_LIST,
            HostOperationKind.FS_READ_TEXT,
            HostOperationKind.FS_WRITE_TEXT,
            HostOperationKind.FS_REMOVE,
            -> resourceAuthority.storageFiles
            HostOperationKind.AUDIO_OPEN, HostOperationKind.AUDIO_EXPORT ->
                resourceAuthority.audioFiles
            HostOperationKind.WORK_SUBMIT,
            HostOperationKind.WORK_RECEIVE,
            HostOperationKind.WORK_BEGIN_EFFECT,
            HostOperationKind.WORK_COMMIT_EFFECT,
            HostOperationKind.WORK_COMPLETE,
            HostOperationKind.WORK_FAIL,
            -> resourceAuthority.workQueue
            HostOperationKind.TRANSCRIBE,
            HostOperationKind.SYNTHESIZE,
            HostOperationKind.PLAYBACK,
            -> true
        }
        if (!declared) return "E_CAPABILITY_UNDECLARED"
        if (
            claim.path != null &&
            !(kind == HostOperationKind.FS_LIST && claim.path.isEmpty()) &&
            MountRelativePath.parse(claim.path) is PathParseResult.Invalid
        ) {
            return "E_INVALID_PATH"
        }
        val mountBacked = when (kind) {
            HostOperationKind.FS_MKDIR,
            HostOperationKind.FS_STAT,
            HostOperationKind.FS_LIST,
            HostOperationKind.FS_READ_TEXT,
            HostOperationKind.FS_WRITE_TEXT,
            HostOperationKind.FS_REMOVE,
            HostOperationKind.AUDIO_OPEN,
            HostOperationKind.AUDIO_EXPORT,
            -> true
            else -> false
        }
        val mount =
            if (mountBacked) {
                claim.declarationId?.let(resourceAuthority.mounts::get)
                    ?: return "E_STALE"
            } else {
                null
            }
        if (
            mount != null &&
            (
                kind == HostOperationKind.FS_MKDIR ||
                    kind == HostOperationKind.FS_WRITE_TEXT ||
                    kind == HostOperationKind.FS_REMOVE ||
                    kind == HostOperationKind.AUDIO_OPEN ||
                    kind == HostOperationKind.AUDIO_EXPORT
            ) &&
            mount.access == "read-only"
        ) {
            return "E_READ_ONLY"
        }
        return null
    }
}

private data class ResourceAuthority(
    val instanceId: String,
    val storageFiles: Boolean,
    val audioFiles: Boolean,
    val keyboardOutput: Boolean,

    val secretsRead: Boolean,
    val networkHttp: Boolean,
    val workQueue: Boolean,
    val workQueues: Set<String>,
    val mounts: Map<String, ResourceMount>,
) {
    companion object {
        val EMPTY = ResourceAuthority(
            instanceId = "",
            storageFiles = false,
            audioFiles = false,
            keyboardOutput = false,
            secretsRead = false,
            networkHttp = false,
            workQueue = false,
            workQueues = emptySet(),
            mounts = emptyMap(),
        )
    }
}

private data class ProfileGrant(
    val id: String,
    val type: String,
    val name: String,
    val values: Map<String, LuaValue>,
    val secrets: Map<String, String>,
)

private data class ResourceMount(
    val access: String,
    val status: String,
)

private data class DeferredTask(
    val coroutineId: Long,
    val functionRef: Int,
)

private sealed interface OpaqueValue {
    val token: String
}
private data class OpaqueAudio(
    override val token: String,
    val metadata: LuaValue.Map,
    val kind: OpaqueValueKind,
) : OpaqueValue
private data class OpaqueMount(
    override val token: String,
    val authorityGeneration: Long,
) : OpaqueValue
private data class OpaqueSecret(override val token: String) : OpaqueValue
private data class OpaqueQueue(override val token: String) : OpaqueValue
private data class OpaqueJsonNull(override val token: String) : OpaqueValue
private data class OpaqueJob(
    val queue: String,
    override val token: String,
    val payload: LuaValue,
) : OpaqueValue

internal enum class SchedulerContext {
    STARTUP,
    INPUT,
    MANAGED,
    SOS,
    RESOLVER,
    OTHER,
}

/** Whole-value rejection raised during boundary conversion; normalized at the protected boundary. */
private class LuaConversionException(message: String) : RuntimeException(message)

/** Mutable per-conversion budget enforcing entry, depth, and string-byte limits atomically. */
private class ConversionBudget(private val policy: LuaValuePolicy) {
    private var entries = 0
    private var stringBytes = 0

    fun noteEntry() {
        entries++
        if (entries > policy.maxEntries) throw LuaConversionException("value exceeds entry limit")
    }

    fun checkDepth(depth: Int) {
        if (depth > policy.maxDepth) throw LuaConversionException("value exceeds depth limit")
    }

    fun checkStringBytes(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes > policy.maxStringUtf8Bytes) {
            throw LuaConversionException("string exceeds byte limit")
        }
        stringBytes += bytes
        if (stringBytes > policy.maxUtf8Bytes) {
            throw LuaConversionException("aggregate string bytes exceed limit")
        }
    }
}
