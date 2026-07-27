package io.talkcan.service

import io.talkcan.http.GenericHttpFailure
import io.talkcan.lua.normalizedCode
import io.talkcan.secret.ProtectedSecretError
import io.talkcan.work.WorkEpoch
import io.talkcan.work.WorkInstanceId
import io.talkcan.work.WorkQueueAvailability
import io.talkcan.work.WorkQueueId
import io.talkcan.work.WorkQueuePartition
import io.talkcan.work.WorkQueueProjection
import io.talkcan.work.WorkRepositoryId
import io.talkcan.work.WorkTerminalClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused projection/privacy/failure-isolation contract for tasks 14.3-14.5.
 *
 * The generic durable-work projection ([projectGenericWork] ->
 * [GenericWorkProjection]) is the host-owned summary of queue state that may
 * reach a channel snapshot and presentation. It MUST carry only a phase, a
 * bounded queued count, and active presence — never a payload, effect key or
 * result, transcript, prompt, response, tool argument, profile field, secret
 * value, queue identity, or terminal reason. These tests inject sensitive
 * sentinel content into the work metadata that feeds the projection and prove
 * the sentinel never crosses the projection boundary, while bounded
 * identity/phase/count/status DO escape (14.5).
 *
 * They also prove failure isolation (14.4): one corrupted or malformed queue
 * partition is folded into the aggregate without throwing and without
 * disturbing sibling counts, and oversized/negative counts are coerced to the
 * finite projection bound. And they audit the normalized diagnostic codes
 * (14.3): HTTP and protected-secret failures reduce to a bounded allowlist of
 * `E_*` codes that never carry the offending field content.
 */
class WorkProjectionPrivacyContractTest {

    // ── 14.5: sensitive sentinels never escape the projection boundary ───────

    @Test
    fun `queue identity and terminal content stay inside the projection boundary`() {
        val sentinelQueue = "SENTINEL_QUEUE_secret_payload"
        val sentinelInstance = "SENTINEL_INSTANCE_channel_transcript"
        val projection = projection(
            queueId = sentinelQueue,
            instanceId = sentinelInstance,
            queuedCount = 3,
            activePresent = true,
            lastTerminalClass = WorkTerminalClass.FAILED,
        )

        // Sanity: the source metadata genuinely carries the sentinels.
        assertTrue(projection.toString().contains(sentinelQueue))
        assertTrue(projection.toString().contains(sentinelInstance))

        val result = projectGenericWork(listOf(projection))

        // Bounded status escapes: phase, count, active presence.
        assertEquals(GenericWorkPhase.FAILED, result.phase)
        assertEquals(3, result.queuedCount)
        assertTrue(result.activePresent)
        // Content does not: no sentinel reaches the projection or its rendering.
        assertFalse(result.toString().contains(sentinelQueue))
        assertFalse(result.toString().contains(sentinelInstance))
        assertFalse(result.toString().contains("secret_payload"))
        assertFalse(result.toString().contains("channel_transcript"))
    }

    @Test
    fun `aggregating many sentinel-laden queues exposes only summed count and phase`() {
        val sentinelA = "SENTINEL_A_prompt_body"
        val sentinelB = "SENTINEL_B_model_response"
        val result = projectGenericWork(
            listOf(
                projection(queueId = sentinelA, queuedCount = 2, activePresent = false, lastTerminalClass = null),
                projection(queueId = sentinelB, queuedCount = 4, activePresent = true, lastTerminalClass = null),
            ),
        )

        assertEquals(GenericWorkPhase.ACTIVE, result.phase)
        assertEquals(6, result.queuedCount)
        assertTrue(result.activePresent)
        assertFalse(result.toString().contains("prompt_body"))
        assertFalse(result.toString().contains("model_response"))
    }

    @Test
    fun `indeterminate terminal exposes phase only, never a reason sentinel`() {
        val sentinelReason = "SENTINEL_REASON_effect_result"
        val result = projectGenericWork(
            listOf(
                projection(queueId = sentinelReason, queuedCount = 0, activePresent = false, lastTerminalClass = WorkTerminalClass.INDETERMINATE),
            ),
        )

        assertEquals(GenericWorkPhase.INDETERMINATE, result.phase)
        assertFalse(result.toString().contains("effect_result"))
    }

    // ── 14.4: sibling malformed subsystem isolation ──────────────────────────

    @Test
    fun `one corrupted queue isolates to indeterminate without disturbing sibling counts`() {
        val result = projectGenericWork(
            listOf(
                projection(queuedCount = 2, activePresent = false, availability = WorkQueueAvailability.AVAILABLE),
                projection(queuedCount = 5, activePresent = false, availability = WorkQueueAvailability.CORRUPTED),
            ),
        )

        // The corrupted partition forces the aggregate to the ambiguous phase but
        // does not throw and does not drop the sibling's queued count.
        assertEquals(GenericWorkPhase.INDETERMINATE, result.phase)
        assertEquals(7, result.queuedCount)
        assertFalse(result.activePresent)
    }

    @Test
    fun `a malformed negative queued count is coerced and never surfaces negative`() {
        val result = projectGenericWork(
            listOf(projection(queuedCount = -100, activePresent = false, lastTerminalClass = null)),
        )

        assertEquals(0, result.queuedCount)
        assertEquals(GenericWorkPhase.IDLE, result.phase)
    }

    @Test
    fun `an oversized queued count clamps to the finite projection bound`() {
        val result = projectGenericWork(
            listOf(projection(queuedCount = Int.MAX_VALUE, activePresent = false, lastTerminalClass = null)),
        )

        assertEquals(GenericWorkProjection.MAX_QUEUED_COUNT, result.queuedCount)
    }

