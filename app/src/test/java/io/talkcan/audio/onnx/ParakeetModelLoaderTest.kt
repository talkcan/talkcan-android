package io.talkcan.audio.onnx

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import io.talkcan.audio.TranscriptionOutcome
import io.talkcan.model.SttModelStatus
import io.mockk.every
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

class ParakeetModelLoaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun emptyInputBypassesLoadingAndOnnxAccess() {
        val queuedExecutor = QueuedExecutor()
        val sessionFactory = mockk<OrtSessionFactory>()
        val transcriber = ParakeetOnnxTranscriber(
            modelDir = createModelDir(),
            environmentProvider = OrtEnvironmentProvider { mockk() },
            sessionFactory = sessionFactory,
            loadExecutor = queuedExecutor,
            sessionOptionsFactory = OrtSessionOptionsFactory { mockk(relaxed = true) },
        )

        try {
            assertEquals(TranscriptionOutcome.EmptyInput, transcriber.transcribe(FloatArray(0)))
            verify(exactly = 0) { sessionFactory.create(any(), any(), any()) }
        } finally {
            transcriber.close()
        }
    }

    @Test
    fun queuedLoadTransitionsFromLoadingToAtomicallyReady() {
        val queuedExecutor = QueuedExecutor()
        val fixture = fixture(loadExecutor = queuedExecutor)
        try {
            assertEquals(SttModelStatus.Loading, fixture.loader.modelStatus)
            assertEquals(ParakeetModelAccess.NotReady, fixture.loader.withModel(1) { "unexpected" })

            queuedExecutor.runNext()

            assertEquals(SttModelStatus.Ready, fixture.loader.modelStatus)
            assertNull(fixture.loader.loadError)
            assertEquals(
                ParakeetModelAccess.Success("ready"),
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
        val encoder = mockk<OrtSession>(relaxed = true)
        val longMessage = "x".repeat(700)
        val sessionFactory = OrtSessionFactory { _, path, _ ->
            when (File(path).name) {
                ParakeetOnnxContract.ENCODER_FILE -> encoder
                else -> throw IllegalStateException(longMessage)
            }
        }
        val loader = ParakeetModelLoader(
            modelDir = modelDir,
            environmentProvider = OrtEnvironmentProvider { environment },
            sessionFactory = sessionFactory,
            loadExecutor = DIRECT_EXECUTOR,
            sessionOptionsFactory = OrtSessionOptionsFactory { options },
        )

        assertEquals(SttModelStatus.Failed, loader.modelStatus)
        val error = requireNotNull(loader.loadError)
        assertTrue(error.startsWith("Parakeet model load failed: "))
        assertEquals(512, error.length)
        assertTrue(loader.withModel(1_000) { Unit } is ParakeetModelAccess.LoadFailed)
        verify(exactly = 1) { encoder.close() }
        loader.close()
        verify(exactly = 1) { encoder.close() }
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
            val first = workers.submit<ParakeetModelAccess<Int>> {
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
            val second = workers.submit<ParakeetModelAccess<Int>> {
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
            assertEquals(ParakeetModelAccess.Success(1), first.get(2, TimeUnit.SECONDS))
            assertEquals(ParakeetModelAccess.Success(2), second.get(2, TimeUnit.SECONDS))
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
        val releaseDecoderCreation = CountDownLatch(1)
        val decoderCreationEntered = CountDownLatch(1)
        val sessions = sessions()
        val loader = ParakeetModelLoader(
            modelDir = createModelDir(),
            environmentProvider = OrtEnvironmentProvider { sessions.environment },
            sessionFactory = OrtSessionFactory { _, path, _ ->
                when (File(path).name) {
                    ParakeetOnnxContract.ENCODER_FILE -> sessions.encoder
                    ParakeetOnnxContract.DECODER_JOINT_FILE -> {
                        decoderCreationEntered.countDown()
                        assertTrue(releaseDecoderCreation.await(2, TimeUnit.SECONDS))
                        sessions.decoder
                    }
                    ParakeetOnnxContract.PREPROCESSOR_FILE -> sessions.preprocessor
                    else -> error("unexpected model path: $path")
                }
            },
            loadExecutor = loadExecutor,
            sessionOptionsFactory = OrtSessionOptionsFactory { sessions.options },
        )
        try {
            assertTrue(decoderCreationEntered.await(2, TimeUnit.SECONDS))
            loader.close()
            loader.close()
            assertEquals(SttModelStatus.Failed, loader.modelStatus)
            assertEquals("Parakeet engine closed during model load", loader.loadError)
            assertEquals(ParakeetModelAccess.Closed, loader.withModel(1_000) { Unit })

            releaseDecoderCreation.countDown()
            loadExecutor.submit { }.get(2, TimeUnit.SECONDS)

            verifyOrder {
                sessions.preprocessor.close()
                sessions.decoder.close()
                sessions.encoder.close()
            }
            verify(exactly = 1) { sessions.preprocessor.close() }
            verify(exactly = 1) { sessions.decoder.close() }
            verify(exactly = 1) { sessions.encoder.close() }
        } finally {
            releaseDecoderCreation.countDown()
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
            val operation = workers.submit<ParakeetModelAccess<String>> {
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
            assertEquals(ParakeetModelAccess.Success("done"), operation.get(2, TimeUnit.SECONDS))
            close.get(2, TimeUnit.SECONDS)
            fixture.loader.close()

            verify(exactly = 1) { fixture.sessions.preprocessor.close() }
            verify(exactly = 1) { fixture.sessions.decoder.close() }
            verify(exactly = 1) { fixture.sessions.encoder.close() }
        } finally {
            releaseOperation.countDown()
            workers.shutdownNow()
            fixture.loader.close()
        }
    }

    @Test
    fun postCloseAccessAndTranscriptionRejectBeforeModelWork() {
        val fixture = fixture(loadExecutor = DIRECT_EXECUTOR)
        val operations = AtomicInteger()
        fixture.loader.close()
        assertEquals(
            ParakeetModelAccess.Closed,
            fixture.loader.withModel(1_000) {
                operations.incrementAndGet()
            },
        )
        assertEquals(0, operations.get())

        val transcriber = ParakeetOnnxTranscriber(
            modelDir = createModelDir(),
            environmentProvider = OrtEnvironmentProvider { fixture.sessions.environment },
            sessionFactory = sessionFactory(fixture.sessions),
            loadExecutor = DIRECT_EXECUTOR,
            sessionOptionsFactory = OrtSessionOptionsFactory { fixture.sessions.options },
        )
        transcriber.close()
        assertEquals(
            TranscriptionOutcome.Failure("Parakeet engine is closed"),
            transcriber.transcribe(floatArrayOf(0f)),
        )
        transcriber.close()
    }

    private fun fixture(loadExecutor: Executor): Fixture {
        val sessions = sessions()
        val loader = ParakeetModelLoader(
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
        val encoder = mockk<OrtSession>(relaxed = true)
        val preprocessor = mockk<OrtSession>(relaxed = true)
        val decoder = mockk<OrtSession>(relaxed = true)
        val stateInfo = mockk<TensorInfo>()
        every { stateInfo.shape } returns longArrayOf(2, -1, 640)
        val state1 = mockk<NodeInfo>()
        val state2 = mockk<NodeInfo>()
        every { state1.info } returns stateInfo
        every { state2.info } returns stateInfo
        every { decoder.inputInfo } returns mapOf(
            ParakeetOnnxContract.DECODER_INPUT_STATE_1 to state1,
            ParakeetOnnxContract.DECODER_INPUT_STATE_2 to state2,
        )
        return Sessions(environment, options, encoder, decoder, preprocessor)
    }

    private fun sessionFactory(sessions: Sessions): OrtSessionFactory =
        OrtSessionFactory { _, path, _ ->
            when (File(path).name) {
                ParakeetOnnxContract.ENCODER_FILE -> sessions.encoder
                ParakeetOnnxContract.DECODER_JOINT_FILE -> sessions.decoder
                ParakeetOnnxContract.PREPROCESSOR_FILE -> sessions.preprocessor
                else -> error("unexpected model path: $path")
            }
        }

    private fun createModelDir(): File = temporaryFolder.newFolder().apply {
        File(this, ParakeetOnnxContract.ENCODER_FILE).writeBytes(byteArrayOf(1))
        File(this, ParakeetOnnxContract.DECODER_JOINT_FILE).writeBytes(byteArrayOf(1))
        File(this, ParakeetOnnxContract.PREPROCESSOR_FILE).writeBytes(byteArrayOf(1))
        File(this, ParakeetOnnxContract.VOCABULARY_FILE).writeText("token 0\n<blk> 1\n")
    }

    private data class Sessions(
        val environment: OrtEnvironment,
        val options: OrtSession.SessionOptions,
        val encoder: OrtSession,
        val decoder: OrtSession,
        val preprocessor: OrtSession,
    )

    private data class Fixture(
        val loader: ParakeetModelLoader,
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
    }
}
