package io.talkcan.storage

import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounded admission and exact-once terminal-gate tests (task 4.12): count/byte/page exhaustion,
 * timeout/cancel/close/revocation races, late-completion suppression, foreign-lease rejection, and
 * cleanup. Deterministic: concurrency uses virtual time and explicit gates, never real sleeps.
 */
class MountedFilesystemLifecycleTest {

    /** A delegating backend that can delay reads/writes (virtual time) or gate reads on a deferred. */
    private class TestBackend(val inner: InMemoryDocumentTreeBackend) : DocumentTreeBackend by inner {
        val rootRef: NodeRef get() = inner.rootRef
        var readFileDelayMs: Long = 0
        var writeFileDelayMs: Long = 0
        var readGate: CompletableDeferred<Unit>? = null

        override suspend fun readFile(node: NodeRef, maxBytes: Long): BackendResult<ByteArray> {
            if (readFileDelayMs > 0) delay(readFileDelayMs)
            readGate?.await()
            return inner.readFile(node, maxBytes)
        }

        override suspend fun writeFile(
            parent: NodeRef,
            name: String,
            bytes: ByteArray,
            mode: BackendWriteMode,
        ): BackendResult<NodeRef> {
            if (writeFileDelayMs > 0) delay(writeFileDelayMs)
            return inner.writeFile(parent, name, bytes, mode)
        }
    }

    private class Harness(
        limits: VfsLimits = VfsLimits(),
        policy: VfsPolicy = VfsPolicy(),
        generation: Long = 1,
        owner: LeaseOwner = LeaseOwner("state-1", "instance-1", generation),
        revalidator: MountLeaseRevalidator = MountLeaseRevalidator { FilesystemOutcome.Success(Unit) },
    ) {
        val backend = TestBackend(InMemoryDocumentTreeBackend())

        private fun resolved(gen: Long) = ResolvedMount(
            mountToken = "mount-token",
            declarationId = "output",
            generation = gen,
            access = MountAccessMode.READ_WRITE,
            grantFingerprint = "grant-fp",
            backend = backend,
            root = backend.rootRef,
        )

        val resolver = MutableResolver(resolved(generation))
        val registry = MountLeaseRegistry(owner, resolver, revalidator)
        val fs = MountedFilesystem(registry, policy, limits)

        fun handle(): MountHandle = (fs.mount("output") as FilesystemOutcome.Success).value

        fun advanceGeneration(gen: Long) {
            resolver.mount = resolved(gen)
            fs.advanceGeneration(gen)
        }
    }

    private fun bytes(s: String): ByteArray = s.toByteArray(StandardCharsets.UTF_8)

    private fun code(outcome: FilesystemOutcome<*>): FilesystemErrorCode =
        (outcome as FilesystemOutcome.Failure).error.code

    // -- Active-operation count exhaustion ---------------------------------

    @Test
    fun activeOperationExhaustionRejectsBusy() = runTest {
        val h = Harness(limits = VfsLimits(maxActiveOperations = 1))
        h.backend.inner.seedFile(listOf("f.txt"), bytes("hello"))
        h.backend.readFileDelayMs = 10_000
        val handle = h.handle()
        val first = launch { h.fs.readText(handle, "f.txt", ReadTextOptions(100)) }
        yield() // first op admits the only slot, then suspends in the read delay
        assertEquals(1, h.fs.operations.activeCount)
        val second = h.fs.readText(handle, "f.txt", ReadTextOptions(100))
        assertEquals(FilesystemErrorCode.E_BUSY, code(second))
        assertEquals(1, h.fs.operations.activeCount) // first still holds its slot
        first.cancelAndJoin()
        assertEquals(0, h.fs.operations.activeCount)
    }

    // -- Transfer-byte exhaustion ------------------------------------------

    @Test
    fun generationByteExhaustionRejectsTooLargeWithoutEffect() = runTest {
        val h = Harness(limits = VfsLimits(maxGenerationTransferBytes = 10, maxProcessTransferBytes = 1_000_000))
        h.fs.writeText(h.handle(), "a.txt", "aaaaaa", WriteTextOptions(WriteMode.CREATE_NEW)).success() // 6 bytes
        assertEquals(6L, h.fs.operations.generationTransferBytes)
        val second = h.fs.writeText(h.handle(), "b.txt", "bbbbbb", WriteTextOptions(WriteMode.CREATE_NEW)) // 6+6>10
        assertEquals(FilesystemErrorCode.E_TOO_LARGE, code(second))
        assertFalse(h.backend.inner.exists(listOf("b.txt"))) // rejected before any provider effect
        assertEquals(6L, h.fs.operations.generationTransferBytes) // reservation released
    }

