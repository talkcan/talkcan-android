package io.talkcan.voice

import io.talkcan.audio.onnx.SupertonicOnnxContract
import io.talkcan.audio.onnx.SupertonicStyleException
import io.talkcan.audio.onnx.SupertonicVoiceStyle
import org.json.JSONArray
import org.json.JSONObject

/** Model family and version metadata embedded in editor-produced profile documents. */
internal data class VoiceProfileModelMetadata(
    val family: String,
    val version: String,
) {
    init {
        require(family.isNotBlank()) { "Model family must not be blank" }
        require(version.isNotBlank()) { "Model version must not be blank" }
    }
}

/** Complete decoded profile document: tensors plus bounded metadata and provenance. */
internal data class DecodedVoiceProfileDocument(
    val tensors: VoiceProfileTensors,
    val model: VoiceProfileModelMetadata?,
    val provenance: VoiceProfileProvenance?,
)

/**
 * Compact canonical nested JSON codec for Supertonic-3 voice profiles.
 *
 * Encoded documents are always readable by [SupertonicVoiceStyle.parse] because tensor
 * data uses the nested `data[batch][row][column]` layout matching `dims`. Extra metadata
 * under `talkcan_profile` is ignored by the synthesizer parser. Flat tensor arrays are
 * rejected on decode.
 */
internal object VoiceProfileCodec {
    const val METADATA_KEY: String = "talkcan_profile"
    const val MODEL_FAMILY: String = "supertonic-3"

    /**
     * The one centralized model-set version tagged on editor-produced and imported-compatible
     * profile documents. It matches the hash-verified `supertonic-3` asset manifest version;
     * every writer and compatibility check must use this constant rather than its own copy.
     */
    const val CURRENT_MODEL_VERSION: String = "supertonic-3-2026-06-24"

    /** Current verified model metadata derived from [MODEL_FAMILY] and [CURRENT_MODEL_VERSION]. */
    val CURRENT_MODEL: VoiceProfileModelMetadata
        get() = VoiceProfileModelMetadata(MODEL_FAMILY, CURRENT_MODEL_VERSION)

    /** Encodes tensors plus optional metadata/provenance as compact canonical nested JSON. */
    fun encode(
        tensors: VoiceProfileTensors,
        model: VoiceProfileModelMetadata? = null,
        provenance: VoiceProfileProvenance? = null,
    ): String {
        val root = JSONObject()
        root.put(SupertonicOnnxContract.STYLE_TTL, encodeComponent(tensors.ttl))
        root.put(SupertonicOnnxContract.STYLE_DP, encodeComponent(tensors.dp))
        if (model != null || provenance != null) {
            root.put(METADATA_KEY, encodeMetadata(model, provenance))
        }
        return root.toString()
    }

    /**
     * Decodes a profile document, enforcing nested tensor layout and exact Supertonic-3
     * shapes. Flat tensor data is rejected. Returns tensors with optional metadata and
     * provenance when present.
     */
    fun decode(jsonText: String): DecodedVoiceProfileDocument {
        val root =
            try {
                JSONObject(jsonText)
            } catch (e: Exception) {
                throw SupertonicStyleException("voice profile: malformed JSON: ${e.message}")
            }
        rejectFlatTensorData(root, SupertonicOnnxContract.STYLE_TTL)
        rejectFlatTensorData(root, SupertonicOnnxContract.STYLE_DP)
        val tensors = VoiceProfileStyleDecoder.decode(jsonText)
        val metadata = root.opt(METADATA_KEY) as? JSONObject
        val model = metadata?.let { decodeModel(it) }
        val provenance = metadata?.let { decodeProvenance(it) }
        return DecodedVoiceProfileDocument(tensors, model, provenance)
    }

    private fun encodeComponent(tensor: VoiceTensor): JSONObject {
        val dims = tensor.dimensions
        val component = JSONObject()
        component.put("dims", JSONArray(listOf(dims.batch, dims.rows, dims.columns)))
        val batchArray = JSONArray()
        for (b in 0 until dims.batch) {
            val rowArray = JSONArray()
            for (r in 0 until dims.rows) {
                val columnArray = JSONArray()
                for (c in 0 until dims.columns) {
                    columnArray.put(tensor[b * dims.rows * dims.columns + r * dims.columns + c].toDouble())
                }
                rowArray.put(columnArray)
            }
            batchArray.put(rowArray)
        }
        component.put("data", batchArray)
        return component
    }

