package io.talkcan.channel.capability

import io.talkcan.audio.RecordedPcm
import io.talkcan.lua.LuaOpaqueAudioRegistry
import io.talkcan.lua.LuaStateGeneration
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaStateId
import io.mockk.spyk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused quota tests for [LuaOpaqueAudioRegistry] covering task 4.9.
 *
 * Four contract pillars, each defended with a tight admit sequence:
 *
 *  - **First-over-limit disposal / no token.** Each quota dimension (per-artifact
 *    retained bytes, per-artifact duration, live tokens per owner, retained
 *    bytes per owner, live tokens per generation, retained bytes per
 *    generation, process live tokens, process retained bytes) rejects the
 *    first over-limit artifact with `null`, and that artifact's
 *    [OpaqueAudioRecording.dispose] / [OpaqueSynthesizedAudio.dispose] runs
 *    exactly once — before any token is created and before any Lua userdata
 *    could be delivered.
 *
 *  - **No truncation / no partial state.** A rejected oversize artifact keeps
 *    its full sample array untouched, the registry holds no entry for it, and
 *    `accounting()` reports zero impact for the failed admit.
 *
 *  - **Sibling isolation.** Filling one execution owner's token or byte quota
 *    leaves a sibling owner admitting normally against its own quota; the
 *    shared process accountant is the only cross-registry dimension and it is
 *    observed explicitly.
 *
 *  - **Zero close accounting.** After [LuaOpaqueAudioRegistry.close] the
 *    generation's `accounting()` is identically zero (live tokens and retained
 *    bytes, per-owner map empty) and the shared [LuaOpaqueAudioRegistry.ProcessQuota]
 *    returns to zero as well.
 *
 * Disposal call counts are observed with MockK spies over the real internal
 * artifact classes; quota failures create no token, so these tests never reach
 * the task-5 synthesis/task-owned artifact path.
 */
class LuaOpaqueAudioRegistryQuotaTest {

    private val stateHandle = LuaStateHandle(LuaStateId(7), LuaStateGeneration(1))

    private fun input(id: String) = LuaOpaqueAudioRegistry.Owner.Input(id)
    private fun task(id: String) = LuaOpaqueAudioRegistry.Owner.Task(id)

    /**
     * Spied captured artifact: [sampleCount] mono 16-bit samples at
     * [sampleRate] Hz. Retained bytes = sampleCount * 2; durationMillis =
     * sampleCount * 1000 / sampleRate (0 when sampleRate <= 0).
     */
    private fun capturedSpy(
        operationId: String,
        sampleCount: Int,
        sampleRate: Int = 16_000,
    ): RecordedPcmAudioRecording =
        spyk(RecordedPcmAudioRecording(RecordedPcm(ShortArray(sampleCount), sampleRate), operationId = operationId, generation = RuntimeGeneration(0)))

    /** Spied synthesized artifact: [sampleCount] mono float32 samples. */
    private fun synthesizedSpy(operationId: String, sampleCount: Int): SynthesizedAudioArtifact =
        spyk(SynthesizedAudioArtifact(FloatArray(sampleCount), operationId = operationId, generation = RuntimeGeneration(0)))

    private fun capturedBytes(sampleCount: Int): Long = sampleCount.toLong() * 2L
    private fun synthesizedBytes(sampleCount: Int): Long = sampleCount.toLong() * 4L

    /**
     * Limits with every dimension unbounded except [block] overrides. Avoids
     * restating six `Long.MAX_VALUE`/`Int.MAX_VALUE` arguments per case.
     */
    private fun wideLimits(block: LuaOpaqueAudioRegistry.Limits.() -> LuaOpaqueAudioRegistry.Limits = { this }): LuaOpaqueAudioRegistry.Limits =
        LuaOpaqueAudioRegistry.Limits(
            maxBytesPerArtifact = Long.MAX_VALUE,
            maxDurationPerArtifactMillis = Long.MAX_VALUE,
            maxTokensPerOwner = Int.MAX_VALUE,
            maxBytesPerOwner = Long.MAX_VALUE,
            maxTokensPerGeneration = Int.MAX_VALUE,
            maxBytesPerGeneration = Long.MAX_VALUE,
        ).block()

