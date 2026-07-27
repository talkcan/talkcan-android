package io.talkcan.storage

import java.nio.charset.StandardCharsets
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Operation-semantics and error-normalization tests for the VFS core over an in-memory backend
 * (tasks 4.5, 4.6, 4.8, 4.9, 4.10, 4.11). Pagination lives in a dedicated test class.
 */
class MountedFilesystemOperationsTest {

    private fun bytes(s: String): ByteArray = s.toByteArray(StandardCharsets.UTF_8)

    // -- Mount lookup -------------------------------------------------------

    @Test
    fun mountResolvesADeclaredBindingToAnOpaqueHandle() = runTest {
        val vfs = Vfs()
        assertTrue(vfs.fs.mount("output") is FilesystemOutcome.Success)
        assertEquals(1, vfs.resolver.calls)
    }

    @Test
    fun mountRejectsEmptyDeclarationId() {
        val vfs = Vfs()
        assertEquals(FilesystemErrorCode.E_INVALID_ARGUMENT, vfs.fs.mount("").failure().code)
    }

    @Test
    fun mountReportsUndeclaredMount() {
        val vfs = Vfs()
        vfs.resolver.mount = null
        assertEquals(FilesystemErrorCode.E_CAPABILITY_UNDECLARED, vfs.fs.mount("output").failure().code)
    }

    @Test
    fun mountPropagatesTypedResolverFailures() {
        val vfs = Vfs()
        vfs.resolver.failure = FilesystemError(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, "grant revoked")
        val error = vfs.fs.mount("output").failure()
        assertEquals(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, error.code)
        assertEquals("grant revoked", error.reason)
    }

    @Test
    fun resolverExceptionCollapsesToIoWithoutLeakage() {
        val fs = MountedFilesystem(MountLeaseRegistry(LeaseOwner("state-1", "instance-1", 1), ThrowingResolver()))
        val error = fs.mount("output").failure()
        assertEquals(FilesystemErrorCode.E_IO, error.code)
        assertNoLeak(error.reason)
    }

    // -- mkdir --------------------------------------------------------------

    @Test
    fun mkdirCreatesANewDirectory() = runTest {
        val vfs = Vfs()
        val result = vfs.fs.mkdir(vfs.handle(), "entries", MkdirOptions(parents = false)).success()
        assertEquals(MkdirStatus.CREATED, result.status)
        assertTrue(vfs.backend.exists(listOf("entries")))
    }

    @Test
    fun mkdirReportsExistingDirectory() = runTest {
        val vfs = Vfs()
        vfs.backend.seedDir(listOf("entries"))
        val result = vfs.fs.mkdir(vfs.handle(), "entries", MkdirOptions(parents = false)).success()
        assertEquals(MkdirStatus.EXISTING, result.status)
    }

    @Test
    fun mkdirOnAnExistingFileFailsExists() = runTest {
        val vfs = Vfs()
        vfs.backend.seedFile(listOf("entries"), bytes("x"))
        val error = vfs.fs.mkdir(vfs.handle(), "entries", MkdirOptions(parents = false)).failure()
        assertEquals(FilesystemErrorCode.E_EXISTS, error.code)
    }

    @Test
    fun mkdirWithoutParentsRequiresAnExistingParent() = runTest {
        val vfs = Vfs()
        val error = vfs.fs.mkdir(vfs.handle(), "missing/child", MkdirOptions(parents = false)).failure()
        assertEquals(FilesystemErrorCode.E_NOT_FOUND, error.code)
    }

    @Test
    fun mkdirWithoutParentsRejectsAFileParent() = runTest {
        val vfs = Vfs()
        vfs.backend.seedFile(listOf("a.txt"), bytes("x"))
        val error = vfs.fs.mkdir(vfs.handle(), "a.txt/child", MkdirOptions(parents = false)).failure()
        assertEquals(FilesystemErrorCode.E_NOT_DIRECTORY, error.code)
    }

    @Test
    fun mkdirWithParentsCreatesTheWholeChainWithoutScanning() = runTest {
        val vfs = Vfs()
        val result = vfs.fs.mkdir(vfs.handle(), "2026/2026-07/entries", MkdirOptions(parents = true)).success()
        assertEquals(MkdirStatus.CREATED, result.status)
        assertTrue(vfs.backend.exists(listOf("2026")))
        assertTrue(vfs.backend.exists(listOf("2026", "2026-07")))
        assertTrue(vfs.backend.exists(listOf("2026", "2026-07", "entries")))
    }

