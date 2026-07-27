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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused lifecycle tests for [LuaOpaqueAudioRegistry] covering task 4.8:
 *
 *  - Owner termination disposes that owner's unconsumed artifacts and later use
 *    resolves stale (E_STALE).
 *  - Generation close disposes every unconsumed artifact and post-close
 *    resolution is closed (E_CLOSED), taking precedence over foreign/stale/
 *    wrong-kind.
 *  - Exactly-once disposal: idempotent [LuaOpaqueAudioRegistry.close] and
 *    [LuaOpaqueAudioRegistry.invalidateOwner] never dispose an artifact twice,
 *    and post-close termination is a no-op.
 *  - No GC effects: garbage collection of a live artifact, a dropped token
 *    handle, or an undisposed registry never calls [OpaqueAudioRecording.dispose]
 *    / [OpaqueSynthesizedAudio.dispose]; only explicit lifecycle calls dispose.
 *
 * Disposal call counts are observed with MockK spies over the real internal
 * artifact classes (sealed interfaces cannot be subtyped from the test source
 * set, which is a separate Kotlin module). These tests exercise the registry
 * contract directly (Owner.Input + close), not the task-5 synthesis/task-owned
 * artifact path.
 */
class LuaOpaqueAudioRegistryLifecycleTest {

    private val stateHandle1 = LuaStateHandle(LuaStateId(41), LuaStateGeneration(1))
    private val stateHandle2 = LuaStateHandle(LuaStateId(42), LuaStateGeneration(2))

    private fun input(id: String) = LuaOpaqueAudioRegistry.Owner.Input(id)

    private fun capturedSpy(id: String): RecordedPcmAudioRecording =
        spyk(RecordedPcmAudioRecording(RecordedPcm(ShortArray(0), 16_000), operationId = id, generation = RuntimeGeneration(0)))

    private fun synthesizedSpy(id: String): SynthesizedAudioArtifact =
        spyk(SynthesizedAudioArtifact(floatArrayOf(), operationId = id, generation = RuntimeGeneration(0)))

    // ------------------------------------------------------------------
    // Owner termination
    // ------------------------------------------------------------------

    @Test
    fun `owner termination disposes only that owners unconsumed artifacts and later use resolves stale`() {
        val registry = LuaOpaqueAudioRegistry(stateHandle1)
        val recA = capturedSpy("a")
        val synA = synthesizedSpy("a")
        val recB = capturedSpy("b")
        val synB = synthesizedSpy("b")

        val tokenRecA = registry.admitCaptured(input("owner-A"), recA)!!
        val tokenSynA = registry.admitSynthesized(input("owner-A"), synA)!!
        val tokenRecB = registry.admitCaptured(input("owner-B"), recB)!!
        val tokenSynB = registry.admitSynthesized(input("owner-B"), synB)!!

        registry.invalidateOwner(input("owner-A"))

        // Terminated owner's artifacts (both kinds) disposed exactly once.
        verify(exactly = 1) { recA.dispose() }
        verify(exactly = 1) { synA.dispose() }
        // Sibling owner untouched.
        verify(exactly = 0) { recB.dispose() }
        verify(exactly = 0) { synB.dispose() }

        // Later use of a terminated owner's tokens is stale (not closed/foreign).
        assertEquals(
            LuaOpaqueAudioRegistry.Resolution.Stale,
            registry.resolve(tokenRecA, input("owner-A"), LuaOpaqueAudioRegistry.Kind.Captured),
        )
        assertEquals(
            LuaOpaqueAudioRegistry.Resolution.Stale,
            registry.resolve(tokenSynA, input("owner-A"), LuaOpaqueAudioRegistry.Kind.Synthesized),
        )
        // Sibling owner's tokens still resolve.
        assertTrue(
            registry.resolve(tokenRecB, input("owner-B"), LuaOpaqueAudioRegistry.Kind.Captured)
                is LuaOpaqueAudioRegistry.Resolution.Captured,
        )
        assertTrue(
            registry.resolve(tokenSynB, input("owner-B"), LuaOpaqueAudioRegistry.Kind.Synthesized)
                is LuaOpaqueAudioRegistry.Resolution.Synthesized,
        )
    }

    @Test
    fun `invalidateOwner is idempotent and disposes each artifact exactly once`() {
        val registry = LuaOpaqueAudioRegistry(stateHandle1)
        val rec = capturedSpy("rec")
        val syn = synthesizedSpy("syn")
        registry.admitCaptured(input("owner"), rec)!!
        registry.admitSynthesized(input("owner"), syn)!!

        registry.invalidateOwner(input("owner"))
        registry.invalidateOwner(input("owner")) // idempotent re-termination
        registry.invalidateOwner(input("never-admitted")) // unknown owner is a no-op

        verify(exactly = 1) { rec.dispose() }
        verify(exactly = 1) { syn.dispose() }
    }

    // ------------------------------------------------------------------
    // Generation close (E_CLOSED)
    // ------------------------------------------------------------------

    @Test
    fun `generation close disposes every unconsumed artifact exactly once`() {
        val registry = LuaOpaqueAudioRegistry(stateHandle1)
        val recA = capturedSpy("a")
        val synB = synthesizedSpy("b")
        registry.admitCaptured(input("owner-A"), recA)!!
        registry.admitSynthesized(input("owner-B"), synB)!!

        registry.close()

        verify(exactly = 1) { recA.dispose() }
        verify(exactly = 1) { synB.dispose() }
    }

