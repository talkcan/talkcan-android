package io.talkcan.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Bounded reusable stream buffer. Two buffers let the pump fill one while the
 * framework drains the other; no per-chunk allocation occurs after the track
 * is built. 320 frames = 20 ms at 16 kHz, large enough to avoid underruns on a
 * Bluetooth SCO link while small enough for responsive skip/cancel.
 */
private const val STREAM_BUFFER_FRAMES = 320
private const val STREAM_BUFFER_BYTES = STREAM_BUFFER_FRAMES * Short.SIZE_BYTES

/**
 * Duck gain applied to response speech while a rejection tone is overlaid,
 * expressed in Q15 fixed point (unity = 32768). Short linear ramps between
 * unity and the ducked target avoid an audible click at both edges.
 */
private const val GAIN_UNITY_Q = 1 shl 15
private const val GAIN_DUCK_Q = GAIN_UNITY_Q / 2          // ~0.5
private const val DUCK_RAMP_FRAMES = 160                  // 10 ms ramp

/**
 * Rejection tone parameters mirror the existing static error beep
 * ([AndroidPcmOutput.playErrorBeep] / [LocalPcmOutput.playErrorBeep]):
 * 400 Hz then 300 Hz, 150 ms each, amplitude 0.35. Mixing is saturating and
 * in-place; no second [AudioTrack] is opened.
 */
private const val TONE_AMPLITUDE = 0.35
private const val TONE_FIRST_HZ = 400.0
private const val TONE_SECOND_HZ = 300.0
private const val TONE_SEGMENT_MS = 150

/**
 * One host-owned streaming playback route.
 *
 * The route/device is supplied by the host at construction; no channel
 * contract observes [AudioDeviceInfo] or [AudioTrack]. Each [start] creates
 * one [ActiveStreamPcmPlayback] that owns a single MODE_STREAM track, pumps
 * the supplied PCM through bounded reusable buffers, and supports in-stream
 * duck-and-overlay rejection tone mixing and explicit skip without opening a
 * second output route. [release] is exactly-once and owns no audio object
 * after the active playback has terminated (the active playback owns the track).
 */
