package io.talkcan.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adversarial and bounds tests for the canonical mount-relative path parser (tasks 4.3, 4.4). */
class MountRelativePathTest {

    private fun parse(raw: String, bounds: PathBounds = PathBounds.DEFAULT): PathParseResult =
        MountRelativePath.parse(raw, bounds)

    private fun valid(raw: String, bounds: PathBounds = PathBounds.DEFAULT): MountRelativePath =
        (parse(raw, bounds) as PathParseResult.Valid).path

    private fun invalidCode(raw: String, bounds: PathBounds = PathBounds.DEFAULT): FilesystemErrorCode {
        val result = parse(raw, bounds)
        assertTrue("expected invalid for <$raw>", result is PathParseResult.Invalid)
        return (result as PathParseResult.Invalid).error.code
    }

    // -- Happy path ---------------------------------------------------------

    @Test
    fun singleComponentParses() {
        assertEquals(listOf("entries"), valid("entries").components)
    }

    @Test
    fun nestedComponentsParseInOrder() {
        assertEquals(listOf("2026", "2026-07", "entries", "id"), valid("2026/2026-07/entries/id").components)
    }

    @Test
    fun multibyteUtf8ComponentsParseWithCorrectContent() {
        val path = valid("café/日本語/emoji-\uD83D\uDE00")
        assertEquals(listOf("café", "日本語", "emoji-\uD83D\uDE00"), path.components)
    }

    @Test
    fun dottedFilenamesThatAreNotDotComponentsAreAllowed() {
        assertEquals(listOf("journal-day-2026-07-23.md"), valid("journal-day-2026-07-23.md").components)
        assertEquals(listOf("a.b", "..c", "d.."), valid("a.b/..c/d..").components)
    }

    // -- Structural rejections ---------------------------------------------

    @Test
    fun emptyInputIsRejected() {
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode(""))
    }

    @Test
    fun absolutePathIsRejected() {
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("/entries"))
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("/"))
    }

    @Test
    fun trailingSlashIsRejected() {
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("entries/"))
    }

    @Test
    fun repeatedSeparatorsAreRejected() {
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("a//b"))
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("//"))
    }

    @Test
    fun dotAndDotDotComponentsAreRejected() {
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("."))
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode(".."))
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("entries/."))
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("entries/.."))
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("./entries"))
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("../entries"))
    }

    @Test
    fun parentTraversalIsRejectedBeforeResolution() {
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("entries/../../outside"))
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("a/b/../../../x"))
    }

    @Test
    fun nulIsRejected() {
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("a\u0000b"))
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("entries/\u0000"))
    }

    @Test
    fun backslashAndPlatformPathsAreRejected() {
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("a\\b"))
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("C:\\entries"))
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("entries\\id"))
    }

    @Test
    fun androidAndContentPlatformPathsAreRejected() {
        // Absolute raw path.
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("/storage/emulated/0/Journal"))
        // content:// has an empty component after the scheme's '//'.
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("content://com.android.externalstorage/tree"))
    }

    @Test
    fun unpairedSurrogatesAreInvalidUtf8() {
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("a\uD800b"))
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("a\uDC00b"))
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("\uD83D")) // high surrogate alone
    }

    // -- Bounds -------------------------------------------------------------

    @Test
    fun componentCountBoundIsEnforced() {
        val bounds = PathBounds(maxComponents = 3, maxComponentBytes = 64, maxTotalBytes = 4096)
        assertEquals(listOf("a", "b", "c"), valid("a/b/c", bounds).components)
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("a/b/c/d", bounds))
    }

    @Test
    fun componentByteBoundIsEnforcedOnUtf8Bytes() {
        val bounds = PathBounds(maxComponents = 8, maxComponentBytes = 4, maxTotalBytes = 4096)
        // "abcd" is exactly 4 bytes: allowed.
        assertEquals(listOf("abcd"), valid("abcd", bounds).components)
        // "abcde" is 5 bytes: rejected.
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("abcde", bounds))
        // "é" is 2 bytes; "ééé" is 6 bytes: rejected at 3 chars under a 4-byte bound.
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("ééé", bounds))
    }

    @Test
    fun totalByteBoundIsEnforcedAcrossComponents() {
        val bounds = PathBounds(maxComponents = 8, maxComponentBytes = 64, maxTotalBytes = 5)
        // "ab/cd" is 4 content bytes (separators excluded): allowed.
        assertEquals(listOf("ab", "cd"), valid("ab/cd", bounds).components)
        // "ab/cde" is 5 content bytes: allowed at the exact bound.
        assertEquals(listOf("ab", "cde"), valid("ab/cde", bounds).components)
        // "ab/cdef" is 6 content bytes: rejected.
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("ab/cdef", bounds))
    }

    @Test
    fun supplementaryCodePointCountsFourUtf8Bytes() {
        // 😀 is U+1F600 → 4 UTF-8 bytes.
        val bounds = PathBounds(maxComponents = 4, maxComponentBytes = 4, maxTotalBytes = 4096)
        assertEquals(listOf("\uD83D\uDE00"), valid("\uD83D\uDE00", bounds).components)
        // Two emoji = 8 bytes exceeds a 4-byte component bound.
        assertEquals(FilesystemErrorCode.E_INVALID_PATH, invalidCode("\uD83D\uDE00\uD83D\uDE01", bounds))
    }

    @Test
    fun allParseFailuresUseTheInvalidPathCode() {
        val adversarial = listOf(
            "", "/", "/a", "a/", "a//b", ".", "..", "a/./b", "a/../b", "../x",
            "a\u0000b", "a\\b", "\uD800", "a/b/c/d",
        )
        val tight = PathBounds(maxComponents = 3, maxComponentBytes = 255, maxTotalBytes = 4096)
        for (raw in adversarial) {
            assertEquals("case <$raw>", FilesystemErrorCode.E_INVALID_PATH, invalidCode(raw, tight))
        }
    }
}
