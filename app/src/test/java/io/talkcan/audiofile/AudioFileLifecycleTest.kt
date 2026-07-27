package io.talkcan.audiofile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6.8: Operation admission, transfer accounting, and the exact-once terminal gate.
 *
 * Proves active-slot exhaustion (E_BUSY), generation/process transfer-byte exhaustion (E_TOO_LARGE)
 * with generation-only reset and reserve-to-actual reconciliation, reservation release on failed
 * transfer, the exact-once terminal gate (first cause wins, late completions discarded), and
 * mount-lease rejection (foreign/revoked/stale-generation) before admission, plus cooperative
 * cancellation releasing the slot and rethrowing.
 */
class AudioFileLifecycleTest {

    private val samples = shortArrayOf(0, 1, -1, 32767, -32768, 2, -2, 3) // 8 samples -> 60-byte WAV
    private val rate = 16_000
    private val wavNew = AudioExportOptions(AudioFileFormat.WAV_PCM_S16LE, AudioExportMode.CREATE_NEW)
    private val wavOptions = AudioOpenOptions(AudioFileFormat.WAV_PCM_S16LE)

    private fun code(o: AudioFileOutcome<*>): AudioFileErrorCode = o.audioFailure().code
    private fun pcm() = PcmMonoS16Le(samples, rate)

    // -- Active-operation slot exhaustion ----------------------------------

    @Test
    fun activeOperationExhaustionRejectsBusy() = runTest {
        val h = AudioFileHarness(limits = AudioFileLimits(maxActiveOperations = 1))
        h.mem.seedFile(listOf("a.wav"), buildWav(samples = samples))
        val gate = CompletableDeferred<Unit>()
        h.gated.readGate = gate
        val handle = h.handle()
        val first = launch { h.adapter.open(h.owner, handle, "a.wav", wavOptions) }
        yield() // first op admits the only slot, suspends at the read gate
        assertEquals(1, h.adapter.operations.activeCount)
        val second = h.adapter.open(h.owner, handle, "a.wav", wavOptions)
        assertEquals(AudioFileErrorCode.E_BUSY, code(second))
        assertEquals(1, h.adapter.operations.activeCount) // first still holds its slot
        gate.complete(Unit)
        first.cancelAndJoin()
        assertEquals(0, h.adapter.operations.activeCount)
    }

    // -- Transfer-byte exhaustion ------------------------------------------

    @Test
    fun generationTransferExhaustionRejectsTooLargeWithoutEffect() = runTest {
        val h = AudioFileHarness(limits = AudioFileLimits(maxGenerationTransferBytes = 100, maxProcessTransferBytes = 1_000_000))
        val rec = h.recording(pcm())
        h.adapter.export(h.owner, rec, h.handle(), "a.wav", wavNew).audioSuccess() // 60 bytes
        assertEquals(60L, h.adapter.operations.generationTransferBytes)
        val second = h.adapter.export(h.owner, rec, h.handle(), "b.wav", wavNew) // 60 + 60 > 100
        assertEquals(AudioFileErrorCode.E_TOO_LARGE, code(second))
        assertFalse(h.mem.exists(listOf("b.wav"))) // rejected before any provider effect
        assertEquals(60L, h.adapter.operations.generationTransferBytes) // reservation released
    }

    @Test
    fun processTransferExhaustionSurvivesGenerationReset() = runTest {
        val h = AudioFileHarness(limits = AudioFileLimits(maxGenerationTransferBytes = 1_000_000, maxProcessTransferBytes = 100))
        val rec = h.recording(pcm())
        h.adapter.export(h.owner, rec, h.handle(), "a.wav", wavNew).audioSuccess() // proc = 60
        h.adapter.advanceGeneration(2) // resets generation budget, never the process budget
        assertEquals(0L, h.adapter.operations.generationTransferBytes)
        assertEquals(60L, h.adapter.operations.processTransferBytes)
        val second = h.adapter.export(h.owner, rec, h.handle(), "b.wav", wavNew) // proc 60 + 60 > 100
        assertEquals(AudioFileErrorCode.E_TOO_LARGE, code(second))
    }

    @Test
    fun openReserveReconcilesDownToActualTransfer() = runTest {
        // open reserves maxArtifactBytes (100) up front but only ~60 bytes actually transfer.
        val h = AudioFileHarness(
            bounds = AudioArtifactBounds(maxArtifactBytes = 100),
            limits = AudioFileLimits(maxGenerationTransferBytes = 121, maxProcessTransferBytes = 1_000_000),
        )
        h.mem.seedFile(listOf("a.wav"), buildWav(samples = samples)) // 60-byte WAV
        h.adapter.open(h.owner, h.handle(), "a.wav", wavOptions).audioSuccess()
        assertEquals(60L, h.adapter.operations.generationTransferBytes) // reconciled from the 100 reserve
        // A 60-byte export now fits (60 + 60 = 120 <= 121); it would not if the 100-byte reserve were
        // still held, proving the reserve was reconciled down to the actual transfer.
        val rec = h.recording(pcm())
        val written = h.adapter.export(h.owner, rec, h.handle(), "out.wav", wavNew)
        assertTrue(written is AudioFileOutcome.Success<*>)
    }

