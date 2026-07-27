package io.talkcan.storage

/** Portable mount access modes. V1 admits exactly [READ_WRITE]. */
public enum class MountAccessMode {
    READ_WRITE,
}

/** Portable node kinds surfaced to callers by `stat` and `list`. */
public enum class NodeKind {
    FILE,
    DIRECTORY,
}

/**
 * Finite, host-configured bounds applied by [MountedFilesystem] to paths, text transfers, names,
 * pages, and diagnostic reasons. These are policy numbers, not Lua compatibility promises; the
 * host may tighten them. Operation/generation/process counts, deadlines, cancellation, and close
 * are enforced separately by the lifecycle layer and are not part of this policy.
 */
public data class VfsPolicy(
    val pathBounds: PathBounds = PathBounds.DEFAULT,
    /** Host ceiling for `read_text` `max_bytes`; the accepted bound is min(requested, this). */
    val maxReadBytes: Long = 1L shl 20,
    /** Host ceiling for one `write_text` payload in UTF-8 bytes. */
    val maxWriteBytes: Long = 1L shl 20,
    /** Maximum UTF-8 bytes in one returned entry or stat name. */
    val maxNameBytes: Int = 255,
    /** Maximum entries in one returned list page. */
    val maxPageSize: Int = 500,
    /** Maximum total UTF-8 name bytes across one returned list page. */
    val maxListResponseBytes: Long = 256L shl 10,
    /** Maximum UTF-8 bytes in one sanitized diagnostic reason. */
    val maxReasonBytes: Int = 256,
) {
    init {
        require(maxReadBytes > 0) { "maxReadBytes must be positive" }
        require(maxWriteBytes > 0) { "maxWriteBytes must be positive" }
        require(maxNameBytes > 0) { "maxNameBytes must be positive" }
        require(maxPageSize > 0) { "maxPageSize must be positive" }
        require(maxListResponseBytes > 0) { "maxListResponseBytes must be positive" }
        require(maxReasonBytes > 0) { "maxReasonBytes must be positive" }
    }
}

// ---------------------------------------------------------------------------
// Options (each mirrors one exact-key Lua options table)
// ---------------------------------------------------------------------------

/** `mkdir` options: required [parents]. */
public data class MkdirOptions(val parents: Boolean)

/** `list` options: bounded positive [limit] and optional opaque [cursor]. */
public data class ListOptions(
    val limit: Int,
    val cursor: ListCursor? = null,
)

/** `read_text` options: bounded positive [maxBytes]. */
public data class ReadTextOptions(val maxBytes: Long)

/** `write_text` modes. Exactly `create-new` or `replace`. */
public enum class WriteMode {
    CREATE_NEW,
    REPLACE,
}

/** `write_text` options: required [mode]. */
public data class WriteTextOptions(val mode: WriteMode)

/** `remove` options: required [missingOk]. */
public data class RemoveOptions(val missingOk: Boolean)

// ---------------------------------------------------------------------------
// Results
// ---------------------------------------------------------------------------

/** `mkdir` terminal status. */
public enum class MkdirStatus {
    CREATED,
    EXISTING,
}

public data class MkdirResult(val status: MkdirStatus)

/**
 * Portable `stat` result: bounded [name], [kind], non-negative [sizeBytes] (zero for a
 * directory), and optional [modifiedAtUnixMs] when the provider supplies it. Never exposes inode,
 * device, owner, mode, native identifier, absolute path, URI, URL, or provider object.
 */
public data class StatResult(
    val name: String,
    val kind: NodeKind,
    val sizeBytes: Long,
    val modifiedAtUnixMs: Long?,
) {
    init {
        require(sizeBytes >= 0) { "Stat size must be non-negative" }
    }
}

/** One directory entry: bounded [name] and portable [kind] only. */
public data class ListEntry(val name: String, val kind: NodeKind)

/**
 * An opaque, unforgeable listing continuation token. Callers receive it from
 * [ListPage.nextCursor] and present it back in [ListOptions.cursor]; they cannot inspect it. A
 * cursor is bound to one mount, directory, generation, and listing session and is invalidated
 * after completion, terminal pagination failure, replacement, or close.
 *
 * The host operation bridge round-trips the token across the kernel boundary as an opaque string:
 * the constructor and [token] are public to the host so it can present a cursor the kernel
 * yielded back to [ListOptions] verbatim. The token carries no meaning to a package, which only
 * ever passes the value it received; the registry revalidates the binding on every use.
 */
@JvmInline
public value class ListCursor public constructor(public val token: String)

/** One bounded list page: at most the accepted limit of [entries] plus optional [nextCursor]. */
public data class ListPage(
    val entries: List<ListEntry>,
    val nextCursor: ListCursor?,
)

/** `read_text` result: complete [text] and its exact UTF-8 [bytes] count. */
public data class ReadTextResult(val text: String, val bytes: Long)

/** `write_text` terminal status. */
public enum class WriteStatus {
    WRITTEN,
}

/** `write_text` result: [status] and the exact UTF-8 [bytes] written. */
public data class WriteResult(val status: WriteStatus, val bytes: Long)