    @Test
    fun mkdirWithParentsOverAnExistingChainReportsExisting() = runTest {
        val vfs = Vfs()
        vfs.backend.seedDir(listOf("2026", "2026-07", "entries"))
        val result = vfs.fs.mkdir(vfs.handle(), "2026/2026-07/entries", MkdirOptions(parents = true)).success()
        assertEquals(MkdirStatus.EXISTING, result.status)
    }

    @Test
    fun mkdirWithParentsCreatesOnlyMissingAncestors() = runTest {
        val vfs = Vfs()
        vfs.backend.seedDir(listOf("a", "b"))
        val result = vfs.fs.mkdir(vfs.handle(), "a/b/c/d", MkdirOptions(parents = true)).success()
        assertEquals(MkdirStatus.CREATED, result.status)
        assertTrue(vfs.backend.exists(listOf("a", "b", "c", "d")))
    }

    @Test
    fun mkdirWithParentsRejectsAFileInTheChain() = runTest {
        val vfs = Vfs()
        vfs.backend.seedFile(listOf("a", "f.txt"), bytes("x"))
        val error = vfs.fs.mkdir(vfs.handle(), "a/f.txt/x", MkdirOptions(parents = true)).failure()
        assertEquals(FilesystemErrorCode.E_NOT_DIRECTORY, error.code)
        assertFalse(vfs.backend.exists(listOf("a", "f.txt", "x")))
    }