    private fun wideProcessQuota(): LuaOpaqueAudioRegistry.ProcessQuota =
        LuaOpaqueAudioRegistry.ProcessQuota(Int.MAX_VALUE, Long.MAX_VALUE)

    private fun assertRejects(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException but no exception was thrown")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // ------------------------------------------------------------------
    // Finite-positive limit validation
    // ------------------------------------------------------------------

    @Test
    fun `Limits requires every bound to be strictly positive`() {
        val base = LuaOpaqueAudioRegistry.Limits(
            maxBytesPerArtifact = 1L,
            maxDurationPerArtifactMillis = 1L,
            maxTokensPerOwner = 1,
            maxBytesPerOwner = 1L,
            maxTokensPerGeneration = 1,
            maxBytesPerGeneration = 1L,
        )
        assertEquals(1L, base.maxBytesPerArtifact)

        assertRejects { base.copy(maxBytesPerArtifact = 0L) }
        assertRejects { base.copy(maxBytesPerArtifact = -1L) }
        assertRejects { base.copy(maxDurationPerArtifactMillis = 0L) }
        assertRejects { base.copy(maxDurationPerArtifactMillis = -1L) }
        assertRejects { base.copy(maxTokensPerOwner = 0) }
        assertRejects { base.copy(maxTokensPerOwner = -1) }
        assertRejects { base.copy(maxBytesPerOwner = 0L) }
        assertRejects { base.copy(maxBytesPerOwner = -1L) }
        assertRejects { base.copy(maxTokensPerGeneration = 0) }
        assertRejects { base.copy(maxTokensPerGeneration = -1) }
        assertRejects { base.copy(maxBytesPerGeneration = 0L) }
        assertRejects { base.copy(maxBytesPerGeneration = -1L) }
    }

    @Test
    fun `ProcessQuota requires positive tokens and bytes`() {
        LuaOpaqueAudioRegistry.ProcessQuota(1, 1L) // sanity: valid construction
        assertRejects { LuaOpaqueAudioRegistry.ProcessQuota(0, 1L) }
        assertRejects { LuaOpaqueAudioRegistry.ProcessQuota(-1, 1L) }
        assertRejects { LuaOpaqueAudioRegistry.ProcessQuota(1, 0L) }
        assertRejects { LuaOpaqueAudioRegistry.ProcessQuota(1, -1L) }
    }

    // ------------------------------------------------------------------
    // First-over-limit disposal / no token — per-artifact bytes
    // ------------------------------------------------------------------

    @Test
    fun `first captured artifact over per-artifact byte limit is disposed and admits no token`() {
        val limit = 100L
        val registry = LuaOpaqueAudioRegistry(
            stateHandle,
            wideLimits { copy(maxBytesPerArtifact = limit) },
            wideProcessQuota(),
        )
        val owner = input("owner")
        // 50 samples = 100 bytes: exactly at limit, admitted.
        val atLimit = capturedSpy("at-limit", sampleCount = 50)
        // 51 samples = 102 bytes: first over limit, rejected.
        val over = capturedSpy("over", sampleCount = 51)

        val tokenAtLimit = registry.admitCaptured(owner, atLimit)
        assertNotNull("at-limit artifact admitted", tokenAtLimit)
        verify(exactly = 0) { atLimit.dispose() }

        val tokenOver = registry.admitCaptured(owner, over)
        assertNull("over-limit artifact rejected (no token)", tokenOver)
        verify(exactly = 1) { over.dispose() }

        // Accounting reflects only the admitted artifact.
        val acc = registry.accounting()
        assertEquals(1, acc.liveTokens)
        assertEquals(capturedBytes(50), acc.retainedBytes)
    }

    @Test
    fun `first synthesized artifact over per-artifact byte limit is disposed and admits no token`() {
        val limit = 100L
        val registry = LuaOpaqueAudioRegistry(
            stateHandle,
            wideLimits { copy(maxBytesPerArtifact = limit) },
            wideProcessQuota(),
        )
        val owner = input("owner")
        // 25 floats = 100 bytes: at limit, admitted.
        val atLimit = synthesizedSpy("at-limit", sampleCount = 25)
        // 26 floats = 104 bytes: first over limit, rejected.
        val over = synthesizedSpy("over", sampleCount = 26)

        assertNotNull(registry.admitSynthesized(owner, atLimit))
        verify(exactly = 0) { atLimit.dispose() }

        assertNull(registry.admitSynthesized(owner, over))
        verify(exactly = 1) { over.dispose() }

        assertEquals(1, registry.accounting().liveTokens)
        assertEquals(synthesizedBytes(25), registry.accounting().retainedBytes)
    }

    @Test
    fun `null-duration synthesized artifact is admitted against the byte bound alone`() {
        // SynthesizedAudioArtifact reports durationMillis = null; the registry
        // must enforce bytes but not reject for absence of duration metadata.
        val registry = LuaOpaqueAudioRegistry(
            stateHandle,
            wideLimits { copy(maxBytesPerArtifact = 40L, maxDurationPerArtifactMillis = 1L) },
            wideProcessQuota(),
        )
        // 10 floats = 40 bytes: at byte limit; duration null so the tiny
        // duration limit never applies.
        val admitted = synthesizedSpy("ok", sampleCount = 10)
        val token = registry.admitSynthesized(input("owner"), admitted)
        assertNotNull("null-duration artifact admitted on bytes alone", token)
        verify(exactly = 0) { admitted.dispose() }
    }

    // ------------------------------------------------------------------
    // First-over-limit disposal / no token — per-artifact duration
    // ------------------------------------------------------------------

    @Test
    fun `first captured artifact over per-artifact duration limit is disposed and admits no token`() {
        val maxDurationMillis = 1_000L
        val registry = LuaOpaqueAudioRegistry(
            stateHandle,
            wideLimits { copy(maxDurationPerArtifactMillis = maxDurationMillis) },
            wideProcessQuota(),
        )
        val owner = input("owner")
        // 1 sample/sec: sampleCount == duration in ms exactly.
        val atLimit = capturedSpy("at-limit", sampleCount = 1_000, sampleRate = 1_000)
        val over = capturedSpy("over", sampleCount = 1_001, sampleRate = 1_000)

        assertNotNull("at-limit-duration artifact admitted", registry.admitCaptured(owner, atLimit))
        verify(exactly = 0) { atLimit.dispose() }

        assertNull("over-duration artifact rejected", registry.admitCaptured(owner, over))
        verify(exactly = 1) { over.dispose() }
    }

    // ------------------------------------------------------------------
    // First-over-limit disposal / no token — per-owner quotas
    // ------------------------------------------------------------------

    @Test
    fun `first artifact over per-owner token limit is disposed and admits no token`() {
        val maxTokensPerOwner = 2
        val registry = LuaOpaqueAudioRegistry(
            stateHandle,
            wideLimits { copy(maxTokensPerOwner = maxTokensPerOwner) },
            wideProcessQuota(),
        )
        val owner = input("owner")
        val first = capturedSpy("first", sampleCount = 1)
        val second = capturedSpy("second", sampleCount = 1)
        val third = capturedSpy("third", sampleCount = 1)

        assertNotNull(registry.admitCaptured(owner, first))
        assertNotNull(registry.admitCaptured(owner, second))
        assertNull("third token for the same owner rejected", registry.admitCaptured(owner, third))
        verify(exactly = 1) { third.dispose() }

        val ownerAccounting = registry.accounting().perOwner[owner]
        assertNotNull(ownerAccounting)
        assertEquals(maxTokensPerOwner, ownerAccounting!!.tokens)
    }

    @Test
    fun `first artifact over per-owner byte limit is disposed and admits no token`() {
        val maxBytesPerOwner = capturedBytes(2) // two 1-sample captures
        val registry = LuaOpaqueAudioRegistry(
            stateHandle,
            wideLimits { copy(maxBytesPerOwner = maxBytesPerOwner) },
            wideProcessQuota(),
        )
        val owner = input("owner")
        val first = capturedSpy("first", sampleCount = 1)
        val second = capturedSpy("second", sampleCount = 1)
        // third would push owner retained bytes from 4 to 6 > 4.
        val third = capturedSpy("third", sampleCount = 1)

        assertNotNull(registry.admitCaptured(owner, first))
        assertNotNull(registry.admitCaptured(owner, second))
        assertNull("third byte-exceeding artifact rejected", registry.admitCaptured(owner, third))
        verify(exactly = 1) { third.dispose() }

        val ownerAccounting = registry.accounting().perOwner[owner]
        assertEquals(maxBytesPerOwner, ownerAccounting?.bytes)
    }

    // ------------------------------------------------------------------
    // First-over-limit disposal / no token — per-generation quotas
    // ------------------------------------------------------------------

    @Test
    fun `first artifact over per-generation token limit is disposed and admits no token`() {
        val maxTokensPerGeneration = 2
        val registry = LuaOpaqueAudioRegistry(
            stateHandle,
            wideLimits { copy(maxTokensPerGeneration = maxTokensPerGeneration) },
            wideProcessQuota(),
        )
        // Distinct owners so per-owner isolation cannot mask the generation cap.
        val first = capturedSpy("first", sampleCount = 1)
        val second = capturedSpy("second", sampleCount = 1)
        val third = capturedSpy("third", sampleCount = 1)

        assertNotNull(registry.admitCaptured(input("A"), first))
        assertNotNull(registry.admitCaptured(input("B"), second))
        assertNull("third token in the generation rejected", registry.admitCaptured(input("C"), third))
        verify(exactly = 1) { third.dispose() }

        assertEquals(maxTokensPerGeneration, registry.accounting().liveTokens)
    }

    @Test
    fun `first artifact over per-generation byte limit is disposed and admits no token`() {
        val maxBytesPerGeneration = capturedBytes(2)
        val registry = LuaOpaqueAudioRegistry(
            stateHandle,
            wideLimits { copy(maxBytesPerGeneration = maxBytesPerGeneration) },
            wideProcessQuota(),
        )
        val first = capturedSpy("first", sampleCount = 1)
        val second = capturedSpy("second", sampleCount = 1)
        val third = capturedSpy("third", sampleCount = 1)

        assertNotNull(registry.admitCaptured(input("A"), first))
        assertNotNull(registry.admitCaptured(input("B"), second))
        assertNull("generation byte-exceeding artifact rejected", registry.admitCaptured(input("C"), third))
        verify(exactly = 1) { third.dispose() }

        assertEquals(maxBytesPerGeneration, registry.accounting().retainedBytes)
    }

    // ------------------------------------------------------------------
    // First-over-limit disposal / no token — process quotas
    // ------------------------------------------------------------------

    @Test
    fun `first artifact over process token limit is disposed and admits no token`() {
        val processQuota = LuaOpaqueAudioRegistry.ProcessQuota(maxTokens = 1, maxBytes = Long.MAX_VALUE)
        val registry = LuaOpaqueAudioRegistry(stateHandle, wideLimits(), processQuota)
        val owner = input("owner")
        val admitted = capturedSpy("admitted", sampleCount = 1)
        val over = capturedSpy("over", sampleCount = 1)

        assertNotNull(registry.admitCaptured(owner, admitted))
        assertNull("process token-exhausting artifact rejected", registry.admitCaptured(owner, over))
        verify(exactly = 1) { over.dispose() }

        assertEquals(1, processQuota.liveTokens())
    }

    @Test
    fun `first artifact over process byte limit is disposed and admits no token`() {
        val processQuota = LuaOpaqueAudioRegistry.ProcessQuota(maxTokens = Int.MAX_VALUE, maxBytes = capturedBytes(1))
        val registry = LuaOpaqueAudioRegistry(stateHandle, wideLimits(), processQuota)
        val owner = input("owner")
        val admitted = capturedSpy("admitted", sampleCount = 1)
        val over = capturedSpy("over", sampleCount = 1)

        assertNotNull(registry.admitCaptured(owner, admitted))
        assertNull("process byte-exhausting artifact rejected", registry.admitCaptured(owner, over))
        verify(exactly = 1) { over.dispose() }

        assertEquals(capturedBytes(1), processQuota.retainedBytes())
    }

    // ------------------------------------------------------------------
    // No truncation / no partial state
    // ------------------------------------------------------------------

    @Test
    fun `rejected oversize captured artifact keeps its samples intact and leaves no registry state`() {
        val limit = 100L
        val registry = LuaOpaqueAudioRegistry(
            stateHandle,
            wideLimits { copy(maxBytesPerArtifact = limit) },
            wideProcessQuota(),
        )
        val marker: Short = 13
        val sampleCount = 1_000 // 2000 bytes >> 100 limit
        val over = RecordedPcmAudioRecording(
            RecordedPcm(ShortArray(sampleCount) { marker }, 16_000),
            operationId = "over",
            generation = RuntimeGeneration(0),
        )

        val token = registry.admitCaptured(input("owner"), over)
        assertNull("oversize artifact rejected", token)

        // Not truncated: full sample array preserved, contents untouched.
        assertEquals(sampleCount, over.recording.samples.size)
        assertEquals(marker.toInt(), over.recording.samples.first().toInt())
        assertEquals(marker.toInt(), over.recording.samples.last().toInt())
        // Disposed exactly once before any userdata could be delivered.
        assertTrue("oversize artifact disposed on rejection", over.isDisposed)

        // Not partially registered: no entry, no accounting, no process impact.
        val acc = registry.accounting()
        assertEquals(0, acc.liveTokens)
        assertEquals(0L, acc.retainedBytes)
        assertTrue(acc.perOwner.isEmpty())
    }

    @Test
    fun `rejected oversize synthesized artifact keeps its samples intact and leaves no registry state`() {
        val limit = 100L
        val registry = LuaOpaqueAudioRegistry(
            stateHandle,
            wideLimits { copy(maxBytesPerArtifact = limit) },
            wideProcessQuota(),
        )
        val marker = 0.5f
        val sampleCount = 1_000 // 4000 bytes >> 100 limit
        val over = SynthesizedAudioArtifact(FloatArray(sampleCount) { marker }, operationId = "over", generation = RuntimeGeneration(0))

        val token = registry.admitSynthesized(input("owner"), over)
        assertNull("oversize artifact rejected", token)

        assertEquals(sampleCount, over.samples.size)
        assertEquals(marker, over.samples.first())
        assertEquals(marker, over.samples.last())
        assertTrue("oversize artifact disposed on rejection", over.isDisposed)

        val acc = registry.accounting()
        assertEquals(0, acc.liveTokens)
        assertEquals(0L, acc.retainedBytes)
        assertTrue(acc.perOwner.isEmpty())
    }

    @Test
    fun `a rejected admit does not perturb a sibling owners accounting or the generation`() {
        val maxBytesPerOwner = capturedBytes(1)
        val registry = LuaOpaqueAudioRegistry(
            stateHandle,
            wideLimits { copy(maxBytesPerOwner = maxBytesPerOwner) },
            wideProcessQuota(),
        )
        val ownerA = input("A")
        val ownerB = input("B")

        // Owner-A admits one artifact at its byte ceiling.
        val aAdmitted = capturedSpy("a", sampleCount = 1)
        assertNotNull(registry.admitCaptured(ownerA, aAdmitted))
        // Owner-A's next artifact is rejected and disposed.
        val aRejected = capturedSpy("a-rej", sampleCount = 1)
        assertNull(registry.admitCaptured(ownerA, aRejected))
        verify(exactly = 1) { aRejected.dispose() }

        // Owner-B is unaffected: admits its own artifact, generation grows by one.
        val bAdmitted = capturedSpy("b", sampleCount = 1)
        assertNotNull(registry.admitCaptured(ownerB, bAdmitted))
        verify(exactly = 0) { bAdmitted.dispose() }

        val acc = registry.accounting()
        assertEquals(2, acc.liveTokens)
        assertEquals(capturedBytes(2), acc.retainedBytes)
        assertEquals(1, acc.perOwner[ownerA]?.tokens)
        assertEquals(1, acc.perOwner[ownerB]?.tokens)
    }

    // ------------------------------------------------------------------
    // Sibling isolation
    // ------------------------------------------------------------------

    @Test
    fun `filling one owners token quota leaves a sibling owner admitting against its own quota`() {
        val maxTokensPerOwner = 1
        val registry = LuaOpaqueAudioRegistry(
            stateHandle,
            wideLimits { copy(maxTokensPerOwner = maxTokensPerOwner) },
            wideProcessQuota(),
        )
        val ownerA = input("A")
        val ownerB = input("B")

        val aOnly = capturedSpy("a-only", sampleCount = 1)
        assertNotNull("owner-A admits its one allowed token", registry.admitCaptured(ownerA, aOnly))
        val aOver = capturedSpy("a-over", sampleCount = 1)
        assertNull("owner-A second token rejected", registry.admitCaptured(ownerA, aOver))
        verify(exactly = 1) { aOver.dispose() }

        // Sibling owner-B still gets its full independent quota.
        val bFirst = capturedSpy("b-first", sampleCount = 1)
        assertNotNull("sibling owner-B admits its first token", registry.admitCaptured(ownerB, bFirst))
        val bOver = capturedSpy("b-over", sampleCount = 1)
        assertNull("sibling owner-B second token rejected", registry.admitCaptured(ownerB, bOver))
        verify(exactly = 1) { bOver.dispose() }

        val acc = registry.accounting()
        assertEquals(2, acc.liveTokens)
        assertEquals(1, acc.perOwner[ownerA]?.tokens)
        assertEquals(1, acc.perOwner[ownerB]?.tokens)
    }

    @Test
    fun `filling one owners byte quota leaves a sibling owner admitting against its own byte quota`() {
        val maxBytesPerOwner = capturedBytes(2)
        val registry = LuaOpaqueAudioRegistry(
            stateHandle,
            wideLimits { copy(maxBytesPerOwner = maxBytesPerOwner) },
            wideProcessQuota(),
        )
        val ownerA = input("A")
        val ownerB = input("B")

        // Owner-A saturates its byte quota exactly.
        assertNotNull(registry.admitCaptured(ownerA, capturedSpy("a1", sampleCount = 1)))
        assertNotNull(registry.admitCaptured(ownerA, capturedSpy("a2", sampleCount = 1)))
        val aOver = capturedSpy("a-over", sampleCount = 1)
        assertNull("owner-A byte-exceeding artifact rejected", registry.admitCaptured(ownerA, aOver))
        verify(exactly = 1) { aOver.dispose() }

        // Sibling owner-B can still admit the same byte volume.
        assertNotNull(registry.admitCaptured(ownerB, capturedSpy("b1", sampleCount = 1)))
        assertNotNull(registry.admitCaptured(ownerB, capturedSpy("b2", sampleCount = 1)))

        val acc = registry.accounting()
        assertEquals(capturedBytes(2), acc.perOwner[ownerA]?.bytes)
        assertEquals(capturedBytes(2), acc.perOwner[ownerB]?.bytes)
    }

    @Test
    fun `shared process quota is the only cross-registry dimension`() {
        // Two registries (sibling generations) share one ProcessQuota. Each
        // registry enforces its own per-owner/per-generation limits locally;
        // only the process accountant is shared and observable from both.
        val processQuota = LuaOpaqueAudioRegistry.ProcessQuota(
            maxTokens = 2,
            maxBytes = Long.MAX_VALUE,
        )
        val registry1 = LuaOpaqueAudioRegistry(
            LuaStateHandle(LuaStateId(11), LuaStateGeneration(1)),
            wideLimits(),
            processQuota,
        )
        val registry2 = LuaOpaqueAudioRegistry(
            LuaStateHandle(LuaStateId(12), LuaStateGeneration(2)),
            wideLimits(),
            processQuota,
        )

        // Registry-1 admits one; process accountant holds one.
        assertNotNull(registry1.admitCaptured(input("a"), capturedSpy("r1", sampleCount = 1)))
        assertEquals(1, processQuota.liveTokens())
        // Registry-2 admits the second; process accountant holds two.
        assertNotNull(registry2.admitCaptured(input("b"), capturedSpy("r2", sampleCount = 1)))
        assertEquals(2, processQuota.liveTokens())
        // Registry-2's next admit exhausts the shared process quota; its own
        // per-generation/per-owner limits are nowhere near saturated.
        val over = capturedSpy("over", sampleCount = 1)
        assertNull("shared process quota rejects the third admit", registry2.admitCaptured(input("c"), over))
        verify(exactly = 1) { over.dispose() }

        // Each registry's local accounting is independent.
        assertEquals(1, registry1.accounting().liveTokens)
        assertEquals(1, registry2.accounting().liveTokens)
    }

    // ------------------------------------------------------------------
    // Zero close accounting
    // ------------------------------------------------------------------

    @Test
    fun `close returns all generation and process accounting to zero`() {
        val processQuota = LuaOpaqueAudioRegistry.ProcessQuota(
            maxTokens = 8,
            maxBytes = 4_096L,
        )
        val registry = LuaOpaqueAudioRegistry(stateHandle, wideLimits(), processQuota)

        val ownerInput = input("input-owner")
        val ownerTask = task("task-owner")
        val a = capturedSpy("a", sampleCount = 100)       // 200 bytes
        val b = synthesizedSpy("b", sampleCount = 50)     // 200 bytes
        val c = capturedSpy("c", sampleCount = 100)       // 200 bytes

        assertNotNull(registry.admitCaptured(ownerInput, a))
        assertNotNull(registry.admitSynthesized(ownerTask, b))
        assertNotNull(registry.admitCaptured(ownerInput, c))

        // Pre-close: registry and process accountant hold three live tokens.
        val preClose = registry.accounting()
        assertEquals(3, preClose.liveTokens)
        assertEquals(capturedBytes(200) + synthesizedBytes(50), preClose.retainedBytes)
        assertEquals(2, preClose.perOwner[ownerInput]?.tokens)
        assertEquals(1, preClose.perOwner[ownerTask]?.tokens)
        assertEquals(3, processQuota.liveTokens())
        assertEquals(capturedBytes(200) + synthesizedBytes(50), processQuota.retainedBytes())

        registry.close()

        val postClose = registry.accounting()
        assertEquals("generation tokens zero after close", 0, postClose.liveTokens)
        assertEquals("generation bytes zero after close", 0L, postClose.retainedBytes)
        assertTrue("per-owner map empty after close", postClose.perOwner.isEmpty())
        assertEquals("process tokens zero after close", 0, processQuota.liveTokens())
        assertEquals("process bytes zero after close", 0L, processQuota.retainedBytes())

        verify(exactly = 1) { a.dispose() }
        verify(exactly = 1) { b.dispose() }
        verify(exactly = 1) { c.dispose() }
    }

    @Test
    fun `invalidateOwner zeros that owners accounting and releases its process claim while siblings survive`() {
        val processQuota = LuaOpaqueAudioRegistry.ProcessQuota(
            maxTokens = 8,
            maxBytes = 4_096L,
        )
        val registry = LuaOpaqueAudioRegistry(stateHandle, wideLimits(), processQuota)

        val ownerA = input("A")
        val ownerB = input("B")
        val a1 = capturedSpy("a1", sampleCount = 100) // 200 bytes
        val a2 = capturedSpy("a2", sampleCount = 100) // 200 bytes
        val b1 = capturedSpy("b1", sampleCount = 100) // 200 bytes

        assertNotNull(registry.admitCaptured(ownerA, a1))
        assertNotNull(registry.admitCaptured(ownerA, a2))
        assertNotNull(registry.admitCaptured(ownerB, b1))
        assertEquals(3, processQuota.liveTokens())

        registry.invalidateOwner(ownerA)

        // Owner-A's artifacts disposed, its accounting gone, process claim released.
        verify(exactly = 1) { a1.dispose() }
        verify(exactly = 1) { a2.dispose() }
        assertFalse("owner-A removed from per-owner map", registry.accounting().perOwner.containsKey(ownerA))
        assertEquals(1, processQuota.liveTokens())
        assertEquals(capturedBytes(100), processQuota.retainedBytes())

        // Sibling owner-B intact in both generation and process accounting.
        val acc = registry.accounting()
        assertEquals(1, acc.liveTokens)
        assertEquals(capturedBytes(100), acc.retainedBytes)
        val bAccounting = acc.perOwner[ownerB]
        assertNotNull(bAccounting)
        assertEquals(1, bAccounting!!.tokens)
        assertEquals(capturedBytes(100), bAccounting.bytes)
        verify(exactly = 0) { b1.dispose() }
    }
}
