package io.talkcan.ui

import io.talkcan.voice.VoiceProfileAvailability
import io.talkcan.voice.VoiceProfileCatalogue
import io.talkcan.voice.VoiceProfileCompatibility
import io.talkcan.service.VoiceProfileDraftSummary
import io.talkcan.voice.VoiceProfileId
import io.talkcan.voice.VoiceProfileKind
import io.talkcan.voice.VoiceProfileLimits
import io.talkcan.voice.VoiceProfileSummary
import io.talkcan.voice.VoiceTensor
import java.util.Locale
import kotlin.math.abs

/**
 * Categorized view of the unified voice-profile catalogue.
 *
 * Retains one selectable list while grouping visibly by built-in, edited/custom,
 * mixed, imported, unverified compatibility, and unavailable profiles.
 */
internal data class VoiceProfileCatalogueGroups(
    val builtIn: List<VoiceProfileSummary>,
    val edited: List<VoiceProfileSummary>,
    val mixed: List<VoiceProfileSummary>,
    val imported: List<VoiceProfileSummary>,
    val unverified: List<VoiceProfileSummary>,
    val unavailable: List<VoiceProfileSummary>,
) {
    /** Returns non-empty section headers and their profiles in canonical visual order. */
    val visibleSections: List<Pair<String, List<VoiceProfileSummary>>>
        get() = buildList {
            if (builtIn.isNotEmpty()) add("Built-in Profiles" to builtIn)
            if (edited.isNotEmpty()) add("Edited / Custom Profiles" to edited)
            if (mixed.isNotEmpty()) add("Mixed Profiles" to mixed)
            if (imported.isNotEmpty()) add("Imported Profiles" to imported)
            if (unverified.isNotEmpty()) add("Unverified Compatibility" to unverified)
            if (unavailable.isNotEmpty()) add("Unavailable Profiles" to unavailable)
        }
}

internal object VoiceProfileManagementUiHelpers {

    fun groupCatalogue(catalogue: VoiceProfileCatalogue): VoiceProfileCatalogueGroups {
        val builtIn = mutableListOf<VoiceProfileSummary>()
        val edited = mutableListOf<VoiceProfileSummary>()
        val mixed = mutableListOf<VoiceProfileSummary>()
        val imported = mutableListOf<VoiceProfileSummary>()
        val unverified = mutableListOf<VoiceProfileSummary>()
        val unavailable = mutableListOf<VoiceProfileSummary>()

        for (summary in catalogue.all) {
            val isUnavailable = summary.availability is VoiceProfileAvailability.Unavailable ||
                summary.compatibility == VoiceProfileCompatibility.INCOMPATIBLE
            if (isUnavailable) {
                unavailable.add(summary)
                continue
            }
            if (summary.compatibility == VoiceProfileCompatibility.UNVERIFIED) {
                unverified.add(summary)
                continue
            }
            when (summary.kind) {
                VoiceProfileKind.BUILT_IN -> builtIn.add(summary)
                VoiceProfileKind.EDITED -> edited.add(summary)
                VoiceProfileKind.MIXED -> mixed.add(summary)
                VoiceProfileKind.IMPORTED -> imported.add(summary)
            }
        }
        return VoiceProfileCatalogueGroups(
            builtIn = builtIn,
            edited = edited,
            mixed = mixed,
            imported = imported,
            unverified = unverified,
            unavailable = unavailable,
        )
    }

    /**
     * Checks whether an individual profile is selectable as a mix source.
     * Unavailable or incompatible profiles are rejected.
     */
    fun isProfileSelectable(summary: VoiceProfileSummary): Boolean = summary.selectable

    /**
     * Checks whether a source checkbox can be deselected without violating the two-source minimum.
     */
    fun canDeselectSource(
        summaryId: VoiceProfileId,
        selectedIds: List<VoiceProfileId>,
    ): Boolean {
        if (!selectedIds.contains(summaryId)) return true
        return selectedIds.size > VoiceProfileLimits.MIN_MIX_SOURCES
    }

    /**
     * Validates whether a candidate list of source IDs meets the 2..16 bounds and distinctness.
     */
    fun isValidSourceSelection(ids: List<VoiceProfileId>): Boolean {
        if (ids.size !in VoiceProfileLimits.MIN_MIX_SOURCES..VoiceProfileLimits.MAX_MIX_SOURCES) {
            return false
        }
        return ids.toSet().size == ids.size
    }

    /**
     * Determines whether changing selection or weights requires explicit confirmation because
     * latent edits have been applied on top of the current baseline.
     */
    fun requiresResetConfirmation(
        draft: VoiceProfileDraftSummary?,
        isChangingSources: Boolean = false,
        isChangingWeights: Boolean = false,
    ): Boolean {
        if (draft == null || !draft.hasEdits) return false
        return isChangingSources || isChangingWeights
    }

    /**
     * Normalizes a list of positive finite raw weights so they sum to 1.0.
     */
    fun normalizeWeights(rawWeights: List<Double>): List<Double> {
        if (rawWeights.isEmpty() || !rawWeights.all { it.isFinite() && it > 0.0 }) {
            return emptyList()
        }
        val sum = rawWeights.sum()
        return rawWeights.map { it / sum }
    }