class StreamPlaybackRoute(
    private val sampleRate: Int = 16_000,
    private val preferredDevice: AudioDeviceInfo? = null,
    private val contentType: Int = AudioAttributes.CONTENT_TYPE_SPEECH,
    private val usage: Int = AudioAttributes.USAGE_VOICE_COMMUNICATION,
    private val routeEndpoint: AudioRouteEndpoint = AudioRouteEndpoint.Unspecified,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AcquiredPlaybackRoute {

    override val endpoint: AudioRouteEndpoint get() = routeEndpoint

    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    override suspend fun start(recording: RecordedPcm): ActivePcmPlayback {
        require(!released.get()) { "StreamPlaybackRoute released" }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
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
            .setBufferSizeInBytes(max(minBuf, STREAM_BUFFER_BYTES * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            .build()
        try {
            if (preferredDevice != null) {
                check(track.setPreferredDevice(preferredDevice)) {
                    "Preferred device ${preferredDevice.productName} is not routable for this AudioTrack"
                }
            }
        } catch (t: Throwable) {
            runCatching { track.release() }
            throw t
        }
        return ActiveStreamPcmPlayback(
            track = track,
            recording = recording,
            sampleRate = sampleRate,
            dispatcher = dispatcher,
        )
    }

    override suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        // The active playback owns its own track; the route holds no audio
        // object after its active playback has terminated. Nothing to free.
    }
}

/**
 * Controllable streaming playback over one MODE_STREAM [AudioTrack].
 *
 * - [awaitCompletion] resolves exactly once with the terminal classification.
 * - [rejectPttWithTone] ducks speech and overlays the two-tone rejection beep
 *   into the same stream; a per-operation single-slot debounce guarantees at
 *   most one active/queued tone, so one physical press yields at most one tone
 *   while a tone is active. Returns false if a tone is already queued.
 * - [skip] stops playback and classifies the completion as
 *   [PlaybackCompletion.ExplicitlySkipped].
 *
 * All mixing is saturated in-place in reusable buffers; no second track or
 * route is acquired.
 */
class ActiveStreamPcmPlayback internal constructor(
    private val track: AudioTrack,
    private val recording: RecordedPcm,
    private val sampleRate: Int,
    private val dispatcher: CoroutineDispatcher,
) : ActivePcmPlayback {

    private val completion = CompletableDeferred<PlaybackCompletion>()
    private val pumpJob: Job

    @Volatile private var skipRequested = false

    /** Single-slot debounce: at most one tone request is latched at a time. */
    private val toneSlot = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        val scope = CoroutineScope(dispatcher)
        pumpJob = scope.launch(dispatcher) {
            var terminal: PlaybackCompletion = PlaybackCompletion.Interrupted
            try {
                terminal = pump()
            } catch (ce: CancellationException) {
                terminal = PlaybackCompletion.Interrupted
                throw ce
            } catch (t: Throwable) {
                terminal = PlaybackCompletion.Failed(t.message ?: t::class.java.simpleName)
            } finally {
                try { cleanup() } catch (_: Throwable) { /* best-effort teardown */ }
                complete(terminal)
            }
        }
    }

    override suspend fun awaitCompletion(): PlaybackCompletion = completion.await()

    /**
     * Requests a rejection tone overlay. Returns true iff this call latched
     * the tone (no tone was already latched). One physical press therefore
     * maps to at most one tone while a tone is active or queued.
     */
    override fun rejectPttWithTone(): Boolean {
        if (!completion.isActive) return false
        return toneSlot.compareAndSet(false, true)
    }

    override fun skip(): Boolean {
        if (!completion.isActive) return false
        if (skipRequested) return false
        skipRequested = true
        toneSlot.set(false) // skip wins; cancel any latched tone
        return true
    }

    /**
     * Pumps the recording through the stream, overlaying rejection tones when
     * requested. Uses two reusable frame buffers; no allocation in the loop.
     */
    private suspend fun pump(): PlaybackCompletion {
        val samples = recording.samples
        if (recording.isEmpty) return PlaybackCompletion.Completed

        val frameSize = STREAM_BUFFER_FRAMES
        val bufA = ShortArray(frameSize)
        val bufB = ShortArray(frameSize)
        var which = 0

        val totalFrames = samples.size
        var pos = 0
        var tone: ToneOverlay? = null

        try {
            track.play()
        } catch (t: Throwable) {
            return PlaybackCompletion.Failed(t.message ?: "play() failed")
        }

        while (pos < totalFrames) {
            coroutineContext.ensureActive()

            if (skipRequested) return PlaybackCompletion.ExplicitlySkipped

            val len = min(frameSize, totalFrames - pos)
            val buf = if (which == 0) bufA else bufB
            which = which xor 1

            // Copy speech chunk into the reusable buffer.
            System.arraycopy(samples, pos, buf, 0, len)
            if (len < frameSize) {
                // Zero the trailing padding so stale samples are never written.
                java.util.Arrays.fill(buf, len, frameSize, 0.toShort())
            }

            // Latch a pending tone request once: one press -> one tone.
            if (tone == null && toneSlot.compareAndSet(true, false)) {
                tone = ToneOverlay(sampleRate)
            }

            if (tone != null) {
                tone = tone.mixInto(buf, frameSize)
            }

            // Blocking write of the full frame buffer.
            var off = 0
            while (off < frameSize) {
                if (skipRequested) return PlaybackCompletion.ExplicitlySkipped
                val n = track.write(buf, off, frameSize - off, AudioTrack.WRITE_BLOCKING)
                if (n <= 0) return PlaybackCompletion.Failed("write error: $n")
                off += n
            }

            pos += len
        }

        if (skipRequested) return PlaybackCompletion.ExplicitlySkipped
        // Let the framework drain its buffered tail before stopping, avoiding a
        // truncation click. The drain is bounded; cleanup() is authoritative.
        drainTail()
        return PlaybackCompletion.Completed
    }

    private fun complete(result: PlaybackCompletion) {
        if (!completion.isCompleted && !completion.isCancelled) completion.complete(result)
    }

    private suspend fun drainTail() {
        val startHead = track.playbackHeadPosition
        val writtenFrames = recording.samples.size.toLong()
        val deadlineMs = 2_000L
        val start = System.nanoTime()
        while ((track.playbackHeadPosition - startHead).toLong() < writtenFrames) {
            if (skipRequested) return
            if ((System.nanoTime() - start) / 1_000_000L >= deadlineMs) return
            delay(10)
        }
    }

    private suspend fun cleanup() {
        withContext(NonCancellable) {
            runCatching { if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop() }
            runCatching { track.release() }
        }
    }

    /**
     * Saturating in-place rejection-tone overlay. Each phase tracks how many
     * frames have been consumed within it so one overlay can span multiple
     * stream buffers without per-buffer allocation. Lifecycle:
     * RampDown -> First(400 Hz) -> Second(300 Hz) -> RampUp -> done (null).
     */
    private class ToneOverlay(sampleRate: Int) {
        private val sampleRate = sampleRate
        private val toneFrames = sampleRate * TONE_SEGMENT_MS / 1_000
        private var phase = Phase.RampDown
        private var consumedInPhase = 0

        enum class Phase { RampDown, First, Second, RampUp }

        /**
         * Mixes up to [len] frames of the current phase into [buf] and advances.
         * Returns null once the full overlay has finished, else this.
         */
        fun mixInto(buf: ShortArray, len: Int): ToneOverlay? {
            var i = 0
            while (i < len) {
                val phaseFrames = phaseFrameCount()
                val step = min(len - i, phaseFrames - consumedInPhase)
                when (phase) {
                    Phase.RampDown -> renderRamp(buf, i, step, GAIN_UNITY_Q, GAIN_DUCK_Q)
                    Phase.First -> renderTone(buf, i, step, TONE_FIRST_HZ)
                    Phase.Second -> renderTone(buf, i, step, TONE_SECOND_HZ)
                    Phase.RampUp -> renderRamp(buf, i, step, GAIN_DUCK_Q, GAIN_UNITY_Q)
                }
                i += step
                consumedInPhase += step
                if (consumedInPhase >= phaseFrames) {
                    phase = nextPhase(phase) ?: return null
                    consumedInPhase = 0
                }
            }
            return this
        }

        private fun phaseFrameCount(): Int = when (phase) {
            Phase.RampDown, Phase.RampUp -> DUCK_RAMP_FRAMES
            Phase.First, Phase.Second -> toneFrames
        }

        private fun nextPhase(p: Phase): Phase? = when (p) {
            Phase.RampDown -> Phase.First
            Phase.First -> Phase.Second
            Phase.Second -> Phase.RampUp
            Phase.RampUp -> null
        }

        private fun renderTone(buf: ShortArray, offset: Int, count: Int, freqHz: Double) {
            var idx = consumedInPhase
            for (i in 0 until count) {
                val ducked = (buf[offset + i].toInt() * GAIN_DUCK_Q) shr 15
                val ph = 2.0 * PI * freqHz * idx / this.sampleRate
                val toneVal = (sin(ph) * Short.MAX_VALUE * TONE_AMPLITUDE).roundToInt()
                val sum = ducked + toneVal
                buf[offset + i] = sum.toShort().coerceIn(Short.MIN_VALUE, Short.MAX_VALUE)
                idx++
            }
        }

        private fun renderRamp(buf: ShortArray, offset: Int, count: Int, from: Int, to: Int) {
            val span = DUCK_RAMP_FRAMES
            for (i in 0 until count) {
                val p = (consumedInPhase + i).coerceIn(0, span)
                val gain = from + (to - from) * p / span
                buf[offset + i] = ((buf[offset + i].toInt() * gain) shr 15).toShort()
            }
        }
    }
}