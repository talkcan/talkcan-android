package io.talkcan.storage

import java.nio.charset.StandardCharsets
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic pagination and opaque cursor-binding tests for the VFS core (task 4.7).
 * Cursors must be bound to mount, directory, generation, and listing session, and must be
 * one-time-use and unforgeable.
 */
class MountedFilesystemPaginationTest {

    private fun seedEntries(vfs: Vfs, dir: String, count: Int) {
        for (i in 0 until count) {
            vfs.backend.seedFile(listOf(dir, "f$i.txt"), "x".toByteArray(StandardCharsets.UTF_8))
        }
    }

    @Test
    fun singlePageBelowLimitHasNoCursor() = runTest {
        val vfs = Vfs()
        seedEntries(vfs, "d", 2)
        val page = vfs.fs.list(vfs.handle(), "d", ListOptions(limit = 10)).success()
        assertEquals(2, page.entries.size)
        assertNull(page.nextCursor)
    }

    @Test
    fun emptyPathListsTheMountRoot() = runTest {
        val vfs = Vfs()
        vfs.backend.seedDir(listOf("2026"))
        vfs.backend.seedFile(listOf("note.txt"), "x".toByteArray(StandardCharsets.UTF_8))

        val page = vfs.fs.list(vfs.handle(), "", ListOptions(limit = 10)).success()

        assertEquals(setOf("2026", "note.txt"), page.entries.map { it.name }.toSet())
        assertNull(page.nextCursor)
    }

    @Test
    fun paginationReturnsEveryEntryExactlyOnceAcrossPages() = runTest {
        val vfs = Vfs()
        seedEntries(vfs, "d", 10)
        val seen = LinkedHashSet<String>()
        var cursor: ListCursor? = null
        var pages = 0
        do {
            val page = vfs.fs.list(vfs.handle(), "d", ListOptions(limit = 3, cursor = cursor)).success()
            assertTrue("page over limit", page.entries.size <= 3)
            for (entry in page.entries) {
                assertTrue("duplicate entry ${entry.name}", seen.add(entry.name))
            }
            cursor = page.nextCursor
            pages++
            assertTrue("runaway pagination", pages < 50)
        } while (cursor != null)
        assertEquals(10, seen.size)
        assertEquals(4, pages) // 3 + 3 + 3 + 1
    }

    @Test
    fun listingOrderIsDeterministicWithinASession() = runTest {
        val vfs = Vfs()
        seedEntries(vfs, "d", 5)
        val page = vfs.fs.list(vfs.handle(), "d", ListOptions(limit = 2)).success()
        assertEquals(listOf("f0.txt", "f1.txt"), page.entries.map { it.name })
    }

    @Test
    fun entriesCarryNameAndPortableKind() = runTest {
        val vfs = Vfs()
        vfs.backend.seedFile(listOf("d", "file.txt"), "x".toByteArray(StandardCharsets.UTF_8))
        vfs.backend.seedDir(listOf("d", "sub"))
        val page = vfs.fs.list(vfs.handle(), "d", ListOptions(limit = 10)).success()
        val kinds = page.entries.associate { it.name to it.kind }
        assertEquals(NodeKind.FILE, kinds["file.txt"])
        assertEquals(NodeKind.DIRECTORY, kinds["sub"])
    }

    @Test
    fun pageLimitIsClampedToHostPolicy() = runTest {
        val vfs = Vfs(policy = VfsPolicy(maxPageSize = 2))
        seedEntries(vfs, "d", 5)
        val page = vfs.fs.list(vfs.handle(), "d", ListOptions(limit = 100)).success()
        assertEquals(2, page.entries.size)
        assertNotNull(page.nextCursor)
    }

    @Test
    fun nonPositiveLimitIsRejected() = runTest {
        val vfs = Vfs()
        seedEntries(vfs, "d", 1)
        assertEquals(FilesystemErrorCode.E_INVALID_ARGUMENT, vfs.fs.list(vfs.handle(), "d", ListOptions(limit = 0)).failure().code)
    }

