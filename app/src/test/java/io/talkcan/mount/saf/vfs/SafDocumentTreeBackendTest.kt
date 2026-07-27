package io.talkcan.mount.saf.vfs

import io.talkcan.storage.BackendFailure
import io.talkcan.storage.BackendNodeKind
import io.talkcan.storage.BackendResult
import io.talkcan.storage.BackendWriteMode
import io.talkcan.storage.NodeRef
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused JVM contract tests for [SafDocumentTreeBackend] against the in-memory
 * [FakeSafDocumentGateway]. Prove every operation, portable failure
 * normalization, complete-on-success staging/cleanup, cancellation and
 * revocation handling, deterministic pagination, bounded reads, provider
 * divergence, resource balance, and the absence of URI / raw-path / document-ID
 * leakage.
 */
class SafDocumentTreeBackendTest {

    private fun bytes(s: String): ByteArray = s.toByteArray(StandardCharsets.UTF_8)

    private fun backend(gateway: FakeSafDocumentGateway = FakeSafDocumentGateway()): SafDocumentTreeBackend =
        SafDocumentTreeBackend(gateway, gateway.root.id, Dispatchers.Unconfined)

    private fun <T> BackendResult<T>.ok(): T = (this as BackendResult.Ok<T>).value
    private fun BackendResult<*>.err(): BackendFailure = (this as BackendResult.Err).failure
    private fun BackendResult<*>.reason(): String? = (this as BackendResult.Err).reason

    // -- child ---------------------------------------------------------------

