/*
 * Portions adapted from transcribe-rs 0.3.11, revision
 * 343768c100d566b135fbb7a2441e61fa8aa177f2.
 * Copyright (c) 2025 Ilya Stupakov.
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

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import io.talkcan.model.SttModelStatus
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
 * Completed, immutable Parakeet model aggregate.
 *
 * Owns exactly the three shipped ONNX sessions — encoder, combined
 * decoder/joint, preprocessor — plus the parsed vocabulary and the
 * metadata-derived recurrent state layouts. It retains a non-owned
 * reference to the process-shared [OrtEnvironment] its sessions were
 * created against so inference callers build per-call tensors on the same
 * environment; it never closes the environment and holds no per-call
 * tensors: inference callers create and close their own temporary values
 * in the lexical scope that creates them.
 *
 * Construction order follows the pinned `transcribe-rs` reference (encoder,
 * decoder/joint, preprocessor); [close] releases the sessions exactly once,
 * in reverse construction order. Instances are published all-or-nothing by
 * [ParakeetModelLoader] and are never visible partially.
 */
internal class LoadedParakeetModel internal constructor(
    /**
     * Process-shared environment the sessions were created against. Non-owned:
     * inference callers create per-call tensors on it; [close] never closes it.
     */
    val environment: OrtEnvironment,
    val encoder: OrtSession,
    val decoderJoint: OrtSession,
    val preprocessor: OrtSession,
    val vocabulary: ParakeetVocabulary,
    /** Runtime layout of decoder `input_states_1`, read from session metadata. */
    val decoderState1: DecoderStateLayout,
    /** Runtime layout of decoder `input_states_2`, read from session metadata. */
    val decoderState2: DecoderStateLayout,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        var failure: Throwable? = null
        for (session in listOf(preprocessor, decoderJoint, encoder)) {
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
 * Outcome of one serialized [ParakeetModelLoader.withModel] access attempt.
 *
 * - [Success]: the model was ready and the operation completed; [Success.value]
 *   is its result.
 * - [NotReady]: the bounded readiness wait elapsed while still loading.
 * - [Closed]: the loader is closed; nothing entered ONNX Runtime.
 * - [LoadFailed]: construction failed; [LoadFailed.message] is the bounded
 *   load diagnostic.
 */
internal sealed interface ParakeetModelAccess<out R> {
    data class Success<R>(val value: R) : ParakeetModelAccess<R>
    data object NotReady : ParakeetModelAccess<Nothing>
    data object Closed : ParakeetModelAccess<Nothing>
    data class LoadFailed(val message: String) : ParakeetModelAccess<Nothing>
}

/**
 * Background-loading owner of the Parakeet model sessions.
 *
 * Construction is cheap: the constructor immediately exposes
 * [SttModelStatus.Loading] and submits session construction to [loadExecutor].
 * The encoder, decoder/joint, and preprocessor sessions, the decoder
 * recurrent-state metadata, and the vocabulary are built into a local
 * [LoadedParakeetModel] and published atomically only after every part
 * succeeds. Any partial construction is closed in reverse order by the
 * construction scope and never becomes visible. The loader never closes the
 * process-shared environment.
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
 * - After close, [modelStatus] reports [SttModelStatus.Failed] with a closed
 *   diagnostic and [withModel] returns [ParakeetModelAccess.Closed].
 *
 * Test seams: [environmentProvider], [sessionFactory], and [loadExecutor] are
 * injectable; a directly-executing executor makes construction synchronous
 * and deterministic.
 */
internal class ParakeetModelLoader(
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

    private var publishedModel: LoadedParakeetModel? = null
    private var status: SttModelStatus = SttModelStatus.Loading
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
                status = SttModelStatus.Failed
                failureMessage = boundedDiagnostic(submissionFailure)
                terminalCondition.signalAll()
            }
            ownedExecutor?.shutdown()
        }
    }

    /**
     * Current lifecycle state; never [SttModelStatus.Idle]. Safe to poll from
     * any thread. Reports [SttModelStatus.Failed] once closed.
     */
    val modelStatus: SttModelStatus
        get() = lifecycleLock.withLock { status }

    /** Bounded diagnostic of the latest load failure or post-close rejection. */
    val loadError: String?
        get() = lifecycleLock.withLock { failureMessage }

    /**
     * Run [operation] against the loaded model aggregate.
     *
     * One call holds the lifecycle lock for its entire duration, so accesses
     * are serialized against each other and against [close]; a complete
     * inference loop belongs inside a single call. Waits for loading to reach
     * a terminal state for at most [timeoutMillis], then resolves:
     * [ParakeetModelAccess.Closed] if the loader is closed, [ParakeetModelAccess.NotReady]
     * if the wait elapsed while still loading, [ParakeetModelAccess.LoadFailed]
     * with the bounded diagnostic if construction failed, and
     * [ParakeetModelAccess.Success] wrapping the operation result otherwise.
     * Exceptions thrown by [operation] propagate to the caller; the model
     * remains usable by later accesses.
     */
    fun <R> withModel(
        timeoutMillis: Long,
        operation: (LoadedParakeetModel) -> R,
    ): ParakeetModelAccess<R> {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive, was $timeoutMillis" }
        lifecycleLock.withLock {
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (status == SttModelStatus.Loading && !closed) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) return ParakeetModelAccess.NotReady
                terminalCondition.awaitNanos(remainingNanos)
            }
            if (closed) return ParakeetModelAccess.Closed
            val model = publishedModel
                ?: return ParakeetModelAccess.LoadFailed(failureMessage ?: "Parakeet model load failed")
            return ParakeetModelAccess.Success(operation(model))
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
            if (status != SttModelStatus.Failed) {
                failureMessage = if (status == SttModelStatus.Loading) {
                    "Parakeet engine closed during model load"
                } else {
                    "Parakeet engine closed"
                }
                status = SttModelStatus.Failed
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
                    status = SttModelStatus.Ready
                    failureMessage = null
                },
                onFailure = { error ->
                    status = SttModelStatus.Failed
                    failureMessage = boundedDiagnostic(error)
                },
            )
            terminalCondition.signalAll()
        }
    }

    private fun buildModel(): LoadedParakeetModel = OrtConstructionScope().use { construction ->
        val environment = environmentProvider.get()
        sessionOptionsFactory.create().use { options ->
            val encoder = construction.own(
                createSession(environment, options, ParakeetOnnxContract.ENCODER_FILE),
            )
            val decoderJoint = construction.own(
                createSession(environment, options, ParakeetOnnxContract.DECODER_JOINT_FILE),
            )
            val preprocessor = construction.own(
                createSession(environment, options, ParakeetOnnxContract.PREPROCESSOR_FILE),
            )

            val decoderState1 = decoderStateLayout(decoderJoint, ParakeetOnnxContract.DECODER_INPUT_STATE_1)
            val decoderState2 = decoderStateLayout(decoderJoint, ParakeetOnnxContract.DECODER_INPUT_STATE_2)

            val vocabularyFile = File(modelDir, ParakeetOnnxContract.VOCABULARY_FILE)
            if (!vocabularyFile.isFile) {
                throw FileNotFoundException("missing Parakeet vocabulary file: ${vocabularyFile.path}")
            }
            val vocabulary = vocabularyFile.bufferedReader().use { reader ->
                ParakeetVocabulary.load(reader.lineSequence())
            }

            val model = LoadedParakeetModel(
                environment = environment,
                encoder = encoder,
                decoderJoint = decoderJoint,
                preprocessor = preprocessor,
                vocabulary = vocabulary,
                decoderState1 = decoderState1,
                decoderState2 = decoderState2,
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
            throw FileNotFoundException("missing Parakeet model file: ${file.path}")
        }
        return sessionFactory.create(environment, file.absolutePath, options)
    }

    private fun decoderStateLayout(session: OrtSession, inputName: String): DecoderStateLayout {
        val node: NodeInfo = session.inputInfo[inputName]
            ?: throw TensorShapeException("decoder joint model has no input '$inputName'")
        val tensorInfo = node.info as? TensorInfo
            ?: throw TensorShapeException("decoder input '$inputName' is not a tensor: ${node.info.javaClass.simpleName}")
        return ParakeetShapes.decoderState(tensorInfo.shape)
    }

    private fun boundedDiagnostic(error: Throwable): String {
        val detail = error.message?.ifBlank { null } ?: error.javaClass.simpleName
        return "Parakeet model load failed: $detail".take(MAX_LOAD_ERROR_CHARS)
    }

    private companion object {
        const val LOADER_THREAD_NAME = "parakeet-model-loader"
        const val MAX_LOAD_ERROR_CHARS = 512
    }
}
