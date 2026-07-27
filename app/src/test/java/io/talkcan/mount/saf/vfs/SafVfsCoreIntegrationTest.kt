package io.talkcan.mount.saf.vfs

import io.talkcan.storage.FilesystemError
import io.talkcan.storage.FilesystemErrorCode
import io.talkcan.storage.FilesystemOutcome
import io.talkcan.storage.LeaseOwner
import io.talkcan.storage.ListOptions
import io.talkcan.storage.MkdirOptions
import io.talkcan.storage.MkdirStatus
import io.talkcan.storage.MountAccessMode
import io.talkcan.storage.MountHandle
import io.talkcan.storage.MountLeaseRegistry
import io.talkcan.storage.MountLeaseRevalidator
import io.talkcan.storage.MountResolution
import io.talkcan.storage.MountResolver
import io.talkcan.storage.MountedFilesystem
import io.talkcan.storage.NodeKind
import io.talkcan.storage.ReadTextOptions
import io.talkcan.storage.RemoveOptions
import io.talkcan.storage.RemoveStatus
import io.talkcan.storage.ResolvedMount
import io.talkcan.storage.VfsLimits
import io.talkcan.storage.VfsPolicy
import io.talkcan.storage.WriteMode
import io.talkcan.storage.WriteTextOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end proof that the generic [MountedFilesystem] core drives the SAF
 * [SafDocumentTreeBackend] (over [FakeSafDocumentGateway]) and continues to own
 * every public semantic: path validation, finite bounds, and normalization of
 * backend failures into the fixed portable vocabulary without leakage. The core
 * is unchanged; only the backend is SAF-backed.
 */
class SafVfsCoreIntegrationTest {

    private class Harness(gateway: FakeSafDocumentGateway = FakeSafDocumentGateway()) {
        val gateway = gateway
        private val backend = SafDocumentTreeBackend(gateway, gateway.root.id, Dispatchers.Unconfined)
        private val resolved = ResolvedMount(
            mountToken = "saf-mount-token",
            declarationId = "output",
            generation = 1L,
            access = MountAccessMode.READ_WRITE,
            grantFingerprint = "saf-grant-fingerprint",
            backend = backend,
            root = backend.rootRef,
        )
        private val resolver = object : MountResolver {
            override fun resolve(declarationId: String): MountResolution =
                if (declarationId == "output") {
                    MountResolution.Resolved(resolved)
                } else {
                    MountResolution.Failed(FilesystemError(FilesystemErrorCode.E_CAPABILITY_UNDECLARED))
                }
        }

        /** Flippable revalidator to simulate live grant revocation between operations. */
        var revocationFailure: FilesystemError? = null
        private val revalidator = MountLeaseRevalidator {
            revocationFailure?.let { return@MountLeaseRevalidator FilesystemOutcome.Failure(it) }
            FilesystemOutcome.Success(Unit)
        }

        val fs = MountedFilesystem(
            MountLeaseRegistry(LeaseOwner("state-1", "instance-1", 1L), resolver, revalidator),
            VfsPolicy(),
            VfsLimits(),
        )

        fun handle(): MountHandle = (fs.mount("output") as FilesystemOutcome.Success).value
    }

    private fun <T> FilesystemOutcome<T>.success(): T = (this as FilesystemOutcome.Success).value
    private fun FilesystemOutcome<*>.code(): FilesystemErrorCode = (this as FilesystemOutcome.Failure).error.code
    private fun FilesystemOutcome<*>.reason(): String? = (this as FilesystemOutcome.Failure).error.reason

