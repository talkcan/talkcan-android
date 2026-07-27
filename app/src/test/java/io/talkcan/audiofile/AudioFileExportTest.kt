package io.talkcan.audiofile

import io.talkcan.storage.BackendFailure
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6.6: Recording export.
 *
 * WAV/PCM export borrows (never consumes) the Recording and publishes only a complete authorized
 * destination under create-new/replace semantics with exact metadata/bytes.
 */
class AudioFileExportTest {

    private val samples = shortArrayOf(0, 1, -1, 32767, -32768, 2, -2, 3)
    private val rate = 16_000
    private val wavNew = AudioExportOptions(AudioFileFormat.WAV_PCM_S16LE, AudioExportMode.CREATE_NEW)
    private val wavReplace = AudioExportOptions(AudioFileFormat.WAV_PCM_S16LE, AudioExportMode.REPLACE)

    private fun code(o: AudioFileOutcome<*>): AudioFileErrorCode = o.audioFailure().code

    private fun pcm() = PcmMonoS16Le(samples, rate)

    // -- WAV export (6.6) ---------------------------------------------------

    @Test
    fun exportWavCreateNewWritesExactCompleteDestination() = runTest {
        val h = AudioFileHarness()
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "capture.wav", wavNew).audioSuccess()

        val expected = WavPcm16.encode(pcm())
        assertArrayEquals(expected, h.mem.fileContent(listOf("capture.wav")))

        assertEquals(AudioExportStatus.WRITTEN, result.status)
        assertEquals(AudioFileFormat.WAV_PCM_S16LE, result.format)
        assertEquals(rate, result.sampleRate)
        assertEquals(1, result.channels)
        assertEquals(samples.size.toLong() * 1000 / rate, result.durationMs)
        assertEquals(expected.size.toLong(), result.bytes)
    }

    @Test
    fun exportWavBorrowsAndDoesNotConsume() = runTest {
        val h = AudioFileHarness()
        val rec = h.recording(pcm())
        h.adapter.export(h.owner, rec, h.handle(), "a.wav", wavNew).audioSuccess()
        assertEquals(1, h.recordings.liveCount()) // still live after export
        // A second export of the same borrowed Recording succeeds (replace to reuse the path).
        h.adapter.export(h.owner, rec, h.handle(), "a.wav", wavReplace).audioSuccess()
        assertEquals(1, h.recordings.liveCount())
    }

    @Test
    fun exportWavReplaceOverwritesExistingDestination() = runTest {
        val h = AudioFileHarness()
        h.mem.seedFile(listOf("capture.wav"), byteArrayOf(9, 9, 9))
        val rec = h.recording(pcm())
        h.adapter.export(h.owner, rec, h.handle(), "capture.wav", wavReplace).audioSuccess()
        assertArrayEquals(WavPcm16.encode(pcm()), h.mem.fileContent(listOf("capture.wav")))
    }

    @Test
    fun exportWavCreateNewOnExistingIsExists() = runTest {
        val h = AudioFileHarness()
        val original = byteArrayOf(9, 9, 9)
        h.mem.seedFile(listOf("capture.wav"), original)
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "capture.wav", wavNew)
        assertEquals(AudioFileErrorCode.E_EXISTS, code(result))
        assertArrayEquals(original, h.mem.fileContent(listOf("capture.wav"))) // unchanged
    }

    @Test
    fun exportWavToDirectoryIsInvalidValue() = runTest {
        val h = AudioFileHarness()
        h.mem.seedDir(listOf("capture.wav"))
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "capture.wav", wavNew)
        assertEquals(AudioFileErrorCode.E_INVALID_VALUE, code(result))
    }

    @Test
    fun exportWavMissingParentIsNotFound() = runTest {
        val h = AudioFileHarness()
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "nodir/capture.wav", wavNew)
        assertEquals(AudioFileErrorCode.E_NOT_FOUND, code(result))
    }

    @Test
    fun exportWavAbsolutePathIsInvalidPath() = runTest {
        val h = AudioFileHarness()
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "/capture.wav", wavNew)
        assertEquals(AudioFileErrorCode.E_INVALID_PATH, code(result))
    }

    @Test
    fun exportWavProviderFailurePublishesNothing() = runTest {
        val h = AudioFileHarness()
        h.mem.writeFailureDuringPublish = BackendFailure.NO_SPACE
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "capture.wav", wavNew)
        assertEquals(AudioFileErrorCode.E_NO_SPACE, code(result))
        assertFalse(h.mem.exists(listOf("capture.wav"))) // complete-on-success: no partial node
    }

    @Test
    fun exportWavThrownFailureLeaksNoPlatformDetail() = runTest {
        val h = AudioFileHarness()
        h.mem.throwOn = "writeFile"
        val rec = h.recording(pcm())
        val error = h.adapter.export(h.owner, rec, h.handle(), "capture.wav", wavNew).audioFailure()
        assertEquals(AudioFileErrorCode.E_IO, error.code)
        val reason = error.reason ?: ""
        assertTrue(reason, !reason.contains("content://"))
        assertTrue(reason, !reason.contains("/storage"))
        assertTrue(reason, !reason.contains("SAF"))
    }


    // -- Ownership: export borrows the current execution's Recording --------

    @Test
    fun exportForeignRecordingIsInvalidArgument() = runTest {
        val h = AudioFileHarness()
        val rec = h.recording(pcm()) // owned by h.owner
        val result = h.adapter.export(h.taskOwner, rec, h.handle(), "capture.wav", wavNew)
        assertEquals(AudioFileErrorCode.E_INVALID_ARGUMENT, code(result))
    }

    @Test
    fun exportStaleRecordingIsStale() = runTest {
        val h = AudioFileHarness()
        val rec = h.recording(pcm(), h.owner)
        val futureOwner = ExecutionOwner("input-1", ExecutionOwnerKind.INPUT, generation = 99)
        val result = h.adapter.export(futureOwner, rec, h.handle(), "capture.wav", wavNew)
        assertEquals(AudioFileErrorCode.E_STALE, code(result))
    }
}