    @Test
    fun mkdirRejectsInvalidPathsBeforeAnyEffect() = runTest {
        val vfs = Vfs()
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, vfs.fs.mkdir(vfs.handle(), "../up", MkdirOptions(true)).failure().code)
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, vfs.fs.mkdir(vfs.handle(), "/abs", MkdirOptions(true)).failure().code)
        assertFalse(vfs.backend.exists(listOf("..", "up")))
    }

    // -- stat ---------------------------------------------------------------

    @Test
    fun statReturnsOnlyBoundedPortableFieldsForAFile() = runTest {
        val vfs = Vfs()
        vfs.backend.nowProvider = { 1_784_752_965_123L }
        vfs.backend.seedFile(listOf("capture.json"), bytes("hello"))
        val stat = vfs.fs.stat(vfs.handle(), "capture.json").success()
        assertEquals("capture.json", stat.name)
        assertEquals(NodeKind.FILE, stat.kind)
        assertEquals(5L, stat.sizeBytes)
        assertEquals(1_784_752_965_123L, stat.modifiedAtUnixMs)
    }

    @Test
    fun statReportsDirectoryKindAndZeroSize() = runTest {
        val vfs = Vfs()
        vfs.backend.seedDir(listOf("entries"))
        val stat = vfs.fs.stat(vfs.handle(), "entries").success()
        assertEquals(NodeKind.DIRECTORY, stat.kind)
        assertEquals(0L, stat.sizeBytes)
    }

    @Test
    fun statOmitsModifiedTimeWhenProviderDoesNotSupplyIt() = runTest {
        val vfs = Vfs()
        vfs.backend.nowProvider = { null }
        vfs.backend.seedFile(listOf("f.txt"), bytes("x"))
        assertNull(vfs.fs.stat(vfs.handle(), "f.txt").success().modifiedAtUnixMs)
    }

    @Test
    fun statMissingPathFailsNotFound() = runTest {
        val vfs = Vfs()
        assertEquals(FilesystemErrorCode.E_NOT_FOUND, vfs.fs.stat(vfs.handle(), "nope").failure().code)
    }

    @Test
    fun statThroughAFileFailsNotDirectory() = runTest {
        val vfs = Vfs()
        vfs.backend.seedFile(listOf("a.txt"), bytes("x"))
        assertEquals(FilesystemErrorCode.E_NOT_DIRECTORY, vfs.fs.stat(vfs.handle(), "a.txt/child").failure().code)
    }

    // -- read_text ----------------------------------------------------------

    @Test
    fun readTextReturnsCompleteTextAndExactBytes() = runTest {
        val vfs = Vfs()
        vfs.backend.seedFile(listOf("note.txt"), bytes("café")) // 'é' is 2 UTF-8 bytes → 5 bytes.
        val result = vfs.fs.readText(vfs.handle(), "note.txt", ReadTextOptions(maxBytes = 100)).success()
        assertEquals("café", result.text)
        assertEquals(5L, result.bytes)
    }

    @Test
    fun readTextRejectsOversizedDocumentsWithoutPartialText() = runTest {
        val vfs = Vfs()
        vfs.backend.seedFile(listOf("big.txt"), bytes("0123456789"))
        val error = vfs.fs.readText(vfs.handle(), "big.txt", ReadTextOptions(maxBytes = 4)).failure()
        assertEquals(FilesystemErrorCode.E_TOO_LARGE, error.code)
    }

    @Test
    fun readTextClampsToHostPolicyMaximum() = runTest {
        val vfs = Vfs(policy = VfsPolicy(maxReadBytes = 4))
        vfs.backend.seedFile(listOf("big.txt"), bytes("0123456789"))
        // Requested bound is large, but host policy caps it at 4 → the 10-byte file is too large.
        assertEquals(FilesystemErrorCode.E_TOO_LARGE, vfs.fs.readText(vfs.handle(), "big.txt", ReadTextOptions(maxBytes = 1_000)).failure().code)
    }

    @Test
    fun readTextRejectsInvalidUtf8WithoutPublishingText() = runTest {
        val vfs = Vfs()
        vfs.backend.seedFile(listOf("bad.bin"), byteArrayOf(0x68, 0x69, 0xFF.toByte()))
        val error = vfs.fs.readText(vfs.handle(), "bad.bin", ReadTextOptions(maxBytes = 100)).failure()
        assertEquals(FilesystemErrorCode.E_UNSUPPORTED, error.code)
    }

    @Test
    fun readTextOnADirectoryFailsIsDirectory() = runTest {
        val vfs = Vfs()
        vfs.backend.seedDir(listOf("entries"))
        assertEquals(FilesystemErrorCode.E_IS_DIRECTORY, vfs.fs.readText(vfs.handle(), "entries", ReadTextOptions(maxBytes = 10)).failure().code)
    }

    @Test
    fun readTextMissingFailsNotFound() = runTest {
        val vfs = Vfs()
        assertEquals(FilesystemErrorCode.E_NOT_FOUND, vfs.fs.readText(vfs.handle(), "nope", ReadTextOptions(maxBytes = 10)).failure().code)
    }

    @Test
    fun readTextRejectsNonPositiveBound() = runTest {
        val vfs = Vfs()
        vfs.backend.seedFile(listOf("f.txt"), bytes("x"))
        assertEquals(FilesystemErrorCode.E_INVALID_ARGUMENT, vfs.fs.readText(vfs.handle(), "f.txt", ReadTextOptions(maxBytes = 0)).failure().code)
    }

    // -- write_text ---------------------------------------------------------

    @Test
    fun writeTextCreateNewWritesANewDocument() = runTest {
        val vfs = Vfs()
        val result = vfs.fs.writeText(vfs.handle(), "state.json", "{}", WriteTextOptions(WriteMode.CREATE_NEW)).success()
        assertEquals(WriteStatus.WRITTEN, result.status)
        assertEquals(2L, result.bytes)
        assertEquals("{}", String(vfs.backend.fileContent(listOf("state.json"))!!, StandardCharsets.UTF_8))
    }

    @Test
    fun writeTextCreateNewRefusesToOverwriteAndLeavesContentUnchanged() = runTest {
        val vfs = Vfs()
        vfs.backend.seedFile(listOf("state.json"), bytes("original"))
        val error = vfs.fs.writeText(vfs.handle(), "state.json", "new", WriteTextOptions(WriteMode.CREATE_NEW)).failure()
        assertEquals(FilesystemErrorCode.E_EXISTS, error.code)
        assertEquals("original", String(vfs.backend.fileContent(listOf("state.json"))!!, StandardCharsets.UTF_8))
    }

    @Test
    fun writeTextCreateNewOnADirectoryFailsIsDirectory() = runTest {
        val vfs = Vfs()
        vfs.backend.seedDir(listOf("entries"))
        assertEquals(FilesystemErrorCode.E_IS_DIRECTORY, vfs.fs.writeText(vfs.handle(), "entries", "x", WriteTextOptions(WriteMode.CREATE_NEW)).failure().code)
    }

    @Test
    fun writeTextReplaceOverwritesCompletely() = runTest {
        val vfs = Vfs()
        vfs.backend.seedFile(listOf("day.md"), bytes("old content"))
        val result = vfs.fs.writeText(vfs.handle(), "day.md", "complete new content", WriteTextOptions(WriteMode.REPLACE)).success()
        assertEquals(WriteStatus.WRITTEN, result.status)
        assertEquals("complete new content", String(vfs.backend.fileContent(listOf("day.md"))!!, StandardCharsets.UTF_8))
    }

    @Test
    fun writeTextReplaceCreatesWhenAbsent() = runTest {
        val vfs = Vfs()
        vfs.fs.writeText(vfs.handle(), "fresh.md", "hi", WriteTextOptions(WriteMode.REPLACE)).success()
        assertEquals("hi", String(vfs.backend.fileContent(listOf("fresh.md"))!!, StandardCharsets.UTF_8))
    }

    @Test
    fun failedReplaceLeavesOriginalIntactAndNoStagedNodeBehind() = runTest {
        val vfs = Vfs()
        vfs.backend.seedFile(listOf("day.md"), bytes("original"))
        vfs.backend.writeFailureDuringPublish = BackendFailure.NO_SPACE
        val error = vfs.fs.writeText(vfs.handle(), "day.md", "replacement", WriteTextOptions(WriteMode.REPLACE)).failure()
        assertEquals(FilesystemErrorCode.E_NO_SPACE, error.code)
        assertEquals("original", String(vfs.backend.fileContent(listOf("day.md"))!!, StandardCharsets.UTF_8))
        assertNoStagedNode(vfs, emptyList())
    }

    @Test
    fun writeExceptionCollapsesToIoLeavesOriginalAndNoStageAndNoLeak() = runTest {
        val vfs = Vfs()
        vfs.backend.seedFile(listOf("day.md"), bytes("original"))
        vfs.backend.throwOn = "writeFile"
        val error = vfs.fs.writeText(vfs.handle(), "day.md", "replacement", WriteTextOptions(WriteMode.REPLACE)).failure()
        assertEquals(FilesystemErrorCode.E_IO, error.code)
        assertEquals("original", String(vfs.backend.fileContent(listOf("day.md"))!!, StandardCharsets.UTF_8))
        assertNoStagedNode(vfs, emptyList())
        assertNoLeak(error.reason)
    }

    @Test
    fun writeTextRejectsOversizedPayloads() = runTest {
        val vfs = Vfs(policy = VfsPolicy(maxWriteBytes = 4))
        assertEquals(FilesystemErrorCode.E_TOO_LARGE, vfs.fs.writeText(vfs.handle(), "f.txt", "0123456789", WriteTextOptions(WriteMode.CREATE_NEW)).failure().code)
        assertFalse(vfs.backend.exists(listOf("f.txt")))
    }

    @Test
    fun writeTextRejectsInvalidUtf8BeforeAnyEffect() = runTest {
        val vfs = Vfs()
        val error = vfs.fs.writeText(vfs.handle(), "f.txt", "a\uD800b", WriteTextOptions(WriteMode.CREATE_NEW)).failure()
        assertEquals(FilesystemErrorCode.E_INVALID_ARGUMENT, error.code)
        assertFalse(vfs.backend.exists(listOf("f.txt")))
    }

    @Test
    fun writeTextRequiresAnExistingParentDirectory() = runTest {
        val vfs = Vfs()
        assertEquals(FilesystemErrorCode.E_NOT_FOUND, vfs.fs.writeText(vfs.handle(), "missing/f.txt", "x", WriteTextOptions(WriteMode.CREATE_NEW)).failure().code)
    }

    // -- remove -------------------------------------------------------------

    @Test
    fun removeDeletesOneFile() = runTest {
        val vfs = Vfs()
        vfs.backend.seedFile(listOf("spool.wav"), bytes("x"))
        val result = vfs.fs.remove(vfs.handle(), "spool.wav", RemoveOptions(missingOk = false)).success()
        assertEquals(RemoveStatus.REMOVED, result.status)
        assertFalse(vfs.backend.exists(listOf("spool.wav")))
    }

    @Test
    fun removeDeletesOneEmptyDirectory() = runTest {
        val vfs = Vfs()
        vfs.backend.seedDir(listOf("empty"))
        assertEquals(RemoveStatus.REMOVED, vfs.fs.remove(vfs.handle(), "empty", RemoveOptions(missingOk = false)).success().status)
        assertFalse(vfs.backend.exists(listOf("empty")))
    }

    @Test
    fun removeRefusesNonEmptyDirectoryAndKeepsDescendants() = runTest {
        val vfs = Vfs()
        vfs.backend.seedFile(listOf("entries", "a.txt"), bytes("x"))
        val error = vfs.fs.remove(vfs.handle(), "entries", RemoveOptions(missingOk = false)).failure()
        assertEquals(FilesystemErrorCode.E_BUSY, error.code)
        assertTrue(vfs.backend.exists(listOf("entries", "a.txt")))
    }

    @Test
    fun removeMissingFailsNotFoundUnlessMissingOk() = runTest {
        val vfs = Vfs()
        assertEquals(FilesystemErrorCode.E_NOT_FOUND, vfs.fs.remove(vfs.handle(), "ghost", RemoveOptions(missingOk = false)).failure().code)
        assertEquals(RemoveStatus.MISSING, vfs.fs.remove(vfs.handle(), "ghost", RemoveOptions(missingOk = true)).success().status)
    }

    // -- error normalization ------------------------------------------------

    @Test
    fun readBackendFailuresNormalizeToTheFixedVocabulary() = runTest {
        val cases = listOf(
            BackendFailure.NO_SPACE to FilesystemErrorCode.E_NO_SPACE,
            BackendFailure.REAUTHORIZATION_REQUIRED to FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED,
            BackendFailure.READ_ONLY to FilesystemErrorCode.E_READ_ONLY,
            BackendFailure.UNAVAILABLE to FilesystemErrorCode.E_MOUNT_UNAVAILABLE,
            BackendFailure.TOO_LARGE to FilesystemErrorCode.E_TOO_LARGE,
            BackendFailure.BUSY to FilesystemErrorCode.E_BUSY,
            BackendFailure.UNSUPPORTED to FilesystemErrorCode.E_UNSUPPORTED,
            BackendFailure.IO to FilesystemErrorCode.E_IO,
        )
        for ((failure, code) in cases) {
            val vfs = Vfs()
            vfs.backend.seedFile(listOf("f.txt"), bytes("x"))
            vfs.backend.readFileFailure = failure
            assertEquals("read $failure", code, vfs.fs.readText(vfs.handle(), "f.txt", ReadTextOptions(100)).failure().code)
        }
    }

    @Test
    fun removeAndStatBackendFailuresNormalize() = runTest {
        val removeVfs = Vfs()
        removeVfs.backend.seedFile(listOf("f.txt"), bytes("x"))
        removeVfs.backend.deleteFailure = BackendFailure.READ_ONLY
        assertEquals(FilesystemErrorCode.E_READ_ONLY, removeVfs.fs.remove(removeVfs.handle(), "f.txt", RemoveOptions(false)).failure().code)

        val statVfs = Vfs()
        statVfs.backend.seedFile(listOf("f.txt"), bytes("x"))
        statVfs.backend.infoFailure = BackendFailure.BUSY
        assertEquals(FilesystemErrorCode.E_BUSY, statVfs.fs.stat(statVfs.handle(), "f.txt").failure().code)
    }

    @Test
    fun backendReasonPropagatesSanitized() = runTest {
        val vfs = Vfs()
        vfs.backend.seedFile(listOf("f.txt"), bytes("x"))
        vfs.backend.readFileFailure = BackendFailure.NO_SPACE
        vfs.backend.injectedReason = "quota exhausted for volume"
        val error = vfs.fs.readText(vfs.handle(), "f.txt", ReadTextOptions(10)).failure()
        assertEquals(FilesystemErrorCode.E_NO_SPACE, error.code)
        assertEquals("quota exhausted for volume", error.reason)
    }

    @Test
    fun reasonIsBoundedToPolicyBytes() = runTest {
        val vfs = Vfs(policy = VfsPolicy(maxReasonBytes = 16))
        vfs.backend.seedFile(listOf("f.txt"), bytes("x"))
        vfs.backend.readFileFailure = BackendFailure.IO
        vfs.backend.injectedReason = "a".repeat(1000)
        val reason = vfs.fs.readText(vfs.handle(), "f.txt", ReadTextOptions(10)).failure().reason
        assertTrue(reason!!.toByteArray(StandardCharsets.UTF_8).size <= 16)
    }

    @Test
    fun thrownBackendFailureNeverLeaksPlatformDetail() = runTest {
        val vfs = Vfs()
        vfs.backend.seedFile(listOf("f.txt"), bytes("x"))
        vfs.backend.throwOn = "readFile"
        val error = vfs.fs.readText(vfs.handle(), "f.txt", ReadTextOptions(10)).failure()
        assertEquals(FilesystemErrorCode.E_IO, error.code)
        assertNoLeak(error.reason)
    }

    // -- helpers ------------------------------------------------------------

    private fun assertNoStagedNode(vfs: Vfs, dir: List<String>) {
        for (name in vfs.backend.childrenNames(dir)) {
            assertFalse("staged node left behind: $name", name.contains("__stage__"))
        }
    }

    private fun assertNoLeak(reason: String?) {
        val text = reason ?: return
        for (secret in listOf("content://", "SAF", "storage", "emulated", "FileNotFoundException", "document/42")) {
            assertFalse("reason leaked <$secret>: $text", text.contains(secret))
        }
    }

    private class ThrowingResolver : MountResolver {
        override fun resolve(declarationId: String): MountResolution =
            throw RuntimeException("content://secret/document/42 at /storage/emulated/0 SAF FileNotFoundException")
    }
}
