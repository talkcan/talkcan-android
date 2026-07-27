package io.talkcan.mount.saf.vfs

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * Production [SafDocumentGateway] over the app's [ContentResolver].
 *
 * Every operation addresses documents through `DocumentsContract` URIs built
 * from the granted tree; the tree is never resolved to a filesystem path and the
 * legacy all-files storage permission is never used. Each method performs its
 * own bounded provider access, validates the provider's cursor rows strictly,
 * and closes every cursor, stream, and file descriptor before returning. Open
 * streams are handed to the caller, which owns closing them.
 *
 * Provider disappearance, revoked grants, moved/deleted roots, read-only trees,
 * malformed cursors, quota exhaustion, and unsupported operations are all
 * normalized to [SafGatewayResult.Failed]; no Android exception, URI, document
 * ID, or provider detail ever crosses this boundary.
 */
class AndroidSafDocumentGateway(
    private val contentResolver: ContentResolver,
    private val treeUri: Uri,
) : SafDocumentGateway {

    override fun treeRootId(treeUri: String): SafGatewayResult<String> {
        val parsed = parseTree(treeUri) ?: return SafGatewayResult.Failed(SafGatewayFailure.UNAVAILABLE)
        return try {
            val rootId = DocumentsContract.getTreeDocumentId(parsed)
            if (rootId.isNullOrEmpty()) {
                SafGatewayResult.Failed(SafGatewayFailure.UNAVAILABLE)
            } else {
                SafGatewayResult.Ok(rootId)
            }
        } catch (_: IllegalArgumentException) {
            SafGatewayResult.Failed(SafGatewayFailure.UNAVAILABLE)
        } catch (_: RuntimeException) {
            SafGatewayResult.Failed(SafGatewayFailure.UNAVAILABLE)
        }
    }

    override fun queryDocument(documentId: String): SafGatewayResult<SafDocumentRow> {
        val uri = documentUri(documentId) ?: return SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, QUERY_COLUMNS, null, null, null)
                ?: return SafGatewayResult.Failed(SafGatewayFailure.UNAVAILABLE)
            if (!cursor.moveToFirst()) {
                return SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
            }
            readRow(cursor)
        } catch (_: FileNotFoundException) {
            SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        } catch (_: SecurityException) {
            SafGatewayResult.Failed(SafGatewayFailure.REVOKED)
        } catch (_: IllegalArgumentException) {
            SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        } catch (failure: UnsupportedOperationException) {
            Log.w(DIAGNOSTIC_TAG, "query_document_unsupported type=${failure.javaClass.simpleName}")
            SafGatewayResult.Failed(SafGatewayFailure.UNSUPPORTED)
        } catch (_: NullPointerException) {
            SafGatewayResult.Failed(SafGatewayFailure.UNAVAILABLE)
        } catch (_: RuntimeException) {
            SafGatewayResult.Failed(SafGatewayFailure.IO)
        } finally {
            cursor?.close()
        }
    }

    override fun queryChildren(documentId: String): SafGatewayResult<List<SafDocumentRow>> {
        val uri = childrenUri(documentId) ?: return SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, CHILD_QUERY_COLUMNS, null, null, null)
                ?: return SafGatewayResult.Failed(SafGatewayFailure.UNAVAILABLE)
            val rows = ArrayList<SafDocumentRow>()
            while (cursor.moveToNext()) {
                when (val row = readRow(cursor)) {
                    is SafGatewayResult.Ok -> rows.add(row.value)
                    is SafGatewayResult.Failed -> return row
                }
            }
            // Sort in the adapter so pagination order is stable and provider-independent.
            rows.sortBy { it.displayName }
            SafGatewayResult.Ok(rows)
        } catch (_: FileNotFoundException) {
            SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        } catch (_: SecurityException) {
            SafGatewayResult.Failed(SafGatewayFailure.REVOKED)
        } catch (_: IllegalArgumentException) {
            SafGatewayResult.Failed(SafGatewayFailure.NOT_A_DIRECTORY)
        } catch (_: UnsupportedOperationException) {
            SafGatewayResult.Failed(SafGatewayFailure.UNSUPPORTED)
        } catch (_: NullPointerException) {
            SafGatewayResult.Failed(SafGatewayFailure.UNAVAILABLE)
        } catch (failure: RuntimeException) {
            Log.w(DIAGNOSTIC_TAG, "query_children_runtime type=${failure.javaClass.simpleName}")
            SafGatewayResult.Failed(SafGatewayFailure.IO)
        } finally {
            closeCursorQuietly(cursor, "query_children")
        }
    }

    override fun hasChildren(documentId: String): SafGatewayResult<Boolean> {
        val uri = childrenUri(documentId) ?: return SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            ) ?: return SafGatewayResult.Failed(SafGatewayFailure.UNAVAILABLE)
            SafGatewayResult.Ok(cursor.moveToFirst())
        } catch (_: FileNotFoundException) {
            SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        } catch (_: SecurityException) {
            SafGatewayResult.Failed(SafGatewayFailure.REVOKED)
        } catch (_: IllegalArgumentException) {
            SafGatewayResult.Failed(SafGatewayFailure.NOT_A_DIRECTORY)
        } catch (_: UnsupportedOperationException) {
            SafGatewayResult.Failed(SafGatewayFailure.UNSUPPORTED)
        } catch (_: NullPointerException) {
            SafGatewayResult.Failed(SafGatewayFailure.UNAVAILABLE)
        } catch (_: RuntimeException) {
            SafGatewayResult.Failed(SafGatewayFailure.IO)
        } finally {
            cursor?.close()
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        directory: Boolean,
        displayName: String,
    ): SafGatewayResult<String> {
        val parentUri = documentUri(parentDocumentId)
            ?: return SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        val mimeType = if (directory) {
            DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            FILE_MIME_TYPE
        }
        return try {
            val created = DocumentsContract.createDocument(contentResolver, parentUri, mimeType, displayName)
                ?: return SafGatewayResult.Failed(SafGatewayFailure.IO)
            val newId = DocumentsContract.getDocumentId(created)
            if (newId.isNullOrEmpty()) {
                // Roll back a document we cannot identify so no orphan is left behind.
                runCatching { DocumentsContract.deleteDocument(contentResolver, created) }
                SafGatewayResult.Failed(SafGatewayFailure.IO)
            } else {
                SafGatewayResult.Ok(newId)
            }
        } catch (_: FileNotFoundException) {
            SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        } catch (_: SecurityException) {
            SafGatewayResult.Failed(SafGatewayFailure.REVOKED)
        } catch (_: IllegalArgumentException) {
            // Providers signal a duplicate display name or a non-directory parent this way.
            SafGatewayResult.Failed(SafGatewayFailure.ALREADY_EXISTS)
        } catch (_: UnsupportedOperationException) {
            SafGatewayResult.Failed(SafGatewayFailure.UNSUPPORTED)
        } catch (_: NullPointerException) {
            SafGatewayResult.Failed(SafGatewayFailure.UNAVAILABLE)
        } catch (_: RuntimeException) {
            SafGatewayResult.Failed(SafGatewayFailure.IO)
        }
    }

    override fun deleteDocument(documentId: String): SafGatewayResult<Unit> {
        val uri = documentUri(documentId) ?: return SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        return try {
            val deleted = DocumentsContract.deleteDocument(contentResolver, uri)
            if (deleted) {
                SafGatewayResult.Ok(Unit)
            } else {
                SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
            }
        } catch (_: FileNotFoundException) {
            SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        } catch (_: SecurityException) {
            SafGatewayResult.Failed(SafGatewayFailure.REVOKED)
        } catch (_: IllegalArgumentException) {
            SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        } catch (_: UnsupportedOperationException) {
            SafGatewayResult.Failed(SafGatewayFailure.UNSUPPORTED)
        } catch (_: NullPointerException) {
            SafGatewayResult.Failed(SafGatewayFailure.UNAVAILABLE)
        } catch (_: RuntimeException) {
            SafGatewayResult.Failed(SafGatewayFailure.IO)
        }
    }

    override fun openRead(documentId: String): SafGatewayResult<InputStream> {
        val uri = documentUri(documentId) ?: return SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        return try {
            val stream = contentResolver.openInputStream(uri)
                ?: return SafGatewayResult.Failed(SafGatewayFailure.UNAVAILABLE)
            SafGatewayResult.Ok(stream)
        } catch (_: FileNotFoundException) {
            SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        } catch (_: SecurityException) {
            SafGatewayResult.Failed(SafGatewayFailure.REVOKED)
        } catch (_: IllegalArgumentException) {
            SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        } catch (failure: UnsupportedOperationException) {
            Log.w(DIAGNOSTIC_TAG, "open_read_unsupported type=${failure.javaClass.simpleName}")
            SafGatewayResult.Failed(SafGatewayFailure.UNSUPPORTED)
        } catch (_: NullPointerException) {
            SafGatewayResult.Failed(SafGatewayFailure.UNAVAILABLE)
        } catch (_: RuntimeException) {
            SafGatewayResult.Failed(SafGatewayFailure.IO)
        }
    }

    override fun openWrite(documentId: String, truncate: Boolean): SafGatewayResult<OutputStream> {
        val uri = documentUri(documentId) ?: return SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        val mode = if (truncate) MODE_TRUNCATE else MODE_WRITE
        return try {
            val stream = contentResolver.openOutputStream(uri, mode)
                ?: return SafGatewayResult.Failed(SafGatewayFailure.UNAVAILABLE)
            SafGatewayResult.Ok(QuotaTranslatingOutputStream(stream))
        } catch (_: FileNotFoundException) {
            SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        } catch (_: SecurityException) {
            SafGatewayResult.Failed(SafGatewayFailure.REVOKED)
        } catch (_: IllegalArgumentException) {
            SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        } catch (_: UnsupportedOperationException) {
            SafGatewayResult.Failed(SafGatewayFailure.UNSUPPORTED)
        } catch (_: NullPointerException) {
            SafGatewayResult.Failed(SafGatewayFailure.UNAVAILABLE)
        } catch (_: RuntimeException) {
            SafGatewayResult.Failed(SafGatewayFailure.IO)
        }
    }

    /** Strictly reads one row at the cursor position; any structural defect is [SafGatewayFailure.MALFORMED]. */
    private fun readRow(cursor: Cursor): SafGatewayResult<SafDocumentRow> {
        val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) {
            return SafGatewayResult.Failed(SafGatewayFailure.MALFORMED)
        }
        val id = cursor.getString(idIndex)
        val name = cursor.getString(nameIndex)
        val mime = cursor.getString(mimeIndex)
        if (id.isNullOrEmpty() || name == null || mime == null) {
            return SafGatewayResult.Failed(SafGatewayFailure.MALFORMED)
        }
        val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex).coerceAtLeast(0L) else 0L
        val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else null
        return SafGatewayResult.Ok(
            SafDocumentRow(
                id = id,
                displayName = name,
                directory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                sizeBytes = size,
                lastModifiedMs = modified,
            ),
        )
    }

    private fun documentUri(documentId: String): Uri? = try {
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    } catch (_: RuntimeException) {
        null
    }

    private fun childrenUri(documentId: String): Uri? = try {
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
    } catch (_: RuntimeException) {
        null
    }

    private fun parseTree(uri: String): Uri? {
        val parsed = try {
            Uri.parse(uri)
        } catch (_: RuntimeException) {
            return null
        }
        return if (parsed.scheme == ContentResolver.SCHEME_CONTENT) parsed else null
    }

    private fun closeCursorQuietly(cursor: Cursor?, operation: String) {
        try {
            cursor?.close()
        } catch (failure: RuntimeException) {
            Log.w(DIAGNOSTIC_TAG, "${operation}_close_runtime type=${failure.javaClass.simpleName}")
        }
    }

    private companion object {
        val CHILD_QUERY_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val QUERY_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        const val DIAGNOSTIC_TAG = "TalkcanStorage"
        const val FILE_MIME_TYPE = "application/octet-stream"
        const val MODE_TRUNCATE = "wt"
        const val MODE_WRITE = "w"
    }
}


/**
 * Wraps a provider output stream so a storage-quota exhaustion (`ENOSPC`)
 * surfaces as the leak-free [SafQuotaException] instead of a provider
 * exception. Every other byte passes through unchanged; closing the wrapper
 * closes the underlying stream and its file descriptor.
 */
private class QuotaTranslatingOutputStream(
    private val delegate: OutputStream,
) : OutputStream() {
    override fun write(b: Int) = translate { delegate.write(b) }
    override fun write(b: ByteArray) = translate { delegate.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) = translate { delegate.write(b, off, len) }
    override fun flush() = translate { delegate.flush() }
    override fun close() = translate { delegate.close() }

    private inline fun translate(block: () -> Unit) {
        try {
            block()
        } catch (io: java.io.IOException) {
            if (io.isNoSpace()) throw SafQuotaException() else throw io
        }
    }

    private fun java.io.IOException.isNoSpace(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is ErrnoException && cause.errno == OsConstants.ENOSPC) return true
            cause = cause.cause
        }
        return false
    }
}