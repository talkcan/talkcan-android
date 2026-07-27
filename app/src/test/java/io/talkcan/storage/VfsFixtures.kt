package io.talkcan.storage

/** Shared fixtures for focused VFS tests: an in-memory backend wired through a mutable resolver. */

internal fun inMemoryMount(
    backend: InMemoryDocumentTreeBackend,
    token: String = "output-token",
    declarationId: String = "output",
    generation: Long = 1L,
    access: MountAccessMode = MountAccessMode.READ_WRITE,
    grantFingerprint: String = "grant-fingerprint-$token",
): ResolvedMount = ResolvedMount(
    mountToken = token,
    declarationId = declarationId,
    generation = generation,
    access = access,
    grantFingerprint = grantFingerprint,
    backend = backend,
    root = backend.rootRef,
)

/** A resolver whose resolved mount (or failure) tests can change to simulate lifecycle transitions. */
internal class MutableResolver(var mount: ResolvedMount?) : MountResolver {
    var failure: FilesystemError? = null
    var calls: Int = 0

    override fun resolve(declarationId: String): MountResolution {
        calls++
        failure?.let { return MountResolution.Failed(it) }
        val resolved = mount
            ?: return MountResolution.Failed(
                FilesystemError(FilesystemErrorCode.E_CAPABILITY_UNDECLARED, "mount not declared"),
            )
        return MountResolution.Resolved(resolved)
    }
}

/** A live binding-status revalidator whose result tests can flip to simulate grant revocation. */
internal class MutableRevalidator : MountLeaseRevalidator {
    var failure: FilesystemError? = null
    var calls: Int = 0

    override fun revalidate(facts: MountLeaseFacts): FilesystemOutcome<Unit> {
        calls++
        failure?.let { return FilesystemOutcome.Failure(it) }
        return FilesystemOutcome.Success(Unit)
    }
}

/**
 * Convenience harness: one in-memory backend, one lease registry, one core, and handles for the
 * current generation. Lifecycle transitions (generation advance, binding replacement) route
 * through the core's lifecycle methods so predecessor leases invalidate faithfully.
 */
internal class Vfs(
    val backend: InMemoryDocumentTreeBackend = InMemoryDocumentTreeBackend(),
    val policy: VfsPolicy = VfsPolicy(),
    val limits: VfsLimits = VfsLimits(),
    token: String = "output-token",
    generation: Long = 1L,
    stateId: String = "state-1",
    instanceId: String = "instance-1",
) {
    var token: String = token
        private set
    var generation: Long = generation
        private set

    val owner = LeaseOwner(stateId, instanceId, generation)
    val resolver = MutableResolver(inMemoryMount(backend, token = token, generation = generation))
    val revalidator = MutableRevalidator()
    val registry = MountLeaseRegistry(owner, resolver, revalidator)
    val fs = MountedFilesystem(registry, policy, limits)

    fun handle(): MountHandle =
        (fs.mount("output") as FilesystemOutcome.Success).value

    fun advanceGeneration(newGeneration: Long): MountHandle {
        generation = newGeneration
        resolver.mount = inMemoryMount(backend, token = token, generation = newGeneration)
        fs.advanceGeneration(newGeneration)
        return handle()
    }

    fun switchMount(newToken: String): MountHandle {
        token = newToken
        resolver.mount = inMemoryMount(backend, token = newToken, generation = generation)
        fs.revokeAll(MountRevocationSource.BINDING_REPLACED)
        return handle()
    }
}

internal fun <T> FilesystemOutcome<T>.success(): T =
    (this as FilesystemOutcome.Success).value

internal fun <T> FilesystemOutcome<T>.failure(): FilesystemError =
    (this as FilesystemOutcome.Failure).error