    @Test
    fun cursorFromAnotherDirectoryIsRejected() = runTest {
        val vfs = Vfs()
        seedEntries(vfs, "d1", 3)
        vfs.backend.seedDir(listOf("d2"))
        val cursor = vfs.fs.list(vfs.handle(), "d1", ListOptions(limit = 1)).success().nextCursor!!
        val error = vfs.fs.list(vfs.handle(), "d2", ListOptions(limit = 1, cursor = cursor)).failure()
        assertEquals(FilesystemErrorCode.E_STALE, error.code)
    }

    @Test
    fun cursorFromAnOlderGenerationIsRejected() = runTest {
        val vfs = Vfs()
        seedEntries(vfs, "d", 3)
        val cursor = vfs.fs.list(vfs.handle(), "d", ListOptions(limit = 1)).success().nextCursor!!
        val successor = vfs.advanceGeneration(2)
        val error = vfs.fs.list(successor, "d", ListOptions(limit = 1, cursor = cursor)).failure()
        assertEquals(FilesystemErrorCode.E_STALE, error.code)
    }

    @Test
    fun cursorFromAnotherMountIsRejected() = runTest {
        val vfs = Vfs()
        seedEntries(vfs, "d", 3)
        val cursor = vfs.fs.list(vfs.handle(), "d", ListOptions(limit = 1)).success().nextCursor!!
        val foreign = vfs.switchMount("other-mount-token")
        val error = vfs.fs.list(foreign, "d", ListOptions(limit = 1, cursor = cursor)).failure()
        assertEquals(FilesystemErrorCode.E_STALE, error.code)
    }

    @Test
    fun aConsumedCursorCannotBeReplayed() = runTest {
        val vfs = Vfs()
        seedEntries(vfs, "d", 5)
        val cursor1 = vfs.fs.list(vfs.handle(), "d", ListOptions(limit = 1)).success().nextCursor!!
        vfs.fs.list(vfs.handle(), "d", ListOptions(limit = 1, cursor = cursor1)).success()
        assertEquals(FilesystemErrorCode.E_STALE, vfs.fs.list(vfs.handle(), "d", ListOptions(limit = 1, cursor = cursor1)).failure().code)
    }

    @Test
    fun anUnknownOrForgedCursorIsRejected() = runTest {
        val vfs = Vfs()
        seedEntries(vfs, "d", 3)
        val error = vfs.fs.list(vfs.handle(), "d", ListOptions(limit = 1, cursor = ListCursor("forged-token"))).failure()
        assertEquals(FilesystemErrorCode.E_STALE, error.code)
    }

    @Test
    fun terminalPaginationFailureInvalidatesTheCursor() = runTest {
        val vfs = Vfs()
        seedEntries(vfs, "d", 5)
        val cursor = vfs.fs.list(vfs.handle(), "d", ListOptions(limit = 2)).success().nextCursor!!
        vfs.backend.listChildrenFailure = BackendFailure.UNAVAILABLE
        val failed = vfs.fs.list(vfs.handle(), "d", ListOptions(limit = 2, cursor = cursor)).failure()
        assertEquals(FilesystemErrorCode.E_MOUNT_UNAVAILABLE, failed.code)
        vfs.backend.listChildrenFailure = null
        assertEquals(FilesystemErrorCode.E_STALE, vfs.fs.list(vfs.handle(), "d", ListOptions(limit = 2, cursor = cursor)).failure().code)
    }

    @Test
    fun listOnAFileFailsNotDirectory() = runTest {
        val vfs = Vfs()
        vfs.backend.seedFile(listOf("f.txt"), "x".toByteArray(StandardCharsets.UTF_8))
        assertEquals(FilesystemErrorCode.E_NOT_DIRECTORY, vfs.fs.list(vfs.handle(), "f.txt", ListOptions(limit = 10)).failure().code)
    }

    @Test
    fun listOnMissingDirectoryFailsNotFound() = runTest {
        val vfs = Vfs()
        assertEquals(FilesystemErrorCode.E_NOT_FOUND, vfs.fs.list(vfs.handle(), "nope", ListOptions(limit = 10)).failure().code)
    }

    @Test
    fun listRejectsInvalidPathsBeforeTraversal() = runTest {
        val vfs = Vfs()
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, vfs.fs.list(vfs.handle(), "../escape", ListOptions(limit = 10)).failure().code)
    }
}
