package io.talkcan.service

import io.talkcan.audio.AudioRouteEndpoint
import io.talkcan.audio.FakeTtsSynthesizer
import io.talkcan.audio.RecordedPcm
import io.talkcan.audio.SynthesisOutcome
import io.talkcan.audio.TtsController
import io.talkcan.model.InputMode
import io.talkcan.model.TtsStatus
import io.talkcan.voice.Supertonic3VoiceProfileContract
import io.talkcan.voice.VoiceProfileChannelDependency
import io.talkcan.voice.VoiceProfileCodec
import io.talkcan.voice.VoiceProfileCompatibility
import io.talkcan.voice.VoiceProfileFailure
import io.talkcan.voice.VoiceProfileId
import io.talkcan.voice.VoiceProfileKind
import io.talkcan.voice.VoiceProfileModelMetadata
import io.talkcan.voice.VoiceProfileMutation
import io.talkcan.voice.VoiceProfileProvenance
import io.talkcan.voice.VoiceProfileRepository
import io.talkcan.voice.VoiceProfileSourceProvenance
import io.talkcan.voice.VoiceProfileStore
import io.talkcan.voice.VoiceProfileTensors
import io.talkcan.voice.VoiceProfileTtlOperation
import io.talkcan.voice.VoiceProfileWeightMode
import io.talkcan.voice.VoiceTensor
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Coordinator-contract tests: immutable state transitions, invalid-intent atomicity, atomic
 * preview-cache serialization and reuse, lifecycle cancellation, current-mode visible route
 * target publication, and the explicit operational-preemption boundary.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceProfileEditorCoordinatorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var builtInDir: File
    private lateinit var profilesDir: File
    private lateinit var cacheDir: File
    private var idCounter = 0

    @Before
    fun setUp() {
        idCounter = 0
        builtInDir = folder.newFolder("builtins")
        profilesDir = folder.newFolder("profiles")
        cacheDir = folder.newFolder("cache")
        for ((token, value) in BUILT_IN_TTL_VALUES) {
            File(builtInDir, "$token.json").writeText(
                VoiceProfileCodec.encode(tensors(value), MODEL, null)
            )
        }
    }

    // ── State transitions ────────────────────────────────────────────────────

    @Test
    fun selectingTwoBuiltInsMaterializesEqualWeightBaseline() = runTest {
        val harness = harness()

        harness.coordinator.selectSources(listOf(F1, F2))

        val state = harness.coordinator.state.value
        assertEquals(listOf(F1, F2), state.selectedSourceIds)
        assertEquals(VoiceProfileWeightMode.EQUAL, state.weightMode)
        assertNull(state.randomSeed)
        assertEquals(listOf(1.0, 1.0), state.rawWeights)
        assertEquals(listOf(0.5, 0.5), state.normalizedWeights)
        assertNull(state.failure)
        val draft = requireDraft(state)
        assertFalse(draft.hasEdits)
        assertFalse(draft.canUndo)
        assertEquals(0, draft.operationCount)
        // Equal mix of F1(0.2) and F2(0.8) materializes 0.5 in every TTL element.
        assertEquals(0.5, draft.ttl[0].toDouble(), 1e-6)
    }

    @Test
    fun manualWeightsRematerializeBaselineWithNormalizedValues() = runTest {
        val harness = harness()
        harness.coordinator.selectSources(listOf(F1, F2))

        harness.coordinator.setManualWeights(listOf(3.0, 1.0))

        val state = harness.coordinator.state.value
        assertEquals(VoiceProfileWeightMode.MANUAL, state.weightMode)
        assertEquals(listOf(3.0, 1.0), state.rawWeights)
        assertEquals(0.75, state.normalizedWeights[0], 1e-12)
        assertEquals(0.25, state.normalizedWeights[1], 1e-12)
        assertEquals(1.0, state.normalizedWeights.sum(), 1e-12)
        // 0.75 * 0.2 + 0.25 * 0.8 = 0.35
        assertEquals(0.35, requireDraft(state).ttl[0].toDouble(), 1e-6)
    }

    @Test
    fun seededRandomWeightsAreDeterministicAndRecordSeed() = runTest {
        val harness = harness()
        harness.coordinator.selectSources(listOf(F1, F2, M1))

        harness.coordinator.setRandomWeights(seed = 42L)
        val first = harness.coordinator.state.value

        // Re-randomizing with the same seed on a clean baseline reproduces everything.
        harness.coordinator.setRandomWeights(seed = 42L)
        val second = harness.coordinator.state.value

        assertEquals(VoiceProfileWeightMode.RANDOM, first.weightMode)
        assertEquals(42L, first.randomSeed)
        assertEquals(first.rawWeights, second.rawWeights)
        assertEquals(first.normalizedWeights, second.normalizedWeights)
        assertEquals(requireDraft(first).ttl, requireDraft(second).ttl)

        // A different seed changes the mixture.
        harness.coordinator.setRandomWeights(seed = 43L)
        assertTrue(harness.coordinator.state.value.normalizedWeights != first.normalizedWeights)
    }

    @Test
    fun operationUndoResetLifecycleTracksBounds() = runTest {
        val harness = harness()
        harness.coordinator.selectSources(listOf(F1, F2))
        val baselineTtl = requireDraft(harness.coordinator.state.value).ttl

        harness.coordinator.applyOperation(VoiceProfileTtlOperation.Invert)
        var draft = requireDraft(harness.coordinator.state.value)
        assertTrue(draft.hasEdits)
        assertTrue(draft.canUndo)
        assertEquals(1, draft.operationCount)
        assertEquals(1, draft.undoDepth)
        assertEquals(0, draft.baselineOperationCount)

        harness.coordinator.undo()
        draft = requireDraft(harness.coordinator.state.value)
        assertFalse(draft.hasEdits)
        assertFalse(draft.canUndo)
        assertEquals(0, draft.operationCount)
        assertEquals(baselineTtl, draft.ttl)

        harness.coordinator.applyOperation(VoiceProfileTtlOperation.TimeMirror)
        harness.coordinator.applyOperation(VoiceProfileTtlOperation.Invert)
        harness.coordinator.reset()
        draft = requireDraft(harness.coordinator.state.value)
        assertFalse(draft.hasEdits)
        assertFalse(draft.canUndo)
        assertEquals(0, draft.undoDepth)
        assertEquals(baselineTtl, draft.ttl)
    }

    @Test
    fun saveAsNewPublishesSelectableCustomProfile() = runTest {
        val harness = harness()
        harness.coordinator.selectSources(listOf(F1, F2))
        harness.coordinator.applyOperation(VoiceProfileTtlOperation.Invert)

        assertTrue(harness.coordinator.saveDraftAsNew("My Voice"))

        val custom = harness.repository.currentCatalogue.custom
        assertEquals(1, custom.size)
        assertEquals("My Voice", custom.single().displayName)
        assertEquals(VoiceProfileKind.EDITED, custom.single().kind)
        assertEquals(VoiceProfileCompatibility.VERIFIED, custom.single().compatibility)
        assertTrue(custom.single().selectable)
        assertNull(harness.coordinator.state.value.failure)
    }

    // ── Invalid-intent atomicity ─────────────────────────────────────────────

    @Test
    fun selectingFirstSourceStagesSelectionWithoutMaterializingMix() = runTest {
        val harness = harness()

        harness.coordinator.selectSources(listOf(F1))

        assertNull(harness.coordinator.state.value.failure)
        assertEquals(listOf(F1), harness.coordinator.state.value.selectedSourceIds)
        assertNull(harness.coordinator.state.value.draft)
    }

    @Test
    fun selectingUnavailableProfileFailsAndKeepsPriorSelection() = runTest {
        val harness = harness()
        harness.coordinator.selectSources(listOf(F1, F2))
        val saved = harness.repository.saveAsNew(
            tensors(0.9f),
            VoiceProfileProvenance(
                sources = listOf(VoiceProfileSourceProvenance(F1, "F1", 1.0)),
                weightMode = VoiceProfileWeightMode.MANUAL,
            ),
            "Doomed",
        ) as VoiceProfileMutation.Saved
        // Corrupt the committed document and reconcile so it publishes unavailable.
        File(profilesDir, "${saved.summary.id.value}.json").writeText("{corrupt")
        harness.repository.reload()

        harness.coordinator.selectSources(listOf(F1, saved.summary.id))

        val failure = harness.coordinator.state.value.failure
        assertTrue(failure is VoiceProfileEditorFailure.UnavailableSource)
        assertEquals(saved.summary.id, (failure as VoiceProfileEditorFailure.UnavailableSource).profileId)
        // The prior selection and draft are untouched.
        assertEquals(listOf(F1, F2), harness.coordinator.state.value.selectedSourceIds)
        requireDraft(harness.coordinator.state.value)
    }

    @Test
    fun changingSourcesAfterLatentEditsRequiresExplicitReset() = runTest {
        val harness = harness()
        harness.coordinator.selectSources(listOf(F1, F2))
        harness.coordinator.applyOperation(VoiceProfileTtlOperation.Invert)
        val edited = requireDraft(harness.coordinator.state.value)

        harness.coordinator.selectSources(listOf(M1, M2))

        assertTrue(
            harness.coordinator.state.value.failure is VoiceProfileEditorFailure.LatentEditsRequireReset
        )
        // Draft and selection are byte-identical to the pre-intent state.
        assertEquals(listOf(F1, F2), harness.coordinator.state.value.selectedSourceIds)
        assertEquals(edited.ttl, requireDraft(harness.coordinator.state.value).ttl)
        assertEquals(1, requireDraft(harness.coordinator.state.value).operationCount)

        // Explicit discard succeeds and installs the new equal-weight baseline.
        harness.coordinator.selectSources(listOf(M1, M2), discardLatentEdits = true)
        val state = harness.coordinator.state.value
        assertNull(state.failure)
        assertEquals(listOf(M1, M2), state.selectedSourceIds)
        assertFalse(requireDraft(state).hasEdits)
        // Equal mix of M1(0.3) and M2(0.9) materializes 0.6 in every TTL element.
        assertEquals(0.6, requireDraft(state).ttl[0].toDouble(), 1e-6)
    }

    @Test
    fun invalidWeightsFailAtomically() = runTest {
        val harness = harness()
        harness.coordinator.selectSources(listOf(F1, F2))
        val before = harness.coordinator.state.value

        harness.coordinator.setManualWeights(listOf(1.0))

        assertTrue(harness.coordinator.state.value.failure is VoiceProfileEditorFailure.InvalidWeights)
        assertEquals(before.rawWeights, harness.coordinator.state.value.rawWeights)
        assertEquals(before.normalizedWeights, harness.coordinator.state.value.normalizedWeights)
        assertEquals(requireDraft(before).ttl, requireDraft(harness.coordinator.state.value).ttl)

        harness.coordinator.setManualWeights(listOf(1.0, 0.0))

        assertTrue(harness.coordinator.state.value.failure is VoiceProfileEditorFailure.InvalidWeights)
        assertEquals(before.normalizedWeights, harness.coordinator.state.value.normalizedWeights)
    }

    @Test
    fun weightChangeAfterLatentEditsRequiresReset() = runTest {
        val harness = harness()
        harness.coordinator.selectSources(listOf(F1, F2))
        harness.coordinator.applyOperation(VoiceProfileTtlOperation.Invert)
        val editedTtl = requireDraft(harness.coordinator.state.value).ttl

        harness.coordinator.setEqualWeights()

        assertTrue(
            harness.coordinator.state.value.failure is VoiceProfileEditorFailure.LatentEditsRequireReset
        )
        assertEquals(editedTtl, requireDraft(harness.coordinator.state.value).ttl)
    }

    @Test
    fun operationUndoResetWithoutDraftFailAtomically() = runTest {
        val harness = harness()

        harness.coordinator.applyOperation(VoiceProfileTtlOperation.Invert)
        assertTrue(harness.coordinator.state.value.failure is VoiceProfileEditorFailure.NoMaterializedDraft)

        harness.coordinator.undo()
        assertTrue(harness.coordinator.state.value.failure is VoiceProfileEditorFailure.NoMaterializedDraft)

        harness.coordinator.reset()
        assertTrue(harness.coordinator.state.value.failure is VoiceProfileEditorFailure.NoMaterializedDraft)
        assertNull(harness.coordinator.state.value.draft)
    }

    @Test
    fun savingDuplicateNameReportsTypedRepositoryFailure() = runTest {
        val harness = harness()
        harness.coordinator.selectSources(listOf(F1, F2))
        assertTrue(harness.coordinator.saveDraftAsNew("Duplicated"))

        assertFalse(harness.coordinator.saveDraftAsNew("Duplicated"))

        val failure = harness.coordinator.state.value.failure
        assertTrue(failure is VoiceProfileEditorFailure.RepositoryMutation)
        assertTrue(
            (failure as VoiceProfileEditorFailure.RepositoryMutation).failure is VoiceProfileFailure.DuplicateName
        )
    }

    @Test
    fun deletingAssignedProfileIsRefusedWithDependentChannels() = runTest {
        val assigned = mutableMapOf<VoiceProfileId, List<VoiceProfileChannelDependency>>()
        val harness = harness(assignedChannels = { assigned[it] ?: emptyList() })
        harness.coordinator.selectSources(listOf(F1, F2))
        assertTrue(harness.coordinator.saveDraftAsNew("Assigned"))
        val id = harness.repository.currentCatalogue.custom.single().id
        assigned[id] = listOf(VoiceProfileChannelDependency("channel-7", "Nav Channel"))

        assertFalse(harness.coordinator.deleteProfile(id))

        val failure = harness.coordinator.state.value.failure
        assertTrue(failure is VoiceProfileEditorFailure.DeletionRefusedAssigned)
        val refused = failure as VoiceProfileEditorFailure.DeletionRefusedAssigned
        assertEquals(id, refused.profileId)
        assertEquals(
            listOf(VoiceProfileChannelDependency("channel-7", "Nav Channel")),
            refused.dependentChannels,
        )
        assertTrue(refused.diagnostic.contains("Nav Channel"))
        assertEquals(1, harness.repository.currentCatalogue.custom.size)
    }

    // ── Preview cache ────────────────────────────────────────────────────────

    @Test
    fun previewSerializesChangedDraftAtomicallyAndReusesUnchangedCache() = runTest {
        val harness = harness()
        harness.coordinator.selectSources(listOf(F1, F2))
        val cacheFile = harness.cacheFile

        harness.coordinator.requestPreview("preview one")
        advanceUntilIdle()

        assertTrue(cacheFile.isFile)
        val decoded = VoiceProfileCodec.decode(cacheFile.readText())
        assertEquals(requireDraft(harness.coordinator.state.value).ttl, decoded.tensors.ttl)
        assertEquals(MODEL, decoded.model)
        // Atomic write leaves no temp artifact behind.
        assertTrue(cacheDir.listFiles()!!.none { it.name.endsWith(".tmp") })

        // An unchanged draft reuses the cache: a sentinel written over the file survives.
        cacheFile.writeText("SENTINEL")
        harness.coordinator.requestPreview("preview two")
        advanceUntilIdle()
        assertEquals("SENTINEL", cacheFile.readText())

        // A changed draft forces one atomic rewrite with the new tensors.
        harness.coordinator.applyOperation(VoiceProfileTtlOperation.Invert)
        harness.coordinator.requestPreview("preview three")
        advanceUntilIdle()
        val rewritten = cacheFile.readText()
        assertTrue(rewritten != "SENTINEL")
        assertEquals(
            requireDraft(harness.coordinator.state.value).ttl,
            VoiceProfileCodec.decode(rewritten).tensors.ttl,
        )
        assertTrue(cacheDir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun previewPublishesCurrentModeTargetAndAcquiredEndpoint() = runTest {
        var mode = InputMode.OnAPinch
        var endpoint = AudioRouteEndpoint.Local
        val harness = harness(
            inputMode = { mode },
            playback = { _, onRouteAcquired ->
                onRouteAcquired(endpoint)
                true
            },
        )
        harness.coordinator.selectSources(listOf(F1, F2))

        harness.coordinator.requestPreview("hello")
        advanceUntilIdle()

        var preview = harness.coordinator.state.value.preview
        assertEquals(VoiceProfilePreviewPhase.IDLE, preview.phase)
        assertEquals("hello", preview.text)
        assertEquals(InputMode.OnAPinch, preview.targetMode)
        assertEquals(AudioRouteEndpoint.Local, preview.targetEndpoint)

        // The visible target follows the current input mode on the next preview.
        mode = InputMode.Work
        endpoint = AudioRouteEndpoint.Rsm
        harness.coordinator.requestPreview("hello again")
        advanceUntilIdle()

        preview = harness.coordinator.state.value.preview
        assertEquals(VoiceProfilePreviewPhase.IDLE, preview.phase)
        assertEquals(InputMode.Work, preview.targetMode)
        assertEquals(AudioRouteEndpoint.Rsm, preview.targetEndpoint)
    }

    @Test
    fun previewSynthesisFailureSurfacesErrorPhaseWithDiagnostic() = runTest {
        val harness = harness(
            synthesizer = FakeTtsSynthesizer().apply {
                setOutcome(SynthesisOutcome.Failure("model unavailable"))
            },
        )
        harness.coordinator.selectSources(listOf(F1, F2))

        harness.coordinator.requestPreview("doomed")
        advanceUntilIdle()

        val preview = harness.coordinator.state.value.preview
        assertEquals(VoiceProfilePreviewPhase.ERROR, preview.phase)
        assertEquals("model unavailable", preview.diagnostic)
    }

    @Test
    fun previewWithoutDraftFailsAtomically() = runTest {
        val harness = harness()

        harness.coordinator.requestPreview("nothing to preview")
        advanceUntilIdle()

        assertTrue(harness.coordinator.state.value.failure is VoiceProfileEditorFailure.NoMaterializedDraft)
        assertEquals(VoiceProfilePreviewPhase.IDLE, harness.coordinator.state.value.preview.phase)
        assertFalse(harness.cacheFile.exists())
    }

    @Test
    fun previewWithoutControllerReportsUnavailable() = runTest {
        val harness = harness(controllerSupplier = { null })
        harness.coordinator.selectSources(listOf(F1, F2))

        harness.coordinator.requestPreview("no controller")
        advanceUntilIdle()

        assertTrue(harness.coordinator.state.value.failure is VoiceProfileEditorFailure.PreviewUnavailable)
    }

    // ── Lifecycle cancellation ───────────────────────────────────────────────

    @Test
    fun exitEditorCancelsActivePreviewAndReleasesController() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val harness = harness(playback = { _, onRouteAcquired ->
            onRouteAcquired(AudioRouteEndpoint.Local)
            gate.await()
        })
        harness.coordinator.selectSources(listOf(F1, F2))
        harness.coordinator.requestPreview("held")
        advanceUntilIdle()
        assertEquals(VoiceProfilePreviewPhase.PLAYING, harness.coordinator.state.value.preview.phase)
        assertEquals(TtsStatus.Playing, harness.controller!!.status.value)

        harness.coordinator.exitEditor()

        assertEquals(VoiceProfilePreviewPhase.IDLE, harness.coordinator.state.value.preview.phase)
        assertEquals(TtsStatus.Idle, harness.controller!!.status.value)
        advanceUntilIdle()
    }

    @Test
    fun releaseDeletesCacheAndResetsPublishedState() = runTest {
        val harness = harness()
        harness.coordinator.selectSources(listOf(F1, F2))
        harness.coordinator.requestPreview("cache me")
        advanceUntilIdle()
        assertTrue(harness.cacheFile.isFile)

        harness.coordinator.release()

        assertFalse(harness.cacheFile.exists())
        assertEquals(VoiceProfileEditorState(), harness.coordinator.state.value)
    }

    // ── Operational preemption ───────────────────────────────────────────────

    @Test
    fun operationalPreemptionCancelsPreviewAndInvokesHostBoundary() = runTest {
        var hostPreemptions = 0
        val gate = CompletableDeferred<Boolean>()
        val harness = harness(
            playback = { _, onRouteAcquired ->
                onRouteAcquired(AudioRouteEndpoint.Rsm)
                gate.await()
            },
            preemptHostPlayback = { hostPreemptions += 1 },
        )
        harness.coordinator.selectSources(listOf(F1, F2))
        harness.coordinator.requestPreview("preview in progress")
        advanceUntilIdle()
        assertEquals(VoiceProfilePreviewPhase.PLAYING, harness.coordinator.state.value.preview.phase)

        harness.coordinator.preemptPreviewForOperationalAudio()

        // The editor preview state and shared controller status clear synchronously.
        assertEquals(1, hostPreemptions)
        assertEquals(VoiceProfilePreviewPhase.IDLE, harness.coordinator.state.value.preview.phase)
        assertEquals(TtsStatus.Idle, harness.controller!!.status.value)
        advanceUntilIdle()

        // With no active preview, the host boundary is still invoked (it is kind-scoped inside
        // HostAudioCoordinator and no-ops) and the editor state remains idle.
        harness.coordinator.preemptPreviewForOperationalAudio()
        assertEquals(2, hostPreemptions)
        assertEquals(VoiceProfilePreviewPhase.IDLE, harness.coordinator.state.value.preview.phase)
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private inner class Harness(
        val coordinator: VoiceProfileEditorCoordinator,
        val repository: VoiceProfileRepository,
        val controller: TtsController?,
        val cacheFile: File,
    )

    private fun TestScope.harness(
        synthesizer: FakeTtsSynthesizer = FakeTtsSynthesizer(),
        controllerSupplier: ((TtsController?) -> TtsController?)? = null,
        inputMode: () -> InputMode = { InputMode.OnAPinch },
        playback: suspend (RecordedPcm, (AudioRouteEndpoint) -> Unit) -> Boolean = { _, onRoute ->
            onRoute(AudioRouteEndpoint.Local)
            true
        },
        preemptHostPlayback: () -> Unit = {},
        assignedChannels: (VoiceProfileId) -> List<VoiceProfileChannelDependency> = { emptyList() },
    ): Harness {
        val dispatcher = coroutineContext[CoroutineDispatcher]!!
        val repository = VoiceProfileRepository(
            store = VoiceProfileStore(profilesDir),
            builtInDir = builtInDir,
            currentModel = MODEL,
            newId = { "profile-${idCounter++}" },
            assignedChannels = assignedChannels,
        )
        val cacheFile = File(cacheDir, "preview.json")
        val controller = TtsController(
            scope = this,
            synthesizer = synthesizer,
            play = { true },
            synthesisDispatcher = dispatcher,
        )
        val effectiveController: TtsController? =
            if (controllerSupplier != null) controllerSupplier.invoke(controller) else controller
        val coordinator = VoiceProfileEditorCoordinator(
            repository = repository,
            scope = this,
            previewCacheFile = cacheFile,
            ttsController = { effectiveController },
            currentInputMode = inputMode,
            previewPlayback = playback,
            previewDefaults = { VoiceProfilePreviewSynthesisDefaults("en", 8, 1.0f, 16_000) },
            preemptHostPlayback = preemptHostPlayback,
            ioDispatcher = dispatcher,
        )
        return Harness(coordinator, repository, effectiveController, cacheFile)
    }

    private fun tensors(ttl: Float): VoiceProfileTensors =
        VoiceProfileTensors(
            ttl = VoiceTensor.copyOf(
                Supertonic3VoiceProfileContract.TTL_DIMENSIONS,
                FloatArray(Supertonic3VoiceProfileContract.TTL_ELEMENT_COUNT) { ttl },
            ),
            dp = VoiceTensor.copyOf(
                Supertonic3VoiceProfileContract.DP_DIMENSIONS,
                FloatArray(Supertonic3VoiceProfileContract.DP_ELEMENT_COUNT) { ttl * 2f },
            ),
        )

    private fun requireDraft(state: VoiceProfileEditorState): VoiceProfileDraftSummary =
        checkNotNull(state.draft) { "Expected a materialized draft" }

    private companion object {
        val MODEL: VoiceProfileModelMetadata = VoiceProfileCodec.CURRENT_MODEL
        val F1 = VoiceProfileId("builtin:F1")
        val F2 = VoiceProfileId("builtin:F2")
        val M1 = VoiceProfileId("builtin:M1")
        val M2 = VoiceProfileId("builtin:M2")
        val BUILT_IN_TTL_VALUES = mapOf(
            "F1" to 0.2f, "F2" to 0.8f, "F3" to -0.4f, "F4" to 0.6f, "F5" to 0.1f,
            "M1" to 0.3f, "M2" to 0.9f, "M3" to -0.2f, "M4" to 0.4f, "M5" to 0.7f,
        )
    }
}
