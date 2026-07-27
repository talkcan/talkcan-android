package io.talkcan.ui

import io.talkcan.model.BootstrapStage
import io.talkcan.model.BootstrapState
import io.talkcan.model.ModelAcquisitionProgress
import io.talkcan.model.ModelSetProgress
import io.talkcan.model.OfflineNavigationVoiceIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic tests for BootstrapState → RootSurface and progress-label projections.
 * No Compose runtime required.
 */
class BootstrapStateProjectionTest {

    // ============================================================
    // Root surface mapping tests
    // ============================================================

    @Test
    fun `ConnectingService maps to Loading`() {
        val state = BootstrapState.ConnectingService
        assertEquals(BootstrapRootSurface.Loading, bootstrapRootSurface(state))
    }

    @Test
    fun `CheckingPrerequisites maps to Loading`() {
        val state = BootstrapState.CheckingPrerequisites(BootstrapStage.CheckingPermissions)
        assertEquals(BootstrapRootSurface.Loading, bootstrapRootSurface(state))
    }

    @Test
    fun `CheckingPrerequisites CheckingModels maps to Loading`() {
        val state = BootstrapState.CheckingPrerequisites(BootstrapStage.CheckingModels)
        assertEquals(BootstrapRootSurface.Loading, bootstrapRootSurface(state))
    }

    @Test
    fun `ProbingNavigationVoice remains a loading stage with no fabricated progress`() {
        val state = BootstrapState.CheckingPrerequisites(BootstrapStage.ProbingNavigationVoice)

        assertEquals(BootstrapRootSurface.Loading, bootstrapRootSurface(state))
        assertEquals("Probing offline navigation voice", bootstrapStageText(state))
        assertNull(bootstrapProgressDetail(state))
    }

    @Test
    fun `AcquiringModels maps to Loading`() {
        val state = BootstrapState.AcquiringModels(ModelAcquisitionProgress())
        assertEquals(BootstrapRootSurface.Loading, bootstrapRootSurface(state))
    }

    @Test
    fun `PreparingCore InitializingStt maps to Loading`() {
        val state = BootstrapState.PreparingCore(BootstrapStage.InitializingStt)
        assertEquals(BootstrapRootSurface.Loading, bootstrapRootSurface(state))
    }


    @Test
    fun `Failed maps to Loading for recovery surface`() {
        val state = BootstrapState.Failed(
            stage = BootstrapStage.CheckingModels,
            diagnostic = "Model hash mismatch",
            retryable = true,
        )
        assertEquals(BootstrapRootSurface.Loading, bootstrapRootSurface(state))
    }

    @Test
    fun `NeedsSetup maps to Setup`() {
        val state = BootstrapState.NeedsSetup(
            missingPermissions = listOf("android.permission.RECORD_AUDIO"),
            invalidModelSets = listOf("stt-model"),
            error = null,
        )
        assertEquals(BootstrapRootSurface.Setup, bootstrapRootSurface(state))
    }

    @Test
    fun `NeedsSetup with missing all-files access maps to Setup`() {
        val state = BootstrapState.NeedsSetup(needsManageExternalStorage = true)

        assertEquals(BootstrapRootSurface.Setup, bootstrapRootSurface(state))
    }

    @Test
    fun `NeedsSetup with error still maps to Setup`() {
        val state = BootstrapState.NeedsSetup(
            missingPermissions = emptyList(),
            invalidModelSets = listOf("tts-model"),
            error = "Download interrupted",
        )
        assertEquals(BootstrapRootSurface.Setup, bootstrapRootSurface(state))
    }

    @Test
    fun `NeedsSetup with offline navigation voice issue routes to setup`() {
        val state = BootstrapState.NeedsSetup(
            offlineNavigationVoiceIssue = OfflineNavigationVoiceIssue(
                diagnostic = "No installed offline English voice",
                enginePackage = "com.example.tts",
            ),
        )

        assertEquals(BootstrapRootSurface.Setup, bootstrapRootSurface(state))
        assertEquals("Setup required", bootstrapStageText(state))
    }

    @Test
    fun `Ready maps to Dashboard for automatic cutover`() {
        val state = BootstrapState.Ready
        assertEquals(BootstrapRootSurface.Dashboard, bootstrapRootSurface(state))
    }

    // ============================================================
    // Progress label tests — model acquisition bytes
    // ============================================================

    @Test
    fun `AcquiringModels with totalBytes produces byte progress`() {
        val progress = ModelAcquisitionProgress(
            sets = listOf(
                ModelSetProgress(
                    dirName = "stt-model",
                    bytesRead = 50L * 1024 * 1024,  // 50 MB
                    totalBytes = 100L * 1024 * 1024, // 100 MB
                ),
            ),
        )
        val state = BootstrapState.AcquiringModels(progress)

        val detail = bootstrapProgressDetail(state, progress)
        assertEquals("50.0 MB / 100.0 MB (50%)", detail)
    }

