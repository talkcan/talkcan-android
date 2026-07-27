package io.talkcan.dependency

import io.talkcan.model.ChannelImplementationId
import io.talkcan.lua.LuaKernelBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sealed typed mutation results distinguishing Installed, Updated, Reinstalled(idempotent),
 * RolledBack, Removed, and publication-after-commit failure.
 */
public sealed interface MutationResult {
    public data class Installed(val implementationId: ChannelImplementationId) : MutationResult
    public data class Updated(val implementationId: ChannelImplementationId) : MutationResult
    public data class Reinstalled(val implementationId: ChannelImplementationId) : MutationResult
    public data class RolledBack(val implementationId: ChannelImplementationId) : MutationResult
    public data class Removed(val implementationId: ChannelImplementationId) : MutationResult
    public data class PublicationFailure(val error: PackageFailure) : MutationResult
}

/**
 * Closeable package repository managing the package life cycle transactions.
 *
 * @param publisher consumes the whole immutable [MaterializationResult] (valid bindings +
 *   typed per-provider failures); the host composition is responsible for atomic
 *   publication of valid bindings and reconciliation/reporting of failures.
 */
public class InstalledPackageRepository internal constructor(
    private val store: InstalledPackageStore,
    private val bridge: LuaKernelBridge,
    private val publisher: suspend (MaterializationResult) -> PackageOutcome<Unit>,
    private val dispatcher: CoroutineDispatcher,
    private val bounds: PackageValidationBounds = PackageValidationBounds.DEFAULT
) {
    private val transactionMutex = Mutex()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var needsRecovery = false

    @Volatile
    private var activeInputStream: InputStream? = null
    private val activeInputStreamLock = Any()


    /**
     * Installs or updates a package from an input stream.
     */
    public suspend fun installOrUpdate(
        inputStream: InputStream,
        sourceRecord: PackageSourceRecord
    ): PackageOutcome<MutationResult> {
        if (closed.get()) {
            return PackageOutcome.Failure(
                PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
            )
        }
        if (needsRecovery) {
            return PackageOutcome.Failure(
                PackageFailure.Recovery(PackageFailure.RecoveryDetail.RECOVERY_INDEX_INVALID)
            )
        }

        return transactionMutex.withLock {
            if (closed.get()) {
                return@withLock PackageOutcome.Failure(
                    PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
                )
            }
            if (needsRecovery) {
                return@withLock PackageOutcome.Failure(
                    PackageFailure.Recovery(PackageFailure.RecoveryDetail.RECOVERY_INDEX_INVALID)
                )
            }

            val operationId = UUID.randomUUID().toString()
            val stagingFile = store.stagingHandle(operationId)

            try {
                withContext(dispatcher) {
                    // Step 1: Register the stream for shutdown cancellation (race-safe with requestClose).
                    synchronized(activeInputStreamLock) {
                        if (closed.get()) {
                            return@withContext PackageOutcome.Failure(
                                PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
                            )
                        }
                        activeInputStream = inputStream
                    }

                    // Step 2: Stage + static validation.
                    val validatedRevision = when (val outcome = PackageValidator.validatePackage(inputStream, sourceRecord, stagingFile, bounds)) {
                        is PackageOutcome.Success -> outcome.value
                        is PackageOutcome.Failure -> return@withContext PackageOutcome.Failure(outcome.error)
                    }

                    // Shutdown checkpoint after validation: abort before content commit.
                    if (closed.get()) {
                        val discardResult = store.discardStaging(stagingFile)
                        if (discardResult is PackageOutcome.Failure) {
                            return@withContext PackageOutcome.Failure(discardResult.error)
                        }
                        return@withContext PackageOutcome.Failure(
                            PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
                        )
                    }

                    // Step 3: Load current index.
                    val index = when (val indexResult = store.loadIndex()) {
                        is PackageOutcome.Success -> indexResult.value.index
                        is PackageOutcome.Failure -> return@withContext indexResult
                    }

                    val repoId = sourceRecord.repositoryId
                    val implId = InstalledProviderId.derive(repoId)
                    val record = index.providers[repoId]

                    // Step 4: Idempotent exact reinstall: no index/content/publication mutation.
                    if (record != null && record.active.digest == validatedRevision.digest) {
                        val discardResult = store.discardStaging(stagingFile)
                        if (discardResult is PackageOutcome.Failure) {
                            return@withContext PackageOutcome.Failure(discardResult.error)
                        }
                        return@withContext PackageOutcome.Success(MutationResult.Reinstalled(implId))
                    }

                    // Shutdown checkpoint before content commit.
                    if (closed.get()) {
                        val discardResult = store.discardStaging(stagingFile)
                        if (discardResult is PackageOutcome.Failure) {
                            return@withContext PackageOutcome.Failure(discardResult.error)
                        }
                        return@withContext PackageOutcome.Failure(
                            PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
                        )
                    }

                    // Step 5: Commit immutable content.
                    val commitContentResult = store.commitContent(stagingFile, validatedRevision.digest)
                    if (commitContentResult is PackageOutcome.Failure) {
                        return@withContext PackageOutcome.Failure(commitContentResult.error)
                    }

                    // Shutdown checkpoint before index commit.
                    if (closed.get()) {
                        return@withContext PackageOutcome.Failure(
                            PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
                        )
                    }

                    // Step 6: Build immutable copied index mutation.
                    val newActive = StoredPackageRevision(
                        digest = validatedRevision.digest,
                        manifest = validatedRevision.manifest,
                        sourceRecord = validatedRevision.sourceRecord
                    )
                    val newRollback = record?.active
                    val newRecord = StoredProviderRecord(active = newActive, rollback = newRollback)
                    val newProviders = LinkedHashMap(index.providers).apply {
                        this[repoId] = newRecord
                    }
                    val newIndex = StoredInstalledIndex(index.version, newProviders)

                    // Step 7: Commit index.
                    var committed = false
                    try {
                        val commitIndexResult = store.commitIndex(newIndex)
                        if (commitIndexResult is PackageOutcome.Failure) {
                            if (commitIndexResult.error is PackageFailure.Recovery &&
                                commitIndexResult.error.detail == PackageFailure.RecoveryDetail.COMMIT_STATE_AMBIGUOUS) {
                                handleAmbiguousCommit(implId)
                                return@withContext PackageOutcome.Failure(
                                    PackageFailure.Recovery(PackageFailure.RecoveryDetail.COMMIT_STATE_AMBIGUOUS, implId)
                                )
                            }
                            return@withContext PackageOutcome.Failure(commitIndexResult.error)
                        }
                        committed = true
                    } finally {
                        if (!committed) {
                            // No content rollback; committed bytes remain and are recovered by cleanup.
                        }
                    }

                    // --- POST COMMIT: every error must be PublicationFailure, never outer Failure. ---

                    // Shutdown suppression after commit -> PublicationFailure(Shutdown).
                    if (closed.get()) {
                        return@withContext PackageOutcome.Success(
                            MutationResult.PublicationFailure(
                                PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS, implId)
                            )
                        )
                    }

                    // Re-materialize from committed exact bytes.
                    val matResult = store.loadAndMaterialize(bridge, bounds)
                    if (matResult is PackageOutcome.Failure) {
                        return@withContext PackageOutcome.Success(
                            MutationResult.PublicationFailure(matResult.error)
                        )
                    }

                    val matSuccess = matResult as PackageOutcome.Success
                    val pubOutcome = publisher(matSuccess.value)
                    if (pubOutcome is PackageOutcome.Failure) {
                        return@withContext PackageOutcome.Success(
                            MutationResult.PublicationFailure(pubOutcome.error)
                        )
                    }

                    val result = if (record == null) {
                        MutationResult.Installed(implId)
                    } else {
                        MutationResult.Updated(implId)
                    }
                    PackageOutcome.Success(result)
                }
            } finally {
                synchronized(activeInputStreamLock) {
                    activeInputStream = null
                }
            }
        }
    }

    /**
     * Swaps active/rollback without download. Revalidates retained rollback bytes via store.
     */
    public suspend fun rollback(
        repositoryId: GitHubRepositoryIdentity
    ): PackageOutcome<MutationResult> {
        if (closed.get()) {
            return PackageOutcome.Failure(
                PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
            )
        }
        if (needsRecovery) {
            return PackageOutcome.Failure(
                PackageFailure.Recovery(PackageFailure.RecoveryDetail.RECOVERY_INDEX_INVALID)
            )
        }

        return transactionMutex.withLock {
            if (closed.get()) {
                return@withLock PackageOutcome.Failure(
                    PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
                )
            }
            if (needsRecovery) {
                return@withLock PackageOutcome.Failure(
                    PackageFailure.Recovery(PackageFailure.RecoveryDetail.RECOVERY_INDEX_INVALID)
                )
            }

            val implId = InstalledProviderId.derive(repositoryId)

            withContext(dispatcher) {
                // Load authoritative index (copied).
                val index = when (val indexResult = store.loadIndex()) {
                    is PackageOutcome.Success -> indexResult.value.index
                    is PackageOutcome.Failure -> return@withContext indexResult
                }

                val record = index.providers[repositoryId]
                    ?: return@withContext PackageOutcome.Failure(
                        PackageFailure.Rollback(PackageFailure.RollbackDetail.NO_ROLLBACK_REVISION, implId)
                    )

                val rollbackRevision = record.rollback
                    ?: return@withContext PackageOutcome.Failure(
                        PackageFailure.Rollback(PackageFailure.RollbackDetail.NO_ROLLBACK_REVISION, implId)
                    )

                // Revalidate retained rollback bytes via store typed API.
                val revalidateResult = store.revalidateStoredRevision(rollbackRevision, bounds)
                if (revalidateResult is PackageOutcome.Failure) {
                    val mapped = mapRevalidateFailure(revalidateResult.error, implId)
                    return@withContext PackageOutcome.Failure(mapped)
                }

                // Shutdown checkpoint before index commit.
                if (closed.get()) {
                    return@withContext PackageOutcome.Failure(
                        PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
                    )
                }

                // Build immutable copied mutation.
                val newRecord = StoredProviderRecord(active = rollbackRevision, rollback = record.active)
                val newProviders = LinkedHashMap(index.providers).apply {
                    this[repositoryId] = newRecord
                }
                val newIndex = StoredInstalledIndex(index.version, newProviders)

                val commitIndexResult = store.commitIndex(newIndex)
                if (commitIndexResult is PackageOutcome.Failure) {
                    if (commitIndexResult.error is PackageFailure.Recovery &&
                        commitIndexResult.error.detail == PackageFailure.RecoveryDetail.COMMIT_STATE_AMBIGUOUS) {
                        handleAmbiguousCommit(implId)
                        return@withContext PackageOutcome.Failure(
                            PackageFailure.Recovery(PackageFailure.RecoveryDetail.COMMIT_STATE_AMBIGUOUS, implId)
                        )
                    }
                    return@withContext PackageOutcome.Failure(commitIndexResult.error)
                }

                // --- POST COMMIT ---

                if (closed.get()) {
                    return@withContext PackageOutcome.Success(
                        MutationResult.PublicationFailure(
                            PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS, implId)
                        )
                    )
                }

                val matResult = store.loadAndMaterialize(bridge, bounds)
                if (matResult is PackageOutcome.Failure) {
                    return@withContext PackageOutcome.Success(
                        MutationResult.PublicationFailure(matResult.error)
                    )
                }
                val matSuccess = matResult as PackageOutcome.Success
                val pubOutcome = publisher(matSuccess.value)
                if (pubOutcome is PackageOutcome.Failure) {
                    return@withContext PackageOutcome.Success(
                        MutationResult.PublicationFailure(pubOutcome.error)
                    )
                }

                PackageOutcome.Success(MutationResult.RolledBack(implId))
            }
        }
    }

    /**
     * Removes the active provider binding for the repository identity.
     */
    public suspend fun remove(
        repositoryId: GitHubRepositoryIdentity
    ): PackageOutcome<MutationResult> {
        if (closed.get()) {
            return PackageOutcome.Failure(
                PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
            )
        }
        if (needsRecovery) {
            return PackageOutcome.Failure(
                PackageFailure.Recovery(PackageFailure.RecoveryDetail.RECOVERY_INDEX_INVALID)
            )
        }

        return transactionMutex.withLock {
            if (closed.get()) {
                return@withLock PackageOutcome.Failure(
                    PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
                )
            }
            if (needsRecovery) {
                return@withLock PackageOutcome.Failure(
                    PackageFailure.Recovery(PackageFailure.RecoveryDetail.RECOVERY_INDEX_INVALID)
                )
            }

            val implId = InstalledProviderId.derive(repositoryId)

            withContext(dispatcher) {
                val index = when (val indexResult = store.loadIndex()) {
                    is PackageOutcome.Success -> indexResult.value.index
                    is PackageOutcome.Failure -> return@withContext indexResult
                }

                // Typed missing behavior via NOT_INSTALLED.
                if (!index.providers.containsKey(repositoryId)) {
                    return@withContext PackageOutcome.Failure(
                        PackageFailure.Mutation(PackageFailure.MutationDetail.NOT_INSTALLED, implId)
                    )
                }

                // Shutdown checkpoint before index commit.
                if (closed.get()) {
                    return@withContext PackageOutcome.Failure(
                        PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
                    )
                }

                // Build immutable copied mutation.
                val newProviders = LinkedHashMap(index.providers).apply {
                    this.remove(repositoryId)
                }
                val newIndex = StoredInstalledIndex(index.version, newProviders)

                val commitIndexResult = store.commitIndex(newIndex)
                if (commitIndexResult is PackageOutcome.Failure) {
                    if (commitIndexResult.error is PackageFailure.Recovery &&
                        commitIndexResult.error.detail == PackageFailure.RecoveryDetail.COMMIT_STATE_AMBIGUOUS) {
                        handleAmbiguousCommit(implId)
                        return@withContext PackageOutcome.Failure(
                            PackageFailure.Recovery(PackageFailure.RecoveryDetail.COMMIT_STATE_AMBIGUOUS, implId)
                        )
                    }
                    return@withContext PackageOutcome.Failure(commitIndexResult.error)
                }

                // --- POST COMMIT ---

                if (closed.get()) {
                    return@withContext PackageOutcome.Success(
                        MutationResult.PublicationFailure(
                            PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS, implId)
                        )
                    )
                }

                val matResult = store.loadAndMaterialize(bridge, bounds)
                if (matResult is PackageOutcome.Failure) {
                    return@withContext PackageOutcome.Success(
                        MutationResult.PublicationFailure(matResult.error)
                    )
                }
                val matSuccess = matResult as PackageOutcome.Success
                val pubOutcome = publisher(matSuccess.value)
                if (pubOutcome is PackageOutcome.Failure) {
                    return@withContext PackageOutcome.Success(
                        MutationResult.PublicationFailure(pubOutcome.error)
                    )
                }

                PackageOutcome.Success(MutationResult.Removed(implId))
            }
        }
    }

    /**
     * Loads the active packages index from local storage, revalidates and publishes them.
     */
    public suspend fun loadAndPublish(): PackageOutcome<Unit> = recover()

    /**
     * Recovers active packages index from local storage, revalidates and publishes them.
     * Clears the [needsRecovery] gate when publication succeeds.
     */
    public suspend fun recover(): PackageOutcome<Unit> {
        if (closed.get()) {
            return PackageOutcome.Failure(
                PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
            )
        }

        return transactionMutex.withLock {
            if (closed.get()) {
                return@withLock PackageOutcome.Failure(
                    PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
                )
            }

            withContext(dispatcher) {
                val matResult = store.loadAndMaterialize(bridge, bounds)
                if (matResult is PackageOutcome.Failure) {
                    return@withContext PackageOutcome.Failure(matResult.error)
                }

                if (closed.get()) {
                    return@withContext PackageOutcome.Failure(
                        PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
                    )
                }

                val matSuccess = matResult as PackageOutcome.Success
                val pubOutcome = publisher(matSuccess.value)
                if (pubOutcome is PackageOutcome.Failure) {
                    return@withContext PackageOutcome.Failure(pubOutcome.error)
                }

                // Recovery publication succeeded; clear the recovery gate.
                needsRecovery = false
                PackageOutcome.Success(Unit)
            }
        }
    }

    /**
     * Non-blocking request to stop admission and cancel the currently admitted InputStream.
     * Idempotent.
     */
    public fun requestClose() {
        if (closed.compareAndSet(false, true)) {
            synchronized(activeInputStreamLock) {
                activeInputStream?.let { stream ->
                    runCatching { stream.close() }
                }
                activeInputStream = null
            }
        }
    }

    /**
     * Suspend until the currently admitted transaction completes, then invoke bounded
     * cleanup against the authoritative selected index. Returns the cleanup result.
     * If the transaction does not complete within the timeout, returns TRANSACTION_ABORTED.
     */
    public suspend fun closeAndAwait(timeoutMillis: Long): PackageOutcome<CleanupResult> {
        require(timeoutMillis > 0) { "timeoutMillis must be > 0" }
        requestClose()
        val acquired = withTimeoutOrNull(timeoutMillis) {
            transactionMutex.withLock {
                withContext(dispatcher) {
                    val indexResult = store.loadIndex()
                    when (indexResult) {
                        is PackageOutcome.Success -> {
                            store.cleanup(indexResult.value.index)
                        }
                        is PackageOutcome.Failure -> {
                            // Index load failed: clean staging ONLY, preserving all committed content.
                            val stagingCleanup = store.cleanupStagingOnly()
                            when (stagingCleanup) {
                                is PackageOutcome.Success ->
                                    PackageOutcome.Failure(indexResult.error)
                                is PackageOutcome.Failure -> stagingCleanup
                            }
                        }
                    }
                }
            }
        }
        return acquired ?: PackageOutcome.Failure(
            PackageFailure.Shutdown(PackageFailure.ShutdownDetail.TRANSACTION_ABORTED)
        )
    }

    /**
     * On a COMMIT_STATE_AMBIGUOUS, reload the authoritative index and set the needsRecovery gate.
     * Further mutations are blocked until [recover] publishes successfully.
     */
    private suspend fun handleAmbiguousCommit(implId: ChannelImplementationId) {
        needsRecovery = true
        // Reload authoritative index (best-effort); mutation stays blocked until recovery.
        withContext(dispatcher) {
            store.loadIndex()
        }
    }

    private fun mapRevalidateFailure(error: PackageFailure, implId: ChannelImplementationId): PackageFailure {
        return when (error) {
            is PackageFailure.Integrity,
            is PackageFailure.Mutation,
            is PackageFailure.Format,
            is PackageFailure.Compatibility,
            is PackageFailure.Capability,
            is PackageFailure.Identity -> PackageFailure.Rollback(PackageFailure.RollbackDetail.ROLLBACK_VALIDATION_FAILED, implId)
            else -> error
        }
    }
}
