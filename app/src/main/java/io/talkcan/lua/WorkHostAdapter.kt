package io.talkcan.lua

import io.talkcan.work.DurableWorkCoordinator
import io.talkcan.work.DurableWorkStore
import io.talkcan.work.WorkClaimRelease
import io.talkcan.work.WorkEffectBegin
import io.talkcan.work.WorkEffectResult
import io.talkcan.work.WorkId
import io.talkcan.work.WorkInstanceId
import io.talkcan.work.WorkQueueId
import io.talkcan.work.WorkQueuePartition
import io.talkcan.work.WorkReceiveOutcome
import io.talkcan.work.WorkRepositoryId
import io.talkcan.work.WorkStoreFailure
import io.talkcan.work.WorkStoreResult
import io.talkcan.work.WorkValue
import io.talkcan.work.decodeValue
import io.talkcan.work.encodeValue
import org.json.JSONObject
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Typed host adapter for the durable `talkcan.work` operations (task 12.10).
 *
 * One adapter serves one runtime generation of one channel instance. It maps
 * the declared package-local queue id carried in claims to the durable
 * partition, mints opaque job tokens for claimed work, and drives the
 * existing [DurableWorkStore]/[DurableWorkCoordinator] through their typed
 * results. No work id, sequence, partition, lease, transaction, or database
 * object appears in a resume value: Lua observes only the opaque job token,
 * detached payloads in the shared tagged value encoding, and normalized
 * `E_*` failures.
 *
 * The effect bracket (task 12.6/12.7) is stateless here by design: the kernel runs
 * the nested yield-capable function between WORK_BEGIN_EFFECT and
 * WORK_COMMIT_EFFECT and may interleave nested SECRET_READ/HTTP_REQUEST
 * claims on the same generation; each claim is answered independently and a
 * started-but-uncommitted effect reconciles to indeterminate on [close].
 */
