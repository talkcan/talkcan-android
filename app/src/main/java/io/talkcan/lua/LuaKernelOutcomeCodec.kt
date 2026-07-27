package io.talkcan.lua

import org.json.JSONException
import org.json.JSONObject

/**
 * Strict decoder for the JSON outcome objects returned by the native kernel
 * bridge. Malformed or unknown JSON normalizes to a
 * [LuaKernelOutcome.RuntimeFailure] rather than throwing.
 *
 * Required `kind` values are mapped to [LuaKernelOutcome] variants. Unknown
 * `kind` values, missing required fields, or malformed JSON all normalize to
 * [LuaKernelOutcome.RuntimeFailure] with a diagnostic string.
 *
 * Optional fields: `stateId`, `generation`, `coroutineId`, `operationId`,
 * `value`, `diagnostic`, `elapsedNanos`, `luaVersion`, `bindingVersion`,
 * `topology`.
 */
internal object LuaKernelOutcomeCodec {

    /**
     * Decode a JSON outcome string. Never throws for expected input;
     * malformed or unknown JSON returns [LuaKernelOutcome.RuntimeFailure].
     */
    fun decode(json: String): LuaKernelOutcome {
        val root: JSONObject = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = null,
                generation = null,
                diagnostic = "malformed outcome json: ${e.message}",
            )
        } catch (e: Exception) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = null,
                generation = null,
                diagnostic = "unexpected decode error: ${e.javaClass.simpleName}",
            )
        }

        val kind: String = try {
            root.getString("kind")
        } catch (e: JSONException) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = optLong(root, "stateId"),
                generation = optLong(root, "generation"),
                diagnostic = "missing required 'kind' field",
            )
        }

        return try {
            when (kind) {
                "created" -> decodeCreated(root)
                "completed" -> decodeCompleted(root)
                "yielded" -> decodeYielded(root)
                "syntax_failure" -> decodeSyntaxFailure(root)
                "validation_failure" -> decodeValidationFailure(root)
                "runtime_failure" -> decodeRuntimeFailure(root)
                "interrupted" -> decodeInterrupted(root)
                "cancelled" -> decodeCancelled(root)
                "invalid_ownership" -> decodeInvalidOwnership(root)
                "stale" -> decodeStale(root)
                "closed" -> decodeClosed(root)
                else -> LuaKernelOutcome.RuntimeFailure(
                    stateId = optLong(root, "stateId"),
                    generation = optLong(root, "generation"),
                    diagnostic = "unknown outcome kind: $kind",
                )
            }
        } catch (e: Exception) {
            LuaKernelOutcome.RuntimeFailure(
                stateId = optLong(root, "stateId"),
                generation = optLong(root, "generation"),
                diagnostic = "malformed outcome json: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private fun decodeCreated(root: JSONObject): LuaKernelOutcome {
        val stateId = reqLong(root, "stateId") ?: return failure(root, "created: missing stateId")
        val generation = reqLong(root, "generation") ?: return failure(root, "created: missing generation")
        val luaVersion = reqString(root, "luaVersion") ?: return failure(root, "created: missing luaVersion")
        val bindingVersion = reqString(root, "bindingVersion") ?: return failure(root, "created: missing bindingVersion")
        val topology = reqString(root, "topology") ?: return failure(root, "created: missing topology")
        return LuaKernelOutcome.Created(
            stateId = stateId,
            generation = generation,
            luaVersion = luaVersion,
            bindingVersion = bindingVersion,
            topology = topology,
        )
    }

    private fun decodeCompleted(root: JSONObject): LuaKernelOutcome {
        val stateId = reqLong(root, "stateId") ?: return failure(root, "completed: missing stateId")
        val generation = reqLong(root, "generation") ?: return failure(root, "completed: missing generation")
        // The native snapshot operation emits kind="completed" with an explicit
        // "operation":"snapshot" marker. Normal load/start/resume completions
        // lack the marker. We decode marker-present as LuaKernelOutcome.Snapshot
        // and marker-absent as LuaKernelOutcome.Completed.
        val operation = optString(root, "operation")
        if (operation == "snapshot") {
            return LuaKernelOutcome.Snapshot(
                stateId = stateId,
                generation = generation,
                elapsedNanos = optLong(root, "elapsedNanos"),
                luaVersion = optString(root, "luaVersion"),
                bindingVersion = optString(root, "bindingVersion"),
                topology = optString(root, "topology"),
            )
        }
        val spawnedList = optionalLongArray(root, "spawnedCoroutines")
        val logsList = optionalStringArray(root, "logs")
        return LuaKernelOutcome.Completed(
            stateId = stateId,
            generation = generation,
            coroutineId = optLong(root, "coroutineId"),
            value = optValueJsonText(root, "value"),
            elapsedNanos = optLong(root, "elapsedNanos"),
            luaVersion = optString(root, "luaVersion"),
            bindingVersion = optString(root, "bindingVersion"),
            topology = optString(root, "topology"),
            spawnedCoroutines = spawnedList,
            logs = logsList
        )
    }

    private fun decodeYielded(root: JSONObject): LuaKernelOutcome {
        val stateId = reqLong(root, "stateId") ?: return failure(root, "yielded: missing stateId")
        val generation = reqLong(root, "generation") ?: return failure(root, "yielded: missing generation")
        val coroutineId = reqLong(root, "coroutineId") ?: return failure(root, "yielded: missing coroutineId")
        val operationId = reqLong(root, "operationId") ?: return failure(root, "yielded: missing operationId")
        val spawnedList = optionalLongArray(root, "spawnedCoroutines")
        val logsList = optionalStringArray(root, "logs")
        return LuaKernelOutcome.Yielded(
            stateId = stateId,
            generation = generation,
            coroutineId = coroutineId,
            operationId = operationId,
            value = optString(root, "value"),
            spawnedCoroutines = spawnedList,
            logs = logsList,
        )
    }

    private fun decodeSyntaxFailure(root: JSONObject): LuaKernelOutcome {
        val stateId = reqLong(root, "stateId") ?: return failure(root, "syntax_failure: missing stateId")
        val generation = reqLong(root, "generation") ?: return failure(root, "syntax_failure: missing generation")
        val diagnostic = reqString(root, "diagnostic") ?: return failure(root, "syntax_failure: missing diagnostic")
        return LuaKernelOutcome.SyntaxFailure(
            stateId = stateId,
            generation = generation,
            diagnostic = diagnostic,
        )
    }

    private fun decodeValidationFailure(root: JSONObject): LuaKernelOutcome {
        val stateId = reqLong(root, "stateId") ?: return failure(root, "validation_failure: missing stateId")
        val generation = reqLong(root, "generation") ?: return failure(root, "validation_failure: missing generation")
        val diagnostic = reqString(root, "diagnostic") ?: return failure(root, "validation_failure: missing diagnostic")
        return LuaKernelOutcome.ValidationFailure(
            stateId = stateId,
            generation = generation,
            diagnostic = diagnostic,
        )
    }

    private fun decodeRuntimeFailure(root: JSONObject): LuaKernelOutcome {
        return LuaKernelOutcome.RuntimeFailure(
            stateId = optLong(root, "stateId"),
            generation = optLong(root, "generation"),
            diagnostic = reqString(root, "diagnostic") ?: "runtime failure (no diagnostic)",
        )
    }

    private fun decodeInterrupted(root: JSONObject): LuaKernelOutcome {
        val stateId = reqLong(root, "stateId") ?: return failure(root, "interrupted: missing stateId")
        val generation = reqLong(root, "generation") ?: return failure(root, "interrupted: missing generation")
        return LuaKernelOutcome.Interrupted(
            stateId = stateId,
            generation = generation,
            diagnostic = optString(root, "diagnostic"),
            elapsedNanos = optLong(root, "elapsedNanos"),
        )
    }

    private fun decodeCancelled(root: JSONObject): LuaKernelOutcome {
        val stateId = reqLong(root, "stateId") ?: return failure(root, "cancelled: missing stateId")
        val generation = reqLong(root, "generation") ?: return failure(root, "cancelled: missing generation")
        val operationId = reqLong(root, "operationId") ?: return failure(root, "cancelled: missing operationId")
        return LuaKernelOutcome.Cancelled(
            stateId = stateId,
            generation = generation,
            operationId = operationId,
        )
    }

    private fun decodeInvalidOwnership(root: JSONObject): LuaKernelOutcome {
        return LuaKernelOutcome.InvalidOwnership(
            stateId = optLong(root, "stateId"),
            generation = optLong(root, "generation"),
            diagnostic = reqString(root, "diagnostic") ?: "invalid ownership (no diagnostic)",
        )
    }

    private fun decodeStale(root: JSONObject): LuaKernelOutcome {
        return LuaKernelOutcome.Stale(
            stateId = optLong(root, "stateId"),
            generation = optLong(root, "generation"),
            diagnostic = reqString(root, "diagnostic") ?: "stale (no diagnostic)",
        )
    }

    private fun decodeClosed(root: JSONObject): LuaKernelOutcome {
        val stateId = reqLong(root, "stateId") ?: return failure(root, "closed: missing stateId")
        val generation = reqLong(root, "generation") ?: return failure(root, "closed: missing generation")
        return LuaKernelOutcome.Closed(
            stateId = stateId,
            generation = generation,
        )
    }

    private fun failure(root: JSONObject, reason: String): LuaKernelOutcome =
        LuaKernelOutcome.RuntimeFailure(
            stateId = optLong(root, "stateId"),
            generation = optLong(root, "generation"),
            diagnostic = reason,
        )

    private fun optLong(root: JSONObject, field: String): Long? {
        if (!root.has(field) || root.isNull(field)) return null
        return strictLong(root.opt(field))
    }

    private fun reqLong(root: JSONObject, field: String): Long? {
        if (!root.has(field) || root.isNull(field)) return null
        return strictLong(root.opt(field))
    }

    private fun strictLong(raw: Any?): Long? = when (raw) {
        is Long, is Int, is Short, is Byte -> (raw as Number).toLong()
        is Double, is Float -> {
            val value = (raw as Number).toDouble()
            if (!value.isFinite() || value != Math.floor(value) ||
                value < Long.MIN_VALUE.toDouble() || value >= Long.MAX_VALUE.toDouble()
            ) null else value.toLong()
        }
        else -> null
    }

    private fun optionalLongArray(root: JSONObject, field: String): List<Long>? {
        if (!root.has(field) || root.isNull(field)) return null
        val array = root.optJSONArray(field)
            ?: throw IllegalArgumentException("$field must be an array")
        return List(array.length()) { index ->
            strictLong(array.opt(index))
                ?: throw IllegalArgumentException("$field[$index] must be an in-range integer")
        }
    }

    private fun optionalStringArray(root: JSONObject, field: String): List<String>? {
        if (!root.has(field) || root.isNull(field)) return null
        val array = root.optJSONArray(field)
            ?: throw IllegalArgumentException("$field must be an array")
        return List(array.length()) { index ->
            array.opt(index) as? String
                ?: throw IllegalArgumentException("$field[$index] must be a string")
        }
    }

    private fun optString(root: JSONObject, field: String): String? {
        if (!root.has(field) || root.isNull(field)) return null
        // Strict: accept only JSON String, not coerced numbers.
        val raw = root.opt(field)
        if (raw !is String) return null
        return raw
    }

    /** Preserves a native structured value as JSON text for LuaValue decoding. */
    private fun optValueJsonText(root: JSONObject, field: String): String? {
        if (!root.has(field) || root.isNull(field)) return null
        return when (val raw = root.opt(field)) {
            is String -> raw
            else -> raw?.toString()
        }
    }

    private fun reqString(root: JSONObject, field: String): String? {
        if (!root.has(field) || root.isNull(field)) return null
        val raw = root.opt(field)
        if (raw !is String) return null
        return raw
    }

}