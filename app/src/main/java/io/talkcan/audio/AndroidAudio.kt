package io.talkcan.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

class AndroidPcmOutput(
    private val audioManager: AudioManager,
    private val communicationDevice: () -> AudioDeviceInfo? = { audioManager.communicationDevice },
    private val requireActiveScoCommunicationDevice: Boolean = true,
) : PcmOutput {
    override suspend fun playReadyBeep(coldStart: Boolean) {
        val device = communicationDeviceForPlayback()
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
            preferredDevice = device,
        )
    }

    override suspend fun playErrorBeep(coldStart: Boolean) {
        val device = communicationDeviceForPlayback()
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
            preferredDevice = device,
        )
    }

    override suspend fun play(recording: RecordedPcm) {
        if (recording.isEmpty) return
        val device = communicationDeviceForPlayback()
        playStaticPcm(
            samples = recording.samples,
            sampleRate = recording.sampleRate,
            contentType = AudioAttributes.CONTENT_TYPE_SPEECH,
            preferredDevice = device,
        )
    }

    private fun communicationDeviceForPlayback(): AudioDeviceInfo? {
        val device = communicationDevice()
        if (!requireActiveScoCommunicationDevice) {
            return device?.takeIf { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        }
        check(device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            "Bluetooth SCO route is not active"
        }
        val active = audioManager.communicationDevice
        check(active != null && sameAudioDevice(active, device)) {
            "Selected Bluetooth SCO route is not active"
        }
        return device
    }

    private suspend fun playStaticPcm(
        samples: ShortArray,
        sampleRate: Int,
        contentType: Int,
        preferredDevice: AudioDeviceInfo? = null,
    ) = withContext(Dispatchers.IO) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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

        if (preferredDevice != null) {
            track.setPreferredDevice(preferredDevice)
        }

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

