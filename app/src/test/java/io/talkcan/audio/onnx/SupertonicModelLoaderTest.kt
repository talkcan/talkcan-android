package io.talkcan.audio.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.talkcan.audio.SynthesisOutcome
import io.talkcan.audio.SynthesisRequest
import io.talkcan.model.TtsModelStatus
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SupertonicModelLoaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun emptyTextBypassesLoadingAndOnnxAccess() {
        val queuedExecutor = QueuedExecutor()
        val sessionFactory = mockk<OrtSessionFactory>()
        val synthesizer = SupertonicOnnxSynthesizer(
            modelDir = createModelDir(),
            environmentProvider = OrtEnvironmentProvider { mockk() },
            sessionFactory = sessionFactory,
            loadExecutor = queuedExecutor,
            sessionOptionsFactory = OrtSessionOptionsFactory { mockk(relaxed = true) },
        )

        try {
            assertEquals(TtsModelStatus.Loading, synthesizer.modelStatus)
            assertEquals(SynthesisOutcome.EmptyText, synthesizer.synthesize(synthesisRequest(text = "  ")))
            verify(exactly = 0) { sessionFactory.create(any(), any(), any()) }
        } finally {
            synthesizer.close()
        }
    }

    @Test
    fun queuedLoadTransitionsFromLoadingToAtomicallyReady() {
        val queuedExecutor = QueuedExecutor()
        val fixture = fixture(loadExecutor = queuedExecutor)
        try {
            assertEquals(TtsModelStatus.Loading, fixture.loader.modelStatus)
            assertEquals(SupertonicModelAccess.NotReady, fixture.loader.withModel(1) { "unexpected" })

            queuedExecutor.runNext()

            assertEquals(TtsModelStatus.Ready, fixture.loader.modelStatus)
            assertNull(fixture.loader.loadError)
            assertEquals(
                SupertonicModelAccess.Success("ready"),
                fixture.loader.withModel(1_000) { "ready" },
            )
        } finally {
            fixture.loader.close()
        }
    }

    @Test
    fun loadFailureClosesPartialConstructionAndBoundsDiagnostic() {
        val modelDir = createModelDir()
        val environment = mockk<OrtEnvironment>()
        val options = mockk<OrtSession.SessionOptions>(relaxed = true)
        val durationPredictor = mockk<OrtSession>(relaxed = true)
        val longMessage = "x".repeat(700)
        val sessionFactory = OrtSessionFactory { _, path, _ ->
            when (File(path).name) {
                DURATION_PREDICTOR_FILE -> durationPredictor
                else -> throw IllegalStateException(longMessage)
            }
        }
        val loader = SupertonicModelLoader(
            modelDir = modelDir,
            environmentProvider = OrtEnvironmentProvider { environment },
            sessionFactory = sessionFactory,
            loadExecutor = DIRECT_EXECUTOR,
            sessionOptionsFactory = OrtSessionOptionsFactory { options },
        )

        assertEquals(TtsModelStatus.Failed, loader.modelStatus)
        val error = requireNotNull(loader.loadError)
        assertTrue(error.startsWith("Supertonic model load failed: "))
        assertEquals(512, error.length)
        assertTrue(loader.withModel(1_000) { Unit } is SupertonicModelAccess.LoadFailed)
        verify(exactly = 1) { durationPredictor.close() }
        loader.close()
        verify(exactly = 1) { durationPredictor.close() }
    }

    @Test
    fun concurrentModelOperationsAreSerialized() {
        val fixture = fixture(loadExecutor = DIRECT_EXECUTOR)
        val workers = Executors.newFixedThreadPool(2)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        try {
            val first = workers.submit<SupertonicModelAccess<Int>> {
                fixture.loader.withModel(1_000) {
                    val now = active.incrementAndGet()
                    maximumActive.accumulateAndGet(now, ::maxOf)
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                    active.decrementAndGet()
                    1
                }
            }
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            val second = workers.submit<SupertonicModelAccess<Int>> {
                fixture.loader.withModel(1_000) {
                    val now = active.incrementAndGet()
                    maximumActive.accumulateAndGet(now, ::maxOf)
                    secondEntered.countDown()
                    active.decrementAndGet()
                    2
                }
            }

            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            assertEquals(SupertonicModelAccess.Success(1), first.get(2, TimeUnit.SECONDS))
            assertEquals(SupertonicModelAccess.Success(2), second.get(2, TimeUnit.SECONDS))
            assertEquals(1, maximumActive.get())
        } finally {
            releaseFirst.countDown()
            workers.shutdownNow()
            fixture.loader.close()
        }
    }

    @Test
    fun closeDuringLoadPreventsPublicationAndClosesCompletedAggregateOnce() {
        val loadExecutor = Executors.newSingleThreadExecutor()
        val releaseTextEncoderCreation = CountDownLatch(1)
        val textEncoderCreationEntered = CountDownLatch(1)
        val sessions = sessions()
        val loader = SupertonicModelLoader(
            modelDir = createModelDir(),
            environmentProvider = OrtEnvironmentProvider { sessions.environment },
            sessionFactory = OrtSessionFactory { _, path, _ ->
                when (File(path).name) {
                    DURATION_PREDICTOR_FILE -> sessions.durationPredictor
                    TEXT_ENCODER_FILE -> {
                        textEncoderCreationEntered.countDown()
                        assertTrue(releaseTextEncoderCreation.await(2, TimeUnit.SECONDS))
                        sessions.textEncoder
                    }
                    VECTOR_ESTIMATOR_FILE -> sessions.vectorEstimator
                    VOCODER_FILE -> sessions.vocoder
                    else -> error("unexpected model path: $path")
                }
            },
            loadExecutor = loadExecutor,
            sessionOptionsFactory = OrtSessionOptionsFactory { sessions.options },
        )
        try {
            assertTrue(textEncoderCreationEntered.await(2, TimeUnit.SECONDS))
            loader.close()
            loader.close()
            assertEquals(TtsModelStatus.Failed, loader.modelStatus)
            assertEquals("Supertonic engine closed during model load", loader.loadError)
            assertEquals(SupertonicModelAccess.Closed, loader.withModel(1_000) { Unit })

            releaseTextEncoderCreation.countDown()
            loadExecutor.submit { }.get(2, TimeUnit.SECONDS)

            verifyOrder {
                sessions.vocoder.close()
                sessions.vectorEstimator.close()
                sessions.textEncoder.close()
                sessions.durationPredictor.close()
            }
            verify(exactly = 1) { sessions.vocoder.close() }
            verify(exactly = 1) { sessions.vectorEstimator.close() }
            verify(exactly = 1) { sessions.textEncoder.close() }
            verify(exactly = 1) { sessions.durationPredictor.close() }
        } finally {
            releaseTextEncoderCreation.countDown()
            loadExecutor.shutdownNow()
        }
    }

    @Test
    fun closeWaitsForInFlightOperationThenClosesSessionsOnce() {
        val fixture = fixture(loadExecutor = DIRECT_EXECUTOR)
        val workers = Executors.newFixedThreadPool(2)
        val operationEntered = CountDownLatch(1)
        val releaseOperation = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        try {
            val operation = workers.submit<SupertonicModelAccess<String>> {
                fixture.loader.withModel(1_000) {
                    operationEntered.countDown()
                    assertTrue(releaseOperation.await(2, TimeUnit.SECONDS))
                    "done"
                }
            }
            assertTrue(operationEntered.await(2, TimeUnit.SECONDS))
            val close = workers.submit {
                closeStarted.countDown()
                fixture.loader.close()
            }
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
            assertFalse(close.isDone)

            releaseOperation.countDown()
            assertEquals(SupertonicModelAccess.Success("done"), operation.get(2, TimeUnit.SECONDS))
            close.get(2, TimeUnit.SECONDS)
            fixture.loader.close()

            verifyOrder {
                fixture.sessions.vocoder.close()
                fixture.sessions.vectorEstimator.close()
                fixture.sessions.textEncoder.close()
                fixture.sessions.durationPredictor.close()
            }
            verify(exactly = 1) { fixture.sessions.vocoder.close() }
            verify(exactly = 1) { fixture.sessions.vectorEstimator.close() }
            verify(exactly = 1) { fixture.sessions.textEncoder.close() }
            verify(exactly = 1) { fixture.sessions.durationPredictor.close() }
        } finally {
            releaseOperation.countDown()
            workers.shutdownNow()
            fixture.loader.close()
        }
    }

    @Test
    fun idempotentCloseClosesSessionsExactlyOnce() {
        val fixture = fixture(loadExecutor = DIRECT_EXECUTOR)
        fixture.loader.close()
        fixture.loader.close()
        fixture.loader.close()

        assertEquals(TtsModelStatus.Failed, fixture.loader.modelStatus)
        assertEquals("Supertonic engine closed", fixture.loader.loadError)
        verify(exactly = 1) { fixture.sessions.vocoder.close() }
        verify(exactly = 1) { fixture.sessions.vectorEstimator.close() }
        verify(exactly = 1) { fixture.sessions.textEncoder.close() }
        verify(exactly = 1) { fixture.sessions.durationPredictor.close() }
    }

    @Test
    fun postCloseAccessAndSynthesisRejectBeforeModelWork() {
        val fixture = fixture(loadExecutor = DIRECT_EXECUTOR)
        val operations = AtomicInteger()
        fixture.loader.close()
        assertEquals(
            SupertonicModelAccess.Closed,
            fixture.loader.withModel(1_000) {
                operations.incrementAndGet()
            },
        )
        assertEquals(0, operations.get())

        val synthesizer = SupertonicOnnxSynthesizer(
            modelDir = createModelDir(),
            environmentProvider = OrtEnvironmentProvider { fixture.sessions.environment },
            sessionFactory = sessionFactory(fixture.sessions),
            loadExecutor = DIRECT_EXECUTOR,
            sessionOptionsFactory = OrtSessionOptionsFactory { fixture.sessions.options },
        )
        synthesizer.close()
        assertEquals(
            SynthesisOutcome.Failure("Supertonic engine is closed"),
            synthesizer.synthesize(synthesisRequest()),
        )
        synthesizer.close()
    }

    @Test
    fun synthesizeMapsLoadFailureToBoundedFailure() {
        val synthesizer = SupertonicOnnxSynthesizer(
            modelDir = temporaryFolder.newFolder(),
            environmentProvider = OrtEnvironmentProvider { mockk() },
            sessionFactory = mockk(),
            loadExecutor = DIRECT_EXECUTOR,
            sessionOptionsFactory = OrtSessionOptionsFactory { mockk(relaxed = true) },
        )
        try {
            assertEquals(TtsModelStatus.Failed, synthesizer.modelStatus)
            val outcome = synthesizer.synthesize(synthesisRequest())
            val failure = outcome as? SynthesisOutcome.Failure
            val reason = requireNotNull(failure).reason
            assertTrue(reason.startsWith("Supertonic model load failed: missing Supertonic model file: "))
        } finally {
            synthesizer.close()
        }
    }

    private fun fixture(loadExecutor: Executor): Fixture {
        val sessions = sessions()
        val loader = SupertonicModelLoader(
            modelDir = createModelDir(),
            environmentProvider = OrtEnvironmentProvider { sessions.environment },
            sessionFactory = sessionFactory(sessions),
            loadExecutor = loadExecutor,
            sessionOptionsFactory = OrtSessionOptionsFactory { sessions.options },
        )
        return Fixture(loader, sessions)
    }

    private fun sessions(): Sessions {
        val environment = mockk<OrtEnvironment>()
        val options = mockk<OrtSession.SessionOptions>(relaxed = true)
        val durationPredictor = mockk<OrtSession>(relaxed = true)
        val textEncoder = mockk<OrtSession>(relaxed = true)
        val vectorEstimator = mockk<OrtSession>(relaxed = true)
        val vocoder = mockk<OrtSession>(relaxed = true)
        return Sessions(environment, options, durationPredictor, textEncoder, vectorEstimator, vocoder)
    }

    private fun sessionFactory(sessions: Sessions): OrtSessionFactory =
        OrtSessionFactory { _, path, _ ->
            when (File(path).name) {
                DURATION_PREDICTOR_FILE -> sessions.durationPredictor
                TEXT_ENCODER_FILE -> sessions.textEncoder
                VECTOR_ESTIMATOR_FILE -> sessions.vectorEstimator
                VOCODER_FILE -> sessions.vocoder
                else -> error("unexpected model path: $path")
            }
        }

    private fun createModelDir(): File = temporaryFolder.newFolder().apply {
        File(this, DURATION_PREDICTOR_FILE).writeBytes(byteArrayOf(1))
        File(this, TEXT_ENCODER_FILE).writeBytes(byteArrayOf(1))
        File(this, VECTOR_ESTIMATOR_FILE).writeBytes(byteArrayOf(1))
        File(this, VOCODER_FILE).writeBytes(byteArrayOf(1))
        File(this, SupertonicOnnxContract.CONFIG_FILE).writeText(
            """{"ae":{"sample_rate":44100,"base_chunk_size":512},""" +
                """"ttl":{"chunk_compress_factor":6,"latent_dim":24}}""",
        )
        File(this, SupertonicOnnxContract.UNICODE_INDEXER_FILE).writeText(
            // Exactly UNICODE_INDEXER_SIZE exact-integer entries, as the shipped asset carries.
            "[" + "0,".repeat(SupertonicOnnxContract.UNICODE_INDEXER_SIZE - 1) + "0]",
        )
    }

    private fun synthesisRequest(text: String = "Hello."): SynthesisRequest =
        SynthesisRequest(
            text = text,
            voiceStylePath = "unused-style.json",
            lang = "en",
            totalSteps = 8,
            speed = 1.05f,
        )

    private data class Sessions(
        val environment: OrtEnvironment,
        val options: OrtSession.SessionOptions,
        val durationPredictor: OrtSession,
        val textEncoder: OrtSession,
        val vectorEstimator: OrtSession,
        val vocoder: OrtSession,
    )

    private data class Fixture(
        val loader: SupertonicModelLoader,
        val sessions: Sessions,
    )

    private class QueuedExecutor : Executor {
        private val tasks = LinkedBlockingQueue<Runnable>()

        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        fun runNext() {
            tasks.remove().run()
        }
    }

    private companion object {
        val DIRECT_EXECUTOR = Executor { command -> command.run() }

        /** Pinned asset basenames mirrored from SupertonicModelLoader's private companion. */
        const val DURATION_PREDICTOR_FILE = "duration_predictor.onnx"
        const val TEXT_ENCODER_FILE = "text_encoder.onnx"
        const val VECTOR_ESTIMATOR_FILE = "vector_estimator.onnx"
        const val VOCODER_FILE = "vocoder.onnx"
    }
}
