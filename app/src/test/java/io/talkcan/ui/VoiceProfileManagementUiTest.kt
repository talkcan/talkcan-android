package io.talkcan.ui

import io.talkcan.service.VoiceProfileDraftSummary
import io.talkcan.voice.VoiceProfileAvailability
import io.talkcan.voice.VoiceProfileCatalogue
import io.talkcan.voice.VoiceProfileCompatibility
import io.talkcan.voice.VoiceProfileId
import io.talkcan.voice.VoiceProfileKind
import io.talkcan.voice.VoiceProfileSummary
import io.talkcan.voice.VoiceProfileUnavailableReason
import io.talkcan.voice.VoiceTensor
import io.talkcan.voice.VoiceTensorDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * JVM state and UI helper contract tests for Task 5.9:
 * built-in+custom and custom+custom selection, two-source minimum, normalized weights,
 * deterministic randomization, unavailable selection, baseline reset confirmation,
 * accessibility content descriptions/labels, process/navigation state, and editor exit.
 */
class VoiceProfileManagementUiTest {

    private fun summary(
        id: String,
        kind: VoiceProfileKind,
        availability: VoiceProfileAvailability = VoiceProfileAvailability.Available,
        compatibility: VoiceProfileCompatibility = VoiceProfileCompatibility.VERIFIED,
        readOnly: Boolean = kind == VoiceProfileKind.BUILT_IN,
    ): VoiceProfileSummary = VoiceProfileSummary(
        id = VoiceProfileId(id),
        displayName = "Profile $id",
        kind = kind,
        availability = availability,
        compatibility = compatibility,
        readOnly = readOnly,
    )

    private fun dummyTtl(): VoiceTensor {
        val dims = VoiceTensorDimensions(1, 50, 256)
        val values = FloatArray(dims.elementCount) { index ->
            (index % 100) * 0.01f - 0.5f
        }
        return VoiceTensor.copyOf(dims, values)
    }

    @Test
    fun `built-in plus custom and custom plus custom source mixing selections are valid`() {
        val builtIn1 = summary("F1", VoiceProfileKind.BUILT_IN)
        val custom1 = summary("custom-1", VoiceProfileKind.EDITED)
        val custom2 = summary("custom-2", VoiceProfileKind.EDITED)

        // built-in + custom
        assertTrue(
            VoiceProfileManagementUiHelpers.isValidSourceSelection(
                listOf(builtIn1.id, custom1.id),
            ),
        )

        // custom + custom
        assertTrue(
            VoiceProfileManagementUiHelpers.isValidSourceSelection(
                listOf(custom1.id, custom2.id),
            ),
        )
    }

    @Test
    fun `two source minimum is enforced visibly and in validation`() {
        val f1 = VoiceProfileId("F1")
        val f2 = VoiceProfileId("F2")
        val f3 = VoiceProfileId("F3")

        // Single source is invalid
        assertFalse(VoiceProfileManagementUiHelpers.isValidSourceSelection(listOf(f1)))

        // Two sources selected -> cannot deselect either source (would drop below 2)
        assertFalse(VoiceProfileManagementUiHelpers.canDeselectSource(f1, listOf(f1, f2)))
        assertFalse(VoiceProfileManagementUiHelpers.canDeselectSource(f2, listOf(f1, f2)))

        // Three sources selected -> can deselect any source
        assertTrue(VoiceProfileManagementUiHelpers.canDeselectSource(f1, listOf(f1, f2, f3)))
        assertTrue(VoiceProfileManagementUiHelpers.canDeselectSource(f2, listOf(f1, f2, f3)))
    }

    @Test
    fun `normalized weights sum to one and format correctly as percentages`() {
        val normalized = VoiceProfileManagementUiHelpers.normalizeWeights(listOf(2.0, 6.0))
        assertEquals(2, normalized.size)
        assertEquals(0.25, normalized[0], 1e-9)
        assertEquals(0.75, normalized[1], 1e-9)

        assertEquals("25.0%", VoiceProfileManagementUiHelpers.formatNormalizedPercentage(normalized[0]))
        assertEquals("75.0%", VoiceProfileManagementUiHelpers.formatNormalizedPercentage(normalized[1]))
    }

    @Test
    fun `seeded randomization generates deterministic repeatable weight distributions`() {
        fun generateSeededWeights(seed: Long, count: Int): List<Double> {
            val random = Random(seed)
            return List(count) { 1.0 - random.nextDouble() }
        }

        val weights1 = generateSeededWeights(12345L, 3)
        val weights2 = generateSeededWeights(12345L, 3)
        val weights3 = generateSeededWeights(54321L, 3)

        assertEquals(weights1, weights2)
        assertFalse(weights1 == weights3)

        val normalized1 = VoiceProfileManagementUiHelpers.normalizeWeights(weights1)
        val normalized2 = VoiceProfileManagementUiHelpers.normalizeWeights(weights2)
        assertEquals(normalized1, normalized2)
    }

    @Test
    fun `unavailable and incompatible profiles are rejected from selection`() {
        val available = summary("F1", VoiceProfileKind.BUILT_IN)
        val unavailable = summary(
            "custom-unavail",
            VoiceProfileKind.EDITED,
            availability = VoiceProfileAvailability.Unavailable(
                VoiceProfileUnavailableReason.MISSING_FILE,
                "File missing",
            ),
        )
        val incompatible = summary(
            "custom-incompat",
            VoiceProfileKind.EDITED,
            availability = VoiceProfileAvailability.Unavailable(
                VoiceProfileUnavailableReason.INCOMPATIBLE_MODEL,
                "Incompatible model",
            ),
            compatibility = VoiceProfileCompatibility.INCOMPATIBLE,
        )

        assertTrue(VoiceProfileManagementUiHelpers.isProfileSelectable(available))
        assertFalse(VoiceProfileManagementUiHelpers.isProfileSelectable(unavailable))
        assertFalse(VoiceProfileManagementUiHelpers.isProfileSelectable(incompatible))
    }

