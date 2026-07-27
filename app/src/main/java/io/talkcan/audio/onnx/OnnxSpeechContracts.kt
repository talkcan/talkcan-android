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

/** Tensor and asset contract verified against the shipped Parakeet v3 int8 model set. */
internal object ParakeetOnnxContract {
    const val REFERENCE_REVISION = "343768c100d566b135fbb7a2441e61fa8aa177f2"

    /** Shipped model asset basenames inside the Parakeet model directory. */
    const val PREPROCESSOR_FILE = "nemo128.onnx"
    const val ENCODER_FILE = "encoder-model.int8.onnx"
    const val DECODER_JOINT_FILE = "decoder_joint-model.int8.onnx"
    const val VOCABULARY_FILE = "vocab.txt"

    const val PREPROCESSOR_WAVEFORMS = "waveforms"
    const val PREPROCESSOR_WAVEFORM_LENGTHS = "waveforms_lens"
    const val PREPROCESSOR_FEATURES = "features"
    const val PREPROCESSOR_FEATURE_LENGTHS = "features_lens"

    const val ENCODER_AUDIO_SIGNAL = "audio_signal"
    const val ENCODER_LENGTH = "length"
    const val ENCODER_OUTPUTS = "outputs"
    const val ENCODER_OUTPUT_LENGTHS = "encoded_lengths"

    const val DECODER_ENCODER_OUTPUTS = "encoder_outputs"
    const val DECODER_TARGETS = "targets"
    const val DECODER_TARGET_LENGTH = "target_length"
    const val DECODER_INPUT_STATE_1 = "input_states_1"
    const val DECODER_INPUT_STATE_2 = "input_states_2"
    const val DECODER_OUTPUTS = "outputs"
    const val DECODER_OUTPUT_STATE_1 = "output_states_1"
    const val DECODER_OUTPUT_STATE_2 = "output_states_2"

    const val FEATURE_SIZE = 128
    const val ENCODER_CHANNELS = 1024
    const val DECODER_STATE_LAYERS = 2
    const val DECODER_STATE_CHANNELS = 640
    const val LOGIT_SIZE = 8198
    const val VOCABULARY_SIZE = 8193
    const val BLANK_INDEX = 8192
    const val SUBSAMPLING_FACTOR = 8
}

/** Tensor and asset contract verified against the shipped Supertonic 3 model set. */
internal object SupertonicOnnxContract {
    const val REFERENCE_REVISION = "dff55dc00064c398736080c78195f577527832ae"

    /** Asset basenames inside the downloaded Supertonic model directory. */
    const val CONFIG_FILE = "tts.json"
    const val UNICODE_INDEXER_FILE = "unicode_indexer.json"

    /** Reference per-language chunk limits from `TextToSpeech.call`. */
    const val DEFAULT_MAX_CHUNK_LENGTH = 300
    const val CJK_MAX_CHUNK_LENGTH = 120

    const val TEXT_IDS = "text_ids"
    const val STYLE_DP = "style_dp"
    const val TEXT_MASK = "text_mask"
    const val DURATION = "duration"

    const val STYLE_TTL = "style_ttl"
    const val TEXT_EMBEDDING = "text_emb"

    const val NOISY_LATENT = "noisy_latent"
    const val LATENT_MASK = "latent_mask"
    const val CURRENT_STEP = "current_step"
    const val TOTAL_STEP = "total_step"
    const val DENOISED_LATENT = "denoised_latent"

    const val LATENT = "latent"
    const val WAVEFORM = "wav_tts"

    const val SAMPLE_RATE = 44_100
    const val BASE_CHUNK_SIZE = 512
    const val CHUNK_COMPRESSION_FACTOR = 6
    const val LATENT_DIMENSION = 24
    const val EXPANDED_LATENT_CHANNELS = 144
    const val TTL_STYLE_COUNT = 50
    const val TTL_STYLE_DIMENSION = 256
    const val DP_STYLE_COUNT = 8
    const val DP_STYLE_DIMENSION = 16
    const val UNICODE_INDEXER_SIZE = 65_536
}
