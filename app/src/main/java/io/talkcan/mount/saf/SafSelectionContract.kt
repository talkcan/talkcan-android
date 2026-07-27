package io.talkcan.mount.saf

import android.content.Intent
import io.talkcan.dependency.PackageMountAccess

/**
 * Generic read/write access request derived from a package mount declaration.
 *
 * The SAF adapter requests exactly the declared access and nothing more.
 * Manifest v1 currently accepts only `read-write` directory trees, but the
 * request type stays generic so the picker/permission boundary never widens
 * beyond what the declaration authorizes.
 */
data class SafRequestedAccess(
    val read: Boolean,
    val write: Boolean,
) {
    init {
        require(read || write) { "SAF selection must request at least one access mode" }
    }

    /**
     * Intent grant flags requesting exactly the declared access, persistably.
     *
     * Always includes [Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION] so the
     * returned tree survives process death; includes the read and/or write
     * grant flag precisely when requested. Never includes prefix grants.
     */
    fun intentFlags(): Int {
        var flags = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        if (read) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (write) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return flags
    }

    companion object {
        /** Maps a manifest access mode to the exact SAF access request. */
        fun from(access: PackageMountAccess): SafRequestedAccess =
            when (access) {
                PackageMountAccess.READ_WRITE -> SafRequestedAccess(read = true, write = true)
            }
    }
}

/**
 * Pure description of the picker intent for one declared mount.
 *
 * Contains no Android objects so the exact request contract is testable on a
 * plain JVM; [AndroidSafTreeIntentFactory] materializes a real [Intent].
 */
data class SafTreeIntentSpec(
    val action: String,
    val flags: Int,
    val extras: Map<String, String>,
)

/** Builds the exact [SafTreeIntentSpec] for a generic directory-tree selection. */
object SafTreeSelection {
    /**
     * The picker intent contract: `ACTION_OPEN_DOCUMENT_TREE` with exactly the
     * requested read/write grant flags plus the persistable grant flag.
     */
    fun intentSpec(access: SafRequestedAccess): SafTreeIntentSpec =
        SafTreeIntentSpec(
            action = Intent.ACTION_OPEN_DOCUMENT_TREE,
            flags = access.intentFlags(),
            extras = emptyMap(),
        )
}

/** Materializes a real Android [Intent] from a pure [SafTreeIntentSpec]. */
class AndroidSafTreeIntentFactory {
    fun toIntent(spec: SafTreeIntentSpec): Intent =
        Intent(spec.action).apply {
            addFlags(spec.flags)
            for ((key, value) in spec.extras) {
                putExtra(key, value)
            }
        }
}

/**
 * Outcome of one system picker round trip.
 *
 * A null or blank activity result means the user cancelled; anything else is
 * the returned document-tree URI string, kept verbatim and opaque.
 */
sealed interface SafTreePickerOutcome {
    data class Selected(val treeUri: String) : SafTreePickerOutcome

    data object Cancelled : SafTreePickerOutcome

    companion object {
        fun fromActivityResult(resultUri: String?): SafTreePickerOutcome =
            if (resultUri.isNullOrBlank()) {
                Cancelled
            } else {
                Selected(resultUri.trim())
            }
    }
}
