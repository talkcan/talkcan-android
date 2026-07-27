package io.talkcan.audiofile

import io.talkcan.audio.JournalWavWriter
import io.talkcan.audio.WavPcmReader
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 6.5/6.6: Strict bounded mono PCM S16LE WAV codec.
 *
 * Proves the decoder validates the complete RIFF/WAVE container (chunk walk, unknown-chunk
 * skipping, PCM S16LE mono admission, supported rate, artifact/duration/PCM bounds, completeness,
 * no partial publication) and the encoder produces the deterministic canonical document
 * byte-for-byte identical to the host's existing [JournalWavWriter] and readable by the existing
 * [WavPcmReader] (codec reuse, no fork).
 */
class WavPcm16Test {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bounds = AudioArtifactBounds()
    private val samples = shortArrayOf(0, 1, -1, 32767, -32768, 2, -2, 3)

    private fun decoded(bytes: ByteArray, b: AudioArtifactBounds = bounds): PcmMonoS16Le =
        (WavPcm16.decode(bytes, b) as WavPcm16.DecodeResult.Decoded).pcm

    private fun result(bytes: ByteArray, b: AudioArtifactBounds = bounds): WavPcm16.DecodeResult =
        WavPcm16.decode(bytes, b)

    // -- Encode / decode round-trip ----------------------------------------

    @Test
    fun encodeDecodeRoundTripsExactSamplesAndRate() {
        val pcm = PcmMonoS16Le(samples, 16_000)
        val bytes = WavPcm16.encode(pcm)
        val back = decoded(bytes)
        assertEquals(16_000, back.sampleRate)
        assertArrayEquals(samples, back.samples)
        assertEquals(samples.size.toLong() * 2, back.pcmBytes)
        assertEquals(1, back.channels)
    }

    @Test
    fun encodeIsDeterministicForIdenticalInput() {
        val pcm = PcmMonoS16Le(samples, 16_000)
        assertArrayEquals(WavPcm16.encode(pcm), WavPcm16.encode(pcm))
    }

    @Test
    fun encodeProducesCanonicalHeaderLayout() {
        val bytes = WavPcm16.encode(PcmMonoS16Le(samples, 16_000))
        val dataSize = samples.size * 2
        assertEquals(44 + dataSize, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + dataSize, readLe32(bytes, 4))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals(16, readLe32(bytes, 16))
        assertEquals(1, readLe16(bytes, 20)) // PCM format tag
        assertEquals(1, readLe16(bytes, 22)) // mono
        assertEquals(16_000, readLe32(bytes, 24))
        assertEquals(32_000, readLe32(bytes, 28)) // byte rate = rate * 2
        assertEquals(2, readLe16(bytes, 32)) // block align
        assertEquals(16, readLe16(bytes, 34)) // bits per sample
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(dataSize, readLe32(bytes, 40))
    }

    // -- Codec reuse: byte-for-byte identical to the existing host writer ----

    @Test
    fun encodeIsByteForByteIdenticalToJournalWavWriter() {
        val rate = 16_000
        val file = tmp.newFile("journal.wav")
        val writer = JournalWavWriter(file, rate)
        writer.writeChunk(samples)
        writer.finalize()
        val journalBytes = file.readBytes()
        assertArrayEquals(journalBytes, WavPcm16.encode(PcmMonoS16Le(samples, rate)))
    }

    @Test
    fun decodeAcceptsWhatJournalWavWriterProduces() {
        val rate = 8_000
        val file = tmp.newFile("journal8k.wav")
        val writer = JournalWavWriter(file, rate)
        writer.writeChunk(samples)
        writer.finalize()
        val back = decoded(file.readBytes())
        assertEquals(rate, back.sampleRate)
        assertArrayEquals(samples, back.samples)
    }

    @Test
    fun encodedWavIsReadableByExistingWavPcmReader() {
        val rate = 16_000
        val file = tmp.newFile("encoded.wav")
        file.writeBytes(WavPcm16.encode(PcmMonoS16Le(samples, rate)))
        val info = WavPcmReader.read(file)!!
        assertEquals(rate, info.sampleRate)
        assertEquals(1, info.channelCount)
        assertEquals(16, info.bitsPerSample)
        assertArrayEquals(samples, info.samples)
    }

