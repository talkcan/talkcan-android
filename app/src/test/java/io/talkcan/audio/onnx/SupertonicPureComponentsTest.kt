package io.talkcan.audio.onnx

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Optional
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 4.6 pure/inference component coverage for the Kotlin Supertonic engine:
 * text preprocessing and normalization, unsupported-language rejection, empty
 * text, language-dependent chunk boundaries, Unicode indexer ids/masks,
 * latent-plan shape math, the pinned Gaussian fill formula, speed and
 * total-step propagation through mocked ORT sessions, exact denoising step
 * count with in-place latent replacement, vocoder PCM projection and
 * finite-sample rejection, and composer silence/overflow semantics.
 *
 * ORT is mocked with MockK following the existing Parakeet test conventions;
 * no native runtime is touched.
 */
class SupertonicPureComponentsTest {

    // ------------------------------------------------------------------
    // Text preprocessing and normalization
    // ------------------------------------------------------------------

    @Test
    fun preprocessTextAppliesPinnedReplacementsAndTagWrapping() {
        val processed = SupertonicText.preprocessText("Hi—there “friend” [note] a@b", "en")

        assertEquals("<en>Hi-there \"friend\" note a at b.</en>", processed)
    }

    @Test
    fun preprocessTextNormalizesCompatibilityFormsViaNfkd() {
        // Fullwidth letters NFKD-fold to ASCII before tagging.
        assertEquals("<en>Hi.</en>", SupertonicText.preprocessText("Ｈｉ", "en"))
    }

    @Test
    fun preprocessTextKeepsExistingTerminalPunctuation() {
        assertEquals("<en>Done!</en>", SupertonicText.preprocessText("Done!", "en"))
    }

    @Test
    fun preprocessTextRemovesEmojiCodePointsAcrossSurrogatePairs() {
        val processed = SupertonicText.preprocessText("Wave \uD83D\uDC4B ok", "en")

        assertEquals("<en>Wave ok.</en>", processed)
    }

    @Test
    fun preprocessTextRejectsUnsupportedLanguageBeforeAnyTextWork() {
        val error =
            assertThrows(SupertonicLanguageException::class.java) {
                SupertonicText.preprocessText("hello", "xx")
            }

        assertTrue(error.message, error.message!!.contains("Unsupported language 'xx'"))
        assertTrue(error.message, error.message!!.contains("Available: [en, ko, ja"))
    }

    @Test
    fun preprocessTextAddsTerminalPeriodToEmptyText() {
        // Reference behavior: empty text still gains the terminal period.
        assertEquals("<en>.</en>", SupertonicText.preprocessText("", "en"))
    }

    @Test
    fun splitSentencesHonorsAbbreviationLookbehind() {
        val sentences = SupertonicText.splitSentences("Dr. Smith went home. He slept.")

        assertEquals(listOf("Dr. Smith went home.", "He slept."), sentences)
    }

    // ------------------------------------------------------------------
    // Chunk boundaries
    // ------------------------------------------------------------------

    @Test
    fun maxChunkLengthSelectsCjkLimitOnlyForKoAndJa() {
        assertEquals(120, SupertonicText.maxChunkLength("ko"))
        assertEquals(120, SupertonicText.maxChunkLength("ja"))
        assertEquals(300, SupertonicText.maxChunkLength("en"))
        assertEquals(300, SupertonicText.maxChunkLength("de"))
    }

    @Test
    fun defaultChunkingSplitsLongParagraphsAtSentenceBoundaries() {
        val first = "A".repeat(180) + "."
        val second = "B".repeat(180) + "."

        val chunks = SupertonicText.chunkText("$first $second")

        assertEquals(listOf(first, second), chunks)
        assertTrue(chunks.all { it.length <= SupertonicOnnxContract.DEFAULT_MAX_CHUNK_LENGTH })
    }

