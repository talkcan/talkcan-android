package io.talkcan.audio

import android.content.Context
import io.talkcan.model.ModelAcquisitionProgress
import io.talkcan.model.ModelAssetResult
import io.talkcan.model.ModelSetProgress
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-scoped model asset repository: the single authoritative owner of
 * model inspection, acquisition, repair, and progress observation.
 *
 * Wraps [ModelVerifier] and [ModelDownloader] with a per-model-set
 * single-flight coroutine. Concurrent requests for the same model set join
 * the in-flight operation and receive its terminal result instead of
 * starting a second writer. Progress is published through a shared
 * [StateFlow] so all observers receive updates from the single active
 * operation.
 *
 * Key guarantees:
 * - A matching version marker NEVER bypasses file-presence, nonzero-length,
 *   and SHA-256 validation (see [inspect]).
 * - A completion marker is committed only after every required file in the
 *   set verifies (see [ModelDownloader.downloadSet]).
 * - The aggregate result is emitted only after a final full verification of
 *   all required sets (see [acquireAll] and [ensureReady]).
 * - Partial-file HTTP Range resume and retry behavior is preserved because
 *   acquisition delegates to [ModelDownloader.downloadSet], which reuses the
 *   existing per-file [ModelDownloader.downloadFileWithRetries] logic.
 *
 * The repository retains only `applicationContext` and process-scoped state.
 */
class ModelAssetRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    /**
     * Per-model-set single-flight mutexes. Each acquisition holds its mutex
     * for the entire download+verify cycle so a second request joins the
     * in-flight operation.
     */
    private val setMutexes = mutableMapOf<String, Mutex>()
    private val setMutexesLock = Mutex()

    /**
     * In-flight acquisition deferreds per model set. A concurrent caller
     * retrieves the existing deferred and awaits it.
     */
    private val inFlight = mutableMapOf<String, Deferred<ModelAssetResult>>()
    private val inFlightLock = Mutex()

    private val _progress = MutableStateFlow(ModelAcquisitionProgress())
    val progress: StateFlow<ModelAcquisitionProgress> = _progress.asStateFlow()

    /**
     * Inspect a model set's validity WITHOUT downloading. Returns
     * [ModelAssetResult.Valid] only when the version marker matches AND every
     * required file is present, non-zero-length, and SHA-256 verified.
     *
     * A matching marker alone is NOT sufficient — this closes the
     * marker-matches-but-hash-fails repair path that [ModelDownloader.ensure]
     * previously bypassed.
     */
    suspend fun inspect(dirName: String): ModelAssetResult {
        val manifest = ModelVerifier.loadManifest(appContext)
        val status = setMutexesLock.withLock {
            val m = setMutexes.getOrPut(dirName) { Mutex() }
            m.withLock {
                ModelVerifier.status(appContext, manifest, dirName)
            }
        }
        return when (status) {
            ModelSetStatus.Valid -> ModelAssetResult.Valid(dirName)
            ModelSetStatus.NeedsDownload -> ModelAssetResult.UserActionRequired(
                dirName,
                "Model set '$dirName' is absent, version-mismatched, or hash-invalid",
            )
        }
    }

    /**
     * Inspect all model sets in the manifest. Returns the list of
     * [ModelAssetResult.UserActionRequired] entries for sets that need
     * download or repair. An empty list means all sets are valid.
     */
    suspend fun inspectAll(): List<ModelAssetResult> {
        val manifest = ModelVerifier.loadManifest(appContext)
        val results = mutableListOf<ModelAssetResult>()
        for (dirName in manifest.sets.keys) {
            results.add(inspect(dirName))
        }
        return results
    }

    /**
     * Acquire (download + verify) a single model set. If an acquisition for
     * the same set is already in flight, the caller joins it and receives
     * the same terminal result.
     *
     * Returns [ModelAssetResult.Valid] on success, or
     * [ModelAssetResult.Failed] on failure.
     */
    suspend fun acquire(dirName: String): ModelAssetResult {
        val mutex = setMutexesLock.withLock { setMutexes.getOrPut(dirName) { Mutex() } }

        // Fast check: already in flight?
        val existing = inFlightLock.withLock { inFlight[dirName] }
        if (existing != null && !existing.isCompleted) {
            return existing.await()
        }

        // Acquire single-flight for this set.
        return mutex.withLock {
            // Double-check in-flight after acquiring mutex.
            val existing2 = inFlightLock.withLock { inFlight[dirName] }
            if (existing2 != null && !existing2.isCompleted) {
                return@withLock existing2.await()
            }

            val deferred = scope.async(Dispatchers.IO) {
                try {
                    _progress.value = _progress.value.copy(
                        sets = _progress.value.sets + ModelSetProgress(dirName),
                    )
                    val result = ModelDownloader.ensureFull(appContext, dirName) { progress ->
                        _progress.value = _progress.value.copy(
                            sets = _progress.value.sets.map {
                                if (it.dirName == dirName) ModelSetProgress(
                                    dirName = dirName,
                                    currentFile = progress.currentFile,
                                    bytesRead = progress.bytesRead,
                                    totalBytes = progress.totalBytes,
                                    fileIndex = progress.fileIndex,
                                    fileCount = progress.fileCount,
                                )
                                else it
                            },
                        )
                    }
                    if (result) {
                        val manifest = ModelVerifier.loadManifest(appContext)
                        val status = ModelVerifier.status(appContext, manifest, dirName)
                        when (status) {
                            ModelSetStatus.Valid -> ModelAssetResult.Valid(dirName)
                            ModelSetStatus.NeedsDownload -> ModelAssetResult.Failed(
                                dirName,
                                "Post-download verification failed for '$dirName'",
                            )
                        }
                    } else {
                        ModelAssetResult.Failed(
                            dirName,
                            "Download failed for '$dirName'",
                        )
                    }
                } catch (e: Exception) {
                    ModelAssetResult.Failed(dirName, e.message ?: "Acquisition failed")
                } finally {
                    inFlightLock.withLock { inFlight.remove(dirName) }
                }
            }

            inFlightLock.withLock { inFlight[dirName] = deferred }
            deferred.await()
        }
    }

    /**
     * Acquire all specified model sets and run a final aggregate full
     * verification before reporting readiness.
     *
     * Returns `true` only if every set in [dirNames] passes a fresh full
     * verification after acquisition.
     */
    suspend fun ensureReady(dirNames: List<String>): Boolean {
        val results = dirNames.map { acquire(it) }
        // Aggregate full verification.
        val manifest = ModelVerifier.loadManifest(appContext)
        return manifest.sets.keys
            .filter { it in dirNames }
            .all { ModelVerifier.status(appContext, manifest, it) == ModelSetStatus.Valid }
    }

    /**
     * Convenience: acquire all sets in the manifest.
     */
    suspend fun ensureAllReady(): Boolean {
        val manifest = ModelVerifier.loadManifest(appContext)
        return ensureReady(manifest.sets.keys.toList())
    }

    /**
     * Check whether all model sets are valid WITHOUT downloading.
     */
    suspend fun isAllValid(): Boolean {
        val manifest = ModelVerifier.loadManifest(appContext)
        return manifest.sets.keys.all {
            ModelVerifier.status(appContext, manifest, it) == ModelSetStatus.Valid
        }
    }
}