    @Test
    fun processByteExhaustionSurvivesGenerationReset() = runTest {
        val h = Harness(limits = VfsLimits(maxGenerationTransferBytes = 1_000_000, maxProcessTransferBytes = 10))
        h.fs.writeText(h.handle(), "a.txt", "aaaaaa", WriteTextOptions(WriteMode.CREATE_NEW)).success() // proc=6
        h.advanceGeneration(2) // resets the generation budget, never the process budget
        assertEquals(0L, h.fs.operations.generationTransferBytes)
        assertEquals(6L, h.fs.operations.processTransferBytes)
        h.fs.writeText(h.handle(), "b.txt", "bbbb", WriteTextOptions(WriteMode.CREATE_NEW)).success() // proc=10
        assertEquals(10L, h.fs.operations.processTransferBytes)
        val third = h.fs.writeText(h.handle(), "c.txt", "c", WriteTextOptions(WriteMode.CREATE_NEW)) // 10+1>10
        assertEquals(FilesystemErrorCode.E_TOO_LARGE, code(third))
    }

    @Test
    fun transferBudgetReconcilesReservedToActualBytes() = runTest {
        val h = Harness(limits = VfsLimits(maxGenerationTransferBytes = 201, maxProcessTransferBytes = 1_000_000))
        h.backend.inner.seedFile(listOf("small.txt"), bytes("hello")) // 5 bytes
        // Reserves maxBytes=100 up front, but only 5 bytes actually transfer.
        h.fs.readText(h.handle(), "small.txt", ReadTextOptions(100)).success()
        assertEquals(5L, h.fs.operations.generationTransferBytes)
        // A 196-byte write now fits (5 + 196 = 201 <= 201); it would not if the 100-byte reserve
        // were still held, proving the reserve was reconciled down to the actual transfer.
        val written = h.fs.writeText(h.handle(), "out.txt", "x".repeat(196), WriteTextOptions(WriteMode.CREATE_NEW))
        assertTrue(written is FilesystemOutcome.Success)
    }

    @Test
    fun failedTransferReleasesItsReservation() = runTest {
        val h = Harness(limits = VfsLimits(maxGenerationTransferBytes = 100, maxProcessTransferBytes = 1_000_000))
        h.backend.inner.seedFile(listOf("day.md"), bytes("original"))
        h.backend.inner.writeFailureDuringPublish = BackendFailure.NO_SPACE
        val result = h.fs.writeText(h.handle(), "day.md", "replacement", WriteTextOptions(WriteMode.REPLACE))
        assertEquals(FilesystemErrorCode.E_NO_SPACE, code(result))
        // Reserved 11 bytes ("replacement") but transferred none: the budget is fully released.
        assertEquals(0L, h.fs.operations.generationTransferBytes)
        assertEquals(0, h.fs.operations.activeCount)
    }

    // -- Pagination exhaustion ---------------------------------------------

    @Test
    fun listPageExhaustionRejectsTooLarge() = runTest {
        val h = Harness(limits = VfsLimits(maxListPagesPerSession = 2))
        for (i in 0 until 5) h.backend.inner.seedFile(listOf("d", "f$i.txt"), bytes("x"))
        val handle = h.handle()
        val page1 = h.fs.list(handle, "d", ListOptions(limit = 1)).success()
        val page2 = h.fs.list(handle, "d", ListOptions(limit = 1, cursor = page1.nextCursor)).success()
        val page3 = h.fs.list(handle, "d", ListOptions(limit = 1, cursor = page2.nextCursor))
        assertEquals(FilesystemErrorCode.E_TOO_LARGE, code(page3))
    }

