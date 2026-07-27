package io.talkcan.audio

import kotlin.math.PI
import kotlin.math.sin

/** Error feedback PCM for host-owned controllable playback. */
object HostAudioFeedback {
    private const val SAMPLE_RATE = 16_000


    fun errorBeep(): RecordedPcm = RecordedPcm(
        tone(400.0, 150) + tone(300.0, 150),
        SAMPLE_RATE,
    )

    private fun tone(frequencyHz: Double, durationMillis: Int): ShortArray {
        val count = SAMPLE_RATE * durationMillis / 1_000
        return ShortArray(count) { index ->
            val phase = 2.0 * PI * frequencyHz * index / SAMPLE_RATE
            (sin(phase) * Short.MAX_VALUE * 0.35).toInt().toShort()
        }
    }
}
