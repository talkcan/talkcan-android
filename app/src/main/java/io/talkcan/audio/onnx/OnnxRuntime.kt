package io.talkcan.audio.onnx

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

/** Supplies the process-scoped ONNX Runtime environment without owning its lifetime. */
internal fun interface OrtEnvironmentProvider {
    fun get(): OrtEnvironment
}

/**
 * Lazily resolves ONNX Runtime's process singleton. Engines may close sessions and values, but
 * must never close this environment because other engines in the process can still use it.
 */
internal object ProcessOrtEnvironmentProvider : OrtEnvironmentProvider {
    private val environment: OrtEnvironment by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OrtEnvironment.getEnvironment()
    }

    override fun get(): OrtEnvironment = environment
}

/** Creates an ONNX session from a model file path against caller-owned options. */
internal fun interface OrtSessionFactory {
    fun create(
        environment: OrtEnvironment,
        modelPath: String,
        options: OrtSession.SessionOptions,
    ): OrtSession

    companion object {
        /** Default binding: [OrtEnvironment.createSession] over a filesystem path. */
        fun default(): OrtSessionFactory = OrtSessionFactory { environment, modelPath, options ->
            environment.createSession(modelPath, options)
        }
    }
}

/** Creates caller-owned ONNX session options; injectable so lifecycle tests avoid native setup. */
internal fun interface OrtSessionOptionsFactory {
    fun create(): OrtSession.SessionOptions

    companion object {
        fun default(): OrtSessionOptionsFactory =
            OrtSessionOptionsFactory { OrtSession.SessionOptions() }
    }
}

/**
 * Owns resources while a multi-resource construction is incomplete.
 *
 * Resources close in reverse construction order on failure. A completed aggregate calls
 * [releaseAll] after taking ownership, so this scope then has nothing to close.
 */
internal class OrtConstructionScope : AutoCloseable {
    private val resources = ArrayDeque<AutoCloseable>()
    private var closed = false

    fun <T : AutoCloseable> own(resource: T): T {
        check(!closed) { "ONNX construction scope is closed" }
        resources.addFirst(resource)
        return resource
    }

    fun releaseAll() {
        check(!closed) { "ONNX construction scope is closed" }
        resources.clear()
    }

    override fun close() {
        if (closed) return
        closed = true

        var failure: Throwable? = null
        while (resources.isNotEmpty()) {
            try {
                resources.removeFirst().close()
            } catch (closeFailure: Throwable) {
                if (failure == null) {
                    failure = closeFailure
                } else {
                    failure.addSuppressed(closeFailure)
                }
            }
        }
        failure?.let { throw it }
    }
}

/** Creates typed ONNX tensors over primitive arrays or caller-owned NIO buffers. */
internal object OnnxTensors {
    fun floats(environment: OrtEnvironment, values: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape)
    fun floats(environment: OrtEnvironment, values: FloatBuffer, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, values, shape)


    fun ints(environment: OrtEnvironment, values: IntArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, IntBuffer.wrap(values), shape)
    fun ints(environment: OrtEnvironment, values: IntBuffer, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, values, shape)


    fun longs(environment: OrtEnvironment, values: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, LongBuffer.wrap(values), shape)
    fun longs(environment: OrtEnvironment, values: LongBuffer, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, values, shape)
}

/** Extracts the named tensor output from [result] or raises [TensorShapeException]. */
internal fun requiredTensor(result: OrtSession.Result, name: String): OnnxTensor {
    val value = result.get(name).orElse(null)
        ?: throw TensorShapeException("model output '$name' is missing from the session result")
    return value as? OnnxTensor
        ?: throw TensorShapeException(
            "model output '$name' is not a tensor: ${value.javaClass.simpleName}",
        )
}

/** Requires [tensor] to carry the [expected] ONNX element type. */
internal fun requireTensorType(tensor: OnnxTensor, expected: OnnxJavaType, what: String) {
    val actual = tensor.info.type
    if (actual != expected) {
        throw TensorShapeException("$what must have element type $expected, was $actual")
    }
}

/** Requires [shape] to have exactly [rank] axes. */
internal fun requireRank(shape: LongArray, rank: Int, what: String) {
    if (shape.size != rank) {
        throw TensorShapeException("$what must be rank $rank, was rank ${shape.size} ${shape.contentToString()}")
    }
}

/** Requires [shape] axis [axis] to equal [expected]. */
internal fun requireDimension(shape: LongArray, axis: Int, expected: Long, what: String) {
    val actual = shape[axis]
    if (actual != expected) {
        throw TensorShapeException("$what axis $axis must be $expected, was $actual in ${shape.contentToString()}")
    }
}

/** Requires [shape] axis [axis] to be a positive 32-bit dimension and returns it. */
internal fun requirePositiveDimension(shape: LongArray, axis: Int, what: String): Int {
    val actual = shape[axis]
    if (actual <= 0L || actual > Int.MAX_VALUE.toLong()) {
        throw TensorShapeException("$what axis $axis must be a positive Int dimension, was $actual")
    }
    return actual.toInt()
}
