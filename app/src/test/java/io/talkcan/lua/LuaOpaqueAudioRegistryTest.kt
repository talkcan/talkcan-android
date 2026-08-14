package io.talkcan.channel.capability

import io.talkcan.audio.RecordedPcm
import io.talkcan.lua.LuaOpaqueAudioRegistry
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaStateId
import io.talkcan.lua.LuaStateGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class LuaOpaqueAudioRegistryTest {

    private val stateHandle1 = LuaStateHandle(LuaStateId(41), LuaStateGeneration(1))
    private val stateHandle2 = LuaStateHandle(LuaStateId(42), LuaStateGeneration(2))

    private val ownerInput1 = LuaOpaqueAudioRegistry.Owner.Input("input-1")
    private val ownerInput2 = LuaOpaqueAudioRegistry.Owner.Input("input-2")
    private val ownerTask1 = LuaOpaqueAudioRegistry.Owner.Task("task-1")

    private val recording1 = RecordedPcmAudioRecording(RecordedPcm(ShortArray(0), 16000), operationId = "rec-1", generation = RuntimeGeneration(0))
    private val recording2 = RecordedPcmAudioRecording(RecordedPcm(ShortArray(0), 16000), operationId = "rec-2", generation = RuntimeGeneration(0))
    private val audio1 = SynthesizedAudioArtifact(floatArrayOf(), operationId = "audio-1", generation = RuntimeGeneration(0))
    private val audio2 = SynthesizedAudioArtifact(floatArrayOf(), operationId = "audio-2", generation = RuntimeGeneration(0))

    @Test
    fun `admitted captured and synthesized tokens are unguessable base64 strings`() {
        val registry = LuaOpaqueAudioRegistry(stateHandle1)
        val token = registry.admitCaptured(ownerInput1, recording1)

        assertNotNull(token)
        val value = token!!.value
        // 32 random bytes base64 encoded URL safe without padding is 43 characters
        assertEquals("Token length must be 43 characters for 32 secure random bytes", 43, value.length)
        
        // Assert token contains URL-safe Base64 characters and no padding
        assertFalse("Token must not contain '+'", value.contains("+"))
        assertFalse("Token must not contain '/'", value.contains("/"))
        assertFalse("Token must not contain '='", value.contains("="))

        // Successive tokens must be distinct
        val token2 = registry.admitCaptured(ownerInput1, recording2)
        assertNotNull(token2)
        assertNotEquals("Tokens must be unique", token.value, token2!!.value)
    }

    @Test
    fun `resolution verifies correct owner and kind`() {
        val registry = LuaOpaqueAudioRegistry(stateHandle1)
        
        val tokenCap = registry.admitCaptured(ownerInput1, recording1)
        assertNotNull(tokenCap)
        
        val tokenSyn = registry.admitSynthesized(ownerTask1, audio1)
        assertNotNull(tokenSyn)

        // 1. Success resolution
        val resCapOk = registry.resolve(tokenCap!!, ownerInput1, LuaOpaqueAudioRegistry.Kind.Captured)
        assertTrue(resCapOk is LuaOpaqueAudioRegistry.Resolution.Captured)
        assertEquals(recording1, (resCapOk as LuaOpaqueAudioRegistry.Resolution.Captured).recording)

        val resSynOk = registry.resolve(tokenSyn!!, ownerTask1, LuaOpaqueAudioRegistry.Kind.Synthesized)
        assertTrue(resSynOk is LuaOpaqueAudioRegistry.Resolution.Synthesized)
        assertEquals(audio1, (resSynOk as LuaOpaqueAudioRegistry.Resolution.Synthesized).audio)

        // 2. Foreign owner validation
        val resCapForeign = registry.resolve(tokenCap, ownerInput2, LuaOpaqueAudioRegistry.Kind.Captured)
        assertEquals(LuaOpaqueAudioRegistry.Resolution.Foreign, resCapForeign)

        val resSynForeign = registry.resolve(tokenSyn, ownerInput1, LuaOpaqueAudioRegistry.Kind.Synthesized)
        assertEquals(LuaOpaqueAudioRegistry.Resolution.Foreign, resSynForeign)

        // 3. Wrong kind validation
        val resCapWrongKind = registry.resolve(tokenCap, ownerInput1, LuaOpaqueAudioRegistry.Kind.Synthesized)
        assertEquals(LuaOpaqueAudioRegistry.Resolution.WrongKind, resCapWrongKind)

        val resSynWrongKind = registry.resolve(tokenSyn, ownerTask1, LuaOpaqueAudioRegistry.Kind.Captured)
        assertEquals(LuaOpaqueAudioRegistry.Resolution.WrongKind, resSynWrongKind)

        // 4. Non-existent token (Stale)
        val fakeToken = LuaOpaqueAudioRegistry.Token("fake-token-value-must-be-stale-in-the-reg")
        val resFake = registry.resolve(fakeToken, ownerInput1, LuaOpaqueAudioRegistry.Kind.Captured)
        assertEquals(LuaOpaqueAudioRegistry.Resolution.Stale, resFake)
    }

    @Test
    fun `owns method correctly reports presence and closed status`() {
        val registry = LuaOpaqueAudioRegistry(stateHandle1)
        val token = registry.admitCaptured(ownerInput1, recording1)!!

        assertTrue(registry.owns(token))

        val fakeToken = LuaOpaqueAudioRegistry.Token("not-present")
        assertFalse(registry.owns(fakeToken))

        registry.close()
        assertFalse("Cannot own tokens after registry closure", registry.owns(token))
    }

    @Test
    fun `invalidateOwner removes only targeted owner entries`() {
        val registry = LuaOpaqueAudioRegistry(stateHandle1)
        val tokenCap1 = registry.admitCaptured(ownerInput1, recording1)!!
        val tokenCap2 = registry.admitCaptured(ownerInput2, recording2)!!
        val tokenSyn1 = registry.admitSynthesized(ownerInput1, audio1)!!

        assertTrue(registry.owns(tokenCap1))
        assertTrue(registry.owns(tokenCap2))
        assertTrue(registry.owns(tokenSyn1))

        registry.invalidateOwner(ownerInput1)

        assertFalse("Entries for invalidated owner must be removed", registry.owns(tokenCap1))
        assertFalse("Entries for invalidated owner must be removed", registry.owns(tokenSyn1))
        assertTrue("Entries for other owners must remain untouched", registry.owns(tokenCap2))
    }

    @Test
    fun `closing registry clears all entries and rejects future operations`() {
        val registry = LuaOpaqueAudioRegistry(stateHandle1)
        val token = registry.admitCaptured(ownerInput1, recording1)!!

        assertTrue(registry.owns(token))
        
        registry.close()
        
        assertFalse("Registry should not own token after close", registry.owns(token))
        assertEquals(LuaOpaqueAudioRegistry.Resolution.Closed, registry.resolve(token, ownerInput1, LuaOpaqueAudioRegistry.Kind.Captured))

        // Re-admission must be rejected (returns null)
        val tokenPostClose = registry.admitCaptured(ownerInput1, recording2)
        assertNull("Registry must reject admission after close", tokenPostClose)
    }

    @Test
    fun `independent registries maintain isolated token ownership`() {
        val registry1 = LuaOpaqueAudioRegistry(stateHandle1)
        val registry2 = LuaOpaqueAudioRegistry(stateHandle2)

        val token1 = registry1.admitCaptured(ownerInput1, recording1)!!
        val token2 = registry2.admitCaptured(ownerInput1, recording2)!!

        assertTrue(registry1.owns(token1))
        assertFalse("Registry 1 must not own Registry 2's token", registry1.owns(token2))

        assertTrue(registry2.owns(token2))
        assertFalse("Registry 2 must not own Registry 1's token", registry2.owns(token1))

        assertEquals(LuaOpaqueAudioRegistry.Resolution.Stale, registry1.resolve(token2, ownerInput1, LuaOpaqueAudioRegistry.Kind.Captured))
        assertEquals(LuaOpaqueAudioRegistry.Resolution.Stale, registry2.resolve(token1, ownerInput1, LuaOpaqueAudioRegistry.Kind.Captured))
    }

    @Test
    fun `consume rejects foreign or wrong-kind admission without invalidating live token then consumes once`() {
        val registry = LuaOpaqueAudioRegistry(stateHandle1)
        val token = registry.admitCaptured(ownerInput1, recording1)!!

        assertNull(registry.consume(token, ownerInput2, LuaOpaqueAudioRegistry.Kind.Captured))
        assertTrue("failed foreign admission must preserve the live token", registry.owns(token))
        assertNull(registry.consume(token, ownerInput1, LuaOpaqueAudioRegistry.Kind.Synthesized))
        assertTrue("failed wrong-kind admission must preserve the live token", registry.owns(token))

        assertNotNull(registry.consume(token, ownerInput1, LuaOpaqueAudioRegistry.Kind.Captured))
        assertFalse(registry.owns(token))
        assertEquals(
            LuaOpaqueAudioRegistry.Resolution.Stale,
            registry.resolve(token, ownerInput1, LuaOpaqueAudioRegistry.Kind.Captured),
        )
        assertNull("a consumed token cannot be reused", registry.consume(token, ownerInput1, LuaOpaqueAudioRegistry.Kind.Captured))
    }
    @Test
    fun `foreign state sibling instance predecessor generation and task owners reject without consuming valid entry`() {
        val registry = LuaOpaqueAudioRegistry(stateHandle1)
        val sibling = LuaOpaqueAudioRegistry(stateHandle1)
        val predecessor = LuaOpaqueAudioRegistry(
            LuaStateHandle(LuaStateId(41), LuaStateGeneration(0)),
        )
        val owner = LuaOpaqueAudioRegistry.Owner.Input("callback-valid")
        val foreignCallback = LuaOpaqueAudioRegistry.Owner.Input("callback-foreign")
        val sameIdTask = LuaOpaqueAudioRegistry.Owner.Task("callback-valid")
        val token = registry.admitCaptured(owner, recording1)!!
        val before = registry.accounting()

        assertEquals(LuaOpaqueAudioRegistry.Resolution.Stale, sibling.resolve(token, owner, LuaOpaqueAudioRegistry.Kind.Captured))
        assertEquals(LuaOpaqueAudioRegistry.Resolution.Stale, predecessor.resolve(token, owner, LuaOpaqueAudioRegistry.Kind.Captured))
        assertEquals(LuaOpaqueAudioRegistry.Resolution.Foreign, registry.resolve(token, foreignCallback, LuaOpaqueAudioRegistry.Kind.Captured))
        assertEquals(LuaOpaqueAudioRegistry.Resolution.Foreign, registry.resolve(token, sameIdTask, LuaOpaqueAudioRegistry.Kind.Captured))
        assertEquals(LuaOpaqueAudioRegistry.Resolution.WrongKind, registry.resolve(token, owner, LuaOpaqueAudioRegistry.Kind.Synthesized))
        assertEquals(before, registry.accounting())

        assertNull(sibling.consume(token, owner, LuaOpaqueAudioRegistry.Kind.Captured))
        assertNull(predecessor.consume(token, owner, LuaOpaqueAudioRegistry.Kind.Captured))
        assertNull(registry.consume(token, foreignCallback, LuaOpaqueAudioRegistry.Kind.Captured))
        assertNull(registry.consume(token, sameIdTask, LuaOpaqueAudioRegistry.Kind.Captured))
        assertNull(registry.consume(token, owner, LuaOpaqueAudioRegistry.Kind.Synthesized))
        assertEquals(before, registry.accounting())
        assertNotNull(registry.consume(token, owner, LuaOpaqueAudioRegistry.Kind.Captured))
        assertEquals(0, registry.accounting().liveTokens)
    }

    @Test
    fun `default registry limits and process quota defaults are configured correctly`() {
        val limits = LuaOpaqueAudioRegistry.Limits.DEFAULT
        assertEquals(19_200_000L, limits.maxBytesPerArtifact)
        assertEquals(600_000L, limits.maxDurationPerArtifactMillis)
        assertEquals(32, limits.maxTokensPerOwner)
        assertEquals(32L * 1024 * 1024, limits.maxBytesPerOwner)
        assertEquals(64, limits.maxTokensPerGeneration)
        assertEquals(48L * 1024 * 1024, limits.maxBytesPerGeneration)

        // ProcessQuota default check
        val processQuota = LuaOpaqueAudioRegistry.ProcessQuota.DEFAULT
        // Clean any leftovers from other tests if they mutated the static default
        processQuota.release(processQuota.liveTokens(), processQuota.retainedBytes())

        assertTrue(processQuota.tryReserve(1, 64L * 1024 * 1024))
        assertFalse(processQuota.tryReserve(1, 1L))
        processQuota.release(1, 64L * 1024 * 1024)
    }
}
