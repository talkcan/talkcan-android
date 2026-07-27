package io.talkcan.audio.onnx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Task 3.5 pure-component coverage for the Kotlin Parakeet engine: vocabulary
 * parsing, whitespace transcript decoding, bounded greedy selection, and tensor
 * shape interpretation. Every fixture uses runtime-local token ids, blank
 * indices, and dimensions; no production vocabulary or shape constants are
 * hardcoded.
 */
class ParakeetPureComponentsTest {

    // -- Vocabulary parsing --------------------------------------------------

    @Test
    fun vocabularyDenseIdsRoundTrip() {
        val vocab = ParakeetVocabulary.load(listOf("a 0", "b 1", "<blk> 2").asSequence())
        assertEquals(3, vocab.size)
        assertEquals(2, vocab.blankIndex)
        assertEquals("a", vocab.tokenOrNull(0))
        assertEquals("b", vocab.tokenOrNull(1))
        assertEquals("<blk>", vocab.tokenOrNull(2))
    }

    @Test
    fun vocabularyPlacesTokensByIdNotByLineOrder() {
        val vocab = ParakeetVocabulary.load(listOf("b 2", "<blk> 0", "a 1").asSequence())
        assertEquals(3, vocab.size)
        assertEquals(0, vocab.blankIndex)
        assertEquals("<blk>", vocab.tokenOrNull(0))
        assertEquals("a", vocab.tokenOrNull(1))
        assertEquals("b", vocab.tokenOrNull(2))
    }

    @Test
    fun vocabularyGapsResolveToEmptyStringNotNull() {
        val vocab = ParakeetVocabulary.load(listOf("a 0", "<blk> 3").asSequence())
        assertEquals(4, vocab.size)
        assertEquals(3, vocab.blankIndex)
        assertEquals("", vocab.tokenOrNull(1))
        assertEquals("", vocab.tokenOrNull(2))
    }

    @Test
    fun vocabularySizeIsMaxIdPlusOneEvenWhenBlankIsNotMax() {
        val vocab = ParakeetVocabulary.load(listOf("a 9", "<blk> 0").asSequence())
        assertEquals(10, vocab.size)
        assertEquals(0, vocab.blankIndex)
        assertEquals("a", vocab.tokenOrNull(9))
    }

    @Test
    fun vocabularySkipsMalformedLinesWithoutFailing() {
        val vocab = ParakeetVocabulary.load(
            listOf(
                "solo",
                "",
                "tok x",
                "neg -1",
                "two  spaces",
                "a b 5",
                "ok 4",
                "<blk> 0",
            ).asSequence(),
        )
        assertEquals(5, vocab.size)
        assertEquals(0, vocab.blankIndex)
        assertEquals("ok", vocab.tokenOrNull(4))
        assertEquals("", vocab.tokenOrNull(1))
        assertEquals("", vocab.tokenOrNull(2))
        assertEquals("", vocab.tokenOrNull(3))
        // The skipped "a b 5" line never allocated id 5: maxId stayed at 4.
        assertNull(vocab.tokenOrNull(5))
    }

    @Test
    fun vocabularyIgnoresExtraFieldsAfterId() {
        val vocab = ParakeetVocabulary.load(listOf("tok 2 extra", "<blk> 0").asSequence())
        assertEquals(3, vocab.size)
        assertEquals("tok", vocab.tokenOrNull(2))
    }

    @Test
    fun vocabularyTrimsTrailingWhitespaceBeforeSplit() {
        val vocab = ParakeetVocabulary.load(listOf("tok 1 ", "<blk> 0\t").asSequence())
        assertEquals(2, vocab.size)
        assertEquals("tok", vocab.tokenOrNull(1))
        assertEquals(0, vocab.blankIndex)
    }

    @Test
    fun vocabularyParsesWindowsLineEndings() {
        val vocab = ParakeetVocabulary.load("a 0\r\n<blk> 1\r\n")
        assertEquals(2, vocab.size)
        assertEquals("a", vocab.tokenOrNull(0))
        assertEquals(1, vocab.blankIndex)
    }

    @Test
    fun vocabularyDuplicateIdLastWriteWins() {
        val vocab = ParakeetVocabulary.load(listOf("a 1", "b 1", "<blk> 0").asSequence())
        assertEquals("b", vocab.tokenOrNull(1))
    }

