package io.talkcan.storage

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * The generic document-tree VFS core.
 *
 * Implements [MountedStoragePort] over an injected [DocumentTreeBackend] reached through a
 * generation-owned [MountLeaseRegistry]. It owns every provider-independent behavior the portable
 * contract requires, and layers finite admission bounds and an exact-once terminal gate
 * ([VfsLimits]) on top of the portable semantics:
 *
 *  - canonical path validation and confinement beneath the mount root (no `..`, no absolute or
 *    platform paths, no cross-mount traversal);
 *  - `mkdir` create-versus-existing semantics with optional ancestor creation but no ambient
 *    recursive traversal;
 *  - bounded portable `stat`;
 *  - deterministic paginated `list` with unforgeable cursors bound to mount, directory,
 *    generation, and listing session;
 *  - strict bounded UTF-8 `readText` with no partial success and `writeText` `create-new` /
 *    complete-on-success `replace`;
 *  - nonrecursive `remove` that refuses non-empty directories;
 *  - normalization of every backend outcome and any thrown failure into the fixed portable
 *    vocabulary with bounded sanitized reasons and no platform leakage.
 *
 * The core never sees a platform path, URI, document ID, or exception detail: backends address
 * nodes by opaque [NodeRef] and report failures as [BackendFailure]. Cooperative cancellation is
 * always rethrown so the lifecycle layer can observe it; it is never normalized to a result.
 */
