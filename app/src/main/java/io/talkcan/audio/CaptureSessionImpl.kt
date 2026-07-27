package io.talkcan.audio

import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The single active capture session created by [CaptureService.startSession].
 *
 * Runs the PCM read loop on [readDispatcher], buffers up to
 * [maxDurationMs] × [maxBufferSamplesFactor] worth of samples, and
 * finalizes with a [CaptureCompletion] on max-duration, stop, or cancel.
 *
 * Extracted from [CaptureService] to reduce the service's scope; only
 * [CaptureService] constructs instances.
 */
internal class CaptureSessionImpl(
    private val scope: CoroutineScope,
    private val opened: OpenedCaptureSource,
    @Suppress("UNUSED_PARAMETER") private val coldStart: Boolean,
    private val readDispatcher: CoroutineDispatcher,
    private val maxDurationMs: Long,
    private val maxBufferSamplesFactor: Int,
    private val clock: () -> Long,
    private val onCaptureSignalChange: (Boolean) -> Unit,
    private val onLevelUpdate: (Float) -> Unit,
    private val onFinalize: (CaptureSessionImpl) -> Unit,
) : CaptureSession {
    override val frames: SharedFlow<ShortArray>
    override val completion: Deferred<CaptureCompletion>
    override val sampleRate: Int = opened.sampleRate

    private val buffer = mutableListOf<Short>()
    private val bufferLock = Any()
    private val finalizeLock = Any()
    @Volatile private var finalized: RecordedPcm? = null
    private val _completion = CompletableDeferred<CaptureCompletion>()

    private val readJob: Job

    init {
        val mutableFrames = MutableSharedFlow<ShortArray>(
            replay = 0,
            // DROP_OLDEST requires a positive buffer; one slot keeps the
            // most recent chunk available to a slow subscriber without
            // ever backpressuring the read loop.
            extraBufferCapacity = 1,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
        frames = mutableFrames.asSharedFlow()
        completion = _completion
        val sampleRate = opened.sampleRate
        val maxBufferSamples = sampleRate * maxBufferSamplesFactor
        readJob = scope.launch(readDispatcher) {
            readLoop(mutableFrames, maxBufferSamples)
        }
    }

    private suspend fun readLoop(
        emitter: MutableSharedFlow<ShortArray>,
        maxBufferSamples: Int,
    ) {
        val readBuffer = ShortArray(opened.bufferSizeShorts.coerceAtLeast(1))
        val startedAt = clock()
        try {
            while (scope.isActive && finalized == null) {
                if (clock() - startedAt >= maxDurationMs) {
                    finalize(CaptureCompletion.MaxDuration(readFinalPcm()))
                    return
                }
                val read = opened.read(readBuffer)
                if (read > 0) {
                    val chunk = readBuffer.copyOfRange(0, read)
                    accumulate(chunk, maxBufferSamples)
                    emitter.tryEmit(chunk)
                    onLevelUpdate(computeRms(chunk))
                }
                // Tick virtual time forward so test dispatchers can drive
                // the loop and the max-duration check deterministically.
                // In production this is a no-op-ish 1ms pause between
                // chunks; [android.media.AudioRecord.read] already blocks
                // the IO thread until the next chunk is available.
                delay(1)
            }
        } finally {
            opened.close()
        }
    }

    private fun accumulate(chunk: ShortArray, maxBufferSamples: Int) {
        synchronized(bufferLock) {
            val remaining = maxBufferSamples - buffer.size
            if (remaining <= 0) return
            val toCopy = minOf(chunk.size, remaining)
            for (i in 0 until toCopy) buffer += chunk[i]
        }
    }

    private fun computeRms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) {
            val n = s.toDouble() / Short.MAX_VALUE
            sum += n * n
        }
        return sqrt(sum / samples.size).toFloat()
    }

    private fun readFinalPcm(): RecordedPcm = synchronized(bufferLock) {
        RecordedPcm(buffer.toShortArray(), opened.sampleRate)
    }

    private fun finalize(reason: CaptureCompletion): RecordedPcm {
        val pcm = reason.recordedPcm
        synchronized(finalizeLock) {
            if (finalized != null) return finalized!!
            finalized = pcm
            onFinalize(this)
            onCaptureSignalChange(false)
            _completion.complete(reason)
        }
        readJob.cancel()
        return pcm
    }

    override suspend fun stop(): RecordedPcm {
        val existing = finalized
        if (existing != null) return existing
        return finalize(CaptureCompletion.Stopped(readFinalPcm())).also {
            // Ensure the read loop has unwound before returning so the
            // caller observes a quiescent source (matches the legacy
            // recorder's synchronous stop semantics).
            runCatching { readJob.join() }
        }
    }

    fun cancel(): RecordedPcm {
        val existing = finalized
        if (existing != null) return existing
        return finalize(CaptureCompletion.Cancelled(readFinalPcm()))
    }
}
