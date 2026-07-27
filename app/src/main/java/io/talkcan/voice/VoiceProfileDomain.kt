package io.talkcan.voice

import java.util.Collections
import kotlin.math.abs

/** Stable host-owned identity. Display names and filenames never establish profile identity. */
@JvmInline
value class VoiceProfileId(val value: String) {
    init {
        require(value.isNotBlank()) { "Voice profile ID must not be blank" }
        require(value.toByteArray(Charsets.UTF_8).size <= VoiceProfileLimits.MAX_PROFILE_ID_BYTES) {
            "Voice profile ID must not exceed ${VoiceProfileLimits.MAX_PROFILE_ID_BYTES} UTF-8 bytes"
        }
    }
}

/** Durable and editor bounds for the Supertonic voice-profile subsystem. */
internal object VoiceProfileLimits {
    const val STORE_VERSION: Int = 1
    const val MAX_CUSTOM_PROFILES: Int = 64
    const val MAX_PROFILE_ID_BYTES: Int = 64
    const val MAX_DISPLAY_NAME_BYTES: Int = 128
    const val MAX_IMPORT_DOCUMENT_BYTES: Int = 2 * 1024 * 1024
    const val MAX_AGGREGATE_STORAGE_BYTES: Long = 32L * 1024L * 1024L
    const val MIN_MIX_SOURCES: Int = 2
    const val MAX_MIX_SOURCES: Int = 16
    const val MAX_UNDO_SNAPSHOTS: Int = 20
}

/** Exact rank-three tensor dimensions accepted by the Supertonic-3 profile boundary. */
internal data class VoiceTensorDimensions(
    val batch: Int,
    val rows: Int,
    val columns: Int,
) {
    val elementCount: Int

    init {
        require(batch > 0 && rows > 0 && columns > 0) { "Voice tensor dimensions must be positive" }
        val count = batch.toLong() * rows.toLong() * columns.toLong()
        require(count <= Int.MAX_VALUE) { "Voice tensor element count exceeds 32-bit storage" }
        elementCount = count.toInt()
    }

    fun asList(): List<Int> = listOf(batch, rows, columns)
}

internal object Supertonic3VoiceProfileContract {
    val TTL_DIMENSIONS: VoiceTensorDimensions = VoiceTensorDimensions(batch = 1, rows = 50, columns = 256)
    val DP_DIMENSIONS: VoiceTensorDimensions = VoiceTensorDimensions(batch = 1, rows = 8, columns = 16)

    const val TTL_ELEMENT_COUNT: Int = 12_800
    const val DP_ELEMENT_COUNT: Int = 128

    init {
        check(TTL_DIMENSIONS.elementCount == TTL_ELEMENT_COUNT)
        check(DP_DIMENSIONS.elementCount == DP_ELEMENT_COUNT)
    }
}

/** Primitive immutable tensor value. Construction and reads defensively isolate mutable arrays. */
internal class VoiceTensor private constructor(
    val dimensions: VoiceTensorDimensions,
    private val storage: FloatArray,
) {
    val size: Int
        get() = storage.size

    operator fun get(index: Int): Float = storage[index]

    fun copyValues(): FloatArray = storage.copyOf()

    internal inline fun forEachIndexed(action: (Int, Float) -> Unit) {
        storage.forEachIndexed(action)
    }

    internal fun ownedCopy(): VoiceTensor = takeOwnership(dimensions, storage.copyOf())

    override fun equals(other: Any?): Boolean =
        other is VoiceTensor && dimensions == other.dimensions && storage.contentEquals(other.storage)

    override fun hashCode(): Int = 31 * dimensions.hashCode() + storage.contentHashCode()

    override fun toString(): String = "VoiceTensor(dimensions=$dimensions, size=${storage.size})"

    companion object {
        fun copyOf(dimensions: VoiceTensorDimensions, values: FloatArray): VoiceTensor =
            takeOwnership(dimensions, values.copyOf())

        internal fun takeOwnership(dimensions: VoiceTensorDimensions, values: FloatArray): VoiceTensor {
            require(values.size == dimensions.elementCount) {
                "Voice tensor has ${values.size} elements; expected ${dimensions.elementCount} for $dimensions"
            }
            require(values.all(Float::isFinite)) { "Voice tensor values must all be finite" }
            return VoiceTensor(dimensions, values)
        }
    }
}