    @Test
    fun liveCursorExhaustionRejectsTooLarge() = runTest {
        val h = Harness(limits = VfsLimits(maxLiveListCursors = 1))
        for (i in 0 until 3) h.backend.inner.seedFile(listOf("d1", "f$i.txt"), bytes("x"))
        for (i in 0 until 3) h.backend.inner.seedFile(listOf("d2", "f$i.txt"), bytes("x"))
        val handle = h.handle()
        h.fs.list(handle, "d1", ListOptions(limit = 1)).success() // publishes a cursor, holds the slot
        assertEquals(1, h.fs.operations.liveCursorCount)
        val second = h.fs.list(handle, "d2", ListOptions(limit = 1)) // cannot publish a cursor
        assertEquals(FilesystemErrorCode.E_TOO_LARGE, code(second))
    }

    // -- Deadline, cancellation, close, revocation races --------------------

    @Test
    fun operationDeadlineReturnsTimeoutAndReleasesSlot() = runTest {
        val h = Harness(limits = VfsLimits(operationDeadlineMs = 50))
        h.backend.inner.seedFile(listOf("f.txt"), bytes("hello"))
        h.backend.readFileDelayMs = 10_000 // virtual time; withTimeout(50) fires first
        val result = h.fs.readText(h.handle(), "f.txt", ReadTextOptions(100))
        assertEquals(FilesystemErrorCode.E_TIMEOUT, code(result))
        assertEquals(0, h.fs.operations.activeCount)
    }

    @Test
    fun cancellationReleasesReservationAndRethrows() = runTest {
        val h = Harness()
        h.backend.inner.seedFile(listOf("f.txt"), bytes("hello"))
        h.backend.readFileDelayMs = 10_000
        val handle = h.handle()
        val job = launch { h.fs.readText(handle, "f.txt", ReadTextOptions(100)) }
        yield() // op admits its slot and suspends in the read delay
        assertEquals(1, h.fs.operations.activeCount)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        // The terminal gate released the slot on the cancellation path.
        assertEquals(0, h.fs.operations.activeCount)
    }

    @Test
    fun closeDuringOperationSuppressesLateSuccess() = runTest {
        val h = Harness()
        h.backend.inner.seedFile(listOf("f.txt"), bytes("hello"))
        val gate = CompletableDeferred<Unit>()
        h.backend.readGate = gate
        val handle = h.handle()
        var result: FilesystemOutcome<ReadTextResult>? = null
        val job = launch { result = h.fs.readText(handle, "f.txt", ReadTextOptions(100)) }
        yield() // op admits and suspends at the gate
        assertEquals(1, h.fs.operations.activeCount)
        h.fs.close() // close the registry while the operation is in flight
        gate.complete(Unit) // let the backend finish with a success
        job.join()
        // The late success is suppressed by the publication guard; the slot is released.
        assertEquals(FilesystemErrorCode.E_CLOSED, code(result!!))
        assertEquals(0, h.fs.operations.activeCount)
    }

    @Test
    fun revocationDuringOperationSuppressesLateSuccess() = runTest {
        val h = Harness()
        h.backend.inner.seedFile(listOf("f.txt"), bytes("hello"))
        val gate = CompletableDeferred<Unit>()
        h.backend.readGate = gate
        val handle = h.handle()
        var result: FilesystemOutcome<ReadTextResult>? = null
        val job = launch { result = h.fs.readText(handle, "f.txt", ReadTextOptions(100)) }
        yield()
        h.fs.revokeAll(MountRevocationSource.GRANT_REVOKED) // revoke while in flight
        gate.complete(Unit)
        job.join()
        assertEquals(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, code(result!!))
        assertEquals(0, h.fs.operations.activeCount)
    }

    // -- Foreign / revoked / revalidation rejection -------------------------

    @Test
    fun foreignLeaseIsRejectedBeforeAdmission() = runTest {
        val h = Harness()
        val other = Harness(owner = LeaseOwner("state-2", "instance-2", 1))
        val foreignHandle = other.handle()
        val result = h.fs.stat(foreignHandle, "x")
        assertEquals(FilesystemErrorCode.E_STALE, code(result))
        assertEquals(0, h.fs.operations.activeCount) // never admitted
    }

