package io.talkcan.storage

import java.util.TreeMap

/**
 * In-memory [DocumentTreeBackend] for focused JVM tests.
 *
 * Models a document tree with opaque node identities, deterministic (name-sorted) pagination,
 * bounded reads, complete-on-success writes with a visible staging step and guaranteed cleanup,
 * and nonrecursive deletion. It also exposes failure and exception injection so tests can prove
 * the VFS core normalizes backend failures to the fixed portable vocabulary without leaking.
 *
 * This is test-only; it never references Android, SAF, or Journal types.
 */
internal class InMemoryDocumentTreeBackend : DocumentTreeBackend {

    private class MemNode(
        val ref: NodeRef,
        var name: String,
        var kind: BackendNodeKind,
        var parent: MemNode?,
    ) {
        val children: TreeMap<String, MemNode> = TreeMap()
        var content: ByteArray = ByteArray(0)
        var modifiedAtUnixMs: Long? = null
    }

    private val nodesById = HashMap<NodeRef, MemNode>()
    private var idCounter = 0L
    private val root: MemNode

    /** Supplies the modified time for newly created/written nodes; null means the provider omits it. */
    var nowProvider: () -> Long? = { null }

    // Per-operation injected failures (reported before any effect unless noted).
    var childFailure: BackendFailure? = null
    var infoFailure: BackendFailure? = null
    var createDirectoryFailure: BackendFailure? = null
    var listChildrenFailure: BackendFailure? = null
    var readFileFailure: BackendFailure? = null
    var deleteFailure: BackendFailure? = null

    /** Write failure raised during publish, after a stage node exists, to exercise cleanup. */
    var writeFailureDuringPublish: BackendFailure? = null

    /** Operation name ("child", "info", "createDirectory", "listChildren", "readFile", "writeFile", "delete") that throws. */
    var throwOn: String? = null

    /** Optional reason attached to injected failures, to exercise reason propagation/sanitization. */
    var injectedReason: String? = null

    init {
        root = MemNode(NodeRef("root"), "", BackendNodeKind.DIRECTORY, null)
        nodesById[root.ref] = root
    }

    val rootRef: NodeRef get() = root.ref

    // -- Test setup / inspection helpers ------------------------------------

    fun seedDir(path: List<String>): NodeRef {
        var node = root
        for (name in path) {
            val existing = node.children[name]
            node = if (existing != null) {
                check(existing.kind == BackendNodeKind.DIRECTORY) { "seed path crosses a file" }
                existing
            } else {
                val dir = newDir(name, node)
                node.children[name] = dir
                dir
            }
        }
        return node.ref
    }

    fun seedFile(path: List<String>, content: ByteArray): NodeRef {
        require(path.isNotEmpty()) { "file path must not be empty" }
        val parentRef = seedDir(path.dropLast(1))
        val parent = nodesById.getValue(parentRef)
        val name = path.last()
        val file = newFile(name, parent, content)
        parent.children[name] = file
        return file.ref
    }

    fun childrenNames(dirPath: List<String>): List<String> {
        var node = root
        for (name in dirPath) {
            node = node.children[name] ?: return emptyList()
        }
        return node.children.values.map { it.name }
    }

    fun fileContent(path: List<String>): ByteArray? {
        var node: MemNode = root
        for (name in path) {
            node = node.children[name] ?: return null
        }
        return if (node.kind == BackendNodeKind.FILE) node.content.copyOf() else null
    }

    fun exists(path: List<String>): Boolean {
        var node: MemNode = root
        for (name in path) {
            node = node.children[name] ?: return false
        }
        return true
    }

    // -- DocumentTreeBackend ------------------------------------------------

    override suspend fun child(parent: NodeRef, name: String): BackendResult<NodeRef> {
        if (throwOn == "child") throw simulated()
        childFailure?.let { return BackendResult.Err(it, injectedReason) }
        val parentNode = nodesById[parent] ?: return BackendResult.Err(BackendFailure.NOT_FOUND)
        if (parentNode.kind != BackendNodeKind.DIRECTORY) return BackendResult.Err(BackendFailure.NOT_A_DIRECTORY)
        val child = parentNode.children[name] ?: return BackendResult.Err(BackendFailure.NOT_FOUND)
        return BackendResult.Ok(child.ref)
    }

    override suspend fun info(node: NodeRef): BackendResult<BackendNodeInfo> {
        if (throwOn == "info") throw simulated()
        infoFailure?.let { return BackendResult.Err(it, injectedReason) }
        val n = nodesById[node] ?: return BackendResult.Err(BackendFailure.NOT_FOUND)
        val size = if (n.kind == BackendNodeKind.FILE) n.content.size.toLong() else 0L
        return BackendResult.Ok(BackendNodeInfo(n.name, n.kind, size, n.modifiedAtUnixMs))
    }

