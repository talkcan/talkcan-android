package io.talkcan.mount.saf.vfs

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.talkcan.storage.BackendNodeKind
import io.talkcan.storage.BackendResult
import io.talkcan.storage.BackendWriteMode
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation scaffolding that exercises the REAL [AndroidSafDocumentGateway]
 * and [SafDocumentTreeBackend] against a live, persisted SAF tree grant on a
 * connected device or emulator — the real-target half of OpenSpec task 4.13.
 *
 * There is no fake production fallback: this drives `ContentResolver` /
 * `DocumentsContract` directly. It requires a tree the app already holds a
 * persisted read/write grant for. Provide it as an instrumentation argument:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.treeUri='content://com.android.externalstorage.documents/tree/primary%3AJournal'
 * ```
 *
 * (First grant the tree to the app through `ACTION_OPEN_DOCUMENT_TREE` +
 * `takePersistableUriPermission`, e.g. via the app's mount picker.) When no
 * `treeUri` argument is supplied the test is skipped, not failed, so it never
 * breaks a device-less build.
 */
@RunWith(AndroidJUnit4::class)
class SafDocumentTreeBackendInstrumentationTest {

    private fun bytes(s: String): ByteArray = s.toByteArray(StandardCharsets.UTF_8)

    private fun <T> BackendResult<T>.ok(): T = (this as BackendResult.Ok<T>).value

    @Test
    fun smokeEveryOperationThroughARealSafTree(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val treeUri = args.getString("treeUri").orEmpty()
        assumeTrue("Provide -e treeUri=<persisted SAF tree> to run the real SAF smoke", treeUri.isNotBlank())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gateway = AndroidSafDocumentGateway(context.contentResolver, Uri.parse(treeUri))
        val rootId = (gateway.treeRootId(treeUri) as SafGatewayResult.Ok).value
        val backend = SafDocumentTreeBackend(gateway, rootId)
        val root = backend.rootRef

        // The granted tree root resolves and is a directory.
        assertEquals(BackendNodeKind.DIRECTORY, backend.info(root).ok().kind)

        // Use a unique smoke directory so repeated runs do not collide.
        val dirName = "talkcan-smoke-${System.currentTimeMillis()}"
        val dir = backend.createDirectory(root, dirName).ok()
        assertEquals(BackendNodeKind.DIRECTORY, backend.info(dir).ok().kind)

        // create-new write then bounded read round-trips the exact bytes.
        val file = backend.writeFile(dir, "day.md", bytes("héllo saf"), BackendWriteMode.CREATE_NEW).ok()
        assertArrayEquals(bytes("héllo saf"), backend.readFile(file, 1024).ok())

        // The child is listable and stat reports the file size.
        val listed = backend.listChildren(dir, null, 100).ok().entries.map { it.name }
        assertTrue(listed.contains("day.md"))
        assertEquals(bytes("héllo saf").size.toLong(), backend.info(file).ok().sizeBytes)

        // replace overwrites completely.
        val replaced = backend.writeFile(dir, "day.md", bytes("replaced"), BackendWriteMode.REPLACE).ok()
        assertArrayEquals(bytes("replaced"), backend.readFile(replaced, 1024).ok())

        // Nonrecursive remove: file, then the now-empty directory.
        backend.delete(replaced).ok()
        backend.delete(dir).ok()

        // The smoke directory is gone.
        val afterChildren = backend.listChildren(root, null, 500).ok().entries.map { it.name }
        assertTrue(!afterChildren.contains(dirName))
    }
}
