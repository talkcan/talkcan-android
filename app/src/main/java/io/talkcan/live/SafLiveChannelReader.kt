package io.talkcan.live

import android.content.ContentResolver
import android.net.Uri
import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ChannelImplementationDescriptor
import io.talkcan.model.ChannelImplementationId
import io.talkcan.mount.saf.SafGrantController
import io.talkcan.mount.saf.vfs.AndroidSafDocumentGateway
import io.talkcan.mount.saf.vfs.SafMountLeaseRevalidator
import io.talkcan.mount.saf.vfs.SafVfsMountFactory
import io.talkcan.mount.saf.vfs.SafVfsMountResolver
import io.talkcan.resource.MountBindingStore
import io.talkcan.storage.FilesystemError
import io.talkcan.storage.FilesystemErrorCode
import io.talkcan.storage.FilesystemOutcome
import io.talkcan.storage.LeaseOwner
import io.talkcan.storage.ListCursor
import io.talkcan.storage.ListOptions
import io.talkcan.storage.MountLeaseRegistry
import io.talkcan.storage.MountedFilesystem
import io.talkcan.storage.NodeKind
import io.talkcan.storage.ReadTextOptions
import java.util.UUID
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * SAF-backed [LiveChannelReader] confined to declared active-channel mounts.
 *
 * Composition mirrors `AndroidLuaRuntimeResourcesFactory`: one [SafVfsMountFactory] over an
 * [AndroidSafDocumentGateway], one [SafVfsMountResolver] bound to the channel instance, one
 * [SafMountLeaseRevalidator] for per-operation grant rechecks, one [MountLeaseRegistry] for
 * unforgeable lease tokens, and one [MountedFilesystem] core for canonical confinement,
 * paginated list cursors, and strict bounded UTF-8 reads. The only platform touch is the
 * validated SAF tree through the gateway; private host files and secret stores are never
 * read (no `ProtectedSecretStore`, no direct `ContentResolver` file access outside the
 * declared tree).
 *
 * Session discipline:
 * - Only the current [definitions] entry for [channelId] is served, and only when it is
 *   enabled. [descriptor] supplies the declared mounts; a null descriptor (unsupported
 *   channel) yields honestly empty mounts and `unknown_mount` for list/read — never fake
 *   content. `read` returns the stored channel bytes verbatim, never a synthesized
 *   transcript.
 * - Filesystems are cached per channel (bounded, LRU-evicted) so [MountedFilesystem]
 *   listing cursors stay bound to their mount/directory/generation/session across pages.
 *   A stale or foreign cursor is rejected by the core (`stale`).
 * - The captured definition is revalidated before and after every suspending operation;
 *   a changed definition closes and evicts that channel's filesystem and the late result
 *   is suppressed as `stale`. Mount grants revalidate on every I/O through the lease
 *   revalidator, and the publication guard suppresses late success after revocation/close.
 * - Every operation runs under a finite wall-clock deadline ([OPERATION_TIMEOUT_MS] outside
 *   the core's own 30s deadline). Cooperative cancellation is rethrown.
 * - [close] closes every owned filesystem/lease. Idempotent.
 *
 * Channel names, entry names, and file text are untrusted data: returned verbatim as JSON
 * strings, never interpreted as instructions.
 */
public class SafLiveChannelReader(
    private val contentResolver: ContentResolver,
    private val bindings: MountBindingStore,
    private val grants: SafGrantController,
    private val definitions: () -> List<ChannelDefinition>,
    private val descriptor: (ChannelImplementationId) -> ChannelImplementationDescriptor?,
    private val sessionIsActive: () -> Boolean,
) : LiveChannelReader {

    public companion object {
        /** Per-reader cached filesystems (one per channel); eldest evicted and closed. */
        public const val MAX_CACHED_FILESYSTEMS: Int = 8

        /** Outer finite deadline per list/read, above the VFS core's own 30s deadline. */
        public const val OPERATION_TIMEOUT_MS: Long = 35_000L

        public const val MAX_LIST_LIMIT: Int = 50
        public const val MAX_READ_BYTES: Int = 32768
        public const val MAX_ID_CHARS: Int = 128
        public const val MAX_PATH_CHARS: Int = 1024
        public const val MAX_PATH_BYTES: Int = 4096
        public const val MAX_CURSOR_CHARS: Int = 512

        private const val GENERATION: Long = 1L
    }

    private data class CachedEntry(
        val definition: ChannelDefinition,
        val filesystem: MountedFilesystem,
    )

    private val lock = Any()
    @Volatile private var closed = false
    private val stateId: String = UUID.randomUUID().toString()
    private val cache = LinkedHashMap<String, CachedEntry>(MAX_CACHED_FILESYSTEMS + 1, 0.75f, true)

    override suspend fun mounts(channelId: String): JSONArray {
        if (closed || !sessionIsActive()) return JSONArray()
        if (channelId.isBlank()) return JSONArray()
        val definition = try {
            definitions().firstOrNull { it.id == channelId }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            return JSONArray()
        } ?: return JSONArray()
        if (!definition.enabled) return JSONArray()
        val mounts = try {
            descriptor(definition.implementationId)?.resourceDeclarations?.mounts
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            null
        } ?: return JSONArray()
        val out = JSONArray()
        for (mount in mounts.take(MAX_CACHED_FILESYSTEMS)) {
            out.put(
                JSONObject()
                    .put("mount_id", mount.id)
                    .put("label", mount.label),
            )
        }
        return out
    }

    override suspend fun list(
        channelId: String,
        mountId: String,
        path: String,
        limit: Int,
        cursor: String?,
    ): JSONObject {
        if (closed || !sessionIsActive()) return error("session_closed", "live session is closed")
        if (limit !in 1..MAX_LIST_LIMIT) return error("invalid_limit", "limit must be an integer in 1..$MAX_LIST_LIMIT")
        if (channelId.isBlank() || channelId.length > MAX_ID_CHARS) {
            return error("invalid_channel_id", "channel_id is invalid")
        }
        if (mountId.isBlank() || mountId.length > MAX_ID_CHARS) {
            return error("invalid_mount_id", "mount_id is invalid")
        }
        if (cursor != null && (cursor.isBlank() || cursor.length > MAX_CURSOR_CHARS)) {
            return error("invalid_cursor", "cursor is invalid")
        }
        if (path.length > MAX_PATH_CHARS || path.toByteArray(Charsets.UTF_8).size > MAX_PATH_BYTES) {
            return error("invalid_path", "path exceeds the path bound")
        }
        val defBefore = try {
            definitions().firstOrNull { it.id == channelId }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            return error("io_error", "unable to read channel definitions")
        } ?: return error("unknown_channel", "channel not found")
        if (!defBefore.enabled) return error("channel_disabled", "channel is disabled")
        val declared = try {
            descriptor(defBefore.implementationId)?.resourceDeclarations?.mounts
                ?.firstOrNull { it.id == mountId }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            null
        } ?: return error("unknown_mount", "mount is not declared for this channel")
        // `declared` is the confinement proof: only this mount id may be opened below.
        @Suppress("UNUSED_VARIABLE")
        val confinement = declared.id

        val filesystem = synchronized(lock) {
            if (closed) return error("session_closed", "live session is closed")
            entryFor(defBefore)
        }
        if (closed || !sessionIsActive()) return error("session_closed", "live session is closed")
        if (definitionNow(channelId) != defBefore) {
            synchronized(lock) { closeChannelLocked(channelId) }
            return error("stale", "channel definition changed")
        }
        val outcome = try {
            withTimeout(OPERATION_TIMEOUT_MS) {
                val mountOutcome = filesystem.mount(mountId)
                val handle = when (mountOutcome) {
                    is FilesystemOutcome.Success -> mountOutcome.value
                    is FilesystemOutcome.Failure -> return@withTimeout failure(mountOutcome.error)
                }
                when (
                    val page = filesystem.list(
                        handle,
                        path,
                        ListOptions(limit = limit, cursor = cursor?.let { ListCursor(it) }),
                    )
                ) {
                    is FilesystemOutcome.Success -> {
                        val entries = JSONArray()
                        for (entry in page.value.entries) {
                            entries.put(
                                JSONObject()
                                    .put("name", entry.name)
                                    .put("kind", kindName(entry.kind)),
                            )
                        }
                        val next = page.value.nextCursor
                        JSONObject()
                            .put("entries", entries)
                            .put("next_cursor", next?.token ?: JSONObject.NULL)
                    }
                    is FilesystemOutcome.Failure -> failure(page.error)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            return error("timeout", "channel file listing timed out")
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            return error("io_error", "channel file listing failed")
        }
        if (closed || !sessionIsActive()) return error("session_closed", "live session is closed")
        if (definitionNow(channelId) != defBefore) {
            synchronized(lock) { closeChannelLocked(channelId) }
            return error("stale", "channel definition changed")
        }
        return outcome
    }

    override suspend fun read(
        channelId: String,
        mountId: String,
        path: String,
        maxBytes: Int,
    ): JSONObject {
        if (closed || !sessionIsActive()) return error("session_closed", "live session is closed")
        if (maxBytes !in 1..MAX_READ_BYTES) {
            return error("invalid_max_bytes", "max_bytes must be an integer in 1..$MAX_READ_BYTES")
        }
        if (channelId.isBlank() || channelId.length > MAX_ID_CHARS) {
            return error("invalid_channel_id", "channel_id is invalid")
        }
        if (mountId.isBlank() || mountId.length > MAX_ID_CHARS) {
            return error("invalid_mount_id", "mount_id is invalid")
        }
        if (path.isEmpty() || path.length > MAX_PATH_CHARS ||
            path.toByteArray(Charsets.UTF_8).size > MAX_PATH_BYTES
        ) {
            return error("invalid_path", "path is invalid")
        }
        val defBefore = try {
            definitions().firstOrNull { it.id == channelId }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            return error("io_error", "unable to read channel definitions")
        } ?: return error("unknown_channel", "channel not found")
        if (!defBefore.enabled) return error("channel_disabled", "channel is disabled")
        val declared = try {
            descriptor(defBefore.implementationId)?.resourceDeclarations?.mounts
                ?.firstOrNull { it.id == mountId }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            null
        } ?: return error("unknown_mount", "mount is not declared for this channel")
        @Suppress("UNUSED_VARIABLE")
        val confinement = declared.id

        val filesystem = synchronized(lock) {
            if (closed) return error("session_closed", "live session is closed")
            entryFor(defBefore)
        }
        if (closed || !sessionIsActive()) return error("session_closed", "live session is closed")
        if (definitionNow(channelId) != defBefore) {
            synchronized(lock) { closeChannelLocked(channelId) }
            return error("stale", "channel definition changed")
        }
        val outcome = try {
            withTimeout(OPERATION_TIMEOUT_MS) {
                val mountOutcome = filesystem.mount(mountId)
                val handle = when (mountOutcome) {
                    is FilesystemOutcome.Success -> mountOutcome.value
                    is FilesystemOutcome.Failure -> return@withTimeout failure(mountOutcome.error)
                }
                when (
                    val read = filesystem.readText(handle, path, ReadTextOptions(maxBytes.toLong()))
                ) {
                    is FilesystemOutcome.Success ->
                        JSONObject()
                            .put("text", read.value.text)
                            .put("bytes", read.value.bytes)
                    is FilesystemOutcome.Failure -> failure(read.error)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            return error("timeout", "channel file read timed out")
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            return error("io_error", "channel file read failed")
        }
        if (closed || !sessionIsActive()) return error("session_closed", "live session is closed")
        if (definitionNow(channelId) != defBefore) {
            synchronized(lock) { closeChannelLocked(channelId) }
            return error("stale", "channel definition changed")
        }
        return outcome
    }

    override suspend fun close() {
        synchronized(lock) {
            closed = true
            for (entry in cache.values) {
                try {
                    entry.filesystem.close()
                } catch (_: Throwable) {
                    // Close is best-effort and idempotent; never throws across the boundary.
                }
            }
            cache.clear()
        }
    }

    // ------------------------------------------------------------------
    // Cache (caller holds [lock] unless noted)
    // ------------------------------------------------------------------

    private fun entryFor(definition: ChannelDefinition): MountedFilesystem {
        val cached = cache[definition.id]
        if (cached != null) {
            if (cached.definition == definition) return cached.filesystem
            try {
                cached.filesystem.close()
            } catch (_: Throwable) {
            }
            cache.remove(definition.id)
        }
        while (cache.size >= MAX_CACHED_FILESYSTEMS) {
            val eldest = cache.keys.firstOrNull() ?: break
            val removed = cache.remove(eldest)
            try {
                removed?.filesystem?.close()
            } catch (_: Throwable) {
            }
        }
        val filesystem = createFilesystem(definition)
        cache[definition.id] = CachedEntry(definition, filesystem)
        return filesystem
    }

    private fun closeChannelLocked(channelId: String) {
        val removed = cache.remove(channelId) ?: return
        try {
            removed.filesystem.close()
        } catch (_: Throwable) {
        }
    }

    private fun definitionNow(channelId: String): ChannelDefinition? {
        return try {
            definitions().firstOrNull { it.id == channelId }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            null
        }
    }

    private fun createFilesystem(definition: ChannelDefinition): MountedFilesystem {
        val factory = SafVfsMountFactory(grants) { treeUri ->
            AndroidSafDocumentGateway(contentResolver, Uri.parse(treeUri))
        }
        val resolver = SafVfsMountResolver(
            store = bindings,
            factory = factory,
            channelInstanceId = definition.id,
            implementationId = definition.implementationId,
            generationProvider = { GENERATION },
        )
        val registry = MountLeaseRegistry(
            owner = LeaseOwner(
                stateId = stateId,
                instanceId = definition.id,
                generation = GENERATION,
            ),
            resolver = resolver,
            revalidator = SafMountLeaseRevalidator(
                store = bindings,
                grants = grants,
                implementationId = definition.implementationId,
            ),
        )
        return MountedFilesystem(registry)
    }

    // ------------------------------------------------------------------
    // Errors: fixed short codes, bounded sanitized messages, no platform detail
    // ------------------------------------------------------------------

    private fun failure(failure: FilesystemError): JSONObject =
        error(shortCode(failure.code), failure.reason ?: shortCode(failure.code))

    private fun error(code: String, message: String): JSONObject =
        JSONObject().put("error", JSONObject().put("code", code).put("message", truncate(message, 256)))

    private fun shortCode(code: FilesystemErrorCode): String = when (code) {
        FilesystemErrorCode.E_INVALID_ARGUMENT -> "invalid_arguments"
        FilesystemErrorCode.E_INVALID_PATH -> "invalid_path"
        FilesystemErrorCode.E_INVALID_CONTEXT -> "not_permitted"
        FilesystemErrorCode.E_CAPABILITY_UNDECLARED -> "unknown_mount"
        FilesystemErrorCode.E_MOUNT_UNAVAILABLE -> "mount_unavailable"
        FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED -> "reauthorization_required"
        FilesystemErrorCode.E_READ_ONLY -> "read_only"
        FilesystemErrorCode.E_NOT_FOUND -> "not_found"
        FilesystemErrorCode.E_EXISTS -> "already_exists"
        FilesystemErrorCode.E_NOT_DIRECTORY -> "not_directory"
        FilesystemErrorCode.E_IS_DIRECTORY -> "is_directory"
        FilesystemErrorCode.E_TOO_LARGE -> "too_large"
        FilesystemErrorCode.E_NO_SPACE -> "io_error"
        FilesystemErrorCode.E_BUSY -> "busy"
        FilesystemErrorCode.E_TIMEOUT -> "timeout"
        FilesystemErrorCode.E_CANCELLED -> "cancelled"
        FilesystemErrorCode.E_CLOSED -> "closed"
        FilesystemErrorCode.E_STALE -> "stale"
        FilesystemErrorCode.E_UNSUPPORTED -> "unsupported"
        FilesystemErrorCode.E_IO -> "io_error"
    }

    private fun kindName(kind: NodeKind): String = when (kind) {
        NodeKind.FILE -> "file"
        NodeKind.DIRECTORY -> "directory"
    }

    private fun truncate(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        return value.substring(0, maxChars)
    }
}
