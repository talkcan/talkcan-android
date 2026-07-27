package io.talkcan.mount.saf.vfs

import io.talkcan.storage.BackendEntry
import io.talkcan.storage.BackendFailure
import io.talkcan.storage.BackendListPage
import io.talkcan.storage.BackendNodeInfo
import io.talkcan.storage.BackendNodeKind
import io.talkcan.storage.BackendResult
import io.talkcan.storage.BackendWriteMode
import io.talkcan.storage.DocumentTreeBackend
import io.talkcan.storage.NodeRef
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Android SAF [DocumentTreeBackend] over a [SafDocumentGateway].
 *
 * Resolves every node by an opaque [NodeRef] wrapping an adapter-private SAF
 * document identifier and confines all access beneath the single granted tree
 * root supplied at construction: the only document identifiers the backend ever
 * handles are the tree root and identifiers the provider itself returned for
 * documents created or listed under that tree. It never sees a logical path,
 * URI, or raw filesystem path, and never reports a URI, document ID, or
 * exception through [BackendResult]; gateway failures are normalized onto the
 * fixed [BackendFailure] set with, at most, a short fixed portable reason token.
 *
 * Writes are complete-on-success. `CREATE_NEW` writes directly to a freshly
 * created document and removes it on any failure. `REPLACE` first writes the
 * complete payload to a uniquely named staging document (proving quota and
 * writability without touching the target), then publishes to the target, and
 * always removes the staging document — including on cancellation, revocation,
 * or close — so no staged node is ever left behind. Reads are bounded and never
 * publish a partial payload.
 */
