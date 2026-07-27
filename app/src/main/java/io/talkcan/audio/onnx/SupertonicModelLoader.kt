/*
 * Portions adapted from transcribe-rs 0.3.11, revision
 * 343768c100d566b135fbb7a2441e61fa8aa177f2.
 * Copyright (c) 2025 Ilya Stupakov.
 *
 * Portions adapted from the official Supertonic Java reference, revision
 * dff55dc00064c398736080c78195f577527832ae.
 * Copyright (c) 2025 Supertone Inc.
 *
 * MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.talkcan.audio.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.talkcan.model.TtsModelStatus
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Completed, immutable Supertonic model aggregate.
 *
 * Owns exactly the four shipped ONNX sessions — duration predictor, text
 * encoder, vector estimator, vocoder — plus the parsed `tts.json` runtime
 * configuration and the `unicode_indexer.json` code-point table. It retains
 * a non-owned reference to the process-shared [OrtEnvironment] its sessions
 * were created against so inference callers build per-call tensors on the
 * same environment; it never closes the environment and holds no per-call
 * tensors: inference callers create and close their own temporary values in
 * the lexical scope that creates them.
 *
 * Construction order follows the pinned reference pipeline (duration
 * predictor, text encoder, vector estimator, vocoder); [close] releases the
 * sessions exactly once, in reverse construction order. Instances are
 * published all-or-nothing by [SupertonicModelLoader] and are never visible
 * partially.
 */
internal class LoadedSupertonicModel internal constructor(
    /**
     * Process-shared environment the sessions were created against. Non-owned:
     * inference callers create per-call tensors on it; [close] never closes it.
     */
    val environment: OrtEnvironment,
    val durationPredictor: OrtSession,
    val textEncoder: OrtSession,
    val vectorEstimator: OrtSession,
    val vocoder: OrtSession,
    val config: SupertonicConfig,
    val indexer: SupertonicUnicodeIndexer,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        var failure: Throwable? = null
        for (session in listOf(vocoder, vectorEstimator, textEncoder, durationPredictor)) {
            try {
                session.close()
            } catch (closeFailure: Throwable) {
                if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
            }
        }
        failure?.let { throw it }
    }
}

/**
 * Outcome of one serialized [SupertonicModelLoader.withModel] access attempt.
 *
 * - [Success]: the model was ready and the operation completed; [Success.value]
 *   is its result.
 * - [NotReady]: the bounded readiness wait elapsed while still loading.
 * - [Closed]: the loader is closed; nothing entered ONNX Runtime.
 * - [LoadFailed]: construction failed; [LoadFailed.message] is the bounded
 *   load diagnostic.
 */
internal sealed interface SupertonicModelAccess<out R> {
    data class Success<R>(val value: R) : SupertonicModelAccess<R>
    data object NotReady : SupertonicModelAccess<Nothing>
    data object Closed : SupertonicModelAccess<Nothing>
    data class LoadFailed(val message: String) : SupertonicModelAccess<Nothing>
}

/**
 * Background-loading owner of the Supertonic model sessions.
 *
 * Construction is cheap: the constructor immediately exposes
 * [TtsModelStatus.Loading] and submits session construction to [loadExecutor].
 * The duration-predictor, text-encoder, vector-estimator, and vocoder
 * sessions, the `tts.json` runtime configuration, and the Unicode indexer
 * are built into a local [LoadedSupertonicModel] and published atomically
 * only after every part succeeds. Any partial construction is closed in
 * reverse order by the construction scope and never becomes visible. The
 * loader never closes the process-shared environment.
 *
 * Lifecycle state machine; every transition happens under one lock:
 *
 * ```
 *               ┌─────────> Ready ───close──┐
 * Loading ──────┤                           v
 *               └─────────> Failed <──── Failed (terminal, fail-closed)
 * ```
 *
 * - Close during load: close marks the loader closed under the lock; when the
 *   load task completes it discards (closes) its local aggregate instead of
 *   publishing it. An in-flight ONNX session construction cannot be
 *   interrupted, so a superseded load finishes its work before discarding —
 *   bounded, deterministic, and never leaked.
 * - Close racing inference: [withModel] holds the lifecycle lock for the
 *   whole operation, so an in-flight serialized operation retains ownership
 *   until it returns; close then releases the sessions. No later operation
 *   can enter ONNX Runtime.
 * - After close, [modelStatus] reports [TtsModelStatus.Failed] with a closed
 *   diagnostic and [withModel] returns [SupertonicModelAccess.Closed].
 *
 * Test seams: [environmentProvider], [sessionFactory], [sessionOptionsFactory],
 * and [loadExecutor] are injectable; a directly-executing executor makes
 * construction synchronous and deterministic.
 */
