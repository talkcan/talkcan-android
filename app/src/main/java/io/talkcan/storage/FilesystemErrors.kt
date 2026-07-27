package io.talkcan.storage

import java.nio.charset.StandardCharsets

/**
 * The fixed portable filesystem failure vocabulary.
 *
 * Every expected filesystem failure returned by [MountedStoragePort] carries exactly one of
 * these codes. The set is closed: unknown platform failures collapse to [E_IO]. Codes are
 * language-neutral and never encode Android exceptions, URIs, document IDs, raw paths, iOS
 * error domains, URLs, bookmarks, provider accounts, or device identity.
 */
public enum class FilesystemErrorCode {
    /** A caller-supplied argument or options table was malformed or out of bounds. */
    E_INVALID_ARGUMENT,

    /** A logical path failed canonical mount-relative validation. */
    E_INVALID_PATH,

    /** The call was made from a context that is not permitted to perform filesystem I/O. */
    E_INVALID_CONTEXT,

    /** The package did not declare the storage capability or the named mount. */
    E_CAPABILITY_UNDECLARED,

    /** The addressed mount is not currently usable. */
    E_MOUNT_UNAVAILABLE,

    /** The mount grant must be reauthorized by the user before I/O can proceed. */
    E_REAUTHORIZATION_REQUIRED,

    /** The mount is read-only and the operation requires write access. */
    E_READ_ONLY,

    /** The addressed node does not exist. */
    E_NOT_FOUND,

    /** A create-new destination already exists. */
    E_EXISTS,

    /** A path component that must be a directory is not a directory. */
    E_NOT_DIRECTORY,

    /** The addressed node is a directory where a file was required. */
    E_IS_DIRECTORY,

    /** A document, name, path, or transfer exceeds a finite bound. */
    E_TOO_LARGE,

    /** The underlying store has no space for the operation. */
    E_NO_SPACE,

    /** The node is busy (for example, a non-empty directory addressed by nonrecursive remove). */
    E_BUSY,

    /** The operation exceeded its bounded deadline. */
    E_TIMEOUT,

    /** The operation was explicitly cancelled. */
    E_CANCELLED,

    /** The owning generation, mount handle, or session is closed. */
    E_CLOSED,

    /** A continuation token is stale, foreign, or no longer valid. */
    E_STALE,

    /** The operation or content is not supported by this mount. */
    E_UNSUPPORTED,

    /** An otherwise unclassified I/O failure. */
    E_IO,
}

/**
 * A normalized filesystem failure.
 *
 * [reason] is optional, bounded to [MountedStoragePort] policy bytes, language-neutral, and
 * sanitized: it never carries platform exceptions, paths, URIs, document IDs, URLs, bookmarks,
 * provider accounts, or device identity. Callers MUST NOT parse [reason]; it is diagnostic only.
 */
public data class FilesystemError(
    val code: FilesystemErrorCode,
    val reason: String? = null,
)

/**
 * The terminal result of one admitted filesystem operation. Mirrors the Lua `(result, err)`
 * pair: exactly one of a typed [Success] value or a normalized [Failure] is produced, and the
 * operation never throws across the host boundary.
 */
public sealed interface FilesystemOutcome<out T> {
    public data class Success<T>(val value: T) : FilesystemOutcome<T>
    public data class Failure(val error: FilesystemError) : FilesystemOutcome<Nothing>
}

/**
 * Builds bounded, sanitized diagnostic reasons. Any reason longer than [maxReasonBytes] UTF-8
 * bytes is truncated on a code-point boundary; platform detail is never appended by callers.
 */
internal class ReasonSanitizer(private val maxReasonBytes: Int) {
    init {
        require(maxReasonBytes > 0) { "Reason bound must be positive" }
    }

    fun sanitize(reason: String?): String? {
        if (reason == null) return null
        val trimmed = reason.trim()
        if (trimmed.isEmpty()) return null
        val bytes = trimmed.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= maxReasonBytes) return trimmed
        // Truncate on a UTF-8 code-point boundary so the reason stays valid UTF-8.
        var end = maxReasonBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) {
            end--
        }
        return String(bytes, 0, end, StandardCharsets.UTF_8)
    }

    fun error(code: FilesystemErrorCode, reason: String? = null): FilesystemError =
        FilesystemError(code, sanitize(reason))
}
