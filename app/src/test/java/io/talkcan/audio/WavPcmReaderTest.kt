package io.talkcan.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WavPcmReaderTest {
    private lateinit var tempDir: File
    private var fixtureIndex = 0

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("wav-pcm-reader-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun readsUnsignedPcm8IntoCenteredNormalizedSamples() {
        val decoded = decodeSuccess(
            writeWav(
                formatTag = WAVE_FORMAT_PCM,
                channels = 1,
                sampleRate = 16_000,
                bitsPerSample = 8,
                payload = byteArrayOf(0, 128.toByte(), 255.toByte()),
            ),
        )

        assertEquals(16_000, decoded.sampleRate)
        assertFloatSamples(floatArrayOf(-1.0f, 0.0f, 127.0f / 128.0f), decoded.samples)
    }

    @Test
    fun readsSignedPcm16IntoNormalizedSamples() {
        val decoded = decodeSuccess(
            writeWav(
                formatTag = WAVE_FORMAT_PCM,
                channels = 1,
                sampleRate = 16_000,
                bitsPerSample = 16,
                payload = pcm16Bytes(shortArrayOf(-32_768, -16_384, 0, 16_384, 32_767)),
            ),
        )

        assertEquals(16_000, decoded.sampleRate)
        assertFloatSamples(
            floatArrayOf(-1.0f, -0.5f, 0.0f, 0.5f, 32_767.0f / 32_768.0f),
            decoded.samples,
        )
    }

    @Test
    fun readsIeeeFloatAndClampsSourceSamplesBeforePcm16Conversion() {
        val decoded = decodeSuccess(
            writeWav(
                formatTag = WAVE_FORMAT_IEEE_FLOAT,
                channels = 1,
                sampleRate = 16_000,
                bitsPerSample = 32,
                payload = float32Bytes(floatArrayOf(-1.25f, -0.00002f, 0.00002f, 1.25f)),
            ),
        )

        assertFloatSamples(floatArrayOf(-1.0f, -0.00002f, 0.00002f, 1.0f), decoded.samples)

        val normalized = requireNotNull(TtsAudio.toNavigationPcm(decoded.samples, decoded.sampleRate))
        assertEquals(16_000, normalized.sampleRate)
        assertEquals(shortArrayOf(-32_768, -1, 1, 32_767).toList(), normalized.samples.toList())
    }

    @Test
    fun downmixesStereoByAveragingEachFrame() {
        val decoded = decodeSuccess(
            writeWav(
                formatTag = WAVE_FORMAT_PCM,
                channels = 2,
                sampleRate = 16_000,
                bitsPerSample = 16,
                payload = pcm16Bytes(shortArrayOf(-16_384, 16_384, 0, 32_767)),
            ),
        )

        assertFloatSamples(floatArrayOf(0.0f, 32_767.0f / 65_536.0f), decoded.samples)
    }

    @Test
    fun normalizesFortyFourPointOneKhzToSixteenKhzWithLinearInterpolation() {
        val decoded = decodeSuccess(
            writeWav(
                formatTag = WAVE_FORMAT_PCM,
                channels = 1,
                sampleRate = 44_100,
                bitsPerSample = 16,
                payload = pcm16Bytes(ShortArray(441) { ((it - 220) * 100).toShort() }),
            ),
        )

        val normalized = requireNotNull(TtsAudio.toNavigationPcm(decoded.samples, decoded.sampleRate))
        assertEquals(16_000, normalized.sampleRate)
        assertEquals(160, normalized.samples.size)
        assertEquals(-22_000, normalized.samples[0].toInt())
        assertEquals(50, normalized.samples[80].toInt())
        assertEquals(21_824, normalized.samples[159].toInt())
    }

    @Test
    fun normalizesTwentyTwoPointZeroFiveKhzToSixteenKhzWithLinearInterpolation() {
        val decoded = decodeSuccess(
            writeWav(
                formatTag = WAVE_FORMAT_PCM,
                channels = 1,
                sampleRate = 22_050,
                bitsPerSample = 16,
                payload = pcm16Bytes(ShortArray(441) { ((it - 220) * 100).toShort() }),
            ),
        )

        val normalized = requireNotNull(TtsAudio.toNavigationPcm(decoded.samples, decoded.sampleRate))
        assertEquals(16_000, normalized.sampleRate)
        assertEquals(320, normalized.samples.size)
        assertEquals(-22_000, normalized.samples[0].toInt())
        assertEquals(50, normalized.samples[160].toInt())
        assertEquals(21_962, normalized.samples[319].toInt())
    }

    @Test
    fun normalizesMonoAndStereoSupportedWavEncodingsToExactNavigationPcmSamples() {
        data class Fixture(
            val name: String,
            val formatTag: Int,
            val channels: Int,
            val sampleRate: Int,
            val bitsPerSample: Int,
            val payload: ByteArray,
            val expectedSamples: ShortArray,
        )

        val fixtures = listOf(
            Fixture(
                name = "native-rate mono PCM8",
                formatTag = WAVE_FORMAT_PCM,
                channels = 1,
                sampleRate = 16_000,
                bitsPerSample = 8,
                payload = byteArrayOf(0, 128.toByte(), 255.toByte()),
                expectedSamples = shortArrayOf(-32_768, 0, 32_512),
            ),
            Fixture(
                name = "resampled stereo PCM8",
                formatTag = WAVE_FORMAT_PCM,
                channels = 2,
                sampleRate = 8_000,
                bitsPerSample = 8,
                payload = byteArrayOf(0, 128.toByte(), 128.toByte(), 255.toByte()),
                expectedSamples = shortArrayOf(-16_384, -64, 16_256, 16_256),
            ),
            Fixture(
                name = "resampled mono PCM16",
                formatTag = WAVE_FORMAT_PCM,
                channels = 1,
                sampleRate = 8_000,
                bitsPerSample = 16,
                payload = pcm16Bytes(shortArrayOf(-32_768, 16_384)),
                expectedSamples = shortArrayOf(-32_768, -8_192, 16_384, 16_384),
            ),
            Fixture(
                name = "native-rate stereo PCM16",
                formatTag = WAVE_FORMAT_PCM,
                channels = 2,
                sampleRate = 16_000,
                bitsPerSample = 16,
                payload = pcm16Bytes(shortArrayOf(-16_384, 0, 0, 16_384, 32_767, 32_767)),
                expectedSamples = shortArrayOf(-8_192, 8_192, 32_767),
            ),
            Fixture(
                name = "native-rate mono IEEE float",
                formatTag = WAVE_FORMAT_IEEE_FLOAT,
                channels = 1,
                sampleRate = 16_000,
                bitsPerSample = 32,
                payload = float32Bytes(floatArrayOf(-1.0f, -0.5f, 0.5f, 1.0f)),
                expectedSamples = shortArrayOf(-32_768, -16_384, 16_384, 32_767),
            ),
            Fixture(
                name = "resampled stereo IEEE float",
                formatTag = WAVE_FORMAT_IEEE_FLOAT,
                channels = 2,
                sampleRate = 8_000,
                bitsPerSample = 32,
                payload = float32Bytes(floatArrayOf(-1.0f, 0.0f, 0.0f, 1.0f)),
                expectedSamples = shortArrayOf(-16_384, 0, 16_384, 16_384),
            ),
        )

        fixtures.forEach { fixture ->
            val result = normalizeWavToScoPcm(
                writeWav(
                    formatTag = fixture.formatTag,
                    channels = fixture.channels,
                    sampleRate = fixture.sampleRate,
                    bitsPerSample = fixture.bitsPerSample,
                    payload = fixture.payload,
                ),
            )

            assertTrue("${fixture.name}: expected normalized PCM, got $result", result is NormalizeResult.Success)
            val pcm = (result as NormalizeResult.Success).pcm
            assertEquals("${fixture.name}: target sample rate", 16_000, pcm.sampleRate)
            assertEquals("${fixture.name}: exact PCM16 samples", fixture.expectedSamples.toList(), pcm.samples.toList())
        }
    }

    @Test
    fun mapsWavDecodeBoundariesToDistinctInfrastructureFailures() {
        data class FailureCase(
            val name: String,
            val writeFixture: () -> File,
            val expected: NavigationTtsFailure.RendererInfrastructureFailure,
        )

        val cases = listOf(
            FailureCase(
                name = "unsupported encoding",
                writeFixture = {
                    writeWav(
                        formatTag = 7,
                        channels = 1,
                        sampleRate = 16_000,
                        bitsPerSample = 8,
                        payload = byteArrayOf(0),
                    )
                },
                expected = NavigationTtsFailure.RendererInfrastructureFailure.UnsupportedEncoding,
            ),
            FailureCase(
                name = "unsupported channel count",
                writeFixture = {
                    writeWav(
                        formatTag = WAVE_FORMAT_PCM,
                        channels = 3,
                        sampleRate = 16_000,
                        bitsPerSample = 16,
                        payload = ByteArray(6),
                    )
                },
                expected = NavigationTtsFailure.RendererInfrastructureFailure.UnsupportedChannelCount,
            ),
            FailureCase(
                name = "empty PCM",
                writeFixture = {
                    writeWav(
                        formatTag = WAVE_FORMAT_PCM,
                        channels = 1,
                        sampleRate = 16_000,
                        bitsPerSample = 16,
                        payload = ByteArray(0),
                    )
                },
                expected = NavigationTtsFailure.RendererInfrastructureFailure.EmptyPcm,
            ),
            FailureCase(
                name = "malformed WAV",
                writeFixture = { nextFixture().apply { writeText("not a WAV file") } },
                expected = NavigationTtsFailure.RendererInfrastructureFailure.WavDecodeFailure,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                NormalizeResult.Failure(case.expected),
                normalizeWavToScoPcm(case.writeFixture()),
            )
        }
    }
    @Test
    fun reportsEmptyPcmWithoutReturningARecording() {
        val file = writeWav(
            formatTag = WAVE_FORMAT_PCM,
            channels = 1,
            sampleRate = 16_000,
            bitsPerSample = 16,
            payload = ByteArray(0),
        )

        assertEquals(WavDecodeResult.EmptyPcm, WavPcmReader.readNormalizedResult(file))
        assertNull(WavPcmReader.readNormalized(file))
    }

    @Test
    fun reportsUnsupportedEncodingWithDeclaredFormatDetails() {
        val cases = listOf(
            UnsupportedEncodingCase("mu-law", formatTag = 7, bitsPerSample = 8),
            UnsupportedEncodingCase("24-bit PCM", formatTag = WAVE_FORMAT_PCM, bitsPerSample = 24),
            UnsupportedEncodingCase("64-bit IEEE float", formatTag = WAVE_FORMAT_IEEE_FLOAT, bitsPerSample = 64),
        )

        cases.forEach { case ->
            val result = WavPcmReader.readNormalizedResult(
                writeWav(
                    formatTag = case.formatTag,
                    channels = 1,
                    sampleRate = 16_000,
                    bitsPerSample = case.bitsPerSample,
                    payload = ByteArray(case.bitsPerSample / 8),
                ),
            )

            assertEquals(
                "${case.name} must be rejected before decoding",
                WavDecodeResult.UnsupportedEncoding(case.formatTag, case.bitsPerSample),
                result,
            )
        }
    }

    @Test
    fun reportsUnsupportedChannelCountBeforeAttemptingDownmix() {
        val cases = listOf(0, 3)

        cases.forEach { channels ->
            val result = WavPcmReader.readNormalizedResult(
                writeWav(
                    formatTag = WAVE_FORMAT_PCM,
                    channels = channels,
                    sampleRate = 16_000,
                    bitsPerSample = 16,
                    payload = ByteArray(6),
                ),
            )

            assertEquals(
                "channel count $channels must not be converted",
                WavDecodeResult.UnsupportedChannelCount(channels),
                result,
            )
        }
    }

    @Test
    fun reportsMalformedHeaderForTruncatedContainerAndTruncatedPcmData() {
        val truncatedContainer = nextFixture().apply { writeBytes("RIFF".toByteArray(StandardCharsets.US_ASCII)) }
        val truncatedData = writeWav(
            formatTag = WAVE_FORMAT_PCM,
            channels = 1,
            sampleRate = 16_000,
            bitsPerSample = 16,
            payload = pcm16Bytes(shortArrayOf(123)),
        ).apply {
            writeBytes(readBytes().copyOf(length().toInt() - 1))
        }

        assertEquals(WavDecodeResult.MalformedHeader, WavPcmReader.readNormalizedResult(truncatedContainer))
        assertEquals(WavDecodeResult.MalformedHeader, WavPcmReader.readNormalizedResult(truncatedData))
    }

    @Test
    fun reportsZeroSampleRateAsMalformedHeader() {
        val file = writeWav(
            formatTag = WAVE_FORMAT_PCM,
            channels = 1,
            sampleRate = 0,
            bitsPerSample = 16,
            payload = pcm16Bytes(shortArrayOf(123)),
        )

        assertEquals(WavDecodeResult.MalformedHeader, WavPcmReader.readNormalizedResult(file))
    }

    @Test
    fun skipsOddSizedMetadataChunkPaddingBeforeFormatAndData() {
        val decoded = decodeSuccess(writeWavWithOddSizedJunkChunk())

        assertEquals(16_000, decoded.sampleRate)
        assertFloatSamples(floatArrayOf(0.5f), decoded.samples)
    }

    @Test
    fun reportsPartialPcmFrameAsMalformedHeader() {
        val file = writeWav(
            formatTag = WAVE_FORMAT_PCM,
            channels = 1,
            sampleRate = 16_000,
            bitsPerSample = 16,
            payload = byteArrayOf(0, 0, 0),
        )

        assertEquals(WavDecodeResult.MalformedHeader, WavPcmReader.readNormalizedResult(file))
    }

    private fun decodeSuccess(file: File): WavDecoded {
        val result = WavPcmReader.readNormalizedResult(file)
        assertTrue("expected successful WAV decode, got $result", result is WavDecodeResult.Success)
        return (result as WavDecodeResult.Success).decoded
    }

    private fun assertFloatSamples(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index ->
            assertEquals("sample $index", expected[index], actual[index], FLOAT_TOLERANCE)
        }
    }

    private fun writeWav(
        formatTag: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        payload: ByteArray,
    ): File {
        val blockAlign = channels * (bitsPerSample / 8)
        val header = ByteBuffer.allocate(44 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(StandardCharsets.US_ASCII))
        header.putInt(36 + payload.size)
        header.put("WAVE".toByteArray(StandardCharsets.US_ASCII))
        header.put("fmt ".toByteArray(StandardCharsets.US_ASCII))
        header.putInt(16)
        header.putShort(formatTag.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * blockAlign)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(StandardCharsets.US_ASCII))
        header.putInt(payload.size)
        header.put(payload)
        return nextFixture().apply { writeBytes(header.array()) }
    }

    private fun writeWavWithOddSizedJunkChunk(): File {
        val payload = pcm16Bytes(shortArrayOf(16_384))
        val junkSize = 3
        val riffSize = 4 + 8 + junkSize + 1 + 8 + 16 + 8 + payload.size
        val wav = ByteBuffer.allocate(riffSize + 8).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray(StandardCharsets.US_ASCII))
        wav.putInt(riffSize)
        wav.put("WAVE".toByteArray(StandardCharsets.US_ASCII))
        wav.put("JUNK".toByteArray(StandardCharsets.US_ASCII))
        wav.putInt(junkSize)
        wav.put(byteArrayOf(1, 2, 3))
        wav.put(0)
        wav.put("fmt ".toByteArray(StandardCharsets.US_ASCII))
        wav.putInt(16)
        wav.putShort(WAVE_FORMAT_PCM.toShort())
        wav.putShort(1)
        wav.putInt(16_000)
        wav.putInt(32_000)
        wav.putShort(2)
        wav.putShort(16)
        wav.put("data".toByteArray(StandardCharsets.US_ASCII))
        wav.putInt(payload.size)
        wav.put(payload)
        return nextFixture().apply { writeBytes(wav.array()) }
    }

    private fun pcm16Bytes(samples: ShortArray): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putShort(it) }
        }.array()

    private fun float32Bytes(samples: FloatArray): ByteArray =
        ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putFloat(it) }
        }.array()

    private fun nextFixture(): File = File(tempDir, "fixture-${fixtureIndex++}.wav")

    private data class UnsupportedEncodingCase(
        val name: String,
        val formatTag: Int,
        val bitsPerSample: Int,
    )

    private companion object {
        const val WAVE_FORMAT_PCM = 1
        const val WAVE_FORMAT_IEEE_FLOAT = 3
        const val FLOAT_TOLERANCE = 0.000001f
    }
}