    @Test
    fun failedTransferReleasesItsReservation() = runTest {
        val h = AudioFileHarness(limits = AudioFileLimits(maxGenerationTransferBytes = 100, maxProcessTransferBytes = 1_000_000))
        h.mem.writeFailureDuringPublish = io.talkcan.storage.BackendFailure.NO_SPACE
        val rec = h.recording(pcm())
        val result = h.adapter.export(h.owner, rec, h.handle(), "a.wav", wavNew)
        assertEquals(AudioFileErrorCode.E_NO_SPACE, code(result))
        assertEquals(0L, h.adapter.operations.generationTransferBytes) // reserved 60, transferred none
        assertEquals(0, h.adapter.operations.activeCount)
    }

    // -- Cancellation -------------------------------------------------------

    @Test
    fun cancellationReleasesReservationAndRethrows() = runTest {
        val h = AudioFileHarness()
        h.mem.seedFile(listOf("a.wav"), buildWav(samples = samples))
        val gate = CompletableDeferred<Unit>()
        h.gated.readGate = gate
        val handle = h.handle()
        val job = launch { h.adapter.open(h.owner, handle, "a.wav", wavOptions) }
        yield() // op admits its slot and suspends at the read gate
        assertEquals(1, h.adapter.operations.activeCount)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, h.adapter.operations.activeCount) // terminal gate released the slot
        assertEquals(0, h.recordings.liveCount()) // no Recording published on cancellation
    }

    // -- Mount-lease rejection before admission -----------------------------

    @Test
    fun foreignLeaseIsRejectedBeforeAdmission() = runTest {
        val h = AudioFileHarness()
        val other = AudioFileHarness()
        val foreignHandle = other.handle()
        h.mem.seedFile(listOf("a.wav"), buildWav(samples = samples))
        val result = h.adapter.open(h.owner, foreignHandle, "a.wav", wavOptions)
        assertEquals(AudioFileErrorCode.E_STALE, code(result))
        assertEquals(0, h.adapter.operations.activeCount) // never admitted
    }

    @Test
    fun revokedHandleIsRejectedBeforeAnyProviderEffect() = runTest {
        val h = AudioFileHarness()
        h.mem.seedFile(listOf("a.wav"), buildWav(samples = samples))
        val handle = h.handle()
        h.registry.revokeAll(io.talkcan.storage.MountRevocationSource.GRANT_REVOKED)
        val result = h.adapter.open(h.owner, handle, "a.wav", wavOptions)
        assertEquals(AudioFileErrorCode.E_REAUTHORIZATION_REQUIRED, code(result))
        assertEquals(0, h.recordings.liveCount())
        assertEquals(0, h.adapter.operations.activeCount)
    }

    @Test
    fun advanceGenerationInvalidatesPredecessorHandle() = runTest {
        val h = AudioFileHarness()
        h.mem.seedFile(listOf("a.wav"), buildWav(samples = samples))
        val predecessor = h.handle()
        h.registry.advanceGeneration(2)
        val stale = h.adapter.open(h.owner, predecessor, "a.wav", wavOptions)
        assertEquals(AudioFileErrorCode.E_CLOSED, code(stale))
        // A freshly minted handle under the new generation succeeds.
        assertTrue(h.adapter.open(h.owner, h.handle(), "a.wav", wavOptions) is AudioFileOutcome.Success<*>)
    }

    // -- Exact-once terminal gate (unit) ------------------------------------

    @Test
    fun terminalGateIsExactOnceAndDiscardsLateCompletions() {
        val ledger = AudioFileLedger(AudioFileLimits())
        val reservation = (ledger.tryAdmit(100) as AudioAdmission.Admitted).reservation
        assertEquals(1, ledger.activeCount)
        assertEquals(100L, ledger.generationTransferBytes)
        assertTrue(reservation.terminate(AudioTerminalCause.TIMEOUT))
        assertEquals(0, ledger.activeCount)
        assertEquals(0L, ledger.generationTransferBytes) // a timeout transfers nothing
        // A late "success" completion is discarded: no double release, no byte accounting.
        assertFalse(reservation.terminate(AudioTerminalCause.SUCCESS, actualBytes = 100))
        assertEquals(0, ledger.activeCount)
        assertEquals(0L, ledger.generationTransferBytes)
        assertEquals(AudioTerminalCause.TIMEOUT, reservation.cause) // first cause wins
    }

    @Test
    fun successTerminationAccountsActualBytes() {
        val ledger = AudioFileLedger(AudioFileLimits())
        val reservation = (ledger.tryAdmit(100) as AudioAdmission.Admitted).reservation
        assertEquals(100L, ledger.generationTransferBytes) // reserved up front
        assertTrue(reservation.terminate(AudioTerminalCause.SUCCESS, actualBytes = 7))
        assertEquals(0, ledger.activeCount)
        assertEquals(7L, ledger.generationTransferBytes) // reconciled to the actual transfer
        assertEquals(7L, ledger.processTransferBytes)
    }

    @Test
    fun admissionRejectsWhenActiveSlotsExhausted() {
        val ledger = AudioFileLedger(AudioFileLimits(maxActiveOperations = 1))
        assertTrue(ledger.tryAdmit(0) is AudioAdmission.Admitted)
        val rejected = ledger.tryAdmit(0)
        assertTrue(rejected is AudioAdmission.Rejected)
        assertEquals(AudioFileErrorCode.E_BUSY, (rejected as AudioAdmission.Rejected).error.code)
    }
}
