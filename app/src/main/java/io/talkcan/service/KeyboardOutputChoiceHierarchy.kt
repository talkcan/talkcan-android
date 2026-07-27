package io.talkcan.service

import io.talkcan.model.DynamicConfigurationChoice
import io.talkcan.model.DynamicConfigurationChoiceRequest
import io.talkcan.model.DynamicConfigurationChoiceResolution
import io.talkcan.model.DynamicConfigurationChoiceUnavailableReason
import io.sleepwalker.core.keymap.HostProfile

/**
 * Deterministic bounded three-stage dynamic-choice hierarchy over the complete
 * keymap corpus: platforms (distinct host OS) -> layouts (`platform:layout`) ->
 * final profiles (exact [HostProfile.key]).
 *
 * Every stage publishes the complete set for its slice with stable sorted IDs
 * and unique nonblank labels, and never truncates: if a slice exceeds the
 * generic publication bound, the shared source registry rejects the whole
 * publication all-or-nothing. Missing, malformed, or unknown dependency scalars
 * resolve to a typed [DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING].
 *
 * The corpus is read once and cached; an empty corpus retains [HostProfile.LINUX_US]
 * so the default chain stays reachable.
 */
internal class KeyboardOutputChoiceHierarchy(profiles: () -> Collection<HostProfile>) {
    constructor(profiles: Collection<HostProfile>) : this({ profiles })

    private val corpus: List<HostProfile> by lazy {
        profiles().distinctBy { it.key }.ifEmpty { listOf(HostProfile.LINUX_US) }
    }

    /** Stage 1: distinct host operating systems; no dependency. */
    fun resolvePlatforms(): DynamicConfigurationChoiceResolution = available(
        corpus.asSequence()
            .map { it.hostOs.lowercase() }
            .distinct()
            .sorted()
            .map { DynamicConfigurationChoice(it, it) }
            .toList(),
    )

    /** Stage 2: layouts for the platform named by [DynamicConfigurationChoiceRequest.dependencyValue]. */
    fun resolveLayouts(request: DynamicConfigurationChoiceRequest): DynamicConfigurationChoiceResolution {
        val platform = request.dependencyValue?.takeIf(String::isNotBlank) ?: return dependencyMissing()
        val layouts = corpus.asSequence()
            .filter { it.hostOs.equals(platform, ignoreCase = true) }
            .map { it.layout.lowercase() }
            .distinct()
            .sorted()
            .toList()
        if (layouts.isEmpty()) return dependencyMissing()
        return available(uniqueLabels(layouts) { layout -> "${platform.lowercase()}:$layout" to layout })
    }

    /** Stage 3: exact profile keys for the `platform:layout` named by the dependency scalar. */
    fun resolveProfiles(request: DynamicConfigurationChoiceRequest): DynamicConfigurationChoiceResolution {
        val layoutId = request.dependencyValue?.takeIf(String::isNotBlank) ?: return dependencyMissing()
        val separator = layoutId.indexOf(':')
        if (separator <= 0 || separator == layoutId.lastIndex) return dependencyMissing()
        val platform = layoutId.substring(0, separator)
        val layout = layoutId.substring(separator + 1)
        val matches = corpus.asSequence()
            .filter {
                it.hostOs.equals(platform, ignoreCase = true) && it.layout.equals(layout, ignoreCase = true)
            }
            .sortedBy { it.key }
            .toList()
        if (matches.isEmpty()) return dependencyMissing()
        return available(
            uniqueLabels(matches) { profile -> profile.key to (profile.variant?.lowercase() ?: DEFAULT_LABEL) },
        )
    }

    private fun available(choices: List<DynamicConfigurationChoice>): DynamicConfigurationChoiceResolution =
        DynamicConfigurationChoiceResolution.Available(choices)

    private fun dependencyMissing(): DynamicConfigurationChoiceResolution =
        DynamicConfigurationChoiceResolution.Unavailable(
            DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING,
        )

    private companion object {
        const val DEFAULT_LABEL = "Default"

        /**
         * Deterministic label uniquification: candidate labels that collide within
         * a slice are qualified with their unique choice ID so every published
         * label is distinct without dropping choices or depending on input order.
         */
        fun <T> uniqueLabels(
            items: List<T>,
            toIdAndLabel: (T) -> Pair<String, String>,
        ): List<DynamicConfigurationChoice> {
            val mapped = items.map(toIdAndLabel)
            val labelCounts = mapped.groupingBy { it.second }.eachCount()
            return mapped.map { (id, label) ->
                DynamicConfigurationChoice(id, if (labelCounts.getValue(label) > 1) "$label ($id)" else label)
            }
        }
    }
}
