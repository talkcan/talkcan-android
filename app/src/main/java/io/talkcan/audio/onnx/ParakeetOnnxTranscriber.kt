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

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import io.talkcan.audio.SttTranscriber
import io.talkcan.audio.TranscriptionOutcome
import io.talkcan.model.SttModelStatus
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * Direct-ONNX Parakeet transcription engine.
 *
 * Mirrors the pinned `transcribe-rs` `ParakeetModel` pipeline: 250 ms of
 * leading silence is prepended, the raw-waveform preprocessor produces
 * features, the int8 encoder runs once, its `[1, channels, time]` output is
 * transposed once into row-major `[time, channels]` primitive storage, and
 * the combined decoder/joint graph then runs recurrently per encoded time
 * step with greedy blank-terminated token selection (at most
 * [ParakeetGreedyDecoder.MAX_TOKENS_PER_STEP] emissions per step). Emitted
 * tokens are decoded through the runtime vocabulary and whitespace cleanup
 * into the final transcript; timestamps are not ported because
 * [SttTranscriber] exposes text only.
 *
 * Lifecycle and serialization:
 * - Sessions load atomically on the loader-owned background executor through
 *   [ParakeetModelLoader]; [modelStatus] and [loadError] delegate to it.
 * - One [transcribe] call waits at most [MODEL_WAIT_MILLIS] for readiness and
 *   then executes the entire preprocessor → encoder → decoder pipeline inside
 *   a single serialized [ParakeetModelLoader.withModel] lease, so concurrent
 *   requests and [close] never interleave with an in-flight inference.
 * - Empty input returns [TranscriptionOutcome.EmptyInput] without any ONNX
 *   Runtime call; after [close] the lease resolves closed before any ONNX
 *   Runtime entry and the call fails through [TranscriptionOutcome.Failure].
 *
 * Ownership: every input tensor and every [OrtSession.Result] is closed in
 * the lexical scope that creates it. Preprocessor output tensors are handed
 * straight to the encoder while the preprocessor result owns them; the
 * encoder result is copied/transposed into primitive storage before its
 * result closes; decoder recurrent-state outputs are copied into reused
 * primitive arrays only when a token is emitted. The process-shared
 * environment is never closed.
 */