class SafDocumentTreeBackend(
    private val gateway: SafDocumentGateway,
    private val rootDocumentId: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DocumentTreeBackend {

    init {
        require(rootDocumentId.isNotEmpty()) { "SAF tree root document id must not be empty" }
    }

    /** The opaque root node of the granted tree. */
    val rootRef: NodeRef = NodeRef(rootDocumentId)

    private val stageCounter = AtomicLong(0L)

    override suspend fun child(parent: NodeRef, name: String): BackendResult<NodeRef> {
        when (val parentCheck = gate { gateway.queryDocument(parent.opaque) }) {
            is SafGatewayResult.Failed -> return err(parentCheck.failure)
            is SafGatewayResult.Ok -> if (!parentCheck.value.directory) return fail(BackendFailure.NOT_A_DIRECTORY)
        }
        return when (val children = gate { gateway.queryChildren(parent.opaque) }) {
            is SafGatewayResult.Failed -> err(children.failure)
            is SafGatewayResult.Ok -> {
                val match = children.value.firstOrNull { it.displayName == name }
                    ?: return fail(BackendFailure.NOT_FOUND)
                BackendResult.Ok(NodeRef(match.id))
            }
        }
    }

    override suspend fun info(node: NodeRef): BackendResult<BackendNodeInfo> =
        when (val doc = gate { gateway.queryDocument(node.opaque) }) {
            is SafGatewayResult.Failed -> err(doc.failure)
            is SafGatewayResult.Ok -> {
                val row = doc.value
                BackendResult.Ok(
                    BackendNodeInfo(
                        name = row.displayName,
                        kind = if (row.directory) BackendNodeKind.DIRECTORY else BackendNodeKind.FILE,
                        sizeBytes = if (row.directory) 0L else row.sizeBytes,
                        modifiedAtUnixMs = row.lastModifiedMs,
                    ),
                )
            }
        }

    override suspend fun createDirectory(parent: NodeRef, name: String): BackendResult<NodeRef> {
        when (val parentCheck = gate { gateway.queryDocument(parent.opaque) }) {
            is SafGatewayResult.Failed -> return err(parentCheck.failure)
            is SafGatewayResult.Ok -> if (!parentCheck.value.directory) return fail(BackendFailure.NOT_A_DIRECTORY)
        }
        when (val children = gate { gateway.queryChildren(parent.opaque) }) {
            is SafGatewayResult.Failed -> return err(children.failure)
            is SafGatewayResult.Ok ->
                if (children.value.any { it.displayName == name }) return fail(BackendFailure.ALREADY_EXISTS)
        }
        return when (val created = gate { gateway.createDocument(parent.opaque, directory = true, displayName = name) }) {
            is SafGatewayResult.Failed -> err(created.failure)
            is SafGatewayResult.Ok -> BackendResult.Ok(NodeRef(created.value))
        }
    }

    override suspend fun listChildren(parent: NodeRef, pageToken: String?, limit: Int): BackendResult<BackendListPage> {
        when (val parentCheck = gate { gateway.queryDocument(parent.opaque) }) {
            is SafGatewayResult.Failed -> return err(parentCheck.failure)
            is SafGatewayResult.Ok -> if (!parentCheck.value.directory) return fail(BackendFailure.NOT_A_DIRECTORY)
        }
        val start = when (pageToken) {
            null -> 0
            else -> pageToken.toIntOrNull() ?: return fail(BackendFailure.IO)
        }.coerceAtLeast(0)
        return when (val children = gate { gateway.queryChildren(parent.opaque) }) {
            is SafGatewayResult.Failed -> err(children.failure)
            is SafGatewayResult.Ok -> {
                val all = children.value
                if (start >= all.size) {
                    return BackendResult.Ok(BackendListPage(emptyList(), null))
                }
                val end = (start + limit).coerceAtMost(all.size)
                val entries = all.subList(start, end).map {
                    BackendEntry(it.displayName, if (it.directory) BackendNodeKind.DIRECTORY else BackendNodeKind.FILE)
                }
                val next = if (end < all.size) end.toString() else null
                BackendResult.Ok(BackendListPage(entries, next))
            }
        }
    }

    override suspend fun readFile(node: NodeRef, maxBytes: Long): BackendResult<ByteArray> {
        when (val doc = gate { gateway.queryDocument(node.opaque) }) {
            is SafGatewayResult.Failed -> return err(doc.failure)
            is SafGatewayResult.Ok -> if (doc.value.directory) return fail(BackendFailure.IS_A_DIRECTORY)
        }
        val opened = gate { gateway.openRead(node.opaque) }
        val stream = when (opened) {
            is SafGatewayResult.Failed -> return err(opened.failure)
            is SafGatewayResult.Ok -> opened.value
        }
        return try {
            val collected = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(READ_CHUNK_BYTES)
            var total = 0L
            var tooLarge = false
            withContext(ioDispatcher) {
                while (true) {
                    coroutineContext.ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        tooLarge = true
                        break
                    }
                    collected.write(buffer, 0, read)
                }
            }
            if (tooLarge) fail(BackendFailure.TOO_LARGE) else BackendResult.Ok(collected.toByteArray())
        } catch (_: IOException) {
            fail(BackendFailure.IO)
        } finally {
            closeQuietly(stream)
        }
    }

    override suspend fun writeFile(
        parent: NodeRef,
        name: String,
        bytes: ByteArray,
        mode: BackendWriteMode,
    ): BackendResult<NodeRef> {
        when (val parentCheck = gate { gateway.queryDocument(parent.opaque) }) {
            is SafGatewayResult.Failed -> return err(parentCheck.failure)
            is SafGatewayResult.Ok -> if (!parentCheck.value.directory) return fail(BackendFailure.NOT_A_DIRECTORY)
        }
        val existing = when (val children = gate { gateway.queryChildren(parent.opaque) }) {
            is SafGatewayResult.Failed -> return err(children.failure)
            is SafGatewayResult.Ok -> children.value.firstOrNull { it.displayName == name }
        }
        if (existing != null && existing.directory) return fail(BackendFailure.IS_A_DIRECTORY)
        if (mode == BackendWriteMode.CREATE_NEW && existing != null) return fail(BackendFailure.ALREADY_EXISTS)

        return if (mode == BackendWriteMode.CREATE_NEW) {
            writeCreateNew(parent, name, bytes)
        } else {
            writeReplace(parent, name, bytes, existing?.id)
        }
    }

    /** CREATE_NEW: create the target, write the complete payload, remove the target on any failure. */
    private suspend fun writeCreateNew(parent: NodeRef, name: String, bytes: ByteArray): BackendResult<NodeRef> {
        val created = gate { gateway.createDocument(parent.opaque, directory = false, displayName = name) }
        val docId = when (created) {
            is SafGatewayResult.Failed -> return err(created.failure)
            is SafGatewayResult.Ok -> created.value
        }
        var published = false
        return try {
            when (val written = writeAll(docId, bytes)) {
                is SafGatewayResult.Failed -> err(written.failure)
                is SafGatewayResult.Ok -> {
                    coroutineContext.ensureActive()
                    published = true
                    BackendResult.Ok(NodeRef(docId))
                }
            }
        } finally {
            if (!published) cleanupDelete(docId)
        }
    }

    /**
     * REPLACE: stage the complete payload under a unique hidden document, then
     * publish to the target (overwrite the existing file, or create it). The
     * staging document is removed on every terminal path — success, failure,
     * cancellation, revocation, or close — so no staged node survives.
     */
    private suspend fun writeReplace(
        parent: NodeRef,
        name: String,
        bytes: ByteArray,
        existingId: String?,
    ): BackendResult<NodeRef> {
        val stageName = stageName(name)
        val staged = gate { gateway.createDocument(parent.opaque, directory = false, displayName = stageName) }
        val stageId = when (staged) {
            is SafGatewayResult.Failed -> return err(staged.failure)
            is SafGatewayResult.Ok -> staged.value
        }
        var createdTargetId: String? = null
        var published = false
        return try {
            // 1. Full staged write validates quota and writability while the target is untouched.
            when (val stagedWrite = writeAll(stageId, bytes)) {
                is SafGatewayResult.Failed -> return err(stagedWrite.failure)
                is SafGatewayResult.Ok -> Unit
            }
            coroutineContext.ensureActive()

            // 2. Publish the complete payload to the target.
            val targetId: String
            if (existingId != null) {
                when (val overwritten = writeAll(existingId, bytes)) {
                    is SafGatewayResult.Failed -> return err(overwritten.failure)
                    is SafGatewayResult.Ok -> targetId = existingId
                }
            } else {
                val created = gate { gateway.createDocument(parent.opaque, directory = false, displayName = name) }
                when (created) {
                    is SafGatewayResult.Failed -> return err(created.failure)
                    is SafGatewayResult.Ok -> targetId = created.value
                }
                createdTargetId = targetId
                when (val written = writeAll(targetId, bytes)) {
                    is SafGatewayResult.Failed -> return err(written.failure)
                    is SafGatewayResult.Ok -> Unit
                }
            }
            coroutineContext.ensureActive()
            published = true
            BackendResult.Ok(NodeRef(targetId))
        } finally {
            cleanupDelete(stageId)
            if (!published) {
                createdTargetId?.let { cleanupDelete(it) }
            }
        }
    }

    override suspend fun delete(node: NodeRef): BackendResult<Unit> {
        if (node.opaque == rootDocumentId) return fail(BackendFailure.BUSY)
        when (val doc = gate { gateway.queryDocument(node.opaque) }) {
            is SafGatewayResult.Failed -> return err(doc.failure)
            is SafGatewayResult.Ok -> {
                if (doc.value.directory) {
                    when (val hasChildren = gate { gateway.hasChildren(node.opaque) }) {
                        is SafGatewayResult.Failed -> return err(hasChildren.failure)
                        is SafGatewayResult.Ok -> if (hasChildren.value) return fail(BackendFailure.NOT_EMPTY)
                    }
                }
            }
        }
        return when (val deleted = gate { gateway.deleteDocument(node.opaque) }) {
            is SafGatewayResult.Failed -> err(deleted.failure)
            is SafGatewayResult.Ok -> BackendResult.Ok(Unit)
        }
    }

    /** Writes the complete [bytes] to [documentId], truncating existing content; closes the stream in all cases. */
    private suspend fun writeAll(documentId: String, bytes: ByteArray): SafGatewayResult<Unit> {
        val opened = gate { gateway.openWrite(documentId, truncate = true) }
        val stream: OutputStream = when (opened) {
            is SafGatewayResult.Failed -> return SafGatewayResult.Failed(opened.failure)
            is SafGatewayResult.Ok -> opened.value
        }
        return try {
            withContext(ioDispatcher) {
                var offset = 0
                while (offset < bytes.size) {
                    coroutineContext.ensureActive()
                    val count = minOf(bytes.size - offset, WRITE_CHUNK_BYTES)
                    stream.write(bytes, offset, count)
                    offset += count
                }
                stream.flush()
            }
            SafGatewayResult.Ok(Unit)
        } catch (_: SafQuotaException) {
            SafGatewayResult.Failed(SafGatewayFailure.NO_SPACE)
        } catch (_: IOException) {
            SafGatewayResult.Failed(SafGatewayFailure.IO)
        } finally {
            closeQuietly(stream)
        }
    }

    /** Runs a bounded blocking gateway call on the I/O dispatcher; cooperative cancellation is rethrown. */
    private suspend fun <T> gate(block: () -> SafGatewayResult<T>): SafGatewayResult<T> =
        withContext(ioDispatcher) { block() }

    /** Best-effort staging/target removal that runs even under cancellation (no suspension, no throw). */
    private fun cleanupDelete(documentId: String) {
        try {
            gateway.deleteDocument(documentId)
        } catch (_: Throwable) {
            // Cleanup must never mask the in-flight result or cancellation.
        }
    }

    private fun stageName(name: String): String =
        ".talkcan-stage-${Integer.toHexString(name.hashCode())}-${stageCounter.incrementAndGet()}"

    private fun err(failure: SafGatewayFailure): BackendResult<Nothing> =
        BackendResult.Err(failure.toBackend(), failure.reasonToken())

    private fun fail(failure: BackendFailure): BackendResult<Nothing> = BackendResult.Err(failure)

    private fun closeQuietly(closeable: java.io.Closeable?) {
        try {
            closeable?.close()
        } catch (_: Throwable) {
            // Closing must never mask the operation result.
        }
    }

    private companion object {
        const val READ_CHUNK_BYTES = 8 * 1024
        const val WRITE_CHUNK_BYTES = 8 * 1024
    }
}