    @Test
    fun childResolvesAnExistingImmediateChild() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedFile(listOf("a", "b.txt"), bytes("x"))
        val backend = backend(gateway)
        val a = backend.child(backend.rootRef, "a").ok()
        val b = backend.child(a, "b.txt").ok()
        assertEquals("b.txt", backend.info(b).ok().name)
    }

    @Test
    fun childReportsNotFoundForMissingName() = runTest {
        val backend = backend()
        assertEquals(BackendFailure.NOT_FOUND, backend.child(backend.rootRef, "nope").err())
    }

    @Test
    fun childReportsNotADirectoryWhenParentIsAFile() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedFile(listOf("f.txt"), bytes("x"))
        val backend = backend(gateway)
        val file = backend.child(backend.rootRef, "f.txt").ok()
        assertEquals(BackendFailure.NOT_A_DIRECTORY, backend.child(file, "inner").err())
    }

    @Test
    fun childReportsNotFoundWhenParentIsGone() = runTest {
        val backend = backend()
        assertEquals(BackendFailure.NOT_FOUND, backend.child(NodeRef("missing-doc"), "x").err())
    }

    // -- info ----------------------------------------------------------------

    @Test
    fun infoReportsFileKindSizeAndModifiedTime() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.nowProvider = { 1_700_000_000_000L }
        gateway.seedFile(listOf("f.txt"), bytes("hello"))
        val backend = backend(gateway)
        val file = backend.child(backend.rootRef, "f.txt").ok()
        val info = backend.info(file).ok()
        assertEquals("f.txt", info.name)
        assertEquals(BackendNodeKind.FILE, info.kind)
        assertEquals(5L, info.sizeBytes)
        assertEquals(1_700_000_000_000L, info.modifiedAtUnixMs)
    }

    @Test
    fun infoReportsDirectoryWithZeroSizeAndOmitsMissingModifiedTime() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.nowProvider = { null }
        gateway.seedDir(listOf("d"))
        val backend = backend(gateway)
        val dir = backend.child(backend.rootRef, "d").ok()
        val info = backend.info(dir).ok()
        assertEquals(BackendNodeKind.DIRECTORY, info.kind)
        assertEquals(0L, info.sizeBytes)
        assertNull(info.modifiedAtUnixMs)
    }

    @Test
    fun infoReportsNotFoundForMissingNode() = runTest {
        val backend = backend()
        assertEquals(BackendFailure.NOT_FOUND, backend.info(NodeRef("gone")).err())
    }

    // -- createDirectory -----------------------------------------------------

    @Test
    fun createDirectoryMakesAListableChild() = runTest {
        val backend = backend()
        val dir = backend.createDirectory(backend.rootRef, "d").ok()
        assertEquals(BackendNodeKind.DIRECTORY, backend.info(dir).ok().kind)
        assertEquals(listOf("d"), backend.listChildren(backend.rootRef, null, 10).ok().entries.map { it.name })
    }

    @Test
    fun createDirectoryReportsAlreadyExistsForFileOrDirectory() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedFile(listOf("f.txt"), bytes("x"))
        gateway.seedDir(listOf("d"))
        val backend = backend(gateway)
        assertEquals(BackendFailure.ALREADY_EXISTS, backend.createDirectory(backend.rootRef, "f.txt").err())
        assertEquals(BackendFailure.ALREADY_EXISTS, backend.createDirectory(backend.rootRef, "d").err())
    }

    @Test
    fun createDirectoryReportsNotADirectoryOnFileParent() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedFile(listOf("f.txt"), bytes("x"))
        val backend = backend(gateway)
        val file = backend.child(backend.rootRef, "f.txt").ok()
        assertEquals(BackendFailure.NOT_A_DIRECTORY, backend.createDirectory(file, "inner").err())
    }

    // -- listChildren / pagination ------------------------------------------

    @Test
    fun listChildrenIsDeterministicallyOrderedAndPaginatesWithoutDuplicatesOrSkips() = runTest {
        val gateway = FakeSafDocumentGateway()
        val names = listOf("gamma", "alpha", "delta", "beta", "epsilon")
        names.forEach { gateway.seedFile(listOf(it), bytes("x")) }
        val backend = backend(gateway)

        val seen = mutableListOf<String>()
        var token: String? = null
        var pages = 0
        do {
            val page = backend.listChildren(backend.rootRef, token, 2).ok()
            seen += page.entries.map { it.name }
            token = page.nextPageToken
            pages++
            assertTrue("pagination must terminate", pages <= 10)
        } while (token != null)

        assertEquals(names.sorted(), seen)
    }

    @Test
    fun listChildrenPastTheEndReturnsAnEmptyTerminalPage() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedFile(listOf("a"), bytes("x"))
        val backend = backend(gateway)
        val page = backend.listChildren(backend.rootRef, "99", 10).ok()
        assertTrue(page.entries.isEmpty())
        assertNull(page.nextPageToken)
    }

    @Test
    fun listChildrenRejectsAMalformedTokenAsIo() = runTest {
        val backend = backend()
        assertEquals(BackendFailure.IO, backend.listChildren(backend.rootRef, "not-a-number", 10).err())
    }

    @Test
    fun listChildrenReportsNotADirectoryOnFileParent() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedFile(listOf("f.txt"), bytes("x"))
        val backend = backend(gateway)
        val file = backend.child(backend.rootRef, "f.txt").ok()
        assertEquals(BackendFailure.NOT_A_DIRECTORY, backend.listChildren(file, null, 10).err())
    }

    // -- readFile ------------------------------------------------------------

    @Test
    fun readReturnsCompleteContentWithinBound() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedFile(listOf("f.txt"), bytes("hello"))
        val backend = backend(gateway)
        val file = backend.child(backend.rootRef, "f.txt").ok()
        assertArrayEquals(bytes("hello"), backend.readFile(file, 5).ok())
    }

    @Test
    fun readAtExactlyTheBoundSucceedsAndOneOverIsTooLarge() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedFile(listOf("f.txt"), bytes("hello"))
        val backend = backend(gateway)
        val file = backend.child(backend.rootRef, "f.txt").ok()
        assertArrayEquals(bytes("hello"), backend.readFile(file, 5).ok())
        assertEquals(BackendFailure.TOO_LARGE, backend.readFile(file, 4).err())
    }

    @Test
    fun readReportsIsADirectoryForDirectoryNode() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedDir(listOf("d"))
        val backend = backend(gateway)
        val dir = backend.child(backend.rootRef, "d").ok()
        assertEquals(BackendFailure.IS_A_DIRECTORY, backend.readFile(dir, 10).err())
    }

    // -- writeFile: create-new ----------------------------------------------

    @Test
    fun writeCreateNewPublishesCompleteContentAndLeavesNoStage() = runTest {
        val gateway = FakeSafDocumentGateway()
        val backend = backend(gateway)
        val node = backend.writeFile(backend.rootRef, "f.txt", bytes("payload"), BackendWriteMode.CREATE_NEW).ok()
        assertArrayEquals(bytes("payload"), backend.readFile(node, 100).ok())
        assertFalse(gateway.isStagePresent(gateway.root.id))
        assertEquals(0, gateway.openStreamCount)
    }

    @Test
    fun writeCreateNewReportsAlreadyExistsWithoutTouchingExistingContent() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedFile(listOf("f.txt"), bytes("original"))
        val backend = backend(gateway)
        val fileId = gateway.childNames(gateway.root.id) // sanity
        assertEquals(listOf("f.txt"), fileId)
        val result = backend.writeFile(backend.rootRef, "f.txt", bytes("new"), BackendWriteMode.CREATE_NEW)
        assertEquals(BackendFailure.ALREADY_EXISTS, result.err())
        assertArrayEquals(bytes("original"), gateway.contentOf(firstFileId(gateway)))
    }

    @Test
    fun writeCreateNewAgainstAutoRenamingProviderStillReportsAlreadyExists() = runTest {
        // Local providers auto-rename duplicate creates ("name (1)"); the backend's
        // pre-check is what enforces create-new, so it must still fail closed.
        val gateway = FakeSafDocumentGateway(duplicateCreate = FakeSafDocumentGateway.DuplicateCreate.AUTO_RENAME)
        gateway.seedFile(listOf("f.txt"), bytes("original"))
        val backend = backend(gateway)
        val result = backend.writeFile(backend.rootRef, "f.txt", bytes("new"), BackendWriteMode.CREATE_NEW)
        assertEquals(BackendFailure.ALREADY_EXISTS, result.err())
        assertEquals(listOf("f.txt"), gateway.childNames(gateway.root.id))
    }

    @Test
    fun writeCreateNewFailureRemovesTheCreatedNodeAndPublishesNothing() = runTest {
        val gateway = FakeSafDocumentGateway()
        val backend = backend(gateway)
        // Let the create succeed, then fail the stream open so a node exists but cannot be written.
        gateway.failOpenWrite = SafGatewayFailure.NO_SPACE
        val result = backend.writeFile(backend.rootRef, "f.txt", bytes("payload"), BackendWriteMode.CREATE_NEW)
        assertEquals(BackendFailure.NO_SPACE, result.err())
        assertFalse(gateway.existsName(gateway.root.id, "f.txt"))
        assertFalse(gateway.isStagePresent(gateway.root.id))
    }

    @Test
    fun writeCreateNewOnDirectoryTargetReportsIsADirectory() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedDir(listOf("d"))
        val backend = backend(gateway)
        assertEquals(
            BackendFailure.IS_A_DIRECTORY,
            backend.writeFile(backend.rootRef, "d", bytes("x"), BackendWriteMode.CREATE_NEW).err(),
        )
    }

    // -- writeFile: replace --------------------------------------------------

    @Test
    fun writeReplaceOverwritesExistingFileCompletely() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedFile(listOf("f.txt"), bytes("original-content"))
        val backend = backend(gateway)
        val node = backend.writeFile(backend.rootRef, "f.txt", bytes("new"), BackendWriteMode.REPLACE).ok()
        assertArrayEquals(bytes("new"), backend.readFile(node, 100).ok())
        assertFalse(gateway.isStagePresent(gateway.root.id))
        assertEquals(listOf("f.txt"), gateway.childNames(gateway.root.id))
    }

    @Test
    fun writeReplaceCreatesTheFileWhenAbsent() = runTest {
        val gateway = FakeSafDocumentGateway()
        val backend = backend(gateway)
        val node = backend.writeFile(backend.rootRef, "fresh.txt", bytes("data"), BackendWriteMode.REPLACE).ok()
        assertArrayEquals(bytes("data"), backend.readFile(node, 100).ok())
        assertFalse(gateway.isStagePresent(gateway.root.id))
    }

    @Test
    fun writeReplacePublishFailureRemovesStageAndPreservesExistingTarget() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedFile(listOf("f.txt"), bytes("keep-me"))
        val backend = backend(gateway)
        // Staged write succeeds; publishing to the existing target fails at open.
        gateway.failOpenWrite = null
        // Fail only the second open (the publish) by failing after the stage write:
        gateway.onStageChunkWrite = { gateway.failOpenWrite = SafGatewayFailure.NO_SPACE }
        val result = backend.writeFile(backend.rootRef, "f.txt", bytes("replacement"), BackendWriteMode.REPLACE)
        assertEquals(BackendFailure.NO_SPACE, result.err())
        assertArrayEquals(bytes("keep-me"), gateway.contentOf(firstFileId(gateway)))
        assertFalse(gateway.isStagePresent(gateway.root.id))
        assertEquals(listOf("f.txt"), gateway.childNames(gateway.root.id))
    }

    @Test
    fun writeReplaceQuotaDuringStagedWriteLeavesNoStageAndPreservesTarget() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedFile(listOf("f.txt"), bytes("keep-me"))
        gateway.quotaBytes = 3 // payload exceeds quota during the staged write
        val backend = backend(gateway)
        val result = backend.writeFile(backend.rootRef, "f.txt", bytes("too-big-payload"), BackendWriteMode.REPLACE)
        assertEquals(BackendFailure.NO_SPACE, result.err())
        assertFalse(gateway.isStagePresent(gateway.root.id))
        assertArrayEquals(bytes("keep-me"), gateway.contentOf(firstFileId(gateway)))
        assertEquals(0, gateway.openStreamCount)
    }

    @Test
    fun writeReplaceOnDirectoryTargetReportsIsADirectoryWithoutStaging() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedDir(listOf("d"))
        val backend = backend(gateway)
        assertEquals(
            BackendFailure.IS_A_DIRECTORY,
            backend.writeFile(backend.rootRef, "d", bytes("x"), BackendWriteMode.REPLACE).err(),
        )
        assertFalse(gateway.isStagePresent(gateway.root.id))
    }

    // -- delete --------------------------------------------------------------

    @Test
    fun deleteRemovesAFile() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedFile(listOf("f.txt"), bytes("x"))
        val backend = backend(gateway)
        val file = backend.child(backend.rootRef, "f.txt").ok()
        backend.delete(file).ok()
        assertEquals(BackendFailure.NOT_FOUND, backend.child(backend.rootRef, "f.txt").err())
    }

    @Test
    fun deleteRemovesAnEmptyDirectory() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedDir(listOf("d"))
        val backend = backend(gateway)
        val dir = backend.child(backend.rootRef, "d").ok()
        backend.delete(dir).ok()
        assertEquals(BackendFailure.NOT_FOUND, backend.child(backend.rootRef, "d").err())
    }

    @Test
    fun deleteReportsNotEmptyForDirectoryWithChildren() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedFile(listOf("d", "inner.txt"), bytes("x"))
        val backend = backend(gateway)
        val dir = backend.child(backend.rootRef, "d").ok()
        assertEquals(BackendFailure.NOT_EMPTY, backend.delete(dir).err())
    }

    @Test
    fun deleteReportsNotFoundForMissingNode() = runTest {
        val backend = backend()
        assertEquals(BackendFailure.NOT_FOUND, backend.delete(NodeRef("ghost")).err())
    }

    @Test
    fun deleteRefusesTheTreeRoot() = runTest {
        val backend = backend()
        assertEquals(BackendFailure.BUSY, backend.delete(backend.rootRef).err())
    }

    // -- failure normalization ----------------------------------------------

    @Test
    fun gatewayFailuresNormalizeToTheFixedBackendVocabulary() = runTest {
        val cases = listOf(
            SafGatewayFailure.NOT_FOUND to BackendFailure.NOT_FOUND,
            SafGatewayFailure.ALREADY_EXISTS to BackendFailure.ALREADY_EXISTS,
            SafGatewayFailure.NOT_A_DIRECTORY to BackendFailure.NOT_A_DIRECTORY,
            SafGatewayFailure.IS_A_DIRECTORY to BackendFailure.IS_A_DIRECTORY,
            SafGatewayFailure.NOT_EMPTY to BackendFailure.NOT_EMPTY,
            SafGatewayFailure.READ_ONLY to BackendFailure.READ_ONLY,
            SafGatewayFailure.NO_SPACE to BackendFailure.NO_SPACE,
            SafGatewayFailure.REVOKED to BackendFailure.REAUTHORIZATION_REQUIRED,
            SafGatewayFailure.UNAVAILABLE to BackendFailure.UNAVAILABLE,
            SafGatewayFailure.UNSUPPORTED to BackendFailure.UNSUPPORTED,
            SafGatewayFailure.MALFORMED to BackendFailure.IO,
            SafGatewayFailure.IO to BackendFailure.IO,
        )
        for ((gatewayFailure, backendFailure) in cases) {
            val gateway = FakeSafDocumentGateway()
            gateway.failQueryDocument = gatewayFailure
            val backend = backend(gateway)
            assertEquals(gatewayFailure.name, backendFailure, backend.info(NodeRef("any")).err())
        }
    }

    @Test
    fun malformedChildrenCursorNormalizesToIo() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.malformedChildren = true
        val backend = backend(gateway)
        assertEquals(BackendFailure.IO, backend.listChildren(backend.rootRef, null, 10).err())
    }

    @Test
    fun revokedGrantMapsEveryOperationToReauthorizationRequired() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedFile(listOf("f.txt"), bytes("x"))
        val backend = backend(gateway)
        val file = backend.child(backend.rootRef, "f.txt").ok()
        gateway.revoked = true
        assertEquals(BackendFailure.REAUTHORIZATION_REQUIRED, backend.info(file).err())
        assertEquals(BackendFailure.REAUTHORIZATION_REQUIRED, backend.child(backend.rootRef, "f.txt").err())
        assertEquals(BackendFailure.REAUTHORIZATION_REQUIRED, backend.delete(file).err())
        assertEquals(
            BackendFailure.REAUTHORIZATION_REQUIRED,
            backend.writeFile(backend.rootRef, "g.txt", bytes("x"), BackendWriteMode.CREATE_NEW).err(),
        )
    }

    @Test
    fun readOnAReadOnlyProviderFailsAtOpenWhileMetadataStillWorks() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedFile(listOf("f.txt"), bytes("x"))
        val backend = backend(gateway)
        val file = backend.child(backend.rootRef, "f.txt").ok()
        gateway.failOpenRead = SafGatewayFailure.READ_ONLY
        assertEquals(BackendFailure.READ_ONLY, backend.readFile(file, 10).err())
        // Metadata reads are unaffected by the write/read-open read-only signal.
        assertEquals("f.txt", backend.info(file).ok().name)
    }

    // -- cancellation --------------------------------------------------------

    @Test
    fun replaceCancellationRemovesStagingAndCreatesNoTarget() {
        val gateway = FakeSafDocumentGateway()
        val backend = SafDocumentTreeBackend(gateway, gateway.root.id, Dispatchers.Default)
        var writeJob: Job? = null
        gateway.onStageChunkWrite = { writeJob?.cancel() }
        runBlocking {
            writeJob = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                backend.writeFile(backend.rootRef, "f.txt", bytes("payload"), BackendWriteMode.REPLACE)
            }
            writeJob!!.start()
            writeJob!!.join()
        }
        assertTrue(writeJob!!.isCancelled)
        assertFalse(gateway.isStagePresent(gateway.root.id))
        assertFalse(gateway.existsName(gateway.root.id, "f.txt"))
        assertEquals(0, gateway.openStreamCount)
    }

    // -- resource balance ----------------------------------------------------

    @Test
    fun everyStreamIsClosedAfterReadsAndWrites() = runTest {
        val gateway = FakeSafDocumentGateway()
        val backend = backend(gateway)
        backend.writeFile(backend.rootRef, "f.txt", bytes("abc"), BackendWriteMode.CREATE_NEW).ok()
        val file = backend.child(backend.rootRef, "f.txt").ok()
        backend.readFile(file, 10).ok()
        backend.writeFile(backend.rootRef, "f.txt", bytes("defg"), BackendWriteMode.REPLACE).ok()
        assertEquals(0, gateway.openStreamCount)
    }

    // -- no leakage ----------------------------------------------------------

    @Test
    fun nodeRefsAndReasonsCarryNoUriPathOrDocumentDetail() = runTest {
        val gateway = FakeSafDocumentGateway()
        gateway.seedFile(listOf("f.txt"), bytes("x"))
        val backend = backend(gateway)

        // Opaque node refs must not be URIs or raw paths.
        val file = backend.child(backend.rootRef, "f.txt").ok()
        assertNoLeak(file.opaque)
        assertNoLeak(backend.rootRef.opaque)

        // Failure reasons are fixed portable tokens only.
        gateway.revoked = true
        val reason = backend.info(file).reason()
        assertEquals("saf.revoked", reason)
        assertNoLeak(reason)

        gateway.revoked = false
        gateway.failQueryDocument = SafGatewayFailure.MALFORMED
        assertNoLeak(backend.info(file).reason())
    }

    private fun firstFileId(gateway: FakeSafDocumentGateway): String {
        val name = gateway.childNames(gateway.root.id).first()
        // Resolve the child's document id through the gateway's public row query.
        val rows = (gateway.queryChildren(gateway.root.id) as SafGatewayResult.Ok).value
        return rows.first { it.displayName == name }.id
    }

    private fun assertNoLeak(value: String?) {
        if (value == null) return
        assertFalse("leaked content URI: $value", value.contains("content://"))
        assertFalse("leaked raw path: $value", value.contains("/storage"))
        assertFalse("leaked raw path: $value", value.contains("/emulated"))
        assertFalse("leaked exception: $value", value.contains("Exception"))
    }
}