internal class WorkHostAdapter(
    private val store: DurableWorkStore,
    private val coordinator: DurableWorkCoordinator,
    private val repositoryId: WorkRepositoryId,
    private val instanceId: WorkInstanceId,
    private val holderToken: String,
    private val declaredQueues: Set<String>? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val jobTokenGenerator: () -> String = { UUID.randomUUID().toString() },
) : TypedHostOperationAdapter {
    init {
        require(holderToken.isNotBlank()) { "Work adapter holder token must not be blank" }
    }

    private data class JobBinding(
        val workId: WorkId,
        val partition: WorkQueuePartition,
    )

    /** Opaque job token → durable binding. The token never leaves the host. */
    private val jobs = ConcurrentHashMap<String, JobBinding>()

    override suspend fun complete(claim: HostOperationClaim.Admitted): TypedHostCompletion =
        when (claim.kind) {
            HostOperationKind.WORK_SUBMIT -> submit(claim)
            HostOperationKind.WORK_RECEIVE -> receive(claim)
            HostOperationKind.WORK_BEGIN_EFFECT -> beginEffect(claim)
            HostOperationKind.WORK_COMMIT_EFFECT -> commitEffect(claim)
            HostOperationKind.WORK_COMPLETE -> completeWork(claim)
            HostOperationKind.WORK_FAIL -> failWork(claim)
            else -> TypedHostCompletion.failure("E_DENIED")
        }

    /**
     * Generation close (host side of task 12.9): every active claim held by
     * this generation is safely released — returned to QUEUED in place when
     * no effect started, terminally indeterminate when one did. Durable
     * records remain host-owned; late completions afterwards resolve no
     * binding and fail denied.
     */
    fun close(atMillis: Long = clock.millis()) {
        val snapshot = jobs.keys.toList()
        for (token in snapshot) {
            val binding = jobs.remove(token) ?: continue
            store.releaseClaim(binding.workId, holderToken, atMillis)
        }
    }

    val activeJobCount: Int get() = jobs.size

    /**
     * Content-free instance work projection for channel snapshots (task 14.1/14.2,
     * design D12). Folds this generation's per-queue durable-work metadata through
     * [io.talkcan.service.projectGenericWork]; carries phase, bounded
     * queued count, and active presence only — never a payload, effect key/result,
     * transcript, prompt, response, tool argument, profile field, or secret value.
     */
    fun workProjection(): io.talkcan.service.GenericWorkProjection =
        io.talkcan.service.projectGenericWork(coordinator.instanceProjection(instanceId))

    // ── Queue:submit ─────────────────────────────────────────────────────

    private fun submit(claim: HostOperationClaim.Admitted): TypedHostCompletion {
        val partition = partitionFor(claim.queue)
            ?: return TypedHostCompletion.failure("E_INVALID_ARGUMENT")
        val payload = decodePayload(claim.payloadJson)
            ?: return TypedHostCompletion.failure("E_INVALID_VALUE")
        return when (val result = store.submit(partition, payload, clock.millis())) {
            is WorkStoreResult.Success -> TypedHostCompletion(
                success = true,
                value = JSONObject().put("sequence", result.value.sequence).toString(),
            )
            is WorkStoreResult.Failure -> TypedHostCompletion.failure(result.failure.normalizedCode())
        }
    }

    // ── Queue:receive ────────────────────────────────────────────────────

    private suspend fun receive(claim: HostOperationClaim.Admitted): TypedHostCompletion {
        val partition = partitionFor(claim.queue)
            ?: return TypedHostCompletion.failure("E_INVALID_ARGUMENT")
        return when (val result = coordinator.receive(partition, holderToken, clock.millis())) {
            is WorkStoreResult.Success -> when (val outcome = result.value) {
                is WorkReceiveOutcome.Claimed -> {
                    val jobToken = jobTokenGenerator()
                    jobs[jobToken] = JobBinding(outcome.claim.work.id, partition)
                    TypedHostCompletion(
                        success = true,
                        value = JSONObject()
                            .put("jobId", jobToken)
                            .put("payloadJson", encodeValue(outcome.claim.payload).toString())
                            .toString(),
                    )
                }
                WorkReceiveOutcome.Closed -> TypedHostCompletion(
                    success = true,
                    value = JSONObject().put("closed", true).toString(),
                )
            }
            is WorkStoreResult.Failure -> TypedHostCompletion.failure(result.failure.normalizedCode())
        }
    }

    // ── Job:effect bracket ───────────────────────────────────────────────

    private fun beginEffect(claim: HostOperationClaim.Admitted): TypedHostCompletion {
        val binding = jobs[claim.job ?: ""]
            ?: return TypedHostCompletion.failure("E_DENIED")
        val key = claim.effectKey?.takeIf { it.isNotEmpty() }
            ?: return TypedHostCompletion.failure("E_INVALID_ARGUMENT")
        val fingerprint = claim.fingerprint
            ?: return TypedHostCompletion.failure("E_INVALID_ARGUMENT")
        return when (val result = store.beginEffect(binding.workId, holderToken, key, fingerprint)) {
            is WorkStoreResult.Success -> when (val begin = result.value) {
                is WorkEffectBegin.Started -> TypedHostCompletion(
                    success = true,
                    value = JSONObject().put("started", true).toString(),
                )
                is WorkEffectBegin.Replay -> {
                    val resultValue = when (begin.result) {
                        is WorkEffectResult.Success -> begin.result.value
                        is WorkEffectResult.Error -> begin.result.value
                    }
                    TypedHostCompletion(
                        success = true,
                        value = JSONObject()
                            .put("replay", true)
                            .put("resultOk", begin.result is WorkEffectResult.Success)
                            .put("resultJson", encodeValue(resultValue).toString())
                            .toString(),
                    )
                }
            }
            is WorkStoreResult.Failure -> TypedHostCompletion.failure(result.failure.normalizedCode())
        }
    }

    private fun commitEffect(claim: HostOperationClaim.Admitted): TypedHostCompletion {
        val binding = jobs[claim.job ?: ""]
            ?: return TypedHostCompletion.failure("E_DENIED")
        val key = claim.effectKey?.takeIf { it.isNotEmpty() }
            ?: return TypedHostCompletion.failure("E_INVALID_ARGUMENT")
        val resultValue = decodePayload(claim.resultJson)
            ?: return TypedHostCompletion.failure("E_INVALID_VALUE")
        val effectResult: WorkEffectResult = if (claim.resultOk) {
            WorkEffectResult.Success(resultValue)
        } else {
            WorkEffectResult.Error(resultValue)
        }
        return when (val result = store.commitEffect(binding.workId, holderToken, key, effectResult)) {
            is WorkStoreResult.Success -> OK
            is WorkStoreResult.Failure -> TypedHostCompletion.failure(result.failure.normalizedCode())
        }
    }

    // ── Job terminal transitions ─────────────────────────────────────────

    private fun completeWork(claim: HostOperationClaim.Admitted): TypedHostCompletion {
        val token = claim.job ?: return TypedHostCompletion.failure("E_INVALID_ARGUMENT")
        val binding = jobs[token] ?: return TypedHostCompletion.failure("E_DENIED")
        // Optional resultJson is tombstone metadata only; the durable store
        // retains bounded identity/class, never provider-shaped result bytes.
        return when (val result = store.completeWork(binding.workId, holderToken, clock.millis(), playbackHandedOff = false)) {
            is WorkStoreResult.Success -> {
                jobs.remove(token)
                OK
            }
            is WorkStoreResult.Failure -> {
                if (result.failure is WorkStoreFailure.Conflict) jobs.remove(token)
                TypedHostCompletion.failure(result.failure.normalizedCode())
            }
        }
    }

    private fun failWork(claim: HostOperationClaim.Admitted): TypedHostCompletion {
        val token = claim.job ?: return TypedHostCompletion.failure("E_INVALID_ARGUMENT")
        val binding = jobs[token] ?: return TypedHostCompletion.failure("E_DENIED")
        val reasonTag = reasonTagOf(claim.reasonJson)
        return when (val result = store.failWork(binding.workId, holderToken, reasonTag, clock.millis(), playbackHandedOff = false)) {
            is WorkStoreResult.Success -> {
                jobs.remove(token)
                OK
            }
            is WorkStoreResult.Failure -> {
                if (result.failure is WorkStoreFailure.Conflict) jobs.remove(token)
                TypedHostCompletion.failure(result.failure.normalizedCode())
            }
        }
    }

    // ── Normalization helpers ────────────────────────────────────────────

    private fun partitionFor(queueId: String?): WorkQueuePartition? {
        val id = queueId?.takeIf { it.isNotBlank() } ?: return null
        if (declaredQueues != null && id !in declaredQueues) return null
        return WorkQueuePartition(repositoryId, instanceId, WorkQueueId(id))
    }

    /** Decode one shared tagged WorkValue document; any malformation → null. */
    private fun decodePayload(json: String?): WorkValue? {
        json ?: return null
        return try {
            decodeValue(JSONObject(json))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Derive a bounded, content-bounded reason tag from the normalized
     * failure value. Only a text value contributes its bounded prefix;
     * anything else becomes the neutral `failed` tag. The durable tombstone
     * never carries the raw reason document.
     */
    private fun reasonTagOf(reasonJson: String?): String {
        val value = decodePayload(reasonJson) as? WorkValue.Text ?: return NEUTRAL_REASON_TAG
        return value.value.takeUtf8Bytes(MAX_REASON_TAG_BYTES).ifBlank { NEUTRAL_REASON_TAG }
    }

    companion object {
        private val OK = TypedHostCompletion(true, JSONObject().put("ok", true).toString())
        private const val NEUTRAL_REASON_TAG = "failed"
        private const val MAX_REASON_TAG_BYTES = 64
    }
}

/** Normalization into the WORK_* allowlist (default E_STORE). */
internal fun WorkStoreFailure.normalizedCode(): String = when (this) {
    is WorkStoreFailure.InvalidValue -> "E_INVALID_VALUE"
    is WorkStoreFailure.TooLarge -> "E_TOO_LARGE"
    WorkStoreFailure.Busy -> "E_BUSY"
    is WorkStoreFailure.Conflict -> "E_STORE"
    is WorkStoreFailure.NotFound -> "E_NOT_FOUND"
    is WorkStoreFailure.Stale -> "E_STALE"
    is WorkStoreFailure.Corrupted -> "E_STORE"
    is WorkStoreFailure.Storage -> "E_STORE"
}

/** Bounded UTF-8 prefix that never splits a code point. */
internal fun String.takeUtf8Bytes(maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    var limit = 0
    var bytes = 0
    while (limit < length) {
        val codePoint = codePointAt(limit)
        val size = when {
            codePoint <= 0x7FF -> if (codePoint <= 0x7F) 1 else 2
            else -> if (codePoint <= 0xFFFF) 3 else 4
        }
        if (bytes + size > maxBytes) break
        bytes += size
        limit += Character.charCount(codePoint)
    }
    return substring(0, limit)
}