    @Test
    fun `post-close resolution is closed for admitted foreign-owner wrong-kind and never-admitted tokens`() {
        val registry = LuaOpaqueAudioRegistry(stateHandle1)
        val admitted = capturedSpy("admitted")
        val token = registry.admitCaptured(input("owner-A"), admitted)!!

        registry.close()

        // Admitted token, correct owner -> Closed (E_CLOSED).
        assertEquals(
            LuaOpaqueAudioRegistry.Resolution.Closed,
            registry.resolve(token, input("owner-A"), LuaOpaqueAudioRegistry.Kind.Captured),
        )
        // Admitted token, foreign owner -> Closed takes precedence over Foreign.
        assertEquals(
            LuaOpaqueAudioRegistry.Resolution.Closed,
            registry.resolve(token, input("owner-B"), LuaOpaqueAudioRegistry.Kind.Captured),
        )
        // Admitted token, wrong kind -> Closed takes precedence over WrongKind.
        assertEquals(
            LuaOpaqueAudioRegistry.Resolution.Closed,
            registry.resolve(token, input("owner-A"), LuaOpaqueAudioRegistry.Kind.Synthesized),
        )
        // Never-admitted token -> Closed takes precedence over Stale.
        assertEquals(
            LuaOpaqueAudioRegistry.Resolution.Closed,
            registry.resolve(
                LuaOpaqueAudioRegistry.Token("never-admitted-token"),
                input("owner-A"),
                LuaOpaqueAudioRegistry.Kind.Captured,
            ),
        )
        // Admission is rejected post-close.
        assertNull("captured admission rejected post-close", registry.admitCaptured(input("owner-A"), capturedSpy("x")))
        assertNull(
            "synthesized admission rejected post-close",
            registry.admitSynthesized(input("owner-A"), synthesizedSpy("y")),
        )
        // Ownership gone; the one admitted artifact was disposed exactly once.
        assertFalse("ownership dropped post-close", registry.owns(token))
        verify(exactly = 1) { admitted.dispose() }
    }

    @Test
    fun `close is idempotent and post-close invalidateOwner does not re-dispose`() {
        val registry = LuaOpaqueAudioRegistry(stateHandle1)
        val rec = capturedSpy("rec")
        registry.admitCaptured(input("owner"), rec)!!

        registry.close()
        registry.close() // idempotent: no second disposal pass
        registry.invalidateOwner(input("owner")) // post-close: registry empty, no-op
        registry.invalidateOwner(input("owner")) // repeated: still no-op

        verify(exactly = 1) { rec.dispose() }
    }

    // ------------------------------------------------------------------
    // Generation replacement
    // ------------------------------------------------------------------

    @Test
    fun `generation replacement closes predecessor and isolates successor`() {
        val predecessor = LuaOpaqueAudioRegistry(stateHandle1)
        val successor = LuaOpaqueAudioRegistry(stateHandle2)

        val recOld = capturedSpy("old")
        val recNew = capturedSpy("new")
        val oldToken = predecessor.admitCaptured(input("owner"), recOld)!!
        val newToken = successor.admitCaptured(input("owner"), recNew)!!

        predecessor.close() // simulate generation retirement

        // Predecessor: post-close resolution is E_CLOSED and artifact disposed.
        assertEquals(
            LuaOpaqueAudioRegistry.Resolution.Closed,
            predecessor.resolve(oldToken, input("owner"), LuaOpaqueAudioRegistry.Kind.Captured),
        )
        verify(exactly = 1) { recOld.dispose() }
        // Successor: independent registry; its token live, predecessor's unknown.
        assertTrue("successor owns its own token", successor.owns(newToken))
        assertEquals(
            "predecessor token unknown to successor",
            LuaOpaqueAudioRegistry.Resolution.Stale,
            successor.resolve(oldToken, input("owner"), LuaOpaqueAudioRegistry.Kind.Captured),
        )
        verify(exactly = 0) { recNew.dispose() }
    }

    // ------------------------------------------------------------------
    // No GC effects
    // ------------------------------------------------------------------

    @Test
    fun `garbage collection never disposes a live artifact or drops ownership`() {
        val registry = LuaOpaqueAudioRegistry(stateHandle1)
        val rec = capturedSpy("rec")
        val token = registry.admitCaptured(input("owner"), rec)!!

        // Force GC over the live artifact: the registry owns it, so collection
        // can neither dispose it nor drop ownership.
        forceGc()
        verify(exactly = 0) { rec.dispose() }

        // Simulate Lua GC of the userdata handle: drop the Token reference, keep
        // only the token value. The registry owns by value, not by handle identity.
        val handleValue = token.value
        forceGc()
        verify(exactly = 0) { rec.dispose() }
        assertTrue(
            "ownership survives handle collection",
            registry.owns(LuaOpaqueAudioRegistry.Token(handleValue)),
        )

        // Only an explicit lifecycle call disposes, exactly once.
        registry.close()
        verify(exactly = 1) { rec.dispose() }
    }

    @Test
    fun `garbage collection of an undisposed registry triggers no host effect`() {
        // The registry registers no finalizer/cleaner, so collecting an
        // undisposed registry must never call dispose() (no route release).
        val rec = capturedSpy("rec")
        var registry: LuaOpaqueAudioRegistry? = LuaOpaqueAudioRegistry(stateHandle1)
        registry!!.admitCaptured(input("owner"), rec)!!
        registry = null // drop the only strong reference to the registry

        forceGc()

        verify(exactly = 0) { rec.dispose() }
    }

    /**
     * Force at least one garbage-collection sweep, confirmed by collecting a
     * freshly-allocated sentinel.
     */
    private fun forceGc() {
        val sentinel = java.lang.ref.WeakReference(Any())
        var passes = 0
        while (sentinel.get() != null && passes < 20) {
            System.gc()
            System.runFinalization()
            try {
                Thread.sleep(15)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            passes++
        }
    }
}