/** Complete materialized Supertonic-3 style. Both tensors are always retained together. */
internal data class VoiceProfileTensors(
    val ttl: VoiceTensor,
    val dp: VoiceTensor,
) {
    init {
        require(ttl.dimensions == Supertonic3VoiceProfileContract.TTL_DIMENSIONS) {
            "TTL dimensions must be ${Supertonic3VoiceProfileContract.TTL_DIMENSIONS.asList()}, found ${ttl.dimensions.asList()}"
        }
        require(dp.dimensions == Supertonic3VoiceProfileContract.DP_DIMENSIONS) {
            "DP dimensions must be ${Supertonic3VoiceProfileContract.DP_DIMENSIONS.asList()}, found ${dp.dimensions.asList()}"
        }
    }
}

internal enum class VoiceProfileKind {
    BUILT_IN,
    EDITED,
    MIXED,
    IMPORTED,
}

internal enum class VoiceProfileCompatibility {
    VERIFIED,
    UNVERIFIED,
    INCOMPATIBLE,
}

internal enum class VoiceProfileUnavailableReason {
    MISSING_FILE,
    CORRUPT_DOCUMENT,
    INCOMPATIBLE_MODEL,
}

internal sealed interface VoiceProfileAvailability {
    data object Available : VoiceProfileAvailability

    data class Unavailable(
        val reason: VoiceProfileUnavailableReason,
        val diagnostic: String,
    ) : VoiceProfileAvailability {
        init {
            require(diagnostic.isNotBlank()) { "Unavailable profile diagnostic must not be blank" }
        }
    }
}

/** Immutable bounded projection safe to publish outside the repository. */
internal data class VoiceProfileSummary(
    val id: VoiceProfileId,
    val displayName: String,
    val kind: VoiceProfileKind,
    val availability: VoiceProfileAvailability,
    val compatibility: VoiceProfileCompatibility,
    val readOnly: Boolean,
) {
    init {
        requireValidVoiceProfileDisplayName(displayName)
        require(readOnly == (kind == VoiceProfileKind.BUILT_IN)) {
            "Only built-in voice profiles are read-only"
        }
        require(compatibility != VoiceProfileCompatibility.INCOMPATIBLE || availability is VoiceProfileAvailability.Unavailable) {
            "An incompatible voice profile cannot be available"
        }
    }

    val selectable: Boolean
        get() = availability is VoiceProfileAvailability.Available &&
            compatibility != VoiceProfileCompatibility.INCOMPATIBLE
}

/** A channel instance that depends on a voice profile through host preferences. */
internal data class VoiceProfileChannelDependency(
    val channelId: String,
    val channelDisplayName: String,
)

/** One immediate, materialized source of a saved draft. */
internal data class VoiceProfileSourceProvenance(
    val id: VoiceProfileId,
    val displayName: String,
    val normalizedWeight: Double,
) {
    init {
        requireValidVoiceProfileDisplayName(displayName)
        require(normalizedWeight.isFinite() && normalizedWeight > 0.0) {
            "Source weight must be finite and strictly positive"
        }
    }
}

internal enum class VoiceProfileOperationKind {
    FEATURE_MIRROR,
    TIME_MIRROR,
    INVERT,
    SCALAR_ADD,
    SCALAR_MULTIPLY,
    TIME_DERIVATIVE,
    FEATURE_ROLL,
    TIME_ROLL,
    FEATURE_SHARPEN,
    QUANTIZE,
    FEATURE_ECHO,
    FEATURE_TREMOLO,
    JITTER,
}