    private fun encodeMetadata(
        model: VoiceProfileModelMetadata?,
        provenance: VoiceProfileProvenance?,
    ): JSONObject {
        val metadata = JSONObject()
        metadata.put("codec_version", VoiceProfileLimits.STORE_VERSION)
        if (model != null) {
            metadata.put("model_family", model.family)
            metadata.put("model_version", model.version)
        }
        if (provenance != null) {
            metadata.put("provenance", encodeProvenance(provenance))
        }
        return metadata
    }

    private fun encodeProvenance(provenance: VoiceProfileProvenance): JSONObject {
        val json = JSONObject()
        val sourcesArray = JSONArray()
        for (source in provenance.sources) {
            val sourceJson = JSONObject()
            sourceJson.put("id", source.id.value)
            sourceJson.put("display_name", source.displayName)
            sourceJson.put("normalized_weight", source.normalizedWeight)
            sourcesArray.put(sourceJson)
        }
        json.put("sources", sourcesArray)
        json.put("weight_mode", provenance.weightMode.name)
        if (provenance.randomSeed != null) {
            json.put("random_seed", provenance.randomSeed)
        }
        if (provenance.operations.isNotEmpty()) {
            val opsArray = JSONArray()
            for (op in provenance.operations) {
                val opJson = JSONObject()
                opJson.put("kind", op.kind.name)
                if (op.parameters.isNotEmpty()) {
                    val paramsJson = JSONObject()
                    for ((key, value) in op.parameters) {
                        paramsJson.put(key, value)
                    }
                    opJson.put("parameters", paramsJson)
                }
                if (op.seed != null) {
                    opJson.put("seed", op.seed)
                }
                opsArray.put(opJson)
            }
            json.put("operations", opsArray)
        }
        return json
    }

    /**
     * Rejects documents where a tensor component's `data` field is a flat array of numbers
     * rather than the required nested `data[batch][row][column]` structure.
     */
    private fun rejectFlatTensorData(root: JSONObject, componentName: String) {
        val component = root.opt(componentName) as? JSONObject ?: return
        val data = component.opt("data") ?: return
        if (data is JSONArray && data.length() > 0) {
            val first = data.opt(0)
            if (first is Number) {
                throw SupertonicStyleException(
                    "voice profile: $componentName.data must be nested arrays matching dims, found flat data"
                )
            }
        }
    }

    private fun decodeModel(metadata: JSONObject): VoiceProfileModelMetadata? {
        val family = metadata.optString("model_family", "")
        val version = metadata.optString("model_version", "")
        if (family.isBlank() || version.isBlank()) return null
        return VoiceProfileModelMetadata(family, version)
    }

    private fun decodeProvenance(metadata: JSONObject): VoiceProfileProvenance? {
        val provJson = metadata.opt("provenance") as? JSONObject ?: return null
        val sourcesArray = provJson.opt("sources") as? JSONArray ?: return null
        val sources = mutableListOf<VoiceProfileSourceProvenance>()
        for (i in 0 until sourcesArray.length()) {
            val sourceJson = sourcesArray.opt(i) as? JSONObject ?: continue
            sources.add(
                VoiceProfileSourceProvenance(
                    id = VoiceProfileId(sourceJson.getString("id")),
                    displayName = sourceJson.getString("display_name"),
                    normalizedWeight = sourceJson.getDouble("normalized_weight"),
                )
            )
        }
        if (sources.isEmpty()) return null
        val weightMode = try {
            VoiceProfileWeightMode.valueOf(provJson.getString("weight_mode"))
        } catch (_: Exception) {
            VoiceProfileWeightMode.MANUAL
        }
        val randomSeed = if (provJson.has("random_seed")) provJson.getLong("random_seed") else null
        val operations = mutableListOf<VoiceProfileOperationRecord>()
        val opsArray = provJson.opt("operations") as? JSONArray
        if (opsArray != null) {
            for (i in 0 until opsArray.length()) {
                val opJson = opsArray.opt(i) as? JSONObject ?: continue
                val kind = try {
                    VoiceProfileOperationKind.valueOf(opJson.getString("kind"))
                } catch (_: Exception) {
                    continue
                }
                val parameters = mutableMapOf<String, Double>()
                val paramsJson = opJson.opt("parameters") as? JSONObject
                if (paramsJson != null) {
                    for (key in paramsJson.keys()) {
                        parameters[key] = paramsJson.getDouble(key)
                    }
                }
                val seed = if (opJson.has("seed")) opJson.getLong("seed") else null
                operations.add(VoiceProfileOperationRecord(kind, parameters, seed))
            }
        }
        return VoiceProfileProvenance(
            sources = sources,
            weightMode = weightMode,
            randomSeed = randomSeed,
            operations = operations,
        )
    }
}