    @Test
    fun `the projection itself rejects negative and over-bound counts`() {
        assertThrows(IllegalArgumentException::class.java) {
            GenericWorkProjection(GenericWorkPhase.IDLE, queuedCount = -1, activePresent = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenericWorkProjection(GenericWorkPhase.QUEUED, queuedCount = GenericWorkProjection.MAX_QUEUED_COUNT + 1, activePresent = false)
        }
    }

    @Test
    fun `phase priority is indeterminate over failed over active over queued over idle`() {
        // indeterminate dominates failed
        assertEquals(
            GenericWorkPhase.INDETERMINATE,
            projectGenericWork(
                listOf(
                    projection(lastTerminalClass = WorkTerminalClass.FAILED),
                    projection(lastTerminalClass = WorkTerminalClass.INDETERMINATE),
                ),
            ).phase,
        )
        // failed dominates active
        assertEquals(
            GenericWorkPhase.FAILED,
            projectGenericWork(
                listOf(
                    projection(activePresent = true, lastTerminalClass = null),
                    projection(lastTerminalClass = WorkTerminalClass.FAILED),
                ),
            ).phase,
        )
        // active dominates queued
        assertEquals(
            GenericWorkPhase.ACTIVE,
            projectGenericWork(
                listOf(projection(queuedCount = 3, activePresent = true, lastTerminalClass = null)),
            ).phase,
        )
        // queued dominates idle
        assertEquals(
            GenericWorkPhase.QUEUED,
            projectGenericWork(listOf(projection(queuedCount = 1, activePresent = false, lastTerminalClass = null))).phase,
        )
        // empty is idle
        assertEquals(GenericWorkPhase.IDLE, projectGenericWork(emptyList()).phase)
        assertEquals(GenericWorkProjection.Idle, projectGenericWork(emptyList()))
    }

    // ── 14.3: normalized diagnostics are bounded and content-free ────────────

    @Test
    fun `http failures normalize to bounded codes that never carry field content`() {
        val sentinel = "SENTINEL_HEADER_bearer_secret"
        val invalidArgument = GenericHttpFailure.InvalidArgument(field = sentinel).normalizedCode()
        val invalidValue = GenericHttpFailure.InvalidValue(field = sentinel).normalizedCode()

        assertEquals("E_INVALID_ARGUMENT", invalidArgument)
        assertEquals("E_INVALID_VALUE", invalidValue)
        assertFalse(invalidArgument.contains(sentinel))
        assertFalse(invalidValue.contains(sentinel))
        assertFalse(invalidArgument.contains("bearer_secret"))

        assertEquals("E_TOO_LARGE", GenericHttpFailure.TooLarge.normalizedCode())
        assertEquals("E_BUSY", GenericHttpFailure.Busy.normalizedCode())
        assertEquals("E_TIMEOUT", GenericHttpFailure.Timeout.normalizedCode())
        assertEquals("E_CANCELLED", GenericHttpFailure.Cancelled.normalizedCode())
        assertEquals("E_TRANSPORT", GenericHttpFailure.TransportUnavailable.normalizedCode())
    }

    @Test
    fun `protected secret failures normalize to a bounded nondisclosing allowlist`() {
        val codes = setOf(
            ProtectedSecretError.NotFound.normalizedCode(),
            ProtectedSecretError.Denied.normalizedCode(),
            ProtectedSecretError.TooLarge.normalizedCode(),
            ProtectedSecretError.InvalidUtf8.normalizedCode(),
            ProtectedSecretError.InvalidValue.normalizedCode(),
            ProtectedSecretError.StorageUnavailable.normalizedCode(),
            ProtectedSecretError.CorruptValue.normalizedCode(),
            ProtectedSecretError.TooManyConcurrentOperations.normalizedCode(),
        )

        // Every code is one of the fixed nondisclosing allowlist entries.
        val allowlist = setOf(
            "E_NOT_FOUND", "E_DENIED", "E_TOO_LARGE", "E_INVALID_VALUE",
            "E_STORAGE", "E_BUSY",
        )
        assertTrue("all secret failure codes must be bounded allowlist entries: $codes", allowlist.containsAll(codes))
        // Exact mapping for the diagnostic-stability-critical cases.
        assertEquals("E_NOT_FOUND", ProtectedSecretError.NotFound.normalizedCode())
        assertEquals("E_DENIED", ProtectedSecretError.Denied.normalizedCode())
        assertEquals("E_STORAGE", ProtectedSecretError.StorageUnavailable.normalizedCode())
        assertEquals("E_STORAGE", ProtectedSecretError.CorruptValue.normalizedCode())
        assertEquals("E_BUSY", ProtectedSecretError.TooManyConcurrentOperations.normalizedCode())
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun projection(
        queueId: String = "turns",
        instanceId: String = "instance-1",
        repositoryId: Long = 42L,
        queuedCount: Int = 0,
        activePresent: Boolean = false,
        availability: WorkQueueAvailability = WorkQueueAvailability.AVAILABLE,
        lastTerminalClass: WorkTerminalClass? = null,
    ): WorkQueueProjection = WorkQueueProjection(
        partition = WorkQueuePartition(
            repositoryId = WorkRepositoryId(repositoryId),
            instanceId = WorkInstanceId(instanceId),
            queueId = WorkQueueId(queueId),
        ),
        epoch = WorkEpoch(1L),
        availability = availability,
        queuedCount = queuedCount,
        activePresent = activePresent,
        lastTerminalClass = lastTerminalClass,
    )
}
