package io.talkcan.audio

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnnxModelInventoryInstrumentedTest {
    @Test
    fun downloadedModelsExposeProductionTensorContract() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val environment = OrtEnvironment.getEnvironment()
        logConfigurationContract(context)
        val models = listOf(
            "parakeet-preprocessor" to File(context.filesDir, "parakeet-tdt-0.6b-v3-int8/nemo128.onnx"),
            "parakeet-encoder" to File(context.filesDir, "parakeet-tdt-0.6b-v3-int8/encoder-model.int8.onnx"),
            "parakeet-decoder-joint" to File(
                context.filesDir,
                "parakeet-tdt-0.6b-v3-int8/decoder_joint-model.int8.onnx",
            ),
            "supertonic-duration-predictor" to File(context.filesDir, "supertonic-3/duration_predictor.onnx"),
            "supertonic-text-encoder" to File(context.filesDir, "supertonic-3/text_encoder.onnx"),
            "supertonic-vector-estimator" to File(context.filesDir, "supertonic-3/vector_estimator.onnx"),
            "supertonic-vocoder" to File(context.filesDir, "supertonic-3/vocoder.onnx"),
        )

        for ((name, model) in models) {
            assertTrue("Missing model $model", model.isFile)
            OrtSession.SessionOptions().use { options ->
                environment.createSession(model.absolutePath, options).use { session ->
                    Log.i(
                        LOG_TAG,
                        "$name inputs=${format(session.inputInfo)} outputs=${format(session.outputInfo)}",
                    )
                }
            }
        }
    }

    private fun format(nodes: Map<String, ai.onnxruntime.NodeInfo>): String = nodes.entries.joinToString(
        prefix = "[",
        postfix = "]",
        separator = ";",
    ) { (name, node) ->
        val tensor = node.info as? TensorInfo
            ?: throw AssertionError("$name is not a tensor: ${node.info}")
        "$name:${tensor.type}:${tensor.shape.joinToString(prefix = "[", postfix = "]")}" 
    }
    private fun logConfigurationContract(context: Context) {
        val parakeetDir = File(context.filesDir, "parakeet-tdt-0.6b-v3-int8")
        val parakeetConfig = JSONObject(File(parakeetDir, "config.json").readText())
        val vocabulary = File(parakeetDir, "vocab.txt").readLines().mapNotNull { line ->
            val parts = line.trimEnd().split(' ')
            parts.getOrNull(1)?.toIntOrNull()?.let { id -> parts[0] to id }
        }
        val vocabularySize = vocabulary.maxOf { it.second } + 1
        val blankIndex = vocabulary.first { it.first == "<blk>" }.second
        Log.i(
            LOG_TAG,
            "parakeet-config model_type=${parakeetConfig.getString("model_type")} " +
                "features_size=${parakeetConfig.getInt("features_size")} " +
                "subsampling_factor=${parakeetConfig.getInt("subsampling_factor")} " +
                "vocabulary_size=$vocabularySize blank_index=$blankIndex",
        )

        val supertonicDir = File(context.filesDir, "supertonic-3")
        val supertonicConfig = JSONObject(File(supertonicDir, "tts.json").readText())
        val ae = supertonicConfig.getJSONObject("ae")
        val ttl = supertonicConfig.getJSONObject("ttl")
        val voice = JSONObject(File(supertonicDir, "M1.json").readText())
        val ttlStyle = voice.getJSONObject("style_ttl")
        val dpStyle = voice.getJSONObject("style_dp")
        val unicodeIndexer = org.json.JSONArray(File(supertonicDir, "unicode_indexer.json").readText())
        Log.i(
            LOG_TAG,
            "supertonic-config ae.sample_rate=${ae.getInt("sample_rate")} " +
                "ae.base_chunk_size=${ae.getInt("base_chunk_size")} " +
                "ttl.chunk_compress_factor=${ttl.getInt("chunk_compress_factor")} " +
                "ttl.latent_dim=${ttl.getInt("latent_dim")} " +
                "style_ttl.dims=${dimensions(ttlStyle)} style_ttl.type=${ttlStyle.getString("type")} " +
                "style_dp.dims=${dimensions(dpStyle)} style_dp.type=${dpStyle.getString("type")} " +
                "unicode_indexer.size=${unicodeIndexer.length()}",
        )
    }

    private fun dimensions(component: JSONObject): String {
        val dims = component.getJSONArray("dims")
        return (0 until dims.length()).joinToString(prefix = "[", postfix = "]") { dims.getInt(it).toString() }
    }


    private companion object {
        const val LOG_TAG = "TalkcanOnnxInventory"
    }
}
