package io.talkcan.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Android [CaptureSource] backed by [AudioRecord], parameterized by the
 * [MediaRecorder.AudioSource] passed in.
 *
 * Collapses the legacy [InMemoryRecorder] (`VOICE_COMMUNICATION`) and
 * [PhoneMicRecorder] (`MIC`) — both clones differed only by this enum.
 */
abstract class AndroidCaptureSource(
    private val audioSource: Int,
) : CaptureSource {
    @SuppressLint("MissingPermission")
    override suspend fun open(): OpenedCaptureSource? {
        val selectedRate = selectSampleRate() ?: return null
        val minBuffer = AudioRecord.getMinBufferSize(
            selectedRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null

        val format = AudioFormat.Builder()
            .setSampleRate(selectedRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val record = runCatching {
            AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
        }.getOrNull() ?: return null

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }

        runCatching { record.startRecording() }.getOrElse {
            record.release()
            return null
        }

        // API 24+ exposes the active recording configuration; client-silencing
        // is available on API 29+. The app minSdk is 31, so this is safe on all
        // supported devices. A missing configuration remains unknown (null).
        val activeConfiguration = record.activeRecordingConfiguration

        return object : OpenedCaptureSource {
            override val sampleRate: Int = selectedRate
            override val bufferSizeShorts: Int = minBuffer / Short.SIZE_BYTES

            override val requiresPreCommitSignal: Boolean = true
            override val preCommitSignalAttempts: Int = 2

            override val startupEvidence: CaptureStartupEvidence = CaptureStartupEvidence(
                clientSilenced = activeConfiguration?.isClientSilenced,
                inputDeviceName = record.routedDevice?.routeDebugString(),
            )

            override fun read(buffer: ShortArray): Int =
                record.read(buffer, 0, buffer.size)

            override fun readNonBlocking(buffer: ShortArray): Int =
                record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)

            override fun close() {
                runCatching { record.stop() }
                record.release()
            }
        }
    }


    private fun selectSampleRate(): Int? = listOf(16_000, 8_000).firstOrNull { rate ->
        AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ) > 0
    }
}

/** [CaptureSource] for the SCO communication route — `VOICE_COMMUNICATION`. */
class AndroidVoiceCommunicationCaptureSource :
    AndroidCaptureSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
    override val sourceId: CaptureSourceId = CaptureSourceId.VoiceCommunication
}

/** [CaptureSource] for the phone microphone fallback — `MIC`. */
class AndroidMicCaptureSource :
    AndroidCaptureSource(MediaRecorder.AudioSource.MIC) {
    override val sourceId: CaptureSourceId = CaptureSourceId.Mic
}