    @Test
    fun revokedHandleIsRejectedBeforeAnyProviderEffect() = runTest {
        val h = Harness()
        val handle = h.handle()
        h.fs.revokeAll(MountRevocationSource.GRANT_REVOKED)
        val result = h.fs.writeText(handle, "new.txt", "x", WriteTextOptions(WriteMode.CREATE_NEW))
        assertEquals(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, code(result))
        assertFalse(h.backend.inner.exists(listOf("new.txt"))) // no provider access performed
        assertEquals(0, h.fs.operations.activeCount)
    }

    @Test
    fun revalidationFailureBlocksTheOperation() = runTest {
        val revalidator = MutableRevalidator()
        val h = Harness(revalidator = revalidator)
        h.backend.inner.seedFile(listOf("f.txt"), bytes("hi"))
        val handle = h.handle() // open does not revalidate
        revalidator.failure = FilesystemError(FilesystemErrorCode.E_MOUNT_UNAVAILABLE, "grant unavailable")
        val result = h.fs.readText(handle, "f.txt", ReadTextOptions(100))
        assertEquals(FilesystemErrorCode.E_MOUNT_UNAVAILABLE, code(result))
        assertEquals(0, h.fs.operations.activeCount) // rejected before admission
    }

    @Test
    fun advanceGenerationInvalidatesPredecessorHandle() = runTest {
        val h = Harness()
        h.backend.inner.seedFile(listOf("f.txt"), bytes("hi"))
        val predecessor = h.handle()
        h.advanceGeneration(2)
        val stale = h.fs.readText(predecessor, "f.txt", ReadTextOptions(100))
        assertEquals(FilesystemErrorCode.E_CLOSED, code(stale))
        assertTrue(h.fs.readText(h.handle(), "f.txt", ReadTextOptions(100)) is FilesystemOutcome.Success)
    }

    // -- Exact-once terminal gate (unit) ------------------------------------

    @Test
    fun terminalGateIsExactOnceAndDiscardsLateCompletions() {
        val ledger = OperationLedger(VfsLimits())
        val reservation = (ledger.tryAdmit(100) as Admission.Admitted).reservation
        assertEquals(1, ledger.activeCount)
        assertTrue(reservation.terminate(TerminalCause.TIMEOUT))
        assertEquals(0, ledger.activeCount)
        assertEquals(0L, ledger.generationTransferBytes) // a timeout transfers nothing
        // A late "success" completion is discarded: no double release, no byte accounting.
        assertFalse(reservation.terminate(TerminalCause.SUCCESS, actualBytes = 100))
        assertEquals(0, ledger.activeCount)
        assertEquals(0L, ledger.generationTransferBytes)
        assertEquals(TerminalCause.TIMEOUT, reservation.cause) // first cause wins
    }

    @Test
    fun successTerminationAccountsActualBytes() {
        val ledger = OperationLedger(VfsLimits())
        val reservation = (ledger.tryAdmit(100) as Admission.Admitted).reservation
        assertEquals(100L, ledger.generationTransferBytes) // reserved up front
        assertTrue(reservation.terminate(TerminalCause.SUCCESS, actualBytes = 7))
        assertEquals(0, ledger.activeCount)
        assertEquals(7L, ledger.generationTransferBytes) // reconciled to the actual transfer
        assertEquals(7L, ledger.processTransferBytes)
    }

    // -- Cleanup -----------------------------------------------------------

    @Test
    fun admittedOperationReleasesItsSlotOnCompletion() = runTest {
        val h = Harness()
        h.backend.inner.seedFile(listOf("f.txt"), bytes("hi"))
        h.fs.readText(h.handle(), "f.txt", ReadTextOptions(100)).success()
        assertEquals(0, h.fs.operations.activeCount)
    }

    @Test
    fun closeInvalidatesCursorsAndLeasesAndResetsCursorAccounting() = runTest {
        val h = Harness()
        for (i in 0 until 3) h.backend.inner.seedFile(listOf("d", "f$i.txt"), bytes("x"))
        val handle = h.handle()
        h.fs.list(handle, "d", ListOptions(limit = 1)).success().nextCursor!!
        assertEquals(1, h.fs.operations.liveCursorCount)
        h.fs.close()
        assertEquals(0, h.fs.operations.liveCursorCount) // cursor accounting reset
        assertEquals(FilesystemErrorCode.E_CLOSED, code(h.fs.mount("output")))
        assertEquals(FilesystemErrorCode.E_CLOSED, code(h.fs.stat(handle, "d")))
    }
}
