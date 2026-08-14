package io.talkcan.lua.kernel

import io.talkcan.lua.HostOperationClaim
import io.talkcan.lua.HostOperationKind
import io.talkcan.lua.LuaValue
import io.talkcan.work.WorkMapEntry
import io.talkcan.work.WorkValue
import io.talkcan.work.encodeValue
import java.util.concurrent.atomic.AtomicLong
import party.iroiro.luajava.JFunction
import party.iroiro.luajava.Lua
import party.iroiro.luajava.lua54.Lua54

import java.nio.charset.CodingErrorAction
internal data class WorkOpaqueOwner(val queue: String, val job: String? = null)
internal enum class OpaqueValueKind { AUDIO_RECORDING, AUDIO_SYNTHESIZED, OTHER }

/** Owner-thread constructors for opaque, exactly-once host-operation requests. */
internal class LuaHostRequestBroker(
    private val state: LuaEngineState,
    private val resolveOpaque: (Long) -> String?,
    private val resolveOpaqueKind: (Long) -> OpaqueValueKind?,
    private val resolveWorkOwner: (Long) -> WorkOpaqueOwner?,
    private val eligibilityError: (HostOperationClaim.Admitted) -> String?,
) {
    private var activeLua: Lua54? = null
    private val callbackLua: Lua54
        get() = checkNotNull(activeLua) { "host request callback is not active" }
    fun callbacks(): Map<String, JFunction> = mapOf(
        "host_transcribe" to requestCallback { requestId -> transcribeClaim(requestId) },
        "host_synthesize" to requestCallback { requestId -> synthesisClaim(requestId) },
        "host_playback" to requestCallback { requestId -> playbackClaim(requestId) },
        "host_audio_file" to requestCallback { requestId -> audioFileClaim(requestId) },
        "host_fs_io" to requestCallback { requestId -> fsClaim(requestId) },
        "host_keyboard_output" to requestCallback { requestId -> keyboardClaim(requestId) },
        "host_secrets_read" to requestCallback { requestId ->
            val pointer = opaquePointer(1)
            val reference = pointer?.let(resolveOpaque)
                ?: reject("E_INVALID_ARGUMENT", HostOperationKind.SECRET_READ)
            admitted(
                requestId,
                HostOperationKind.SECRET_READ,
                referenceToken = reference,
            )
        },
        "host_http_request" to requestCallback { requestId -> httpClaim(requestId) },
        "host_work_submit" to requestCallback { requestId ->
            val owner = workOwnerAt(1)
            admitted(
                requestId,
                HostOperationKind.WORK_SUBMIT,
                queue = owner.queue,
                payloadJson = workJson(readValue(2)),
            )
        },
        "host_work_receive" to requestCallback { requestId ->
            val owner = workOwnerAt(1)
            admitted(requestId, HostOperationKind.WORK_RECEIVE, queue = owner.queue)
        },
        "host_work_effect_begin" to requestCallback { requestId ->
            val owner = workOwnerAt(1)
            admitted(
                requestId,
                HostOperationKind.WORK_BEGIN_EFFECT,
                queue = owner.queue,
                job = owner.job,
                effectKey = callbackLua.toString(2),
                fingerprint = callbackLua.toString(3),
            )
        },
        "host_work_effect_commit" to requestCallback { requestId ->
            val owner = workOwnerAt(1)
            admitted(
                requestId,
                HostOperationKind.WORK_COMMIT_EFFECT,
                queue = owner.queue,
                job = owner.job,
                effectKey = callbackLua.toString(2),
                resultOk = callbackLua.toBoolean(3),
                resultJson = workJson(readValue(4)),
            )
        },
        "host_work_complete" to requestCallback { requestId ->
            val owner = workOwnerAt(1)
            admitted(
                requestId,
                HostOperationKind.WORK_COMPLETE,
                queue = owner.queue,
                job = owner.job,
                resultJson = workJson(readValue(2)),
            )
        },
        "host_work_fail" to requestCallback { requestId ->
            val owner = workOwnerAt(1)
            admitted(
                requestId,
                HostOperationKind.WORK_FAIL,
                queue = owner.queue,
                job = owner.job,
                reasonJson = workJson(readValue(2)),
            )
        },
        "host_feedback_emit" to requestCallback { requestId ->
            val toneStr = callbackLua.toString(1)
            val tone = when (toneStr) {
                "recording_limit_warning" -> io.talkcan.service.CaptureFeedbackTone.RecordingLimitWarning
                "recording_limit_final" -> io.talkcan.service.CaptureFeedbackTone.RecordingLimitFinal
                else -> reject("E_INVALID_ARGUMENT", HostOperationKind.AUDIO_FEEDBACK)
            }
            admitted(
                requestId,
                HostOperationKind.AUDIO_FEEDBACK,
                feedbackTone = tone,
            )
        },
    )

    private fun requestCallback(
        build: (Long) -> HostOperationClaim.Admitted,
    ): JFunction = JFunction { invokedLua ->
        activeLua = invokedLua as Lua54
        try {
            val requestId = NEXT_REQUEST_ID.getAndIncrement()
            val request = HostOperationRequest(build(requestId))
            val eligibility = eligibilityError(request.claim)
            when {
                eligibility != null -> {
                    invokedLua.push(false)
                    invokedLua.push(eligibility)
                }
                state.registerHostOperation(request) -> {
                    invokedLua.push(true)
                    invokedLua.push(requestId)
                }
                else -> {
                    invokedLua.push(false)
                    invokedLua.push("E_BUSY")
                }
            }
        } catch (rejected: RequestRejected) {
            val contextError = rejected.kind?.let {
                eligibilityError(admitted(0L, it))
            }
            invokedLua.push(false)
            invokedLua.push(contextError ?: rejected.code)
        } catch (_: Throwable) {
            invokedLua.push(false)
            invokedLua.push("E_INVALID_ARGUMENT")
        } finally {
            activeLua = null
        }
        2
    }

    private fun transcribeClaim(requestId: Long): HostOperationClaim.Admitted {
        val pointer = opaquePointer(1)
        val token = pointer?.let(resolveOpaque)
            ?: reject("E_INVALID_ARGUMENT", HostOperationKind.TRANSCRIBE)
        if (resolveOpaqueKind(pointer) != OpaqueValueKind.AUDIO_RECORDING) {
            reject("E_INVALID_VALUE", HostOperationKind.TRANSCRIBE)
        }
        return admitted(requestId, HostOperationKind.TRANSCRIBE, audioToken = token)
    }

    private fun synthesisClaim(requestId: Long): HostOperationClaim.Admitted {
        val values = mapAt(1)
        if (values.keys !in setOf(
                setOf("text", "language", "voice"),
                setOf("text", "language", "voice", "speed"),
            )
        ) reject("E_INVALID_ARGUMENT")
        val text = values.string("text")
        val language = values.string("language")
        val voice = values.string("voice")
        val speed = values.number("speed", 1.0)
        if (
            text.isNullOrBlank() || text.toByteArray().size > 16_384 ||
            language == null || language.toByteArray().size > 64 ||
            language.split('-').any {
                it.isEmpty() || it.length > 8 || it.any { char -> !char.isLetterOrDigit() }
            } ||
            voice.isNullOrBlank() || voice.toByteArray().size > 128 ||
            !speed.isFinite() || speed <= 0.0 || speed > 4.0
        ) reject("E_INVALID_ARGUMENT")
        return admitted(
            requestId,
            HostOperationKind.SYNTHESIZE,
            text = text,
            language = language,
            voice = voice,
            speed = speed,
        )
    }

    private fun playbackClaim(requestId: Long): HostOperationClaim.Admitted {
        val pointer = opaquePointer(1)
        val token = pointer?.let(resolveOpaque) ?: reject("E_INVALID_ARGUMENT")
        if (resolveOpaqueKind(pointer) !in setOf(
                OpaqueValueKind.AUDIO_RECORDING,
                OpaqueValueKind.AUDIO_SYNTHESIZED,
            )
        ) reject("E_INVALID_ARGUMENT")
        val options = mapAt(2)
        if (options.keys.any { it != "delay_seconds" }) reject("E_INVALID_ARGUMENT")
        val delay = options.number("delay_seconds", 0.0)
        if (!delay.isFinite() || delay < 0.0 || delay > 86_400.0) {
            reject("E_INVALID_VALUE")
        }
        return admitted(
            requestId,
            HostOperationKind.PLAYBACK,
            audioToken = token,
            delaySeconds = delay,
        )
    }

    private fun audioFileClaim(requestId: Long): HostOperationClaim.Admitted {
        val kind = when (callbackLua.toString(1)) {
            "open" -> HostOperationKind.AUDIO_OPEN
            "export" -> HostOperationKind.AUDIO_EXPORT
            else -> throw IllegalArgumentException("invalid audio operation")
        }
        val values = mapAt(2)
        return admitted(
            requestId,
            kind,
            audioToken = values.string("recording"),
            declarationId = values.string("mount"),
            mountToken = values.string("mount"),
            path = values.string("path"),
            mode = values.string("mode"),
            format = values.string("format"),
        )
    }

    private fun fsClaim(requestId: Long): HostOperationClaim.Admitted {
        val kind = when (callbackLua.toString(1)) {
            "mkdir" -> HostOperationKind.FS_MKDIR
            "stat" -> HostOperationKind.FS_STAT
            "list" -> HostOperationKind.FS_LIST
            "read_text" -> HostOperationKind.FS_READ_TEXT
            "write_text" -> HostOperationKind.FS_WRITE_TEXT
            "remove" -> HostOperationKind.FS_REMOVE
            else -> throw IllegalArgumentException("invalid filesystem operation")
        }
        val values = mapAt(2)
        return admitted(
            requestId,
            kind,
            declarationId = values.string("mount"),
            mountToken = values.string("mount"),
            path = values.string("path"),
            parents = values.bool("parents"),
            limit = values.long("limit"),
            cursor = values.string("cursor"),
            maxBytes = values.long("max_bytes"),
            text = values.string("text"),
            mode = values.string("mode"),
            missingOk = values.bool("missing_ok"),
        )
    }

    private fun keyboardClaim(requestId: Long): HostOperationClaim.Admitted {
        val values = mapAt(2)
        val profile = values.string("profile")
        if (
            profile.isNullOrBlank() ||
            profile.toByteArray(Charsets.UTF_8).size > 256
        ) reject("E_INVALID_ARGUMENT")
        return when (callbackLua.toString(1)) {
            "send_text" -> {
                if (values.keys != setOf("text", "profile")) reject("E_INVALID_ARGUMENT")
                val text = values.string("text")
                if (
                    text.isNullOrEmpty() ||
                    text.toByteArray(Charsets.UTF_8).size > 16_384
                ) reject("E_INVALID_ARGUMENT")
                admitted(
                    requestId,
                    HostOperationKind.KEYBOARD_SEND_TEXT,
                    text = text,
                    profile = profile,
                )
            }
            "send_key" -> {
                if (values.keys != setOf("key", "profile")) reject("E_INVALID_ARGUMENT")
                val key = values.string("key")
                if (key == null) reject("E_INVALID_ARGUMENT")
                if (key !in setOf("enter", "escape")) reject("E_INVALID_VALUE")
                admitted(
                    requestId,
                    HostOperationKind.KEYBOARD_SEND_KEY,
                    profile = profile,
                    key = key,
                )
            }
            else -> reject("E_INVALID_ARGUMENT")
        }
    }

    private fun httpClaim(requestId: Long): HostOperationClaim.Admitted {
        val values = mapAt(1)
        if (
            values.keys.any { it !in HTTP_REQUEST_FIELDS } ||
            "method" !in values || "url" !in values
        ) reject("E_INVALID_ARGUMENT")
        val method = values.string("method") ?: reject("E_INVALID_ARGUMENT")
        if (method !in HTTP_METHODS) reject("E_INVALID_VALUE")
        val url = values.string("url") ?: reject("E_INVALID_ARGUMENT")
        validateHttpsUrl(url)
        val headers = when (val value = values["headers"]) {
            null -> LuaValue.Map(emptyMap())
            is LuaValue.Map -> value
            else -> reject("E_INVALID_ARGUMENT")
        }
        if (headers.pairs.size > 100) reject("E_TOO_LARGE")
        for ((name, value) in headers.pairs) {
            val header = (value as? LuaValue.StringValue)?.value
                ?: reject("E_INVALID_ARGUMENT")
            if (
                name.isEmpty() ||
                name.toByteArray(Charsets.UTF_8).size > 256 ||
                header.toByteArray(Charsets.UTF_8).size > 8 * 1_024
            ) reject("E_TOO_LARGE")
        }
        val body = values.string("body")
        if (body != null && body.toByteArray(Charsets.UTF_8).size > 1 shl 20) {
            reject("E_TOO_LARGE")
        }
        val timeout = when (val value = values["timeout_ms"]) {
            null -> 30_000L
            is LuaValue.Integer -> {
                if (value.value <= 0L) reject("E_INVALID_ARGUMENT")
                value.value
            }
            is LuaValue.Real -> {
                val number = value.value
                if (!number.isFinite() || number <= 0.0 || number % 1.0 != 0.0) {
                    reject("E_INVALID_ARGUMENT")
                }
                number.toLong()
            }
            else -> reject("E_INVALID_ARGUMENT")
        }
        if (timeout > 300_000L) reject("E_TOO_LARGE")
        val encodedHeaders = headers.toJsonString()
        val headersJson = when (encodedHeaders) {
            is io.talkcan.lua.JsonEncodingResult.Success -> encodedHeaders.json
            is io.talkcan.lua.JsonEncodingResult.Failure ->
                reject("E_INVALID_ARGUMENT")
        }
        return admitted(
            requestId,
            HostOperationKind.HTTP_REQUEST,
            method = method,
            url = url,
            headersJson = headersJson,
            body = body,
            timeoutMs = timeout,
        )
    }

    private fun validateHttpsUrl(url: String) {
        if (url.toByteArray(Charsets.UTF_8).size > 8 * 1_024) reject("E_TOO_LARGE")
        if (!url.startsWith("https://")) reject("E_INVALID_VALUE")
        val authority = url.removePrefix("https://").substringBefore('/')
        if (
            authority.isEmpty() || '@' in authority ||
            '?' in authority || '#' in authority
        ) reject("E_INVALID_VALUE")
    }

    private fun readValue(index: Int): LuaValue =
        readValue(
            callbackLua.toAbsoluteIndex(index),
            depth = 0,
            budget = ReadBudget(),
            ancestors = mutableListOf(),
        )

    private fun readValue(
        index: Int,
        depth: Int,
        budget: ReadBudget,
        ancestors: MutableList<Int>,
    ): LuaValue {
        require(depth <= MAX_VALUE_DEPTH) { "value exceeds depth limit" }
        return when (callbackLua.type(index)) {
            Lua.LuaType.NIL, Lua.LuaType.NONE -> LuaValue.Nil
            Lua.LuaType.BOOLEAN -> LuaValue.Bool(callbackLua.toBoolean(index))
            Lua.LuaType.NUMBER -> {
                if (callbackLua.isInteger(index)) {
                    LuaValue.Integer(callbackLua.toInteger(index))
                } else {
                    val number = callbackLua.toNumber(index)
                    require(number.isFinite()) { "number must be finite" }
                    LuaValue.Real(number)
                }
            }
            Lua.LuaType.STRING -> {
                val buffer = checkNotNull(callbackLua.toBuffer(index))
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                require(bytes.size <= MAX_STRING_BYTES) { "string exceeds byte limit" }
                val text = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString()
                LuaValue.StringValue(text)
            }
            Lua.LuaType.TABLE -> readTable(index, depth, budget, ancestors)
            Lua.LuaType.LIGHTUSERDATA -> {
                val pointer = callbackLua.getLuaNatives()
                    .lua_touserdata(callbackLua.getPointer(), index)
                LuaValue.StringValue(
                    resolveOpaque(pointer)
                        ?: throw IllegalArgumentException("stale opaque handle"),
                )
            }
            else -> throw IllegalArgumentException("unsupported host request value")
        }
    }

    private fun readTable(
        index: Int,
        depth: Int,
        budget: ReadBudget,
        ancestors: MutableList<Int>,
    ): LuaValue {
        require(callbackLua.getMetatable(index) == 0) {
            callbackLua.pop(1)
            "tables with metatables are not accepted"
        }
        for (ancestor in ancestors) {
            callbackLua.refGet(ancestor)
            val cycle = callbackLua.rawEqual(index, callbackLua.getTop())
            callbackLua.pop(1)
            require(!cycle) { "value contains a cycle" }
        }
        callbackLua.pushValue(index)
        val self = callbackLua.ref()
        ancestors.add(self)
        return try {
            val integers = HashMap<Long, LuaValue>()
            val strings = LinkedHashMap<String, LuaValue>()
            callbackLua.pushNil()
            while (callbackLua.next(index) != 0) {
                budget.entries++
                require(budget.entries <= MAX_VALUE_ENTRIES) { "value exceeds entry limit" }
                val keyIndex = callbackLua.getTop() - 1
                val valueIndex = callbackLua.getTop()
                when (callbackLua.type(keyIndex)) {
                    Lua.LuaType.NUMBER -> {
                        require(callbackLua.isInteger(keyIndex)) { "non-integer table key" }
                        val key = callbackLua.toInteger(keyIndex)
                        require(key >= 1L) { "out-of-range table key" }
                        integers[key] = readValue(valueIndex, depth + 1, budget, ancestors)
                    }
                    Lua.LuaType.STRING -> {
                        val key = callbackLua.toString(keyIndex) ?: ""
                        require(key.toByteArray(Charsets.UTF_8).size <= MAX_STRING_BYTES) {
                            "key exceeds byte limit"
                        }
                        strings[key] = readValue(valueIndex, depth + 1, budget, ancestors)
                    }
                    else -> throw IllegalArgumentException("unsupported table key")
                }
                callbackLua.pop(1)
            }
            require(integers.isEmpty() || strings.isEmpty()) { "mixed table keys" }
            if (integers.isEmpty()) {
                LuaValue.Map(strings)
            } else {
                val values = ArrayList<LuaValue>(integers.size)
                for (i in 1L..integers.size.toLong()) {
                    values += integers[i] ?: throw IllegalArgumentException("sparse array")
                }
                LuaValue.Array(values)
            }
        } finally {
            ancestors.removeAt(ancestors.lastIndex)
            callbackLua.unref(self)
        }
    }

    private class ReadBudget(var entries: Int = 0)

    private fun workOwnerAt(index: Int): WorkOpaqueOwner {
        if (callbackLua.type(index) == Lua.LuaType.LIGHTUSERDATA) {
            val pointer = callbackLua.getLuaNatives()
                .lua_touserdata(callbackLua.getPointer(), index)
            return resolveWorkOwner(pointer)
                ?: throw IllegalArgumentException("stale work handle")
        }
        val owner = mapAtOrEmpty(index)
        return WorkOpaqueOwner(
            queue = owner.string("queue")
                ?: throw IllegalArgumentException("missing queue owner"),
            job = owner.string("job"),
        )
    }

    private fun mapAt(index: Int): Map<String, LuaValue> =
        (readValue(index) as? LuaValue.Map)?.pairs
            ?: throw IllegalArgumentException("expected table")

    private fun mapAtOrEmpty(index: Int): Map<String, LuaValue> =
        if (callbackLua.type(index) == Lua.LuaType.TABLE) mapAt(index) else emptyMap()

    private fun opaquePointer(index: Int): Long? =
        if (callbackLua.type(index) == Lua.LuaType.LIGHTUSERDATA) {
            callbackLua.getLuaNatives()
                .lua_touserdata(callbackLua.getPointer(), index)
        } else {
            null
        }

    private fun opaqueString(index: Int): String? = when (callbackLua.type(index)) {
        Lua.LuaType.STRING -> callbackLua.toString(index)
        Lua.LuaType.LIGHTUSERDATA ->
            resolveOpaque(
                callbackLua.getLuaNatives().lua_touserdata(callbackLua.getPointer(), index),
            )
        else -> null
    }

    private fun workJson(value: LuaValue): String = encodeValue(value.toWorkValue()).toString()

    private fun LuaValue.toWorkValue(): WorkValue = when (this) {
        LuaValue.Nil -> WorkValue.Null
        is LuaValue.Bool -> WorkValue.Bool(value)
        is LuaValue.Integer -> WorkValue.Integer(value)
        is LuaValue.Real -> WorkValue.Real(value)
        is LuaValue.StringValue -> WorkValue.Text(value)
        is LuaValue.Array -> WorkValue.List(values.map { it.toWorkValue() })
        is LuaValue.Map -> WorkValue.Map(
            pairs.entries.map { (key, value) -> WorkMapEntry(key, value.toWorkValue()) },
        )
    }

    private fun admitted(
        requestId: Long,
        kind: HostOperationKind,
        audioToken: String? = null,
        text: String? = null,
        language: String? = null,
        voice: String? = null,
        speed: Double = 0.0,
        delaySeconds: Double = 0.0,
        feedbackTone: io.talkcan.service.CaptureFeedbackTone? = null,
        declarationId: String? = null,
        mountToken: String? = null,
        path: String? = null,
        parents: Boolean = false,
        limit: Long = 0,
        cursor: String? = null,
        maxBytes: Long = 0,
        mode: String? = null,
        missingOk: Boolean = false,
        format: String? = null,
        profile: String? = null,
        key: String? = null,
        referenceToken: String? = null,
        method: String? = null,
        url: String? = null,
        headersJson: String? = null,
        body: String? = null,
        timeoutMs: Long = 0,
        queue: String? = null,
        payloadJson: String? = null,
        job: String? = null,
        effectKey: String? = null,
        fingerprint: String? = null,
        resultOk: Boolean = false,
        resultJson: String? = null,
        reasonJson: String? = null,
    ) = HostOperationClaim.Admitted(
        requestId = requestId,
        kind = kind,
        audioToken = audioToken,
        text = text,
        language = language,
        voice = voice,
        speed = speed,
        delaySeconds = delaySeconds,
        feedbackTone = feedbackTone,
        declarationId = declarationId,
        mountToken = mountToken,
        path = path,
        parents = parents,
        limit = limit,
        cursor = cursor,
        maxBytes = maxBytes,
        mode = mode,
        missingOk = missingOk,
        format = format,
        profile = profile,
        key = key,
        referenceToken = referenceToken,
        method = method,
        url = url,
        headersJson = headersJson,
        body = body,
        timeoutMs = timeoutMs,
        queue = queue,
        payloadJson = payloadJson,
        job = job,
        effectKey = effectKey,
        fingerprint = fingerprint,
        resultOk = resultOk,
        resultJson = resultJson,
        reasonJson = reasonJson,
    )

    private fun Map<String, LuaValue>.string(key: String): String? =
        (get(key) as? LuaValue.StringValue)?.value

    private fun Map<String, LuaValue>.number(key: String, default: Double): Double =
        when (val value = get(key)) {
            is LuaValue.Integer -> value.value.toDouble()
            is LuaValue.Real -> value.value
            else -> default
        }

    private fun Map<String, LuaValue>.long(key: String): Long =
        when (val value = get(key)) {
            is LuaValue.Integer -> value.value
            is LuaValue.Real -> value.value.toLong()
            else -> 0L
        }

    private fun Map<String, LuaValue>.bool(key: String): Boolean =
        (get(key) as? LuaValue.Bool)?.value ?: false

    private class RequestRejected(
        val code: String,
        val kind: HostOperationKind? = null,
    ) : RuntimeException()

    private fun reject(code: String, kind: HostOperationKind? = null): Nothing =
        throw RequestRejected(code, kind)

    private companion object {
        const val MAX_VALUE_DEPTH = 32
        const val MAX_VALUE_ENTRIES = 4_096
        const val MAX_STRING_BYTES = 256 * 1_024
        val HTTP_REQUEST_FIELDS =
            setOf("method", "url", "headers", "body", "timeout_ms")
        val HTTP_METHODS =
            setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        val NEXT_REQUEST_ID = AtomicLong(1L)
    }
}