    // -- Decode: valid containers ------------------------------------------

    @Test
    fun decodeAdmitsSupportedRates() {
        for (rate in bounds.supportedSampleRates) {
            val back = decoded(buildWav(sampleRate = rate, samples = samples))
            assertEquals(rate, back.sampleRate)
            assertArrayEquals(samples, back.samples)
        }
    }

    @Test
    fun decodeSkipsUnknownChunksHonoringOddPadding() {
        val bytes = buildWav(
            samples = samples,
            extraChunks = listOf(
                "LIST" to byteArrayOf(9, 8, 7, 6), // even size
                "JUNK" to byteArrayOf(1, 2, 3), // odd size -> 1 pad byte
            ),
        )
        val back = decoded(bytes)
        assertArrayEquals(samples, back.samples)
        assertEquals(16_000, back.sampleRate)
    }

    // -- Decode: malformed header ------------------------------------------

    @Test
    fun decodeRejectsShortBufferAsMalformed() {
        assertEquals(WavPcm16.DecodeResult.MalformedHeader, result(ByteArray(11)))
    }

    @Test
    fun decodeRejectsBadRiffMagic() {
        val bytes = buildWav(samples = samples).also { it[0] = 'X'.code.toByte() }
        assertEquals(WavPcm16.DecodeResult.MalformedHeader, result(bytes))
    }

    @Test
    fun decodeRejectsBadWaveMagic() {
        val bytes = buildWav(samples = samples).also { it[8] = 'X'.code.toByte() }
        assertEquals(WavPcm16.DecodeResult.MalformedHeader, result(bytes))
    }

    @Test
    fun decodeRejectsMissingFmtChunk() {
        // data chunk only, no fmt
        val bytes = riffWave("data" to le16Bytes(samples))
        assertEquals(WavPcm16.DecodeResult.MalformedHeader, result(bytes))
    }

    @Test
    fun decodeRejectsMissingDataChunk() {
        val bytes = riffWave("fmt " to fmtChunk())
        assertEquals(WavPcm16.DecodeResult.MalformedHeader, result(bytes))
    }

    @Test
    fun decodeRejectsUndersizedFmtChunk() {
        val bytes = riffWave(
            "fmt " to ByteArray(14), // < 16
            "data" to le16Bytes(samples),
        )
        assertEquals(WavPcm16.DecodeResult.MalformedHeader, result(bytes))
    }

    // -- Decode: truncation / inconsistent length --------------------------

    @Test
    fun decodeRejectsChunkRunningPastBufferAsTruncated() {
        val full = buildWav(samples = samples)
        assertEquals(WavPcm16.DecodeResult.Truncated, result(full.copyOfRange(0, full.size - 3)))
    }

    @Test
    fun decodeRejectsDeclaredDataSizeBeyondBufferAsTruncated() {
        // declaredDataSize larger than the bytes actually present
        val bytes = buildWav(samples = samples, declaredDataSize = samples.size * 2 + 100)
        assertEquals(WavPcm16.DecodeResult.Truncated, result(bytes))
    }

    @Test
    fun decodeRejectsOddDataSizeAsInconsistent() {
        val bytes = buildWav(samples = shortArrayOf(1, 2, 3, 4), declaredDataSize = 9) + byteArrayOf(0)
        assertEquals(WavPcm16.DecodeResult.InconsistentLength, result(bytes))
    }

    @Test
    fun decodeRejectsWrongBlockAlignAsInconsistent() {
        val bytes = buildWav(samples = samples, blockAlign = 4)
        assertEquals(WavPcm16.DecodeResult.InconsistentLength, result(bytes))
    }

    @Test
    fun decodeRejectsWrongByteRateAsInconsistent() {
        val bytes = buildWav(samples = samples, byteRate = 16_000 * 3)
        assertEquals(WavPcm16.DecodeResult.InconsistentLength, result(bytes))
    }

    // -- Decode: unsupported encoding / channels / rate --------------------

