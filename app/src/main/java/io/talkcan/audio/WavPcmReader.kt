package io.talkcan.audio

import java.io.File
import java.io.RandomAccessFile

/** Mono normalized PCM decoded from a WAV file, in `[-1.0, 1.0]`. */
data class WavDecoded(
    val sampleRate: Int,
    val samples: FloatArray,
)

/**
 * Typed outcome of decoding a WAV file to a mono normalized [FloatArray].
 *
 * Failure subtypes align with the renderer-infrastructure failure categories
 * defined by the navigation TTS engine: [UnsupportedEncoding],
 * [UnsupportedChannelCount], [EmptyPcm], and [MalformedHeader] are all
 * renderer/format-infrastructure failures that do not indicate a broken engine
 * or voice.
 */
sealed interface WavDecodeResult {
    /** Successful decode to a non-empty mono normalized [FloatArray]. */
    data class Success(val decoded: WavDecoded) : WavDecodeResult

    /**
     * The WAV encoding is not one of the supported formats (PCM 8-bit unsigned,
     * PCM 16-bit signed, or IEEE float). [formatTag] is the raw `wFormatTag`
     * from the `fmt` chunk; [bitsPerSample] is the declared bit depth.
     */
    data class UnsupportedEncoding(val formatTag: Int, val bitsPerSample: Int) : WavDecodeResult

    /**
     * The channel count is not 1 (mono) or 2 (stereo). No channel conversion is
     * attempted.
     */
    data class UnsupportedChannelCount(val channelCount: Int) : WavDecodeResult

    /** The PCM output is empty (file empty or zero samples after decode). */
    data object EmptyPcm : WavDecodeResult

    /** The WAV file could not be parsed or its header was malformed. */
    data object MalformedHeader : WavDecodeResult
}

data class WavInfo(
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val dataSize: Long,
    val durationMs: Long,
    val samples: ShortArray,
)