    @Test
    fun vocabularyZeroIdIsValid() {
        val vocab = ParakeetVocabulary.load(listOf("x 0", "<blk> 1").asSequence())
        assertEquals(2, vocab.size)
        assertEquals("x", vocab.tokenOrNull(0))
    }

    @Test
    fun vocabularyWithoutBlankTokenFails() {
        assertThrows(ParakeetVocabularyException::class.java) {
            ParakeetVocabulary.load(listOf("a 0", "b 1").asSequence())
        }
        assertThrows(ParakeetVocabularyException::class.java) {
            ParakeetVocabulary.load("")
        }
        assertThrows(ParakeetVocabularyException::class.java) {
            ParakeetVocabulary.load(listOf("junk", "also junk").asSequence())
        }
    }

    @Test
    fun vocabularyBlankIndexFollowsRuntimeBlankId() {
        val vocab = ParakeetVocabulary.load(listOf("<blk> 7", "a 0").asSequence())
        assertEquals(8, vocab.size)
        assertEquals(7, vocab.blankIndex)
        assertEquals("<blk>", vocab.tokenOrNull(7))
    }

    @Test
    fun vocabularyConvertsSentencepieceSpaceToAsciiSpace() {
        val vocab = ParakeetVocabulary.load(
            listOf(
                "\u2581hello 0",
                "a\u2581b 1",
                "\u2581 2",
                "plain 3",
                "<blk> 4",
            ).asSequence(),
        )
        assertEquals(" hello", vocab.tokenOrNull(0))
        assertEquals("a b", vocab.tokenOrNull(1))
        assertEquals(" ", vocab.tokenOrNull(2))
        assertEquals("plain", vocab.tokenOrNull(3))
    }

    @Test
    fun vocabularyOutOfRangeLookupReturnsNull() {
        val vocab = ParakeetVocabulary.load(listOf("a 0", "<blk> 1").asSequence())
        assertNull(vocab.tokenOrNull(2))
        assertNull(vocab.tokenOrNull(-1))
        assertNull(vocab.tokenOrNull(Int.MAX_VALUE))
    }

    // -- Whitespace transcript decoding ---------------------------------------

    private val decodeVocab = ParakeetVocabulary.load(
        listOf(
            "\u2581hello 0",
            "\u2581world 1",
            ", 2",
            "\u2581. 3",
            "x\u2581 5",
            "\u2581y 6",
            "hello\u2581 7",
            "<blk> 8",
            "\u2581so 9",
            "\u2581and 10",
            "\u2581my 11",
            "\u2581fellow 12",
            "x\ty 13",
            "a 14",
            "b 15",
            "\u2581\u2581x 16",
        ).asSequence(),
    )

    @Test
    fun decodeZeroCountYieldsEmptyString() {
        assertEquals("", ParakeetTranscriptDecoder.decode(intArrayOf(0, 1), 0, decodeVocab))
        assertEquals("", ParakeetTranscriptDecoder.decode(intArrayOf(), 0, decodeVocab))
    }

    @Test
    fun decodeRemovesLeadingWhitespace() {
        assertEquals("hello", ParakeetTranscriptDecoder.decode(intArrayOf(0), 1, decodeVocab))
    }

    @Test
    fun decodeRemovesOnlyTheFirstLeadingWhitespaceChar() {
        // Token 16 decodes to two leading spaces; the pinned cleanup removes the
        // anchored first char only, and the second (a boundary space) survives.
        assertEquals(" x", ParakeetTranscriptDecoder.decode(intArrayOf(16), 1, decodeVocab))
    }

    @Test
    fun decodeKeepsBoundaryWhitespaceAsSingleSpace() {
        assertEquals("hello world", ParakeetTranscriptDecoder.decode(intArrayOf(0, 1), 2, decodeVocab))
    }

    @Test
    fun decodeRemovesWhitespaceBeforePunctuation() {
        assertEquals("world,", ParakeetTranscriptDecoder.decode(intArrayOf(1, 2), 2, decodeVocab))
        assertEquals("hello.", ParakeetTranscriptDecoder.decode(intArrayOf(0, 3), 2, decodeVocab))
        assertEquals(
            "hello world.",
            ParakeetTranscriptDecoder.decode(intArrayOf(0, 1, 3), 3, decodeVocab),
        )
    }

    @Test
    fun decodeCollapsesAdjacentSpacesToOne() {
        assertEquals("x y", ParakeetTranscriptDecoder.decode(intArrayOf(5, 6), 2, decodeVocab))
    }