    @Test
    fun decodeRejectsNonPcmFormat() {
        val bytes = buildWav(samples = samples, audioFormat = 3) // IEEE float
        assertEquals(WavPcm16.DecodeResult.UnsupportedEncoding, result(bytes))
    }

    @Test
    fun decodeRejectsNon16Bit() {
        val bytes = buildWav(samples = samples, bitsPerSample = 8, blockAlign = 1, byteRate = 16_000)
        assertEquals(WavPcm16.DecodeResult.UnsupportedEncoding, result(bytes))
    }

    @Test
    fun decodeRejectsStereo() {
        val bytes = buildWav(samples = samples, channels = 2, blockAlign = 4, byteRate = 16_000 * 4)
        assertEquals(WavPcm16.DecodeResult.UnsupportedChannels, result(bytes))
    }

    @Test
    fun decodeRejectsUnsupportedRate() {
        val bytes = buildWav(samples = samples, sampleRate = 12_345, byteRate = 12_345 * 2)
        assertEquals(WavPcm16.DecodeResult.UnsupportedRate, result(bytes))
    }

    @Test
    fun decodeRejectsZeroRate() {
        val bytes = buildWav(samples = samples, sampleRate = 0, byteRate = 0)
        assertEquals(WavPcm16.DecodeResult.UnsupportedRate, result(bytes))
    }

    // -- Decode: empty ------------------------------------------------------

    @Test
    fun decodeRejectsZeroDataAsEmpty() {
        val bytes = buildWav(samples = shortArrayOf(), declaredDataSize = 0)
        assertEquals(WavPcm16.DecodeResult.Empty, result(bytes))
    }

    // -- Decode: bounds (no partial publication) ---------------------------

    @Test
    fun decodeRejectsPcmOverMaxPcmBytesAsTooLarge() {
        val bytes = buildWav(samples = samples) // 16 PCM bytes
        val tight = bounds.copy(maxPcmBytes = 15)
        assertEquals(WavPcm16.DecodeResult.TooLarge, result(bytes, tight))
    }

    @Test
    fun decodeRejectsDurationOverMaxAsTooLarge() {
        // 16 samples @ 8000 Hz decode to a 2ms duration
        val bytes = buildWav(samples = ShortArray(16), sampleRate = 8_000, byteRate = 8_000 * 2)
        // 2ms exceeds a 1ms cap -> too large (the bound must stay positive)
        val tight = bounds.copy(maxDurationMs = 1)
        assertEquals(WavPcm16.DecodeResult.TooLarge, result(bytes, tight))
    }

    @Test
    fun decodeAdmitsAtExactPcmBound() {
        val bytes = buildWav(samples = samples) // 16 PCM bytes
        val exact = bounds.copy(maxPcmBytes = 16)
        assertTrue(result(bytes, exact) is WavPcm16.DecodeResult.Decoded)
    }

    // -- helpers ------------------------------------------------------------

    private fun readLe16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun readLe32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun le16Bytes(samples: ShortArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (s in samples) writeLe16(out, s.toInt())
        return out.toByteArray()
    }

    private fun fmtChunk(
        audioFormat: Int = 1,
        channels: Int = 1,
        sampleRate: Int = 16_000,
        byteRate: Int = 32_000,
        blockAlign: Int = 2,
        bits: Int = 16,
    ): ByteArray {
        val b = ByteArrayOutputStream()
        writeLe16(b, audioFormat)
        writeLe16(b, channels)
        writeLe32(b, sampleRate)
        writeLe32(b, byteRate)
        writeLe16(b, blockAlign)
        writeLe16(b, bits)
        return b.toByteArray()
    }

    /** Assembles a RIFF/WAVE document from arbitrary chunks (for structural edge cases). */
    private fun riffWave(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((id, payload) in chunks) {
            body.write(id.toByteArray(Charsets.US_ASCII))
            writeLe32(body, payload.size)
            body.write(payload)
            if (payload.size % 2 != 0) body.write(0)
        }
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeLe32(out, 4 + body.size())
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        body.writeTo(out)
        return out.toByteArray()
    }
}