object WavPcmReader {
    /**
     * Decode a WAV file to a mono normalized [FloatArray] in `[-1.0, 1.0]` with
     * a typed [WavDecodeResult] distinguishing each failure category.
     *
     * Accepts `WAVE_FORMAT_PCM` (1) with 8-bit unsigned or 16-bit signed
     * samples and `WAVE_FORMAT_IEEE_FLOAT` (3) with 32-bit float samples.
     * Accepts mono or stereo (downmixed to mono by averaging left/right).
     * Returns [WavDecodeResult.MalformedHeader] for an unparseable header,
     * [WavDecodeResult.UnsupportedEncoding] for any other encoding or an
     * unsupported bit depth, [WavDecodeResult.UnsupportedChannelCount] for a
     * channel count other than 1 or 2, and [WavDecodeResult.EmptyPcm] for empty
     * PCM output.
     */
    fun readNormalizedResult(file: File): WavDecodeResult {
        val header = runCatching { parseHeader(file) }.getOrNull()
            ?: return WavDecodeResult.MalformedHeader
        val encoding = header.audioFormat
        val channels = header.channelCount
        if (channels != 1 && channels != 2) {
            return WavDecodeResult.UnsupportedChannelCount(channels)
        }
        if (header.sampleRate <= 0) {
            return WavDecodeResult.MalformedHeader
        }
        val bytesPerSample = header.bitsPerSample / 8
        if (bytesPerSample <= 0) {
            return WavDecodeResult.UnsupportedEncoding(encoding, header.bitsPerSample)
        }
        val encodingSupported = (encoding == WAVE_FORMAT_PCM && (header.bitsPerSample == 8 || header.bitsPerSample == 16)) ||
            (encoding == WAVE_FORMAT_IEEE_FLOAT && header.bitsPerSample == 32)
        if (!encodingSupported) {
            return WavDecodeResult.UnsupportedEncoding(encoding, header.bitsPerSample)
        }
        if (header.dataSize <= 0) {
            return WavDecodeResult.EmptyPcm
        }
        val frameSize = bytesPerSample * channels
        if (header.dataSize % frameSize != 0L) {
            return WavDecodeResult.MalformedHeader
        }
        val frameCount = (header.dataSize / frameSize).toInt()
        if (frameCount <= 0) {
            return WavDecodeResult.EmptyPcm
        }
        val out = runCatching {
            val byteBuf = ByteArray(frameCount * frameSize)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(header.dataOffset)
                raf.readFully(byteBuf)
            }
            decodeFrames(byteBuf, frameCount, frameSize, channels, encoding, header.bitsPerSample)
        }.getOrNull()
            ?: return WavDecodeResult.MalformedHeader
        if (out.isEmpty()) {
            return WavDecodeResult.EmptyPcm
        }
        return WavDecodeResult.Success(WavDecoded(header.sampleRate, out))
    }

    /**
     * Decode a WAV file to a mono normalized [FloatArray] in `[-1.0, 1.0]`.
     *
     * Convenience wrapper over [readNormalizedResult] mapping all failure
     * categories to `null`. Use [readNormalizedResult] when typed failure
     * classification is required.
     */
    fun readNormalized(file: File): WavDecoded? = runCatching {
        when (val r = readNormalizedResult(file)) {
            is WavDecodeResult.Success -> r.decoded
            else -> null
        }
    }.getOrNull()

    fun read(file: File): WavInfo? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val header = parseHeader(raf) ?: return null
            check(header.bitsPerSample == 16) { "Only 16-bit PCM supported" }
            check(header.dataSize > 0) { "No data chunk found" }

            val samplesCount = header.dataSize / 2
            val samples = ShortArray(samplesCount.toInt())
            raf.seek(header.dataOffset)
            val byteBuf = ByteArray((samplesCount * 2).toInt())
            raf.readFully(byteBuf)
            for (i in samples.indices) {
                val lo = byteBuf[i * 2].toInt() and 0xFF
                val hi = byteBuf[i * 2 + 1].toInt() shl 8
                samples[i] = (lo or hi).toShort()
            }

            val totalSamples = header.dataSize / (header.bitsPerSample / 8)
            val durationMs = (totalSamples * 1000) / header.sampleRate
            WavInfo(
                sampleRate = header.sampleRate,
                channelCount = header.channelCount,
                bitsPerSample = header.bitsPerSample,
                dataSize = header.dataSize,
                durationMs = durationMs,
                samples = samples,
            )
        }
    }.getOrNull()

    private const val WAVE_FORMAT_PCM = 1
    private const val WAVE_FORMAT_IEEE_FLOAT = 3

    private class WavHeader(
        val audioFormat: Int,
        val channelCount: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataSize: Long,
        val dataOffset: Long,
    )

    private fun parseHeader(file: File): WavHeader? =
        RandomAccessFile(file, "r").use { parseHeader(it) }

    private fun parseHeader(raf: RandomAccessFile): WavHeader? {
        val riff = raf.readBytes(4).decodeToString()
        if (riff != "RIFF") return null
        readInt32Le(raf)
        val wave = raf.readBytes(4).decodeToString()
        if (wave != "WAVE") return null

        var fmtChunkFound = false
        var audioFormat = 0
        var channelCount = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataSize = 0L
        var dataOffset = 0L

        while (raf.filePointer < raf.length()) {
            if (raf.length() - raf.filePointer < 8L) return null
            val chunkId = raf.readBytes(4).decodeToString()
            val chunkSize = readInt32Le(raf).toLong() and 0xFFFFFFFFL
            val chunkDataOffset = raf.filePointer
            if (chunkSize > raf.length() - chunkDataOffset) return null
            val chunkEnd = chunkDataOffset + chunkSize
            val paddedChunkEnd = chunkEnd + (chunkSize and 1L)
            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < 16L || paddedChunkEnd > raf.length()) return null
                    audioFormat = readInt16Le(raf)
                    channelCount = readInt16Le(raf)
                    sampleRate = readInt32Le(raf)
                    readInt32Le(raf) // byte rate
                    readInt16Le(raf) // block align
                    bitsPerSample = readInt16Le(raf)
                    fmtChunkFound = true
                    raf.seek(paddedChunkEnd)
                }
                "data" -> {
                    dataOffset = chunkDataOffset
                    dataSize = chunkSize
                    break
                }
                else -> {
                    if (paddedChunkEnd > raf.length()) return null
                    raf.seek(paddedChunkEnd)
                }
            }
        }
        if (sampleRate <= 0) return null
        if (!fmtChunkFound) return null
        return WavHeader(audioFormat, channelCount, sampleRate, bitsPerSample, dataSize, dataOffset)
    }

    private fun decodeFrames(
        byteBuf: ByteArray,
        frameCount: Int,
        frameSize: Int,
        channels: Int,
        encoding: Int,
        bitsPerSample: Int,
    ): FloatArray {
        val out = FloatArray(frameCount)
        when (encoding) {
            WAVE_FORMAT_PCM -> {
                when (bitsPerSample) {
                    8 -> {
                        // Unsigned 8-bit, centered around zero.
                        for (i in 0 until frameCount) {
                            var acc = 0.0f
                            for (c in 0 until channels) {
                                val raw = byteBuf[i * frameSize + c].toInt() and 0xFF
                                acc += (raw - 128) / 128.0f
                            }
                            out[i] = acc / channels
                        }
                    }
                    16 -> {
                        for (i in 0 until frameCount) {
                            var acc = 0.0f
                            for (c in 0 until channels) {
                                val base = i * frameSize + c * 2
                                val lo = byteBuf[base].toInt() and 0xFF
                                val hi = byteBuf[base + 1].toInt() shl 8
                                val s = (lo or hi).toShort().toInt()
                                acc += s / 32768.0f
                            }
                            out[i] = acc / channels
                        }
                    }
                    else -> return FloatArray(0)
                }
            }
            WAVE_FORMAT_IEEE_FLOAT -> {
                if (bitsPerSample != 32) return FloatArray(0)
                for (i in 0 until frameCount) {
                    var acc = 0.0f
                    for (c in 0 until channels) {
                        val base = i * frameSize + c * 4
                        val bits = (byteBuf[base].toInt() and 0xFF) or
                            ((byteBuf[base + 1].toInt() and 0xFF) shl 8) or
                            ((byteBuf[base + 2].toInt() and 0xFF) shl 16) or
                            ((byteBuf[base + 3].toInt() and 0xFF) shl 24)
                        val f = Float.fromBits(bits).coerceIn(-1.0f, 1.0f)
                        acc += f
                    }
                    out[i] = acc / channels
                }
            }
            else -> return FloatArray(0)
        }
        return out
    }

    private fun readInt16Le(raf: RandomAccessFile): Int {
        val lo = raf.readUnsignedByte()
        val hi = raf.readUnsignedByte()
        return (hi shl 8) or lo
    }

    private fun readInt32Le(raf: RandomAccessFile): Int {
        val b0 = raf.readUnsignedByte()
        val b1 = raf.readUnsignedByte()
        val b2 = raf.readUnsignedByte()
        val b3 = raf.readUnsignedByte()
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    private fun RandomAccessFile.readBytes(count: Int): ByteArray {
        val buf = ByteArray(count)
        readFully(buf)
        return buf
    }
}