    @Test
    fun decodeRemovesTrailingWhitespace() {
        assertEquals("hello", ParakeetTranscriptDecoder.decode(intArrayOf(7), 1, decodeVocab))
    }

    @Test
    fun decodeNormalizesAnyWhitespaceClassToSingleSpace() {
        assertEquals("x y", ParakeetTranscriptDecoder.decode(intArrayOf(13), 1, decodeVocab))
    }

    @Test
    fun decodePinnedPhraseCleanup() {
        assertEquals(
            "and so, my fellow",
            ParakeetTranscriptDecoder.decode(intArrayOf(10, 9, 2, 11, 12), 5, decodeVocab),
        )
    }

    @Test
    fun decodeHonorsCountPrefixOnly() {
        assertEquals("hello", ParakeetTranscriptDecoder.decode(intArrayOf(0, 1), 1, decodeVocab))
    }

    @Test
    fun decodeDropsOutOfRangeTokenIdsAndJoinsNeighborsDirectly() {
        assertEquals(
            "ab",
            ParakeetTranscriptDecoder.decode(intArrayOf(14, 999, -1, 15), 4, decodeVocab),
        )
    }

    @Test
    fun decodeGapTokensContributeNothing() {
        // Id 4 is a parse-time gap resolving to the empty string.
        assertEquals("ab", ParakeetTranscriptDecoder.decode(intArrayOf(14, 4, 15), 3, decodeVocab))
    }

    @Test
    fun decodeRejectsCountOutsideBuffer() {
        assertThrows(IllegalArgumentException::class.java) {
            ParakeetTranscriptDecoder.decode(intArrayOf(0, 1), -1, decodeVocab)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ParakeetTranscriptDecoder.decode(intArrayOf(0, 1), 3, decodeVocab)
        }
    }

    // -- Bounded greedy selection ---------------------------------------------

