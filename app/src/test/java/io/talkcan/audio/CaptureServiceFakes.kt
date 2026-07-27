package io.talkcan.audio

import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope

/**
 * Shared capture-service fakes for controller tests.
 *
 * Each controller test (EchoControllerTest, SttControllerTest, etc.) needs a
 * [CaptureSource] that produces deterministic PCM so the controller's
 * post-stop behavior (playback, transcription, journal WAV) can be asserted.
 * These helpers centralize that pattern.
 */

object CaptureServiceFakes {
    /**
     * A [CaptureSource] that emits a fixed PCM buffer once, then signals
     * "no more data" on subsequent reads. Use [emptySource] for an idle source.
     */
    fun singleShotSource(
        pcm: ShortArray,
        sampleRate: Int = 16_000,
        sourceId: CaptureSourceId = CaptureSourceId.VoiceCommunication,
    ): SingleShotCaptureSource = SingleShotCaptureSource(pcm, sampleRate, sourceId)

    fun emptySource(
        sourceId: CaptureSourceId = CaptureSourceId.VoiceCommunication,
    ): SingleShotCaptureSource = SingleShotCaptureSource(shortArrayOf(), 16_000, sourceId)

    fun failingSource(
        sourceId: CaptureSourceId = CaptureSourceId.VoiceCommunication,
    ): SingleShotCaptureSource = SingleShotCaptureSource(shortArrayOf(), 16_000, sourceId, openShouldFail = true)

    fun testDispatcher(scope: TestScope): CoroutineDispatcher =
        scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher

    @OptIn(ExperimentalCoroutinesApi::class)
    fun newService(
        scope: TestScope,
        maxDurationMs: Long = CaptureService.DEFAULT_MAX_DURATION_MS,
    ): CaptureService = CaptureService(
        scope = scope,
        readDispatcher = testDispatcher(scope),
        maxDurationMs = maxDurationMs,
        clock = { scope.testScheduler.currentTime },
    )
}

class SingleShotCaptureSource(
    private val pcm: ShortArray,
    private val sampleRate: Int,
    override val sourceId: CaptureSourceId,
    private val openShouldFail: Boolean = false,
) : CaptureSource {
    var openCount: Int = 0; private set

    override suspend fun open(): OpenedCaptureSource? {
        openCount += 1
        if (openShouldFail) return null
        return SingleShotOpenedSource(pcm, sampleRate)
    }
}

private class SingleShotOpenedSource(
    private val pcm: ShortArray,
    override val sampleRate: Int,
) : OpenedCaptureSource {
    private var delivered = false

    override val bufferSizeShorts: Int = pcm.size.coerceAtLeast(1)

    override fun readNonBlocking(buffer: ShortArray): Int = 0

    override fun read(buffer: ShortArray): Int {
        if (delivered || pcm.isEmpty()) return 0
        delivered = true
        val n = minOf(pcm.size, buffer.size)
        pcm.copyInto(buffer, 0, 0, n)
        return n
    }

    override fun close() {}
}
