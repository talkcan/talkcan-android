package io.talkcan.audiofile

/**
 * 6.5/6.6: The strict bounded mono PCM S16LE WAV codec for the generic audio-file capability.
 *
 * [decode] validates a complete RIFF/WAVE container and admits only PCM (format tag 1) mono 16-bit
 * little-endian audio at a supported sample rate within the artifact/PCM/duration bounds. It walks
 * chunks, honors RIFF odd-size padding on unknown chunks, rejects inconsistent derived fields and
 * truncated/incomplete data, and never publishes a partial Recording: the sample array is allocated
 * only after every bound and completeness check passes. [encode] produces the deterministic
 * canonical 44-byte-header PCM16 LE document, byte-for-byte compatible with the host's existing
 * WAV writer/reader. No raw PCM, path, or diagnostic escapes this object.
 */
internal object WavPcm16 {

    /** The precise outcome of a strict bounded decode, before mapping to [AudioFileErrorCode]. */
    sealed interface DecodeResult {
        data class Decoded(val pcm: PcmMonoS16Le) : DecodeResult
        data object MalformedHeader : DecodeResult
        data object Truncated : DecodeResult
        data object UnsupportedEncoding : DecodeResult
        data object UnsupportedChannels : DecodeResult
        data object UnsupportedRate : DecodeResult
        data object InconsistentLength : DecodeResult
        data object TooLarge : DecodeResult
        data object Empty : DecodeResult
    }

    private const val HEADER_SIZE = 44
    private const val PCM_FORMAT_TAG = 1
    private const val MONO = 1
    private const val BITS_PER_SAMPLE = 16
    private const val BLOCK_ALIGN = 2

    fun decode(bytes: ByteArray, bounds: AudioArtifactBounds): DecodeResult {
        if (bytes.size < 12) return DecodeResult.MalformedHeader
        if (!fourCc(bytes, 0, 'R', 'I', 'F', 'F')) return DecodeResult.MalformedHeader
        if (!fourCc(bytes, 8, 'W', 'A', 'V', 'E')) return DecodeResult.MalformedHeader

        var pos = 12
        var fmtSeen = false
        var audioFormat = 0
        var channels = 0
        var sampleRate = 0
        var byteRate = 0
        var blockAlign = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = -1

        while (pos + 8 <= bytes.size) {
            val chunkSize = readInt32Le(bytes, pos + 4)
            if (chunkSize < 0) return DecodeResult.MalformedHeader
            val body = pos + 8
            // A chunk whose declared body runs past the buffer is a truncated container. This also
            // rejects an inconsistent `data` length before any sample is read.
            if (body.toLong() + chunkSize.toLong() > bytes.size.toLong()) return DecodeResult.Truncated
            when {
                fourCc(bytes, pos, 'f', 'm', 't', ' ') -> {
                    if (chunkSize < 16) return DecodeResult.MalformedHeader
                    audioFormat = readInt16Le(bytes, body)
                    channels = readInt16Le(bytes, body + 2)
                    sampleRate = readInt32Le(bytes, body + 4)
                    byteRate = readInt32Le(bytes, body + 8)
                    blockAlign = readInt16Le(bytes, body + 12)
                    bitsPerSample = readInt16Le(bytes, body + 14)
                    fmtSeen = true
                }
                fourCc(bytes, pos, 'd', 'a', 't', 'a') -> {
                    if (dataOffset < 0) {
                        dataOffset = body
                        dataSize = chunkSize
                    }
                }
                // Unknown chunks are skipped, honoring the RIFF pad byte on odd sizes.
            }
            pos = body + chunkSize + (chunkSize and 1)
        }

        if (!fmtSeen) return DecodeResult.MalformedHeader
        if (dataOffset < 0) return DecodeResult.MalformedHeader

        if (audioFormat != PCM_FORMAT_TAG) return DecodeResult.UnsupportedEncoding
        if (bitsPerSample != BITS_PER_SAMPLE) return DecodeResult.UnsupportedEncoding
        if (channels != MONO) return DecodeResult.UnsupportedChannels
        if (sampleRate <= 0) return DecodeResult.UnsupportedRate
        if (sampleRate !in bounds.supportedSampleRates) return DecodeResult.UnsupportedRate
        if (blockAlign != BLOCK_ALIGN) return DecodeResult.InconsistentLength
        if (byteRate != sampleRate * 2) return DecodeResult.InconsistentLength

        if (dataSize < 0) return DecodeResult.MalformedHeader
        if (dataSize % 2 != 0) return DecodeResult.InconsistentLength
        if (dataSize == 0) return DecodeResult.Empty
        if (dataSize.toLong() > bounds.maxPcmBytes) return DecodeResult.TooLarge

        val sampleCount = dataSize / 2
        val durationMs = sampleCount.toLong() * 1000L / sampleRate.toLong()
        if (durationMs > bounds.maxDurationMs) return DecodeResult.TooLarge

        // Bounds and completeness are proven; allocate and unpack exactly once (no partial publish).
        val samples = ShortArray(sampleCount)
        var p = dataOffset
        for (i in 0 until sampleCount) {
            val lo = bytes[p].toInt() and 0xFF
            val hi = (bytes[p + 1].toInt() and 0xFF) shl 8
            samples[i] = (lo or hi).toShort()
            p += 2
        }
        return DecodeResult.Decoded(PcmMonoS16Le(samples, sampleRate))
    }