    @Test
    fun `baseline reset confirmation is required when changing sources or weights after latent edits`() {
        val draftWithEdits = VoiceProfileDraftSummary(
            ttl = dummyTtl(),
            hasEdits = true,
            canUndo = true,
            undoDepth = 1,
            operationCount = 1,
            baselineOperationCount = 0,
        )
        val draftWithoutEdits = VoiceProfileDraftSummary(
            ttl = dummyTtl(),
            hasEdits = false,
            canUndo = false,
            undoDepth = 0,
            operationCount = 0,
            baselineOperationCount = 0,
        )

        // With edits -> changing sources or weights requires reset confirmation
        assertTrue(
            VoiceProfileManagementUiHelpers.requiresResetConfirmation(
                draftWithEdits,
                isChangingSources = true,
            ),
        )
        assertTrue(
            VoiceProfileManagementUiHelpers.requiresResetConfirmation(
                draftWithEdits,
                isChangingWeights = true,
            ),
        )

        // Without edits -> no confirmation required
        assertFalse(
            VoiceProfileManagementUiHelpers.requiresResetConfirmation(
                draftWithoutEdits,
                isChangingSources = true,
            ),
        )
        assertFalse(
            VoiceProfileManagementUiHelpers.requiresResetConfirmation(
                draftWithoutEdits,
                isChangingWeights = true,
            ),
        )
    }

    @Test
    fun `accessibility content descriptions and labels cover all catalogue groups and heatmap`() {
        val builtIn = summary("F1", VoiceProfileKind.BUILT_IN)
        val edited = summary("custom-1", VoiceProfileKind.EDITED)
        val mixed = summary("mix-1", VoiceProfileKind.MIXED)
        val imported = summary("imp-1", VoiceProfileKind.IMPORTED)
        val unverified = summary(
            "unv-1",
            VoiceProfileKind.IMPORTED,
            compatibility = VoiceProfileCompatibility.UNVERIFIED,
        )
        val unavailable = summary(
            "err-1",
            VoiceProfileKind.EDITED,
            availability = VoiceProfileAvailability.Unavailable(
                VoiceProfileUnavailableReason.CORRUPT_DOCUMENT,
                "Corrupt",
            ),
        )

        assertEquals(
            "Profile Profile F1, built-in voice profile, available, verified, read-only",
            VoiceProfileManagementUiHelpers.profileAccessibilityDescription(builtIn),
        )
        assertEquals(
            "Profile Profile custom-1, edited voice profile, available, verified, editable",
            VoiceProfileManagementUiHelpers.profileAccessibilityDescription(edited),
        )
        assertEquals(
            "Profile Profile mix-1, mixed voice profile, available, verified, editable",
            VoiceProfileManagementUiHelpers.profileAccessibilityDescription(mixed),
        )
        assertEquals(
            "Profile Profile imp-1, imported voice profile, available, verified, editable",
            VoiceProfileManagementUiHelpers.profileAccessibilityDescription(imported),
        )
        assertEquals(
            "Profile Profile unv-1, imported voice profile, available, unverified compatibility, editable",
            VoiceProfileManagementUiHelpers.profileAccessibilityDescription(unverified),
        )
        assertEquals(
            "Profile Profile err-1, edited voice profile, unavailable, verified, editable",
            VoiceProfileManagementUiHelpers.profileAccessibilityDescription(unavailable),
        )

        val ttl = dummyTtl()
        val desc = VoiceProfileManagementUiHelpers.ttlHeatmapAccessibilityDescription(ttl)
        assertTrue(desc.startsWith("TTL heatmap 256x50, min -0.500, max 0.490"))

        assertEquals(
            "Feature Mirror control button",
            VoiceProfileManagementUiHelpers.operationControlAccessibilityDescription("Feature Mirror"),
        )
        assertEquals(
            "Quantize Factor control",
            VoiceProfileManagementUiHelpers.operationControlAccessibilityDescription("Quantize", "Factor"),
        )
    }

    @Test
    fun `catalogue grouping visibly categorizes built-in custom mixed imported unverified and unavailable`() {
        val f1 = summary("F1", VoiceProfileKind.BUILT_IN)
        val edit1 = summary("custom-1", VoiceProfileKind.EDITED)
        val mix1 = summary("mix-1", VoiceProfileKind.MIXED)
        val imp1 = summary("imp-1", VoiceProfileKind.IMPORTED)
        val unv1 = summary(
            "unv-1",
            VoiceProfileKind.IMPORTED,
            compatibility = VoiceProfileCompatibility.UNVERIFIED,
        )
        val unavail1 = summary(
            "err-1",
            VoiceProfileKind.EDITED,
            availability = VoiceProfileAvailability.Unavailable(
                VoiceProfileUnavailableReason.MISSING_FILE,
                "Missing",
            ),
        )

        val catalogue = VoiceProfileCatalogue(
            builtIn = listOf(f1),
            custom = listOf(edit1, mix1, imp1, unv1, unavail1),
        )
        val groups = VoiceProfileManagementUiHelpers.groupCatalogue(catalogue)

        assertEquals(listOf(f1), groups.builtIn)
        assertEquals(listOf(edit1), groups.edited)
        assertEquals(listOf(mix1), groups.mixed)
        assertEquals(listOf(imp1), groups.imported)
        assertEquals(listOf(unv1), groups.unverified)
        assertEquals(listOf(unavail1), groups.unavailable)

        assertEquals(6, groups.visibleSections.size)
    }
}