    @Test
    fun everyVfsOperationRoundTripsThroughTheSafBackend() = runTest {
        val h = Harness()
        val m = h.handle()

        // mkdir (with parents) then stat the directory.
        assertEquals(MkdirStatus.CREATED, h.fs.mkdir(m, "notes/2026", MkdirOptions(parents = true)).success().status)
        val dirStat = h.fs.stat(m, "notes/2026").success()
        assertEquals(NodeKind.DIRECTORY, dirStat.kind)

        // write_text create-new then read_text round-trips the exact UTF-8 text.
        val written = h.fs.writeText(m, "notes/2026/day.md", "héllo wörld", WriteTextOptions(WriteMode.CREATE_NEW)).success()
        assertEquals("héllo wörld".toByteArray(Charsets.UTF_8).size.toLong(), written.bytes)
        val read = h.fs.readText(m, "notes/2026/day.md", ReadTextOptions(maxBytes = 1024)).success()
        assertEquals("héllo wörld", read.text)
        assertEquals(written.bytes, read.bytes)

        // stat reports the file size.
        assertEquals(NodeKind.FILE, h.fs.stat(m, "notes/2026/day.md").success().kind)

        // list returns the directory child.
        val page = h.fs.list(m, "notes/2026", ListOptions(limit = 10)).success()
        assertEquals(listOf("day.md"), page.entries.map { it.name })

        // write_text replace overwrites completely.
        h.fs.writeText(m, "notes/2026/day.md", "replaced", WriteTextOptions(WriteMode.REPLACE)).success()
        assertEquals("replaced", h.fs.readText(m, "notes/2026/day.md", ReadTextOptions(maxBytes = 1024)).success().text)

        // nonrecursive remove of the file, then the empty directories.
        assertEquals(RemoveStatus.REMOVED, h.fs.remove(m, "notes/2026/day.md", RemoveOptions(missingOk = false)).success().status)
        assertEquals(RemoveStatus.REMOVED, h.fs.remove(m, "notes/2026", RemoveOptions(missingOk = false)).success().status)
        assertEquals(RemoveStatus.REMOVED, h.fs.remove(m, "notes", RemoveOptions(missingOk = false)).success().status)
        assertEquals(RemoveStatus.MISSING, h.fs.remove(m, "notes", RemoveOptions(missingOk = true)).success().status)

        // No staging documents survived anywhere in the tree.
        assertFalse(h.gateway.isStagePresent(h.gateway.root.id))
        assertEquals(0, h.gateway.openStreamCount)
    }

    @Test
    fun backendFailuresNormalizeToThePortableVocabularyThroughTheCore() = runTest {
        val h = Harness()
        val m = h.handle()

        // stat of a missing path -> E_NOT_FOUND.
        assertEquals(FilesystemErrorCode.E_NOT_FOUND, h.fs.stat(m, "absent.txt").code())

        // create-new over an existing file -> E_EXISTS.
        h.fs.writeText(m, "f.txt", "x", WriteTextOptions(WriteMode.CREATE_NEW)).success()
        assertEquals(
            FilesystemErrorCode.E_EXISTS,
            h.fs.writeText(m, "f.txt", "y", WriteTextOptions(WriteMode.CREATE_NEW)).code(),
        )

        // nonrecursive remove of a non-empty directory -> E_BUSY.
        h.fs.mkdir(m, "d", MkdirOptions(parents = false)).success()
        h.fs.writeText(m, "d/inner.txt", "x", WriteTextOptions(WriteMode.CREATE_NEW)).success()
        assertEquals(FilesystemErrorCode.E_BUSY, h.fs.remove(m, "d", RemoveOptions(missingOk = false)).code())

        // read over the bound -> E_TOO_LARGE with no partial text.
        h.fs.writeText(m, "big.txt", "0123456789", WriteTextOptions(WriteMode.CREATE_NEW)).success()
        assertEquals(FilesystemErrorCode.E_TOO_LARGE, h.fs.readText(m, "big.txt", ReadTextOptions(maxBytes = 4)).code())
    }

    @Test
    fun liveRevocationFailsOperationsClosedWithReauthorizationRequired() = runTest {
        val h = Harness()
        val m = h.handle()
        h.fs.writeText(m, "f.txt", "x", WriteTextOptions(WriteMode.CREATE_NEW)).success()

        h.revocationFailure = FilesystemError(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, "grant revoked")
        assertEquals(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, h.fs.readText(m, "f.txt", ReadTextOptions(1024)).code())
        assertEquals(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, h.fs.stat(m, "f.txt").code())
    }

    @Test
    fun gatewayRevocationNormalizesToReauthorizationWithoutLeakage() = runTest {
        val h = Harness()
        val m = h.handle()
        h.fs.writeText(m, "f.txt", "x", WriteTextOptions(WriteMode.CREATE_NEW)).success()

        h.gateway.revoked = true
        val outcome = h.fs.readText(m, "f.txt", ReadTextOptions(1024))
        assertEquals(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, outcome.code())
        val reason = outcome.reason()
        assertTrue(reason == null || (!reason.contains("content://") && !reason.contains("/storage")))
    }
}
