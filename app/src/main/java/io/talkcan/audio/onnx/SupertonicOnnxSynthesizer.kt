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

import io.talkcan.audio.SynthesisOutcome
import io.talkcan.audio.SynthesisRequest
import io.talkcan.audio.TtsSynthesizer
import io.talkcan.model.TtsModelStatus
import java.io.File
import java.util.concurrent.Executor

/**
 * Direct-ONNX Supertonic synthesis engine.
 *
 * Mirrors the pinned reference `TextToSpeech.call` pipeline: the voice-style
 * document is parsed from the per-request filesystem path, the language,
 * denoising-step count, and speed are validated against the same predicates
 * the inference stages enforce, the text is chunked by the pinned
 * per-language limit, and each chunk is prepared into Unicode token ids and
 * run through the duration-prediction / text-encoding stage
 * ([SupertonicTextStage]) and then the vector-estimation / vocoding stage
 * ([SupertonicDenoisingStage]). Per-chunk primitive PCM is composed in order
 * with [SupertonicChunkComposer.INTER_CHUNK_SILENCE_SECONDS] of silence
 * between adjacent chunks and returned through [SynthesisOutcome.Success].
 *
 * Lifecycle and serialization:
 * - Sessions, `tts.json`, and the Unicode indexer load atomically on the
 *   loader-owned background executor through [SupertonicModelLoader];
 *   [modelStatus] and [loadError] delegate to it.
 * - One [synthesize] call waits at most [MODEL_WAIT_MILLIS] for readiness
 *   and then executes the entire multi-chunk pipeline inside a single
 *   serialized [SupertonicModelLoader.withModel] lease, so concurrent
 *   requests and [close] never interleave with an in-flight inference.
 * - Blank text returns [SynthesisOutcome.EmptyText] without any ONNX Runtime
 *   call; after [close] the lease resolves closed before any ONNX Runtime
 *   entry and the call fails through [SynthesisOutcome.Failure].
 *
 * The stateless stages are constructed per lease around the published
 * model's runtime configuration, keeping their configuration lifetime bound
 * to the model aggregate; the injected [uniformSource] seeds the noisy
 * latent sampling. Ownership: every input tensor and every session result
 * closes inside the stage scopes that create it; the only value escaping a
 * lease is the composed primitive PCM array. The process-shared environment
 * is never closed.
 */
internal class SupertonicOnnxSynthesizer(
    modelDir: File,
    environmentProvider: OrtEnvironmentProvider = ProcessOrtEnvironmentProvider,
    sessionFactory: OrtSessionFactory = OrtSessionFactory.default(),
    loadExecutor: Executor? = null,
    sessionOptionsFactory: OrtSessionOptionsFactory = OrtSessionOptionsFactory.default(),
    private val uniformSource: SupertonicUniformSource = RandomSupertonicUniformSource(),
) : TtsSynthesizer {

    private val loader = SupertonicModelLoader(
        modelDir = modelDir,
        environmentProvider = environmentProvider,
        sessionFactory = sessionFactory,
        loadExecutor = loadExecutor,
        sessionOptionsFactory = sessionOptionsFactory,
    )

    override val modelStatus: TtsModelStatus
        get() = loader.modelStatus

    override val loadError: String?
        get() = loader.loadError

    override fun synthesize(request: SynthesisRequest): SynthesisOutcome {
        if (request.text.isBlank()) return SynthesisOutcome.EmptyText

        val access = try {
            loader.withModel(MODEL_WAIT_MILLIS) { model -> runSynthesis(model, request) }
        } catch (inferenceFailure: Throwable) {
            return SynthesisOutcome.Failure(boundedDiagnostic(inferenceFailure))
        }
        return when (access) {
            is SupertonicModelAccess.Success -> SynthesisOutcome.Success(access.value)
            SupertonicModelAccess.NotReady -> SynthesisOutcome.ModelNotReady
            SupertonicModelAccess.Closed -> SynthesisOutcome.Failure(ENGINE_CLOSED_REASON)
            is SupertonicModelAccess.LoadFailed -> SynthesisOutcome.Failure(access.message)
        }
    }

    /** Idempotent; delegates to the loader and never closes the environment. */
    override fun close() {
        loader.close()
    }

    private fun runSynthesis(model: LoadedSupertonicModel, request: SynthesisRequest): FloatArray {
        val style = SupertonicVoiceStyle.load(File(request.voiceStylePath))
        validateRequest(request)

        val chunks = SupertonicText.chunkText(
            request.text,
            SupertonicText.maxChunkLength(request.lang),
        )
        val textStage = SupertonicTextStage(model.config)
        val denoisingStage = SupertonicDenoisingStage(model.config, uniformSource)

        val pcmChunks = ArrayList<FloatArray>(chunks.size)
        for (chunk in chunks) {
            val prepared = PreparedSupertonicText.prepare(model.indexer, chunk, request.lang)
            pcmChunks += textStage.run(
                environment = model.environment,
                durationPredictor = model.durationPredictor,
                textEncoder = model.textEncoder,
                text = prepared,
                style = style,
                speed = request.speed,
            ) { encoded ->
                denoisingStage.run(
                    environment = model.environment,
                    vectorEstimator = model.vectorEstimator,
                    vocoder = model.vocoder,
                    encoded = encoded,
                    totalSteps = request.totalSteps,
                )
            }
        }
        return SupertonicChunkComposer.compose(
            model.config.sampleRate,
            pcmChunks,
            SupertonicChunkComposer.INTER_CHUNK_SILENCE_SECONDS,
        )
    }

    /**
     * Rejects invalid request parameters before any ONNX Runtime call, using
     * the same predicates and message forms the inference stages enforce
     * ([SupertonicLanguages.isValid], the text stage's speed requirement,
     * and the denoising stage's step requirement).
     */
    private fun validateRequest(request: SynthesisRequest) {
        require(SupertonicLanguages.isValid(request.lang)) {
            "Unsupported language '${request.lang}'. Available: ${SupertonicLanguages.AVAILABLE}"
        }
        require(request.totalSteps > 0) { "totalSteps must be positive, was ${request.totalSteps}" }
        require(request.speed.isFinite() && request.speed > 0f) {
            "speed must be finite and positive, was ${request.speed}"
        }
    }

    private fun boundedDiagnostic(error: Throwable): String {
        val detail = error.message?.ifBlank { null } ?: error.javaClass.simpleName
        return "Supertonic synthesis failed: $detail".take(MAX_FAILURE_REASON_CHARS)
    }

    private companion object {
        /** Existing host readiness bound: wait at most 120 s for model load. */
        const val MODEL_WAIT_MILLIS: Long = 120_000L
        const val ENGINE_CLOSED_REASON: String = "Supertonic engine is closed"
        const val MAX_FAILURE_REASON_CHARS: Int = 512
    }
}