internal class ParakeetOnnxTranscriber(
    modelDir: File,
    environmentProvider: OrtEnvironmentProvider = ProcessOrtEnvironmentProvider,
    sessionFactory: OrtSessionFactory = OrtSessionFactory.default(),
    loadExecutor: Executor? = null,
    sessionOptionsFactory: OrtSessionOptionsFactory = OrtSessionOptionsFactory.default(),
) : SttTranscriber {

    private val loader = ParakeetModelLoader(
        modelDir = modelDir,
        environmentProvider = environmentProvider,
        sessionFactory = sessionFactory,
        loadExecutor = loadExecutor,
        sessionOptionsFactory = sessionOptionsFactory,
    )

    override val modelStatus: SttModelStatus
        get() = loader.modelStatus

    override val loadError: String?
        get() = loader.loadError

    override fun transcribe(samples: FloatArray): TranscriptionOutcome {
        if (samples.isEmpty()) return TranscriptionOutcome.EmptyInput

        val access = try {
            loader.withModel(MODEL_WAIT_MILLIS) { model -> runInference(model, samples) }
        } catch (inferenceFailure: Throwable) {
            return TranscriptionOutcome.Failure(boundedDiagnostic(inferenceFailure))
        }
        return when (access) {
            is ParakeetModelAccess.Success -> TranscriptionOutcome.Success(access.value)
            ParakeetModelAccess.NotReady -> TranscriptionOutcome.ModelNotReady
            ParakeetModelAccess.Closed -> TranscriptionOutcome.Failure(ENGINE_CLOSED_REASON)
            is ParakeetModelAccess.LoadFailed -> TranscriptionOutcome.Failure(access.message)
        }
    }

    /** Idempotent; delegates to the loader and never closes the environment. */
    override fun close() {
        loader.close()
    }

    private fun runInference(model: LoadedParakeetModel, samples: FloatArray): String {
        val encoded = preprocessAndEncode(model, ParakeetAudio.prependLeadingSilence(samples))
        return decodeSequence(model, encoded)
    }

    /**
     * Run preprocessor then encoder and return the encoder output transposed
     * into row-major `[time, channels]` primitive storage plus the encoded
     * step bound read from `encoded_lengths`.
     */
    private fun preprocessAndEncode(model: LoadedParakeetModel, paddedSamples: FloatArray): EncodedAudio {
        val environment = model.environment
        val sampleCount = paddedSamples.size.toLong()
        OnnxTensors.floats(environment, paddedSamples, longArrayOf(1L, sampleCount)).use { waveforms ->
            OnnxTensors.longs(environment, longArrayOf(sampleCount), SINGLETON_SHAPE).use { waveformLengths ->
                model.preprocessor
                    .run(
                        mapOf(
                            ParakeetOnnxContract.PREPROCESSOR_WAVEFORMS to waveforms,
                            ParakeetOnnxContract.PREPROCESSOR_WAVEFORM_LENGTHS to waveformLengths,
                        ),
                    )
                    .use { preprocessed ->
                        val features = requiredTensor(preprocessed, ParakeetOnnxContract.PREPROCESSOR_FEATURES)
                        requireFeatures(features)
                        val featureLengths = requiredTensor(preprocessed, ParakeetOnnxContract.PREPROCESSOR_FEATURE_LENGTHS)
                        requireInt64Singleton(featureLengths, "preprocessor feature lengths")

                        // The preprocessor result owns these output tensors for the
                        // encoder run; they are never copied or closed separately.
                        model.encoder
                            .run(
                                mapOf(
                                    ParakeetOnnxContract.ENCODER_AUDIO_SIGNAL to features,
                                    ParakeetOnnxContract.ENCODER_LENGTH to featureLengths,
                                ),
                            )
                            .use { encoded ->
                                val outputs = requiredTensor(encoded, ParakeetOnnxContract.ENCODER_OUTPUTS)
                                requireTensorType(outputs, OnnxJavaType.FLOAT, "encoder outputs")
                                val rawShape = outputs.info.shape
                                requireRank(rawShape, 3, "encoder outputs")
                                requireDimension(rawShape, 0, 1L, "encoder outputs batch")
                                requireDimension(
                                    rawShape,
                                    1,
                                    ParakeetOnnxContract.ENCODER_CHANNELS.toLong(),
                                    "encoder outputs channels",
                                )
                                val timeSteps = requirePositiveDimension(rawShape, 2, "encoder outputs time")

                                val encodedLengths = requiredTensor(encoded, ParakeetOnnxContract.ENCODER_OUTPUT_LENGTHS)
                                requireInt64Singleton(encodedLengths, "encoder encoded lengths")
                                val stepCount = encodedLengths.longBuffer.get(0)
                                if (stepCount < 0L || stepCount > timeSteps.toLong()) {
                                    throw TensorShapeException(
                                        "encoder encoded lengths $stepCount outside encoder output time $timeSteps",
                                    )
                                }

                                val raw = outputs.floatBuffer
                                val channels = ParakeetOnnxContract.ENCODER_CHANNELS
                                if (raw.remaining() != timeSteps * channels) {
                                    throw TensorShapeException(
                                        "encoder outputs hold ${raw.remaining()} elements, " +
                                            "expected ${timeSteps * channels} for shape ${rawShape.contentToString()}",
                                    )
                                }
                                return EncodedAudio(transposeEncoderOutputs(raw, timeSteps, channels), channels, stepCount.toInt())
                            }
                    }
            }
        }
    }

    /**
     * Copy the raw `[1, channels, time]` encoder output buffer into row-major
     * `[time, channels]` storage, the Kotlin equivalent of the reference
     * `permuted_axes([0, 2, 1])` followed by `to_owned()`. Reads the source
     * sequentially per channel and strides the writes.
     */
    private fun transposeEncoderOutputs(raw: FloatBuffer, timeSteps: Int, channels: Int): FloatArray {
        val encodings = FloatArray(timeSteps * channels)
        for (channel in 0 until channels) {
            var source = channel * timeSteps
            val sourceEnd = source + timeSteps
            var destination = channel
            while (source < sourceEnd) {
                encodings[destination] = raw.get(source)
                source++
                destination += channels
            }
        }
        return encodings
    }

    /**
     * Recurrent greedy decode over [encoded]. The initial recurrent states are
     * zero-filled at the metadata-derived layouts; the first target is the
     * runtime blank token. State outputs are copied into the reused primitive
     * state arrays only when a non-blank token is emitted, exactly matching
     * the reference `prev_state = new_state` update.
     */
    private fun decodeSequence(model: LoadedParakeetModel, encoded: EncodedAudio): String {
        val environment = model.environment
        val vocabulary = model.vocabulary
        val greedyDecoder = ParakeetGreedyDecoder(vocabulary.size, vocabulary.blankIndex)
        greedyDecoder.beginSequence()

        val state1 = FloatArray(model.decoderState1.layers * model.decoderState1.channels)
        val state2 = FloatArray(model.decoderState2.layers * model.decoderState2.channels)
        val state1Shape = model.decoderState1.initialShape()
        val state2Shape = model.decoderState2.initialShape()
        val encoderStepShape = longArrayOf(1L, encoded.channels.toLong(), 1L)

        var targetToken = vocabulary.blankIndex
        var tokenBuffer = IntArray(INITIAL_TOKEN_CAPACITY)
        var tokenCount = 0
        var logitScratch = FloatArray(0)

        var timeIndex = 0
        while (timeIndex < encoded.stepCount) {
            val decision = OrtConstructionScope().use { step ->
                val encoderStep = step.own(
                    OnnxTensors.floats(
                        environment,
                        FloatBuffer.wrap(encoded.encodings, timeIndex * encoded.channels, encoded.channels),
                        encoderStepShape,
                    ),
                )
                val targets = step.own(OnnxTensors.ints(environment, intArrayOf(targetToken), TARGETS_SHAPE))
                val targetLength = step.own(OnnxTensors.ints(environment, TARGET_LENGTH_ONE, SINGLETON_SHAPE))
                val inputState1 = step.own(OnnxTensors.floats(environment, state1, state1Shape))
                val inputState2 = step.own(OnnxTensors.floats(environment, state2, state2Shape))

                model.decoderJoint
                    .run(
                        mapOf(
                            ParakeetOnnxContract.DECODER_ENCODER_OUTPUTS to encoderStep,
                            ParakeetOnnxContract.DECODER_TARGETS to targets,
                            ParakeetOnnxContract.DECODER_TARGET_LENGTH to targetLength,
                            ParakeetOnnxContract.DECODER_INPUT_STATE_1 to inputState1,
                            ParakeetOnnxContract.DECODER_INPUT_STATE_2 to inputState2,
                        ),
                    )
                    .use { stepped ->
                        val logits = requiredTensor(stepped, ParakeetOnnxContract.DECODER_OUTPUTS)
                        requireTensorType(logits, OnnxJavaType.FLOAT, "decoder logits")
                        val logitCount = ParakeetShapes.logitsLength(logits.info.shape)
                        val logitBuffer = logits.floatBuffer
                        if (logitScratch.size != logitBuffer.remaining()) {
                            logitScratch = FloatArray(logitBuffer.remaining())
                        }
                        logitBuffer.get(logitScratch)
                        val selection = greedyDecoder.decide(logitScratch, logitCount)
                        if (selection.emit) {
                            copyState(
                                stepped,
                                ParakeetOnnxContract.DECODER_OUTPUT_STATE_1,
                                state1,
                                model.decoderState1,
                                "decoder output state 1",
                            )
                            copyState(
                                stepped,
                                ParakeetOnnxContract.DECODER_OUTPUT_STATE_2,
                                state2,
                                model.decoderState2,
                                "decoder output state 2",
                            )
                        }
                        selection
                    }
            }

            if (decision.emit) {
                targetToken = decision.token
                if (tokenCount == tokenBuffer.size) {
                    tokenBuffer = tokenBuffer.copyOf(tokenBuffer.size * 2)
                }
                tokenBuffer[tokenCount++] = decision.token
            }
            if (decision.advanceTime) {
                timeIndex++
            }
        }

        return ParakeetTranscriptDecoder.decode(tokenBuffer, tokenCount, vocabulary)
    }

    /**
     * Validate one decoder state output against its metadata-derived layout
     * and copy it into [destination]. Called only on emission, so blank steps
     * never read or copy state outputs.
     */
    private fun copyState(
        result: OrtSession.Result,
        name: String,
        destination: FloatArray,
        layout: DecoderStateLayout,
        what: String,
    ) {
        val state = requiredTensor(result, name)
        requireTensorType(state, OnnxJavaType.FLOAT, what)
        val shape = state.info.shape
        requireRank(shape, 3, what)
        requireDimension(shape, 0, layout.layers.toLong(), "$what layers")
        requireDimension(shape, 1, 1L, "$what sequence")
        requireDimension(shape, 2, layout.channels.toLong(), "$what channels")
        val buffer = state.floatBuffer
        if (buffer.remaining() != destination.size) {
            throw TensorShapeException(
                "$what holds ${buffer.remaining()} elements, expected ${destination.size} " +
                    "for shape ${shape.contentToString()}",
            )
        }
        buffer.get(destination)
    }

    private fun requiredTensor(result: OrtSession.Result, name: String): OnnxTensor {
        val value = result.get(name).orElse(null)
            ?: throw TensorShapeException("model output '$name' is missing from the session result")
        return value as? OnnxTensor
            ?: throw TensorShapeException(
                "model output '$name' is not a tensor: ${value.javaClass.simpleName}",
            )
    }

    private fun requireTensorType(tensor: OnnxTensor, expected: OnnxJavaType, what: String) {
        val actual = tensor.info.type
        if (actual != expected) {
            throw TensorShapeException("$what must have element type $expected, was $actual")
        }
    }

    private fun requireFeatures(features: OnnxTensor) {
        requireTensorType(features, OnnxJavaType.FLOAT, "preprocessor features")
        val shape = features.info.shape
        requireRank(shape, 3, "preprocessor features")
        requireDimension(shape, 0, 1L, "preprocessor features batch")
        requireDimension(shape, 1, ParakeetOnnxContract.FEATURE_SIZE.toLong(), "preprocessor features size")
        requirePositiveDimension(shape, 2, "preprocessor features time")
    }

    private fun requireInt64Singleton(lengths: OnnxTensor, what: String) {
        requireTensorType(lengths, OnnxJavaType.INT64, what)
        val shape = lengths.info.shape
        requireRank(shape, 1, what)
        requireDimension(shape, 0, 1L, what)
    }

    private fun requireRank(shape: LongArray, rank: Int, what: String) {
        if (shape.size != rank) {
            throw TensorShapeException("$what must be rank $rank, was rank ${shape.size} ${shape.contentToString()}")
        }
    }

    private fun requireDimension(shape: LongArray, axis: Int, expected: Long, what: String) {
        val actual = shape[axis]
        if (actual != expected) {
            throw TensorShapeException("$what axis $axis must be $expected, was $actual in ${shape.contentToString()}")
        }
    }

    private fun requirePositiveDimension(shape: LongArray, axis: Int, what: String): Int {
        val actual = shape[axis]
        if (actual <= 0L || actual > Int.MAX_VALUE.toLong()) {
            throw TensorShapeException("$what axis $axis must be a positive Int dimension, was $actual")
        }
        return actual.toInt()
    }

    private fun boundedDiagnostic(error: Throwable): String {
        val detail = error.message?.ifBlank { null } ?: error.javaClass.simpleName
        return "Parakeet transcription failed: $detail".take(MAX_FAILURE_REASON_CHARS)
    }

    /** Row-major `[time, channels]` encoder output plus the encoded step bound. */
    private class EncodedAudio(
        val encodings: FloatArray,
        val channels: Int,
        val stepCount: Int,
    )

    private companion object {
        /** Existing host readiness bound: wait at most 120 s for model load. */
        const val MODEL_WAIT_MILLIS: Long = 120_000L
        const val ENGINE_CLOSED_REASON: String = "Parakeet engine is closed"
        const val MAX_FAILURE_REASON_CHARS: Int = 512
        const val INITIAL_TOKEN_CAPACITY: Int = 64
        val SINGLETON_SHAPE: LongArray = longArrayOf(1L)
        val TARGETS_SHAPE: LongArray = longArrayOf(1L, 1L)
        val TARGET_LENGTH_ONE: IntArray = intArrayOf(1)
    }
}
