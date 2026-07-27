package io.talkcan.ui

import io.talkcan.dependency.PackageMountDeclaration
import io.talkcan.resource.MountAvailability
import io.talkcan.resource.MountBindingStatus

/**
 * 2.6: Immutable presentation of one package-declared mount for the generic editor.
 * Carries only package-provided metadata and portable status; never a platform
 * grant blob, URI, path, or document id.
 */
data class MountDeclarationPresentation(
    val declarationId: String,
    val label: String,
    val help: String?,
    val required: Boolean,
)

/**
 * 2.6: Editor-facing status for one declared mount instance.
 * Combines package declaration metadata with current availability projection.
 */
data class MountEditorEntry(
    val declaration: MountDeclarationPresentation,
    val availability: MountAvailability,
) {
    val isBlocking: Boolean
        get() = declaration.required && availability is MountAvailability.Unavailable

    val statusPortable: String
        get() = when (val a = availability) {
            is MountAvailability.Available -> MountBindingStatus.AVAILABLE.portable
            is MountAvailability.Unavailable -> when (a.reason) {
                io.talkcan.resource.MountUnavailableReason.Unbound -> "unbound"
                io.talkcan.resource.MountUnavailableReason.NeedsReauthorization ->
                    MountBindingStatus.NEEDS_REAUTHORIZATION.portable
                io.talkcan.resource.MountUnavailableReason.ReadOnly ->
                    MountBindingStatus.READ_ONLY.portable
                else -> MountBindingStatus.UNAVAILABLE.portable
            }
        }
}

/**
 * 2.6: Typed instance unavailability when required mounts are unavailable.
 */
data class ResourceUnavailability(
    val blockingDeclarationIds: List<String>,
)

/**
 * 2.6: Pure projection from declarations and bindings to editor entries.
 */
object MountEditorProjection {
    fun entries(
        declarations: List<PackageMountDeclaration>,
        availabilityFor: (declarationId: String) -> MountAvailability,
    ): List<MountEditorEntry> = declarations.map { declaration ->
        MountEditorEntry(
            declaration = MountDeclarationPresentation(
                declarationId = declaration.id,
                label = declaration.label,
                help = declaration.help,
                required = declaration.required,
            ),
            availability = availabilityFor(declaration.id),
        )
    }

    /**
     * 2.6: Project typed instance unavailability from editor entries.
     * Returns null when all required mounts are available.
     */
    fun requiredResourceUnavailability(entries: List<MountEditorEntry>): ResourceUnavailability? {
        val blocking = entries.filter(MountEditorEntry::isBlocking)
        return if (blocking.isEmpty()) {
            null
        } else {
            ResourceUnavailability(blocking.map { it.declaration.declarationId })
        }
    }
}