    /**
     * Encodes [pcm] as the deterministic canonical mono PCM16 LE WAV: a 44-byte RIFF/WAVE/fmt/data
     * header followed by little-endian S16 samples. Byte-for-byte identical to the host's existing
     * WAV writer output for the same rate and samples.
     */
    fun encode(pcm: PcmMonoS16Le): ByteArray {
        val dataSize = pcm.samples.size * 2
        val out = ByteArray(HEADER_SIZE + dataSize)
        writeFourCc(out, 0, "RIFF")
        writeInt32Le(out, 4, 36 + dataSize)
        writeFourCc(out, 8, "WAVE")
        writeFourCc(out, 12, "fmt ")
        writeInt32Le(out, 16, 16)
        writeInt16Le(out, 20, PCM_FORMAT_TAG)
        writeInt16Le(out, 22, MONO)
        writeInt32Le(out, 24, pcm.sampleRate)
        writeInt32Le(out, 28, pcm.sampleRate * 2)
        writeInt16Le(out, 32, BLOCK_ALIGN)
        writeInt16Le(out, 34, BITS_PER_SAMPLE)
        writeFourCc(out, 36, "data")
        writeInt32Le(out, 40, dataSize)
        var p = HEADER_SIZE
        for (sample in pcm.samples) {
            val v = sample.toInt()
            out[p] = (v and 0xFF).toByte()
            out[p + 1] = ((v shr 8) and 0xFF).toByte()
            p += 2
        }
        return out
    }

    private fun fourCc(bytes: ByteArray, offset: Int, a: Char, b: Char, c: Char, d: Char): Boolean {
        if (offset + 4 > bytes.size) return false
        return bytes[offset] == a.code.toByte() &&
            bytes[offset + 1] == b.code.toByte() &&
            bytes[offset + 2] == c.code.toByte() &&
            bytes[offset + 3] == d.code.toByte()
    }

    private fun readInt16Le(bytes: ByteArray, offset: Int): Int {
        val lo = bytes[offset].toInt() and 0xFF
        val hi = (bytes[offset + 1].toInt() and 0xFF) shl 8
        return lo or hi
    }

    private fun readInt32Le(bytes: ByteArray, offset: Int): Int {
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = (bytes[offset + 1].toInt() and 0xFF) shl 8
        val b2 = (bytes[offset + 2].toInt() and 0xFF) shl 16
        val b3 = (bytes[offset + 3].toInt() and 0xFF) shl 24
        return b0 or b1 or b2 or b3
    }

    private fun writeFourCc(out: ByteArray, offset: Int, value: String) {
        out[offset] = value[0].code.toByte()
        out[offset + 1] = value[1].code.toByte()
        out[offset + 2] = value[2].code.toByte()
        out[offset + 3] = value[3].code.toByte()
    }

    private fun writeInt16Le(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeInt32Le(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value shr 8) and 0xFF).toByte()
        out[offset + 2] = ((value shr 16) and 0xFF).toByte()
        out[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