    @Test
    fun defaultChunkingFallsBackToCommaPartsForLongSentences() {
        val part1 = "a".repeat(150)
        val part2 = "b".repeat(150)
        val part3 = "c".repeat(150)
        val sentence = "$part1, $part2, $part3."

        val chunks = SupertonicText.chunkText(sentence)

        assertTrue("expected multiple chunks, got $chunks", chunks.size > 1)
        assertTrue(chunks.all { it.length <= SupertonicOnnxContract.DEFAULT_MAX_CHUNK_LENGTH })
        val rejoined = chunks.joinToString(separator = " ")
        assertTrue(rejoined.indexOf(part1) < rejoined.indexOf(part2))
        assertTrue(rejoined.indexOf(part2) < rejoined.indexOf(part3))
    }

    @Test
    fun cjkChunkingHonorsThe120CharLimit() {
        val first = "한".repeat(79) + "."
        val second = "글".repeat(79) + "."

        val chunks = SupertonicText.chunkText("$first $second", SupertonicText.maxChunkLength("ko"))

        assertEquals(listOf(first, second), chunks)
        assertTrue(chunks.all { it.length <= SupertonicOnnxContract.CJK_MAX_CHUNK_LENGTH })
    }

    @Test
    fun chunkingBlankInputYieldsSingleEmptyChunk() {
        assertEquals(listOf(""), SupertonicText.chunkText(""))
        assertEquals(listOf(""), SupertonicText.chunkText("   "))
        assertEquals(listOf(""), SupertonicText.chunkText("\n\n  \n"))
    }