    override suspend fun createDirectory(parent: NodeRef, name: String): BackendResult<NodeRef> {
        if (throwOn == "createDirectory") throw simulated()
        createDirectoryFailure?.let { return BackendResult.Err(it, injectedReason) }
        val parentNode = nodesById[parent] ?: return BackendResult.Err(BackendFailure.NOT_FOUND)
        if (parentNode.kind != BackendNodeKind.DIRECTORY) return BackendResult.Err(BackendFailure.NOT_A_DIRECTORY)
        if (parentNode.children.containsKey(name)) return BackendResult.Err(BackendFailure.ALREADY_EXISTS)
        val dir = newDir(name, parentNode)
        parentNode.children[name] = dir
        return BackendResult.Ok(dir.ref)
    }

    override suspend fun listChildren(parent: NodeRef, pageToken: String?, limit: Int): BackendResult<BackendListPage> {
        if (throwOn == "listChildren") throw simulated()
        listChildrenFailure?.let { return BackendResult.Err(it, injectedReason) }
        val parentNode = nodesById[parent] ?: return BackendResult.Err(BackendFailure.NOT_FOUND)
        if (parentNode.kind != BackendNodeKind.DIRECTORY) return BackendResult.Err(BackendFailure.NOT_A_DIRECTORY)
        val all = parentNode.children.values.toList() // TreeMap iteration is name-sorted: deterministic.
        val start = pageToken?.toIntOrNull() ?: 0
        if (start >= all.size) return BackendResult.Ok(BackendListPage(emptyList(), null))
        val end = (start + limit).coerceAtMost(all.size)
        val entries = all.subList(start, end).map { BackendEntry(it.name, it.kind) }
        val next = if (end < all.size) end.toString() else null
        return BackendResult.Ok(BackendListPage(entries, next))
    }

    override suspend fun readFile(node: NodeRef, maxBytes: Long): BackendResult<ByteArray> {
        if (throwOn == "readFile") throw simulated()
        readFileFailure?.let { return BackendResult.Err(it, injectedReason) }
        val n = nodesById[node] ?: return BackendResult.Err(BackendFailure.NOT_FOUND)
        if (n.kind != BackendNodeKind.FILE) return BackendResult.Err(BackendFailure.IS_A_DIRECTORY)
        if (n.content.size.toLong() > maxBytes) return BackendResult.Err(BackendFailure.TOO_LARGE)
        return BackendResult.Ok(n.content.copyOf())
    }

    override suspend fun writeFile(
        parent: NodeRef,
        name: String,
        bytes: ByteArray,
        mode: BackendWriteMode,
    ): BackendResult<NodeRef> {
        val parentNode = nodesById[parent] ?: return BackendResult.Err(BackendFailure.NOT_FOUND)
        if (parentNode.kind != BackendNodeKind.DIRECTORY) return BackendResult.Err(BackendFailure.NOT_A_DIRECTORY)
        val existing = parentNode.children[name]
        if (mode == BackendWriteMode.CREATE_NEW && existing != null) {
            return BackendResult.Err(BackendFailure.ALREADY_EXISTS)
        }
        if (existing != null && existing.kind == BackendNodeKind.DIRECTORY) {
            return BackendResult.Err(BackendFailure.IS_A_DIRECTORY)
        }

        // Stage under a distinct name, then publish complete-on-success. On any failure the stage
        // is removed in `finally`, so no partial or staged node is ever left behind.
        val stageName = ".$name.__stage__"
        val staged = newFile(stageName, parentNode, bytes)
        parentNode.children[stageName] = staged
        var published = false
        try {
            if (throwOn == "writeFile") throw simulated()
            writeFailureDuringPublish?.let { return BackendResult.Err(it, injectedReason) }
            parentNode.children.remove(name)
            staged.name = name
            parentNode.children[name] = staged
            published = true
            return BackendResult.Ok(staged.ref)
        } finally {
            if (!published) {
                parentNode.children.remove(stageName)
            }
        }
    }

    override suspend fun delete(node: NodeRef): BackendResult<Unit> {
        if (throwOn == "delete") throw simulated()
        deleteFailure?.let { return BackendResult.Err(it, injectedReason) }
        val n = nodesById[node] ?: return BackendResult.Err(BackendFailure.NOT_FOUND)
        if (n === root) return BackendResult.Err(BackendFailure.BUSY)
        if (n.kind == BackendNodeKind.DIRECTORY && n.children.isNotEmpty()) {
            return BackendResult.Err(BackendFailure.NOT_EMPTY)
        }
        n.parent?.children?.remove(n.name)
        nodesById.remove(n.ref)
        return BackendResult.Ok(Unit)
    }

    // -- Internals ----------------------------------------------------------

    private fun newDir(name: String, parent: MemNode): MemNode {
        val node = MemNode(nextRef(), name, BackendNodeKind.DIRECTORY, parent)
        node.modifiedAtUnixMs = nowProvider()
        nodesById[node.ref] = node
        return node
    }

    private fun newFile(name: String, parent: MemNode, content: ByteArray): MemNode {
        val node = MemNode(nextRef(), name, BackendNodeKind.FILE, parent)
        node.content = content.copyOf()
        node.modifiedAtUnixMs = nowProvider()
        nodesById[node.ref] = node
        return node
    }

    private fun nextRef(): NodeRef = NodeRef("n${idCounter++}")

    private fun simulated(): RuntimeException =
        RuntimeException("content://secret/document/42 at /storage/emulated/0 SAF FileNotFoundException")
}
