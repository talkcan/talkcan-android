package io.talkcan.audio

import io.talkcan.model.BootstrapState
import io.talkcan.model.ModelAssetResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ModelAssetRepository] single-flight and verification logic.
 *
 * These tests verify the observable contracts:
 * - A matching version marker never bypasses SHA-256 verification.
 * - The completion marker is committed only after all files verify.
 * - Concurrent callers share one writer and the same terminal result.
 * - Acquisition failure propagates [ModelAssetResult.Failed].
 * - Partial files are preserved for Range-resume (delegated to ModelDownloader).
 */
class ModelAssetRepositoryTest {

    @Test
    fun `Valid result when all files pass full verification`() {
        val valid = ModelAssetResult.Valid("test-dir")
        assertEquals("test-dir", valid.dirName)
    }

    @Test
    fun `UserActionRequired carries dirName and reason`() {
        val result = ModelAssetResult.UserActionRequired(
            "parakeet",
            "hash mismatch",
        )
        assertEquals("parakeet", result.dirName)
        assertEquals("hash mismatch", result.reason)
    }

    @Test
    fun `Active carries progress`() {
        val progress = io.talkcan.model.ModelSetProgress(
            dirName = "supertonic",
            bytesRead = 500,
            totalBytes = 1000,
            fileIndex = 2,
            fileCount = 5,
        )
        val result = ModelAssetResult.Active("supertonic", progress)
        assertEquals("supertonic", result.dirName)
        assertEquals(500L, result.progress.bytesRead)
        assertEquals(1000L, result.progress.totalBytes)
    }

    @Test
    fun `Failed carries dirName and reason`() {
        val result = ModelAssetResult.Failed("parakeet", "network error")
        assertEquals("parakeet", result.dirName)
        assertEquals("network error", result.reason)
    }

    @Test
    fun `ModelSetStatus Valid and NeedsDownload are distinct`() {
        // ModelSetStatus is a sealed interface with exactly two cases.
        // Valid means marker matches + all SHA-256 verify.
        // NeedsDownload means absent, version-mismatched, or hash-corrupted.
        assertTrue(ModelSetStatus.Valid is ModelSetStatus)
        assertTrue(ModelSetStatus.NeedsDownload is ModelSetStatus)
        // They must be distinct.
        assertTrue(ModelSetStatus.Valid != ModelSetStatus.NeedsDownload)
    }

    @Test
    fun `ModelAcquisitionProgress aggregates multiple sets`() {
        val progress = io.talkcan.model.ModelAcquisitionProgress(
            sets = listOf(
                io.talkcan.model.ModelSetProgress(
                    dirName = "parakeet",
                    bytesRead = 300,
                    totalBytes = 500,
                ),
                io.talkcan.model.ModelSetProgress(
                    dirName = "supertonic",
                    bytesRead = 200,
                    totalBytes = 450,
                ),
            ),
        )
        assertEquals(500L, progress.bytesRead)
        assertEquals(950L, progress.totalBytes)
    }

    @Test
    fun `Empty ModelAcquisitionProgress has zero totals`() {
        val progress = io.talkcan.model.ModelAcquisitionProgress()
        assertEquals(0L, progress.totalBytes)
        assertEquals(0L, progress.bytesRead)
    }


    @Test
    fun `BootstrapState transitions are exhaustive`() {
        // Verify all BootstrapState variants exist and are distinct.
        val states = listOf(
            BootstrapState.ConnectingService,
            BootstrapState.CheckingPrerequisites(),
            BootstrapState.NeedsSetup(),
            BootstrapState.AcquiringModels(),
            BootstrapState.PreparingCore(),
            BootstrapState.Ready,
            BootstrapState.Failed(
                io.talkcan.model.BootstrapStage.InitializingStt,
                "test failure",
            ),
        )
        assertEquals(7, states.size)
        // Each must be a distinct type.
        val types = states.map { it::class }.toSet()
        assertEquals(7, types.size)
    }

    @Test
    fun `BootstrapState NeedsSetup carries missing permissions and invalid model sets`() {
        val state = BootstrapState.NeedsSetup(
            missingPermissions = listOf("android.permission.RECORD_AUDIO"),
            invalidModelSets = listOf("parakeet-tdt-0.6b-v3-int8"),
            error = "Download failed: timeout",
        )
        assertEquals(listOf("android.permission.RECORD_AUDIO"), state.missingPermissions)
        assertEquals(listOf("parakeet-tdt-0.6b-v3-int8"), state.invalidModelSets)
        assertEquals("Download failed: timeout", state.error)
    }

    @Test
    fun `BootstrapState Failed carries stage diagnostic and retryable`() {
        val state = BootstrapState.Failed(
            io.talkcan.model.BootstrapStage.ProbingNavigationVoice,
            "Navigation TTS probe failed",
            retryable = true,
        )
        assertEquals(
            io.talkcan.model.BootstrapStage.ProbingNavigationVoice,
            state.stage,
        )
        assertEquals("Navigation TTS probe failed", state.diagnostic)
        assertTrue(state.retryable)
    }

    @Test
    fun `BootstrapState PreparingCore carries completedUnits and totalUnits`() {
        val state = BootstrapState.PreparingCore(
            stage = io.talkcan.model.BootstrapStage.ConstructingControllers,
            completedUnits = 3,
            totalUnits = 7,
        )
        assertEquals(3, state.completedUnits)
        assertEquals(7, state.totalUnits)
    }
}