/** Maps a normalized gateway failure onto the generic backend failure vocabulary. */
private fun SafGatewayFailure.toBackend(): BackendFailure = when (this) {
    SafGatewayFailure.NOT_FOUND -> BackendFailure.NOT_FOUND
    SafGatewayFailure.ALREADY_EXISTS -> BackendFailure.ALREADY_EXISTS
    SafGatewayFailure.NOT_A_DIRECTORY -> BackendFailure.NOT_A_DIRECTORY
    SafGatewayFailure.IS_A_DIRECTORY -> BackendFailure.IS_A_DIRECTORY
    SafGatewayFailure.NOT_EMPTY -> BackendFailure.NOT_EMPTY
    SafGatewayFailure.READ_ONLY -> BackendFailure.READ_ONLY
    SafGatewayFailure.NO_SPACE -> BackendFailure.NO_SPACE
    SafGatewayFailure.REVOKED -> BackendFailure.REAUTHORIZATION_REQUIRED
    SafGatewayFailure.UNAVAILABLE -> BackendFailure.UNAVAILABLE
    SafGatewayFailure.UNSUPPORTED -> BackendFailure.UNSUPPORTED
    SafGatewayFailure.MALFORMED -> BackendFailure.IO
    SafGatewayFailure.IO -> BackendFailure.IO
}

/**
 * Short, fixed, portable diagnostic token for a gateway failure. Static ASCII
 * only: it never carries a URI, document ID, path, exception, or provider
 * detail, and is comfortably within the VFS reason bound.
 */
private fun SafGatewayFailure.reasonToken(): String = when (this) {
    SafGatewayFailure.NOT_FOUND -> "saf.not-found"
    SafGatewayFailure.ALREADY_EXISTS -> "saf.exists"
    SafGatewayFailure.NOT_A_DIRECTORY -> "saf.not-dir"
    SafGatewayFailure.IS_A_DIRECTORY -> "saf.is-dir"
    SafGatewayFailure.NOT_EMPTY -> "saf.not-empty"
    SafGatewayFailure.READ_ONLY -> "saf.read-only"
    SafGatewayFailure.NO_SPACE -> "saf.no-space"
    SafGatewayFailure.REVOKED -> "saf.revoked"
    SafGatewayFailure.UNAVAILABLE -> "saf.unavailable"
    SafGatewayFailure.UNSUPPORTED -> "saf.unsupported"
    SafGatewayFailure.MALFORMED -> "saf.malformed"
    SafGatewayFailure.IO -> "saf.io"
}
