package io.talkcan.mount.saf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.7: Production dependency check for the SAF adapter package.
 *
 * The new adapter must never invoke `StoragePathResolver`, construct raw
 * `/storage/...` paths, or depend on `MANAGE_EXTERNAL_STORAGE`; authority
 * stays in persisted SAF `content://` grants exercised through
 * `ContentResolver`/`DocumentsContract`.
 */
class SafAdapterProductionDependencyTest {

    private fun adapterSourceDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "app/src/main/java/io/talkcan/mount/saf")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        throw AssertionError("Unable to locate the mount/saf source directory from ${System.getProperty("user.dir")}")
    }

    private fun sources(): List<File> {
        val files = adapterSourceDir().walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("Expected SAF adapter sources", files.isNotEmpty())
        return files
    }

    @Test
    fun `adapter never depends on the legacy raw path machinery`() {
        val forbidden = listOf(
            "StoragePathResolver",
            "resolveTreeUri",
            "/storage/",
            "MANAGE_EXTERNAL_STORAGE",
            "getExternalStorageDirectory",
            "android.os.Environment",
        )
        for (file in sources()) {
            val text = file.readText()
            for (token in forbidden) {
                assertTrue(
                    "${file.name} must not reference $token",
                    !text.contains(token),
                )
            }
        }
    }

    @Test
    fun `adapter persists and releases grants through the SAF permission APIs`() {
        val all = sources().joinToString("\n") { it.readText() }
        assertTrue("expected takePersistableUriPermission", all.contains("takePersistableUriPermission"))
        assertTrue("expected releasePersistableUriPermission", all.contains("releasePersistableUriPermission"))
        assertTrue("expected persistedUriPermissions", all.contains("persistedUriPermissions"))
        assertTrue("expected ACTION_OPEN_DOCUMENT_TREE", all.contains("ACTION_OPEN_DOCUMENT_TREE"))
        assertTrue("expected DocumentsContract probe", all.contains("DocumentsContract"))
    }
}