    private val blankLogits = floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f, 9f)
    private val nonBlankLogits = floatArrayOf(9f, 0.1f, 0.1f, 0.1f, 0.1f)

    private fun decoder(vocabSize: Int = 5, blankIndex: Int = 4): ParakeetGreedyDecoder =
        ParakeetGreedyDecoder(vocabSize, blankIndex)

    @Test
    fun decoderRejectsNonPositiveVocabSize() {
        assertThrows(IllegalArgumentException::class.java) { ParakeetGreedyDecoder(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { ParakeetGreedyDecoder(-1, 0) }
    }

    @Test
    fun decoderRejectsBlankOutsideVocab() {
        assertThrows(IllegalArgumentException::class.java) { ParakeetGreedyDecoder(5, -1) }
        assertThrows(IllegalArgumentException::class.java) { ParakeetGreedyDecoder(5, 5) }
        ParakeetGreedyDecoder(1, 0)
        ParakeetGreedyDecoder(5, 4)
    }

    @Test
    fun nonBlankArgmaxEmitsWithoutAdvancingTime() {
        assertEquals(
            GreedyDecision(token = 0, emit = true, advanceTime = false),
            decoder().decide(nonBlankLogits),
        )
    }

    @Test
    fun blankSelectionAdvancesTimeWithoutEmitting() {
        assertEquals(
            GreedyDecision(token = 4, emit = false, advanceTime = true),
            decoder().decide(blankLogits),
        )
    }

    @Test
    fun argmaxIgnoresLogitsBeyondRuntimeVocabSize() {
        val padded = floatArrayOf(0.1f, 0.2f, 0.05f, 99f, 99f)
        val expected = GreedyDecision(token = 1, emit = true, advanceTime = false)
        assertEquals(expected, decoder(vocabSize = 3, blankIndex = 2).decide(padded))
        assertEquals(expected, decoder(vocabSize = 3, blankIndex = 2).decide(padded, padded.size))
    }

    @Test
    fun logitCountBoundsArgmaxWindow() {
        val d = decoder()
        assertEquals(
            GreedyDecision(token = 0, emit = true, advanceTime = false),
            d.decide(floatArrayOf(9f, 0.1f, 0.2f, 0.1f, 0.1f), 1),
        )
        assertEquals(
            GreedyDecision(token = 1, emit = true, advanceTime = false),
            d.decide(floatArrayOf(0.1f, 0.2f, 9f, 0.1f, 0.1f), 2),
        )
    }

    @Test
    fun zeroLogitCountSelectsBlankAndAdvances() {
        assertEquals(
            GreedyDecision(token = 4, emit = false, advanceTime = true),
            decoder().decide(nonBlankLogits, 0),
        )
    }

    @Test
    fun logitCountOutsideBufferRejected() {
        val d = decoder()
        assertThrows(IllegalArgumentException::class.java) { d.decide(nonBlankLogits, -1) }
        assertThrows(IllegalArgumentException::class.java) { d.decide(nonBlankLogits, 6) }
    }

    @Test
    fun exactTiesPreferFirstIndexNotBlank() {
        val d = decoder()
        assertEquals(
            GreedyDecision(token = 0, emit = true, advanceTime = false),
            d.decide(floatArrayOf(0.5f, 0.5f, 0.1f, 0.1f, 0.1f)),
        )
        // Blank (4) ties the leader but comes later: position, not blankness, wins.
        assertEquals(
            GreedyDecision(token = 1, emit = true, advanceTime = false),
            d.decide(floatArrayOf(0.1f, 0.5f, 0.5f, 0.1f, 0.5f)),
        )
    }

    @Test
    fun nanNeverDisplacesFiniteBest() {
        val d = decoder()
        assertEquals(
            GreedyDecision(token = 0, emit = true, advanceTime = false),
            d.decide(floatArrayOf(0.5f, Float.NaN, 0.4f, 0.1f, 0.1f)),
        )
        assertEquals(
            GreedyDecision(token = 2, emit = true, advanceTime = false),
            d.decide(floatArrayOf(0.5f, Float.NaN, 0.9f, 0.1f, 0.1f)),
        )
    }

    @Test
    fun emitsExactlyMaxTokensPerStepThenAdvancesAndResets() {
        val d = decoder()
        val max = ParakeetGreedyDecoder.MAX_TOKENS_PER_STEP
        d.beginSequence()
        for (i in 1 until max) {
            assertEquals(
                "emission $i must not advance time",
                GreedyDecision(token = 0, emit = true, advanceTime = false),
                d.decide(nonBlankLogits),
            )
        }
        assertEquals(
            GreedyDecision(token = 0, emit = true, advanceTime = true),
            d.decide(nonBlankLogits),
        )
        // Counter reset on advance: the next emission starts a fresh run.
        assertEquals(
            GreedyDecision(token = 0, emit = true, advanceTime = false),
            d.decide(nonBlankLogits),
        )
    }

    @Test
    fun blankResetsEmissionCounterBeforeBound() {
        val d = decoder()
        val max = ParakeetGreedyDecoder.MAX_TOKENS_PER_STEP
        d.beginSequence()
        repeat(max - 1) { d.decide(nonBlankLogits) }
        assertEquals(
            GreedyDecision(token = 4, emit = false, advanceTime = true),
            d.decide(blankLogits),
        )
        // Without the blank-driven reset this would be emission #max and advance.
        assertEquals(
            GreedyDecision(token = 0, emit = true, advanceTime = false),
            d.decide(nonBlankLogits),
        )
    }

    @Test
    fun beginSequenceResetsCarriedEmissionCount() {
        val d = decoder()
        val max = ParakeetGreedyDecoder.MAX_TOKENS_PER_STEP
        d.beginSequence()
        repeat(max - 1) { d.decide(nonBlankLogits) }
        d.beginSequence()
        assertEquals(
            GreedyDecision(token = 0, emit = true, advanceTime = false),
            d.decide(nonBlankLogits),
        )
    }

    // -- Tensor shape interpretation -------------------------------------------

    @Test
    fun decoderStateInterpretsLayersAndChannelsIgnoringMiddleAxis() {
        val dynamic = ParakeetShapes.DYNAMIC_AXIS.toLong()
        val fromDynamic = ParakeetShapes.decoderState(longArrayOf(6, dynamic, 1024))
        assertEquals(6, fromDynamic.layers)
        assertEquals(1024, fromDynamic.channels)
        val fromConcrete = ParakeetShapes.decoderState(longArrayOf(6, 42, 1024))
        assertEquals(fromDynamic.layers, fromConcrete.layers)
        assertEquals(fromDynamic.channels, fromConcrete.channels)
    }

    @Test
    fun decoderStateInitialShapePinsMiddleAxisToOne() {
        val layout = ParakeetShapes.decoderState(longArrayOf(4, -1, 512))
        assertArrayEquals(longArrayOf(4, 1, 512), layout.initialShape())
    }

    @Test
    fun decoderStateRejectsWrongRank() {
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.decoderState(longArrayOf(6, 1024))
        }
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.decoderState(longArrayOf(6, 1, 1024, 2))
        }
    }

    @Test
    fun decoderStateRejectsNonPositiveStaticAxes() {
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.decoderState(longArrayOf(0, -1, 1024))
        }
        // Dynamic is only honored on the middle axis; layers/channels must be static.
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.decoderState(longArrayOf(-1, -1, 1024))
        }
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.decoderState(longArrayOf(6, -1, 0))
        }
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.decoderState(longArrayOf(6, -1, -1))
        }
    }

    @Test
    fun encoderOutputInterpretsConcreteShape() {
        val layout = ParakeetShapes.encoderOutput(longArrayOf(1, 250, 1024))
        assertEquals(1, layout.batch)
        assertEquals(250, layout.timeSteps)
        assertEquals(1024, layout.features)
    }

    @Test
    fun encoderOutputAcceptsDynamicBatchOnly() {
        val layout = ParakeetShapes.encoderOutput(
            longArrayOf(ParakeetShapes.DYNAMIC_AXIS.toLong(), 250, 1024),
        )
        assertEquals(ParakeetShapes.DYNAMIC_AXIS, layout.batch)
        assertEquals(250, layout.timeSteps)
        assertEquals(1024, layout.features)
    }

    @Test
    fun encoderOutputRejectsWrongRank() {
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.encoderOutput(longArrayOf(1, 250))
        }
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.encoderOutput(longArrayOf(1, 250, 1024, 2))
        }
    }

    @Test
    fun encoderOutputRejectsInvalidBatch() {
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.encoderOutput(longArrayOf(0, 250, 1024))
        }
        // Only -1 is dynamic; other negatives are invalid.
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.encoderOutput(longArrayOf(-2, 250, 1024))
        }
    }

    @Test
    fun encoderOutputRejectsNonPositiveTimeOrFeatures() {
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.encoderOutput(longArrayOf(1, 0, 1024))
        }
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.encoderOutput(longArrayOf(1, -1, 1024))
        }
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.encoderOutput(longArrayOf(1, 250, 0))
        }
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.encoderOutput(longArrayOf(1, 250, -1))
        }
    }

    @Test
    fun logitsLengthCollapsesLeadingSingletonAndDynamicAxes() {
        val dynamic = ParakeetShapes.DYNAMIC_AXIS.toLong()
        assertEquals(4096, ParakeetShapes.logitsLength(longArrayOf(1, 1, 4096)))
        assertEquals(4096, ParakeetShapes.logitsLength(longArrayOf(4096)))
        assertEquals(4096, ParakeetShapes.logitsLength(longArrayOf(dynamic, 1, 4096)))
        assertEquals(4096, ParakeetShapes.logitsLength(longArrayOf(1, dynamic, 4096)))
        assertEquals(4096, ParakeetShapes.logitsLength(longArrayOf(dynamic, dynamic, 4096)))
    }

    @Test
    fun logitsLengthRejectsRankZero() {
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.logitsLength(longArrayOf())
        }
    }

    @Test
    fun logitsLengthRejectsNonPositiveVocabDimension() {
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.logitsLength(longArrayOf(1, 1, 0))
        }
        // The vocabulary dimension must be static; dynamic is not admissible here.
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.logitsLength(longArrayOf(1, 1, -1))
        }
    }

    @Test
    fun logitsLengthRejectsLeadingAxisNeitherSingletonNorDynamic() {
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.logitsLength(longArrayOf(2, 1, 4096))
        }
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.logitsLength(longArrayOf(1, 0, 4096))
        }
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.logitsLength(longArrayOf(1, -2, 4096))
        }
    }

    @Test
    fun dimensionsAboveIntRangeRejected() {
        val overflow = Int.MAX_VALUE.toLong() + 1
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.encoderOutput(longArrayOf(1, 250, overflow))
        }
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.decoderState(longArrayOf(overflow, -1, 1024))
        }
        assertThrows(TensorShapeException::class.java) {
            ParakeetShapes.logitsLength(longArrayOf(1, 1, overflow))
        }
    }
}
