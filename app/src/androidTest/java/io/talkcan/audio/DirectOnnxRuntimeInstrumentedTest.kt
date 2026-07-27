package io.talkcan.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.talkcan.audio.onnx.OnnxTensors
import io.talkcan.audio.onnx.OrtConstructionScope
import io.talkcan.audio.onnx.OrtEnvironmentProvider
import io.talkcan.audio.onnx.ParakeetOnnxContract
import io.talkcan.audio.onnx.ProcessOrtEnvironmentProvider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectOnnxRuntimeInstrumentedTest {
    @Test
    fun createsRunsAndClosesDirectOnnxSession() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val model = File(context.filesDir, "parakeet-tdt-0.6b-v3-int8/nemo128.onnx")
        val engine = DirectPreprocessorEngine(ProcessOrtEnvironmentProvider, model)

        val outputShape = engine.run(FloatArray(1_600))

        assertEquals(3, outputShape.size)
        assertEquals(1L, outputShape[0])
        assertEquals(ParakeetOnnxContract.FEATURE_SIZE.toLong(), outputShape[1])
        assertTrue(outputShape[2] > 0L)
        engine.close()
        engine.close()
    }

    private class DirectPreprocessorEngine(
        private val environmentProvider: OrtEnvironmentProvider,
        model: File,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val session: OrtSession

        init {
            val environment = environmentProvider.get()
            OrtConstructionScope().use { construction ->
                session = OrtSession.SessionOptions().use { options ->
                    construction.own(environment.createSession(model.absolutePath, options))
                }
                construction.releaseAll()
            }
        }

        fun run(samples: FloatArray): LongArray {
            check(!closed.get()) { "Direct ONNX smoke engine is closed" }
            val environment = environmentProvider.get()
            OnnxTensors.floats(environment, samples, longArrayOf(1, samples.size.toLong())).use { waveforms ->
                OnnxTensors.longs(environment, longArrayOf(samples.size.toLong()), longArrayOf(1)).use { lengths ->
                    session.run(
                        mapOf(
                            ParakeetOnnxContract.PREPROCESSOR_WAVEFORMS to waveforms,
                            ParakeetOnnxContract.PREPROCESSOR_WAVEFORM_LENGTHS to lengths,
                        ),
                    ).use { result ->
                        val features = result.get(ParakeetOnnxContract.PREPROCESSOR_FEATURES)
                            .orElseThrow { AssertionError("Missing preprocessor features") } as OnnxTensor
                        assertTrue(features.floatBuffer.hasRemaining())
                        return features.info.shape.copyOf()
                    }
                }
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) session.close()
        }
    }
}
