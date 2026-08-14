package io.talkcan.audio

import android.media.AudioAttributes
import io.talkcan.service.CaptureFeedbackTone
import android.media.AudioFormat
import android.media.AudioTrack
import io.talkcan.model.ScoState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

class NoopScoRoute : ScoRoute {
    private val _state = MutableStateFlow<ScoState>(ScoState.Active)
    override val state: StateFlow<ScoState> = _state.asStateFlow()
    override val endpoint: AudioRouteEndpoint = AudioRouteEndpoint.Local
    override val coldStart: Boolean = false

    override fun hasAvailableScoDevice(): Boolean = false
    override suspend fun acquire(): Boolean = true
    override fun isActive(): Boolean = true
    override fun release() {}
}

object CaptureFeedbackToneGenerator {
    fun generateSinePcm16(
        frequencyHz: Double,
        durationMs: Int,
        sampleRate: Int,
        amplitude: Double,
    ): ShortArray {
        val count = sampleRate * durationMs / 1_000
        return ShortArray(count) { index ->
            val phase = 2.0 * Math.PI * frequencyHz * index / sampleRate
            (kotlin.math.sin(phase) * Short.MAX_VALUE * amplitude).toInt().toShort()
        }
    }

    fun generateWarningTones(sampleRate: Int): ShortArray {
        val singleTone = generateSinePcm16(880.0, 150, sampleRate, 0.35)
        val silence = ShortArray(sampleRate * 150 / 1_000)
        return singleTone + silence + singleTone + silence + singleTone
    }

    fun generateFinalTone(sampleRate: Int): ShortArray {
        return generateSinePcm16(880.0, 750, sampleRate, 0.35)
    }
}

class LocalPcmOutput : PcmOutput {
    override suspend fun playReadyBeep(coldStart: Boolean) {
        val sampleRate = 16_000
        var samples = generateSinePcm16(
            frequencyHz = 880.0,
            durationMs = 150,
            sampleRate = sampleRate,
            amplitude = 0.35,
        )
        if (coldStart) {
            val silenceCount = sampleRate * 100 / 1_000
            val silence = ShortArray(silenceCount)
            samples = silence + samples
        }
        playStaticPcm(
            samples = samples,
            sampleRate = sampleRate,
            contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
        )
    }

    override suspend fun playErrorBeep(coldStart: Boolean) {
        val sampleRate = 16_000
        val tone1 = generateSinePcm16(
            frequencyHz = 400.0,
            durationMs = 150,
            sampleRate = sampleRate,
            amplitude = 0.35,
        )
        val tone2 = generateSinePcm16(
            frequencyHz = 300.0,
            durationMs = 150,
            sampleRate = sampleRate,
            amplitude = 0.35,
        )
        var samples = tone1 + tone2
        if (coldStart) {
            val silenceCount = sampleRate * 100 / 1_000
            val silence = ShortArray(silenceCount)
            samples = silence + samples
        }
        playStaticPcm(
            samples = samples,
            sampleRate = sampleRate,
            contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
        )
    }

    override suspend fun play(recording: RecordedPcm) {
        if (recording.isEmpty) return
        playStaticPcm(
            samples = recording.samples,
            sampleRate = recording.sampleRate,
            contentType = AudioAttributes.CONTENT_TYPE_SPEECH,
        )
    }

    override suspend fun playCaptureFeedback(tone: CaptureFeedbackTone) {
        val sampleRate = 16_000
        val samples = when (tone) {
            CaptureFeedbackTone.RecordingLimitWarning -> CaptureFeedbackToneGenerator.generateWarningTones(sampleRate)
            CaptureFeedbackTone.RecordingLimitFinal -> CaptureFeedbackToneGenerator.generateFinalTone(sampleRate)
        }
        playStaticPcm(
            samples = samples,
            sampleRate = sampleRate,
            contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
        )
    }

    private suspend fun playStaticPcm(
        samples: ShortArray,
        sampleRate: Int,
        contentType: Int,
    ) = withContext(Dispatchers.IO) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(contentType)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            track.write(samples, 0, samples.size)
            track.play()
            val durationMs = samples.size * 1_000L / sampleRate
            delay(durationMs + 50)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun generateSinePcm16(
        frequencyHz: Double,
        durationMs: Int,
        sampleRate: Int,
        amplitude: Double,
    ): ShortArray {
        val count = sampleRate * durationMs / 1_000
        return ShortArray(count) { index ->
            val phase = 2.0 * PI * frequencyHz * index / sampleRate
            (sin(phase) * Short.MAX_VALUE * amplitude).toInt().toShort()
        }
    }
}