    @Test
    fun chunkingRejectsNegativeLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            SupertonicText.chunkText("text", -1)
        }
    }

    // ------------------------------------------------------------------
    // Unicode indexer ids and masks
    // ------------------------------------------------------------------

    @Test
    fun preparedTextMapsCodePointsThroughIndexerWithAllActiveMask() {
        val prepared = PreparedSupertonicText.prepare(identityIndexer(), "ab", "en")

        // "<en>ab.</en>" as code points.
        assertArrayEquals(
            longArrayOf(60, 101, 110, 62, 97, 98, 46, 60, 47, 101, 110, 62),
            prepared.ids,
        )
        assertArrayEquals(FloatArray(12) { 1.0f }, prepared.mask, 0.0f)
        assertEquals(12, prepared.length)
    }

    @Test
    fun preparedTextIteratesSupplementaryCodePointsAndMapsUncoveredToMinusOne() {
        val prepared = PreparedSupertonicText.prepare(identityIndexer(), "𠀀", "ja")

        // "<ja>𠀀.</ja>": U+20000 sits outside the 65536-entry indexer range.
        assertEquals(11, prepared.length)
        assertEquals(-1L, prepared.ids[4])
        assertEquals(60L, prepared.ids[0])
        assertEquals(46L, prepared.ids[5])
    }

    @Test
    fun indexerRejectsWrongEntryCount() {
        assertThrows(SupertonicIndexerException::class.java) {
            SupertonicUnicodeIndexer.parse("[1, 2, 3]")
        }
    }

    @Test
    fun indexerMapsOutOfRangeCodePointsToMinusOne() {
        val indexer = identityIndexer()

        assertEquals(65535L, indexer.idFor(65535))
        assertEquals(-1L, indexer.idFor(65536))
        assertEquals(-1L, indexer.idFor(0x1F600))
    }

    // ------------------------------------------------------------------
    // Latent plan shape math
    // ------------------------------------------------------------------

    @Test
    fun latentPlanMatchesReferenceSizeMath() {
        val plan = SupertonicLatentPlan.forDuration(testConfig(), 1.0f)

        assertEquals(44_100L, plan.waveformSamples)
        // ceil(44100 / (512 * 6)) = 15
        assertEquals(15, plan.latentLength)
        // 24 * 6
        assertEquals(144, plan.latentChannels)
        assertEquals(1.0f, plan.durationSeconds)
    }

    @Test
    fun latentPlanTruncatesFractionalSamplesAndAcceptsZeroDuration() {
        val plan = SupertonicLatentPlan.forDuration(testConfig(), 0.5f)

        assertEquals(22_050L, plan.waveformSamples)
        assertEquals(8, plan.latentLength)

        val zero = SupertonicLatentPlan.forDuration(testConfig(), 0.0f)
        assertEquals(0L, zero.waveformSamples)
        assertEquals(0, zero.latentLength)
    }

    @Test
    fun latentPlanRejectsNonFiniteAndNegativeDurations() {
        assertThrows(TensorShapeException::class.java) {
            SupertonicLatentPlan.forDuration(testConfig(), Float.NaN)
        }
        assertThrows(TensorShapeException::class.java) {
            SupertonicLatentPlan.forDuration(testConfig(), -0.1f)
        }
        assertThrows(TensorShapeException::class.java) {
            SupertonicLatentPlan.forDuration(testConfig(), 1.0e30f)
        }
    }

    // ------------------------------------------------------------------
    // Gaussian fill formula
    // ------------------------------------------------------------------

    @Test
    fun gaussianFillUsesPinnedBoxMullerCosineBranchAndDrawOrder() {
        val draws = ArrayDeque(listOf(0.5, 0.5, 0.25, 0.5))
        val source = SupertonicUniformSource { draws.removeFirst() }
        val target = FloatArray(2)

        SupertonicGaussianLatent.fill(target, source)

        // sqrt(-2 ln 0.5) * cos(pi) and sqrt(-2 ln 0.25) * cos(pi)
        assertEquals(-1.1774100f, target[0], 1.0e-5f)
        assertEquals(-1.6651096f, target[1], 1.0e-5f)
        assertTrue("all scripted draws must be consumed", draws.isEmpty())
    }

    @Test
    fun gaussianFillFloorsZeroUniformBeforeLogarithm() {
        val draws = ArrayDeque(listOf(0.0, 0.0))
        val source = SupertonicUniformSource { draws.removeFirst() }
        val target = FloatArray(1)

        SupertonicGaussianLatent.fill(target, source)

        // sqrt(-2 ln 1e-10) * cos(0)
        assertEquals(6.7861404f, target[0], 1.0e-5f)
    }

    // ------------------------------------------------------------------
    // Text stage: speed propagation through mocked sessions
    // ------------------------------------------------------------------

    @Test
    fun textStageDividesPredictedDurationBySpeedAndExposesShapePlan() {
        withMockedOnnxTensorFactory { created ->
            val environment = mockk<OrtEnvironment>()
            val durationPredictor = mockk<OrtSession>()
            every { durationPredictor.run(any()) } returns
                resultOf(
                    SupertonicOnnxContract.DURATION to
                        floatTensor(longArrayOf(1), floatArrayOf(2.0f)),
                )
            val textEncoder = mockk<OrtSession>()
            every { textEncoder.run(any()) } returns
                resultOf(
                    SupertonicOnnxContract.TEXT_EMBEDDING to
                        floatTensor(longArrayOf(1, 8, 12), FloatArray(96)),
                )

            val prepared = PreparedSupertonicText.prepare(identityIndexer(), "hi", "en")
            val stage = SupertonicTextStage(testConfig())

            val observed =
                stage.run(environment, durationPredictor, textEncoder, prepared, testStyle(), 2.0f) { encoded ->
                    assertEquals(1.0f, encoded.durationSeconds)
                    assertEquals(44_100L, encoded.waveformSamples)
                    assertEquals(15, encoded.latentLength)
                    assertEquals(144, encoded.latentChannels)
                    assertEquals(12, encoded.textLength)
                    assertEquals(8, encoded.textEmbeddingChannels)
                }

            assertEquals(Unit, observed)
            verify(exactly = 1) { durationPredictor.run(any()) }
            verify(exactly = 1) { textEncoder.run(any()) }
            // Construction order: text_ids, text_mask, style_dp, then style_ttl.
            assertEquals(
                listOf(
                    listOf(1L, 12L),
                    listOf(1L, 1L, 12L),
                    listOf(1L, 2L, 3L),
                    listOf(1L, 2L, 3L),
                ),
                created.map { it.shape.toList() },
            )
            assertArrayEquals(
                longArrayOf(60, 101, 110, 62, 104, 105, 46, 60, 47, 101, 110, 62),
                created[0].longData!!,
            )
        }
    }

    @Test
    fun textStageRejectsNonFiniteDurationOutput() {
        withMockedOnnxTensorFactory {
            val durationPredictor = mockk<OrtSession>()
            every { durationPredictor.run(any()) } returns
                resultOf(
                    SupertonicOnnxContract.DURATION to
                        floatTensor(longArrayOf(1), floatArrayOf(Float.NaN)),
                )
            val stage = SupertonicTextStage(testConfig())
            val prepared = PreparedSupertonicText.prepare(identityIndexer(), "hi", "en")

            val error =
                assertThrows(TensorShapeException::class.java) {
                    stage.run(mockk(), durationPredictor, mockk(), prepared, testStyle(), 1.0f) { }
                }

            assertTrue(error.message, error.message!!.contains("finite"))
        }
    }

    @Test
    fun textStageRejectsNonPositiveSpeed() {
        val stage = SupertonicTextStage(testConfig())
        val prepared = PreparedSupertonicText.prepare(identityIndexer(), "hi", "en")

        assertThrows(IllegalArgumentException::class.java) {
            stage.run(mockk(), mockk(), mockk(), prepared, testStyle(), 0.0f) { }
        }
    }

    // ------------------------------------------------------------------
    // Denoising stage: step count, propagation, projection
    // ------------------------------------------------------------------

    @Test
    fun denoisingStageRunsExactlyRequestedStepsWithStepTensorsAndInPlaceReplacement() {
        withMockedOnnxTensorFactory { created ->
            val environment = mockk<OrtEnvironment>()
            val vectorEstimator = mockk<OrtSession>()
            every { vectorEstimator.run(any()) } returns
                resultOf(
                    SupertonicOnnxContract.DENOISED_LATENT to
                        floatTensor(longArrayOf(1, 144, 1), FloatArray(144) { 7.0f }),
                )
            val vocoder = mockk<OrtSession>()
            every { vocoder.run(any()) } returns
                resultOf(
                    SupertonicOnnxContract.WAVEFORM to
                        floatTensor(longArrayOf(1, 250), FloatArray(250) { it * 0.001f }),
                )

            val stage = SupertonicDenoisingStage(testConfig(), SupertonicUniformSource { 0.5 })
            val pcm = stage.run(environment, vectorEstimator, vocoder, encodedFixture(), 3)

            verify(exactly = 3) { vectorEstimator.run(any()) }
            verify(exactly = 1) { vocoder.run(any()) }

            // Scalar tensors: total_step once, then current_step 0, 1, 2.
            val scalars = created.filter { it.shape.toList() == listOf(1L) }.map { it.floatData!![0] }
            assertEquals(listOf(3.0f, 0.0f, 1.0f, 2.0f), scalars)

            // First noisy latent is the pinned Gaussian draw (cos(pi) branch), masked by ones.
            val noisy = created.filter { it.shape.toList() == listOf(1L, 144L, 1L) }
            assertEquals(4, noisy.size)
            assertArrayEquals(FloatArray(144) { -1.1774100f }, noisy[0].floatData!!, 1.0e-5f)
            // Every later noisy latent carries the previous step's denoised values.
            assertArrayEquals(FloatArray(144) { 7.0f }, noisy[1].floatData!!, 0.0f)
            assertArrayEquals(FloatArray(144) { 7.0f }, noisy[2].floatData!!, 0.0f)
            // Latent mask is all-active for one chunk.
            val mask = created.single { it.shape.toList() == listOf(1L, 1L, 1L) }
            assertArrayEquals(floatArrayOf(1.0f), mask.floatData!!, 0.0f)

            // Projection truncates the 250-sample vocoder output to the planned 100.
            assertEquals(100, pcm.size)
            assertEquals(0.0f, pcm[0])
            assertEquals(0.099f, pcm[99], 1.0e-6f)
            assertTrue(pcm.all { it.isFinite() })
        }
    }

    @Test
    fun denoisingStagePadsZeroWhenVocoderUnderproduces() {
        withMockedOnnxTensorFactory {
            val vectorEstimator = mockk<OrtSession>()
            every { vectorEstimator.run(any()) } returns
                resultOf(
                    SupertonicOnnxContract.DENOISED_LATENT to
                        floatTensor(longArrayOf(1, 144, 1), FloatArray(144)),
                )
            val vocoder = mockk<OrtSession>()
            every { vocoder.run(any()) } returns
                resultOf(
                    SupertonicOnnxContract.WAVEFORM to
                        floatTensor(longArrayOf(1, 40), FloatArray(40) { 0.5f }),
                )

            val pcm =
                SupertonicDenoisingStage(testConfig(), SupertonicUniformSource { 0.5 })
                    .run(mockk(), vectorEstimator, vocoder, encodedFixture(), 1)

            assertEquals(100, pcm.size)
            assertArrayEquals(FloatArray(40) { 0.5f }, pcm.copyOfRange(0, 40), 0.0f)
            assertArrayEquals(FloatArray(60), pcm.copyOfRange(40, 100), 0.0f)
        }
    }

    @Test
    fun denoisingStageRejectsNonFiniteVocoderSamples() {
        withMockedOnnxTensorFactory {
            val vectorEstimator = mockk<OrtSession>()
            every { vectorEstimator.run(any()) } returns
                resultOf(
                    SupertonicOnnxContract.DENOISED_LATENT to
                        floatTensor(longArrayOf(1, 144, 1), FloatArray(144)),
                )
            val bad = FloatArray(100) { 0.25f }
            bad[2] = Float.NaN
            val vocoder = mockk<OrtSession>()
            every { vocoder.run(any()) } returns
                resultOf(SupertonicOnnxContract.WAVEFORM to floatTensor(longArrayOf(1, 100), bad))

            val error =
                assertThrows(TensorShapeException::class.java) {
                    SupertonicDenoisingStage(testConfig(), SupertonicUniformSource { 0.5 })
                        .run(mockk(), vectorEstimator, vocoder, encodedFixture(), 1)
                }

            assertTrue(error.message, error.message!!.contains("index 2"))
            assertTrue(error.message, error.message!!.contains("finite"))
        }
    }

    @Test
    fun denoisingStageRejectsMismatchedDenoisedShape() {
        withMockedOnnxTensorFactory {
            val vectorEstimator = mockk<OrtSession>()
            every { vectorEstimator.run(any()) } returns
                resultOf(
                    SupertonicOnnxContract.DENOISED_LATENT to
                        floatTensor(longArrayOf(1, 144, 2), FloatArray(288)),
                )

            val error =
                assertThrows(TensorShapeException::class.java) {
                    SupertonicDenoisingStage(testConfig(), SupertonicUniformSource { 0.5 })
                        .run(mockk(), vectorEstimator, mockk(), encodedFixture(), 1)
                }

            assertTrue(error.message, error.message!!.contains("axis 2"))
        }
    }

    @Test
    fun denoisingStageRejectsMissingDenoisedOutput() {
        withMockedOnnxTensorFactory {
            val vectorEstimator = mockk<OrtSession>()
            every { vectorEstimator.run(any()) } returns resultOf()

            val error =
                assertThrows(TensorShapeException::class.java) {
                    SupertonicDenoisingStage(testConfig(), SupertonicUniformSource { 0.5 })
                        .run(mockk(), vectorEstimator, mockk(), encodedFixture(), 1)
                }

            assertTrue(error.message, error.message!!.contains("denoised_latent"))
        }
    }

    @Test
    fun denoisingStageRejectsNonPositiveStepCount() {
        val stage = SupertonicDenoisingStage(testConfig(), SupertonicUniformSource { 0.5 })

        assertThrows(IllegalArgumentException::class.java) {
            stage.run(mockk(), mockk(), mockk(), encodedFixture(), 0)
        }
    }

    // ------------------------------------------------------------------
    // Chunk composer
    // ------------------------------------------------------------------

    @Test
    fun composerInsertsThirteenThousandTwoHundredThirtyZerosBetweenChunksOnly() {
        val first = FloatArray(10) { 1.0f }
        val second = FloatArray(10) { 2.0f }

        val composed = SupertonicChunkComposer.compose(44_100, listOf(first, second))

        // (int) (0.3f * 44100) = 13230
        assertEquals(10 + 13_230 + 10, composed.size)
        assertArrayEquals(first, composed.copyOfRange(0, 10), 0.0f)
        assertArrayEquals(FloatArray(13_230), composed.copyOfRange(10, 13_240), 0.0f)
        assertArrayEquals(second, composed.copyOfRange(13_240, 13_250), 0.0f)
    }

    @Test
    fun composerAccumulatesSilenceAcrossThreeChunks() {
        val chunk = FloatArray(4) { 3.0f }

        val composed = SupertonicChunkComposer.compose(44_100, listOf(chunk, chunk, chunk))

        assertEquals(4 * 3 + 13_230 * 2, composed.size)
        assertArrayEquals(FloatArray(13_230), composed.copyOfRange(4, 13_234), 0.0f)
        assertArrayEquals(chunk, composed.copyOfRange(13_234, 13_238), 0.0f)
    }

    @Test
    fun composerCopiesSingleChunkWithoutSilenceAndEmptyListToEmptyArray() {
        val chunk = FloatArray(7) { 5.0f }

        assertArrayEquals(chunk, SupertonicChunkComposer.compose(44_100, listOf(chunk)), 0.0f)
        assertEquals(0, SupertonicChunkComposer.compose(44_100, emptyList()).size)
    }

    @Test
    fun composerRejectsComposedTotalBeyondThe32BitPcmIndexSpace() {
        // silenceSamples = 2^30 exactly; 3 chunks + 2 gaps = 2147483651 > Int.MAX.
        val error =
            assertThrows(TensorShapeException::class.java) {
                SupertonicChunkComposer.compose(
                    1_073_741_824,
                    listOf(FloatArray(1), FloatArray(1), FloatArray(1)),
                    1.0f,
                )
            }

        assertTrue(error.message, error.message!!.contains("32-bit PCM index space"))
    }

    @Test
    fun composerRejectsSilenceBeyondTheCheckedSampleBound() {
        val error =
            assertThrows(TensorShapeException::class.java) {
                SupertonicChunkComposer.compose(Int.MAX_VALUE, listOf(FloatArray(1), FloatArray(1)), 1.5f)
            }

        assertTrue(error.message, error.message!!.contains("silence sample bound"))
    }

    @Test
    fun composerRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException::class.java) {
            SupertonicChunkComposer.compose(0, listOf(FloatArray(1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SupertonicChunkComposer.compose(44_100, listOf(FloatArray(1)), Float.POSITIVE_INFINITY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SupertonicChunkComposer.compose(44_100, listOf(FloatArray(1)), -0.1f)
        }
    }

    // ------------------------------------------------------------------
    // Fixtures and ORT mocking
    // ------------------------------------------------------------------

    private class CreatedTensor(
        val shape: LongArray,
        val floatData: FloatArray?,
        val longData: LongArray?,
    )

    /**
     * Mocks the static [OnnxTensor] buffer factories so stage code creates
     * recording tensor mocks instead of touching the native runtime. Every
     * created tensor's shape and copied primitive data land in [created], in
     * creation order, for later assertions.
     */
    private fun withMockedOnnxTensorFactory(
        block: (MutableList<CreatedTensor>) -> Unit,
    ) {
        val created = mutableListOf<CreatedTensor>()
        mockkStatic(OnnxTensor::class)
        try {
            every { OnnxTensor.createTensor(any(), any<FloatBuffer>(), any<LongArray>()) } answers {
                val buffer = secondArg<FloatBuffer>()
                val shape = thirdArg<LongArray>()
                val data = FloatArray(buffer.remaining())
                buffer.duplicate().get(data)
                created += CreatedTensor(shape, data, null)
                floatTensor(shape, data)
            }
            every { OnnxTensor.createTensor(any(), any<LongBuffer>(), any<LongArray>()) } answers {
                val buffer = secondArg<LongBuffer>()
                val shape = thirdArg<LongArray>()
                val data = LongArray(buffer.remaining())
                buffer.duplicate().get(data)
                created += CreatedTensor(shape, null, data)
                longTensor(shape)
            }
            block(created)
        } finally {
            unmockkAll()
        }
    }

    private fun floatTensor(shape: LongArray, data: FloatArray): OnnxTensor =
        tensorMock(shape, OnnxJavaType.FLOAT) { tensor ->
            every { tensor.floatBuffer } answers { FloatBuffer.wrap(data) }
        }

    private fun longTensor(shape: LongArray): OnnxTensor = tensorMock(shape, OnnxJavaType.INT64)

    private fun tensorMock(
        shape: LongArray,
        type: OnnxJavaType,
        stub: (OnnxTensor) -> Unit = {},
    ): OnnxTensor {
        val elementCount =
            shape.fold(1L) { product, dimension -> Math.multiplyExact(product, dimension) }.toInt()
        val info =
            when (type) {
                OnnxJavaType.FLOAT ->
                    TensorInfo.constructFromBuffer(FloatBuffer.allocate(elementCount), shape, type)
                OnnxJavaType.INT64 ->
                    TensorInfo.constructFromBuffer(LongBuffer.allocate(elementCount), shape, type)
                else -> error("Unsupported test tensor type: $type")
            }
        val tensor = mockk<OnnxTensor>()
        every { tensor.info } returns info
        every { tensor.close() } just runs
        stub(tensor)
        return tensor
    }

    private fun resultOf(vararg outputs: Pair<String, OnnxTensor>): OrtSession.Result {
        val byName = outputs.toMap()
        val result = mockk<OrtSession.Result>()
        every { result.get(any<String>()) } answers { Optional.ofNullable(byName[firstArg()]) }
        every { result.close() } just runs
        return result
    }

    private fun testConfig(): SupertonicConfig =
        SupertonicConfig(
            sampleRate = SupertonicOnnxContract.SAMPLE_RATE,
            baseChunkSize = SupertonicOnnxContract.BASE_CHUNK_SIZE,
            chunkCompressFactor = SupertonicOnnxContract.CHUNK_COMPRESSION_FACTOR,
            latentDim = SupertonicOnnxContract.LATENT_DIMENSION,
        )

    private fun testStyle(): SupertonicVoiceStyle =
        SupertonicVoiceStyle(
            ttlDims = StyleTensorDims(1, 2, 3, 6),
            ttlData = FloatArray(6),
            dpDims = StyleTensorDims(1, 2, 3, 6),
            dpData = FloatArray(6),
        )

    /**
     * One encoded chunk with latent plan `[1, 144, 1]` and a planned PCM
     * count of 100 samples; tensor contents are irrelevant to the denoising
     * stage beyond their shapes.
     */
    private fun encodedFixture(): SupertonicEncodedText =
        SupertonicEncodedText(
            textEmbedding = floatTensor(longArrayOf(1, 16, 5), FloatArray(80)),
            styleTtl = floatTensor(longArrayOf(1, 2, 3), FloatArray(6)),
            textMask = floatTensor(longArrayOf(1, 1, 5), FloatArray(5) { 1.0f }),
            textIds = longTensor(longArrayOf(1, 5)),
            durationSeconds = 100.0f / SupertonicOnnxContract.SAMPLE_RATE,
            waveformSamples = 100L,
            latentLength = 1,
            latentChannels = SupertonicOnnxContract.EXPANDED_LATENT_CHANNELS,
            textLength = 5,
            textEmbeddingChannels = 16,
        )

    private fun identityIndexer(): SupertonicUnicodeIndexer {
        val json = buildString {
            append('[')
            for (codePoint in 0 until SupertonicOnnxContract.UNICODE_INDEXER_SIZE) {
                if (codePoint > 0) append(',')
                append(codePoint)
            }
            append(']')
        }
        return SupertonicUnicodeIndexer.parse(json)
    }
}