public class MountedFilesystem(
    private val registry: MountLeaseRegistry,
    private val policy: VfsPolicy = VfsPolicy(),
    private val limits: VfsLimits = VfsLimits(),
) : MountedStoragePort {

    private val sanitizer = ReasonSanitizer(policy.maxReasonBytes)
    private val sessions = ConcurrentHashMap<String, ListSession>()
    private val ledger = OperationLedger(limits)
    private val tokenEntropy = SecureRandom()

    /** The lease registry this core resolves and revalidates every operation through. */
    internal val leases: MountLeaseRegistry get() = registry

    /** The admission/accounting ledger (exposed for focused lifecycle tests and the host). */
    internal val operations: OperationLedger get() = ledger

    // -----------------------------------------------------------------------
    // Mount lookup
    // -----------------------------------------------------------------------

    override fun mount(declarationId: String): FilesystemOutcome<MountHandle> {
        if (declarationId.isEmpty()) {
            return fail(FilesystemErrorCode.E_INVALID_ARGUMENT, "mount declaration id is empty")
        }
        return registry.open(declarationId)
    }

    // -----------------------------------------------------------------------
    // Admission and the exact-once terminal gate (4.12)
    // -----------------------------------------------------------------------

    /**
     * Admits one operation and runs it through the exact-once terminal gate. Resolves and
     * revalidates the lease (owner, status, generation, binding) before admitting; reserves
     * [reserveBytes] of the transfer budget; runs the block under the finite deadline; and
     * terminates the reservation exactly once on success, provider failure, timeout, cancel,
     * revocation, or close — releasing the slot and reconciling bytes on every path and
     * discarding any late completion.
     */
    private suspend fun <T> guarded(
        mount: MountHandle,
        reserveBytes: Long = 0,
        transferred: (T) -> Long = { 0L },
        block: suspend (ResolvedMount) -> FilesystemOutcome<T>,
    ): FilesystemOutcome<T> {
        val resolved = when (val lookup = registry.resolveForOperation(mount.leaseToken)) {
            is FilesystemOutcome.Success -> lookup.value
            is FilesystemOutcome.Failure -> return lookup
        }
        val admission = ledger.tryAdmit(reserveBytes)
        if (admission is Admission.Rejected) {
            return FilesystemOutcome.Failure(admission.error)
        }
        val reservation = (admission as Admission.Admitted).reservation
        return runToTerminal(mount, reservation, transferred, resolved, block)
    }

    private suspend fun <T> runToTerminal(
        mount: MountHandle,
        reservation: OperationReservation,
        transferred: (T) -> Long,
        resolved: ResolvedMount,
        block: suspend (ResolvedMount) -> FilesystemOutcome<T>,
    ): FilesystemOutcome<T> {
        val outcome: FilesystemOutcome<T> = try {
            withTimeout(limits.operationDeadlineMs) { block(resolved) }
        } catch (timeout: TimeoutCancellationException) {
            reservation.terminate(TerminalCause.TIMEOUT)
            return fail(FilesystemErrorCode.E_TIMEOUT, "operation exceeded its deadline")
        } catch (cancel: CancellationException) {
            reservation.terminate(TerminalCause.CANCELLED)
            throw cancel
        }
        return when (outcome) {
            is FilesystemOutcome.Failure -> {
                reservation.terminate(TerminalCause.PROVIDER_FAILURE)
                outcome
            }
            is FilesystemOutcome.Success -> when (val live = registry.publicationCheck(mount.leaseToken)) {
                is FilesystemOutcome.Failure -> {
                    // Late publication suppressed: the lease was revoked, closed, or
                    // generation-replaced while the operation ran. Discard the success.
                    reservation.terminate(publicationCause(live.error.code))
                    live
                }
                is FilesystemOutcome.Success -> {
                    reservation.terminate(TerminalCause.SUCCESS, transferred(outcome.value))
                    outcome
                }
            }
        }
    }

    private fun publicationCause(code: FilesystemErrorCode): TerminalCause = when (code) {
        FilesystemErrorCode.E_CLOSED -> TerminalCause.CLOSED
        FilesystemErrorCode.E_STALE -> TerminalCause.REVOKED
        FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED -> TerminalCause.REVOKED
        FilesystemErrorCode.E_MOUNT_UNAVAILABLE -> TerminalCause.REVOKED
        FilesystemErrorCode.E_READ_ONLY -> TerminalCause.REVOKED
        else -> TerminalCause.CLOSED
    }

    // -----------------------------------------------------------------------
    // mkdir
    // -----------------------------------------------------------------------

    override suspend fun mkdir(
        mount: MountHandle,
        path: String,
        options: MkdirOptions,
    ): FilesystemOutcome<MkdirResult> = guarded(mount) { resolved -> mkdirCore(resolved, path, options) }

    private suspend fun mkdirCore(
        resolved: ResolvedMount,
        path: String,
        options: MkdirOptions,
    ): FilesystemOutcome<MkdirResult> {
        requireWritable(resolved)?.let { return it }
        val parsed = parsePathOrFailure(path)
        if (parsed is PathParse.Fail) return parsed.result
        val components = (parsed as PathParse.Ok).components

        if (!options.parents) {
            val parent = descend(resolved, components, components.size - 1)
            if (parent !is FilesystemOutcome.Success) return asFailure(parent)
            val parentNode = parent.value
            val name = components.last()
            return when (val existing = backendChild(resolved, parentNode, name)) {
                is ChildLookup.Found -> when (val kind = backendKind(resolved, existing.node)) {
                    KindProbe.Directory -> FilesystemOutcome.Success(MkdirResult(MkdirStatus.EXISTING))
                    KindProbe.File -> fail(FilesystemErrorCode.E_EXISTS, "a file already exists at path")
                    is KindProbe.Failed -> kind.result
                }
                ChildLookup.Missing -> when (val created = backendCreateDir(resolved, parentNode, name)) {
                    is FilesystemOutcome.Success -> FilesystemOutcome.Success(MkdirResult(MkdirStatus.CREATED))
                    is FilesystemOutcome.Failure -> created
                }
                is ChildLookup.Failed -> existing.result
            }
        }

        // parents=true: walk the exact components, creating each missing directory. Never scans
        // or traverses any subtree beyond the addressed chain.
        var created = false
        var node = resolved.root
        for (name in components) {
            when (val lookup = backendChild(resolved, node, name)) {
                is ChildLookup.Found -> when (val kind = backendKind(resolved, lookup.node)) {
                    KindProbe.Directory -> node = lookup.node
                    KindProbe.File -> return fail(FilesystemErrorCode.E_NOT_DIRECTORY, "a file is in the directory chain")
                    is KindProbe.Failed -> return kind.result
                }
                ChildLookup.Missing -> when (val made = backendCreateDir(resolved, node, name)) {
                    is FilesystemOutcome.Success -> {
                        created = true
                        node = made.value
                    }
                    is FilesystemOutcome.Failure -> return made
                }
                is ChildLookup.Failed -> return lookup.result
            }
        }
        val status = if (created) MkdirStatus.CREATED else MkdirStatus.EXISTING
        return FilesystemOutcome.Success(MkdirResult(status))
    }

    // -----------------------------------------------------------------------
    // stat
    // -----------------------------------------------------------------------

    override suspend fun stat(
        mount: MountHandle,
        path: String,
    ): FilesystemOutcome<StatResult> = guarded(mount) { resolved -> statCore(resolved, path) }

    private suspend fun statCore(
        resolved: ResolvedMount,
        path: String,
    ): FilesystemOutcome<StatResult> {
        val parsed = parsePathOrFailure(path)
        if (parsed is PathParse.Fail) return parsed.result
        val components = (parsed as PathParse.Ok).components
        val target = resolveTarget(resolved, components)
        if (target !is FilesystemOutcome.Success) return asFailure(target)
        return when (val info = backendInfo(resolved, target.value)) {
            is FilesystemOutcome.Success -> toStatResult(info.value)
            is FilesystemOutcome.Failure -> info
        }
    }

    // -----------------------------------------------------------------------
    // list
    // -----------------------------------------------------------------------

    override suspend fun list(
        mount: MountHandle,
        path: String,
        options: ListOptions,
    ): FilesystemOutcome<ListPage> = guarded(mount) { resolved -> listCore(resolved, path, options) }

    private suspend fun listCore(
        resolved: ResolvedMount,
        path: String,
        options: ListOptions,
    ): FilesystemOutcome<ListPage> {
        if (options.limit <= 0) {
            return fail(FilesystemErrorCode.E_INVALID_ARGUMENT, "list limit must be positive")
        }
        val parsed = parsePathOrFailure(path, allowMountRoot = true)
        if (parsed is PathParse.Fail) return parsed.result
        val components = (parsed as PathParse.Ok).components

        // Clamp the accepted page size so that even max-sized names stay within the response
        // budget; this bounds the page without ever truncating mid-page (which could skip
        // entries on the next call).
        val perName = policy.maxNameBytes.toLong()
        val budgetLimit = (policy.maxListResponseBytes / perName).coerceAtLeast(1L)
        val limit = options.limit.toLong()
            .coerceAtMost(policy.maxPageSize.toLong())
            .coerceAtMost(budgetLimit)
            .toInt()

        val session: ListSession
        val backendToken: String?
        val cursor = options.cursor
        if (cursor == null) {
            // Open a new listing session bound to this mount, directory, and generation.
            val target = resolveTarget(resolved, components)
            if (target !is FilesystemOutcome.Success) return asFailure(target)
            val dirNode = target.value
            when (val kind = backendKind(resolved, dirNode)) {
                KindProbe.Directory -> Unit
                KindProbe.File -> return fail(FilesystemErrorCode.E_NOT_DIRECTORY, "path is not a directory")
                is KindProbe.Failed -> return kind.result
            }
            session = ListSession(
                mountToken = resolved.mountToken,
                directory = components,
                generation = resolved.generation,
                dirNode = dirNode,
                backendToken = null,
            )
            backendToken = null
        } else {
            // Atomically claim the one-time cursor. A replay, a foreign token, or a token from a
            // different mount/directory/generation/session is rejected before provider traversal.
            // Claiming releases the live-cursor slot the paused listing held.
            val claimed = sessions.remove(cursor.token)
                ?: return fail(FilesystemErrorCode.E_STALE, "listing cursor is no longer valid")
            ledger.cursorReleased()
            if (!claimed.matches(resolved.mountToken, components, resolved.generation)) {
                return fail(FilesystemErrorCode.E_STALE, "listing cursor does not match this listing")
            }
            session = claimed
            backendToken = claimed.backendToken
        }

        // Bounded pagination: a session may serve at most this many pages before exhaustion.
        if (session.pagesServed >= limits.maxListPagesPerSession) {
            return fail(FilesystemErrorCode.E_TOO_LARGE, "listing exceeded the page bound")
        }

        val page = try {
            resolved.backend.listChildren(session.dirNode, backendToken, limit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Terminal pagination failure: the claimed session is not re-keyed, so its cursor is
            // invalidated and cannot resume.
            return ioFailure()
        }
        return when (page) {
            is BackendResult.Ok -> {
                val value = page.value
                val entries = ArrayList<ListEntry>(value.entries.size)
                for (entry in value.entries) {
                    val nameBytes = entry.name.toByteArray(StandardCharsets.UTF_8).size
                    if (nameBytes > policy.maxNameBytes) {
                        return fail(FilesystemErrorCode.E_TOO_LARGE, "an entry name exceeds the name bound")
                    }
                    entries.add(ListEntry(entry.name, toNodeKind(entry.kind)))
                }
                session.pagesServed++
                val nextCursor = if (value.nextPageToken != null) {
                    // Advance the session and re-key it under a fresh unforgeable token, reserving
                    // a live-cursor slot; cursor-budget exhaustion fails the listing closed.
                    if (!ledger.tryPublishCursor()) {
                        return fail(FilesystemErrorCode.E_TOO_LARGE, "too many live listing cursors")
                    }
                    session.backendToken = value.nextPageToken
                    val token = newToken()
                    sessions[token] = session
                    ListCursor(token)
                } else {
                    // Listing complete: the session is dropped and its cursor stays invalid.
                    null
                }
                FilesystemOutcome.Success(ListPage(entries, nextCursor))
            }
            is BackendResult.Err -> FilesystemOutcome.Failure(normalize(page.failure, page.reason))
        }
    }

    // -----------------------------------------------------------------------
    // read_text
    // -----------------------------------------------------------------------

    override suspend fun readText(
        mount: MountHandle,
        path: String,
        options: ReadTextOptions,
    ): FilesystemOutcome<ReadTextResult> {
        val reserveBytes = if (options.maxBytes > 0) options.maxBytes.coerceAtMost(policy.maxReadBytes) else 0L
        return guarded(mount, reserveBytes = reserveBytes, transferred = { it.bytes }) { resolved ->
            readTextCore(resolved, path, options)
        }
    }

    private suspend fun readTextCore(
        resolved: ResolvedMount,
        path: String,
        options: ReadTextOptions,
    ): FilesystemOutcome<ReadTextResult> {
        if (options.maxBytes <= 0) {
            return fail(FilesystemErrorCode.E_INVALID_ARGUMENT, "max_bytes must be positive")
        }
        val parsed = parsePathOrFailure(path)
        if (parsed is PathParse.Fail) return parsed.result
        val components = (parsed as PathParse.Ok).components
        val target = resolveTarget(resolved, components)
        if (target !is FilesystemOutcome.Success) return asFailure(target)
        val node = target.value
        when (val kind = backendKind(resolved, node)) {
            KindProbe.File -> Unit
            KindProbe.Directory -> return fail(FilesystemErrorCode.E_IS_DIRECTORY, "path is a directory")
            is KindProbe.Failed -> return kind.result
        }
        val accepted = options.maxBytes.coerceAtMost(policy.maxReadBytes)
        return when (val read = backendRead(resolved, node, accepted)) {
            is FilesystemOutcome.Success -> {
                val bytes = read.value
                val text = String(bytes, StandardCharsets.UTF_8)
                // Strict UTF-8: a round-trip mismatch means the bytes were not valid UTF-8 (the
                // decoder replaced invalid sequences). No partial text is published.
                if (!text.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
                    return fail(FilesystemErrorCode.E_UNSUPPORTED, "document is not valid UTF-8")
                }
                FilesystemOutcome.Success(ReadTextResult(text, bytes.size.toLong()))
            }
            is FilesystemOutcome.Failure -> read
        }
    }

    // -----------------------------------------------------------------------
    // write_text
    // -----------------------------------------------------------------------

    override suspend fun writeText(
        mount: MountHandle,
        path: String,
        text: String,
        options: WriteTextOptions,
    ): FilesystemOutcome<WriteResult> {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        return guarded(mount, reserveBytes = bytes.size.toLong(), transferred = { it.bytes }) { resolved ->
            writeTextCore(resolved, path, text, bytes, options)
        }
    }

    private suspend fun writeTextCore(
        resolved: ResolvedMount,
        path: String,
        text: String,
        bytes: ByteArray,
        options: WriteTextOptions,
    ): FilesystemOutcome<WriteResult> {
        requireWritable(resolved)?.let { return it }
        val parsed = parsePathOrFailure(path)
        if (parsed is PathParse.Fail) return parsed.result
        val components = (parsed as PathParse.Ok).components

        // Validate bounded UTF-8 before any effect. An unpaired surrogate cannot encode to valid
        // UTF-8, so a clean round-trip proves the supplied text is valid.
        if (!String(bytes, StandardCharsets.UTF_8).contentEquals(text)) {
            return fail(FilesystemErrorCode.E_INVALID_ARGUMENT, "text is not valid UTF-8")
        }
        if (bytes.size.toLong() > policy.maxWriteBytes) {
            return fail(FilesystemErrorCode.E_TOO_LARGE, "text exceeds the write bound")
        }

        val parent = descend(resolved, components, components.size - 1)
        if (parent !is FilesystemOutcome.Success) return asFailure(parent)
        val parentNode = parent.value
        val name = components.last()

        // Reject an existing destination for create-new, and a directory destination for either
        // mode, before the backend write begins.
        when (val existing = backendChild(resolved, parentNode, name)) {
            is ChildLookup.Found -> when (val kind = backendKind(resolved, existing.node)) {
                KindProbe.Directory -> return fail(FilesystemErrorCode.E_IS_DIRECTORY, "path is a directory")
                KindProbe.File -> if (options.mode == WriteMode.CREATE_NEW) {
                    return fail(FilesystemErrorCode.E_EXISTS, "destination already exists")
                }
                is KindProbe.Failed -> return kind.result
            }
            ChildLookup.Missing -> Unit
            is ChildLookup.Failed -> return existing.result
        }

        val mode = when (options.mode) {
            WriteMode.CREATE_NEW -> BackendWriteMode.CREATE_NEW
            WriteMode.REPLACE -> BackendWriteMode.REPLACE
        }
        return when (val written = backendWrite(resolved, parentNode, name, bytes, mode)) {
            is FilesystemOutcome.Success ->
                FilesystemOutcome.Success(WriteResult(WriteStatus.WRITTEN, bytes.size.toLong()))
            is FilesystemOutcome.Failure -> written
        }
    }

    // -----------------------------------------------------------------------
    // remove
    // -----------------------------------------------------------------------

    override suspend fun remove(
        mount: MountHandle,
        path: String,
        options: RemoveOptions,
    ): FilesystemOutcome<RemoveResult> =
        guarded(mount) { resolved -> removeCore(resolved, path, options) }

    private suspend fun removeCore(
        resolved: ResolvedMount,
        path: String,
        options: RemoveOptions,
    ): FilesystemOutcome<RemoveResult> {
        requireWritable(resolved)?.let { return it }
        val parsed = parsePathOrFailure(path)
        if (parsed is PathParse.Fail) return parsed.result
        val components = (parsed as PathParse.Ok).components
        val target = resolveTarget(resolved, components)
        if (target !is FilesystemOutcome.Success) {
            val failure = (target as FilesystemOutcome.Failure).error
            if (failure.code == FilesystemErrorCode.E_NOT_FOUND && options.missingOk) {
                return FilesystemOutcome.Success(RemoveResult(RemoveStatus.MISSING))
            }
            return FilesystemOutcome.Failure(failure)
        }
        return when (val deleted = backendDelete(resolved, target.value)) {
            is FilesystemOutcome.Success -> FilesystemOutcome.Success(RemoveResult(RemoveStatus.REMOVED))
            is FilesystemOutcome.Failure -> deleted
        }
    }

    // -----------------------------------------------------------------------
    // Path validation and confinement
    // -----------------------------------------------------------------------

    private sealed interface PathParse {
        data class Ok(val components: List<String>) : PathParse
        data class Fail(val result: FilesystemOutcome<Nothing>) : PathParse
    }

    private fun parsePathOrFailure(
        path: String,
        allowMountRoot: Boolean = false,
    ): PathParse {
        if (allowMountRoot && path.isEmpty()) return PathParse.Ok(emptyList())
        return when (val result = MountRelativePath.parse(path, policy.pathBounds)) {
            is PathParseResult.Valid -> PathParse.Ok(result.path.components)
            is PathParseResult.Invalid -> PathParse.Fail(FilesystemOutcome.Failure(result.error))
        }
    }

    // -----------------------------------------------------------------------
    // Resolution helpers (always confined beneath the mount root)
    // -----------------------------------------------------------------------

    /**
     * Walks the first [count] [components] from the mount root, requiring each to be an existing
     * directory. Returns the node reached (the root when [count] is zero).
     */
    private suspend fun descend(
        resolved: ResolvedMount,
        components: List<String>,
        count: Int,
    ): FilesystemOutcome<NodeRef> {
        var node = resolved.root
        for (i in 0 until count) {
            when (val lookup = backendChild(resolved, node, components[i])) {
                is ChildLookup.Found -> when (val kind = backendKind(resolved, lookup.node)) {
                    KindProbe.Directory -> node = lookup.node
                    KindProbe.File -> return fail(FilesystemErrorCode.E_NOT_DIRECTORY, "a file is in the path")
                    is KindProbe.Failed -> return kind.result
                }
                ChildLookup.Missing -> return fail(FilesystemErrorCode.E_NOT_FOUND, "a path component is missing")
                is ChildLookup.Failed -> return lookup.result
            }
        }
        return FilesystemOutcome.Success(node)
    }

    /** Resolves the addressed target node, requiring intermediates to be directories. */
    private suspend fun resolveTarget(
        resolved: ResolvedMount,
        components: List<String>,
    ): FilesystemOutcome<NodeRef> {
        if (components.isEmpty()) return FilesystemOutcome.Success(resolved.root)
        val parent = descend(resolved, components, components.size - 1)
        if (parent !is FilesystemOutcome.Success) return parent
        return when (val lookup = backendChild(resolved, parent.value, components.last())) {
            is ChildLookup.Found -> FilesystemOutcome.Success(lookup.node)
            ChildLookup.Missing -> fail(FilesystemErrorCode.E_NOT_FOUND, "path not found")
            is ChildLookup.Failed -> lookup.result
        }
    }

    private sealed interface ChildLookup {
        data class Found(val node: NodeRef) : ChildLookup
        data object Missing : ChildLookup
        data class Failed(val result: FilesystemOutcome<Nothing>) : ChildLookup
    }

    private suspend fun backendChild(resolved: ResolvedMount, parent: NodeRef, name: String): ChildLookup {
        val result = try {
            resolved.backend.child(parent, name)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return ChildLookup.Failed(ioFailure())
        }
        return when (result) {
            is BackendResult.Ok -> ChildLookup.Found(result.value)
            is BackendResult.Err -> if (result.failure == BackendFailure.NOT_FOUND) {
                ChildLookup.Missing
            } else {
                ChildLookup.Failed(FilesystemOutcome.Failure(normalize(result.failure, result.reason)))
            }
        }
    }

    private sealed interface KindProbe {
        data object File : KindProbe
        data object Directory : KindProbe
        data class Failed(val result: FilesystemOutcome<Nothing>) : KindProbe
    }

    private suspend fun backendKind(resolved: ResolvedMount, node: NodeRef): KindProbe =
        when (val info = backendInfo(resolved, node)) {
            is FilesystemOutcome.Success -> when (info.value.kind) {
                BackendNodeKind.FILE -> KindProbe.File
                BackendNodeKind.DIRECTORY -> KindProbe.Directory
            }
            is FilesystemOutcome.Failure -> KindProbe.Failed(info)
        }

    // -----------------------------------------------------------------------
    // Backend calls, each normalized to the fixed vocabulary
    // -----------------------------------------------------------------------

    private suspend fun backendInfo(resolved: ResolvedMount, node: NodeRef): FilesystemOutcome<BackendNodeInfo> =
        normalizeCall { resolved.backend.info(node) }

    private suspend fun backendCreateDir(resolved: ResolvedMount, parent: NodeRef, name: String): FilesystemOutcome<NodeRef> =
        normalizeCall { resolved.backend.createDirectory(parent, name) }

    private suspend fun backendRead(resolved: ResolvedMount, node: NodeRef, maxBytes: Long): FilesystemOutcome<ByteArray> =
        normalizeCall { resolved.backend.readFile(node, maxBytes) }

    private suspend fun backendWrite(
        resolved: ResolvedMount,
        parent: NodeRef,
        name: String,
        bytes: ByteArray,
        mode: BackendWriteMode,
    ): FilesystemOutcome<NodeRef> =
        normalizeCall { resolved.backend.writeFile(parent, name, bytes, mode) }

    private suspend fun backendDelete(resolved: ResolvedMount, node: NodeRef): FilesystemOutcome<Unit> =
        normalizeCall { resolved.backend.delete(node) }

    private suspend fun <T> normalizeCall(block: suspend () -> BackendResult<T>): FilesystemOutcome<T> {
        val result = try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return ioFailure()
        }
        return when (result) {
            is BackendResult.Ok -> FilesystemOutcome.Success(result.value)
            is BackendResult.Err -> FilesystemOutcome.Failure(normalize(result.failure, result.reason))
        }
    }

    // -----------------------------------------------------------------------
    // Error normalization and result plumbing
    // -----------------------------------------------------------------------

    private fun normalize(failure: BackendFailure, reason: String?): FilesystemError {
        val code = when (failure) {
            BackendFailure.NOT_FOUND -> FilesystemErrorCode.E_NOT_FOUND
            BackendFailure.ALREADY_EXISTS -> FilesystemErrorCode.E_EXISTS
            BackendFailure.NOT_A_DIRECTORY -> FilesystemErrorCode.E_NOT_DIRECTORY
            BackendFailure.IS_A_DIRECTORY -> FilesystemErrorCode.E_IS_DIRECTORY
            BackendFailure.NOT_EMPTY -> FilesystemErrorCode.E_BUSY
            BackendFailure.READ_ONLY -> FilesystemErrorCode.E_READ_ONLY
            BackendFailure.NO_SPACE -> FilesystemErrorCode.E_NO_SPACE
            BackendFailure.UNAVAILABLE -> FilesystemErrorCode.E_MOUNT_UNAVAILABLE
            BackendFailure.REAUTHORIZATION_REQUIRED -> FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED
            BackendFailure.TOO_LARGE -> FilesystemErrorCode.E_TOO_LARGE
            BackendFailure.BUSY -> FilesystemErrorCode.E_BUSY
            BackendFailure.UNSUPPORTED -> FilesystemErrorCode.E_UNSUPPORTED
            BackendFailure.IO -> FilesystemErrorCode.E_IO
        }
        return sanitizer.error(code, reason)
    }

    private fun sanitized(error: FilesystemError): FilesystemError =
        FilesystemError(error.code, sanitizer.sanitize(error.reason))

    private fun fail(code: FilesystemErrorCode, reason: String): FilesystemOutcome<Nothing> =
        FilesystemOutcome.Failure(sanitizer.error(code, reason))

    /** Unknown failures collapse to E_IO with a generic reason; no exception detail leaks. */
    private fun ioFailure(): FilesystemOutcome<Nothing> =
        FilesystemOutcome.Failure(sanitizer.error(FilesystemErrorCode.E_IO, "operation failed"))

    private fun toStatResult(info: BackendNodeInfo): FilesystemOutcome<StatResult> {
        val nameBytes = info.name.toByteArray(StandardCharsets.UTF_8).size
        if (nameBytes > policy.maxNameBytes) {
            return fail(FilesystemErrorCode.E_TOO_LARGE, "name exceeds the name bound")
        }
        val size = if (info.kind == BackendNodeKind.FILE) info.sizeBytes else 0L
        return FilesystemOutcome.Success(
            StatResult(
                name = info.name,
                kind = toNodeKind(info.kind),
                sizeBytes = size,
                modifiedAtUnixMs = info.modifiedAtUnixMs,
            ),
        )
    }

    private fun toNodeKind(kind: BackendNodeKind): NodeKind = when (kind) {
        BackendNodeKind.FILE -> NodeKind.FILE
        BackendNodeKind.DIRECTORY -> NodeKind.DIRECTORY
    }

    /** Write operations require a writable mount; a non-writable mount fails closed. */
    private fun requireWritable(resolved: ResolvedMount): FilesystemOutcome<Nothing>? =
        when (resolved.access) {
            MountAccessMode.READ_WRITE -> null
        }

    private fun asFailure(outcome: FilesystemOutcome<NodeRef>): FilesystemOutcome<Nothing> =
        FilesystemOutcome.Failure((outcome as FilesystemOutcome.Failure).error)

    private fun newToken(): String {
        val bytes = ByteArray(24)
        tokenEntropy.nextBytes(bytes)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX[(b.toInt() shr 4) and 0x0F]).append(HEX[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    // -----------------------------------------------------------------------
    // Lifecycle (4.2 / 4.12): generation advance, revocation, close
    // -----------------------------------------------------------------------

    /**
     * Advances the owning generation. Predecessor leases become stale and fail their next
     * resolution, the per-generation transfer budget resets, and every paused listing cursor is
     * invalidated. New [mount] calls bind to [newGeneration].
     */
    public fun advanceGeneration(newGeneration: Long) {
        registry.advanceGeneration(newGeneration)
        ledger.resetGeneration()
        sessions.clear()
        ledger.resetCursors()
    }

    /**
     * Revokes every active lease under [source] (for example on grant revocation or binding
     * replacement). An in-flight operation suppresses its late success on the publication check.
     */
    public fun revokeAll(source: MountRevocationSource): Int = registry.revokeAll(source)

    /**
     * Closes the core: closes the registry (every lease becomes closed), invalidates all paused
     * listing cursors, and resets cursor accounting. Idempotent.
     */
    public fun close() {
        registry.close()
        sessions.clear()
        ledger.resetCursors()
    }

    private companion object {
        private val HEX = "0123456789abcdef".toCharArray()
    }
}

/**
 * One live listing session. Bound to a mount token, an exact directory (by validated components),
 * and a generation; carries the backend's opaque continuation token. A cursor is a random key
 * into the core's session map, so it is unforgeable and reveals nothing about the listing.
 */
private class ListSession(
    val mountToken: String,
    val directory: List<String>,
    val generation: Long,
    val dirNode: NodeRef,
    var backendToken: String?,
) {
    /** Pages this session has served; bounded by [VfsLimits.maxListPagesPerSession]. */
    var pagesServed: Int = 0
    fun matches(mountToken: String, directory: List<String>, generation: Long): Boolean =
        this.mountToken == mountToken && this.directory == directory && this.generation == generation
}