    /**
     * Formats a normalized weight (0.0..1.0) as a percentage string (e.g. "25.0%").
     */
    fun formatNormalizedPercentage(weight: Double): String {
        return String.format(Locale.US, "%.1f%%", (weight * 100.0).coerceIn(0.0, 100.0))
    }

    /**
     * Generates an accessibility content description for a profile card in the catalogue list.
     */
    fun profileAccessibilityDescription(summary: VoiceProfileSummary): String {
        val kindLabel = when (summary.kind) {
            VoiceProfileKind.BUILT_IN -> "built-in voice profile"
            VoiceProfileKind.EDITED -> "edited voice profile"
            VoiceProfileKind.MIXED -> "mixed voice profile"
            VoiceProfileKind.IMPORTED -> "imported voice profile"
        }
        val availLabel = when (summary.availability) {
            is VoiceProfileAvailability.Available -> "available"
            is VoiceProfileAvailability.Unavailable -> "unavailable"
        }
        val compatLabel = when (summary.compatibility) {
            VoiceProfileCompatibility.VERIFIED -> "verified"
            VoiceProfileCompatibility.UNVERIFIED -> "unverified compatibility"
            VoiceProfileCompatibility.INCOMPATIBLE -> "incompatible"
        }
        val accessLabel = if (summary.readOnly) "read-only" else "editable"
        return "Profile ${summary.displayName}, $kindLabel, $availLabel, $compatLabel, $accessLabel"
    }

    /**
     * Generates an accessibility content description for the 256x50 TTL heatmap.
     */
    fun ttlHeatmapAccessibilityDescription(ttl: VoiceTensor): String {
        val (min, max) = heatmapMinMax(ttl)
        return String.format(
            Locale.US,
            "TTL heatmap 256x50, min %.3f, max %.3f",
            min,
            max,
        )
    }

    /**
     * Generates an accessibility content description for a TTL operation control.
     */
    fun operationControlAccessibilityDescription(operationName: String, parameterLabel: String? = null): String {
        return if (parameterLabel != null) {
            "$operationName $parameterLabel control"
        } else {
            "$operationName control button"
        }
    }

    /**
     * Computes the finite minimum and maximum values in a TTL tensor.
     */
    fun heatmapMinMax(ttl: VoiceTensor): Pair<Float, Float> {
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        ttl.forEachIndexed { _, value ->
            if (value.isFinite()) {
                if (value < min) min = value
                if (value > max) max = value
            }
        }
        if (min == Float.POSITIVE_INFINITY || max == Float.NEGATIVE_INFINITY) {
            return 0f to 0f
        }
        return min to max
    }

    /**
     * Generates a 256x50 ARGB pixel array from a TTL tensor using a high-contrast thermal colormap.
     * The array is ordered row-major: pixel[y * 256 + x] corresponds to TTL tensor index[row=y, col=x].
     */
    fun generateHeatmapPixels(ttl: VoiceTensor): IntArray {
        val width = 256
        val height = 50
        val pixels = IntArray(width * height)
        val (min, max) = heatmapMinMax(ttl)
        val range = if (abs(max - min) < 1e-6f) 1f else max - min

        // High contrast instrumentation colormap:
        // 0.0 -> Dark Navy (#0B0E14)
        // 0.25 -> Deep Blue (#1A365D)
        // 0.50 -> Emerald Teal (#2C7A7B)
        // 0.75 -> Amber (#D69E2E)
        // 1.0 -> Cream White (#F7FAFC)
        val colors = intArrayOf(
            0xFF0B0E14.toInt(),
            0xFF1A365D.toInt(),
            0xFF2C7A7B.toInt(),
            0xFFD69E2E.toInt(),
            0xFFF7FAFC.toInt(),
        )

        for (row in 0 until height) {
            for (col in 0 until width) {
                val tensorIndex = row * width + col
                val value = ttl[tensorIndex]
                val norm = ((value - min) / range).coerceIn(0f, 1f)
                pixels[row * width + col] = interpolateColor(colors, norm)
            }
        }
        return pixels
    }

    private fun interpolateColor(colors: IntArray, norm: Float): Int {
        val scaled = norm * (colors.size - 1)
        val index = scaled.toInt().coerceIn(0, colors.size - 2)
        val fraction = scaled - index
        val c1 = colors[index]
        val c2 = colors[index + 1]

        val a1 = (c1 shr 24) and 0xFF
        val r1 = (c1 shr 16) and 0xFF
        val g1 = (c1 shr 8) and 0xFF
        val b1 = c1 and 0xFF

        val a2 = (c2 shr 24) and 0xFF
        val r2 = (c2 shr 16) and 0xFF
        val g2 = (c2 shr 8) and 0xFF
        val b2 = c2 and 0xFF

        val a = (a1 + (a2 - a1) * fraction).toInt().coerceIn(0, 255)
        val r = (r1 + (r2 - r1) * fraction).toInt().coerceIn(0, 255)
        val g = (g1 + (g2 - g1) * fraction).toInt().coerceIn(0, 255)
        val b = (b1 + (b2 - b1) * fraction).toInt().coerceIn(0, 255)

        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
