package io.talkcan.storage

/**
 * Opaque identity for one node (file or directory) inside a single mounted document tree.
 *
 * A platform adapter maps this to whatever native identifier it needs (for example, a Storage
 * Access Framework document ID or an iOS file-provider item identifier), but the value is opaque
 * to the VFS core and to callers: it is never a raw path, URI, URL, bookmark, or document
 * identifier on the public contract, and it is only meaningful to the backend that produced it.
 */
@JvmInline
public value class NodeRef(public val opaque: String) {
    init {
        require(opaque.isNotEmpty()) { "NodeRef must not be empty" }
    }
}

/** Portable node kinds. No other metadata kind is exposed. */
public enum class BackendNodeKind {
    FILE,
    DIRECTORY,
}

/** How a [DocumentTreeBackend.writeFile] admits a destination. */
public enum class BackendWriteMode {
    /** Fail with [BackendFailure.ALREADY_EXISTS] if the destination already exists. */
    CREATE_NEW,

    /** Overwrite the destination's complete content. */
    REPLACE,
}

/**
 * Bounded portable metadata for one node, as reported by the backend. The backend MUST NOT
 * include inode, device, owner, mode, native identifier, absolute path, URI, URL, or any
 * provider object; [modifiedAtUnixMs] is the only optional time field and may be null when the
 * provider does not supply it.
 */
public data class BackendNodeInfo(
    val name: String,
    val kind: BackendNodeKind,
    val sizeBytes: Long,
    val modifiedAtUnixMs: Long?,
) {
    init {
        require(sizeBytes >= 0) { "Node size must be non-negative" }
    }
}

/** One child entry in a directory listing page: bounded name and portable kind only. */
public data class BackendEntry(
    val name: String,
    val kind: BackendNodeKind,
)

/**
 * One page of directory children plus an opaque backend continuation token. [nextPageToken] is
 * null when the listing is exhausted. The token is meaningful only to the same backend and the
 * same directory; the VFS core wraps it in its own session-bound, unforgeable cursor.
 */
public data class BackendListPage(
    val entries: List<BackendEntry>,
    val nextPageToken: String?,
)

/**
 * The small, portable failure set a [DocumentTreeBackend] may report. The VFS core normalizes
 * these into the fixed [FilesystemErrorCode] vocabulary. A backend MUST report failures through
 * [BackendResult.Err] rather than throwing; if it does throw, the core collapses the failure to
 * [FilesystemErrorCode.E_IO] without leaking the exception. [reason] is optional and MUST be
 * sanitized by the backend (no path, URI, document ID, provider account, or device identity).
 */
public enum class BackendFailure {
    NOT_FOUND,
    ALREADY_EXISTS,
    NOT_A_DIRECTORY,
    IS_A_DIRECTORY,
    NOT_EMPTY,
    READ_ONLY,
    NO_SPACE,
    UNAVAILABLE,
    REAUTHORIZATION_REQUIRED,
    TOO_LARGE,
    BUSY,
    UNSUPPORTED,
    IO,
}

/** A backend result: a typed value or a portable [BackendFailure]. Never throws across the boundary. */
public sealed interface BackendResult<out T> {
    public data class Ok<T>(val value: T) : BackendResult<T>
    public data class Err(val failure: BackendFailure, val reason: String? = null) : BackendResult<Nothing>
}

/**
 * The generic, platform-neutral document-tree backend.
 *
 * A platform adapter (Android SAF today, an iOS file-provider adapter in the future) implements
 * this interface over its native document-tree authority. Every operation addresses nodes by
 * opaque [NodeRef] and relative child names; the backend never exposes a platform path, URI,
 * document ID, bookmark, file descriptor, SDK object, or exception to the VFS core. Each method
 * performs its own bounded provider access and releases it before returning.
 *
 * The VFS core ([MountedFilesystem]) is the only consumer; it validates logical paths, confines
 * resolution beneath a mount root, applies finite bounds, and normalizes [BackendResult] into the
 * fixed portable failure vocabulary before any result reaches a caller.
 */
public interface DocumentTreeBackend {
    /**
     * Resolves the immediate child of [parent] named [name]. Returns [BackendFailure.NOT_FOUND]
     * when no such child exists. Does not create anything.
     */
    public suspend fun child(parent: NodeRef, name: String): BackendResult<NodeRef>

    /** Reports bounded portable metadata for [node]. */
    public suspend fun info(node: NodeRef): BackendResult<BackendNodeInfo>

    /**
     * Creates a directory child of [parent] named [name]. Returns [BackendFailure.ALREADY_EXISTS]
     * when the name is already taken (by a file or a directory).
     */
    public suspend fun createDirectory(parent: NodeRef, name: String): BackendResult<NodeRef>

    /**
     * Returns one page of at most [limit] children of the directory [parent], resuming at the
     * backend's opaque [pageToken] (null starts at the beginning). Iteration order is
     * unspecified but MUST be stable for a given [pageToken] lineage so pagination neither
     * duplicates nor skips entries.
     */
    public suspend fun listChildren(parent: NodeRef, pageToken: String?, limit: Int): BackendResult<BackendListPage>

    /**
     * Reads the complete content of the file [node]. Returns the whole payload when it is at
     * most [maxBytes]; otherwise returns [BackendFailure.TOO_LARGE] with no bytes (no partial
     * read is ever published).
     */
    public suspend fun readFile(node: NodeRef, maxBytes: Long): BackendResult<ByteArray>

    /**
     * Writes [bytes] to a file named [name] in [parent] under [mode]. The write is
     * complete-on-success: on [BackendResult.Ok] the complete content is visible, and on any
     * failure no partial or staged node is left behind (the backend cleans up its own staging).
     * [BackendWriteMode.CREATE_NEW] fails with [BackendFailure.ALREADY_EXISTS] when [name] exists.
     */
    public suspend fun writeFile(
        parent: NodeRef,
        name: String,
        bytes: ByteArray,
        mode: BackendWriteMode,
    ): BackendResult<NodeRef>

    /**
     * Deletes exactly one file or one empty directory. Returns [BackendFailure.NOT_EMPTY] when a
     * directory still has children (removal is never recursive). Descendants are never touched.
     */
    public suspend fun delete(node: NodeRef): BackendResult<Unit>
}
