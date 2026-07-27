package io.talkcan.lua

import io.talkcan.audio.RecordedPcm
import io.talkcan.audiofile.ExecutionOwner
import io.talkcan.audiofile.ExecutionOwnerKind
import io.talkcan.audiofile.PcmMonoS16Le
import io.talkcan.audiofile.RecordingBorrow
import io.talkcan.audiofile.RecordingHandle
import io.talkcan.audiofile.RecordingHost
import io.talkcan.audiofile.hostToken
import io.talkcan.audiofile.recordingHandle
import io.talkcan.channel.capability.RuntimeGeneration
import io.talkcan.channel.capability.opaqueAudioRecording
import io.talkcan.channel.capability.recordedPcmOf

/**
 * Adapts the state-local opaque Lua audio registry to the language-neutral
 * [RecordingHost] contract used by [io.talkcan.audiofile.AudioFilePort].
 * The two handle types intentionally carry the same random host token; neither
 * exposes it outside this composition boundary.
 */
internal class LuaRecordingHost(
    private val registry: LuaOpaqueAudioRegistry,
) : RecordingHost {
    override fun borrow(handle: RecordingHandle, owner: ExecutionOwner): RecordingBorrow {
        val resolution = registry.resolve(
            token = LuaOpaqueAudioRegistry.Token(handle.hostToken()),
            owner = owner.luaOwner(),
            kind = LuaOpaqueAudioRegistry.Kind.Captured,
        )
        return when (resolution) {
            is LuaOpaqueAudioRegistry.Resolution.Captured -> {
                val pcm = recordedPcmOf(resolution.recording)
                    ?: return RecordingBorrow.Stale
                RecordingBorrow.Borrowed(PcmMonoS16Le(pcm.samples, pcm.sampleRate))
            }
            LuaOpaqueAudioRegistry.Resolution.Foreign -> RecordingBorrow.Foreign
            LuaOpaqueAudioRegistry.Resolution.Closed -> RecordingBorrow.Closed
            LuaOpaqueAudioRegistry.Resolution.Stale,
            LuaOpaqueAudioRegistry.Resolution.WrongKind,
            is LuaOpaqueAudioRegistry.Resolution.Synthesized -> RecordingBorrow.Stale
        }
    }

    override fun admit(pcm: PcmMonoS16Le, owner: ExecutionOwner): RecordingHandle? {
        val recording = opaqueAudioRecording(
            RecordedPcm(pcm.samples, pcm.sampleRate),
            RuntimeGeneration(owner.generation),
        )
        val token = registry.admitCaptured(owner.luaOwner(), recording) ?: return null
        return recordingHandle(token.value)
    }

    override fun dispose(handle: RecordingHandle) {
        registry.dispose(
            LuaOpaqueAudioRegistry.Token(handle.hostToken()),
            LuaOpaqueAudioRegistry.Kind.Captured,
        )
    }

    fun handleFor(token: LuaOpaqueAudioRegistry.Token): RecordingHandle = recordingHandle(token.value)

    fun tokenFor(handle: RecordingHandle): String = handle.hostToken()

    private fun ExecutionOwner.luaOwner(): LuaOpaqueAudioRegistry.Owner = when (kind) {
        ExecutionOwnerKind.INPUT -> LuaOpaqueAudioRegistry.Owner.Input(id)
        ExecutionOwnerKind.TASK -> LuaOpaqueAudioRegistry.Owner.Task(id)
    }
}