    @Test
    fun `AcquiringModels with zero totalBytes produces null`() {
        val progress = ModelAcquisitionProgress()
        val state = BootstrapState.AcquiringModels(progress)

        assertNull(bootstrapProgressDetail(state, progress))
    }

    @Test
    fun `AcquiringModels with KB range formats as KB`() {
        val progress = ModelAcquisitionProgress(
            sets = listOf(
                ModelSetProgress(
                    dirName = "small-model",
                    bytesRead = 512L * 1024,  // 512 KB
                    totalBytes = 1024L * 1024, // 1 MB
                ),
            ),
        )
        val state = BootstrapState.AcquiringModels(progress)

        val detail = bootstrapProgressDetail(state, progress)
        assertEquals("512 KB / 1.0 MB (50%)", detail)
    }

    @Test
    fun `AcquiringModels multi-set aggregates bytes`() {
        val progress = ModelAcquisitionProgress(
            sets = listOf(
                ModelSetProgress(
                    dirName = "stt-model",
                    bytesRead = 30L * 1024 * 1024,
                    totalBytes = 60L * 1024 * 1024,
                ),
                ModelSetProgress(
                    dirName = "tts-model",
                    bytesRead = 20L * 1024 * 1024,
                    totalBytes = 40L * 1024 * 1024,
                ),
            ),
        )
        val state = BootstrapState.AcquiringModels(progress)

        val detail = bootstrapProgressDetail(state, progress)
        assertEquals("50.0 MB / 100.0 MB (50%)", detail)
    }


    @Test
    fun `PreparingCore other stage produces null even with totalUnits`() {
        val state = BootstrapState.PreparingCore(
            stage = BootstrapStage.InitializingStt,
            completedUnits = 1,
            totalUnits = 5,
        )

        assertNull(bootstrapProgressDetail(state))
    }

    // ============================================================
    // Stage text tests — no manual entry acknowledgement
    // ============================================================

    @Test
    fun `NeedsSetup stage text is Setup required without Enter Talkcan`() {
        val state = BootstrapState.NeedsSetup()
        val text = bootstrapStageText(state)
        assertEquals("Setup required", text)
    }

    @Test
    fun `Ready stage text is Ready`() {
        val state = BootstrapState.Ready
        val text = bootstrapStageText(state)
        assertEquals("Ready", text)
    }

    @Test
    fun `ConnectingService stage text`() {
        val state = BootstrapState.ConnectingService
        val text = bootstrapStageText(state)
        assertEquals("Connecting to service", text)
    }

    @Test
    fun `CheckingPrerequisites CheckingPermissions stage text`() {
        val state = BootstrapState.CheckingPrerequisites(BootstrapStage.CheckingPermissions)
        val text = bootstrapStageText(state)
        assertEquals("Checking permissions", text)
    }

    @Test
    fun `CheckingPrerequisites CheckingModels stage text`() {
        val state = BootstrapState.CheckingPrerequisites(BootstrapStage.CheckingModels)
        val text = bootstrapStageText(state)
        assertEquals("Verifying model assets", text)
    }

    @Test
    fun `AcquiringModels stage text`() {
        val state = BootstrapState.AcquiringModels()
        val text = bootstrapStageText(state)
        assertEquals("Downloading speech packages", text)
    }

    @Test
    fun `PreparingCore InitializingStt stage text`() {
        val state = BootstrapState.PreparingCore(BootstrapStage.InitializingStt)
        val text = bootstrapStageText(state)
        assertEquals("Initializing speech-to-text engine", text)
    }


    @Test
    fun `Failed stage text includes failed stage name`() {
        val state = BootstrapState.Failed(
            stage = BootstrapStage.CheckingModels,
            diagnostic = "Model hash mismatch",
            retryable = true,
        )
        val text = bootstrapStageText(state)
        assertEquals("Failed: CheckingModels", text)
    }

    // ============================================================
    // formatBytes helper tests
    // ============================================================

    @Test
    fun `formatBytes less than 1 MB formats as KB`() {
        assertEquals("512 KB", formatBytes(512L * 1024))
        assertEquals("0 KB", formatBytes(0L))
    }

    @Test
    fun `formatBytes exactly 1 MB formats as MB`() {
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
    }

    @Test
    fun `formatBytes greater than 1 MB formats as MB with one decimal`() {
        assertEquals("50.0 MB", formatBytes(50L * 1024 * 1024))
        assertEquals("123.5 MB", formatBytes(123L * 1024 * 1024 + 512L * 1024))
    }
}