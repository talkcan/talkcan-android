package io.talkcan.audio

import kotlin.math.sqrt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/**
 * The single active capture session created by [CaptureService.startSession].
 *
 * Runs the PCM read loop on [readDispatcher], buffers up to
 * [maxDurationMs] worth of samples, and
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
    override val maxDurationMs: Long,
    private val clock: () -> Long,
    private val onCaptureSignalChange: (Boolean) -> Unit,
    private val onLevelUpdate: (Float) -> Unit,
    private val onFinalize: (CaptureSessionImpl) -> Unit,
    private val pcmOutput: PcmOutput,
) : CaptureSession, SemanticFeedbackEmitter {
    override val frames: SharedFlow<ShortArray>
    override val completion: Deferred<CaptureCompletion>
    override val sampleRate: Int = opened.sampleRate
    private val startedAt = clock()

    override val remainingDurationMs: Long
        get() {
            val elapsed = clock() - startedAt
            return (maxDurationMs - elapsed).coerceAtLeast(0L)
        }

    override val semanticFeedbackEmitter: SemanticFeedbackEmitter get() = this

    val emittedTones = mutableListOf<io.talkcan.service.CaptureFeedbackTone>()
    private val feedbackMutex = Mutex()
    private val feedbackLock = Any()
    private var inFlightFeedbackJob: Job? = null

    override suspend fun emit(tone: io.talkcan.service.CaptureFeedbackTone) {
        if (finalized != null) {
            throw IllegalStateException("Session finalized")
        }
        feedbackMutex.withLock {
            if (finalized != null) {
                throw IllegalStateException("Session finalized")
            }
            synchronized(emittedTones) {
                emittedTones.add(tone)
            }
            val job = scope.async {
                try {
                    pcmOutput.playCaptureFeedback(tone)
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    failure
                }
            }
            synchronized(feedbackLock) {
                if (finalized != null) {
                    job.cancel()
                    throw IllegalStateException("Session finalized")
                }
                inFlightFeedbackJob = job
            }
            try {
                job.await()?.let { throw it }
            } catch (e: CancellationException) {
                job.cancel()
                withContext(NonCancellable) {
                    runCatching { job.join() }
                }
                throw e
            } finally {
                synchronized(feedbackLock) {
                    if (inFlightFeedbackJob === job) {
                        inFlightFeedbackJob = null
                    }
                }
            }
        }
    }

    private val chunks = mutableListOf<ShortArray>()
    private var totalSampleCount = 0L
    private val maxSamples: Long
    private val bufferLock = Any()
    private val finalizeLock = Any()
    @Volatile private var finalized: RecordedPcm? = null
    private val _completion = CompletableDeferred<CaptureCompletion>()

    private val readJob: Job

    init {
        val sampleRate = opened.sampleRate
        maxSamples = try {
            if (maxDurationMs < 0) {
                throw IllegalArgumentException("maxDurationMs must be non-negative: $maxDurationMs")
            }
            val samplesLong = Math.multiplyExact(sampleRate.toLong(), maxDurationMs) / 1000L
            if (samplesLong > Int.MAX_VALUE) {
                throw ArithmeticException("Sample count exceeds Int.MAX_VALUE")
            }
            samplesLong
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("Sample count overflow: sampleRate=$sampleRate, maxDurationMs=$maxDurationMs", e)
        }

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
        readJob = scope.launch(readDispatcher) {
            readLoop(mutableFrames, maxSamples)
        }
    }

    private suspend fun readLoop(
        emitter: MutableSharedFlow<ShortArray>,
        maxSamples: Long,
    ) {
        val readBuffer = ShortArray(opened.bufferSizeShorts.coerceAtLeast(1))
        try {
            while (scope.isActive && finalized == null) {
                if (clock() - startedAt >= maxDurationMs) {
                    finalize(CaptureCompletion.MaxDuration(readFinalPcm()))
                    return
                }
                val read = opened.read(readBuffer)
                if (read > 0) {
                    val chunk = readBuffer.copyOfRange(0, read)
                    accumulate(chunk, maxSamples)
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

    private fun accumulate(chunk: ShortArray, maxSamples: Long) {
        synchronized(bufferLock) {
            val remaining = maxSamples - totalSampleCount
            if (remaining <= 0) return
            val toCopy = minOf(chunk.size.toLong(), remaining).toInt()
            if (toCopy <= 0) return
            val chunkToRetain = if (toCopy == chunk.size) {
                chunk
            } else {
                chunk.copyOfRange(0, toCopy)
            }
            chunks.add(chunkToRetain)
            totalSampleCount += toCopy
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
        val result = ShortArray(totalSampleCount.toInt())
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, destinationOffset = offset)
            offset += chunk.size
        }
        RecordedPcm(result, opened.sampleRate)
    }

    private suspend fun finalize(reason: CaptureCompletion): RecordedPcm {
        val callingJob = coroutineContext[Job]
        return withContext(NonCancellable) {
            val pcm = reason.recordedPcm
            val jobToCleanup: Job?
            synchronized(finalizeLock) {
                if (finalized != null) return@withContext finalized!!
                finalized = pcm
                jobToCleanup = synchronized(feedbackLock) {
                    val j = inFlightFeedbackJob
                    inFlightFeedbackJob = null
                    j
                }
            }

            jobToCleanup?.let { job ->
                job.cancel()
                runCatching { job.join() }
            }

            if (callingJob !== readJob) {
                readJob.cancel()
                runCatching { readJob.join() }
            } else {
                readJob.cancel()
            }

            synchronized(finalizeLock) {
                onFinalize(this@CaptureSessionImpl)
                onCaptureSignalChange(false)
                _completion.complete(reason)
            }
            pcm
        }
    }

    override suspend fun stop(): RecordedPcm {
        val existing = finalized
        if (existing != null) {
            completion.await()
            return existing
        }
        return finalize(CaptureCompletion.Stopped(readFinalPcm()))
    }

    suspend fun cancel(): RecordedPcm {
        val existing = finalized
        if (existing != null) {
            completion.await()
            return existing
        }
        return finalize(CaptureCompletion.Cancelled(readFinalPcm()))
    }
}
