package io.talkcan.audio

import java.io.File
import java.io.RandomAccessFile

class JournalWavWriter(
    targetFile: File,
    private val sampleRate: Int,
) {
    private val lock = Any()
    @Volatile private var closed: Boolean = false
    private val raf: RandomAccessFile = RandomAccessFile(targetFile, "rw").also {
        writeWavHeader(it, sampleRate, dataSize = 0)
    }

    fun writeChunk(chunk: ShortArray) {
        synchronized(lock) {
            if (closed) return
            val file = raf
            val bufferBytes = ByteArray(chunk.size * 2)
            for (i in chunk.indices) {
                val v = chunk[i].toInt()
                bufferBytes[i * 2] = (v and 0xFF).toByte()
                bufferBytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            file.write(bufferBytes)
        }
    }

    fun finalize() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val file = raf
            runCatching {
                val dataSize = file.length() - HEADER_SIZE
                if (dataSize < 0) return@runCatching
                file.seek(4)
                writeInt32Le(file, (HEADER_SIZE + dataSize - 8).toInt())
                file.seek(40)
                writeInt32Le(file, dataSize.toInt())
                file.close()
            }
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { raf.close() }
        }
    }

    companion object {
        private const val HEADER_SIZE = 44

        private fun writeWavHeader(file: RandomAccessFile, sampleRate: Int, dataSize: Int) {
            file.writeBytes("RIFF")
            writeInt32Le(file, 36 + dataSize)
            file.writeBytes("WAVE")
            file.writeBytes("fmt ")
            writeInt32Le(file, 16)
            writeInt16Le(file, 1)
            writeInt16Le(file, 1)
            writeInt32Le(file, sampleRate)
            writeInt32Le(file, sampleRate * 2)
            writeInt16Le(file, 2)
            writeInt16Le(file, 16)
            file.writeBytes("data")
            writeInt32Le(file, dataSize)
        }

        private fun writeInt16Le(file: RandomAccessFile, value: Int) {
            file.writeByte(value and 0xFF)
            file.writeByte((value shr 8) and 0xFF)
        }

        private fun writeInt32Le(file: RandomAccessFile, value: Int) {
            file.writeByte(value and 0xFF)
            file.writeByte((value shr 8) and 0xFF)
            file.writeByte((value shr 16) and 0xFF)
            file.writeByte((value shr 24) and 0xFF)
        }
    }
}