/** `remove` terminal status. */
public enum class RemoveStatus {
    REMOVED,
    MISSING,
}

public data class RemoveResult(val status: RemoveStatus)

// ---------------------------------------------------------------------------
// Mount lookup integration
// ---------------------------------------------------------------------------

/**
 * Opaque, generation-bound access to one resolved, authorized mounted document tree.
 *
 * Produced only by [MountedStoragePort.mount] via the mount-lease registry; callers pass it back
 * to the I/O operations but cannot inspect anything it carries. It holds exactly one
 * state-local unforgeable lease token: no backend, root, platform grant, grant bytes, generation,
 * path, URI, URL, bookmark, or document identifier. The registry maps that token to the live
 * owner, declaration, access, grant fingerprint, and backend access, and revalidates it before
 * every operation. [toString] is the default identity form and reveals no field.
 */
public class MountHandle internal constructor(
    internal val leaseToken: String,
)

/**
 * One resolved mount as seen by the VFS core: the opaque [mountToken] used for cursor binding and
 * diagnostics, the declaration and generation the access is bound to, its [access] mode, the
 * opaque [grantFingerprint] (a digest of the platform grant, never the grant bytes), and the
 * [backend] plus [root] node that confine every operation. Produced by a [MountResolver]; consumed
 * only by the mount-lease registry and [MountedFilesystem]. Contains no platform value.
 */
public data class ResolvedMount(
    val mountToken: String,
    val declarationId: String,
    val generation: Long,
    val access: MountAccessMode,
    val grantFingerprint: String,
    val backend: DocumentTreeBackend,
    val root: NodeRef,
) {
    init {
        require(mountToken.isNotEmpty()) { "Mount token must not be empty" }
        require(declarationId.isNotEmpty()) { "Declaration ID must not be empty" }
        require(grantFingerprint.isNotEmpty()) { "Grant fingerprint must not be empty" }
    }
}

/** The outcome of a synchronous mount lookup. */
public sealed interface MountResolution {
    public data class Resolved(val mount: ResolvedMount) : MountResolution
    public data class Failed(val error: FilesystemError) : MountResolution
}

/**
 * Resolves a declared mount ID to opaque backend access for the current generation.
 *
 * This is the host-internal integration point implemented by the mount lease/binding layer
 * (generation-owned leases and persisted bindings live elsewhere). The VFS core depends only on
 * this contract: it performs a bounded synchronous lookup and never touches a binding store,
 * platform grant, or provider object directly.
 */
public interface MountResolver {
    public fun resolve(declarationId: String): MountResolution
}

// ---------------------------------------------------------------------------
// The port
// ---------------------------------------------------------------------------

/**
 * The platform-neutral mounted-storage port.
 *
 * This is the operation surface the host broker dispatches package filesystem calls to. It
 * exposes exactly mount lookup, `mkdir`, `stat`, paginated `list`, UTF-8 `readText`/`writeText`,
 * and nonrecursive `remove`. Every logical path is validated and confined to one mount before any
 * provider access; every failure is a normalized [FilesystemError] in the fixed portable
 * vocabulary, never a thrown exception and never a platform detail.
 *
 * Implementations are provider-independent: the same port runs over an Android SAF adapter, a
 * future iOS adapter, or an in-memory backend without changing callers.
 */
public interface MountedStoragePort {
    /**
     * Bounded synchronous mount lookup. Resolves a declared [declarationId] to an opaque
     * [MountHandle] for the current generation, or a normalized failure (for example
     * [FilesystemErrorCode.E_CAPABILITY_UNDECLARED], [FilesystemErrorCode.E_MOUNT_UNAVAILABLE],
     * [FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED]). Performs no provider I/O.
     */
    public fun mount(declarationId: String): FilesystemOutcome<MountHandle>

    /** Creates one directory (or, with [MkdirOptions.parents], the missing ancestor chain). */
    public suspend fun mkdir(
        mount: MountHandle,
        path: String,
        options: MkdirOptions,
    ): FilesystemOutcome<MkdirResult>

    /** Returns bounded portable metadata for the addressed node. */
    public suspend fun stat(
        mount: MountHandle,
        path: String,
    ): FilesystemOutcome<StatResult>

    /** Returns one bounded page of a directory's children plus an opaque continuation cursor. */
    public suspend fun list(
        mount: MountHandle,
        path: String,
        options: ListOptions,
    ): FilesystemOutcome<ListPage>

    /** Reads a complete bounded UTF-8 document; no partial text is ever published. */
    public suspend fun readText(
        mount: MountHandle,
        path: String,
        options: ReadTextOptions,
    ): FilesystemOutcome<ReadTextResult>

    /** Writes complete bounded UTF-8 text under `create-new` or `replace` semantics. */
    public suspend fun writeText(
        mount: MountHandle,
        path: String,
        text: String,
        options: WriteTextOptions,
    ): FilesystemOutcome<WriteResult>

    /** Removes exactly one file or one empty directory; never recursive. */
    public suspend fun remove(
        mount: MountHandle,
        path: String,
        options: RemoveOptions,
    ): FilesystemOutcome<RemoveResult>
}
