package io.talkcan.mount.saf.vfs

import java.io.InputStream
import java.io.OutputStream

/**
 * One metadata row for a SAF document, already reduced to portable fields.
 *
 * The document identifier [id] is adapter-private: it only ever travels between
 * [SafDocumentGateway] and [SafDocumentTreeBackend] inside this package and is
 * wrapped in an opaque `NodeRef` before it reaches the VFS core. It is never a
 * raw path, and no URI, MIME type, provider account, or device identity crosses
 * this boundary. [directory] is pre-resolved from the provider MIME type so the
 * backend never sees a MIME string.
 */
data class SafDocumentRow(
    val id: String,
    val displayName: String,
    val directory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long?,
)

/**
 * The small, portable failure set a [SafDocumentGateway] may report.
 *
 * These are normalized platform facts: the gateway maps Android/provider
 * exceptions onto this set and never forwards exception text, URIs, document
 * IDs, or provider detail. [SafDocumentTreeBackend] maps them onto the generic
 * `BackendFailure` vocabulary.
 */
enum class SafGatewayFailure {
    /** The addressed document does not exist (deleted, moved, or unknown). */
    NOT_FOUND,

    /** A create collided with an existing display name. */
    ALREADY_EXISTS,

    /** The addressed parent is a file where a directory was required. */
    NOT_A_DIRECTORY,

    /** The addressed node is a directory where a file was required. */
    IS_A_DIRECTORY,

    /** A directory addressed for nonrecursive deletion still has children. */
    NOT_EMPTY,

    /** The provider rejected a write because the tree is read-only. */
    READ_ONLY,

    /** The provider reported the store is out of space / quota exhausted. */
    NO_SPACE,

    /** The persisted grant was revoked or the provider denied access. */
    REVOKED,

    /** The provider vanished or the tree root is unreachable. */
    UNAVAILABLE,

    /** The operation is not supported by this provider. */
    UNSUPPORTED,

    /** The provider returned a malformed cursor or structurally invalid row. */
    MALFORMED,

    /** An otherwise unclassified I/O failure. */
    IO,
}

/** A gateway result: a typed value or a portable [SafGatewayFailure]. Never throws across the boundary. */
sealed interface SafGatewayResult<out T> {
    data class Ok<T>(val value: T) : SafGatewayResult<T>
    data class Failed(val failure: SafGatewayFailure) : SafGatewayResult<Nothing>
}

/**
 * The narrow platform boundary for SAF document-tree I/O.
 *
 * This port is the only place the storage backend touches `ContentResolver` /
 * `DocumentsContract`; the backend and its tests stay platform-free. Every
 * method performs its own bounded provider access and releases every cursor,
 * stream, and file descriptor before returning (open streams are handed to the
 * caller, which owns closing them). A vanished provider, revoked grant,
 * moved/deleted root, read-only tree, malformed cursor, or quota exhaustion is
 * reported as an ordinary [SafGatewayResult.Failed], never a thrown exception.
 */
interface SafDocumentGateway {
    /** Resolves the tree root document identifier for a granted tree URI string. */
    fun treeRootId(treeUri: String): SafGatewayResult<String>

    /** Reports portable metadata for one document; [SafGatewayFailure.NOT_FOUND] when it is gone. */
    fun queryDocument(documentId: String): SafGatewayResult<SafDocumentRow>

    /**
     * Returns every immediate child of the directory [documentId], ordered by
     * display name (stable, provider-independent). The cursor is fully consumed
     * and closed before returning.
     */
    fun queryChildren(documentId: String): SafGatewayResult<List<SafDocumentRow>>

    /** Reports whether the directory [documentId] has at least one child. */
    fun hasChildren(documentId: String): SafGatewayResult<Boolean>

    /**
     * Creates a child of [parentDocumentId] named [displayName]; a directory when
     * [directory] is true, otherwise a regular file. Returns the new document id.
     */
    fun createDocument(parentDocumentId: String, directory: Boolean, displayName: String): SafGatewayResult<String>

    /** Deletes exactly one document; reports [SafGatewayFailure.NOT_FOUND] when already gone. */
    fun deleteDocument(documentId: String): SafGatewayResult<Unit>

    /** Opens the file [documentId] for reading; the caller owns closing the stream. */
    fun openRead(documentId: String): SafGatewayResult<InputStream>

    /**
     * Opens the file [documentId] for writing; when [truncate] is true the
     * existing content is replaced from offset zero. The caller owns closing the
     * stream (which releases the underlying file descriptor).
     */
    fun openWrite(documentId: String, truncate: Boolean): SafGatewayResult<OutputStream>
}

/**
 * Leak-free signal that a document write exhausted the provider's storage quota.
 *
 * A gateway's write stream throws this instead of a provider exception so the
 * backend can map quota exhaustion to `BackendFailure.NO_SPACE` without ever
 * seeing exception text, a URI, or a document ID. Carries no detail by design.
 */
class SafQuotaException : java.io.IOException()