internal class SupertonicModelLoader(
    private val modelDir: File,
    private val environmentProvider: OrtEnvironmentProvider = ProcessOrtEnvironmentProvider,
    private val sessionFactory: OrtSessionFactory = OrtSessionFactory.default(),
    loadExecutor: Executor? = null,
    private val sessionOptionsFactory: OrtSessionOptionsFactory = OrtSessionOptionsFactory.default(),
) : AutoCloseable {
    private val lifecycleLock = ReentrantLock()
    private val terminalCondition = lifecycleLock.newCondition()

    private val ownedExecutor: ExecutorService?
    private val executor: Executor

    private var publishedModel: LoadedSupertonicModel? = null
    private var status: TtsModelStatus = TtsModelStatus.Loading
    private var failureMessage: String? = null
    private var closed = false

    init {
        if (loadExecutor != null) {
            executor = loadExecutor
            ownedExecutor = null
        } else {
            val owned = Executors.newSingleThreadExecutor { task ->
                Thread(task, LOADER_THREAD_NAME).apply { isDaemon = true }
            }
            ownedExecutor = owned
            executor = owned
        }

        try {
            executor.execute { loadModel() }
        } catch (submissionFailure: Throwable) {
            lifecycleLock.withLock {
                status = TtsModelStatus.Failed
                failureMessage = boundedDiagnostic(submissionFailure)
                terminalCondition.signalAll()
            }
            ownedExecutor?.shutdown()
        }
    }

    /**
     * Current lifecycle state; never [TtsModelStatus.Idle]. Safe to poll from
     * any thread. Reports [TtsModelStatus.Failed] once closed.
     */
    val modelStatus: TtsModelStatus
        get() = lifecycleLock.withLock { status }

    /** Bounded diagnostic of the latest load failure or post-close rejection. */
    val loadError: String?
        get() = lifecycleLock.withLock { failureMessage }

    /**
     * Run [operation] against the loaded model aggregate.
     *
     * One call holds the lifecycle lock for its entire duration, so accesses
     * are serialized against each other and against [close]; a complete
     * multi-chunk synthesis belongs inside a single call. Waits for loading
     * to reach a terminal state for at most [timeoutMillis], then resolves:
     * [SupertonicModelAccess.Closed] if the loader is closed,
     * [SupertonicModelAccess.NotReady] if the wait elapsed while still
     * loading, [SupertonicModelAccess.LoadFailed] with the bounded diagnostic
     * if construction failed, and [SupertonicModelAccess.Success] wrapping
     * the operation result otherwise. Exceptions thrown by [operation]
     * propagate to the caller; the model remains usable by later accesses.
     */
    fun <R> withModel(
        timeoutMillis: Long,
        operation: (LoadedSupertonicModel) -> R,
    ): SupertonicModelAccess<R> {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive, was $timeoutMillis" }
        lifecycleLock.withLock {
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (status == TtsModelStatus.Loading && !closed) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) return SupertonicModelAccess.NotReady
                terminalCondition.awaitNanos(remainingNanos)
            }
            if (closed) return SupertonicModelAccess.Closed
            val model = publishedModel
                ?: return SupertonicModelAccess.LoadFailed(failureMessage ?: "Supertonic model load failed")
            return SupertonicModelAccess.Success(operation(model))
        }
    }

    /**
     * Release this loader generation. Idempotent: the first call closes the
     * published aggregate exactly once (later calls are no-ops) and shuts the
     * loader-owned executor down; a load still in flight discards its local
     * aggregate when it completes. Never closes the process-shared
     * environment.
     */
    override fun close() {
        val condemned = lifecycleLock.withLock {
            if (closed) return
            closed = true
            val previous = publishedModel
            publishedModel = null
            if (status != TtsModelStatus.Failed) {
                failureMessage = if (status == TtsModelStatus.Loading) {
                    "Supertonic engine closed during model load"
                } else {
                    "Supertonic engine closed"
                }
                status = TtsModelStatus.Failed
            }
            terminalCondition.signalAll()
            previous
        }
        condemned?.close()
        ownedExecutor?.shutdown()
    }

    private fun loadModel() {
        val result = runCatching { buildModel() }
        lifecycleLock.withLock {
            if (closed) {
                // Close won the race: discard the local aggregate, publish nothing.
                result.getOrNull()?.close()
                return
            }
            result.fold(
                onSuccess = { model ->
                    publishedModel = model
                    status = TtsModelStatus.Ready
                    failureMessage = null
                },
                onFailure = { error ->
                    status = TtsModelStatus.Failed
                    failureMessage = boundedDiagnostic(error)
                },
            )
            terminalCondition.signalAll()
        }
    }

    private fun buildModel(): LoadedSupertonicModel = OrtConstructionScope().use { construction ->
        val environment = environmentProvider.get()
        sessionOptionsFactory.create().use { options ->
            val durationPredictor = construction.own(
                createSession(environment, options, DURATION_PREDICTOR_FILE),
            )
            val textEncoder = construction.own(
                createSession(environment, options, TEXT_ENCODER_FILE),
            )
            val vectorEstimator = construction.own(
                createSession(environment, options, VECTOR_ESTIMATOR_FILE),
            )
            val vocoder = construction.own(
                createSession(environment, options, VOCODER_FILE),
            )

            val configFile = File(modelDir, SupertonicOnnxContract.CONFIG_FILE)
            if (!configFile.isFile) {
                throw FileNotFoundException("missing Supertonic config file: ${configFile.path}")
            }
            val config = SupertonicConfig.load(modelDir)

            val indexerFile = File(modelDir, SupertonicOnnxContract.UNICODE_INDEXER_FILE)
            if (!indexerFile.isFile) {
                throw FileNotFoundException("missing Supertonic unicode indexer file: ${indexerFile.path}")
            }
            val indexer = SupertonicUnicodeIndexer.load(indexerFile)

            val model = LoadedSupertonicModel(
                environment = environment,
                durationPredictor = durationPredictor,
                textEncoder = textEncoder,
                vectorEstimator = vectorEstimator,
                vocoder = vocoder,
                config = config,
                indexer = indexer,
            )
            construction.releaseAll()
            model
        }
    }

    private fun createSession(
        environment: OrtEnvironment,
        options: OrtSession.SessionOptions,
        fileName: String,
    ): OrtSession {
        val file = File(modelDir, fileName)
        if (!file.isFile) {
            throw FileNotFoundException("missing Supertonic model file: ${file.path}")
        }
        return sessionFactory.create(environment, file.absolutePath, options)
    }

    private fun boundedDiagnostic(error: Throwable): String {
        val detail = error.message?.ifBlank { null } ?: error.javaClass.simpleName
        return "Supertonic model load failed: $detail".take(MAX_LOAD_ERROR_CHARS)
    }

    private companion object {
        const val LOADER_THREAD_NAME = "supertonic-model-loader"
        const val MAX_LOAD_ERROR_CHARS = 512

        /** Shipped model asset basenames inside the Supertonic model directory. */
        const val DURATION_PREDICTOR_FILE = "duration_predictor.onnx"
        const val TEXT_ENCODER_FILE = "text_encoder.onnx"
        const val VECTOR_ESTIMATOR_FILE = "vector_estimator.onnx"
        const val VOCODER_FILE = "vocoder.onnx"
    }
}