/** Durable operation record; random operations carry the exact seed used. */
internal data class VoiceProfileOperationRecord(
    val kind: VoiceProfileOperationKind,
    val parameters: Map<String, Double> = emptyMap(),
    val seed: Long? = null,
) {
    val immutableParameters: Map<String, Double> =
        Collections.unmodifiableMap(LinkedHashMap(parameters))

    init {
        require(parameters.keys.all { it.isNotBlank() }) { "Operation parameter names must not be blank" }
        require(parameters.values.all(Double::isFinite)) { "Operation parameters must be finite" }
        val seeded = kind == VoiceProfileOperationKind.FEATURE_ROLL ||
            kind == VoiceProfileOperationKind.TIME_ROLL ||
            kind == VoiceProfileOperationKind.JITTER
        require(seeded == (seed != null)) {
            if (seeded) "$kind requires an explicit seed" else "$kind does not accept a seed"
        }
    }
}

internal enum class VoiceProfileWeightMode {
    EQUAL,
    MANUAL,
    RANDOM,
}

/** Immediate-source provenance; ancestor provenance is intentionally not embedded. */
internal data class VoiceProfileProvenance(
    val sources: List<VoiceProfileSourceProvenance>,
    val weightMode: VoiceProfileWeightMode,
    val randomSeed: Long? = null,
    val operations: List<VoiceProfileOperationRecord> = emptyList(),
) {
    val immutableSources: List<VoiceProfileSourceProvenance> =
        Collections.unmodifiableList(ArrayList(sources))
    val immutableOperations: List<VoiceProfileOperationRecord> =
        Collections.unmodifiableList(ArrayList(operations))

    init {
        require(sources.isNotEmpty()) { "Voice profile provenance requires at least one source" }
        require(sources.size <= VoiceProfileLimits.MAX_MIX_SOURCES) {
            "Voice profile provenance exceeds ${VoiceProfileLimits.MAX_MIX_SOURCES} immediate sources"
        }
        require(sources.map { it.id }.toSet().size == sources.size) {
            "Voice profile provenance source IDs must be distinct"
        }
        require((weightMode == VoiceProfileWeightMode.RANDOM) == (randomSeed != null)) {
            if (weightMode == VoiceProfileWeightMode.RANDOM) {
                "Random voice profile weighting requires an explicit seed"
            } else {
                "$weightMode voice profile weighting does not accept a seed"
            }
        }
        val sum = sources.sumOf { it.normalizedWeight }
        require(abs(sum - 1.0) <= NORMALIZED_WEIGHT_TOLERANCE) {
            "Voice profile provenance weights must sum to 1, found $sum"
        }
    }
}

/** Immutable editor state. The mixed baseline remains the reset target. */
internal data class VoiceProfileDraft(
    val baseline: VoiceProfileTensors,
    val current: VoiceProfileTensors,
    val provenance: VoiceProfileProvenance,
    val undoSnapshots: List<VoiceProfileTensors> = emptyList(),
    val baselineOperationCount: Int = provenance.operations.size,
) {
    val immutableUndoSnapshots: List<VoiceProfileTensors> =
        Collections.unmodifiableList(ArrayList(undoSnapshots))

    init {
        require(undoSnapshots.size <= VoiceProfileLimits.MAX_UNDO_SNAPSHOTS) {
            "Voice profile draft retains at most ${VoiceProfileLimits.MAX_UNDO_SNAPSHOTS} undo snapshots"
        }
        require(baselineOperationCount in 0..provenance.operations.size) {
            "Draft baseline operation count must address its provenance"
        }
        require(current.dp == baseline.dp) { "TTL operations must retain the materialized DP tensor" }
        require(undoSnapshots.all { it.dp == baseline.dp }) {
            "Every undo snapshot must retain the materialized DP tensor"
        }
    }

    val hasLatentEdits: Boolean
        get() = current.ttl != baseline.ttl

    val canUndo: Boolean
        get() = undoSnapshots.isNotEmpty()
}

internal fun requireValidVoiceProfileDisplayName(displayName: String) {
    require(displayName.isNotBlank()) { "Voice profile display name must not be blank" }
    require(displayName.toByteArray(Charsets.UTF_8).size <= VoiceProfileLimits.MAX_DISPLAY_NAME_BYTES) {
        "Voice profile display name must not exceed ${VoiceProfileLimits.MAX_DISPLAY_NAME_BYTES} UTF-8 bytes"
    }
}

private const val NORMALIZED_WEIGHT_TOLERANCE: Double = 1e-9
