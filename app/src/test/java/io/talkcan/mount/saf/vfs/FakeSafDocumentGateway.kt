package io.talkcan.mount.saf.vfs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * In-memory fake [SafDocumentGateway] for focused JVM tests.
 *
 * Models a SAF document tree with adapter-private document identifiers,
 * deterministic name-sorted children, bounded reads, complete-on-success writes
 * committed on stream close, and nonrecursive deletion. It injects the
 * portable gateway failures (revocation, unavailability, malformed cursors,
 * quota, unsupported) and the local-versus-remote provider divergence
 * (duplicate-create auto-rename versus reject) so the backend's normalization,
 * cleanup, and confinement can be proven without any Android type.
 *
 * Test-only; never references Android, SAF, or Journal types.
 */
class FakeSafDocumentGateway(
    rootId: String = "tree-root",
    private val duplicateCreate: DuplicateCreate = DuplicateCreate.REJECT,
) : SafDocumentGateway {

    /** How the provider resolves a create whose display name already exists. */
    enum class DuplicateCreate {
        /** Strict provider: reject the duplicate (backend also pre-checks). */
        REJECT,

        /** Local-provider behavior: auto-rename the new document ("name (1)"). */
        AUTO_RENAME,
    }

    class Node(
        val id: String,
        var name: String,
        var directory: Boolean,
        val parent: Node?,
        var content: ByteArray = ByteArray(0),
        var lastModifiedMs: Long? = null,
    ) {
        val children: LinkedHashMap<String, Node> = LinkedHashMap()
    }

    val root: Node = Node(rootId, "", directory = true, parent = null)
    private val nodesById: MutableMap<String, Node> = LinkedHashMap<String, Node>().apply { put(rootId, root) }
    private var idSeq = 0L

    /** Wall-clock supplier for created/modified timestamps; null means the provider omits it. */
    var nowProvider: () -> Long? = { null }

    // -- Provider-state failure injection ----------------------------------
    /** Simulates a revoked persisted grant: every access is denied. */
    var revoked: Boolean = false

    /** Simulates a vanished provider / unreachable tree root. */
    var unavailable: Boolean = false

    /** Simulates a structurally malformed children cursor. */
    var malformedChildren: Boolean = false

    var failQueryDocument: SafGatewayFailure? = null
    var failQueryChildren: SafGatewayFailure? = null
    var failHasChildren: SafGatewayFailure? = null
    var failCreate: SafGatewayFailure? = null
    var failDelete: SafGatewayFailure? = null
    var failOpenRead: SafGatewayFailure? = null
    var failOpenWrite: SafGatewayFailure? = null

    // -- Hooks --------------------------------------------------------------
    /** Invoked while a staging document's content is being written (for cancellation tests). */
    var onStageChunkWrite: (() -> Unit)? = null

    /** When set, a write whose staged size exceeds this many bytes throws [SafQuotaException] (quota). */
    var quotaBytes: Long? = null

    // -- Call / leak tracking ------------------------------------------------
    val createdNames: MutableList<String> = mutableListOf()
    val deletedIds: MutableList<String> = mutableListOf()
    var openStreamCount: Int = 0
        private set

    // -- Test setup / inspection helpers ------------------------------------

    fun seedDir(path: List<String>): String {
        var node = root
        for (name in path) {
            val existing = node.children[name]
            node = if (existing != null) {
                require(existing.directory) { "seed path crosses a file" }
                existing
            } else {
                val dir = Node(nextId(), name, directory = true, parent = node, lastModifiedMs = nowProvider())
                node.children[name] = dir
                nodesById[dir.id] = dir
                dir
            }
        }
        return node.id
    }

    fun seedFile(path: List<String>, content: ByteArray): String {
        require(path.isNotEmpty()) { "file path must not be empty" }
        val parentId = seedDir(path.dropLast(1))
        val parent = nodesById.getValue(parentId)
        val name = path.last()
        val file = Node(nextId(), name, directory = false, parent = parent, content = content.copyOf(), lastModifiedMs = nowProvider())
        parent.children[name] = file
        nodesById[file.id] = file
        return file.id
    }

    fun contentOf(documentId: String): ByteArray? =
        nodesById[documentId]?.takeIf { !it.directory }?.content?.copyOf()

    fun existsName(parentId: String, name: String): Boolean =
        nodesById[parentId]?.children?.containsKey(name) == true

    fun childNames(documentId: String): List<String> =
        nodesById[documentId]?.children?.values?.map { it.name } ?: emptyList()

    fun nodeCount(): Int = nodesById.size

    fun isStagePresent(parentId: String): Boolean =
        nodesById[parentId]?.children?.keys?.any { it.startsWith(".talkcan-stage") } == true

    // -- SafDocumentGateway --------------------------------------------------

    override fun treeRootId(treeUri: String): SafGatewayResult<String> =
        when {
            unavailable -> SafGatewayResult.Failed(SafGatewayFailure.UNAVAILABLE)
            else -> SafGatewayResult.Ok(root.id)
        }

    override fun queryDocument(documentId: String): SafGatewayResult<SafDocumentRow> {
        stateFailure()?.let { return SafGatewayResult.Failed(it) }
        failQueryDocument?.let { return SafGatewayResult.Failed(it) }
        val node = nodesById[documentId] ?: return SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        return SafGatewayResult.Ok(row(node))
    }

    override fun queryChildren(documentId: String): SafGatewayResult<List<SafDocumentRow>> {
        stateFailure()?.let { return SafGatewayResult.Failed(it) }
        failQueryChildren?.let { return SafGatewayResult.Failed(it) }
        if (malformedChildren) return SafGatewayResult.Failed(SafGatewayFailure.MALFORMED)
        val node = nodesById[documentId] ?: return SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        if (!node.directory) return SafGatewayResult.Failed(SafGatewayFailure.NOT_A_DIRECTORY)
        val rows = node.children.values.map { row(it) }.sortedBy { it.displayName }
        return SafGatewayResult.Ok(rows)
    }

    override fun hasChildren(documentId: String): SafGatewayResult<Boolean> {
        stateFailure()?.let { return SafGatewayResult.Failed(it) }
        failHasChildren?.let { return SafGatewayResult.Failed(it) }
        val node = nodesById[documentId] ?: return SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        if (!node.directory) return SafGatewayResult.Failed(SafGatewayFailure.NOT_A_DIRECTORY)
        return SafGatewayResult.Ok(node.children.isNotEmpty())
    }

    override fun createDocument(parentDocumentId: String, directory: Boolean, displayName: String): SafGatewayResult<String> {
        stateFailure()?.let { return SafGatewayResult.Failed(it) }
        failCreate?.let { return SafGatewayResult.Failed(it) }
        val parent = nodesById[parentDocumentId] ?: return SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        if (!parent.directory) return SafGatewayResult.Failed(SafGatewayFailure.NOT_A_DIRECTORY)
        val resolvedName = if (parent.children.containsKey(displayName)) {
            when (duplicateCreate) {
                DuplicateCreate.REJECT -> return SafGatewayResult.Failed(SafGatewayFailure.ALREADY_EXISTS)
                DuplicateCreate.AUTO_RENAME -> autoRename(parent, displayName)
            }
        } else {
            displayName
        }
        val node = Node(nextId(), resolvedName, directory, parent, lastModifiedMs = nowProvider())
        parent.children[resolvedName] = node
        nodesById[node.id] = node
        createdNames.add(resolvedName)
        return SafGatewayResult.Ok(node.id)
    }

    override fun deleteDocument(documentId: String): SafGatewayResult<Unit> {
        stateFailure()?.let { return SafGatewayResult.Failed(it) }
        failDelete?.let { return SafGatewayResult.Failed(it) }
        val node = nodesById[documentId] ?: return SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        if (node.directory && node.children.isNotEmpty()) {
            return SafGatewayResult.Failed(SafGatewayFailure.NOT_EMPTY)
        }
        node.parent?.children?.remove(node.name)
        nodesById.remove(documentId)
        deletedIds.add(documentId)
        return SafGatewayResult.Ok(Unit)
    }

    override fun openRead(documentId: String): SafGatewayResult<InputStream> {
        stateFailure()?.let { return SafGatewayResult.Failed(it) }
        failOpenRead?.let { return SafGatewayResult.Failed(it) }
        val node = nodesById[documentId] ?: return SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        if (node.directory) return SafGatewayResult.Failed(SafGatewayFailure.IS_A_DIRECTORY)
        openStreamCount++
        val delegate = ByteArrayInputStream(node.content.copyOf())
        return SafGatewayResult.Ok(CountingInputStream(delegate))
    }

    override fun openWrite(documentId: String, truncate: Boolean): SafGatewayResult<OutputStream> {
        stateFailure()?.let { return SafGatewayResult.Failed(it) }
        failOpenWrite?.let { return SafGatewayResult.Failed(it) }
        val node = nodesById[documentId] ?: return SafGatewayResult.Failed(SafGatewayFailure.NOT_FOUND)
        if (node.directory) return SafGatewayResult.Failed(SafGatewayFailure.IS_A_DIRECTORY)
        openStreamCount++
        return SafGatewayResult.Ok(CommittingOutputStream(node))
    }

    // -- Internals -----------------------------------------------------------

    private fun stateFailure(): SafGatewayFailure? = when {
        revoked -> SafGatewayFailure.REVOKED
        unavailable -> SafGatewayFailure.UNAVAILABLE
        else -> null
    }

    private fun row(node: Node): SafDocumentRow = SafDocumentRow(
        id = node.id,
        displayName = node.name,
        directory = node.directory,
        sizeBytes = if (node.directory) 0L else node.content.size.toLong(),
        lastModifiedMs = node.lastModifiedMs,
    )

    private fun autoRename(parent: Node, base: String): String {
        var candidate = "$base (1)"
        var n = 2
        while (parent.children.containsKey(candidate)) {
            candidate = "$base ($n)"
            n++
        }
        return candidate
    }

    private fun nextId(): String = "doc-${idSeq++}"

    private inner class CountingInputStream(private val delegate: InputStream) : InputStream() {
        private var open = true
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun close() {
            if (open) {
                open = false
                openStreamCount--
                delegate.close()
            }
        }
    }

    private inner class CommittingOutputStream(private val node: Node) : OutputStream() {
        private val buffer = ByteArrayOutputStream()
        private var open = true
        override fun write(b: Int) {
            buffer.write(b)
            enforceQuota()
            maybeStageHook()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            buffer.write(b, off, len)
            enforceQuota()
            maybeStageHook()
        }

        override fun flush() {}

        override fun close() {
            if (open) {
                open = false
                openStreamCount--
                node.content = buffer.toByteArray()
                node.lastModifiedMs = nowProvider()
            }
        }

        private fun maybeStageHook() {
            if (node.name.startsWith(".talkcan-stage")) {
                onStageChunkWrite?.invoke()
            }
        }

        private fun enforceQuota() {
            val limit = quotaBytes ?: return
            if (buffer.size().toLong() > limit) throw SafQuotaException()
        }
    }
}
